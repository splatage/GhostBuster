package com.splatage.ghostbuster.reflect;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Predicate;

public final class Reflectors {
  public static Object call(Object target, String method, Class<?>[] sig, Object... args) {
    try {
      Method m = target.getClass().getMethod(method, sig);
      m.setAccessible(true);
      return m.invoke(target, args);
    } catch (Throwable t) { return null; }
  }
  public static Object get(Object target, String field) {
    try {
      Field f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      return f.get(target);
    } catch (Throwable t) { return null; }
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
        // Java 21: prefer trySetAccessible() to avoid throwing
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
  try { f.setAccessible(true); return f.get(target); } catch (Throwable t) { return null; }
}
public static int trackedCount() {
  return references.size();
}
}
