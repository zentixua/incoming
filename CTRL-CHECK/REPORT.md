# CTRL-CHECK: held paid-review fixture

Source base: afaefd7238b6ed14e2ab4a6393017f2349687762.
Final fixture: 25f48055d (650507c37 plus review cleanup correction), branch codex/nuke-paid-commit-check.
Scope: only paidCommitSchedulesOnlyOneStrike in NuclearReviewGameTests.

## Exact observation

Original CI PR589/run41849166254, shard2, raw line1690: valid request not dispatched. The immediately preceding batch was fuel_fire_rain.

1. Instrumented isolated nuclear_review_launch: 1/1 PASS,24s Gradle. BEGIN true, READY0/0/NONE throughout, visibility20000 throughout, accepted payment and one schedule.
2. Instrumented actual minimal prefix fuel_fire_rain|nuclear_review_launch: 2/3 PASS,22s Gradle; same paid-review assertion RED. BEGIN true and READY0/0/NONE throughout. Captured review visibility5000; current visibility20000 by tick60. COMMIT false, review cleared, incoming.nuke.review.changed emitted. This establishes the e.visibility != visibility(p) invalidation branch, rather than preparation/payment/worker refusal.
3. Same prefix with final fixture-only source: 3/3 PASS,2.902s tests/26s Gradle, wrapper exit0. Exact source compile included. No temporary probes remain.

ServerLevel.setWeatherParameters switches weather flags; residual interpolated rainLevel fades afterward. The paid-review fixture previously started during this fade. Production correctly invalidates an exact review on changed visibility. Final fixture establishes clear flags and rain/thunder levels BEFORE request and restores original flags/timers/levels after completion. BEGIN is asserted. Existing one-charge/one-schedule/duplicate rejection assertions remain; duplicate inventory and no refund on accepted launch are also asserted. Timeout remains100, held delay65, production HOLD_TICKS60 unchanged.

## Reproduce

Use tools/laptop_job.sh --light with explicit SESSION_TREES and INCOMING_GPU_OWNER. All runs used Java21, --no-daemon, --max-workers=4, -Dorg.gradle.jvmargs="-Xmx2g -XX:ActiveProcessorCount=4", ignored limited.gradle applying -Xmx2g/-XX:ActiveProcessorCount=4 to run JVMs. Command from mod directory:

    ./gradlew --no-daemon -Dorg.gradle.jvmargs="-Xmx2g -XX:ActiveProcessorCount=4" --max-workers=4 -I run/ctrl-check/limited.gradle runGameTestServer '-PgametestOnly=fuel_fire_rain|nuclear_review_launch'

terminal-probe.patch contains the method-only diagnostic against base. probe2.log is isolated; prefix-red.log and prefix-green.log form paired evidence. probe.log preserves an initial diagnostic-only compile error (LOGGER spelling, corrected to existing Incoming.LOG before any successful probe). All jobs were awaited/collected; no GPU/client/Crafty/protected-server operations. No standalone fullbuild/PR/CI for fixture-only component per assigned bounded task. LEAD2 owns final combined CI/merge, which is not claimed here.

Final exact source25f48055d repeated the same prefix after the minor review correction:3/3 PASS,3.130s tests/24s Gradle,wrapperexit0. Independent review is preserved in REVIEW.md.
