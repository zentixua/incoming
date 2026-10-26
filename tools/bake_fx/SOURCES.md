# Fireball source and reproduction

`fireball_00.png` through `fireball_47.png` (and their motion vectors `fireballv_00.png` through `fireballv_47.png`)
are rendered from JangaFX's **Gasoline Explosion** OpenVDB sequence
(131 frames), generated with EmberGen. The source is CC0, as stated both on the
[JangaFX download page](https://jangafx.com/software/embergen/download/free-vdb-animations/)
and in the archive's `LICENSE.txt` and `README.txt`. No EmberGen installation or runtime mod is required.

Download and extract Gasoline Explosion from that page. Keep the VDB sequence and render output outside tracked files.
Run Blender through `tools/laptop_job.sh --gpu --wait` on the laptop, while the owner's game is closed:

```sh
blender --background --factory-startup --python tools/bake_fx/bake.py -- \
  /path/to/bake fireball-vdb --vdb /path/to/Gasoline_Explosion_01 --tile 512 --samples 256 \
  --frames 17,18,…,96
uv run tools/bake_fx/pack.py /path/to/bake
```

The supplied simulation provides billows and soot, not weapon dimensions or timing. `CloudModel` still gives each
weapon's fireball radius, rise, burn time and cooling temperature. The bake maps EmberGen's unitless temperature
field to 1800 K at its burning 90th percentile (an approximation); surface emissive power in the selected burn
frames is about 314 kW/m², within the CCPS fireball range of 200–350 kW/m². `pack.py` reports that check.

The display uses the [Narkowicz ACES film curve approximation](https://knarkowicz.wordpress.com/2016/01/06/aces-filmic-tone-mapping-curve/)
under CC0 (the author offers CC0 or MIT), with one exposure shared by all frames.
The 95th burning percentile maps to 0.60, with highlight desaturation only when an exposed channel exceeds white.
Renders are 512×512 at 256 samples without adaptive sampling (Cycles' denoiser does not clean transparent soot, so
32 samples left speckled cooling frames); tiles are 256×256: burning frames are taken without enlargement, and the wider cooling frames average several
render pixels per texel. There are 32
burning frames, evenly spaced in simulation time, and 16 cooling frames, evenly spaced up to the last glowing frame.
Each tile holds all of its visible matter: the edge comes from density, not from a circle, and the frame never shrinks;
`fireball.json` gives each frame's half-size in fireball radii. Optical flow (Farneback, as for the smoke puffs) gives
motion vectors to the next frame, and the layer shader blends neighbouring frames along them.
Display opacity is at least the displayed luminance; thin edges darken and redden.
Adjacent premultiplied frames blend in the layer shader along their motion vectors; the upward simulation is only mirrored horizontally, never rotated. This is presentation: it preserves bright folds and dark soot without
altering the source temperature or the model's light power. Surrounding glare, pall and light on smoke/dust remain bounded by the displayed fire (`fireball.json`),
about 0.35 at peak instead of the old clipped sheet’s 0.87. World light is therefore also lower; the night capture is
the visual check of that presentation. A small additive source bloom is drawn behind the opaque fire (presentation),
with scene-depth and smoke occlusion; this is not a new world light or an Iris HDR integration.
The soot texture is self-emissive; the separate smoke six-way maps are unchanged.

# Smoke puff source and reproduction

`puff6a_00.png` through `puff6a_15.png`, `puff6b_*` and `puff6v_*` (six-way light maps and motion vectors of the smoke
puff) are rendered from JangaFX's **Ground Explosion** OpenVDB sequence (200 frames, EmberGen), CC0 as stated on the
same download page and in the archive's `LICENSE.txt` and `README.txt`. Our own Mantaflow puff was a fuzzy ball with
almost flat light maps; the EmberGen cloud has real billows.

```sh
blender --background --factory-startup --python tools/bake_fx/bake.py -- \
  /path/to/bake puff-vdb --vdb /path/to/ground_explosion_VDB --cap --tile 384 --samples 96 \
  --frames 72,75,78,81,84,87,90,93,96,99,102,105,108,111,114,117
uv run tools/bake_fx/pack.py /path/to/bake
```

A puff of the mod is a lump of cloud, not the whole cloud with its stem: the bake keeps the cap above the cloud's neck
(the narrowest silhouette between the ground dust and the cap), because the mod draws the stem and the ground dust with
puffs of its own. The cap is rendered from above, along the cloud's axis: seen from the side, the cut left a straight
bottom on every frame (27–49 % of the frame), and the flat dust of a raid, whose puffs lie on the ground, showed it as a
wall with a vertical edge (review of #443). From above the cut is behind the dense cap and the silhouette is billows all
round. From above the cap is elongated (axes 1.4–1.7 : 1), and an elongated puff, mirrored or lying, laid the mod's
Gaussian as a slanted strip with long even sides; the bake squeezes the footprint along its long axis to round (in the
density data, by bilinear resampling: a Blender object matrix cannot hold that shear). Frames run from 72 (earlier
caps have little fine structure) to 117: from frame 126 the cap reaches the simulation domain at y = 285 and is cut
straight; the bake refuses such frames. Every frame is rendered at the same optical depth (median 12 across the silhouette): the
simulation thins out by its own dissipation, while the mod's cloud takes its opacity and colour from `CloudModel`. The
six suns, albedo 0.55 and asymmetry 0.55 are those of the old bake; the frames give the shape of the billows and how
light passes them, not the density. The camera frames each cap, and `pack.py` measures growth in voxels (never
decreasing). `pack.py` also moves the water-spray maps into the new measure of the smoke maps (one normalisation for all
puffs).

## Source and playback

Ball frames - render of the free simulation JangaFX “Gasoline Explosion” (EmberGen, 131 frame). License CC0 verified by
 on the author's page and in the `LICENSE.txt`/`README.txt` archive. The commands above repeat the baking; the original VDB is not included in git.
Clubs and soot are from the simulation, dimensions and time are from `CloudModel`. Conversion of temperature EmberGen to kelvins is approximate;
 surface radiation during combustion is about 314 kW/m² (CCPS: 200–350). The film curve is a presentation, not a change in physics.

Render 512×512, 256 samples without adaptive sampling (noise reduction Cycles does not clean transparent soot: with 32 samples the frames
 cooled down were speckled), frames 256×256: burning - without enlargement, for wider frames the cooling pixel is
, the average of several render pixels. Burning - 32 frame is equal in simulation time, cooling - 16 until the last
 frame with a luminous flame. The frame contains all the matter: the edge - in density, not in a circle; the half-size of the frame in the radii
 of the ball is in `fireball.json`. Motion vectors to the next frame are optical flow (Farneback, like billows smoke); the
 shader blends adjacent frames along them.

presentation: the top is fixed, adjacent frames are mixed in the shader along the motion vectors. The thin edge turns red and darkens;
alpha is not lower than screen brightness, cores above white lose saturation. The ambient light is limited by the fire table on the screen,
 not the same overexposed sheet. Soft halo - presentation after Iris, no change in physical power.

## billows smoke

`puff6a_*`, `puff6b_*`, `puff6v_*` - render of CC0 simulation JangaFX “Ground Explosion” (EmberGen, 200 frames), commands above.
 Its Mantaflow gave a fluffy ball with almost even light maps, while EmberGen gave the billows of a real explosion cloud. billows mod -
 a lump of cloud, and not the whole cloud with stem: a cap is taken above the neck of the cloud (the mod draws the stem and dust near the ground with its billows), and
 it is removed from above, along the axis of the cloud: from the side the cut lay with the straight bottom of each frame, and the underlying dust of the raid rose up as a wall with a steep edge (review #443); cut from above - behind the thick cap. The top cap is elongated (1,4–1,7 : 1), and the elongated
 club laid a Gaussian mode oblique stripe with long straight sides: baking compresses the spot along the major axis to a round one (in
 density data, bilinear: object matrix Blender does not hold such a shift). Frames - from 72 (previously the cap had little fine
 structure) to 117: then the cap rests on the edge of the simulation area (y = 285) and is cut off straight; baking does not take such frames
. The thickness of the frames is the same (median 12 across the
 silhouette): the simulation melts with its scattering, and the opacity and color of the mod cloud are from `CloudModel`. Six suns, albedo
0,55 and asymmetry 0,55 are the same: the frames give the shape of the billows and how the light passes through them, not the density. Spray maps `pack.py`
 converts to a new measure of smoke maps (one for all billows).

# Ejecta, crushed-dust and air-burst puffs

`eject6a/b/v_*`, `crush6a/b/v_*` and `air6a/b/v_*` (six-way light maps and motion vectors, the same layout and light
measure as the smoke puff) are rendered from three more CC0 JangaFX EmberGen OpenVDB sequences from the same
[download page](https://jangafx.com/software/embergen/download/free-vdb-animations/); the licence is CC0 on the page
and in each archive's `LICENSE.txt` and `README.txt`:

- **Grenade Dust Impact** (199 frames, density only) — the earth curtain of a hit in soil and the cold crushed dust of
  loose ground: a dome of dust with clods thrown round it;
- **Building Implosion** (116 frames, density only) — the crushed dust of stone, concrete, masonry and slabs: the dust
  of a collapsing building, lumpy from its geometry;
- **Mid-Air Explosion** (126 frames, density, flames, temperature) — the cloud of a burst in the air.

```sh
blender --background --factory-startup --python tools/bake_fx/bake.py -- /path/to/bake clubs-vdb --kind eject \
  --vdb /path/to/DustImpact/DustImpactVDB --view top --round --tile 160 --samples 96 --cpu --frames 9,15,…,99
blender … clubs-vdb --kind crush --vdb /path/to/Building_Implosion_01 --view top --round --tile 160 --samples 96 --cpu --frames 9,11,…,39
blender … clubs-vdb --kind air --vdb /path/to/Midair_Explosion_01 --view side --tile 160 --samples 96 --cpu --frames 6,9,…,48,49
uv run tools/bake_fx/pack.py /path/to/bake
```

A puff is a lump laid by the mod's Gaussian, so every frame must be roughly round and free of a straight cut
(`FxAtlasTest`). The two ground-hugging simulations are rendered from above, like the smoke cap: seen from the side,
their floor cut the silhouette straight, and mirroring them in the floor (a cloud at a free-slip wall is half of a free
cloud) put a dark shadowed band across the middle of every frame, because the upper half shades the lower one. Their
footprint is squeezed to round, as for the cap. The Dust Shockwave torus has no lump-like view and is not used.
Mid-Air is seen from the side, across its direction of flight: its jets run along the view and do not cut the
silhouette. Frames touching a domain wall across the view are refused. Every frame has the same optical depth and is
lit by the same six suns with the smoke's albedo and asymmetry: the frames give the shape of the billows, the mod gives
the matter, colour and opacity. `pack.py` writes each frame's share of the simulation's life (`"life"`): the curtain
and the crushed dust take their frame by the share of their own life, the air-burst cloud by its growth, like smoke.
The renders were made on the CPU (the GPU was busy), 160×160 at 96 samples without adaptive sampling.

## Ejection, crushing dust and rupture cloud in the air

`eject6*`, `crush6*`, `air6*` - render of three more CC0 simulations JangaFX (EmberGen) from the same page, license - in the same place
 and in `LICENSE.txt`/`README.txt` archives: “Grenade Dust Impact” - a curtain of soil ejection and cold loose dust (a dome of
 dust with clods around), “Building Implosion” - dust crushing stone, concrete, masonry and slabs (building collapse dust),
"Mid-Air Explosion" - a cloud of rupture in the air. Teams are higher. billows - a lump that puts a Gaussian mode: the frame is round and
 without a straight cut (`FxAtlasTest`). Two simulations near the ground were shot from above, like a cap: from the side their floor cut off the silhouette straight, and
 the reflection in the floor (a cloud near the wall without friction - half free) put a dark stripe in the middle of each frame - the top
 obscures the bottom. The torus Dust Shockwave does not come off in a lump - it is not taken. Mid-Air - from the side, across the flight: the jets go along the
 view. A frame that rests against the wall of the area across the line of sight is not taken. Thickness and light - like smoke; frames give the shape
clubs, substance, color and opacity - from the mod. “life” in json is the share of simulation life of the frame: the curtain and dust of
 crushing take the frame according to the share of their life, the rupture cloud - by height. Render on the processor (the video card was busy),
160×160, 96 samples without adaptive sampling.
