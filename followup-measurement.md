# RUINZONE follow-up: radial settlement and sustained throughput

Final code: `3b68a5adefb8b38e60b3b2387afc11d0ac7e2248`, PR #553. No merge or release.

## World and machine

This is an existing Newisle city, not the 100-chunk synthetic GameTest. Its filming master is dated 14 October 2026, with a 4,656-block world border centered at (-520, -264). The original `mod/run/newisle-master/world` was only read. Reflink copies under this worktree's ignored `mod/run/ruinzone/` were the only worlds opened or modified. The source has 99,225 occupied region-header entries (including partial chunks), about 1.4 GiB of files.

CPU-only dedicated NeoForge 21.1.264 server, Java 21, Create 6.0.10, Sable 2.0.6 and Aeronautics 1.3.2; no player, DH/Voxy/LSS/MapSyncer or GPU. Ryzen 7 7745HX laptop, **power-saver profile unchanged**, 8 GiB JVM heap. `-XX:ActiveProcessorCount=12` makes the JVM see 12 processors and the ruin pool choose ten workers; this is not a CPU affinity limit or a benchmark of the actual netcup host.

A retained 7 kt surface detonation at (-520, 65, -264), scale **1.0**, gives a 6,275.79-block ruin radius and 484,901 candidate chunks. It uses the normal older-save settlement path, without live strike preparation or contemporaneous flash/crater/fallout work. Gameplay settings were not tuned for throughput. Partial/absent disk chunks are skipped by settlement; ordinary neighbor loading/old-world retrogen still follows vanilla behavior.

## Results and honest extrapolation

The complete traversal confirmed **85,849 saved chunks**, visited **484,901 positions**, and ended with **zero pending positions and zero held tickets**. Vanilla then logged all dimensions saved. Two deliberate process stops/restarts resumed the same detonation and durable cursor. Total active server time across C/D/E was approximately **913 seconds (15m 13s)**, excluding shutdown/startup and the deliberate intervals between runs. The raw whole-pass rate is **94.0 saved chunks/s**. It includes checking almost **399,052 empty/partial positions**, which dominate the end of this small city inside a much larger 7 kt circle.

| Measured interval | Saved chunks confirmed in interval | Seconds | Chunks/s | Direct 385,000 / rate |
|---|---:|---:|---:|---:|
| Dense central city, phase C | 37,444 | 360.007 | 104.0 | 61.7 min |
| Final head, resumed city processing, phase E through 320 s | 36,604 | 319.968 | 114.4 | 56.1 min |
| Final head, complete remaining pass including the large empty tail | 39,440 | 468.001 | 84.3 | 76.1 min |
| Whole traversal across C/D/E, including the empty tail | 85,849 | ~913 | 94.0 | 68.2 min |

The last two raw extrapolations preserve Newisle's very sparse saved-chunk fraction (17.7% of the circle). The reported 25565 case has 385,000 saved chunks in a similar 484,901-position circle (79.4%), so that ratio is different. The observed empty-tail scan rate was **2,560 positions/s** (phase E: 236,089 at 369.952 s to 466,489 at 459.952 s). A transparent two-cost estimate is:

- measured saved-chunk cost: `(913 - 399052 / 2560) / 85849 = 0.00882 s/chunk`;
- 25565-size zone: `385000 * 0.00882 + (484901 - 385000) / 2560 ≈ 3434 s`, **57.2 minutes**;
- using the slower dense-center rate instead gives about **62.3 minutes**, including the remaining empty probes.

Thus **about 57–63 minutes** is the estimate for the requested saved-chunk density, not a measured netcup completion time. The actual server's CPU, storage, players and map/LOD I/O can change it. A comparable dense 15 kt world has more work; no 15 kt or actual-netcup run was performed.

Phases C/D used `940bd87e5`; E used final `3b68a5ade`. The latter fixes protection of incomplete disk windows and distinguishes preparation gating from memory pressure. The final-head interval alone covers 39,440 additional saved chunks and the remainder of the entire circle. The process restarts also test upgrading an unfinished pass. Earlier diagnostic A/B copies were stopped and saved normally after exposing cache retention problems; they are not counted as successful complete traversals.

## Tick time and memory

**Full server MSPT is measured from highest-priority ServerTick.Pre to lowest-priority ServerTick.Post.** This includes Incoming's Post work. Phase C instead sampled vanilla `tickTimesNanos`, which is written before Post; its `mspt*` fields are not presented as full MSPT here. It still supplies valid wall time, saved counts and separately measured nuclear-lane time.

| Final-head phase E | Full MSPT mean / p95 / max | Nuclear lane mean / max |
|---|---|---|
| City-processing interval through 320 s | 22.212 / 50.515 / 95.683 ms | 4.058 / 26.904 ms |
| Entire remaining pass, including empty tail | 16.402 / 49.810 / 95.683 ms | 3.092 / 30.625 ms |

The loop advanced 9,360 ticks in 468 seconds, approximately 20 TPS. These are CPU-only, no-player measurements; individual vanilla/GC/I/O stalls remain possible. The common work clock still admits units by its deadline/estimate and retains the repository's single-final-unit rule; it is not a guarantee that every wall-clock tick or indivisible operation stays under 30/50 ms. Deterministic counting-clock tests check the queue's admission and trimming bounds.

Final-head E: sampled used-heap peak **3,288 MiB**, largest reported post-GC live heap **1,249 MiB**, largest reported retained-plan estimate **185 MiB**. The dense-center C phase peaked at 4,663 MiB sampled used heap, 1,514 MiB reported live heap. Used heap includes garbage and is not live retention. Reported samples are taken every ten seconds; they are not allocation-profiler maxima.

## What limits throughput now

There is no fixed eight-ticket ceiling. Admission grows/shrinks with external tick time, spare nuclear clock time and plan/heap headroom. Root tickets reached **144** during the dense-center run and **63** during final-head E; already held work drains when the admission limit shrinks. The normal vanilla load/save/unload pipeline, plan work and memory headroom set the sustained rate. All operations still consume the common nuclear clock after player-visible work. Header I/O is bounded at 128 outstanding requests and durable unconfirmed positions at 2,048.

A circular frontier retains many neighbor snapshots. Under pressure, LRU eviction frees re-readable cold snapshots and cached fractures under that same clock; it keeps changed-block deltas, pre-wave shadow heights, active worker windows and windows still collecting disk reads/awaiting worker admission. Empty plans also complete the old-world retention window. Without these two retention fixes, the measured pass slowed or stopped around 16,000–25,000 city chunks despite ample live heap.

## Configuration and validation

The fresh generated TOML contains **both** chained comment paragraphs for `nuclear.far_zone`; see `far-zone-generated.toml`. The setting's meaning did not change in this follow-up, so its tooltip keys were not edited again. Existing saved chunks settle regardless of the DH generation setting.

Final head `3b68a5ade`: full local `./gradlew --daemon build` passed in 6m 19s: **1,740 unit tests**, zero failures/errors/skips. All **32 touched GameTests** passed locally, including flash-layer, disk/restart, budget, neighbor, heap, cache and stop/drain batches. Heavy jobs ran through `tools/laptop_job.sh`, serially. [Final CI](https://github.com/zentixua/incoming/actions/runs/41422173025), including all six GameTest shards and required **Итог**, is green.

New regressions cover radial order/completeness/cursor restore, migration from the early row cursor, adaptive growth/backoff, empty-plan retention, rebuilding original blocks after snapshot eviction, and partial no-heightmap disk windows surviving eviction before worker admission. The last two reported retention bugs have red/green logs. The known `chunkWithheldUntilRuins` flake did not occur.

The saved-world cause of #523 is addressed. Normal client cache refresh and transient boundaries during unfinished work remain outside this fix. No real player/server/Prism world was modified. Measurement drivers and logs are evidence on `look-gate/ruinzone`, outside the PR diff.
