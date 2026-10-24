# /// script
# dependencies = ["nbtlib"]
# ///
"""Read only: RV2's saved MOTION_BLOCKING columns, in Minecraft's packed order."""
import gzip, io, json, struct, zlib
from pathlib import Path
import nbtlib

world = Path('/mnt/data/projects/incoming/mod/run/look-gate/inst-RV2m/saves/incoming_scenario')
out = Path(__file__).parent
count = 0
with gzip.open(out/'rv2-heights.bin.gz', 'wb') as dest:
    for path in sorted((world/'region').glob('*.mca')):
        _, rx, rz, _ = path.name.split('.')
        rx, rz = int(rx), int(rz)
        if not (-10 <= rx <= 8 and -9 <= rz <= 8): continue
        with path.open('rb') as f:
            locations=f.read(4096)
            for idx in range(1024):
                cx, cz = rx*32+idx%32, rz*32+idx//32
                if abs(cx*16+8-(-416)) > 4200 or abs(cz*16+8-(-82)) > 4200: continue
                loc=int.from_bytes(locations[idx*4:idx*4+3], 'big')
                if not loc: continue
                f.seek(loc*4096)
                length=struct.unpack('>I',f.read(4))[0]
                compression=f.read(1)[0]
                data=f.read(length-1)
                if compression==2: data=zlib.decompress(data)
                elif compression==1: data=gzip.decompress(data)
                elif compression!=3: raise ValueError(compression)
                tag=nbtlib.File.parse(io.BytesIO(data))
                hm=tag.get('Heightmaps',{}).get('MOTION_BLOCKING')
                if hm is None: continue
                vals=[((int(hm[i//7]) & ((1<<64)-1)) >> ((i%7)*9) & 511)-64 for i in range(256)]
                dest.write(struct.pack('>ii256h',cx,cz,*vals)); count+=1
print(json.dumps({'world':str(world),'chunks':count,'heightmap':'MOTION_BLOCKING','min_y':-64,'packing':'7 values/long, 9 bits/value; value is first air Y','read_only':True}))
