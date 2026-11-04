# RMT-L2 — independent review of B3 / #564

Reviewed 2026-11-04. **Changes requested: three new correctness defects, plus one unresolved acceptance blocker that predates this PR.** The last commit was also inspected separately.

## Scope and independence

- Checkout: `/mnt/data/projects/incoming/mod/run/wt-RMT-L2`, clean detached HEAD `f4c5c276612834ed9c4feab556f210fe689e9111`.
- Main comparison: `659af24a492c277f33a3dd92827542ad49ebbc1c..f4c5c276612834ed9c4feab556f210fe689e9111`; separate comparison: `HEAD^..HEAD`.
- Read current TEAM, BOARD, context, newest live entries, repository AGENTS, applicable client/nuclear/world/compat/network/test rules and shared memory index. This review session is the separately launched independent session, not an implementation continuation. MT-L2 remains the implementation owner.
- Did not read the PR description, author reports, MT-L2-next.md, inbox contents, author conversation, evidence analysis/verdict files or old MT implementation handoff. The present review brief supplies its pin and scope; reading the author's handoff would violate that scope.
- Checked the worktree and processes: MT-L2's separate recovery build was active in wt-MT; it was not touched. No review GPU, server, Gradle, build or other heavy jobs were started. No source edits, commit, push, merge or public comment. `git diff --check` passed.
- Findings below are source-derived execution paths unless explicitly marked as earlier runtime evidence. No new runtime reproduction is claimed.

## New blocking findings

### R1 — P1: a dimension change can permanently deadlock acknowledged delivery

Primary location: `mod/src/main/java/ua/zentix/incoming/client/ClientHorizon.java:53–61` (especially line 54). Supporting paths: `ClientHorizon.java:36–40,51,75–86,128–135`; `client/ClientLodBatches.java:68–80`; `client/IncomingClient.java:242,311–329`; `compat/VoxyLods.java:114–116,341–350`.

Execution:

1. In dimension A the horizon buffer reaches `full()` while holding future front columns. The finish marker arrives and the client withholds its ACK because `ClientHorizon.full()` is true.
2. The player changes to dimension B. There is no horizon reset on client level unload/clone. Login/logout reset does not run for an ordinary dimension change; `ClientNuclear.respawned` only adjusts death effects.
3. `VoxyIngest.tick()` calls `ClientHorizon.tick(B, now)`, which returns immediately because its saved world is A. A's event-clock snapshots stop advancing. The scheduled worker still sees `world == A`, repeatedly defers the future head, and retains A and the buffer.
4. The old batch's ACK still waits for that full buffer. The server intentionally preserves the active batch across dimension changes and will not send a new B batch before that ACK. Therefore no B `offer()` can arrive to trigger the reset that the implementation relies on. Returning to a newly created A level does not solve the identity mismatch either. The connection's Incoming LOD stream stays wedged until a logout/reset.

This is new in the PR's client-held deadline/backpressure scheme, already present in HEAD's parent. Fix session retirement independently of a subsequent terrain packet, without blocking the game thread on foreign work. Regression: fill a small injected horizon capacity with future A columns, finish a batch, switch to B with no further terrain receipt, and require old queued ownership to retire and the pending ACK/new B delivery to progress. The existing server-only dimension batch-ID test does not exercise this cycle.

### R2 — P1: sparse section updates assume a client baseline that was never established

Primary location: `mod/src/main/java/ua/zentix/incoming/compat/VoxyLods.java:285–290`. Supporting paths: `nuclear/world/FrontLods.java:213–219,305`; `compat/LodSections.java:101–108,142`; `client/VoxyIngest.java:122–127`.

`FrontLods` snapshots the server's disk sections before applying a plan. The existence of this server snapshot selects `sendChanged`, and `VoxyLods` unconditionally encodes the difference for every selected player. There is no per-viewer baseline revision, fingerprint, acknowledgement or client presence check. `UNCHANGED` means the receiver does not ingest that section at all.

Concrete ordinary-edit path: a Voxy player previously saw a stone lower floor, moves away, and another player rebuilds that floor with diamond blocks and saves it. The later nuclear plan removes a roof in an upper section but leaves the rebuilt lower section unchanged. Server before/after both contain the diamond floor, so the sparse packet omits it. This client retains its obsolete stone floor indefinitely unless another producer refreshes it; a client with no baseline retains a hole instead. Before this PR a full column from this path refreshed both sections. A server disk baseline is not proof of the client's baseline. The optional LSS path does not make this valid for Voxy-only clients, and a later LSS arrival can itself overwrite the changed upper section (R4).

Send a full column for an unknown/mismatched recipient baseline, or establish a versioned per-viewer baseline and define mismatch recovery. A test must apply the delta to a deliberately different or absent client baseline and assert the complete resulting column, including unchanged visible sections. The new codec test only checks which sections were emitted.

### R3 — P1: one event's future head blocks another event whose visible wave has already arrived

Primary location: `mod/src/main/java/ua/zentix/incoming/client/ClientHorizon.java:83–87`. Supporting paths: `client/lod/TimedLodBuffer.java:20–23,52–54`; `client/nuclear/ClientNuclear.java:68–72,100–103,381–383`.

The priority queue orders absolute server `notBefore` values, but eligibility uses a different live client clock for each detonation. These clocks need not have equal offsets: live detonations start with `before = 0`, so two detonation packets handled in the same client tick after a stall start at equal ages despite different server event times.

Example: A has `gameTime=1000`, B has `gameTime=1050`; both packets were handled together. At client age 380, A's horizon clock is 1380 and B's is 1430. Held columns have deadlines A=1400 and B=1420. B is due on its own visible-wave clock, A is not. The queue takes A first because 1400 < 1420, defers it and breaks. Every retry repeats this until A advances another 20 ticks. B's distant ruins consequently trail its own wave by at least one second despite being fully received and otherwise ready. This can affect overlapping strikes without any foreign LOD writer.

Select eligible work across event timelines, e.g. bounded per-event queue heads; never advance a pressure clock to unblock the queue. Regression: inject exactly the clocks/deadlines above, require B to submit and A to remain held. A single-event `take(now)` test cannot catch this. New in this PR, also present in HEAD's parent.

## Unresolved player-acceptance blocker / preexisting behavior

### R4 — P1 acceptance: an older foreign column can still publish intact geometry after ruins, with no guaranteed repair

Locations in HEAD: `client/VoxyIngest.java:120–127,148–164`; `client/lod/LodRefreshHints.java:25–43,64–65`; `client/ClientHorizon.java:80,94–101`. Existing server repair path: `nuclear/world/FarLods.java:226–249,522–568,622–629`.

LSS independently submits its original sections to Voxy. Incoming's subscriber only records a hint; it cannot replace, veto or version that publication. Voxy `rawIngest` returns after enqueueing; a worker that already acquired an older job is not included in `getTaskCount()`. Such a worker can finish after a newer Incoming submission and restore intact geometry. Serializing Incoming's own worker does not serialize that foreign work or renderer publication.

Even eventual repair is not guaranteed. HEAD evicts recent coordinates beyond 65,536 and pending hints beyond 4,096; reconnect clears them. A stale callback before the first `remember()` is also unrecognized. A recognized hint is consumed once, and `FarLods.refresh` returns without a replacement when no arrived plan/eligible trace/darkness remains; it does not always resend current saved terrain after an already queued old LSS delivery. Later material settlement and ordinary edits make replaying a historical locally retained image incorrect, so restoring that cache is not a solution.

The independent foreign-writer race and late/reconnect limitation already exist in the base. The last commit deliberately replaces the parent's historical-image repair with bounded server hints, which avoids overwriting later edits but does not meet the requested “old intact geometry never returns / reconnect and overlaps correct” acceptance. This is a goal blocker, not a claim that removing historical replay was the wrong choice.

Earlier raw evidence corroborates the foreign publication, rather than proving HEAD fixed it:

- Evidence commit `93ef1d4d71ee2c10128dc4234721ca30a264d5a6`, run `runs/client-20261104-031908`; native copies are under `wt-MT/mod/run/mt-b3/client-20261104-031908`.
- Inspected native `replay_before.png`, `replay_2_020_tick6518.png`, and `front_1000_089_tick6069.png`. The replay frame at tick 6518 contains the returned upright central geometry absent before dispatch at tick 6517.
- Raw `lod-probe/events.tsv`: replay round 2 dispatches 64 columns; database witnesses record 40 stale samples from 79.4844 to 165.7828 ms after dispatch. A later successful witness does not erase this interval.
- Raw 1 Mt panorama has 46,843 columns; submission lateness max 13 ticks, and its sampled visible witness is 0.98 ticks late. These are synthetic panorama measurements, not real-city preparation or total-memory verification.
- The scenario's synthetic production transport uses detonation `-1` (`LodBufferScenario.java:483–484`) and does not create matching `ClientNuclear.Active` events. It therefore exercises the world-clock fallback, not R3's per-detonation clocks. These earlier frames/runs are not a runtime validation of f4c5c2766.

## Supported architecture alternatives: what the source establishes

### Exact LSS source and current API

Inspected the specified upstream tree at `9af90cfd09e2b62ebe71a2a434840c78981be841`, including every file in `xplat/.../api`, the client dispatch/processing path, and `compat/VoxyCompat`. Also fetched current upstream main metadata (`45100da848135ac248cc4c70c32c6d98c7ef7b8f`) and the current API file. Its SHA-256 equals the pinned API file: `a6f60f8bdbcac3d4ab24a82be98faf9420c32c5332bb4cb4fdc6e48d615ea3bf`.

Source proof, not merely search absence:

- [VoxelColumnConsumer](https://github.com/VoX/lod-server-support/blob/9af90cfd09e2b62ebe71a2a434840c78981be841/xplat/src/main/java/dev/vox/lss/api/VoxelColumnConsumer.java) returns `void`; no replacement result or veto token is passed.
- [LSSApi](https://github.com/VoX/lod-server-support/blob/9af90cfd09e2b62ebe71a2a434840c78981be841/xplat/src/main/java/dev/vox/lss/api/LSSApi.java), lines 230–248, unconditionally calls every registered consumer and isolates even throwing consumers. Throwing/reporting failure schedules another delivery to all consumers, not cancellation of Voxy's delivery.
- `IngestFailureHandle.deferAcceptance()` holds LSS receipt acceptance for that delivery; `isActive()` is a delivery/session check. Neither cancels already queued Voxy work nor prevents another consumer from publishing. `columnTimestamp` is data for the consumer, not a Voxy commit-version argument.
- [VoxyCompat](https://github.com/VoX/lod-server-support/blob/9af90cfd09e2b62ebe71a2a434840c78981be841/xplat/src/main/java/dev/vox/lss/compat/VoxyCompat.java), lines 185–206, immediately invokes the same versionless `rawIngest` for each section. Register/remove-consumer APIs manage the caller's subscription; they are not a supported replacement for LSS's own bridge.

### Roxy and supported Voxy

[Roxy's current requirements](https://github.com/RasaNovum/Roxy#requirements) still identify Voxy 0.2.16 as its supported pairing. Inspected the exact runtime ingest bytecode in `wt-MT/mod/run/mt-b3/voxy-ingest-bytecode.txt`, corresponding 0.2.16 decompiled surrounding code under `mod/run/vxc2-scratch/voxy216`, and Roxy integration sources. Current Roxy head fetched for this review: `3531a506f15f7f20f1ab72f8e987099cad17a57f`.

- Voxy `rawIngest0` appends an unversioned `IngestSection` to a concurrent deque; `processJob` pops it, converts it, then calls `WorldUpdater.insertUpdate`. There is no expected-base/authority revision or deadline in those signatures. `getTaskCount` calls `Service.numJobs`, which is semaphore available permits; a running job acquired its permit before executing. Zero is not a commit/render fence. The public WorldEngine overload can improve explicit engine ownership, but does not order foreign revisions.
- Current [RoxyVoxyIngestSnapshotMixin](https://github.com/RasaNovum/Roxy/blob/3531a506f15f7f20f1ab72f8e987099cad17a57f/versions/1.21.1/src/main/java/net/rasanovum/roxy/mixin/RoxyVoxyIngestSnapshotMixin.java) copies section data. It exposes no caller registration/veto contract.
- Inspected current [RoxyVoxyRenderPatch](https://github.com/RasaNovum/Roxy/blob/3531a506f15f7f20f1ab72f8e987099cad17a57f/versions/1.21.1/src/main/java/net/rasanovum/roxy/patch/RoxyVoxyRenderPatch.java), including `recordRenderTaskCreation`, `acceptIfCurrent`, and world/task epochs. These reject stale renderer/task results. They do not know whether the latest database update came from an older authoritative terrain revision. A new mesh of stale database contents is still a current render task.
- `RoxyVoxyWorkDrain` schedules render continuations; its counters are not an externally usable column publication barrier.

I found **no existing supported replacement/veto or versioned publication API for this supported Voxy pairing**. The LSS dispatch conclusion is proved by the complete public API and its actual fan-out. The broader Roxy/Voxy conclusion is limited to the inspected source/runtime and current candidate hooks; it is not a proof that no future library/addon could offer such a contract.

A clean route for the full acceptance needs an upstream-supported commit contract: session identity and monotonic authoritative column/section revision, a bounded prepared replacement with a not-before event clock, and rejection of older in-flight work at actual database publication (plus compatible mesh invalidation). All writers, including ordinary vanilla/LSS updates, must respect it; current edits and material settlement must supersede previews. This can be supplied by a sanctioned LSS/Voxy/Roxy extension and then consumed by optional Incoming compat. No contacting authors or publishing such a change was performed in this read-only review.

Using mutable `VoxelColumnData` arrays according to incidental consumer registration order, taking over LSS's consumer, manipulating private service/renderer locks, repeating repairs, or directly writing the database without an ordering contract does not supply this guarantee. DH's own overwrite API serves DH clients, not the selected Voxy renderer. Early destruction or hiding intact terrain changes the requested behavior. None is recommended.

**R1–R3 are fixable inside Incoming; they are not blocked by a missing upstream API.** R4 is the narrowly identified contract gap for the stronger never-return guarantee.

## Last commit versus its parent

`f4c5c2766` removes the parent's 24 MiB retained terrain repair images and `LodSections.merge`, adds bounded coordinate hints, retains hints across another successful submission, and checks hint session ownership. This is a sound direction for later player edits/material settlement: old snapshots must not be replayed as current truth. The new tests establish finite hint counts and explicit-reset ownership in isolation.

They do not establish automatic dimension retirement (R1), recipient baseline validity (R2), independent event-clock eligibility (R3), or a foreign commit fence (R4). R1–R3 are inherited from the parent within this PR. R4's race predates the PR; bounded evictions and server-only recovery in this final commit must be assessed as best effort, not a fixed never-return guarantee. No separate last-commit-only crash defect was established.

## Optional / narrower issues and validation limits

- `IncomingClient.tick` snapshots horizon clocks at line 242 before `ClientNuclear.tick` increments age at line 270 / `ClientNuclear.java:337`. Rendering then sees the incremented age. Even an already received single-event column can start submission one client tick after that visible clock reaches its deadline. Align the sampled clock phase and add a controlled-clock test; the asynchronous renderer adds further latency independently.
- Queue/hint bounds are useful: 96 MiB of charged encoded horizon ownership, 65,536 tickets, 65,536 recent coordinates, 4,096 pending hints; taken work remains charged across reset. This is not a measured total client heap/GPU bound. Codec scratch, the Voxy service/active workers, renderer/database and other mods remain outside that figure. No new unbounded section-image cache was found in HEAD.
- The production scenario preloads known synthetic intact columns, supplies synthetic erased upper sections, and uses the world-clock fallback. It does not test real city plans, ordinary edits/material settlement during a held preview, reconnect, the automatic client-level transition, or two differently offset live detonation clocks. Earlier raw evidence cannot close those cases on the final head.

## Delivery

Read-only review complete. Implementation owner should resolve R1–R3 and settle the explicitly scoped R4 acceptance/contract gap before treating B3 as delivered. No production action is authorized or performed by this review.
