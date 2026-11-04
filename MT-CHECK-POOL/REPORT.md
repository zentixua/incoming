# Executor lifecycle fixture — MT-CHECK continuation

Source `463f6cc6721c0d00f53500553764ffe3891afd34`, branch `codex/nuke-executor-fixture`, exact base `8d251a2c1`. Previous source branch `codex/nuke-core-fixtures`/5c7525454 and its evidence remain unchanged.

## Current failure and cause

CI586 runtime source820257c4a, run41833102665, failed only `resizeKeepsOnePoolAndShutdownTerminatesQueuedFutures` at its original300-tick deadline. Full original raw log remains at `/mnt/data/projects/incoming/mod/run/wt-LEAD2-NATURAL/mod/run/natural-trial/ci-820-failed-full.log`.

The fixture blocks its first worker, changes configured parallelism1→2, submits through a previously captured executor and then waits for the second callback to start. Configured count is an upper bound. In `RuinWorkers.pool`, dimension-height reconciliation can update `size` to2 while keeping `active`1; `adapt` only restores the second worker when the measured external tick cost permits it. The first blocked task therefore legitimately keeps the captured callback queued. Waiting for concurrent execution accidentally tests adaptation/recovery rather than executor lifetime.

Local initial diagnostic passed only after a logged adaptive recovery to2of2. A bounded control kept the original fixture assertions/timeout but waited25ticks at its existing game pace before resize, filling the actual preceding tick-time window without overriding any production clock/state. It then reproduced the CI timeout: configured2, size2, active1, stage1 at ticks61/121/181/241, first task started, second callback not started, inFlight2. No production defect/change is claimed.

## Narrow correction

Only the assigned method in `RuinLifecycleGameTests.java` changed (14 insertions,6 deletions). No other test or production file changed.

- Read-only reflection asserts exact underlying executor identity before and after resizing.
- The captured executor must accept its callback into the same accounting lifetime (`inFlight==2`), whether queued or running.
- The shutdown probe must actually be queued behind blocked workers.
- Shutdown must report that its real threads terminated.
- Original queued-cancellation, resolved running/queued futures, cancelled shutdown-probe future, old-executor rejection and next-lifetime accounting assertions remain.
- Original300-tick timeout, work settings, clocks and cleanup behavior remain. No blanket cleanup or new production test hook.

## Executed checks

All runs used tools/laptop_job.sh, unique `INCOMING_GPU_OWNER=MT-CHECK-POOL-20261104`, explicit SESSION_TREES for this checkout, no --daemon option, and `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 -Xmx2g`. Only batch `nuke_lifecycle_pool` ran.

- diagnostic.log: original assertions plus observations PASS1/1,25s Gradle; adaptive recovery2of2 was logged.
- pressure-red.log: original assertions with25 game-paced prerequisite ticks FAIL at300ticks; size2/active1 and queued callback observed throughout. Exit1 is expected.
- pressure-green.log: same pressure prerequisite plus fixture correction PASS1/1,20s Gradle. Still size2/active1; shutdown dropped2queued tasks and fully terminated.
- final.log: clean final committed method, without temporary prerequisite or diagnostics, PASS1/1,18s Gradle.
- git diff --check PASS. All owned jobs finished/collected, active incoming-job-mt-check-pool-* units0 at13:19:53UTC.

No GPU, desktop, Crafty, protected server, world, public release, production edit, PR or extra reviewer. The task explicitly permits this bounded fixture-only handback without redundant review. Root retains integration/CI/PR/merge; MT-S2 retains its unrelated disk-frontier production work.
