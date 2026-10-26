"""Bake fireball and smoke billow images for the effects layer: Blender (Mantaflow, Cycles), free.

  blender --background --factory-startup --python tools/bake_fx/bake.py -- <folder> [fireball|puff|spray|all|nukeball] [--res N] [--draft] [--cpu]
  blender … bake.py -- <folder> fireball-vdb --vdb <.vdb frame folder> [--frames 1,2,…] [--tile N] [--samples N] [--draft] [--cpu]
  blender … bake.py -- <folder> puff-vdb --vdb <.vdb frame folder> --frames 60,66,… [--cap] [--tau T] [--tile N] [--samples N]
  blender … bake.py -- <folder> clubs-vdb --vdb <.vdb frame folder> --kind <name> --frames a,b,… [--view side|front|top] [--mirror]
                     [--round] [--wedge DEG] [--tau T] [--tile N] [--samples N] [--draft] [--cpu]

Next: tools/bake_fx/pack.py packs frames onto the layer atlas (fx/particle/*.png and fx/particle/flipbooks.json).

Numbers use the mod cloud model units (CloudModel), not visual guesses: one atlas serves any charge; the model
supplies size and time, and a frame is a fraction of lifetime.
- fireball — fuel fireball per CCPS (1994): D = 5.8·M^⅓ m, burns for 0.45·M^⅓ s. Simulates a ball of M = {FUEL_KG} kg
  in real meters and seconds (gravity 9.81): in CCPS, t ≈ √(D/g) is the ball rise time (Froude number ~1), so frames
  at fractions of burn time work for any M. Combustion is Mantaflow gas with fire; one EXR render per frame: soot absorbs
  (σ = κ·(smoke + {FLAME_SOOT}·flame): soot emits, and the reaction zone has more soot than the smoke field carries — ⚠ estimate),
  and emits by Kirchhoff's law at the same σ × blackbody brightness at its temperature (Wien at 555 nm for brightness,
  Blackbody node for color). Temperature is the Mantaflow field (ΔT in its units) mapped linearly to kelvins: from air
  at {T_AIR} K to {T_PEAK} K at the hottest point (hydrocarbon diffusion-flame core, ⚠ estimate; pack.py checks surface
  emission during burning is of the order of CCPS 275 kW/m²). Frames run from flash to cooling below the Draper point.
- fireball-vdb — the same ball from a ready-made combustion simulation (fireball_vdb): JangaFX "Gasoline Explosion"
  OpenVDB frames (EmberGen, CC0: https://jangafx.com/software/embergen/download/free-vdb-animations/ — download and unpack;
  not stored in the repository). Our Mantaflow produces a smooth ball without billows (incompressible gas, ~70 pixels
  across the ball); EmberGen has billows, tongues and soot of real combustion. Light follows the same emission law;
  time is in burn-time fractions.
- puff-vdb — smoke billow on the mod atlas: cloud cap from a ready-made simulation (JangaFX "Ground Explosion" OpenVDB
  frames, CC0), six suns as for puff (puff_vdb below, source: SOURCES.md).
- clubs-vdb — the same billow from any other ready-made simulation (soil ejecta, crushing dust, airburst cloud):
  frame is a simulation lifetime fraction (16 frames chosen by the baker), one optical thickness across frames,
  six suns, view along one axis, no straight cut (floor by mirroring; skip frames with a domain wall across the view);
  details at clubs_vdb.
- puff — previous smoke billow from our Mantaflow (fluffy ball; atlas now uses puff-vdb): gas emitted in every direction,
  without buoyancy (mod model supplies rise and drift), billowing and spreading.
  Six renders per frame with one sun on the right, left, top, bottom, front and back (six-sided lighting), black world;
  albedo {PUFF_ALBEDO}, asymmetry {PUFF_G}: halfway between soot (0.25; 0.5) and dust (0.8; 0.6). The mod supplies material
  color; maps only describe light passing through a billow (⚠ approximation).
Orthographic side camera (along +Y): right is +X, up is +Z, toward camera is −Y. Cycles renders on the GPU (OptiX):
run only when Artem's game is closed (tools/laptop_job.sh).
- nukeball — nuclear fireball surface (below, at nukeball()): Mantaflow only, no render, on the CPU
  (~1 h on the laptop); ball gas-fraction fields by frame, pattern computed by pack.py.
"""
import json
import math
import os
import sys

import bpy
import numpy as np
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = os.path.abspath(argv[0] if argv else "bake-out")
# Mantaflow puts his noise tile (waveletNoiseTile.bin, 24 MB) in the current folder - let it be in the baking folder, not in the tree
os.makedirs(OUT, exist_ok=True)
os.chdir(OUT)
WHAT = argv[1] if len(argv) > 1 and not argv[1].startswith("--") else "all"
DRAFT = "--draft" in argv
# --cpu: Cycles on the processor (video card is busy with another session; slower)
CPU = "--cpu" in argv
RES = int(argv[argv.index("--res") + 1]) if "--res" in argv else (48 if DRAFT else 96)
TILE = int(argv[argv.index("--tile") + 1]) if "--tile" in argv else (128 if DRAFT else 384)
SAMPLES = int(argv[argv.index("--samples") + 1]) if "--samples" in argv else (16 if DRAFT else 64)
FPS = 24

# fuel ball: mass, diameter and burning time according to CCPS
FUEL_KG = 20.0
D_BALL = 5.8 * FUEL_KG ** (1 / 3)
T_BURN = 0.45 * FUEL_KG ** (1 / 3)
T_AIR, T_PEAK = 288.0, 1800.0
FLAME_SOOT = 3.0
# soot weakening per unit smoke field, 1/m: ball thickness ~10 thick (opaque, like CCPS - ε ≈ 1)
SOOT_PER_M = 10.0 / D_BALL
C2 = 1.4388e-2
LAMBDA = 555e-9
PUFF_ALBEDO, PUFF_G = 0.55, 0.55
# Mantaflow: buoyancy of heat, combustion rate and combustion fraction while there is an influx of vapors (⚠ selection of the shape for CCPS:
# , the ball grows to D for the first fifth of combustion, t burns, floats up; pack.py writes what happened)
BETA, BURN_RATE, INFLOW = 5.0, 0.5, 0.4
# finished simulation (fireball-vdb): thickness of soot per diameter of a burning ball - like ours (SOOT_PER_M · D_BALL)
VDB_TAU = 10.0
VDB_SMOKE = float(argv[argv.index("--smoke") + 1]) if "--smoke" in argv else 0.3
VDB_HOT = float(argv[argv.index("--hot") + 1]) if "--hot" in argv else 90.0


def reset(tile):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    if CPU:
        sc.cycles.device = "CPU"
    else:
        prefs = bpy.context.preferences.addons["cycles"].preferences
        prefs.compute_device_type = "OPTIX"
        prefs.get_devices()
        for d in prefs.devices:
            d.use = d.type == "OPTIX"
        sc.cycles.device = "GPU"
    sc.cycles.samples = SAMPLES
    sc.cycles.use_denoising = True
    if WHAT in ("fireball-vdb", "puff-vdb", "clubs-vdb"):
        # Cycles' denoiser filters radiance, not transparent-volume alpha. Every pixel needs the
        # full sample count before downsampling; adaptive RGB convergence leaves speckled soot.
        sc.cycles.use_adaptive_sampling = False
    sc.cycles.volume_bounces = 6
    sc.cycles.max_bounces = 8
    sc.cycles.transparent_max_bounces = 8
    sc.cycles.volume_step_rate = 0.5
    sc.render.film_transparent = True
    sc.render.resolution_x = sc.render.resolution_y = tile
    sc.render.fps = FPS
    sc.render.image_settings.file_format = "OPEN_EXR"
    sc.render.image_settings.color_mode = "RGBA"
    sc.render.image_settings.color_depth = "32"
    sc.view_settings.view_transform = "Standard"
    world = bpy.data.worlds.new("black")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[1].default_value = 0.0
    sc.world = world
    return sc


def domain(size, z0, kind, frames):
    """Area cube size (bottom - z0) with gas Mantaflow."""
    bpy.ops.mesh.primitive_cube_add(size=size, location=(0, 0, z0 + size / 2))
    dom = bpy.context.active_object
    dom.name = "domain"
    m = dom.modifiers.new("fluid", "FLUID")
    m.fluid_type = "DOMAIN"
    ds = m.domain_settings
    ds.domain_type = "GAS"
    ds.resolution_max = RES
    ds.cache_type = "ALL"
    ds.cache_directory = os.path.join(OUT, "cache-" + kind)
    ds.cache_frame_start = 1
    ds.cache_frame_end = frames
    ds.cache_data_format = "OPENVDB"
    ds.use_adaptive_domain = False
    ds.use_noise = not DRAFT
    ds.noise_scale = 2
    ds.noise_strength = 1.0
    ds.time_scale = 1.0
    ds.dissolve_speed = 0
    ds.use_dissolve_smoke = False
    for side in ("top", "front", "back", "left", "right", "bottom"):
        setattr(ds, "use_collision_border_" + side, False)
    return dom, ds


def flow_sphere(r, z, kind):
    bpy.ops.mesh.primitive_uv_sphere_add(radius=r, location=(0, 0, z), segments=32, ring_count=16)
    flow = bpy.context.active_object
    flow.name = "flow-" + kind
    fm = flow.modifiers.new("fluid", "FLUID")
    fm.fluid_type = "FLOW"
    fs = fm.flow_settings
    fs.flow_behavior = "INFLOW"
    fs.flow_source = "MESH"
    fs.surface_distance = 1.0
    fs.volume_density = 1.0
    fs.use_initial_velocity = True
    flow.hide_render = True
    return flow, fs


def pulse(fs, last):
    """The source works frames 1...last and turns off: an explosion is a release, not a jet."""
    fs.use_inflow = True
    fs.keyframe_insert("use_inflow", frame=1)
    fs.keyframe_insert("use_inflow", frame=last)
    fs.use_inflow = False
    fs.keyframe_insert("use_inflow", frame=last + 1)


def bake(dom):
    with bpy.context.temp_override(object=dom, active_object=dom, selected_objects=[dom]):
        bpy.ops.fluid.free_all()
        bpy.ops.fluid.bake_all()


def camera(size, z):
    bpy.ops.object.camera_add(location=(0, -3 * size, z), rotation=(math.pi / 2, 0, 0))
    cam = bpy.context.active_object
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = size
    cam.data.clip_end = 10 * size
    bpy.context.scene.camera = cam
    return cam


def grid_max(cache, name, frames):
    """The largest field value across all cache frames (OpenVDB)."""
    import openvdb
    top = 0.0
    for f in range(1, frames + 1):
        p = os.path.join(cache, "data", f"fluid_data_{f:04d}.vdb")
        if not os.path.exists(p):
            continue
        try:
            top = max(top, openvdb.read(p, name).evalMinMax()[1])
        except Exception:
            pass
    return top


def save_npy(path_exr, path_npy):
    """EXR render (color multiplied by alpha, linear) → float32 h×w×4 in .npy: pack.py without OpenEXR."""
    img = bpy.data.images.load(path_exr, check_existing=False)
    w, h = img.size
    a = np.array(img.pixels[:], np.float32).reshape(h, w, 4)[::-1]
    bpy.data.images.remove(img)
    np.save(path_npy, a)
    os.remove(path_exr)


def node(nt, kind, **inputs):
    n = nt.nodes.new(kind)
    for k, v in inputs.items():
        n.inputs[k].default_value = v
    return n


def math_node(nt, op, a=None, b=None):
    n = nt.nodes.new("ShaderNodeMath")
    n.operation = op
    for i, v in enumerate((a, b)):
        if v is None:
            continue
        if isinstance(v, (int, float)):
            n.inputs[i].default_value = v
        else:
            nt.links.new(v, n.inputs[i])
    return n.outputs[0]


def attr(nt, name):
    n = nt.nodes.new("ShaderNodeAttribute")
    n.attribute_name = name
    return n.outputs["Fac"]


def fire_material(tmax, soot, flame, smoke=1.0):
    """Soot absorbs (σ = soot·(smoke + FLAME_SOOT·flame), per unit length of the scene) and according to Kirchhoff emits the same σ × brightness
     of a black body of its temperature (the field temperature is linear in kelvins: 0 - air, tmax and higher - T_PEAK); flame is the name of the flame field
    , smoke is the weight of the smoke field against the flame in soot e."""
    mat = bpy.data.materials.new("fireball")
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    absorb = nt.nodes.new("ShaderNodeVolumeAbsorption")
    absorb.inputs["Color"].default_value = (0, 0, 0, 1)
    emit = nt.nodes.new("ShaderNodeEmission")
    add = nt.nodes.new("ShaderNodeAddShader")
    sigma = math_node(nt, "MULTIPLY", math_node(nt, "ADD", math_node(nt, "MULTIPLY", attr(nt, "density"), smoke),
                                                math_node(nt, "MULTIPLY", attr(nt, flame), FLAME_SOOT)), soot)
    nt.links.new(sigma, absorb.inputs["Density"])
    kelvin = math_node(nt, "ADD", math_node(nt, "MULTIPLY", math_node(nt, "MINIMUM", attr(nt, "temperature"), tmax), (T_PEAK - T_AIR) / tmax), T_AIR)
    kelvin = math_node(nt, "MAXIMUM", kelvin, T_AIR)
    bb = nt.nodes.new("ShaderNodeBlackbody")
    nt.links.new(kelvin, bb.inputs["Temperature"])
    nt.links.new(bb.outputs[0], emit.inputs["Color"])
    # wien brightness at 555 nm against black body 1490 K (fuel ball CCPS): exp(c2/λ·(1/1490 − 1/T))
    wien = math_node(nt, "EXPONENT", math_node(nt, "MULTIPLY", math_node(nt, "SUBTRACT", 1 / 1490.0, math_node(nt, "DIVIDE", 1.0, kelvin)), C2 / LAMBDA))
    nt.links.new(math_node(nt, "MULTIPLY", sigma, wien), emit.inputs["Strength"])
    nt.links.new(absorb.outputs[0], add.inputs[0])
    nt.links.new(emit.outputs[0], add.inputs[1])
    nt.links.new(add.outputs[0], out.inputs["Volume"])
    return mat


def fireball():
    frames = int(math.ceil(3.0 * T_BURN * FPS)) + 4
    sc = reset(TILE)
    sc.frame_start, sc.frame_end = 1, frames
    size = 2.0 * D_BALL
    dom, ds = domain(size, 0, "fireball", frames)
    ds.use_collision_border_bottom = True
    # buoyancy: for gas at T g' = g·(1 − T_a/T); for a ball ~1500 K - 0,8 g with field ~ flame_max_temp (⚠ evaluation of field units)
    ds.flame_max_temp = 3.0
    ds.beta = BETA
    ds.alpha = 0.0
    ds.burning_rate = BURN_RATE
    ds.flame_smoke = 3.0
    ds.flame_vorticity = 0.5
    ds.vorticity = 0.15
    ds.flame_ignition = 1.5
    # fuel vapors leave the cloud near the ground for the first {INFLOW} combustion: Mantaflow is incompressible and does not expand the gas from heat
    #  reaction, therefore the growth of the ball to D (combustion products are ~7 times more voluminous than vapors) gives an influx, not heating
    #  center of the cloud vapors - at the height where CCPS places the ball (0,5 · D at the ground and above, FuelFireball): at the ground, the gas would spread
    #  over it, and the ball would come out as a flat dome-“box”
    flow, fs = flow_sphere(0.15 * D_BALL, 0.35 * D_BALL, "fuel")
    fs.flow_type = "BOTH"
    fs.fuel_amount = 2.0
    fs.temperature = 1.0
    fs.density = 0.1
    fs.velocity_normal = 1.0 * D_BALL / T_BURN
    fs.velocity_coord = (0, 0, 0.15 * D_BALL / T_BURN)
    fs.velocity_random = 0.4
    pulse(fs, max(2, int(round(INFLOW * T_BURN * FPS))))
    bake(dom)
    cache = ds.cache_directory
    tmax = grid_max(cache, "temperature", frames) or 3.0
    mat = fire_material(tmax, SOOT_PER_M, "flame")
    dom.data.materials.clear()
    dom.data.materials.append(mat)
    camera(size, size * 0.5)
    os.makedirs(os.path.join(OUT, "fireball"), exist_ok=True)
    for frame in range(1, frames + 1):
        sc.frame_set(frame)
        exr = os.path.join(OUT, "fireball", f"f_{frame:03d}.exr")
        sc.render.filepath = exr
        bpy.ops.render.render(write_still=True)
        save_npy(exr, exr[:-4] + ".npy")
    meta = {"fuel_kg": FUEL_KG, "diameter_m": D_BALL, "burn_s": T_BURN, "fps": FPS, "frames": frames, "view_m": size,
            "t_peak": T_PEAK, "t_air": T_AIR, "temperature_max": tmax, "soot_per_m": SOOT_PER_M, "reference_k": 1490.0}
    with open(os.path.join(OUT, "fireball", "meta.json"), "w") as f:
        json.dump(meta, f)


def fireball_vdb(src):
    """fireball from a finished combustion simulation (frames OpenVDB with fields density, flames, temperature - free CC0
     simulations JangaFX EmberGen, "Gasoline Explosion": the fuel burns near the ground and floats up like a ball of impact fuel): the same
     render of soot radiation as in its simulation (fire_material), the soot is {VDB_TAU} thick across the diameter of the burning ball
     (in CCPS the ball is opaque, ε ≈ 1), combustion ends when the flame is less than half of its maximum after the peak.
     Frame time is in fractions of combustion (the mod takes the frame according to the state of the ball of its model), and not in seconds EmberGen."""
    import glob
    import re

    import openvdb
    files = sorted(glob.glob(os.path.join(src, "*.vdb")), key=lambda f: int(re.findall(r"(\d+)\.vdb$", f)[0]))
    flames, hot, width, lo, hi = [], [], 0.0, [1 << 30] * 3, [-(1 << 30)] * 3
    for f in files:
        g = openvdb.read(f, "flames")
        if g.activeVoxelCount() == 0:
            flames.append(0.0)
            continue
        a, b = g.evalActiveVoxelBoundingBox()
        arr = np.zeros((b[0] - a[0] + 1, b[1] - a[1] + 1, b[2] - a[2] + 1), np.float32)
        g.copyToArray(arr, ijk=a)
        flames.append(float(arr.sum()))
        if (len(flames) - 1) % 5 == 0:
            t = np.zeros_like(arr)
            openvdb.read(f, "temperature").copyToArray(t, ijk=a)
            hot.append((len(flames) - 1, t[arr > 0.05]))
        d = openvdb.read(f, "density")
        if d.activeVoxelCount():
            da, db = d.evalActiveVoxelBoundingBox()
            lo = [min(lo[i], da[i]) for i in range(3)]
            hi = [max(hi[i], db[i]) for i in range(3)]
    peak = int(np.argmax(flames))
    end = next((i for i in range(peak, len(flames)) if flames[i] < 0.5 * flames[peak]), len(flames) - 1)
    # field temperature EmberGen - without units, and its largest - rare emissions (the median where it burns is 5–10 times
    #  lower): T_PEAK receives the {VDB_HOT}-th share of the field where it burns (flame > 0,05), in frames of complete combustion (the flame is at least
    #  half the maximum, every fifth); what is hot - T_PEAK.
    # The measure is the radiation of the surface in combustion (pack.py writes it against CCPS 200–350 kW/m²)
    burning = [h for i, h in hot if flames[i] >= 0.5 * flames[peak] and i <= end] or [h for _, h in hot]
    tmax = float(np.percentile(np.concatenate(burning), VDB_HOT))
    # diameter of the burning ball - along the flame in its largest frame (voxels)
    g = openvdb.read(files[peak], "flames")
    a, b = g.evalActiveVoxelBoundingBox()
    width = float(max(b[0] - a[0], b[1] - a[1]) + 1)
    soot = VDB_TAU / width
    frames = len(files)
    sc = reset(TILE)
    sc.frame_start, sc.frame_end = 1, frames
    vol = bpy.data.volumes.new("fire")
    vol.filepath = files[0]
    obj = bpy.data.objects.new("fire", vol)
    sc.collection.objects.link(obj)
    vol.materials.append(fire_material(tmax, soot, "flames", VDB_SMOKE))
    # orthographic camera on the side (along +Y), frame - everything that is occupied by smoke for all frames, square
    size = 1.04 * max(hi[0] - lo[0], hi[2] - lo[2])
    cx, cz = (lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2
    bpy.ops.object.camera_add(location=(cx, lo[1] - 3 * size, cz), rotation=(math.pi / 2, 0, 0))
    cam = bpy.context.active_object
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = size
    cam.data.clip_end = 10 * size
    sc.camera = cam
    # grid VDB - in indices (voxel - 1); shift the file into the object so that the indices coincide with the camera frame
    vol.grids.load()
    obj.matrix_world = vol.grids[0].matrix_object.inverted() if len(vol.grids) else obj.matrix_world
    os.makedirs(os.path.join(OUT, "fireball"), exist_ok=True)
    only = {int(x) for x in argv[argv.index("--frames") + 1].split(",")} if "--frames" in argv else None
    for i, f in enumerate(files):
        if only is not None and i + 1 not in only:
            continue
        # interrupted baking continues: finished frames are not rendered again
        if os.path.exists(os.path.join(OUT, "fireball", f"f_{i + 1:03d}.npy")):
            continue
        vol.filepath = f
        vol.grids.load()
        exr = os.path.join(OUT, "fireball", f"f_{i + 1:03d}.exr")
        sc.render.filepath = exr
        bpy.ops.render.render(write_still=True)
        save_npy(exr, exr[:-4] + ".npy")
        print("vdb frame", i + 1, "/", frames, flush=True)
    meta = {"source": src, "fps": FPS, "frames": frames, "burn_s": end / FPS, "burn_end_frame": end, "flames_peak_frame": peak,
            "width_voxels": width, "view_voxels": size, "t_peak": T_PEAK, "t_air": T_AIR, "temperature_max": tmax,
            "soot_per_voxel": soot, "reference_k": 1490.0, "render_size": TILE, "samples": SAMPLES,
            "adaptive_sampling": False}
    with open(os.path.join(OUT, "fireball", "meta.json"), "w") as fo:
        json.dump(meta, fo)


LIGHTS = {"right": (-1, 0, 0), "left": (1, 0, 0), "top": (0, 0, -1), "bottom": (0, 0, 1), "front": (0, 1, 0), "back": (0, -1, 0)}


def sun(direction, strength):
    """The sun, whose light travels along direction (from the source to the cloud)."""
    d = Vector(direction).normalized()
    bpy.ops.object.light_add(type="SUN")
    lamp = bpy.context.active_object
    lamp.data.energy = strength
    lamp.data.angle = math.radians(0.5)
    lamp.rotation_euler = (-d).to_track_quat("Z", "Y").to_euler()
    return lamp


# billows splashes (explosion on water, WaterBlast mod): gas droplets heavier than air - thrown up and in all directions and falls
#  back (density buoyancy is negative, without heat), swirls weaker than smoke; the drops almost do not absorb
#  (albedo - {SPRAY_ALBEDO}) and scatter forward (asymmetry - {SPRAY_G}, like condensation drops CloudModel.DROP_G).
#  The rise, size and time of the column are given by the mod model - frames only as the substance of the splash diverges and falls (⚠ selection of shape).
SPRAY_ALBEDO, SPRAY_G = 0.99, 0.85
CLUBS = {
    "puff": dict(alpha=0.0, vorticity=0.6, z=0.0, normal=3.0, random=0.6, up=0.3, albedo=PUFF_ALBEDO, g=PUFF_G, density=8.0, bounces=6),
    # splashes are optically thick (a column of water is tens of thicknesses, WaterBlast): the light in them is scattered many times, and drops with
    #  asymmetry 0,85 glow white from all sides, and not just in the light; reflections in volume - how much thicker is needed
    "spray": dict(alpha=-1.5, vorticity=0.25, z=-1.5, normal=1.5, random=1.0, up=4.0, albedo=SPRAY_ALBEDO, g=SPRAY_G, density=600.0,
                  bounces=32),
}


def puff(kind="puff"):
    c = CLUBS[kind]
    frames = 40 if DRAFT else 120
    sc = reset(TILE)
    sc.frame_start, sc.frame_end = 1, frames
    size = 8.0
    dom, ds = domain(size, -size / 2, kind, frames)
    ds.alpha = c["alpha"]
    ds.beta = 0.0
    ds.vorticity = c["vorticity"]
    flow, fs = flow_sphere(0.6, c["z"], kind)
    fs.flow_type = "SMOKE"
    fs.temperature = 0.0
    fs.density = 1.0
    fs.smoke_color = (0.5, 0.5, 0.5)
    fs.velocity_normal = c["normal"]
    fs.velocity_random = c["random"]
    fs.velocity_coord = (0, 0, c["up"])
    pulse(fs, 4)
    bake(dom)
    mat = bpy.data.materials.new(kind)
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    vol = nt.nodes.new("ShaderNodeVolumePrincipled")
    nt.links.new(vol.outputs[0], out.inputs["Volume"])
    nt.links.new(math_node(nt, "MULTIPLY", attr(nt, "density"), c["density"]), vol.inputs["Density"])
    sc.cycles.volume_bounces = c["bounces"]
    sc.cycles.max_bounces = max(sc.cycles.max_bounces, c["bounces"])
    vol.inputs["Color"].default_value = (c["albedo"], c["albedo"], c["albedo"], 1)
    vol.inputs["Anisotropy"].default_value = c["g"]
    dom.data.materials.clear()
    dom.data.materials.append(mat)
    camera(size, 0)
    lamps = {k: sun(v, math.pi) for k, v in LIGHTS.items()}
    os.makedirs(os.path.join(OUT, kind), exist_ok=True)
    # frames that will go to the sheet (pack.py takes 16 from the eighth to the last): the age step increases - billows the slower
    picks = sorted({8 + round((frames - 8) * (i / 15) ** 1.4) for i in range(16)})
    for frame in picks:
        sc.frame_set(frame)
        for name, lamp in lamps.items():
            for other in lamps.values():
                other.hide_render = other is not lamp
            exr = os.path.join(OUT, kind, f"{name}_{frame:03d}.exr")
            sc.render.filepath = exr
            bpy.ops.render.render(write_still=True)
            save_npy(exr, exr[:-4] + ".npy")
    with open(os.path.join(OUT, kind, "meta.json"), "w") as f:
        json.dump({"frames": frames, "picks": picks, "view_m": size, "albedo": c["albedo"], "g": c["g"]}, f)


# billows of smoke from the finished simulation (puff-vdb): frames OpenVDB with field density (free CC0 simulation JangaFX EmberGen,
# "Ground Explosion": https://jangafx.com/software/embergen/download/free-vdb-animations/ - download and unpack, do not put in the
#  repository) - the same six suns, albedo and asymmetry as ours (puff), without radiation: in the frame —
# just as the light passes billows. Its Mantaflow gave a fluffy ball (~96 cells across the diameter, wavelet noise along the
#  edge - “cotton wool”, even light inside), the EmberGen - billows of a real explosion cloud. take cap - the substance above the neck (the narrowest
#  place between the dust near the ground and the cap, {CAP_FROM}–{CAP_TO} cloud height, with a soft edge {CAP_SOFT} height); stem and dust from the
#  earth mod draws with its billows. the cap is removed from above, along the axis of the cloud: from the side, the cut lay with the straight bottom of each frame
#  (the width of the stem and the bottom of the cap is 27–49 % of the frame), and against the lying dust of the raid it stood up as a wall with a steep edge (review
# #443); the top cut is behind the thick cap, and the edge of the frame is its billows on all sides (the top of the frame is +y simulation). Thickness —
# {VDB_PUFF_TAU} across the middle of the frame (median thickness along the silhouette): the mod has its own opacity and color (CloudModel),
#  maps give the shape of the billows and the shadow over them; in the simulation the smoke melts towards the end (its dissipation), but in the mod it follows its law, so
#  the thickness of the frames is the same. cap is written with its own frame OpenVDB (field density, source conversion). The camera is
#  orthographic for each frame (frame - cap), its measure is in meta.json (“scale”, voxels per render pixel): pack.py
#  counts growth in one measure.
VDB_PUFF_TAU = float(argv[argv.index("--tau") + 1]) if "--tau" in argv else 12.0
CAP_FROM, CAP_TO, CAP_SOFT = 0.12, 0.6, 0.06
# substance closer than that many voxels to the edge of the simulation area (except for the floor) - cut off by its wall
WALL = 3


def cap_neck(arr):
    """The height of the cloud neck (index according to z of the x·y·z array): the narrowest point of the silhouette on the side between the dust near the ground and the cap."""
    proj = arr.sum(axis=1)
    width = (proj > 0.05 * proj.max()).sum(axis=0)
    nz = arr.shape[2]
    lo, hi = int(CAP_FROM * nz), int(CAP_TO * nz)
    return lo + int(np.argmin(width[lo:hi]))


def cap_mask(arr):
    """The fraction of substance cap in each voxel: 1 above the neck, below - goes out at CAP_SOFT cloud height."""
    neck = cap_neck(arr)
    soft = max(CAP_SOFT * arr.shape[2], 2.0)
    z = np.arange(arr.shape[2], dtype=np.float32)[None, None, :]
    return np.clip((z - (neck - soft)) / soft, 0, 1).astype(np.float32), neck


def round_footprint(arr, view=2):
    """cap on top - elongated spot (axes of inertia 1,4–1,7: 1): billows mode - Gaussian with square axes σh × σv, and a frame elongated along
     diagonally would be placed its oblique stripe with long straight sides (in large billows of dust there is a wall near the eye, review
    #443). The spot is compressed along the major axis to a round one (the billows are the same, slightly flattened): the density is recalculated in
     the same voxels, horizontally - bilinearly, the vertical thickness at each point is the same. Compression is by data, and not by
     transforming the object: the object matrix Blender is location, rotation and scale along its axes, the shift does not hold in it.
    view is the view axis (the projection along it is compressed; the cap on top has z). Returns the field and the previous axes ratio."""
    if view != 2:
        # the axis of view is the last one: compression occurs in the plane of the first two, then the axis is in place
        order = [k for k in range(3) if k != view] + [view]
        out, ratio = round_footprint(np.ascontiguousarray(arr.transpose(order)), 2)
        return np.ascontiguousarray(out.transpose(np.argsort(order))), ratio
    proj = arr.sum(axis=2)
    nx, ny = proj.shape
    ii, jj = np.nonzero(proj > 0)
    wgt = proj[ii, jj]
    xy = np.stack([ii, jj], 1).astype(np.float64)
    centre = np.average(xy, axis=0, weights=wgt)
    ev, vec = np.linalg.eigh(np.cov((xy - centre).T, aweights=wgt))
    ratio = float(np.sqrt(ev[1] / ev[0]))
    # where to take the point (x, y): reverse compression - stretching along the major axis
    back = vec @ np.diag([1.0, ratio]) @ vec.T
    gx, gy = np.meshgrid(np.arange(nx, dtype=np.float64), np.arange(ny, dtype=np.float64), indexing="ij")
    sx = centre[0] + back[0, 0] * (gx - centre[0]) + back[0, 1] * (gy - centre[1])
    sy = centre[1] + back[1, 0] * (gx - centre[0]) + back[1, 1] * (gy - centre[1])
    inside = (sx >= 0) & (sx <= nx - 1) & (sy >= 0) & (sy <= ny - 1)
    sx, sy = np.clip(sx, 0, nx - 1.001), np.clip(sy, 0, ny - 1.001)
    x0, y0 = sx.astype(int), sy.astype(int)
    fx, fy = (sx - x0)[..., None], (sy - y0)[..., None]
    out = (arr[x0, y0] * (1 - fx) * (1 - fy) + arr[x0 + 1, y0] * fx * (1 - fy) + arr[x0, y0 + 1] * (1 - fx) * fy
           + arr[x0 + 1, y0 + 1] * fx * fy)
    return (out * inside[..., None]).astype(np.float32), ratio


# billows from any ready-made simulation (clubs-vdb): the same as cap (six suns, one frame thickness, camera per frame, measure of
#  growth), but the frame is the entire simulation filmed along one axis (--view: side - along y, front - along x, top - from above, along z;
#  for all the top of the frame - at the top of the simulation, for top - +y), and not cap. Frame - the share of the life of the simulation: 16 of its frames that
#  was chosen by the baker (--frames) - they go into the mod according to the share of the life of their phenomenon. The frame does not have a direct cut:
#  - the floor of the area (the ground from which the dust rose) - by reflection (--mirror): the substance near a solid wall without friction is the same as
#   , which is half of a free cloud of twice the mass reflected in it (reflection method); from the side, the bottom of the frame is then not
#    straight, and billows mod puts the reflected half under the relief;
#  - the wall of the area across the view would cut off the silhouette straight - such a frame is not taken (like the cap); along the view, the wall
#    cuts only the thickness along the ray, and not the silhouette, and the frame is good;
#  - the elongated silhouette is compressed to a round one (--round, like the cap above): the shape of the cloud is set by the mod gauss, not the frame;
#  - a ring (torus of dust) - not a lump: --wedge takes a piece of it so many degrees wide, facing the camera (wedge_mask).
# Light - six suns with albedo and asymmetry billows of smoke (CLUBS["puff"]): the color and opacity of the substance is from the mod.
VIEWS = {"side": 1, "front": 0, "top": 2}


def mirror_floor(arr, a, floor):
    """Field arr (frame from the corner a according to the indices) with a reflection in the floor: everything below the floor is its reflection. The floor is the bottom of the
     dust layer, not the wall of the region: at the wall EmberGen holds a boundary layer in 2–5 voxels with almost no matter (in Dust Shockwave
     in frame 101 - 0–35 % of the largest layer), and the reflection in the wall itself placed an empty seam in the middle of the frame. The bottom is the first
     layer from the wall, where the substances are not less than half of the largest layer in the lower quarter of the cloud. Returns the field and new
    angle."""
    k = max(0, floor - a[2])
    layers = arr[:, :, k:].sum(axis=(0, 1))
    low = layers[:max(3, len(layers) // 4)]
    k += int(np.argmax(low >= 0.5 * low.max()))
    floor = a[2] + k
    above = arr[:, :, k:]
    out = np.concatenate([above[:, :, ::-1], above], axis=2)
    return np.ascontiguousarray(out), (a[0], a[1], floor - above.shape[2])


def wedge_mask(arr, a, view, degrees):
    """The proportion of matter in the wedge in azimuth around the middle of the spot (horizontally): a wedge with a width of degrees towards the camera (along the axis of the
     view, towards the − view), the edges - softly at {WEDGE_SOFT} of its width. The ring of dust (Dust Shockwave) is not a lump, but a torus; lump
    ring - a piece of it, removed from the outside: a piece of a roll of dust (the mod has a ring - gauss by sector, GroundDust)."""
    proj = arr.sum(axis=2)
    ii, jj = np.nonzero(proj > 0.05 * proj.max())
    w = proj[ii, jj]
    cx, cy = np.average(ii, weights=w), np.average(jj, weights=w)
    x = np.arange(arr.shape[0], dtype=np.float32)[:, None] - cx
    y = np.arange(arr.shape[1], dtype=np.float32)[None, :] - cy
    # to the camera: for side (look along +y) - −y, for front (look along +x) - −x
    toward = {1: -np.pi / 2, 0: np.pi}[view]
    phi = np.angle(np.exp(1j * (np.arctan2(y, x) - toward)))
    half = np.radians(degrees) / 2
    u = np.clip((half - np.abs(phi)) / (WEDGE_SOFT * 2 * half) + 0.5, 0, 1)
    return (u * u * (3 - 2 * u))[:, :, None].astype(np.float32)


# the soft edge of the wedge is this fraction of its width
WEDGE_SOFT = 0.25


def puff_vdb(src, kind="puff", view=None, mirror=False, roundup=None, wedge=0.0):
    """billows from the finished simulation: for puff-vdb - cloud cap (--cap: from above and compressed to round), for clubs-vdb - the entire
     simulation along the view axis; description - above (puff-vdb and clubs-vdb)."""
    import glob
    import re

    import openvdb
    from mathutils import Matrix
    files = sorted(glob.glob(os.path.join(src, "*.vdb")), key=lambda f: int(re.findall(r"(\d+)\.vdb$", f)[0]))
    picks = [int(x) for x in argv[argv.index("--frames") + 1].split(",")]
    cap = "--cap" in argv
    if view is None:
        view = "top" if cap else "side"
    if roundup is None:
        roundup = cap
    axis = VIEWS[view]
    os.makedirs(os.path.join(OUT, kind + "-vdb"), exist_ok=True)
    # simulation area - the union of the frames of the field density of all frames (file metadata): the substance that rested against its
    #  wall was cut off straight (for Ground Explosion the cap from the 126 frame rests against y = 285) - we don’t take such a frame
    wall_lo, wall_hi = [1 << 30] * 3, [-(1 << 30)] * 3
    for f in files:
        for m in openvdb.readAllGridMetadata(f):
            if m.name == "density" and "file_bbox_min" in m.metadata:
                wall_lo = [min(wall_lo[k], m.metadata["file_bbox_min"][k]) for k in range(3)]
                wall_hi = [max(wall_hi[k], m.metadata["file_bbox_max"][k]) for k in range(3)]
    # cap (puff-vdb) does not take the wall along any axis except the floor; billows (clubs-vdb) - only across the line of sight
    across = range(3) if kind == "puff" else [k for k in range(3) if k != axis]
    frames = {}
    for i in picks:
        d = openvdb.read(files[i - 1], "density")
        a, b = d.evalActiveVoxelBoundingBox()
        arr = np.zeros((b[0] - a[0] + 1, b[1] - a[1] + 1, b[2] - a[2] + 1), np.float32)
        d.copyToArray(arr, ijk=a)
        neck, ratio = None, 1.0
        if cap:
            m, neck = cap_mask(arr)
            arr = arr * m
        # substance at the wall of the simulation area - until the spot is compressed (it moves the edge inward)
        idx = np.argwhere(arr > 0.01 * arr.max())
        lo, hi = idx.min(0) + np.array(a), idx.max(0) + np.array(a)
        near = [k for k in across if hi[k] > wall_hi[k] - WALL] + [k for k in across if k < 2 and lo[k] < wall_lo[k] + WALL]
        if near:
            raise SystemExit(f"{kind}: frame {i} rests on the edge of the simulation area (axis {near}): cut straight, take earlier")
        if wedge > 0:
            arr = arr * wedge_mask(arr, a, axis, wedge)
        if mirror:
            arr, a = mirror_floor(arr, a, wall_lo[2])
        if roundup:
            arr, ratio = round_footprint(arr, axis)
        path = os.path.join(OUT, kind + "-vdb", f"cap_{i:03d}.vdb")
        g = openvdb.FloatGrid()
        g.copyFromArray(arr, ijk=a, tolerance=1e-4)
        g.name = "density"
        g.transform = d.transform
        openvdb.write(path, grids=[g])
        # frame - where the density is noticeable (1 % highest), and not all active voxels
        idx = np.argwhere(arr > 0.01 * arr.max())
        lo, hi = idx.min(0) + np.array(a), idx.max(0) + np.array(a)
        # thickness - along the camera's view
        proj = arr.sum(axis=axis)
        sil = proj > 0.05 * proj.max()
        frames[i] = (lo, hi, VDB_PUFF_TAU / float(np.median(proj[sil])), path, neck, ratio)
    c = CLUBS["puff" if kind not in CLUBS else kind]
    sc = reset(TILE)
    vol = bpy.data.volumes.new(kind)
    vol.filepath = frames[picks[0]][3]
    obj = bpy.data.objects.new(kind, vol)
    sc.collection.objects.link(obj)
    mat = bpy.data.materials.new(kind)
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    pv = nt.nodes.new("ShaderNodeVolumePrincipled")
    nt.links.new(pv.outputs[0], out.inputs["Volume"])
    # Principled Volume itself multiplies “Density” by the field density (Density Attribute): here - only attenuation per voxel
    sigma = nt.nodes.new("ShaderNodeValue")
    nt.links.new(sigma.outputs[0], pv.inputs["Density"])
    pv.inputs["Color"].default_value = (c["albedo"], c["albedo"], c["albedo"], 1)
    pv.inputs["Anisotropy"].default_value = c["g"]
    sc.cycles.volume_bounces = c["bounces"]
    sc.cycles.max_bounces = max(sc.cycles.max_bounces, c["bounces"])
    vol.materials.append(mat)
    vol.grids.load()
    # the camera looks along +y of the world, the top of the frame is +z of the world: the axis of view of the simulation by rotating the object is in +y of the world of
    # top: rotation by 90° around x (z simulations → −y, y → z, top of frame — +y simulations); front: on 90° around z (x → y,
    # y → −x); side - as is
    turn = {"top": Matrix.Rotation(math.pi / 2, 4, "X"), "front": Matrix.Rotation(math.pi / 2, 4, "Z")}.get(view, Matrix.Identity(4))
    obj.matrix_world = turn @ (vol.grids[0].matrix_object.inverted() if len(vol.grids) else Matrix.Identity(4))
    bpy.ops.object.camera_add(rotation=(math.pi / 2, 0, 0))
    cam = bpy.context.active_object
    cam.data.type = "ORTHO"
    sc.camera = cam
    lamps = {k: sun(v, math.pi) for k, v in LIGHTS.items()}
    os.makedirs(os.path.join(OUT, kind), exist_ok=True)
    scale = []
    ratios = []
    for i in picks:
        lo, hi, k, path, neck, ratio = frames[i]
        ratios.append(round(ratio, 3))
        # frame in the frame axes: to the right - x of the world, up - z of the world, towards the camera - −y of the world (corners of the index frame rotated by the object)
        corners = [turn @ Vector((x, y, z)) for x in (lo[0], hi[0]) for y in (lo[1], hi[1]) for z in (lo[2], hi[2])]
        x0, x1 = min(p.x for p in corners), max(p.x for p in corners)
        up0, up1 = min(p.z for p in corners), max(p.z for p in corners)
        near, deep = min(p.y for p in corners), max(p.y for p in corners) - min(p.y for p in corners)
        size = 1.08 * max(x1 - x0, up1 - up0) + 2
        cam.data.ortho_scale = size
        cam.data.clip_end = 10 * size + deep
        cam.location = ((x0 + x1) / 2 + 0.5, near - 3 * size, (up0 + up1) / 2 + 0.5)
        sigma.outputs[0].default_value = k
        scale.append(size / TILE)
        vol.filepath = path
        vol.grids.load()
        for name, lamp in lamps.items():
            npy = os.path.join(OUT, kind, f"{name}_{i:03d}.npy")
            if os.path.exists(npy):
                continue
            for other in lamps.values():
                other.hide_render = other is not lamp
            exr = npy[:-4] + ".exr"
            sc.render.filepath = exr
            bpy.ops.render.render(write_still=True)
            save_npy(exr, npy)
        print("vdb puff frame", i, "size", round(size, 1), "sigma", round(k, 4), "neck", neck, "axes", round(ratio, 2), flush=True)
    with open(os.path.join(OUT, kind, "meta.json"), "w") as f:
        json.dump({"source": src, "picks": picks, "scale": scale, "tau": VDB_PUFF_TAU, "cap": cap,
                   "cap_soft": CAP_SOFT, "view": view, "mirror": mirror, "round": roundup, "wedge": wedge, "axes_before": ratios,
                   "albedo": c["albedo"], "g": c["g"], "render_size": TILE, "samples": SAMPLES}, f)


# fireball the nuke (surface for passage pall: MushroomField, mushroom.glsl): not combustion, but a bubble of hot air
# after the second maximum - the mod has a ball 15 kt (R = 70·15^0,4 m, Glasstone and Dolan 2.12) no longer grows as a shock wave and
#  floats up Mantaflow is an incompressible gas (Boussinesq), so it is only considered. what it can do: ascent and
#  mixing with air. One field is the gas fraction of the ball c (density Mantaflow): it also has buoyancy (alpha, no heat),
#  and temperature (mixing of two gases: T = T_air + c·ΔT, ⚠ constant heat capacity. The heterogeneity at the beginning is clumps of charge vapors hitting the underside of the front (Brode 1963, RAND RM-3622, fig. 2: “blistered appearance caused by blobs of
# debris”; G&D 2.119): clots with correlation length NUKE_CLUMP·R, gas fraction difference NUKE_CONTRAST and volume fraction
# NUKE_FILL — ⚠ assessment from the image Trinity 25 ms (brightness correlation ½ on 0,15 R, print spread ~10 %; film response
#  unknown: with print ∝ L^(1/2,2) or ∝ L surface temperature spread 3–6,5 %, taken ~4,5 %). Time is not seconds:
#  frame takes the path of the center ascent in the radii of the ball, the mod has the same path according to its law of ascent (FireballModel.centreHeight),
# , therefore neither the time Mantaflow nor the size are important: the Boussinesq flow is similar to itself, and the ball is considered to have a radius NUKE_SIM m
#  (buoyancy Mantaflow - in cells, on the ball in 200 m it is thousands of times weaker, and it does not float up), ⚠ the Reynolds number is
#  of the grid, not the ball. The fields of the ball are written c by frame - float16 .npy.
NUKE_SIM = 1.0
NUKE_CLUMP, NUKE_CONTRAST, NUKE_FILL = 0.15, 0.2, 0.3
# uneven movement of clumps: random ejection speed, fraction of ascent speed (⚠ estimate; without it, the flow on the cubic
#  grid remains axisymmetric with its four axes - a cross on the top of the ball)
NUKE_STIR = 0.3
NUKE_RISE = 5.0
NUKE_ALPHA = 2.0


def nukeball():
    import openvdb
    r = NUKE_SIM
    width, height = 6 * r, 10 * r
    sc = reset(16)
    frames = 120 if DRAFT else 320
    sc.frame_start, sc.frame_end = 1, frames
    dom, ds = domain(height, 0, "nukeball", frames)
    dom.scale = (width / height, width / height, 1.0)
    bpy.ops.object.transform_apply(scale=True)
    ds.resolution_max = RES if "--res" in argv else (80 if DRAFT else 200)
    ds.alpha = NUKE_ALPHA
    ds.beta = 0.0
    ds.use_collision_border_bottom = True
    ds.vorticity = 0.1
    ds.use_noise = not DRAFT
    ds.noise_scale = 2
    flow, fs = flow_sphere(r, 2.0 * r, "nukeball")
    fs.flow_type = "SMOKE"
    fs.density = 1.0
    fs.temperature = 0.0
    fs.use_initial_velocity = True
    fs.velocity_normal = 0.0
    fs.velocity_random = NUKE_STIR
    fs.density = 1.0 - NUKE_CONTRAST
    # clumps: balls of gas fraction 1 inside the ball 1 − NUKE_CONTRAST (emission Mantaflow is the largest of the sources, not the sum);
    #  radii - equal to 0,6 to 1,4 NUKE_CLUMP·R (for balls of radius b the correlation is ½ - at ~0,8 b), volume fraction - NUKE_FILL
    rng = np.random.default_rng(15)
    left = NUKE_FILL * r ** 3
    while left > 0:
        b = NUKE_CLUMP / 0.8 * r * rng.uniform(0.6, 1.4)
        while True:
            q = rng.uniform(-1, 1, 3)
            if q @ q <= 1:
                break
        q *= r - b
        _, cs = flow_sphere(b, 0, "clump")
        bpy.context.active_object.location = (q[0], q[1], 2.0 * r + q[2])
        cs.flow_type = "SMOKE"
        cs.density = 1.0
        cs.temperature = 0.0
        cs.use_initial_velocity = False
        pulse(cs, 1)
        left -= b ** 3
    pulse(fs, 1)
    bake(dom)
    nukeball_frames(ds.cache_directory, ds.use_noise, frames)


def nukeball_frames(cache, use_noise, frames):
    """Frames from the calculated cache. Separate from the scene: a new scene with an area on the same cache folder erases it."""
    import openvdb
    r = NUKE_SIM
    out = os.path.join(OUT, "nukeball")
    os.makedirs(out, exist_ok=True)

    def grid(kind, f):
        p = os.path.join(cache, kind, f"fluid_{kind}_{f:04d}.vdb")
        return openvdb.read(p, "density_noise" if kind == "noise" else "density") if os.path.exists(p) else None

    # path of the gas center of the ball in frames - along a grid without noise (center of mass of the gas fraction)
    path = []
    for f in range(1, frames + 1):
        g = grid("data", f)
        if g is None:
            continue
        lo, hi = g.evalActiveVoxelBoundingBox()
        if hi[0] < lo[0]:
            continue
        a = np.zeros((hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1), np.float32)
        g.copyToArray(a, ijk=lo)
        m = float(a.sum())
        if m <= 0:
            continue
        z = float((np.arange(a.shape[2]) * a.sum(axis=(0, 1))).sum() / m) + lo[2]
        path.append((f, g.transform.indexToWorld((0, 0, z))[2]))
    z0 = path[0][1]
    # frames: 64 - equally along the ascent path in the radius of the ball from 0 to NUKE_RISE (or until the end of the count)
    rise = [(f, (z - z0) / r) for f, z in path]
    top = min(NUKE_RISE, rise[-1][1])
    picks, k = [], 0
    for i in range(64):
        want = top * i / 63
        while k + 1 < len(rise) and rise[k + 1][1] <= want:
            k += 1
        picks.append(rise[k] if k + 1 >= len(rise) or want - rise[k][1] <= rise[k + 1][1] - want else rise[k + 1])
    kind = "noise" if use_noise else "data"
    rows = []
    for f, s in picks:
        g = grid(kind, f)
        xf = g.transform
        voxel = xf.voxelSize()[0]
        lo, hi = g.evalActiveVoxelBoundingBox()
        a = np.zeros((hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1), np.float32)
        g.copyToArray(a, ijk=lo)
        m = float(a.sum())
        idx = [np.arange(n, dtype=np.float64) for n in a.shape]
        c = [float((idx[i] * a.sum(axis=tuple(j for j in range(3) if j != i))).sum() / m) for i in range(3)]
        # cut around the center: ±3 radius (the ball grows, taking in air)
        half = int(math.ceil(3 * r / voxel))
        box = [(max(0, int(c[i]) - half), min(a.shape[i], int(c[i]) + half + 1)) for i in range(3)]
        crop = a[box[0][0]:box[0][1], box[1][0]:box[1][1], box[2][0]:box[2][1]]
        np.save(os.path.join(out, f"c_{f:04d}.npy"), crop.astype(np.float16))
        rows.append({"frame": f, "rise_radii": s, "voxel_m": voxel, "centre": [c[i] - box[i][0] for i in range(3)]})
        print("nukeball pick", f, "rise", round(s, 3), "R, crop", crop.shape, flush=True)
    with open(os.path.join(out, "meta.json"), "w") as fo:
        json.dump({"radius": r, "clump": NUKE_CLUMP, "contrast": NUKE_CONTRAST, "frames": frames, "path": path,
                   "picks": rows}, fo)


if WHAT in ("fireball", "all"):
    fireball()
if WHAT == "fireball-vdb":
    fireball_vdb(os.path.abspath(argv[argv.index("--vdb") + 1]))
if WHAT in ("puff", "all"):
    puff()
if WHAT == "puff-vdb":
    puff_vdb(os.path.abspath(argv[argv.index("--vdb") + 1]))
if WHAT == "clubs-vdb":
    puff_vdb(os.path.abspath(argv[argv.index("--vdb") + 1]), argv[argv.index("--kind") + 1],
             argv[argv.index("--view") + 1] if "--view" in argv else "side", "--mirror" in argv, "--round" in argv,
             float(argv[argv.index("--wedge") + 1]) if "--wedge" in argv else 0.0)
if WHAT in ("spray", "all"):
    puff("spray")
if WHAT == "nukeball":
    # --reuse: cache in OUT has already been calculated - just shoot frames (do not build a scene)
    if "--reuse" in argv:
        nukeball_frames(os.path.join(OUT, "cache-nukeball"), not DRAFT, 120 if DRAFT else 320)
    else:
        nukeball()
print("BAKE DONE", OUT)
