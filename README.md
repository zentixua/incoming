# Normal nuclear carrier preparation evidence

Production component: `6b362f9f1124dec15bd97c8a770824c7f0f4c41f`, draft [PR #581](https://github.com/zentixua/incoming/pull/581).
This orphan branch contains technical evidence only. No world files, jars, frames or personal session records.

## Implemented contract

One server-owned spec Gaussian miss is selected before carrier preparation and reused at launch. Accepted unshifted launch authority stays separate from the existing nominal terminal geometry. Ground missile uses MOTION_BLOCKING top minus 0.5, with the existing NO_LEAVES clamp; B-2 nominal arrival uses NO_LEAVES top minus 0.5. Fixed Point keeps its existing clamp. Ground's initial launch Y and first tracker refinement remain distinct. Exact burst AND ground matching, tracking, contacts, ICBM scheduling and warning duration stay on their existing paths.

## Executed checks

- Full build and devtest compile: PASS, 1801 unit tests, zero failures/errors/skips, 3m36s at `439409a29`. The final head differs only in key-fixture cleanup. `manifests/retry-provenance.json` proves identical production source, unit source and Gradle inputs.
- Final registered union: ALL 116 GameTests PASS, 5.172 min execution / 5m35s Gradle, at `6b362f9f1124dec15bd97c8a770824c7f0f4c41f`. Exact selector and raw log included.
- Five normal route tests call the real public preparation facade, wait for nonempty changed-block plans and durable write receipts, launch through ordinary StrikeService, let registered flight terminate, compare exact burst/ground, verify bound identity maps to the actual event and observe actual prepared-plan replacement. They never inject READY or call impact. Fixtures use supported 1kt / CUSTOM effects_scale0.02; they are not large-yield throughput measurements.

| Normal route | Registered flight ticks | Identity → event | Result |
| --- | ---: | --- | --- |
| MISSILE Ground / GROUND | 610 | 1 → 2 | Exact geometry and prepared handoff |
| MISSILE known Point | 609 | 31 → 32 | Exact geometry and prepared handoff |
| B-2 normal route / contact payload | 825 | 36 → 37 | Exact geometry and prepared handoff |
| MISSILE Ground / AIR | 600 | 42 → 43 | Exact geometry and prepared handoff |
| MISSILE Ground / canopy M>D | 611 | 52 → 53 | Exact geometry and prepared handoff |

The two paired ordinary/prepared missile flights compare position, aim, phase and speed each tick under the same selected miss, route random inputs and real air/readiness schedule. Ground compared608 ticks and ended on tick608; AIR compared600 and ended on tick600. Both pairs have exactly equal actual burst/ground and terminal tick. These auxiliary pairs disable block damage and do not establish plan consumption; the five nonempty-plan routes above do that separately. Request and launch RNG checks verify the existing draw count; repeated PREPARE/poll/read and duplicate commit do not redraw/launch. A registered facade geometry check covers a known empty-column Point and the missile's pre-warhead clamp.

The union also covers core layout/worker/input cancellation, F1/F2 source failures, complete material/storage, flash, save/virtual/B-2 identity, authority/review/keys/site, actual collision/retarget/no-event and Sable blast controls. NBT fallback checks retain an explicit identity with no backend owner; they are not a full server-restart acceptance run.

## Review and corrected fixture boundaries

Fresh independent Sol xhigh source review: `9d1d3304e..74412e353`, report included verbatim. Required shared READY-fixture migration was fixed without weakening carrier sample guards. The review requested no extra round for that fixture migration. Later changes include a small empty-column nominal clamp correction with a registered regression, source integration owned/reviewed by S2 and accepted main14f, and authority-fixture cleanup regressions.

Initial compile failure and float-HUD/exact-server-aim assertion failure are preserved as historical evidence. Exact server assertions now observe TargetTracker.point, rather than the synchronized float HUD aim.

Prior full CI at5dfe reproduced a leaked ICBM ground owner: nuke_command_ready launched#20 then removed only its scheduled row; nuke_stop later found the same area key0x14. The fixture now calls ordinary NuclearStrikes.clear and asserts actual AreaLoader owner release. The known grid fixture correction is included from main580.

The first integrated union at439 was deliberately stopped after an impossible pair count: the key dimension fixture left a15kt carrier alive, producing foreign event46 before the pair's47/48. Its old cleanup removed only ICBMs. Final6b cleanup discards all nuclear fixture flights and asserts none remain; the full116 union then passed. An external systemd stop returned wrapper0, which is not represented here as a successful interrupted GameTest run.

## Limits

Nominal reuse is covered for fixed Point/Ground orders and retained Sighted positions. Unrestricted entity/craft resolution, earlier contacts, wind/terrain changes or restart may require fresh work at the actual physical site. No future-contact prediction, tolerance, snapping, spread reduction or shifted reuse of old outputs is supplied. Exact-match guards remain. No full1Mt preparation time, total heap bound, dense-city B-2 hit ratio, distant-client freshness or complete release acceptance is claimed. LEAD2/MT-S2 own those integrated runtime gates. Production and parent PRs remain unmerged by this component owner.

Final head CI: [run41818158413](https://github.com/zentixua/incoming/actions/runs/41818158413) SUCCESS at6b362f9f1124dec15bd97c8a770824c7f0f4c41f. tools/pr_ci.sh581 completed0; all six GameTest shards, unit/build, both LSS integrations, clean installation, store checks and required Итог passed. Raw receipt and waiter output are included. No rendering code is authored by this component; GPU frames/scene bench are not part of these server-only checks.
