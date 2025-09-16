package com.splatage.ghostbuster.reflect;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;
import java.lang.ref.WeakReference;
import java.lang.ref.ReferenceQueue;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public final class Reflectors {
  private static final ReferenceQueue<Object> queue = new ReferenceQueue<>();
  private static final Map<UUID, WeakReference<Object>> references = new HashMap<>();

  private static Logger debugLog = null;

  public static void enableDebug(Logger log) {
    debugLog = log;
  }

  public static Object call(Object target, String method, Class<?>[] sig, Object... args) {
    if (target == null) return null;
    try {
      Method m = target.getClass().getMethod(method, sig);
      m.setAccessible(true);
      return m.invoke(target, args);
    } catch (Throwable t) {
      if (debugLog != null) {
        debugLog.warning("Reflectors.call failed: " +
          target.getClass().getName() + "#" + method + " → " + t.getClass().getSimpleName());
      }
      return null;
    }
  }

  public static Object get(Object target, String field) {
    if (target == null) return null;
    try {
      Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      return f.get(target);
    } catch (Throwable t) {
      if (debugLog != null) {
        debugLog.warning("Reflectors.get failed: " +
          target.getClass().getName() + "#" + field + " → " + t.getClass().getSimpleName());
      }
      return null;
    }
  }

  public static List<Field> fields(Object target, Predicate<Field> p) {
    List<Field> out = new ArrayList<>();
    if (target == null) return out;
    Class<?> c = target.getClass();
    while (c != null && c != Object.class) {
      // Don’t reflect into JDK internals
      if (isJdkClass(c)) break;
      for (Field f : c.getDeclaredFields()) {
        if (!p.test(f)) continue;
        try {
          if (f.trySetAccessible()) out.add(f);
        } catch (Throwable ignored) {}
      }
      c = c.getSuperclass();
    }
    return out;
  }

  private static boolean isJdkClass(Class<?> c) {
    String n = c.getName();
    return n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.");
  }

  public static Object getFieldValue(Object target, Field f) {
    if (target == null || f == null) return null;
    try {
      f.setAccessible(true);
      Object out = f.get(target);
      if (debugLog != null && out != null) {
        debugLog.fine("Reflectors.getFieldValue: " + f.getDeclaringClass().getSimpleName()
          + "#" + f.getName() + " → " + out.getClass().getSimpleName());
      }
      return out;
    } catch (Throwable t) {
      if (debugLog != null) {
        debugLog.warning("Reflectors.getFieldValue failed: "
          + f.getDeclaringClass().getSimpleName() + "#" + f.getName() + " → "
          + t.getClass().getSimpleName());
      }
      return null;
    }
  }

  public static int trackedCount() {
    return references.size();
  }

  public static int retainedCount() {
    int n = 0;
    for (WeakReference<Object> ref : references.values()) {
      if (ref.get() != null) n++;
    }
    return n;
  }

  public static void track(UUID id, Object obj) {
    if (id == null || obj == null) return;
    references.put(id, new WeakReference<>(obj, queue));
    if (debugLog != null) {
      debugLog.fine("Reflectors.track: tracked=" + id + " world=" +
        (obj instanceof org.bukkit.entity.Entity e ? e.getWorld().getName() : "n/a"));
    }
  }
public static List<String> dumpTrackedLines() {
  List<String> out = new ArrayList<>();
  if (references.isEmpty()) {
    out.add("No tracked entities.");
    return out;
  }
  out.add("Tracked UUIDs:");
  for (Map.Entry<UUID, WeakReference<Object>> entry : references.entrySet()) {
    Object obj = entry.getValue().get();
    String state = (obj == null) ? "cleared" : obj.getClass().getSimpleName();
    out.add(" - " + entry.getKey() + " => " + state);
  }
  return out;
}

}
