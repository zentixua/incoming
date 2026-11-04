# B7ALL diagnostic component validation

Source: f53ecbe1c3cfec751c1aa978362897a07adc2e7d, component base0f1a77ff88b4e6546212aec9ab34a834eda525d1; clean pushed branch codex/mt-natural-trial, draft PR586. Four Java files only. Never merge this diagnostic branch into main or release it. Production source, runtime, GPU and integration remain separately owned.

Local wrapper build completed successfully in4m04s with1845 unit tests, zero failures/errors/skips. compileJava/compileDevtestJava passed. Focused native GameTest mt_b7_pending|mt_b7_cutoff passed2/2 in19s with normal save/shutdown. Both commands used tools/laptop_job.sh in an owned user service; JAVA_TOOL_OPTIONS ActiveProcessorCount4/Xmx2g, Gradle workers2 and single-use --no-daemon; testForks1 for build. No GPU/client/desktop/remote server action. Exact invocation and output are in build.log/gametest.log. No build warnings from changed Java. The native run retains existing Gradle9 deprecation notices.

Fresh independent RB7ALL on explicit Sol/high reviewed0f1→f53 plus surrounding code and original raw failure evidence, without author conclusions. No blocking defect in Java delta. Review also identified private analyzer interval slicing defect A1/P2, handed to its owner LEAD4; it does not require a Java change. The optional operational stage/driver v4 delta was not included in the review's listed inputs; no approval of that delta is inferred. See independent-review.md for exact pins/limitations.

Observer-cost and whole-view runtime coverage are UNMEASURED here. Deterministic telemetry clocks prove accounting semantics, not speed. Runtime correctness/readiness, successful source remediation and actual1Mt/100kt acceptance are NOT established by these checks. No new trial was run and the previous1Mt failure remains the last runtime evidence.

The field/opt-in/coverage contract is in SCHEMA.md. A census is current server loaded tracking view, not continuous visibility or rendered geometry. Any missing/partial/cutoff/drop data precludes an exhaustive completion claim. Selected detonation ID must match the actual detonation log; preparation/strike IDs are not silently substituted.

## Exact CI: RED, not waived

[Run41889061857](https://github.com/zentixua/incoming/actions/runs/41889061857), headf53ecbe1c3cfec751c1aa978362897a07adc2e7d: required Итог FAILED; tools/pr_ci.sh586 awaited to exit1. The sole failing test is RuinFragmentCacheGameTests.cacheRefusalDoesNotBlockAnAdmittedSolver in shard6, assertion “Uncached solver has not progressed with pressure still held”. All other required jobs passed. Original failed log, full CI result JSON and waiter output are retained here.

A bounded same-source diagnostic run of nuke_fragment_optional alone passed1/1 in16s under the same4CPU2GiB wrapper and normal save/stop. This does not establish why the CI shard failed. In CI6, the new mt_b7_cutoff batch runs after the failure; mt_b7_pending is not in that shard, ruling out direct earlier fixture-state mutation by these two tests. No assertion/timeout/pressure was weakened, no existing fixture or production source was changed, and no blind CI rerun was requested. Prior-shard context, tick deadline and actual worker state remain unresolved and returned to LEAD4/MT-S5 for integrated source/check ownership. There is no green-CI claim for this exact head.

LEAD4 reported resolution of private analyzer A1 in its own scope: new SHAaef11e0c673ff429f01857a07d308d4eb65a44c236dbe37a0f5261dc0d0207a1; preserved old summarize_progress-before-A1.py, exact interval red/green + per-session isolation + original-raw UNKNOWN regressions in ignored lead2/check_progress_summary.py and lead4-progress-A1-red-green.json. Those artifacts belong to root's evidence, not this Java component. The independent report retains its original old-analyzer pin and finding; no new independent review of the corrected analyzer is claimed here.

All B7ALL owned jobs, CI waiter and reviewer have completed. No resources remain. This component is handed back clean/pushed for later root integration; the CI failure remains explicit.
