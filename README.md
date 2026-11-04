# RAIN2: opaque post-nuclear image diagnosis

2026-11-04. Source base `7bdb5b2c1` (`codex/black-rain-visibility`). No production change proposed.
Nuclear client, effects-layer and FarDraw source trees have no diff against the original actual-trial source `ad13fe2bb926e6c95ed2c30cc31efb01af0537b9`.

## Question and result

The actual LEAD2 15 kt ground-burst screenshots at camera Y106 and Y250 show vertical dark rain over an apparently opaque field. They do not isolate whether the rain, world fog, or nuclear volume hides the city.

Rain alone does not produce an opaque sheet in either the Iris-only sandbox or a fresh full-pack flat-world client. A close wall and distant ground remain visible behind the drops. The runtime-selected shader is `position_tex_color`, class `net.minecraft.client.renderer.ShaderInstance`, and GL_BLEND is enabled immediately after FarDraw.setup. The GPU fragment source was obtained from the actual attached fragment shader via `glGetAttachedShaders`/`glGetShaderSource`. It samples texture alpha, multiplies vertex color and discards alpha below0.1. In the full pack Veil preprocesses this shader but retains those operations. These observations do not capture blend state at the original city's actual draw.

A separate full-pack fixture receives a past detonation through standard `ClientNuclear.sync`, then independently bypasses `drawBlackRain` or forces `NukeSky.rain` to zero. The opaque brown nuclear backdrop remains in both controls. Clearing the nuclear event returns blue sky. Thus rain geometry and its intensity/fog are not necessary for this fixture's hidden-world result; the remaining nuclear volume/lighting path is responsible. Existing PallField/DustModel/PallPass source describes optically thick post-wave dust at this age. This evidence does not establish that the original city's density is physically correct or certify its exact frame.

A blend/custom-shader change would therefore be speculative. No rendering, physics or visibility numbers were changed.

## Frames

- `original-*`: raw actual LEAD2 screenshots; original jar SHA256 `6fd6b17dcf31e8ce0da5eae1bd8c199898858239ea4bc2545050783f9f6d3024`.
- `iris-only-near`, `fullpack-rain-near`, `fullpack-rain-far`: forced rain=1, no nuclear event; same original rendering code plus private logging.
- `aged-both-upward`: aged nuclear event, forced rain=1, drops enabled.
- `aged-no-drops-upward`: same event/eye, forced rain=1, drawBlackRain bypassed.
- `aged-rain-zero-upward`: same event/eye, forced rain=0 and drawBlackRain bypassed.
- `cleared-rain-zero-upward`: event cleared, same eye/rain=0/drops bypassed.
- `cleared-level`, `aged-level`: 60 m camera, approximately level gaze, illustrating clear world versus vertical streaks over an opaque nuclear backdrop. The aged capture has only its0.1 s frame: the fixed240 s client deadline occurred before1 s. Do not treat it as a complete capture.

## Fixture and boundaries

`diagnostic.patch` contains the complete private instrumentation, now restored out of production source. A file under the isolated client's `fx/aged-event` injects one past15 kt surface event: HOME(0.5,65,0.5)+(-32,0,1009), initial age420 s, scale1, wave0.25, wind direction0/speed5, visibility20000, seed1234, dayTime6000, fallout/fires enabled. This roughly matches original range/height, not original city terrain, seed, wind or clock. The actual original burst was(3104,63,560), observer near(3136,106,-449).

`fx/geometry-off` bypasses only drawBlackRain; `fx/rain-off` forces the private field to zero each RenderFrameEvent.Pre; absent marker forces1. No server detonation, terrain destruction, Crafty operation or player-world change occurs. A spent booster at200 m only starts the existing screenshot clock. It is not a nuclear event and is not the experiment's time origin.

The first controls used `cam 1 43 180 43`, which looks about58 degrees upward because Sandbox's tp-facing target is computed from the player's FEET while its height argument specifies the eye. The historical 'horizontal' directory names are wrong; evidence filenames explicitly say upward. The final camera uses60 m distance and therefore looks about1.5 degrees upward. Sequential captures have changing detonation age, dust/light and ash positions, so these are qualitative causal controls, not pixel-exact or photometric comparisons. No audio judgment or frame-cost claim is made.

Each full-pack run has its own copied instance and fresh flat world; mod hashes, iris config, raw render-log excerpts and capture metadata are preserved here. Full raw logs and test jars remain in the ignored worktree's mod/run/rain2 directory. Startup logs use a different timezone from UTC shell time. Both full-pack clients ended at their240 s wrapper deadlines (expected143); the Iris-only sandbox was stopped through its wrapper. GPU lease released2026-11-04 15:01:43 UTC; no owned clients/jobs remain.

## Repeated check

On a fresh disposable checkout, apply only diagnostic.patch and use the existing `tools/laptop_job.sh` + `tools/prod_client.py sandbox` + `tools/nested_kwin.sh` path. Set SESSION_TREES and INCOMING_GPU_OWNER explicitly. Do not rerun over an existing sandbox save: preserve it first. At world readiness, use the existing fx/inbox file commands for a close wall and an elevated camera. Arm `shots <name> 0.1,1 2`, then `spent drone 200`. Save rain-only frames; create aged-event; save drops-on, geometry-off, rain-off and cleared-event controls. Check each .done result for errors and each capture.json for actual frame completion. No inferred camera or image-size assumptions.

## Validation scope

Private instrumented code compiled successfully through scenarioJar and ran with active ComplementaryUnbound5.9.3 on both clients. Production checkout is restored byte-for-byte and clean. No production patch means no code PR, production CI, touched GameTest or scene bench is applicable. Independent fresh Sol xhigh source/evidence review completed with no serious rain alpha/blend defect established; see REVIEW.md. This does not certify the overall15 kt trial, near-ruin publication or release readiness.
