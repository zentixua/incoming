# AS2: bounded surface presentation and fresh fractures

Before: `cd310f2b` (merged #437). After: `a9891ad674d830a4802522b99718bf80b2866895`.

Development sandbox, vanilla renderer, no Iris or full-pack capture. Fresh flat worlds, seed 20261022, noon.
Original 1280 × 692 PNG and 48 kHz stereo, 4 s WAV. No image edits or audio mastering. The host power profile was unchanged; this is not an FPS comparison.

Cameras use horizontal distance and height above the target: wide `18 5 90 0` (18.68 m slant range), close `4 3 90 0` (5 m slant range). Corresponding before/after scenes have identical sites and camera parameters.

- `road-wide-*`: Shahed on supported black concrete, 0.1 / 1 / 10 / 60 s.
- `road-close-*`: the same fixture, close view at 10 s.
- `slab-wide-*` and `slab-close-*`: Lancet on a one-metre concrete bridge slab, at 10 s.

Fresh pale aggregate and small material chips are inside a dark scorched edge. The residual slab keeps its collision. Conventional cosmetic chips no longer plant metre-sized blocks on the intact road. Static shapes approximate a fractured face rather than a ballistic simulation. The blast laws are unchanged.

See `provenance.json` for measured ages, sites, camera settings and SHA-256 hashes of all original PNG/WAV files. The early requested 0.1 s samples differ by up to 44 ms from their requested age; assess surface presentation using the 10/60 s frames. Particle/debris trajectories vary between runs. The scripts record all commands used.

Code and tests: https://github.com/zentixua/incoming/pull/440 . These evidence files live only on the orphan branch `look-gate/as2`; do not merge it.
