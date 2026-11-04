# MAT2 — bounded repeated material work

Exact component source base: `76937d7d869b95e3eabc55def2be37a1c87c73a3`.
Final checked component: `dec508cc2e444f0968848faafd385b7e750feb8b`.
Production commit: `39cd2a8fadeecfc00d9197aebd800ee9299af737`; additional tests: `dec508cc2`.
PR: https://github.com/zentixua/incoming/pull/591

## Change and compatibility

RuinMatter's existing 157-byte FIFO row captures fine/coarse in unused bits of its flags byte. Legacy internal flags 0/1 still classify from current tags once; encoding the next rotation adds the marker. New captures and disk restores still classify current tags. FIFO rotation and frozen-save iteration retain the captured classification. This removes the repeated tag calls, not Piece allocation or physical integration.

The durable packed payload remains 145 bytes and its outside byte remains ordinary 0/1. Legacy named-field saves remain unchanged. No persistent marker, new queue/cache, record expansion or memory-cap change. Cargo and palette ownership use the existing representation.

Held-window readiness is observed once within one work invocation, invalidated when the held window/radius changes or unloads. Each work call refreshes it, including another call within the same game tick. This controls ticket replacement only; destination and block-callback readiness still query current world state. The original loadTick restriction, physics, elapsed time, drops and accounting remain unchanged. A newly ready held window may be observed at the next invocation; no cached result authorizes an unready destination read.

## Validation

- Original source: `red.log` / `red.xml`, 3 tests, 2 failures. One fixture counts 64 held-neighbourhood calls where one is required. Another changes tag membership after capture and observes the old FIFO reclassification. Outside-work live readiness passes on both versions.
- Candidate: `green.log`, 3/3 pass in 11 s. Expanded `extended.log`, 4/4 pass in 8 s. Final unit XML contains all four passing cases, including both old outside flag values, all physical fields/scorch/cargo identity, unchanged retained ownership, live destination checks, same-tick refresh and outside-work refresh.
- One full build: **1816 unit tests, zero failures/errors/skips**, 4 min 17 s.
- Touched material/ledger/transport GameTests: **22/22 pass**, 4.883 s test time / 22 s Gradle. Includes new `nuke_material_codec`, ordinary falling/deposition and displaced-decoration drops, cargo save/reload, wall gravity/pulse, pile/support, memory admission, real frozen SavedData wrapper and cold ledger import.
- New codec GameTest: five released types (slab, barrel, leaves, glass block, pane), two coarse pending records; 64 same-time work rotations preserve named save fields and retained ownership; actual streaming writer keeps both 145-byte motion records; packed and legacy reloads preserve aggregate/per-type material totals, scorch, physical fields and cargo; repeated handoff creates no extra material.
- Fresh independent Sol xhigh review: `RMAT2-review.md`, exact component diff and surrounding source, no serious findings. No author conclusions/PR description supplied. Review first returned before the author's full build/GT completed; those terminal results are recorded above separately.
- Jobs: repository laptop_job wrapper, each JVM 4 active processors / up to 2 GiB, no shared daemon. All owned heavy jobs ended with exit 0; the deliberate red run ended 1 as expected. No GPU, client, Crafty, played-world or protected-server operations.

## Measurement limits

The motivating actual15 JFR is source `ad13`, reported separately by PERF-C2: 3311/12469 late server samples inclusive in RuinMatter, 350 repeated fine-tag-chain samples and 283 held-window-guard samples. Those are sampled path attribution, not exact saved wall time. The regression demonstrates 64→1 held-window observations and no tag queries across 64 marked rotations. It does **not** establish real-map or whole-zone speedup, remove Piece allocations, prove a whole-JVM heap limit, accept inherited producer work, or establish release/1 Mt readiness. No additional broad profiling or 760-case corpus was run.

LEAD2 owns final source composition, main merge and actual runtime acceptance. MAT2's exact three-file component excludes inherited producer/grid changes.

## Terminal full CI — not green

Run [41856497724](https://github.com/zentixua/incoming/actions/runs/41856497724) for exact component head `dec508cc2` completed with **failure**. `tools/pr_ci.sh 591` was awaited and returned **1**, recording the failed `Итог`; its earlier “no checks” line was a temporary observation while the final summary had not yet appeared. The final workflow API has no unfinished jobs. Build, both unit groups, both LSS integrations and clean installation passed. Five GameTest shards each failed one test:

| Shard | Test | Actual failure |
| --- | --- | --- |
| 1/6 | expiredworkerkeepsinputsuntilactualexit | cancel mutated running Grid inputs |
| 2/6 | forgottentaskandworldcontextkeepactualgridinputs | cancel mutated running Grid inputs |
| 3/6 | clearedpreparationkeepsactualgridinputs | cancel mutated running Grid inputs |
| 4/6 | cancelledpreparationkeepsactualgridinputs | cancel mutated running Grid inputs |
| 6/6 | detonationrunsbudgetedqueue | Glass still present at relative 16,12,32, t=1201 |

The four shared Grid assertions are in unchanged `RuinInputLifetimeGameTests.java:95–96` (one of a missing block, changed lighting, or absent cached Blast). The pipeline assertion is unchanged `NuclearGameTests.java:1437`. These are concrete integration validation failures outside MAT2's exact three-file diff. **Their root causes are not diagnosed here, and they are not declared flaky or unrelated by assumption.** The source base inherits the older producer implementation; producer/queue source owners and LEAD2 own diagnosis and final combined CI. No inherited code or fixture was changed, no CI rerun was requested, and no additional full build was repeated. The independent review identifies no serious defect in the exact MAT2 diff, while full CI remains a blocker to merging this stack.

Raw complete failed-job download: `ci-failed.log`; individual reviewer downloads: `RMAT2-ci-shard{1,2,3,4,6}.log`; terminal job metadata: `ci-final.json`. Component source/tests and review are explicitly handed back to LEAD2 for integration; this PR remains a draft and no main merge is performed.
