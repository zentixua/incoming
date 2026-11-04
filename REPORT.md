# MT-RESULT component report — 4 November 2026

## Source and ownership boundary

Fresh `codex/nuke-result-ownership` from exact `6eb589d59df2a1ebdd41a3e2f8577dcd04448c32`, worktree `/mnt/data/projects/incoming/mod/run/wt-MT-RESULT`. Final component head `f91885460875828d3d39dc962513f504ebd9461b` (plan checkpoint `a1e3f19c8`, includes `c1f9ff3f4`, EMPTY follow-up `c99699b30`; separate fragment helper commit `f91885460`). Only RuinPlan.java, new RuinResults.java and three new dedicated test files changed. No independent PR, main merge, release or reviewer started; MT-S2 owns source composition, global producer/consumer glue and new scoped independent review.

## Contract

- `RuinResults.bytes()` is one global AtomicLong, numeric only; no static world/server/context/map owner. The lease only holds a Future already owned by the caller.
- `RuinPlan.maxResultBytes(Grid)` is the exact helper class/name. It bounds the PLAN payload estimate. `RuinResults.tryReserve(maxBytes, room)` adds 128 B fixed lease/map/binding metadata; the caller supplies cap minus current resident ownership INCLUDING existing RuinResults.bytes. Refusal returns null, before solver allocation. Admission is server-thread-only; asynchronous changes only decrease this counter. Aggregate and per-request long overflow refuse safely.
- Solver calls `lease.markResult(plan)` before returning it. This checks the source bound, attaches exclusively to non-EMPTY output, and shrinks to actual plan.bytes()+128. `actualExit()` is the pool's true callable-exit/unstarted-removal callback, not Future.isDone.
- Explicit `cancel()` precedes Future.cancel. It retains the charge until actualExit; cancellation/mark races cannot lend the reservation early. Unmarked failure/unstarted exit releases. Binding after fast completion also detects cancelled/failed futures. EMPTY attaches no mutable lease and releases at actualExit without waiting for collection.
- For queued promotion: `beginTransfer()`, promote/cancel old, `bindFuture(new-or-old)`. Existing pool promotion transfers its single actual-exit callback to the new task; no callback must run for an old successfully moved task. Detached old cancellation must not become final cancellation.
- `detachFuture()` on collection breaks the future/result/lease cycle, without refunding local handoff. Consumer FIRST charges its counted owner, THEN `plan.releaseResultToCountedOwner()` (or lease equivalent). This transfer may precede pool callback exit because the counted consumer owns the completed output. Stale/discarded output calls `plan.discardResult()` or explicit lease.cancel; callback still gates final release. Calls are idempotent.
- `abortBeforeSubmit()` is only for unused admission with no submitted callable.
- `detached()`, store/restore and EMPTY never copy mutable result ownership. Cargo/material/schema-2 output is unchanged.

## Bound derivation and cost

C = 4096 per centre section whose snapshot may contain non-air; mayHave includes original old blocks restored over existing ruins. Collapse.collect and land/ground/plants/fireball/snowy only change these original non-air positions, with no material deposition. Changed cells/old-state references, light checks and material candidates each have maximum C.

F = C+256: wave-broken leaves can each nominate one fire; land at most one per column, including air above the top non-air section. Fires and blown arrays use F; fire lookup keys are prepaid. Fluid ticks scan the exact 18x18 footprint once per y: count section domains with 256 columns in the centre, 16 in each edge neighbour and one in each diagonal. All nine chunks are included, so low/high neighbour fractures or collapse cannot invalidate a centre-surface-only assumption. Outside logs have no active current producer; a conservative C records are nevertheless included. Heights cover 256 columns × five map/sky values × three ints, palette <=1024, fingerprints/hashes use section count, flash <=min(6C,MAX_CHARS), material <=C. No hardcoded full-height plan reservation or dropped output.

Both actual bytes and bound use the same explicit field-element law: ints4, longs/references8, existing fixed plan allowance64, biome payload allowance96. Lazy fireKeys, sectionBits and BOTH oldTops bands (int[512]) are prepaid so lookup cannot grow an already-shrunk estimate. This is a producer ownership POLICY estimate, not a census of all Java object headers/capacities or Minecraft heap.

Important independent boundary: at base6eb, Grid.blast publishes up to nine Blast records into ctx.blasts; currentBytes/shotBytes do not count that cache. maxResultBytes DOES NOT silently include or release that independent cache lifetime. MT-S2/LEAD2 were notified at14:16:40UTC; root owns Grid/currentBytes. Adding cache bytes to this reservation and shrinking everything to plan.bytes would still be incorrect while the cache remains. A separate charged cache lifetime (or source-proven removal of persistence) is required for an all-solver-output claim. This component makes no such claim.

## Validation

All heavy jobs use tools/laptop_job.sh with explicit SESSION_TREES and unique INCOMING_GPU_OWNER=MT-RESULT-20261104; no GPU used. CPU limited to4, JVM2GiB. Initial13units, then16/17units passed; final separate-fragment follow-up19 focused units pass,0failure/error/skip. Compile main/devtest/test occurred through these checks, no duplicated full build.

New unit suites cover admission/owner metadata, scalar and aggregate long overflow, completed-uncollected/local handoff, explicit cancellation before actual exit, discard-after-mark, failure, queued never-started exit, success/cancel/failure before binding, queued promotion/rebinding, early counted transfer, EMPTY, oversized output, duplicate owner,100 concurrent release/cancel/mark rounds, complete field inventory, stable lazy caches and maximum encoded section dimensions.

The dedicated GameTest nuke_result_bound solves nine actual ruin/flash/scorch plans at2/20/50psi using sparse snapshots, a taller neighbour fracture/fluid domain and centre foliage/ground above a section edge. It checks output <= preallocation bound, actual reservation shrink, completed Future retention, full-store/detach/restore equality and absence of copied ownership. Final exact-source plan bound859880B; actual ruin21552/26628B, flash2856/5796/5788B, scorch2856/4376B. All9 actual cached Blast estimates also fit their separate562056B source bound (actual8664B at2psi,11408B at20/50psi). Final logs accompany evidence. This is a component fixture, not a whole-city/1Mt throughput, global512MiB, all-solver-cache or whole-heap proof.

Existing RuinPlanStorageGameTests exhaustive REBUILT inventory needs adding resultOwner. Root explicitly accepted that integration edit at14:17; this component did not edit the pre-existing test outside assigned scope. New dedicated inventory already classifies it.

No independent review/CI belongs to this component branch by explicit assignment; root obtains the combined new-scope review and normal final build/CI. No public512/1Mt readiness claim.

## Separate source-only fragment follow-up (requested/accepted by MT-S2)

`RuinPlan.maxFragmentBytes(Grid)` sums all nine present centre-fracture domains, including an already-cached one (conservative; the caller decides its existing owner). `RuinPlan.fragmentBytes(Blast)` gives the actual estimated cache payload; the five-count scalar overload is a unit-test seam. These helpers add NO ownership fields and change NO cache behavior/lifetime.

`Blast.run` emits each removed centre position once, from original non-air cells. `fellTrees` records at most one accepted centre root per column, then breaks the y loop: trees<=min(populatedCells,256), each a long[3]. The field law includes the9-long numeric stamp, int removed array, tree tuples/array headers, both list reference backings, estimated record/stamp/list shells and map-entry allowance. Append-only ArrayList capacity is bounded by max(10,2n) because it starts at10 and grows by1.5; this is a source-proven backing-capacity bound, not a guessed solver-output multiplier. Root must reserve before solver production and retain through actual temporary/cache ownership, separately from plan shrinking.

One failed new unit attempt constructed a Blast in an unbootstrapped JVM and hit vanilla registry bootstrap (no production failure). Corrected fixture tests the pure scalar field law; actual Blast objects are tested in the registered GameTest. Failed/final raw logs are retained in evidence. No timeout/cap/config changes or blind reruns.

Memory: added canonical ruin-result-ownership.md and its MEMORY.md index entry; numeric producer/consumer lifetimes, queued promotion binding, int[512] oldTops and independent Blast cache lifetime are durable facts with pinned source limits.
