---
paths:
  - "mod/src/main/java/ua/zentix/incoming/warhead/**"
  - "mod/src/test/java/ua/zentix/incoming/warhead/**"
---

# Warheads (`warhead/`)

## Structure
- `warhead/` — `Warheads` (a vanilla explosion split into parts — `StagedExplosion`: `ExplosionEvent.Start` and the force of
  all 1352 rays (with the same `level.random` as vanilla) as the first unit; the rays in units of 128 in a loop, a copy of
  vanilla's (resistance through the explosion calculator with the real level on the server thread; a repeated ray point
  from the cache); then gathering entities and `Detonate`; damage in portions of 8 entities (the explosion packet with
  knockback — for a player hit, in the same unit; for the others within 64 blocks — at the end); world blocks from the
  center outward in time portions (~5 ms, at least one block; batches of 16 shuffled; the clock and the deadline are fields
  for checks, GameTest `blockPortionsByTime`) by steps of `finalizeExplosion` (a block taking longer than 10 ms — a line
  “Blast: removing block X took N ms” with the block entity, the first 5 per strike; drops — one list per explosion, with
  decay as for a creeper (`DESTROY_WITH_DECAY`: a block drops with probability 1/force; TNT by default drops everything,
  and after salvos every 30 ticks thousands of items take two thirds of the server thread; in a grid of plots and at
  craft from the nuke — `ExplosionInteraction.BLOCK`, rule `blockExplosionDropDecay`; GameTest `blastDropsDecay`); a block
  that is a different `Block` than on the rays is not touched; explosions of one area placed in the same tick — bunker
  buster detonations — remove blocks when all of them have chosen by rays and hit entities: `BlastArea.peersPicking`,
  otherwise the crater and the sand spread would depend on server speed; GameTest `areaExplosionsPickTogether`); a Sable
  craft within the rays' reach (`Warheads.reach` = 2 × force + 1: in air a ray goes up to ~1.73 force;
  `SubLevels.mayHaveCraftNear`, GameTest `craftInRayReachGoesVanilla`) or a broken companion — vanilla `Explosion.explode`
  in the first unit (the Sable mixin at every ray step in air looks for and pushes craft; `Detonate` is in it too; the mod
  does not touch the method itself; damage in portions: the mod's `Detonate` handler at priority `LOWEST` takes the entity
  list of the nested explosion and clears it, and the vanilla damage loop runs over an empty list —
  `StagedExplosion.onDetonate`; a missile near an airship — 181.6 ms in one tick with vanilla damage), a snapshot of world
  blocks — the next unit; its craft blocks are removed in one go after damage (step “aircraft blocks”; as in vanilla: the
  hull shields from damage while intact) — those whose plot is in a chunk of the same craft and whose block is the same
  as at disassembly: the plot after a split could have gone to another (GameTest `vanillaPathDamagesDetonateList`,
  `craftBlockLeftByDetonateStays`, `craftHullShieldsLikeVanilla`); explosion steps for measurement — `ExplosionStage`,
  vanilla `explode()` — in one step (GameTest `raysMatchVanilla` — its own loop gives the same blocks as vanilla at any
  unit size; `craftBlocksGoRightAfterDamage` — for a craft the path is vanilla); an explosion centered on a craft — entirely
  `level.explode`, with its own DamageSource and a silent sound; an underground bomb explosion with a weakened block set;
  knockback), `Shatter` (glass in a ground explosion — as a ball by the pressure of the warhead charge's wave: passport
  `charge`, kg of TNT, Kinney–Graham `BlastModel.surfaceOverpressureKpa`, the glass threshold and its spread — as for the
  nuke, `BlockResponse.breaksAt`; the ball goes down to the lowest threshold, Shahed ~84 blocks, missile ~176; the wave
  travels through air: glass only if air from the street reaches it — the place above the top of the
  `MOTION_BLOCKING_NO_LEAVES` column no further than `Shatter.SKY_DEPTH` + 1 steps through openings and glass, as
  `Blast.SKY_DEPTH` for the nuke (an underground base, glass under water intact); missile foliage — as a box; sections
  from the center outward (the list — in the first unit, not in the strike tick); a section without glass is cut off by
  the palette `util/Palettes.contains`; a unit — up to `Shatter.PORTION` (32) panes of a section above the pressure
  threshold and up to 1024 cut-off sections; the sound and the fragments — at the windows themselves: per unit one
  broken-block sound with volume √n in the middle of its broken glass (audible up to 16√n blocks) and up to 64 particles
  along its frame — previously 2–3 sounds and hundreds of particles in the epicenter, where there is no glass (BLASTSEQ
  D13, GameTest `glassSoundsFromItsWindow`); chunks with broken glass — in DH; a box around the explosion instead of a ball
  would leave colored windows intact right beyond its edge), `DebrisSpawner`, `GroundMaterial`, `CraterFalls` (collapse in
  the area of the explosion while `BlastArea` holds it, and 10 s after: no more than `LIVE_CAP` live falling blocks per
  area (the areas of neighboring explosions overlap — a fall is counted for each whose square it hit) and `TOTAL_CAP` per
  world; other falls of sand, gravel and concrete powder — a cancelled `EntityJoinLevelEvent` and the block placed at once
  on its support, if the landing is vanilla's — only “place the block”; without a limit, the craters of B-2 would have
  151+ entities).

- The warhead explosion of Shahed, missile, Grad and Lancet — by the passport charge (`WeaponSpec.charge`, kg of TNT), not
  by vanilla force (`StagedExplosion` with the charge; vanilla force is kept only for a collision before arming):
  an `Explosion` with radius `breakReach` (how far the wave breaks anything at all) is used only for events and drops; the
  rays — vanilla's 1352 directions, pressure and impulse by Kinney–Graham on the ground (`BlastModel.surfaceOverpressureKpa`,
  `chemicalImpulse`); a block breaks along the pressure–impulse curve of its threshold (`BlockResponse.breaksAt(psi,
  impulse, seed)` — Jarrett's curve, `nuclear/model/BlastDamage`; the nuke calls the earlier `breaksAt(psi, seed)`); a
  broken block passes a share `passes`; an intact one, the ground (`nuke_ground`: the crater digs it), liquid, and a block
  with resistance ≥ 1200 — shadow; a block without a collision shape (grass, flowers, torches) does not stop the wave,
  broken or not (otherwise grass at the ground would close the bottom of walls — GameTest of radii runs on grass); the
  strike block (the center) removes contact, rays go out from its faces. Then the gases (`Room`: a walk of the air up to
  4000 cells; when it ends — a room, pressure `BlastDamage.quasiStaticKpa` on the walls, the impulse — until it exits
  through openings: glass, fragile blocks, wooden doors and hatches, what the rays broke through). Bodies —
  `nuclear/world/BlastBody` through `BlastBody.Wave` (the pressure of an open field where the center is visible,
  `Explosion.getSeenPercent`; in a room — the gases), up to the glass fragment threshold (0.8 psi); items, frames, carts —
  where the wave breaks light structures (4 psi). Sable craft — a vanilla explosion in the center with the `CraftCharge`
  calculator: a plot block breaks by the same law by distance in the world, entities are not touched, knockback — Sable
  (not physical); force — up to the far point of the craft within the wave's reach (`craftPower`; over the whole reach the
  explosion would read the world at ~1.9 reach and cost the peak of a tick), the strike area at a craft — up to
  `craftReach`; while it runs, `chargeOf` returns the charge, and the entity list of its `Detonate` is empty. The explosion
  charge for event handlers — `Warheads.chargeOf` (blackout: a substation is knocked out where the wave breaks 4 psi). An
  explosion cancelled by protection claims does not dig, burn or throw debris; on a craft — neither a crater nor a fuel
  ball. Behind the main explosion of a strike come the units of `Craters.dig` (the crater `CraterModel.chargeRadius`:
  0.65·∛W across, a quarter in depth, the profile and rim — the same `CraterJob.digColumn` as for the nuke, mode `DIG`:
  only ground, whole blocks with a random addition by the fractional part; not if the charge above ground is deeper than
  the depth), `Craters.ignite` (the fuel fireball `FuelFireball` — without the crater share `POOL_SHARE`, one number and one
  ball with the client: `FuelFireball.Sweep` — growth, rising to 0.75·D and drift by the world's wind, as in the picture
  (`CloudModelTest.serverFireballIsTheBallThePlayerSees`); the impulse of a place — for the whole burning; inside the ball
  full, outside — (r/d)²; a walk through air from the near cells to the far ones (not from the larger impulse: for a risen
  ball it is largest in the air above it, and the limit would end the walk there), up to `PulseIgnition.FLOOR` and not
  above the whole column and its neighbors (`Ignition.ceiling`: the sky above the missile ball — 280 thousand cells without
  fuel); below the detonation point — everywhere (a detonation in air, into a roof breach); GameTest `blast_fire_roof` (a
  canopy 63×63) and `blast_fire_air` (a detonation at 6 m); in portions of `Craters.PORTION` cells per unit of work — the
  missile's 40 thousand cells at once cost 0.2 s of a tick; the impulse of the ball ignites thin fuel by the threshold of its
  class, `nuclear/model/PulseIgnition` (foliage, paper, grass, cloth — tags `pulse_*`; without a tag — by vanilla's ignition
  rate ≥ 60 like grass); thick wood — no; the decision is once per block, the fire is on the fuel itself: a plant without a
  shape burns in its own place; for a solid block — flame in the air at the face, if the floor under it is the fuel itself
  or not solid: vanilla fire with a solid floor is drawn on the floor, and sand nearby would “burn” (complaint 23.10);
  GameTest `blast_fire_*` — no fire of the ball at logs and on an empty floor), fuel in the crater (`FuelFires`, below),
  debris, glass. There are no secondary detonations, no fire ring and no knockback belts for a ground explosion; a burning
  debris ignites only fuel nearby (`DebrisEntity.touchesFuel`). GameTest `BlastGameTests` (Jarrett radii, a room, a crater,
  fire, bodies); the detonation in flight tests — `BlastLog`.
  `S2C.Blast.surfaceY` for a ground explosion is the strike point itself: “where the ground is” the server does not decide
  (Artem's decision 23.10: “the universe does not check whether this is a roof or not”); the surfaces around, their
  material and height the client sees itself (`FarBlasts.ground`, the dust law — `client-fx.md`). For a bomb — the top above
  the charge (`BunkerCover`).
- Fuel in the crater (`warhead/FuelFires`, Artem's decision 24.10: there are no puffs of our own flame — Minecraft fire on
  blocks) — its own unit after the crater: the law `nuclear/model/FuelPool` (shared with the client: the tank share
  `POOL_SHARE`, the area of the crater circle, burnout of a thin layer; passport `WeaponSpec.pool`, the fuel — by the
  cruise engine `WeaponSpec.fuel`) decides how many cells (area in m², Shahed 5, missile 9) and how long each burns
  (`FuelPool.cellSeconds`: the burning area is the share `share`, the edge goes out first, the lowest cell burns the whole
  term — ~2.5 and ~16 min). Where — blocks: from the strike point down to the first block with a shape (the bottom of the
  crater, the floor under a breached roof); onto water and into the void — no; spreading — first downward (from the edge
  — down to the support), then nearer, one step up to a block along the surface (splashes over the whole bowl), onto a
  wall and through it — no; a cell — where vanilla fire holds (`holds`) and only with ready neighboring chunks: vanilla
  looks at the six neighbors of the cell, and at the chunk edge the neighbor would load within the tick (review FV; on a
  solid support vanilla does not read the neighbors, so GameTest `fuel_fire_edge` — a bottom slab at the edge of the far
  chunk with a board next to it); what fits in a smaller area burns longer and weaker (`WeaponSpec.pool(lit)`, the client
  does the same). Fire — `BaseFireBlock.getState` (on non-flammable — on the top face, as on netherrack), it is held by
  resetting age (`setBlock` with age 0 and `UPDATE_INVISIBLE` — as vanilla changes age, without a mixin; otherwise on stone
  it goes out in ~20 s); gone out not by fuel (rain, a hand) — lit again through `RELIGHT` (5 ticks): a burning pool of
  hydrocarbons is not put out by rain (⚠ estimate of the heat balance in `RELIGHT`). Gone out faster than `QUICK` (5 ticks)
  after ignition — the world does not hold fire (Burnt under rain puts out its own fire every tick): the next attempt is
  twice as late, up to `RELIGHT_MAX` (10 s) — without this, 30 missile craters would give ~1000 relightings per second
  (review FV; measurement with Burnt under rain: fire 1.3 % of ticks, in steady state ~27 per second for 30 craters); the
  fuel burns by the law meanwhile — smoke, light and rumble go to the client. Vanilla rain and Burnt in dry weather put
  out fire in their own tick, after 30+ ticks — their backoff does not affect it (GameTest `fuel_fire_rain`: 30 craters in
  rain — fire on 100 % of the check's ticks, ~0.11 relightings per cell per second; in the same batch the world puts out
  the cell's fire on the next tick — 8 ignitions per cell in 30 s, limit 10, without backoff ~120). A cell buried or
  flooded — its fuel ran out before its term; the support burned or was broken — the fuel drips down in the same column
  (there is no fuel fire in the air — complaint 24.10: puffs hung where the roof was). Burnt out — the fire is removed if no
  fuel is nearby (a tree burns further by vanilla rules); the cell's chunk is not ready — it waits and is removed when the
  chunk is ready again (otherwise with `doFireTick` = false the fire would stand forever; GameTest `fuel_fire_unload`).
  Someone else's fire in the cell — it burns, age is reset, only if the block has it: Burnt (probe FV, 24.10) places its own
  `burnt:firestarter` (a successor of `FireBlock`, `FireBlockOnPlaceMixin`) instead of vanilla fire everywhere except the
  floor from its tag `burnt:vanilla_fire_burns_on` (stone, brick), and on ground it puts it out itself. For clients —
  `S2C.FuelFire`: the state of the crater (number, cells in burnout order, which ran out before their term, the weapon,
  the strike tick) on appearance, on every change, and to whoever receives its chunk (`onChunkSent`); smoke, light and
  rumble — on the client (`client-fx.md`). Saved with the world (attachment `fuel_fires` with a codec: after a restart the
  fuel burns out in its term, `FuelFiresCodecTest`). `fire = false` — no fuel fire either. GameTest `FuelFireGameTests`
  (`fuel_fire_*`, with `doFireTick` — it is off on the GameTest server): in every tick every cell is fire on a support, the
  last one goes out in the term of the law, and after that there is no fire on bare ground; a roof — fuel on the floor; the
  flooring is gone — it drips down, and a player nearby gets new places over the network (`Watcher`: a packet listener in
  the server's player list; how the client applies them — `FuelBurnTest.serverUpdateMovesTheClientSource`: a packet through
  the format → `FireSmoke.fuel` → the hearth and light in the new place); buried and flooded — the client gets the ones
  gone out; on water — no; rain; the chunk edge; unloading.
- Casing fragments (S4): the law — `FragmentModel` (shared with the client, without Minecraft): the casing is a cylinder of
  explosive in steel of diameter `Article.Charge.diameter` (thickness and length — from the masses); speed — Gurney; masses —
  Mott; a belt across the axis with Taylor's angle and the impact speed of the passport; deceleration e^(−x/L); damage —
  `injury`/`death` by energy (79 J DDESB); the hazard distance — `hazardDistance`. On the server — `FragmentStrike` (units
  `ImpactCost.Kind.FRAGMENTS` after the main explosion, only if it was): a snapshot of the living in `reach()`
  (`getAllEntities` into a list), each fragment — a ray (up to `MAX_TRACED`, otherwise a weight); a body on the path takes it
  with the silhouette share `BODY_FILL`; blocks — `FragmentTrace` (a walk of cells through ready chunks, `getChunkNow`; an
  unready one — the ray ends; glass `SHATTERS` is pierced and hits, without a collision shape — flies through, liquid and
  solid — stop); the dent — the vanilla cracked look of masonry (`CRACKED`); damage — `BlastInjury.lostShare` with its own
  body randomness and `invulnerableTime = 0` (the invulnerability window after the wave would eat the wounds). The seed and
  the axis (`Warheads.axis`: the last step of the projectile) — the same as in `S2C.Blast`: the client leads the first rays
  with the same `FragmentTrace`. Sable craft neither hold nor break the fragments. GameTest `FragmentGameTests` (1/R², a
  wall, glass, dents).

## Pitfalls
- A support, not a block name (`SurfaceBlocks`): the Jarrett threshold for a panel applies only with a free back face.
  A solid block behind the loaded face holds it; the wave's ray stops. The ray face is the actual entry into the cell
  (`SlabBreach.exitFace`); for gases — each face toward the room's air. The normal cannot be chosen by the vector “charge →
  block center”: at a tall wall above the charge at the floor it is steep, but the gases load it horizontally; otherwise the
  neighboring wall block above would wrongly count as the back support. A tall room and `work_budget` cover this case.
  Contact rays into an obstacle go only through `SlabBreach`; air rays — from the outer face (not from the aim point half a
  block deep). Contact does not remove a block without a law. Cover on the ground enters the crater's surface only up to the
  impact height (`excavationTop`): a wall, a pillar or a tree above this plane does not raise the bowl and the rim; the pit
  is not dug under intact concrete. Concrete by the masonry tag — `STONE`, not the ground under it. Natural ground, separated
  from the slab by air, is not counted as support.
- Fractional depth in a cohesive material (`CraterJob.Mode.DIG`) — `SurfaceDamage`, not a random removal of a meter of the
  slab: whole meters are removed, the remainder of the depth is kept with the chunk, cracks — by vanilla block-break packets
  (refreshed every 100 ticks, otherwise the client forgets them after 400). The visible main trace — vanilla `block_display`:
  the exposed filler and chips of material, saved with the world and visible at the normal entity distance, not only within
  32 m of vanilla cracks. This is an approximation of the look of a broken surface, not the removal of the collision
  remainder. Displays keep an anchor and are removed when the block changes, including after a reload. The collision shape
  of an intact block is kept; there is no damage accumulation law and no weakening of the load-bearing section. Loose
  ground keeps the unshifted random addition of whole blocks. `Mode.VAPORIZE` of the nuke is unchanged. `SurfaceGameTests`
  checks the volume removed + damaged in a cohesive medium; all the rows of coverage and small charges are included.
- The crater's ground (`GroundMaterial.soil`) — by water saturation, not by appearance: dry sand is the reference for the
  curves (TM 23-200), gravel is its own `GRAVEL` (loose, like soil), not `STONE` (up to 3.6.1 the railway embankment was dug
  as rock, ×0.7); saturated — only the bottom under water. Natural blocks (`nuke_ground`) are shadow for the wave at any
  thickness; the crater digs them as a half-space; a slab over a void (a bridge deck, the roof over a room, a cave vault) is
  breached only by the breach law (`SlabBreach`, below), not by the wave.
- Breach of a barrier by an applied charge — `BreachModel` (without Minecraft; FM 5-250 1992, formula B-7 K = R³·M·C, table
  B-3, fig. 3-15: on a face without backfill C = 1.8, in the mass C = 1.0; inverting the formula and summing the layers
  Σ path/R ≤ 1 — ours, estimates) and `SlabBreach` on blocks: how the charge is applied is decided by the caller
  (`SlabBreach.Placement`: the warhead strike — `SURFACE`, if not on a craft; a bomb in the ground — `BURIED`; the share that
  went out and `testCharge` — `NONE`). `SURFACE`: the center is in a solid block (the aim point — half a block into the
  ground) — the charge lies on the open face of the block, rays from it inward (otherwise the shallow rays from the depth of
  the block would exit upward nearby and shear off a disc of the top layer); two opposite faces open (a deck into a block)
  — from the center in all directions; the center in the void — applied only if the face of a solid neighbor is no farther
  than the charge ball's radius (`chargeRadius`, missile 0.28 m; a detonation at a body in air does not breach).
  Breach rays — the same 1352 directions, in units of the wave's rays: path in the block / R of its group (the data map
  `incoming:penetration`, field `breach`, by default — by the medium's kind; a block without an entry — by the harvest tool:
  axe — wood, shovel — ground, pickaxe up to hardness 2 — stone, otherwise — reinforced concrete, as FM says for an
  unidentified one; do not add new entries to the map for the breach: an entry changes also the rubble of the bomb's
  crushing zone, `Warheads.rubble`); exited into the void (air, liquid, no shape) under a ceiling (`SlabBreach.covered`:
  above in the column, below the height map without foliage — a solid block not of foliage) with a sum ≤ 1 — the passed
  path is in `chosen`, the wave goes further into the void with the full share (an upper estimate). The void under open sky
  (the surface at the strike, a terrace of relief, the bomb's shaft, open water) is not a breach face but a crater face:
  without this a missile at a terrace would shear a wedge of earth into a block up to 4 m (the earth's R is 4.2 m — 2.4 times
  the crater radius), and a bomb with λ < 1 would dig a cone to the surface beyond `BuriedBlast` (review #420; GameTest
  `blast_breach_terrace`, `blast_breach_buried_open`). Indestructible, resistance ≥ 1200, the map's `STOP` and protection
  claims — the ray stops. There is no collapse of a weakened span (the mod has no load law). Bodies and charge blocks go in
  one front from the nearest to the farthest (`StagedExplosion.FRONT`), without mixing: a block broken before the front
  reaches a body is no longer a screen; farther blocks still stand. The arrival time — the integral of dr/U(P(r)),
  `BlastModel.chemicalArrivalSeconds`; the server step is 50 ms; the budget may postpone execution. Glass and the reflecting
  wall at a body are remembered until the blocks are removed (`BlastBody.Before`); the vanilla collision path is unchanged.
  GameTest `BreachGameTests` (a 1 m missile — hole √(R² − t²), 3 m — intact; Shahed 1 and 2 m; a bomb charge in stone — the
  void at 4.5 m is breached, at 9.5 m it is not).
- The server's vanilla explosion (`Level.explode`) sends its own particles to clients too — TNT puffs (`EXPLOSION_EMITTER`):
  over the mod's picture they read as white rings with a dark middle (the B-2 bomb in a city — three detonations of force
  12–20 at the surface). All warhead explosions pass the empty particle `ModParticles.NONE` (the client's provider creates
  nothing), as well as the silent `ModSounds.SILENT`; the picture — only `BlastEffects`; a collision before arming
  (`StrikeProjectile.crash`) stays vanilla. The depth of a bomb's detonation — from the top above the charge without its
  own shaft (`BunkerCover`: the lower median of the ring of columns, foliage not counted; the height map in the charge's
  column — the bottom of the breached passage).
- An underground bomb explosion (`Warheads.BunkerBlast`) — the law `BuriedBlast` by the scaled depth λ = d/W^⅓ (shared with
  the client: the cloud and the sound — the share of the charge that went into air, `airCharge`): first the wave of the
  charge `charge` from the detonation point (natural ground — shadow, so it hits only what is in the void: the bunker, a
  cave, the cavity itself); at λ < 1 — also the wave of the share of the charge above ground (two explosions of one area and
  tick — `areaExplosionsPickTogether`); after them, in units — bodies in the crushing zone, the cavity and the crushing by
  layers, the vaults in the crack zone by layers (gravel falls on its own; the collapse is held by `CraterFalls`), the ejection
  crater (`Craters.dig` with the radius and depth of the law), gases from the shaft (`S2C.Vent`), through `CHIMNEY_TICK` — the
  collapse chimney of the vault (the sinkhole — `S2C.Collapse`). The bomb's entry into the ground — without an explosion: a
  strike packet with the speed and the knockback by the strike energy (`BunkerImpact`). The area holds the crack radius, not
  the reach of the charge's wave: in unready chunks the rays end on their own. GameTest — `BombGameTests`.
- The warhead explosion is not `Level.explode`, but its steps by units (`StagedExplosion`): two vanilla explosions of force
  12–20 in a city at the host — 559 ms in one tick, so the rays and the damage run in their own loop in portions. NeoForge
  events (`ExplosionEvent.Start`/`Detonate`) and the block `onExplosionHit` work as usual; mixins on `Explosion.explode` do
  not fire on our own loop — that is why an explosion next to a Sable craft goes through vanilla `explode()` whole; wrappers
  of `ServerLevel.explode` itself (in Sable — the transfer of an explosion from a plot) do not see it, so an explosion in a
  grid of plots is entirely vanilla `level.explode`. Work posted from work (an explosion wakes handlers) goes to the end of
  the queue — a walk of `UnitQueue` by index.
  Mixins of the host's pack on the explosion path (the jar scan — `/mnt/project-files/reports/explosion-mixins/laptop-explosion-mixins.sh`,
  repeat when the pack is updated; the analysis — javap): amendments 2.1.10 `ExplosionMixin` — only `FireballExplosion`, not
  ours; supplementaries 3.9.9 `ExplosionMixin` — the client's `finalizeExplosion`, reached by our `ClientboundExplodePacket`;
  `SelfGunpowderMixin` — `GunpowderBlock.onBlockExploded`, called through `onExplosionHit`; collective 8.39 — a Fabric config,
  does not load on NeoForge; createpropulsion 1.1.5 — a CBC projectile, not ours; Lithium — a calculator by entity, we have
  no source; copycats `CopycatBlockMixin`/`MultiStateCopycatBlockMixin` — resistance depends on the place, correct through
  the calculator with the real level; alltheleaks `BlockBehaviorMixinSquared` — the jar is not on Modrinth, not analyzed.


- The crater of a surface explosion uses its own seed (`SurfaceBlast.seed`), not the shared `level.random` during deferred work. `SurfaceGameTests` sets the seed from the relative impact point through `Warheads.testDetonate`: the sampling of loose ground does not depend on the order of batches or the random place of the site. `blast_crater_seed` compares identical strikes after extraneous samples of the world generator; the earlier volume limits are not widened.

- Charge at Sable: a craft is an event of the front before the bodies behind it, not a final stage after the whole world. A scan of
  the ready cells of the plot (256 per unit) finds the nearest solid block taking the pose into account; void inside the envelope
  does not count as a hull. Events of craft, world blocks and bodies are merged by distance; `CraftCharge` chooses only the plot of
  this craft. The `work_craft` test checks removal of the chosen hull in the damage callback of a body behind it, not in its own
  Detonate. A block broken by pressure/impulse does not additionally stop the ray with TNT's vanilla resistance; an intact hull and
  the ground keep their resistance (otherwise in a three-meter hull only the front boards would disappear).
- 1352 directions (an even 16³ grid) are an approximation of the wave's angular resolution. Beyond about 7.5 m the gap between rays
  can be wider than a meter, and a single column falls between the rays. The measurement reference chooses the cells actually
  sampled; this does not fix the density of the wave itself and does not promise a hit on every thin target.

- Partial damage `SurfaceDamage`: the depth is in the chunk's attachment; the picture — one `noSave` display per place, at most
  `DISPLAY_CAP` (256) per dimension. Restored near players every 100 ticks, removed far away. Old saved vanilla displays #437 are
  cancelled at `EntityJoinLevelEvent`, even without a chunk entity tick. The radius of craft found by scanning the front is held by
  a separate `BlastArea`; its readiness is in `ready()`, so the wait passes through the term `UnitQueue.GIVE_UP_TICKS`, without
  spending units in `front()`.

- Presentation of a partial break: the standard `DisplayRenderer` applies the synchronized transformation; the area of the picture
  is proportional to the saved depth, the height of the chips is `min(0.15, depth * 0.3)`, as in #437. Do not lose the
  transformation in your own renderer. Uneven burn/filler patches are drawn inside the same damaged cell.
- `DebrisSpawner`: the ground ejecta is separated from the hull parts. An intact connected surface gives fine crumbs; large boulders
  remain only on loose ground. `wreck()` and burning debris keep their earlier placement under `debris_stay`; fire follows the earlier
  fuel check. Do not turn the whole debris stream into `chip()`.
