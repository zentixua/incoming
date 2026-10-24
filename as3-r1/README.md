# AS3 revision 1: damage that remains visible

Original sandbox PNG and WAV for [Incoming PR #437](https://github.com/zentixua/incoming/pull/437).

- After: 77334a037127cdb05c44ca7e8451ce6b0e11ec42.
- Main: cf8fa358d39b56148f95643cae9fbcb19a92b7b7. Its production code is unchanged. Only the same scene helpers were added; exact patch: main-fixtures.patch.
- Main pairs: Shahed on 1 m black-concrete paving over earth (`real drone paved`); Lancet on 1 m gray-concrete bridge deck over a river trench (`real loiter concrete 1`). Identical impact sites, fresh flat world seed 20261022, midday and camera `18 12 0 0`.
- Frames at 1, 10 and 60 s; each WAV is the first 6 s, original 48 kHz stereo. capture.json gives the measured age of every frame. Particles and debris positions vary between runs; compare the surface and opening.
- Detail pair: `real drone paved`, camera `4 8 90 0`, frame at 10 s. The partial patch shows exposed aggregate and chips; the remaining block keeps collision. These static fragments approximate a fractured surface, not a ballistic fragment simulation or sub-voxel collision mesh.
- Distance supplement: `real drone paved`, camera `48 18 0 0`, frame at 10 s (~51 m slant range). The saved fragments use ordinary vanilla entity tracking, beyond the 32 m crack-packet radius; angular size is small at this distance.
- Development sandbox, vanilla renderer, no Iris, host quiet profile left unchanged. This is a terrain-behavior comparison, not a frame-budget or full-pack look gate.
- The surface benchmark's 10.5 m³ is the sum over 25 Shahed impacts (~0.42 m³ per hit). This is fractional damage, not a 10.5 m³ hole from one impact. Whole metres are still removed where the crater law reaches them.

provenance.json records hashes, audio measurements, camera/sites and actual capture ages. No image editing, resizing or audio mastering.
