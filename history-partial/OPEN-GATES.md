# MT-B2: implementation boundary

The current draft repairs the independently reproduced short-window regressions. It does **not** complete MT-report §§11–17 or §33.2 B2.

| Already available | Why it is insufficient for the remaining work |
| --- | --- |
| Immutable snapshots, cached fractures, worker plans and stale-plan checks | Each plan still owns a fixed 3×3 collapse window; `RuinContext.Grid` captures 5×5 snapshots for those windows. A component outside that boundary has no continuation or shared source revision. |
| Whole-tree `outside` entries | `ScarQueue.logs` is an in-memory queue; `RuinPlan.placeLog` can return without placement, and entries do not carry conserved volume or a durable idempotency receipt. |
| A single atomic `RuinPlan` delta | Removals, rubble and trunks are written together at front time. There is no separate release/fall/deposition revision. |
| Column removal counters | `Collapse.collect` excludes logs, leaves, fluids and materials below hardness 1; `RuinPlanner.rubble` takes 12%, caps height at four, and `land` omits columns below three removals. This is not a material ledger. |

## Current implementation

- Structural logs participate in pressure fracture; recognized trees still take the whole-tree path first, including preservation of standing trunks below the toppling load or with tree fall disabled in every fracture round.
- Vertical tree recognition scans the actual snapshot height rather than stopping at 24 blocks.
- Closed surviving components are reconciled against original anchor connectivity after short-span collapse. The existing scratch arrays are reused. Unknown horizontal boundaries remain unresolved; they are never used as anchors by the added pass.
- Nuclear-only fallback for untagged breakable hardness above 9 preserves the strongest existing breakable proxy, while hardness ≥50 / negative hardness remain protected. Conventional response is unchanged.

## Not implemented or accepted

The fourth reproduced case (a thin roof disappears without rubble) remains unresolved. So do material/connection descriptors, load/impulse response, arbitrary boundary continuation, exact solid-volume accounting, durable cross-chunk transfer ownership, staged motion/deposition, diagonal/branched tree transport, pile spreading and crater/ejecta accounting. No full 15 kt structural/material gate is claimed, and no larger yield is exposed.

A safe continuation must introduce a shared source/deposition revision with bounded resumable boundary requests, durable pending transfers and receipts, and distinct causal times before replacing the decoration with authoritative deposits. MT-B1 is independently changing preparation ownership and publication. The [MT-B1 gate report](https://github.com/zentixua/incoming/blob/1d7bf0946780f70572bd4ad90d7872fdaf72b8b9/MT-B1-gate.md) records the supported staging gate as CLOSED: installed Voxy 0.2.16 / Roxy 0.4.1 / LSS 0.20 expose no hidden staging, clocked activation and stale-revision rejection contract. The transport probe is not renderer-visibility evidence; MT-report §33.2 B1 explicitly forbids assuming that primitive exists when starting a large solver rewrite. This is an integration requirement, not proof that all remaining B2 work is impossible in isolation.

The draft must stay unmerged and must not be presented as completion of Part 2. A connectivity repair alone is explicitly insufficient under the accepted plan.
