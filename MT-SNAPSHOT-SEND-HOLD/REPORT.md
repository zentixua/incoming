# Read-only diagnosis: nuke_send_hold

Exact source: `834382f68182b9594de68902fd0dfd29456fb38f` (`codex/nuke-send-hold-diagnosis`), production6eb plus actual-far-read-gate fixture. The worker153 workspace fix and all new snapshot/result guards are absent. Clean source branch05a5 remains preserved separately.

## Finding

**The CI failure was not reproduced. No production or fixture fix is established or proposed by these passing runs.** PR587 CI41846465643 GameTest3/6 timed out chunkWithheldUntilRuins (1200 ticks) with “the ruins have not yet risen” at raw line1203. The existing CI log has no target held/latch/job/future/readiness state before cleanup; it cannot distinguish the requested failure classes. Five other shards passed per owner context. No CI rerun was launched.

RawCI SHA256: `93d70ee5f0471573ed15d6ba8d2d643a350979aec23afcc94d0098160b9e4062`. Failure-world chunk from log: test origin1989647,-60,8644439; detonation116 at1989679,-48,8644471, scale0.1. Log records flash scorch13/81 at957ticks, but without target callback/latch state that alone does not prove the target solver's status.

## Executed bounded probes

Only temporary method-local diagnostics inside chunkWithheldUntilRuins; original assertions, latch semantics, timeout1200 and all production files unchanged. Wrapper jobs explicitly set SESSION_TREES to wt-MT-SNAPSHOT and ownerMT-SNAPSHOT-DIAG. JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 -Xmx2G; Gradle JVM2G/4CPU too.

1. Isolated `-PgametestOnly=nuke_send_hold`: **1/1 PASS**,2.217s test/33s Gradle, wrapperexit0. First release at elapsed2: heldtrue/latch1 before countDown, scar0/glasspresent, ready25/25, asked52/wave50, pool2active+38queued, counts[8,32,0], targetjobdue52, ctxfailed0/windowFailedfalse/32reads. Beforecleanup at elapsed223: heldtrue/latch0, scar1/glassgone(replacedfire),ready25/25,targetjobgone, failed0/reading0/windowFailedfalse; ctx8 other tasks, pool2active+4queued, counts[0,6,0]. This first observer logged repeated release checks; logs are retained, not used for a timing/performance conclusion.
2. ONE minimal prefix, actual logged order matches the two immediately preceding CI batches: `dh_front` → `grid_edge_signal_back` → `nuke_send_hold`. **3/3 PASS**,10.15s tests/53s Gradle, wrapperexit0. Final sparse probe logs firstrelease only, then each200callbacks and beforecleanup. No full shard/union was run.

Prefix target details:

- First release: elapsed1/callback2, event3, chunk[200209,-178923], heldtrue/latch1, scar0/glasspresent,ready25/25,asked1095/wave1094. Pool2active+33queued, counts[8,27,0]. Target ctx no task yet,27ownedwindowreads,windowFailedfalse.
- Elapsed199/callback200: heldtrue/latch0,scar0/glasspresent,ready25/25,target “plan in the background”. Future startedtrue/donefalse/cancelledfalse, priorityNEAR,since1208. Context6tasks/no failed,29ownedwindowreads,windowFailedfalse;pool2active+33queued,counts[0,35,0]. This observed wait is actual background work, not a held latch or missing loaded neighbourhood. It does not explain the separate CI timeout.
- Beforecleanup elapsed203/callback204: heldtrue/latch0,scar3/glassgone(replacedfire),ready25/25,targetjob/taskgone. Context4othertasks/no failed,29reads,windowFailedfalse. Pool2active+30queued/counts[0,32,0],completed2016. Nuclear realclock unitCost0,units155,used1,286,910ns;lastscar1,152,017ns. Work remaining elsewhere is captured before NuclearStrikes.clear.

Complete raw states include all queue sizes, neighbour/job/ask fields, context source/read/cache counters, target Future execution flags, worker counts/threadstacks, budget class/estimates/deadlines and last lane timings. No budget, heap, timeout, assertion or worker setting was relaxed. The200tick send fail-open remains the existing source behavior; these passes do not assert a new zero-pop gate or timing contract.

## Handback and next useful capture

Both owned jobs exited0 and were waited. Final sparse terminal-state `probe.patch` is saved for the next already-required root union/shard (not a request for a blind rerun). It dumps before latch release and NuclearStrikes.clear even on timeout. Root retains final resolution/source ownership and separately owns syncplanWithWindow/adaptation pump fixes. No production bug or fixture deadlock was reproduced; do not attribute this old-worker CI failure to153 from these data.

Only my temporary method probe was restored with git restore. Diagnostic branch clean at exact834; source component branch remains clean05a5. No additional source commits, agents, review, fullbuild/union/CI, GPU, remote/real server or played-world operation.
