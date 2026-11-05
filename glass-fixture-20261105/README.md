# Watched progress fixture: enclosing-barrier correction

Source `fa2b09d3` → `2f9466177908f1e97b689928659058c300458593`, PR602. Only the existing GameTest and its area rule change. Production physics, memory admission, worker scheduling, pressure, deadline and final damage/material assertions remain unchanged.

The original full CI failed `everyWatchedWindowFinishesAtCapacityWithMaterialBacklog`: a queued chunk committed, but its glass block remained. The exact recorded 12-batch prefix and native starting grid reproduce that outcome. All 16 targets initially contain glass, have no scar marker and see the sky; pressure is about 1.176–1.199 psi. These facts alone do not establish horizontal blast exposure.

The failed target `(9925304,-22,-11516632)` lies on the template's maximum-Z boundary. Its west/east/up neighbors are the framework's `minecraft:barrier` blocks. An unpressured fresh physical plan already leaves this one glass intact, while the other 15 plans include removal. There is no evidence here of a queue dropping required physical damage.

The causal intervention changes only the two neighboring barrier faces to air at the original target, retaining its location, pressure and seed. Its reference then includes removal and the actual prefix passes 12/12, with all 16 watched scars committed, released material 8208 and competing backlog 8192. This is a fixture-only control, not the proposed production behavior.

The final fixture places its same 4×4 cohort at relative interior positions and asserts template containment and clear horizontal faces. Its original prefix passes 12/12; the same actual queue/damage/backlog checks remain. A mutant restoring the old positioning while retaining the new guard fails immediately with `Watched glass must stay inside the fixture`. The framework barrier is preserved in the final change.

The clean final source has no temporary selector or observations. Full local build: 1850 unit tests, zero failures/errors/skips; normal focused GameTest: 1/1. Independent review and exact required CI are recorded separately when complete. Controlled-clock tick counts and instrumented logs are not real player latency or CPU performance measurements.

Raw logs are in [raw/](raw/); exact temporary variants and selector are in [controls/](controls/). [Provenance](provenance.json) records the original CI, native sequence, positions and limitations; [manifest](manifest.json) binds the preserved files. The two native grid draws and batch order are controlled, but historical UUIDs, every random draw and asynchronous interleavings are not authenticated. The completed-only counter was not accepted in place of the unchanged actual-glass assertion.

This component does not approve main merge, the real 1 Mt/100 kt criteria, client/LOD delivery or player installation. It removes a demonstrated invalid fixture condition; the separate preparation-service failure has its own owner and evidence. Personal launchers and full test worlds are not included in this orphan branch.

Final closure: fresh independent [RGLASS7 review](independent-review.md) found no serious blocker in the exact two-file diff. [Required CI waiter](raw/pr-ci.log) completed green for PR602/head2f9466177. Root integrated this component into the readiness branch; no main or actual-runtime approval follows. Native raw logs retain their original whitespace.
