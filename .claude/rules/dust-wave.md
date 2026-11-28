---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/fx/layer/DustWallPass.java"
  - "mod/src/main/java/ua/zentix/incoming/client/fx/layer/DustIntervals.java"
  - "mod/src/main/java/ua/zentix/incoming/client/nuclear/DustSources.java"
  - "mod/src/main/java/ua/zentix/incoming/client/nuclear/DustMaterialTiles.java"
  - "mod/src/main/java/ua/zentix/incoming/client/nuclear/PallField.java"
  - "mod/src/main/java/ua/zentix/incoming/client/nuclear/DustGround.java"
  - "mod/src/main/resources/assets/incoming/shaders/include/dustwall.glsl"
  - "mod/src/main/resources/assets/incoming/shaders/include/wall_layer.glsl"
  - "mod/src/main/resources/assets/incoming/shaders/core/fx_dustwall*"
  - "tools/uw_probe/**"
  - "tools/dust_wall_*.py"
---

# The nuclear dust wave

## Structure
- The dust wave is the one owner of nuclear ground dust (soil dust, sand, water spray, a water burst's base surge) from
  birth at the contact line to the last thin haze, in every condition (water, several detonations, secondary views, every
  preset): one ray-marched pass, one presentation model. `client/nuclear/DustSources` builds the frame's inputs,
  `fx/layer/DustWallPass` draws (march → pack → resolve), `include/dustwall.glsl` is the model, `core/fx_dustwall*` its
  programs. `DustOwnershipTest` holds the owner per condition (`DustSources.owner`: the dust wave, or none with dust OFF or
  programs not ready) and fails on any old drawing's name coming back.
- Picture: an envelope born on the contact line as a ramp leaning back (never ahead of the front: dust there is cut by the
  ring, a seam), a hump whose crest is 1.4–1.6 of the settled layer, back to the layer within 0.5–1 s
  (`DustWallGlslTest`), billows and bulges fixed in the air across the ground and carried in height by the dust's own
  column; then the textured young level gives way to the haze level (`PallField.textured`: 1 to 24 s, 0 at 60 s), which
  thins by the law to the last haze. Per-point birth: the front is a line of births; each ground point's world-fixed seed
  sets its birth delay after the front's arrival, amount, column height and overshoot; its column rises, overshoots and
  settles by its own age and stays where it was born. Behind the front one law of time since the passage per point moves it
  (the blast wind's outward surge, the weaker longer reverse, the afterwind drawing low dust in, eddies turning either way
  decaying over ~25 s, swells, tongues, the wind). Light from the fireball, sun and sky with multiply scattered light in the
  soil's bulk colour (no near-black); light paths see the large shapes only.
- Sources: `DustSources.frame` takes every detonation with dust left on its table, nearest first, up to 16 a frame
  (`DustWallPass.MAX_SOURCES`), in every view (secondary views keep their own march targets and history,
  `DustWallPass.ViewState`, swapped inside `PallPass.ViewState`). One source table holds every source (`DW_ROWS` = 66 rows
  each: arrival row, 32 log-age rows, the spray row, 31 inside-sight rows, the frame-input row; `dwLoad`); slots are stable per detonation
  (`SLOT_OF`, idle slots free after 120 frames). A ray draws its eight nearest source crossings in full, further ones
  noise-free (`gDwFull`). A source first drawn already aged fades in over 1 s (live only).
- Ground: the dust lies on the eye's own ground (`DustGround`: two 128² tiles centred on the eye, fine 4–16 m over the drawn
  chunks by render distance, coarse 32 m over 4 km; the atlas's last two tiles, `DwTerrain0/1`), from what the client knows
  (`FarTerrain.dust`: client chunks below permeable canopies, then the LSS/Voxy/DH map tiles' ground under canopies, `TerrainTiles.dustGround`;
  a tile column under leaves whose ground its source did not measure, an LSS column saved before canopies were kept, is unknown: the
  canopy tops stood the dust 20-35 m over a jungle's floor past the loaded chunks and cut the blanket seen from above along their square); the fine tile takes each cell's lowest
  column, the coarse their mean. `dwFloor` blends fine → coarse → the source's own map by share: the fine tile hands over inside
  what it knows (8 cells); past what the client knows the coarse one holds the ground where it was last seen: once the
  survey has gone round the whole tile, an unknown cell takes its nearest known cell's height and keeps it until it is known or
  leaves the tile, out to the tile's edge, where the source's map takes over (held at the first, time-bounded fill, the cells
  past the drawn chunks kept the ground next to the eye, 15–31 m under the edge in a valley; until the first full sweep the
  nearest known height is taken afresh; `DustGroundFirstFillProbeTest`). The sweep goes on across a gliding re-centre and
  starts again at the eye after a jump (teleport, respawn) or a reset (resumed mid-sweep, the ground under a teleported eye
  stayed unknown for 15–100 s). A mean of the known area or a blend into the map from the known edge stands over a
  valley floor and ends rays down it short of the ground (`DustGroundRayEndTest`); a nearest height taken afresh at each refill
  moves the floor 1–2 km out by metres as the player moves (`DustGroundTest.farFloorHoldsWhileMoving`); `DustGroundTest` also
  holds no step over 1 m per 2 m at the drawn chunks' edge. A re-centred tile refills in the same tick (`DustGroundRecentreTest`),
  every view uses the tiles' one height reference (`DustGround.refY`), and the survey takes at most 0.25 ms a tile per client
  tick (`DustGround.SURVEY_NANOS`). The dust thins to its floor over 0.5 m where the fine tile knows the ground, 3 m where the floor is a
  coarser estimate (`gDwFloorSoft`: a 3 m thinning on the true ground left an eye 1.6 m up in clear air under young dust). The source's map learns what the coarse tile knows (`PallGround.learnAround`). Known limit:
  past the drawn chunks without an LOD map the held heights keep one height along each bearing from where they were seen
  (radial ridges and grooves to 1.8 km, unseen beyond Minecraft's own fog). Java readers of the
  eye's height in the dust use the same floor (`DustGround.floor`). Each source also has its own 128² terrain map
  (`PallGround`, atlas `Sampler3`), the floor beyond the eye's tiles, and two ground-material tiles
  (`DustMaterialTiles`: fine at the server's dense cell, coarse at its outer cell; RGBA8 water/sand/hard/known, filled
  1024 cells a frame from `SourceMaterial`; a known cell never changes). Water is a material of the same wave: the material
  mixes the dust law's column with the spray's (`dwDustDensity`, spray row); a water burst's base surge is a second
  source term (`dwSurge`). The dust never lies indoors (the outdoor-air window) and never under the fireball's ground
  footprint.

## Laws as inputs
- The laws decide where, when and how much; the model draws it. `PallField.waveTable` (built once per detonation) holds
  per ground radius: front arrival, dust over hard ground (`paved`), the fire zone's share; per log age
  (`lifetimeAge`, `AGE0` 0.05 s, to `lifetimeEnd`): the drawn top and the optical depth the transfer reads, with slopes
  (cubic Hermite in the age coordinate); and the spray law per node (`lifetimeSpray`). Settling and dilution are the law's
  alone (`DustModel.Column.depth`); the age clock is the motion age M, real time plus the jumps counted in the dimension
  since the burst (model seconds = M ticks / (20 · scale), `Active.airSeconds`, `PallField.motion`), never the strike
  clock: a night everyone slept through ages the dust by its hours, usually past its table's end.
- Presentation deviations (marked in code and in the guide's deviation list): the drawn height ×68/58 of the law's
  (`PallField.PRESENT_HEIGHT`: the crest just under a 120 m camera's horizon at 4.5 km from 1 Mt), the hump, the
  transfer of the textured young level (`dwTransfer` ↔ `PallField.transfer`: optical depth above 1 drawn compressed, the
  matte face; the haze level draws the law's depth held under the march's exit depth ln 100, `dwHazeDepth` ↔
  `PallField.hazeDepth`, applied to the law's whole column after material, paved share and fade, within 1 % of the law up
  to τ 3.3; the two by `dwTextured`, `PallField.column`) and the sight inside old dust
  (`PallField.sighted`: about 200 m at one minute, 700 m by five, then the law's own thinning).
- The inside sight is the camera's, not the dust's. The table holds the law's optical depth per age node and, in its
  sight rows (`PallField.SIGHT_ROW`, `DW_SIGHT_ROW`: one texel per age interval with both nodes' value and slope), the
  depth a camera inside sees, baked per node through the haze level's fade (the inside view is main's at every age
  within 3 %, `DustSightTest.insideTheColumnIsMainsAtEveryAge`). Each source's frame inputs carry the camera's share
  (`DwSight`, texel 5's w: `PallField.sight`, the eye's depth below the haze level's own top there, envelope × (1 +
  swell + plume) from `DustNoise`'s copy of `dwSwellField`/`dwPlume` on the march's lattice, over its soft edge, × its
  column against the sight's ceiling, taken in over the eye's dust's first 2 s), and the density moves the law's depth to
  the sight's by that share before the water share (`dwSight`: one fetch per radius node, no work at share 0); the haze
  level takes its held outside column to the transfer of that by the same share (`dwHaze`), so a camera inside sees main's
  column on land and at shores (`tools/dust_wall_gl.py` shore case). Outside the dust every ray reads the law's whole
  column as drawn (`PallField.column`): from a hill, 15 kt at 1.5 km straight down 1 % of the light at one minute, 1.7 % at
  twenty, opaque slant; from 400 m above at 2 km, 70° down, under 1 % at 70 s and two minutes over soil and over hard ground
  (`DustSightTest.fromAboveTheHazeIsTheLaw`, `hardGroundFromAboveFollowsTheLaw`, `tools/dust_wall_gl.py` "from N blocks
  up" and hard-ground cases); the transfer alone passes 12–20 % there. The hold keeps the carpet at the stem's foot under
  the stem's density (`StemFootTest`: the bare law's carpet there, 0.6/block at 60 s from 15 kt, raises a bell that breaks
  off at its top). Java readers of the world's dust (stem foot, the mushroom's ground, breach dust) read the
  law as drawn; the eye's readers (`DustSources.eyeDepth`, `PallField.addDrawnEye`) take the camera's share (`DustSightTest`,
  `DustNoiseTest`, `DustWaveCopiesTest`, `tools/dust_wall_gl.py` noise and inside-sight cases).

## Readers
- The pall's rays end at the wave's dust: in the pall interval the dust pass runs first, then `fx_dustwall_limit` writes, at
  the pixels the rays read this frame, the world distance or, nearer, the view depth where the resolved dust (read as the
  composite reads it, `wallLayer`) lets less than the rays' exit share (`RAY_EXIT`, 2 %) through; the rays take it as their
  world distance. Behind it the dust hides the pall, so the composite's light and alpha stay within that share. The pall's
  GPU parts are summed in the order their stamps were issued (`PallPass.stamp`).
- The march writes three outputs: light/opacity, occupied view depths with the opacity-weighted distance, and two intervals
  (`wallSplit`: A to the first clear gap of more than max(48, 0.1 t) blocks, B the rest; without such a gap A and B meet at
  the bend of the ray's optical depth, `dwKnot`: of the depths where it first reaches 0.03 · 2^j, the one farthest from the
  chord between the first and last occupied depth, kept within 0.02 of it; A exit × cover, B entry × cover, A opacity, A
  share × cover). Readers take the transmittance in front of their own depth by interval (`wall_layer.glsl`:
  `wallPrefixT`, `wallPrefixShare`, `wallTo`, `wallCut`; Java copy `DustIntervals`, `DustIntervalsTest`). A ray inside thin
  haze in front of a dense wall has no clear gap; read as one homogeneous slab it put the wall's opacity in front of every
  reader in the haze (a smoke quad 700 blocks out: 0.36 of transmission against 0.88 on its neighbour whose ray left the haze's
  top first), and the boundary between such rays drew jagged grey spires along the haze's top that flipped with the sample
  jitter (Artem's Zearth scene; `tools/dust_wall_split_probe.py` holds both rays within 0.1, `DustWallDiag.split` false is
  the gap-only control).
- The pall composite reconstructs the foreground to the first occupied depth; inside the interval each visible smoke or
  mushroom sample obscures the share of wall opacity behind it; a pane truncates both the wall and the integration
  interval. Once only the haze level is left, a pixel whose haze starts at the eye (first occupied depth under 8 blocks)
  takes it in front of the pall's other air in one step; an eye above the layer keeps the split (contract `hazeonly`). Quads and source probes take the atlas's transmission on screen (`pall_front.glsl`: `dustWallThrough`; `DwDiv`
  0: no pass this frame) behind the pall's haze in front of them. Each tap contributes only its dust in front of the pixel's
  depth.
- Java readers read the drawn wave, not the law's carpet: mushroom ground under the cap (`PallField.drawnTau` in
  `MushroomField.groundUnder`), breach-dust merge height (`PallField.drawnTop` in `BlastDust`), the pall's eye ray limit
  (`PallField.addDrawnEye` in `NukePall.eyeDust`), eye adaptation inside (`DustSources.eyeDepth`, its boost on the haze
  share only). The camera's sound, hearing and medium read `BlastWall.sigma`/`tau` (laws, `nuclear.md`).
- Visible dust undrawn for 20 frames warns once with the reason (`DustSources.undrawn`); with `incoming.nukeDiag` a
  per-second summary logs eligible and drawn sources; `incoming.dustwave.notReadyFrames` (env
  `INCOMING_DUSTWAVE_NOT_READY` in dev runs) forces the not-ready path.

## What does not draw ground dust
- The pall draws only haze, the mushroom, the Wilson shell and the firestorm; its table has the haze row alone
  (`PallField.ROWS` 1: rgb by radius, a by shift along the wind, the smoke parcels, `nuclear.md`). There is no fallback camera dust, HUD veil, pall head, pall surge or crossfade between pictures;
  dust OFF draws none. `NukeDust` draws litter, removed pieces and surface litter only.

## Budget
- Volume budget per preset with the pall's adaptive ladder: LOW 7, MEDIUM 15, HIGH 24 ms (`PallPass.Quality.budget`); the
  wall's divisor and step cap follow the effective rung (`PallPass.LADDER[..].wallDiv()`), doubled once only haze is left.
  Interleaved rays (checkerboard) always; `fx_dustwall_resolve` averages ~10 frames. Dust-wave targets in the young window
  (rungs pinned, 2560×1600, RTX 4060): HIGH 8.5, MEDIUM 6.5, LOW 4 ms; after the window ≤ 2 ms.
- Program size: every inlined call of `dwDensity` copies the whole density into the linked program (llvmpipe
  `GL_PROGRAM_BINARY_LENGTH`: one call site 318 kB, a second one 725 kB, per-frame switches around it 1.33 MB, where the march
  doubled; 725 → 318 kB moved it 0–10 %). `dwAccumulate` has one call site; `dwSourceDensity` calls `dwDensity` once and
  switches the noise by a global (`gDwMean`). Check the linked size after an edit that adds a call of the density, bound or light.
- The pall's composites with the dust atlas readers (`fx_pall_composite`, `wall_layer.glsl`) run at full resolution: 4.9–7.6 ms at
  2560×1600 (take 33's scene); the dust read is 4–5 ms of 6–8 (Artem's Zearth scene). From rung 5 the composite cuts the
  filtered taps once at the pixel's depth (`gWallCutOnce`, `Step.cutOnce`; equal on uniform taps, `wall_layer_identity.py`). Of the dust read above the
  layer (2.0–3.6 ms): the second, layer-frame composite 0.7–0.9, the medium in front of the wall 0.1–1.2, the depth class
  0.1–0.2, the rest the per-tap clipping; the gather takes the four nearest march pixels (a 3 × 3 tent cost 1.7–2.5 ms more).
- The march's integration features (terrain atlas, outdoor-air window, interval bookkeeping, per-source loop, material
  read) cost together 5–10 % of the march (compile-time variants, 2e); the march is 1.35–1.55× the single-source probe's
  late in the young window for another reason.

## Pitfalls
- A view from above shows the ground through the dust only where something is drawn behind it: vanilla's render distance is
  a sphere (at 12 chunks nothing 400 m below is drawn: a 70° ray from 400 m meets the ground at 426 m, 27 chunks), so such a
  frame has sky behind the dust and reads opaque at 15 % transmission; Voxy or DH LODs put the ground there. Judge views
  from above with LODs (`tools/client_scenario.sh commands voxy`) and the march's dump (`dw:dump`, `dw:probe=-2`).
- The per-ray tuft (T6) lifts the far top seen edge-on only: a ray meeting the band nearer than about 1 km looks down into the
  near dust and takes none (`dwRayTuft`). Clamped to its 600 m column point, every such ray took its azimuth's tuft and lifted
  the near dust by screen column: hard vertical bands from the top down to the frame's foot (`probe_gl.py tuftnear`).
- The blend window (24–60 s) evaluates both levels and costs their sum (the march jumps about 35 % at +25 s). One level per
  march sample, picked with the blend weight (stochastic level selection), cut the late window's march by a third (HIGH +31 s
  16 → 10.5–11 ms) but shimmered with grey streaks and specks at +45–60 s and drew the dust darker (fresh judge; `clip_metrics`
  flicker 0.56 against 0.36/255, share over 1/255 12 % against 7 %): transmittance averaged over frames is not the blended
  density's. A cheaper window needs a blend that keeps one density per sample.
- Nothing rides with the front or is placed by bearing × the front's radius: texture riding out with it slides at its speed
  and flutters as the front passes under the eye, a ramp shared by every point reads as one mass carried out from the
  centre, features placed by bearing × the current radius slide along the front (`DustWallGlslTest`, the turning and births
  checks in `tools/dust_wall_gl.py`).
- Flicker is fixed in time, not by blur: the sample grid is fixed along each ray from the eye, its offset moves every
  frame, the ray moves within its march pixel every frame (a quarter-resolution silhouette otherwise draws a staircase of
  4-pixel rows), and the resolve reprojects through the settled layer top, reads on the march lattice, checks each history
  sample against the previous depth, clamps to the depth-alike 3 × 3 neighbourhood with the pixel's own sample, and restarts
  on another detonation, a clock jump or a view resize. A fixed offset sparkles along the far top where 9–18 m steps cross
  the moving billowed surface. A shadow tap reading the fine texture re-shades the approaching face every frame.
- A long thin span (the veil over the billowed top, thin dust whose span grew) knows nothing of the cells it covers: near the
  leaning face its next sample lands deep in the layer, past the lit skin (light 0.10 against 0.32), and which ray skips
  moves with its per-frame offset, so the history keeps dark specks. A dense sample right after such a span finds the span's
  first dense cell by density alone and the thin sample is taken again with its span ending there (contract `skin`). Each
  march pixel's offsets start at its own integer-hashed phases: a spatial pattern (interleaved gradient noise) keeps its
  pattern through the history as a lattice of dots.
- Where the scene has no depth the ray ends on the nearest source's relief traced from the eye in 64 steps (`dwRayFloor`;
  24 stepped over a 64-block ridge 400 blocks out); where the world is drawn its depth alone ends it. Minecraft draws no
  ground there, so a ray ended on the undrawn ground in front of a wave standing on it: the wave floated over a strip of
  sky between the drawn edge's angle and its foot's (15 kt from 2 km, 12 chunks: a ray 0.5° down ended 315 m out, the wall
  400 m out, transmittance 1.000 against 0.005 one march row up; 7 px at 1080p, shrinking as the wall came in). A ray under
  the undrawn ground's horizon along its bearing is moved toward that horizon (`dwRayFloor`'s `dir`, presentation):
  `dir.y + w ×` the rise it needs to clear the ground, `w` full for an eye up to `DustWallPass.HORIZON_LIFT` (4 m) over the
  floor at the drawn edge, none from twice that, 0 when an LOD mod drew the far ground this frame (`SceneDepth.lod`; an
  LOD mod whose far ground is not built yet keeps the strip). A standing eye's strip shows the horizon ray, the foot on the
  drawn edge; a hill or a roof keeps main's ray end. Rows stay in order and each changes at its own eye height: one raised
  ray for every row flipped the whole strip within 5 cm of eye height, a fixed raise angle drew the wave below its foot from a
  hill and through LOD holes (`tools/dust_wall_lift_threshold_probe.py`, `dust_wall_horizon_probe.py`, both in
  `dust_wall_gl.py`; the root cause: a raised ray chosen from a quantity constant across the strip's rows). The 1 px left at
  the edge is vanilla's fogged last metres of terrain (`tools/dust_wall_contract_gl.py horizon`). The horizon, the ray's end
  and a part-way ray's second search share one loop (a dynamic second pass): a second loop of floor reads was unrolled by
  AMD's compiler (`fx_dustwall` 114 → 150 KB). The march, resolve and
  composite judge "the same place" by one rule (`pallSamePlace`: a looser rule in one pass let dust from behind a foreground
  edge through another). The frame air (haze, weather) lies in front of the dust (`pallAir`, `pallAirLightOf`).
- A source's terrain map spans its pall's whole extent (1 Mt: 508 m cells) and its four fixed columns per cell know 4–13 of
  16 384 cells on the Zearth Silo map: laid on it alone, the dust's floor stands 10 m over the eye in a valley, the drawn ground
  13 m under it with no dust on it, and every ray past the drawn chunks ends at their edge (a clean strip under a
  flat-bottomed deck; step 7, `DustWallDiag.probe` −2 writes each ray's end, the scene's distance, the relief's end and the floor
  there, `dw:ground=` dumps the maps and the tiles' known cells and tick cost). The relief search past the drawn chunks reads
  the floor the dust lies on (`dwFloorCoarse`: searched on the map alone, rays down a valley ended 56–70 m past the drawn
  chunks, `DustGroundTest`) and keeps the pall's lift law (that floor dropped under the ray at the start, as it was four map
  cells on).
- Program size: each inlined copy of `dwFloor` (three tile fetches) costs AMD's compiler ~1.5 KB; one copy in every light
  path adds 11 KB (127 against a 114 KB budget). The floor is read once per march sample (`dwSourceDensity`'s caller), its
  density, light and light paths take it (`gDwFloorAt`); the relief search's loop reads the coarse floor (116 KB).
- Colour: one soil colour in white light draws the face as one tone whose rolls read only by light (chroma ±2.7 over the face
  against ±9.8 on the reference wall, corr(lightness, chroma) ≈ 0). `dwDustTint` (presentation): coarse soil low darker and
  earthier and fines on the billow tops paler (`gDwLow`: half the height in the column, half the billow texture), ±4 % per-point
  soil tint (seed), debris over hard ground greyer; the textured level only. A hue deepened with the main light's depth draws a
  grey disc with a light ring where the main light switches between the fireball and the sun (the one-tap light's own
  boundary) and a dark grey swath down the face, so the light term keeps one hue. On MEDIUM and LOW the one shadow tap goes toward the
  brighter light and the other light takes its depth to the layer top along its own direction (one depth for both held
  their ratio over the face: one hue; chroma detail 2.7 against HIGH's 3.8, now 3.6); where the lights are within ×1.5 of
  each other both taps are taken and the assignment blends (a hard switch stepped 12.9/255 at the median: an arc sweeping
  over the face as the ball cools; `tools/uw_probe/onetap_flip.py`, run by `dust_wall_gl.py`, holds it under 1/255). At approach distances the frame air
  in front of the face carries ~45 % of its luminance and ~80 % of its blue (13 % air share at 700 m, the 20 km law, against
  a face 4–5 times darker than the horizon sky at noon): an albedo tint reaches the screen at under half its amplitude,
  so colour work goes through the light, not the tint (step 8, dev client: tint off or ×3 moved chroma by 0.2).
- Eye inside the dust: the adaptation boost applies to the haze level only (on the textured level it washed the view to
  white); crest tufts apply only while the eye is above the band (inside they drew vertical bands).
- The ray bound must stay under a 120 m camera, or every near-eye ray marches fine steps through empty air.
- In RGBA16F, one transmission step changes reconstructed depth by about 5 % at 1 % opacity and 1 % at 5 % opacity.
- The harness path (no `DW_SOURCES`) declares the source uniforms in the harness preludes: `ShaderSourceTest` reads
  uniforms under `#else` too. A harness loop to a constant 16 with `break` unrolls and times out: loops run to `sources`.
- Shader checks without a game: `tools/dust_wall_gl.py`, `tools/dust_wall_contract_gl.py` (`volume` and `crossing` cases
  isolate the crest and pane from texture noise), `tools/nuclear_volume_warmup_gl.py`, `tools/uw_probe/probe_gl.py`.
