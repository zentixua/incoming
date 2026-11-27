---
paths:
  - "mod/src/main/java/ua/zentix/incoming/nuclear/**"
  - "mod/src/main/java/ua/zentix/incoming/client/nuclear/**"
  - "docs/DESIGN-nuke.md"
  - "mod/src/test/java/ua/zentix/incoming/nuclear/**"
---

# Nuclear strike (`nuclear/`, `client/nuclear/`)

Ruins and everything in `nuclear/world/` are in `nuclear-world.md`.

## Structure
- Units: model in meters, seconds, kilotons; `Detonation.blocks/metres` convert with `effects_scale` (`scale`): distances
  × scale, model front time × scale. In-game front is `FrontProfile` (`Detonation.arrivalTicks/frontRadius`): model speed
  out to two sphere radii (at most 200 blocks), then smoothly to 600 blocks toward far-zone speed, a sound-speed share
  `nuclear.wave_speed` stored per detonation (`Detonation.wave`; 1.0 is real life: city ruins appear in seconds; lower
  gives the server more time and makes the rolling wall visible); beyond the dust-wall edge (0.3 psi), only sound at
  sound speed; dust wall, haze, sound, damage, knockback and ruins (by chunk center) take arrival time only from here.
- `nuclear/` — nuclear strike:
  - `model/` — pure physics without Minecraft (ground pressure by burst height: `HobBlast`, Brode down to 2 psi, DNA
    standard beyond, table over ln R and angle; front arrival and positive phase from Brode's PT subroutine, phase
    below 1 psi from Kinney–Graham at the listener; sphere, mushroom, light, prompt radiation, fallout and black rain,
    crater, radiation sickness, burns, eyes, blast injuries — `BlastInjury`), unit tests. Point pressure and dynamic
    pressure: `Detonation` (`overpressureKpa`, `dynamicPressureKpa`: along-ground dynamic pressure, `blastWind`,
    `positivePhaseSeconds`; ground versions `ground*`): an elevated point is treated as ground with burst height
    reduced by its height; above the burst, as a surface burst (upper bound (est.): no Mach stem high above ground,
    DESIGN-nuke §1.1); wave hits down to 0.3 psi (`HIT_KPA`), front window by slant range `blastReach`, not `radiusMax`
    (ground range);
  - `Detonation` — one detonation (all server/client inputs for visuals and consequences), `NuclearEvents` (SavedData:
    detonations and scheduled strikes), `NuclearWarhead` (detonation, light and radiation for one entity),
    `NuclearStrikes` (ICBM launch, timer, world events, client synchronization);
  - `radiation/` — player dose (attachment), `RadiationTicker` (once per second: fallout field × shielding +
    contamination), `GroundFallout` (`fallout.md`), `Shielding` (block shielding by path mass: rays toward burst and sky for detonation, rays toward
    surrounding sky-exposed surfaces for fallout), `WornShielding` (worn items: data map, else Mekanism; sum ≤ 1;
    new exposure only), `FlashBurns` (light burns by armor slot: skin, clothes, metal; shock),
    `FlashEyes` (retinal burns: visual-field spots, attachment `RETINA`, packet `S2C.RetinaScars`, full list/state;
    client calculates field glare, `NukeFlash`), radiation-sickness and burn effects (burns cap health while healing).
- Mushroom is a volume in the pall pass (`fx_pall`), not billows: shape, cap matter and cooling laws in
  `nuclear/model/MushroomModel` and `BlackBody`; rise and height in `CloudModel` (one law for sphere and cloud: top
  S + (E − S)(1 − e^(−t/τ)), sphere center follows top; τ and height from Wasp 1 kt, Grable 15 kt, Glasstone and Dolan
  table 2.12 for 1 Mt; Nevada heights are above ground, AGL; ring top and center never descend, `MushroomModelTest`);
  frame table `client/nuclear/MushroomField` (68 texels per mushroom, black-body light row), shader
  `include/mushroom.glsl` (law copies checked by `MushroomGlslTest`) and `mush*` functions in `include/fx_pall.glsl`.
  Billows are domes from noise channel B (`VolumeNoise`), stem uses R-channel patches. A water burst adds a fourth
  component to the same volume, a hollow water column with spray crown (`WaterModel.column`: wall from water to crown
  middle, crown an ellipse above, one surface via smooth minimum; white drops, g = 0.85 like pall spray; wall jets are
  R-channel patches stretched vertically and offset to column top: move upward with water, downward while falling;
  crown uses domes). Dry bursts do not calculate a column (extinction 0, no samples). Fireball is the same shape from
  the first frame (step M2): before rollup, cap is the fireball sphere (`FireballModel.radius`, `centreHeight`), ring
  grows from it by `rollUp` share (each shape number moves from sphere to cap, no image swap); apparent temperature
  follows Stefan–Boltzmann from luminosity and radius until the second maximum (double flash; for 15 kt, minimum at
  ~10 ms, shorter than a frame, under white screen), then cooling toward air (`FireballModel.temperature`, chronology
  keys); rolling edge mixes with air (`MushroomModel.edgeShare`: sphere 1, ring `EDGE_HEAT`) and billows grow (`billows`:
  sphere almost smooth); the jet's hollow grows under the ring section with the churn clock, leaving a lip at the rim: `mushRingDist`
  subtracts the `MUSH_HOLLOW_*` ellipse with a smooth maximum, only removing matter, so `mushGap`'s ellipse bound holds,
  `MushroomGlslTest.capRollKeepsRimAndBounds`). Fireball surface temperature variation is baked (`client/nuclear/NukeBallSurface`,
  `textures/fx/nukeball.bin`, `tools/bake_fx`: Blender Mantaflow hot-gas bubble with charge-vapor clumps rises and mixes
  with air; pattern is brightness temperature along radius from outside, octahedral direction map from cap center,
  mean p⁴ = 1, power from model): absent before luminosity minimum (surface is the smooth shock front, G&D 2.117–2.119),
  clumps emerge by breakaway (`FireballModel.breakawaySeconds`, 15/11 of minimum time) (Brode 1963; spread ~4.5 % and
  correlation 0.15 R (est.) from Trinity 25 ms image), then frame follows rise path in fireball radii
  (`FireballModel.surfaceRise`: self-similar bubble flow, one pattern for all yields); cool air darkens the underside;
  during rollup the pattern gives way to billow tongues `SKIN` (share 1 − `rollUp`; texel 16.z, w:
  `MushroomField.ballSurface`). Bake uses a 1 m radius sphere: Mantaflow buoyancy is in cells, thousands of times weaker
  for a 200 m sphere, which does not rise. Surrounding fireball light is a Lambertian sphere of cap radius at the
  black-body brightness of its apparent temperature (`MushroomModel.light`, L·R²/d²): beam on stem, skirt and water
  column (`mushAt`), particle light (`FxLights`), world light (`fx_pall_composite`: `MushroomField.balls`, at night to
  ~12 s), glare and veil (`NukeRenderer.fireballs`), amber sky (`NukeSky.amber`; it and `NukeFlash` sky glare use weather
  at detonation, `Detonation.visibility`: air-scattered light, direct+diffuse transmittance
  `ThermalModel.transmittance`, Glasstone fig. 7.104; glare is a beam, `Sight.Air`; sky-glare share 0.6 is inherited (est.), `FlashLight`), dust-wall, pall, Wilson cloud and camera-local dust light
  (`CloudPuffs.pallLight` → `pallBallLight`, `client-fx.md`). Matter cannot appear brighter than screen white, so at pall
  ray end (`fx_pall`) overbrightness becomes pixel coverage (cover·L, its own light color) and adds to what is behind:
  frame transmittance stays real; layer quads behind matter receive its light (`fx.fsh`: before). Otherwise a sphere
  in distant haze (10 km clear, share 0.14) would be a pale patch in the daytime sky. Mushroom emission (sphere, glowing
  cap/stem) is separate from pall: own ray sum (`mushAt` → `emitted`) with fireball exposure, film curve over pall,
  like the conventional sphere (`client/nuclear/NukeExposure`, copy `mushBallGain`, `mushFilm*` in `mushroom.glsl`,
  `MushroomGlslTest`; presentation): camera exposed for fire, visible surface (cap-edge temperature) with brightness L
  in noon whites on curve K·L^β (K 0.233, β 0.15, same day/night); dim glow remains in frame measure with eye adaptation
  as before. Reference is surface, not core (core reference dimmed the cap at 7–10 s). Curve only while sphere is a
  sphere: ring share (`mushFilmShare` ↔ `NukeExposure.filmShare`, 0.15 → 0.45; ~5 → 7 s for 15 kt) transitions to the old
  path, but only once the surface through air is dimmer than 1–25 noon whites (`mushFilmShareLit` ↔ `filmShare(roll, lit)`:
  rollup ∝ Y^0.2 comes before cooling ∝ Y^0.44 above ~50 kt, and a 250 kt cap re-blew the frame at 9–13 s; 0.1–15 kt
  unchanged, `NukeExposureTest.bigCapsDoNotBlowTheFrameAgainAtRollup`; big caps at night keep the film curve longer). The old
  path is `pallShown` (emission with pall light, overbrightness to coverage in one hue); blend finished images, not
  emission (even a small share through the old path already overexposes a pixel, causing a jump). Per-channel curves
  whitened the rolled cap's orange interior at night and dimmed it through haze (review #441);
  `NukeExposureTest.rolledCapIsComposedAsBeforeThroughHaze` checks cap pixels at 7 and 10 s, day/night, through haze and
  dust against the old path (copy of `main`'s end, `NukeExposure.pixel`; `shaderBlocksAreWhatTheJavaCopyModels` compares
  whole copied `fx_pall` blocks, ignoring whitespace/comments: `pallShown`, emission in `mushAt`, `mushStep`, sums and
  accumulation of `heat`/`emitted`/`filmed`, pixel composition; shader edits without updating copy and expected text fail
  comparison, `silentShaderEditsFailTheCheck` guards with mutations), transition has no jumps. Coverage overexposure
  gave the entire over-white sphere one color, a flat disk at 3–5 s (FX 23.10); now surface temperature variation shows
  at 1–5 s (`NukeExposureTest.fireballKeepsItsPatternFromOneToFiveSeconds`). Fireball light on haze, dust wall, stem and
  world remains in frame measure: a “no brighter than on-screen sphere” cap killed nighttime haze glow (fireball beam
  attenuates through dust by e^(−τ) from real, not screen brightness) and orange ground light by day. Cap
  (`mushBallCap`) applies only to what surrounds the sphere: Wilson shell (sphere surface no brighter than on screen,
  then L·R²/d²; without this, at 0.5–1 s it merged with the sphere as a white disk) and glare/veil
  (`NukeExposure.capShown`); glare size follows brightness with fireball exposure and frame overexposure. Droplet phase
  near the sphere averages its disk (`hgDisc`, `NukeExposureTest`): nearby sphere spans tens of degrees; point-source
  peak (g 0.85: 82 versus ~11 for a 30° disk) made the shell several times brighter than the sphere. Flash frame: `NukeFlash.overexposed` (law and numbers: DESIGN-nuke §6).
  Eye-glare veil starts at pulse maximum. Volume noise prepares from world entry (`IncomingClient.login`):
  `/incoming nuke now` has no warning, otherwise the sphere has no body for its first second. Mushroom daytime world
  shadow is a shadow map (`fx_mush_shadow`, output `fx_pall_composite`; `client-fx.md`; tile and world top: texels 17, 18).
  - `flash/` — flash traces on faces (DESIGN-nuke §3.7): `FlashChar` (material by `nuke_chars`/`nuke_soots` tags,
    scorch tier 1–7 by light × angle, face mark in one int: position, side, tier, block hash), `FlashMarks` (chunk
    attachment `FLASH_MARKS`: layers per detonation, scorch, shadows with 16×16 2-bit masks; per-chunk limits
    `MAX_CHARS`/`MAX_SHADOWS`, layers `MAX_LAYERS`, oldest (lower IDs) evicted: packet decoding checks the same limit,
    and a chunk with 17 layers would disconnect a client), `FlashShadows` (body shadows: snapshot on detonation tick,
    rays under nuclear budget), `FlashSync` (attachment and `S2C.NukeFlashMarks` to chunk viewers and on chunk send;
    block changes at marks below). Ruin plans calculate scorch (`nuclear-world.md`); client:
    `client/nuclear/FlashMarksClient` (marks by chunks/sections, clock gate), `FlashMarksMesh` (quads into section mesh),
    `FlashAftermath` (EMP at grid lamps in strike-darkened districts, radius from server in `S2C.NukeDetonation`,
    `Blackouts.nukeRadius`; pre-wave wisps; live only).
  - `MobPanic` — mob panic: own flee goal for each `PathfinderMob` on world entry, triggered by flash
    (`NuclearWarhead.expose`) and wave (`BlastBody.hit`).
- `client/nuclear/` after the strike: `NukeEchoes` — terrain echo (shared `client/sound/BlastEchoes`, `TerrainEcho`
  geometry, `EchoMix` mixing, `OwnSources` playback), `NukeAfter` — burning city (roar from distant `FireStorm` heat and
  local-chunk fire `fx/FireWatch` (watches only near detonations), law `client/sound/FireRoar`; inward wind and secondary
  explosions from server fire model `FireField`; collapses), `NukeSounds.Schedule.clatter` — glass/debris removed by
  wave near listener (`S2C.NukeDebris`: position sample and total removed, law `client/sound/Clatter`), `AnimalSilence` —
  quiet animals.

- Post-wave pall (`client/nuclear/PallField` field, `NukePall` at camera/into frame, `NukeAsh` ash;
  `client/fx/layer/PallPass` renders): dust follows ground pressure and front arrival; the haze is the fires' smoke
  parcels (`nuclear/model/SmokeParcels`: server rain and client haze from clock anchors; laws in its javadoc): a point reads the newest parcel over it, travel counted from the zone's downwind edge
  (`DownwindFireZoneSmokeTest`), alike in Java, `pall.glsl` and `startSeconds`; drawn faded only below `GONE`
  (`theBandHasNoCut`). Row channel a = parcels by travel (128 nodes, `SmokeParcels.drawn` both sides); fire light = row b ×
  the zone's fire share at the point (a third read broke the AMD budget). Terrain map: band to
  `MAP_DOWNWIND` fire radii; slot box: parcels within `NukePall.AIR_DEPTH` air depths of the eye. No time-of-day logic here. Pace game-tick timers to the clock on a fixed game-time grid (`BurnsEffect`: every
  20th game tick, never on the timer's own remainder, which a correction keeps landing on); dust lives on M (below); detonation lives on the client while pall remains visible. Field laws in Java and copied in `shaders/include/pall.glsl` (`PallGlslTest` reads
  shader functions and compares with Java; edit laws in both); fireball/fire lighting on pall (`pallBallShare`,
  `pallBurn`) copies `CloudPuffs.ball` and `burn`: change lighting there too.
  Dust and dust-wall head are one layer (`nuclear/model/DustModel`; laws: “Blast-lofted dust” survey, “cinema-level
  nuke” design §8; DESIGN-nuke §6), not two fitted curves: wave-lofted ground carried by wind behind the front along
  ground during local positive phase (ground pressure, phase and along-ground dynamic-pressure share by burst height,
  `HobBlast`; Gaj and Small 1991: rough-plate u*, suspension from 10 entrainment thresholds, below that saltation;
  surface `Surface.REFERENCE`, dry loamy sand) and debris dust where houses collapse (`destroyed`, 0.6–6 psi); top during
  wave phase is Kuhl's dusty boundary layer (h = 0.1·R^(1/6)·(U_s·τ)^(5/6): zero at front, tens of meters; U_s·τ is
  actual decelerating-front path along its normal behind the position over dust age, `DustModel.run`/`waveTop`, not
  arrival speed × phase: at a surface 15 kt epicenter that gave 2·10⁶ m instead of 440 and an 18 km top; under an airburst,
  integrate ∫cos α along ground-front trace (`normalPath`; cos α = √`HobBlast.alongGround`, as for entrainment wind):
  regular reflection's trace runs faster than the front, infinitely at epicenter; path is slant-range increment,
  Mach region uses stem path; ground-trace path for 15 kt at 592 m gave 509 m during phase under burst instead of 189,
  top 52 m instead of 23, and head sagged to 36 m as it left epicenter); then flash-heated ground lifts it (h_b over
  `RISE_S` = 60 s, capped at 400·∛(Y/15) m); five size bins (3–400 µm) settle from the mixed layer, fines dilute over
  `FINE_S` (800 s), near epicenter into stem (`STEM_*`); optical depth uses geometrical optics, k = 3Q/(2ρD) (mix:
  108 m²/kg). Dust age is model seconds since in-game front arrival, like the head: younger than positive phase means
  head, older means pall; transition over half a phase (`BlastWall.handoff`, `PallField.pallShare`), combined column
  depth equals the layer depth (`PallFieldTest.headAndPallAreOneLayer`: within 20 %, at 2.5 km seam 6 %). Survey numbers
  in `DustModelTest` (15 kt at 592 m): top at 0.25 s 6–30 m, phase end 30–70 m, after 5 s 50–150 m (Rosenblatt: 60–150 m;
  at 1.4 km 58 m), at 1.42 km beneath layer night until minute 20, brighter by minute 30 (Fujii in Hersey), at 3.2 km
  twilight, at 300 m clears in minutes. Every view from outside the dust draws this law through the transfer alone; only
  a camera inside it takes the presentation's sight (`dust-wave.md`, `PallField.sight`). Fine dust hangs for hours:
  surface 15 kt pall visible ~1.6 h, airburst (592 m) ~1.7 h (longest a kilometer from epicenter; at 3 km ~0.9 and ~1.1
  h); detonation holds its pall slot that long.
  Numbers marked `(est.)` (fitted to Hiroshima or estimated) in `DustModel`: debris dust 0.1 kg/m² and `FINE_S`
  (together, with `HobBlast` pressure; old Kinney–Graham ×1.8 used 0.5 kg/m² and 1000 s), `STEM_*`, `RISE_S`, `TOP_MAX`,
  `TOP_C`, heating light share 0.2, γ 5 K/km, entrainment wind over pressure phase (not dynamic-pressure phase, survey
  §8.1), and `lifted` shaped as dynamic-pressure impulse rather than an entrainment law. Lofted mass uses
  `REFERENCE` everywhere (hard ground: `PAVED` share); the colour is the soil's: `PallGround` channel a is the sand share
  (`FarTerrain.sand`), `PallField.sandShare` mixes reference `DUST` with desert `SAND` (AERONET ω, Dubovik 2002,
  `NukePall.SAND_OMEGA`) by mass, debris over hard ground stays `DUST`; soot share is the table's fire zone × `FireStorm.growth`
  (`PallField.smoke`), for head and pall alike (18.11: a 0.77 constant in the head made a black disk out to the ignition range). From above, pall is one blanket (18.10 complaint: golden/dark patches
  from altitude): young-layer patches (noise with gaps, `pallShape`) mix over one layer-eddy turnover (`PallField.mixed`,
  e-fold over `DustModel.RISE_S`) to fluctuations `MIXED` = 0.2 of mean (`clumps`; the pall's haze takes `pallMixed` at
  full mixing; Willis and Deardorff 1976, (est.)), while thermals make the top bumpy, top not holes: top × (1 ± `BUMPS` =
  0.3, entrainment zone, Stull 1988, (est.)). Light in troughs equals layer-top light (depth overhead from the same
  layer): rays through billow gaps lit by mean optical depth (and fires) produce dark/orange patches seen only from above.
  `PallFieldTest.oldPallIsOneBlanket`: after 6–7 minutes, where mean depth ≥ 5, thinnest column ≥ 3. Wind carries dust
  and spray from the moment lofted (`PallField.share`, age-based drift share `drift`; `source`), not the entire pall by wind travel since detonation: otherwise downwind dust edge/head led the server
  front (sound, shake, knockback, debris) by 100–200 blocks, upwind lagged. Pall bounds grow with drift until dust
  settles (`PallField.extent`: tens of kilometers in an hour); terrain map does not: it covers pall at source, wall
  head and camera (`PallField.sourceExtent`, `client-fx.md`), otherwise camera cells would coarsen to half a kilometer;
  dust carried past the map rests on edge terrain. No dust above water (DESIGN-nuke §6, step 6): wind-stripped spray
  behind the front has its own layer (`PallField.spray`), height and law: rises to wave-crest stripping height (`WaterModel.sprayHeight`), large drops fall,
  lowering top to 0.6 of that (`PallField.sprayTop`, (est.)), rather than rising like dust over hot ground
  (`WaterModel.spray`: visible over 60 m, thins in 30 s, falls out in three minutes; head portion is the same lower,
  rearward wedge, same `handoff`); water bursts also have a base surge by model laws/clock (`PallField.surge`,
  `WaterModel.surge*`; from the water-column wall falling into it on the same clock in the mushroom volume). These laws
  feed the camera (`PallField.sigma`, `mist`, sound, ash) and the dust pass's inputs; the pall volume draws only the
  fire-zone haze (one table row per slot, `PallField.ROWS`; `PallPass.ROWS` 2), never ground dust, spray or the base
  surge. Water beneath pall is channel 2 of `PallGround`; matter follows its source (position minus drift): dust carried
  over water stays dust, spray carried ashore stays spray; haze/glow use source water on the plume axis (no fires over
  water), as do ash/paper (`PallField.Water`, `NukePall.water`). What is absent under pall (`PallGround.hasLand`) is
  absent in the table: no haze over open sea; over land (slot `S[3].w` 0) shaders skip the water map. A detonation takes a
  pall slot only for haze, a Wilson shell, a mushroom or a firestorm (`NukePall.visible`).
  Unknown water takes nearest known, not average (`PallGround.nearest`; epicenter is known, `NukeWater.under`): averaging
  would spread a camera-local pond's pale spray across unknown land, and leave half-dust over sea near a shore burst.
- Shock-wave dust wall (`client/nuclear/BlastWall` laws for the camera: dust at the eye, sound, `NukePall.dustLight`; on
  screen the dust pass draws the ground dust, the pall only the Wilson shell, `pallWilson`, `PallWilson0/1`): front itself
  is invisible; along-ground wind behind it
  (`Detonation.groundWind`: Rankine–Hugoniot × √along-ground dynamic-pressure share) lofts surface matter: soil dust (its
  colour from `PallGround`, soot only as fires grow: `PallField.smoke`), spray over water; fire light from below
  is the fires' own flame (`PallField.fireLight`, as the haze's), not an incandescence clock. Head is pall dust's youngest, lowest, densest part (`DustModel` above), not
  its own curve: a wedge from front to where local positive phase has ended (`length` = front(t) − `tail`(t); `tail`
  solves in-game front arrival + local phase = t, by table nodes; phase varies by range, `HobBlast.positivePhaseSeconds`:
  15 kt at 592 m: 0.45 s beneath burst, 0.74 s at 1 km, 1.29 s at 5 km). Dust leading edge is in-game front (`head`),
  ring centered on actual epicenter (wind drift zero at front, `PallField.share`). No ground dust inside the fireball
  (`underBall`: mushroom sphere, `FireballModel.groundRadius`): no head while front is inside; once out, wedge plus
  pall handoff no longer than (front − sphere edge) / `HANDOFF_TO`, water spray band (`SPRAY_LAG`) also stops at sphere
  edge, no pall (`PallField.pallShare`) under sphere. Dust at such a wedge's back is younger than its phase (`backAge`;
  same for any detonation before phase ends anywhere, with wedge to epicenter): top uses layer at that age
  (`DustModel.waveTop`: Kuhl law over front path during that age, like `Column.height`), lofted amount `lifted` at the
  same phase share. Surface 15 kt head grows from zero height by ~0.13 s (first 2 ms front a meter ahead of sphere,
  head a centimeter high); wedge reaches phase end by ~0.6 s, sphere lifts off ground at ~4.4 s; airburst sphere never
  touches ground. Top behind fresh front starts at zero: first 0.1 s for 1 kt–1 Mt surface/low airbursts, no higher than
  ~7 m (previously up to 48 km for surface 1 Mt; `BlastWallTest.headTopIsTheBoundaryLayer`: 0.1 ms grid and high airbursts,
  1 kt at 240 m, 15 kt at 592 m; top is Kuhl law over front-normal path behind wedge end during local dust age,
  no spikes, and while sphere is off ground, top never sags by more than 2 % and 5 cm). Known out-of-game limit: low
  airbursts above 15 kt (1 Mt at 700 m) touch ground behind the front, shrinking the wedge by 1.5 on that tick, top
  10–25 % lower; game bursts (up to 15 kt, airbursts above 2.9 sphere radii) never do this.
  Over wedge fraction u behind edge, lofted amount follows dynamic-pressure impulse (`DustModel.lifted`), top Kuhl
  dusty-layer law u^(5/6) (`top`), ground density their ratio (`shape`: 0 at edge, at u ≈ 0.1 2.6 times denser than wedge
  back); column depth grows rearward. Back top and edge/back density use the same `DustModel.Column` (`first`,
  `lifted`: 256-node table down to 0.2 psi); intermediate density is linear (est.). Beyond wedge, transition to
  pall from 1 to 1.5 wedge lengths (`handoff`; pall takes 1 − handoff by local age / t₊). Where wind no longer lofts
  visible dust (thinner than `GONE`, visible over 4 km), no head, dust remains pall. The head's laws feed the camera's
  readers (sound, hearing, the eye's medium: `BlastWall.sigma`/`tau`); its picture is the dust wave's (`dust-wave.md`). Wilson cloud is a Gaussian shell behind front (0.93 front radius,
  0.15–3.5·∛(Y/15) s) when the negative phase condenses water in the world air (`BlastWall.condenses`: `Condensation.water`, the
  conventional law, with the negative phase p/e² capped at 4 psi, G&D §3.05, and `Air.surfaceHumidity`: forms from 51 % RH, so
  in clear weather (70 %) and rain; not biome downfall): ray traversal is an event, like tornado (`pallWilson`, `wilsonLight`). Camera pall
  (`NukePall.tick`: sound, ray range) also uses mean head (`BlastWall.sigma`).
- Visible shock front and shimmer over sphere/storm use distortion pass `HeatPass` (`client/nuclear/NukeHeat`,
  `client-fx.md`): sphere `frontRadius` while beyond the fireball; compression thickness `ShockOptics.DECAY_SHARE` of
  in-game positive-phase length (`frontRadius − phaseEndRadius`); absent beyond dust-wall edge, shock fades from
  0.6 psi (presentation, marked: changes no wave consequences).
- Firestorm (`client/nuclear/FireStorm` model, `FireStorms` frame; same `PallPass` renders): sources on 32-block grid,
  server/sound ignition law (`FireField`), district flammability from detonation seed (grid in background); flash
  ignition like server flash layer (`Detonation.flashTicks` by cell light, spread to `IGNITE_SPREAD_S`); wave arrival
  blows out some cell fire over `BLOW_TICKS` (`Grid.before` → `strength`, `Blowout.keep`, up to half); flame/smoke
  by cell age on the detonation clock (t² growth over `GROW_S`, peak, decay, out by `OUT_S` = 12 h); all from detonation
  age and that clock (seek, late entry), no per-cell packet. Known
  fuel only burns (`FireStorm.survey`, 128 cells/tick nearest the eye first, `FireStorms.surface`): cured-biome soil, dry
  fuel, buildings (non-natural tops) — not green turf, trees, rock, water/ice/snow; blocks count before and after the
  wave alike (stripped turf reads as soil, collapsed buildings as piles of their own blocks: `lateClientSeesTheSameFuel`)
  except loose rubble (`Kindling.rubble`: gravel, rooted dirt, andesite, tuff; `Surface.LOOSE`), ground before the cell's
  wave and land after it; each of a cell's 3 × 3 points keeps its first block reading and counts once, the cell freezes
  when all are read (`surveyWhileChunksLoadEndsAsTheFullView`); map-tile land and post-wave rubble take the read fueled
  share (`landFuel`). Known approximations: coarse-dirt roof rubble reads as soil, natural gravel read after its wave as
  land; unknown cells use epicenter conditions (`NukeWater.under`: distant sea burst does not burn sea); seen cells
  remain seen. `NukeAfter` weighs model fire and secondaries by `fuelAt`. Flash pyrolysis smoke (`FlashAftermath.Smoke`):
  world-anchored 12-block grid around the eye, all directions, nearest first; volatiles bounded by bed load and moisture
  (`FlashPyrolysis`), 19.11 complaint: opaque billow at the crosshair. The shown age gates emission and puff expiry
  at front arrival, measured from the burst to the sampled face in 3D (`FlashAftermathClockTest`).
  Server fires disabled (`nuclear.fires`, `fires_per_detonation` = 0) means no fire zone (`PallField`: no haze or glow),
  storm or roar: `Detonation.fires` (settings at detonation, in detonation packet; absent field defaults yes).
  `Detonation.blockFire` (`doFireTick` at detonation, saved `block_fire`, packet tail; absent defaults yes) is the one
  source for server and client, however the rule changes later: with it off the detonation's fire counter does not
  ignite (`ScarQueue.budget`), so ruins and the flash layer write their planned fire as none; LOD copies take
  `RuinContext.blockFires(d)` (front copies built before the burst under another rule are dropped at `FrontLods.handOff`
  and follow `FarLods`); `NukeAfter.realFire` draws and sounds the model storm in local chunks too, trust untouched.
  Plans always carry fire, writes decide. GameTests `nuke_flash_firetick_off`/`_on` (rule flipped after the burst),
  `NukeAfterBlockFireTest`. Black rain is the
  parcels' rain-out (`BlackRainModel`: onset on M, deposit `fallout.md`, `BlackRainDepositSeamTest`;
  rain only under drawn haze, `PallFieldTest.blackRainFallsOnlyUnderTheDrawnHaze`). Black rain
  needs fires and `Detonation.rain` (`nuclear.black_rain` at detonation, same packet rule): off, `BlackRainModel` gets no
  fire zone, so no rain picture/sound, washout or skin contamination on server and client alike (the live rule still
  gates contamination for detonations saved before the field, which read as rain on). Storm
  also fades where fire was extinguished: `FireStorm.trust` checks local-chunk fire only while young (later vanilla
  fire burns out independently, storm lasts its hours); lives with storm, including after replay seek; no local
  zone chunks in this period means trust 1. Column laws (drift, width, map levels) are in Java and copied in
  `pall.glsl` (`PallGlslTest`).

## Pitfalls
- Far clip plane is `renderDistance × 4` blocks: sphere/sky beyond it render “compressed” (`NukeRenderer`).
- Mushroom table stores epicenter like pall slot, (x, z, ground y); shader reads `t0.xzy`. Reading xyz placed the mushroom
  underground, invisible.
- Mushroom light: sun optical depth from mean field without billows makes the cap a flat sphere (soft edge along
  surface spans tens of optical depths); nearest 0.75 billow tile uses actual billows (`mushSunTau`), mean field beyond.
  Eye optical depth for side skylight is ray-traversed (`gTauEye`), not mean field, which would hide visible edge and
  blacken cap center. Empty ray steps use frame jitter; without it rays sample the same places every frame and billow
  edge grain never averages out.
- Fires under mushroom come from storm fire map (`FireStorm.meanFlame`, trust `gate`), not haze table: it knows no water
  and made daytime cap underside orange over sea. `MushroomField.fire` writes light after frame light (day/night).
- Distant mushroom fades into air haze (`pallAir` in `pall.glsl`, like distant `Sight.Air`) by Koschmieder:
  L = T_air·L_cap + (1 − T_air)·airlight, the cap's opacity kept whole; airlight on screen (`pallAirLight`) is vanilla's drawn sky along the
  pixel without its sun, moon and stars, computed from the frame's sky colour (`ClientLevel.getSkyColor`) and fog colour
  (`FxLayer.fogColor`): sky disc 16 above the eye fogged by FOG_SKY (0 → render distance, cylinder, smoothstep), fog
  colour at/below the horizon. Never sampled from the frame: a sampled frame carried the sun (screen taps off axis)
  and nearby blocks into the cap (review #671). The `haze` share is added after `pallShown`, alpha is the matter's
  transmittance only — in rain, a mushroom 10 km away is not a crisp silhouette, and the sun never shows through a cap
  (the former `lost` share let the frame behind through: the sun quad through the cap at 240 s;
  `NukeExposureTest.hazeNeverShowsWhatLiesBehindTheCap`, `opaqueCapNeverShowsTheSunNorItsNeighbours`). Under Iris the
  shaderpack's sky can differ from vanilla's, so distant hazed matter takes vanilla's sky tone. Haze thins with altitude (aerosol scale
  1.2 km, clean air 8 km; visibility at sea level): uniform-height haze hid two thirds of a 2 kt cap 5 km high over a
  hill 2.6 km away, leaving a pale translucent sphere. Cap is droplets and NO₂, no color clock or own color
  (`MushroomModel.capDrops`/`capNo2`, `MushroomField.capMatter`; numbers/sources DESIGN-nuke §1.3): droplets scatter
  (extinction from water content, droplet asymmetry), NO₂ absorbs per channel (`NO2_CROSS` × detonation molecules per
  cap volume), soil contributes thick-layer `CAP_DUSTY` color absorption (gray, only if sphere touched ground not over
  water, `MushroomModel.soil`); delta-Eddington like pall: texel 4 σ' in green (one ray depth), texel 5 per-channel ω'
  and g'. Cap whitens outside as NO₂ dilutes with growth; transmitted light/interior is brown: absorption accumulates
  through multiple scattering (`MushroomFieldTest`). Trough walls (R∞ in `mushAt`) use hemispheric-mean closure
  (`mushWallR`, `MushroomField.wallReflectance`, Toon et al. 1989): Eddington gives R∞ below zero at ω' < 1/(4 − 3g').
  Fresh ring outside is ochre (35° hue for 15 kt), transmitted is reddish brown (17°); reflected light is never reddish
  brown (`MushroomFieldTest`). Water-burst steam is matter mixed with cap: ω' averaged by extinction shares (steam
  `STEAM_SHARE` × water under burst × ring share, its ω' from `STEAM` color), not screen-layer color blending:
  inverting zero-clamped Eddington reflectance raised every channel with ω' < 0.38 to 0.38 on steam's first frame
  (edge glow to ×0.77). Mushroom lifetime (`MushroomModel.lifeSeconds`, also `CloudPuffs.lifeSeconds`, client detonation
  and `BlastWall` table) is stabilization and turbulent dispersion, (R²/(g·ε))^⅓ by cap radius (Richardson–Obukhov,
  ε (est.)): grows with yield, not slow rise (4 stabilization times gave 1 kt 45 minutes, longer than 15 kt and
  1 Mt). Stem/skirt matter is local dust, thick-layer color `NukePall.DUST` (`MushroomField.DUST`): stem fed only by wave
  dust (`StemDust`), its old “dust with smoke” color was grayer than the carpet beneath. Ground seen by stem, skirt and
  cap underside is this layer's top (`MushroomField.groundUnder`, texel 18.yzw: layer optical depth τ from
  `PallField.tau` at 2 stem radii above ground, from ground to dust R∞ as e^(−2kτ)), not bare ground 0.2; sunlight on
  pall beneath cap passes through its shadow map (`pallSunAt`). Gray stem by day is cap shadow: side-lit face is brown
  like the carpet. Inflow at stem foot (texel 19, `MushroomField.foot`, `mushFoot*` in `mushroom.glsl` ↔
  `MushroomField.footWidth/footShare/footDensity`, `MushroomGlslTest.footMatchesJava`): ground-level wind toward stem
  carries wave dust into it, flow in a “ground–wall” corner (ψ = x·z); layer-air band outside stem wall at height h is
  top/√(1 + (h/top)²), top = layer top at wall (`PallField.footLayer`, mean of four sides); dust is layer dust (air
  carries it without dilution; inflow share `DustModel.stemShare` at wall is band width, not density): layer plus
  inflow at wall is σ·1/(1 + (h/top)²), no step at carpet top (inflow subtracts what the layer already holds,
  `pallProfile`); above carpet top, band tapers to wall by merge height (`MushroomField.footMerge`, texel 11.w: where
  σ/(1 + (h/top)²) equals stem density — above, carpet dust mixes into stem and `StemDust` carries it); no carpet dust
  above inflow dust front (`stemRise` from front arrival at wall + `DustModel.stemFrom`). Do not truncate band by
  density: in thick dust any density fade shows a sharp edge where band depth reaches one, a flat-topped “cartridge
  case” ledge (first version, sandbox frames); tapering into wall joins inflow surface to stem surface. Stem/inflow
  are one dust (`mushMean`, `mushDense`: use inflow where denser). Incoming dust's light source is shared with carpet
  (`pallSource` in `fx_pall`, material/billow shadows via `slot`), computed once per frame in prepass `fx_pall_foot`:
  cylindrical table 64 azimuths × 16 radii × 32 heights in RGBA32F (raw HDR light, not screen color). Two bilinear
  samples interpolate height; azimuth wraps by repeating first column, radius runs from 0.6 of wall radius to outer
  billow edge. Constant mushroom parameters read the shared texture (`mushLoad`), not copied into each pixel's private
  `gMush` array. Main mushroom pass neither calls `pallSource` nor retains its registers, only table light; moving
  density/surface detail stays unchanged. Light filters by table-node size. Pall body is `include/fx_pall.glsl`:
  `core/fx_pall.fsh` and prepass `core/fx_pall_foot.fsh` (with `#define PALL_FOOT_CACHE`) import it via
  `#moj_import <incoming:fx_pall.glsl>`; Forgified Fabric API and Veil handle quoted imports differently from NeoForge
  (`client-fx.md`, Veil). Lifted dust contributes to local density and column above point/whole layer: `FootColumn`
  integrates positive `footDensity` using a closed-form primitive (positive interval boundaries via bisection when
  front is inside carpet), 64 heights in texels 20–35 and 128 radii in 36–67. CPU finds tapered-band edge height for
  offset from wall; subtract column above it from column above point and whole layer: hill surface is not shadowed
  by nonexistent overhead dust (`FootColumnTest`). GPU interpolates and applies radial wall share. Without this,
  dome received full sky and whitened at the side; before taper, carpet light, smoothly to stem light at merge height
  (`mushFootLightShare`). Equal density alone cannot join them: separate stem lighting made a smooth bluish bell on
  the carpet. Main stem pattern remains `mushLobes`, no remapping; inflow uses carpet billow scale (`mushInflowLobes`).
  Carpet at foot and lifted dust share one flow map (`carpetFlow`, `carpetUv`): inward at ground, then upward; path
  `stemRise`, weak swirl and bend geometry are presentation, not measurements. Two overlapping path segments offset
  by half a cycle (Vlachos, Valve, Water Flow 2010) prevent stretching into smooth arcs: displacement capped at a third
  of a billow tile, phase starts shifted by smooth spatial noise so the whole band does not pulse together. Influence
  (`mushFootInfluence`) starts outside 0.6 stem radius, extends at most two carpet heights beyond wall and no higher
  than merge; this map leaves distant haze and inner stem jets unchanged. Inflow is at wall and outward
  (`mushFootWall`, from 0.6 stem radius): stem interior has its own dust.
  `StemFootTest.stemAwayFromTheFootIsAsBefore` checks unchanged field/pattern above
  merge and inside stem. Bounds include full band width (`MushroomField.reach`); empty-space steps use actual tapered
  band (`mushFootBandAt`), old stem clearance above merge. Slope bound below merge includes both derivatives:
  width ≤0.385 and taper ≤1.5/(merge−start). Inflow cylinder checked once per pixel; inside, one flow map per slot is
  shared by all billow/shadow samples, outside old coordinates. ⚠ Corner is planar (stem radius comparable to layer
  depth), share decay with height is an approximation (est.); inside stem near axis carpet top remains (carpet denser there than
  at wall, but stem opaque across). `StemFootTest` catches steps/voids between carpet and stem (along inflow streamline,
  no jumps and density at least half carpet or stem; dust surface over carpet is a flare outside wall, tapering up).
  Stem jets (texel 11.x) rise with cloud-rise share (`MushroomModel.stemRise`) and appear with it; storm smoke top under
  mushroom (11.y) is `PallField.hazeTop`, storm columns rise to it in pall too (`FireStorms.slot`). Rain/thunderstorm do
  not thin with altitude (drops/clouds): cap above them barely visible in rain, invisible in thunderstorm. Pall/mushroom
  rays stop beyond 12 dust optical depths (visibility < 10⁻⁵), by depth along the ray itself (`pallClip` in `fx_pall`):
  eye-local dust (`PallFrame.w`) thins with altitude like the layer; vertical depths above/below eye are `PallEyeTau`
  (pall and mean head, `NukePall.tick`), combined with ray slope; end where layer depth reaches 12, otherwise do not
  truncate; no closer than half a pall billow tile: cutoff uses mean depth, but eye may be in a billow gap (noise cell
  is quarter tile). In 15 kt wave dust at 1.4 km, visibility is meters, mean-depth cutoff 20 blocks; truncating inside
  a gap missed its walls, showing sky through tens of optical depths (`film` frames). Old cutoff at eye-local 12/σ
  removed cap/stem entirely for dusty viewers (host video 18.10: mushroom vanished at 17 s despite thin dust above).
  Mushrooms on the ray ignore cutoff; transmittance ends them. Pall OFF does not cut rays (σ/depths zero), otherwise
  invisible dust would hide the mushroom.
- Removed debris is client cosmetic: server sends removed chunk positions (`S2C.NukeDebris`) only to chunk viewers on
  ruin-replacement tick. `WaveEvents` admits each sampled position against the server tick at receipt and dispatches
  it to `NukeDust.debris` during the displayed front's wind window (clock limits below).
  Pieces ride local wave wind at removal origin (`BlastGust`: Friedlander with `blastWind` and local positive phase,
  same air path as head dust, `BlastWall.carriedShare`); masonry-breach dust (mass for all positions represented by
  sample, `S2C.NukeDebris.weight`) forms downwind effects-layer billow streaks (`BlastDust`: grouped in 4-block cells
  within 48 blocks of camera, one per chunk beyond, to `NukeDust.NEAR`; fades into Kuhl layer). With game wave
  (`wave_speed` 0.35), near-burst front is slower than real wind behind it: cap wind at in-game front speed, air path
  at front position, no wind ahead (`BlastGustTest.piecesStayBehindTheFront`: without speed cap, a leaf clump at 600 m
  reached 7 blocks/tick in one tick and overtook front). Wind clock uses detonation ID (`ClientNuclear.ticksOf`): entry
  and seek replace detonation records while airborne pieces remain. Surface litter is only turf, litter and leaves
  (`NukeDust.litter`), light sheets; no pieces from stone, sand or snow. At most 256 streaks per detonation
  (`BlastDust.MAX_STREAKS`, nearer evicts farther). Server-toppled trunks are also “removed positions” (log relocates),
  but wood has no dust: masonry only (otherwise forests produced tons of dust spheres, `wall` frames); natural ground
  and rock (wave-torn ridges/ledges, `NukeDust.ground`) use pall dust (surface entrainment), otherwise tons of streak dust
  around camera (`film` frames). Streak merges into layer as layer outgrows it (`BlastDust.apart`: share
  σ²/(σ² + σ_layer²)), not expanding to layer size: effects-layer billows inside wall head receive sky without head
  shadow, so expanded streaks formed a flat veil across the frame. Replay seeks spawn no debris (`Replay.event`).
- Water (`NukeWater`, `BurstWater`, `fx/layer/WaterDecal`, model `WaterModel`): no dust above water — mushroom skirt over
  water (water under epicenter, `BurstWater.under`) whitens; wall head over water (`PallGround` water share at point)
  is spray, lower (crest-stripping height), own density (`WaterModel.spray`); the pall volume draws no head or spray
  (ground dust and spray are the dust pass's), `NukeWater.front` particles are crest drops. Spray head/local spray start behind dust edge (front itself) by head
  travel during last `WaterModel.SPRAY_LAG` (0.5 s; no farther than grounded fireball edge; `BlastWall.slot`): spray on
  the front hid the dark ring from waterside viewers, the main water-wave cue. Water under epicenter (client chunk,
  map tile, otherwise surface exactly at sea level 63) is reevaluated until client detonation age 30 own ticks
  (historical entry/seek 100; `NukeWater.settles`), then fixed: column cannot appear/disappear mid-detonation. Deadline
  from `Active.age` (packet arrival), not detonation: late joiners' `seconds` already exceed it on first tick, freezing
  water before epicenter chunk arrives; `BurstWater` stays the same object after seek (`Active(Active, …)`). Land and
  heightmap `groundY` is first air above ground (integer); sight, binoculars, missile and B-2 (fluid ray) use surface
  itself, 62.889 at sea: surface block is `NukeWater.surfaceBlock` (`ceil − 1`), not `floor − 1` (block below top water
  block, making shallow water land without column/surge, while “sea” fallback failed). `Level.getSeaLevel` is deprecated in
  1.21.1 (always 63), use `WaterDecal.SEA_LEVEL`.
- Dust/surface pieces use `MOTION_BLOCKING` heightmap top: in ceiling dimensions (Nether), that is ceiling, so dust uses
  epicenter altitude and no surface pieces; over void (End), no terrain.
- Detonation packet may arrive one tick before client clock reaches detonation `gameTime`; time must never be < 0.
- Flash traces render via `AddSectionGeometryEvent`: event on game thread, renderer on section-build threads after
  all blocks; snapshot everything it reads (marks, neighboring chunk tiers) in the event (`FlashMarksMesh.Section`).
  `QuadLighter.process` multiplies quad vertex color (`readExistingColor`): darken through copied model-quad vertex
  colors. Scorch uses model vertices at the same positions/layer after blocks (`LEQUAL` depth), no offset: offset
  would flicker at distance and open seams. Silhouette uses cell strips over scorch 1/256 block ahead of face: strips
  at model vertices fought depth (different-triangle interpolation differs in final bits, bright lines across whole
  faces in `flash` scenario frames); beyond ~50 blocks the gap falls below depth precision and silhouette merges with
  scorch. Neighbor tiers in face plane smooth corners, so edge marks also rebuild neighboring sections
  (`FlashMarksClient.dirty`).
- Chunk marks carry block hashes: block changed, mark gone. Server removes marks at the position on
  `BlockEvent.NeighborNotifyEvent` (`updateNeighborsAt`: `setBlock` flag 1, `/setblock`, `/fill`, pistons, explosions,
  player hands; also fires for unchanged neighbors — remove only marks no longer held by the local block,
  `FlashChar.holds`: material/hash); one chunk packet at world-tick end (`FlashSync.onLevelTick`, chunks in
  non-persistent world attachment `FLASH_PENDING`). Otherwise replacing a broken block with the same type would
  restore the mark, same hash. Changes without neighbor notification (`setBlock` without flag 1, used by some mods)
  are invisible to event: next chunk save/send prunes (`FlashMarks.pruned`), unless same-type block replaced it first.
  Client mesh checks same hash while awaiting packet. Block hash is 6 bits; all 32 mark bits are used.
- Live detonation age (`ClientNuclear.AgeClock`) advances on processed client ticks plus the frame fraction.
  The birth packet confirms age zero. Each ordered `NukeTick` confirms server game time minus detonation time.
  Steering targets that age plus elapsed own time (up to 40 ticks), buffered by two ticks. Small errors within the
  buffer receive proportionally less correction. Normal speed is 0.85–1.2× before the 140–200 tick linear lead brake.
  The final speed slews at 0.2×/s, the lead stops at 200 ticks, and show end releases it to uncapped own time.
  A late client's first estimate may be replaced before the first draw. Once shown it stays monotone. A frozen or
  half-rate client and a client behind at admission can catch up at up to 3× for two seconds. A displayed estimate
  above its first anchor advances at half speed until the target reaches it. Tick callers use `ticks(0)`.
- Two viewers must agree within ten ticks after ten seconds of normal service following a steady server or a short
  stall. After 20 s steady, 60 s at 10 TPS and recovery to 20 TPS, a cold join has 60 s to reach that band and stay
  there. At +10 s the reachable gap is ≥31.733 ticks (server age 1200, resident ≥1271.733, joiner ≤1240).
  The independent trajectory and all recovery checkpoints are in the [join calculation](https://github.com/zentixua/incoming/pull/742#clock-contract-and-cold-joins).
- Ruin packets take their server age from the preceding anchor. The detonation, epoch, server tick and batch
  ordinal identify a delivery. Each sampled site must be fresh at receipt (up to 20 server ticks after crossing).
  At dispatch the drawn front must have reached the site within the last 20 displayed ticks (`DEBRIS_LATE`).
  A site beyond that window produces neither wind-driven debris nor its clatter. Accepted clatter from a live
  burst travels from the original site arrival, including later arrivals at the ear. A reconstructed show stays
  silent for clatter. Seek or world entry rebuilds pending and consumed state. Replay clocks are in `replay.md`.
- **Server timing limit.** Ruin replacement and damage follow the server. They can lag the drawn wave by its lead:
  up to 4.7 s with the 5.15 s stall on Artem's recorded stream, and 8.3 s after 60 s at 10 TPS
  (`AgeClockLeadProbeTest`). The client applies authoritative changes at receipt.
- `PresentationClockTest` checks all 1,445 deliveries in the recording through the final drain, overload, restart,
  joins and frame pacing. `ReplayExportFramesProbeTest` compares editor and export ages and wall extinction.
  `ClientNuclearTest` checks freeze, the half-rate client and the J3/J6 recorded frame streams
  with ±25 ms arrival jitter. With `-Dincoming.nukeDiag=true`, `nukeFrame` logs epoch, detonation, receive-clock
  nanoseconds, shown age, confirmed age, lead, steering and held status. `nukeAnchor` logs receipt time and server
  age, and `nukeEvent` logs server tick, ordinal, due age, shown age, signed skew (shown minus due), decision and reason.
- Clocks (`docs/design/time.md` §8.3). Each detonation has two ages (`util/StrikeClock`): the motion
  age M (real time plus jumps counted in its dimension) for what is in the air, the strike clock S (M through the
  show, then the world's rate joined by the rate blend `StrikeClock.blend`, dS/dt = 1 + (k − 1)·smoothstep(r/60 s): never
  above k, never catching up the show) for what stays in place. Server: `NuclearEvents.motion`/`seconds` now,
  `motionAt`/`secondsAt` at a past game time from the saved world-clock history (`util/StrikeHistory`: a sample a real
  minute until M passes 12 h, plus show end, blend end and both sides of each counted jump); client:
  `ClientNuclear.Active.motionTicksAt`/`clockTicksAt`. The show ends at `NukeClock.SHOW_S` or at the first counted jump
  (`NuclearEvents.endShows`, each tick); the blend's end is an anchor (`rampRate`), so a later change of k never moves
  the past. World clocks (`util/WorldClock` javadoc): `now` (every jump: doses, the grid clock),
  `rateClock` (none), `pace` (k ≥ 1, from a step following a running one at its cadence), per dimension
  `counted` (nobody awake; held during a front, `NuclearStrikes.frontCrossing`, then counted only if still nobody is
  awake, else unseen, `AirAwakeJumpProbeGameTests`; strikes born then take it, dropped if seen, `heldSeen`) and `unseen`.
  Awake is noted at `ServerTickEvent.Pre`: vanilla wakes sleepers in the skip.
  Fixed-time dimensions count no jumps. `/time set` vs `/time add`: `CommandEvent`'s last child context (`/execute …
  run` too); datapack functions fire none: their forward sets count as additions (limit). `S2C.NukeClock` (clocks,
  the receiver's dimension's jumps, every anchor) goes to every player each second, at a show's end, on entry and the
  tick the count rises; a Flashback snapshot's last copy restores them on seek (`ClientNuclear.ANCHORS`
  outlives `rewound`); a recording without anchors: a 20-minute show, no jumps (`Clocks.NONE`).
- Shared states (time.md rule 8, `util/RecoveryClock`): real while a cause is in its show (the youngest detonation that
  acted on the state, ids grow; a conventional strike's show end, `warhead/StrikeShow`: max(π/N apex, crater fuel
  burn-out)), then the blend from the last show's end; every jump (`counted` and `unseen`) counts; event time (real +
  counted) for exposure windows. Bodies (`radiation/BodyClock`, attachment `BODY_CLOCK`, not copied on death): sickness
  (`RadiationDose.exposedAt` is body-clock ticks) and burn pacing (`BurnsEffect.pace` by `BodyClock.lastSecond`) read it.
  Players are stepped once a second (`BodyClock.tick`), mobs by their sickness and burn effects; a step after more than
  `PRESENT_GAP` ticks is an absence (offline, unloaded): nothing elapses, so no fatal-stage login. A body without a clock
  starts at `WorldClock.now`: saved `exposedAt` keep their meaning. Causes: `RadiationTicker.addDose(…, d, ago)` (prompt;
  a late mob dates its exposure `ago` by the cause's S, `MobFallout` by `secondsAt` at the crossing: limit, a mob first
  dosed while unloaded comes back sick by that time, while one already sick when unloaded is frozen), fallout or rain
  reaching a player (`BodyClockGameTests.falloutHoldsThePlayersClock`), `FlashBurns`. Detonation ids are per
  dimension: a step in another dimension drops the cause (`CrossDimensionCauseTest`). The ear is the client's (`client-sound.md`), kept on the server across relogs
  (`radiation/Ears`, attachment `EAR`, opaque tag ≤ `C2S.Ear.EAR_BYTES`, `S2C.Ear` on login).
- The air on M (rule 2) reads `Active.airTicks`/`airSeconds`, the pall `PallField.motion` of the real age passed
  (`AirWiringTest`); front, flash, Wilson cloud, sounds and ruin events keep real age; fire S. The count is sent the tick
  it rises (`WorldClock.countedRose`), before detonations on join.
- `Detonation.dayTime` is unread, kept for the save/packet formats. Anchors per detonation (`NuclearEvents.Anchor`: the
  world clock at burst and show end, which date bodies' exposure, and the `StrikeClock` anchors) and the history are
  persisted; a save without them reads as a 20-minute show, no jumps.
- Fallout on the ground, ground fields: `fallout.md`. A burst-clock anchor for a detonation dated in the past (tests) uses `NuclearEvents.add(d, level)`, which
  backdates it as a vanilla day. Detonation stream codec (`Detonation.STREAM_CODEC`, 3.1.0 fields)
  is frozen: new fields go in `CODEC` (save, `optionalFieldOf`) and at end of each detonation packet (`NukeDetonation`,
  `NukeSync`; replay.md).
- Client scenarios (`incoming.scenario`, same-JVM server) derive wind/detonation seed from detonation ID
  (`NuclearWarhead.SCENARIO`), not `level.random`: pall, mushroom and plume frames compare across runs.
- Camera dust is the pall volume and the dust pass, like the distant wall head; land-local `NukeDust` only draws litter,
  removed pieces and surface litter (same over water, crest drops `NukeWater.front`). Particle billows a step or two from
  the eye, wider than the frame, would duplicate volume dust: full-screen pixel stair-steps (near particles use nearest
  pixels), dark spheres above wall top. No screen veil: the volume itself darkens the screen at the eye.
  Litter and pieces use world light: small/opaque, correctly dark against wall.
- Client does not know server fire settings (`fires`, `fires_per_detonation`): distant fire is `FireField` model (server
  probability: flash ignition, `Detonation.flashTicks`, wave arrival at ground by slant range from burst blows out
  half from 2 psi; cap by server count of 20 000 fires remaining after wave: airburst 15 kt ignites 40 000 out to ~146
  blocks). Model lacks ruins, a known limit: counted layer fire extinguished by ruins (rubble/collapse) leaves server
  count, and ruins beyond the layer's capped ring place their own fires — after wave a city server burns beyond
  model's last ring, model empty; local chunks correct this near player, not far away. Local chunks use real fire
  blocks (`FireWatch`), and trust in distant model follows actual versus modeled local fire (`NukeAfter.trust`, window
  from last ignition); too few modeled local chunks means unchanged. Server applies flash layer in player chunks
  first, spending the fire counter there: distant model excludes local fires (in `nuke` scenario, 8–11 thousand fire
  blocks near viewer at 2 km). All burning follows detonation age: secondary explosions scheduled from seed from wave
  arrival; after replay seek new `Active` skips past events.
- Villager panic uses `WALK_TARGET` memory (brain runs), not `PANIC` activity, which summons golems. Brain-mob flee goals
  set memory location and clear only if it remains their location.
- Flee goal (priority −1, `MOVE`) overrides `SitWhenOrderedToGoal`: owned (tamed, someone's `OwnableEntity`, leashed,
  named) mobs do not flee, otherwise sitting wolves ran hundreds of blocks. Enclosed mobs (pen, farm, pond) always
  report navigation `isDone()`: new paths every goal tick (up to 8 positions plus full pathfinding), so search at most
  every `MIN_REPATH`; a path going nowhere doubles next delay (GameTest `nuke_panic`: cow in 1×1 pen, 3–6 searches
  during panic, 33 without backoff). Sheltered waves do not scare.
- Flee position is custom (`MobPanic.place`), not vanilla `DefaultRandomPos`: random height (one of 15 valid), sideways
  and underfoot positions made empty search/one-step paths count as enclosure, leaving mobs standing 4–8 s (GameTest
  failed 4 of 30 runs). Empty search is not always a pen: airborne mobs (just spawned/wave-tossed) cannot find ground
  paths; wait for ground instead of backoff. Flyers without their own check (parrot, allay) see a solid, path-blocked
  cell itself as “stable”; fallback `LandRandomPos` raises it out of solid ground.
- `FireWatch`: global-palette `maybeHas` (detailed city) always says yes, small palette remembers burned-out fire;
  full section count (`PalettedContainer.count`, map of 4096 positions) over 16 chunks/tick cost 0.7 ms/tick over burning
  land and 3.2 ms in city (6 global-palette sections/chunk), so sample every 8th block under 0.1 ms budget: 0.07 and
  0.13 ms/tick (20-tick median; single 4–8 ms ticks were thread stalls on shared machine during GC, old count up to
  17 ms). Static world reference resets when nothing burns and on world change (otherwise retains old `ClientLevel`).
  Cost: `SCENARIO nuke-sound … watch` (`INCOMING_CITY` for detailed city, adjacent `SCENARIO city old-scan` for old count
  over same chunks).
- Mushroom-table texel 12 (`MushroomField`) has two writers: `frame` writes cap churn clock to w, `fire` after frame
  light writes fire light to rgb, preserving w. Clock is detonation-age function (`MushroomModel.churn`, per-detonation
  table), not frame accumulation: replay after seek and late entry produce same cap.
- Camera (engine listener) hears detonation, player holds ears/body: projectile camera circles explosion 32–42 blocks
  away, player kilometers away. `NukeSounds.Schedule` meets front twice (`FrontMeeting`): camera gets wave sounds,
  echo/wind (`arrive`), eye-local dust; player's eyes get hearing/body tilt (`hit`) by own pressure/path/phase. Camera
  on/near player (distance to burst within one tick's front path) meets front by body on the same tick, otherwise
  one-tick-early ear hit would muffle its own wave sounds. Meet only when front crossed position this tick
  (`FrontMeeting.passed`: front was before it, or position moved toward burst by at most `JUMP` per tick — approaching
  positions cross front between ticks): checking only “front beyond position” would replay hit when camera teleports
  behind front (next impact orbit, return to body). Spectator body is camera: viewing through another entity leaves
  client `LocalPlayer` at initial viewing location (server moves only `ServerPlayer`). Body effects belong in `hit`,
  not at camera position.
- Head-dust wind path behind front (`BlastWall.carriedShare`) is Friedlander integral
  u·e^(1−u), including after positive phase: negative-phase wind reverses (G&D §3.05), dust returns toward epicenter
  (total wave impulse zero). Same Friedlander for post-front wind sound (`BlastPulse.friedlander`).
- Mushroom stem dust is `nuclear/model/StemDust`: dust drawn from wave layer (`DustModel.Column.stem`, starting at
  `stemFrom`: immediately for grounded sphere, after `STEM_FROM_S`·∛(Y/15) s for airburst) rises with stem air
  (`MushroomModel.stemRise`); airburst dusty stem may never catch cap (NRC 1985, p. 22), surface burst leaves sphere
  trail up to cap. Per-detonation table builds in background (`StemDust.ready`: first frames without dust).

- ICBM terminal arrival (`IcbmArrival`, `IcbmApproaches`, `IcbmArrivalRenderer`) uses the existing NUKE airframe's
  FlightScale speed (game value is not physical). Once ready ground is known during the final window, its exact
  geometry is persisted in `ScheduledStrike.arrival` and used by detonation. The window is at most 30 seconds
  (`IcbmArrival.WINDOW`; at real speed no higher than the luminous onset `ONSET`, 80 km), never while the first stage burns
  (`IcbmArrival.BOOST` = `Ascent.burnTicks`, 61 s); shorter flights split their time between climb and fall in the
  same proportion (`IcbmArrival.boost`, passed to `IcbmEntity.prepare`; default 90 s: 60.3 s climb, 29.7 s fall); no new flight setting. An unready target has no speculative head.
  `NukeApproach` snapshots carry only active terminal paths and the server epoch; one tick of interpolation,
  no countdown extrapolation. A stalled server pauses upstream like an entity, never at the burst endpoint.
  `NukeDetonation.strikeId` consumes exactly its own head; the emitted wake (at most `IcbmArrival.WAKE`) survives
  warning removal. Look: `nuclear/model/ReentryGlow` (Allen–Eggers speed at the head's real height, drag power ×
  luminous efficacy: magnitude −11…−14 from 5–10 km, `ReentryGlowTest`); head through `FarSprites.light` (core, glare,
  veil, eye adaptation), world light through `HeatPass.Frame.light` (`IcbmArrivalRenderer.heat`); wake strip glows for
  the real quench time ∝ 1/ρ laid along the game path, drawn until it fades below `FAINT` on screen (a fixed three
  e-folds ended in a hard white cut at night). The previous 1.5 m spot was one pixel at 2 km: invisible by day.
  Legacy detonation packets retain location matching. Clear/dimension/replay reset arrival state.
  Arrival glows/ribbons use positive source-sized softness: zero skips the shared scene-depth sample in the
  full-resolution pass and allowed a halo over nearby Iris masonry (`arrival 1`, house 50, camera 11/2/0/800).

## Shared nuclear flash light
`FlashLight`: visible pulse/area source, opening-transfer law; `NukeLight`: one frame sample/exposure before fog,
used by all nuclear illumination (firebody film cancels it once against its own ambient reference). By day one frame
exposure E0·r^(f·day) (design fireball-light; f ½ for a flash, 0.85 for a lasting light, `NukeLight.adapt`): daylight
× `daylight()`, ball light (amber dome too) × `frameExposure()`; eye veil `flashVeil`. Not covered: quad daylight,
vanilla hand; no ball shadows (screen-space ones moved with view). Blindness never dims it.
`FlashLight.temperature` is escaping brightness temperature, not core/mixed gas temperature: never in server energy
or injury. Source derivation,
opening approximation and outside-OpenAir limitation: DESIGN-nuke §6. Sealed eye/world pixels get no new flash;
block light is never an opening. A distant ball is seen by the visible share of its disc above the terrain line
(`NukeLight.share`: `Sightline` from the ground under the centre, `FarBlasts.visible`), not one ray to its centre:
a ground ball's centre sits at ground level for seconds, and tree crowns 2 km out hid it while 94–98 % of the disc was
open, cutting the light 3.5 times (Zearth 09.11.2026, `NukeLightShareTest`); glare and dazzle stay yes/no at half. Paused game time cannot advance adaptation; reset/seek reconstructs exposure.
Transient frame flash must skip non-live detonations reconstructed by sync/seek; their steady illumination still adapts.
Vanilla sky flash (`ClientLevel.setSkyFlashTime`; a switch, also full-day light map: only under ≥ 0.98 white fill) mixes sky and fog toward white by min(1, timer − partial tick): a
timer renewed to one tick each frame is a 20 Hz sawtooth (replay 10.11: ±0.055 luminance); `SKY_FLASH_TICKS` is 3:
the fog color reads the sky before the `ComputeFogColor` renewal, one tick less after a tick (`NukeFlashSkyTest`).

## Dust source material

- Server cells: 64 blocks out to optical depth 1 at one second, 256 blocks out to 0.05 (presentation keeps its own
  thresholds). A cell closes 200 ticks before the front reaches its nearest ground point; surveys continue after
  detonation; instant sources start from the observations at birth.
- Class by column plurality, ties water, sand, soil, hard; unread cells: 1/d²-weighted vote of the 8 nearest measured
  closed centres (`MaterialMap.Nearest`); cells due together share samples. Ground-zero wetness: origin block, surveyed ground, soil.
- `NuclearEvents` keeps the UUID across detonation: open sources save birth samples, timestamped observations and
  frontier tick; finished ones packed cells. A malformed entry is isolated; a worker failure publishes uniform seed.
- Checkpoints stand alone (partial maps carry fractional origin and sealed extent); four cells per byte, byte runs
  when smaller. 64 KB is per framed packet: a typical 1 Mt source fits whole, an incompressible 330,870-cell airburst
  needs 82,718 B of values, 17 sources fit in 1.5 MB. Cold delivery nearest first, 12,500 material bytes per part,
  20 parts per rolling second per viewer.
- `ClientNuclear.Active.sourceMaterial()`: class, commit status, origin wetness; the detonation packet carries the
  frozen seed for an unwarned viewer (`frozenFallback()` until its cell arrives); empty outside the footprint; a source
  without a revision freezes the first local terrain answer per cell.
