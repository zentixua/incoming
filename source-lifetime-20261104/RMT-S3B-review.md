# RMT-S3B independent fixture review

## Serious findings

None found in the scoped delta. The fixture now acquires the world-owned context through the timestamped lifecycle API, preserves the existing pressure/output checks, and uses the matching removal API for cleanup. This is a fixture/rule review only; it does not approve the producer core, integrated runtime, main, or a release.

## Exact scope and independence

- Base: `ee0cd0f96c1d762f59477c5a6e3990090b36192c`.
- Head: `4b0aacb2ace682169f1f35f7e29f2a8e22742724`.
- Worktree: `/mnt/data/projects/incoming/mod/run/wt-MT-OPT`; branch `codex/nuke-producer-budget`. HEAD matched the requested head and the tracked working tree was clean at both inspection points.
- Only changed files: `.claude/rules/gametest.md` and `mod/src/devtest/java/ua/zentix/incoming/nuclear/world/RuinSourceProgressGameTests.java` (18 insertions, 8 deletions). No production changes in this delta.
- Reviewed the diff and relevant source around `NuclearWorld`, `RuinContext`, `RuinInputs`, `RuinWorkers`, `NuclearPrep`, the storage fixture and GameTest cleanup/pacing helpers. Read TEAM, newest live entries, repository/area rules and the canonical MEMORY index only.
- Did not read author conversation, PR body, STATUS, BOARD conclusions, task reports/checkpoints/inboxes, the two excluded task-memory files, or prior review. The mandatory index does contain brief task summaries; no linked task memory was opened or used as evidence. The changed rule's explanation was treated as a claim to verify against code and raw evidence.
- No tests, heavy jobs, GPU, server/client, source edits, commits, or PR actions. Existing process inventory was read only. The author retains ownership.

## Source assessment

1. **Lifetime:** `NuclearWorld.ruins` (lines 123–128) obtains/restores the owner and sets `ctx.used = now`. `sweepRuins` (144–152) runs on multiples of 100 and removes contexts unused for more than 600 ticks unless the scar queue owns pending work. Direct fixture insertion previously bypassed this timestamp. The new call matches the source API used by `ScarQueue`. The added identity assertion occurs before draining/re-requesting/submitting work, so a detached fixture cannot silently proceed as though still world-owned.
2. **Clock:** `setupTicks = 700` advances normal setup before fixture construction; the final source never sets/restores world game time. Admission waits for phase 90–99 of the existing 100-tick sweep. The final strict `now > nextBoundary` check avoids passing in the GameTest callback at the boundary before the post-tick sweep has had its turn. The original 600 execution-tick timeout remains. The context is created when the test starts, so the setup delay does not age the newly registered owner by 700 ticks.
3. **Pressure and output:** The 48 MiB starting allowance, zero-resident-worker prerequisite, partially admitted real disk reads, worker-startup allowance, fixed Nether reservation, sampled global cap, three full stored-plan comparisons (including material), zero pending reads, and no live cold-source chunks are retained. Registration does not refund the external reservation. Completed result leases remain owned until cleanup; direct `resultNow()` does not transfer/refund them during the pressure check.
4. **Cleanup:** The listener is registered before creating request contexts and runs on success/failure/timeout. `forgetRuinsForTest` removes the registered context and calls `cancelTasks`, which closes the optional cache, detaches inputs, cancels result owners and invalidates reads/storage. Worker shutdown and restoration of the original other-dimension reservation remain after this. If a context was already swept, the lookup is absent because the sweep already cancelled it. No new cleanup bypass was found.
5. **Rule:** Its zero-timestamp / idle-sweep explanation agrees with the implementation and the native red trace. The phrase about crossing a sweep describes this bounded fixture, not a general guarantee that all arbitrary world-time boundaries execute nuclear work.

## Raw evidence, independently interpreted

- `mod/run/mt-s2-handoff-failed-shard3.log`: checkout line 122 identifies GitHub merge commit `eb3ac2c`, merging the requested base into `056c8424e2752702dc23621c01e6b7c34e1bac9c`, not a standalone exact-base run. Lines 1218–1234 show the source-progress batch, 8 admitted reads, 16,777,216 bytes left, fixed external owner 484,156,464, then `RuinInputs.acquire` throwing `Ruin inputs already detached` through the fixture's `submitPlanWorker`. This establishes the failing caller; alone it does not identify who detached it.
- `red-native-probe.patch` adds native setup delay, sweep-phase alignment and observational lifecycle/stack logs to the old raw-map fixture. It does not change the producer algorithms. `red-native.log` lines 151–185 show admission at game time 790, all `used=0`, and pending work at 800. Lines 186–242 identify `NuclearWorld.sweepRuins -> cancelTasks` as the detach origin. Lines 244–252 show the same objects unregistered and detached at 801–803; lines 254–259 show the subsequent acquisition exception. This supplies the missing causal chain.
- `green-native-probe.patch` differs from that native red probe by obtaining the context through `world.ruins`; it still has the redundant map insertion and old direct cleanup, unlike the final exact head. `green-native.log` lines 151–154 records the same 8 reads, 16,777,216 remaining bytes and fixed external 484,156,928. Contexts have `used=746`; lines 183–189 show them registered/not detached at 800 and 801, with real reads still pending. Lines 221–224 show all three plans completing at 812 after 23 callbacks, sampled peak known ownership 533,364,736 bytes and unchanged external ownership. Subsequent detach stacks go through `testPassed` cleanup, followed by one required test passed and `BUILD SUCCESSFUL` (279, 304). This supports the registration correction, but is not a run of all final-head assertions/cleanup.
- `red-probe.patch` instead sets the shared world game time to 699 and later restores it. `red.log` lines 113–129 show only two callbacks (699, 700), failure with 0/3 complete, then detachment through `testFailed` cleanup. It does **not** show idle-sweep detachment or the detached-input exception. This experiment perturbs the GameTest time domain and cannot serve as the native red reproduction; the native pair above can.

## Evidence limits and nonblocking observations

- The native logs are instrumented probes, not an exact `4b0aacb2` validation certificate. No local rerun, CI review or main/runtime acceptance was performed in this assignment.
- Advancing past a multiple of 100 is an opportunity for the real sweep, not an explicit execution counter. `NuclearWorld.tick` can return before the sweep while ledgers are unavailable. The native red trace proves actual sweep execution in that reproduction; the final fixture does not independently assert that prerequisite. Also, a faster run may finish all reads before the boundary and hold completed owners until it passes. Neither weakens the retained pressure/full-result assertions, but do not claim it always holds unfinished reads across a sweep.
- The unchanged cleanup does not assert that `RuinWorkers.shutdown()` returned true or that every ownership counter returned to its baseline. Source inspection found no new cleanup defect; the supplied green probe shows successful cleanup entry, not a comprehensive leak certificate.
- Ownership numbers are admission estimates sampled by the fixture, not measured whole-JVM heap peaks. One small three-window run does not establish real-map throughput, all asynchronous interleavings, long-lived idle renewal, or general producer lifecycle safety.

## В память

Independent durable evidence boundary recorded separately: changing world game time inside a GameTest can confound the test clock; distinguish failure-cleanup detach stacks from production sweep detach stacks. No excluded task-memory file was read or modified.
