# OPT post-reboot evidence

Current core under review: fb67a826ea31063f485e3b6a089d760e83dead8e. `head.txt` also includes the later independently reviewed main grid-fixture fix. This is a component checkpoint, not release or whole-zone acceptance.

## Exact results and timing

The real-input harness uses 190 distinct Zearth 5x5 grids, 760 cases at 1/6/20/50 psi with 1000 kt geometry, and 3040 alternating reference/current pairs. Each pair compares all Collapse outputs; material-aware detached plan round trips are checked too. Two pairs warm each case and two are timed. All cases pass. All 2177 source hashes were verified; raw NBT stays private.

| Post-warm result | Reference | Current |
|---|---:|---:|
| Median Collapse time | 28.148 ms | 24.069 ms |
| Median allocated bytes | 18.93 MB | 8.87 MB |

Median paired time ratio is 0.862; summed time ratio 0.881. East/west median paired ratios are 0.860/0.870. Across four pressures they range 0.857–0.867. Complete rows and sampling method are included; these are selected dense/median contexts, not a zone-weighted sample.

Cold 5x5 parse median is 8.537 ms and all nine cold Blast fractures 74.459 ms; neighboring production centers can reuse both inputs/fractures. Hot finish/encode medians are 2.238/0.037 ms. These stages do not include live application, support/material continuation, disk receipts, network, client LOD or whole-world restart. Do not multiply them into a 1 Mt deadline claim. The JFR summary identifies sampled hot methods; sample counts are not elapsed stage times.

The synthetic paired corpus is included separately. Historical before/after launches in the old evidence root used different coordinates and are not a valid paired speedup proof.

## Ownership checks

- The integrated core passes 1796 unit tests (zero failures/errors/skips) and 50 affected GameTests. Build 3m33s; GameTest run 1m16s. Logs are included.
- The actual-event marker regression and source/frozen-hint/ceiling/disabled-destruction checks pass in the separate six-test run.
- [Material field/cargo evidence](https://github.com/zentixua/incoming/tree/c5a208999c89fd52b693dd63cc0cb066a183bfc0): complete schema v2; original, detached, direct codec and real spill/apply each produce 64 records / 58,560 kg / 64 pending support sources and retain the exact populated barrel through actual SavedData file reloads.
- [Worker evidence](https://github.com/zentixua/incoming/tree/b1952151d355c434125a902c5c27e260bd1854b9): configured 46/256 threads admit 17 actual workers within a 224 MiB estimated workspace partition; public preparation reaches READY before/after actual pool initialization/expiry.
- [Layout evidence](https://github.com/zentixua/incoming/tree/03cdb08c51d3e1197f5a0789d15e5b3786854ac2): actual retained graph is 6,167,040/27,001,280 bytes at 100/1000 kt, versus old array accounting 990,112/4,404,048. Conservative pre-admission construction reservation is 45,003,264/188,453,376 bytes. Cancellation retains actual running construction and completed-result ownership; installation transfers its charge. The physical layout is unchanged. These are live window estimates, not a complete JVM heap proof.
- [Merged B5 evidence](https://github.com/zentixua/incoming/tree/ef7cf1d7cad62f60c5f7bd5dd3fcd9b72f65d50a): frozen streamed ledgers, cold admission/progress, reservation/release and migration remain the material storage authority. Draft OPT packed disk format was discarded before release.

Initial failures/review are retained, not waived: detached material was omitted, far cached sources bypassed validation, high configured worker counts blocked admission, and ceiling target resolution differed. Further source fixes preserve deferred frozen-ledger cache hints, remove redundant finished-window records, bind actual event markers and account layout lifetime/backing capacities. The independent follow-up at fb67 is now included. It found two additional serious ownership/readiness gaps. The actual solver-input owner was corrected in LIFE8c521e51e and source-publication failure handling in c9c92be69. The follow-up review was not declared clean retroactively: its original findings are retained. Seven focused units and five actual populated-Grid lifetime tests pass, together with the three ordinary/cached-fallback/absence publication regressions. [LIFE raw evidence](https://github.com/zentixua/incoming/tree/dc32adeabc4bb06d4f7078a2ce537332ade57792) records queued promotion/cancellation, expiry, forget/world removal, cancel and clear. No third core review was requested under the repository workflow.

Current composed core0ac334e17 includes those fixes, backing/lifetime corrections, the completion-only page read fence and a structural-candidate iteration-order assertion for the material FIFO. Required CI568 is green at that exact head; the previous fb67 CI failure was only the independently fixed grid fixture, preserved here. Final carrier/control composition has a separate single full build + union validation owner; whole-runtime acceptance remains pending.

## Remaining acceptance

Normal carrier intent versus terminal geometry, unexpected-contact fallback, actual full pipeline restart/application/material/LOD, server/client/network measurements, bounded whole-zone resident memory and the 1000 kt / 10–15 minute player goal remain separate gates. No public yield ceiling or release is authorized by this evidence. Raw world NBT/JFR and machine/session notes are excluded from this branch.
