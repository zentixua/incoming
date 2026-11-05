# RAIR8B independent second review — source phase

- Reviewer: fresh Codex, gpt-6.1-sol / xhigh; read-only production access.
- Exact base: `3efac357cc368045f6fe821ac02c561ab99af4c8`.
- Exact candidate: `c156f260588aaa967327ed6577f775e015bc0ec7`.
- Checkout: `/mnt/data/projects/incoming/mod/run/wt-RAIR8B`, detached and clean before/after inspection.
- Source owner remains AIR8; integration/runtime/GPU owner remains LEAD4.

## Source finding

**No blocking source defect found in the complete nine-file base-to-candidate diff.** This is the source phase of the second independent review, not completed visual/performance acceptance or authorization to merge without the remaining gates.

### Cache failure and admission

`StemDust.java:209–230`: `ready` first establishes that a cached future is terminal. A pending observation returns null without reading its result. Once terminal, ordinary CompletableFuture completion cannot switch from success to exceptional completion; the following exceptional-status check and `getNow` therefore avoid the pending-to-failed race. Computation failure completes the stored future exceptionally and remains dust-free on the render path. The synchronous `of` path joins outside the cache lock; the frame call uses only `ready`.

`StemDust.java:175–221`: insertion/admission is serialized under the same map lock, successful and failed completed entries can make room, and pending entries are retained. With eight pending entries an unrepresented render request returns null without submitting another computation. This bounds cached background ownership; the explicit uncached synchronous fallback is not used by the production frame. No world object is retained by Source or the dust table.

### Timing identity, units and reconstruction

`StemDust.java:47–85`: Source snapshots the 24 annular arrival values into a private array and includes geometry, burst mode, visibility and those values in equality/hash. The retained FrontProfile has private final arrays created internally and exposes no mutation. Moving-foot arrival is evaluated against that same profile. Dust-table sharing depends on all arrival values actually used by construction, rather than profile object identity.

`StemDust.java:70–73` matches `Detonation.slantOf/arrivalTicks`: horizontal model meters become slant blocks using burst height and effects scale; ticks return to model seconds through division by `20 * scale`. `PallField.seconds` and `ClientNuclear.Active.seconds` use the same model-time conversion. The old no-detonation overload remains a model/test fallback; production MushroomField supplies `a.pall.stemSource`.

`StemDust.java:93–165,234–241`: annular geometry, DustModel columns, extinction flux, STEM_S, cloud-rise transport, cap transfer and grounded-wake rule remain the existing model. Integer nodes retain the one-second right-endpoint quadrature, now aged from the supplying front. Positive source onsets are added as reconstruction knots; no source amount enters at or before its onset. Between integer nodes the reconstruction uses the already computed next quadrature amount only after its own source onset. This is a numerical reconstruction, not measured fractional-time transport or a new calibration of the cloud-rise law.

### Client and documentation

`PallField.java:178–181`, `MushroomField.java:304–311,645–658`: one retained source feeds both the asynchronous stem shape and the moving-foot rise. PallField is reused for the same detonation after replay seek; a changed detonation builds a new field/source. The ordinary clock/geometry conversions remain consistent. No packet/save format changes occur in this diff.

Both player guides now distinguish the approximate transport model from a timed physical calibration and describe GAME-wave supply delay. The code/rules continue to disclose the existing water-footprint limitation. No upper-cloud, material-composition, inflow-speed or water-coverage correction is established by this component.

## Raw control evidence inspected

Root: `/mnt/data/projects/incoming/mod/run/wt-AIR8-evidence/checks/review-fix/`.

- Read `failure_control.py`, `failure-race-red.log` and XML. The script removes only the new terminal guard and selects the single failure-transition fixture. XML records one executed test / one failure, with CompletionException caused by the deliberately timed exceptional completion. This is an API-order discrimination test using an overridden CompletableFuture status observation, not a scheduler stress test.
- Read `clock_control.py`, wrong-clock log and both XML files. The mutant replaces both annular onset/age and the moving-foot arrival with HobBlast arrival. Seven tests execute; four fail. The GAME/REAL distinction and onset assertion fail materially; the moving-foot failure is expected 351.82395775273363 versus actual 381.49260325432226. This establishes that the selected fixtures distinguish the substituted model clock; it does not isolate every changed clock call into a separate control.
- Read restored-clock log and XML: seven tests / zero failures, including the exceptional-transition and all-pending-admission fixtures. The selected command runs two JUnit classes, not the full build or native GameTest.
- Inspected the new native `StemClockGameTests` source: the CODEC round trip and restored slant/scale assertions exercise the pure model through a native test holder; this phase does not independently witness its execution at c156.
- `git diff --check` is clean. No local JVM, build, GameTest, GPU, server, CI waiter, nested reviewer or PR mutation was started by RAIR8B.

The earlier `checks/build.log`, `build-result.json` and `gametest.log` belong to 68a and were not used to certify c156. The provided targeted control outputs do not replace final exact-pin full-build/native/CI provenance.

## Pending phase and explicit handback

Native paired before/after frames, actual ages/settings/camera/raw capture provenance and the final 100-scene frame-cost comparison are absent from this source phase. No visual quality, physical-realism, shader visibility or frame-budget acceptance is given. Final exact-pin validation evidence is also still required from its owner.

AIR8 should explicitly resume this same independent session when those records are available. Continue this second review with the added evidence; no routine third source round is requested. This phase returns its report to AIR8 and LEAD4 and ends to free the slot. All production sources/data/resources are preserved; RAIR8B owns no remaining jobs or resources.
