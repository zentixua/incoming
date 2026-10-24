# DU2 review round 1 — paved street and forest sources

PR: https://github.com/zentixua/incoming/pull/416
Baseline: main `4498ecfc`. Candidate: `1bd1cae5`.
The previous 3.4.1/full-pack comparison remains a separate coordinated task.

Both versions use the same deterministic sandbox geometry and camera per scene, midday, no shaders, seed 1.
The laptop remains in the owner's quiet power profile. These are visual comparisons, not performance claims.

The baseline has only the `house` / `scenery` test commands and capture clock instrumentation copied in.
For the stone roof its blast packet reports the roof as the surface: the baseline's actual `groundUnder` returns
that for stone. Production rendering/physics are unmodified. Both temporary harness patches are included.
`time …` also resets game time to 6000 and waits for its packet, matching saved-world wind rather than just daylight;
input air is logged. The candidate's temporary harness is restored after recording.

Initialise/remove the maximum allowed house once after starting each client. The sandbox saves its world but not
the command's in-memory house dimensions, so this clears any house left by the previous capture session:

```sh
tools/sandbox.sh fx 'house 50 12' 'house 0' 'wait 80'
```

A 97×97 stone-paved square surrounds the first two impacts. The forest has deterministic oak-log trunks and
persistent leaf crowns every eight metres, a central opening, and grass below. It is a material/occlusion fixture,
not a reconstruction of Newisle. The leaf canopy remains in the client-only blast scene on both versions.

Commands, on each version:

```sh
tools/sandbox.sh fx 'house 0' 'scenery paved' 'house 15 10 stone' 'wait 80'
tools/sandbox.sh shot paved-roof 'blast missile 1 15' --cam '65 10 90 8' --at 0.1,1,10,60 --wav 4 --no-swap

tools/sandbox.sh fx 'house 0' 'scenery paved' 'wait 80'
tools/sandbox.sh shot paved-hit 'blast missile 1' --cam '65 10 90 8' --at 0.1,1,10,60 --wav 4 --no-swap

tools/sandbox.sh fx 'house 0' 'scenery forest' 'wait 80'
tools/sandbox.sh shot forest-hit 'blast missile 1' --cam '65 2 73 2' --at 0.1,1,10,60 --wav 4 --no-swap
tools/sandbox.sh shot forest-close 'blast missile 1' --cam '25 2 73 2' --at 0.1,1,10,60 --wav 4 --no-swap
tools/sandbox.sh shot forest-downwind 'blast missile 1' --cam '65 2 235 2' --at 0.1,1,10,60 --wav 4 --no-swap
```

`main/` and `du2/` retain original PNGs, 4 s / 48 kHz WAVs, capture timing and input-air logs.
Forest wide, close and downwind views retain the same trees; they expose occlusion rather than clearing a sightline.
Timeline sheets resize the originals and add labels; 10 s details use identical central crops, at native scale.
The original full frames remain available for checking occlusion and the complete scene.

Check the low brown material separately from the dark rising cap and grey fuel plume. Main's old surface model
gave paving the 15 kg/m² soil supply. The candidate leaves deposited HARD loading at 0.0006 kg/m² and instead feeds
low dust with the cold part of its finite contact-crushing budget. On a roof this must descend rather than remain
at roof height; under leaves the wave must sample the floor rather than a gram-scale hard crown.

Important model limit: the equal hot/cold split is an explicit two-stream estimate, not a measured conventional
explosion correlation. Total produced crushed-material dust is unchanged; this fixes its missing low component.
The existing crater-equivalent volume at a building impact is still an estimate, not a server block-removal count.
The accepted Huppert–Simpson spreading, dense-thermal/lee descent and Stokes settling laws are retained.

## Observed comparison

- Paved roof, 10 s: main has the brown sheet next to the roof; DU2 has a distinct brown current down at the
  surface beside the house. At 0.1/1 s the old broad roof-height disc is absent. The later grey fuel plume is separate.
- Direct paved hit, 10 s: a low brown current remains visible in DU2, with HARD unchanged. At 60 s the brown
  component has thinned; the grey/black fuel plume remains on both versions. No claim of identical dust density.
- Forest, close view, 1 s: both versions retain the low ring between the trunks. At 10 s this upwind view is
  occluded; the downwind view shows the candidate's brown haze crossing the camera, unlike main's clear foreground.
  At 60 s it has passed/thinned. The wide view is retained to make that occlusion apparent.

All 40 original frames are within 17 ms of the requested time. Each of ten WAVs is exactly 192000 frames at
48000 Hz (4 s); sound code is unchanged. Input wind/stability/humidity are recorded for each blast.
