#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy"]
# ///
"""Survey a nuclear strike's ruins: compare a blasted world with its untouched copy, block by block.

Reads only region files (region/r.X.Z.mca) of both worlds; never changes either, and may run on a live server's world.

    uv run tools/nuke_survey.py <untouched world> <blasted world> <ground zero x> <ground zero z>
        [--radius 4000] [--ring 250] [--procs 4] [--spot x,z ...]

Prints:
- by distance ring: how many built blocks of each kind (glass, wood, logs, masonry, terracotta, concrete, metal,
  containers, light, wool, leaves, snow) the untouched world had and what share of them is still in place;
- spikes: one-block columns of the blasted world standing at least 5 blocks above all 8 neighbors (what fell should
  not leave towers), per ring, with the tallest listed by top block;
- for each --spot, the untouched and blasted block columns as height ranges.
Natural ground (stone, soil, sand, ores) is left out of the survival table: it hides the buildings.
"""
import argparse
import math
import os
import struct
import sys
import zlib
from collections import Counter, defaultdict
from concurrent.futures import ProcessPoolExecutor

import numpy as np

AIR = {'minecraft:air', 'minecraft:cave_air', 'minecraft:void_air'}
FLUID = {'minecraft:water', 'minecraft:lava', 'minecraft:bubble_column'}
WOODS = ('oak', 'spruce', 'birch', 'jungle', 'acacia', 'dark_oak', 'mangrove', 'cherry', 'bamboo', 'crimson', 'warped', 'pale_oak')
KINDS = ['glass', 'leaves', 'log', 'wood', 'container', 'terracotta', 'concrete', 'masonry', 'metal', 'light', 'wool', 'snow',
         'other']
GROUND = ('stone', 'dirt', 'grass_block', 'sand', 'gravel', 'deepslate', 'ore', 'andesite', 'diorite', 'granite', 'calcite',
          'tuff', 'clay', 'podzol', 'mycelium', 'mud', 'bedrock', 'netherrack', 'basalt', 'farmland', 'dirt_path', 'moss_block',
          'sandstone', 'ice', 'magma', 'obsidian')


def kind(name: str) -> str:
    n = name.split(':', 1)[-1]
    if name in AIR: return 'air'
    if name in FLUID: return 'fluid'
    if 'glass' in n: return 'glass'
    if n.endswith('_leaves'): return 'leaves'
    if n.endswith(('_log', '_wood', '_stem', '_hyphae')): return 'log'
    if 'planks' in n or (n.startswith(WOODS) and any(k in n for k in ('stairs', 'slab', 'fence', 'door', 'trapdoor', 'sign', 'button', 'pressure'))):
        return 'wood'
    if any(k in n for k in ('chest', 'barrel', 'shulker_box', 'furnace', 'smoker', 'hopper', 'dispenser', 'dropper')): return 'container'
    if 'terracotta' in n: return 'terracotta'
    if 'concrete' in n and 'powder' not in n: return 'concrete'
    if n in ('snow', 'snow_block', 'powder_snow'): return 'snow'
    if any(k in n for k in ('glowstone', 'lantern', 'torch', 'shroomlight', 'redstone_lamp', 'froglight', 'end_rod')): return 'light'
    if any(k in n for k in ('iron', 'copper', 'gold_block', 'anvil', 'chain', 'netherite', 'cauldron')): return 'metal'
    if 'wool' in n or 'carpet' in n: return 'wool'
    if any(k in n for k in ('brick', 'cobble', 'smooth_', 'polished_', 'quartz', 'cut_', 'chiseled', 'tiles', 'purpur', 'prismarine',
                              'stone_slab', 'stone_stairs', '_wall')):
        return 'masonry'
    if any(k in n for k in GROUND) or n in ('grass', 'short_grass', 'tall_grass', 'fern', 'large_fern'): return 'ground'
    return 'other'


# ---------------------------------------------------------------- NBT and regions

def read_nbt(data: bytes):
    pos = 0

    def take(n):
        nonlocal pos
        b = data[pos:pos + n]
        pos += n
        return b

    def u(fmt):
        size = struct.calcsize('>' + fmt)
        return struct.unpack('>' + fmt, take(size))[0]

    def string():
        return take(u('H')).decode('utf-8', 'replace')

    def payload(t):
        if t == 1: return u('b')
        if t == 2: return u('h')
        if t == 3: return u('i')
        if t == 4: return u('q')
        if t == 5: return u('f')
        if t == 6: return u('d')
        if t == 7: return take(u('i'))
        if t == 8: return string()
        if t == 9:
            et, n = u('b'), u('i')
            return [payload(et) for _ in range(n)]
        if t == 10:
            out = {}
            while True:
                tt = u('b')
                if tt == 0: return out
                name = string()
                out[name] = payload(tt)
        if t == 11:
            n = u('i')
            return np.frombuffer(take(4 * n), dtype='>i4')
        if t == 12:
            n = u('i')
            return np.frombuffer(take(8 * n), dtype='>u8')
        raise ValueError('tag %d' % t)

    t = u('b')
    string()
    return payload(t)


def region_chunks(path):
    """(local x, local z) -> raw chunk NBT for every chunk stored in the region file."""
    out = {}
    if not os.path.exists(path) or os.path.getsize(path) < 8192: return out
    with open(path, 'rb') as f:
        data = f.read()
    for i in range(1024):
        off = int.from_bytes(data[4 * i:4 * i + 3], 'big')
        if off == 0: continue
        start = off * 4096
        length = int.from_bytes(data[start:start + 4], 'big')
        comp = data[start + 4]
        body = data[start + 5:start + 4 + length]
        if comp == 2: raw = zlib.decompress(body)
        elif comp == 1:
            import gzip
            raw = gzip.decompress(body)
        elif comp == 3: raw = body
        else: continue
        out[(i & 31, i >> 5)] = raw
    return out


def blocks(raw, ids, names):
    """Chunk blocks as an int array [y, z, x] of global ids (names[id]), with the chunk's lowest y; None if not full."""
    tag = read_nbt(raw)
    if tag.get('Status', '').split(':')[-1] not in ('full',): return None, 0
    secs = [s for s in tag.get('sections', []) if 'block_states' in s]
    if not secs: return None, 0
    ys = [s['Y'] for s in secs]
    lo, hi = min(ys), max(ys)
    arr = np.zeros(((hi - lo + 1) * 16, 16, 16), dtype=np.int32)
    air = ids.setdefault('minecraft:air', len(ids))
    if air == len(names): names.append('minecraft:air')
    arr[:] = air
    for s in secs:
        bs = s['block_states']
        pal = [p['Name'] for p in bs['palette']]
        gid = np.array([ids[n] if n in ids else (ids.setdefault(n, len(ids)), names.append(n))[0] for n in pal], dtype=np.int32)
        y0 = (s['Y'] - lo) * 16
        if len(pal) == 1 or 'data' not in bs:
            arr[y0:y0 + 16] = gid[0]
            continue
        bits = max(4, math.ceil(math.log2(len(pal))))
        per = 64 // bits
        longs = np.asarray(bs['data'], dtype=np.uint64)
        shifts = (np.arange(per, dtype=np.uint64) * np.uint64(bits))
        vals = (longs[:, None] >> shifts[None, :]) & np.uint64((1 << bits) - 1)
        idx = vals.reshape(-1)[:4096].astype(np.int64)
        arr[y0:y0 + 16] = gid[idx].reshape(16, 16, 16)
    return arr, lo * 16


# ---------------------------------------------------------------- per region

def survey_region(job):
    golden, blasted, rx, rz, gx, gz, radius, ring = job
    name = 'r.%d.%d.mca' % (rx, rz)
    g = region_chunks(os.path.join(golden, 'region', name))
    b = region_chunks(os.path.join(blasted, 'region', name))
    ids, names = {}, []
    nr = int(radius // ring) + 1
    total = np.zeros((nr, len(KINDS)), dtype=np.int64)
    kept = np.zeros((nr, len(KINDS)), dtype=np.int64)
    added = np.zeros((nr, len(KINDS)), dtype=np.int64)
    top = np.full((512, 512), -10_000, dtype=np.int32)
    topname = np.full((512, 512), -1, dtype=np.int32)
    gname = np.full((512, 512), -1, dtype=np.int32)
    chunks = 0
    for (lx, lz), raw in b.items():
        cx, cz = (rx << 5) + lx, (rz << 5) + lz
        d = math.hypot(cx * 16 + 8 - gx, cz * 16 + 8 - gz)
        if d > radius + 12 or (lx, lz) not in g: continue
        ba, blo = blocks(raw, ids, names)
        ga, glo = blocks(g[(lx, lz)], ids, names)
        if ba is None or ga is None or blo != glo or ba.shape != ga.shape: continue
        chunks += 1
        lut = np.array([KINDS.index(k) if (k := kind(n)) in KINDS else -1 for n in names], dtype=np.int32)
        solid = np.array([kind(n) not in ('air', 'fluid') for n in names], dtype=bool)
        xs = np.arange(16) + cx * 16 + 0.5 - gx
        zs = np.arange(16) + cz * 16 + 0.5 - gz
        dist = np.hypot(xs[None, :], zs[:, None])
        rings = np.minimum((dist // ring).astype(np.int64), nr - 1)
        inside = dist <= radius
        gk, bk = lut[ga], lut[ba]
        same = ga == ba
        rr = np.broadcast_to(rings, ga.shape)
        ins = np.broadcast_to(inside, ga.shape)
        m = (gk >= 0) & ins
        np.add.at(total, (rr[m], gk[m]), 1)
        mk = m & same
        np.add.at(kept, (rr[mk], gk[mk]), 1)
        ma = (bk >= 0) & ~same & ins
        np.add.at(added, (rr[ma], bk[ma]), 1)
        # column tops of the blasted world (solid, not air or fluid)
        sb = solid[ba]
        has = sb.any(axis=0)
        t = sb.shape[0] - 1 - np.argmax(sb[::-1], axis=0)
        zz, xx = np.nonzero(has)
        top[lz * 16 + zz, lx * 16 + xx] = t[zz, xx] + blo
        topname[lz * 16 + zz, lx * 16 + xx] = ba[t[zz, xx], zz, xx]
        gname[lz * 16 + zz, lx * 16 + xx] = ga[t[zz, xx], zz, xx]
    # spikes: top at least 5 above all 8 neighbors (region edges skipped)
    spikes = []
    c = top[1:-1, 1:-1]
    nb = np.max(np.stack([top[1 + dz:511 + dz, 1 + dx:511 + dx] for dz in (-1, 0, 1) for dx in (-1, 0, 1) if dz or dx]), axis=0)
    sel = (c > -10_000) & (nb > -10_000) & (c - nb >= 5)
    for zz, xx in zip(*np.nonzero(sel)):
        x, z = (rx << 9) + xx + 1, (rz << 9) + zz + 1
        dd = math.hypot(x + 0.5 - gx, z + 0.5 - gz)
        if dd > radius: continue
        tn = names[topname[zz + 1, xx + 1]]
        gn = names[gname[zz + 1, xx + 1]] if gname[zz + 1, xx + 1] >= 0 else '?'
        spikes.append((int(dd), x, int(c[zz, xx]), z, int(c[zz, xx] - nb[zz, xx]), tn, gn))
    return chunks, total, kept, added, spikes


def column(world, x, z):
    rx, rz = x >> 9, z >> 9
    raw = region_chunks(os.path.join(world, 'region', 'r.%d.%d.mca' % (rx, rz))).get(((x >> 4) & 31, (z >> 4) & 31))
    if raw is None: return 'not on disk'
    ids, names = {}, []
    arr, lo = blocks(raw, ids, names)
    if arr is None: return 'not a full chunk'
    col = arr[:, z & 15, x & 15]
    runs, start = [], 0
    for y in range(1, len(col) + 1):
        if y == len(col) or col[y] != col[start]:
            n = names[col[start]]
            if n not in AIR: runs.append('%d..%d %s' % (start + lo, y - 1 + lo, n.split(':')[-1]) if y - 1 > start else '%d %s' % (start + lo, n.split(':')[-1]))
            start = y
    return '; '.join(runs[-14:])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('golden')
    ap.add_argument('blasted')
    ap.add_argument('gx', type=float)
    ap.add_argument('gz', type=float)
    ap.add_argument('--radius', type=float, default=4000)
    ap.add_argument('--ring', type=float, default=250)
    ap.add_argument('--procs', type=int, default=4)
    ap.add_argument('--spot', action='append', default=[])
    a = ap.parse_args()
    jobs = []
    for rx in range(int((a.gx - a.radius) // 512) - 1, int((a.gx + a.radius) // 512) + 2):
        for rz in range(int((a.gz - a.radius) // 512) - 1, int((a.gz + a.radius) // 512) + 2):
            if os.path.exists(os.path.join(a.blasted, 'region', 'r.%d.%d.mca' % (rx, rz))):
                jobs.append((a.golden, a.blasted, rx, rz, a.gx, a.gz, a.radius, a.ring))
    nr = int(a.radius // a.ring) + 1
    total = np.zeros((nr, len(KINDS)), dtype=np.int64)
    kept, added = total.copy(), total.copy()
    spikes, chunks = [], 0
    with ProcessPoolExecutor(a.procs) as ex:
        for n, (c, t, k, ad, sp) in enumerate(ex.map(survey_region, jobs)):
            chunks += c
            total += t
            kept += k
            added += ad
            spikes += sp
            print('region %d/%d' % (n + 1, len(jobs)), file=sys.stderr)
    print('Chunks compared: %d (both worlds full, within %.0f m of %.0f %.0f)' % (chunks, a.radius, a.gx, a.gz))
    print('\nShare of untouched built blocks still in place, by ring (count in thousands):')
    print('ring m      ' + ' '.join('%10s' % k[:10] for k in KINDS))
    for r in range(nr):
        if total[r].sum() == 0: continue
        cells = []
        for k in range(len(KINDS)):
            cells.append('%4.0f%%/%-4s' % (100 * kept[r, k] / total[r, k], fmt(total[r, k])) if total[r, k] >= 50 else '%10s' % '-')
        print('%5d-%-5d ' % (r * a.ring, (r + 1) * a.ring) + ' '.join('%10s' % c for c in cells))
    print('\nNew blocks where the untouched world had something else (rubble, fallen logs), thousands:')
    for r in range(nr):
        if added[r].sum() == 0: continue
        print('%5d-%-5d ' % (r * a.ring, (r + 1) * a.ring) + ' '.join('%10s' % fmt(added[r, k]) for k in range(len(KINDS))))
    print('\nSpikes (one-block columns >= 5 above all 8 neighbors), by ring: total and top blocks')
    by = defaultdict(list)
    for s in spikes: by[int(s[0] // a.ring)].append(s)
    for r in sorted(by):
        cnt = Counter(s[5].split(':')[-1] for s in by[r])
        same = sum(1 for s in by[r] if s[5] == s[6])
        print('%5d-%-5d %6d  (top block unchanged %d)  %s' % (r * a.ring, (r + 1) * a.ring, len(by[r]), same,
                                                          ', '.join('%s %d' % kv for kv in cnt.most_common(6))))
    print('\nTallest spikes: distance m, x y z, height above neighbors, top block (untouched block there)')
    for s in sorted(spikes, key=lambda s: -s[4])[:40]:
        print('%6d  %d %d %d  +%d  %s (%s)' % (s[0], s[1], s[2], s[3], s[4], s[5].split(':')[-1], s[6].split(':')[-1]))
    for spot in a.spot:
        x, z = (int(v) for v in spot.split(','))
        print('\nColumn %d %d (%.0f m):' % (x, z, math.hypot(x + 0.5 - a.gx, z + 0.5 - a.gz)))
        print('  untouched: ' + column(a.golden, x, z))
        print('  blasted:   ' + column(a.blasted, x, z))


def fmt(n):
    return '%.0f' % (n / 1000) if n >= 1000 else ('%.1f' % (n / 1000) if n else '0')


if __name__ == '__main__':
    main()
