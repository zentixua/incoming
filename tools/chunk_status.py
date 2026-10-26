#!/usr/bin/env python3
"""Which world chunks on disk are fully generated: histogram of the Status field in a square around a point.

Only reads region files (region/r.X.Z.mca), never changes the world; can run on a copy or while the game is open.
For non-FULL chunks: number of nonempty blocks (whether buildings exist) and recorded heightmaps; pre-1.18 format
and FULL chunks with below-zero retrogen are separate (mod does not build disk copies from these); non-FULL by
distance to nearest FULL: rings at the explored-world edge or places where the world was never generated.

    python3 tools/chunk_status.py <world folder> <block x> <block z> <radius in chunks> [dimension: overworld|nether|end]
"""
import io
import os
import struct
import sys
import zlib
import gzip
from collections import Counter, defaultdict


def read_nbt(data: bytes):
    """Root tag NBT (compression already removed): dictionaries, lists, numbers, strings, arrays."""
    buf = io.BytesIO(data)

    def u(fmt):
        return struct.unpack('>' + fmt, buf.read(struct.calcsize('>' + fmt)))[0]

    def string():
        n = u('H')
        return buf.read(n).decode('utf-8', 'replace')

    def payload(t):
        if t == 1: return u('b')
        if t == 2: return u('h')
        if t == 3: return u('i')
        if t == 4: return u('q')
        if t == 5: return u('f')
        if t == 6: return u('d')
        if t == 7: return buf.read(u('i'))
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
        if t == 11: return list(struct.unpack('>%di' % (n := u('i')), buf.read(4 * n)))
        if t == 12: return list(struct.unpack('>%dq' % (n := u('i')), buf.read(8 * n)))
        raise ValueError('tag %d' % t)

    t = u('b')
    string()
    return payload(t)


def chunk(region_dir, cx, cz, cache):
    rx, rz = cx >> 5, cz >> 5
    key = (rx, rz)
    if key not in cache:
        path = os.path.join(region_dir, 'r.%d.%d.mca' % key)
        cache[key] = open(path, 'rb').read() if os.path.exists(path) else None
    data = cache[key]
    if data is None or len(data) < 8192:
        return None
    i = 4 * ((cx & 31) + (cz & 31) * 32)
    off = int.from_bytes(data[i:i + 3], 'big') * 4096
    if off == 0:
        return None
    length = int.from_bytes(data[off:off + 4], 'big')
    kind = data[off + 4]
    raw = data[off + 5:off + 4 + length]
    if kind & 0x80:
        return 'external file'
    if kind == 2: raw = zlib.decompress(raw)
    elif kind == 1: raw = gzip.decompress(raw)
    elif kind == 4: return 'lz4'
    return read_nbt(raw)


def main():
    if len(sys.argv) < 5:
        print(__doc__)
        sys.exit(2)
    world, bx, bz, r = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
    dim = sys.argv[5] if len(sys.argv) > 5 else 'overworld'
    region = os.path.join(world, {'overworld': 'region', 'nether': 'DIM-1/region', 'end': 'DIM1/region'}[dim])
    cx0, cz0 = bx >> 4, bz >> 4
    cache, status, blocks, maps, samples = {}, Counter(), defaultdict(list), Counter(), defaultdict(list)
    # for a copy with ruins from disk (mod: DiskShots) only FULL of the new format is suitable without additional generation from scratch
    full, partial, versions, retrogen = set(), {}, Counter(), 0
    for cx in range(cx0 - r, cx0 + r + 1):
        for cz in range(cz0 - r, cz0 + r + 1):
            c = chunk(region, cx, cz, cache)
            if c is None:
                status['not on disk'] += 1
                partial[(cx, cz)] = 'not on disk'
                continue
            if isinstance(c, str):
                status[c] += 1
                continue
            versions[c.get('DataVersion', '?')] += 1
            s = c.get('Status')
            if s is None and isinstance(c.get('Level'), dict):
                # format up to 1.18: everything is inside Level - the mod does not read it, vanilla will update when loading
                s = 'up to 1.18: ' + str(c['Level'].get('Status', '?'))
            s = s or '?'
            if s == 'minecraft:full' and 'below_zero_retrogen' in c:
                retrogen += 1
                s = 'minecraft:full + additional generation at zero'
            status[s] += 1
            if s == 'minecraft:full':
                full.add((cx, cz))
            else:
                partial[(cx, cz)] = s
            if s != 'minecraft:full':
                solid = 0
                for sec in c.get('sections', []):
                    pal = sec.get('block_states', {}).get('palette', [])
                    if any(p.get('Name') != 'minecraft:air' for p in pal):
                        solid += 1
                blocks[s].append(solid)
                maps[(s, tuple(sorted(c.get('Heightmaps', {}).keys())))] += 1
                if len(samples[s]) < 3:
                    samples[s].append((cx, cz))
    total = sum(status.values())
    print('chunks in a square: %d (center %d %d, radius %d)' % (total, cx0, cz0, r))
    for s, n in status.most_common():
        line = '  %-28s %6d' % (s, n)
        if s in blocks:
            b = blocks[s]
            line += '  non-empty sections on average %.1f, without blocks %d, examples %s' % (sum(b) / len(b), sum(1 for x in b if x == 0), samples[s])
        print(line)
    for (s, keys), n in maps.most_common():
        print('  height maps for %s: %s - %d' % (s, ', '.join(keys) or 'no', n))
    print('data version (DataVersion): %s' % ', '.join('%s — %d' % (v, n) for v, n in versions.most_common(6)))
    if retrogen:
        print('FULL with additional generation at zero (the world was raised from 1.17 and the chunk has not loaded since then): %d' % retrogen)
    # rings around the explored: at the edge of FULL there are under-generated chunks - 1: light (blocks and their own parts are there),
    # 2: caves (no trees), 3: biomes (no terrain), then - the beginnings of structures or nothing
    rings = defaultdict(Counter)
    for (cx, cz), s in partial.items():
        d = next((k for k in range(1, 9) if any((cx + i, cz + j) in full for i in range(-k, k + 1) for j in (-k, k))
                  or any((cx + i, cz + j) in full for j in range(-k + 1, k) for i in (-k, k))), None)
        rings['further 8' if d is None else d][s] += 1
    print('not FULL by distance to the nearest FULL (chunks):')
    for d in sorted(rings, key=lambda k: (isinstance(k, str), k)):
        print('  %-9s %s' % (d, ', '.join('%s — %d' % (s, n) for s, n in rings[d].most_common())))


if __name__ == '__main__':
    main()
