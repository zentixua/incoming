# MT-META: preparation layout allocation and ownership

Source before correction: 5e1148a6d. Corrected/check-tested source: c3507af43.
No world data is included. The detonation is a mathematical geometry at (3104.5,63,560.5), ground burst, scale1.

The ignored JavaExec harness invokes the actual NuclearPrep.layout, without a running server or solver.
ThreadMXBean measures current-thread allocated bytes; a premain Instrumentation agent measures the identity-deduplicated reachable object graph. These are different metrics. Allocation includes dead construction temporaries; reachable graphs exclude shared enum instances. The first 1kt call warms class initialization and is not a comparable allocation sample. Individual field graphs overlap through Tile/ChunkPos references; do not sum them.

| Yield | Zone chunks | Header keys | Tiles | Allocated bytes before | Retained graph before | Old array estimate |
|---|---:|---:|---:|---:|---:|---:|
|100kt|58486|63086|2434|22212664|6167040|990112|
|1000kt|268082|277736|10920|95840488|27001280|4404048|

Raw capacities include hash table slots, list backing lengths and all reverse-index references. See baseline.log. Missing retained ownership is 5,176,928 / 22,597,232 bytes. Unfinished and completed-uncollected layouts previously had no admission charge. Corrected graphs differ by one additional 8-byte Layout field; physical zone and reverse-index counts are identical. ObjectArrayList permits querying backing capacities without reflecting into JDK ArrayList in production. Corrected raw allocation is diagnostic, not a timing comparison under shared CPU load.

Admission now reserves 45,003,264 / 188,453,376 bytes before background submission. These are conservative engineering estimates from a bounding grid including tile/window margins, 512 bytes/cell and 1MiB fixed room. They exceed the isolated actual layout allocations; they are not proven whole-JVM peak-heap bounds. Retained estimates account actual backing capacities with an uncompressed-reference/object margin. No cap increase, physical radius reduction, graph replacement, or server ceiling change is included.

LayoutBuild retains workspace while a cancelled callable is still running; completed uncollected layouts retain result ownership; installation transfers to Prep metadata; cancellation clears completed results. Geometry waiting for layout admission remains PAUSED instead of reporting empty READY.

Validation on c3507af43:
- compileJava and compileDevtestJava passed, no Java compiler warnings.
- 4 dedicated JUnit tests passed (unit.log, unit-results.xml).
- 8 focused GameTests passed, 20s Gradle/wrapper exit0 (gametest.log): new registered facade 3x1000kt admission + actual-layout cancellation gate, new metadata backing-clear regression, existing detached/lifecycle layout/cross-dimension/receipts and both high-count worker tests.
- The facade test temporarily sets a test-only1000kt config value; production Loadout.Nuke.MAX_YIELD remains unchanged15 in this checkout.

Commands (JDK21, each heavy invocation via tools/laptop_job.sh with explicit SESSION_TREES and INCOMING_GPU_OWNER=MT-META):
`cd mod && ./gradlew --daemon -I run/mt-meta/measure.init.gradle measureActualLayout compileDevtestJava`
`cd mod && ./gradlew --daemon test --tests '*RuinLayoutOwnershipTest'`
`cd mod && ./gradlew runGameTestServer '-PgametestOnly=nuke_layout_.*|nuke_lifecycle_layout|nuke_lifecycle_memory|nuke_lifecycle_detached|nuke_prelaunch_workers_.*|nuke_prelaunch_receipts'`

The first corrected measurement failed on a generic backing-array cast (List[] versus Object[]); the next checkpoint explicitly queries Object[] and passes the actual builders. Both logs are retained. Root MT-S2 owns the requested final integrated full build, second fresh independent review and #568 CI. No helper full build/review/PR/main merge/release/GPU/server work was performed. This is focused ownership/admission evidence, not 1Mt whole-map throughput, all nuclear heap accounting, save/restart, or final release readiness.
