package com.splatage.ghostbuster.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LogFmt {
  private final Map<String,Object> kv = new LinkedHashMap<>();
  public static LogFmt kv(String k, Object v) { return new LogFmt().kv(k, v); }
  public LogFmt kv(String k, Object v) { kv.put(k, v); return this; }
  @Override public String toString() {
    StringBuilder sb = new StringBuilder();
    boolean first = true;
    for (var e : kv.entrySet()) {
      if (!first) sb.append(' ');
      first = false;
      sb.append(e.getKey()).append('=').append(String.valueOf(e.getValue()));
    }
    return sb.toString();
  }
}
