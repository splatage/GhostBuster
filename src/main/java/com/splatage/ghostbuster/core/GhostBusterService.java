package com.splatage.ghostbuster.core;

import com.splatage.ghostbuster.config.PluginConfig;
import com.splatage.ghostbuster.platform.PlatformInfo;
import com.splatage.ghostbuster.platform.SchedulerFacade;
import com.splatage.ghostbuster.reflect.NmsIntrospector;
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
    // Seed live set initially (sync)
    sched.runGlobalSync(() -> {
      Set<UUID> liveSnap = new HashSet<>();
      Map<String, Set<UUID>> perWorldTracked = new HashMap<>();
      liveSnap.addAll(live.keySet());
      for (World w: Bukkit.getWorlds()) {
        for (Entity e : w.getEntities()) {
          Reflectors.track(e.getUniqueId(), e);
        }
        perWorldTracked.put(w.getName(), nms.snapshotTrackedUUIDs(w, cfg.maxMapScanEntries()));
      }
    });

    // periodic snapshot
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
    analyzePool.execute(() -> { snapshotThenAnalyze(); reply.accept("Scan triggered."); });
  }
  public void requestPrune(String uuidStr, Consumer<String> reply) {
    try {
      UUID id = UUID.fromString(uuidStr);
      sched.withEntityWorld(id, world -> pruneOne(world, id, reply));
    } catch (IllegalArgumentException ex) { reply.accept("Invalid UUID."); }
  }

  // ---- Pipeline ----

  private void snapshotThenAnalyze() {
    // Step 1 (SYNC): snapshot trackers & live UUIDs
    Map<String, Set<UUID>> perWorldTracked = new HashMap<>();
    Set<UUID> liveSnap = new HashSet<>();
    sched.runGlobalSync(() -> {
      // live snapshot from cache (cheap) + sanity add of currently present
      liveSnap.addAll(live.keySet());
      for (World w: Bukkit.getWorlds()) {
        perWorldTracked.put(w.getName(), nms.snapshotTrackedUUIDs(w, cfg.maxMapScanEntries()));
      }
      lastGcTimestamp = System.currentTimeMillis();
    });

    // Step 2 (ASYNC): diff
    Map<String, List<UUID>> ghostsByWorld = new HashMap<>();
    for (var e : perWorldTracked.entrySet()) {
      List<UUID> ghosts = e.getValue().stream()
          .filter(u -> !liveSnap.contains(u))
          .collect(Collectors.toList());

      if (!ghosts.isEmpty()) {
        LogFmt.info("GhostBuster: world=%s, detected=%d ghost(s)", e.getKey(), ghosts.size());
      }
      
      ghostsByWorld.put(e.getKey(), history.filterStable(ghosts, cfg.hysteresisCycles()));
    }


    // Step 3 (SYNC): verify & prune
    if (!ghostsByWorld.isEmpty()) {
      sched.runGlobalSync(() -> {
        for (World w: Bukkit.getWorlds()) {
          List<UUID> candidates = ghostsByWorld.getOrDefault(w.getName(), List.of());
          if (candidates.isEmpty()) continue;
          int allowed = unlinkRate.permit(candidates.size());
          int pruned = 0;
          for (UUID id : candidates) {
            if (pruned >= allowed) break;
            pruneOne(w, id, msg -> plugin.getLogger().warning(msg));
            pruned++;
          }
        }
      });
    }
  }

  private void pruneOne(World world, UUID id, Consumer<String> feedback) {
    boolean inWorld = world.getEntities().stream().anyMatch(e -> e.getUniqueId().equals(id));
    boolean inTrackers = nms.isInTrackers(world, id);
    if (inWorld || !inTrackers) return; // not a ghost or already clean

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
