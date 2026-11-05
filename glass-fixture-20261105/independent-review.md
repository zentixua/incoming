# RGLASS7 independent source/raw review

Pin: `fa2b09d3ea9aea3b5c4d5168a5b809285d2b5fdd` → `2f9466177908f1e97b689928659058c300458593`; detached `/mnt/data/projects/incoming/mod/run/wt-RCI-S6`, clean at review completion. Exactly two files: `RuinSustainedProgressGameTests.java` and `.claude/rules/gametest.md`.

**Serious blocking findings: none.** This bounded fixture/rule correction is acceptable. No production architecture, combined CI or actual-runtime acceptance is implied.

## Source assessment

- Fixture lines 62–83 now put the 4×4 cohort at relative X/Z 8,24,40,56, with the original relative Y38. The actual `range.nbt` is 64×40×64 and has no blocks at Y37/38. All 16 targets remain distinct chunks for any translation under this test's default rotation; the chunk of relative (8,8) anchors their existing 8×8 preload and held area. Bounds and horizontal-air assertions reject invalid setup before snapshots/demands. They strengthen the prerequisite rather than suppress a failed damage assertion.
- Cached dependency inspected directly: `wt-CI-S6/mod/build/moddev/artifacts/neoforge-21.1.264-sources.jar`, entries `GameTestHelper.java:867–870,916–917`, `GameTestInfo.java:263–270`, `StructureUtils.java:70–81,131–147`, `StructureGridSpawner.java:45–60`, `AABB.java:361–366`. `skyAccess` omits the ceiling, not the side barriers; bounds are world-space with exclusive maximum. Old world chunk-centre arithmetic can land on/beyond those walls.
- Native physical decisions use open faces, thickness, reflection and inferred element extent (`Blast.java:530–585,652–670,709–729`). A completed unchanged plan is legal: `RuinPlan.apply:950–958` completes EMPTY, and `ScarQueue.java:1294` writes the scar after successful application. A scar mark therefore does not certify that arbitrarily shielded glass must break.
- Timeout240, counting-clock setup, cold worker restart, 16 offered demands, calculated room/fixed competing reservation, cap, real glass removal, material conservation/backlog and no-orphan assertions are unchanged (fixture33–61,84–157). No pressure refund, result waiver, extra solver or increased timeout was added.
- Cleanup is unchanged and registered before the new setup assertions: restore reservation/seen predicate/clock/thread config, clear registered world work/material and shut down workers; release the held area on either result (46–67; `StrikeGameTests.afterTest:3713–3736`). `NuclearWorld.clear:400–424` cancels its contexts; `RuinContext.cancelTasks:1163–1175` retires reads and closes storage. The new cohort also stops placing glass/support outside the template. No new owner/resource path was introduced.

## Raw controls

Raw directory: `/mnt/data/projects/incoming/mod/run/wt-CI-S6/mod/run/ci-journal-glass/`.

- `RuinSustainedProgress.original.java` equals the pinned base byte-for-byte; `.fixed.java` equals the reviewed fixture. Probe/reference/intervention differences were inspected, not inferred from reports.
- Original full CI job `wt-LEAD2/mod/run/lead2/journal-production-ci-job126995941624.log:631–653` records this test failing after the same start counters and at the same fixture position. All seven supplied prefix logs have the same first12 native batches through `nuke_sustained_progress:0` and starting grid `(9925128,-59,-11516958)`.
- `prefix-red-probe.log:287` identifies surviving glass `(9925304,-22,-11516632)`, exactly on bounds.maxZ. `prefix-marks.log` records old scar0; `prefix-reference.log:294` has `includes=false, changes=0` for that glass. `prefix-neighbours.log:302–314` shows its adjacent barriers, followed by the same failure at331.
- `.barrier-intervention.java` removes only the WEST/EAST adjacent barriers before the same diagnostic reference calls. `prefix-open-faces.log:296,310–320` then includes that target and applies all16 with released8208/pending8192; wrapper exit0 at351. This discriminates local geometry from a queue silently losing an expected change.
- Final placement: `prefix-fixed.log:256–263,290` applies all16 in6ticks, peak532060567 below536870912, material8208/8192, all12 tests pass, wrapper0. Reverting placement while keeping the new guards (`.guard-mutant.java`) fails the bounds prerequisite: `prefix-guard-mutant.log:257,303`, wrapper1.
- `final-validation.log:28,194–225` now has terminal receipts: build success; isolated target succeeds in119ticks with all16/material8208/8192; GameTest build success and `laptop_job lead4-glass-final: exit 0`. I launched none of these jobs.

## Important limits

The temporary `ci-s6/final-selector.java` changes batches after grouping, pins the initial grid with two intercepted native range draws, consumes those original draws, then restores the RandomSource; it also seeds RNG and truncates/reorders the selected batch list. This is a controlled native-order/grid reproduction, not authentication of every historical UUID/random/thread interleaving. Diagnostic `planFresh` calls add work/cache effects; the uninstrumented fixed prefix is the relevant final fixture control. Tick6 versus119 is not a performance comparison. These raws establish the reproduced geometry defect, not that isolated success proves the sole cause of every historical failure. Final remote/combined CI and actual player/runtime validation remain LEAD4-owned; no review claim is made for MT-S7's separate production change.

Handback: LEAD4 retains all implementation/integration/delivery ownership. No source edits, builds, tests, JVM/GPU/server/CI/helper launches or owned waiters. Resources: 0.
