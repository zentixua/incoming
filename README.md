# Latest: AS3 revision 1

See [as3-r1](as3-r1/README.md) for the direct main comparison (1/10/60 s), saved visible damage, detail and distance views. The as3/ folder below is the previous review round.

# AS3: supported paving and a thin concrete slab

Original sandbox PNG and WAV for [Incoming PR #437](https://github.com/zentixua/incoming/pull/437).

- Road: `real drone paved`; one metre of black concrete resting on flat earth.
- Slab: `real loiter concrete 1`; one metre of gray concrete over the sandbox river trench.
- Each JVM creates a new flat world with seed 20261022. Same scene order, impact sites and camera (`18 12 0 0`), midday. Frames at 0.1, 1, 10 and 60 seconds; WAV is the first 6 seconds. Actual frame ages are in each capture.json.
- Development sandbox, vanilla renderer, no Iris; quiet power profile. These are terrain-behavior comparisons, not performance measurements or the separate full-pack look gate.
- After: 1a131e23f5019c84939de912d467112824059938. Before: the same tree and scene helpers, with StagedExplosion.java, Craters.java, GroundMaterial.java and CraterJob.java restored exactly from e592f2ff. This isolates the old surface physics while retaining identical presentation code.
- The later per-impact RNG isolation changes loose-soil sampling/reproducibility. It does not change the cohesive-depth or slab-opening laws demonstrated here. Source attribution is deliberately the capture commit, not a later commit.
- Observed at 60 s: the old road has an open crater and the old slab a one-block hole. Both corrected surfaces remain closed. Black-on-black paving cracks are not legible at this camera distance; saved fractional depth and intact collision are covered by GameTests.

`as3/provenance.json` records hashes, audio measurements and capture metadata. No image retouching, resizing or audio mastering.
