# RAIN2 independent read-only diagnosis

Scope: source and existing raw frames/logs only. No production patch, source edit, job, GPU, server or network action by the reviewer. The two private uncommitted diagnostic files were inspected as instrumentation, not reviewed as a proposed production change. Author reports, inbox, STATUS and author conclusions were not read.

## Findings

**No serious rain alpha/blend defect is established. A production patch to the rain-producing code is not warranted by these data.** This does not certify the intended appearance or establish that the original city view is correct.

1. The original `corrected15kt-diag-post-window.png` and `post-elevated.png` show local streaks over a nearly uniform field. They do not show what the framebuffer contained immediately before the rain pass, so they cannot attribute the missing city to rain compositing. The preflight frame has a different nuclear state and cannot supply that control.
2. The no-event forced-rain close-wall frames (`sandbox/.../baseline_001.00s.png`, `rain2/fullpack/.../fullpack-baseline_001.00s.png`) visibly retain masonry behind the streaks. The `fullpack-far_001.00s.png` frame also retains ground and the small blast. These contradict an unconditional opaque full-screen rain-sheet failure on the tested paths.
3. The aged-event controls strengthen the distinction: `aged-no-drops-horizontal_001.00s.png` still has an opaque brown field and ash with rain geometry explicitly skipped. `aged-rain-off_001.00s.png` remains opaque with both rain geometry and forced rain intensity removed, retaining the nuclear event. Clearing that event restores the sky (`cleared-rain-off_001.00s.png`). The larger-distance camera controls show terrain with the event cleared (`cleared-level_001.00s.png`) and a brown field plus streaks with the event present (`aged-level-both_000.10s.png`). The last control is one frame, not a completed capture. Thus rain geometry is unnecessary for the hidden-background result in this synthetic setup; the retained nuclear state is sufficient.

## Source and alternate causes

- `FarDraw.setup/blend` selects `position_tex_color`, enables blending and sets the default straight-alpha blend (`FarDraw.java:98–114`). The captured fullpack fragment shader outputs texture multiplied by vertex color and discards alpha below 0.1; it does not replace alpha with 1. The repository rain texture has 15,542/16,384 zero-alpha texels (94.86%). The inspected cached Minecraft `ShaderInstance.apply` does not override blend state. The private GL_BLEND log is sampled before the draw, so it alone is not a complete draw-state capture; the visual controls provide stronger practical evidence.
- `NukeSky.fog` explicitly contracts world fog to at most 48 blocks at full rain intensity (`NukeSky.java:121–125`), and rain also changes fog color. Farther geometry can disappear while drops within 10 blocks remain visible. Original elevated-camera terrain is not a near-geometry control.
- The nuclear pall has a separate volume/composite path before the rain; its alpha is transmittance and its composite uses `ONE, SRC_ALPHA` (`PallPass.java:713–719`). It can extinguish the background independently. The event-retained, rain-off control is consistent with that path being sufficient. These controls do not isolate the individual pall components or demonstrate a wrong field density, wrong depth input, or wrong composite operation.

## Evidence limits and decisive discriminator

The synthetic event is a client-synced 15 kt ground burst initially aged 420 seconds, on flat terrain with forced rain intensity; it is not the original city/pin/runtime state. Event age continues between captures. No exact color, opacity or performance A/B claim is supported.

The earlier controls labeled `horizontal` use camera distance 1, height 43, look 43. `Sandbox.placeCamera` teleports feet at eyeY minus 1.62 and uses unanchored `facing`, so that setup looks roughly 58 degrees upward. The later distance-60 controls reduce this to about 1.5 degrees. The label does not establish a horizon view.

If the original complaint is reopened, the decisive test is a fixed original eye/event/age with a known nearby contrast object, capturing immediately before and after the rain pass, then geometry-only-off, rain/fog-only-off, and pall-only-off controls. A vanished background already present before rain rules out rain as its producer. Recovering the object only when pall is disabled identifies that path for density/depth diagnosis; recovering it only when rain fog is disabled identifies the fog path. Capture actual draw blend factors/equation and texture binding only if the rain pass itself demonstrably erases existing background. Do not change alpha, blending, density or fog merely to make a blank background reveal the city.

No production correctness or pipeline defect was proven in the reviewed scope. No canonical memory update: the result is a bounded diagnostic finding, not an accepted visual change.
