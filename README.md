# MT B4 targeting/confirmation evidence

Production head: f3cee71e7. Captured on 3 November 2026, zentixua-pc, leased headless sandbox, mod-only client, 1280×692.

- `console.png`: integer yield visible, kt unit and server-capped 1/5/15 kt suggestions.
- `map-air.png`, `map-ground.png`: Fit effects; model contours and legend separated from the uncharted-terrain notice. No distant terrain source installed; unknown terrain stays uncharted.
- `review-air.png`: 15 kt, airburst height 592 m, 5 psi 1.61 km, 1 psi 4.46 km, glazing reference 7.19 km, exposed-skin first-degree median 3.36 km, distance and downwind arrow. The final three-second hold is the only launch confirmation.
- `review-ground.png`: surface reference radii. This earlier frame predates the arrowhead refinement visible in `review-air.png`; no physics changed.
- `main-guide-console-reference.png`: existing main guide image, for historical UI reference only. It is not a new paired same-camera baseline capture.
- `keyboard.log`: dev-only scripted screen events exercise focused Space beginning the hold and release cancelling it. No actual launch is issued by this fixture.
- `build.log`: full build and all 1,745 unit tests passed. `package.log` rebuilds the final package after the GUI-only visual fixes without repeating unchanged tests. `gametest.log`: all 9 nuclear review/key tests passed. Tests exercise cancellation, early/released hold rejection, restart-like attachment loss, revised order IDs, cap reduction, permission policy, failed payment, one paid commit only, no extra loaded world chunks, and existing second-key/alarm/refund behavior.

UI fixtures use real production screens and 15 kt loadouts. 100/1000 kt are pure-model/unit-test inputs only: the public hard/config ceiling remains 15 kt. No higher-yield UI/runtime acceptance is claimed. No audio path changed, so no audio comparison is required. No world effects rendering changed.

The PR is a fallback targeting/confirmation increment. Pre-launch preparation/ready states, durable reservation, reusable preparation, ETA, terrain/LOD revision readiness, cold observers and carrier contingencies remain unimplemented until an admission contract exists. These screenshots do not certify those requirements.
