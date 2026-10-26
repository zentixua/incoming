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
    contamination), `Shielding` (block shielding by path mass: rays toward burst and sky for detonation, rays toward
    surrounding sky-exposed surfaces for fallout), `FlashBurns` (light burns by armor slot: skin, clothes, metal; shock),
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
  sphere almost smooth). Fireball surface temperature variation is baked (`client/nuclear/NukeBallSurface`,
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
  `ThermalModel.transmittance`, Glasstone fig. 7.104; glare is a beam, `Sight.Air`; sky-glare share 0.6 and world-flash
  threshold 0.05 are (est.), `NukeFlash.SKY_GLARE`), dust-wall, pall, Wilson cloud and camera-local dust light
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
  path (`pallShown`: emission with pall light, overbrightness to coverage in one hue); blend finished images, not
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
  peak (g 0.85: 82 versus ~11 for a 30° disk) made the shell several times brighter than the sphere. Flash frame
  (`NukeFlash.overexposed`): overexposure X = K^(e^(−u/0.2 s)) after the second maximum (K is camera flash light versus
  exposure, `NukeFlash.flashOnCamera`); frame gain X^(1/2.2) via “frame × (1 + c)” blending and lens-scattered light
  X/100; at 15 kt, 1.4 km, white until ~0.25 s, ×3 at 0.5 s, back by one second (previously white for full 1.24 s pulse
  plus 2–4 s). Eye-glare veil starts at pulse maximum. Volume noise prepares from world entry (`IncomingClient.login`):
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
  `client/fx/layer/PallPass` renders): dust follows ground pressure and front arrival, haze over the ignition zone
  until morning (server time of day at detonation, `Detonation.dayTime`; less than 5 minutes before sunrise means next
  morning; afterward world clock, `PallField.daylight`: clock jumps (sleep, `/time add`, `/time set`) are time-of-day
  shifts relative to game time (lagging-server corrections move both); recalculate morning from time of day, not hour
  count (`/time set` sets it directly): daytime after night fades haze over a minute, otherwise next sunrise, no earlier
  than 5 minutes after detonation; never fade later than the old deadline; world entry and replay seek compare first
  sample to detonation time, so unseen jumps match everyone else's, without the minute fade); detonation lives on the
  client while pall remains visible. Field laws in Java and copied in `shaders/include/pall.glsl` (`PallGlslTest` reads
  shader functions and compares with Java; edit laws in both); fireball/fire lighting on pall (`pallBallShare`,
  `pallBurn`) copies `CloudPuffs.ball` and `burn` (also camera dust light, `NukePall.dustColor`): change lighting there too.
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
  twilight, at 300 m clears in minutes. Fine dust hangs for hours: surface 15 kt pall visible ~1.6 h, airburst (592 m)
  ~1.7 h (longest a kilometer from epicenter; at 3 km ~0.9 and ~1.1 h); detonation holds its pall slot that long.
  Numbers marked `(est.)` (fitted to Hiroshima or estimated) in `DustModel`: debris dust 0.1 kg/m² and `FINE_S`
  (together, with `HobBlast` pressure; old Kinney–Graham ×1.8 used 0.5 kg/m² and 1000 s), `STEM_*`, `RISE_S`, `TOP_MAX`,
  `TOP_C`, heating light share 0.2, γ 5 K/km, entrainment wind over pressure phase (not dynamic-pressure phase, survey
  §8.1), and `lifted` shaped as dynamic-pressure impulse rather than an entrainment law. No per-cell surface classes
  (grass, city, sand) yet; `REFERENCE` everywhere. From above, pall is one blanket (18.10 complaint: golden/dark patches
  from altitude): young-layer patches (noise with gaps, `pallShape`) mix over one layer-eddy turnover (`PallField.mixed`,
  e-fold over `DustModel.RISE_S`, dust-row fourth channel) to fluctuations `MIXED` = 0.2 of mean (`clumps`, `pallMixed`;
  Willis and Deardorff 1976, (est.)), while thermals make the top bumpy, top not holes: `topD` × (1 ± `BUMPS` = 0.3,
  entrainment zone, Stull 1988, (est.); `topBumps` in `fx_pall`, last `pallField` parameter, 1 without noise). Light in
  troughs equals layer-top light (depth overhead from the same layer); previously rays through billow gaps reached
  bottom, lit by mean optical depth (and fires), producing dark/orange patches seen only from above.
  `PallFieldTest.oldPallIsOneBlanket`: after 6–7 minutes, where mean depth ≥ 5, thinnest column ≥ 3. Wind carries dust
  and spray from the moment lofted (`PallField.share`, age-based drift share `drift`, haze-row fourth channel;
  `source`), not the entire pall by wind travel since detonation: otherwise downwind dust edge/head led the server
  front (sound, shake, knockback, debris) by 100–200 blocks, upwind lagged. Pall bounds grow with drift until dust
  settles (`PallField.extent`: tens of kilometers in an hour); terrain map does not: it covers pall at source, wall
  head and camera (`PallField.sourceExtent`, `client-fx.md`), otherwise camera cells would coarsen to half a kilometer;
  dust carried past the map rests on edge terrain. No dust above water (DESIGN-nuke §6, step 6): wind-stripped spray
  behind the front has its own table row (slot rows: dust, spray, haze; `PallField.ROWS`, 6 for two detonations in
  `PallPass`), height and law: rises to wave-crest stripping height (`WaterModel.sprayHeight`), large drops fall,
  lowering top to 0.6 of that (`PallField.sprayTop`, (est.)), rather than rising like dust over hot ground
  (`WaterModel.spray`: visible over 60 m, thins in 30 s, falls out in three minutes; head portion is the same lower,
  rearward wedge, same `handoff`); water bursts also have a base surge by model laws/clock (`PallField.surge`,
  `WaterModel.surge*`; from the water-column wall falling into it on the same clock in the mushroom volume). Pall OFF
  hides the surge, leaves the column (mushroom LOW). Water beneath pall is channel 2 of `PallGround`; matter follows
  its source (position minus drift, also table lookup): dust carried over water stays dust, spray carried ashore stays
  spray; haze/glow use source water on the plume axis (no fires over water), as do ash/paper (`PallField.Water`,
  `NukePall.water`). What is absent under pall (`PallGround.hasLand`/`hasWater`) is absent in the table: a sea burst
  without dust/haze releases its slot once spray settles and surge fades, while over land shaders skip the water map.
  Unknown water takes nearest known, not average (`PallGround.nearest`; epicenter is known, `NukeWater.under`): averaging
  would spread a camera-local pond's pale spray across unknown land, and leave half-dust over sea near a shore burst.
- Shock-wave dust wall (`client/nuclear/BlastWall` laws; rendered by pall: `pallWall*`, `pallWilson` in `pall.glsl`, head
  in `fx_pall`, slot numbers `PallWall0/1`, `PallWilson0/1`): front itself is invisible; along-ground wind behind it
  (`Detonation.groundWind`: Rankine–Hugoniot × √along-ground dynamic-pressure share) lofts surface matter: dust, mixed
  with smoke over fires, spray over water. Head is pall dust's youngest, lowest, densest part (`DustModel` above), not
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
  10–25 % lower, and pall handoff band (`PALL_WALL_HANDOFF_FROM/TO` shader constants) jumps away from sphere: slot has
  no sphere radius (all 20 numbers used); game bursts (up to 15 kt, airbursts above 2.9 sphere radii) never do this.
  Over wedge fraction u behind edge, lofted amount follows dynamic-pressure impulse (`DustModel.lifted`), top Kuhl
  dusty-layer law u^(5/6) (`top`), ground density their ratio (`shape`: 0 at edge, at u ≈ 0.1 2.6 times denser than wedge
  back); column depth grows rearward. Back top and edge/back density use the same `DustModel.Column` (`first`,
  `lifted`: 256-node table down to 0.2 psi); intermediate density is linear (est.). Beyond wedge, transition to
  pall from 1 to 1.5 wedge lengths (`handoff`; pall takes 1 − handoff by local age / t₊). Where wind no longer lofts
  visible dust (thinner than `GONE`, visible over 4 km), no head, dust remains pall. Law copies: `pallLifted`,
  `pallWallTop/Shape/Fade/Mean` (`PallGlslTest`). Head noise uses dust coordinates (`BlastWall.origin`, copy
  `pallWallOrigin`): position minus post-front wind path (`carried`: air path over local positive phase, U·t₊/e by
  Friedlander, tens of meters for 15 kt at 592 m; phase share `carriedShare` = u·e^(1−u)); air stationary at ground,
  1/7 boundary-layer law above, upward 0.02–0.08 of path (Kuhl §3.4). Billows form where front lofted dust and stay there;
  against the departing front, edge dust goes up/back, rolling the wall. Noise tile (`TILE` of maximum phase-end layer
  top) is fixed per detonation: a tile growing with the head, like old noise in a tile rotated by wall angle at front,
  moved billows over ground at front speed — dust “spread from epicenter” (18.10 complaint,
  `BlastWallTest.headCloudsStayWhereTheDustWasLifted`). Cartesian coordinates, no tiled circumference or noise phases.
  Rays sample the head more densely (`pallWallSpans`: ring from dust edge to 1.5 wedges and terrain-map height layer,
  step one eighth its height, at most `steps` extra steps). Same light as pall, light-path depth follows wedge shape
  (`wall` in `fx_pall`, copy `NukePall.dustLight`): diffuse uses the lesser mean-head depth above point (to local wedge
  top) or sideways to wedge exit (at height z, wedge ends nearer edge by (z/top)^1.2 of length, inverse of u^(5/6));
  below point, to terrain (ground/fires, no below-map dust); sun/fireball beams to head top (within 1.5 wedges and its
  top), steps denser near point (quadratic, `wallChordNear`: dense dust extinguishes light in first meters). Old four
  even steps over 1.5 wedges/top skipped thin wedge, leaving uniform light deep inside. Thus head interior is dark
  (meters of visibility), top lit by sun/fireball, bottom by fires at night (`BlastWallTest.nearDustTakesWallLight`).
  Billow shadow uses two samples toward main light. Wilson cloud is a Gaussian shell behind front (0.93 front radius,
  humid air, 0.15–3.5·∛(Y/15) s): ray traversal is an event, like tornado (`pallWilson`, `wilsonLight`). Camera pall
  (`NukePall.tick`: sound, ray range) also uses mean head (`BlastWall.sigma`). Fallback camera dust (`NukeDust.front`,
  when pall is not drawn) uses head `BlastWall.wall`, in its light (`NukePall.dustColor`, below).
- Visible shock front and shimmer over sphere/storm use distortion pass `HeatPass` (`client/nuclear/NukeHeat`,
  `client-fx.md`): sphere `frontRadius` while beyond the fireball; compression thickness `ShockOptics.DECAY_SHARE` of
  in-game positive-phase length (`frontRadius − phaseEndRadius`); absent beyond dust-wall edge, shock fades from
  0.6 psi (presentation, marked: changes no wave consequences).
- Firestorm (`client/nuclear/FireStorm` model, `FireStorms` frame; same `PallPass` renders): sources on 32-block grid,
  server/sound ignition law (`FireField`), district flammability from detonation seed (grid in background); flash
  ignition like server flash layer (`Detonation.flashTicks` by cell light, spread to `IGNITE_SPREAD_S`); wave arrival
  blows out some cell fire over `BLOW_TICKS` (`Grid.before` → `strength`, from 1.5 to 2.5 psi, up to half); flame/smoke
  by cell age, fades by pall morning (`PallField.end`); all from detonation age (seek, late entry), no packet. Known
  water/ice/snow (client chunks, `TerrainTiles.wet` map tiles) do not burn (`FireStorm.survey`, 128 cells/tick); unknown
  cells use epicenter conditions (`NukeWater.under`: distant sea burst does not burn sea); seen cells remain seen.
  Server fires disabled (`nuclear.fires`, `fires_per_detonation` = 0) means no fire zone (`PallField`: no haze or glow),
  storm or roar: `Detonation.fires` (settings at detonation, in detonation packet; absent field defaults yes). Storm
  also fades where fire was extinguished: `FireStorm.trust` checks local-chunk fire only while young (later vanilla
  fire burns out independently, storm lasts until morning); lives with storm, including after replay seek; no local
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
- Distant mushroom fades into air haze (`pallAir` in `pall.glsl`, like distant `Sight.Air`): share of its light/opacity,
  not fog color — in rain, a mushroom 10 km away is not a crisp silhouette. Haze thins with altitude (aerosol scale
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
  (`mushFootWall`, from 0.6 stem radius): stem interior has its own dust. First version filled the whole stem with
  carpet-density inflow and bent flow across the whole stem, producing a ring across the stem (inflow dust front
  inside) and “herringbone” jets above; `StemFootTest.stemAwayFromTheFootIsAsBefore` checks unchanged field/pattern above
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
- Dust-wall head rests on pall terrain map (`PallGround`, like layer floor), not epicenter altitude (otherwise one
  plane through hills and above valleys): map is coarser than world, so head extends a quarter to half its height
  below it, avoiding hovering in lowlands beneath the map.
- Removed debris is client cosmetic: server sends removed chunk positions (`S2C.NukeDebris`) only to chunk viewers on
  ruin-replacement tick; client tears off pieces when its clock's front reaches the position (`NukeDust.debris`:
  delay up to `Fragments.DELAY_MAX`; ruins more than a second after front mean no debris, wind is already gone).
  Pieces ride local wave wind at removal origin (`BlastGust`: Friedlander with `blastWind` and local positive phase,
  same air path as head dust, `BlastWall.carriedShare`); masonry-breach dust (mass for all positions represented by
  sample, `S2C.NukeDebris.weight`) forms downwind effects-layer billow streaks (`BlastDust`: grouped in 4-block cells
  within 48 blocks of camera, one per chunk beyond, to `NukeDust.NEAR`; fades into Kuhl layer). With game wave
  (`wave_speed` 0.2), near-burst front is slower than real wind behind it: cap wind at in-game front speed, air path
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
  is spray, lower (crest-stripping height), own density (`WaterModel.spray`); camera dust (`NukeDust.front`) only over
  land; over water the same head volume draws local spray, `NukeWater.front` particles are crest drops (fallback spray
  billows are smooth when pall does not draw head, `NukePall.drawsHead`: a billow wider than screen near eye duplicated
  volume spray and made square stair-steps). Spray head/local spray start behind dust edge (front itself) by head
  travel during last `WaterModel.SPRAY_LAG` (0.5 s; no farther than grounded fireball edge; `BlastWall.slot`): spray on
  the front hid the dark ring from waterside viewers, the main water-wave cue. Water under epicenter (client chunk,
  map tile, otherwise surface exactly at sea level 63) is reevaluated until client detonation age 30 own ticks
  (historical entry/seek 100; `NukeWater.settles`), then fixed: column cannot appear/disappear mid-detonation. Deadline
  from `Active.age` (packet arrival), not detonation: late joiners' `seconds` already exceed it on first tick, freezing
  water before epicenter chunk arrives; `BurstWater` stays the same object after seek (`Active(Active, …)`). Land and
  heightmap `groundY` is first air above ground (integer); sight, binoculars, missile and B-2 (fluid ray) use surface
  itself, 62.889 at sea: surface block is `NukeWater.surfaceBlock` (`ceil − 1`), not `floor − 1` (block below top water
  block, making shallow water land without column/surge, while “sea” fallback failed). `BlastWall.humidity` uses same
  block (used to query air, making over-water Wilson cloud biome-dependent). `Level.getSeaLevel` is deprecated in
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
- Client game clock jumps backward during server catch-up: visual detonation time uses own client ticks since packet
  arrival (`ClientNuclear.Active`), not `level.getGameTime()`.
- Detonation time of day is server `Detonation.dayTime` (`level.getDayTime()` at detonation, saved), not current client
  clock: joining later (login/dimension change) after sleep soon after a night strike made detonation appear minutes
  before sunrise, so haze waited until next morning while others had cleared. Old-save/replay detonations do not know
  it (`NO_DAY_TIME`), then use current clock as before. Detonation stream codec (`Detonation.STREAM_CODEC`, 3.1.0 fields)
  is frozen: new fields go in `CODEC` (save, `optionalFieldOf`) and at end of each detonation packet (`NukeDetonation`,
  `NukeSync`; replay.md).
- Client scenarios (`incoming.scenario`, same-JVM server) derive wind/detonation seed from detonation ID
  (`NuclearWarhead.SCENARIO`), not `level.random`: pall, mushroom and plume frames compare across runs.
- Pall volume draws camera dust like distant wall head (`NukePall.drawsHead`: quality not OFF, shaders/noise working,
  detonation in two pall slots, nearest by pall or head edge, `NukePall.outside`: pall alone made a third detonation's
  local head yield to detonations whose haze contains camera); land-local `NukeDust` then only draws litter, removed
  pieces and surface litter (same over water, crest drops `NukeWater.front`). Square billows a step or two from eye,
  wider than frame, duplicated volume dust: full-screen pixel stair-steps (near particles use nearest pixels), dark
  spheres above wall top, over sea billows 340 m above water, above head top. Fallback camera dust when pall is not
  drawn (`NukeDust`: head billows, breach dust, billows while head surrounds player only within it, by local mean
  density `BlastWall.sigma`; all smooth, `Fx.Spec.smooth`) uses local wall color (`NukePall.dustColor` → `dustLight`:
  copy of `wall` light in `fx_pall` over mean head, same frame light `NukePall.sky`, depths to top/edge/sun/sphere,
  `PallLight.diffuse`; mean beam phase; dark billows/smoke take a share), without lightmap (`Fx.Spec.prelit`, darker
  under roofs by sky/block light): nighttime lightmap is dark while wall glows from fires, so camera billows
  (2–6 % frame height) formed near-black spots on orange wall (trailer, night city shot at 2 km). Own billow light
  (old `CloudPuffs.wallColor`, fireball glow without head shadow) made them orange by day over a gray-brown wall in
  fireball shadow: dust/wall share light. Full-screen veil (`NukeDust.veil`) uses mean head at eye (`NukePall.tick`),
  local wall color, only with pall OFF (or not drawn due to broken noise): otherwise pall draws head itself, doubling
  screen darkening. Head holds veil while at eye (`veilWanted` each tick), fading only after head leaves. Old veil,
  based on front-arrival pressure, minimum 0.2, darkened even sound-only locations (15 kt beyond 10 km, host video).
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
- Head-dust wind path behind front (`BlastWall.carriedShare`, copy `pallWallCarried`) is Friedlander integral
  u·e^(1−u), including after positive phase: negative-phase wind reverses (G&D §3.05), dust returns toward epicenter
  (total wave impulse zero). Same Friedlander for post-front wind sound (`BlastPulse.friedlander`).
- Mushroom stem dust is `nuclear/model/StemDust`: dust drawn from wave layer (`DustModel.Column.stem`, starting at
  `stemFrom`: immediately for grounded sphere, after `STEM_FROM_S`·∛(Y/15) s for airburst) rises with stem air
  (`MushroomModel.stemRise`); airburst dusty stem may never catch cap (NRC 1985, p. 22), surface burst leaves sphere
  trail up to cap. Per-detonation table builds in background (`StemDust.ready`: first frames without dust).
