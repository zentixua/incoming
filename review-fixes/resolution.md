# PR #558 review corrections

Reviewed head: `f3cee71e7eab2bd1c95061e8bbb064733515b2ab`.
Corrected head: `0cf3dd5206bd47ae48b93530e805f276a324aaf9`.

## Blocking B1: global nuclear abort

`ServerActions.clearAll(..., true, ...)` now invalidates all online players' unpaid review entries across dimensions before sending cancellation. Already queued COMMIT packets retain no server authority. A nuclear `S2C.Cleared` also closes `NuclearReviewScreen` and clears its hold; individual review completion uses the same screen-close path. No inventory had been reserved.

Regression: `globalNuclearAbortInvalidatesAnAlreadyHeldReview` requests a review, starts holding, invokes the privileged global nuclear clear path, observes the outgoing nuclear cancellation, waits 65 ticks and retries the old UUID. The review must be empty, COMMIT rejected, inventory unchanged and no owned nuclear event scheduled.

## Blocking B2: unpaid coordinate disclosure

Review resolution is separate from ordinary launch resolution. Map markers and reviews share invisible/spectator/dimension/self exclusions. Launch authorization is still checked separately. An unseen hostile player's permitted contact position is used even during `Sightings.CURRENT`; current live movement is never inferred from that grace period. A currently seen target, or an eligible friendly/target with sight rules disabled, retains the existing permitted current-position behavior. Crosshair/mounted player targets use the same disclosure check.

Packet regression: `unpaidReviewsSendNoCoordinatesForExcludedPlayers` captures actual outgoing `S2C.NuclearReview` payloads for real online ServerPlayers. All four cases first prove a visible positive control without any ammunition, then exclude the target and retry: invisible/spectator with sight rules disabled, and invisible/spectator teammates with sight rules enabled. No open review payload or stored review is allowed. `unseenHostileReviewUsesOnlyTheRememberedPositionEvenForAFreshContact` moves a hostile after recording a fresh sighting and checks that the payload contains only the recorded position.

## Blocking B3: death before respawn

A registered `LivingDeathEvent` handler invalidates review state at actual death. Requests and all actions also reject dead/removed actors before using the attachment. Respawn attachment replacement is no longer the invalidation mechanism.

Regression: `actualDeathWithRetainedInventoryInvalidatesBeforeRespawn` uses a real ServerPlayer and `hurt(genericKill, Float.MAX_VALUE)` with keepInventory enabled. It asserts immediate invalidation, then attempts the old COMMIT and a new review before respawning after 65 ticks. Both must fail, with retained inventory and no scheduled strike. FakePlayer is intentionally not used: its `die` override is a no-op.

## Optional findings

EN/RU labels and guide now call the fireball contour a reference radius at the second light maximum, explicitly allowing its later growth. Pressure/burn contours and all model numbers are unchanged. The shared guide console illustration now uses the already captured numeric-entry console frame; no new GPU session or world-rendering change was needed.

## Evidence

- `review-red.log`: all four new regressions fail on the reviewed implementation, at the intended abort/death/disclosure/fresh-contact assertions.
- `review-gametest.log`: all 13 review/second-key GameTests pass after corrections.
- `review-build.log`: full build and all 1,745 unit tests passed, zero failures/errors/skips. `review-final-gametest.log`: all 14 required tests passed, including the map-player filter regression.

No new packet format, yield ceiling, flight setting, preparation guarantee, release, deployment or merge. The B4 preparation/LOD-admission fallback disclosed in the original PR remains unchanged. Stop after green Итог for the requested independent follow-up review.
