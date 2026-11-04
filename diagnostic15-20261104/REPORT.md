# Actual 15 kt diagnostic trial — 4 November 2026

Source `ad13fe2bb926e6c95ed2c30cc31efb01af0537b9`, production `5e065ece9f516c095b84c0ee2ca9bb8ff1a29d3b`, TEST-ONLY jar SHA256 `6fd6b17dcf31e8ce0da5eae1bd8c199898858239ea4bc2545050783f9f6d3024`. This includes frontier6eb and worker153, before subsequent snapshot/result admission guards. It is not a release artifact or full-readiness result.

Fresh immutable checked domain100 Zearth copy, ground zero(3104.5,63,560.5), camera(3136.5,106,-448.5,yaw1.82,pitch2.5), full pack, Complementary Unbound5.9.3 ON,2560x1600,render12/sim8, server8GiB/12processors. No hashes/PNG/video during fixed phases. Native preflight frame outside baseline shows populated city. A prelaunch client was stopped and preserved to correct profiler Java-argfile discovery; no preparation/strike had occurred. The actual client rejoined14:29:40.974UTC, received a full90s warmup before the60s baseline.

Normal command14:32:13.174UTC; launch14:32:31.651; flash14:34:01.678. Command-to-launch18.477s, flight90.027s, command-to-flash108.504s. Driver observed five minutes after flash, then native save completed14:39:05.412. Normal shutdown requested14:42:17.019, stopped14:42:24.158, immutable cutA completed14:42:32.474. No forced kill of the server.

## Progress and limits

Near preparation completed before normal launch. Far work initially advanced strongly, then almost stopped at the memory ceiling: final far plans14315, cursor14737/16870, compared with14309/cursor14730 around14:35. This does not establish eventual whole-zone convergence. Resident sampled lanes0/2 high536838250B (511.969MiB,32662B below512MiB). Future-worker admission-policy high613351116B (584.937MiB) is a distinct nonconcurrent policy quantity, not extra resident heap. These are source ownership estimates, not all JVM live objects. Raw after-GC heap/host reserve and profile coverage require their own interpretation.

First clean-stopped saved ledgers all four pass the streaming audit:169225 moving records,651 cargo records,33106 original-support records. Released1027493, deposited703884, dispersed154384 pieces; exact pending count balances. This first cut proves nonempty saved accounting. The same jar and saved world were restarted at14:51:48.221UTC, ready14:52:15.250; actual game time advanced2040663038→2040664238 (1200 ticks). Native save receipt14:53:19.395, normal stop14:53:29.478, immutable cutB completed14:53:41.453. All four B ledgers pass:435255 moving records,2137 cargo rows; released1361809, deposited728229, dispersed198325, with count/volume/mass balances. Continued physical work changes the saved ledger; this is an actual restart/write check, not comparison of unchanged presave hashes. All55 archive files (209895424 bytes) are byte-identical A→B, none missing/new/changed, and the progress archive reference survives. The progress cursor is still0; this does not prove whole-zone completion or every-block/world inventory correctness. The auditor schema source4d89 is metadata; actual running source is ad13 above.

## Diagnostic timing, not clean performance comparison

| Phase | Frame p99 / max ms | Server work p99 / max ms |
|---|---:|---:|
| Baseline |29.284 /83.791|12.580 /48.234|
| Preparation |31.348 /42.204|44.743 /52.698|
| Flight |32.034 /384.455|44.292 /249.834|
| Flash0–30s |61.773 /145.401|76.640 /105.744|
| Wave30–120s |58.699 /1551.433|70.047 /99.746|
| Late120–300s |50.211 /97.552|65.979 /162.072|

The384ms flight maximum overlaps profiler Thread.print. The1551ms wave maximum and adjacent605/411ms tails overlap late recovery JFR/native startup; they are not evidence of a clean gameplay regression. Do not silently remove them or compare this diagnostic run as a clean ON/OFF pair. Server work is the Pre→Post event interval, not full tick-wall scheduling.

The scheduled early client profile failed before any attach due a private controller event-name collision. EARLY CLIENT STACKS ARE MISSING. A separately labeled recovery captures native CPU aroundT+75–96s, native wall aroundT+239–247s and stockJFR afterT+75s; successful native exits and a positive named JFR completion check are retained. Server stockJFR covers approximately14:33:23–14:39:23, including flash, wave, late and native save; positive JFR.check at14:39:25.482 and20,628,827-byte file confirm completion. PID/start ticks, owned cgroup and exact gameDir were checked; no user instance was attached.

Raw ticks/progress/ledger footers complete, drop0; all measured frame pairs consecutive, GPUringwait0. Stand has no observe-stop on normal wrapper SIGTERM; all bounded phases precede its complete coverage, shutdown tail remains unbounded. TCP counters cover attributed Minecraft sockets, not Incoming-only traffic. Exact before/after causal clock brackets and analysis input remain beside report.

## Current visible defect

Post-window native frames atY106 and thenY250 show an opaque field of vertical streaks. They do not prove LOD freshness or ruins visibility. This is a current reproduced observation, not revival of an old visual backlog. RAIN2 owns the separate diagnosis/minimal fix. Camera movement happened after fixed timing and profiler completion, with an explicit post-window action marker.

World/NBT/JFR files stay private. Selected frames, phase summaries and compressed telemetry may be published only on the evidence orphan branch. Production implementation acceptance, final100/1000kt clean runs, eventual LSS delivery and complete release readiness remain pending.
