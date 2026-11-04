Reviewed at 2026-11-04 10:13:24 UTC

# RMT-S2-OPT-2 independent source review

Reviewed snapshot: `fb67a826ea31063f485e3b6a089d760e83dead8e`, detached and clean, against main `eee272309e1c8306952f32b5f6b7ed4192be2e7c` (B5 already included). Session process explicitly uses GPT-6.1 Sol, xhigh. Production tree remained read-only. No jobs, builds, tests, CI watchers, sub-reviewers, GPU or server operations were started by this reviewer. Author PR text, handoffs, inboxes, reports, earlier verdicts and measurement summaries were not used. Current TEAM/BOARD/context/live, repository rules, the memory index and general cancellation facts were read.

Verdict: **two serious findings remain; do not accept this backend snapshot as complete.** These are source-proven failure paths, not claimed reproductions from newly executed tests. Whole-product CTL2 integration, proposed nominal terminal geometry and full 1-Mt runtime acceptance are outside this review.

## F1 — P1: cancelling a preparation drops the known context charge before running solvers exit

Primary locations (all under `mod/src/main/java/ua/zentix/incoming/nuclear/world/`):
- `NuclearPrep.java:548-553`: drain calls `cancelTasks()` and immediately removes the preparation when it has no tile tickets.
- `RuinContext.java:627-634`: the running callable captures `Grid g`; `Grid.ctx` retains the context, including its snapshot cache, heights and fracture cache.
- `RuinContext.java:758-766`: `cancel(false)`/`tasks.clear()` abandons the future owner, but neither waits for execution exit nor transfers a retained-byte owner for those inputs/context.
- `NuclearPrep.java:473-480`, `NuclearWorld.java:181-188`: the context charge exists only through attached preparations or handed-off contexts. An unbound preparation's context is not in the latter map.
- `RuinWorkers.java:289-348,407-438`: task counts correctly survive running cancellation, but the global worker byte charge pays solver workspace, not the now-unattached context's known retained data.

Failure sequence: prepare A until a real solver is running with a populated context; cancel A (owner cancellation, expiry or replacement has the same drain). The next tick removes A and its entire `p.ruins.retainedBytes()` from cross-dimension admission. `FutureTask.cancel(false)` does not end its running callable: `g.ctx()` and its caches are still reachable until that callable returns. New preparation B can therefore spend space that A still owns. With several busy workers/cancel-refire cycles, several distinct old contexts can overlap. This is a loss of an already-known admission charge, independent of any claim about whole-JVM heap precision. The new layout owner handles the analogous case, but does not cover solver contexts.

Required correction: retain/transfer cancelled-context input ownership until the last actual execution exits, or detach solver inputs so the retained graph and its byte owner are explicitly bounded. Merely keeping `inFlight` or switching to `cancel(true)` does not transfer this payload ownership. Do not clear shadow/fracture state while a still-running Grid uses it.

Discriminating regression: populate a real Grid/context, install a gated actual RuinWorkers task capturing that Grid in the preparation's task map, wait for its execution to start, and cancel/clear through the registered preparation drain. Keep the gate closed after Future.isDone becomes true. Assert the old context's measured admission estimate remains charged, and a second dimension/handle cannot spend that room; then release the worker, await actual exit and assert exactly one release and renewed admission. Cover queued cancellation separately. Existing lifecycle tests retain delayed read/front-copy reservations; the new layout tests retain LayoutBuild. Neither exercises cancelled running solver-context payload ownership.

## F2 — P1: a source-snapshot exception can certify an explicit handle READY

Primary locations: `NuclearPrep.java:915-928`, `1014-1026`, `394-410`.

The ordinary far-read completion catches `RuntimeException` from `p.ruins.putDisk(level, r)`, marks the coordinate unreadable, and logs it, but leaves `p.failure == NONE`. For an explicit handle, when that coordinate is an order root and has no published snapshot, the far walk then increments `farSkipped` and advances. `status()` includes `farSkipped` in `complete`, so once remaining reads/plans/writes drain this handle becomes READY even though a final-block source on disk failed preparation. Both bind methods accept that READY stage.

This is a real callback boundary: `putDisk` primes missing maps and constructs ChunkShot; those operations call `Blast.props`/state property publication, including a block's collision-shape callback (`Blast.java:189-205`, `DiskShots.java:87-106`, `ChunkShot.java:178-193,210-229`). A modded source whose callback throws here is not an absent/partial chunk. The adjacent `r.skip()` error path and submit/collect exception paths already fail explicit handles with IO; this catch is the omission. Cached-source fallback can also reach fresh source publication, which must preserve the same rule.

Required correction: fail the explicit request (or keep it explicitly pending for a bounded retry), never count source-publication errors as completed non-required terrain. Legacy flight fallback can remain separate.

Discriminating regression: deliver a successful final-block `DiskShots.Read` for a required root, force an exception at priming/snapshot property publication (a devtest block callback or an injection at that boundary), drain the ordinary far-completion/cursor path and then query status/bind. Require FAILED/IO or an honest retry state, zero READY/binding, and no completed-skip credit for the failed required root. Separately retain absent/partial disk coverage so legitimate non-required chunks are still handled as intended.

## Checked boundaries without another serious finding

- RuinPlan authoritative arrays were traced from producer through detached payload, named palette/schema v2 restoration and apply: material positions, outside logs, biome cells, fires/blown flags, face scorch, fluids, old states and light/height data. Rebuilt process-local hashes and discarded fingerprint fast paths were checked against the apply fallback. Live container cargo is captured at source removal, not manufactured by stored plan data. The field inventory plus real structural barrel/masonry fixture tests detached/restored/spilled routes, material/support ownership and four real SavedData file-wrapper reloads.
- Compact FIFO widths preserve source/state/cargo identity and every motion/scorch/volume/mass field. Cargo side-map removal, requeue and dormant reservoir transfer were inspected. Frozen LedgerData file-wrapper ownership, streaming old/new schema loading, pre-admission cold scan, cross-dimension cold priority and over-cap preservation remain consistent at these boundaries; this review does not reopen B5 as an unmerged change.
- Layout reservation, backing-capacity accounting, cancellation-before-start, cancellation-after-start, completed result transfer, per-handle/dimension identity, lease/configuration invalidation, ceiling terrain and empty-work handling were inspected. Async layout/read/write ownership is materially different from F1's solver-input lifetime.
- ScarQueue stale/failure paths submit to the bounded pool and do not borrow the old synchronous solver; expired cancellation waits on RuinWorkers.finished before collection. Window/archive reads validate unscarred fresh sources while retaining original scarred neighbor inputs. Actual event marker adoption is independent of immutable geometry.

## Optional improvement

`RuinPages.java:44-49` retains the last successful read's Optional<CompoundTag> as `tail`, after the consumer has restored the plan and released its read reservation. Keep only a completion-only fence in tail (while returning the payload future to the caller), or account that bounded retained payload. This is at most the last result per store, not an unbounded history; no independently measured cap breach is asserted here.

## Raw evidence independently examined

- `wt-MT-OPT/mod/run/mt-s2-real-corpus.log`, ignored `mt-s2/RealCorpusBench-complete.java`, `mt-s2/evidence/real-corpus-rows.json`, and pinned ReferenceCollapse/RuinStorageGameTests. ReferenceCollapse differs from current-main Collapse only by class rename and one provenance comment. Harness uses the same immutable 5x5 Grid with identical seed/geometry and precomputed Blast inputs, alternates old/new order and checks gone/drained/state changes/counts/ground/leaves/fluid outputs each pair. 190 distinct roots x 4 pressures = 760 cases; 3040 pairs, including 1520 after two labelled warmup pairs per case. Logged completion: all 1 required corpus test passed and BUILD SUCCESSFUL.
- Recomputed from raw non-warm rows: dense512 old/new Collapse means 50.742/45.023 ms, allocated bytes 30,096,859/17,172,266 (1024 pairs); dense1024 19.330/16.336 ms, bytes 13,971,793/5,658,688 (496 pairs). Both full and sufficient-support paths occur. Parsing is serial file-read/parse timing, finish/encode are separate; those costs are not clean paired before/after stages. This benchmark demonstrates local exactness and solver cost, not async throughput, player MSPT or end-to-end 1-Mt readiness.
- MT-MAT raw compile/storage-final/build-final logs: successful; 8 storage tests, four real routes each release 64 pieces, volume 26.2 and mass 58,560, with support and identical barrel ownership. MT-BUF raw unit/GT logs: successful, 2 registered preparation-worker GT. MT-META raw instrumentation harness and measurement/corrected-measurement logs: initial failed ClassCastException is present and not hidden; corrected2 succeeds. Actual retained layout graphs are ~6.17 MB at 100 kt and ~27.00 MB at 1000 kt; corrected reservations 45,003,264/188,453,376 bytes. Harness uses identity-deduplicated Instrumentation graph sizes and thread-allocation deltas, not whole-process heap. Its separate unit/8-GT logs succeed.
- Root event-marker and corrections-final logs: 6 and 7 required GT pass, respectively. Fallback checks: build and 9 required GT pass. Final integrated root logs finished during this review: `mt-s2-final-core-build.log` BUILD SUCCESSFUL (3m33s); `mt-s2-final-core-gt.log` all 50 required GT passed, BUILD SUCCESSFUL (1m16s). These successes do not exercise the two missing discriminating regressions above. No CI result was inferred or watched here.

Final state: pinned detached worktree still clean. No owned jobs or child reviewers. Root retains execution, CI and final integration/merge ownership.
