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


# Continuation — 5 November 2026: df40 source, ownership and native frames

Same independent second review / same RAIR8B session, resumed explicitly. Exact base remains `3efac357cc368045f6fe821ac02c561ab99af4c8`; candidate is now `df40a1e369306d21cad94677aadd99e77e235eba`, detached clean wt-RAIR8B. Inspected the complete nine-file diff and the c156-to-df40 delta with surrounding model, frame scan, lifetime and replay callers. No author PR body, author report/verdict or the newly authored nuclear-stem-source-clock memory note was used.

**No blocking source or frame defect identified at df40 within the evidence scope below. Final exact-df40 100-scene cost acceptance remains OPEN.** This continuation does not approve overall server/runtime readiness or physical realism.

## Caller ownership and eventual consumption

`StemDust.Request.ready/release` (`StemDust.java:95–124`) retains the admitted future in the consumer. Shared eviction can no longer remove that consumer's only route to its result. An unadmitted Request retries admission on later calls; once admitted it does not continually reinsert itself. For a finite cohort scanned repeatedly, with admitted workers completing, earlier consumers cease competing for new slots and later consumers can obtain their own result. This is eventual consumption under those assumptions, not a fixed latency guarantee under arbitrary new demand or stalled workers.

The shared cache still admits at most eight represented pending builders. Pending work is not evicted; equal sources found in that cache share their admitted future. Completed data retained by Requests can exceed eight tables, and a later equal source can rebuild after its shared entry was evicted even if another consumer retains an older equal table. The cache cap is **not** a total retained-heap bound. Each completed table has three double arrays with at most roughly 25 KiB payload at the supported yields; live consumers, source/front data, futures/object overhead and temporary builder storage require separate accounting. No unlimited-source total-memory guarantee is inferred.

`start/value/compute` (`StemDust.java:251–282`) preserves the terminal-first failure check reviewed earlier and now also converts worker-submission failure into an exceptional stored future. A Request retains a failed terminal entry and stays dust-free without retrying it every frame. Release detaches only that consumer; it neither cancels a shared computation nor invalidates another consumer's table. An expired cloud releases on the next `PallField.stemDust` observation at or beyond `MushroomModel.lifeSeconds`; field/world teardown also drops its caller-owned reference. This is lazy cleanup through the shape path, not a separate expiry timer. Replay of the same detonation reuses PallField and can reacquire after release. The production frame calls `shape` before view selection for every Active, including expired shapes, so the ordinary frame path reaches this cleanup.

### Raw ownership controls

- Independently diffed `ownership/fixture/.../Air8LegacyDust.java` against actual 452 StemDust: only class renaming and controlled executor dispatch were added. Its native builders complete between frames; raw legacy XML records `010101010101`.
- The c156 red record executes the last-consumer regression and fails with `000000000000`. This exposes a concrete gap beyond the earlier all-pending size test: bounded admission alone did not ensure consumption after earlier frame candidates evicted completed entries.
- df40 raw green XML records `000111111111` for 24 distinct Requests over 12 scans, positive/stable identity after admission, cache/queued-work caps, actual shared eviction, independent release and value-preserving reacquisition. Source inspection confirms that the production frame scans all Active values before selecting four rows, so the controlled ordering tests the relevant consumer pattern. This is a deterministic native-builder unit schedule, not a 24-detonation GPU capture.
- The expiration/replay unit checks drop/reacquire behavior while shared reuse is available; the separate ownership test covers reacquisition after actual eviction.
- The df40 private hot probe uses four prewarmed production Requests for 500,000 sampled iterations and records zero allocating bytes on its candidate thread. A1/A2 use the renamed original source and allocate about 400 B/call. It measures `Request.ready`, tau/top and foot-arrival lookup, excluding pending construction, lifetime/shape packing, shaders and the complete frame. It cannot be used as a whole-frame heap or CPU claim. The earlier c156 `hot-probe/` record is a different lookup path and retains its original pin.

## Native records and visual inspection

Native BEFORE is `452f1f78af23779200baf4aef804e8e92d6dca40`; AFTER is **c156f260588aaa967327ed6577f775e015bc0ec7**, not df40. Read the actual fixture used/restored files, logs, capture metadata, compiled-entry manifests and model.csv. The used ArrivalScene/Sandbox fixtures match between sides; their saved originals match their respective git source. Air8Capture invokes the real MushroomField shape path. The compiled manifests contain 133 entries on each side, with differences in the expected StemDust/Source, PallField and MushroomField entries. This is recorded build provenance, not an independently recompiled binary audit.

Independently checked all 58 PNG hashes against frames.json and all 29 paired camera/FOV/dimension records: exact paired geometry; maximum paired nuclear-age discrepancy is 0.016003 s. Native resolution is 2560×1412. Personally viewed 20 original PNGs, rather than accepting preview sheets: both sides of 15 AIR at 1/15/60 s, 5 AIR at 10/60 s, both GROUND yields at 60 s, and high-HOB at 50/75/90 s.

The 5/15 kt AIR views retain a detached rising cap and short lower dusty column; near onset that column is largely hidden by the low dust/skirt. At 60 s the stem is visible below the cap in both versions. GROUND retains the continuous wake joining the cap in the viewed pairs. The views do not demonstrate an instantaneous ground-to-cap connector or a newly missing renderer component. Small model changes at ordinary heights are expected: at 15 kt/592 m, model.csv gives stem top 873.06→851.48 m at 60 s while the cap parameters remain unchanged; viewed frames agree with a slightly shorter lower column, not a cloud-geometry redesign.

The high control is 1000 kt AIR / HOB2400 m / GAME0.25 / dust quality OFF, with no initial-age skip. Logs give first-annulus actual arrival21.558908 s versus model arrival3.197703 s, plus inflow delay40.548013 s. Thus the corresponding onset moves from approximately43.745716 to62.106922 model seconds. CSV first positive integer samples move44→63 s; after has no stem extinction at50/60 s and has positive extinction at75/90 s. Viewed high PNGs show the later/shorter lower stem, with persistent skirt matter around it; OFF does not remove the mushroom's own skirt, so those frames alone are not an unambiguous image threshold for first stem matter. The source-clock change is discriminated by raw model records as well as the images.

Scenes are isolated client-only bursts: fixed camera8 km away/300 m above the fixture site, zero wind, clear20 km visibility, noon, scale1, fires=false; ordinary sequences use MEDIUM. They establish controlled morphology/timing, not a city, full-pack/Iris visibility or a real server's terrain/damage arrival. No independent timed real-world calibration was supplied; exact gap duration and transport constants remain approximate.

All ten raw WAVs have valid 48 kHz, stereo, 16-bit headers and 45 s duration. Capture metadata identifies their source stream/range and logs identify OpenAL Wave File Writer. WAV duration is shorter than the60/90 s image sequences. No sound code changes occur here, and header/provenance inspection is not an auditory-fidelity verdict.

### Explicit captured-path reuse decision

**Recapture is not required for the already captured single-source steady data/appearance comparison.** The c156→df40 diff does not change Source timing, the table constructor/reconstruction, lambda/top sampling, moving-foot law, shaders, material values or cloud geometry. It changes access from shared lookup to a retained caller Request plus lifetime release. Each captured scene has one source, no cache-pressure cohort, no expiry/replay and ages at most90 s; its admitted result has the same values. Equal value after eviction/reacquisition is additionally exercised by raw df40 unit evidence.

Keep these native files labeled452→c156. Reuse is limited to that captured path and model values; these are not newly captured df40 frames and do not demonstrate multi-source rendering or exact first-frame async scheduling. The new multi-source ownership behavior is supported by its separate deterministic source/raw tests, not by relabeling these images.

## Exact-pin build/CI and remaining cost gate

Read `ownership/build.log`, build-result.json and native GameTest log: full local1850 unit tests with zero failures/errors/skips are recorded for df40; the native nuke_stem_clock batch reports all1 required test passed and clean shutdown. The local full count comes from the supplied result record; this reviewer did not rerun a JVM.

The actual CI receipt is newer than source.json's stale pending-CI field: run41943641063 is completed/success for head df40, including required Итог. `ci-merge.json` records synthetic merge d3f54e87 with tree `b87277f5c72357e753fb95892fedfc7e6dc6fc7c`; independently resolving df40's tree gives exactly that tree. The artifact receipt includes incoming-jar and tested-tree-b87277..., tied to the same run/head. `pr-ci.log` records the completed green waiter. No additional local CI job was launched by the reviewer.

The previous standard100-scene A1/B/A2 records in bench/ are none-pack conventional-scene measurements for c156, with main7bdb as baseline. They are not df40 cost evidence and are not a high-yield nuclear or 24-source consumption benchmark. The request-path CPU/allocation probe above is also a different scope. A **fresh final-df40 100-scene cost comparison remains required and unaccepted**. Supply its raw results/context/artifact pin as the last phase of this same independent review; no further routine source round is requested.

RAIR8B returns this source/frame continuation now and ends to free the S7 correction-review slot. No source, JVM, GPU, heavy job, server, nested reviewer, PR or user-desktop action was performed. AIR8 retains source ownership; LEAD4 retains integration/runtime/GPU ownership. All records preserved; reviewer-owned resources0.


# Final cost receipt — 5 November 2026

Same second independent review, final evidence phase. Source is still exact `df40a1e369306d21cad94677aadd99e77e235eba`, detached clean wt-RAIR8B, with no source delta since the preceding source/frame continuation.

**PASS for the required sampled standard100 conventional-strike / none-pack frame-cost comparison. No blocking finding remains within this component review's established source, controlled-frame and sampled-cost scope.** This closes the previously pending final-df40 cost gate; integration/runtime/delivery remain LEAD4-owned.

Inspected raw `bench-final/` pair/context metadata, A1/B/A2 frames.csv, events.jsonl, host.jsonl, plans, client logs and completed driver stages. Did not read the supplied verdict.json or author PR/report conclusions. Reparsed all three raw runs with the existing stand implementation using `stand.analyze(write=False)` and independently invoked the inspected gate comparison; no receipt/result file was rewritten. Recomputed metrics exactly match the recorded results, and the recomputed gate returns pass with no failures.

- Actual candidate JAR SHA-256 independently checked: `3cb9666c52723f93d702b19ff96a8763954a4bb73091fa4c3a3d9203f5221685`, the supplied df40 CI JAR. Both baseline metadata records use main `7bdb5b2c12c9c387aa346046250a9fd62fdf3990`, SHA-256 `1767702dc97870b3f31e139fca970648376008764b42a237b68b8bf54022554d`.
- Recomputed current harness hash matches pair context: `d410cda45bc305b2d1ef5ea6e3d4f019739387723e3e045211bc295ed1a7f8be`. All three contexts match; saved scene plans are byte-identical. All15 checked metadata/raw/plan files match their original `stand/out/air8-df40-100` copies by SHA-256.
- Same RTX4060 Laptop GPU / NVIDIA615.78.08 /2560×1600 / Java21.0.7 / performance profile; recorded Iris pipeline is off throughout, as required for pack none. Driver records warmup, A1, B, A2 completed consecutively, followed by both wrapper exits0. Warmup is excluded from the decision.
- All runs finish20 steps and100/100 impacts (`why=all`), with no validity errors, failed camera/deadline checks or missing windows. Each gated window has3780–4307 usable frames. Screenshot and following frames are excluded by the existing parser; frame intervals are taken from the next row. Gate CPU is render-thread CPU time, not wall-clock frame duration; GPU values are GL timestamp spans.

| Gated window | GPU p50 A1 / B / A2, ms | CPU thread p95 A1 / B / A2, ms |
|---|---:|---:|
|100/outside|2.407 /2.392 /2.447|5.150 /5.363 /5.385|
|100/edge|3.720 /3.492 /3.554|4.765 /4.890 /5.063|
|100/inside|3.336 /3.024 /3.946|4.803 /4.478 /4.552|

The existing gate compares each candidate metric with the worse A1/A2 value, allowing25%. All six candidate gated metrics are below that baseline. This supports absence of a gate-detectable regression in this pair; it is not a causal performance-improvement estimate. The100-hit window is report-only; first-strike and accumulation gates were not captured in this subset.

Host records cover every gated window at100% for both CPU and sampled GPU observations; performance profile is stable. No load notes are produced. Candidate foreign CPU peaks at0.03 core in those windows; foreign GPU peaks at16% SM, with highest window mean4.59%, within existing25% peak/5% mean reporting thresholds. These sampled records do not prove absence of interference at every instant. The same-session A1/B/A2 comparison is the prescribed control for heating/background variability.

## Final bounded disposition and retained limits

The source/ownership and captured-path reuse findings in the preceding continuation remain unchanged. Native PNGs remain labeled452→c156 and are reused only for the explicitly justified unchanged single-source table/steady appearance path; they are not relabeled df40 captures. The final benchmark is df40, rather than the historical c156 pair.

This receipt establishes standard100 conventional-strike cost under none, on one A1/B/A2 pair. It does not establish nuclear multi-source rendering cost, all-shaderpack/full-pack performance, arbitrary source-count total heap bounds, fixed async completion latency, calibrated real-world stem/gap duration, water-footprint completeness, actual server100/1000kt terrain publication, save/restart/multiplayer/traversal readiness or private/public delivery. The retained caller-owned data and shared eight-entry cache continue to have different ownership/memory scopes.

The prior exact-tree CI/local unit/native receipts remain as previously inspected; no new source review round, JVM/GPU/heavy job, nested session, PR mutation or desktop/game operation was performed in this final phase. RAIR8B owns no pending jobs/resources. Final component handback goes to AIR8 for evidence/PR/handoff bookkeeping and LEAD4 for integration/runtime/delivery. END.
