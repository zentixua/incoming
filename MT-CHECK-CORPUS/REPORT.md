# Same-grid persistent Blast reuse versus fresh fractures

**760 cases / 3040 alternating pairs completed; all authoritative Collapse outputs and full durable/material plan payloads agree.** Of these,1520 pairs are post-warmup timing samples. Uncached/cached solve+finish ratio: median **3.498×**, p90 **4.685×**, ratio of summed times **3.544×**. Ratio of summed allocated bytes **3.146×**.

This measures the repeated-Grid endpoint with all9 fractures already reusable versus all9 fresh each solve. It is not an area-weighted production throughput estimate, a cache-policy recommendation, a15-minute projection or a512MiB/whole-heap validation.

## Results (two timed pairs per case; decimal MB)

| Measurement | Cached reuse | Uncached fresh |
|---|---:|---:|
| Solve+finish median | 33.544ms | 126.352ms |
| Solve+finish p90 | 117.168ms | 407.258ms |
| Solve+finish sum | 80.385s | 284.924s |
| Allocated bytes median | 9.590MB | 34.381MB |
| Allocated bytes p90 | 32.581MB | 94.802MB |
| Allocated bytes sum | 22.074036GB | 69.455864GB |
| Solve median | 30.772ms | 123.313ms |
| Finish median | 2.921ms | 2.922ms |
| Finish p90 | 9.032ms | 8.989ms |

Median paired allocation ratio3.515×, p904.561×. Median of the760 per-case timing-ratio medians3.497×, p904.661×. The paired ratio quantiles are NOT ratios of the absolute-time quantiles.

The cached context's **one-time cold9 Blast setup** is recorded separately, once per case: median91.671ms, p90296.125ms, sum102.805s; allocation median24.347MB/p9061.749MB. It is excluded from the marginal reuse timing ratio. The uncached solve timer directly includes all9 fresh fractures plus Collapse; it is not an estimate obtained by subtracting unrelated timers. Finish has its own timer and allocation counter.

## Pressure breakdown

| Pressure | Timed pairs | Median paired ratio | p90 paired ratio | Summed-time ratio |
|---|---:|---:|---:|---:|
| 1.0psi | 380 | 3.280× | 4.338× | 3.273× |
| 6.0psi | 380 | 3.355× | 4.274× | 3.368× |
| 20.0psi | 380 | 3.710× | 4.893× | 3.808× |
| 50.0psi | 380 | 3.724× | 4.859× | 3.828× |

## Group and noise checks

| Corpus group | Timed pairs | Median paired ratio | p90 paired ratio | Summed-time ratio |
|---|---:|---:|---:|---:|
| dense1024 | 496 | 3.506× | 4.409× | 3.574× |
| dense512 | 1024 | 3.495× | 4.772× | 3.540× |

These are the manifest first-occurrence labels for190 deduplicated roots:128 dense512 and62 dense1024. They are sample groups, not whole-zone weights.

- Cached first=True: 760timed pairs, median ratio3.504×, summed ratio3.517×.
- Cached first=False: 760timed pairs, median ratio3.495×, summed ratio3.573×.
- JVM collection-time counter increased=True: 852pairs, median ratio3.605×, summed ratio3.572×.
- JVM collection-time counter increased=False: 668pairs, median ratio3.399×, summed ratio3.461×.

No CPU-quiet claim: LEAD2's full client was active at task start, and another team's validation job overlapped part of the run. Later snapshots recorded only this corpus service; host load/memory/service snapshots are in noise.jsonl. Zero reported GC milliseconds is not proof of no GC or scheduling pause. These are descriptive measurements from one JVM, with two timed pairs per case and alternating call order; no independent repetitions or confidence intervals are claimed.

The single full GameTest process passed in15 min 54 s wall time (including load, warmups, all assertions/encoding, parsing and GC). Server stall warnings reflect the intentionally synchronous multi-solve test callback. They are not in-game MSPT results. ThreadMXBean allocation is allocation on the measured server thread, not retained cache bytes, process RSS, native memory or peak heap.

## Exact paired method

Base6eb589d59df2a1ebdd41a3e2f8577dcd04448c32. Private CacheCorpusBench.java is supplied unchanged from the compiled run; no production or tracked source edits. No ReferenceCollapse changes/calls, extra agents/review, fullbuild, CI, GPU or real server operation.

-190 unique 5×5 source windows × pressures1/6/20/50psi =760 cases, matching the existing reference corpus selection/deduplication and1000 kt geometry.
- Strictly parse/prime25 reads once per case, build one shared25 Shot array, then two independent contexts/Grids with Blast.SERVER, same detonation/noise seed/settings. Inputs are never applied to a world.
- Cached Grid prewarms9 Blast exactly once. Every subsequent pair asserts the same 9 object identities remain.
- Before EVERY uncached solve, clear ONLY its private ctx.blasts map. Collapse.solve itself builds the9 fresh fractures. Afterward assert 9 objects and no reuse from its previous solve. All other context state/input/laws remain as in cached mode. The reflection clear is outside the solve timer, emulating absence of persistent reuse; ordinary base6eb map insertion/lookup remains inside the solver.
- Four alternating pairs per case: cached-first, uncached-first, cached-first, uncached-first. First 2 pairs warm; last 2 timed. Each variant is finished immediately after its solve. All physics/output/roundtrip assertions run on warm AND timed pairs, outside the measured solver/finish intervals.
- Compare geometry/support-path flags, full gone/drained arrays, changed states AND candidate order, removed/wood/ground arrays, leaf/fluid order and9fracture removed arrays. Compare complete stored NBT including material; restore BOTH plans and compare each full re-encoded payload. All 3040 Collapse comparisons and6080 individual plan roundtrips pass.
- Parse, prime, snapshot/context setup, one-time cold Blast setup and both-plan encode/restore cost are separate raw columns. Pair total is solve+finish; source parsing and validation are excluded from that total.

## Provenance and artifact boundaries

Manifest SHA256 `abc7d17082f7219b826774bdc6deee333419dd13f53a5b6cdf6c9d5d0b16db83`. All 2177 decompressed chunk hashes/lengths verified: 179140007raw bytes, DataVersion 3955, minY -64, height 384, seed-1905315873147090944. Per-chunk hashes only are in chunk-hashes.csv; no private NBT or full private manifest is published.

Exact createpropulsion 1.1.5 provider SHA256 `5525a3a0c8b1c476cbc467d021e9d1492b58c78de31ed269eb04f169d42db0a1` (1707765bytes) copied from the existing runtime-hold into ONLY the disposable GameTest mods, hash checked by the copy task, then moved aside on exit. Original and returned copies match; runtime-proof.json records this. No jar is published.

tools/laptop_job.sh, explicit SESSION_TREES and unique INCOMING_GPU_OWNER=MT-CHECK-CORPUS-20261104. GameTest and single-use Gradle JVMs use4 CPU/2 GiB for the measured run; no --daemon option. Initial source verification+compilation passed in 13 s; the only actual corpus run is the full760-case process. All jobs are finished/collected. Generated harness classes were preserved outside the active devtest classpath after the run; all artifacts remain ignored/private except this evidence branch. Tracked checkout is clean.

Public files: this report; exact harness/init/verification/analysis source; source/runtime/input hashes; numeric raw CACHE lines; all3040 pairs.csv;760 cases.csv; summary/environment/noise JSON. Full JVM startup/stack log and disposable runtime/world remain local under mod/run/mt-check-corpus. No private NBT/world, binary jar/class/JFR, release or production diff is published. Root owns any cache/ownership design decision and its later review/validation.
