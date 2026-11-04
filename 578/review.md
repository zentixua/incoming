# RASYNC2 independent code review

Reviewed SHA: `7779f488a41406bc82d83d9d09c6b18e279b618d`  
Parent: `b80860172ebe6b32f557b4b39d9d0412a9863f3b`  
Tree: fresh detached review checkout at the reviewed SHA.
Reviewer: fresh Codex session RASYNC2, gpt-6.1-sol, xhigh, 2026-11-04.

## Verdict

No serious defects found in the exact six-file patch by source inspection. No blocking findings. This is a code-review result, not a runtime performance or release-readiness certification.

## Reviewed behavior and dependencies

- `ScarQueue.java:888-916,940-1036,1159-1256`: stale prepared plans are discarded; stale collected/parked plans call `RuinContext.retry`; subsequent inspections use window acquisition, worker admission and background collection. There is no remaining `RuinPlanner.plan`/`blastsReady` or direct solver call in ScarQueue. A failed collection returns to the queue rather than writing a scar mark. The mark remains after successful application only (`1235-1237`). Memory refusal keeps the plan parked and yields the lane.
- `RuinContext.java:611-627,651-733`: successful admission clears the previous failure marker; retry clears planned/done-time markers and protects the pending window. The next grid validates live snapshots through `shot`/`ChunkShot.current`, and cached fractures require matching snapshot serials. A cancelled expired attempt remains in `tasks` until `RuinWorkers.finished` permits collection; no replacement for that key is admitted while that attempt is retained.
- `RuinWorkers.java:276-331`: a cancelled running FutureTask remains uncollectable while its execution slot is counted; `Task.run` releases that slot in finally. Queued cancellation removes queue ownership and releases the count. Priority/promotion behavior and admission limits are otherwise unchanged.
- Traced `NuclearPrep` harvest/far collection, `NuclearWorld` expiry/unload/idle cleanup, `ChunkShot` validation, `RuinContext.Grid` snapshot/settings capture, `Collapse.solve`, `RuinPlanner.finish`, and `RuinPlan.current/apply`. Background and former synchronous planning invoke the same solver/completion functions with captured settings. Pressure timing, send deadline, material capture, replacement-neighbour checks and packet formats are unchanged. This supports equivalence for the same validated input window; it does not guarantee completion during continuous player edits.
- Read the changed stale-plan GameTest (`RuinBackgroundGameTests.java:1342-1388`), both cancellation unit tests (`RuinWorkersTest.java:56-116`), and the existing background/server parity and background-call-graph checks. The stale-plan fixture holds worker admission, asserts no synchronous replacement/scar, then releases admission and asserts removal and a scar.

## Optional minor finding

**P3 — Existing overview still describes the removed fallback.**

Locations: `.claude/rules/nuclear-world.md:24-25,49-50`; related unchanged comment `NuclearWorld.java:445`.

Reproduction: read these lines at the reviewed SHA and search for `staleBackgroundPlanRebuiltOnServer`. The overview still promises server-thread reconstruction and names a removed test, while the new rule at `nuclear-world.md:425-432` and implementation require background retry. Update the old overview/test reference and comment to match. This is documentation consistency only and does not block the patch.

## Validation and limits

- Checked exact parent-to-commit diff, all six changed files, applicable TEAM/AGENTS/area rules, relevant source/tests and live process ownership. `git diff --check 7779f488a^ 7779f488a` passed.
- No PR description, author conversation/conclusions, previous review reports or author evidence verdict were read. Raw old trace was not needed or inspected.
- No Gradle build, unit/GameTest execution, CI invocation, JFR/performance analysis, GPU/client/server/world actions, production edits, PR comments or merge. MT-L2 CPU quiet was respected; this review needed no heavy job.
- Runtime failure/timeout-to-retry integration, repeated staleness under load, the block-damage-disabled queue path, actual cancellation latency, real-city MSPT, total memory, save/reload and cross-mod runtime behavior were not newly exercised here. The added cancellation assertions cover queued and running `cancel(false)` at Task level; they do not themselves exercise `RuinContext.expire` with `cancel(true)` and retained window ownership.
- No owned jobs, clients, servers or GPU leases were created; nothing remains to wait for or release. Production checkout remains clean at the reviewed SHA. Implementation/integration ownership remains with LEAD2.
