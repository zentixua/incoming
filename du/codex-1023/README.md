# DU2: dust after a roof hit

The PR is <https://github.com/zentixua/incoming/pull/416>.

Baseline: `e592f2ff` (main rendering/physics; the later main commit `3a8b6eb1` adds only AGENTS.md). Candidate: `a855ba38` (rebased DU2, including the CI fixture correction).
Both use the development sandbox, no shaders, daytime, the same seed and camera.
The laptop is in its owner's quiet power profile; these captures are visual evidence, not performance measurements.

The baseline has the candidate's `house` sandbox command added. Its blast packet sets `surfaceY` to the roof
for the stone roof and the soil roof: that is what baseline `Warheads.groundUnder` returns for those blocks.
Both capture harnesses reset game time to 6000 with `time …` (as the existing nuke-near scene does), wait for the
clock packet, and log the input air. This keeps the saved worlds' wind equal as well as their lighting.
The patches are included as `baseline-harness.patch` and `candidate-harness.patch`.
No production rendering or physics code is changed in either tree; the temporary candidate harness is restored after capture.

Commands (each capture clears previous effects):

```sh
tools/sandbox.sh fx 'house 15 10 stone' 'wait 40'
tools/sandbox.sh shot roof-stone 'blast missile 1 15' --cam '65 10 90 8' --at 0.1,1,10,60 --wav 4 --no-swap
tools/sandbox.sh fx 'house 15 12 coarse_dirt' 'wait 40'
tools/sandbox.sh shot roof-soil-wide 'blast missile 1 15' --cam '65 10 90 8' --at 0.1,1,10,30 --wav 4 --no-swap
tools/sandbox.sh fx 'house 0' 'wait 40'
tools/sandbox.sh shot ground 'blast missile 1' --cam '65 10 90 8' --at 0.1,1,10,60 --wav 4 --no-swap
```

`main/` and `du2/` contain the original PNG frames, WAV recordings and capture metadata.
The timeline sheets resize the originals and add labels. The 10 s detail pairs use the same central 800×420-pixel crop of each original, at native scale. Full frames are retained.

Look for the brown dust sheet outside the roof edge at 10 s, and whether ground dust still lies near the surface.
On the soil roof, dust entrained into the hot fireball rises with the cloud; cold dust outside it must descend.
The grey fuel-fire plume can remain near roof height initially because it is hot smoke, a different source.
Sound is recorded to document the scene; this PR changes no sound code.

This is the per-PR comparison against main. The coordinated, one-time comparison against 3.4.1 with the full modpack
is a separate task, as required by the current development pipeline.

## Reused sources

- [Explosion Overhaul](https://modrinth.com/mod/explosionoverhaul) documents falling debris and smoke trails.
- [Photon](https://modrinth.com/mod/photon-editor) documents particle and trail authoring. Neither page documents a
  terrain-following, dust-laden gravity-current model; a rendering dependency does not supply that missing law.
- [Huppert & Simpson, 1980](https://doi.org/10.1017/S0022112080000894): dense-current spreading.
- [EPA AP-42 §13.2.1, 2011, table 13.2.1-2](https://www.epa.gov/sites/default/files/2020-10/documents/13.2.1_paved_roads.pdf):
  0.6 g/m² baseline silt loading on low-traffic paved roads. Applying it to all hard surfaces is an estimate, already
  marked in `DustModel.Surface.HARD`; it is not a measurement of roof dust.
