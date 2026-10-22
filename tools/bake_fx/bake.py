"""Запекание огненного шара и клуба дыма для слоя эффектов: Blender (Mantaflow, Cycles), бесплатно.

  blender --background --factory-startup --python tools/bake_fx/bake.py -- <папка> [fireball|puff|spray|all] [--res N] [--draft]

Дальше — tools/bake_fx/pack.py: кадры на лист слоя (fx/particle/*.png и fx/particle/flipbooks.json).

Числа — в единицах модели облака мода (CloudModel), а не на глаз: один атлас служит любому заряду, размер и время даёт
модель, кадр — доля жизни.
- fireball — шар топлива по CCPS (1994): D = 5,8·M^⅓ м, горит 0,45·M^⅓ с. Считается шар M = {FUEL_KG} кг в настоящих
  метрах и секундах (тяжесть 9,81): у CCPS t ≈ √(D/g) — время всплытия шара (число Фруда ~1), поэтому кадры в долях
  времени горения годятся для любого M. Горение — газ Mantaflow с огнём; на кадр — один рендер EXR: сажа поглощает
  (σ = κ·(дым + {FLAME_SOOT}·пламя): светит сажа, а в зоне реакции её больше, чем выносит поле дыма — ⚠ оценка), излучает
  по Кирхгофу то же σ × яркость чёрного тела её температуры (Вин на 555 нм — яркость, цвет — узел Blackbody). Температура —
  поле Mantaflow (ΔT в его единицах) линейно в кельвины: от воздуха {T_AIR} K до {T_PEAK} K у самого горячего
  (ядро диффузионного пламени углеводорода, ⚠ оценка; pack.py проверяет, что излучение поверхности в горении — порядка
  275 кВт/м² CCPS). Кадры — от вспышки до остывания ниже точки Дрейпера.
- puff — клуб дыма: выброс газа во все стороны, без всплытия (подъём и снос даёт модель мода), клубится и расползается.
  На кадр — шесть рендеров с одним солнцем справа, слева, сверху, снизу, спереди и сзади (шестисторонний свет), мир
  чёрный; альбедо {PUFF_ALBEDO}, асимметрия {PUFF_G} — середина между сажей (0,25; 0,5) и пылью (0,8; 0,6): цвет вещества
  даёт мод, карты — только как свет проходит клуб (⚠ приближение).
Камера ортографическая сбоку (вдоль +Y): право — +X, верх — +Z, к камере — −Y. Рендер — Cycles на видеокарте (OptiX):
запускать только когда игра Артёма закрыта (tools/laptop_job.sh).
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
# Mantaflow кладёт свой тайл шума (waveletNoiseTile.bin, 24 МБ) в текущую папку — пусть в папку запекания, не в дерево
os.makedirs(OUT, exist_ok=True)
os.chdir(OUT)
WHAT = argv[1] if len(argv) > 1 and not argv[1].startswith("--") else "all"
DRAFT = "--draft" in argv
RES = int(argv[argv.index("--res") + 1]) if "--res" in argv else (48 if DRAFT else 96)
TILE = 128 if DRAFT else 384
SAMPLES = 16 if DRAFT else 64
FPS = 24

# шар топлива: масса, диаметр и время горения по CCPS
FUEL_KG = 20.0
D_BALL = 5.8 * FUEL_KG ** (1 / 3)
T_BURN = 0.45 * FUEL_KG ** (1 / 3)
T_AIR, T_PEAK = 288.0, 1800.0
FLAME_SOOT = 3.0
# ослабление сажи на единицу поля дыма, 1/м: толщина шара в ~10 толщ (непрозрачный, как у CCPS — ε ≈ 1)
SOOT_PER_M = 10.0 / D_BALL
C2 = 1.4388e-2
LAMBDA = 555e-9
PUFF_ALBEDO, PUFF_G = 0.55, 0.55
# Mantaflow: плавучесть тепла, скорость сгорания и доля горения, пока идёт приток паров (⚠ подбор формы под CCPS:
# шар растёт к D за первую пятую горения, горит t, всплывает; pack.py пишет, что вышло)
BETA, BURN_RATE, INFLOW = 5.0, 0.5, 0.4


def reset(tile):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"
    prefs = bpy.context.preferences.addons["cycles"].preferences
    prefs.compute_device_type = "OPTIX"
    prefs.get_devices()
    for d in prefs.devices:
        d.use = d.type == "OPTIX"
    sc.cycles.device = "GPU"
    sc.cycles.samples = SAMPLES
    sc.cycles.use_denoising = True
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
    """Куб области size (низ — z0) с газом Mantaflow."""
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
    """Источник работает кадры 1…last и выключается: взрыв — выброс, а не струя."""
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
    """Наибольшее значение поля по всем кадрам кэша (OpenVDB)."""
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
    """EXR рендера (цвет умножен на альфу, линейный) → float32 h×w×4 в .npy: pack.py без OpenEXR."""
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


def fireball():
    frames = int(math.ceil(3.0 * T_BURN * FPS)) + 4
    sc = reset(TILE)
    sc.frame_start, sc.frame_end = 1, frames
    size = 2.0 * D_BALL
    dom, ds = domain(size, 0, "fireball", frames)
    ds.use_collision_border_bottom = True
    # плавучесть: у газа при T g' = g·(1 − T_a/T); у шара ~1500 K — 0,8 g при поле ~ flame_max_temp (⚠ оценка единиц поля)
    ds.flame_max_temp = 3.0
    ds.beta = BETA
    ds.alpha = 0.0
    ds.burning_rate = BURN_RATE
    ds.flame_smoke = 3.0
    ds.flame_vorticity = 0.5
    ds.vorticity = 0.15
    ds.flame_ignition = 1.5
    # пары топлива выходят из облака у земли первые {INFLOW} горения: Mantaflow несжимаем и не расширяет газ от тепла
    # реакции, поэтому рост шара до D (продукты горения в ~7 раз объёмнее паров) даёт приток, а не нагрев
    # центр облака паров — на высоте, где CCPS ставит шар (0,5·D у земли и выше, FuelFireball): у земли газ растекался бы
    # по ней, и шар выходил бы плоским куполом-«коробкой»
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
    # материал: поглощение сажи и излучение той же сажи по Кирхгофу
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
    sigma = math_node(nt, "MULTIPLY", math_node(nt, "ADD", attr(nt, "density"), math_node(nt, "MULTIPLY", attr(nt, "flame"), FLAME_SOOT)), SOOT_PER_M)
    nt.links.new(sigma, absorb.inputs["Density"])
    kelvin = math_node(nt, "ADD", math_node(nt, "MULTIPLY", attr(nt, "temperature"), (T_PEAK - T_AIR) / tmax), T_AIR)
    kelvin = math_node(nt, "MAXIMUM", kelvin, T_AIR)
    bb = nt.nodes.new("ShaderNodeBlackbody")
    nt.links.new(kelvin, bb.inputs["Temperature"])
    nt.links.new(bb.outputs[0], emit.inputs["Color"])
    # яркость по Вину на 555 нм против чёрного тела 1490 K (шар топлива CCPS): exp(c2/λ·(1/1490 − 1/T))
    wien = math_node(nt, "EXPONENT", math_node(nt, "MULTIPLY", math_node(nt, "SUBTRACT", 1 / 1490.0, math_node(nt, "DIVIDE", 1.0, kelvin)), C2 / LAMBDA))
    nt.links.new(math_node(nt, "MULTIPLY", sigma, wien), emit.inputs["Strength"])
    nt.links.new(absorb.outputs[0], add.inputs[0])
    nt.links.new(emit.outputs[0], add.inputs[1])
    nt.links.new(add.outputs[0], out.inputs["Volume"])
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


LIGHTS = {"right": (-1, 0, 0), "left": (1, 0, 0), "top": (0, 0, -1), "bottom": (0, 0, 1), "front": (0, 1, 0), "back": (0, -1, 0)}


def sun(direction, strength):
    """Солнце, свет которого идёт вдоль direction (от источника к облаку)."""
    d = Vector(direction).normalized()
    bpy.ops.object.light_add(type="SUN")
    lamp = bpy.context.active_object
    lamp.data.energy = strength
    lamp.data.angle = math.radians(0.5)
    lamp.rotation_euler = (-d).to_track_quat("Z", "Y").to_euler()
    return lamp


# Клуб брызг (взрыв на воде, WaterBlast мода): газ капель тяжелее воздуха — брошен вверх и во все стороны и падает
# обратно (плавучесть по плотности отрицательная, без тепла), закручивается слабее дыма; капли почти не поглощают
# (альбедо — {SPRAY_ALBEDO}) и рассеивают вперёд (асимметрия — {SPRAY_G}, как у капель конденсации CloudModel.DROP_G).
# Подъём, размер и время столба даёт модель мода — кадры только как вещество брызг расходится и опадает (⚠ подбор формы).
SPRAY_ALBEDO, SPRAY_G = 0.99, 0.85
CLUBS = {
    "puff": dict(alpha=0.0, vorticity=0.6, z=0.0, normal=3.0, random=0.6, up=0.3, albedo=PUFF_ALBEDO, g=PUFF_G),
    "spray": dict(alpha=-1.5, vorticity=0.25, z=-1.5, normal=1.5, random=1.0, up=4.0, albedo=SPRAY_ALBEDO, g=SPRAY_G),
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
    nt.links.new(math_node(nt, "MULTIPLY", attr(nt, "density"), 8.0), vol.inputs["Density"])
    vol.inputs["Color"].default_value = (c["albedo"], c["albedo"], c["albedo"], 1)
    vol.inputs["Anisotropy"].default_value = c["g"]
    dom.data.materials.clear()
    dom.data.materials.append(mat)
    camera(size, 0)
    lamps = {k: sun(v, math.pi) for k, v in LIGHTS.items()}
    os.makedirs(os.path.join(OUT, kind), exist_ok=True)
    # кадры, что пойдут на лист (pack.py берёт 16 с восьмого по последний): шаг по возрасту растёт — клуб тем медленнее
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


if WHAT in ("fireball", "all"):
    fireball()
if WHAT in ("puff", "all"):
    puff()
if WHAT in ("spray", "all"):
    puff("spray")
print("BAKE DONE", OUT)
