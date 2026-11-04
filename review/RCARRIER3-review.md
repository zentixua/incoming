# RCARRIER3 independent implementation review

Reviewed: `9d1d3304e..74412e353379bf6141f1e6a0c32bb77c0bfdb0c8` in `/mnt/data/projects/incoming/mod/run/wt-RCARRIER3` (clean detached checkout). Fresh `gpt-6.1-sol`, `xhigh` session, 2026-11-04. This is source review only; the implementation owner retains fixes and integration.

TEAM/context, the relevant BOARD ownership row, newest live entries, repository rules and the memory index were read for authority. Implementation evidence was the diff, surrounding source, existing tests and the four explicitly allowed raw logs. No author reports/conversations, API discussion, inboxes, other reviews, PR descriptions or verdicts were used. No source changes, builds, tests, GPU jobs, servers, desktop operations or public messages were performed.

## Required finding

### F1 — P2: migrate the shared READY fixture to the new carrier preparation contract

Changed location: `mod/src/main/java/ua/zentix/incoming/strike/StrikeService.java:164-165`; contract guard: `nuclear/world/NuclearPrep.java:360-362`.

Prepared carrier launch now requires `carrierMiss(handle, weapon)` to return a sample. The unchanged `mod/src/devtest/java/ua/zentix/incoming/nuclear/world/NuclearReadyFixture.java:21-27` still calls generic `RuinPreparation.prepare` for every weapon. Consequently its carrier requests have `carrierWeapon == null`, so even an injected READY request returns null from `carrierMiss` and is refused before creating a projectile.

Two existing registered regressions deterministically encounter this:

- `NuclearSalvoPreparationGameTests.preparedGroundSiteWaitKeepsExactResolvedHeight` (`nuke_prepared_site`): lines 35-40 create that fixture, tick the prepared salvo, then require a virtual projectile. The launch returns FAILED and `findFirst().orElseThrow()` has no projectile to find.
- `NuclearKeyGameTests.armedCarrierWarningStillLaunchesAfterOwnerChangesDimension` (`nuclear_keys_armed_dimension`): `order` at lines 292-296 uses the same generic fixture; the due-warning tick at lines 241-246 refuses launch and the required prepared-projectile assertion fails (and the paid carrier follows the failure/refund path).

This is a concrete test-suite regression, not evidence that production `NuclearCommands`/`NuclearReview` use the wrong factory: both production callers were updated correctly. Update the shared fixture to select `prepareCarrier` for carriers and construct its controlled geometry from the stored sample and the appropriate terminal-height law, while retaining the accepted unshifted `resolvedTarget`. Merely weakening the new weapon/sample guard would remove a useful contract. Check the existing warning/site regressions as well as the new route tests after this migration.

No new serious production correctness, crash or tick-budget defect was established for static Ground/Point nominal arrivals in this diff. F1 must still be fixed before the required full suite can pass.

## Optional finding / contract boundary

### O1 — describe nominal reuse in terms of target kinds, not only whether a target moved

`NuclearPrep.terminalGround` at lines 366-371 receives a point and `surface` flag, not the target kind. Therefore the normal Ground/Point laws are represented, but unrestricted entity/craft resolution is not universally represented, even for an unmoving subject.

Concrete source example: an unrestricted `Target.OfEntity` standing on flat terrain with first-air height H, an aim at its body above H, and the ordinary nonzero missile sample. Preparation predicts `min(H, targetY) == H`. After launch `Target.OfEntity.offset` supplies spread, and `Target.OfEntity.resolve` (`target/Target.java:147-155`) resolves a grounded spread point to H - 0.5. The missile's normal airburst `impact(aim)` then produces ground H - 0.5, so the exact check in `NuclearPrep.preparedFor` at lines 1754-1755 correctly rejects the pages despite the subject never moving. A stationary rotated Sable subject also retains its existing plot-space offset law rather than a general world-space `target + miss` law.

The fallback preserves physical behavior; this is not a reason to freeze tracking, widen equality or snap the impact. Consider stating explicitly that the guaranteed nominal reuse is for static point/ground orders, or add a target-intent regression if unchanged entity/craft subjects are intended to be covered. The new guide's list of exceptions currently mentions movement, not this target-kind boundary.

## Source assessment

- `prepareCarrier` draws the spec miss only when a new request is installed. Repeated PREPARE/poll/read of the same live request does not redraw. `carrierMiss` rejects another weapon and bound requests. Duplicate prepared launches therefore do not create a second carrier.
- Ground missile preparation resolves the accepted target separately from the offset terminal column, uses canopy-inclusive height minus 0.5 for the flight aim, and applies the existing no-leaves ground clamp. Point orders retain their Y clamp. B-2 nominal arrival uses the existing no-leaves surface minus 0.5. These match `TargetTracker`, `BomberEntity.surfaceUnder`, `StrikeProjectile.advance`, `CruiseMissileEntity.impact`, `NuclearStrikes.ground` and `NuclearWarhead.geometry` for the reviewed static nominal branches.
- Identity is still explicitly bound before entry; virtual/save transitions and B-2 payload transfer keep their existing ownership plumbing. Detonation still computes fresh physical geometry, and prepared lookup requires the matching identity and exact burst/ground for handle-backed requests. Earlier contacts are not redirected to the prepared target. Stale saved identities can fall back without proximity claiming a different handle.
- New live terrain reads use `Terrain.Allowed.CHUNK`/`getChunkNow`. Cold resolution uses sequential bounded `DiskShots` reads, primes only under the work clocks, and uses the existing inbox/reservation lifetime. No new synchronous chunk loading or whole-zone server-thread scan was found. Layout, archives, plan ownership and downstream scar consumption remain on their existing bounded paths. This is source inspection, not a measured large-yield memory/performance result.
- Generic/ICBM requests retain zero miss, the old surface/point geometry and scheduled binding. A carrier request is newly rejected by scheduled `bind`. Unprepared launches retain the old spec sample and physical collision paths. Target tracking, review disclosure and warning/site continuation authority are unchanged by the production diff.

## Executed-evidence limits

Read-only logs under `/mnt/data/projects/incoming/mod/run/wt-CARRIER3/mod/run/carrier3/`:

| Evidence | What it establishes |
| --- | --- |
| `compile.log` | `compileJava` and `compileDevtestJava` completed successfully; this is not a full build/unit-test pass and the log does not pin the reviewed SHA. |
| `first-gt.log` | Initial ground-flight test failed on the initial Ground-height assertion; one-shot sample test passed. This is historical failure evidence. |
| `ground-gt.log` | Both selected tests passed: ground missile durable-plan/real-flight handoff and sample-once. The logged ground flight consumed its prepared identity/event and finished after 610 ticks. |
| `ci-initial-failed.log` | Initial CI failed because a double yield was passed to the int Nuke constructor. The reviewed source now declares int yield; that old CI result neither proves a current compile defect nor establishes current green CI. |

There is no supplied executed log for the new air, canopy, B-2, Point or paired-equivalence routes. Their source is stronger than an injected READY/impact-only test: the normal-route checks require nonempty changed-block plans, durable receipts, registered flight, exact event geometry/identity and actual `appliedPrepared` progress; paired tests compare ordinary/prepared flight state and terminal time. However, unexecuted assertions do not establish that those routes pass. The paired tests use block-damage-disabled mode and cannot alone prove nonempty plan consumption. The changed save/reload test uses an absent preparation identity, which covers fallback rather than consumption of a real prepared cache.

The review did not launch additional checks during the shared timing window. The owner should repair F1 and obtain the required build/GameTest/CI evidence on the final head; no additional review round is requested for that fixture migration alone.
