# Existing archive progress under retained-memory pressure

Production observation: diagnostic artifact fc07be9e6 has the same production code as f8481f253. The root-owned one-shot snapshot at 2026-11-04T16:31:43.751Z ran on the guarded existing server thread after preparation expired. Private agent source is included; source SHA256 e23a7a53d96cfc4978674af4c75deab97b6e4228a915123055d4092b62befd2f; deployed jar SHA256 57777c6e38dd7aef4391f45e3283710407894dedeba151509879769924aff144. The snapshot took48.207066ms, outside the baseline measurement window. It did not invoke mutable eviction/admission/accounting methods or change the world.

The observed context retained435036688 snapshot bytes.1247 snapshots /113013570 bytes had no pending-window/task pin and no matching saved or completed-write receipt. No immediately reclaimable unpinned snapshot existed. The old archive supplier reserves configured future worker buffers plus64MiB transition space: those and snapshot bytes alone total634855952, already above536870912 before any other owner. Thus no new archive write can begin to reclaim those113MB. This establishes the current post-expiry reclamation dependency, not the missing historical far-frontier head.

A separate observed visible parked plan had22054 material cells and memoryWaiting=true with all25 chunks ready. Its old minimum material reservation191958016B is a separate blocker owned by CI-ASTRA. This archive fix alone is not a1Mt readiness claim.

## Controlled source reproduction

Base f9d1ac8ee13f612f7774d9a5e72dfb6a4e3c96e0 includes the exact production archive path plus previously reviewed integration fixes. The new GameTest opens the actual production restored archive, retains a fixed other-dimension owner, attempts bounded eviction of one unpinned original, requires the exact serialized original to survive real region write/read, and samples known resident ownership. It does not fabricate successful I/O, refund the external owner, force GC, change the512MiB cap or alter physics.

On4CPU/2GiB, RED keeps fixed530276862B; resident532676608B; future-admission626327552B. The17006-byte original remains after200callbacks with zero writes. GREEN changes only the six NuclearPrep cache spill/read/pump room callers to resident headroom: the same fixture/pressure finishes after3callbacks, retains the same external owner, reads the exact original payload, peak sampled known resident532727674B. Logs and the identical diagnostic test source are included. These are fresh deterministic fixture worlds, not a replay of the entire old1Mt world.

The earlier archive-red.log also reproduces the refusal but ran with16cores: JAVA_TOOL_OPTIONS outside laptop_job was not forwarded. It is preserved and is not claimed to be the constrained pair. The constrained RED/GREEN put environment options inside the wrapper command.

Final source9b93e305dd90e6f7d2928373fcf60000437b9969 additionally isolates the fixture event ID and restores its archive pointer at cleanup; raw pair used the preceding fixture version. Final exact-source combined build/targeted GameTests, fresh independent Astra/high review and PR594 CI remain in progress at this checkpoint. PR594 targets the integration branch, not main; root owns final integration and real100/1000kt acceptance. No publication authorization is inferred.


## Independent review finding and bounded correction

The first independent Astra/high review of9b93 found a serious cap gap: plan writes added retained-source B and48-byte receipt ownership after admitting only their3B encoder allowance; snapshot writes similarly added48 bytes. The28-targeted/1840-unit run and required CI on9b93 passed but did not catch this. That head is superseded and was not cleared for merge.

Two new regressions use actual production-restored owners and real region writes held behind an incomplete ordered predecessor, preserving the actual directory probe. They provide the total complete counted cost of two writes minus one byte. RED admits both and observes536870913B before any receipt can refund, for both plan and snapshot writes. GREEN admits only one; explicitly funding the missing byte then admits both at exactly536870912, still before releasing the gate. Actual receipts subsequently complete and release ownership. No fabricated successful receipt or wall-clock sleep is used.

The same boundary fixture/one-byte condition is used in both runs. Snapshot full cost is102132B in both. Generated physical plan sizes differ across fresh fixture worlds (complete cost502432B RED,490144B GREEN); this is not a claim of byte-identical plan inputs. The invariant and exact known-cap violation are independent of that size, and each run computes its boundary from the actual retained plans. The original archive test also remains GREEN in the corrected run.

Final correction5d431b337 debits all incremental counted write owners in RetainedPlans.pump, including successive writes, and requires snapshot receipt metadata before admission. It narrows the supplier change to cache WRITES; retained-plan demand reads keep the prior admission policy. NuclearPrep's four changed writer/pump call sites use resident headroom. The known cap, fresh preparation/worker admission, snapshot pins/serial validity and physical output are unchanged. Material-plan admission remains separate PR595.

A second fresh independent Astra/high review and final exact-head build/30targetedGT/CI are running. This is the one permitted follow-up review after the serious finding, not a repeat of old producer reviews. Final receipts will be added before handback.


## Final local validation and second review

Exact5d431b337563be9f4fa7bae23e5af14ff535d80b clean source passed build,1840unit tests (zero failures/errors/skips) and30targeted GameTests. Wrapper exited0; elapsed4m21s. Final run again exercises both held-write boundaries and the archive progress case. Source/compiled-class/jar hashes and test totals are in final-validation-receipt.json; this receipt was collected after the unchanged-source run, not embedded retroactively into rawlogs. The fresh second Astra/high review found no serious issues and independently confirmed the first P2 resolved. No third review. Exact-head CI is still pending at this local checkpoint.


## Exact required CI completed and component integration

Required check126726077926 for exact5d431b337563be9f4fa7bae23e5af14ff535d80b, run41872103400, completed success at2026-11-04T17:04:12Z. The direct exact-job watcher completed0. The original pr_ci594 waiter had returned0 earlier because root's normal integration merge auto-closed the PR by ancestry; that shortcut was explicitly NOT treated as a green required check. Both waiter logs and the final exact-head check-run receipt are included. No rerun was requested.

LEAD2 explicitly accepted component integration ownership. Its normal combined merges are READYb5610b3c91a2c1e2e2d09d28c0fd503464a43982 / NATURAL0f1a77ff88b4e6546212aec9ab34a834eda525d1, including separate material PR595. This is integration-branch delivery, not main or release readiness. Fresh combined runtime acceptance and any further diagnosis remain root-owned.
