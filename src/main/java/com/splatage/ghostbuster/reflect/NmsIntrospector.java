package com.splatage.ghostbuster.reflect;

import com.splatage.ghostbuster.config.PluginConfig;
import org.bukkit.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;

import static com.splatage.ghostbuster.reflect.Reflectors.*;

public final class NmsIntrospector {
  private final Logger log;
  private final PluginConfig cfg;

  public NmsIntrospector(Logger log, PluginConfig cfg) { this.log = log; this.cfg = cfg; }

  // -------- debug: synthetic ghost injection --------
  /**
   * DEBUG ONLY: inject a synthetic "ghost" UUID reference into one of the
   * server-side tracking maps so a scan can detect and prune it.
   * We avoid JDK internals and only traverse MC/server packages.
   */
  @SuppressWarnings({ "rawtypes", "unchecked" })
  public boolean debugInjectGhost(World world, UUID uuid) {
    try {
      Object sl = call(world, "getHandle", new Class<?>[0]); // ServerLevel (versioned type)
      if (sl == null) return false;

      List<Object> roots = new ArrayList<>();
      roots.add(sl);
      Object chunkSource = call(sl, "getChunkSource", new Class<?>[0]);
      if (chunkSource != null) roots.add(chunkSource);
      Object chunkMap = get(chunkSource, "chunkMap");
      if (chunkMap != null) roots.add(chunkMap);

      Deque<Object> dq = new ArrayDeque<>(roots);
      Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

      while (!dq.isEmpty()) {
        Object cur = dq.poll();
        if (cur == null || seen.contains(cur)) continue;
        seen.add(cur);

        for (Field f : fields(cur, fld -> Map.class.isAssignableFrom(fld.getType()))) {
          Object obj = getFieldValue(cur, f);
          if (obj instanceof Map m) {
            try { m.put(uuid, new Object()); return true; } catch (Throwable ignored) {}
          }
        }
        for (Field f : fields(cur, fld ->
            !fld.getType().isPrimitive()
                && !Map.class.isAssignableFrom(fld.getType())
                && !Collection.class.isAssignableFrom(fld.getType()))) {
          Object nxt = getFieldValue(cur, f);
          if (nxt != null && isAllowedPackage(nxt.getClass())) dq.add(nxt);
        }
      }
    } catch (Throwable t) {
      log.warning("debugInjectGhost failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    return false;
  }

  // -------- snapshots --------

  public Set<UUID> snapshotTrackedUUIDs(World world, int maxEntries) {
    Set<UUID> out = new HashSet<>();
    Object sl = call(world, "getHandle", new Class<?>[0]); // ServerLevel
    if (sl == null) return out;

    List<Object> roots = new ArrayList<>();
    roots.add(sl);
    Object chunkSource = call(sl, "getChunkSource", new Class<?>[0]);
    if (chunkSource != null) roots.add(chunkSource);
    Object chunkMap = get(chunkSource, "chunkMap");
    if (chunkMap != null) roots.add(chunkMap);

    Deque<Object> dq = new ArrayDeque<>(roots);
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    int depth = 0;
    while (!dq.isEmpty() && depth < 3) {
      int sz = dq.size();
      for (int i = 0; i < sz; i++) {
        Object cur = dq.poll();
        if (cur == null || seen.contains(cur)) continue;
        seen.add(cur);

        for (Field f : fields(cur, fld -> Map.class.isAssignableFrom(fld.getType()))) {
          Object mapObj = getFieldValue(cur, f);
          if (mapObj instanceof Map<?, ?> m) {
            int count = 0;
            for (var e : m.entrySet()) {
              if (count++ > maxEntries) break;
              Object k = e.getKey(), v = e.getValue();
              UUID u = asUUID(k);
              if (u != null) { out.add(u); continue; }
              u = extractEntityUUID(v);
              if (u != null) out.add(u);
            }
          }
        }
        for (Field f : fields(cur, fld ->
            !fld.getType().isPrimitive()
                && !Map.class.isAssignableFrom(fld.getType())
                && !Collection.class.isAssignableFrom(fld.getType())
        )) {
          Object nxt = getFieldValue(cur, f);
          if (nxt != null && isAllowedPackage(nxt.getClass())) dq.add(nxt);
        }
      }
      depth++;
    }
    return out;
  }

  // -------- verification & owners --------

  public boolean isInTrackers(World world, UUID uuid) {
    return !findOwners(world, uuid, 1).isEmpty();
  }

  public List<String> findOwners(World world, UUID uuid, int limit) {
    List<String> owners = new ArrayList<>();
    Object sl = call(world, "getHandle", new Class<?>[0]);
    if (sl == null) return owners;

    List<Object> roots = new ArrayList<>();
    roots.add(sl);
    Object chunkSource = call(sl, "getChunkSource", new Class<?>[0]);
    if (chunkSource != null) roots.add(chunkSource);
    Object chunkMap = get(chunkSource, "chunkMap");
    if (chunkMap != null) roots.add(chunkMap);

    Deque<Object> dq = new ArrayDeque<>(roots);
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    while (!dq.isEmpty() && owners.size() < limit) {
      Object cur = dq.poll();
      if (cur == null || seen.contains(cur)) continue;
      seen.add(cur);

      for (Field f : fields(cur, fld -> Map.class.isAssignableFrom(fld.getType()))) {
        Object mapObj = getFieldValue(cur, f);
        if (!(mapObj instanceof Map<?, ?> m)) continue;

        int count = 0;
        for (var e : m.entrySet()) {
          if (count++ > cfg.maxMapScanEntries()) break;
          Object k = e.getKey(), v = e.getValue();
          if (uuid.equals(asUUID(k)) || uuid.equals(extractEntityUUID(v))) {
            owners.add(cur.getClass().getName() + "#" + f.getName());
            if (owners.size() >= limit) break;
          }
        }
      }
      for (Field f : fields(cur, fld ->
          !fld.getType().isPrimitive()
              && !Map.class.isAssignableFrom(fld.getType())
              && !Collection.class.isAssignableFrom(fld.getType()))) {
        Object nxt = getFieldValue(cur, f);
        if (nxt != null && isAllowedPackage(nxt.getClass())) dq.add(nxt);
      }
    }
    return owners;
  }

  // -------- unlink (best-effort, version-agnostic) --------

  public boolean unlinkFromOwners(World world, UUID uuid) {
    Object sl = call(world, "getHandle", new Class<?>[0]);
    if (sl == null) return false;
    boolean changed = false;

    List<Object> roots = new ArrayList<>();
    roots.add(sl);
    Object chunkSource = call(sl, "getChunkSource", new Class<?>[0]);
    if (chunkSource != null) roots.add(chunkSource);
    Object chunkMap = get(chunkSource, "chunkMap");
    if (chunkMap != null) roots.add(chunkMap);

    Deque<Object> dq = new ArrayDeque<>(roots);
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

    while (!dq.isEmpty()) {
      Object cur = dq.poll();
      if (cur == null || seen.contains(cur)) continue;
      seen.add(cur);

      for (Field f : fields(cur, fld -> Map.class.isAssignableFrom(fld.getType()))) {
        Object obj = getFieldValue(cur, f);
        if (!(obj instanceof Map<?, ?> m)) continue;

        List<Object> removeKeys = new ArrayList<>();
        int scanned = 0;
        for (var e : m.entrySet()) {
          if (scanned++ > cfg.maxMapScanEntries()) break;
          Object k = e.getKey(), v = e.getValue();
          if (uuid.equals(asUUID(k)) || uuid.equals(extractEntityUUID(v))) {
            removeKeys.add(k);
            clearWatcherSets(v);
          }
        }
        for (Object rk : removeKeys) {
          try { m.remove(rk); changed = true; } catch (Throwable ignored) {}
        }
      }
      for (Field f : fields(cur, fld ->
          !fld.getType().isPrimitive()
              && !Map.class.isAssignableFrom(fld.getType())
              && !Collection.class.isAssignableFrom(fld.getType()))) {
        Object nxt = getFieldValue(cur, f);
        if (nxt != null && isAllowedPackage(nxt.getClass())) dq.add(nxt);
      }
    }
    return changed;
  }

  // -------- helpers --------

  private static UUID asUUID(Object o) {
    if (o instanceof UUID u) return u;
    if (o instanceof String s && looksLikeUuid(s)) {
      try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {}
    }
    return null;
  }

  private static boolean looksLikeUuid(String s) {
    // quick shape check: 36 chars, dashes at 8-13-18-23, hex elsewhere
    if (s.length() != 36) return false;
    if (s.charAt(8)!='-' || s.charAt(13)!='-' || s.charAt(18)!='-' || s.charAt(23)!='-') return false;
    for (int i=0;i<36;i++) {
      if (i==8||i==13||i==18||i==23) continue;
      char c = s.charAt(i);
      boolean hex = (c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F');
      if (!hex) return false;
    }
    return true;
  }

  // Attempt to get a UUID from unknown NMS entity object
  private static UUID extractEntityUUID(Object entity) {
    if (entity == null) return null;

    // 1) If it's already a Bukkit entity wrapper
    try {
      if (entity instanceof org.bukkit.entity.Entity be) {
        return be.getUniqueId();
      }
    } catch (Throwable ignored) {}

    // 2) Prefer a direct zero-arg method that returns UUID (e.g., getUUID())
    try {
      Method m = entity.getClass().getMethod("getUUID");
      if (UUID.class.isAssignableFrom(m.getReturnType())) {
        Object v = m.invoke(entity);
        if (v instanceof UUID u) return u;
      }
    } catch (Throwable ignored) {}

    // 3) Fallback: scan declared fields of type UUID
    for (Field f : entity.getClass().getDeclaredFields()) {
      if (f.getType() == UUID.class) {
        try { if (f.trySetAccessible()) return (UUID) f.get(entity); } catch (Throwable ignored) {}
      }
    }

    // 4) LAST resort (avoid if possible due to remapper overhead): bridge via Bukkit
    try {
      Object bukkit = call(entity, "getBukkitEntity", new Class<?>[0]);
      if (bukkit instanceof org.bukkit.entity.Entity be) {
        return be.getUniqueId();
      }
    } catch (Throwable ignored) {}

    return null;
  }

  // Best-effort: clear watcher-like sets inside tracked entry objects
  private static void clearWatcherSets(Object tracked) {
    if (tracked == null) return;
    for (Field f : tracked.getClass().getDeclaredFields()) {
      try {
        if (!f.trySetAccessible()) continue;
        Object val = f.get(tracked);
        if (val instanceof Set<?> s) { s.clear(); }
        if (val instanceof Map<?, ?> m) { m.clear(); }
      } catch (Throwable ignored) {}
    }
  }

  // Only traverse inside these package roots (avoid JDK internals)
  private static boolean isAllowedPackage(Class<?> cls) {
    String n = cls.getName();
    return n.startsWith("net.minecraft.")
        || n.startsWith("io.papermc.")
        || n.startsWith("ca.spottedleaf.")
        || n.startsWith("it.unimi.")          // fastutil
        || n.startsWith("com.destroystokyo.")
        || n.startsWith("org.bukkit.");
  }
}
