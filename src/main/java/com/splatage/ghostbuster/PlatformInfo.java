package com.splatage.ghostbuster.platform;

import org.bukkit.Server;

public final class PlatformInfo {
  private final boolean folia;
  private final boolean parallelTicking;

  private PlatformInfo(boolean folia, boolean parallelTicking) {
    this.folia = folia;
    this.parallelTicking = parallelTicking;
  }
  public static PlatformInfo detect(Server server) {
    boolean folia = hasMethod(server.getClass(), "getGlobalRegionScheduler")
                    || classExists("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
    // Leaf parallel world ticking (no stable API): use a heuristic flag via system prop or env,
    // and allow manual override with config. Keep conservative default: assume false.
    boolean pwt = Boolean.getBoolean("leaf.parallelTicking") || envTrue("LEAF_PWT");
    return new PlatformInfo(folia, pwt);
  }
  private static boolean hasMethod(Class<?> c, String name) {
    for (var m: c.getMethods()) if (m.getName().equals(name)) return true;
    return false;
  }
  private static boolean classExists(String name) {
    try { Class.forName(name); return true; } catch (Throwable t) { return false; }
  }
  private static boolean envTrue(String k) {
    String v = System.getenv(k);
    return v != null && (v.equalsIgnoreCase("1") || v.equalsIgnoreCase("true"));
  }
  public boolean isFolia() { return folia; }
  public boolean parallelTickingDetected() { return parallelTicking; }
}
