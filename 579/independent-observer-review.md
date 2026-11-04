# R579: private window-observer artifact review

2026-11-04. Codex, Sol xhigh. First independent review of this measurement artifact; the earlier production-source review is unchanged.

## Verdict

**No blocking measurement-corrupting defect found by static inspection.** The frozen artifact is suitable for the owner's planned runtime-Mixin validity gate. This is not evidence that injections actually applied, that a run is valid, or that candidate performance is acceptable. No runtime proof was supplied or assumed.

Artifact directory: `/mnt/data/projects/incoming/mod/run/wt-MT-L2-BUDGET/mod/run/p2-window-pair`.

Reviewed jar: `stand-window-1.0.0.jar`, SHA-256 **5c8f4f7963fbe1b3371e081bfc3b1dafe18f30857a8f5aea29eefae312fb7f37**. The retained callback-version jar is a different artifact and is outside this verdict.

Read primary observer-src, observer-source-hashes.json, observer-build.json, compile/packaging source, mixin configuration, jar entries and bytecode. Also inspected the primary pair preparation/controller and tick-output code for linkage and artifact selection. No helper reports, author notes or diagnosis conclusions used. No compilation, tests, client/GPU/server runs, CI/PR operations or source edits; only lightweight read-only hash/zip/bytecode inspection. No owned jobs remain.

## Artifact identity

- All nine observer sources match both source manifests; classpath, original jar and mixin-config hashes match observer-build.json.
- The frozen jar hash matches the requested hash. Its contents differ from original-stand.jar in exactly the 12 declared overlay entries, with no deleted or duplicate entries. All overlay class bytes match retained observer-classes. The configuration adds precisely the four Window* mixins and retains the existing mixins/settings.
- Bytecode inspection confirms the argument-preserving scalar hooks and field getters. Stand gains observer activation; ServerWatch gains the flush call before advancing its existing ticks counter. Existing tick wall/CPU capture precedes this flush.
- The prepared a1, b and a2 helper slots and their manifests all contain the same reviewed SHA; each launch script enables perfa.windows once. The controller checks helper/tool hashes and inputs before each arm, validates A1 before proceeding to B, and requires baseline account-hook absence versus candidate presence. Future executed-arm identities still belong in run evidence.

## Hooks and scheduler context

`WindowClockMixin.java:16–27` observes end(J)J's original began argument and record(J)V's original nanos argument with ModifyVariable handlers that return the same long. Its stores target only observer-owned stand$ fields. There is no per-record timer, reflection, stack capture, serialization or CallbackInfo handler. The clock/timing views only read fields; no deadline, budget, worked/late flag, estimate or target counter is set by the observer.

Queue hooks select the explicit three-argument work overload, so the public two-argument delegator does not create duplicate windows. Normal RETURN follows candidate reconciliation. Optional accountWindow hooks use require=0, permitting the baseline without that method. The other target hooks retain the configuration's required-injection behavior. These signatures match the reviewed baseline/candidate source, but javac and bytecode inspection cannot validate runtime Mixin transformation.

Scheduler HEAD/RETURN hooks label early and late invocations. Early is visited per world, but only the actual work invocation produces window rows; absent phases are not fabricated as zero-cost windows. impactBudget's returned share and timing's returned object are observed without modifying those results. Early windows see the then-current early_used_snapshot; the corresponding work-phase row is created after the scheduler writes its final earlyUsed. Consumers must preserve that distinction.

One qualification: scheduler HEAD hooks explicitly call e.hasTime once more. They do not call WorkClock.spare or reuse that read to control scheduling. The recorded spare_at_phase_entry is therefore a separate sampled signal, not the exact signal at every admission. In sprint the effective scheduler spare policy can be true despite that sampled event signal. The extra poll also occurs before WindowTelemetry's active check, even when windows are disabled.

## Metric meanings and clock bounds

| Fields | What they establish |
| --- | --- |
| elapsed_wall_ns and observer_begin/end_ns | An observer bracket enclosing the queue method body. Context-map preparation precedes the first timestamp; end payload construction follows the last. This is not the exact production accountWindow sampling interval. |
| used_before/after_ns, charged_delta_ns | Snapshots of the real clock's accumulated expenditure and their difference. They include the candidate's reconciliation; they are not summed ImpactCost kind timers. |
| record_charged_ns, record_count | Per-clock cumulative record arguments/counts differenced across this window. These are actual clock records, including a throwing step's finally, not kind-attribution totals. |
| first/late_record_count and last_record_* | Flags observed at record HEAD, plus the last end argument and measured duration. They identify recorded units with those flags; they do not prove that a particular admission needed a guarantee or met its estimated-cost condition. |
| common_budget_ns, impact_cap_ns, early_budget_ns | Configured common budget, returned lane share, and existing timing allowance. The cap is not necessarily the actual usable window after absolute-deadline/busy restrictions. Read it with the clock deadlines. |
| account_* | Optional candidate sampling arguments and before/after charge, bracketed by observer timestamps. Baseline fields legitimately remain null/false. |

For the actual default System.nanoTime impact clock, let B=account_used_argument_ns, T=account_began_ns, U=account_used_before_ns, E=account_enter_ns and X=account_exit_ns. The target time sample is between E and X, so the reconciled charge must lie in `[max(U,B+E-T), max(U,B+X-T)]`. pair.py uses this valid bound. It avoids requiring observer elapsed to equal the candidate's charge exactly. A non-counting flag alone would not prove clock-epoch equality for an arbitrary custom supplier; the formula is justified here by the reviewed production clock. Counting clocks require their own clock-domain interpretation.

There is no complete canStart/overdue decision trace or per-admission estimate/spare sample. The data can support window accounting and last-record analysis, not a universal proof that every admission or every overshoot was permitted. It also cannot prove strike completion from queue records alone.

## Tick linkage and perturbation limits

Window/phase rows retain MinecraftServer.getTickCount. ServerWatch flushes them with the same stand ticks value used by that tick event, before ticks increments. work-link explicitly maps n to server_tick. A mismatch becomes n=-1 plus tick_link_mismatch=true, and the controller rejects it. This preserves the association without equating stand n to Minecraft's tick number. Mixed-thread event file ordering is not the join key.

The helper is observational with respect to Incoming state, but it is not free. Window entry/exit allocate maps and boxed values and take timestamps; record hooks add scalar operations/volatile reads. The candidate-only account hooks add timestamps/boxing around its sample. Same jar in all arms prevents helper-version drift, but does not make executed observer cost identical across branches or different unit counts.

Serialization happens after the tick wall/CPU sample. It is consequently absent from that row's measured wall_ms/cpu_ms while still consuming real server-thread time, potentially affecting next-tick headroom, allocation/GC and concurrent client frames. The measured CPU is the sampled server thread, not nuclear workers or the full process. The observer window itself also contains some injected overhead. Do not present this run as clean uninstrumented gameplay latency or attribute every observed speed difference to production code.

Queue and scheduler RETURN hooks are not finally hooks. An uncaught exceptional exit can leave incomplete observer state; such a failed run must be rejected, not analyzed as a complete window trace. Ordinary job RuntimeExceptions caught inside UnitQueue still reach normal RETURN. The reviewed use is a fresh single integrated-server run; arbitrary overlapping scheduler invocations or cross-server reuse are not established by this artifact.

## Runtime handback

Keep the same helper for all arms. A1 must establish actual rows, both phases, nonzero work records, valid tick links, matching budgets/deadlines and expected missing account hooks before B. B must separately establish that its optional account hooks really executed and satisfy the captured bounds; an A1 pass cannot validate the candidate-only injection. The existing controller checks hook presence, links and reconciliation bounds, but its success is explicitly a schema/invariant result, not a budget/performance verdict.

No correction to the frozen artifact is requested by this static review. Optional improvements are gating the extra hasTime poll when disabled and making exceptional-exit/lifecycle invalidation explicit if this private helper is later generalized. Runtime application, representative workload validity, completion and performance acceptance remain with MT-L2.
