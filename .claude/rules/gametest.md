---
paths:
  - "mod/src/devtest/java/ua/zentix/incoming/**"
  - "mod/src/devtest/resources/**"
  - "mod/scripts/gen_test_structures.py"
  - "mod/src/test/**"
---

# GameTest and flight scenarios

## Pitfalls
- Whole strikes against data: `SurfaceGameTests` (batches `surface_<weapon>`, `surface_<weapon>_nearby`, `surface_bomb`):
  the mod's strike path (`Warheads.detonate` with a weapon entity, axis from the spec's impact angle; bomb is a real
  entity dropped by B-2) over seven surfaces; expectations are sourced laws and data spread; report:
  `grep SURFACEBENCH` in the shard log (rows and batch table). Each row prints once (`succeedWhen` repeats the check
  each tick). Sub-block craters (Lancet, Grad) are checked by summing grid strikes with 2σ of random whole-block
  addition (`lawBlocks`). All cover and small-charge rows are checked; in cohesive media, count removed volume +
  saved damaged depth (`SurfaceDamage`); a block fraction does not mean a hole; slabs above void follow the breach law
  (`BreachModel`, table B-3 steps).
- `BlastGameTests.layPosts` posts must fall on ray steps (`sampledPosts`), not merely avoid occluding neighbors. An even
  16³ grid has no zero direction components: block (0, 9) from the cell's bottom center may lie between all rays.
  Position-hash threshold jitter picked it instead of the post at ~0.6 R — rare `surface_missile_nearby` failure.
  Thresholds and radius fractions stay unchanged; positions are chosen among measurable ones. `surface_layout` keeps
  the reproduced position and 31 more offsets.
- CI shards GameTest over six machines (`-PgametestShard=i/n`, `GameTestShards`): each batch stays in one shard; shards
  balance by `gametest-durations.json`; `tools/gametest_durations.py` refreshes the table from shard logs when durations
  diverge noticeably. CI has no Lithium run: the mod does not touch what Lithium replaces (block-entity ticking,
  `Explosion.explode` loop); `-PwithLithium` runs GameTest with Lithium as on the host (absent from the jar and
  `mods.toml`), for manual compatibility checks.
- Local batch subset: `./gradlew runGameTestServer -PgametestOnly='<batch name regex>'` (`GameTestShards.only`, works
  with `-PgametestShard`). Nuclear ground-trace checks use batch `nuke_scorch`.
- GameTest places the template 1 block above its origin; without `skyAccess = true`, a barrier ceiling covers the
  platform (and becomes flight terrain). Below the platform is an air layer above the world: for ruin collapse
  (`Collapse`, support counted from the window bottom), the entire platform is an unsupported overhang, so nuclear
  tests fill this layer (`detonation`). Tests run at random distant coordinates; each nuclear test gets its own batch.
- `CruiseMissileEntity.launch` chooses initial altitude from `getChunkNow`; passed `pos.y` does not set it. For a
  ridge-descent test, `level.getChunk` during construction is insufficient: wait for `Terrain.ready` before launch
  and check that actual launch altitude exceeds the tested floor; the previous position for step measurement is
  `missile.position()` after launch.
- GameTest mock players (`makeMockServerPlayerInLevel`) break on Create packets (“simulated:end_sea”); test through
  methods needing only position and UUID (`NuclearStrikes.launchFrom`). To check server packets to nearby players
  (`PacketDistributor.sendToPlayersNear` searches the server player list), use `FuelFireGameTests.Watcher`: `FakePlayer`
  with its own `ServerGamePacketListenerImpl` recording wanted packets and swallowing others, in the player list's
  `players` field (`getPlayers()` is read-only in NeoForge) for the test, without login or world presence. Server-state
  checks do not see transmission (FV review, round 2).
- GameTest servers tick without pauses (`GameTestServer.waitUntilNextTick` only executes tasks): tick deadlines pass
  faster than background generation. Platform chunks load synchronously; neighbors beyond its edge load in the
  background; anything needed by a deadline must stay in chunks whose neighbors are also on the platform (x and z
  16 to 47 for a 64×64 platform).
- Flight scenarios (`gametest/scenario`) repeat exactly: each has its own grid location (far from platforms and each
  other), projectile seed (`StrikeProjectile.seed`, not called in the game; also pins projectile air — world wind
  depends on game time, and CI shards run a batch at different times, which would shift Grad and bomb by hundredths
  of a block from baseline; the bomb takes its B-2's air via `shareAir`); chunk readiness is a function of tickets:
  `InstantChunks` synchronously finishes everything tickets require fully at server-tick end, and brings everything
  granted entity ticking to that state. Otherwise a projectile at the ticking-chunk edge leaves or stays in the world
  depending on whether background generation or a server-thread task finished, and the trajectory diverges from
  baseline. The check for this class is `ChunkChurn`: each tick it takes and releases a distant area and recalculates
  chunk levels itself at world-tick start, before the mod and before each entity tick; baseline is recorded without it
  (`runScenarioRecord`) and checked with it. A solution reading chunk ticking before its tickets are applied diverges
  immediately with it (22.10: `loiter_rail.player` in CI shards). Work-lane clocks (root 1) count work during scenarios
  (`WorkBudgetWatch`): each flight's tick budget is checked, and the “explosion” outcome comes from
  `ExplosionEvent.Start`, running in impact work with the impact tick. Removal of the explosion-selected block is
  recorded by `BlockEvent.NeighborNotifyEvent` with air or fire: explosion batches span ticks, and debris and crater
  falls land in the crater before secondary detonations finish blasting it; final state does not prove removal.
  `StagedExplosion` leaves a position alone if its block changed before the batch. The GameTest world is deleted
  before launch (as in CI). Flight before detonation must not touch `level.random` (shared by all tests in a batch);
  bomb drilling does, so its baseline ends at ground entry. Add disturbances at the end of `Scenario.Disturbance`
  (existing scenarios do not shift); new weapons or target types shift all, so rerecord baseline. Known flaws fixed by
  an open PR go in `Scenario.knownIssues` until merge: only the broken property (loiter or hit) is waived; others remain
  mandatory, and the scenario is excluded from baseline. Scenario loading is ideal — only `-PscenarioRealChunks`
  exposes area waits and synchronous waits for chunks already requested by a ticket.
- Flight scale (`scale.flight_speed`) is a world setting for the entire server: a scale-1 check sets it through
  `StrikeGameTests.flightScale` (CUSTOM preset, restored afterward), running in its own `flight_scale_*` batch;
  otherwise neighboring tests in the batch would fly at someone else's scale.
- Tests must not measure wall time on CI machines (shared runners: any column can stall): GameTest checks nuclear
  queue budgets with counting clocks `WorkClock.counting` (one work unit = 1 ms).
- GameTest servers tick without pauses (~2000 ticks/s on CI), while the mod measures loading waits in ticks
  (1200 = one game minute): a test waiting for background generation of a fresh area must run at game speed
  (`gameSpeed` in `StrikeGameTests`) or generate needed chunks synchronously (`level.getChunk`); otherwise it passes
  locally and fails on CI.
- Before a launch-sector test in CI, the next grid row's platform already stands ahead toward +Z (FV diagnosis:
  another platform's ground 41 blocks ahead of the runway); missile elevation after arming reads terrain hundreds of
  blocks ahead (`Airframe.reliefLookahead`): an “empty runway” depended on shard composition and became blocked when
  new batches reshuffled shards. Tests reading far ahead place the launcher above the grid
  (`StrikeGameTests.laneSite`, 120 blocks above the runway); test-built structures are relative to the rail.
- GameTest surrounds the platform with a barrier wall (ceiling only without `skyAccess`): a projectile entering from
  outside must fly above it. The grid (`StructureGridSpawner`) places 8 platforms per row along +X with 5-block gaps,
  rows along +Z with 6-block gaps, at batch start, and leaves them. Toward −Z and −X are previous batches with their
  walls and structures; +Z and +X are still empty. A roof blocking the surroundings from launcher positions must be
  above the area's highest block (`StrikeGameTests.roofOver`), not relative to the shooter: a neighboring batch's
  structure offered a spot above the roof (CI 16.10). Remove it afterward: such a roof is above the next batch's
  platform-clearing height, and the neighboring test's Shahed hit it. Remove test structures outside the platform
  afterward too (`afterTest`).
- Platforms move each run, changing everything the mod derives from position hashes: block threshold jitter ±15 %
  (`BlockResponse.jitterAt`) moves the destruction edge by −17…+22 %. Law-based checks compare a block with its own
  threshold and place posts at the same fractions of its radius (`BlastGameTests.radiusTest`), not an unjittered
  threshold. World state from previous batches remains: crater-fall areas (`CraterFalls`) live another 10 s after the
  explosion; the next batch's platform is 5 blocks away, so area-limit checks must tolerate a neighboring open area.
- Vanilla starts a test only when all platform chunks tick entities (`GameTestInfo.tick`); beyond the platform,
  chunks are level 32: blocks tick (sand and gravel start falling), entities do not. The explosion area (`AreaLoader`)
  enables entity ticking in rings as neighbors become ready and releases at explosion end: on slow CI machines,
  falling at the area edge would hang — `CraterFalls` places it immediately. Each platform has an air layer beneath:
  the bomb fracture zone collapses the neighboring platform's floor.
- `StrikeService` launch offsets aim by the spec's miss (`WeaponSpec.Route#miss`, Shahed axial standard deviation 3 m):
  compare hits with the projectile's aim point (`aimPoint`), not the target. Explosion work uses counting clocks
  (`WorkClock.counting`) if the check depends on how it is split across ticks.

- Measure strike completion with `StrikeWorld.idle()`, not just an empty `impacts()` queue: after a pause, the bomb
  timeline schedules collapse at tick 22. For sand/gravel, the final pit includes falling into the cavity:
  `SurfaceGameTests` waits for the timeline and crater falls, records `FallingBlockEntity` from spawn (before
  `CraterFalls`, which may cancel spawning and place the block immediately), and separates this from crater ejection.
  The camouflet's three-column displacement limit remains; fallen-block count is a separate quantity, not a crater
  radius adjustment.

- Fixed surface-baseline seeds and `blast_crater_seed` regression: see `warhead.md`.
