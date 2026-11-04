# RPROD-S2 independent source review

Reviewed: 2a42059da..4e1f8f6021c10d586758f30639efec6c0de7fe17.
Reviewer: fresh Codex GPT-6.1 Sol / xhigh session, wt-RPROD-S2, detached exact head. Initial worktree was clean at 7bdb5b2c1; switched only after checking cleanliness and live processes. Review finished 2026-11-04 14:49:22 UTC.

Verdict: **one P2 blocks the affected admission change**. No P1 established. This is a read-only source review, not integration/runtime acceptance. No source/fixture edits, new agents, tests, full build, CI, server, GPU job, merge or external publication were performed. Author handoff, conversation and PR #589 description were not consulted. TEAM/BOARD/live/context, repository rules and the canonical fact index/relevant memory were read for routing/scope; technical conclusions below come from the pinned code and supplied raw evidence.

## F1 — [P2] Preserve a fundable decoder when admitting window reads

Primary changed location: `mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinContext.java:335` (especially line 336). The new condition spends all resident headroom on WINDOW_READ_BYTES, without leaving a worker restart allowance or a publication allowance.

Supporting source:

- `RuinContext.java:297`: each window inbox slot costs 4 MiB; limit 32 is per context, not server-wide. `requestWindow` lines 326–347 can open these slots before any solver is submitted.
- `DiskShots.java:156`: parsing, including the absent-chunk case, uses `thenApplyAsync(..., RuinWorkers.executor(priority))`.
- `RuinWorkers.java:403`: the captured executor only calls `execute`; it does not service worker birth. A factory invocation outside the BIRTH_ROOM scope returns null (`Workspace.thread`, line 171). Therefore a cold pool can accumulate queued parse callbacks with zero workers.
- `RuinWorkers.java:260` and `:267`: server servicing has exactly `PLANS_CAP - residentMemory` available. At H=384 a single thread requires `48*48*384*15 = 13,271,040 B`, greater than 4 MiB.
- `RuinInbox.java:31`, `:50`: the read slot is charged at `expect` and refunded only at collection/invalidation completion. An unfinished parse has nothing to poll.
- `RuinContext.java:376`: even a ready nonempty head now needs another positive publication reservation before its read slot can be refunded.
- `RuinContext.java:968`: expiry handles submitted solver tasks, and skips queued tasks; it does not cancel the queued parse callbacks. `NuclearWorld.java:144` keeps a context while its scar jobs remain pending.

### Bounded reachable state / reproduction for the owner

Start with loaded empty ledgers, no moving material, no releasable snapshots, and a new or idle-expired ruin pool with zero live threads. Keep four ordinary detonation contexts alive through pending loaded-chunk scar jobs. Ask for disjoint disk-neighbour windows before the next birth-service pass; two separated window centres per context suffice to reach each context's 32-read limit. Do not run a solver or inject an external material reservation. Release the source I/O completions on the ordinary asynchronous route.

Let C=512 MiB, B be the small fixed resident estimate before those reads, and R=4 MiB. The current check permits N=floor((C-B)/R) slots across the contexts, leaving 0 <= C-B-NR < 4 MiB. For an otherwise empty three-dimension server, four contexts' aggregate capacity of 128 slots is sufficient (normally 127 slots after the empty-ledger charges). The loaded centre chunks themselves are outside this estimator; no large payload fixture is needed.

All completed I/O callbacks can now be queued for decoding, while zero worker threads exist. Repeated `adapt`/service calls cannot buy the 13,271,040-byte first worker from less than 4 MiB. No parse completes, so no inbox slot can be collected/refunded. There is no material or cold snapshot to drain. Request retries preserve the same state, and pending scar jobs prevent idle-context retirement. This is a circular ownership dependency; it needs an unrelated cancellation/unload or an external owner release to recover.

This regression does not depend on a demanding near-wave deadline, 1 Mt, or zero-pop requirements. It can prevent ordinary pending ruins from progressing at all. The previous `memory(level)` gate retained worker/transition headroom; switching to resident accounting needs an explicit progress allowance. `submitPlanWorker` already reserves one thread at line 871, but that protects solver-output admission only, after these disk inputs have decoded.

Minimal correction direction: keep enough globally accounted room for at least one decoder and its publication path when opening window reads, or transfer/reconcile an already owned read allowance without requiring unfunded growth. Preserve the current ready-head ownership and MEMORY-vs-source-error distinction. Add a bounded cold-pool/queued-read regression that progresses without manually returning an external reservation. The current snapshot-pressure tests deliberately restore external headroom, so they cannot detect this dependency.

No runtime reproducer was launched: the task explicitly restricted this session to source/evidence review. The failure is derived from the above control flow and the admission arithmetic; an owner-side bounded regression is the next step.

## Other inspected seams

No additional serious defect was established in the following inspected paths; these are source conclusions within this diff, not a total-heap proof:

- `RuinResults` reserves plan + private fragment + owner allowances before submitting a solver. Mark/shrink precedes Future publication; failure, cancellation, unbound-before-submit, successful-but-uncollected output and EMPTY have distinct terminal handling. Temporary fractures remain charged through actual exit, including early counted-consumer transfer.
- `NuclearPrep.residentMemory` and `memory` include the numeric global result counter once outside the dimension loop. Detached input ownership remains separate. No global collection of worlds/contexts was introduced.
- `RuinContext` waits for `RuinWorkers.finished`, not only Future.isDone, before collection/replacement; cancellation and forget retain actual consumer ownership. Promotion unbinds the old cancelled Future, transfers the exit callback, and rebinds the replacement. FlashScorch uses the owner-aware submission/cancellation route.
- Successful collector handoffs charge `RetainedPlans`, `ScarQueue.parked`, `RuinContext.flashed` or applied-plan ownership before dropping the producer charge. Flash discard exits retain/release the lease appropriately. Detached/store/restore plans do not copy producer ownership onto copies or EMPTY.
- The result bound was checked against the actual producing domains in Collapse.collect, RuinPlanner.finish/land/Sections.finish and Blast.run/fellTrees: populated centre section cells; all nine chunks' 18x18 fluid domain; centre leaf fires plus at most one ground fire per column; height triples, palette/flash caps, both old and new state arrays, material positions and lazy lookup arrays. Private worker fragments include removed positions, stamps and both tree lists/records. No bound escape was identified in those producer domains. Background fragments no longer persist in the shared context cache; diagnostic server-cache fragments have their own count.
- Live grid capture preflights the whole new window and height band, so a capacity refusal does not masquerade as an absent neighbour. Disk/archive publication checks precede priming/constructing the snapshot. Known lazy global/ruined property arrays and embedded old plans are charged. Archive coordinate/fingerprint/scar validation and fresh-source fallback remain; nullable archive admission is retried, distinct from IO failure.
- New inbox loops keep ready heads charged through publication. This lifetime correction is useful and should be retained while fixing F1's progress reserve.
- The additive callback-fault-isolation change at RuinWorkers.uncount catches RuntimeException/Error, allowing FutureTask.done to return and bulk shutdown to continue. The supplied new unit/GT paths inspect superclass callable clearing and the remaining drained shutdown queue. Earlier worker birth/height design was not reopened as a third review round; its funding interface was inspected only where the new producer admission depends on it.
- This diff changes ownership/admission and diagnostic plumbing, not default physics laws, material release rules or the plan storage schema. Same-input solver/store equality has focused evidence below. Recomputing task-private fractures can change CPU throughput; no whole-zone speed acceptance follows from equality fixtures.

## Raw evidence inspected and its limits

Only the authorized raw logs/telemetry were used. Private RAW_PATH/NBT/world data was neither copied nor uploaded. All paths below remain local.

| Evidence | What it actually shows |
| --- | --- |
| wt-MT-OPT/mod/run/mt-s2-producer-focused.log | compileJava/compileDevtestJava/compileTestJava and `test` succeeded (16 s); 2 required GT passed; wrapper exit 0. The log does not enumerate a total numeric-unit count. |
| wt-MT-OPT/mod/run/mt-s2-producer-flow.log | 12 required GT passed (10.93 s test time), clean shutdown and wrapper exit 0. At pressures 2/20/50, result bound 859,880 B versus actual ruin 21,552/26,628/26,628 B; fragment bound 562,056 B versus 8,664/11,408/11,408 B. These are actual solver fixtures, not whole-map maxima. |
| wt-MT-OPT/mod/run/mt-s2-frontier-final.log | Earlier build succeeded; 78-GT union has **2 required failures**: `ruinspreparedduringflightfallwithfront` and `nearplanscanlaunchwhilefarworkcontinues`. Do not call this union green or attribute the failures to this delta without diagnosis. |
| wt-LEAD2/mod/run/lead2/corrected100kt-natural-on-progress-sample.txt | Actual archived 100 kt sample ticks 6460–6680: near 675, far cursor 112/58486 and running 0 remain unchanged while read cursor advances 39040→39078; reading 47–50. This is older runtime evidence, not a reproduction of F1 at the reviewed pin. |
| corrected100kt-natural-on-server/telemetry/{progress.log,ticks.csv,ledger.csv} | Read locally. ticks.csv has 18,573 valid tick rows and an 8,589,934,592-byte JVM maximum; ledger.csv has 25 valid rows with true success values across its listed phases. These files do not establish a 512 MiB whole-JVM heap guarantee or 1 Mt throughput. Cumulative stage/estimate observations cannot be summed into a simultaneous peak. |

Raw-log SHA256 at inspection:

- focused: `91e51dd07c5e7719cf74387b63bfad0d46bd559015a322b3ca6853a8717c3ca9`
- flow: `2997ad97df64f5bbf9d6ad421db13c0d223529e2de47bb0cd0758c0a375ede86`
- earlier union: `fa9bbe68548a9716092bfb8f4a0075f445d2384d51c571c4b4ba63bf5fa16bbc`

Final union, CI and real 15/100/1000 kt acceptance remain owner work. This reviewer did not start or operate the LEAD2 runner.

## Explicit read-only handback and final resources

Source ownership remains MT-S2; integrated trials/delivery remain LEAD2. Handing back F1 and this review, not taking implementation ownership.

- wt-RPROD-S2 remains detached at exact 4e1f8f6021c10d586758f30639efec6c0de7fe17; tracked tree clean. `git diff --check` for the reviewed range passed.
- Own laptop jobs, test JVMs, CI waiters, helper/review sessions, clients and servers: **none launched, none left to wait for**.
- Reviewer GPU lease: never acquired; final read-only `gpu_lease.sh status` reported **GPU is free**. No other owner's lease was released.
- Protected servers, the LEAD2 actual runner, author worktree/source and private worlds/settings: untouched.
- Only writes: this report, authorized MT-S2/LEAD2 inbox notices, STATUS milestones and the shared factual memory append. No commit or upload.

В память: recorded the pin-specific cold-decoder/progress-reserve lesson in canonical `admission-observation-boundaries.md`; it is not a main/release acceptance claim.
