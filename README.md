# MT-B5 evidence and remaining acceptance gates

This is a partial MT-B5 implementation under the fallback in MT-report §33.2. The public yield ceiling remains **15 kt**, as do existing world values and `default_yield=15`. There is no release, deployment, merge or claim that the complete 1 Mt feature is ready.

## Existing tools considered first

| Available solution | What is reused / why it does not close this task |
| --- | --- |
| NeoForge SavedData and world attachments | Existing authoritative material/checkpoint persistence and per-world ownership are reused. No new dependency. |
| fastutil collections and the existing nuclear work clock | Existing bounded-step scheduling and primitive collections are reused. |
| Shinoyuki BetterAutoSave | Async saving addresses save stalls, not pre-removal material ownership, support provenance, cross-dimension admission or event-time publication. It is not added. |
| MT-B3 `RadialChunks` | Reused row-head traversal; a default-preserving overload retains the legacy crater block-column order. |

References: [NeoForge data storage](https://docs.neoforged.net/docs/1.21.3/datastorage/attachments/), [BetterAutoSave compatibility](https://github.com/ShinoyukiMiyako/Shinoyuki-BetterAutoSave/blob/main/docs/COMPATIBILITY.en.md), [project listing](https://modrinth.com/mod/shinoyuki-betterautosave).

## Implemented behavior

- Material ownership is reserved before destructive writes. Moving pieces, support seeds/provenance/live graphs, terminal reservoirs, crater checkpoints and streamed settlement work contribute to the shared server admission counter across dimensions, including restored SavedData. Preparation leaves transition headroom. Refusal keeps the source/plan or saved work pending.
- Completed crater detail is reduced to material totals. Terminal material groups equal block state/cargo instead of retaining every trajectory; inventory NBT remains owned. The counter is a conservative working-reservation estimate, **not a bound on the complete JVM heap** or arbitrary legacy SavedData deserialization.
- Capacity waits yield the scar lane. Existing moving material gets an early bounded work batch. Retired configured worker buffers cannot block material/settlement admission. Cache eviction runs before traversal allocation, so re-readable snapshots can free the capacity needed by an already retained plan.
- Crater and settlement traversal use O(radius) row heads and bounded replay instead of constructing an O(area) array/sort before work. Completed crater chunk checkpoints are cleared. Settlement still eventually visits coordinates in the zone; **a sparse MCA-region index is not implemented; pre-launch `NuclearPrep` order/edge arrays also remain outside this streaming change**. Historical unresolved state is resumable, not constant-total-cost.
- Fire coverage no longer ends at ±4096 m: a bounded texture covers the physical reach while source columns retain their physical spacing. This is coverage, not a change to blast/fire laws.
- Historical detonation packets cannot revive an expired arrival wake after a Flashback seek. Packet-level privacy regressions cover entity hints, direct crosshair targets and an invisible passenger. Game-model carrier regressions preserve actual impact position/time/yield and distinguish ordinary lethal damage from interception.

## Checks and their limits

The local full build and 43 selected GameTests passed before the final cache-only correction; the final correction passed a fresh build (1,770 unit tests) and all five targeted production-tick/admission GameTests. CI is recorded separately for the final pushed head. Unit tests cover the 15/100/1000 kt fire footprint and exact legacy crater traversal order. Material tests cover saved state, compaction/cargo, cross-dimension capacity, continuation and 1/10/100 registered overlapping events. The latter are bounded structural fixtures, **not 100 complete 1 Mt launches**.

Two independent fresh `codex exec` reviews were run, with only diff/surrounding code and rules. First-round findings (scar starvation and retired worker buffers) were reproduced before fixes. The final review found cache cleanup behind its own allocation gate; `cache-repro.log` reproduces it on dba2d153d. `cache-fix.log` covers the fix through production `NuclearWorld.tick`, with a registered event, no moving pieces, no outside reservation release, cache reduction and exactly one plan application. No third review was run.

## CPU measurement: what the numbers mean

`PipelineBench.java` registers real `NuclearEvents`; material/support work runs through production `RuinMatter.work`. `RuinMatter.instrumented.java` and `RuinSupport.instrumented.java` contain only timing/retained-size instrumentation for this disposable probe. The final probe attaches its context to `NuclearWorld`, samples immediate post-apply ownership, and records live graph size inside support work (an earlier between-ticks sample missed short-lived graphs).

Each sample constructs one already-loaded chunk, then measures snapshot/descriptor work, blast, collapse, plan finish/apply, light barrier and packet encoding, followed by the registered material continuation. Yield is 15, 100 or 1000 kt. Sparse controls have a short glass-supported roof; dense fixtures have a 60-block concrete frame with floors and glazing. Two passes reuse the location; deposits outside the reconstructed footprint remain. Changed-block counts differ, so this is **not an equal-work speed comparison**. There are no cold disk reads in a timed planning stage. GameTest runs ticks unpaced; `elapsedMs` includes a 240-tick drain window and checkpoint measurement. It is **not preparation acceptance-to-flash latency**.

The earlier #560 baseline was **7.68 ms/chunk apply-only** and **10.10 ms/chunk median apply plus actual support continuation (7.93–39.82 ms)**. These are per-fixture CPU observations, not a whole-zone preparation rate. New per-yield measurements are in `measurements.md` beside that baseline.

## Paired frames and scene bench

`frames/main` and `frames/head` contain identical diagnostic sandbox commands/cameras. `install_media.py` is the exact helper. It injects a synthetic historical client event at age **300 seconds**, then captures at +1/+4 seconds. It does not launch a carrier, prepare a map or prove full/LOD destruction. Main is ab2741fa1; frame head is 381bd8c57. Subsequent production changes are server-side capacity/lifecycle fixes only; the renderer/shaders are byte-identical to the final head, as recorded in `source-identity.json`.

The wide 1000 kt pair shows the removed square fire-map clip; the 15 kt pair preserves existing coverage. The existing rectangular pall/ground edge in the 15 kt diagnostic remains in both versions. These are coverage diagnostics, not an accepted complete 1 Mt cinematic result. Sandbox WAVs are incidental captures; no audio assets/engine changed and no subjective listening verdict is claimed.

The single scene bench uses main/candidate/main, 100-strike scenes, and none/Complementary/Photon shader packs. The no-shader candidate used dba2d153d. The final server-only build replaced the input jar before Complementary/Photon; those candidate runs use 733c4b93c. The stand records jar identity only at startup, so its original pair metadata alone does not describe those later runs. `bench-jar-identity.json` records the actual per-pack revisions/hashes; all client/shader source hashes match. The only production difference is server-side cache eviction order. Exact hashes, host-load records and verdicts are archived under `scene-bench`. This conventional scene suite is not a 100-nuclear-event client-memory trial.

## Gates still open — no higher yield exposed

1. **MT-B3 remains open and its publication gate is failing.** Its recorded 1 Mt panorama has positive publication lateness and stale LSS replay; green CI does not certify event-time distant publication. See PR #564 / its own evidence. B5 does not alter that front's client buffer or claim to fix its gate.
2. **Unexpected cold carrier impacts are not prepared.** `NuclearReview` explicitly has no pre-launch terrain-admission contract. The added carrier tests prove current event semantics, not a bounded correct full-zone cold path. No delayed flash, reduced yield, dud or ICBM-only policy is substituted.
3. **No complete cold/reusable 15/100/1000 kt integration trial was completed.** Therefore there is no measured real 1 Mt acceptance-to-flash figure and no demonstrated 10–15-minute pass. Small-chunk observations must not be presented as that figure. Dominant unresolved work is whole-zone saved-chunk discovery/read/solve/commit plus support/material continuation and time-correct client publication; their full-world proportions remain unmeasured here.
4. **The entire arbitrary-world memory/completion envelope remains unproved.** Active ledger ownership now participates in admission, but a component larger than available memory can pause; this PR adds no graph paging. SavedData load and total heap/client/GPU allocations require separate full-envelope profiling. The 1/10/100 ledger fixture does not close every simultaneous launch/observer dimension.

These are substantive missing acceptance gates, not a request to waive them. Per the approved fallback, keep useful changes at 15 kt and leave MT-B5 / the 1 Mt feature explicitly incomplete. Command range, `MAX_YIELD`, new-world `max_yield`, Almighty command notes and network protocol remain unchanged because no ceiling or packet-format change is exposed.

Main integration: origin/main advanced to **1aca1654a (#566)** during validation; it is included by merge commit **e4338e65d**, with no rebase or force push. The sole conflict was the appended area-rule text; both entries were retained. Upstream closes cancelled-read ownership, historical plan-byte accounting and late FarLods publication after cancellation. Those fixes remain intact alongside B5 material admission. MT-B3 #564 itself remains open. The finite CPU probe and frame/stand labels retain their actual pre-merge revisions; production client/shader sources did not change in this merge. Final CI targets the merge head.

Final CI fixture correction: the first merged run exposed two delayed `NuclearReviewGameTests` sharing global ops-only/yield policy in one batch. One test’s cleanup could restore policy while the other requested a revised order (`No value present`). Commit **490a2aa84** gives the revision test a separate batch, retaining all assertions; production code is unchanged. This is the only extra targeting-test overlap. Raw failed shard log is archived as `ci-gametest2.log`.

The initial Complementary scene pair failed CPU p95 in the inside-smoke window (9.66 ms vs 5.89 ms). `bench-cpu-overlap.json` shows the author’s merge GameTest entering that same window: foreign CPU samples 3.31 and 7.10 cores, versus zero in both main windows. The failure is retained, not relabeled as a pass. Only that contaminated pair was repeated through the normal GPU queue; other passed pairs were not rerun.

Final merged-head verification: all seven targeted memory/cancellation integration GameTests passed after merging #566. **Итог is green for 490a2aa84**, [CI run 41770766544](https://github.com/zentixua/incoming/actions/runs/41770766544), including the isolated revision fixture, complete mod build, unit tests, all GameTest shards, both LSS paths and clean installation. The earlier failed CI remains archived.

## Final isolated scene result

The final immutable CI jar 490a2aa84 versus main 1aca1654a passed the isolated Complementary main/candidate/main pair, all six gated metrics. Inside render-thread CPU p95 was 6.70 ms versus 5.92 ms for the worse main run; GPU p50 was 11.00 versus 11.27 ms. No B5 CPU check overlapped it; the raw host record includes a brief foreign CPU sample up to 1.06 cores. Initial none/Photon pairs already passed. `accepted-scene-results.json` references the accepted pairs without rewriting the original failed verdict. `scene-bench-isolated` contains the exact final pair and immutable jar hashes. GPU lease was released; no B5 game client remains.
