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
import java.util.concurrent.atomic.AtomicInteger;
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

  // Avoid duplicate verify schedules for the same UUID in rapid-churn scenarios
  private final Set<UUID> pendingVerify = ConcurrentHashMap.newKeySet();

  private long lastGcTimestamp = 0;

  public GhostBusterService(Plugin plugin, PluginConfig cfg, SchedulerFacade sched, PlatformInfo platform) {
    this.plugin = plugin; this.cfg = cfg; this.sched = sched; this.platform = platform;
    this.nms = new NmsIntrospector(plugin.getLogger(), cfg);
    this.unlinkRate = new RateLimiter(cfg.maxUnlinksPerTick(), cfg.maxUnlinksPerMinute());
  }

  public void start() {
    plugin.getServer().getPluginManager().registerEvents(this, plugin);

    // Initial sync snapshot of live entities (legal on global because it only reads Bukkit API)
    sched.runGlobalSync(() -> {
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
    var ent = e.getEntity();
    live.put(ent.getUniqueId(), ent.getWorld().getName());
  }

  @EventHandler public void onRemove(EntityRemoveFromWorldEvent e) {
    UUID id = e.getEntity().getUniqueId();
    live.remove(id);

    // Event-driven verify to catch ghosts created between interval scans
    int delay = Math.max(0, cfg.verifyDelayTicks()); // 0 means verify next tick
    scheduleVerify(e.getEntity(), delay);
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

  // DEBUG: delegate to NmsIntrospector for synthetic ghost injection
  public boolean debugInject(World world, UUID uuid) {
    return nms.debugInjectGhost(world, uuid);
  }

  private Map<String, Integer> snapshotThenAnalyze() {
    Map<String, Integer> resultMap = new HashMap<>();
    Map<String, Set<UUID>> perWorldTracked = new ConcurrentHashMap<>();
    Set<UUID> liveSnap = new HashSet<>(live.keySet());

    // Take per-world tracker snapshots on the world’s region thread
    List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
    CountDownLatch latch = new CountDownLatch(worlds.size());
    for (World w : worlds) {
      sched.runAt(w, 0, 0, () -> {
        try {
          perWorldTracked.put(w.getName(), nms.snapshotTrackedUUIDs(w, cfg.maxMapScanEntries()));
        } catch (Throwable t) {
          plugin.getLogger().warning("[GhostBuster] snapshot failed in world " + w.getName() + ": " + t.getClass().getSimpleName());
        } finally {
          latch.countDown();
        }
      });
    }
    try {
      // Allow generous time; if some world is stuck we still proceed with what we have
      latch.await(15, TimeUnit.SECONDS);
    } catch (InterruptedException ignored) {}

    lastGcTimestamp = System.currentTimeMillis();

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

    // Prune per-world, on region thread, honoring rate limits
    if (!ghostsByWorld.isEmpty()) {
      for (World w : worlds) {
        List<UUID> candidates = ghostsByWorld.getOrDefault(w.getName(), List.of());
        if (candidates.isEmpty()) continue;

        int allowed = unlinkRate.permit(candidates.size());
        if (allowed <= 0) continue;

        AtomicInteger pruned = new AtomicInteger(0);
        for (UUID id : candidates) {
          if (pruned.get() >= allowed) break;
          sched.runAt(w, 0, 0, () -> {
            if (pruned.get() >= allowed) return;
            try {
              Reflectors.track(id, null); // Track ghost candidates only
              pruneOne(w, id, msg -> plugin.getLogger().warning(msg));
            } finally {
              pruned.incrementAndGet();
            }
          });
        }
      }
    }

    return resultMap;
  }

  private void pruneOne(World world, UUID id, Consumer<String> feedback) {
    boolean inWorld;
    try {
      // Cheap, region-safe when already on the region thread
      inWorld = world.getEntity(id) != null;
    } catch (Throwable t) {
      // If world API is guarded, fall back to assuming “not present”
      inWorld = false;
    }

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

  // Schedules a short delayed verify after entity removal to catch ghosts that would
  // otherwise be invisible between periodic scans. Runs on the entity's region thread.
  private void scheduleVerify(Entity entity, int delayTicks) {
    if (platform.parallelTickingDetected() && !cfg.allowUnderParallelTicking()) {
      // Config says: do not mutate/verify under parallel ticking
      return;
    }

    final UUID id = entity.getUniqueId();
    final World w = entity.getWorld();
    final int bx = entity.getLocation().getBlockX();
    final int bz = entity.getLocation().getBlockZ();

    if (!pendingVerify.add(id)) return;

    Runnable verifyTask = () -> sched.runAt(w, bx, bz, () -> {
      try {
        // If the entity came back, abort
        if (w.getEntity(id) != null) return;

        // If trackers still reference it, prune (respects dry-run & PWT)
        if (nms.isInTrackers(w, id)) {
          pruneOne(w, id, msg -> plugin.getLogger().warning("verify: " + msg));
        }
      } catch (Throwable t) {
        plugin.getLogger().warning("verify-on-remove failed for " + id + ": "
            + t.getClass().getSimpleName() + ": " + t.getMessage());
      } finally {
        pendingVerify.remove(id);
      }
    });

    if (delayTicks <= 0) {
      // next tick
      sched.runLaterSync(1, verifyTask);
    } else {
      sched.runLaterSync(delayTicks, verifyTask);
    }
  }
}
