# Snapshot producer admission component

Source: `05a5f09146fed04a403b103db27cf949c39306fe`, branch `codex/nuke-snapshot-admission`, exact base `6eb589d59df2a1ebdd41a3e2f8577dcd04448c32`. Two focused commits; root manually integrates disjoint RuinContext methods. No PR, full build, reviewer, GPU, remote server, merge or release was launched by this component.

## Implemented boundary

- A live Grid reserves the complete missing/current-invalid snapshot window and unique loaded shadow-height band before any ChunkShot.take or height payload allocation/publication. An admission refusal returns null; existing current snapshots can be reused at capacity. No unloaded world reads.
- Pure server-thread `RuinMemory.reserveInputs` uses the existing all-dimension resident counter and materialReservation, without ledger admission/configured worker growth. Reservation closes in finally.
- Disk publication reserves from actual detached palette sizes before priming/constructing/publishing. putDisk false means retry the same owned Read. Window inbox keeps its outstanding reservation through publication/discard; refusal does not poll, mark absence, set failure or advance read counters.
- Snapshot known-byte law prepays lazy Sec references: 4096 references for ruined/global-palette sections, palette-size references for local sections, plus fixed Sec allowance. Embedded old-plan bytes and oldTops are counted. Same pure section law is used before capture and after construction.
- Archived restore bounds present section palette domains, embedded old-tag arrays and known metadata before decode/publication. Its nullable Boolean API distinguishes MEMORY from stale validation. Invalid dimensions retain the existing decoder rejection/fallback route.
- flashHeightsReady admits a standalone centre shadow tile before a stored flash plan writes it.

## Executed checks

Own laptop_job compileJava + compileDevtestJava: PASS, 13 s. Initial four actual GameTests: PASS, 22 s wrapper. Extended six: PASS. Final exact source tree: `./gradlew runGameTestServer -PgametestOnly="nuke_snapshot_.*" -Dorg.gradle.jvmargs="-Xmx2G -XX:ActiveProcessorCount=4"` via laptop_job, explicit SESSION_TREES and INCOMING_GPU_OWNER=MT-SNAPSHOT: **7/7 PASS**, tests 5.606 s, Gradle 31 s, wrapper exit 0. Raw logs attached. No outstanding owned job remains.

Proofs: whole-window refusal before serial allocation, no partial cache/heights, retry identical actual snapshots and complete physical/material plan; actual global-palette snapshot larger than 96 KiB waits and retries identical data without loading cold source; refused ready inbox retains owner until publication; replaced input remains charged until actual numeric consumer exit; lazy global/ruined property arrays prepaid without changing charge on access; archived embedded-old plan remains charged and nullable restore retries; standalone flash height preflight waits before growth and reuses an existing tile at capacity. Tests artificially occupy the existing cross-dimension reservation, never change caps/threads/timeouts.

## Integration contract / explicit limits

Root must guard every production grid/flashGrid result, putDisk false, new restoreArchived(level,...) null, and flashHeightsReady false before writing. Lifecycle/submit/collect/expiry/plan-transfer methods, NuclearPrep, FlashScorch, RuinPlan and RuinWorkers are intentionally unchanged here. Older non-level restoreArchived is retained for test/internal already-reserved use only.

This is a known-array/serialized-palette admission estimate, not a full object-heap bound, global continuous peak, timing guarantee or real-map 100/1000 kt acceptance. ChunkStorage.read/upgrader/raw NBT and PalettedContainer codec transient allocations occur before a detached Read exists and cannot be bounded through these allowed producer methods. Archive codec scratch remains an API limit even though its known published arrays are reserved. Existing cross-context/world/adapter ownership outside this component is not claimed. The archived present-section maximum deliberately overestimates local palettes rather than guessing world height times a giant factor. Current/pre-existing asynchronous raw decode reservation policy is unchanged.

RuinContext.storedOldBytes duplicates the supplied MT-RESULT final source field law conservatively because its sourceBytes helper does not exist on this exact base. Root may mechanically replace it with the shared sourceBytes helper on integrated source. Lazy oldTops can be safely overcounted once both components prepay it. Root owns cache/fracture accounting, caller glue, fresh independent scoped review, full checks and actual runtime.
