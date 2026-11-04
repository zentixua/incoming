# PR #560: second and final review corrections

Reviewed/reproduced production head: `f92dabaaac30bb04b3e9b07296be44a2602114c8`.
Final head: `bd7b3ef096c7a77ca50bd3350ba2dbd4306dd81a`; production fixes are in `dd44488c7`, followed by a test-only CI correction.

## Reproduce first

The four new registered-event regressions were added before modifying either production class. They ran on the reviewed production head through real `RuinMatter.work`, using counting work clocks. No direct `RuinSupport.step` calls or snapshot-only substitution. [Pre-fix log](reproduce.log) records all four expected failures, and [the exact test source](RuinReviewGameTests.before-fix.java.txt) is archived:

| Registered regression | Result on f92dabaaa after 500 work units |
|---|---|
| Foliage touching the first beam cell | Only the glass released (`released=1`); `support=1` remains, beam still suspended |
| Two events removing consecutive foundation links | Only four fixture glass blocks released; `support=0`, previously anchored beam incorrectly preserved |
| Same two events, SavedData save/load before reconciliation | Same failure (`released=4`, `support=0`) |
| Reversed removal order, SavedData save/load | Same failure (`released=4`, `support=0`) |

Each overlap fixture also contains a separate genuinely floating beam, which must remain floating. The anchored beam crosses chunk seams. Events are registered in `NuclearEvents`; source capture/removal/release and support seeding use the production path. The clock stays stationary during the bounded proof/release loop, so moving records remain pending: these tests assert support reconciliation and material ownership, not completed flight time.

## Narrow corrections

1. `RuinSupport` uses one bearing-contact predicate in traversal and the shared validation/release boundary check. Colliding but non-bearing foliage cannot turn into a new structural connection only during release.
2. `RuinMatter` keeps one position-indexed pre-failure journal across still-pending detonations. The first removed state at each position survives until support work drains; event IDs retain attribution, not an isolated graph. Existing serialized field names remain readable. Older per-event saves with duplicate positions import the earliest registered event's contact deterministically. Journal cleanup and material totals remain unchanged.

No yield, flight, damage law, transport, renderer, sound, callback-readiness or scheduling changes. Full ledger accounting/compaction remains explicitly deferred to B5; this is not that work.

## Verification

[Post-fix touched-area run](fixed-tests.log): **all 27 required GameTests passed**, including the four reproduced cases, all earlier support review regressions, material settlement/ownership and structural fixtures. The new cases require the entire 56-block beam to transfer to pending material, every original beam cell to become air, support work to drain, the separate floating control to survive, and completed provenance to retire.

[Full build](build.log): **1766 unit tests, zero failures/errors/skips** (`unit-counts.json`). [Final PR CI](pr-ci.log) is recorded beside this report. The production diff is archived separately as `production.patch`. No third independent review was run, as requested.

The first CI run on dd44488c7 passed the new regressions but failed the existing `shoreTowerFallsWithoutWaterWalls` test: its assertion interpreted a non-air block at +60 ticks as an intact tower. The fixture applies destruction synchronously, while conserved material continues moving during those 60 ticks. A local diagnostic run did not repeat that exact CI failure, but recorded zero upper structure blocks immediately after planning, 975 pending bodies, then 212 deposits by +60 ticks (`shore-diagnosis.log`). That establishes why a delayed empty-volume assertion conflates removal with subsequent deposition. The test-only follow-up asserts the same structural cells immediately after planning, retaining the delayed shore/bottom/water assertions. It passes (`shore-fixed.log`). No production parameter was changed or assertion removed; returned rubble is covered by the material settlement tests. The first failed CI log is retained as `ci-first-failure.log` and `pr-ci-first.log`.

No new GPU frames: these fixes change the two pathological continuation cases proved above, without changing the rendered appearance or the prior standard single-event roof/tree/crater scenes. Existing pairs remain explicitly identified as f92dabaaa in `final-head-pairs`; they are not relabeled as bd7b3ef09. No new visual source-identity claim is made.

No PR merge, release, deployment, player-world change or server operation.

Final **[Итог is green](https://github.com/zentixua/incoming/actions/runs/41760797283/job/126350611757)** on bd7b3ef09. Main remained 8e3e2dc28 and is already in the branch ancestry; no additional merge was needed.
