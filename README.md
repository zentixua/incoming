# MT-B3 horizon publication evidence

Panorama candidate: `11f4c67f7`; final source: `54b0198af` (B2 merged). PR [#564](https://github.com/zentixua/incoming/pull/564), base `8e3e2dc28`.
**Functional gate FAIL. This is not completed B3 or acceptance of 1 Mt support.** See `MT-B1-gate.md` for the cumulative gate history and exact limits.

## What changed and what was actually measured

The draft candidate has a timed client queue, changed-section encoding from the same before/after full-chunk plan, bounded network batches, local section-union repair, and preparation ownership fixes. The dev scenario feeds *prepared synthetic columns* into the ordinary production server queue and client buffer. It does not exercise real-city planning or the complete FrontLods producer. The connection is integrated, not WAN. A 90-degree outer-20%-radius panorama covers about 9% of the 5 psi disk. Yields are test inputs; the public ceiling is unchanged.

Installed stack and hashes: `runtime-jars.json`. RTX 4060; Voxy through Roxy, LSS, Iris/Complementary. Each run has its power profile and raw log. Requested 1280x720, captured 1280x692, 120 FPS cap, shader clouds disabled in the disposable fixture only. Atmospheric haze remains. These are diagnostic fixtures, not art approval of a city or the nuke. `prepare-stand.log` identifies a CPU-only harness preparation overlapping the final 1 Mt intact baseline; it completed before the timed ruin pass. No foreign GPU client ran. No user game/world/settings or server was changed.

| Final ordinary-transport run | 15 kt | 100 kt | 1000 kt |
|---|---:|---:|---:|
| Columns | 2,862 | 10,103 | 46,843 |
| Full 24-section baseline columns/s | 454.11 | 493.66 | 499.68 |
| Four-section ruin submission interval, s | 3.499257 | 6.552191 | 14.056841 |
| Maximum submission lateness, ticks | 1 | 0 | 13 |
| Mean submission lateness, ticks | 0.0482 | 0 | 0.9367 |
| Peak reported queued + repair bytes | 6,278,559 | 22,158,072 | 102,728,892 |
| Central rendered-depth witness, ticks late | not captured | 0.6001 | 0.9800 |

The depth witness samples three pixels and is **not** a renderer acknowledgement or worst-case latency. Its 15 kt post-ruin threshold was not met, so there is no final-run depth timing for that yield. Submission means decode/enqueue, not completed Voxy rendering. `elapsedNs` also includes repair-union retention in production; it is elapsed time, not CPU. Timed throughput is demand-paced achieved throughput, not saturated capacity. The 120 FPS capped mean frame intervals (8.48–8.57 ms) are not GPU costs. The normal scene comparison is archived separately and does not exercise this Voxy panorama.

## Decisive stale replay sequence

Run `client-20261104-031908`, source `11f4c67f7`, replay round 2:

- `replay_2_000_tick6514.png`: before old data; no central towers.
- LSS public `dispatchColumn`, 64 intact columns, dispatch completion logged at tick 6517.
- `replay_2_020_tick6518.png`, then frames around ticks 6518–6520: whole tower pieces visibly return.
- All 64 sampled positions converge to ruins by the end of the two-second observation.

The central strip contrast peaks at 4.7576/255; the database witness sees old data from +79.4844 to +165.7828 ms (sampled endpoints). The two-second observation is not a measured repair latency. Both prior single-column replay trials also expose stale database samples, despite mostly hiding between frames. The 64-column cache was primed before replay: this failure does not depend on eviction/reconnect. Public API/ingestion bytecode is archived to show the void observational callback and independent Voxy worker queue; no callback or zero queue count is treated as a fence.

## Sequence fidelity

`*-lossless.mkv` files preserve every captured RGB frame. `video-verification.json` maps frame names and verified decoded RGB MD5s. Playback is a nominal 120 FPS preview; the original `events.tsv` and frame-name game ticks are authoritative timings. Selected PNGs are losslessly recompressed and verified byte-for-byte after decoding. `pack-sequences.py`, `replay_pixels.py`, `analyse.py`, and `archive.py` are included. `SHA256.json` covers every archived file other than itself.

## Runs retained

- `client-20261104-022302`: first production-client attempt, custom probe server transport. 1 Mt maximum 12 ticks late; a single-column replay transient is visible in frame 016. Historical source snapshot included.
- `client-20261104-023441`: ordinary old writer, 15 kt only; 2,862 baseline columns took 143.406 s (19.96/s).
- `client-20261104-025029`: completion-driven writer, old 50 ms client ACK polling; baseline 33.705 s; 15 kt ruin submission max 0 ticks late. Compiled-class hashes retained.
- `client-20261104-025954`: `34ac83d85`, 2 ms completion polling, 24 MiB client queue. 1 Mt max 282 ticks late; two single-column replay sequences.
- `client-20261104-031908`: final `11f4c67f7`, receive sampling separated from capacity waiting, 96 MiB queue + 24 MiB repair cache, 64-column replay. Final table above.

## Limits and validation

The complete 512 MiB server ownership/tick-budget gate is not proven by these fixtures. Queue/cache payload bounds exclude foreign Voxy decoded jobs, storage, meshing, GPU data and other heap. Actual plan-producer prefill, durable revisions, reconnect/late join, legitimate newer chunk changes and chronologically overlapping physical plans remain open. A local section union is not a structural overlap proof. No test claims otherwise. Keep #564 unmerged and the yield ceiling unchanged; #561 remains a separate development probe with its archived baseline.

Full build passed (`build.log`); all 17 selected local GameTests passed (`gametest.log`); `ci-final-11.log` records green Итог. Source/test fixes after the failed intermediate CI are visible in the PR. The final merged-head scene comparison is in `stand/`; the pre-merge comparison is in `stand-pre-merge/`. Both are separate from the failed horizon gate. No merge or release.

Pre-merge normal scene comparison: **pass**, one main/candidate/main pair, pack `none`, 100 strikes.
Worst gated GPU p50 +12.12% (inside: 4.367 vs 3.895 ms), within 25%; every gated render-thread CPU p95
comparison passes. Pre-merge raw frames, timings, host load and verdict are in `stand-pre-merge/`. Another CPU-only job
prevented quiet before warm-up; the harness retained the paired measurements and reported the load.
No Voxy panorama is part of this standard scene test. All clients exited and the lease was released.

Production source caveat: FrontLods still follows its DH lead schedule; there is no independently admitted
complete visible-prefill transaction. The buffered fixture is intentionally a best-case transport test,
not proof of readiness of a real strike.

## B2 integration

Final source head `54b0198af` merges main `ab2741fa1` (#560), without rebase. The panorama measurements above remain measurements of `11f4c67f7`. `FrontLods` consumes the merged B2 `RuinPlan.applyToCopy`: the copy represents structural failure, while live `RuinPlan.apply` also releases material into `RuinMatter` for subsequent support/fall/settlement. The detached copy does not execute those later stages. Thus this merge adopts B2 as the source but does not close stage-by-stage full-chunk/LOD agreement.

Merged-head checks: `54b0198af`, build successful in 3m43s, 1782 unit tests with zero failures/errors/skips; all 17 selected GameTests pass. `ci-merged.log` confirms green Итог (run 41763135923). `build.log`/`gametest.log` now refer to this merged head. The preceding normal scene comparison was pre-merge; the merged-head comparison is recorded below and in `stand/`.


Final merged-head normal scene comparison: **PASS**, `54b0198af` versus main `ab2741fa1`, one A1/B/A2 pair, `none`, 100 strikes. All six gated GPU p50/render-thread p95 comparisons pass (candidate versus the harness-selected baseline: GPU −11.88% edge, −14.83% inside, −0.79% outside; thread −2.53%, −2.08%, −0.58%). These are one paired-run results with baseline variation, not a general performance improvement claim. `stand/` holds the final raw results, host logs and frames; `stand-pre-merge/` preserves the prior comparison. All clients exited; GPU lease released. Publication gate remains FAIL.
