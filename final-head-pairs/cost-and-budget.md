# Final-head per-chunk cost including support continuation

Production head `f92dabaaac30bb04b3e9b07296be44a2602114c8`; only the two archived timing wrappers modify production classes in the disposable measurement checkout. `PipelineBench.registered.java` registers every event with `NuclearEvents`, runs actual `RuinMatter.work`, waits at least 240 ticks and until the material/support queue is empty, then records timings. Setup, light-barrier time, packet encoding and serialization are outside apply/support timing. No GPU was used.

**The old 7.68 ms was apply-only. On the new registered dense fixtures, apply plus actual support continuation costs 10.10 ms median per input chunk (7.93–39.82 ms across four warm samples).** The sum is cumulative CPU over several ticks, not a single server-tick stall. Support is included once: it is already a subset of allMaterialMs. Do not add the separate 0.65–1.41 ms/chunk roof-only probe to the old 7.68 ms: those are different workloads.

| Sample | Fixture | Changed blocks | Apply ms | Support ms | Apply + support ms | All material work ms | Apply + all material ms | Support batches | Largest support batch ms |
|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | roof | 0 | 5.231 | 0.000 | 5.231 | 1.300 | 6.531 | 0 | 0.000 |
| 1 | concrete | 4072 | 10.008 | 0.000 | 10.008 | 79.047 | 89.055 | 0 | 0.000 |
| 2 | timber | 4290 | 9.741 | 10.265 | 20.006 | 147.444 | 157.184 | 619 | 0.125 |
| 3 | roof | 0 | 0.322 | 0.000 | 0.322 | 0.901 | 1.223 | 0 | 0.000 |
| 4 | concrete | 3859 | 6.575 | 2.629 | 9.204 | 92.595 | 99.170 | 257 | 0.042 |
| 5 | timber | 4194 | 6.445 | 33.375 | 39.820 | 359.909 | 366.354 | 2879 | 0.369 |
| 6 | roof | 4 | 0.380 | 0.169 | 0.548 | 1.258 | 1.638 | 18 | 0.037 |
| 7 | concrete | 3907 | 5.497 | 2.430 | 7.928 | 78.898 | 84.396 | 337 | 0.026 |
| 8 | timber | 4153 | 4.989 | 5.999 | 10.989 | 192.582 | 197.571 | 831 | 0.018 |

Samples 0–2 are the first pass; 3–8 repeat the three fixture families. As in the earlier probe, the source footprint is rebuilt in the same world: previously deposited material outside it remains and can be reconciled by a subsequent strike. The changed/released counts therefore differ; these are per-input-chunk observations, not an equal-work speed comparison. The slow timber repeat releases additional old rubble outside the rebuilt footprint. All samples finish with pending=0, and the registered support callbacks are explicitly counted. Roof rows that break nothing are not used in the dense-fixture median.

Including moving pieces and settlement as well as support gives 84.40–366.35 ms cumulative apply-plus-material CPU per warm dense input chunk, spread through the lifecycle. Largest warm support batch is 0.369 ms. No world-wide throughput, hard real-time or retained-heap guarantee follows from this small probe. Raw log, instrumented source and timing patch are beside this report.

## Shared 512 MiB budget: B5 scope

Follow-up (b) is deliberately deferred in full to B5 under Artem’s small-follow-up exception. `NuclearWorld.retainedRuinBytes` currently sums preparation and ruin contexts; `NuclearPrep.memory` shares that estimate across dimensions plus worker buffers. It does **not** yet account for moving pieces/container NBT, support-source/cold-face queues, live graph and original contacts, stranded records, or crater passes.

Adding an approximate counter alone cannot bound their creation by already-admitted plans. These records own material and container contents, so admission must reserve space before world removal, and exhausted space needs a resumable/durable path rather than dropping ownership. Terminal compaction must keep per-type totals and inventories while retiring completed trajectory data and crater column/done checkpoints, with clean-save/restart idempotency and cross-dimension admission tests. Completed original-contact journals already retire in the reviewed fix; no other budget integration or compaction is claimed here. No partial accounting change is included in this follow-up.
