# RMT-ARC independent review

Reviewed 2026-11-04, completed at 16:45 UTC. Reviewer: Codex, fresh gpt-6-astra/high session (the running RMT-ARC process explicitly selected both).

- Base: `f9d1ac8ee13f612f7774d9a5e72dfb6a4e3c96e0`.
- Head: `9b93e305dd90e6f7d2928373fcf60000437b9969`.
- Worktree: `/mnt/data/projects/incoming/mod/run/wt-MT-OPT`.
- Branch: `codex/nuke-archive-progress`; clean at inspection and completion.
- Scope: the three-file diff (NuclearPrep, new RuinArchiveProgressGameTests, nuclear-world rule), with surrounding cache, storage, worker and accounting code. This is not a repeat review of the earlier producer core.

## Serious finding first

### P2 — full resident headroom is not the complete admission cost of a plan spill

Changed location: `mod/src/main/java/ua/zentix/incoming/nuclear/world/NuclearPrep.java:616` (also the restored-plan pump at line 863). The new `storageRoom` at lines 555–558 supplies all `PLANS_CAP - residentMemory` to `RetainedPlans.pump`.

`RetainedPlans.pump` (lines 83–97) checks and subtracts only `R = max(4096, 3 * plan.bytes())`. It then both charges that R through the preparation counter and adds `plan.bytes()` to `writeBytes`, plus a new write entry. `RetainedPlans.bytes()` (line 57) includes this additional write ownership and 48 bytes per entry; both are included in `NuclearPrep.residentMemory` through the live preparation/context. The local room variable is not reduced for them.

A concrete source-level counterexample: let a registered plan have B bytes, R = 3B, and fixed other ownership leave exactly R bytes of resident headroom. Hold the asynchronous write before its completion refund. The changed call admits it, and known resident accounting becomes `PLANS_CAP + B + 48`. Several pending writes accumulate the unchecked B terms. With that same near-cap state the previous future-worker/transition-based supplier rejects the write. The newly widened admission therefore needs a matching fix in the consumer's complete charge, rather than assuming its input room already bounds every increment.

This is a violation of the actual admission counter used for the 512 MiB limit, not a measured JVM heap/OOM claim: the writer also retains references to an already resident plan, and some of the accounting is conservative overlap. It can nevertheless admit above the stated known-ownership cap and close subsequent material/worker admission until receipts drain. No independent runtime reproduction was launched, as required by the review scope.

Required correction: admit/debit the complete additional counted writer ownership (or safely consolidate overlapping owners), including across successive writes in one pump. Add a production-owner regression with fixed cross-dimension pressure and a held write receipt at this boundary; assert the cap before the receipt refund, and release/progress afterward. Merely retaining the existing snapshot test does not cover this path. The same boundary audit should include receipt/map metadata (snapshot writes add a counted 48-byte entry too).

**Disposition:** request this bounded-accounting correction before treating this diff as cleared. No other serious finding established in the scoped changes.

## What the change does correctly

The snapshot deadlock mechanism is real in the source. `RuinContext.trimShots` cannot discard an unarchived original until `RuinSnapshots.evictable` obtains an exact serial-matching successful write receipt. Previously the archive supplier used `memory(level)`, which includes future worker capacity plus 64 MiB transition room, and can report no room even when no solver owns that workspace. Using resident ownership allows the existing original to reach disk and be reclaimed instead of waiting for unrelated ownership to disappear.

The new supplier still counts live/retired worker workspace, detached inputs, result ownership, preparation I/O, material reservations and registered contexts across dimensions. It is not an unconditional spill allowance. Actual worker startup remains separately charged by the worker service. Snapshot serial checks, failed-write rejection, pending-window/task pins and disk/source validation are unchanged. Async spill accounting still belongs to NuclearPrep's lifetime counter, including restored contexts; this diff does not replace it with a context-local counter that vanishes on detach.

The rule correctly identifies the future-reservation circular dependency. Its 512 MiB statement should continue to be understood as estimated tracked ownership, not the entire JVM heap; later existing rule paragraphs already state that limitation. The finding above concerns that tracked estimate itself.

## Test and raw evidence

`archive-red4.log:114,125–129`: fixed external owner 530,276,862 bytes, resident 532,676,608, future admission 626,327,552, original 17,006, resident room 4,194,304. At callback 200 the original remained cached, pending writes were zero, and the required test failed. This is the expected refusal before a write starts, rather than slow disk I/O.

`archive-green4.log:114–119,144`: identical reported pressure/input sizes, exact original recovered after three callbacks, sampled peak known ownership 532,727,674, one required test passed, BUILD SUCCESSFUL. The contrast supports removal of this particular archive-admission obstruction without refunding the synthetic external owner.

The preserved `archive-red4-test.java` is not byte-identical to the final head test. The final test uses an isolated detonation ID 150101 and restores the previous progress archive string on cleanup. The central pressure/trim/exact-payload assertions are retained. The raw logs themselves contain no source SHA or compiled class hash, so their provenance is the supplied evidence association, not an independently authenticated exact-head execution.

The final fixture uses the real production restore supplier, real region-page write/read, a registered world context refreshed on each callback, game-speed async waiting and a counting work clock. Its dedicated batch and cleanup restore the cross-dimension reservation, forget the registered context, restore the previous archive string and stop the test pool. It avoids the earlier ID-100 fixture overlap. I found no separate serious isolation defect in this test.

Coverage limits: it exercises one unpinned snapshot in a reopened archive, not a normal flight preparation with queued retained-plan spills, multiple competing cache owners, near-zero write headroom, failed I/O, or restored-plan pressure. Its direct archive read verifies the serialized original; it does not exercise the production read-reservation/publication pipeline, clean process restart, plan application, material drain or far settlement. Peak ownership is sampled after trim callbacks, not continuously during asynchronous encoding. The 4 MiB slack is much larger than the roughly 51 KiB write charge; it cannot catch the plan-writer boundary above. Existing `nuke_plan_material` storage coverage uses a separate AtomicLong and a constant 64 MiB supplier, so it does not close this global admission gap.

At the final evidence check, `archive-final.log` had reached `:test` and had no build or GameTest terminal result. The MT-S3-owned wrapped validation process was still running; I neither launched nor modified it. Its eventual result is the author's follow-up. No CI claim is made. `git diff --check` on the exact base/head passed.

## Actual observation: causal meaning and limits

Read only the supplied `wt-LEAD2/mod/run/lead2/ownership-probe2/lead2-ownership2.txt` and `wt-MT-OPT/mod/run/mt-s3/probe/OwnershipAgent2.java`, not narrative reports.

The snapshot records an idle pool (one resident thread, zero active/queued, 19,865 completed), 435,036,688 bytes of snapshots, and 1,247 unpinned unsaved/different-serial originals totaling 113,013,570 bytes. All unpinned originals were in that unsaved category; none already had an exact successful receipt. Thus a substantial reclaimable class first needed writes, consistent with the corrected source dependency. The context also retained 348 pending windows and six completed task results; the result leases explicitly show exited=true. Worker idleness alone is not proof that retained task/result or pinned input ownership should be refunded.

The probe does not call `NuclearPrep.memory`, `residentMemory`, `RetainedPlans.get`, or mutation/admission methods. It observes existing attachment fields/maps on the server thread and uses `getChunkNow` for selected loaded masks. This avoids triggering demand reads or ledger admission through the observation itself. The printed snapshot took 48,207,066 ns: it adds roughly 48 ms of server-thread observation cost and must not be used as an unperturbed tick benchmark. Background atomic/future values can still change during the traversal.

This is one state sample, with no source SHA/jar hash in its contents, no measured denied-write headroom and no post-fix counterpart. It supports the presence of unpinned originals awaiting archive ownership, but does not by itself prove that this is the only cause of the real stall or that the patch drains all pinned windows, material and far ruins. Pending-window presence/freshness and successful result consumption are separate questions. The synthetic red/green test establishes the isolated causal mechanism; integrated runtime acceptance remains with the author/root integration owner.

## Independence and handoff

Read TEAM, newest live entries, repository AGENTS/applicable rules and canonical MEMORY index only. Did not read author conversation, PR body, STATUS, BOARD conclusions, prior reviews, narrative task reports/checkpoints or earlier inbox contents. No source changes, local test/server/GPU jobs, PR operations or external messages. Implementation/merge follow-up remains MT-S3's; root integration remains separate.

В память: the durable boundary is that widening a headroom supplier requires auditing every counted increment in its consumers, and a snapshot-only red/green test does not establish plan-spill cap safety. Review evidence and this boundary are preserved here; task-specific canonical memory was not read or changed during this read-only assignment.
