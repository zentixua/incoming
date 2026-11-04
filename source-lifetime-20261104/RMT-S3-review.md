# RMT-S3 independent source review

2026-11-04 16:08 UTC. Reviewer: Codex, fresh gpt-6-astra/high session RMT-S3 (verified in process arguments).

## Serious findings

None found in the assigned fixture/rule correction. No serious gameplay runtime defect is established by this delta or the supplied native red/green pair. This is a scoped source-review result, not runtime or main acceptance.

## Exact scope

- Worktree: `/mnt/data/projects/incoming/mod/run/wt-MT-OPT`.
- Branch: `codex/nuke-producer-budget`.
- Reviewed exact diff: `ee0cd0f96c1d762f59477c5a6e3990090b36192c..4b0aacb2ace682169f1f35f7e29f2a8e22742724`.
- Only `.claude/rules/gametest.md` (+5) and `mod/src/devtest/java/ua/zentix/incoming/nuclear/world/RuinSourceProgressGameTests.java` (+13/-8). No production changes.
- HEAD remained the requested 4b0aacb2a; worktree clean; `git diff --check` passed. MT-S3 remains source owner. No old producer-core review was repeated.
- Read current TEAM/context/BOARD/live, repository AGENTS and relevant GameTest/nuclear/world/chunk/work rules. Read surrounding lifecycle, worker submission/input ownership, source publication and cleanup code, and the supplied raw probes/logs.
- No source mutation, build, heavy job, GPU/client/server operation, PR description/comment, author's session conversation/checkpoint/inbox/report, or acceptance action.

## Behavior and lifetime

At `RuinSourceProgressGameTests.java:58`, normal `world.ruins(level, d, level.getGameTime())` replaces reflective map insertion. `NuclearWorld.java:123-128` restores any applicable archive and timestamps `ctx.used`. The previous constructor plus raw insertion left it zero. `NuclearWorld.java:144-150` sweeps each 100 ticks, cancelling contexts older than 600 ticks without pending scar work. These synthetic IDs have no such scar job.

The preserved red-native trace shows the actual chain: admission at world790 with used0; callback800 still registered; cancellation stacks originate from `NuclearWorld.sweepRuins`; callback801 is unregistered/detached; callback803 attempts `submitPlanWorker` and hits `RuinInputs.acquire`'s detached-owner guard. This is not merely a timeout correlated with pressure. The guard is correct for an already detached owner and is unchanged.

Normal inspected consumers reacquire/touch the context through `world.ruins`: ScarQueue background polling/planning, FlashScorch.work, and RuinPlanner entry points. Preparation handoff explicitly sets `used` before insertion (`NuclearWorld.java:310`). The supplied failure therefore does not demonstrate that gameplay resubmits the expired synthetic owner. This bounded review does not certify every possible runtime lifecycle.

## Clock, pressure and assertions

- `setupTicks=700` at line27 makes the native world old enough to expose used0 even in an isolated test. The original 600-tick execution timeout is unchanged. There is no game-time assignment or production clock override.
- Line81 waits for world tick modulo100 >=90 before admission. Lines111-112 require the original registered identity before draining or resubmitting. Lines136-137 defer success until strictly after the following sweep boundary; the strict comparison matters because the GameTest callback at tick800 precedes the observed world sweep.
- The context is registered when the test body starts and has no need for a fabricated periodic timestamp refresh: the fixture must finish within its original 600-tick lifetime. The fix does not suppress the sweep, skip an expired-context assertion, or weaken the detached guard.
- Cold worker pool, limited aggregate admission (0 < pending <75), one worker restart allowance, fixed other-dimension reservation, sampled resident cap, no source chunk loading, no I/O-failure conversion, all read ownership drained, and three nonempty full serialized physical/material results remain checked. The added clock/identity assertions do not refund external pressure.
- In the native pair both runs admitted 8 reads with cold pool0, startup room16777216 B and fixed external owner484156928 B. Green kept used746/registered=true/detached=false across800/801 and completed all three plans at812 in23 callbacks, sampled known peak533364736 B (<536870912 B cap).

## Cleanup

Lines43-47 register cleanup before creating request contexts. `world.forgetRuinsForTest` removes each registered owner and invokes `cancelTasks`, which closes the cache/storage, detaches input accounting, cancels result owners and invalidates pending reads. An already removed context is a no-op because the world sweep already cancelled it. Worker shutdown and restoration of the original other-dimension reservation remain on both success and ordinary assertion/timeout failure through `StrikeGameTests.afterTest`.

This change does not add a source-region deletion or alter unrelated world/config settings. The native green log's three detach stacks are testPassed cleanup, not premature expiry. The diagnostic pair still uses the older direct cancel/remove cleanup; the final helper replacement is assessed from source, not misrepresented as executed by that probe.

## Evidence distinctions and limits

1. `mod/run/mt-s2-handoff-failed-shard3.log:1218-1234`: original shard failure, admission8/room16777216/fixed484156464 B; exception in RuinInputs.acquire via this fixture. It lacks the native probe's precise sweep/used trace. Its external reservation differs by464 B from the local pair; these are not identical machine runs.
2. `mod/run/mt-s3/red-native.log:151-259` plus `red-native-probe.patch`: native setup delay and boundary alignment, read-only lifetime logging and cancellation stack. Actual world sweep detaches at800; same detached-input exception at803; exit1. This establishes the local causal reproduction.
3. `mod/run/mt-s3/green-native.log:151-279` plus `green-native-probe.patch`: same native timing/probes, replacing context creation with timestamped world registration; full physical output passes; cleanup follows success; exit0. The patch retains redundant map put and older cleanup. It is the diagnostic pair, not the exact final 4b0 source and not integrated CI.
4. `mod/run/mt-s3/red.log` plus `red-probe.patch`: assigns gameTime699 inside an already running test. Only callbacks699/700 occur, separated by about30 seconds; then the test fails for incomplete progress and testFailed cleanup detaches. It does NOT demonstrate sweep-triggered detachment and must not serve as the causal red control. Native setup delay avoids this clock/deadline interference.
5. The final timestamp check proves crossing the scheduled sweep time, not an instrumented invocation counter. `NuclearWorld.tick` can return before sweep while ledgers are unready. The supplied native red trace proves invocation in that reproduction; the final regression assumes normal loaded GameTest ledgers. It also does not force a decoder to remain outstanding exactly at the boundary on every machine (the native pair did have pending work). These are coverage limits, not identified serious defects in this correction.
6. Resident-memory peaks are samples of known ownership, not a JVM peak or exhaustive between-callback bound. Existing succeedWhen assertions are retrying checks; this delta does not turn them into persistent records of every transient violation. No new claim of complete producer/heap correctness is made.
7. No tests or final CI were run/waited by this read-only reviewer. Author-owned validation, integrated runtime and main decisions remain outside the assignment.
8. Independence disclosure: the required shared-memory read of `nuclear-fixture-lifetime.md` unexpectedly included an appended MT-S3 synopsis of this task. Thus this is a fresh independent reviewer session, but not fully blind to the author's conclusion. No author report/session/PR text was opened; all findings above were independently checked against the exact diff, surrounding code and raw evidence. This exposure is recorded rather than claiming an uncontaminated blind review.

## Raw evidence SHA-256

```
4fc7de06d63c4099f1393cb3c871f85efb939c3cfee9d7b481b00047c2c0239f  mt-s2-handoff-failed-shard3.log
72347d820e30f4f62bf72e5ce419f21f1f400a39827c6073077f748d11bec9a1  mt-s3/red-native.log
604278858ab33a10354a122a7f5e311ee423b0cece13ea1907f58a627b00887a  mt-s3/green-native.log
9461509e877990f0057b206de78cf864bf8cc71353bf9c69a4b1660232711db0  mt-s3/red-native-probe.patch
0470704c2926f3458ca09f13c8e65f37a2e4bb53cdb59d8f4895618f4f67ea37  mt-s3/green-native-probe.patch
03434bf6a56e7221c283fa5feae93bbdc1507b951639269bf02ff88a08376ae4  mt-s3/red.log
4fb3791dce1df4a38b06c50c3ffbc08c1015e4b10bf786bb194481b130133389  mt-s3/red-probe.patch
```

## В память

Appended the independent review's evidence boundaries to canonical `nuclear-fixture-lifetime.md`: natural setup versus in-test epoch jump, scheduled-boundary versus observed-sweep proof, diagnostic pair versus exact final source. No runtime/main acceptance recorded.
