---
paths:
  - "mod/src/main/java/ua/zentix/incoming/client/fx/**"
  - "mod/src/main/java/ua/zentix/incoming/client/far/**"
  - "mod/src/main/resources/assets/incoming/shaders/**"
  - "tools/gen_particles.py"
  - "mod/src/test/java/ua/zentix/incoming/client/fx/**"
---

# Own particles, effects layer, trails

## Pitfalls
- The vanilla shader `position_tex_color` discards fragments with alpha below 0.1: faint things (distant smoke, glow,
  dots smaller than a pixel) vanish entirely, so the effects layer draws with its own `fx` shader. The vertex color
  (`BufferBuilder.setColor(float…)`) is a byte without clamping: above 1 it overflows. Vanilla fog turns blue in rain
  (`FogRenderer`: red and green × 0.5, blue × 0.6), so do not mix distant haze with its color.
- The layer's GPU cost is measured by the probe `fx/layer/LayerTimes` (the `FxLayer.describeTiming` line, F3 on the right;
  it runs while the line is read: F3, the line over the last 5 s, in the scene bench, or `always()` for scenarios and the
  sandbox). Without it there are no marks at all, and neither is there for `GpuTimer`. Marks sit at pass boundaries
  (`LayerTimes.Pass`: model atlas, depth, water, pall with volumetric smoke, heat, probes, quads, glow): every frame, read
  2–3 frames later. A mark between adjacent passes “leaks” (the tail of the previous work lands in the next pass), so on
  request (F3 or `-Dincoming.fx.drain=true`; the scenarios and the bench that only read the line run without it, since a
  20–28 ms hitch every 2 s was in their own measurement, look_gate 25.10) every 2 s a drain frame runs: `glFinish`
  before each mark, the passes are exact, and the quads are split again by part (`FxQuads.Part`: puffs, veil, ribbons,
  fire, glare, particles, debris trails, other), each part with its own sort segments in one `glMultiDrawElements`, with
  time and pixels (GL_SAMPLES_PASSED) measured separately. The frame before the parts is copied and then restored, so the
  screen shows the same frame. A quad's part is set by whoever writes it (`FxQuads.part`; the previous version back:
  `LayerPartsTest`). A new drawing in the layer comes with its own part, otherwise it is “other”.
  With half-resolution smoke (`HalfPass`) each part draws as the frame drew it: its quads behind their tiles' cuts in the
  frame, the rest into the target and laid; target samples count four frame pixels in the part's screens.
- Soft smoke at half resolution — `fx/layer/HalfPass` (off-screen particles, GPU Gems 3 ch. 23; the low-resolution
  translucency of Unreal/Unity). Puff cost is the fragment count: 0.73–0.80 ms per screen of fill at 2560×1600 (RTX 4060
  laptop, sandbox raid of 40, drain frame), of which raster and blending of the quad area is ~0.2 ms per screen even with a
  constant shader (the overdraw is local: tens of layers in a cloud's middle), the six-way light maps and powers ~0.2,
  places and alpha the rest; a puff-only program saved 7 % (not register pressure). Puffs, veil cells and glare in the
  eye (pure light, alpha 0, a smooth halo; sorted by its source's distance, night glare would cut most tiles) at least
  `HalfPass.WIDE` (24 px) across both edges draw into an RGBA16F half target (light premultiplied, alpha transmittance:
  blend ONE, 1−a / ZERO, 1−a), laid once onto the frame (`fx_half_composite`, ONE, SRC_ALPHA) with depth-aware upsampling:
  a checkerboard min/max half-resolution world distance (`fx_half_depth`) gives every 2×2 sample neighbourhood both sides of
  a silhouette, a pixel takes bilinear where the four samples lie at its depth (10 %) and the nearest-depth sample where
  they straddle an edge, so smoke stays sharp in front of and behind a roof edge. Thinner quads (a lying dust club
  edge-on, a few pixels thin) stay at full resolution: at half they broke into steps and holes. Puffs are mostly drawn
  smaller than their 128-pixel frames, so the frame's finest octave was already filtered; 1:1 crops show the same
  billows. The target has no hardware depth: smoke behind the world is hidden by the world distance (`FxHalf`: soft edge,
  or a hard test at softness 0), and outputs clamp to 0..1 as in the 8-bit frame (a NaN in the float target drew black
  blocks). Order: per tile (64 px) the cut is the sorted place of its last full-resolution quad (fire, glare, particles,
  a thin puff); on that tile a soft quad behind the cut draws in the frame in order, one in front of it into the target,
  so each tile gets its full-resolution quads in order and the half smoke on top, which is the layer's order exactly
  (fire inside smoke: smoke behind it full, in front of it half). The target is laid right after the last soft quad;
  nearer quads (glare and veil of bright sources in the eye, particles at the camera) draw over it in order and set no
  cuts: night glare covers most of the frame, and with the lay at the end 370 of 6100 puffs went half in the night raid of
  40. A quad over tiles on both sides draws in both passes, each fragment by its tile (`HalfCut`, `quadPlace` =
  gl_VertexID / 4) and the quad clipped (`gl_ClipDistance`) to the bounding rectangle of its tiles on that side
  (`HalfRect`, per quad and pass); upsampling mixes only samples of tiles with the same cut. Two draw calls per frame plus
  one lay: laying the target before each fire in smoke (35–56 lays in the raid) cost ~30–40 µs each (the lay waits for the
  target), deciding whole quads chained nearly every puff behind some fire to full resolution, and a draw call per run
  (hundreds, each a full `apply`) starved the GPU. `HalfPassTest` composes random tile scenes against the layer's order.
  Sandbox raid 40, 2560×1600: puffs 0.26–0.34 ms per screen, quads pass 5.7–6.7 → 3.0 ms (edge, 45 s), 4.8 → 1.9 ms (60 s).
- Own particles (`client/fx/particle`) are not the vanilla engine but their own pool (`FxPool`) and their own layer after the
  world (`fx/layer/FxLayer`). Vanilla's layer has one queue without sorting (whoever was born later is on top: a launch smoke
  10 blocks away lies behind the impact columns hundreds of blocks off), an overfull queue silently evicts old clouds, and a
  distant picture drawn after the world lies over near smoke. The layer sorts all quads of the frame from far to near, and
  blending is one mode, with premultiplied alpha: sparks and flashes only glow (alpha 0), flame covers and glows (adding red
  to the blue sky gives pink). The hardware depth does not see Distant Horizons LODs, so the layer reads the distance to the
  world behind a pixel (`SceneDepth`, with DH depth). The glare and veil of a bright source (fireball, fire, torch) sit at
  the eye and are not cut by their own pixels: they are seen as much as the source itself is visible in the frame. A probe
  per source (`SourceProbes`, shader `fx_probe`) measures the distance to the world at the middle and along the upper half
  of the source's circle on screen, against its front half; a quad takes its probe by number (`FxQuads.seenBy`, minus the
  number in the softness field). A ray over the terrain (`Sightline`, steps from 16 blocks) passes over hills, and the DH
  LODs do not see it: a fire outside the city would shine through houses in the distance (under the shaders, when zoomed).
  The layer's own texture sheet (`FxAtlas`) is one texture for the whole sorted pass, not the Minecraft atlas: its
  downscaled copies (`MipmapGenerator`) zero the transparency below 96 of 255 (for foliage), and smoke behind a wall a
  hundred blocks away would vanish entirely (scenario `occlusion`). A particle up close is drawn as from the Minecraft
  atlas: the nearest pixel of the picture and copies half as small, and anything fainter than 0.1 alpha is not drawn (as in
  the vanilla particle shader; without this the puffs come out soft, and faint halos add up into a murky haze around the
  cloud). A puff under 32 pixels is smooth, without a cut-off. A glow (arc flash, halo) that is blocks in size up close uses
  `Spec.smooth()`: with the nearest pixel, a 64-pixel flash picture covering half the screen falls apart into rotated
  squares of its own pixels with a hard cut edge at 0.1 (frames of a substation arc knocked out by a nuclear strike,
  scenario `flash`). The halo `far/glow` (glare, veil, fire glow, trail ribbons, `FxQuads.glowShape`) larger than a quarter
  of its picture on screen is computed by the formula in `fx.fsh` (`glowAlpha`, the same as in `tools/gen_particles.py`;
  `PallGlslTest` checks it against the picture). Smooth from a 64×64 picture, the veil of a fireball across the screen used
  to go in steps of 25-pixel squares (a kink of the bilinear sample at every picture pixel, and 8 bits in the pale tail);
  smaller ones come from the picture and its copies. The head of the warhead's trail and the afterimage of the nuclear flash
  (`NukeRenderer`, `NukeFlash`) use only the formula, with the own `nuke_flare` shader (no picture). The glare and veil of
  the nuclear fireball are handled as for any bright source (`FarSprites.eyeLight`, the visibility probe); the body is the
  mushroom's volume. The probe also takes the transmission of the pall, the dust walls and mushrooms of the frame, for all
  sources: the world depth does not see their volume. The pall counts up to the front edge of the source, mushrooms up to
  its middle, except their own cap. The pall uses the same code and numbers as the layer's quads (`pall_front.glsl`:
  `pallThrough`, maps and numbers in `PallPass.bindFront`). Mushrooms use `fx_probe`: `probeMush` (a fireball behind
  someone else's cap or stem; a mushroom whose cap middle is the source itself does not block itself with its cap, which
  blocks only up to the front edge with the span of the puffs, while the stem, skirt and water column in front of the cap
  do block). So the glare and veil quad with its probe is not cut at its corners by the pall and mushrooms (`fx.vsh`): the
  light in the eye comes from the source, not from the quad's corners, and a corner inside its own cap would dim the glare
  above it, and the dust would be counted twice. The probe runs after the pall pass, which lays the frame's maps and
  mushroom table. Under Iris the layer runs after the shaderpack passes: the pack does not light the smoke and it is not in
  the pack's fog (light is the Minecraft light map, except for particles with an already-lit color, `Fx.Spec.prelit`: dust
  at the nuke's camera is lit by its own wall, `nuclear.md`; where the client has no chunk, it is open sky, as for a trail
  ribbon: vanilla is dark there, and the puffs of a salvo's trail the viewer has left would land on the bright ribbon as
  black beads). On top of that comes the light of the frame's fireballs (`fx/layer/FxLights`: up to 4 most visible, in the
  layer's shader, as (r/d)² against the light around the eye, not brighter than day; without it, at night the dust near a
  fireball is lit only by the moon, blue, and over a yellow fireball the puffs come out lilac). The fireball's light around
  it, on smoke, glare, veil and glow, is not brighter than the fire of its frame on screen (`FxAtlas.fireLightAt`, table
  `fireball.json` from `tools/bake_fx/pack.py`; for the previous fireball, `ball.json` from `gen_particles.py`): otherwise
  at night ×1200 light keeps the dust under the fireball, already black with soot, in an orange ring. Minecraft fog dims a
  particle rather than tinting it its own color: opaque patches of the fog color at the draw-distance edge would lie on the
  ribbons as bright beads and cover the distant picture of the explosion, which there replaces the particles. But it
  replaces them itself: beyond 0.8 of the draw distance a particle fades by `FxFrame.near` (1 − `FarView.farShare`). With
  Distant Horizons there is no vanilla fog, and without this the particles of an explosion at 400 blocks would be drawn
  together with its distant picture (the particles' smoke covers the fireball).
- Water decal (`fx/layer/WaterDecal`, shader `fx_water`; the dark ring of a nuclear wave): a full-frame pass
  before the layer's quads, without hardware depth. The place behind a pixel comes from `SceneDepth` and the frame's inverse
  matrices (a point on the ray between the near and far planes, with the distance along the view axis: vanilla's view sway
  is in the projection). The water is the surface at the top of still water, 8/9 of a block (water writes depth in vanilla,
  Sodium and under Iris; in “Fabulous” its depth is not in the main frame, so lakes in the draw have no ring). The surface is
  a horizontal plane from neighboring pixels: one height per block would catch every row of blocks in a stripe on walls,
  trunks, grass, mobs and slopes, giving dark stripes on the walls and trunks the front passes, white at the epicenter, and
  stripes at a detonation on land (the decal applies to every detonation).
  Neighbors are their own `SceneDepth` samples on both sides on each screen axis; of the two, the one nearer in height is
  used, not `dFdx`/`dFdy` of the pixel quad. At an object's edge one derivative jumps to the background, and a sideways step
  with a jump along a nearly horizontal ray gave a “horizontal” (dots on treetops, grass, roof ridges and wall tops in
  front of the sky, at each ring). Near the shore, a boat or a post, the quad's slope came out steep, and at the edge the
  water stayed light for 1–2 pixels. The height drop to the chosen neighbor is depth noise plus a tenth of a pixel step
  across the ray (`len·Pixel`): at a wall the drop is the whole step, at a jump to the background along a nearly horizontal
  ray it is larger the farther the jump is from the pixel step over the surface, and far away the pixel of a side face
  under the edge of a block's top stands 1/9 of a block below its neighbor on the top (with a tolerance of a third of a
  pixel step, the rows of a forest canopy at 200 blocks were outlined as “water” along their lower edge). The step is no
  more than three pixel steps over the surface. Through holes in foliage the neighbors are different faces, and without a
  tolerance on the drop the canopy would be speckled with “water”.
  The height tolerance is 0.01 block up close (block tops at 14/16, a chest, an ender chest, 7 layers of snow, lie within
  0.014 of 8/9; mud and soul sand are drawn as whole blocks, at 14/16 they have only collision) and up to 0.037 at the draw
  edge (the depth error grows as d²). Under a shaderpack (`IrisApi.isShaderPackInUse`, via reflection) the surface is lower:
  Complementary Reimagined by default lowers the water top with vertices (`WAVING_WATER_VERTEX`: −0.05 ± 0.025 block), and
  with vanilla's tolerance there would be no decal on the water up close at all. So the tolerance downward is wider by 0.08
  (`WaterDecal.PACK_DROP`, which also takes in the top of a chest), the drop to the neighbor is further widened by the
  difference of their surfaces between vertices (`PACK_STEP`), and both ways by the frame jitter for TAA (`PACK_JITTER`:
  depth is written by a ray shifted by a fraction of a pixel, so the height is off by the shift × distance; with
  Complementary by default it is up to 0.055 pixel, up to 0.014 block at 150 blocks). The pack sways the foliage, and in a
  wide band in the canopies rare “water” dots remain. Beyond 0.8 of the draw distance the surface is at sea level (LOD DH
  draws water in whole blocks), or a place below sea level uses the intersection of the ray with the surface (LOD without
  water depth shows the bottom). That is why at a distance beaches exactly at sea level darken, and so do the bottoms of
  gullies below it, while lakes above sea level do not.
  The noise of the ripples and foam is its own for each ring, measured from its center, not from the world: far coordinates
  in a float have no fractional part, and coordinates taken modulo would tear the noise at the module's boundaries. A shared
  noise from the ring that is most visible at that spot jumped where two rings were equally visible. The foam streaks along
  the wind in polar coordinates of the ring (along it the radius, across it the arc): a place from the center, turned along
  the wind from that same center, is the radius and a zero, and the foam would run in thin circles around the detonation.
  The angle is measured from the world axes, in 24 sectors: the arc is measured from the axis of its own sector, and the
  neighboring ones meet smoothly, with the same noise strength. An angle from the direction to the camera dragged the pattern
  sideways when the camera circles the detonation (a boat, a wave throw, a replay), and an arc from one axis for the whole
  circle skews the streaks far from it (the arc grows along the radius too).
  The ring profiles are their own 64×4 RGBA8 texture (written once, then `glTexSubImage2D`). Under a shaderpack the pack
  draws the water, and the decal lies over it after that and only darkens and whitens what the pack drew (the sky
  reflections it shows under the decal darken together with the water).
- Particle places are counted by `FxBudget` groups (blast clouds, ground puffs, trails, fragments, splashes, flashes, sparks,
  the `SCORCH` flash smoke, ash). A full group does not birth new particles (`FxPool.refused` counts how many were not
  born; the `SCENARIO particles` line). Blast clouds (a fireball cooling into smoke) are kept apart from long-lived dust,
  smoke in the crater from the fire, and launch smoke: in one shared group the latter take all the room within a couple of
  seconds of a salvo, and the smoke of late impacts is never born. The smoke of a full MLRS pack fits by construction
  (`ExplosionsTest`). Thinning births by how full the group is is not allowed: the cloud group is held for half a minute by
  the long puffs of the first explosions, and all the following smoke disappears (in the full build on a host the smoke
  behind a wall vanishes completely). A new kind of smoke goes into its own group via `Spec.budget`. Culling by the frame
  uses the sphere of the whole quad (`FxParticle.reach`), otherwise a puff disappears at the screen edge. Check with
  `tools/client_scenario.sh occlusion [shaders] [dh]`: salvos behind a wall, and the particles by group in the log.
- The projectile's flame is one model up close and in the distance, from one record, `fx/plume/Flame`, which `FlameModel`
  derives from the motor of the spec (`ClientWeaponSpec.Stage.motor`, `fx/plume/Motor`) at the projectile's height: the
  core per Eldred, the afterburning flame of CO/H₂ to stoichiometry, the tail to the Draper point, the color from
  `BlackBody`, the brightness from the particles' ε × black body (cached by height in 50 m steps: `BlackBody` runs 200
  iterations). The light in the distance is a disc of the jet's area seen from the side, its brightness the average over
  the parts; it is also the light of the smoke and the ribbons around (`FxLights`). Eye adaptation at the jet and at the
  distant light is one (`FarView.exposure` of the layer's frame), or the brightness would jump at 0.8 of the draw distance.
  The jet's sheath (flame and tail) along the axis follows the model's law in places (`Flame.sheath`: ε(x)·B(T(x)), τ ∝ 1/x
  beyond the core, T beyond the flame ∝ 1/x up to the Draper point). The average brightnesses of the parts are the integral
  of the same law over the side area (`FlameModel.Jet.mean`; previously it was the value at the middle of the part, which at
  the tail is tens of times lower than the average). The middle of the light (`Flame.middle`) is the center of the flow (at
  the rocket's booster exit 4 m, not the 15 m middle of the length up to the Draper point: the light on the smoke landed as a
  yellow spot in the tail). The tail's light on the world runs to `Flame.tailEnd` (the same middle of the tail's flow).
  The layer draws the plume after the world (`PlumeRenderer.collect` from `FarRenderer.collect`). At render time the entity
  only leaves the frame's plume; the entity leaves nothing when seen from aboard. The frame forgets the plumes at the end of
  the layer stage `PlumeRenderer.endFrame`, and when the layer did not collect. Air haze is handled as for the distant light.
  It is not `RenderType.eyes`: under Iris everything up to the end of the world goes through the shaderpack (Complementary:
  “eyes” ×1.5 to the power 1.5, and the clouds and sky haze over what does not write depth), and by day the plume, 75 times
  brighter than a white wall, came out as a pale scrap at the nozzle (the RV2 gate, start from the side;
  `PlumeRendererTest.fireIsNotDrawnThroughTheShaderPack`). The screen light of a self-luminous source is `Sight.onScreen`
  (dimmer than white in linear space, and to the screen in sRGB; the same for the distant light's core `FarSprites.core`,
  which has no jump at 0.8 of the draw distance). The plume is the `far/glow` profile across it (soft edge, the same light),
  sorted at a length closer than its nearest corner, over the smoke of its own exhaust (glow and diffuse light add up, ⚠
  self-absorption is not counted; by quads the column's clouds had cut it in steps). “Diamonds” are not drawn: the afterburning
  flame around the core is 10–1000 times brighter than it. The light (`Sight.light`) of `FarFlightView` and of the entity:
  glare and veil at any distance, and the core of light replaces the plume from 0.8 of the draw distance (`FarView.farShare`,
  the plume fades by the same share).
  The projectile's entity appears on the client at the edge of the draw distance (the server cuts the tracking range
  of its own), and there the Minecraft and shaderpack fog eats the plume, and it has no glare: with the light only in the
  distance, the torch at the edge went out (complaint 18.10, scenario `flame-edge`).
- The thrust of a booster stage (Shahed, rocket) over time is `strike/ThrustCurve` (`Airframe.thrust`): reaching the regime
  in 6 ticks, a shelf, a decline to separation; the remains of the grain burn out in 5 % of the real work (⚠ an estimate;
  for the Tomahawk 0.6 s), with pressure per Saint-Robert p ∝ S^(1/(1−n)), n = 0.35. By the thrust share, the flame
  (`FarLook.stage(phase, age, curve)` → `Stage.share`, `FlameModel.at(motor, height, share)`: in the critical regime the same
  law for the motor with this flow `Motor.throttled`; below the critical pressure drop, the flame at it, and the light ∝ the
  flow `Flame.dimmed`: a fully expanded jet of the law there has zero velocity and infinite radius, and the light would grow
  toward the end of the decline), the light on the smoke and the world, the start cloud (`Stage.flow`) and the sound
  (`Noise.Rocket.curve`). The trail has shelves (⚠ not thinner at the decline). The light cut off by the phase went out in
  one tick (`FlameModelTest.boosterLightFadesWithoutSteps`, `ThrustCurveTest`).
- The hot nozzle of a booster is `fx/plume/NozzleGlow`: the temperature of the walls (the graphite throat insert, the
  carbon-fiber bell) by conduction through the thickness, an explicit grid. The surface is one balance, continuous in the
  thrust share s: the gas per Bartz h·s^0.8 to the recovery temperature, radiation outward through the gas in the nozzle
  (1 − ε_g)^s, air × (1 − s) (the “gas radiation” switch at the end of the work gave a step of ~280 K). The node sits on the
  surface itself (a half-cell, second order; with the node under the surface the graphite's warm-up converged slowly). There
  are 80 nodes in the carbon fiber and 40 in the graphite (a node thinner than the warm-up during the decline: with 20 the
  bell on separation came out 90–125 K colder). The step is no bigger than a quarter of the table step and a tenth of the
  decline (the carbon fiber's stability step is 0.1–0.3 s: the decline passed within one step). The test compares against a
  grid three times finer in time and thickness. The light is ε × black body. The time is real: the game-speed shelf is
  compressed into real time (`NozzleGlow.seconds`), and a separated stage is taken from the end of the work (`spent`), with
  separation being the same instant of the table. The table is computed in the background at the first booster
  (`ClientWeaponSpec.nozzleGlow`: for the Shahed about 0.1 s of computation, which in the render thread would be a frame hitch).
  The effects layer draws it (`PlumeRenderer.glow`, `FarSprites.glowPlate`): the circle of the cut in its plane (from the side,
  a segment), and the throat circle at its depth as a fraction, visible through the cut (`Shine.throatSeen`). In front of the
  cut there is nothing. A spot smaller than one and a half pixels is a round blob facing the camera (a circle stretched from
  the edge would be a needle thinner than a pixel). The hull does not glow (the heat shield is inside). There are no steps in
  the law or the table (`NozzleGlowTest`). Smoke near an entity with glow comes only from the effects layer (`Exhaust.spent`):
  vanilla semi-transparent particles write depth, and everything the layer draws behind them (nozzle, flame, fire) vanishes
  entirely rather than dimming (the nozzle of a separated booster was not visible at all behind its vanilla puff of smoke).
  The close-up is the sandbox verb `spent <weapon> [height]`.
- Cleaning up an empty booster is not physical (`BoosterLifetime`): the grid, the shadow and the hot nozzle fade out together
  smoothly over 2 s, a second before the server removes it (5 s after a fall or 20 s after separation). The renderer passes
  one visibility into the alpha of `WeaponModels.TRANSLUCENT` and into `PlumeRenderer.glow` (after the tone mapping; otherwise
  a disc overexposed at night would stay white almost to the end). The temperature and color stay per the law. The birth time
  and the removal time are synchronized: a late tracking does not restart the cooling; protocol 40.
  `NozzleGlowTest.cleanupCannotRemoveAVisibleHotNozzle` checks the night light in the last frame; `BoosterGameTests` checks
  the real removal after a fall, at the limit, and under the bottom of the world, and the initial data of a late observer.
  Gravity acts on a booster lying on the ground every tick: `move` with zero Y resets `onGround`, and the renderer tumbles
  the already fallen body again a tick later, flickering the nozzle. The same GameTest checks this before removal.
- The projectile's trail is a ribbon along the path's points from the nozzles (`FarFlightView`; the law is `fx/plume/TrailModel`:
  the mass per meter from the flow and the projectile's speed, the thickness against the Koschmieder threshold, spreading and
  bends of the axis by the Pasquill class, the rise of linear thermals, inversion by Schmidt–Appleman; everything that does
  not depend on age the point receives at its birth, since a frame walks thousands of points), not puffs. The wind is one per
  world (`util/Wind`; on the client `fx/ClientAir`; the same for the nuke). Near the ground the place correction
  `util/LocalWind` applies (buildings and canopies per Macdonald 1998, the street, a hill per Taylor–Lee; one per place,
  `ClientAir.site`): the blast cloud with dust and haze (`CloudModel.Air.site`) and a fire's trail take it, while the debris
  of projectiles does not. The place cache is per 4-block cell: the source's surface height (`LocalWind.base`) comes from the
  source itself (`ClientAir.site(x, y, z)`), not from the cell. The thermal measures height from the burst point, and its air
  is lifted to the height of the explosion (`CloudModel.Air.lifted`). The dust skirt (`GroundDust.channel`) runs over open
  ground: from the arc of a ring where a wall above the impact by `WALL`, the mass leaves for the open arc of the same ring,
  and the puff of a sector goes to the middle of the open arc, the range being the same area as in the field (along a street
  farther, but no more than `REACH_MAX`). The puff of a sector runs over open ground in an unbroken chain of places
  (`GroundDust.open`: a step no more than `CHAIN_STEP`; the path of the puff starts at the same place). Below the roofs, the
  part of the debris transverse to a street's axis stays on the puff's path in steps (`GroundDust.canyon`, the axis is `axis`,
  Dobre et al. 2005), and the debris and the growth into a wall above the middle of the puff likewise; the remainder at the
  edge of the street is `out`. Walls are counted from the ground around (`GroundDust.floor`: an impact into a crater or a pit
  does not make the edges and the field around them walls). In the open field (everything open) it is bit for bit the
  previous one. Particles are pulled by the air toward its velocity (`FxParticle`). Engine smoke comes as particles, and the
  trail has no volume either: a smoke grid cell does not resolve a thin trail. Looking along the segment, the tube is seen
  end-on: the ribbon degenerates (the perpendicular to a segment and a ray that are nearly parallel is arbitrary: a fan of
  stripes with a straight edge, and at the nozzle the flame lights it too: a “stick” in 3.5.0). So a segment shorter than
  its width on screen is drawn as a disc of the cross-section with thickness along the view (`FarSprites.ribbon`, `endOn`).
  An eye inside the tube (nearer than half its width to the segment's axis) makes the ribbon (a plane through the eye: a
  gray “sheet” across the sky when an ICBM passes the camera) fade, and the tube's matter lies as a veil across the screen
  with the thickness of the view's path through the tube (`FarSprites.veil`). While the jet hits the ground before its
  Mortonian length (`LaunchCloud.impinges`, `FarFlightView.launch`, on the entity and on the path from packets, only while the
  booster burns: a rocket's turbojet at a slope is not a launch), the products per tick form the launch cloud
  (`fx/smoke/LaunchCloud`: the thermal `Thermal`, dust by the looseness of the ground; the rebuild happens when the heat has
  grown 1.25 times; the baked puffs draw it). The dust near the ground is carried by the wall jet `fx/smoke/WallJet`: the
  share by azimuth per Hasson–Peck, equal shares by the inverse Kepler equation, the entrainment r⁴ ∝ ∫i dt (by the flow's
  history: I·t − Σ dI·τ; the earlier single kick at the start overstated the carry-off of a long-flow ICBM), up to the place
  where the wind overtakes it. A dust puff is a segment along the horizontal share of the jet, its middle and spread along and
  across from the moments of the dust of the entrainment (`WallJetTest`). The points of the ribbon at this tick are its own
  (`TrailPoints.cloud`). The ribbon carries only the column of the jet itself between the nozzle and the ground
  (`LaunchCloud.column`: ṁ/u(x) per meter, u(x) = u₀·B·d′/x, Pope), at the nozzle's current place, and when the jet has let
  go of the ground, at the place it had, which stays as a trail. Without the column under the rocket there was nothing while
  it was below ~38 m (the start behind by 1.5 s, F4). The ribbon of the particle places does not take room: a full buffer of
  points overwrites the oldest. The ribbon is turned toward the camera, and when you look along the trail it fades to nothing.
  The ribbon is drawn only where the tube on screen is already between `FarFlightView.PUFFS_FROM` and `PUFFS_FULL` (2.4–4.8 px,
  the sigma of the cross-section from one to two pixels, the width at the point of the segment nearer the eye): wider, it
  is a flat strip with straight edges and one color across it, and at the camera a plane across the frame (the SAM trail,
  complaint 22.10; with a 6–12 px threshold a salvo of Grad at 1.7 km went in ribbons: a strip of twelve ribbons, VXC4). A large
  tube is drawn by the baked puffs of clouds (`SmokePuffs.tube`: places at ≤ 1.2σ, 2^k per segment (no more than 128), with the
  new places fading in by a share as σ grows, without jumps; each puff has its own frame, reflection and wander, otherwise a
  stack of coins), and the eye in the tube gets their veil (below). A puff on screen is an almost flat disc (the radius of the
  flat disc of the same coverage along the chord through the middle: 0.71–0.80 of the square's half-size by frames,
  `SmokePuffs.PUFF_DISCS`, checked against the pictures by `PlumeContinuityTest`). The share of a tube's puff cluster is by
  the number of puffs covering its middle (`SmokePuffs.centreCover`: by layers, on average over the cluster's area, the
  axis of a thin trail near the nozzle came out 1.4 of the law). It is not a Gaussian: the visible edge and the opacity at the
  axis belong to the tube (the thickness across the axis e/(√(2π)σ)), not to one Gaussian of the place. The step of places is
  no wider than the half-chord of the smallest puff's disc, shifted by the wander across the axis (`tubeStep`: every point of
  the axis is under two discs, and shifted along the axis too, under at least one), and the wander is no further than half
  a disc (`meander`). Each puff takes its own share of the tube's opacity (the overlap at the axis is 2 · the disc of its frame
  / the piece of the axis; at the axis it comes out 0.8–1.2 of the law 1 − e^(−τ), `PlumeContinuityTest`). Previously the edge
  was computed from the matter of the place (a Gaussian, 0.1–0.5 of the tube's thickness: an edge at 1.2σ, a disc at 0.6–0.9σ),
  with a step of 1.2σ and wander up to 0.5σ: a thin tube far away came out as beads (the Grad trail at dusk at 1.7 km, VXG).
  Puffs are only near the segment in the frame or near the eye. The budget `CLUB_BUDGET` (3000 places: each waits for the
  frame's plan, ~0.5 µs of CPU; beyond the budget their fills cannot be shown separately) is shared by the trails and the
  trails of fires, and is counted at the start of the frame (`FarFlightView.budget`). Beyond it, the places' step grows
  (`stride`) by as many times as more places were requested, and all the tubes stay puffs, just sparser (⚠ a step wider than the
  puff's disc leaves gaps between puffs in a pale tube). The matter of a segment at the places is the same: each stands for its
  own piece of the axis (`SmokeRaidTest.strideKeepsTheMatter`). Places are enough from the second frame of a raid
  (`placesHoldFromTheFirstFrame`). The shares of puffs and ribbons come only from the real width (`puffShare`, summing to 1) and
  do not depend on the budget: before, the width threshold grew, and in a bench raid of 100 it was 28–41 times larger, and the
  trails went as ribbons up to 8000 px: bright straight stripes across the frame (a RenderDoc snapshot of 25.10,
  `SmokeRaidTest.ribbonsAreOnlyNarrowTubes`). A ribbon is drawn only where the tube is already `PUFFS_FULL`
  (`FarFlightView.ribbonPart`: for a segment with a wide end or a wide middle, the pieces up to the place where the ribbon's
  share falls to zero). The ribbon's share is linear between the vertices, and each vertex takes its own width. The vertices
  are the ends, the widest point (the width on screen w/d is quasi-concave, one greatest value, the golden section), and three
  on each side of it at 0.3/0.9/2 of its distance (`knots`): near it the width changes quadratically, and the cut's place moves
  as √Δw. With a vertex only at it, the share at a quarter of the segment jumped by 0.016 over 0.005 px; with one band across
  the ends it went from 0.39 to 0.01 right at the threshold (review #466, `SmokeRaidTest.ribbonShareIsContinuous`: a step of
  no more than 0.0031). The thickness of the puffs is the full attenuation, as for the ribbon; the color and shadow are two
  streams after the delta-Eddington, as for clouds. Matter at a point closer to the nozzle than the visible torch
  (`TrailModel.emerged`: the path the projectile has flown since the point's birth, against the length of the flame now, not
  age × speed at birth, since a rocket accelerating above a SAM launch cloud would leave a hole) is still the flame: smoke
  laid over fire came out as a dark clump. A trail with a real burnout section (`Trail.reach`, `Article.burnPath`: for the
  Grad 683·1.9/2 ≈ 650 m) lies while the projectile's path is shorter than the section, and after the phase with the flame (at
  game speed, smoke without fire, not physical), no further than the section and at real speed. The matter per meter is the
  real flow over the real path (`Trail.inverseSpeed`: t/section, not 1/the projectile's speed; `FarFlightView.laying`,
  `FarFlightViewTest.gradTrailCoversTheRealBurnPath`). The Grad's ignition is in the tube, and the ribbon runs from the first
  tick of acceleration (`FarLook.ignition`): wider than the tube, it would stick out of it. Points come every `Trail.step` ticks
  at each nozzle (the B-2 has four trails), and the joint between segments is a shared cross-section (no gap on a bend).
  The trail's width is not Briggs's σ_y·σ_z: those are for a plume averaged over minutes of measurement, together with the
  wobble of the whole plume by large eddies, and the instantaneous trail came out too wide.
  But the empty SAM frame for 15 s was also explained by the lack of march points: `FlightTracks.interceptors` lived only on the
  entity, which left the draw before the march regime. Now the distant readings of `FarFlight.INTERCEPTOR` continue the same
  UUID until the break message (`ended`); `SamTrailScreenTest` goes from the packet to the vertices and the raster alpha of the
  real textures at 15/30/60 s. The trail is laid all at once as a puff: the width is the relative dispersion of Richardson–
  Obukhov g·ε·t³/6 (ε by class, `TrailModel.dissipation`), not wider than Briggs, and the rest from Briggs is the bends of the
  axis (`TrailModel.wander`: random Fourier modes over the eddy octaves along the point's path, `TrailPoints.clearance`, not
  into the ground; eddies smaller than the trail and smaller than four point steps are not drawn, or the segments would break
  into a zigzag).

- Eye adaptation is one per frame for all the light of conventional weapons (`Sight.exposure`, `FarView.exposure`:
  sky light plus fire light at the eye: balls, puddles, fires and debris of this frame — `HeatPass` collects them before
  the squares are collected; torches of projectiles — of the previous frame, their places are counted by the collection;
  fire of only the previous frame over-lit the first frame of a flash): the fire body, glare and veil, light on smoke,
  particles and the world, the jet at an entity. With a single night ×1200 (`Sight.adapted`), the glare of a booster
  torch at 8 m flooded the whole frame, while light on the world was already adapting to the fire (night launch from
  behind, 22.10). `Sight.adapted` applies only to nukes, to `Sight`, and to the flash on screen (`ExposureSourceTest`).
  Sky light on smoke puffs (`SmokePuffs`: vertex colour and veil) matches in brightness the light on blocks and
  particles in the open (`FarView.sky`: light map, sky 15, block 0; at night ~0.3), not `FarView.ambient` (0.17): with
  it, white smoke at night was darker than stone lit by the moon. The tint comes from Minecraft's clouds
  (`ClientLevel.getCloudColor`), not from the light map: the map turns blue linearly as the sky darkens, and at sunset
  (13000) it is almost as blue as at night, so the distant trail came out dark blue next to the grey clouds of a shader
  pack (VXC4); clouds turn blue only when the light has gone. Particles and ribbons take their light from the map in
  `fx.vsh` — their sky share is multiplied by `FxSkyFix` (smoke light / map, `FarViewSkyTest`). The vertex colour of a
  puff carries only the brightness of this light (`FarView.skyLight`); the tint is `FxSkyTint` in `fx.fsh`, applied to
  the sky light alone: with the tint in the vertex colour, fire on a puff was multiplied by the lilac moonlight, and dust
  near a ball at night came out pink. Fire light on particles is a share of white (without the sky-light multiplier);
  on puffs it is in shares of the linear sky light (`fx_balls.glsl`, `skyLinear`): the night is already in eye
  adaptation, and a second count made fire on smoke 3–14 times dimmer at night
  (`ShaderSourceTest.fireLightOnSmokeCountsTheNightOnce`). The substance's veil at the eye also takes the frame's fire
  (`FxLights`), as its puffs outside do.
- Blast fragment hits — `fx/FragmentHits` (not to be confused with the nuke's `fx/Fragments`): the first `SHOWN` ×
  `Fx.density` rays of the same seed and axis as the server (`warhead/FragmentModel`, `FragmentTrace`), no farther than
  `RANGE` blocks — a puff of block particles, sparks off solid faster than `SPARK_SPEED`, glass shards; on the tick of
  arrival (queue `BlastEffects.FragmentQueue`). The fragment itself is not drawn: ~500–700 K, below the Draper point.
  Burning pieces with smoky tails (`Explosions.solids`) only if the tank held fuel.
- Block chunks (`fx/Fragments`: nuke debris, surface chunks at a dust wall) are vanilla engine particles on the block
  sheet (`TERRAIN_SHEET`), not the effects layer: an opaque cube with the block's texture takes the world's light, fog
  and shader pack like the block itself, and writes depth; the effects layer is for translucent squares facing the
  camera. The engine queue silently evicts old particles and is cleared on a world change without `remove`, so
  `Fragments` counts live pieces itself (its own list of live ones: a piece the engine has not ticked for over 2 ticks
  is gone). The limit is 1200 at `Particles: All`. When there is no room, a new piece nearer the camera evicts the
  farthest one: the wave travels toward the camera, so near slots come last; otherwise all slots would go to the leaves
  of distant chunks (`wall` frames). The rule is the old scan of all pieces on each new one (and a piece of the same
  tick is evicted by the next one, which is nearer), but done through a heap (`fx/FarthestPool`: built once per tick,
  when the slots ran out; new pieces of that tick join it themselves; `FarthestPoolTest` checks it against the scan):
  a salvo of 8 Shaheds produced thousands of grass scraps in one tick, and the scan cost tens of ms. Sorting once per
  tick without the new pieces of that tick changed the rule (review #425: a new piece 90 m away no longer evicted an
  accepted one 100 m away in the same tick). A new source asks for slots through `Fragments.share`: free ones, but no
  fewer than a quarter of the limit (`FrontLitter`: 8 × 900 scraps for 1200 slots meant almost all were evicted at
  once). On touching the ground a piece stays (vanilla no longer moves a particle after a collision,
  `stoppedByCollision`), its tumbling stops, and after a second it crumbles. On the first tick of flight its box (by the
  largest body dimension) is pulled out of the blocks, or a long plank from a breach at floor level would sink into the
  floor. A piece is a body of its own material (`Fragments.Kind`: debris cube, plank, glass shard, leaf — size and drag
  are one set), torn off at rest: wind carries it (`Fragments.Wind`; for the nuke, `BlastGust`) through quadratic drag
  on its fall speed. The step is exact per tick, with gravity around it (`Fragments.step`: a leaf's settling time is 3
  ticks, an Euler step would diverge, and gravity in one step shifted the fall speed by 15 %). Upward wind is a share of
  the wind along the ground: for a piece torn off the ground — 0.02–0.12 (dust layer, Kuhl); for a piece from a building
  or a crown — up to tan 25° (flow separation at the edge of a blunt body, `Fragments.LIFT_EDGE`: without it pieces from
  a house did not rise, while at an edge the flow tosses them). Gravity is real, 0.0245 blocks per tick² (vanilla
  particles: 0.04). A crown piece is a tuft of foliage with a 0.2–0.35 m branch, not a leaf: a sample is drawn (several
  per block), because a 10 cm leaf seen from 60 blocks is smaller than a pixel, and a sample of leaves had vanished. A
  glass piece takes its texture from a corner of the block sheet: the window is transparent except for the frame and
  glare, and particles do not draw transparent pixels.
- Glow (bloom) of fire, fireballs, flashes and torches — `fx/layer/FxBloom` (presentation; player setting `bloom`: OFF /
  FAST by default / FULL). The layer's pass is the same as without it. After the squares, the same `fx` shader
  (`FxGlowPass`) draws the squares again from the farthest glowing one (`FxQuads.drawGlow`, from the same vertex buffer)
  at the level of the pyramid `FxBloom.glowLevel` (FULL — one octave finer than the halo's first level: at 1080, half
  the frame; at 1600, a quarter; FAST — the first level itself), over the whole frame. (Within the scissor box, dust over
  the ground at the glowing sources cut the halo only inside it, giving a rectangular halo edge; the box was only there
  to skip the pass when nothing glowed in the frame. A full-frame pass for a rocket at night at 2560×1600 costs
  0.12–0.19 ms against 0.04–0.08 with the scissor box.) It starts from the depth of the farthest glowing one plus its
  half-size (`FxQuads.glowStart`: the fireball's frame stands at the front half of the ball, and from the start the
  farthest glowing sleeve to the side, at mid-ball depth, was not sent to the pass and did not cut the halo). Emission
  (R11F_G11F_B10F) and the share of the pixel covered in front of the halo (R8, `bloomCovers`: for glowing squares, by
  opacity or light; for the rest, by opacity) are written with the same blending: smoke in front of fire cuts its
  emission by the same share, without light on smoke (`sixAlpha`; the frame's slots are shared with `sixFrames`,
  `sixPlaces`). What lies behind the world is judged by distance to the world (`SceneDepth`; the pass has no hardware
  depth); the soft edge, veil and fog are the same (`FxBloomTest.glowIsCutLikeTheColour`). A second target inside the
  layer (MRT, first variant) cost +0.6–0.85 ms for a rocket at night at 2560×1600: every smoke fragment across the full
  frame blended two more targets. What glows is the vertex's `Emit` byte (its own format element under
  `VertexFormatElement.findNextId()`, as in Iris; `FxQuads.emission`; layout — `FxBloom.SKIP`, `EMITTER`, `SPOT`): shown
  light × clipped overexposure. For nukes, flashes and torches it is the brightness against white up to
  `Sight.onScreen`; for the fire frame it is the model ball's brightness against the frame (`FarBlasts`:
  b·exposure / fireLightAt^2.2; the frame is shown at its own exposure, and by the shown value the glow was 10 times
  weaker), not brighter than the white point of the film curve, 7.24. Halos in the eye, smoke and models — 0. A square
  only three pixels across the pass (a spark, a distant torch, a distant core) hit or missed pixel centres, so the glow
  vanished and flickered (review #442). Such a square goes into the pass as spots (`FxBloom.spots`: halo `far/glow` by
  the formula, half-size 3 pixels, along its length no more than half-size apart, up to 8; energy the same within 1 % at
  any shift, `FxBloomTest.spotKeepsItsEnergyWhereverItFalls`), with its energy — the average light of the place on the
  sheet (`FxAtlas.means`: sums over 4×4 cells accumulated along the row, by the same law as `bloomLight`) × colour ×
  overexposure × solid angle. The square itself does not go into the pass (`SKIP`); the spot is only in the pass (its
  vertices leave the frame in another pass). The spot's light and covered share are stored in the 16 bits of `UV2` on a
  logarithmic scale (`FxBloom.magnitude`, step 0.07 %; a spot needs no light map), and its tint is in the colour: in the
  colour byte a source 0.09 pixels across the pass was zeroed, and at 0.10 it got 187 % (review #442; `FxQuadsGlowTest`
  goes through the real vertex writing of `FxQuads`, and fails without spots).
  The halo is fire light scattered by the air around it (glare and veil in the eye have their own squares,
  `FarSprites.glare`), and in a pixel it passes through the share not covered by the layer in front of it
  (`bloomThrough`). On the glowing one itself there is none: with the model's overexposure it lit the folds and soot of
  the fire into “soap”. Smoke nearer than the farthest glowing one cuts the halo with its opacity: with one share of the
  glowing one, the halo of the open parts of the ball laid a milky veil over the sleeves of smoke in front of it
  (review #442, `FxBloomTest.smokeInFrontAbsorbsTheHalo`, `FxQuadsGlowTest.glowPassTakesWhatStandsWithinTheFire`).
  ⚠ There is no depth of the halo in a pixel: smoke between fires (behind the near one, in front of the far one) also
  cuts the near one's halo.
  The pyramid is the Jimenez 2014 downsample (13 samples), with the levels gathered in one pass by a B-spline (4 samples
  per level, `fx_bloom_gather`) instead of raising additive tents (5 passes fewer). Levels 2–8 at a height of 1080,
  higher — one octave deeper (`FxBloom.first`). Equal weights, a 1/r² falloff (the eye's scattering function, CIE), and
  share 0.12 (Complementary) — one normalization: the gather divides by the number of levels, the output multiplies by
  the share (a second division gave 0.017, `FxBloomTest.chainGivesTheShareOfTheEnergy`). A tent filter up to half the
  frame (a level stretched fourfold linearly is non-round at the source by ~10 %), the curve 1 − e^(−x), and a “screen”
  per frame with noise at a half step (otherwise the tail in 8 bits would form rings). 2×2 downscaled copies
  (`glGenerateMipmap`) instead of the downsample do not work: they tie the deep levels to their own grid, so for a
  moving source the far halo jumps several-fold (measured on the reference). Level samples are taken by
  `gl_FragCoord`: pixel k of a level is point 2^k of the frame; taking the midpoint by texture size with an odd side
  would stretch wide levels by ~20 %. The pyramid's passes use one `apply` per series and their own full-frame triangle
  (their own vertex array; after it — `BufferUploader.invalidate()`, or it would not rebind its own). The cost is a chain
  of small dependent passes and a full-frame output, not pixels. Timestamps (`GpuTimer`) between passes bleed into each
  other: trust the parts only by `glFinish` (the drain frame of `LayerTimes`), and the whole only by A/B of the whole
  layer. The pyramid on the CPU (`FxBloom.halo`) uses the same samples: the test holds energy, roundness, 1/r² and
  stability to shift in both settings. The nuclear ball (the pall's volume) does not glow here. Time — the layer's line
  (`bloom`).
- Nuclear dust pall (`fx/layer/PallPass`) — a volume in the same stage of the layer, before the squares: rays per frame
  are a factor of 2–4 coarser per axis (`fx_pall`); accumulation over frames with transfer by world depth
  (`fx_pall_resolve`); per-frame compositing with blend `ONE, SRC_ALPHA` and a weight by the similarity of distance to
  the world (`fx_pall_composite`). The layer's squares then take their share of the pall in front of them (`fx.vsh`, six
  steps over the field without noise, `pall.glsl` — shared by all): otherwise a mushroom and smoke behind thick dust
  would be drawn over it. The pall's light in front of a square is the share of the pall's light in the frame by its
  share of attenuation (`fx.fsh`, `Sampler6`), not the average light: with the average, puffs of a dust wall kilometres
  away, behind thick pall, came out as light specks on dark pall. The frame's pall alpha is attenuation only; the
  pall's shadow on the world is in `fx_pall_composite` (over the noise-free field at each pixel). With shadow in the
  alpha, the pall's light in front of a square would be divided by the wrong share, and particles in front of shaded
  ground would come out darker. Also there — the mushroom's shadow by day: before the rays, the pass `fx_mush_shadow`
  computes a shadow map (a 128² tile per mushroom in the plane across the sun, RGBA16F: the share of sunlight that the
  mushroom, without noise, passes on a straight line to the sun to three heights — the ground under the epicentre, the
  middle between it and the top of the world, and the top; the tile is the projection of the mushroom's box with a
  margin of 2 texels, `MushroomField.shadowRect`). The output per frame takes one sample per mushroom from it and
  multiplies the sun's beam by it — before the pall, and through it further, by its laws. The share is the direct beam
  e^(−τ) plus the forward-scattered part (two streams in the column along the sun; albedo and asymmetry are each part's
  own, the water column's are droplets'), taken at the share in which the cloud is seen from the ground (`mushSeen`,
  `MushroomField.seen`). A cloud with half-width w (the radius of the circle of its shadow's area across the sun,
  `mushAcross`: the cap and skirt are flat — at a low sun a narrow strip; the stem is seen from the side over its whole
  height) at distance d straight toward the sun covers a share v = w²/(w² + d²) of the hemisphere, and on horizontal
  ground v·(μ + (1 − μ)v/2) of the layer's flux (for an infinite layer, (1 + μ)/2). Without this, a thin stem of shadow
  gave almost nothing: the two streams treat the layer as infinite sideways, and the forward-scattered light from it
  would come from the whole sky. Not steps along the sun at each pixel: the cap is a layer hundreds of metres thick, a
  kilometre above the ground, and sparse steps would sometimes hit it and sometimes miss, giving bands across the
  shadow. A mushroom whose shadow is in the frame while it itself is not goes into the frame (`MushroomField.inView`: a
  box extended along the sun down to the bottom of the world). Below 0.05 (sine) the sun casts the shadow as from this
  height (`MUSH_SHADOW_LOW`): from a setting sun the shadow reaches hundreds of kilometres. Sky covered by the cap is not
  reduced — less than a tenth of its light reaches the ground. Beyond 100 optical depths the direct beam is not computed
  (`MUSH_SHADOW_DARK`): through them, both the water dust and the whitest cap (0.1 kt at the end of life, ω' 0.9975: at
  80 optical depths, about 1 %) less than a percent passes — `MushroomGlslTest`. The sun above the pall (the ray step of
  `fx_pall`: `pallSunAt`) — through the same map, with the point's height not below the map's middle (`mushShadowFrom`:
  the map's layers are ground, middle and top; for dust in the air, the ground's shadow would carry the material below
  the point along its straight line — a false band at the stem's base at a low sun; ⚠ the stem between the point and
  the middle gives the shadow no blanket). The dust blanket under the cap is in the cap's shadow, as is the stem, which
  the cap shades from the sun (`mushSunTau`). Without this, the grey stem in shadow stood on the blanket in full sun with
  a sharp border (host screenshots 3.6.0, `ShaderSourceTest.pallUnderTheCapIsInItsShadow`). By day, per step — up to 3
  samples of the table and one of the map per mushroom whose tile covers the point. The mushroom's shadow on the layer's
  particles does not yet exist. Also there — the light of the fireballs on the world (`ballLight`): up to 4 balls from
  all detonations, not only the visible ones (`MushroomField.balls`: the light falls even when the ball is behind one's
  back). Each is a Lambertian ball of the cap's radius, with the light of a black body at its visible temperature
  (`MushroomModel.light`: L·R²/max(d², R²), white wall, with eye adaptation), fading in the air along the way. Through
  the pall above the point: the direct beam e^(−τ/μ) and the part it scatters downward (`pallDiffuse`), by the share of
  sky that the face sees, (1 + n.y)/2. The normal comes from the depth of neighbouring pixels (on each axis, the
  neighbour nearer in place: at an object's edge the other one is background; at the screen edge, the inner one, or the
  screen border would become a pixel without light), toward the eye. There are no shadows of terrain or houses: behind a
  hill the ball shines the same; only the slope turned away from it is dark. Blending can only attenuate, while light
  makes the world brighter, so while the ball shines, `PallPass` copies the frame before output (`glCopyTexSubImage2D`
  into RGBA8, `Sampler9`), and the output writes the whole pixel (alpha 0): the pall and the world behind it in the
  balls' light. A block's colour is the pixel divided by the frame's open-sky light (the Minecraft light map pixel
  “block 0, sky 15”, `LightTexture.lightPixels` — AT) and by the shading of its own face (`ClientLevel.getShade` along the
  axis nearest the normal, — `PallFaceShade`: in Minecraft, east and west are 0.6 times darker, and the ball's light on
  a face follows the cosine to it, or a face toward the ball would take a third of the light), and not whiter than white
  (`MushroomField.relit`): with the player's gamma the night is brighter, and we divide by that too. In a cave or a house
  Minecraft's light is weaker, and so is the ball's light there. For a block under a torch the colour comes out white
  (the ball's light there is slightly stronger); the corner shading of smooth lighting stays in the colour. Brighter than
  white is clipped to white (`CROSS`, as the substance's light in `fx_pall`), and not brighter than 1 with the same hue.
  The ball shines on the world while the light on the ground beneath it is at least 0.005 of the open-sky light
  (`MushroomField.BALL_SEEN`; at 15 kt at night ~12 s, by day ~9 s); otherwise there is neither a frame copy nor the
  count. 15 kt at night at 3 km (scenario `mushroom` with `INCOMING_MUSHROOM=glow`): the first seconds the light is
  white, by 5–7 s yellow and orange, and by 9 s it goes out. This is the eye adapted to night (`Sight.adapted`, ×1200,
  one for the whole nuke): on a face toward the ball at 3 km, for 3 s — ~4 noons; at 7 s — 2 %. By day, the first 3–5 s
  the world kilometres around is also brighter than noon. The GPU cost (RTX 4060, night, MEDIUM) is no more than 0.03 ms
  on top of the pall passes. Under a shader pack the pixel is the pack's own light, not the Minecraft light map, and the
  block's colour is approximate: with Complementary Reimagined (with DH) the order and hue are the same, but in the 3–5 s
  overexposure the pattern of foliage and grass vanishes — the pack desaturates at night, and the overexposure leaves
  only the hue of the block's colour. Besides the direct beam, the ball's light scattered by the air lands on a face
  (the light that reaches the impulse both directly and scattered together, `ThermalModel.transmittance`, without the
  direct e^(−σd); from the sky around the ball onto a face — (1 + n·v)/2, an estimate), and the light reflected by the
  ground it lights (the ground's albedo under the pall, `pallOver`: without a pall — always `PALL_LAND`, and over water;
  onto a face — (1 − n.y)/2; nearer than ~0.05 V the Glasstone formula is below the direct, so there is no scattered
  part): `mushBallFace` in `mushroom.glsl` ↔ `MushroomField.ballFace` (`MushroomGlslTest`). Without them, a face turned
  away from the ball at 15 kt, at 4 km in the second maximum, stayed at one daylight, while a face toward it was ~250
  times brighter than noon (complaint of 18.10: “second sun”, sharp shadows across the whole map). With them: 7 noons
  away, 45 at the side, 70 upward (`ballLightsWhatFacesAway`) — the whole world white, and after 2 s a face toward the
  ball is 5–6 times brighter than a side one. The brightness and size of the ball are right: a disc of ~6° at an
  altitude of ~9°, in the second maximum ~250 noons, as by the light power of Glasstone and Dolan (P_max = 4·W^0.56 kt/s).
  There are no shadows from the ball at all (the penumbra of a 6° disc is 0.1 blocks per block of height): the light
  falls there too, behind a hill and a house. Under a shader pack the block's colour is the pack's pixel divided by the
  open-sky light: in the pack's sun shadow it is darker, and the ball's light there is weaker by the same factor, so the
  pack's sun shadows under the ball's light keep the same sharpness (without access to the pack's buffers this cannot be
  fixed).
  Where there is no world behind the pixel (beyond the draw distance without DH, beyond the edge of DH's LOD, and between
  them), the pall's ray ends on the terrain below it (`client/nuclear/PallGround`, `pallFloor` in `pall.glsl`);
  otherwise the pall under the ground (for lowlands) hung below the horizon as brown blotches. Not on the plane of the
  ground under the epicentre: a camera in a valley below it looks below the horizon, and the ray goes under the plane
  right at the eye — emptiness without a pall, and a flat cut at the horizon (the dark line on the trailer frames). The
  height map is 128² cells around the epicentre for the pall at the source (`PallField.sourceExtent`: the table, the
  haze trail, the base wave — without drift), the head of the dust wall (`BlastWall.reach`: its fading edge — up to
  0.3 psi, twice as far as the pall's dust; beyond the map, the head would stand on the terrain of its edge), and the
  camera with a margin of 2 km (`PallGround.AROUND_EYE_M`), no wider than the whole box of the pall
  (`PallField.extent`); it grows — the map becomes larger, the known part stays. It does not grow to the whole drift: in
  an hour the wind carries dust tens of kilometres away, and a map covering the whole box would have coarsened even near
  the camera, where the layer's floor and the ray's end are seen up close (15 kt, wind 8 m/s: a cell at one hour ~0.5 km,
  at one and a half 0.6–0.7), whereas with the bounded map the camera at the epicentre is 0.17–0.24 km from the end of the
  pall. Dust carried beyond the map lies on the terrain of its edge (the layer's floor, the ray's end); the substance is
  at the place it was lifted from, and that is at the source, on the map. Each tick samples 128 cells in a circle outward
  from the epicentre (4 columns each: client chunks, terrain map tiles — with DH and far away); once seen, a value stays.
  An unknown height is taken from the known around it (pull-push); the ground under the epicentre (which the server
  knows) counts with the weight of one cell; where nothing is known, its height. The second channel is the water share
  (`FarTerrain.water`); unknown is taken from the nearest known (the epicentre — by `NukeWater.under`; a flag, not a
  field: averaged, it would become “half-water”, which exists nowhere). Into video memory (RG16F, 64 KB per slot) only new
  fills go. A ray into emptiness that passed the whole drawn world without hitting anything: terrain is searched beyond
  the draw distance (`floorFrom`), with 24 steps growing with distance and a secant between the step above the terrain
  and below it. Where the coarse map at the start is above the ray, it is lowered beneath the ray (down to four cells —
  as is, again). The cost is only at pixels without world and within the map's heights (sky above the terrain is one
  box check). Flame on surfaces is also there (`fx_pall_composite`, 16 steps).
  Over water, water spray takes the place of dust (spray in the dust layer; the base wave, from the slot's numbers
  `PallWater0/1`: radius, top, attenuation, whether there is water under the pall; `pallField` returns `PallAt` with three
  substances): white (`MIST` = `CloudPuffs.SPRAY`, checked by `PallGlslTest`). Droplets of tens of microns barely absorb
  and scatter forward (g ≈ 0.85). The light in it is the same as for the whole pall (below): the table's attenuation is
  already the delta-Eddington σ' (the forward peak goes into unscattered light), and both the view ray (volume step,
  `fx.vsh`) and the light toward the point use it, while droplets scatter with albedo ω' ≈ 0.996 and residual phase
  g' ≈ 0.46 (`PALL_MIST_W`, `PALL_MIST_G`). Not a “transport optical depth” for one light (0.28 of the full) with full
  scattering at the same residual phase: that counted the forward peak twice, and thin water spray glowed up to 3.6 times
  brighter — a white wall across half the frame for a minute (frames `nuke-water`, step 6). Fires under dust (`pallBurn`)
  do not glow over water (× (1 − the water share where it was lifted from)). The water map is read in `pallField` only if
  there is water under the pall (`W.w`); for haze, only where haze or fires are: over land, not one extra sample.
  Light of the pall (`client/nuclear/PallLight`; in the shader `pallDiffuse`, `pallK` — checked by `PallGlslTest`, the
  physics by `PallLightTest`): the pall above the point and below it is a flat layer by the noise-free table (optical
  depth τ above the point, τc from the floor, `PallAt.slab`), in which two Eddington streams after delta-Eddington. The
  solution is in decaying exponentials e^(−kτ) and e^(−k(τc − τ)), k² = 3(1 − ω')(1 − ω'g') (without overflow in a thick
  layer; k ≥ 10⁻³ — and for fog without absorption). Sources: the sun and the ball with its cap — beams from above (the
  strength is what a beam gives on a white wall, with the cosine to vertical at least 0.12: the layer is not infinite, so
  a shallow light enters from the side too); the sky from above; fires under dust — light from below, from the ground
  under the layer (land 0.2, water 0.06 — by the share of water under the substance). Toward the eye the point scatters
  ω'·(beams with the Henyey–Greenstein phase g' + I0 + g'·μ·I1). The substance: ω' per channel from the linear colour of
  the thick layer (inverting the two-stream reflectance R∞: `DUST`, `SMOKE`, `HAZE`, `MIST`, `SOOT` → `PALL_*_W`; dust
  0.87–0.73, smoke ≈ 0.5). Smoke has k > 1, and for a beam with kμ = 1 the denominator of the particular solution,
  k²μ² − 1, vanishes: within ±0.05 the beam is shifted entirely to its edge, both in the solution and in transmission
  (`pallBeamMu`). A clamped denominator at the same μ was not a solution: near noon, smoke's light in the layer came out
  several times wrong. g' = g/(1 + g) (dust, smoke and haze g = 0.6, droplets 0.85, soot 0.5). A thick pall takes the
  colours of the dust wall and spray; a thin one glows over its own thickness (the former “three quarters of light
  without thickness”, a limit for multiply-scattered light, applied to thick layers too, and thin fog came out as a white
  wall). In dense dust the light fades as e^(−kτ): brown dusk below, not black and not a flat colour.
  The layer's floor is the terrain under the point (`pallField`: `floorUp` = `pallRelief`, map `PallGround`; the same for
  the ray, the layer's squares and the shadow on the world). In a valley the pall above it is also a layer, with fires
  and ground at the bottom. Above a hill (terrain higher than the ground under the epicentre), the layer rises from its
  top, as the wind lifts dust up its slope; otherwise a hill a kilometre away stood bare above the pall, and behind it
  dust hung as a wall. With the floor at the epicentre's level, every point below it (a viewer in a valley, scenario
  `pall-low`) was “at the bottom” — in full fire light, with no height difference.
  Light is computed linearly (transport is linear in radiance), while the Minecraft frame holds screen values (textures,
  light map, sky — sRGB without conversion). On output, `fx_pall` converts the pall's colour as a substance — light
  divided by opacity — into screen space (`pallDisplay`, γ 2.2) and multiplies by opacity; the effects layer takes this
  colour as rgb / (1 − a) (`fx.fsh`); the shadow on the world in `fx_pall_composite` uses power 1/γ. Screen colours (wall,
  soot, the whirlwind's flame) are converted to linear (`pallLinear`, `*_LINEAR`): a pure power, not the piecewise sRGB
  curve, since the conversion of a product is the product of conversions, and the flux from a thick layer of colour R in
  light L on screen is exactly R·L (toward the eye at a grazing angle a thick layer is brighter, as a real cloud is). In
  screen values the midtones came out darker (0.3 linear — 0.58 on screen): thin fog a dirty grey, dusk in dense dust
  black.
  The frame's light (`NukePall.light`): a white horizontal wall at noon — 1, like the top of a white block by day; the
  sky as Minecraft draws it, in linear × 0.5 (`PallLight.SKY`: at noon about a fifth of the daylight); the sun — the rest
  up to white (the blue of the sky is taken from its beam). The light of the ball and of fires on dust follows the same
  laws for the pall, the wall's head, Wilson clouds and dust near the camera (the slot's numbers — `CloudPuffs.pallLight`;
  dust near the camera — `NukePall.dustColor`). The ball is the same source as the mushroom's body
  (`MushroomModel.light`): a Lambertian ball of the cap's radius with the brightness of a black body at its visible
  temperature, L·R²/d² × `PallExposure`, directly in the frame's linear units (`pallBallLight` ↔ `CloudPuffs.ball`), as a
  beam from the cap's centre (`pallBall`: the epicentre, the cap's drift with the wind `MushroomField.driftAlong`, height).
  The scattered light of the ball enters the wall's head through the near side it lights: its own depth `capDeep` =
  min(optical depth to the cap, optical depth above the point + the depth in front of the ball that its beam still passes
  to the top above the point: max(to the cap − above the point / μ, 0)) − depth; the layer is slab + `capDeep`. A ball low
  and behind the head: with one optical depth above the point, the outer side would glow orange by day with light that
  does not reach there, and with the sun's depth at night, where its light is thousands of times brighter than the sky,
  no shadows would remain in the head. The light of the ball and of fires, in choosing the main light (`keyDir`), is in
  the same units: linear light on a white wall.
  Fires under dust — light from below with strength `pallBurn` × `PallGlowOnSmoke` / `DUST` — on screen on top of the sky
  light (`PallBase` = `FarView.ambient`), into linear by the difference of conversions (`pallLift`: (base + x)^γ − base^γ):
  thick dust in it, on screen, takes the colours of the glow. Dust near the camera comes out on screen as a pall
  (`PallLight.screen`: overexposure to white `CROSS`, γ, a shoulder by the largest channel). Likewise the glow of fires
  under haze (`PallFireLight`), of the storm and of the surroundings. The fire's light (fires under dust, haze, storm,
  surroundings, whirlwind) is not brighter than the flame itself (`pallFlame` = min(`pallLift`, linear colour of the
  flame), `PallLight.flame`): a scattering substance is not brighter than its source, per channel. All fires at a point
  together likewise (`fx_pall`: `fire` under the limit `flame`, of the brightest flame in the frame): each within its own
  limit, while the sum over the storm's fires came out to 1.3 flame in blue — whiter than fire. At night over the fire
  zone the glow on screen is already the colour of fire, and converting through the dust's colour gave light three times
  brighter than the fire: dust at the floor came out brighter than 1 on screen, and in the shadow of a puff too; the
  shoulder reduced the frame to a flat orange. There the pall is dust in flame light: at the side, not brighter than the
  flame and not less than 0.6 of it in red (`PallGlslTest`).
  Sky light on dust at night — at least 0.17 on screen (`FarView.ambient`, `NukePall.nightSky`). Without this, at night
  the pall behind the front was a black curtain over the lit wall and city: it was lit only by the night sky (four times
  darker) and by fires, which take a minute to flare up. Local lights — the glow of fires under haze (`PallFireLight`) and
  of the storm — are a flux from below: the point scatters it as ω'·(1/2 + 3/4·g'·μ) (downward, toward the fire, brighter),
  and in the shadow of puffs beneath it, as e^(−kτ); the surroundings' glow (`fireAround`) fades along the way as e^(−kτ)
  (half as from a bright half-space). In a thick pall the light is seen from a depth of one mean free path (less than a
  large puff), and at uniform light the colour is even: the structure comes from differences in light and from puffs,
  which the eye tells apart.
  Puffs mix with the layer (`pallMixed` by `PallAt.mixed`), and the layer's top is bumpy (`topBumps` → `pallField`, one
  noise sample per step; the bumps are the top, not holes, and the light in a dip is as at the top). The laws are in
  `nuclear.md`. Noise is a cascade (`coarseAt`, `fineAt`): large puffs by tile (`VolumeNoise.shape`: smoothly from gaps
  to twice as dense as the average — a “there / not there” step gave one density and one shadow); within them, small ones
  by tile four times finer (`pallDetail`, 0.3–1.7, averaging 1: density and visibility of the field are the same,
  `PallNoiseTest`), as long as the ray distinguishes them (pixel width or a third of a step no more than 1/32 of a tile;
  beyond that — their average, without a sample). The field is one at any eye position: from the eye more detail is
  visible, not other puffs. The puffs' shadow is the deviation of optical depth toward the main light from the average,
  summed over the cascade's scales: large ones by two samples at 0.06 and 0.22 tile, small ones at 0.015 and 0.055 (its
  quarters), × the density of the large puff (`clumpShade`). For each light: how far it is from the main one's side. It
  shifts the point deeper into the layer (light from above) or farther from the floor (fires), and cuts beams as
  e^(−τ). A shadow of the small ones only, while they are visible, lost the large masses: in dense dust the large ones
  give a difference of units of optical depth, while small ones (their optical depth ~0.3) give a barely visible ripple.
  Above 0.6 — a shoulder, as in film (`shoulder`), on the brightest channel, the others at the same share: the frame
  clips everything above 1 (at night puffs merged into a flat orange), while a per-channel shoulder changed the hue
  (white water spray in the ball's glow came out pink). `ShaderInstance` binds all its textures as two-dimensional, so
  the 3D noise is not declared in the JSON: its own texture unit (7), and the binding and sampler number are set by hand
  after `apply()`. An undeclared `sampler3D` would have stayed on unit 0 together with `sampler2D` — a draw failure.
  Samplers of different types cannot share one unit. Haze holds until morning, but is not visible in the frame all the
  time: only detonations that have something in the table and whose box is in the view pyramid go into the frame
  (`NukePall.frame`; without a far plane, the pall is visible beyond 4 draw distances), otherwise null, and the layer
  computes neither pall passes nor `SceneDepth` (if there are no squares either). The frame's cost is bounded by
  construction: rays — the frame's pixels / factor² (LOW — 1/16 and 20 steps; MEDIUM — 1/4 and 28; HIGH — 1/4 and 48);
  per step — no more than two detonations, each with up to 11 noise samples (8 where small puffs are finer than the ray
  distinguishes; one of them is the bumps of the dust top), 2 table lookups and 1 terrain-map lookup (the layer's floor);
  the light — 3 two-stream solutions per step (6 where fires are under dust; 7 exponentials each, without samples); if
  there is water under the pall, one more table sample (the spray row: the slot has three rows — dust, spray, haze,
  `PallField.ROWS`) and one water-map sample (and one at the haze source, where there is haze or fires; the base wave
  has no samples, it comes from the slot's numbers); at a pixel without world, up to 26 more terrain-map samples per
  detonation. Accumulation and output per frame take 9 and 4 samples per pixel. By day the output also has a shadow: 2
  table lookups per detonation, 1–2 water-map lookups, and one two-stream solution (direct sun, and the light scattered by
  the pall onto the ground, versus the sky without it). Per mushroom: 3 samples of the mushroom table, and in its shadow
  tile 4, plus 1 sample of the shadow map. While the ball shines on the world: a frame copy, per pixel 4 depth samples and
  1 frame-copy sample; for the ball, a two-stream solution; and at night the pall above the point, as for the shadow: 2
  tables per detonation, 1–2 water-map lookups. The shadow map is computed once per frame: 128² straight lines per
  mushroom, up to 129 steps (empty space — a jump to the substance), per step the mushroom field's average without noise,
  since the shadow on the ground is smooth (the cloud's edge is hundreds of metres); per straight line, three two-stream
  solutions. In the cloud (llvmpipe, scenario `mushroom` with `INCOMING_MUSHROOM=shadow`) the 128² map is +2–8 % to the
  pall passes (run-to-run spread of the same order); 256² was +18 %.
  Detail also keeps the budget — 2 ms on the GPU (`PallPass.Detail`): if the average over half a second is longer, it
  drops at once to a step of `PallPass.LADDER` that fits the cost; the headroom to go up a step is granted if, with it,
  the time would be no more than 0.7 of the budget for two windows in a row and 3 s after the last change. It is never
  above the setting and never below LOW (steps between them are a factor of 2–4, 48–20 steps).
  Time — `glQueryCounter(GL_TIMESTAMP)` marks before and after the passes, in a ring of 4 pairs, read when ready (without
  waiting). Not `GL_TIME_ELAPSED`: that query is single in OpenGL for everyone, and Minecraft holds it for the whole frame
  while F3's GPU load is on screen (`TimerQuery`) — a nested one would not start. Pairs taken before the pall had gone
  from the frame (`PallPass.reset`), or before a change of frame size, are not counted (`Detail.restart`: a new
  generation of the step). In the client scenario, and while Flashback exports video (`FlashbackReplay.exporting` —
  `Flashback.EXPORT_JOB`), the step is pinned to the setting (`Detail.pin`): frames there are not real-time, a slow frame
  costs nothing, while the trailer and the guide's frames with a coarsened pall would come out worse. The `SCENARIO pall`
  line shows the step, “pinned” and the GPU time; when pinned — an INFO line “Nuclear dust pall: … detail pinned at the
  setting …”, a step change in game — DEBUG “Nuclear dust pall: … ms on the GPU”.
  Invalid values do not reach the frame. A distance to the world that is NaN, infinite or behind the eye (another mod's
  LOD depth) is treated as sky (`pallScene`; `fx_depth` takes the DH distance only if it is in front of the eye and
  finite). An invalid ray result (`pallValid`: NaN, light above 6·10⁴, transmittance outside 0..1) gets alpha −1; the
  accumulation takes this pixel's past, and without it — a pixel with no pall. Nothing invalid enters the history, the
  frame (`fx_pall_composite`), or the layer's squares (`fx.fsh`): NaN in the blend `ONE, SRC_ALPHA` is a black world pixel.

- Pall dust — the substance of outdoor air: attenuation of the field at the ray (`fx_pall`: `pallStep`), at the pall in
  front of the quads and probes (`pall_front.glsl`: `pallThrough`) and the pall's shadow on the world
  (`fx_pall_composite`, place — the air block in front of the surface) — × the share of outdoor air at the point
  (`pall.glsl`: `pallOpen`): Minecraft's sky light / 15 in the window of 96×64×96 blocks around the eye
  (`fx/layer/OpenAir`, R8 3D on the block of texture 13, by sections modulo the size: new sections of the window and 4
  per frame in a circle; where there is no chunk — 1; beyond the window — open above the coarse terrain map of the slot
  (the pall beyond the far mouth of an adit is visible from its depth); below it — the share of the block where the ray
  from the eye left the window: all places of the pall and its shadow lie on rays from the eye through air, so a long
  adit and a big cave beyond the window stay closed; in a dimension without sky there is no window; in shaders —
  `pallOpenHere`, the first slot's terrain map, whatever it has). For the eye the same goes for sound, for the break of
  a ray and for dust at the camera (`NukePall.tick`: σ × sky light at the eye / 15). Without this the field is a
  function of height above the coarse terrain map, and in an adit under the pall a ray went several blocks through dense
  dust — murk underground (client scenario `pall-mine`: an adit, a shaft to the sky). Sky light is a measure of the link
  with the sky of Minecraft itself (in a shaft to the sky — 15 down to the bottom, into an adit — 1 less per block); the
  share from it is an estimate, air exchange with the street is not counted. The new path of pall matter also goes
  through `pallOpen`
  (`pallOpenHere`, `ShaderSourceTest.pallKnowsWhereTheOpenAirIs`).
- The pall frame is smaller than the full one (two to four times; under a shader pack the GPU budget more often holds
  1/4): at an object's edge against the pall it has no ray of its own. The output (`fx_pall_composite`: `upsample`) is
  weighted by the similarity of distance to the world (`pallAlike`); rays are in their own lattice (`pallLattice`: the
  ray of pixel t goes through the centre of the full-frame pixel floor((t + 0.5)·factor)); "the same place" is the
  distance the pixel's surface would have (`pallSlope`, `pallAhead`: for a plane, 1/distance on screen is linear;
  without slope, flat ground far away under a grazing ray came out as an edge at every row of rays); an object already
  narrower than the ray step (rods, a fence, foliage, a block's edge far away) has no ray of its own among the four near
  rays of its place — then the nearest ray by distance out of 4×4, and the rest by the field without noise (transmission
  × e^(∓thickness between places); for a nearly opaque ray — transmission to the pixel by the field: RGBA16F behind a
  thickness of ~17 stores zero; light — the same share onto opacity). Without this the lattice at 5 blocks sank into the
  pall behind it, and along block edges a rim of its colour ran (scenario `pall-mine`, `INCOMING_PALL=LOW`, frames
  `mine_rim_*`). Accumulation (`fx_pall_resolve`) takes the past in the same lattice (at the centre of a pall-frame
  pixel — offset by half a full-frame pixel: a blur with neighbours every frame and when the gaze is still) and clamps
  it within the neighbours of only the same distance to the world: with neighbours beyond an object's edge, the
  background's pall dug into the edge when the gaze moved.
- The blast-wave dust wall and the Wilson cloud are in the same pall passes (`client/nuclear/BlastWall`, the laws —
  `nuclear.md`), not as square puffs: puffs on a ring tens of kilometres across either punched holes in the wall or cost
  thousands of quads, and a rim of flat squares does not curl. The slot has a head (`PallWall0/1`, mat4: the dust edge,
  the wedge's length and its top, extinction, the path of dust with the wind behind the front, spray above water, the
  noise tile, density at the edge and at the end of the wedge) and the Wilson shell (`PallWilson0/1`). The head is read
  in `slot` (`fx_pall`) next to the pall: the same light and the same matter (dust; over fires with smoke, spray over
  water), but its own noise (`wallNoise`: in the coordinates of the dust itself, `pallWallOrigin` — puffs stand where
  the dust was torn off; the puff shape is the same `pallShape` as the pall's, denser in thick matter) and its own
  thicknesses toward the light along the middle head: the head is not a flat plane but a low long wedge, and light comes
  in from the side too. Scattered light — `wallChord` up to the top of the wedge at that place, sideways up to the exit
  from the wedge at its height, down to the terrain; beams toward the sun and the ball — `wallChordNear` up to the top
  of the head (no farther than 1.5 wedges and the top), cells denser at a point by the square's number: four even steps
  over the whole length stepped over the wedge at its edge, and deep in the head there was even light. Each sample of
  these thicknesses (and of the puffs' shadow) has its own extinction (`wallMove`: the frame `pallWallInside`, from the
  edge toward the end of the wedge `pallWallDensity`), as in `BlastWall.sigma`: from the edge to the end of the wedge it
  changes many times over. The Java copy of the light is `NukePall.dustLight` (its laws — `BlastWallTest`);
  `PallGlslTest` checks the head at a point and the ray samples against `BlastWall.sigma` (it assembles them with the
  same text as the shader), and the handoff to the pall beyond the wedge is one constant `PALL_WALL_HANDOFF_TO` =
  `BlastWall.HANDOFF_TO` in the frame, the ring of segments and the ray to the light. The ray at the head is denser: its
  segments on the ray (`pallWallSpans`: a ring from the dust edge to 1.5 wedge lengths, by height — the terrain map with
  the head) run on their own grid with an offset per frame, a step of an eighth of the head's height (a head thicker
  than the step, the ray either went straight through or passed by, and accumulation gave ripple), over all segments —
  no more than `steps` steps beyond the usual ones; inside a segment the step boundary is the nearer of its grid and the
  usual one (at the eye the usual are denser: a wall passing through the viewer otherwise lost the near dust). What the
  ray distinguishes (`cone`: small puffs, storm columns) — by the grid step there, not by a step cut by the entry into
  the segment (that one is random from frame to frame, and small puffs would flicker). The Wilson cloud is a thin shell:
  its two passes by the ray are events, like tornadoes (light and transmission in advance, into the ray when the step
  reaches the pass). The quads of the layer take in front of them both the middle head and the shell (`fx.vsh`:
  `pallWallThrough` — `pallWallTau`, four steps per segment, `pallWilsonThrough`; each segment and pass is in haze at
  its place). The slot's frame grows to include the head and the shell (`NukePall.slot`), and the blast is in the frame
  even when the pall's table has nothing yet. The cost in the head: up to 5 noise samples per step (the tile shift,
  puffs and small puffs in the dust's coordinates; the puffs' shadow — two puff samples), small ones only at the eye;
  and 24 middle-head evaluations (21 — thicknesses toward the light and the puffs' shadow, 3 — under the puffs) —
  arithmetic without samples: two powers and an exponent each (the top of the wedge u^(5/6), raised by `pallLifted`),
  and one more power per step (exit sideways) and the top of the wedge above the point — the head's ALU is about 10–20 %
  more than the former wall envelope, with the same number of samples. The pall with the head and the Wilson cloud far
  away fades in the air haze like a mushroom (`pallAir`, the frame's air `PallAir` — `Sight.Air` in the model's blocks):
  the share of their opacity that the haze ate up to the place is in `lost` (`passMatter` in `fx_pall`; the same in the
  layer's quads, `fx.vsh`): in rain the wall at 8 km does not stand as a dark ring on the horizon.
- The nuke's fire storm is in the same pall passes (`client/nuclear/FireStorm`, `FireStorms`): at the blast slot the
  fire map is RGBA16F by cells of 32 blocks (R — flame, G — smoke presentation, B — it × column rise), with mip levels.
  The levels are themselves clusters of sources (instead of the list of lamps `FxLights` or clusters per frame): glow at
  height h — from the level where the cell ≈ 2h (and it fades with height — smoke under the point: to a fifth of the
  storm's top, three times; it shines to each by whiteness — more dust, less soot); the column — from the level of its
  width; at the ground, while the column is already a cell, — the four near cells (`texelFetch`), each with its own
  column at its own place (`fireSpot`): the columns stand apart and merge by ~65 blocks. Thinner than a third of a ray
  step the column is not read (`cone` in `storm` — the pixel's width or a third of the step): a column already
  step-wide, the ray either went straight through or passed by, and accumulation gave thin translucent grey streaks by
  day — "rain"; farther from the eye the columns come from the map's level with a width of a step, wide and soft. The
  column's edge is bent and torn into puffs by the same noise (the core is solid); wide smoke curls in puffs of the pall
  at the point (`Sample.clumps`, without its own sample); and the sunlight on the column's smoke fades in its own
  thickness toward the sun (a Gaussian column: across — a Gaussian, along — erfc through tanh; wide — to the edge or the
  top): soot scatters little, and without this shadow the column is a flat grey stripe. Above unburnt ground (the sample
  of a wide column from a level not finer than 64 blocks is zero) the columns and the noise are not read. The
  surroundings' glow (`fireAround`) — all of the map's fire (its copy 1×1) as a source the size of a city, 1/r² from the
  epicentre, with the dust's phase and extinction by haze from the zone's edge: without it the dust around a burning
  city glowed at night only above the fire, and from 2 km the city stood as a dark mass behind a kilometre of unlit
  dust. Smoke of the columns — in the same ray march of `fx_pall` (the storm frame `PallFireMin/Max`: there half the
  steps); tornadoes (up to 4 in the frame, the near ones) — their own short march along the cylinder, merged into the
  ray when the step reaches the entry. Flame on surfaces — in `fx_pall_composite` by the world's depth: not nearer than
  `FireStorms.FLAME_NEAR` (it shows up to one and a half; there a spot of paint is visible, while real vanilla fire
  burns), not higher than Minecraft's clouds (`F[3].w`: clouds write depth, so without the cut-off the clouds "burned"),
  and beyond the edge of drawing without DH — on the terrain under the pall (`pallFloor`). The layer's quads take the
  fires' light from below (`fx.vsh`, `Sampler7/8`); they do not count the smoke of columns in front of a quad (only the
  pall's field). The even haze glow of the table, while the storm goes on, fades (×(1 − trust)): it is replaced by the
  glow from the map. The pall OFF — no storm.
- Clouds of ordinary weapons are baked puffs of the effects layer, not a volume (`far/SmokePuffs`): the cloud is a model
  (`fx/smoke/CloudModel`: the charge's heat — after Church's 1969 clouds; fuel — the heat of combustion without the
  radiated share; rise — MTT thermals in stable air, α 0.285, the top at π/N; the fuel ball — CCPS; the ball of
  detonation products; soot — free carbon after Kistyakovsky–Wilson and the fuel's yield (the fuel's soot is born along
  the ball's burning, `sootBurnt`, ⚠ an even burn rate; before, all of it was in the cloud from the first frame); dust —
  the crater; spreading — Briggs; life — while the thickness across exceeds 0.02); the cloud's puffs are Gaussians of
  matter mass (`CloudModel.blobs`; dust — by extinction in kg of mix `DustModel.K_MIX`: the coarse settles, but the fine
  extinguishes, `DustModel.extinctionAloft`; by mass the cloud faded faster than its thickness settled).
- Ground dust — `fx/smoke/GroundDust`: the same law as for the nuke (`DustModel.chemical`: transport by Gaj–Small, the
  Kuhl layer, baskets, suction into the stem, without debris dust; saltation develops over `SALTATION_S` 0.25 s — the
  wave phase for HE is milliseconds, for the nuke the law is steady; surface — by 8 sectors and 5 radii (3–48 m) of the
  top blocks around, `FarBlasts.ground`: each has its material and the height of its top above the blast point, without
  "ground or a building" (Artem's decision 23.10); loose `Surface.FIELD` 0.34 m/s, turf `Surface.COVERED` 3 m/s, solid
  (stone, brick, planks — roof or rock) `Surface.HARD`: only settled dust, 0.6 g/m² after AP-42; water — no dust). Under
  a permeable crown the client looks for the forest floor (`FarTerrain.dustY`: only foliage and air let through; solid
  wood/stone/water hold the same way). Its material determines the source and the terrain of the cold flow;
  `FarTerrain.dust` caches the samples for the current tick, including on a rewind; no chunk — bare soil at the height
  of the map's tile; no tile either — the height is unknown: exactly at the blast's height above what is under the
  point, lower: without this the SAM's break far away would lie on contact with the ground — a "plate" of dust in the
  air), the power — nuclear of the same pressure at the surface (`CloudModel.equivalentKt`: W/0.5, the share of energy
  in the wave for the nuke — half; Broad against Kinney–Graham — within a third, `GroundDustTest`), the blast height
  above each surface — its height (a hit into a surface — `CloudModel.hob` 0; a SAM's break — above the terrain,
  `FarBlasts.heightAbove`; contact — by what is directly under the point, `FarBlasts.overSurface`: roof and wall — 0, an
  aircraft in the sky — its height). The ring of display — the surface's radius: a puff of a sector has one surface from
  which it was lifted (rings by mass shares mixed the roof and the street, and a puff jumped between them). Besides the
  stock settled on the surface there is crushed fines right at the strike: `CloudModel.crushed` — a second instance of
  the same `GroundDust`, its mass — what was destroyed at the target (`BlastTarget.mass`: the bowl in the ground of the
  material or the breach of a slab × the bulk density of the substance) × 0.02, not from `HARD`. The total mass is
  preserved: half — in the hot cloud, half — in the cold ejecta and the reverse fallout (⚠ equal fluxes are an estimate,
  there is no correlation for HE; G&D 1977 §2.96 and NRC 2005 ch. 5 describe the separate cold flow itself). The initial
  area — a crater with a rim (2 radii), the shape of a uniform disc — an estimate; further the same drain, settling and
  flow down, without double counting in `underBall` or the stem. On a paved road low dust remains, even when there is
  almost no old settled stock. The dust of the ring rises with the front's arrival by the share `lifted`, in phase — the
  top of Kuhl (decimetres for a Shahed), then the layer grows the air's turbulence (Briggs by class; the flashes that
  heat the ground are absent for HE) and the wind carries it off its surface; what was torn off under the fireball — is
  in the cloud. A layer heavier than air (complaint 23.10: a brown stratum at roof height stretched over the street):
  the stratum spreads by gravity current (Huppert–Simpson, R² = R₀² + 2Fr√(gM/(πρ))t, Fr 1.19, M — dust in the air;
  `GroundDust.stretch`), and the path of the puff (`GroundDust.level`: the grid `PATH` 0.25–2 s, each node counted once,
  between nodes the level and the delay — in a straight line; a rewind — anew; catch-up — no more than `CATCH_UP` nodes
  per call; it is built with `PATH_FROM` (0.5 s): until then the front still lifts the ring, and the puff's mean radius
  runs outward) goes from the surface under the puff's trail at its place, if from the strip's point to it is one
  surface (a slope, a yard: at the outer ring the puff is tens of metres farther than the strip's point), and if a wall
  lies between them — no higher than the surface from which the dust was lifted: heavy dust itself does not get onto the
  roof (`GroundDust.begin`; complaint 23.10 — street dust started on the roof of a house by the street). Heavy air goes
  down to the bottom it reaches: the surface under the puff is the third from the bottom of the 13 terrain samples under
  the trail, the lower ~20 % (`GroundDust.under`: the terrain `GroundDust.Relief` — `FarTerrain` — at the points
  `FOOTPRINT` at ±σh/2, ±0.62·σh/2 and the diagonals; a street between houses, the foot of a slope, a street beyond the
  roof's edge; a post and a trunk hold nothing, `postsDoNotHoldTheLayer`; a gap that catches one or two samples does not
  take the whole puff, `gullyAndSteepUpslopeKeepDustOnTheSurface`; steps are incommensurable — a trail that is a
  multiple of the block step does not fall entirely on the roofs). Down — along it, while it goes no steeper than one
  block per metre of path (a slope up to 45°); past the edge, steeper — the faster of two: a heavy MTT thermal of the
  dust mass in the air and the puff's size at the edge (`GroundDust.fall`, checked against `Thermal` in
  `GroundDustTest`) or the wind behind the edge (`LEE` 0.5 m per metre of drift: the flow sticks through ~2 heights,
  Castro and Robins 1977); and above a slope it could run down — together with the surface, until it lands
  (`dustFallingOffACliffLandsOnTheSlopeBelow`). Up — no steeper than 45° of path, and only for a rise no higher than the
  thickness of the layer 2σv (Rottman and Simpson 1983: a heavy current deeper than an obstacle spills over it); before
  a rise higher than that — a dam: drift and drain stop (`Track.held`) until the layer becomes thicker (⚠ no flow around
  the obstacle). Terrain unknown — a lying puff holds its level, a falling one falls to the last known surface beneath
  it (there is none — to the lowest surface of the sector). Earlier variants and why they are gone: a surface at one
  point — a column held the puff for tens of metres; the mean along the path — lifted street dust up to the quarter's
  roofs (complaint 23.10, `calmStreetDustStaysInTheStreet`, `windyStreetDustStaysInTheStreet`); a wall along the path
  line with flags — after a step on a slope the puff went under the hill (`dustClimbsStepsAndHillsBehindThem`); without
  path memory — it jumped onto the roof of a neighbouring house (`fallenDustStaysDown`); a threshold per grid step —
  tore off over a slope (`dustFollowsSlopes`); over unknown terrain it hung (`fallingDustOverUnknownReliefStillFalls`).
  `dustNeverHangsOverTheVoid` — a puff over the void lasts no longer and no shorter than the law;
  `roofAndGroundAreOneLaw` — a roof on all sides and bare ground give one layer. The stem (dust of suction from the
  ground to the cloud's base) — 6 pieces of a dust tube (`CloudModel.Blobs.tube`, `STEM_PIECES`, leans with the wind's
  shift with height; dust evenly by height); the stem's top grows from the start of suction (`GroundDust.drawnFrom`) at
  the suction rate `STEM_INFLOW` × the cloud's rise and no higher than its base (`stemTop`, `stemRisesFromTheGround`):
  formerly the column stood its full height while the dust had not yet reached it there (BLASTSEQ D09), it was drawn
  like a fire's plume and trail; formerly — up to 8 puffs at 2σ, at a height of hundreds of metres — at ~7σ: beads
  (frames F5). A piece of tube, like a puff, hides behind the terrain of the blast place far away (`Source.visible` at
  the ends of the piece); the frame of the stem's puffs — by the cloud's growth (sigma0 = σ/`puffGrowth`), as for the
  cap. The stem and trail puffs go into the common `CLUB_BUDGET` with the trails: dozens of clouds with stems nearby
  used to turn the distant tubes into ribbons. In 3.5.0 the "ground dust" puff stood from the first frame at the radius
  of Church's cloud (the final state after tens of seconds) with half of all the dust, and at a SAM's break — in the
  air: a dark "plate" (F3). There is no crater above 0.1 of the radius of the products' ball (`CloudModel.digs`). The
  rings of the count of one charge — once (a cache). The cloud's source is `SmokeGrid.Source`: explosion clouds (the
  `FarBlasts` event, with the condensation cloud; fuel in the crater — the `FireSmoke.pool` hearth), launch clouds
  (`LaunchCloud`), fire smoke (`fx/FireSmoke`); `SmokeGrid.quads` gives them out, `SmokePuffs` draws them — near and far
  alike. A Gaussian is a cluster of puffs `FxAtlas.puff6` (the cap of a cloud from the CC0 simulation by JangaFX "Ground
  Explosion", EmberGen, rendered in Blender — `tools/bake_fx` puff-vdb, `SOURCES.md`: our own Mantaflow gave a fluffy
  ball with nearly even maps — clouds like "cotton wool"; the cap — above the cloud's neck, shot from above and squeezed
  along the major axis to round: from the side the slice lay with a straight bottom, and a stretched frame laid the
  Gaussian as a slanted stripe with even flanks, and the pall of the raid stood up as a wall (review #443); a frame that
  runs into the edge of the simulation area is not taken by the bake; the puff frames have no straight silhouette edge
  and no elongation — `FxAtlasTest.cloudFramesHaveNoStraightEdge`, `puffFramesAreRound`, on the real two-dimensional
  alpha; the stem and the ground dust the mod draws with its own puffs; the thickness of frames is one — opacity belongs
  to the mod; 16 frames, each frame has three maps — the sun's light from three sides and opacity, from the other three,
  the motion vectors to the next frame): the visible edge is where the thickness falls to 0.05 (`SmokePuffs.edge`, for a
  dense one ~3.7σ), the opacity of the middle — 1 − e^(−τ₀), puffs — one per 40 pixels of the visible radius, up to 12
  (a 128-pixel frame is not stretched over half the screen), their shares — so that the middle comes out with the same
  transmission; the frame — by the plan (`SmokePuffs.flush` at the end of `collect`, after the projectiles' trails): the
  cloud puffs and the places of tubes (trails, fire plumes, stems — `SmokePuffs.tube` does not draw them but holds them,
  `Draw.holdPlace`) wait; near ones (closer than σ) of a source's puffs merge into one with the same mass and moments
  (`merge`, as the merging of SCIPUFF puffs); the plan — how many screens each puff and place will cover as one quad
  (the floor) and as a cluster (`cover`, the sum of areas on screen with overlap). In the game this plan is made off the
  client thread one frame behind and drawn at the current eye (`client-smoke.md`, frame planning). Frame cost is bounded
  at any strike count by `FILL_BUDGET` screens (16, about 4.8 ms on the 4060 at 2560×1600; at 8, the edge/inside views
  of 40 strikes already lost dark spreading clouds and plumes to smooth veil haze). Above that floor, first conceal
  occluded matter in world directions (`Draw.conceal`), then merge overlapping clumps and places from different sources
  with the same moment-preserving law at successive levels (`Draw.mergeLevel`: world cells at 0.5/1/2 of the puff's edge
  by octaves of the edge — not of the screen: turning the gaze does not disturb the merge; puffs of different looks
  (`SmokeGrid.Look`: smoke, the ejecta curtain, crushing dust, the burst cloud) and spray with smoke do not merge, spray
  stays round; places of one tube do not merge at any level — for a group of merged places all its tubes stay in it
  (`SmokePuffs.TubeGroups`, lives between levels: the group's holder remembered one tube, and the groups A+B and B+A of
  the next level merged); a cell holds up to `SLOTS` groups; the places of neighbouring tubes converge in pairs; a row
  of tube places merged along the axis,
  In the game this plan is made off the client thread one frame behind and drawn at the current eye (`client-smoke.md`,
  frame planning).
  Frame cost is bounded at any strike count by `FILL_BUDGET` screens (16, about 4.8 ms on the 4060 at 2560×1600;
  at 8, the edge/inside views of 40 strikes already lost dark spreading clouds and plumes to smooth veil haze).
  Above that floor, first conceal occluded matter in world directions (`Draw.conceal`), then merge overlapping
  clumps and places from different sources with the same moment-preserving law at successive levels
  (`Draw.mergeLevel`: world cells at 0.5/1/2 of the puff's edge by octaves of the edge — not of the screen: turning the
  gaze does not disturb the merge; puffs of different looks (`SmokeGrid.Look`: smoke, the ejecta curtain, crushing dust,
  the burst cloud) and spray with smoke do not merge, spray stays round; places of one tube do not merge at any level —
  for a group of merged places all its tubes stay in it (`SmokePuffs.TubeGroups`, lives between levels: the group's
  holder remembered one tube, and the groups A+B and B+A of the next level merged), a cell holds up to `SLOTS` groups;
  the places of neighbouring tubes converge in pairs; a row of tube places merged along the axis,
  creates beads or a thick tube and adds opacity at the bottom of a dense plume (`SmokeRaidTest`, edge view));
  If merging still cannot fit, `Draw.fit` keeps the most visible quads within the floor budget, ranked by their
  opacity after nearby cores. A tube place uses the whole tube's axial opacity, otherwise thin dark plumes go first.
  The remainder becomes veil; rejected ribbons are omitted. The final 15% of the budget fades (`FIT_FADE`) before
  overflow: fit runs above 0.85 budget even when every quad still fits. Otherwise crossing 16.093 → 15.986 screens
  restored 573 partially faded clumps at once, jumping ray transmittance by 0.064 (STEADY frozen raid, 0.5° yaw).
  The 15% band preserves continuity and leaves the 13.16-screen water salvo without budget veil (`WaterSalvoTest`);
  the former 25% band reached opacity 0.175 there. An earlier visibility band twice the cutoff faded every selected
  record in a uniform scene. Trail/plume ribbons also enter the plan and budget
  (`SmokePuffs.ribbon` → `Draw.holdRibbon`: queued for the frame; fill uses the strip vertices, end disc and eye-in-tube
  veil; rendering, planning and tests share `FarSprites.RibbonShape` geometry):
  previously segments beyond the frame's places ran as a ribbon past the budget, and 300 standing tubes 30 m long
  flooded 29.5 screens at 16 (`SmokeRaidTest.ribbonsStayInTheBudget`, and 1000). Previously the largest on screen went
  into the veil — and those are the front puffs, the whole view rests on them: a wall of smoke from the edge became an
  even brown haze with the edge of a veil cell. Merging beyond two edges is not allowed: clouds in one cell merged over
  16 edges — a cloud for the whole raid, and outside, the sky at the frame's edges was closed by 40 %. The past frame's
  level holds while the floor below it does not fall under 0.8 of the budget (`HYSTERESIS`), and also when the floor is
  just under the budget: otherwise at the budget's border the merge ran across frames, and the clumps flickered
  (`SmokeRaidTest.mergeLevelHoldsAtTheBudgetBorder`). Then the clumps take fewer puffs (the share `s` — by division by
  estimate), and the plan checks it by what real quads will fill (`Draw.drawnFill`: ⌈clumps⌉ quads — the last with a
  share of alpha fills its whole area, — size 0.8–1.2 and a shift by grain; at a tube's place and a single puff with its
  own size; for a lying one — by the projection of the quad's four vertices onto the horizontal, `polygonFill`: at the
  depth of the middle the dust layer 40 m below the eye and 5 m high came out at 15.4 screens against 36.7,
  `lyingDustIsMeasuredByItsCorners`);
  Above budget, reduce the share. Measure the previous share's actual fill once per flush and reuse it for both
  hysteresis decisions. Keep the previous frame's share while its actual fill fits; detail grows only
  with 20% headroom, which is also retained after overflow (hysteresis). The old plan used fractional clump counts
  and mean size: 12 overlapping Gaussians planned as 8 screens actually filled 11.3. Now drawn fill never exceeds
  the plan (`SmokeRaidTest.drawnFillNeverExceedsThePlan`). A 16-strike raid fits unchanged (`SmokeFillTest`).
  The 100-strike ground fixture (70 fuel plumes, ground ejecta curtain and crushed stone dust) originally had floors
  of 15/29/67 screens outside/edge/inside with budgeted place stride. Before the budget applied to all paths, one
  clump per Gaussian was the minimum and plumes bypassed planning: 41/104/175 screens. Budgeted fill was 15/16/16;
  regional transmittance stayed closer to model matter than a uniform full-screen layer (`SmokeRaidTest` errors
  0.08/0.17/0.08 versus 0.21/0.24/0.29). ⚠ The veil
  lighter and smoother than puffs of the same matter (light without the puff maps, 12×12 cells): what went into it is
  haze. The plan's work with a raid does not grow by construction, in every frame and in the raid's first frame too:
  places of tubes — no more than `PLACES` (= `CLUB_BUDGET`, 3000; beyond that a segment has no places in this frame, its
  places go into the puff budget, and the place step grows), cloud puffs — no more than `BLOBS` (2000; about 8 per
  strike): beyond that, ones lying on each other merge by the same law no farther than two edges, and the rest is chosen
  by contribution (opacity × solid angle; formerly the cells grew without end, and a grid of Gaussians σ 1 m at 40 m
  came out as Gaussians σ 20 m — smoke in the empty gaps, `blobLimitDoesNotSmearOverClearSky`), those waiting — in a row
  without merged ones (`Draw.thin`, `compact`; a raid of 400 — `SmokeRaidTest`); veil Gaussians — `VEIL_MAX`. ⚠ The
  cloud model itself (`CloudModel.blobs`, ground dust) is computed each frame for each source: about 2 ms of CPU for 100
  strikes. A single puff — without size scatter.
  STEADY replaces the 32×18 screen occlusion grid with a world-direction field. In the original frozen 100-strike
  ablation, 0.5° yaw gave mean |ΔT| 0.002915 inside; removing only that grid gave 0.000106 (−96.4%) at the same fill cap.
  The grid also changed veil priority, so merely fading rejected records would not suffice. The new field has
  world-aligned cube faces and shared seam nodes. Resolution follows the actual projection's diagonal angle
  (`view.edge`): it targets 24 cells across that diagonal, with a 48-cell minimum and 48/96/192/256-cell tiers.
  Coarsening needs 20% headroom;
  buffers retain their largest capacity, so animated FOV and repeated binocular zoom do not allocate every frame.
  Only the four nodes needed by each core query are deposited into (at most 36 nodes per plan entry), so zoom
  does not require dense whole-cube deposition. Before deposition, erode the normalized ellipse radius by its
  angular derivative bound times the node's bilinear footprint (`2*sqrt(2)/cells`). Each node then underestimates
  the original core throughout that footprint, including cube seams; ordinary bilinear sampling remains continuous.
  This prevents a tiny dense core on one node from concealing a physically clear neighboring ray while retaining
  the flat inner profile of resolved cores. Unresolved cores remain in the ordinary budget plan.
  `SmokeOcclusionTest` checks that exposed neighbors survive at normal and 4× zoom, while fully covered cores still
  disappear, including between grid nodes. Nearby cores add optical depth with a smooth edge;
  nine rays through a distant core take its minimum, fading the clump between depths 2 and 3 (old full cutoff).
  Deposit uses unnormalized cube rays: ray length cancels in the plane ratios. Scaled puff axes and a squared
  ellipse rejection leave one division and square root only at contributing nodes.
  Ring sampling stops once the minimum is at most 2, since further rays cannot enable concealment.
  `SmokeOcclusionTest` compares deposited nodes with normalized ray/plane intersections and the conservative
  footprint, including cube seams, poles and overlapping cores.
  Partial concealment enters the rendering record's visibility (`held`/`places`) and priority before merging.
  A separate partial-hide flag prevented merging, leaving about two extra screens of floor and giving nearly
  invisible places their original budget priority.
  Camera yaw cannot rotate these directions. Concealment ramps between one and 1.25 budgets of floor and is absent
  with spare budget; fit priority uses the same strength (`1 - strength * (1 - exp(-depth))`) so crossing the budget
  cannot abruptly reorder selected puffs (`SmokeOcclusionTest`). Flat clumps and matter at the eye are retained.
  Node traversal is bounded by the clump sphere's
  intersection with each face pyramid: without this bound, horizon clumps traversed two extra faces in full
  (about 10 ms versus 0.9 ms CPU at the 100-strike edge camera). Upright planes face the eye position with world-up;
  flat planes keep a seeded world angle (`PuffGeometry`, shared by GPU, plan and tests). `lyingShare` divides optical
  depth continuously between flat/upright planes, both charged to the budget. The first child stays at the center;
  additional children occupy seeded pairs. Size/scatter remain continuous at count 1 (`clusterSize`, `clubScale`).
  The old 40 px threshold changed ray T by up to 0.61 for a 0.00019 m eye step; the flat switch gave 0.49 for 2 µm.
  `SmokeSteadinessProbe` freezes time and measures fixed world rays under yaw/steps, bounding mean |ΔT| and visible
  jumps. `SmokeTargetedProbe` checks both thresholds and fixed flat corners. `Screen` intersects the actual plane:
  a center-depth disc could not test flat-plane rotation, and stepped radial sampling introduced false jumps.
  The veil (`SmokePuffs.Veil`, drawn by `FarSprites.veil`) — 12×12 cells 2 blocks in front of the eye; at each vertex
  the thickness along its ray through all the veil's Gaussians (`sightDepth`), linear between vertices, noise at half a
  step at the output of `fx.fsh` (otherwise a smooth veil would step in 8 bits); it holds no more than `VEIL_MAX`
  Gaussians (48: ~0.3 ms of CPU); more — the near ones merge by the same law (direction from the eye through the cube's
  faces, octaves of distance and size, color — the mean over the visible ones).
  Veil coarsening retains the previous level until the finer representation has 20% headroom, while keeping the
  48-Gaussian cap. A frozen 0.5° yaw changed finer counts from 50 to 48 and previously removed an entire merge pass
  (24 -> 48 final Gaussians), causing a transmittance jump despite stable quad detail and place spacing.
  Budget/world reset and an empty veil frame clear this history.
  At a vertex — softness toward the world (`Veil.soft`): the vertex fades toward the world by the same `fx.fsh` as the
  soft edge of a quad, and half of the veil the world takes where half its opacity accumulates along the ray
  (`Veil.half`: the matter — a segment by the mean and spread over the thickness, `sightMoments`, a truncated Gaussian;
  for a dense one — nearer the start): a house in front of a distant cloud in the veil does not sink into it, and in
  dense smoke around the eye a wall a few metres away is already almost hidden (⚠ linear descent —
  approximation of the optical depth in front of the scene; formerly veil hid everything beyond 2 blocks).
  The mesh remains near the eye, but it blends at its matter's half-opacity depth (`Veil.depth`, alpha-weighted mean
  over all nodes), one key for every cell. In the projection-matched CPU trace, the old 2 m key put veil matter at
  96–109 m over textured clumps starting at 16–17 m, erasing their detail. Reordering preserves T, so a
  transmittance-only test misses it. A key per cell (mean of its four corners) put neighbouring cells on opposite sides
  of a club between their depths: the lighter veil lay over the club in one cell and under it in the next — hard
  screen-aligned rectangles and steps in smoke, 0.1 of the frame diagonal per cell (RECT, 27.10: 51/255 in RGB at equal
  transmittance). `VeilDepthTest` checks one key for all cells, ordering between near/far clumps and dense eye-local
  matter's old order.
  A Gaussian enters the veil while the eye is within its edge, by the fraction its quads do not show
  — the middle to the side, behind, or right at the eye (`SmokePuffs.front`: a quad facing the camera lies entirely at
  the depth of the middle and fades at the near plane, as in `fx.fsh`), and ahead by 1–2.5 σ along the gaze — already
  puffs (`SmokePuffs.ahead`). Previously the veil was one quad with a thickness by the middle of the screen, and puffs
  faded as soon as the eye entered the edge of a Gaussian (~3.7σ): a SAM's trail that the wind carries through the
  camera (the final pass of 3.7.0, sam-burst), from 8 to 10.5 s, gave an even white frame (brightness SD 1.5–3 of 255)
  and over a tube where the ray does not meet it; `EyeInSmokeTest` — a tube through the eye against the matter by rays
  and without jumps. Inside a dense tube the haze stays almost even — and so by the law (thickness across ~2.7): it is
  visible where it is denser (along the tube) and where it ends. color — a thick layer of mixture (`SmokePuffs.thick`:
  R∞ = (1 − s)/(1 + s), s² = (1 − ω')/(1 − ω'g'), closure by the mean of hemispheres — for Eddington soot came out
  negative, soot black; inversion — `SmokeGrid.dustAlbedo`) in the sky's light at the eye; light from the sides — in the
  shader (`fx.fsh`: `sixFrames`, `sixLight`): direct (sun, moon — `FxLayer.sun`, share
  (est.)) uses squared-cosine weights (sum 1; plain cosines would make 45° sunlight √2 brighter). Directions follow
  the actual plane and clump UV axes. Two `UV2` shorts carry the basis instead of constant `FULL_BRIGHT`
  (`PuffGeometry.faceLight/flatLight`); `fx.vsh` restores light and transforms directions at vertices, retaining the
  36-byte format. No fragment basis derivatives/normalization. Ambient averages all six sides; frame fireballs
  (`FxLights`) use the maps and cannot exceed on-screen fire in the same
  to the same extent as for `balls` (illuminance × the sky's light `FxAmbient`: otherwise at night the fire would barely
  light the smoke); the shadow in the maps — of the bake's matter (`bake.py`: ω 0.55, g 0.55), of the puff's mixture —
  to the power k of the mixture / k of the bake (`SmokePuffs.shade`, k of two streams √(3(1 − ω')(1 − ω'g')); byte
  `Normal.y` in the puff's band, `FxQuads.sixWay`): white smoke of aluminium oxide and droplets almost without shadow —
  without this the SAM's launch cloud came out as a dark-grey ball, soot darker than the baked; all of it — against the
  mean over six sides of the puff (`puff6.json` “diffuse”): normalising by the dark middle gave the edges 30 times more
  — orange spots from the ball. The sum is in linear (the sheet's maps — power 1/2.2); the transparent edge of the frame
  — `discard` before the second map (the layer's cost is the fill of large puffs; a Gaussian larger than the screen —
  fewer puffs). The frame's sample steps in from the edge of its place by half a texel of the reduced copy it reads
  (`frameInset`: the level — ⌈log2 step⌉, no farther than `FxAtlas.MIPS`, `PuffAtlasEdgeGlslTest`; likewise the fire
  frames `fireFrames`): with the sheet's half texel, the copies at 3–4 took the edge of the neighbouring frame, and
  puffs far away and the flat dust layer from the edge lay as thin streaks across the frame (WV). The frame — by the
  growth of the matter (`Source.puffGrowth` against the simulation's growth `puff6.json` “growth”, `FxAtlas.puffFrame`),
  between frames — a mixture along the motion vectors (`Normal.z` of the vertex); frames — only the churning ones
  (`FxAtlas.turbulentLast` by “detail” in `puff6.json`/`spray6.json` — the maps' fine structure,
  `tools/bake_fx/pack.py --detail`: no less than 2/3 of the largest): the end of the simulation — a dispersing final
  puff, and a growing cloud is self-similar (Scorer 1957); by the cloud's growth of 150–300 m in 10–30 s, late frames
  were taken — flat blurred balls (RV2). All cap frames churn; the frame spread of the clump's and the tube's puffs —
  ±`SmokePuffs.FRAME_SPREAD` around the source's frame, at the end of the sheet — backward (`clubFrame`): for an old
  cloud the source's frame —
  the last, otherwise all clumps would use one image. Even a single cloud clump uses its seed for frame and mirroring:
  in the old 100-strike fixture at 3 km, all 335 old single clumps used unmirrored frame 14, repeating the same cut.
  Glowing gas (above the Draper point) in a source that draws the fire body
  (`Source.drawsGlow`: the explosion cloud) — its ball: smoke shows through by the same share as the body melts
  (`CloudModel.glowShare(K, sky)`: 798 → 1200 K and B(T) against the sky's light on the smoke `SMOKE_SEEN`·sky, sky —
  `FarView.skyLuminance` (by day 1, at night ~8·10⁻⁴), exactly 0 and 1 beyond the contrast threshold of 0.02 in both
  directions, between — by the logarithm of the ratio (B/(B + s) does not reach 1, and the cloud of the ball carries
  tons of dust: 0.04 % of it — a dark clump in the ball by day); matter inside the glowing ball (the stem, the ground
  dust) takes the ball's temperature (`CloudModel.inBall`); the eye's adaptation is not included — with adaptation to
  the ball, the cold gas beside the eye at night came out as a black clump over the ball; with one temperature the ball
  of a ~1.3 t bomb was solidly orange by day for 3 s at a brightness of 0.009 of a white wall), no seam; the ball's
  light outwards — `CloudModel.emittedLight`: a grey body with the budget `FireballRadiation` × the temperature share
  `glowShare(K)`. `FxLights`, the grid's smoke (`FarBlasts.fireballs`), the world (`FarBlasts.heat` → `HeatPass`) and
  the clouds' glow take one flux, independent of the sky's light (`ShownLightTest`). The body's contrast with day smoke
  is presentation of its visibility, not a change of emission. On cooling below the Draper point the flux fades smoothly
  and does not exceed the budget; a launch cloud has no fire body — its hot cloud is drawn as smoke (otherwise it would
  vanish). HE budget — visible 400–700 nm, η = 0.0024 of TNT's energy (Khristoforov 2014, a small test of 0.95 kg TG; ⚠
  transfer to other compositions and 10–500 kg). Fuel — the whole spectrum, χr = 0.3 of the energy of the mass burnt in
  the ball (TNO; ⚠ an estimate for a tank). HE — the normalised visible impulse by t/m^⅓: one triangle with a front up
  to 1/15 s/kg^⅓ and a decay to 0.2 (⚠ approximation of fig. 2b: the unresolvable millisecond peak is included in the
  wide impulse); the integral to 0.1/0.2 s/kg^⅓ — 0.15/0.24 % of the charge energy per the source text. The budget and
  the peak are checked at 20–240 frames/s with an offset of the frames' start. The blackness of HE varies in time: the
  impulse power / 4πR²Mvis(T). The constant fuel coefficient — budget / integral over the former R(t), T(t), including
  the common cooling tail if the fuel burned last. The sizes and times of the geometry do not change. For particles the
  early return on the invisible body is kept: by day the hidden cooling balls do not take FxLights; this is a cut-off of
  presentation, not switching off the flux onto the world (`FarBlastsTest.hiddenCoolingBallsDoNotEnterParticleLights`).
  The smoke grid (`SmokeGrid`, `layer/SmokePass`, the march `smokeStep` in `fx_pall`) takes only a source of a scale
  that its cell resolves (`Source.volume`; at present there are none: explosion clouds, launch clouds, the ICBM's, and
  fire smoke are all puffs), and only if each of its puffs is no narrower than two cells of its cascade
  (`SmokeGrid.RESOLVE`): in 3.5.0 the clouds of ordinary weapons went into it, and an 8–32 m cell smeared the cloud into
  metre-sized cubes and soap (`SmokeGridTest.gridRefusesWhatItsCellDoesNotResolve`, `conventionalCloudsAreNotVolume`).
  The grid is three cascades 64³ around the eye (2, 8, 32 m), the filling and the ray march — as before.
- Six-way atlas filtering computes one isotropic LOD from the original perspective UV derivatives and reuses it
  with `textureLod` for all maps; keep `frameInset` unchanged (`PuffAtlasEdgeGlslTest`). Repeated `textureGrad` on
  world-oriented planes was the measured GPU cost: the same-VBO far replay changed one FX draw from 1.52 to 0.92 ms,
  not the whole frame. The atlas uses anisotropy 1; affine UV gradients are not equivalent near the eye.
- The fireball of an ordinary blast — frames `fx/particle/fireball` (burning from a ready simulation — CC0 “Gasoline
  Explosion” JangaFX EmberGen, `tools/bake_fx/bake.py fireball-vdb`, rendered in Blender: soot absorbs and emits by
  Kirchhoff the brightness of a black body at its own temperature; ⚠ the EmberGen field has no units: the 90th
  percentile where it burns is 1800 K, by which the surface emission while burning is ~314 kW/m², CCPS 200–350;
  `fireball.json`: frame time in fractions of burning and colour temperature). On the atlas sheet — presentation:
  exposure by the fire and a film curve (`pack.py`: `film_exposure`, `film` — ACES per channel; the 95th percentile of
  the burning ball's brightness is at 0.60 of white, one for all frames; above white — channel coupling for bright
  cores). Measured against the white wall (as in 3.6: 1 − e^(−x) of brightness against a wall tens of times brighter),
  the whole ball was cut to a flat yellow disc without folds or soot, while our own Mantaflow gave a smooth ball without
  puffs (`FxAtlasTest.fireballFramesMatchTheBake`: the frame's fire on screen 0.33–0.75). The ball's light on the world
  and smoke, glare — still the model's black body, not brighter than the frame's fire on screen. The frame — by the
  model ball's state (`FxAtlas.fireballFrame`: burning — by time, cooling — by temperature), size — the model ball's
  radius at that moment. Frames: 48 — 32 for burning equal in time to the simulation, 16 for cooling down to the last
  glowing; 10 frames over 2–3 s of burning took ~0.23 s per frame, and the silhouette stood still (review #426).
  Frames 256²; the top does not rotate (variety — by mirroring u); the blend of neighbouring frames is one fragment in
  `fx.fsh` (`fireFrames`) along the frame's motion vectors (`fireballv_*`, 128², Farneback, as for the smoke puff), with
  multiplied alpha: two squares with weights would give a brightness dip. The frame holds all the matter
  (`fireball.json` “half” — half-size in ball radii, `FarBlasts`: half = r·half): the edge follows density, not a
  circle — a circle cut the cooling soot into a “ghost sphere” (`FxAtlasTest.fireballFramesHoldAllTheirMatter`); render
  512², 256 samples without adaptive sampling (`coolingSootIsNotGrainy`: at 32 samples the soot is speckled — the
  denoiser does not clean it). `pack.py`: alpha not below the on-screen brightness; a thin fringe reddens and darkens
  rather than blending into the sky or smoke as a muddy mix. The glow around the fire — `FxBloom` (below), not a round
  halo behind the body (#426).
  Burning is over — the ball cools by drawing in air (`CloudModel.cooling`: MTT thermal with the ball's radius and
  temperature at the end of burning; the radius — from the CCPS ball to the volume that holds heat, for cooling,
  `CloudModel.handoff`); the light — the black body of this temperature, down to the Draper point; the soot of the
  cooling ball — in it, and it passes into the layers of the cloud-thermal over the same cooling (in 3.6 — all at once
  at the end of burning: a dark dome in a tick, the middle of the soot 17 m higher for a bomb,
  `CloudModelTest.sootCoverageChangesContinuouslyFromBallToCloud`); the share of smoke at the fire's body — by the
  puff's and the tube piece's opacity, not by mass, `SmokePuffs.smokeShare`: a ball has hundreds of thicknesses, and
  mass × share gave continuous soot already at a 1 % share — a jump within ~0.1 s and a dark halo in front of a
  missile's ball by day, `SmokeFillTest.glowShareScalesOpacityNotMass`); in 3.5.0 the light dropped to 0 at the end of
  burning (`CloudModelTest.fireballFadesWithoutSteps`). The earlier procedural ball (`fx/particle/ball_*`,
  `tools/gen_particles.py`: grows and cools in frames by the share of life) — is the particles of the ball
  (`Fx.fireball`: the air-defence missile's burst). Fuel in the crater (law `nuclear/model/FuelPool`, common with the
  server: fuel share 0.25 — Sandia, for a steep impact in the crater 25–50 %; the crater's area; burn-out
  `FuelPool.Fuel.poolBurn` × 0.2 — a thin layer on the ground, Mealy et al. 2011: a Shahed ~2.5 min at ~2 MW, a missile
  ~16 min; in 3.5.0 — a 0.1 share by the burn-out of a deep pool, which went out in 12 s) burns with Minecraft fire on
  the blocks where the glass also landed (server, `warhead/FuelFires`, Artem's decision, 24.10): it has no flames of
  its own (the earlier `Explosions.pool`) — the world draws the fire itself; from 0.8 of draw distance, where fire
  blocks melt, the body of the flame far away and, at any range, the glare and the veil (`FireSmoke.far`, as for the
  substations' oil); the flame's light on the world — still `HeatPass` (`FireSmoke.heat`: the light of block 15 dies out
  over ~14 blocks, while a 2–3 MW flame lights the surroundings for tens of metres at night; without it the frame is
  darker at night than before — check FV), but from the middle of the cells where the fuel burns.
  Particle flames hung in the air where the roof was already gone (Artem's screenshot, 24.10). Smoke and sound — at the
  client, from the `S2C.FuelFire` packet — the state of the crater (`BlastEffects.fuelFire` → `FireSmoke.fuel`,
  `FuelBurn`): the hearth `FireSmoke.pool` above the middle of the burning cells (heat and soot of the fuel,
  `FuelPool.share` by age from the strike's tick × the share of cells that by the law are still to burn, which have not
  run out before their time; if it lay on a smaller area — `WeaponSpec.pool(lit)`, as at the server), the Briggs plume
  and the flame's light on the smoke (`FxLights`), the hum `CraterFire` there too; a new state of the same crater
  (glass, filled in, flooded) carries the hearth and the hum over and cuts the area; when everything is over the hearth
  goes out (`FuelBurnTest`; review FV: earlier the place and the time came from the first packet). With
  `blast_effects` = false — none of this (fire on blocks belongs to the world; the mod has no smoke of its own above
  it). `FireWatch` does not count fire on fuel cells toward the fire of a nuclear city (`FuelCells`: the cell's column,
  not above it — fuel drips only downward; otherwise it would also go into the detonation fires' hum, on top of the
  crater's hum). If the packet did not arrive (a player who joined later, beyond the packet's range) — there is no
  hearth; on blocks — the world's fire. On water there is no fire in the crater (the server decides: fuel falls on
  water — there are no cells).
  The visible flame light (the body of the oil far away, light on the smoke and the world) — from luminous soot
  (`FirePlume.flameLight`: 140 kW/m² of the luminous part per Mudan–Croce, flicker ⚠ 200 K — ~0.1 of the white wall,
  orange), not `FLAME_K` of the mean flow.
  The plume bends with the wind per Briggs: at a ground wind of ~5 m/s a 2 MW fire lays it flat — ~3.5 m of rise over
  the first 10 m, ~11 m over 60 m; the “column” of 3.4.1 from a hand-made table does not exist in such wind. The plume's
  wind and stability are taken at the fire's moment, for the whole burning. Fuel smoke and sound are one-off events:
  after a Flashback replay seek (`Replay`: a one-off event from a seek is the past) they do not come back; fire on
  blocks — as recorded.
- Smoke over the fires of a strike — the client's cloud (`fx/FireSmoke`, drawn by `SmokePuffs`): hearths — the fuel of
  craters and the substations' oil. The Minecraft fire on blocks, including one a strike ignited, the mod does not smoke
  or inspect: it is the game's fire, and its look belongs to vanilla or the mod that runs the burning (Artem's decision,
  26.10: the mod does not change what is not its weapon or its mechanic, and keeps no code for individual mods; earlier
  smoke rose over any fire in draw distance, with Burnt — two smoke layers). `fx/FireWatch` watches fire only at nuclear
  detonations (the hum of a burning city, `NukeAfter`). The Briggs plume (`fx/smoke/FirePlume`: rise along the path, the
  final one by the stability class, spreading by Pasquill–Gifford and by rise, the soot — its own for the fuel), in tube
  pieces (`SmokeGrid.Sink.tube`: σ and soot per metre at each end) — drawn by the same tube segment as the projectile's
  trail (`FarFlightView.line`: far away as a ribbon, large — as puffs, `SmokePuffs.tube`), 10 pieces at any range
  (`FireSmoke.lay`: soot per metre of axis — ṁ/u × path/axis; at the hearth the axis is steeper than the path; empty
  pieces are also handed out — the piece's number is held by the puffs' seed; the smoke's edge — by the hearth's history
  inside the piece, `FirePlumeLayTest`; earlier, beyond 256 m from the eye — 4 segments of ≤ 12 puffs each at full
  opacity, σ of the segment's middle: in calm stable air far away — beads, `PlumeContinuityTest`); it grows from the
  hearth at the wind's speed; an extinguished one lets it go downwind. Hearths whose plumes merge (d ≤ 0.6·(Δh₁ + Δh₂),
  Briggs entrainment β = 0.6) are one fire (`FireSmoke.merge`/`fires`): rise and σ — by the heat of the whole fire,
  soot — its own (a house on a chunk seam gave two to four weak plumes — a low band, `FireMergeTest`).
  Hearths — substation oil and fuel in blast craters (`FireSmoke.pool`, share `CloudModel.poolShare`; its plume, light
  and heat are here, not at the `FarBlasts` event). The plume is no longer than `FireSmoke.REACH` (~770 m) downwind. The
  wind is taken at the hearth's height for the whole plume (no wind shear with height, ⚠ simplification). A nuke's storm
  has its own (`FireStorm`) and is not changed by this.
- Our shaders (`shaders/core`) that Veil, carried by Sable, recompiles through its own GLSL parser (glsl-processor
  0.2.3): the statement `n++;` comes out without “;”, and in every client run's log — ERROR “Failed to recompile vanilla
  shader” (the shader stays as it was). Increment as a separate statement — `n += 1;`; `i++` in a `for` header does not
  break the parser. It prints a conditional expression nested in another without parentheses: `a * (c ? x : y)` comes
  out as `a * c ? x : y` — a different expression, and the shader silently computes the wrong thing (the light of the
  dust wall on the pall stopped depending on density and came out thousands of times brighter — a white frame; the
  neighbour weights in `fx_pall_composite` came out not bilinear). `?:` — only as a whole right-hand side, a return
  value or a call argument; inside an expression — `if` or `mix`; `ShaderSourceTest` checks both rules. It also checks
  that every `uniform` of a `core` program is declared in its JSON and that the JSON has nothing extra (except
  `sampler3D`, whose layer the engine sets itself): it does not set undeclared vanilla uniforms — a new map or number,
  added to one pass and forgotten in another (`fx_pall`, `fx_pall_composite`, `fx.vsh`), would read someone else's
  texture or zero. What a shader has become after Veil is visible by running it through
  `GlslParser.parse(…).toSourceString()` from glsl-processor (a jar inside Veil's jar inside Sable's jar).
  `ShaderSourceTest.veilPrintsEveryProgram` runs every `core` program this way (glsl-processor 0.2.3 is in the tests): if
  it fails to parse or crashes — an ERROR “Couldn't load vanilla shader … skipping” in the log, and Veil does not
  recompile the shader. That happened to `fx_pall` in 3.7.0–3.8.0: a “#” in a comment (“review #441”) the parser takes
  as a node label; with a label, printing takes the hash of each function, and the hash of a `return;` without a value
  is an NPE (`GlslReturnNode.hashCode`). Imports — only `#moj_import <incoming:name>` from `shaders/include`
  (`everyImportIsAnInclude`): a quoted import is looked up by NeoForge next to the shader; Forgified Fabric API (Fabric
  Rendering, present in the pack) prefixes the shader's namespace once more (`incoming:incoming:…` — the program is not
  created; `fx_pall_foot` did not load that way in 3.8.0); and Veil glues it to the file's own path.
- Smoke of short ejections — `fx/smoke/Puffs` (the cloud source, drawn by the `SmokePuffs` puffs): an ejection is a
  thermal `Thermal` of its own heat; its life — by the thickness across ({@code CloudModel.SEEN}); thermals of one
  buoyancy and radius (to within √2) are one calculation (`Puffs.thermal`: ~2.7 ms per thermal). Burning debris in
  flight is drawn so (`Exhaust.debris`: a tree's face `FirePlume.BLOCK_WATTS` per each 0.1 s of path; a cold piece dusts
  behind only if it is loose — `BlockTags.MINEABLE_WITH_SHOVEL`: stone and planks have no detachable dust; earlier any
  piece dusted, BLASTSEQ D12) and the smoke wisps of a nuclear flash (`FlashAftermath.Smoke`: pyrolysis — the mass of
  volatiles by light above a threshold and the heat of gasification of wood; the wave sweeps the wisp away — the
  ejection's lifetime). An aerosol with its own attenuation goes as dust with the same attenuation (`SmokeGrid.dustK`).
  The puff frame at the source is one — by the growth of the youngest ejection.
- A knocked-out substation — a transformer-oil hearth in `FireSmoke` (`FireSmoke.Fuel.OIL`, ~3.6 MW), while the block is
  knocked out (`SubstationBlock.out`; for a player who joined later — by the block's random tick, `ClientHooks.substation`).
  Fire hearths — with fuel (`FireSmoke.Fuel`); Minecraft fire on blocks is not a mod hearth. The flames of hearths and
  burning debris light particles and puffs (`FireSmoke.lights`, `Exhaust.lights` → `FxLights`).
- A blast on water — `fx/smoke/WaterBlast` at the `FarBlasts` event (no crater dust: `CloudModel(…, onWater)`): size —
  Swisdak 1978, table 14 (charge and reduced depth; the safety margin reduced, est.), time — ballistics √(2H'/g). The
  nuclear analogy gave the Shahed only 14 m hidden by the ball, instead of ~78 m. The table's H and R are the visible
  plume, and the visible edge of the spray lies on them (thickness along the ray up to `CloudModel.SEEN`), not the
  middles of the jets: with the middles on H and R the dense jet was visible another 3–4σ farther, the Grad column came
  out three times wider than the law, and the salvo's camera at 30 m fell inside — an evenly grey frame (final pass
  3.7.0; `WaterBlastTest.everyWeaponThrowsAColumnOfTheLawSize`). A jet in one direction is a segment (`Sink.segment`:
  water of one instant at different speeds lies on a line) of round section, `WaterBlast.jet`; the jet's halo is
  `JET_HALO` in radius (est. of the shape), the middle of the fast one — at H − halo. R is the largest visible radius
  over the whole flight, and the spray goes farthest as it falls: the cone — by the envelope (the outermost fast jets
  land at 2H'·sin2θ, with the halo of the falling jet — R; at the apex the column is ~¾R); with a cone by the apex the
  curtain on the descent went out to 1.41R (review #445); the test — up to 2 × the rise. An elongated Gaussian layer is
  drawn as a stretched frame — a band with straight sides (the salvo's “sheets”); there are no spray puffs with
  σv ≠ σh except the flat ring (`jetsAreRoundSegments`, `WaterSalvoTest`). Segment puffs wait like point puffs
  (`SmokePuffs.hold`: merging and the fill budget; earlier a segment was drawn outside the budget), and each — with its
  own frame, reflection and wander, like tube puffs (`vary`): identical frames side by side look like a stack of coins.
  Surface and depth — by the client's chunk (`FarBlasts.water`); with no chunk — a contact charge on deep water; the
  jets start at the surface, not at a submerged charge.
  Foam and the wave ring — `WaterDecal` (group speed capped at √(gD)); as it falls, the water spreads in a ring of the
  base wave (the same law as the nuke, `WaterModel.surgeReach/surgeTop`: the visible edge of the ring and the top of the
  layer); the ring's water dust — droplets of 50 µm (Stokes, 0.3 m/s; they settle through the layer's bottom,
  dM/dt = −v·M/(√(2πe)·σv), `WaterBlast.mistLeft`; with the droplets of the 0.5 mm column the ring was thinner than the
  visibility threshold). Upward per Briggs the layer grows only as far as the eddies at the surface hold droplets: the
  Rouse number v/(κu*), u* = √C_D·U₁₀ (Large, Pond 1981), suspension — at P ≲ 2.5 (van Rijn 1984),
  `WaterBlast.suspended`; at 6 m/s P ≈ 3.6 — the layer does not grow (earlier it grew, like a gas, to 28 m in 20 s — a
  wall of fog over the salvo); spray — as droplets of the grid with recomputed attenuation (spray puffs — atlas `spray6`
  at the source with spray, `Source.sprayClubs`, if spray is more than smoke); clods — particles `BlastEffects.splash`.
  After the last jet returns, `Source.sprayClubs(seconds)` switches the residual mist to `puff6`: fall bands of `spray6`,
  stretched over the thin layer at the water, looked like white ribbons above the grass. Ordinary foam — lace in the
  decal's noise, no more opaque than 0.6 (presentation; flag in the profile's B); nuclear whitecaps stay as they were.
  The front of an ordinary wave — `waveRadius`; its dark edge is smooth, without a rise to the column's radius.
- The blast family by target — `fx/smoke/BlastTarget` (front F, physics.md 25.10): ground, solid, a slab over a void,
  water, air — from what the client knows, not “roof or not” (Artem's decision, 23.10): the packet's material;
  connectivity and support — the packet's support `S2C.Blast.Support`: the server computes it (`SlabBreach.support`: the
  same `locate` and `march` as the breach rays, with one ray along the face's normal inward into the obstacle — not along
  the course: the rays along the normal breach first, and along the course the path is t/cos θ; a missile at 45° over a
  2 m stone deck “did not breach” what the rays cut through, `BreachGameTests`
  `obliqueMissileOnTwoMetresOneDecision`; `Warheads.support` — the blast for a rule without events; the packet also has
  the normal and `lift` — the charge lies at `pos − normal·lift`), the packet goes out before the rays. The client has no
  slab rule of its own: review 1 of front F caught the mismatches (thickness from the blast point, not from the face;
  `SurfaceBlocks.solid` instead of `SlabBreach.cell`) — the Grad over a metre of planks showed a breach the server did not
  make. The match is held by the GameTest `BreachGameTests` (packet support = breach: planks and stone, half-blocks, a
  crown over the exit, obsidian, an oblique strike). If there is no support in the packet (a repeat before the field
  arrives, a blast without a server, a charge not at a face) — by the material's substance (`BlastTarget.Matter.loose`),
  the support is solid. The packet's support is contact: its family, mass and cold dust do not depend on the height
  above the relief (`overSurface`) (far away, a `FarTerrain` tile without the house made the roof's breach an air burst
  without mass — review 2 of front F, `BlastTargetSupportTest.serverContactOutranksCoarseHeight`); an air burst — only
  without support. The cold dust of what was destroyed at such a target is born at the blast point
  (`BlastTarget.atFace` → `GroundDust.crushed` with height 0); the relief — only for run-off; the wave's dust — from the
  relief heights as they are (review 3, `crushedDustStartsAtTheFace`). The mass of what was destroyed — the bowl law by
  `GroundMaterial.soil()` × the bulk density of the substance (for a slab — the breach √(R² − t²) × t); earlier any
  target — dry soil × 1600. The cloud's dust — this mass × the share of fines of the substance (`Matter.fines`: loose and
  planks — `DUST_LOFTED` 0.02, cohesive — 0.1 ⚠: the bowl in concrete and stone is a crushing zone; with 0.02 a strike on
  concrete gave a dust layer thinner than the ground); colours — three: the dust of the surrounding surfaces
  (`CloudModel.Blobs.ground`, the stem — `dustColour`), the target's dust (`Blobs.crushed` — `Matter` r, g, b), the
  cloud's dust — their mix by the share `CloudModel.capMatterShare` (by attenuation). The ball of products at an air
  burst — at the burst point (`heBallHeight`: 0.45 R·(1 − hob/R)), not a hemisphere. The ball's place is one for
  everything (`ballX`/`ballHeight`/`ballZ`: puffs, sprite, light and heat of `FarBlasts` carry the same wind as the
  smoke): the fuel ball grows from the ground, its centre rises exactly to 0.75·D by the end of burning
  (`fireballGrowsFromTheGroundWithoutJumps`); earlier the centre jumped when “which ball is bigger” changed (for a
  missile 3.7 → 17.5 m in 0.125 s), while the light and sprite stood at the strike point while the smoke drifted 10–20 m
  downwind (BLASTSEQ D01, `fireballAndItsSmokeShareOnePlace`). Up close (`BlastEffects.ejecta(level, model)`): ground and
  solid — clods/chips by the ejecta law (`Ejecta.pieces`); a slab — crumbs from the charge's face outward (the
  breach-ejecta law, ⚠) and a spall from the hole on the back face along its axis — the face's normal, on any course
  (`BlastTarget.spall`: the pressure pushes the slab, the Gurney sandwich is one-dimensional; the Gurney plug,
  `BlastTarget.plugSpeed`, ⚠ upper estimate; `BlastTargetSupportTest.spallLeavesThroughTheServerHole`); air — nothing
  (earlier clods flew even at a burst above the roof). Only its own share flies as chunks (`BlastTarget.clods`: for
  ground — `Matter.clods` of the ejecta; for solid and slab — the whole ejecta without dust); chunks + the curtain's grains
  + dust = mass (`EjectaTest`). The ground ejecta curtain — `fx/smoke/Ejecta` at the `FarBlasts` event (at any range,
  not only as particles): the same Housen law as the clods up close (constants — in `Ejecta`), 8 jets in a circle
  (azimuth and angle from ±10° — by the seed), each with 3 segments by share of volume (equal in logarithm from (0.2)³):
  the substance of one instant — on a chord; the section — the angle's spread over the path travelled, not thinner than
  ¼ of the crater radius; what has fallen (returned to the launch height) — is not drawn. The curtain's light — grains
  (`Matter.extinction` 3Q/(2ρd) → kg of mixture dust via `SmokeGrid.dustK` by the clods' colour): clods tens of
  centimetres across hardly hold light; their share (`Matter.clods`) — particles up close. Mass: in the air + on the
  ground = grains; ejecta + dust = the crater (`EjectaTest`).
- The puff's look — `SmokeGrid.Look` (smoke, the ejecta curtain, crushing dust, the air-burst cloud): the source tells
  the receiver `Sink.look(look, frame)` for its next puffs (the state until the next call; `SmokePuffs.Draw.begin` resets
  to smoke); a waiting puff remembers its look and frame (`SmokePuffs.FIELDS` 15, 16); only puffs of one look merge
  (`SmokeFillTest.differentLooksDoNotMerge`); spray — still by the puff's composition for the “smoke” look. Frames —
  `FxAtlas.Clubs` (`eject6`, `crush6`, `air6`: rows 96, 108, 120 of the 1024×8448 sheet — in the shader's frame step from
  `PUFF6_TOP`, `fx.fsh` takes them without its own numbers; maps — in the smoke's scale). The frame of the curtain and of
  crushing dust — by the phenomenon's life share (`FxAtlas.lifeFrame`, “life” in the json: all frames — the change of
  structure in them is the phenomenon: clods around the puff → dust); air-burst clouds — by growth (`growthFrame`, cut by
  “detail”, as for smoke: `turbulentLast` would have left the curtain's first two frames). Baking — `bake.py clubs-vdb`
  (`SOURCES.md`): the simulation near the ground is captured from above: the reflection in the floor (a cloud against a
  wall without friction is half of free) puts a dark band in the middle of the frame — the top shades the bottom; a torus
  (Dust Shockwave) does not capture as a clump.
- The light of a flash and of a conventional weapon's fire on the world, and the shimmer of the air — `fx/layer/HeatPass`
  (shader `fx_heat`) after the pall and before the squares: a copy of the frame (its own, linear), the pixel is written
  whole. The light of an ordinary ball — its own direct law L·R²/max(d²,R²)·exp(−σd)·max(n·v,0) (`heatBallGain` ↔
  `HeatPass.ballGain`, `HeatLightGlslTest`), without scattered and reflected light onto the world: Glasstone's thermal
  transmission does not give an angular distribution of visible light and gave a rise of light on the horizon after a
  kilometre. The nuclear `mushBallFace` does not change; the common `relit` — in `include/scene.glsl`. Nuke balls — in
  `fx_pall_composite`, conventional weapons' — here (`FarBlasts.heat`, a pool, substation oil, burning debris; Minecraft
  fire on blocks belongs to the game: there is no light or shimmer over it here). An engine's torch — not a ball but a
  jet (`HeatPass.Frame.jet` from `FarFlightView.lit`): core [0, core], flame [0, flame] (enclosing the core), tail
  [flame, Draper point] — even segments; light per metre — the part's brightness × its side area / length / π; direct
  light — the exact integral over the part above the face (`HeatPass.segment` = `heatSegment`, without subtracting close
  numbers — in the shader, float); attenuation by air — from the part's middle; far away it converges to the earlier
  ball of the same area (`HeatPassTest`). Direct-light shadows — for all the pass's sources, by blocks, not by the
  frame's depth: a window of occluders around the eye (`fx/layer/Solids`: full opaque blocks `isSolidRender` — with
  `canOcclude` a snow layer, a path, farmland, a carpet, slabs gave the ground nearby a full-cell shadow; 192×96×192,
  sections by modulus, as `OpenAir`; new ones — first zeros (and the place forgets the previous section), block reads —
  no longer than 1 ms per frame: sections in the radius of the frame's sources (nearest to fire first, up to 32, in turn)
  — no more than half, the rest — to new ones and 8 sections in a circle from the eye outward, at least one per frame
  (otherwise sections at fires used up the whole budget and the window never filled); a section of only air or only
  full blocks — by palette (`maybeHas`), without a walk; unpacking — dense until the first load; texture block 15) and a
  ray through its cells (Amanatides–Woo, `HeatPass.blocked` = `heatBlocked`, up to 160 cells; per step — neither whole
  divisions nor branches: the window by modulus — by texture repeat (`textureLod` at the cell's middle), the step axis —
  `step` by the near face; the remainder of division by 192 and the axis choice via `if` at each step cost more than a
  sample; a two-level pass with 4³ cubes on the GPU came out more expensive (near the ground the cubes are almost always
  occupied, nested loops diverge across threads) — do not restore without measurement; a shadow is not searched at a
  source whose direct light is less than 2 % of the whole illumination of the face (sky and fire: `WEBER`, Blackwell 1946
  contrast threshold); the point's own cell — its face). The depth of the frame does not decide shadows: behind the
  visible face — a house's thickness or the void behind a pole (the first variant of PR #399 let light through a solid
  house at an angle to the view). Beyond the window there are no shadows. For a ball — the visible share of the disc
  (`HeatPass.ballSeen` = `heatBallSeen`: Gauss–Chebyshev quadrature of the 2nd kind along the vertical diameter, nodes 0
  and ±0.707r, weights ½ and ¼; the lower edge — two rays: the middle and the lower node if the middle is visible,
  otherwise the upper — shares 0, ¼, ¾, 1; a single ray to the middle at a hill's edge blacked out the whole ball although
  its top was visible — the ground near the camera darkened entirely in the substation frames). For a torch the ray goes
  to the middle of its direct light by the parts above the face; the jet ends where the axis goes into the ground
  (`FarFlightView.lit`: the first block under the cut by the `MOTION_BLOCKING` predicate, downward from the nozzle — the
  height map under a crown or a roof above the nozzle; without this the wall at the launch pad took three times as much
  light from the part that went into the ground as from the part above ground; ⚠ there is no glow of the wall jet).
  Shadow rays — in their own pass at half resolution (`fx_heat_shadow`: sample t — pixel 2t, output RGBA16F — visibility
  codes of balls at 2 bits each, of torches at 1, the distance to the world at the sample); the light pass (`fx_heat`)
  takes for the pixel, from the four nearest samples, the one whose distance to the world is nearer (an object's edge
  does not take the background's visibility); common functions — `include/heat.glsl`. Occluder-window sections load in
  one batch per frame through a pixel-unpack buffer (PBO, a new one per batch): directly from memory into the texture
  that the previous frame reads, the load waited for the GPU (+6.7 ms of the layer in the first second of a night blast
  at the same CPU time), and sections whose blocks did not change (the fingerprint of the palette and storage, fmix64)
  are not re-read at all. Over the full frame, the rays at a Grad salvo at night cost 2.7–3.3 ms of GPU against 0.27
  without shadows. Light weaker than the sRGB step at black (3e-4) does not search for shadow.
  The shimmer — along hot-gas columns on their own axis (`HeatPass.Frame.hot/plume` — upward; `exhaust` — along a
  nozzle's jet from `FarFlightView.lit`: the excess temperature behind the flame and the speed behind the core ∝ 1/x, the
  radius — `Flame.widthAt`): the arrival angle per Kolmogorov σ = δn·√(5.82·L/ℓ)·(ℓ/D)^(1/6) (`hazeAngle`; D — the
  spot the eye resolves in gas: the pupil or an arc-minute × distance); the eye averages fast eddies (`hazeSeen`); the
  shift — in fractions of an arc-minute, not a pixel (`resolve`: presentation; a pixel at a 70° field — 4 minutes; through
  binoculars — a pixel): the earlier δn·√(L/ℓ) in pixels gave 0.01–0.1 px — the shimmer was “nowhere” (FX 23.10). The
  pattern — noise octaves from ℓ; the gas carries it by flow across the map (two layers offset by half a cycle in
  `HeatClock`: time in float with a pattern shifted by place would tear the jet into grain). Copies — `haze*` in
  `fx_heat.fsh` (`HazeGlslTest`), visibility — `HazeTest`. The pass runs only where it can change a pixel
  (`HeatArea`): a ball or a jet reaches a sphere where its light (L·r²/d², a jet q·length/D² from its axis) still moves a
  display channel by half an 8-bit step at the most sensitive pixel — the one equal to the face's open-sky light, the
  darkest face shade and channel (`relit` adds light × albedo; brighter pixels move less) — hot gas and a conventional
  front their own volume; the pass, its frame copy and its shadow rays run in the screen rectangles of those spheres
  (merged when the union costs no more), shifts clamp to `SHIFT_REACH` (32 px; shimmer is sub-pixel, conventional fronts
  at most 3 px) and the copy covers that margin; a nuclear front takes the whole screen. No rectangle on screen — no
  pass: a pool fire 3 km away, nothing lit on screen, cost the full-screen pass 0.94 ms at 2560×1600 (F3, 3.9.1).
  `HeatAreaTest`: no pixel below the threshold changes (relit replica on every 8-bit value), every visibly lit pixel of
  random balls and jets lies inside, off-screen and faint sources draw nothing. The nuke's heat is there too
  (`client/nuclear/NukeHeat`): the shock-wave front — up to two spheres (`HeatPass.Frame.front`), the jump in the
  refractive index by the model's pressure at four heights of the sphere (Rankine–Hugoniot, Gladstone–Dale), the path
  through a thin shell — a Dawson function, the deflection — the average across the pixel's width where the ray crossed
  the front (at a tangent the path derivative is infinite: without the average over the pixel the ring would run as
  sparks of single pixels), no more than √(2δn) per crossing (laws — `nuclear/model/ShockOptics`, a copy in
  `fx_heat.fsh` — `front*`, checked by `ShockOpticsGlslTest`); the gas above the ball and the cap — `hot` of the ordinary
  blast ball's law; above the storm — `plumes` (the field of hearths: a column as wide as the zone, eddies of one hearth;
  with one zone radius the eddies would come out hundreds of metres and there would be no shimmer). A front shift below
  `SHIFT_MIN` is not taken, and a pixel without shift is written as it was (`texelFetch`): with a nuke in progress the
  pass runs every frame, and without this, frames where nothing refracts would shift by fractions of a sample. The
  pressure on the front's sphere at an air burst — the falling wave (`HobBlast.freeAirPsi`); at the ground in the Mach
  region — the Mach stem: `Detonation.overpressureKpa` above the ground is an upper estimate (as for the ground burst);
  with it the ring came out a third stronger.
  The pass's GPU time — in the layer's line (`FxLayer.describeTiming`: heat pass) together with its sources.
  The front of an ordinary blast — the same `front` (`FarBlasts.front`): the sphere `nuclear/model/ChemicalFront`
  (arrival — ∫dr/U per Kinney–Graham and Rankine–Hugoniot, a table per charge, `ChemicalFront.cached`) while it is beyond
  the fireball; the thickness — `DECAY_SHARE` × t₊·U at the front; one pressure over the whole sphere (on the ground —
  the reflected hemisphere, in the air — the free one); there is no threshold other than the pass's `SHIFT_MIN`. From
  the same table come the sound and the impact on the body (`BlastEffects.Timeline.arrivalTicks`): up close the front is
  faster than sound, and at a constant speed of sound the ring would pass the eye a tick before the shove.

- Front of an ordinary blast over grass — `fx/FrontLitter`: at the moment the front arrives (the delay of a
  `Fragments` piece — arrival by `ChemicalFront`), grass blades, sod and crown leaves are torn off where u* behind the
  front is above the sod threshold (`nuclear/model/BlastLitter`, stripping law `DustModel.friction`, about 1 psi; the
  ring runs from the first to the last stripping radius: under an air burst it starts farther out), and they fly off at
  the throw speed, resisted over the positive phase (`BlastLitter.kick`; the phase is shorter than a tick — integrated
  in small steps, a piece starts with it already in still air) — and land within half a metre: a tuft of foliage slows
  down by up to 1.7 m/s per tick, and the air behind the phase shifts by decimetres. The upward launch is boosted as
  presentation (19–35° to the surface, throw speed magnitude kept); the size and number of pieces are also
  presentation (`PER_M2`, `MAX`). The throw is an exact step at the wind of the middle of the step (`BlastLitter.STEPS`
  32, second order: less than 0.2 % off the small-step integral; the previous 400 semi-implicit steps for 900 pieces
  took 4–8 ms in the tick of the blast's birth). `FrontDust` shows coarse grit from the cover (size estimate — 120 μm
  `DustModel` bin, settling 0.745 m/s): the phase ends the presentation, and what was lifted falls out as exp(−v·t/h).
  Mixing layer height 1.3 m, vertical thickness 0.5 and puff thickness no more than 0.8 are presentation (0.08 — the
  ring is not visible; 2.8 per puff — a brown wall across the frame). The reserve for the source area is finite and
  does not grow when the puff expands by Briggs. A puff at the leading edge, still compressed by the front, takes from
  it no thicker than the grown layer and `EDGE_TAU` (otherwise an opaque point), and stands on the ground: the middle
  is at its own σ vertically. The grid of patches is one step along and across the ring (`STEP` 5.6 m, puff σ = 0.6
  steps), no more than `PATCHES` (400) per blast and `ALL_PATCHES` (800) visible across all blasts — the real limit
  (`FrontDust.share`): beyond it, the blast whose share is larger is thinned first, the oldest of them, then the
  farthest. The thinning level is half as often along the arc or by rings (at least three groups per ring); a group is
  one patch at the first by number (its puff grain is unchanged) with the same mass at the centre of mass and σ per
  group step, so the ring stays covered (`FrontDustTest.sharedLimitHoldsWithoutLosingTheRings`: volleys of up to 40
  blasts, every spot of the ring within 2σ of a puff). If there is no one to thin, a new one draws no dust. The previous
  limit “the new one gets what is free, but no less than a quarter” was violated. A patch whose puff is more
  transparent than 0.004 (`SmokePuffs` does not draw it) gives up an empty puff, and the source ends with the last one
  (~10 s, not 20 s of settling down to 10⁻⁵). The reason is frame cost: `SmokePuffs.merge` merges a source's puffs
  pairwise every frame; the previous 24 × 64 grid (864 puffs for a Shahed) cost ~1 ms per blast, and a volley of 8 —
  the effects layer 15–16 ms against 3–4 on main. Colour is albedo from the screen colour (`SmokeGrid.dustAlbedo`), as
  for all sources: using the screen colour instead of albedo gave a grey thick layer with dark puff centres
  (“doughnuts”); the colour is dry light soil dust, ω' ≈ 0.9 (est.). Wind is the air's, at the moment of stripping; the
  thin puff trail does not outrun the front: the tick fraction, the sod threshold and the arrival of `ChemicalFront`;
  no dust before arrival. This is presentation of the details of the stripped cover, not a change of mass and settling
  of the `GroundDust` layer; bare ground stays with it. A piece's wait is rounded up so that it does not fly out before
  the front. The refraction of an ordinary front is boosted as presentation in pixels after projection: weak — up to
  24×, strong smoothly capped at 3 px for the sum of ordinary fronts (`chemicalFrontScale` in `fx_heat.fsh`,
  `ChemicalFrontGlslTest`). Boosting dn itself before the optics turned the ripple into a white dome of glare drawn into
  it. The nuclear contribution is separate; the physical jump, radius and time of `FarBlasts.front` and the nuke's
  refraction do not change. The ordinary blast front in the `HeatPass` pass is `chemicalFront`: it does not take the
  nuke's slot.

- Condensation of an ordinary blast — `fx/smoke/Condensation`: heating at the shock, isentropic expansion behind the
  positive phase, and saturation by Magnus (15 °C), with the heat of condensation. The threshold RH=0.3 and the static
  hemisphere from the first frame are not used. The negative phase is a continuation of Friedlander with b=1 up to
  8 t₊, with a smooth end and the rarefaction limited to half of atmospheric (est., an unverified experimental
  approximation for TNT); the former outer reach at 20 kPa is kept as a conservative estimate of the shown area:
  equilibrium saturation without a droplet growth time does not give a reliable far edge, especially at RH=1. Droplets
  — as shell puffs, no farther than the current front, even at the 4σ edge. The nuclear Wilson cloud does not use this
  calculation.

- Ordinary blasts use `BlastTime`: a monotonic counter on `ClientTickEvent.Pre`, birth in the same counter, tick
  fraction from the frame. Post-tick counters of `FarBlasts`/`SmokeGrid` could age an event by a tick right at creation
  from another Post listener: the Shahed's front was already at 26 m on the first frame of age 0. `BlastEffects.Timeline`
  does not perform age 0 a second time in the same tick. The counter does not depend on world time sync packets; the
  nuke clock and the shared `Effects` queue are unchanged by this.
