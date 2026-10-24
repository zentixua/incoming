# blender -b --factory-startup --python vdb_proj.py -- <vdb dir> <out dir> frames...
import sys, os, glob, re
import numpy as np
import openvdb
argv = sys.argv[sys.argv.index("--") + 1:]
src, out, frames = argv[0], argv[1], [int(x) for x in argv[2:]]
files = sorted(glob.glob(os.path.join(src, "*.vdb")), key=lambda f: int(re.findall(r"(\d+)\.vdb$", f)[0]))
print("files", len(files))
for fr in frames:
    f = files[fr - 1]
    grids = [g.name for g in openvdb.readAllGridMetadata(f)]
    d = openvdb.read(f, "density")
    a, b = d.evalActiveVoxelBoundingBox()
    arr = np.zeros((b[0] - a[0] + 1, b[1] - a[1] + 1, b[2] - a[2] + 1), np.float32)
    d.copyToArray(arr, ijk=a)
    fl = 0.0
    if "flames" in grids:
        g = openvdb.read(f, "flames")
        if g.activeVoxelCount():
            fa, fb = g.evalActiveVoxelBoundingBox()
            farr = np.zeros((fb[0] - fa[0] + 1, fb[1] - fa[1] + 1, fb[2] - fa[2] + 1), np.float32)
            g.copyToArray(farr, ijk=fa)
            fl = float(farr.sum())
    np.save(os.path.join(out, f"proj_{fr:03d}.npy"), arr.sum(axis=1))
    print("frame", fr, "grids", grids, "bbox", a, b, "dens max", float(arr.max()), "sum", float(arr.sum()), "flames", fl, flush=True)
