# RNEAR-S2B independent source review

Reviewed 2026-11-04. Exact range:
`4d89b5b113a53b3cfe8822092e1813750516c9e9..4f1f18115a16d08ff081592ced823b2e56e9b14a`.
Checkout: `/mnt/data/projects/incoming/mod/run/wt-RNEAR-S2B`, clean detached HEAD at the latter SHA before source reads.

Scope: second/final source review of the new stacked near-first boundary (#585), not another review of core #568. Read the diff and relevant source/callers directly. No author PR body, inbox, STATUS, handoff, prior near-review report or author test-result log was read. TEAM/context/BOARD/live, repository rules and relevant canonical memory were consulted. No source changes, tests, Gradle, heavy jobs, GPU, clients, servers, CI or merge. The actual delayed page-write fixture at this head was inspected but not executed. This review cannot establish real 1 Mt acceptance or report the outcome of the author's concurrent checks.

Two correctness findings remain.

## F1 — P2: window result collection can disappear when the near todo is overtaken

Primary changed location: `mod/src/main/java/ua/zentix/incoming/nuclear/world/NuclearPrep.java:1162-1170` and `1183-1189`.
Supporting source: `RuinContext.java:229-273`, `RuinInbox.java:25-64`, `NuclearPrep.java:701-703, 953-999, 403-425`.

`requestWindow` is the only consumer of `RuinContext.reads`, and the only place that turns a window read/decode error into `windowFailed`. Calling it only from a still-unplanned near todo does not give already-owned reads an independent collector. A concrete interleaving is:

1. An explicit near centre requests a cold neighbour. `requestWindow` reserves its inbox slot and returns missing; the todo cursor is rewound.
2. The ordinary far pipeline, which has separate reads, obtains the needed snapshots and submits/completes the same centre. Alternatively the missing neighbour becomes ready in memory while the original I/O is still outstanding.
3. `submit` consumes the todo through the `running`/`containsKey` shortcuts, or successfully submits using the now-available source, without waiting for all previously requested window reads. The original result arrives afterward.
4. Once the remaining todo is exhausted, no preparation path calls `requestWindow`. `harvest` consumes futures and `far` consumes its own inboxes, not `RuinContext.reads`. An already-delivered window error therefore never reaches `p.failure`; completed critical plans and their write receipts can produce READY and bind. A successful late payload also stays charged and retained.

The retention is not merely an uncounted estimate: `RuinInbox.expect` takes a 4 MiB reservation per window read, and only poll/invalidation returns it. `bind` refresh does not drain it. A persistent case can put the only newly ready neighbour outside the ruin-radius membership, so periodic refresh does not create another near todo to incidentally drain the inbox. Shared cross-dimension admission continues charging this abandoned ownership. No total-heap or stall-duration measurement is claimed.

Fix direction: drain/retire already-owned window completions under the work budget independently of todo, plan presence and fresh worker/snapshot admission; propagate any required source failure before declaring/binding READY. If a read is explicitly superseded, invalidate its ownership so the eventual callback cannot leave a retained result behind.

Useful regression: gate an actual near-window completion, allow the same centre to acquire a plan through far work or a newly ready neighbour, consume its todo, then deliver the late result. Tick the registered preparation with no additional todo and check that the reservation is released. Repeat with an I/O/decode failure and check FAILED/IO and both bind APIs rejected. Include a centre near the ruin-radius boundary so unrelated periodic refresh cannot hide missing collection. The current window fixtures inject one completion before explicitly invoking `submit`; they do not exercise collection after the todo has been consumed.

## F2 — P2: reload resubmission discards a completed failed/cancelled near future before observing it

Primary changed location: `mod/src/main/java/ua/zentix/incoming/nuclear/world/NuclearPrep.java:868-878`, reachable through the new explicit-near submission and unload preservation.
Supporting source: `RuinContext.java:729-756, 633-648`; `NuclearWorld.java:342-348`; `NuclearPrep.java:479-487`.

An explicit handle now retains its original near task through unload, which is necessary, but the live-chunk collector checks chunk identity before inspecting the terminal result:

1. Submit an actual near task with the original nonzero chunk identity.
2. Unload/reload the coordinate before harvest, producing another `LevelChunk` instance. The new explicit-handle unload path preserves the task and its submitted collector.
3. Let the old task exit exceptionally or cancelled, with worker admission available.
4. `harvest` sees `done == true`, so its `failed` check in the not-done branch is skipped. `collect(level, newChunk)` enters the identity-mismatch branch, removes the old task and resubmits immediately. It never executes the old future's `get()`.
5. Resubmission clears the context failure flag. Because a replacement task is running, harvest re-adds the collector instead of marking the request IO. A successful replacement can subsequently certify READY and bind, losing the original failed/abandoned completion entirely.

This contradicts the new explicit-request terminal-error behavior: the same completed failure without reload correctly fails the request. Actual worker-exit accounting is respected here; the missing step is observing the terminal outcome before replacing its input lifetime.

Fix direction: distinguish successful-but-stale completion from failed/cancelled completion before discarding/resubmitting for a changed chunk identity. Preserve the failure result for the explicit preparation collector.

Useful regression: extend the real near failure/cancellation fixture with a nonzero original chunk identity and an actual unload/reload before collection. Require FAILED/IO and reject both bind APIs even if replacement admission is open. Keep a successful reload case to prove successful stale snapshots still replan. Existing failed-task fixtures deliberately pass chunkId zero (`RuinNearLifecycleGameTests.java:93`), and the unload fixture invokes callbacks against the same still-live object (`65, 75`); neither reaches this branch.

## Other inspected boundaries and limits

- Near critical membership uses ready chunks consistently; the bind refresh adds newly ready loaded sources. Required metadata participates in the retained preparation estimate.
- The per-key pending-write check covers queued and dispatched critical plan receipts. The new delayed real `RuinPages` tail fixture exercises that actual dispatch path in source; execution remains the author's responsibility. Unrelated far writes do not recreate the full-zone gate.
- Legal absent/partial terrain is separated from read/decode failure at the window-result consumer. F1 concerns whether that consumer remains reachable.
- Handoff transfers the same plans/context and leaves the existing far path available. No new issue is asserted here about pressure timing, durable settlement, total heap, far LOD convergence or real throughput.

Optional findings: none.

Delivery: source findings only; MT-S2 owns fixes, regressions and final checks. Owned jobs: 0. No resources or source ownership acquired.


## Same-round composition continuation — 97c3f7f835eefd73f0356042967036063509fd1f

This is the remaining composition delta of the same second/final review round, explicitly requested after the initial report. Reviewed in the same dedicated worktree, changed from the original clean detached 4f pin to clean detached `97c3f7f835eefd73f0356042967036063509fd1f` before composition source reads. Verified actual parents:

- First: `1593e1cdbfa8ef214d68071c6d38b1ebcfc88c49`.
- Second: `4f1f18115a16d08ff081592ced823b2e56e9b14a`.

Compared the composition against both parents, including the combined merge hunk. Inspected the imported carrier preparation/geometry changes in NuclearPrep and the surrounding remote/command, StrikeService, NuclearKeys, scheduled ICBM and carrier-to-payload/impact identity paths. This is not a fresh review of all pre-existing control/LOD/performance work from the first parent, nor another core #568 review. No later source head, author inbox/report/conclusions or current test results were read. No tests, source edits, jobs, CI, GPU or server/client operations.

**No additional serious integration finding identified.** This does not close or reclassify F1/F2 above: their relevant code is still present in this composition (NuclearPrep F1 now around 1223-1252; F2 collector around 931-941; RuinContext terminal-result handling unchanged). No later author fixes were reviewed.

The following composition boundaries were checked directly:

- `prepareCarrier` samples the server route miss once and retains the unshifted accepted `resolvedTarget` separately. `work` builds the nominal burst at `target + miss` (`NuclearPrep.java:353-378, 675-714`). The new `requiredTarget` is computed from that geometry's burst coordinates (`749-752`), so a miss crossing a chunk boundary does not accidentally gate only the unshifted target chunk. Loaded membership/range checks use the same geometry (`884-897`).
- Both bind paths refresh the loaded required set before checking near READY (`470-495`). The first parent's scheduled-bind rejection of a carrier-specific request remains alongside the added refresh; the combined merge hunk preserves both. Carrier launch reads the same weapon-specific miss, binds the accepted unshifted target and places the returned identity on the projectile (`StrikeService.java:90-105, 161-188`).
- The scheduled ICBM path binds its actual scheduled ID before warning publication, and removes a refused schedule/projectile (`NuclearStrikes.java:92-143`). Carrier impact and B-2 payload transfer continue forwarding their one-shot identity; exact backend burst/ground matching is retained. Partial near-first handoff still shares the plan/context owners; the inherited far pipeline remains present.
- Source fixtures cover real carrier preparation/flight and separate near-first/late-membership cases. Their presence is not an execution claim or proof of the combined runtime goal. The original near-window and reload findings still require the specific regressions described above.

No optional composition finding. Whole 1 Mt preparation, live near-wave timing, memory/throughput and eventual standard LSS delivery remain outside this source-only continuation. MT-S2/LEAD2 retain implementation and validation ownership. Review complete; owned jobs 0.
