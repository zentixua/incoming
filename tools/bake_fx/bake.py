"""Запекание огненного шара и клуба дыма для слоя эффектов: Blender (Mantaflow, Cycles), бесплатно.

  blender --background --factory-startup --python tools/bake_fx/bake.py -- <папка> [fireball|puff|spray|all|nukeball] [--res N] [--draft] [--cpu]
  blender … bake.py -- <папка> fireball-vdb --vdb <папка кадров .vdb> [--frames 1,2,…] [--tile N] [--samples N] [--draft] [--cpu]

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
- fireball-vdb — тот же шар из готовой симуляции горения (fireball_vdb): кадры OpenVDB «Gasoline Explosion» JangaFX
  (EmberGen, CC0: https://jangafx.com/software/embergen/download/free-vdb-animations/ — скачать и распаковать, в репозиторий
  не кладутся). Своя Mantaflow даёт гладкий шар без клубов (несжимаемый газ, ~70 пикселей на шар); у EmberGen — клубы,
  языки и сажа настоящего горения. Свет — тот же закон излучения, время — в долях горения.
- puff — клуб дыма: выброс газа во все стороны, без всплытия (подъём и снос даёт модель мода), клубится и расползается.
  На кадр — шесть рендеров с одним солнцем справа, слева, сверху, снизу, спереди и сзади (шестисторонний свет), мир
  чёрный; альбедо {PUFF_ALBEDO}, асимметрия {PUFF_G} — середина между сажей (0,25; 0,5) и пылью (0,8; 0,6): цвет вещества
  даёт мод, карты — только как свет проходит клуб (⚠ приближение).
Камера ортографическая сбоку (вдоль +Y): право — +X, верх — +Z, к камере — −Y. Рендер — Cycles на видеокарте (OptiX):
запускать только когда игра Артёма закрыта (tools/laptop_job.sh).
- nukeball — поверхность огненного шара ядерки (ниже, у nukeball()): только Mantaflow, без рендера — на процессоре
  (~1 ч на ноутбуке); поля доли газа у шара по кадрам, узор считает pack.py.
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
# --cpu: Cycles на процессоре (видеокарта занята другой сессией; медленнее)
CPU = "--cpu" in argv
RES = int(argv[argv.index("--res") + 1]) if "--res" in argv else (48 if DRAFT else 96)
TILE = int(argv[argv.index("--tile") + 1]) if "--tile" in argv else (128 if DRAFT else 384)
SAMPLES = int(argv[argv.index("--samples") + 1]) if "--samples" in argv else (16 if DRAFT else 64)
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
# готовая симуляция (fireball-vdb): толщ сажи на поперечник горящего шара — как у своей (SOOT_PER_M · D_BALL)
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


def fire_material(tmax, soot, flame, smoke=1.0):
    """Сажа поглощает (σ = soot·(дым + FLAME_SOOT·пламя), на единицу длины сцены) и излучает по Кирхгофу то же σ × яркость
    чёрного тела своей температуры (поле temperature линейно в кельвины: 0 — воздух, tmax и выше — T_PEAK); flame — имя поля
    пламени, smoke — вес поля дыма против пламени в сажe."""
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
    # яркость по Вину на 555 нм против чёрного тела 1490 K (шар топлива CCPS): exp(c2/λ·(1/1490 − 1/T))
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
    """Огненный шар из готовой симуляции горения (кадры OpenVDB с полями density, flames, temperature — бесплатные CC0
    симуляции JangaFX EmberGen, «Gasoline Explosion»: топливо горит у земли и всплывает, как шар топлива удара): тот же
    рендер излучения сажи, что у своей симуляции (fire_material), сажа — {VDB_TAU} толщ на поперечник горящего шара
    (у CCPS шар непрозрачен, ε ≈ 1), горение кончается, когда пламени меньше половины его наибольшего после пика.
    Время кадров — в долях горения (мод берёт кадр по состоянию шара своей модели), а не в секундах EmberGen."""
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
    # поле temperature EmberGen — без единиц, и его наибольшее — редкие выбросы (медиана там, где горит, — в 5–10 раз
    # ниже): T_PEAK получает {VDB_HOT}-я доля поля там, где горит (пламя > 0,05), в кадрах полного горения (пламени не меньше
    # половины наибольшего, каждый пятый); что горячее — T_PEAK.
    # Мерило — излучение поверхности в горении (pack.py пишет его против CCPS 200–350 кВт/м²)
    burning = [h for i, h in hot if flames[i] >= 0.5 * flames[peak] and i <= end] or [h for _, h in hot]
    tmax = float(np.percentile(np.concatenate(burning), VDB_HOT))
    # поперечник горящего шара — по пламени в его наибольшем кадре (вокселей)
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
    # камера ортографическая сбоку (вдоль +Y), кадр — всё, что занимает дым за все кадры, квадратом
    size = 1.04 * max(hi[0] - lo[0], hi[2] - lo[2])
    cx, cz = (lo[0] + hi[0]) / 2, (lo[2] + hi[2]) / 2
    bpy.ops.object.camera_add(location=(cx, lo[1] - 3 * size, cz), rotation=(math.pi / 2, 0, 0))
    cam = bpy.context.active_object
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = size
    cam.data.clip_end = 10 * size
    sc.camera = cam
    # сетка VDB — в индексах (воксель — 1); сдвиг файла кладём в объект, чтобы индексы совпали с рамкой камеры
    vol.grids.load()
    obj.matrix_world = vol.grids[0].matrix_object.inverted() if len(vol.grids) else obj.matrix_world
    os.makedirs(os.path.join(OUT, "fireball"), exist_ok=True)
    only = {int(x) for x in argv[argv.index("--frames") + 1].split(",")} if "--frames" in argv else None
    for i, f in enumerate(files):
        if only is not None and i + 1 not in only:
            continue
        # прерванное запекание продолжается: готовые кадры не рендерятся заново
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
            "soot_per_voxel": soot, "reference_k": 1490.0}
    with open(os.path.join(OUT, "fireball", "meta.json"), "w") as fo:
        json.dump(meta, fo)


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
    "puff": dict(alpha=0.0, vorticity=0.6, z=0.0, normal=3.0, random=0.6, up=0.3, albedo=PUFF_ALBEDO, g=PUFF_G, density=8.0, bounces=6),
    # брызги — оптически толстые (столб воды — десятки толщ, WaterBlast): свет в них рассеян многократно, и капли с
    # асимметрией 0,85 светятся белым со всех сторон, а не только на просвет; отражений в объёме — сколько нужно толще
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


# Огненный шар ядерки (поверхность для прохода пелены: MushroomField, mushroom.glsl): не горение, а пузырь горячего воздуха
# после второго максимума — у мода шар 15 кт (R = 70·15^0,4 м, Glasstone и Dolan 2.12) уже не растёт ударной волной и
# всплывает. Mantaflow — несжимаемый газ (Буссинеск), поэтому считается только то, что он может: всплытие и
# перемешивание с воздухом. Одно поле — доля газа шара c (плотность Mantaflow): им же и плавучесть (alpha, тепла нет),
# и температура (смешение двух газов: T = T_возд + c·ΔT, ⚠ теплоёмкость постоянная). Неоднородность в начале — сгустки
# паров заряда, бьющие в изнанку фронта (Brode 1963, RAND RM-3622, рис. 2: «blistered appearance caused by blobs of
# debris»; G&D 2.119): сгустки с длиной корреляции NUKE_CLUMP·R, перепадом доли газа NUKE_CONTRAST и долей объёма
# NUKE_FILL — ⚠ оценка по снимку Trinity 25 мс (корреляция яркости ½ на 0,15 R, разброс отпечатка ~10 %; отклик плёнки
# неизвестен: при отпечатке ∝ L^(1/2,2) или ∝ L разброс температуры поверхности 3–6,5 %, взято ~4,5 %). Время — не секунды:
# кадр берёт путь всплытия центра в радиусах шара, у мода — тот же путь по его закону подъёма (FireballModel.centreHeight),
# поэтому ни время Mantaflow, ни размер не важны: течение Буссинеска подобно себе, и шар считается радиусом NUKE_SIM м
# (плавучесть Mantaflow — в клетках, на шаре в 200 м она в тысячи раз слабее, и он не всплывает), ⚠ число Рейнольдса —
# сетки, а не шара. Пишутся поля c у шара по кадрам — float16 .npy.
NUKE_SIM = 1.0
NUKE_CLUMP, NUKE_CONTRAST, NUKE_FILL = 0.15, 0.2, 0.3
# неровное движение сгустков: случайная скорость выброса, доля скорости всплытия (⚠ оценка; без неё течение на кубической
# сетке остаётся осесимметричным с её четырьмя осями — крест на верху шара)
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
    # сгустки: шары доли газа 1 внутри шара 1 − NUKE_CONTRAST (выброс Mantaflow — наибольшее из источников, не сумма);
    # радиусы — равно от 0,6 до 1,4 NUKE_CLUMP·R (у шаров радиуса b корреляция ½ — на ~0,8 b), доля объёма — NUKE_FILL
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
    """Кадры из посчитанного кэша. Отдельно от сцены: новая сцена с областью на ту же папку кэша его стирает."""
    import openvdb
    r = NUKE_SIM
    out = os.path.join(OUT, "nukeball")
    os.makedirs(out, exist_ok=True)

    def grid(kind, f):
        p = os.path.join(cache, kind, f"fluid_{kind}_{f:04d}.vdb")
        return openvdb.read(p, "density_noise" if kind == "noise" else "density") if os.path.exists(p) else None

    # путь центра газа шара по кадрам — по сетке без шума (центр масс доли газа)
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
    # кадры: 64 — поровну по пути всплытия в радиусах шара от 0 до NUKE_RISE (или до конца счёта)
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
        # вырезка вокруг центра: ±3 радиуса (шар растёт, вбирая воздух)
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
if WHAT in ("spray", "all"):
    puff("spray")
if WHAT == "nukeball":
    # --reuse: кэш в OUT уже посчитан — только снять кадры (сцену не строить)
    if "--reuse" in argv:
        nukeball_frames(os.path.join(OUT, "cache-nukeball"), not DRAFT, 120 if DRAFT else 320)
    else:
        nukeball()
print("BAKE DONE", OUT)
