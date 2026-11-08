---
paths:
  - "mod/src/main/java/ua/zentix/incoming/work/**"
  - "mod/src/main/java/ua/zentix/incoming/warhead/**"
  - "mod/src/main/java/ua/zentix/incoming/nuclear/world/**"
  - "mod/src/test/java/ua/zentix/incoming/work/**"
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
  Unready areas and blocked dependencies are skipped. Continuation `ready`/`blocked` results are cached once per
  work window, after heads (whose first step can introduce a dependency). Post refreshes them even within the same
  world tick. Unit kinds keep separate clock estimates. Shutdown drains available work without a deadline via
  `finish`, then releases jobs that cannot proceed. Strike timelines enqueue into `StrikeWorld.impacts()` rather
  than executing directly; an empty queue can start the job that tick.
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
