---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/**"
  - "mod/src/test/java/ua/zentix/incoming/client/**"
---

# Client (`client/`)

Particles and the effects layer: `client-fx.md`; models: `client-models.md`; sound: `client-sound.md`; map and DH: `dh.md`; replay: `replay.md`; the nuke: `nuclear.md`.

## Structure
- `client/` (`@Mod(dist = CLIENT)`) includes `render/` (`WeaponModels`: projectile OBJ models and animations from
  `ProjectilePose` (one code path for entities and distant tracks) and flight phase: propeller, wings, intake, doors,
  booster, MLRS rocket spin; MLRS rack is also OBJ, trailer and other racks (Shahed frame, missile containers,
  loitering-weapon catapult) use blocks, `LaunchModels`; slots are `LauncherEntity.railPoint`; `PlumeRenderer`: engine
  plume), `sound/` (sound delay and Doppler: delay-equation solution, `EngineSound`; projectile track is
  `flight/FlightTrack` (shared by sound and distant visuals, registry `flight/FlightTracks`): from entity, otherwise
  `S2C.FarFlights` packets, one track per UUID; explosion packet `S2C.Blast` names the projectile, ending its track on
  explosion tick (`FlightTrack.impact`); engine sounds until the front reaches the ear, then fades beneath it
  (`ClientSounds.FADE`): the last distant-projectile packet can precede the explosion by several ticks, otherwise
  engine would stop before the front, leaving silence before explosion; `BlastSounds`: all explosion sound by range,
  charge from spec (`fx/BlastSource`, weapon in packet): within 640 blocks, perspectives of one recording `BlastMix`
  (near/mid/far, same variant from explosion seed, amplitude crossfade: far by 4 kHz air loss, near versus mid by shock
  highs at ear; pitch/duration ∛(50 kg/W) by Sachs within engine pitch 0.5–2; volume from Kinney–Graham ear pressure,
  full at 1 psi, below half its decibels like the nuke; chest hit is near-perspective bass); ear hit where near fades
  is `BlastPulse` on own source; terrain echo `BlastEchoes` (shared with the nuke; new set only when it has own
  sources beyond hit reserve); motors briefly duck under loud explosion (`Ducking`); player-body hearing
  (`HearingModel.exposure(pa, t₊)`, `Deafness`) and body jolt (`BodySway` through `CameraShake.sway`) are `fx/BlastHit`;
  beyond 640 blocks, `far` under `Outdoor` (ISO 9613-2 in two bands, 63 Hz and 2 kHz: terrain-edge obstruction along
  ray, ground from `GroundMaterial.porosity`, daytime shadow from ray/ground contact (~1 km for ground explosion),
  rain; audibility margin M = 20·lg(R0/d) − losses, R0 is `HearingModel.audibleRangeM` by charge (wave A-energy with
  ISO 9613-1 absorption against quiet-night background, upper estimate 140–360 km, capped at
  `IncomingConfig.FAR_RANGE_MAX`, 32 km); volume continues near model at its edge, `BlastMix.v640`, no step/kink at 640);
  `SoundFilters`: EFX air/obstruction/deafness filter), `far/` (projectiles/explosions beyond render distance:
  `FarRenderer` collects into shared effects-layer pass `fx/layer/FxLayer`; `FarBlasts`: fireball
  (`fx/particle/fireball` animation from flash to soot, same as nearby particles, same size/time; frame at front half
  of sphere, `FarSprites.BALL_FRONT`: a plane through center would sink sphere bottom into ground when viewed from
  above; below 2–4 px, circle with same light), flash/fireball glare and veil at any range, including nearby (in the
  eye: eye-local quad, not depth-clipped; source occlusion by terrain rays beyond render distance and block rays
  within it, visible-disk share, 7 rays; two rays to sphere axis at a tower corner would both hit corner, leaving
  visible half shining without glare; frame glare share also takes screen probe, `fx/layer/SourceProbes`: world
  distance including DH LOD at sphere, see `client-fx.md`); night cloud glow (ball lights underside; eye dark-adapts,
  `Sight.adapted`); for an occluded ball (behind ridge/house at any range), air halo `FarSprites.glow` while ball emits,
  no brighter than open-ball veil (`FarBlasts.behind`: full strength at night filled half frame), quad where lit air
  begins, soft edge at its depth (quad at source would cut houses at same distance into dots/strips and cover DH LOD);
  explosion cloud is `fx/smoke/CloudModel` (same near/far: `FarBlasts` event is cloud source, rendered by baked layer
  billows, `SmokePuffs`; sphere uses baked combustion frames from model-ball state, size its radius, brightness
  black body at its temperature, cooling fades without step); roll when front reaches ear; bomb uses same model
  clouds (`FarBlasts.cloud`: breach is charge share in air with ejecta-crater dust; deeper, gas from channel mouth
  via `S2C.Vent`; entry/collapse dust are heatless clouds, `CloudModel.dust`); `FarFlightView`: all projectile trails
  as ribbons along nozzle track points, shared near/far (entity/packets; `TrailPoints`: all-projectile ring buffer),
  distant body uses real model (`render/FarModels`: each frame model has own atlas tile, eye perspective at four times
  screen detail, with mipmaps; camera-facing quad in shared effects-layer pass; below 3 px screen length, transitions
  to equal-area circle; below 40 px, simplified part copies; bank in packet), plus plume; appearance `FarLook` and
  `Trail` in `ClientWeaponSpec`; shared: `Sight`, Koschmieder haze (σ = 3.912/V at sea level; clear 20 km, rain 5,
  thunderstorm 1, `util/Visibility`, same for flash light; `Sight.Air` thins with altitude, aerosol 1.2 km, air 8 km,
  `FarView.air` by point height above eye); all real angular size without minimum (subpixel becomes 1.5 px spot with
  same energy); bright objects get Stiles–Holladay glare/veil (degrees at night, absent by day); `Sightline`: one ray,
  up to 64 terrain points (`FarTerrain`: client chunks, otherwise map tiles); `FarSprites`: all distant visuals as
  layer-frame quads (models as `FarModels` atlas tiles; near-transfer multiplier and real distance let layer hide
  distant visuals behind terrain/DH LOD), premultiplied-alpha blending (haze via opacity, not color);
  `FarRenderer.describe`: `SCENARIO far` line in client scenarios), `fx/` (screen flash `FlashFalloff`: glare decays
  as near/d, only explosions in view dazzle; by day screen barely brightens, sphere illuminates surroundings by a few
  percent of sunlight (`FlashFalloff.daylight`; caves/houses/night get flash: eye adapts to surroundings,
  `FarView.adaptation` from sky light at eye); camera shake without moving aim (× vanilla “Distortion Effects”);
  ground jolt `CameraShake.ground` (nod, body 5.1 Hz) with ground-wave arrival (`GroundShake`: `GroundShock` at feet,
  rumble `BlastSounds.quake(ppv)`, ρ₀c₀·v; age is source ticks from server elapsed time via `BlastTime`, not `Effects`
  invocation count, which calls effect twice on birth tick; packet later than entire jolt does not replay it);
  concussion screen effect `Concussion` (GUI layer below HUD, vanilla `PostChain`, program `incoming:concussion`:
  Gaussian blur, grayness, dark edges; dose/time `ConcussionDose`, strength `screen_effects` × “Distortion Effects”);
  litter within 16 m of camera, `NearLitter` (`LocalLitter` from local surfaces: upward-open surfaces with sky light
  in sphere around eye, grass under canopy too, nearest first; `Fragments` pieces drawn larger but fly as law's body,
  fall speed passed separately from size; fine dust; world wind thereafter); concussion/litter only with
  `blast_effects` (`BlastHit.feel`: air-defense burst reaches body even when disabled); `Explosions`: solid explosion
  matter as particles (fragments, sparks, fuel-pool flame tongues; smoke/dust/sphere are `FarBlasts` model cloud);
  `BlastSource`: what exploded (explosion-packet weapon: charge, fuel, composition for look/sound); `Exhaust`: plumes,
  ignition fire/flashes, sparks (engine smoke is ribbon), launch cloud `fx/smoke/LaunchCloud`, burning debris;
  `particle/Fx`: own particles, incandescent smoke, flame, fireball (animation frames, `tools/gen_particles.py`:
  volumetric render, black-body temperature color), sparks with smoke tails, flash, shock ring, in own `FxPool`
  (slots by `FxBudget` groups, objects reused, tick after world tick, not while frozen); `layer/`: effects layer,
  `FxLayer` in `AFTER_LEVEL` draws particles/all distant visuals in one call, `FxQuads` frame quads in own memory,
  far-to-near radix sort by real distance, 36-byte vertex; `FxAtlas`: 1024×2304 sheet (baked/old procedural fireball
  frames, baked billow) with mipmaps (premultiplied alpha); `SceneDepth`: world distance behind pixel (Minecraft/DH
  depth, own R32F texture) for soft edges; shaders `fx`, `fx_depth`; DH depth `render/DhDepth` (API 7.2: texture,
  projection from `DhApiBeforeRenderEvent`, depth range/direction; under shaderpack, pack program renders LOD with
  Iris perspective and `DhApiRenderParam` planes, not DH matrix, `DhDepth.irisPlanes`, detected by replaced
  `IDhApiFramebuffer`: DH matrix reads LOD three times nearer, occluding distant smoke)), `hud/` (`ClientFlights`:
  flying projectiles from server, `StrikesHud`: list, ETA, markers; target frame uses angular size in frame matrix,
  `ScreenProjection.rect` (zoom mods, Ok Zoomer, change FOV at `GameRenderer.getFov` return before matrix;
  `ComputeFov` event/`options.fov` miss zoom); target entities use smoothed position (network ID in trailing
  `S2C.Flights` list), at least `MIN_FRAME`; `RadarScope`: SAM radar display beside it, `defense.md`), `replay/Replay`
  (Flashback: event waits for replay-server tick-end marker `S2C.ReplayTick`, sent by `compat/FlashbackReplay` through
  reflection; one-shot events from seek are past, `Replay.event`; seek resets effects, the nuke retains age,
  `ClientNuclear.rewound`), `cam/ProjectileCamera` (shots: launch beside launcher, onboard with telemetry, impact
  interference/orbit; camera outside projectile is client `Marker`, not added to world; left click retargets;
  projectile absent on client means `cam/TacticalMap`, operator map using `FlightStatus` telemetry), `aim/Designator`
  (binoculars), `screen/RemoteScreen` (remote), `screen/MapScreen` (target map: click impact location,
  `C2S.AimHint.GROUND`, range `map_range`, or player for remote “player” target; Shift+click waypoint, drag move,
  right click remove; Ctrl+click/button launch location, operator/host only, `MapScreen.mayPickLaunchSite`, same
  drag/right click; one `RemoteScreen.launchFrom` determines whether strike uses it (not linked launchers/ICBM),
  shared by firing and map route; `map/MapTarget` retains location/route/launch site; view-terrain build time logged
  as “Targeting map: view terrain ready in …”), `map/` (shared `MapProjection`; `MapPlayers`: other players requested
  via `C2S.MapPlayers` while map open (server `ServerActions.mapPlayers`: dimension, `map_range`, no invisible/spectator
  players, `map_players`; GameTest `mapPlayersShowsOnlyWhomItShould`); `TerrainTiles`: 64×64 tiles in vanilla map
  colors, distant layer from LOD Server Support (server columns, `LssTerrain`, `dh.md`), Voxy LOD database
  (`VoxyTerrain`, reflection, `voxy.md`) or Distant Horizons API `DhApi.Delayed.terrainRepo` in background threads
  if installed (compileOnly, `LssTerrain`/`DistantHorizonsTerrain` load only with their mods), client chunks overlay in
  frame; coarse tile from four fresh fine tiles (`TerrainTiles.kids`: fine tile building/stale means wait, do not
  reread DH), rebuilt fine tile copies to own quadrant of coarse tiles; detailed DH tiles near player prebuild while
  remote in inventory or map opened in this world (`map_prefetch`), ready ones reread on `DhApiChunkModifiedEvent`;
  detailed client-chunk tiles within render distance prebuild then too, partial ticks capped at 2 ms wall time
  (`TerrainTiles.Partial`; otherwise chunk layer builds one tile per frame after opening, view terrain ready in
  ~1.1 s); chunk tile with holes rereads when its client chunk arrives (`ChunkEvent.Load` client-side,
  `TerrainTiles.arrived`): prebuild also runs before chunks arrive), `nuclear/` (flash/afterimage, field glare/retinal
  burns `NukeFlash`, sky/fog, sphere/mushroom `MushroomField` (sphere/fire light on dust `CloudPuffs`), dust wall with
  rolling head/Wilson cloud `BlastWall` in pall volume, camera litter/debris/fallback dust `NukeDust`, water dark
  front ring/spray `NukeWater` (decal `fx/layer/WaterDecal`), water column in mushroom volume/base surge in pall,
  post-wave pall/ash `PallField`, `NukePall`, `NukeAsh` (volume `fx/layer/PallPass`), black rain, sound, front hit/body
  wind lean on front arrival (`BodySway`: hit from G&D blast loading, immediate lean rate `jolt`; `CameraShake.sway`
  from body-front arrival including tick fraction), EFX deafness, Geiger counter, countdowns/alarm, two-step launch).

## Pitfalls
- `ClientLevel` calculates sky darkness (`Level.skyDarken`, `updateSkyBrightness`) only in constructor on world entry:
  client `getMaxLocalRawBrightness(pos)` stays daytime all evening. Time-of-day sky light at eye:
  `getMaxLocalRawBrightness(pos, FlashEyes.skyDarken(level))` (vanilla formula, same server-side).
- Client receives only `MOTION_BLOCKING`/`WORLD_SURFACE` heightmaps; `…_NO_LEAVES` is server-only. Client code and shared
  code called by client use only `MOTION_BLOCKING`.
- Under Iris shaders (host uses Complementary), anything drawn before world end goes through shaderpack passes;
  render-distance fog consumes distant visuals. Nuclear visuals draw in `AFTER_LEVEL`, checked with
  `tools/client_scenario.sh nuke shaders` (Sodium/Iris/shaderpack from instance). By `AFTER_LEVEL`, Iris with a pack
  leaves depth writes disabled (`FinalPassRenderer`), and clears respect write masks: enable masks before clearing
  own buffer, then restore (`FarModels`; otherwise atlas depth accumulates, tearing distant models).
- Camera vectors (`Camera.getLookVector/getLeftVector/getUpVector`) are live fields until next frame: JOML `rotate`,
  `mul`, `cross` without `dest` corrupt them for later readers. Rotating into projectile coordinates leaves post-world
  effects-layer smoke/distant visuals facing away from camera (burning-engine frames: flat pancakes, flakes/dots in
  salvo trails). Copy or use `dest`.
- Conventional-explosion camera (`CameraShake`, called by `fx/BlastHit`): impact tremor (`tremor`, presentation:
  strength from log body pressure, duration from phase impulse, 8.5–12.5 Hz, starts/ends at zero), body lean
  (`BodySway`), head on neck (`BodySway.motion`: 2 Hz link, ⚠ no VCR; stepping stop not passed to head). The nuke has no
  neck (`sway(…, false)`).
- Camera rotation about a world axis (body lean, `CameraShake.leaned`) uses a matrix decomposed back to heading,
  pitch/roll for `Camera.setRotation` (`rotationYXZ(π − heading, −pitch, −roll)`): heading rotates around world vertical,
  so adding angles separately works only at horizon gaze; looking downward turns sideways lean into image rotation.
  Check double camera vectors: float JOML (`cosFromSin` of half-angle near 90°) itself errs by up to 0.03° at heading
  near zero. Heading/roll near vertical gaze may flip 180° (camera rotation stays continuous): consumers taking
  heading/pitch without roll (`DisplayRenderer` text billboards, F3 axes) briefly flip.
- Everything after world renders from frame eye (`FarDraw.eye`: projection center from stage matrices), not camera
  position: walking view bob moves eye up to 0.1 blocks (vanilla in projection; Iris/shaderpack in view matrix,
  `MixinModelViewBobbing`). A quad moved nearer along ray from camera position (glare/veil at 0.25 blocks, air halo)
  swings along step arc by almost a third of screen height while fire behind stays still. Own view matrix
  `FarDraw.begin(e, eye)`, frustum `FarDraw.viewProjection`.
- GUI layers below crosshair (`registerBelow(CROSSHAIR)`: flash/concussion) belong to group vanilla hides with F1
  (`hideGui`), like pumpkin/portal. Sandbox hides GUI itself: concussion/flash frames use `hud on`. GUI-layer
  postprocessing: first `GuiGraphics.flush()` (otherwise camera overlays cover already-blurred frame), afterward
  `getMainRenderTarget().bindWrite(false)` (pass leaves own target bound), size via per-frame `resize`. Vanilla
  `post/blur.json` nests `RadiusMultiplier` in `BlurDir`; `PostChain` parsing misses it, all six passes use one radius:
  per-pass value needs separate `uniforms` object with `name`/`values`; `PostChain.setUniform` sets every pass at once.
