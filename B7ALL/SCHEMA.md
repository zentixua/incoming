# B7ALL pending census — diagnostic branch only

This change is based on diagnostic `0f1a77ff88b4e6546212aec9ab34a834eda525d1`, PR586. It must never be merged into main, shipped to players or released. Production integration and actual runtime acceptance belong to LEAD4.

Enable before server/world creation:

```
-Dincoming.mtB7=true
-Dincoming.mtB7.pendingScene=natural-fixed-v1
-Dincoming.mtB7.pendingEvent=<actual detonation ID, positive integer>
-Dincoming.mtB7.pendingDimension=minecraft:overworld
```

One selected event in one selected dimension per process. This is deliberately an exact fixed-camera scene opt-in, not general player telemetry. Existing `progress.log` receives `pending_v1` every20 server ticks, alongside the pre-existing general progress rows. Event identity comes from already-cached `incoming_nuclear` SavedData, checking current AND past lists, with at most1024 event entries. No `NuclearEvents.get`/`NuclearWorld.get`, ledger lookup, world acquisition, queue work, solver, collection, admission, storage read, block state or terrain load is called by the census. It only reads numeric marks, current holders and tracking views. `getChunkNow` is non-loading but can update the normal getter's cache/profiler.

## Fields and population

Each row includes `wall_ms`, `mono_ns`, `server_session`, server `tick`, `dimension`, `game_time`, `event_game_time` and selected `event`. Bind these to source/artifact/config hashes in the run manifest. No counter subtraction across server/attachment lifetimes.

- `holders`: number of visibleChunkMap holders examined; no copy of the holder set. Not a count of loaded FULL chunks.
- `watched_loaded`: union of chunks in any current player ChunkTrackingView for which getChunkNow returns a LevelChunk. Excludes below-FULL and pending-unload holders absent from visibleChunkMap. The test-only extra-view predicate is reused in GameTests; it must be null in real trials.
- `affected`: watched_loaded chunks whose nearest horizontal point is within the selected event's ruinRadius, using the same law as ScarQueue.inRange.
- `scarred`: affected chunks whose existing CHUNK_SCAR >= selected event ID; later scars satisfy earlier events in production too.
- `pending`: affected chunks without that mark, regardless of job existence. Includes future-wave work.
- `overdue`: pending with game_time > ScarQueue.due(event, chunk centre).
- `pending_max_lag_ticks`: maximum positive game_time-minus-wave-due among current pending chunks; zero if none overdue. Retry due times are irrelevant.
- `pending_without_job`: pending chunks missing that event in the authoritative current job's remaining event list. Detects omitted jobs, including jobs removed after errors. Does not inspect stale ready-set entries.
- `prepared_success`, `fresh_success`: cumulative successful CHUNK_SCAR commits in this ScarQueue lifetime for this event (including empty/no-change plans); prepared versus fresh/parked comes from the successful plan source. Does not measure blocks removed or material settlement.
- `seen_success`, `seen_success_max_lag_ticks`: successful commits while currently watched and their maximum nonnegative wave lag.
- `prepared_drops`: selected event's dropPrepared calls; no success implied.
- `unloaded_pending`, `failed_pending`: known selected-event pending jobs removed through unload/out-of-memory-residency or exception paths respectively; include unseen chunks. Not completion counts, not distinct chunk IDs, and not all possible world unloads. Jobs with more than32 remaining events cannot contribute these bounded exit counters; a matched census then reports job_events/incomplete.
- `clears`: ScarQueue.clear calls during observer lifetime. Does not reset diagnostic history and does not count completion.
- `samples`, `partial_samples`, `sampled_pending_max_lag_ticks`: lifetime sampled history; maximum is sampled, not continuous.

## Bounds, costs and validity

The existing progress sampler still limits iteration to8 dimensions and reports dimension_limit; absence of the selected dimension is missing coverage, never completion. No game object references or large arrays are retained by the observer; state is numeric scalars and a selected event ID. Snapshot record contains only scalars and a fixed cutoff string. At most20000 holders and16 players per sample; at most32 remaining event entries per matched job. Overlap between views is counted once because holders are enumerated once. The cooperative time cutoff is1,000,000ns, checked before each32-holder batch and after the scan. It cannot preempt a Java call, class linkage, OS stall or GC. An overrun marks the row incomplete even if iteration ended. No claim of a hard1ms wall-time maximum follows.

`complete=false` plus `cutoff=event_missing|players|holders|time|job_events` makes all counts lower bounds and forbids a completion/PASS inference. `event_scan>1024` identifies the event-lookup cap; a missing selection also produces event_missing. Missing event includes an expired/cleared event, and never means zero pending. `observer_ns` covers census work, including radius/arrival calculations. `observer_pre_enqueue_ns` includes event lookup, census and row formatting up to that timestamp, excluding residual formatting and log enqueue. `progress_drops`, the existing tick total log-drop count and ordered footer expose row loss. This uses the existing bounded512-row/64MiB progress logger; its counters and shutdown completeness retain their original semantics.

For the fixed-camera measurement, require a complete row at every expected20-server-tick cadence, event identity present, no log loss and no cutoffs before treating current counts as exhaustive. Record all observer costs, including first-use/partial overruns; assess actual overhead in the already-planned full-pack trial, without repeating the old reflective graph probe. Warm complete samples must stay below the1ms cooperative criterion. Missing/partial data are UNKNOWN, not PASS. Numeric success maxima supplement samples for chunks that complete between them. After a queue drop/preparation expiry, current loaded unscarred chunks remain pending independently. A falling loaded cohort or growing removal/clear counters is an exit, not evidence that work finished. Root must reconcile these with fixed camera/player and runtime records before any near-wave acceptance; this observer itself issues no readiness verdict.

This is current server tracking-view membership and server scar application, not camera frustum, line of sight, client receipt/mesh/rendering, continuous visibility/waiting, all unloaded zone chunks or final physical settlement. Between-sample membership changes are not reconstructed. A chunk omitted from jobs and both loaded and unloaded between samples can escape this census; the exact scene retains its fixed camera and loaded cohort. Do not call this a continuous renderer fence.

## Focused checks

`mt_b7_pending`: a real destructive prepared plan is refused by fixed external material pressure. Successful-only stats remain zero while watched loaded pending/overdue is1; telemetry game time advances20 ticks without changing the world's clock or pressure. Census must not change owned bytes, jobs or scar mark. Prepared-drop preserves pending lag; deliberately removing its still-loaded job reveals pending_without_job=1. Reoffering and actual application produce scarred=1/pending=0 and one successful commit. Later prepared/queue clearing preserves diagnostic success history.

`mt_b7_cutoff`: missing event and a deterministic injected observer-clock cutoff both remain incomplete, with explicit reason/cost. These are observer correctness checks, not runtime latency or observer-cost measurements.
