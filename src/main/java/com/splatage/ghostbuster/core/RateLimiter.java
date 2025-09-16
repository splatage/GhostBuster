package com.splatage.ghostbuster.core;

public final class RateLimiter {
  private final int maxPerTick;
  private final int maxPerMinute;
  private long minuteWindowStart = 0L;
  private int minuteCount = 0;

  public RateLimiter(int maxPerTick, int maxPerMinute) {
    this.maxPerTick = Math.max(1, maxPerTick);
    this.maxPerMinute = Math.max(1, maxPerMinute);
  }

  public int permit(int requested) {
    long nowMin = System.currentTimeMillis() / 60000L;
    if (nowMin != minuteWindowStart) { minuteWindowStart = nowMin; minuteCount = 0; }
    int remainingMinute = Math.max(0, maxPerMinute - minuteCount);
    int allowed = Math.min(Math.min(requested, maxPerTick), remainingMinute);
    minuteCount += allowed;
    return allowed;
  }
}
