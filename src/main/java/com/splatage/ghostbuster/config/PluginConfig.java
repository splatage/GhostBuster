package com.splatage.ghostbuster.config;

import org.bukkit.configuration.file.FileConfiguration;

public record PluginConfig(
    boolean dryRun,
    boolean allowUnderParallelTicking,
    int scanIntervalSeconds,
    int verifyDelayTicks,
    int hysteresisCycles,
    int maxUnlinksPerTick,
    int maxUnlinksPerMinute,
    int maxMapScanEntries,
    int logOwnerSample,
    boolean verbose
) {
  public static PluginConfig from(FileConfiguration c) {
    return new PluginConfig(
        c.getBoolean("dry-run", true),
        c.getBoolean("allow-under-parallel-ticking", false),
        c.getInt("scan.interval-seconds", 60),
        c.getInt("scan.verify-delay-ticks", 5),
        c.getInt("scan.hysteresis-cycles", 3),
        c.getInt("limits.max-unlinks-per-tick", 10),
        c.getInt("limits.max-unlinks-per-minute", 200),
        c.getInt("limits.max-map-scan-entries", 10000),
        c.getInt("limits.log-owner-sample", 5),
        c.getBoolean("logging.verbose", false)
    );
  }
}
