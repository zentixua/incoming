Review completed: 2026-11-04 15:22:12 UTC

# RMAT2 independent review

Verdict: **no serious findings** in the exact diff `76937d7d869b95e3eabc55def2be37a1c87c73a3..dec508cc2e444f0968848faafd385b7e750feb8b`.

Validation status after continuation: **full CI failed; this PR is not clear to merge**. Local build, 1816 unit tests and 22 targeted GameTests passed. See terminal CI evidence below; the failures are not diagnosed or attributed to this exact diff.

Reviewed in a fresh Sol xhigh review session on `codex/nuke-matter-repeat-work`, worktree `/mnt/data/projects/incoming/mod/run/wt-MAT2`. HEAD matched the requested pin and the worktree was clean. Read current TEAM/context/live, the assignment entry in BOARD, repository instructions and applicable nuclear/world, work, chunk and test rules. No review handoff file was present. The explicit review assignment is the scope; implementation ownership was not transferred. Author reports, MAT2 conversation/logs, PR description and author conclusions were not read.

## Source assessment

- `RuinMatter.java:199-215,378-384`: the cached observation concerns only whether an existing material ticket can be replaced. Actual destination and callback-neighbourhood readiness still uses live `Terrain.ready`, `aroundLoaded` or `neighbourhoodLoaded` checks (`185-196`). A new held window invalidates the observation, each work invocation starts fresh even in the same tick, and `finally` clears the scope. Calls outside work continue to observe current readiness. A cached false can defer ticket replacement until the next invocation; it does not authorize a world read or advance a blocked piece's physical time.
- `RuinMatter.java:245-274,746-772`: the captured fine/coarse value occupies unused bits of the existing one-byte internal row flag. The internal row remains 157 bytes, with the original outside bit preserved. Decode retains cargo identity/key, source, all physical numeric fields and scorch metadata. Existing 0/1 internal rows follow the tag classifier on decode; newly encoded rows avoid those queries during FIFO rotation and frozen-save iteration. No new retained collection/cache or row expansion is introduced.
- `RuinMatter.java:704-735,775-787,868-925`: the durable packed motion payload remains 145 bytes with an ordinary 0/1 outside byte. Legacy named-field and packed disk restore still construct classification from current tags. The new internal marker does not escape through either disk writer. Release accounting, fine-material dispersion, handoff IDs, material totals and elapsed-time integration are unchanged.
- Read surrounding `RuinRecords`, `RuinPalette`, `LedgerData`, `NuclearTickets`, `RuinSupport` and the material-work call sites in `NuclearWorld`. No newly introduced correctness, material/cargo loss, save-compatibility, readiness/load-safety, work-budget or retained-memory defect was identified. Inherited producer integration outside this diff was not reviewed.

## Raw evidence inspected

- `mod/run/mat2/red.xml` and `red.log`: the initial three-test suite has two failures. The blocked-window fixture observed 64 held-neighbourhood polls where it expected one; FIFO rotation reclassified a captured piece. The separate outside-work readiness fixture passed. These are direct regression discriminators, not just a green-only test history.
- `green.log`: targeted run reports `BUILD SUCCESSFUL in 11s`.
- `extended.log`: extended run reports `BUILD SUCCESSFUL in 8s`.
- `mod/build/test-results/test/TEST-ua.zentix.incoming.nuclear.world.RuinMatterRepeatTest.xml`: four tests, zero failures/errors, timestamp `2026-11-04T15:18:32.765Z`. Inspected all four test bodies: captured classification without tag queries; both legacy outside values and all Piece fields/cargo across rotation; one held-window poll per invocation with same-tick refresh and live destination checks; live checks outside work.
- Inspected the added `RuinMatterCodecGameTests` body: five released types partition into two coarse pending records, 64 same-time work rotations preserve encoded physical/cargo/material data and ownership size, both durable formats restore equally, and repeated handoff does not duplicate release. This is fixture coverage; its same-time rotations do not measure actual falling throughput.
- At the final evidence read, `build.log` was still inside `:test` and `gt.log` did not yet exist. Those author-owned jobs and PR CI were already running; this review neither started nor interrupted them and does **not** report them as passed. The owner must collect their terminal results before delivery/merge.
- `git diff --check` passed. No test reruns, source edits, Git mutations, public comments, GPU/client/server/world operations or desktop control were performed.

## Optional suggestions and limits

No optional change is required for this diff. The evidence establishes the bounded query/codec behavior above; it does not establish a real-map wall-time speedup, total JVM heap bound, producer integration acceptance or release readiness.

## Memory

Updated the canonical `ledger-cold-import-progress.md` with the pinned internal-flag versus durable-schema boundary and invocation-scoped readiness distinction.

## Completed local-job evidence (2026-11-04 15:24:44 UTC)

Continuation rechecked the same clean branch/worktree at exact HEAD `dec508cc2e444f0968848faafd385b7e750feb8b`. The `incoming-job-mat2-build-gt` unit is no longer loaded and no MAT2 Gradle/game process remains. Raw `build.log` ends in `BUILD SUCCESSFUL in 4m 17s`; all 292 unit XML suites total 1816 tests, zero failures/errors/skips. Raw `gt.log` explicitly includes `nuke_material_codec`, reports all 22 required GameTests passed, saves all dimensions and ends in `BUILD SUCCESSFUL in 22s`. These completed results supersede the earlier pending-local-job observation. Existing PR591 CI watcher is still running; its checks were inspected read-only without the PR description. Source-review verdict is unchanged.

## Full CI validation blocker (2026-11-04 15:29:28 UTC)

Raw exact-head CI failure: GameTest 4/6, job 126667983196, run 41856497724. Of 117 tests, 1 failed: cancelledpreparationkeepsactualgridinputs. Its actual cancelled worker reported java.lang.AssertionError: cancel mutated running Grid inputs. Assertion is RuinInputLifetimeGameTests.java:95-96 (one of grid.at null, changed grid.lit, or missing hasBlast); forwarded by the test at 170. This file and its producer/cancellation implementation are unchanged by MAT2's exact three-file diff. The log does not identify which disjunct failed, and does not establish a root cause or flakiness. This is a real validation blocker for the PR, not a demonstrated defect attributable to MAT2. Producer diagnosis/fix remains with the assigned owner; this reviewer made no source edits or test reruns. Raw job log saved ignored at mod/run/mat2/RMAT2-ci-shard4.log. Job URL: https://github.com/zentixua/incoming/actions/runs/41856497724/job/126667983196

## Terminal CI evidence (2026-11-04 15:31:35 UTC)

Direct GitHub check-runs API for the exact full SHA now reports 21 checks, none active, and `Итог` completed with `failure`. The workflow is run `41856497724`. Raw failed job logs were downloaded and inspected; five GameTest shards failed:

| Shard | Failed test | Raw assertion |
| --- | --- | --- |
| 1/6 | `expiredworkerkeepsinputsuntilactualexit` | cancel mutated running Grid inputs |
| 2/6 | `forgottentaskandworldcontextkeepactualgridinputs` | cancel mutated running Grid inputs |
| 3/6 | `clearedpreparationkeepsactualgridinputs` | cancel mutated running Grid inputs |
| 4/6 | `cancelledpreparationkeepsactualgridinputs` | cancel mutated running Grid inputs |
| 6/6 | `detonationrunsbudgetedqueue` | Glass still present at relative 16,12,32, t=1201 |

The first four assertions share the unchanged `RuinInputLifetimeGameTests.java:95-96` guard. The last assertion is the unchanged `NuclearGameTests.java:1437`, in `nuke_pipeline` with a 1200-tick timeout. No root cause or flaky-test diagnosis is claimed. These are concrete full-CI blockers despite the green local material/ledger subset. Review remains limited to MAT2's exact diff; producer/queue integration diagnosis and fixes require the assigned implementation owner.

Ignored raw downloads: `mod/run/mat2/RMAT2-ci-shard{1,2,3,4,6}.log`. The already-running author-owned `tools/pr_ci.sh 591` watcher still had PIDs 579211/579212 and retained its stale “no checks” message after the actual checks reached terminal failure. It was not killed, replaced or modified by this read-only reviewer. The terminal API outcome and live watcher anomaly were delivered to MAT2-inbox; no review-owned heavy job exists or remains.
