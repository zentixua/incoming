# RPROBE6 independent source review

Verdict: **no serious new defect found** in the four-line compatibility delta. This is source-only approval of that delta for one post-clean snapshot on the owned disposable 25571, outside performance measurements; it is not native attach/runtime acceptance.

## Exact inputs

- `W=/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2`
- `ownership-probe5/OwnershipAgent5.java` SHA256 `aea923d7cd3166343f38eef52d4d931e67b8e423a991ce15fa26397f55ad6fe9`
- `ownership-probe6/OwnershipAgent6.java` SHA256 `4242a5690644a74af29e6e3e5aad6277ef4ebfc5beb5e5d716353ca450e18214`
- Production inspected with `git show` at `wt-MT-OPT` commit `96648b0d730a93977ffe97b047d67f03885676ee`; READY remains clean `3efac357cc368045f6fe821ac02c561ab99af4c8`. Both worktrees left unchanged.
- Actual diff: mechanical class rename plus lines 303–306 in probe6. No author conclusions, PR bodies or STATUS were read. RMT-S7B approval is not assumed.

## Call graph and safety

`supportTables` (probe6:299–314), reached by `snapshot:71`, retains its three-name/eight-field loops. Existing Map owners follow the old path. Exact production `RuinMatter:142–147` initializes non-null supports/uncertain maps and a non-null `RuinOriginals` journal. `RuinOriginals:19,61–76` is not a Map and owns final non-null `Pages extends Long2ObjectOpenHashMap<Page>`.

The new branch calls existing `dump:363–376` then `read:396–403`. `dump` reads instance fields, with existing `summary:378–390`: journal primitives become scalar text, scratch arrays become lengths, and pages uses the existing Map.size summary. It does not traverse Page entries. `read` walks superclasses using reflection and reads the pages reference. The existing optional field reads then find inherited fastutil headers/arrays. No new cast, payload loop, production journal getter, plan-future payload access/collection, admission/eviction, ownership transfer, chunk/world loading or gameplay mutation is added by this delta.

Reflection is deliberately schema-specific: missing pages/inaccessible fields or an unexpected null pages owner abort the snapshot through the existing `agentmain:25–35` failure path. Optional absent table fields render null; this is not zero capacity. The exact inspected schema has the required non-null field. There is no broad catch that silently turns incompatible originals into an empty journal.

## Interpretation and limits

- `originals.journal` scalar `size` is total original block entries (`RuinOriginals:76,123–133,249–251`). Its pages summary and the following `originals type=...Pages size=...` count outer section pages; n/key/value describe that outer table's capacity. `pageBytes` is a maintained aggregate, not measured heap. Inner per-page table capacities/occupancy are not enumerated. Never equate outer pages with original entries or claim this reports complete retained heap.
- Added work is bounded by the fixed journal fields, superclass depth and the existing fixed header list, independent of journal entry count. The inherited complete observer has loops/locks and reflective/native calls; its 15-second writer timeout does not cancel the scheduled server runnable or establish a hard walltime bound.
- Inherited native FULL lookups can warm vanilla lookup cache/profiler bookkeeping. No absolute zero-side-effects claim is approved. The compatibility delta does not expand those calls.
- No compilation, JVM, game, GPU, server, CI or nested session was launched. Future attach success, heap headroom, runtime readiness and measurement cost remain root-owned and unproven here.

Handback: LEAD4 owns observer compilation/hash/native observation and canonical waits; MT-S7 retains production. All RPROBE6 owned jobs/GPU/server/CI/review resources: **0**. End review; free slot for AIR8 receipt.
