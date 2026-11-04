# R575 independent static review

Reviewed 2026-11-04, Codex / Sol xhigh. Pinned head: `0006b0ccc2a8c041d4947ddc25d0da7810a437d4`; base: `36c9b5bdddbad22cc4e418c7b02bb7a141f56e4b`. Review checkout: `/mnt/data/projects/incoming/mod/run/wt-R575` (clean, detached). Implementation remains owned by MT-L2 in `wt-MT-L2-P2`, branch `codex/conventional-impact-budget`, PR #575. This is a review handback, not an ownership transfer.

## Verdict

**No blocking static correctness finding.** The added empty-section check preserves the previous traversal and its observable encounters for the actual server/client consumers and standard Minecraft/NeoForge chunk APIs inspected. No demonstrated crash, physics change, synchronous chunk load or performance regression was found.

**Performance acceptance remains open with the owner.** Only the supplied baseline exists in this review. Source inspection and getter-call counts do not establish an improvement, preserved whole-scene outputs, or compliance with the final tick budget. This report is not a merge/performance sign-off.

## Scope and independence

Read the requested diff, the previous `FragmentTrace`, current `FragmentStrike` and `FragmentHits` consumers, new unit fixtures, applicable rules and current TEAM/BOARD/live. No PR description, MT-L2 report/conversation/inbox or MT-P2-DIAG report was opened. A general performance-memory file contained a historical diagnostic summary; it was not used to establish the findings or the numerical attribution below, which were derived directly from the supplied raw baseline.

Inspected local Minecraft/NeoForge sources in `/home/artem/.gradle/caches/neoformruntime/intermediate_results/sourcesWithNeoForge_0e9be3e3bbc647eb9af1266fec36029e4755faff_output.zip`. Also inspected installed Lithium 0.15.4 inline-block-access/inline-height and Sable 2.0.6 plot/section mixin bytecode. No source edits, new tests, builds, game/server/GPU jobs, CI run, public action or protected-resource operation were performed. No owned job remains to await. Owner build/GameTest and CI processes were observed in the separate implementation worktree and left alone.

## Correctness evidence

- **Traversal and boundaries:** `FragmentTrace.java:59–84` retains the previous cell stepping, X/Y/Z tie priority, exclusion of the explosion cell, `s >= maxS` endpoint behavior and build-height exits. The optimization does not jump across empty cells or sections. Chunk lookup and unknown-terrain termination still precede the new branch. Negative coordinates use the same arithmetic shifts. `sectionY` is invalidated on every horizontal chunk crossing and refreshed on every vertical section crossing.
- **Readiness:** `chunkNow` is unchanged. Server `getChunkNow` remains the non-waiting lookup; client `getChunk(..., false)` returns null for a missing/out-of-range chunk (`ClientChunkCache.java:72–81`). The new `getSection` and `hasOnlyAir` calls access existing memory and request no chunk. Cached references remain local to one synchronous ray, just as the existing chunk reference did.
- **Getter equivalence:** normal `LevelChunk.getBlockState` itself returns AIR when its section reports `hasOnlyAir` (`LevelChunk.java:178–203`). All section slots are initialized by `ChunkAccess` (`98–118`); `getSectionIndex` uses the chunk's minimum section, not an assumed overworld offset. The trace's existing height check precedes section access. Nonempty sections still use the original chunk getter and the unchanged air/tag/fluid/collision-shape sequence (`FragmentTrace.java:94–103`). Water, leaves, glass, shapeless blocks and contextual shapes therefore retain their previous behavior. No fragment-energy, mass, range, seed, body-intersection or damage law changes.
- **Debug world:** vanilla synthesizes BARRIER at Y=60 and display states at Y=70 before examining stored sections (`LevelChunk.java:182–192`). `level.isDebug()` correctly bypasses the new shortcut for the entire ray. The test's virtual block is synthetic rather than a real debug generator, but exercises the relevant bypass.
- **Mutation/lifetime:** standard `LevelChunk.setBlockState` changes the existing section (`239–249`); section setters/recounts update emptiness. Packet replacement reads into existing section objects (`489–495`). The new code rechecks `hasOnlyAir` on every visited cell; it does not retain an empty/not-empty boolean. Actual visitors only accumulate server glass/chips or client hit records (`FragmentStrike.java:204–215`, `FragmentHits.java:74–84`); world edits occur later (`FragmentStrike.java:245–269`). There is no callback that replaces a section during a trace. No cross-ray/world terrain cache was introduced. An imagined arbitrary section-array replacement during a callback is not a demonstrated defect in these consumers or standard mutation paths.
- **Compatibility:** the inspected Lithium block getter has the same empty-section AIR condition. Sable plot mutation hooks do not synthesize hits on otherwise empty stored sections in the inspected classes. This is a targeted source/bytecode check, not a full-pack runtime compatibility claim or a guarantee about arbitrary getter-replacing third-party mixins.

## Tests: useful coverage and optional follow-up

The `reference` traversal (`FragmentTraceTest.java:162–212`) matches the base implementation except that its chunk lookup calls the unchanged production helper. It independently retains the previous traversal rather than calling the optimized traversal; result equality checks callback order, state, distance, point and entry face. Requested-chunk sequences are compared separately. The fixtures exercise 104 direction/start combinations, negative/chunk/section boundaries, ties, upper-height exits, glass/shapeless/water encounters, chunk absence, later-ray state changes and replacement chunk objects, empty getter avoidance and debug bypass.

**Optional, nonblocking — strengthen API-level fixture coverage (`FragmentTraceTest.java:65–78,162–212`).** Both paths share `chunkNow`, and every fixture is a mocked `ServerLevel`; the client missing-chunk branch is verified here by source, not these tests. Mock getters return directly from a map, and mock section emptiness scans `isAir`, whereas NeoForge's real counter uses `BlockState.isEmpty` (MC-232360 patch). Thus these tests do not independently validate actual section counters, native section mutation/packet decoding, client readiness or special modded-air semantics. A small real-section/client fixture would improve future protection; no current mismatch follows from this because the implementation reuses the actual getter's `hasOnlyAir` predicate. The copied old DDA is an equivalence oracle, not an independent proof of ideal geometric intersection semantics.

Per-ray equivalence also does not prove identical results for a whole evolving world: `FragmentStrike.trace` still ends portions by wall time (`181–185`), so a faster ray can change interleaving with other live jobs. Seeds and ray order are retained, but final preserved-output acceptance needs the owner's controlled comparison.

## Raw baseline and remaining owner gates

Recomputed directly from `wt-PERF-C2/mod/run/perf-c2/runs/main-profile/work-summary.json` and `async.jfr.cpu.collapsed` (full copied pack, 100 native night-city-spread, baseline `3be78e33b`). Confirmed no warhead/work source diff between that baseline and review base `36c9b5bdd`.

| Baseline measure | Value |
|---|---:|
| Fragment kind total, full 1002-tick window | 1642.084 ms |
| Fragment per-tick p99 / max | 26.038 / 28.396 ms |
| Impact total per-tick p99 / max | 31.4792 / 47.3008 ms |
| Server CPU samples containing FragmentTrace | 571 / 4961 server samples |
| Those trace samples also containing LevelChunk.getBlockState | 307 / 571 |
| Render-thread samples containing FragmentTrace | 8 / 23086 render samples |

These kind values aggregate work per tick, not individual ray/unit duration. CPU samples are inclusive, counted once per stack; they are not stopwatch milliseconds or a measured removable fraction. They establish a relevant baseline target, not the gain of skipping empty-section getters.

Owner still needs the requested measured before/after at the final head, preserved physics/output evidence, required build/GameTest, green `tools/pr_ci.sh`/Итог and normal delivery decisions. In particular, report active windows and total cost as well as p99/max; a quiet tail or unchanged scheduler cap can conceal a real change in total work. No additional reviewer run was started.

## Memory

Recorded the durable native section/getter/debug/mutation contract in shared memory as `fragment-section-read-contract.md`; no candidate performance result was recorded as fact.
