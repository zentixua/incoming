# AS2 revision 2 — debris persistence and depth-driven fractures

Code: `ce5aee5d5f1d9989d67c3c95021dbeca679e2a93`. Before comparison: `a9891ad6`, in the branch root.

The accepted noSave/256-display bound and finite front-loading wait are unchanged.

- Ground/charred fallout from an intact cohesive face is small chips. Large clods remain on loose ground; actual large airframe parts and burning debris use the ordinary placement path. `debris_stay=false` disables placement for all of them. Existing flame fuel checks remain.
- Saved depth is synchronized through vanilla display transformation and drawn by the vanilla DisplayRenderer. Footprint side is sqrt(depth), so face area is proportional to fractional depth; maximum chip height is min(0.15, depth × 0.3), as in #437. This is presentation mapping, not a new excavation law. Overlapping lobes give the edge an uneven contour.

The road/slab before and after cases have identical sites and cameras. Fresh flat worlds, seed 20261022, noon, vanilla development renderer without Iris. Close is 4 m horizontally / 3 m high; wide is 18 m horizontally / 5 m high. Controlled `surface` fixtures use the same material and camera at depths 0.01, 0.4 and 0.9, on separate identical flat patches without blast smoke or fire.

Original PNG and 48 kHz stereo WAV, no editing or mastering. `provenance.json` contains exact ages, positions, camera settings, dimensions, audio lengths and hashes. Maximum frame timing error is 0.018 s; for frames at 1 s or later it is 0.015 s. Particle/debris placement varies. No FPS comparison. The host power profile was unchanged.

Check out the code commit above before using `capture-r2.sh`. The script records the commands used; `provenance-r2.py` validates matched road/slab sites/cameras against revision 1.

Local build/unit tests and 87 affected GameTests passed, including setting on/off, actual ground/wreck spawners, native synchronized depth scale, entity limits and finite front wait. Full CI and note are green. Independent review: Claude, https://github.com/zentixua/incoming/pull/440 . Do not merge this evidence branch.
