---
paths:
  - "mod/src/main/java/ua/zentix/incoming/work/**"
  - "mod/src/main/java/ua/zentix/incoming/warhead/**"
  - "mod/src/main/java/ua/zentix/incoming/nuclear/world/**"
  - "mod/src/test/java/ua/zentix/incoming/work/**"
  - "mod/src/main/java/ua/zentix/incoming/client/fx/FireWatch.java"
  - "mod/src/main/java/ua/zentix/incoming/mixin/level/**"
---

# Server work budget (`work/`)

## Structure
- `WorkScheduler` shares `performance.work_ms_per_tick` across heavy work on the server thread. Ready impacts run
  before the first world tick, after freeze/step has been applied; newly queued hits can also start in Post.
  The cap is available while `ServerTickEvent.hasTime` permits work, or unconditionally during `/tick sprint`.
  A server behind schedule keeps `BUSY_MS` = 2 ms per lane (visible nuclear ruins get another 6 ms:
  `nuclear-world.md`). Counting clocks use the full cap independently of wall time.
- Lanes: `IMPACT` takes the whole cap, or 2/3 while nuclear/grid work waits. `NUCLEAR` (`NuclearStrikes.work`) gets
  the remainder; its background workers are outside the clock, and preparation is capped at `PREP_SHARE` = 4/15.
  `GRID` gets what remains, capped at `GRID_SHARE` = 2/15; nuclear work reserves that share while grid work waits.
  One setting controls the total; shares have reasons in code. At 30 ms the old 4-ms grid and 8-ms preparation
  limits remain (`WorkSchedulerTest.sharesKeepTheOldLimitsAtTheDefaultBudget`). Each lane has its own `WorkClock`
  and a one-unit progress guarantee. Admission uses estimated unit cost; running units are not preempted.
- `ownNanos` records lane time per server tick. Every 600 ticks with work, `Mod work over 30 s` reports server time,
  lane time and the remainder. It also reports the percentage of ticks with an early impact window, mean early and
  late impact time, and the percentage with mandatory unload debt; all four use the same 600-tick denominator.
  `tools/logscan.py` shows this line in full under `Work budget`, including fields beyond its usual truncation limit.
  The line ends with tick starts as spark's tick monitor sees them (`ChunkLoadPace.report`: intervals over 60 ms,
  ticks starting over 10 ms after schedule, longest interval) and the paced chunk loads with the measured decode cost.
- `ChunkLoadPace` paces the mod's background disk loads (`RuinSettle` admissions, zone-behind-wave square tickets,
  `NuclearPrep.Tile.holdMore`). Vanilla decodes a disk chunk on the server thread between ticks
  (`ChunkSerializer.read` on the main-thread executor) and runs such tasks up to 50 ms past the tick end
  (`delayedTasksMaxNextTickTimeNanos`), so a batch of loads starts the next tick late although the tick itself is short:
  after 1 Mt over Zearth (r4 run6) the tick took 20 ms (p50) while 20 % of tick-start intervals were 68–82 ms, with
  84 % of between-tick JFR samples in `ChunkSerializer.read` at ~2.6 ms a chunk. A full load decodes its 3×3 square
  (`LOADING_PYRAMID`: light needs ring-1 neighbors at `INITIALIZE_LIGHT`, read from disk), so a load is charged the
  square's chunks without a chunk in memory (`ChunkLoadPace.decodes`). The gap is measured at the end of the last Post
  listener (next scheduled start minus that moment), not at the request: requests come mid-lane, and the lanes and
  other mods' Post work after them may take most of the time left. Requests use the 20th percentile gap of the last
  10 ticks times a budget factor, less 5 ms; the factor drops ×0.7 when a tick starts over 3 ms late after a tick that
  ended in time and grows +0.05 (to 1) after an on-time tick with denied loads. The smallest gap (first version) used
  11.7 ms where 31.8 ms was free and slowed the 1 Mt settlement fourfold (r4 run8: 3.1 % intervals over 60 ms instead
  of 21 %, but 32 % settled at +75 min). Decode cost (start 1 ms) is the server thread's CPU time from the tick end to
  the next start over the `ChunkDataEvent.Load` events in that gap (`ThreadMXBean`; waits for disk reads do not count).
  Timing consecutive events counted those waits: 1.1 ms where JFR showed 0.34 ms `ChunkSerializer.read`, ~0.4 ms
  with all between-tick work (r4 run9). No gap: one load per 4 ticks. In-memory chunks take no pace. `ChunkLoadPaceTest` holds the gap, the learned
  cost, mid-lane requests, neighbor decodes, uneven ticks and the factor; a new background loader
  of disk chunks goes through it too.
- `UnitQueue` runs new heads in enqueue order, then one unit per continuation in a persistent round-robin order.
  Unready areas and blocked dependencies are skipped; a job is cancelled after `GIVE_UP_TICKS` without a unit (since
  its last unit or its enqueue), not by age: a light repair that ran a unit every tick for five minutes may wait a
  tick for its fence (`UnitQueueTest.giveUpCountsTicksWithoutProgressNotAge`). Continuation `ready`/`blocked` results are cached once per
  work window, after heads (whose first step can introduce a dependency). Post refreshes them even within the same
  world tick. Unit kinds keep separate clock estimates. Shutdown drains available work to completion via `finish` while
  its windows run units (a last continuation that enqueues a ready successor keeps the queue length and is still
  progress), then releases only jobs that cannot run (unready areas, waiting for them). Started work is never cut
  short: jobs are not saved, so a release midway would save half-applied edits (GameTest `work_stop_front`); a drain
  past `UnitQueue.SLOW_DRAIN_NANOS` (30 s) only logs. World time stops during the drain: `Job.drain` tells each job
  (also one enqueued meanwhile) to do now what waits for a later tick (the explosion front's arrival); a step
  returning true without progress would otherwise repeat forever in finish's window (`UnitQueueTest`,
  `blast_front_stop`). Strike timelines enqueue into `StrikeWorld.impacts()` rather
  than executing directly; an empty queue can start the job that tick.
- `ChunkDelivery` sends our in-place block changes (ruin writes `RuinPlan.write`, blackout lamps
  `ChunkLights.applyInPlace`) to the players already holding the chunk, the viewers vanilla's broadcast finds (the
  holder's player provider, AT). Fewer than `ChunkDelivery.WHOLE_FROM` (35) changed positions go as section deltas in
  the same tick unless a whole delivery is pending; more go as one whole-chunk packet after the chunk's light. Lamps
  decide per chunk, not per budgeted pass: a pass that stops with lamps left in the chunk goes whole too. Section
  deltas cost the client per position on its render thread: `ClientLevel.setBlock` (in the host pack 55–63 % of it is
  Voxy re-snapshotting the whole section per block, `VoxelIngestService.rawIngest`) and the client's own light
  propagation for every changed block, though the server sends the computed light. A 15 kt wave sends ~247 thousand
  positions in 10 s around a viewer at 1.5 km (one 2.2 s frame of packets, then a 1.8 s light frame); a lamp switched
  off by a delta spreads its block-light removal over its whole light radius on the client, and a 1 Mt blackout over
  the Silo map darkens ~92 thousand lamps around a viewer at 3.9 km within the wave's 10 s passage. A whole chunk costs
  ~0.55 ms (`CHUNK_NANOS`, `POSITION_NANOS`). Further writes join a pending delivery, and only the light fence for the
  latest write revision builds the packet (on the chunk cache's main-thread queue, like `BlockLighting`): built on the
  write tick it would show new blocks in old light. While it waits, vanilla's light broadcast for that chunk is held
  (`onLevelTickPre` clears its holder's light bits, AT; each hold lasts up to `HOLD_LIMIT` ticks, a write after expiry
  starts a new hold): light from new blocks or a changed neighbour would otherwise reach the client's old blocks.
  Fenced chunks wait in a ready queue sent at the world tick's start, in fence order, at most `PER_TICK` (12: vanilla's
  7 ms client chunk budget per tick, `ChunkBatchSizeCalculator`, at `CHUNK_NANOS`, a world-load figure; a ruined city
  chunk also re-creates its block entities and Sodium sections, not measured apart) to each viewer per tick, a chunk
  for all its viewers at once. From `READY_LIMIT` (20 ticks, the ruins' 1 s acceptance) after its first write a chunk
  may use `LATE_PER_TICK` (twice the budget): a late clump of fences (light thread behind, write-to-send lag up to 35
  ticks) or readiness above 12 a tick for over a second spreads at 24 a tick instead of reaching the client at once;
  pacing past 1 s adds only that spread (GameTest `lateFencesStillSpread`). A waiting chunk keeps its light hold and later writes join it (a
  superseded revision leaves the queue, its new fence queues it again); vanilla's own block deltas for it (fire, player
  edits) still reach the client's old blocks meanwhile. Unpaced, a 1 Mt wave crossing the Silo city in view sent a
  viewer at 3.9 km up to 95–105 whole chunks in one tick, ~600 in 3 s; GameTests `readyChunksGoPacedToEachViewer` (two
  viewers), `readyChunkGoesAtItsWaitLimit`. The client re-creates every block entity of a
  whole chunk, and vanilla drops each replaced ticker from the level's list by a single `ArrayList` removal (MC-117075,
  removed × tickers a tick): with 20,074 chests ticking in view on the Silo map, the tick after a frame that loaded
  30–134 ruined chunks took 200–300 ms, worst during G1 concurrent marking. `mixin/level/BlockEntityTickerRemovalMixin`
  drops exactly the tickers the loop found removed, in one pass after it; what ticks and when is unchanged (GameTests
  `removedTickersLeaveInOnePass`, fails without it; `reboundWrapperKeepsTicking`: a wrapper removed after its turn and
  rebound stays, as in vanilla).
  GameTests: `heavyRuinsGoWholeAfterTheirLight` (the client's light is rebuilt from its light before the write and every
  payload's data/empty masks in order, unmentioned sections kept, and must equal the server's right after the whole
  chunk: comparing payload and server while skipping absent sections passed a packet with no light at all),
  `heavyRuinsAsDeltasGiveTheSameChunk` (the same ruins through deltas, both client chunks equal the server's),
  `lightWaitsForTheWholeChunk` (light thread held, a light mark on the waiting chunk reaches the client without the
  hold), `blackoutLampsGoWholeAfterTheirLight` (64 lamps in two budgeted passes whole, 3 lamps as deltas),
  `firstFenceCannotSendSecondWritesWithUnprocessedLight` (one packet with both writes' light),
  `lightHoldExpiresBeforeALaterWriteRestartsIt` (the 200-tick deadline and restart). Measuring client stalls:
  `jstack -l` and `ThreadMXBean.dumpAllThreads(true, true)` walk the heap at a safepoint, 115–250 ms each on the
  full-pack client (probes every 0.5 s turned a 4 s stall into 13 s); sample the render thread with
  `Thread.getStackTrace` (the scenario client's `incoming.hitch` probe) and subtract `-Xlog:safepoint` dump time. Its
  stall stacks carry Mojang's own line numbers, not the decompiled sources': map a line with `javap -l -c` on the
  class in Prism's `libraries/net/neoforged/neoforge/<version>/neoforge-<version>-client.jar`. A
  whole chunk costs the wire more than its deltas (Silo city chunks ~11 KB compressed against ~0.2 KB for 35 deltas);
  vanilla sends chunks at that size as a player moves, unpaced by our code: not measured for a conventional blackout
  over a home uplink.
- `StallWatch` samples the server stack every 0.5 s while stalled. After a stall longer than 2 s, within or between
  ticks, it logs `Server thread stalled for N s` with common stack locations. A single-player pause is not a stall.
  Vanilla's `Can't keep up` is accumulated lateness; the lane report only sees ticks.

## Pitfalls
- Damage includes death, drops and other mods' death handlers (measured at roughly 0.2 ms or more per mob on the
  host). Mass damage runs as budgeted units (`BlastFront`, `PulseJob`), not as one whole-tick entity loop.
- The early impact window is `min(2/3 of the total cap, nextTickTime - now - reserve)`. The share leaves room for
  lower-lane work that may appear during world simulation. `TickReserve` keeps the maximum of 20 recent mandatory
  samples. Unknown cost or an overdue schedule leaves the late fallback. This estimates future simulation; it
  cannot predict a new vanilla/GC stall. There are no new settings.
- `ImpactTiming` attaches through vanilla `ProfilerFiller.tee` for one tick and restores the prior profiler at
  LOWEST-priority Post, including other mods' normal/LOW Post handlers in the measurement. A `chunk_unload` section
  is optional only if it starts without work vanilla performs with `hasTime=false`: removable `toDrop` holders
  (`getGenerationRefCount()==0`, or stale IDs) or more than 2000 queued unload callbacks. Generation-held entries,
  pending save futures and up to 2000 callbacks do not alone imply mandatory debt. With debt the entire section
  stays reserved. Section name suppliers are not evaluated and no profiler paths are built.
  Vanilla writes `tickTimesNanos` before Post; subtracting both impact windows from it would understate the reserve.
- Both impact windows share one clock and expenditure; `WorkClock.resume` retains counters and estimates. If early
  work stayed within its allowance and the cap has room, Post may start one new unit as the final unit. An early
  overrun forbids that extra guarantee. Even when the early window was skipped, Post with spare time is limited by
  the absolute next-tick deadline. Sprint and counting scenarios keep their full allowance; freeze stops both
  impact windows. Timing/reserve state is an unsaved overworld attachment, not static world state.
- `UnitQueue` reconciles each entire window with `WorkClock.accountWindow`: readiness/dependency checks, queue
  bookkeeping and releases count once alongside the already charged steps. Otherwise early-window overhead is
  refunded when Post subtracts only step time from the common cap. Step estimates and counting-clock costs remain
  unchanged; accounting does not preempt a running step or its synchronous release. Readiness scans are also
  indivisible: an all-waiting window can exceed its allowance without recording a unit. Do not infer a universal
  deadline-plus-one-unit bound from the ready-job regression fixture.
- Regressions: `ImpactTimingTest` (mandatory unload classification), `WorkSchedulerTest` (including a skipped early
  window), `UnitQueueTest` (one readiness/dependency check per continuation window), and GameTests
  `work_impact_schedule` (40/43/47 ms plus background on controlled clocks), `work_impact_debt` (real Ignition/Shatter
  and a new front, first-block/front-end/summary ticks), `work_impact_modes` (freeze, sprint, two worlds), and
  `work_impact_profiler` (real `chunk_unload` sections on four consecutive server ticks).

- `BlockLighting` shares the impact clock, alternating first service with strike work after a full world rotation.
  A separate queue always served last can starve behind continuous impacts while retaining chunk leases. Do not use
  tick parity alone: with an even world count, world rotation would lock each world's priority permanently.
  GameTest `light_late_busy` and `WorkSchedulerTest.everyWorldGetsBothQueuesFirstEvenWithEvenWorldCounts` cover both.
  Light jobs opt into UnitQueue dynamic readiness: starting a future inside a continuation must invalidate cached
  readiness before its next step in the same work window (`UnitQueueTest.asynchronousContinuationResamplesReadiness`).
  Legacy admission is itself a budgeted unit: pending positions own no tickets, a bounded scan drops unloaded or
  already marked entries and chooses the nearest player chunk. Only one repair can hold its source-context ring;
  normal live-edit leases remain independent. Shutdown drops pending legacy positions and drains committed edits.
  Recovery checks go to vanilla's one light-engine queue in slices of at most `BlockLighting.LIGHT_SLICE` (256)
  checks in one chunk (the engine orders tasks by chunk, so a fence on one chunk does not cover another's checks):
  the next slice waits until the engine processed the last. The scan resumes at a cell; every move to another section
  (including past one whose storage vanished between slices) or chunk starts at its first cell (GameTest
  `light_repair_cursor`). A pending slice or fence is `blocked`, admitted work rather than an unready area. Server-thread budgets do not bound that queue, and a moving
  player's synchronous chunk load needs a light task from it: whole sections per unit queued 195,000 checks in the
  minute after a 1 Mt blackout (every pre-existing chunk under the outage counts as legacy) and held such loads
  0.3–0.8 s (GameTest `light_repair_slices`).
- A walk over chunks that skips most of them still charges its time budget for the skips and stays bounded to the
  area it searches (`FireWatch.box`).

