"""Запекание огненного шара и клуба дыма в Blender (Mantaflow) для слоя эффектов — проба гибрида (handoff P).

  blender --background --factory-startup --python tools/bake_fx/bake.py -- <папка> [fireball|puff|all] [--res N] [--frames N]

Что считает:
- fireball — наземный взрыв горючего: полусфера топлива у земли с начальной скоростью наружу (вспышка паров),
  Mantaflow «газ» с огнём и дымом. На кадр — два рендера: пламя (излучение по полю flame, без поглощения) и
  непрозрачность сажи (поле density, без света). Цвет пламени по температуре ставит pack.py (чёрное тело): Blender
  даёт только форму и долю горящего.
- puff — клуб дыма: тёплый газ без огня всплывает термиком из шара у земли. На кадр — шесть рендеров с одним
  солнцем справа, слева, сверху, снизу, со стороны камеры и сзади (шестисторонний свет), мир чёрный.
Камера ортографическая сбоку (смотрит вдоль +Y): право — +X, верх — +Z, к камере — −Y. Время и размер в кадрах
безразмерные: pack.py кадрирует каждый кадр по его веществу, масштаб и время даёт модель облака мода.
Рендер — Cycles на видеокарте (OptiX), запуск — только когда игра Артёма закрыта.
"""
import math
import os
import sys

import bpy
from mathutils import Vector

argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = os.path.abspath(argv[0] if argv else "bake-out")
WHAT = argv[1] if len(argv) > 1 else "all"
RES = int(argv[argv.index("--res") + 1]) if "--res" in argv else 96
FRAMES = int(argv[argv.index("--frames") + 1]) if "--frames" in argv else 0
TILE = 256          # рендер кадра, пикселей (pack.py уменьшает до 128)
SAMPLES = 48


def reset():
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
    sc.cycles.volume_bounces = 4
    sc.cycles.max_bounces = 6
    sc.cycles.transparent_max_bounces = 8
    sc.cycles.volume_step_rate = 0.5
    sc.render.film_transparent = True
    sc.render.resolution_x = sc.render.resolution_y = TILE
    sc.render.image_settings.file_format = "PNG"
    sc.render.image_settings.color_mode = "RGBA"
    sc.render.image_settings.color_depth = "16"
    # линейный цвет в файле: pack.py сам переводит в sRGB
    sc.view_settings.view_transform = "Standard"
    sc.display_settings.display_device = "sRGB"
    sc.sequencer_colorspace_settings.name = "Linear Rec.709"
    sc.render.image_settings.color_management = "OVERRIDE"
    sc.render.image_settings.view_settings.view_transform = "Standard"
    try:
        sc.render.image_settings.linear_colorspace_settings.name = "Linear Rec.709"
    except Exception:
        pass
    world = bpy.data.worlds.new("black")
    world.use_nodes = True
    world.node_tree.nodes["Background"].inputs[1].default_value = 0.0
    sc.world = world
    return sc


def domain_and_flow(sc, size, flow_r, flow_z, kind, frames):
    """Куб области size×size×size (низ — земля z=0) и шар-источник радиуса flow_r на высоте flow_z."""
    bpy.ops.mesh.primitive_cube_add(size=size, location=(0, 0, size / 2))
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
    ds.use_adaptive_domain = False
    ds.use_noise = True
    ds.noise_scale = 2
    ds.noise_strength = 1.0
    ds.vorticity = 0.25
    ds.alpha = 1.0     # плавучесть дыма (Boussinesq: плотность)
    ds.beta = 1.0      # плавучесть тепла
    ds.dissolve_speed = 0
    ds.use_dissolve_smoke = False
    # земля — низ области закрыт, остальные стороны открыты
    ds.use_collision_border_bottom = True
    for side in ("top", "front", "back", "left", "right"):
        setattr(ds, "use_collision_border_" + side, False)
    if kind == "fireball":
        ds.burning_rate = 0.85
        ds.flame_smoke = 1.6
        ds.flame_vorticity = 0.6
        ds.flame_ignition = 1.25
        ds.flame_max_temp = 2.5
        ds.flame_smoke_color = (0.06, 0.055, 0.05)
    bpy.ops.mesh.primitive_uv_sphere_add(radius=flow_r, location=(0, 0, flow_z), segments=32, ring_count=16)
    flow = bpy.context.active_object
    flow.name = "flow"
    fm = flow.modifiers.new("fluid", "FLUID")
    fm.fluid_type = "FLOW"
    fs = fm.flow_settings
    fs.flow_behavior = "INFLOW"
    fs.flow_source = "MESH"
    fs.surface_distance = 1.5
    fs.volume_density = 1.0
    if kind == "fireball":
        fs.flow_type = "BOTH"
        fs.fuel_amount = 2.5
        fs.temperature = 3.0
        fs.density = 1.0
        fs.use_initial_velocity = True
        fs.velocity_normal = 4.0      # вспышка паров: газ уходит от поверхности облака топлива
        fs.velocity_coord = (0, 0, 1.0)
        on = 4
    else:
        # клуб: дым, выброшенный во все стороны, без всплытия (подъём и снос облака даёт модель мода) — клубится и расползается
        fs.flow_type = "SMOKE"
        fs.temperature = 0.0
        fs.density = 1.0
        fs.smoke_color = (0.5, 0.5, 0.5)
        fs.use_initial_velocity = True
        fs.velocity_normal = 2.5
        fs.velocity_coord = (0, 0, 0.3)
        on = 4
        ds.beta = 0.0
        ds.alpha = 0.0
        ds.vorticity = 0.5
        ds.use_collision_border_bottom = False
    # источник работает первые кадры, потом выключен (взрыв — мгновенный выброс, а не струя)
    fs.use_inflow = True
    fs.keyframe_insert("use_inflow", frame=1)
    fs.keyframe_insert("use_inflow", frame=on)
    fs.use_inflow = False
    fs.keyframe_insert("use_inflow", frame=on + 1)
    flow.hide_render = True
    return dom


def bake(dom):
    with bpy.context.temp_override(object=dom, active_object=dom, selected_objects=[dom]):
        bpy.ops.fluid.free_all()
        bpy.ops.fluid.bake_all()


def volume_material(dom, kind):
    mat = bpy.data.materials.new(kind)
    mat.use_nodes = True
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    vol = nt.nodes.new("ShaderNodeVolumePrincipled")
    nt.links.new(vol.outputs[0], out.inputs["Volume"])
    dom.data.materials.clear()
    dom.data.materials.append(mat)
    return mat, vol


def camera(sc, size, z):
    bpy.ops.object.camera_add(location=(0, -3 * size, z), rotation=(math.pi / 2, 0, 0))
    cam = bpy.context.active_object
    cam.data.type = "ORTHO"
    cam.data.ortho_scale = size
    cam.data.clip_end = 10 * size
    sc.camera = cam
    return cam


def sun(direction, strength):
    """Солнце, свет которого идёт вдоль direction (от источника к облаку)."""
    d = Vector(direction).normalized()
    bpy.ops.object.light_add(type="SUN")
    lamp = bpy.context.active_object
    lamp.data.energy = strength
    lamp.data.angle = math.radians(0.5)
    lamp.rotation_euler = (-d).to_track_quat("Z", "Y").to_euler()
    return lamp


def fireball():
    frames = FRAMES or 60
    sc = reset()
    sc.frame_start, sc.frame_end = 1, frames
    size = 6.0
    dom = domain_and_flow(sc, size, 0.45, 0.25, "fireball", frames)
    bake(dom)
    mat, vol = volume_material(dom, "fireball")
    nt = mat.node_tree
    cam = camera(sc, size, size * 0.5)
    attr_d = nt.nodes.new("ShaderNodeAttribute")
    attr_d.attribute_name = "density"
    attr_f = nt.nodes.new("ShaderNodeAttribute")
    attr_f.attribute_name = "flame"
    mul_d = nt.nodes.new("ShaderNodeMath")
    mul_d.operation = "MULTIPLY"
    mul_d.inputs[1].default_value = 12.0
    nt.links.new(attr_d.outputs["Fac"], mul_d.inputs[0])
    # излучение пламени ослаблено, чтобы кадр не упирался в белое (форма, не яркость: яркость даёт модель мода)
    mul_f = nt.nodes.new("ShaderNodeMath")
    mul_f.operation = "MULTIPLY"
    mul_f.inputs[1].default_value = 0.12
    nt.links.new(attr_f.outputs["Fac"], mul_f.inputs[0])
    vol.inputs["Color"].default_value = (0.0, 0.0, 0.0, 1)
    vol.inputs["Absorption Color"].default_value = (0, 0, 0, 1)
    for frame in range(1, frames + 1):
        sc.frame_set(frame)
        # пламя: излучение по полю flame, вещество не поглощает (только форма и доля горящего)
        for l in list(nt.links):
            if l.to_node == vol and l.to_socket.name in ("Density", "Emission Strength"):
                nt.links.remove(l)
        vol.inputs["Density"].default_value = 0.0
        vol.inputs["Emission Color"].default_value = (1, 1, 1, 1)
        nt.links.new(mul_f.outputs[0], vol.inputs["Emission Strength"])
        # непрозрачный кадр на чёрном: у излучения без поглощения альфы нет, а PNG с прямой альфой потерял бы цвет
        sc.render.film_transparent = False
        sc.render.filepath = os.path.join(OUT, "fireball", f"flame_{frame:03d}.png")
        bpy.ops.render.render(write_still=True)
        # сажа: только поглощение (альфа кадра)
        for l in list(nt.links):
            if l.to_node == vol and l.to_socket.name in ("Density", "Emission Strength"):
                nt.links.remove(l)
        vol.inputs["Emission Strength"].default_value = 0.0
        nt.links.new(mul_d.outputs[0], vol.inputs["Density"])
        sc.render.film_transparent = True
        sc.render.filepath = os.path.join(OUT, "fireball", f"soot_{frame:03d}.png")
        bpy.ops.render.render(write_still=True)


LIGHTS = {"right": (-1, 0, 0), "left": (1, 0, 0), "top": (0, 0, -1), "bottom": (0, 0, 1), "front": (0, 1, 0), "back": (0, -1, 0)}


def puff():
    frames = FRAMES or 96
    sc = reset()
    sc.frame_start, sc.frame_end = 1, frames
    size = 6.0
    dom = domain_and_flow(sc, size, 0.6, size / 2, "puff", frames)
    bake(dom)
    mat, vol = volume_material(dom, "puff")
    nt = mat.node_tree
    camera(sc, size, size * 0.5)
    attr_d = nt.nodes.new("ShaderNodeAttribute")
    attr_d.attribute_name = "density"
    mul_d = nt.nodes.new("ShaderNodeMath")
    mul_d.operation = "MULTIPLY"
    mul_d.inputs[1].default_value = 8.0
    nt.links.new(attr_d.outputs["Fac"], mul_d.inputs[0])
    nt.links.new(mul_d.outputs[0], vol.inputs["Density"])
    # альбедо 0,6 и пучок вперёд 0,3 — средний дым; цвет вещества по сажам и пыли даёт мод (карты — только свет)
    vol.inputs["Color"].default_value = (0.6, 0.6, 0.6, 1)
    vol.inputs["Anisotropy"].default_value = 0.3
    lamps = {k: sun(v, math.pi) for k, v in LIGHTS.items()}
    # только кадры, что пойдут на лист (pack.py берёт 16 с восьмого по последний)
    picks = sorted({8 + round((frames - 8) * i / 15) for i in range(16)})
    for frame in picks:
        sc.frame_set(frame)
        for name, lamp in lamps.items():
            for other in lamps.values():
                other.hide_render = other is not lamp
            sc.render.filepath = os.path.join(OUT, "puff", f"{name}_{frame:03d}.png")
            bpy.ops.render.render(write_still=True)


if WHAT in ("fireball", "all"):
    fireball()
if WHAT in ("puff", "all"):
    puff()
print("BAKE DONE", OUT)
