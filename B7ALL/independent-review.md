# RB7ALL independent source review

Date: 2026-11-04T18:43:02.720130+00:00. Reviewer: Codex, fresh gpt-6.1-sol/high. Effective launch verified from PID 63564: explicit `-m gpt-6.1-sol` and `model_reasoning_effort="high"`.

## Verdict and scope

No blocking correctness/performance defect found in the four-file diagnostic Java delta at **f53ecbe1c3cfec751c1aa978362897a07adc2e7d**, relative to **0f1a77ff88b4e6546212aec9ab34a834eda525d1**. This is a scoped source-review result, not runtime acceptance, a cost measurement, a test-pass claim, or permission to ship diagnostic code. A reproducible P2 interpretation defect exists separately in LEAD4's private analyzer; see A1. B7ALL does not own that file and need not change Java to resolve A1.

Worktree `/mnt/data/projects/incoming/mod/run/wt-LEAD2-NATURAL`, branch `codex/mt-natural-trial`; clean at start and final pin. HEAD was beyond base, so the committed delta was reviewed; there was no additional working/untracked delta. Diff: four files, 260 additions, one deletion. SHA256 of `git diff BASE HEAD`: `3e9bc63204dd16fa4a8131945db0241b65db9b9d7c13f443dc5b35930a73e17d`.

Current TEAM, newest live entries, BOARD, repository AGENTS and applicable rules, canonical memory index and relevant counter facts were consulted. No B7ALL schema/report/handoff, PR586 body, author conversation, STATUS, or LEAD4 conclusions/checkpoint was read. Source ownership stays B7ALL; runtime/GPU/integration stays LEAD4. No ownership transfer or production merge.

## A1 — P2 private analyzer: interval slicing erases post-observation event loss

File `summarize_progress.py`, lines 34–36 and 60–65, output fields at 101–105. The parser applies `--start-ms` before searching for the first nonnegative `event_game_time`. It therefore cannot distinguish genuinely pre-event missing rows from event loss after an earlier observation excluded by the reporting interval. It labels all missing rows in such a slice `expected_pre_event_rows`, removes them from `event_rows`, and emits an empty `cutoffs` dictionary. This contradicts its own comment “Never exclude a missing event after its first observation.”

Reproduced with the unmodified pinned analyzer, using only in-memory `Path.read_text` substitution and syntactically emitted pending_v1 fields:

1. wall_ms=1000, tick=20, server_session=1, event=2, event_game_time=10, complete=true, cutoff=none.
2. wall_ms=2000, tick=40, same session/event/dimension, event_game_time=-1, complete=false, cutoff=event_missing.

Whole-file summary: `event_observed=true`, `event_rows=2`, cutoffs `none:1,event_missing:1`.
Summary with `start=1500`: `pending_schema_present=true`, `event_observed=false`, `expected_pre_event_rows=1`, `event_rows=0`, `cutoffs={}`, and even `max_progress_drops=null` despite a valid missing-event row in the interval. There is no explicit false readiness verdict, but the failure witness and interval coverage are incorrectly classified. Fix in LEAD4's analyzer: establish event observation history before slicing, scoped by server lifetime/dimension/event; alternatively keep initial missing rows unknown instead of claiming they are expected pre-event. Add this exact interval regression. No analyzer edits made here.

## Java source assessment

- `MtB7Pending.java:47–70` uses the current holder set, current tracking views, `getChunkNow`, ruin range and existing CHUNK_SCAR attachment. The census does not depend on successful-application counters, prepared-map presence, job presence, or mutable retry due. Missing jobs increment pending_without_job; they do not close pending. Lag comes from `now - ScarQueue.due(d,pos)`, the selected event's centre-wave game-time law. A missing/mismatched event is incomplete, not a completed zero.
- `MtB7Metrics.java:81–106` reads only an already-owned NuclearWorld attachment and the existing `incoming_nuclear` SavedData cache slot. It searches active AND past detonations, explicitly selects the configured ID and dimension, and emits server session, clocks and event game time. No call to NuclearWorld.get/NuclearEvents.get, cold-ledger admission, prepared-plan retrieval, collection, ticketing, world block reads or world loading was introduced by the census path.
- `ScarQueue.java:1259–1262` increments the new success counters only after the successful ruin path writes CHUNK_SCAR and marks the chunk unsaved. MEMORY/AGAIN/WAIT, absent jobs and ordinary invocation do not increment them. Existing diagSource is assigned on successful prepared/parked/background branches, independently of NukeDiag.ON. Empty but successfully committed plans remain valid successful scar applications.
- Observer history belongs to its ScarQueue/NuclearWorld lifetime, not preparedStats. dropPrepared/clear do not reset successful counts or sampled maxima. Selected-event filtering applies to successes and prepared-drop calls; unload/failure hooks first check the selected event in the unprocessed job suffix. `clears` describes queue clears, not cleared-chunk totals.
- Source bounds: <=20,000 holders, <=16 players, <=1,024 searched events and <=32 remaining job events. Census clock checks every 32 holders plus final elapsed check. Partial results explicitly set complete=false and identify cutoff; partial counts/maxima are lower bounds. This is cooperative sampling, not hard preemption. No runtime graph walking/reflection is added (reflection is confined to GameTest fixture access).
- Logging inherits bounded 512-row queue, row/file limits and drop counters from MtB7Log. `progress_drops` is sampled before the current enqueue, so final drops/footer still matter. `observer_ns` covers the census. Intentional `observer_pre_enqueue_ns` covers event lookup/census and the formatting evaluated before that clock read; it excludes residual concatenation/enqueue and cannot prove total observer overhead. No performance estimate is inferred here.

## Regression review and honest limits

`MtB7PendingGameTests` exercises real plan application refused by controlled cross-dimension material reservation, verifies the plan's actual memoryWaiting state and zero prepared/fresh completed counters, then observes positive pending/overdue lag increasing by exactly 20 game ticks. It checks no reservation/job/scar mutation by census, removal of prepared ownership, deliberate omission of a still-loaded job, eventual successful scar commit, and history surviving prepared/queue reset. The second fixture covers null event and deterministic synthetic clock cutoff. These meaningfully discriminate the original completed-only blind spot; they are not timing benchmarks.

Coverage gaps (nonblocking source limitations): no actual cached-event lookup/prune integration fixture, real tracking-view movement/multi-player fixture, player/holder/job-event cap fixture, fresh/background success or exception-removal fixture, or last-small-batch overrun fixture. The new tests were read, **not run**; no build/GameTest/CI was launched in this review-only scope.

Interpretation limits: `complete=true` means complete census, not completed strike. The cohort is currently tracking-view AND getChunkNow-loaded chunks, not all historical viewers, waiting times, all in-memory/pending-unload chunks, sent chunks, or rendered geometry. Holder sampling can remain partial repeatedly; zero in an incomplete sample proves nothing. Cached getChunkNow may update ordinary profiler/cache state; it makes no load request. Detonation radius/front laws can populate ordinary derived caches, so no zero-allocation or zero-overhead claim. `prepared_drops` counts dropPrepared calls, including calls with no remaining plans; it is not a count of plans lost. `unloaded_pending`/`failed_pending` count selected-event job-removal occurrences, not distinct chunk losses. Over-32-event job removal is not counted by that bounded helper. Event scan exhaustion also reports event_missing with event_scan=1025; it must not be described as proven event deletion. Scene identity/source artifact still require the run manifest. No lifecycle counter should be compared across different dimension/world/server lifetimes without provenance.

## Analyzer versus actual raw input

Actual emitted pending_v1 names match all required analyzer keys, including intentional observer_pre_enqueue_ns; delimiter parsing accepts the space-separated rows and comma-separated prep blocks. Private analyzer SHA pinned below.

Read-only execution on supplied old `phase-cut/progress.log --event 2` produced 848 overworld world rows, 554 matching prep rows, **pending_schema_present=false**, no event census, and null pending maxima. This is correctly unknown, not zero or a pass. It found far 5357 unchanged across 529 observed samples, wall 1793813868525–1793814396530 (528005 ms), with no observed counter decrease. That is a sampled constant counter span, not proof of deadlock or any continuous between-sample behavior. Success endpoints are lifetime endpoints, not automatic phase deltas; log footer/final drain are not verified by the analyzer's newline flag.

Direct original post-clean4 input: wall_ms=1793814552476, gameTime=2040659058; its watchedSummary reports 415 watched/loaded/overdue queued chunks and maxLag=13551, with no test override. The old probe enumerates jobs, so it cannot witness a loaded watched unscarred chunk with no job. The new holder census closes that specific source blind spot. The post-clean4 instant is later than the supplied phase-cut log ending 1793814396530: do not conflate those timestamps or derive absent new-schema values for the old run.

## Pins (SHA256)

- `mod/src/devtest/java/ua/zentix/incoming/nuclear/world/MtB7PendingGameTests.java`: `88f2340c8d48e4bb4307711e17431baec19a96e4310f207dac6609440e9b158b`
- `mod/src/main/java/ua/zentix/incoming/nuclear/world/MtB7Metrics.java`: `ce98712bf61d5d9f2619a760514fa9787c586d26c077424a87031f5fb67ee9e7`
- `mod/src/main/java/ua/zentix/incoming/nuclear/world/MtB7Pending.java`: `b7f04364891ec968635fdddd4139b4a5a4571a9fbfb5adba32b91bf1a1679fda`
- `mod/src/main/java/ua/zentix/incoming/nuclear/world/ScarQueue.java`: `b8e57eef97df06ccc4738db44bf6c4f71fafcd23b20ccea3f255ac29017be8c6`
- `/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/summarize_progress.py`: `52bca83b942df4475770a998a904badc4a6248a02f0d46f6d1b4219446f997f7`
- `/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/ownership-probe4/OwnershipAgent4.java`: `135e3d792156c23a37bf7148789aceb77ff1761b993260a5750964f52ae9877b`
- `/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/lead3-actual1000-evidence/corrected1000kt-archive-material-on-post-clean4.txt`: `8759dd098ff196102cad7ed5b643cc5c37c6f2fa957863df2f3c604afeaa764a`
- `/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/lead3-actual1000-evidence/phase-cut/progress.log`: `84898a36cf093f491709cd544a1259ee968fe839d97109835742fddd2066098c`

Surrounding source pins: NuclearWorld.java `fe38bb08a7e7ea439d4ae78d9cb1c27fba7760c6a5e7b4e55d058d0c8cf13162`; NuclearEvents.java `1bc2d6f5af6858c8ac4ceae5e7260b3ea0a92e0b8bea68fc6a2f8ee876b8fde3`; compat/MtB7Log.java `c6300eb947c8e082f50504da561727746c16b7ae34be6916e858d61d251691d6`.

Primary cached Minecraft source consulted at `/home/artem/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_0a412762a64eacb3d9f13f602653eb5df1d3774e_output.jar`: ChunkMap.java SHA256 `533792de383413a63b031565db34088aed8588d11fdfa2082c13dbe774768c97`, ServerChunkCache.java `120795c72cf836fe07054ce87f01b6b6ac9cc28082fb255d20995bcbce5a46ef`, ChunkTrackingView.java `aad25b6d008123cbb1213f7a6a8fa3a3d720c7002ff99b6e7b404ef626c240a5`. ChunkMap.getChunks exposes visibleChunkMap values; isChunkTracked excludes pending sends, whereas tracking-view contains includes them; getChunkNow uses cached/visible FULL retrieval without acquiring tickets or waiting. These exact cached-source contracts were consulted; this does not establish the loaded runtime artifact version.

## Final state

`git diff --check BASE HEAD` passed. No source/analyzer changes, commits, builds, heavy jobs, CI, GPU lease, UI, server starts, attaches or runtime probes. Only the requested report/inbox and canonical personal memory notes are written. No owned resources or jobs to await; all lightweight inspection/parser commands completed. No merge/release. Return scope to LEAD4/B7ALL.
