package com.splatage.ghostbuster;

import com.splatage.ghostbuster.config.PluginConfig;
import com.splatage.ghostbuster.core.GhostBusterService;
import com.splatage.ghostbuster.platform.PlatformInfo;
import com.splatage.ghostbuster.platform.SchedulerFacade;
import com.splatage.ghostbuster.platform.SchedulerFacadeImpl;
import com.splatage.ghostbuster.reflect.Reflectors;
import com.splatage.ghostbuster.util.LogFmt;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

public final class GhostBusterPlugin extends JavaPlugin {
  private PluginConfig cfg;
  private SchedulerFacade scheduler;
  private GhostBusterService service;
  private PlatformInfo platform;

  @Override public void onEnable() {
    saveDefaultConfig();
    getCommand("ghostbuster").setExecutor(this);

    this.cfg = PluginConfig.from(getConfig());
    this.platform = PlatformInfo.detect(Bukkit.getServer());
    this.scheduler = new SchedulerFacadeImpl(this, platform);

    if (platform.parallelTickingDetected() && !cfg.allowUnderParallelTicking()) {
      getLogger().warning("Parallel world ticking detected. Mutations disabled (allow-under-parallel-ticking=false). Running in dry-run/observe mode.");
    }

    this.service = new GhostBusterService(this, cfg, scheduler, platform);
    this.service.start();

    getLogger().info(LogFmt.of("dryRun", cfg.dryRun())
        .kv("intervalSec", cfg.scanIntervalSeconds())
        .kv("folia", platform.isFolia())
        .kv("parallelTick", platform.parallelTickingDetected())
        .kv("reflectorDebug", cfg.logReflectorDebug())
        .toString());
  }

  @Override public void onDisable() {
    if (service != null) service.stop();
  }

  @Override
  public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
    if (!sender.hasPermission("ghostbuster.admin")) {
      sender.sendMessage("No permission.");
      return true;
    }

    if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
      sender.sendMessage(service.statusLine());
      return true;
    }

    if (args[0].equalsIgnoreCase("dryrun")) {
      boolean newVal = !cfg.dryRun();
      getConfig().set("dry-run", newVal);
      saveConfig();
      cfg = PluginConfig.from(getConfig());
      sender.sendMessage("dry-run set to " + newVal);
      return true;
    }

    if (args[0].equalsIgnoreCase("debug")) {
      boolean newVal = !cfg.logReflectorDebug();
      getConfig().set("logging.reflector-debug", newVal);
      saveConfig();
      cfg = PluginConfig.from(getConfig());
      sender.sendMessage("reflector-debug set to " + newVal);
      return true;
    }

    if (args[0].equalsIgnoreCase("dump")) {
      if (!cfg.logReflectorDebug()) {
        sender.sendMessage("reflector-debug is off. Use /ghostbuster debug to enable.");
      } else {
        for (String line : Reflectors.dumpTrackedLines()) {
          sender.sendMessage(line);
        }
      }
      return true;
    }

    if (args[0].equalsIgnoreCase("scan")) {
      service.requestImmediateScan(sender::sendMessage);
      return true;
    }

    if (args[0].equalsIgnoreCase("prune") && args.length == 2) {
      service.requestPrune(args[1], sender::sendMessage);
      return true;
    }

    sender.sendMessage("Usage: /ghostbuster <status|dryrun|debug|dump|scan|prune <uuid>>");
    return true;
  }
}
