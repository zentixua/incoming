---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/fx/layer/DustWallPass.java"
  - "mod/src/main/java/ua/zentix/incoming/client/fx/layer/DustIntervals.java"
  - "mod/src/main/java/ua/zentix/incoming/client/nuclear/DustSources.java"
  - "mod/src/main/java/ua/zentix/incoming/client/nuclear/DustMaterialTiles.java"
  - "mod/src/main/java/ua/zentix/incoming/client/nuclear/PallField.java"
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
  `DustWallPass.ViewState`, swapped inside `PallPass.ViewState`). One source table holds every source (`DW_ROWS` = 35 rows
  each: arrival row, 32 log-age rows, the spray row, the frame-input row; `dwLoad`); slots are stable per detonation
  (`SLOT_OF`, idle slots free after 120 frames). A ray draws its eight nearest source crossings in full, further ones
  noise-free (`gDwFull`). A source first drawn already aged fades in over 1 s (live only).
- Ground: each source has its own 128² terrain map (`PallGround`, atlas `Sampler3`) and two ground-material tiles
  (`DustMaterialTiles`: fine at the server's dense cell, coarse at its outer cell; RGBA8 water/sand/hard/known, filled
  1024 cells a frame from `SourceMaterial`; a known cell never changes). Water is a material of the same wave: the material
  mixes the dust law's column with the spray's (`dwMaterialLaw`, spray row); a water burst's base surge is a second
  source term (`dwSurge`). The dust never lies indoors (the outdoor-air window) and never under the fireball's ground
  footprint.

## Laws as inputs
- The laws decide where, when and how much; the model draws it. `PallField.waveTable` (built once per detonation) holds
  per ground radius: front arrival, dust over hard ground (`paved`), the fire zone's share; per log age
  (`lifetimeAge`, `AGE0` 0.05 s, to `lifetimeEnd`): the drawn top and the optical depth the transfer reads, with slopes
  (cubic Hermite in the age coordinate); and the spray law per node (`lifetimeSpray`). Settling and dilution are the law's
  alone (`DustModel.Column.depth`); the age clock is `NukeClock` through the detonation.
- Presentation deviations (marked in code and in the guide's deviation list): the drawn height ×68/58 of the law's
  (`PallField.PRESENT_HEIGHT`: the crest just under a 120 m camera's horizon at 4.5 km from 1 Mt), the hump, the transfer
  (`dwTransfer` ↔ `PallField.transfer`: optical depth above 1 drawn compressed) and the sight inside old dust
  (`PallField.visible`: about 200 m at one minute, 700 m by five, then the law's own thinning).

## Readers
- The pall's rays end at the wave's dust: in the pall interval the dust pass runs first, then `fx_dustwall_limit` writes, at
  the pixels the rays read this frame, the world distance or, nearer, the view depth where the resolved dust (read as the
  composite reads it, `wallLayer`) lets less than the rays' exit share (`RAY_EXIT`, 2 %) through; the rays take it as their
  world distance. Behind it the dust hides the pall, so the composite's light and alpha stay within that share. The pall's
  GPU parts are summed in the order their stamps were issued (`PallPass.stamp`).
- The march writes three outputs: light/opacity, occupied view depths with the opacity-weighted distance, and two intervals
  (`wallSplit`: A to the first clear gap of more than max(48, 0.1 t) blocks, B the rest; A exit × cover, B entry × cover,
  A opacity, A share × cover). Readers take the transmittance in front of their own depth by interval (`wall_layer.glsl`:
  `wallPrefixT`, `wallPrefixShare`, `wallTo`, `wallCut`; Java copy `DustIntervals`, `DustIntervalsTest`).
- The pall composite reconstructs the foreground to the first occupied depth; inside the interval each visible smoke or
  mushroom sample obscures the share of wall opacity behind it; a pane truncates both the wall and the integration
  interval. Quads and source probes take the atlas's transmission on screen (`pall_front.glsl`: `dustWallThrough`; `DwDiv`
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
  (`PallField.ROWS` 1). There is no fallback camera dust, HUD veil, pall head, pall surge or crossfade between pictures;
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
- The pall's composites with the dust atlas readers (`fx_pall_composite`, `wall_layer.glsl`) run at full resolution, outside the
  ladder: 4.9–7.6 ms at 2560×1600 (HIGH and MEDIUM, take 33's scene) against 1.3–3.7 ms on main. Of the dust read above the
  layer (2.0–3.6 ms): the second, layer-frame composite 0.7–0.9, the medium in front of the wall 0.1–1.2, the depth class
  0.1–0.2, the rest the per-tap clipping; the gather takes the four nearest march pixels (a 3 × 3 tent cost 1.7–2.5 ms more).
- The march's integration features (terrain atlas, outdoor-air window, interval bookkeeping, per-source loop, material
  read) cost together 5–10 % of the march (compile-time variants, 2e); the march is 1.35–1.55× the single-source probe's
  late in the young window for another reason.

## Pitfalls
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
- Where the scene has no depth the ray ends on the nearest source's relief traced from the eye in 64 steps (`dwRayFloor`;
  24 stepped over a 64-block ridge 400 blocks out); where the world is drawn its depth alone ends it. The march, resolve and
  composite judge "the same place" by one rule (`pallSamePlace`: a looser rule in one pass let dust from behind a foreground
  edge through another). The frame air (haze, weather) lies in front of the dust (`pallAir`, `pallAirLightOf`).
- The terrain map is coarser than the world: the dust extends a quarter to half its height below it, so it never hovers
  over lowlands beneath the map.
- Eye inside the dust: the adaptation boost applies to the haze level only (on the textured level it washed the view to
  white); crest tufts apply only while the eye is above the band (inside they drew vertical bands).
- The ray bound must stay under a 120 m camera, or every near-eye ray marches fine steps through empty air.
- In RGBA16F, one transmission step changes reconstructed depth by about 5 % at 1 % opacity and 1 % at 5 % opacity.
- The harness path (no `DW_SOURCES`) declares the source uniforms in the harness preludes: `ShaderSourceTest` reads
  uniforms under `#else` too. A harness loop to a constant 16 with `break` unrolls and times out: loops run to `sources`.
- Shader checks without a game: `tools/dust_wall_gl.py`, `tools/dust_wall_contract_gl.py` (`volume` and `crossing` cases
  isolate the crest and pane from texture noise), `tools/nuclear_volume_warmup_gl.py`, `tools/uw_probe/probe_gl.py`.
