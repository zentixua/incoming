# Incoming and shader packs as one picture (proposal)

Status: proposal for Artem's decision. No implementation in this document's PR. Code references are against `main`
at the time of writing; `J/` is `mod/src/main/java/ua/zentix/incoming/`. Pack sources were read from the exact files
the packs ship (`*.pw.toml` hashes); Iris facts from the jar the packs ship (1.8.14-beta.1 for 1.21.1).

## 1. Verdict

Under a shader pack Incoming draws its smoke, fire, mushroom, dust and trails after the pack has finished the frame
(`AFTER_LEVEL`, after Iris's final pass). That choice keeps every effect visible and identical under every pack, but it
is also the root of every seam: the pack never sees our matter, so it cannot fog it, light it, shadow with it, hide it
behind its clouds or put its clouds' light on it, and we never see the pack's clouds, sun or fog.

One picture needs data to cross between the two in each direction. Iris already has a public, pack-side door for it:
a pack may bind any texture registered in the game's texture manager by resource name
(`texture.<stage>.<sampler> = <namespace>:<path>` in `shaders.properties`). Photon uses this door today for the vanilla
cloud texture. Incoming can publish a small live texture (`incoming:shader_bridge/…`) every frame: fireball and flash
lights, where our columns cut the clouds, our smoke volumes for shadows, our wind. A pack that reads it adds a few
dozen lines to its own lighting and cloud code; a pack that does not is unaffected. No Iris mixin, no change to how
Incoming draws today.

Recommended order (section 8): one mod-side fix that needs no pack (the nuke's haze colour, the largest single seam in
the frames), then the bridge with a reference implementation in MakeUp Ultra Fast (LGPL, the only pack whose license allows a modified public copy everywhere), then
Photon (Artem's pack, through an upstream pull request; the author merges outside pull requests), then Complementary through Euphoria Patches (the only
clean route; it needs the add-on author). BSL and Solas get only mod-side fixes: their authors reserve all rights.
Holes punched by every projectile are not proposed: reality has no visible hole behind a drone or missile (section 4).

## 2. Shader packs in the two packs

| Pack (file) | Open Skies (public) | Open Skies friends | License (shipped file) |
|---|---|---|---|
| Photon v1.4 (`photon_v1.4.zip`) | yes | yes; Artem plays it with `CLOUDS_SCALE=1.0` set in his own instance (the packs ship no shader settings, players get the default 10) | Photon Shaders License Agreement, all rights reserved except the grants below |
| Complementary Unbound r5.10 + Euphoria Patches 1.11.2 | yes | yes | Complementary License Agreement 1.8; Euphoria Patches License 1.1 |
| MakeUp Ultra Fast 9.6a | yes | yes | LGPL-3.0 (`LICENSE` in the zip; Modrinth `LGPL-3.0-or-later`) |
| BSL v10.1.8 | no (excluded: author forbids redistribution) | yes | All Rights Reserved (Modrinth); no license file in the zip |
| Solas V3.9 | no | yes | All Rights Reserved (Modrinth); `drm/drm.readme`: "only two official download sources: CurseForge and Modrinth / If you see this file in any other shaderpack, know that it contains STOLEN code from Solas" |

Neither pack selects a shader pack (`iris.properties` absent, Artem 23.10). Both are distributed by packwiz links,
GitHub only; the public pack's README says it is not monetised, and its rules require a license pass before any
Modrinth or CurseForge upload because Photon forbids redistribution there.

What each license allows (exact clauses):
- **Photon**: use; "Modify Photon Shaders for personal use"; "Redistribute modified or unmodified portions" except
  "on platforms that provide direct or indirect monetary compensation to creators (e.g. Modrinth, CurseForge) without my
  explicit written permission" and except for money; "Redistribute Photon Shaders as part of a (commercial) Modpack";
  the license file stays in every substantial copy. Modification is granted only for personal use; redistribution is
  granted for "portions", excluding platforms that compensate creators "directly or indirectly". Whether a whole modified
  pack is a "portion" and whether GitHub (GitHub Sponsors) is such a platform is unresolved; we do not read it as
  allowed. Modrinth, CurseForge and the Incoming jar (published there) are excluded outright. The clean routes are the
  upstream merge or the author's written permission.
- **Complementary 1.8**: a modpack may include the pack only unmodified ("must not be modified in any way, including
  simple variable changes") and only through Modrinth/CurseForge systems (1.2c, 1.2d). A Modified Pack may be
  redistributed only under a different name, visibly different in daytime overworld scenes, with "a significant amount of
  human-written code additions that noticeably contribute to the final visuals in multiple common gameplay scenarios"
  (1.3d, 1.3f), and under no other license terms (1.3e). A bridge that acts only during Incoming strikes fails 1.3f's
  "multiple common gameplay scenarios" whoever writes it, and our pack-side code would be written by agents.
  2.1 forbids using code or footage made with the pack to train AI models (reading the source to integrate with it is not
  training, but this clause is why we never feed its code or frames into any training set).
- **Euphoria Patches 1.1**: extends the Complementary license; a Modified Pack built on Euphoria Patches may be
  distributed "exclusively through a patching mechanism functionally similar to the Patcher Mod", with credit, the
  license file, and a name at least three letters different; the visual-difference requirement is waived (2.3). The
  human-written requirement of CLA 1.3f still applies through inheritance (the Euphoria license cites CLA v1.6, the
  pack ships CLA 1.8). Its modded-support library
  (`block.properties` sources) is MIT.
- **MakeUp (LGPL-3.0)**: modify and redistribute anywhere, including Modrinth, under the same license with source.
- **BSL, Solas**: no grant; only personal use.

Artem allows studying Photon, Complementary and Bliss sources for techniques without 1:1 copying
(open-skies-friends `docs/team/media.md`). This proposal follows that: it names where pack code would change, it does not
copy pack code into Incoming.

## 3. How Incoming draws under a shader pack today

- Every Incoming effect (particles, smoke billows, trails, plumes, the pall and mushroom volume, dust wall, glare,
  bloom, heat shimmer) is drawn in `AFTER_LEVEL` by the effects layer (`client-fx.md`). Under Iris that is after the
  pack's final pass: the frame we draw on is already fogged, exposed, tone-mapped and colour-graded by the pack.
- The layer reads world depth (`SceneDepth`); a pack's sky, clouds, volumetric fog and rain write no depth there, so for
  us they are "sky at infinity". Our matter is always drawn in front of the pack's clouds and fog.
- Our light on the world under a pack: the mushroom ball relights the pack's finished pixel (`MushroomField.relit`,
  pixel divided by open-sky light; limits recorded in `client-fx.md`: the pack's sun shadows keep their sharpness under
  the ball's light, foliage pattern vanishes in the overexposure). Conventional fireballs relight the finished frame the
  same way (`fx/layer/HeatPass`, fed by `FarBlasts.heat`, no shader-pack condition) and light our own particles
  (`FxLights`).
- Data crosses only from the pack to us through the finished frame (and only under the constraints of review #671, see
  5.1); there is no channel for the pack's sun colour, fog curve or cloud opacity. Seams that need those wait for 5.8.
- Things already right: lamps and blackout use block twins, so packs light lamps by block id (`grid.md`). The nuke's
  vanilla fog pulled in close (`client/nuclear/NukeSky`) is honoured by Complementary with Euphoria Patches
  (`MODDED_FOG_SUPPORT`: "honour vanilla fog pulled in closer … by a mod"); Photon reads vanilla fog only in the Nether
  (`fogColor` in `nether_color.glsl`), so in the overworld it ignores both distance and colour.
- Drawing our effects inside the pack instead (as vanilla-shaded geometry before the pack's passes) was tried and
  rejected: the pack's fog and "eyes" handling ate the plumes (`PlumeRendererTest.fireIsNotDrawnThroughTheShaderPack`).

Frame evidence, release 3.14 scene bench, same scenes under no pack, Photon and Complementary (100 strikes, day;
`mod/run/stand/out/rel314-final`, sheet in the mission folder):
- the pack hazes the ground at the distance of the smoke (Complementary blue-grey, Photon whitish); our smoke at the
  same distance keeps full contrast and its own grey: it reads as laid on top of the picture;
- the pack's sky and clouds are lit by its golden or white sun with soft shading; our smoke has the same neutral grey in
  all three frames;
- Photon's clouds cast moving shadows on the ground; our 300-block smoke cloud casts none.

Frame evidence, nuke under Photon with Artem's setting (`tools/client_scenario.sh mushroom shaders`, 15 kt at 10 km,
viewer 200 above ground, current `main`; sheet `photon-mushroom-sheet.jpg` in the mission folder):
- by day the distant mushroom is saturated vanilla-sky blue against Photon's pale sky, at 20 s and 70 s: the hazed
  matter's airlight is vanilla's sky tone (`nuclear.md`, `pallAirLight`), and at 10 km haze dominates the cap;
- the cap is drawn over Photon's clouds;
- at night Photon's clouds near the fireball stay unlit.

## 4. "Projectiles pass through the clouds and punch a hole"

- **Source of the claim.** No Minecraft mod, pack or project named Opencraft with this effect was found (Modrinth,
  CurseForge, GitHub, web search; the OpenCraft projects found are Minecraft clones and servers). Nearest real things:
  cloud mods with their own renderers (Simple Clouds with its Iris bridge add-on, Better Clouds), and WindLink with
  PlunderEngine, which publish a live wind field as "MCWIND channels" that Euphoria Patches reads (Euphoria's
  `shaders/mcwind/mcwind.glsl`: "A MCWIND provider replaces the contents of this file at pack load";
  `shaders.properties`: `mcwind.field = true`). WindLink is for Minecraft 26.x, All Rights Reserved; it is the precedent
  for a mod feeding a shader pack, not a dependency for us.
- **Reality.** Aircraft open "hole-punch" and canal clouds only in supercooled altocumulus, by freezing, over tens of
  minutes (Heymsfield et al., 2010, *Bull. Amer. Meteor. Soc.* 91, 753–766); a drone, cruise missile or rocket of our
  size leaves no visible hole in an ordinary cloud deck. A large rocket launch through a thin overcast does leave a hole
  around its plume, and a nuclear fireball and stem rise through any deck in their way and spread their cap above it
  (Glasstone & Dolan 1977, ch. II, cloud rise).
- **Who reaches the clouds.** Cloud decks: Complementary 192 (vanilla height; a second Unbound layer at 384 when
  enabled, off by default), MakeUp 319–590, Photon cumulus 120 m above sea level at the default `CLOUDS_SCALE` 10
  (y ≈ 183, what players get) or 1200 m at 1 (Artem's own setting, y ≈ 1263), altocumulus 3200 m and cirrus 6000 m
  (scaled the same way). Game cruise heights (`WeaponSpec`): Shahed and
  loitering munition 45 blocks, cruise missile 12, B-2 170 above the target. A Grad rocket leaves a 50° rail (`entity/LauncherRack`) with
  its exit speed solved per range; its apex is about 0.3 × range (review probe: 600 → 182, 1000 → 309, 1500 → 473 blocks
  above the rail). So at the default game scale the nuke, the ICBM, the B-2 and Grad arcs beyond ~450–500 blocks
  of range cross Complementary's and Photon's default decks (MakeUp's beyond ~1000); at real scale the Shahed cruises at 2 km, above Photon's deck.
- **Verdict.** Proposed: the nuclear stem and cap open the deck (radius from the mushroom model at the deck height,
  drifting with the wind afterwards), the ICBM launch column opens a narrow hole (est.), and both light the deck from
  below. Not proposed: holes behind drones, missiles and rockets (concept 4: never add what reality lacks).

## 5. Interaction points

Effort is our hours (mod side, then per pack), excluding review. "Fragility" is what a pack update can break.

### 5.1 Haze on our matter
- Conventional smoke: its haze is applied as opacity over the pack's finished frame (`FarSprites`, `SmokePuffs`:
  L = L₀·t + frame·(1 − t)), so it already fades into the pack's own colour. What differs from the pack's ground is the
  amount (our Koschmieder visibility, 20 km clear, against the pack's own fog curve) and the light on the smoke (sky tint
  from vanilla `getCloudColor`). No colour taken from the frame changes either; both wait for 5.8.
- Nuke pall and mushroom: their airlight is vanilla's drawn sky (`pallAirLight`, `nuclear.md`). Under Photon by day a
  mushroom at 10 km comes out saturated vanilla blue against Photon's pale sky (section 3 frames): the largest single
  seam found, and a break of the "never broken under shaders" rule rather than a refinement.
- Target: the cap at 10 km takes the colour of the sky beside it in the pack's own picture.
- Mechanism, no pack change: under a shader pack, take the airlight from the pack's sky in the finished frame. Sampling
  the frame is what review #671 removed: the sun and nearby blocks leaked into the cap. A design must answer it: sky
  pixels only (no world depth), sun and moon discs excluded by their direction, a coarse per-direction sky map filtered
  over time, never the pixel behind the cap; and keep `NukeExposureTest.hazeNeverShowsWhatLiesBehindTheCap` and
  `opaqueCapNeverShowsTheSunNorItsNeighbours`. Without a pack nothing changes (vanilla first).
- Effort 8–12 h, no pack hours. Frame cost est. under 0.05 ms (a small sky map). Fragility: none (reads only the frame).
  Accepted by the same Photon frame pair, before and after.

### 5.2 Our matter in front of the pack's clouds
- Seen today: the mushroom cap above a cloud deck, and trails above it, show through the deck from below.
- Target: the deck hides what is above it, in proportion to its own opacity.
- Mechanism: only the pack knows its cloud opacity and distance per pixel. Needs section 5.8 (composite in the pack).
  Without it there is no honest fix: guessing the deck from the frame would invent occlusion.
- Effort: part of 5.8.

### 5.3 Fireball and flash light on the pack's clouds and world
- Seen today: at night the fireball lights our own glow sprite at the vanilla cloud height (`FarBlasts.sky`, y = 192), which matches Complementary's deck but hangs in empty sky under Photon and MakeUp; the pack's real
  clouds stay dark. On the ground our relight (`HeatPass`, `MushroomField.relit`) works on the pack's finished pixel,
  without its materials or shadows.
- Target: the underside of the pack's own clouds over the blast flares orange and fades with the ball; the ground and
  houses take the light with the pack's materials.
- Mechanism: bridge lights table (section 6, route B): position, radius, linear colour and intensity per light, up to
  16. The pack adds a point-light term in its cloud lighting and deferred lighting. The Euphoria Patches build of
  Complementary already has exactly this term for lightning (`lib/atmospherics/clouds/unboundClouds.glsl`,
  `lightningFlashEffect` at `lightningBoltPosition`; `lib/lighting/mainLighting.glsl` for the ground; absent from base
  Complementary), so the pack change is a second source in the same code. When the pack reads the table, Incoming hands
  its cloud glow and ground relight over to it for that frame (one model per phenomenon).
- Effort: mod 4 h on top of the bridge; per pack 4–6 h. Frame cost per pack: 16 lights × cloud samples, est. 0.1–0.3 ms
  at 1440p (Photon renders its clouds with temporal upscaling, `CLOUDS_TEMPORAL_UPSCALING 4`). Fragility: the pack's lighting
  functions change names between versions; the patch is re-applied by hand.

### 5.4 The nuke opens the cloud deck; the ICBM column
- Seen today: the stem and cap pass "through" the pack's clouds without touching them.
- Target: where the stem crosses the deck, the deck is cut in a ring the stem's width, the cap spreads above it; the hole
  drifts with the wind and closes over minutes (est.).
- Mechanism: bridge holes table: vertical capsules (bottom, top, radius, softness, age) from the mushroom model
  (`nuclear/model/MushroomModel` stem and cap at the deck's height, which the pack knows). The pack multiplies its cloud
  density by (1 − hole) in every layer's density function (`include/sky/clouds/cumulus.glsl`,
  `clouds_cumulus_density(pos)`, and the others) and in its prepare-stage coverage and cloud-shadow maps
  (`program/p0_clouds_prep.fsh`), or the deck keeps casting its shadow through the hole.
- Effort: mod 4 h; per pack 3–4 h. Frame cost: negligible (a few capsule tests per cloud sample). Fragility: low.
- Temporal upscaling: Photon accumulates clouds over several frames; a hole that grows over seconds is fine, a hole that
  appears in one frame would ghost briefly (est.); holes open with the stem's rise, never instantly.

### 5.5 One wind
- Seen today: Photon's clouds drift at 25 m/s toward 30° (`CLOUDS_CUMULUS_WIND_SPEED/ANGLE`), Complementary's in its own
  direction; our smoke drifts with the mod's wind (`util/Air`). In one frame the clouds and our smoke move apart.
- Target: one wind (what WindLink exists for).
- Mechanism: Photon places clouds by velocity × total time (`wind_velocity * world_age`, `world_age` up to 128 days),
  so a changing velocity in that product makes the deck race (review probe: ~10 m/s extra after one world day, ~100 m/s
  after ten). The contract therefore carries the integrated displacement ∫wind dt (closed form, since `util/Wind`
  depends only on game time), wrapped to the pack's noise period, plus the current velocity for the pack's cloud
  reprojection, in the pack's cloud units (`CLOUDS_SCALE`). The pack swaps both into every layer's density and coverage
  function and its reprojection (`d2_clouds_upscaling.fsh`).
- Effort: mod 3 h; per pack 4–6 h. Fragility: medium (touches every cloud layer). Side effect: clouds change direction
  when our weather changes; that is the point.

### 5.6 Shadows of our smoke and mushroom
- Seen today: the pack's clouds shade the ground; our columns and cap do not.
- Target: a dark smoke column shades the ground on its far side from the sun; under the pack's light shafts it cuts
  visible shafts.
- Mechanism: a raid has thousands of smoke clusters (3.14 bench: up to 4807), so no per-pixel list scales. Incoming
  renders its smoke and the mushroom once per frame into a low-resolution sun-space transmittance map (est. 256² over
  the area with smoke, splatting the planner's clusters and the mushroom volume from the sun) and publishes it as a
  second bridge texture with its projection; the pack multiplies its sun visibility by one lookup, and its volumetric
  light by the same lookup along its shafts.
- Effort: mod 12–16 h; per pack 4–6 h. Frame cost: our map est. 0.2–0.5 ms at thousands of clusters (to be measured on
  the scene bench); the pack's lookups are negligible. Fragility: medium (shadow code is the most version-dependent part
  of a pack).

### 5.7 Flash on the sky
- Seen today: the nuke turns vanilla fog and sky white, then amber (`NukeSky`). Photon ignores vanilla fog colour in the
  overworld, so its sky and clouds keep their own colour and only our glare, veil and sky colouring change the frame.
- Target: the pack's sky and clouds whiten with the flash in every pack.
- Mechanism: bridge header carries the flash's sky term (the same number `NukeSky` uses); the pack adds it to sky and
  cloud radiance. Effort: mod 1 h; per pack 2 h.

### 5.8 Composite our effects inside the pack (the full "one picture")
- Target: our smoke and fire take the pack's fog curve, sit behind or in front of its clouds by distance, receive its
  exposure, bloom and colour grading, and appear in its water reflections.
- Mechanism: Incoming renders its layer into its own offscreen target (premultiplied linear colour, transmittance,
  distance) before the pack's composite passes, publishes it as `incoming:shader_bridge/fx_*`, and draws nothing in
  `AFTER_LEVEL` for a pack that read it. The pack composites it once, after its clouds and before its fog, bloom and tone
  mapping, and skips our pixels in its reflections or includes them, as it chooses.
- Hard parts: our layer needs the world depth earlier than today (under Iris the depth at that stage is the pack's
  attachment, found from the bound framebuffer as `SceneDepth` already validates); our own bloom, glare and exposure
  (`Sight.adapted`, night ×1200) hand over to the pack's, with a per-pack radiance scale; see-through glass logic runs
  before translucent terrain exists. Each is a numbered risk for the prototype, not a blocker found.
- Effort: mod 40–60 h; per pack 12–20 h. Frame cost: our work moves, not grows; the pack adds one full-screen composite
  (est. 0.1–0.3 ms). Fragility: high (depends on the pack's pass order). Only after 5.3–5.6 prove the channel.

### 5.9 Checked, nothing to do
- Lamps and blackout (`grid.md`): packs light by block id; twins keep the pack's emission off.
- Distant terrain (Voxy, DH): the layer already reads the pack's LOD depth (`voxy.md`, `dh.md`).
- Water: the water decal already follows Complementary's lowered surface (`client-fx.md`).

## 6. Mechanisms, ranked

A. **Mod side only** (no pack change): the nuke's airlight from the pack's sky in the frame (5.1). Clean for all packs.

B. **Bridge texture read by the pack** (recommended). Evidence: Iris `CustomTextureManager.createCustomTexture`
   resolves a `ResourceData` texture lazily through `Minecraft.getTextureManager().getTexture(location).getId()` at every
   bind (bytecode of the shipped Iris 1.8.14); Photon's `shaders.properties` already binds
   `texture.deferred.depthtex2 = minecraft:textures/environment/clouds.png`. Incoming registers
   `incoming:shader_bridge/events` (RGBA32F; header: magic number, version, counts, wind displacement and velocity,
   flash; then lights and holes, positions relative to the frame's camera) and the sun-space smoke map (5.6). It fills
   them during camera setup (`ViewportEvent.ComputeCameraAngles`, between `camera.setup` and `renderLevel`): Iris runs
   its prepare and shadow passes before the first `RenderLevelStageEvent`, and Photon builds its cloud coverage and
   cloud-shadow maps there. Properties:
   - the pack declares it as `customTexture.incoming_bridge = incoming:shader_bridge/events` (a named sampler in every
     program; `texture.<stage>.<sampler>` would take a built-in slot per stage); texture names avoid `_n`/`_s` endings
     (Iris reads those as PBR maps);
   - without Incoming Iris binds the 16 × 16 missing texture, so the pack checks `textureSize` first and then the magic
     texel; the added code does nothing, so a patched pack is safe for players without the mod;
   - Incoming learns that a pack reads the bridge when Iris calls `getId()` on our texture during the frame, so it can
     turn off its own stand-in (cloud glow, relight) only then; no Iris API or mixin;
   - the contract is versioned and public (a page in `docs/`), like the WindLink/MCWIND channels; any pack author can adopt
     it, which is the route to offer upstream.
   Iris's public Java API (`IrisApi` v0: `isShaderPackInUse`, `isRenderingShadowPass`, `getSunPathRotation`, …) has no way
   to add uniforms; pack custom uniforms read only Iris's own variables. A texture is the only public channel through
   Iris. It carries data from the mod to the pack only; the pack-to-mod direction is the finished frame or 5.8.
   For Complementary there is a second clean option: the Euphoria Patcher already adds its own uniforms to Iris; its
   author could expose a small public provider API, which Incoming would feed from an optional `compat/` module.

C. **Marker include replaced at pack load** (WindLink's MCWIND way). Needs a mixin into Iris's pack loader. Our rules
   keep other mods' internals out of our code; B gives the same data without it. Rejected.

D. **Ship patched packs.** Clean only for MakeUp (LGPL), everywhere. Photon: unresolved on GitHub (section 2), excluded on
   Modrinth, CurseForge and in the Incoming jar, without the author's written permission. Not clean for Complementary
   (CLA 1.2c forbids a modified pack in a modpack; 1.3e, 1.3f), except as a patch applied by a patcher on Euphoria Patches with the add-on author's terms. Not possible for BSL
   and Solas.

E. **Draw our effects through the pack's own programs.** Rejected (section 3: the pack's fog ate the plumes).

## 7. Clean and not clean, per pack

| Pack | Mod-side (A) | Bridge in pack (B) | How players get it | Clean? |
|---|---|---|---|---|
| MakeUp | yes | we write it | a "MakeUp + Incoming" copy, LGPL, GitHub and stores; offered upstream to the author | clean |
| Photon | yes | we write it | upstream pull request to sixthsurge/photon (21 of the last 30 closed pull requests were merged, 2026-11) | clean through the upstream merge or the author's written permission; a patched copy of our own is not clean until then |
| Complementary + Euphoria | yes | Euphoria Patches author adds the channel (precedent: the generated pack reads WindLink's MCWIND channels and ships Photonics and Colorwheel integrations) | the normal Euphoria Patcher, or a provider API of the Patcher fed by an optional `compat/` module | clean only through the Euphoria author; our own patched copy is not clean (CLA 1.2c, 1.3e, 1.3f) |
| BSL | yes | no | — | only A |
| Solas | yes | no | — | only A |

## 8. Recommendation

| # | Item | Packs | Effort | What the player gets |
|---|---|---|---|---|
| 1 | Nuke airlight from the pack's sky (5.1) | all five | 8–12 h | a distant mushroom stops turning vanilla blue under Photon; it takes the colour of the pack's sky |
| 2 | Bridge contract and texture in Incoming (6 B), with tests of the packing and the "read this frame" detection | — | 16 h | nothing alone |
| 3 | MakeUp reference patch: lights on clouds and ground (5.3), nuke hole (5.4), one wind (5.5), flash sky (5.7) | MakeUp | 14 h | the whole set working in one pack, legally clean, to judge in frames |
| 4 | Same in Photon, as an upstream pull request | Photon (Artem's pack) | 16 h | the nuke lights and opens Photon's real clouds; Photon's clouds move with our wind |
| 5 | Smoke shadows (5.6) | MakeUp, Photon | 12–16 h mod + 4–6 h per pack | smoke columns and the cap shade the ground and cut light shafts |
| 6 | Handover of the contract to the Euphoria Patches author | Complementary | 4 h | same as 4 for Complementary, if the author accepts |
| 7 | Composite inside the pack (5.8) | Photon first | 40–60 h + 12–20 h per pack | full unity: fog, clouds in front of the cap, exposure, reflections |

Items 1 and 2–4 are independent; 1 ships alone. Each pack item is accepted by frames beside the same pack without the
bridge (look skill), vanilla first (the bridge changes nothing without a pack), and a scene-bench run under the pack.
Item 7 starts only with frames from 3–5 in hand. Holes behind drones, missiles and rockets are not done (section 4).

## 9. Needs Artem's word

- Distributing a patched Photon in either pack: the license does not clearly allow it on GitHub and excludes Modrinth,
  CurseForge and the Incoming jar; the clean way is the author's written permission or the upstream merge.
- Opening pull requests or issues to Photon, MakeUp or Euphoria Patches, or writing to their authors (contacting authors).
- Changing either pack's shader pack set or adding a patched pack to it.
- Complementary: asking the Euphoria Patches author to add the channel or a provider API (our own modified copy is not
  allowed by its license).

## 10. What breaks on pack updates

- Bridge reads in a pack are a few self-contained blocks (one sampler line in `shaders.properties`, one include, calls
  in clouds, lighting and sky). An update that renames those functions breaks the patch at compile time, visibly (Iris
  reports the error and the pack falls back), not silently. Upstreamed code moves with the pack.
- The contract is versioned; Incoming writes version N, packs read versions they know. A newer Incoming never breaks an
  older pack: unknown rows are ignored.
- Item 7 depends on pass order; a pack restructuring its composites needs a new patch.
