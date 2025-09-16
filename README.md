# GhostBuster

Prunes **ghost entity links** so Java’s GC can actually reclaim them.  
Designed for **Paper / Purpur / Leaf** (1.20–1.21+), with **Folia-aware** scheduling.

GhostBuster is a lightweight, version-agnostic diagnostic/cleanup plugin: it detects entities that have left the world but are still referenced by trackers/visibility maps/queues, and (optionally) unlinks those references so they become collectible during normal ZGC cycles.

> ⚠️ **Safety-first:** GhostBuster defaults to **dry-run**. Start there, observe owners in logs, then flip to active pruning once you’re confident.

---

## Features

- **Detect “ghosts”**: entities no longer present in world lists but still referenced.
- **Version-agnostic**: no NMS imports; uses bounded reflection by type/behavior.
- **Folia-aware**: runs sync tasks via Global/Region scheduler where needed.
- **Hysteresis & verification**: multiple sightings + recheck before any mutation.
- **Strict rate limiting**: per-tick and per-minute unlink caps.
- **Parallel-ticking guard**: refuses to mutate when parallel world ticking is detected (configurable).
- **Zero forced GC**: just removes the last strong refs; GC does the rest.

---

## How it works (high level)

1. **Snapshot (sync):** capture UUIDs seen by the server’s trackers and current world entities.  
2. **Analyze (async):** diff: `ghosts = tracked − live`. Apply hysteresis so in-flight teleports don’t trigger.  
3. **Verify & prune (sync):** recheck candidates and surgically remove references from owners (maps/sets/trackers).  
4. **GC:** with no strong refs left, ZGC will reclaim the objects on subsequent cycles.

---

## Requirements

- **Java:** JDK **21** (Temurin recommended)
- **Server:** Paper/Purpur/Leaf **1.20–1.21+**  
- **Folia:** Supported (uses schedulers via reflection).  

> If you run **parallel world ticking** (Leaf/Sapling style), GhostBuster will run in observe-only mode unless you explicitly allow it.

---

## Installation

1. Download/build the JAR (see **Build** below).  
2. Drop it into `plugins/`.  
3. Start the server once to generate `config/ghostbuster/config.yml`.  
4. Keep `dry-run: true` initially; watch logs for owners.  
5. Flip to `dry-run: false` when ready.

---

## Configuration (`config.yml`)

```yaml
# GhostBuster Configuration

# Run-time safety switches
dry-run: true                       # true = log only, no pruning
allow-under-parallel-ticking: false # refuse to mutate if parallel ticking is detected

# Scanning cadence (sync snapshots on correct thread)
scan:
  interval-seconds: 60              # how often to snapshot & diff
  verify-delay-ticks: 5             # re-check on main thread before unlinking
  hysteresis-cycles: 3              # candidate must appear in N consecutive scans

# Limits (prevent heavy scans / mass unlinks)
limits:
  max-unlinks-per-tick: 10
  max-unlinks-per-minute: 200
  max-map-scan-entries: 10000       # per container; caps reflection scans
  log-owner-sample: 5               # how many owners to print per ghost

# Logging
logging:
  verbose: false
```

**Recommendations**
- Start with `interval-seconds: 60`.  
- Keep `max-map-scan-entries` conservative on large servers (10k–25k).  
- Only set `allow-under-parallel-ticking: true` if you fully understand the risks and have tested on your fork.

---

## Commands

- `/ghostbuster status` – show counters and current mode.  
- `/ghostbuster dryrun` – toggle dry-run on/off at runtime (also updates config).  
- `/ghostbuster scan` – trigger an immediate scan/diff.  
- `/ghostbuster prune <uuid>` – verify & prune a specific UUID now.

All commands require `ghostbuster.admin` (default: op).

---

## Build

This repo is Gradle-based and ships with a wrapper.

```bash
# Clone the repo
git clone https://github.com/<your-org>/ghostbuster.git
cd ghostbuster

# Build with Java 21
./gradlew build

# Output:
# build/libs/ghostbuster-<version>.jar
```

**Paper plugin main class**

If you use your own package (recommended), set in `paper-plugin.yml`:
```yaml
main: com.splatage.ghostbuster.GhostBusterPlugin
```


---

## Repository layout

```
ghostbuster/
├─ README.md
├─ build.gradle.kts
├─ settings.gradle.kts
├─ src/main/resources/
│  ├─ paper-plugin.yml
│  └─ config.yml
└─ src/main/java/com/splatage/ghostbuster/    # or com/example/ghostbuster
   ├─ GhostBusterPlugin.java                  # entrypoint
   ├─ config/PluginConfig.java                # config binding
   ├─ core/GhostBusterService.java            # snapshots/diff/verify/unlink
   ├─ core/SnapshotDiff.java                  # hysteresis
   ├─ core/RateLimiter.java                   # unlink rate limiting
   ├─ platform/PlatformInfo.java              # detect Folia/parallel-ticking
   ├─ platform/SchedulerFacade*.java          # thread-safe scheduling
   ├─ reflect/NmsIntrospector.java            # owner discovery + unlink (reflection)
   └─ util/LogFmt.java                        # structured log helper
```

---

## Best practices & safety rails

- **Always start in dry-run.** Confirm repeated ghost sightings & owners in logs before enabling pruning.  
- **Keep everything that touches server state on the correct thread.** GhostBuster does this via a scheduler facade.  
- **Use rate limits.** Prevent mass removals during a misconfiguration.  
- **Version-agnostic reflection.** We scan by type/behavior (e.g., Map<…> containing TrackedEntity/UUID), not field names.  
- **Parallel world ticking.** Default is **no mutations**. Only enable if you've validated on your fork.  
- **No forced GC.** Just unlink. ZGC (or your GC) will reclaim in subsequent cycles.

---

## Verifying results

- **GC trend:** old-gen “after GC” baseline should plateau (no longer creeping upward).  
- **Histograms:** `jcmd <PID> GC.class_histogram` over time should flatten for entity/AI/fastutil classes.  
- **Spark:** `/spark heap summary` deltas diminish; live graphs stabilize.

---

## Troubleshooting

- **“No owners found”** – Increase `limits.max-map-scan-entries`; extend `scan.interval-seconds` to reduce contention; ensure the world in question is loaded.  
- **Folia warnings** – If you see “mutations disabled” but you want pruning, set `allow-under-parallel-ticking: true` (at your own risk).  
- **Performance** – Keep scans at ≥ 30–60s. The reflection is bounded; the plugin only copies UUIDs.

---

## Roadmap

- Optional Prometheus/metrics endpoints.  
- Owner fingerprints (class + field hashing) to aid upstream bug reports.  
- Per-world inclusion/exclusion lists.  
- “Auto-quarantine” mode: log and alert without pruning when owners are unknown.

---

## Contributing

PRs welcome! Please include:
- Server fork/version (Paper/Leaf/Folia), MC version, Java version.  
- Config overrides (`config.yml`).  
- Logs showing ghost owners (dry-run).  
- If possible, **JFR Old Object Sample** or **MAT** dominators confirming the owner.

---

## License

MIT (or your preferred OSS license). Add a `LICENSE` file before publishing.

---

## Disclaimer

GhostBuster is a surgical tool meant to **mitigate** retention while you identify and fix the **root cause** (often a fork/patch/plugin bug around teleports/tracking). Keep dry-run enabled during investigation, and submit upstream reports with owner fingerprints once confirmed.
