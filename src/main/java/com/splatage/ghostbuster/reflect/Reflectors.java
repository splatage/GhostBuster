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
    Class<?> c = target.getClass();
    while (c != null && c != Object.class) {
      for (Field f : c.getDeclaredFields()) {
        if (p.test(f)) { f.setAccessible(true); out.add(f); }
      }
      c = c.getSuperclass();
    }
    return out;
  }
  public static Object getFieldValue(Object target, Field f) {
    try { f.setAccessible(true); return f.get(target); } catch (Throwable t) { return null; }
  }
}
