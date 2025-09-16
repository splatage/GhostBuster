package com.splatage.ghostbuster.core;

import com.splatage.ghostbuster.config.PluginConfig;
import com.splatage.ghostbuster.platform.PlatformInfo;
import com.splatage.ghostbuster.platform.SchedulerFacade;
import com.splatage.ghostbuster.reflect.NmsIntrospector;
import com.splatage.ghostbuster.reflect.Reflectors;
import com.splatage.ghostbuster.util.LogFmt;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class GhostBusterService implements Listener {
  private final Plugin plugin;
  private final PluginConfig cfg;
  private final SchedulerFacade sched;
  private final PlatformInfo platform;
  private final NmsIntrospector nms;

  private final ConcurrentMap<UUID, String> live = new ConcurrentHashMap<>();
  private final ScheduledExecutorService analyzePool =
      Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "GhostBuster-Analyzer"));

  private final SnapshotDiff history = new SnapshotDiff();
  private final RateLimiter unlinkRate;

  private long lastGcTimestamp = 0;

  public GhostBusterService(Plugin plugin, PluginConfig cfg, SchedulerFacade sched, PlatformInfo platform) {
    this.plugin = plugin; this.cfg = cfg; this.sched = sched; this.platform = platform;
    this.nms = new NmsIntrospector(plugin.getLogger(), cfg);
    this.unlinkRate = new RateLimiter(cfg.maxUnlinksPerTick(), cfg.maxUnlinksPerMinute());
  }

  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);
    sched.runGlobalSync(() -> {
      // Initial sync to populate live entity snapshot
      for (World w : Bukkit.getWorlds()) {
        for (Entity e : w.getEntities()) {
          live.put(e.getUniqueId(), w.getName());
        }
      }
    });

    long period = Math.max(1, cfg.scanIntervalSeconds());
    analyzePool.scheduleAtFixedRate(this::snapshotThenAnalyze, period, period, TimeUnit.SECONDS);
  }

  public void stop() {
    try { analyzePool.shutdownNow(); } catch (Throwable ignored) {}
    org.bukkit.event.HandlerList.unregisterAll(this);
  }

  @EventHandler public void onAdd(EntityAddToWorldEvent e) {
    live.put(e.getEntity().getUniqueId(), e.getEntity().getWorld().getName());
  }

  @EventHandler public void onRemove(EntityRemoveFromWorldEvent e) {
    live.remove(e.getEntity().getUniqueId());
  }

  public String statusLine() {
    return LogFmt.of("live", live.size())
        .kv("ghosts", history.candidateSize())
        .kv("retained", Reflectors.retainedCount())
        .kv("lastGC", (System.currentTimeMillis() - lastGcTimestamp) / 1000 + "s")
        .kv("dryRun", cfg.dryRun())
        .kv("pwt", platform.parallelTickingDetected())
        .toString();
  }

  public void requestImmediateScan(Consumer<String> reply) {
    analyzePool.execute(() -> {
      Map<String, Integer> result = snapshotThenAnalyze();
      if (result.isEmpty()) {
        String msg = "Scan complete: no ghost candidates found.";
        plugin.getLogger().info(msg);
        reply.accept(msg);
      } else {
        String summary = result.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(" "));
        String msg = "Scan complete: " + summary;
        plugin.getLogger().info(msg);
        reply.accept(msg);
      }
    });
  }

  public void requestPrune(String uuidStr, Consumer<String> reply) {
    try {
      UUID id = UUID.fromString(uuidStr);
      sched.withEntityWorld(id, world -> pruneOne(world, id, reply));
    } catch (IllegalArgumentException ex) {
      reply.accept("Invalid UUID.");
    }
  }

  private Map<String, Integer> snapshotThenAnalyze() {
    Map<String, Integer> resultMap = new HashMap<>();
    Map<String, Set<UUID>> perWorldTracked = new HashMap<>();
    Set<UUID> liveSnap = new HashSet<>();

    sched.runGlobalSync(() -> {
      liveSnap.addAll(live.keySet());
      for (World w : Bukkit.getWorlds()) {
        perWorldTracked.put(w.getName(), nms.snapshotTrackedUUIDs(w, cfg.maxMapScanEntries()));
      }
      lastGcTimestamp = System.currentTimeMillis();
    });

    Map<String, List<UUID>> ghostsByWorld = new HashMap<>();
    for (var e : perWorldTracked.entrySet()) {
      List<UUID> ghosts = e.getValue().stream()
          .filter(u -> !liveSnap.contains(u))
          .collect(Collectors.toList());

      if (!ghosts.isEmpty()) {
        plugin.getLogger().info(
            LogFmt.of("event", "ghosts.detected")
                .kv("world", e.getKey())
                .kv("count", ghosts.size())
                .toString()
        );
      }

      List<UUID> filtered = history.filterStable(ghosts, cfg.hysteresisCycles());
      ghostsByWorld.put(e.getKey(), filtered);
      resultMap.put(e.getKey(), filtered.size());
    }

    if (!ghostsByWorld.isEmpty()) {
      sched.runGlobalSync(() -> {
        for (World w : Bukkit.getWorlds()) {
          List<UUID> candidates = ghostsByWorld.getOrDefault(w.getName(), List.of());
          if (candidates.isEmpty()) continue;
          int allowed = unlinkRate.permit(candidates.size());
          int pruned = 0;
          for (UUID id : candidates) {
            if (pruned >= allowed) break;
            Reflectors.track(id, null); // Track ghost candidates only
            pruneOne(w, id, msg -> plugin.getLogger().warning(msg));
            pruned++;
          }
        }
      });
    }

    return resultMap;
  }

  private void pruneOne(World world, UUID id, Consumer<String> feedback) {
    boolean inWorld = world.getEntities().stream().anyMatch(e -> e.getUniqueId().equals(id));
    boolean inTrackers = nms.isInTrackers(world, id);
    if (inWorld || !inTrackers) return;

    if (cfg.dryRun() || (platform.parallelTickingDetected() && !cfg.allowUnderParallelTicking())) {
      var owners = nms.findOwners(world, id, cfg.logOwnerSample());
      feedback.accept("[DRY] Ghost " + id + " owners=" + owners);
      return;
    }

    boolean ok = nms.unlinkFromOwners(world, id);
    var owners = nms.findOwners(world, id, cfg.logOwnerSample());
    feedback.accept((ok ? "UNLINKED " : "FAILED ") + id + " owners=" + owners);
  }
}
