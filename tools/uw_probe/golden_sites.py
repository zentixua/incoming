#!/usr/bin/env python3
"""Surface survey of a world copy for the dust-wave acceptance scenes: per chunk (one column at its centre, optionally
every chunk or a stride) the top non-air block, its kind (water, natural, built) and height, read offline from region
files (stdlib only; never on a live world). Writes a TSV and, with --pick, proposes the scene sites:

  dry   flat land with no water within 1 km and the smallest height spread over 500 m
  city  the largest share of built tops over 300 m, no water within 300 m
  coast a land column with water within 300 m and the most water in 2 km (a water ground zero on the coast)
  open  open water: the deepest water share over 1 km

    python3 tools/uw_probe/golden_sites.py <world dir> <out.tsv> [--stride N] [--pick]
"""
import io
import math
import os
import struct
import sys
import zlib

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
WATER = {"minecraft:water", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:seagrass", "minecraft:tall_seagrass",
         "minecraft:bubble_column", "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice"}
# natural tops: soils, rock, sand, snow, plants and trees (a forest floor reads as natural ground)
NATURAL_WORDS = ("grass", "dirt", "podzol", "mycelium", "sand", "gravel", "stone", "andesite", "diorite", "granite",
                 "deepslate", "tuff", "calcite", "snow", "clay", "mud", "leaves", "log", "wood", "fern", "flower",
                 "tulip", "poppy", "dandelion", "bush", "vine", "moss", "mushroom", "cactus", "sugar_cane", "lily",
                 "red_sand", "sandstone", "coarse", "rooted", "dripstone", "farmland", "sapling",
                 "azalea", "pumpkin", "melon", "bamboo", "kelp", "powder_snow", "obsidian", "basalt", "netherrack",
                 "orchid", "allium", "bluet", "daisy", "cornflower", "lilac", "rose", "peony", "sunflower", "berry",
                 "lichen", "pointed", "magma", "pink_petals", "wildflowers", "leaf_litter", "dead_bush", "short_grass",
                 "tall_grass")
BUILT_WORDS = ("concrete", "brick", "planks", "glass", "terracotta", "wool", "slab", "stairs", "wall", "fence", "door",
               "iron", "copper", "quartz", "smooth", "polished", "chiseled", "cut_", "lantern", "torch", "rail",
               "carpet", "bed", "chest", "barrel", "lamp", "trapdoor", "pane", "path", "prismarine", "purpur",
               "tile", "button", "plate", "sign", "banner", "anvil", "cauldron", "hopper", "scaffold")


def kind(name):
    if name in WATER: return "water"
    n = name.split(":", 1)[-1]
    if any(w in n for w in BUILT_WORDS): return "built"
    if any(w in n for w in NATURAL_WORDS): return "natural"
    return "built"


class Nbt:
    def __init__(self, b):
        self.b = io.BytesIO(b)

    def r(self, fmt):
        s = struct.calcsize(fmt)
        return struct.unpack(">" + fmt, self.b.read(s))

    def string(self):
        (n,) = self.r("H")
        return self.b.read(n).decode("utf-8", "replace")

    def payload(self, t):
        if t == 1: return self.r("b")[0]
        if t == 2: return self.r("h")[0]
        if t == 3: return self.r("i")[0]
        if t == 4: return self.r("q")[0]
        if t == 5: return self.r("f")[0]
        if t == 6: return self.r("d")[0]
        if t == 7:
            (n,) = self.r("i"); return self.b.read(n)
        if t == 8: return self.string()
        if t == 9:
            (et, n) = struct.unpack(">bi", self.b.read(5))
            return [self.payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                (tt,) = self.r("b")
                if tt == 0: return d
                k = self.string()
                d[k] = self.payload(tt)
        if t == 11:
            (n,) = self.r("i"); return list(self.r("%di" % n)) if n else []
        if t == 12:
            (n,) = self.r("i"); return list(self.r("%dq" % n)) if n else []
        raise ValueError("tag %d" % t)

    def root(self):
        (t,) = self.r("b")
        self.string()
        return self.payload(t)


def top(chunk, lx, lz):
    """(y, block name) of the highest non-air block in column (lx, lz) of a chunk, or None."""
    secs = sorted((s for s in chunk.get("sections", []) if "block_states" in s), key=lambda s: -s["Y"])
    for s in secs:
        bs = s["block_states"]
        pal = [p["Name"] for p in bs["palette"]]
        if len(pal) == 1:
            if pal[0] in AIR: continue
            return s["Y"] * 16 + 15, pal[0]
        data = bs.get("data") or []
        bits = max(4, (len(pal) - 1).bit_length())
        per = 64 // bits
        mask = (1 << bits) - 1
        for y in range(15, -1, -1):
            i = (y * 16 + lz) * 16 + lx
            v = (data[i // per] & 0xFFFFFFFFFFFFFFFF) >> ((i % per) * bits) & mask
            if v < len(pal) and pal[v] not in AIR:
                return s["Y"] * 16 + y, pal[v]
    return None


def survey(world, stride):
    rdir = os.path.join(world, "region")
    for f in sorted(os.listdir(rdir)):
        p = f.split(".")
        if len(p) != 4 or p[3] != "mca": continue
        rx, rz = int(p[1]), int(p[2])
        with open(os.path.join(rdir, f), "rb") as h:
            data = h.read()
        if len(data) < 8192: continue
        for cz in range(0, 32, stride):
            for cx in range(0, 32, stride):
                e = struct.unpack(">I", data[4 * (cx + 32 * cz):4 * (cx + 32 * cz) + 4])[0]
                off, cnt = e >> 8, e & 255
                if off == 0 or cnt == 0: continue
                o = off * 4096
                (n, comp) = struct.unpack(">Ib", data[o:o + 5])
                raw = data[o + 5:o + 4 + n]
                try:
                    b = zlib.decompress(raw) if comp == 2 else raw
                    chunk = Nbt(b).root()
                except Exception:
                    continue
                if chunk.get("Status", "") not in ("minecraft:full", "full"): continue
                t = top(chunk, 8, 8)
                if t is None: continue
                yield (rx * 32 + cx) * 16 + 8, (rz * 32 + cz) * 16 + 8, t[0], t[1], kind(t[1])


def pick(rows, cell):
    grid = {(x // cell, z // cell): (y, k) for x, z, y, name, k in rows}
    def around(c, r):
        n = int(math.ceil(r / cell))
        for dx in range(-n, n + 1):
            for dz in range(-n, n + 1):
                if dx * dx + dz * dz <= n * n and (c[0] + dx, c[1] + dz) in grid:
                    yield grid[(c[0] + dx, c[1] + dz)]
    best = {}
    for c, (y, k) in grid.items():
        if k == "water":
            w = list(around(c, 1000))
            if len(w) > 20:
                share = sum(1 for v in w if v[1] == "water") / len(w)
                if share > best.get("open", (0,))[0]: best["open"] = (share, c, y)
            continue
        near = list(around(c, 300))
        if len(near) < 10: continue
        water300 = sum(1 for v in near if v[1] == "water")
        if water300 == 0:
            built = sum(1 for v in near if v[1] == "built") / len(near)
            if built > best.get("city", (0,))[0]: best["city"] = (built, c, y)
            far = list(around(c, 1000))
            if len(far) > 20 and not any(v[1] == "water" for v in far):
                spread = [v[0] for v in around(c, 500) if v[1] != "built"]
                if len(spread) > 10:
                    s = max(spread) - min(spread)
                    if "dry" not in best or s < best["dry"][0]: best["dry"] = (s, c, y)
        else:
            w = list(around(c, 2000))
            share = sum(1 for v in w if v[1] == "water") / max(len(w), 1)
            if share > best.get("coast", (0,))[0]: best["coast"] = (share, c, y)
    for k, (v, c, y) in sorted(best.items()):
        print("%-6s %d %d %d  (%s %.3f)" % (k, c[0] * cell + cell // 2, y + 1, c[1] * cell + cell // 2,
                                             {"dry": "height spread over 500 m, blocks", "city": "built share over 300 m",
                                              "coast": "water share over 2 km", "open": "water share over 1 km"}[k], v))


def main():
    a = sys.argv[1:]
    stride = int(a[a.index("--stride") + 1]) if "--stride" in a else 1
    rows = list(survey(a[0], stride))
    with open(a[1], "w") as o:
        o.write("x\tz\ty\tblock\tkind\n")
        for r in rows: o.write("%d\t%d\t%d\t%s\t%s\n" % r)
    print("%d columns" % len(rows), file=sys.stderr)
    if "--pick" in a: pick(rows, 16 * stride)


if __name__ == "__main__":
    main()
