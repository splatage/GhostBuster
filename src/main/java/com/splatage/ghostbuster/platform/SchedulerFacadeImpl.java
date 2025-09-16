package com.splatage.ghostbuster.platform;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Consumer;

public final class SchedulerFacadeImpl implements SchedulerFacade {
  private final Plugin plugin;
  private final PlatformInfo platform;

  // Folia reflectors (optional)
  private final Object globalRegionScheduler; // Server.getGlobalRegionScheduler()
  private final Object regionScheduler;       // Server.getRegionScheduler()
  private final Method grsExecute;            // execute(Plugin,Runnable)
  private final Method rsExecute;             // execute(Plugin,World,int,int,Runnable)

  public SchedulerFacadeImpl(Plugin plugin, PlatformInfo platform) {
    this.plugin = plugin;
    this.platform = platform;
    Object grs=null, rs=null; Method g=null, r=null;
    if (platform.isFolia()) {
      try {
        var server = Bukkit.getServer();
        var cls = server.getClass();
        var m1 = cls.getMethod("getGlobalRegionScheduler");
        var m2 = cls.getMethod("getRegionScheduler");
        grs = m1.invoke(server);
        rs  = m2.invoke(server);
        g = grs.getClass().getMethod("execute", Plugin.class, Runnable.class);
        r = rs.getClass().getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
      } catch (Throwable ignored) { grs = rs = null; g = r = null; }
    }
    this.globalRegionScheduler = grs;
    this.regionScheduler = rs;
    this.grsExecute = g;
    this.rsExecute = r;
  }

  @Override public void runGlobalSync(Runnable r) {
    if (globalRegionScheduler != null && grsExecute != null) {
      try { grsExecute.invoke(globalRegionScheduler, plugin, r); return; } catch (Throwable ignored) {}
    }
    Bukkit.getScheduler().runTask(plugin, r); // Paper/Spigot
  }

  @Override public void runLaterSync(long ticks, Runnable r) {
    if (globalRegionScheduler != null && grsExecute != null) {
      // Folia doesn't support delayed global directly; chain with Bukkit scheduler delay then GRS
      Bukkit.getScheduler().runTaskLater(plugin, () -> runGlobalSync(r), ticks);
      return;
    }
    Bukkit.getScheduler().runTaskLater(plugin, r, ticks);
  }

  @Override public void runAt(World world, int blockX, int blockZ, Runnable r) {
    if (regionScheduler != null && rsExecute != null) {
      try { rsExecute.invoke(regionScheduler, plugin, world, blockX, blockZ, r); return; } catch (Throwable ignored) {}
    }
    runGlobalSync(r); // fallback on single-threaded servers
  }

  @Override public void withEntityWorld(UUID uuid, Consumer<World> action) {
    Entity e = Bukkit.getEntity(uuid);
    if (e != null) action.accept(e.getWorld());
  }
}
