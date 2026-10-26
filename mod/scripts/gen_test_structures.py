#!/usr/bin/env python3
"""GameTest templates: flat platforms with stone floors (structure .nbt, MC 1.21.1).
GameTest places a template one block above its origin: template y = 0 is y = 1 in test coordinates.

  python3 scripts/gen_test_structures.py   → src/devtest/resources/data/incoming/structure/*.nbt
                                                                                          (also data/incoming_sweep/structure/pad.nbt — flight scenarios)
"""
import gzip
import os
import struct

DATA_VERSION = 3955  # 1.21.1
MOD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(MOD, "src", "devtest", "resources", "data")

TAG_END, TAG_INT, TAG_STRING, TAG_LIST, TAG_COMPOUND = 0, 3, 8, 9, 10


def _name(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def _payload(tag, v):
    if tag == TAG_INT:
        return struct.pack(">i", v)
    if tag == TAG_STRING:
        return _name(v)
    if tag == TAG_LIST:
        elem_tag, items = v
        out = struct.pack(">bi", elem_tag if items else TAG_END, len(items))
        return out + b"".join(_payload(elem_tag, i) for i in items)
    if tag == TAG_COMPOUND:
        out = b""
        for k, (t, val) in v.items():
            out += struct.pack(">b", t) + _name(k) + _payload(t, val)
        return out + struct.pack(">b", TAG_END)
    raise ValueError(tag)


def structure(sx, sy, sz, floor_layers):
    """floor_layers: block names bottom to top (y = 0, 1, …); remaining space is air."""
    palette = ["minecraft:air"] + sorted(set(floor_layers))
    blocks = []
    for y in range(sy):
        if y >= len(floor_layers):
            break  # Omit air: GameTest clears the platform before each test.
        state = palette.index(floor_layers[y])
        for x in range(sx):
            for z in range(sz):
                blocks.append((TAG_COMPOUND, {"pos": (TAG_LIST, (TAG_INT, [x, y, z])), "state": (TAG_INT, state)}))
    root = {
        "DataVersion": (TAG_INT, DATA_VERSION),
        "size": (TAG_LIST, (TAG_INT, [sx, sy, sz])),
        "palette": (TAG_LIST, (TAG_COMPOUND, [{"Name": (TAG_STRING, n)} for n in palette])),
        "blocks": (TAG_LIST, (TAG_COMPOUND, [b[1] for b in blocks])),
        "entities": (TAG_LIST, (TAG_COMPOUND, [])),
    }
    return struct.pack(">b", TAG_COMPOUND) + _name("") + _payload(TAG_COMPOUND, root)


def write(name, data, namespace="incoming"):
    out = os.path.join(DATA, namespace, "structure")
    os.makedirs(out, exist_ok=True)
    path = os.path.join(out, name + ".nbt")
    with open(path, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as f:
        f.write(data)
    print(path, os.path.getsize(path))


# Small platform for quick checks.
write("pad", structure(8, 6, 8, ["minecraft:stone"]))
# Same platform in its own namespace: runScenarioSweep enables only it, running flight scenarios alone.
write("pad", structure(8, 6, 8, ["minecraft:stone"]), "incoming_sweep")
# Range: 8 stone layers, 2 dirt and turf — craters, drilling and aiming.
write("range", structure(64, 40, 64, ["minecraft:stone"] * 8 + ["minecraft:dirt"] * 2 + ["minecraft:grass_block"]))
# Runway 32×256: projectiles approach from actual distance (missile with pop-up). Height 8: GameTest
# surrounds platform with a template-height barrier wall; cruise missile reenters beyond wall at approach-lane edge
# (256 blocks from target) and flies 12 blocks above test flat world — wall stays below it.
write("runway", structure(32, 8, 256, ["minecraft:stone", "minecraft:dirt", "minecraft:grass_block"]))
