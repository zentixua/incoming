# CI6: worker pressure fixture evidence

Component PR [#597](https://github.com/zentixua/incoming/pull/597), final source `c444cd5ef9ae41cacf4e7aa9a928977f1b9ceabb`, base `f9d9f209c7c3f996ffc460d1ddac6e42d399a2f3`. The cumulative change touches only `RuinWorkerAdmissionGameTests.java`. Production source, the 512 MiB admission cap, the 300-tick deadline, eight held workers, cancellation, actual exit, idle expiry, captured callback refusal and rebirth checks are preserved.

## Established facts

The original CI run [41896220491](https://github.com/zentixua/incoming/actions/runs/41896220491), GT1 job 126806420364, failed the exact-eight assertion. Its log contains no actual pool/entry count. Checkout `1568376b4998ce5fb4dd4767bd258d8ab08feb3e` and f9 have the same tree `dc894d4727ca9ce984ec148ada3776d3628d31aa`. The original count and exact scheduling cause remain unknown.

One instrumented run preserved the original first 36 actual grouped batches / 57 tests, including the preceding tower with pool test. It failed later, at the fully charged callback assertion. A retained tower context lost 286880 bytes between ticks (snapshots 3862524→3607920; fractures 43432→11156), while the fixed Nether reservation 411048131 and all other observed owners remained unchanged. At callback time another 521892 bytes had been reclaimed: resident 536349020, workspace 0, callback queued. This demonstrates the fixture's unstable non-worker baseline. It does not reconstruct the original CI count failure or establish production over-admission.

At H384, each workspace is 13271040 bytes. The fixed base 417653525 funds eight workers (resident 523821845) but is only 221973 bytes short of a ninth. A legal unrelated release can change that expected count without crossing 536870912. All these figures are admission-accounting estimates; the fixture's external pressure is a numeric reservation, not a 417 MB heap allocation.

The corrected setup clears previous nuclear fixtures in every dimension, refreshes replaced ledger owners, and awaits actual ledger, preparation/source, input/result, workspace and settlement-header retirement before calibrating pressure. It seeds 2048 material rows and one controlled settlement-header future per dimension so both review-found preconditions are exercised even in isolation. The header futures enter real `Entry.read` ownership and survive the real clear-to-retiring transfer; they are controlled fixture completions, not disk-latency measurements. Pressure stages run at most once per world tick after asynchronous setup.

## Checks and controls

| Case | Exact source / change | Result |
|---|---|---|
| Original prefix | f9 + original-prefix.patch, unchanged original assertions |56/57; callback charge assertion RED |
| Stale owner control | f9 + stale-owner-control.patch; material seed retained, immediate predecessor setup | RED: resident 430602229 vs 430924565, delta 322336 |
| Settlement control | c444 + settlement-control.patch; removes only two lines waiting for retiring headers | RED: pressure started before the controlled headers completed |
| Earlier production compatibility | b5610b3c91a2c1e2e2d09d28c0fd503464a43982 + byte-identical final fixture |1/1 PASS |
| Final clean build and worker batch | c444 |1840 units,0 failures/errors/skips;3/3 GameTests PASS |
| Final original-order prefix | c444 + corrected-prefix.patch |36 batches/57 tests PASS; exact order compared with original raw |
| Final required CI |41903532730; checkout 82614f30bfa7ce85e892be9b6684045f369605df | All required checks and Итог GREEN; pr_ci exit0 |

Final CI checkout and source c444 share tree `55ec886f657c003bc2257465809c2b66c63004c5`. GT1 passes 139/139. All other shards, both LSS checks, unit groups, build and clean installation pass. Release is skipped.

Final fixture SHA-256 (also used on b561): `988544f0447dd79d70d0a093c4ec692272254a9c0f72b3c8aa865698f66601d9`.

## Final fixed-owner timeline

| World tick | State | Non-worker bytes | Workspace bytes | Entered/exited | Pool / queued |
|---|---|---:|---:|---:|---:|
|4600|calibrated|417653525|0|0/0|0/0|
|4601|stage0, held|417653525|106168320|8/0|8/2|
|4602|stage1, held|417653525|106168320|8/0|8/2|
|4603|callables exited, idle threads resident|417653525|106168320|10/10|8/0|
|4604|actual thread exit|417653525|0|10/10|0/0|
|4605|full-cap captured callback waits|536870912|0|10/10|0/1|
|4606|one-workspace refund allowed callback progress 11|523599872|0|10/10|0/0|

The reborn thread already expired before the final sample. Its transient workspace is not sampled by this table. The callback is outside the ten original callable entry/exit counters. The fixture also asserts same executor identity and largest pool size 8.

## Review and evidence limits

Two fresh independent Sol/xhigh sessions inspected source and raw evidence without author conclusions. `review-1.md` pins 3978 and identifies stale replaced-ledger references; 3159/8ca add refresh/completion ordering and a material regression. `review-2.md` pins 8ca, confirms that correction and identifies independent settlement retirement; c444 closes that route with the held-future discriminator above. `second-review-closure.patch` is the exact 8ca→c444 correction. No third routine review was requested, following repository policy; final source integration/reconciliation remains with the receiving owners.

The temporary observers buffer bounded direct field/atomic/pool observations, time stamps and strings. They add scheduling/allocation costs and do not form an atomic snapshot. Existing resident-accounting calls can retire completed ownership; the observer adds no service/admission/ledger-opening calls. Cleanup and receipt polling are intentional setup actions, not passive observation. The prefix selector trims the already-grouped batch list after the target, preserving the original order; it is absent from the production diff. Each run used a fresh test world, preserving earlier worlds separately, 4 processors and 2 GiB Java heaps through the laptop job wrapper, no shared Gradle daemon.

This is a devtest fixture correction with bounded source/CI evidence. Whole-heap limits, MT runtime acceptance, integration, delivery and release remain separate owner work. Original CI counts remain unknown.
