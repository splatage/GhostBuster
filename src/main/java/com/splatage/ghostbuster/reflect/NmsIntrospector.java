package com.splatage.ghostbuster.reflect;

import com.splatage.ghostbuster.config.PluginConfig;
import org.bukkit.World;

import java.lang.reflect.Field;
import java.util.*;
import java.util.logging.Logger;

import static com.splatage.ghostbuster.reflect.Reflectors.*;

public final class NmsIntrospector {
  private final Logger log;
  private final PluginConfig cfg;

  public NmsIntrospector(Logger log, PluginConfig cfg) { this.log = log; this.cfg = cfg; }

  // -------- snapshots --------

  public Set<UUID> snapshotTrackedUUIDs(World world, int maxEntries) {
    Set<UUID> out = new HashSet<>();
    Object sl = Reflectors.call(world, "getHandle", new Class<?>[0]); // ServerLevel (versioned type)
    if (sl == null) return out;

    // Candidate roots to scan for maps/sets that reference entities/UUIDs
    List<Object> roots = new ArrayList<>();
    roots.add(sl);
    Object chunkSource = call(sl, "getChunkSource", new Class<?>[0]);
    if (chunkSource != null) roots.add(chunkSource);
    Object chunkMap = get(chunkSource, "chunkMap");
    if (chunkMap != null) roots.add(chunkMap);

    // BFS across fields to limited depth
    Deque<Object> dq = new ArrayDeque<>(roots);
    Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    int depth = 0;
    while (!dq.isEmpty() && depth < 3) {
      int sz = dq.size();
      for (int i = 0; i < sz; i++) {
        Object cur = dq.poll();
        if (cur == null || seen.contains(cur)) continue;
        seen.add(cur);

        // Scan Map containers for UUIDs or entity-like objects
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
        // Enqueue interesting composite objects (MC/server packages only)
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
    Object sl = Reflectors.call(world, "getHandle", new Class<?>[0]);
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
      // Enqueue children (MC/server packages only)
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
    Object sl = Reflectors.call(world, "getHandle", new Class<?>[0]);
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

        // Collect keys to remove (avoid CME)
        List<Object> removeKeys = new ArrayList<>();
        int scanned = 0;
        for (var e : m.entrySet()) {
          if (scanned++ > cfg.maxMapScanEntries()) break;
          Object k = e.getKey(), v = e.getValue();
          if (uuid.equals(asUUID(k)) || uuid.equals(extractEntityUUID(v))) {
            removeKeys.add(k);
            // if value looks like a "TrackedEntity", try to clear watcher sets
            clearWatcherSets(v);
          }
        }
        for (Object rk : removeKeys) {
          try { m.remove(rk); changed = true; } catch (Throwable ignored) {}
        }
      }
      // Enqueue children (MC/server packages only)
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
    if (o instanceof String s) { try { return UUID.fromString(s); } catch (IllegalArgumentException ignored) {} }
    return null;
  }

  // Attempt to get a Bukkit UUID from an unknown NMS entity object
  private static UUID extractEntityUUID(Object entity) {
    if (entity == null) return null;
    try {
      // Guess: has method "getBukkitEntity" -> org.bukkit.entity.Entity -> getUniqueId()
      Object bukkit = Reflectors.call(entity, "getBukkitEntity", new Class<?>[0]);
      if (bukkit != null) {
        var m = bukkit.getClass().getMethod("getUniqueId");
        return (UUID) m.invoke(bukkit);
      }
    } catch (Throwable ignored) {}
    // Also inspect declared fields for a UUID
    for (Field f : entity.getClass().getDeclaredFields()) {
      if (f.getType() == UUID.class) {
        try {
          if (f.trySetAccessible()) return (UUID) f.get(entity);
        } catch (Throwable ignored) {}
      }
    }
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
        // Some forks use fastutil maps/sets: clear those too
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
