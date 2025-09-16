package com.splatage.ghostbuster.core;

import java.util.*;

public final class SnapshotDiff {
  private final Map<UUID,Integer> seen = new HashMap<>();
  public List<UUID> filterStable(Collection<UUID> candidates, int minCycles) {
    List<UUID> out = new ArrayList<>();
    Set<UUID> thisCycle = new HashSet<>(candidates);
    // increment counters
    for (UUID u : thisCycle) seen.put(u, seen.getOrDefault(u,0) + 1);
    // decay / remove absent entries
    seen.keySet().removeIf(u -> !thisCycle.contains(u) && seen.put(u, Math.max(0, seen.get(u)-1)) == 0);
    // stable output
    for (var e: seen.entrySet()) if (e.getValue() >= minCycles) out.add(e.getKey());
    return out;
  }
  public int candidateSize() { return seen.size(); }
}
