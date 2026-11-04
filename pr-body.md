<!-- claude-projects-attribution: {"github_login":"zentixua"} -->
_Requested by **Artem** · [project thread](https://claude.ai/code/project/chan_01U1E42ZxayBQ8GE6s1yrHaq?thread=cmsg_01U1E42ZxayBQ8GE6s1yrHaqDaB6U2vRVJk76dZvg9E3f1)_

MT-B3 continuation of the client-buffer path from #561. **Draft: the full B3 publication guarantee is not closed. Do not merge this as completed megaton support.** The yield ceiling and server defaults are unchanged.

Prepared heavy-zone copies now carry the same deterministic plan's changed sections (including newly empty sections/light) and exact front deadline to a bounded serial client queue. The client uses the nuclear event clock; the pressure clock never waits for throughput. Server writes continue from Netty completion callbacks inside the existing acknowledged 256 KiB safety window. Receive-rate sampling ends at receipt, while buffer capacity separately controls acknowledgement.

The measured 1 Mt panorama needs up to 3,638 columns/s at the front, versus the transport's 1,280 columns/s maximum. A 24 MiB client queue left it up to 282 ticks late; this candidate holds up to 96 MiB ahead of the wave, shared across all client strikes, plus a 24 MiB section repair cache. These are client payload bounds, not a certified end-to-end heap or server 512 MiB bound.

**#561 stays a separate development probe.** This PR reuses its fixture and ownership regressions, but an unvalidated production candidate does not replace that measured baseline. Its evidence remains on [look-gate/mt-lod-buffer](https://github.com/zentixua/incoming/tree/look-gate/mt-lod-buffer).

### Ownership and review fixes

- RLOD: Voxy database witnesses run outside the producer/reset monitor. Reset returns an asynchronous Incoming submission barrier; it is explicitly not a foreign Voxy worker/renderer fence. Timings are elapsed, not CPU.
- RMT-B1 O1–O3: detached cancelled reads remain charged until completion; consumed/replaced plans release retained bytes; FarLods rejects old-generation completions. Far scorch traversal prepares bounded radial row heads instead of an area-sized array/sort.
- The front GameTest waits for real preparation, then advances the flight/wave by ticks. Embedded Netty tests advance their own virtual retry clock.
- Accepted OL ideas: stop/refire and late/never/bunched read regressions, incremental traversal before yielding, section-cost metrics, and immutable pressure deadlines.
- Protocol 54 adds trailing defaulted deadline/event fields; old Flashback golden samples remain unchanged.

### Open feasibility gates

The retained section cache can reapply an observed old LSS column. The public LSS consumer cannot veto another consumer; Voxy exposes no atomic publication or renderer completion fence. Two single-column trials on 34ac83d85 converged and showed no tower in 180 recorded frames, but both exposed a transient stale database sample. The final 64-column replay **visibly reinstates central towers**, despite a primed repair cache: [before](https://github.com/zentixua/incoming/blob/look-gate/mt-b3/runs/client-20261104-031908/screenshots/replay_2_000_tick6514.png), [stale towers](https://github.com/zentixua/incoming/blob/look-gate/mt-b3/runs/client-20261104-031908/screenshots/replay_2_020_tick6518.png), [all 90 lossless frames](https://github.com/zentixua/incoming/blob/look-gate/mt-b3/runs/client-20261104-031908/replay_2-lossless.mkv). All 64 positions later converge. **Functional gate FAIL**, not atomic stale exclusion.

| Final ordinary-transport envelope | 15 kt | 100 kt | 1000 kt |
|---|---:|---:|---:|
| Prepared synthetic columns | 2,862 | 10,103 | 46,843 |
| Baseline full-column throughput, columns/s | 454 | 494 | 500 |
| Max ruin submission lateness, ticks | 1 | 0 | 13 |
| Peak measured client queue + repair bytes | 6,278,559 | 22,158,072 | 102,728,892 |

One tick is 50 ms; submission is not completed rendering. The 1 Mt central rendered-depth sample is +0.98 ticks, but cannot bound the other columns' 13-tick tail. The envelopes cover about 9% of the heavy-zone disk on an integrated connection. [Report, raw logs, measurement scripts and front/replay sequences](https://github.com/zentixua/incoming/tree/look-gate/mt-b3) preserve every sampled frame with decoded RGB hash verification.

Reconnect/late join durability, chunk-load authority and legitimate newer edits, chronological overlapping full plans, and the complete global 512 MiB server reservation proof remain open. A section-union unit test is not proof of overlapping physical strikes. The existing FrontLods producer still publishes on its DH lead schedule, without an independently admitted complete visible-prefill transaction; a synthetic prefilled panorama does not prove the real-city preparation envelope. Do not treat eventual disk settling as front-time correctness.

No Voxy/LSS fork, mixin, author contact, release, deployment or server operation. Main `ab2741fa1` with B2 #560 is merged by merge commit `54b0198af` (no rebase). The buffer source is the merged B2 `RuinPlan.applyToCopy`. It represents structural failure; live application separately releases material to `RuinMatter` for falling and settlement. Matching those later stages in detached LOD copies remains open. The panorama evidence below is explicitly from pre-merge `11f4c67f7`, not a real-city B2 settlement proof. No yield increase.

### Validation

Final merged head `54b0198af`: full local build passed, **1782 unit tests**, zero failures/skips; all **17** selected GameTests passed (voxy, heavy/far LOD, stop, heap/edge and lifecycle). [Итог is green](https://github.com/zentixua/incoming/actions/runs/41763135923/job/126357076268), confirmed by `tools/pr_ci.sh`. All GPU panorama envelopes and three replay trials completed on pre-merge `11f4c67f7`; they remain negative evidence, not relabeled merged-head results.

The pre-merge normal scene bench passed (worst gated GPU p50 +12.12%, all gated thread comparisons passed). The **final merged-head/main scene bench also passed**: `54b0198af` versus `ab2741fa1`, one A1/B/A2 pair, `none`, 100 strikes; all six gated GPU p50/render-thread p95 comparisons pass. [Final raw verdict, frames and host logs](https://github.com/zentixua/incoming/tree/look-gate/mt-b3/stand). Neither normal scene bench exercises the Voxy panorama. The technical publication gate remains FAIL despite green CI. Per §33.2, preserve useful lifecycle work and the current maximum; this PR is not ready for merge as B3.
