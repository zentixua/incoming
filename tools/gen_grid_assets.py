#!/usr/bin/env python3
"""Grid (blackout) block models and states derived from vanilla so an unpowered lamp looks like the same lamp.

  python3 tools/gen_grid_assets.py [client.jar]
      → mod/src/main/resources/assets/incoming/blockstates/unlit_*.json, substation.json,
        models/block/unlit_*.json, substation*.json, models/item/substation.json

A lamp counterpart (incoming:unlit_*) uses the vanilla lamp block state:
- for lamps with an "off" model (redstone lamp, copper bulbs), lit variants point to the off model;
- for the others (glowstone, sea lantern, shroomlight, froglights, lanterns, end rod), a copy of the vanilla model
  with tintindex on all faces: the client dims it with color (IncomingClient.blockColors); lanterns and end rods
  receive the cutout layer (render_type) that vanilla assigns in code;
- invisible light block unchanged.
Requires the Minecraft 1.21.1 client jar (default: NeoForm Gradle cache). Standard library only.
"""
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "mod", "src", "main", "resources", "assets", "incoming")
JAR = os.path.expanduser("~/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_client.jar")

# double → lamp (as in ModBlocks.UNLIT)
LAMPS = ["glowstone", "sea_lantern", "shroomlight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
         "lantern", "soul_lantern", "end_rod", "redstone_lamp"] + [
    f"{w}{o}copper_bulb" for w in ("", "waxed_") for o in ("", "exposed_", "weathered_", "oxidized_")] + ["light"]
TINTED = {"glowstone", "sea_lantern", "shroomlight", "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
          "lantern", "soul_lantern", "end_rod"}
# in vanilla, the “cut” layer is specified in the code (ItemBlockRenderTypes), not in the model: for the double - in the model, otherwise the transparent
#  pixels of the handle and edges are drawn solid
CUTOUT = {"lantern", "soul_lantern", "end_rod"}


def off_model(model):
    """Switched off lamp model: redstone_lamp_on → redstone_lamp, copper_bulb_lit_powered → copper_bulb_powered."""
    return model.replace("_lit", "").replace("redstone_lamp_on", "redstone_lamp")


def write(rel, data):
    path = os.path.join(ASSETS, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(data, fh, indent=2)
        fh.write("\n")


def main():
    jar = zipfile.ZipFile(sys.argv[1] if len(sys.argv) > 1 else JAR)

    def vanilla(kind, name):
        return json.loads(jar.read(f"assets/minecraft/{kind}/{name}.json"))

    def elements(model):
        """Model edges taking into account parents: the first in the chain who has them."""
        name = model.removeprefix("minecraft:")
        while True:
            m = vanilla("models", name)
            if "elements" in m:
                return m["elements"]
            name = m["parent"].removeprefix("minecraft:")

    for lamp in LAMPS:
        state = vanilla("blockstates", lamp)
        for variant in state["variants"].values():
            for v in variant if isinstance(variant, list) else [variant]:
                model = v["model"]
                if lamp in TINTED:
                    base = model.split("/")[-1]
                    tinted = json.loads(json.dumps(elements(model)))
                    for el in tinted:
                        for face in el["faces"].values():
                            face["tintindex"] = 0
                    extra = {"render_type": "minecraft:cutout"} if lamp in CUTOUT else {}
                    write(f"models/block/unlit_{base}.json", {"parent": model} | extra | {"elements": tinted})
                    v["model"] = f"incoming:block/unlit_{base}"
                elif lamp != "light":
                    v["model"] = off_model(model)
        write(f"blockstates/unlit_{lamp}.json", state)

    # substation: facade (sign) - north, turns along FACING; embossed - burnt textures
    rot = {"north": 0, "east": 90, "south": 180, "west": 270}
    write("blockstates/substation.json", {"variants": {
        f"facing={f},powered={p}": ({"model": "incoming:block/substation" + ("" if p == "true" else "_burnt")} | ({"y": y} if y else {}))
        for f, y in rot.items() for p in ("true", "false")}})
    write("models/block/substation.json", substation(""))
    write("models/block/substation_burnt.json", {"parent": "incoming:block/substation", "textures": {
        k: f"incoming:block/substation_{k}_burnt" for k in ("side", "front", "top", "fins", "insulator")} | {
        "particle": "incoming:block/substation_side_burnt"}})
    write("models/item/substation.json", {"parent": "incoming:block/substation"})
    print("ok")


def box(frm, to, faces, uv=None):
    """Box: edges {"north": "#tex", …}; uv - according to coordinates, like vanilla models without explicit uv."""
    return {"from": frm, "to": to, "faces": {d: {"texture": t} | ({"cullface": d} if d == "down" and frm[1] == 0 else {})
                                             for d, t in faces.items()}}


def substation(_):
    """Concrete slab, transformer tank with a sign on the front, radiators on the sides, three inputs with insulators on top."""
    six = ("north", "south", "east", "west", "up", "down")
    els = [
        box([0, 0, 0], [16, 2, 16], {d: "#base" for d in six}),
        box([2, 2, 2], [14, 12, 14], {"north": "#front", "south": "#side", "east": "#side", "west": "#side", "up": "#top", "down": "#top"}),
        box([0.5, 3, 3], [2, 11, 13], {"north": "#fins", "south": "#fins", "west": "#fins", "up": "#top", "down": "#top"}),
        box([14, 3, 3], [15.5, 11, 13], {"north": "#fins", "south": "#fins", "east": "#fins", "up": "#top", "down": "#top"}),
    ]
    for x in (3.5, 7, 10.5):
        els.append(box([x, 12, 7], [x + 2, 15, 9], {d: "#insulator" for d in ("north", "south", "east", "west")}))
        els.append(box([x + 0.5, 15, 7.5], [x + 1.5, 16, 8.5], {d: "#top" for d in six if d != "down"}))
    return {"parent": "minecraft:block/block", "ambientocclusion": False, "textures": {
        "particle": "incoming:block/substation_side", "base": "incoming:block/substation_base",
        "side": "incoming:block/substation_side", "front": "incoming:block/substation_front",
        "top": "incoming:block/substation_top", "fins": "incoming:block/substation_fins",
        "insulator": "incoming:block/substation_insulator"}, "elements": els}


if __name__ == "__main__":
    main()
