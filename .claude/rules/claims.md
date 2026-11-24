---
paths:
  - "mod/src/main/java/ua/zentix/incoming/claim/**"
  - "mod/src/main/java/ua/zentix/incoming/api/ClaimQuery.java"
  - "mod/src/devtest/java/ua/zentix/incoming/gametest/ClaimGameTests.java"
---

# Claim protection (`claim/`)

## Structure
- One mechanism for every Incoming block write outside a real charge's own vanilla explosion list: candidate
  `ExplosionEvent.Detonate` batches on `api/ClaimQuery` (zero radius, no entities, `DESTROY_WITH_DECAY`, no particles,
  silent; never finalized, no explosion packet). Claim mods filter `Detonate`: Open Parties and Claims
  (`ChunkProtection.onExplosionDetonate`, 1.21 branch: claim lookup per position, cached per claim, `block == null`,
  no world read) and FTB Chunks (`FTBChunks.explosionDetonate`, 1.21.1/main: per `ChunkDimPos`, ignores a sourceless
  explosion unless `protect_unknown_explosions`, default true) answer without compat code; any other `Detonate` filter
  is asked at every position. No `StrikeTerrainEvent`, no OPAC `onPosAffectedByAnotherPos`.
- `Claims.offer`: batches of `Claims.BATCH` = 1024 (OPAC removes through the iterator, n²/2 moves for a fully
  protected batch); positions in chunks not ready (`Terrain.ready`) are refused unasked, so no listener can load a
  chunk on the tick; counters (`counters`, `drain`) for logs and tests.
- `StrikeClaims` (`BlastArea.claims`, in memory with the strike's jobs): the real charge keeps its own `Start` veto
  (follow-ups run only if `happened()`, `Shatter.step` included); the real `Detonate` list is diffed
  (`StagedExplosion.denied`; vanilla craft path through `Warheads.onDetonateFirst` HIGHEST + `onDetonate` LOWEST) and a
  chunk with any removed position is denied to every later writer of the strike; elsewhere a writer asks at its own
  positions, attributed as the strike's explosion (`StagedExplosion.attributed`: projectile and strike damage source).
- `NuclearClaims` (SavedData `incoming_nuclear_claims`): denied chunks per detonation id, never lifted; a chunk is
  asked at every position its write changes (`RuinPlan.writes`: cells, fires, one position per scorched biome column
  cell, flash-charred faces); any refusal denies the whole chunk for that detonation (a chunk's ruins are one physical
  result). Sourceless query, centred in the chunk asked. A chunk not in memory gets only its stored answer. Records of
  detonations `NuclearEvents` no longer keeps are pruned at the next denial.
- `BlockGate`: a writer's view (`StrikeClaims`, `NuclearClaims.gate`, `ALL` in tests); `CraterJob.digColumn` asks a
  column's writes together and keeps a refused block with the column under it (no floor glazing, no partial-depth mark).

## Writers (covered unless excluded)
- Conventional: main charge blocks and its fire (native `Detonate`); crater `Craters.dig` → `CraterJob.digColumn` with
  `SurfaceDamage`; glass `Shatter.nextPortion`; fireball ignition `Craters.light`; fuel fire `FuelFires.pour` (relights
  only poured cells); fragments `FragmentStrike.applyBlocks`; bomb bore `BunkerBusterEntity.carve` (the bomb is the
  cause), cavity/crush/fracture/chimney `Warheads.BunkerBlast`; collapse falls (`StrikeCollapse.protectedBearing`, own
  context also follows the strike's denied chunks), direct landing `StrikeCollapse.land`, live rubble (`drop`:
  `disableDrop` where its predicted rest is refused); debris `DebrisEntity.place` (the debris is the cause).
- Nuclear: flash layer `FlashScorch.place`, silhouettes `FlashShadows.flush`, ruins `ScarQueue.claimed` (both apply
  sites: live, zone behind the wave, settlement, on-load; denied → `RuinPlan.EMPTY`, which also puts out layer fire),
  toppled trunks `ScarQueue.placeLog`, crater `CraterJob.dig`, LOD copies (`NuclearPrep.arrivedPlan` gives EMPTY,
  `FarLods` scorch ring skipped, `FrontLods.start` skips) by stored denial.
- Excluded: grid outages and lamp twins (not block damage); `CraterFalls` vanilla falling sand placed on its own support
  (vanilla gravity in its own column, after a removal that was itself asked); vanilla explosions (crash before arming,
  craft and plot-grid blasts, nuclear front on craft) fire their own events; later fire spread (vanilla fire).

## Pitfalls
- Never post a query for a position in a chunk not in memory, and never post one off the server thread.
- Never centre a query where the world around it is not in memory: explosion listeners read near `center()` (Create
  Diesel Generators 1.3.15 reads `getFluidState` within 2 blocks of it on every `Detonate`, no load check), a
  synchronous load on the tick. Nuclear questions are centred in the middle of the chunk asked (`NuclearClaims.query`),
  and `Claims.offer` moves any other query to the middle of its first ready chunk (`ClaimQuery.at`); GameTest
  `claim_review_centre`; the collapse's questions go through `Claims.offer` too (`StrikeCollapse.protectedBearing`:
  an unready position is neither asked nor protected). Nuclear centres sit under the build limit, in air. Such a listener also treats each question as an explosion near its centre (CDG removes fuel
  there): a foreign effect, kept in mind when choosing centres.
- Ask a portion at once (`Writes`, `BlockGate.filter`): each event runs every listener. Glass (32 panes a unit), fireball
  cells and direct rubble landings still ask one position at a time, bounded by their unit size.
- A strike area without a charge (the test collapse, `Warheads.testCollapse`) asks sourceless: FTB Chunks with
  `protect_unknown_explosions` off would not protect against it. Every play path has a charge or an entity as source.
- Chunks already denied are still planned in the background (preparation, prechecks) and dropped at `ScarQueue.claimed`:
  wasted work bounded by the claimed area.
- Our own `Detonate` listeners must skip `ClaimQuery` (`Blackouts.onExplosion`); an Incoming explosion map
  (`CHARGES`, `RULES`) never holds a query.
- A LOD copy of a disk-only chunk cannot be asked: it shows ruins unless the chunk was already denied; the world write
  asks when the chunk loads, and the far view follows the loaded chunk.
- GameTests `ClaimGameTests` (batches `claim_*`): every weapon, bomb and wreck beside a claim edge; cancelled `Start`;
  follow-ups asking alone (a handler filtering only questions); query is no explosion and loads nothing; nuclear
  claimed chunk, stored and per-detonation denial; claim change between flash and wave, save and load; query centre.
