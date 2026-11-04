# MT-B1: one-tile Voxy/LSS gate

Result: **CLOSED / unsupported by the installed integration contract**, 3 November 2026, baseline 341c2ea6f. This is a negative capability/transport result, not a passed rendering experiment. Do not let B3/B5 assume atomic time-indexed LOD publication is available.

The requested contract is: prepare a ruined tile invisibly on the client; confirm that preparation; select its revision at an agreed event time without rebuilding/transferring then; reject older LSS data; retain that state across unload/reload.

Inspected the actual installed official Voxy 0.2.16 (Roxy 0.4.1) and LSS NeoForge 0.20.0 jars read-only, not just newer upstream sources. `javap -public` inventories and SHA-256 are in wt-MT/mod/run/mt-b1/. Voxy's called rawIngest interface accepts world, section, coordinates and light, returns enqueue acceptance, and has a queue counter; it has no revision/timestamp/hidden-stage/activate/render-complete operation. WorldEngine's section identity is level/position, with dirty/save callbacks, not selectable revision identity. Its implementation mutation hooks are not a supported staging API. LSS's public column consumer/dispatch and ingest-failure/backlog API likewise supplies no server revision reservation or client atomic activation. No reflection-based renderer mutation, mixin, fork or upstream contact was attempted.

The one-tile CPU transport probe exercised the real Incoming VoxyLods.Viewer with a real blocked Netty outbound channel: one prepared 4096-byte tile remained server-side for 100 drain attempts after it was eligible, with zero delivered; releasing transport delivered exactly one tile. JUnit passed (exit 0). This demonstrates the distinction between bounded delivery and deadline activation, not a Voxy GPU timing result. Current VoxyLods.notBefore holds bytes on the server until the deadline; VoxyIngest.accept calls rawIngest on receipt. Sending sooner would feed the live database sooner; holding until due adds transfer/ingestion after due. Neither implements the required primitive. The client queue acknowledgement does not certify renderer visibility.

Current LSS overwrite handling reports a stale column after it arrives, then asks the server to resend. Saving settled vanilla chunks triggers LSS's real save/hash invalidation path (#554); that repairs durable data but is not a pre-blast hidden revision or stale-replay barrier. The real-LSS GameTests passed with the B1 checks: 45 extended local GameTests with LSS 0.19, then nine focused tests explicitly using LSS 0.20 (including all five LSS integration cases). They validate save/store repair, not the missing staging contract. No unload/reload/slow-client visual proof of atomic activation is claimed because the prerequisite interface is absent. No GPU or player world was used.

Fallback for this front: continue useful bounded lifecycle/ownership fixes at the existing 15 kt ceiling, retain ordinary Incoming delivery and durable settlement, and disclose the missing staged-LOD promise. Do not substitute eventual refresh for passing this gate. A supported optional/upstream revision-selection integration (or a separately approved architecture change) is needed before B3 can close this requirement. Raising yield remains B5's responsibility; B1 changes no yield, flight setting, solver or presentation.

Draft PR: https://github.com/zentixua/incoming/pull/557. Evidence is archived separately at https://github.com/zentixua/incoming/tree/look-gate/mt-b1; see its README, checks.json and SHA256.json. The PR body links this gate and the raw evidence.

## Lifecycle fallback scope

PR #557 retains useful current-yield fixes: one resized worker pool; immediate queued-cancellation release; running work charged until actual exit; captured executor accounting tied to its lifetime; asynchronous result generations and per-candidate identity; nonblocking unfinished-layout handoff; and shared retained-memory admission estimates across server dimensions. Existing saved revision/checkpoint and late-load machinery remains in place and is regression-tested. No solver or rendering output changes are intended.

This is the reduced result allowed by §33.2, not completion of the unrestricted B1/B3 architecture. Eager zone layout enumeration, synchronous stale-plan fallback, complete temporary-result/network/allocation reservations, and end-to-end foreground deadlines remain unresolved. The shared counter is an admission estimate, not proof of total heap below 512 MiB; dropped preparation owners and interrupted jobs require a stronger resource ledger for that promise. Existing-world sparse/unsaved geometry, restart and persistence paths receive regression coverage, not a new general spatial index. Do not increase the yield or advertise a bounded whole-zone system based on this PR. A dense-city elapsed-time/MSPT/allocation profile and real staged client proof are still gates for the later architecture; unit/GameTest timings alone cannot replace them.

## Validation

Production implementation: 79014863755ff1dcd97e207855b24e4fbe09558b; final head 638c18597de8932728aaf8affa378e2fd2d7d632 adds the corrected promotion regression. Full local build: 1744 unit tests passed, zero failures/errors/skips. Extended GameTest: 45/45; final focused GameTest with LSS 0.20: 9/9. The latter includes cancellation/pool resize/shutdown, nonblocking layout, cross-dimension admission, promoted-task single execution and all five real LSS save/store tests. Final-head CI is green, including Итог, all six GameTest shards, unit shards and both optional LSS versions; records are in checks.json.

The first full build passed; a later repeat lost its Gradle daemon without a test assertion and was rerun successfully with a single-use daemon. First-head CI exposed an old test requiring a promoted task's removed Future to stay unfinished; the corrected regression requires cancellation and exactly one execution while retaining its priority-order proof. Both diagnostic logs are preserved. Fresh independent review is delegated to the next session by stageB-common.md; no review completion, merge, release, server deployment, GPU render proof or 1 Mt acceptance is claimed.

## Follow-up: client-owned buffer experiment (#561)

Artem authorized this distinct probe after the original gate failure. PR https://github.com/zentixua/incoming/pull/561 follows frozen #557 at 638c18597 on branch codex/nuke-lod-buffer. The original Voxy-internal staging gate remains closed; this experiment holds prepared bytes in Incoming and submits them through existing rawIngest only at the unchanged front time. Three GPU runs are now complete; the initial and final results below distinguish feasible client buffering from the still-unclosed strict renderer/stale-replay guarantee.

The declared visible envelope is the near-side 90-degree panorama in the outer 20% of the actual 5 psi ground radius, at optimal airburst height, effects_scale=1, wave_speed=0.25, for 15/100/1000 kt. Synthetic 64 m concrete/glass buildings become low rubble columns; this isolates ingestion/visibility and is not a structural-physics validation. Every column carries the existing 24-section wire representation, including air; four structural sections change. Preparation enumerates bounded row headers and merges their distance order; it never allocates/scans the complete zone before yielding. The prototype uses an Incoming-only development wire protocol and is excluded from release jars.

Accepted OL ideas adopted here: bounded preparation slices, stop/refire and bunched/late/never-returning-read ownership tests, changed/transmitted section metrics, and immutable pressure deadlines. A never-completing read retains its reservation rather than allowing repeated cancellation to create unbounded outstanding work. The six pure CPU regression tests passed, including 10,000 refires with a 64-read limit. No Voxy/LSS fork, mixin, foreign consumer removal or author contact.

### First completed GPU probe — 4 November, result recorded immediately

**Client buffering is feasible as best-effort streaming, but the strict front-time / stale-proof gate is NOT CLOSED.** On the RTX 4060 / installed Voxy 0.2.16 + Roxy + LSS 0.20, the first complete integrated-client run submitted 2,862 / 10,103 / 46,843 columns for the declared 15 / 100 / 1000 kt envelopes. Intact-baseline throughput was 1,255 / 2,773 / 3,065 columns/s. Ruin submission took 3.505 / 6.550 / 14.477 s with maximum lateness 1 / 1 / 10 ticks (0.05 / 0.05 / 0.50 s). No pressure deadline was shifted. The encoded ruin buffer peaked at 4,247,552 bytes, 1,024 columns; this is not total process or Voxy memory.

A three-pixel rendered-depth witness changed 0.84 / 0.68 / 10.18 ticks after the nominal outer central-column deadline. This is a sampled visual proxy, not a per-column renderer acknowledgement: its intact depth differed from the nominal tower point by about 25–29 m due to coarse geometry/projection. The first shaderpack panorama was obscured by volumetric clouds; a clear-sky repeat is required before judging stepping. The test world's simulation-distance value 2 was rejected by vanilla and fell back; the repeat will use supported settings. These first-run diagnostics remain archived, not silently replaced.

Real public LSS `dispatchColumn`, with old timestamp 1, overwrote the Voxy database in both trials (1.179 / 0.570 ms). Reapplying the locally retained ruin restored the database (0.194 / 0.147 ms). Trial 0 deliberately held the stale column 42 ticks for a frame; trial 1 repaired promptly. Neither callback veto nor atomic publication exists; replay after cache eviction, reconnect and arbitrary concurrent repeated overwrites are not solved by this probe. GPU client exited normally; no production code, yield ceiling, user world or server changed. Clear-sky repeat and final evidence/CI links follow below.

### Final client-buffer result — clear panorama and changed-section repeat

**The bounded client buffer is a viable transport/ingestion mechanism for the tested panorama, including 1 Mt. It does not close the stronger MT guarantee of renderer-deadline completion plus permanent stale-replay exclusion.** Recommendation: keep this path as the B3 candidate, use verified-baseline changed-section spans, and retain authoritative data for bounded repair. Do not raise the yield ceiling or promise an atomic/stale-proof whole-zone transition from this probe.

Measured on the laptop RTX 4060, Voxy 0.2.16 through Roxy 0.4.1, LSS 0.20.0, Complementary Unbound r5.9.3 through Iris. Requested window 1280×720 produced **1280×692 actual frames**. Voxy config requested five service threads and permitted Sodium builder threads; startup logs show a dedicated pool of five, then one after Roxy's deferred renderer reload. The measured stack, not an assumed thread count, is the reference. Clear repeats used noon, shader clouds off, vanilla render distance 3 / simulation distance 5, in fresh disposable probe worlds. They retain shader atmospheric haze. No production presentation or player setting changed.

| Optimal airburst | 15 kt | 100 kt | 1000 kt |
|---|---:|---:|---:|
| Model 5 psi ground radius, m | 1605.206 | 3021.112 | 6508.789 |
| Panorama columns (90°, outer 20% radius) | 2,862 | 10,103 | 46,843 |
| Analytic row headers (16 prepared/tick) | 44 | 82 | 176 |
| Peak moving-one-second deadline demand, columns/s | 886 | 1,688 | 3,638 |
| Full-column intact baseline throughput, clear run, columns/s | 1,750 | 3,086 | 3,642 |
| Full-column ruin active submission interval, s | 3.501 | 6.553 | 14.046 |
| Full-column ruin achieved rate, columns/s | 817 | 1,542 | 3,335 |
| Full-column submission lateness, clear run, max ticks | 1 | 1 | 1 |
| Full-column submission lateness, first diagnostic run, max ticks | 1 | 1 | 10 |
| Four-section submission lateness, max ticks | 0 | 0 | 0 |
| Four-section database witnesses, max ticks late (33 samples) | 1 | 1 | 1 |
| Four-section rendered-depth proxy, ticks after nominal deadline | 0.54 | 0.56 | 0.68 |
| Full-column decode + enqueue mean, ms/column (clear run) | 0.0890 | 0.0878 | 0.0951 |
| Four-section decode + enqueue mean, ms/column | 0.0264 | 0.0214 | 0.0251 |
| Four-section decode + enqueue p95, ms/column | 0.0368 | 0.0285 | 0.0321 |

One tick is 50 ms at the measured game pace. A zero-tick submission is within the due tick, not zero wall-clock latency. Rates for timed ruins are **demand-paced achieved rates**, not saturated capacity. The intact pass is unpaced but changes different content and includes startup. Across runs, the 1 Mt intact pass ranged from 1,873 to 3,642 columns/s. Two other CPU-only laptop jobs were active during the four-section run (material-profile work and a review build); the GPU was exclusively leased. These are shared-laptop observations, not an isolated CPU capacity benchmark or a multi-client network guarantee.

The current column wire format already represents a contiguous vertical section span. The fixture's baseline was fully installed first; only sections 4–7 changed, including the three now-empty sections that must erase the tower. Sending those four sections instead of all 24 reduced 1 Mt transmitted sections from **1,124,232 to 187,372**, while preserving all four changed sections. Mean decode/enqueue cost fell about **3.8×** at 1 Mt. The fixture's encoded column changed from 4,148 to 2,065 bytes; the peak retained ruin buffer changed from **4,247,552 to 2,114,560 bytes** at 1,024 columns. This saving requires a known baseline and an exact changed-section set; sending only non-air blocks or omitting newly empty sections would be wrong. Unknown/missing baselines require a complete column. No packet format or production protocol change was needed for the experiment.

**Visible stepping.** Inspected the actual before/front/mid-front/after frames at each yield. The radial boundary advances in discrete chunk/LOD geometry steps; it is not an atomic panorama swap. In the first 1 Mt run, submission fell behind by up to 0.50 s and the central depth patch changed about 0.51 s after its nominal deadline. In the clear full-column repeat the central proxies were 0.028 / 0.084 / 0.033 s late; in the four-section run, 0.027 / 0.028 / 0.034 s. These are local sampled proxies: the initial depth differs from the intended tower point by 25–29 m because of projection/coarse geometry, and the depth readback itself costs approximately 2.2–2.8 ms on the sampled frame. Sparse panorama screenshots do not establish a maximum temporal step for every visible mesh. The probe therefore demonstrates the transition and its sampled delay, not a general no-popping or renderer-ready guarantee. Atmospheric haze and deliberately synthetic full-sky lighting also make these diagnostic frames unsuitable as a nuke-look approval.

The four-section timed phases averaged 8.40 / 8.38 / 8.42 ms per frame, with recorded maxima 24.69 / 12.57 / 24.91 ms. The client was capped at 120 FPS; these include instrumentation/captures and are neither GPU-layer timing nor a production cost added by the buffer. All renderer readback and capture code is devtest-only. The prototype adds no normal-play frame work.

**Actual stale replay.** Every one of six public LSS replay trials across three runs overwrote the database despite timestamp 1. Observed overwrite latency was 0.431–1.265 ms; retained-data reapplication restored the witness in 0.127–2.149 ms. The deliberately delayed repair visibly reinstated the tower. In each clear run's prompt-repair sequence, 90 captured frames did not show that stale tower: a fixed tower-strip contrast was at most 0.280/255, against 4.841/255 and 5.220/255 in the deliberately stale frames. This is a bounded observation after recording started, not proof that no intervening frame or future replay can show it. The public callback returns void and cannot suppress the Voxy consumer. Polling a zero queue does not fence running tasks; the probe verifies repair by rereading data. Unload/reload, cache eviction, reconnect, packet loss, overlapping strikes and arbitrary repeated stale deliveries still need their own tests and an ownership/durability policy.

**Scope and next gates.** This envelope is about 9% of the heavy-zone disk. The integrated-server wire uses an explicit real-codec round trip but is not a WAN measurement. Templates are already prepared; real city planning, snapshot/lighting cost, sparse saved-world indexing, 512 MiB end-to-end server reservations, late player motion and multiplayer fairness are unproved. The 48 MiB encoded-data admission limit excludes decoded section objects, Voxy storage and the Minecraft heap. The bounded analytic fixture cursor is not a production world index. Continue with the known-baseline client buffer only behind these limits; close the remote-client, late/never-read, stop/refire, eviction/restart and repeated-LSS-race gates before claiming the broader MT guarantee. Keep the original no-fork/no-mixin boundaries and the pressure clock unchanged.

**OL ideas actually adopted:** stop/refire plus delayed/missing/bunched-read ledger regressions; reservations retained until a cancelled running/read operation really completes; staged preparation (maximum 16 row headers per tick, no whole-zone enumeration before yielding); costs reported by changed/transmitted sections; and deadlines fixed by `Detonation`, never stretched to hide queue delay. These are workflow/model ideas, not copied Orbital Laser source.

**Validation and evidence:** local full build: 1,750 unit tests, zero failures/errors/skips; touched GameTest: 3/3; final development-source compilation and three GPU runs exited successfully. Shaderpack compatibility warnings, the unavailable headless narrator, and Voxy request/node warnings remain in raw logs; no probe failure or Incoming ingestion exception occurred. The first-run invalid simulation-distance setting is preserved and corrected in both clear runs. All raw frames, measurement scripts, parsed data and logs are on the orphan [look-gate/mt-lod-buffer](https://github.com/zentixua/incoming/tree/look-gate/mt-lod-buffer) branch, with a SHA-256 manifest. PR [#561](https://github.com/zentixua/incoming/pull/561) follows #557, is a development probe, and remains a draft for fresh-session review. No merge, release, server operation or yield change is part of this result.

Final PR head `c716837f7fe786251dc3298e63eee5c6a3a5bad0`: **green Итог**, all required build/unit/GameTest checks passed. GPU lease released after all three clients exited normally. The original branch `codex/nuke-megaton` remains at `638c18597`; this front did not push it or merge either PR.

Evidence snapshot: `4e9340221d3c08624dc3186d44794340cea51ff2` on orphan `look-gate/mt-lod-buffer`, 104 hash-verified files; selected PNGs were losslessly recompressed with byte-for-byte decoded-pixel assertions, and the two clear replay videos preserve all 90 RGB frames each.

### MT-B3 production continuation (2026-11-04, in progress; PR #564)

The selected #561 path is now being moved into production on `codex/nuke-horizon`, based on main `8e3e2dc28`.
This is not a new gate pass. The first production-buffer run is in
`mod/run/mt-b3/client-20261104-022302` in `wt-MT`; evidence is not yet archived.
The same prepared copy now carries its exact chunk-front deadline to the client, and its section/light delta
is encoded against the pre-plan copy. LSS observations schedule local retained-section repair. There is no
LSS veto or Voxy renderer completion fence: a successful repair cannot by itself establish that no intact
frame appeared. The sequence begins before stale replay in this run.

First partial results: 15 kt panorama 2,862 columns, submission lateness max 0 ticks; 100 kt 10,103 columns,
max 2 ticks (mean 0.1252). The central render-depth witnesses changed at +0.68 ticks for each yield.
These are synthetic prepared columns through the production client buffer, not measurements of whole-city
server planning or of the ordinary server transport window. The 1000 kt phase and repair sequence are still
running. Reconnect/late-join/overlapping-plan correctness and the complete global memory gate remain open.

RLOD fixes are included in the carried probe: Voxy witness reads run outside the monitor used by the server,
and elapsed submission durations are named `elapsedNs`. Production reset returns an asynchronous Incoming
submission barrier; it is explicitly not advertised as a Voxy internal-worker or renderer barrier.

First production run completed: all three envelopes and both repair convergence checks finished, but the
strict gate FAILS on this version. 1000 kt / 46,843 columns: submission lateness mean 1.0161 ticks, max 12;
render-depth witness +0.6401 ticks; peak retained client bytes reported 27,410,307. Intact baseline throughput
was 2,554 columns/s (18.3338 elapsed seconds). The replay sequence starts at tick 4506, stale LSS dispatch at
4508; `replay_016_tick4509.png` shows a small reinstated central tower/vertical trace absent from
`replay_000_tick4506.png`. Local repair converges, but a transient is visible. This directly closes the old
probe's recording blind spot; it is not acceptable evidence for “the intact city never comes back”.

Next correction under test: wake the serial client publisher on receipt/repair and on background-service
availability, with a bounded section batch, rather than waiting until another client tick. Deadlines still
come only from the event clock. No Voxy completion fence is inferred from its queue count.

Production transport diagnosis (second run, `client-20261104-023441`, 15 kt baseline): 2,862 intact
columns took 2,871 game ticks / 143.406 elapsed submission seconds, **19.96 columns/s**. This run uses the
ordinary `VoxyLods` acknowledged server queue, unlike the faster synthetic producer of #561 and the first
B3 run. The source is the per-tick writer: after dispatching one asynchronous Netty write, it does not
continue the current batch until the next server tick. This is a measured bottleneck, not a Voxy ingestion
limit. The next implementation selects a bounded immutable batch on the server and advances its single
outstanding write from Netty completion callbacks, retaining byte headroom and the client acknowledgement.

MT-B3 ordinary-transport result at `34ac83d85` (`client-20261104-025954`): the writer callback and 2 ms
completion polling remove the 20-column/s bottleneck. Full-column baseline rates are 304.3 / 477.2 /
460.3 columns/s at 15 / 100 / 1000 kt (256 KiB safety window, one acknowledged batch/tick). Four-section
ruin submission lateness is 0 / 0 / **282 ticks** maximum; 1 Mt mean 84.6584 ticks, rendered central
witness +277.1802 ticks. The 24 MiB held queue cannot prefill enough work for the front's 3,638 columns/s
peak versus at most 1,280 columns/s transport. This is a FAIL at 1 Mt, not an ingestion-capacity result.
Next bounded correction: 96 MiB queued client data (shared across all its strikes), 24 MiB repair cache;
separate the receive-rate sample from time waiting for physical deadlines. The global 512 MiB target is
the server working-reservation ledger; client heap/storage/GPU memory remain separate measurements.

Both single-column stale replays at this head visibly converged, and both exposed a stale database
sample (at 1.7845 / 0.9902 ms after dispatch). Across 180 frames recorded before/after both dispatches,
the fixed central-strip contrast remained <=0.2804/255: no reinstated tower was observed in those frames.
This does not establish an atomic exclusion guarantee. Next replay test includes a realistic bunched
64-column old delivery, not just one column. The 2,000 ms observation windows are explicitly not repair
latencies. LSS bytecode confirms void consumer dispatch with no veto; Voxy rawIngest appends to its
shared worker queue and provides no per-column renderer completion fence.

### MT-B3 final candidate gate — FAIL (11f4c67f7, 2026-11-04)

The ordinary-transport / 96 MiB shared client-queue run (`client-20261104-031908`) completed all three
envelopes and three stale replay trials. Maximum submission lateness was **1 / 0 / 13 ticks** at
15 / 100 / 1000 kt; 1 Mt mean 0.9367 ticks, active submission interval 14.056841 s. Peak reported
client encoded ownership was 102,728,892 bytes (queue plus repair cache), within the separate
96 + 24 MiB client payload bounds; this is not a complete heap or global server memory proof.
The pressure clock was not delayed.

The decisive failure is visible stale replay, not just a database race: after public LSS dispatch of
64 intact columns (dispatch completion logged at tick 6517), `replay_2_020_tick6518.png` visibly reinstates several central towers,
absent from `replay_2_000_tick6514.png`. Other frames around 6518–6520 show the same return before repair.
The central strip contrast rises to 4.7576/255 against the <=0.2804 background in the earlier single-column
trial. The sampled database was stale from +79.4844 through +165.7828 ms after dispatch; all 64 positions
converged by the end of the two-second observation. Those endpoints are samples, not exact transition
times. The 90-frame sequence starts BEFORE dispatch and preserves the transient; convergence is not
a substitute for the requested “intact city never comes back”. The repair cache was explicitly primed
for these 64 positions, so this is a failure even without eviction or reconnect.

**Decision:** keep PR #564 as an unmerged, non-ready candidate and retain the current yield ceiling.
The client buffer improves delivery, but does not close B3. Existing LSS consumers cannot veto old
data and Incoming cannot fence Voxy's independent ingestion/meshing/render publication. A larger
buffer or faster callback does not provide that missing ownership. No fork/mixin/author contact was used.
The §33.2 fallback applies: preserve the useful B1/B2 work, do not call eventual disk saves a front-time
solution, and do not advance B5 on these measurements.

Additional open gates are explicit: actual plan-producer prefill for a real city, immutable revisions
that distinguish a stale replay from legitimate newer edits, chunk reload/reconnect/late join,
chronological overlapping strikes, and complete server 512 MiB admission including every retained
owner. The present local section cache is session-local, can evict, and is not that durability protocol.
The test's 90-degree outer-20%-radius envelopes cover about 9% of the heavy-zone disk, with prepared
synthetic columns and an integrated connection; they are not a WAN or whole-city planning benchmark.

Validation at this point: full build passed; all 17 local touched-area GameTests passed; `tools/pr_ci.sh
564` reported green Итог on 11f4c67f7. Fresh main was fetched and was already an ancestor (8e3e2dc28);
#560 was still open. Scene comparison and the immutable evidence archive are being finalized below.

The final normal scene bench (`mt-b3-final`, `none`, 100 strikes, one main/candidate/main pair) passed.
Worst gated GPU p50 was +12.12% (4.367 versus 3.895 ms in the inside view), within the 25% gate; all
gated render-thread CPU p95 comparisons passed. The two-minute quiet wait expired during warm-up
with another CPU-only laptop job active; per-window host data are retained. This standard bench has
no Voxy panorama and does not change the functional FAIL above. All four bench clients exited normally,
and the GPU lease was released.

Source integration limit, beyond the synthetic results: production FrontLods still publishes Voxy
copies on its existing DH lead schedule. It does not yet implement an independently admitted, complete
player-visible prefill transaction. The 96 MiB prefilled fixture therefore cannot certify real strikes.
The branch is explicitly a non-ready candidate with useful lifecycle/transport work, not finished B3.

### MT-B3 B2 integration, 4 November 2026

Main `ab2741fa1` (#560) merged into `codex/nuke-horizon` at `54b0198af`, without rebase. Copies now consume B2 RuinPlan. Code inspection confirms `applyToCopy` carries failure cells; live `apply` additionally releases pieces to RuinMatter, which owns later support/fall/settlement. Detached copies do not execute those subsequent stages: full/LOD stage agreement remains open. Existing measured publication FAIL at `11f4c67f7` remains valid evidence of that tested candidate, not a claimed merged-head city test.

Final B3 handoff validation on `54b0198af`: 1782 unit tests, all 17 selected GameTests, green Итог (run 41763135923), final main/candidate/main 100-strike normal scene bench PASS (all six gated comparisons). Final baseline ab2741fa1 includes B2. These checks do not close the recorded LOD publication FAIL. Evidence: https://github.com/zentixua/incoming/tree/look-gate/mt-b3 . #564 stays draft/unmerged; #561 stays a separate dev probe. No yield increase.
