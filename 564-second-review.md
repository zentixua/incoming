# RMT-L2-FINAL — independent final static review

Reviewed 2026-11-04, pinned HEAD `b97580117913aa285e7145d632319089733f86a9`.

**Changes requested: the specific R1–R3 failures are corrected, but one additional serious session-ownership race remains (R5 below).** R4 remains a separate product-acceptance/publication-contract question; these fixes do not establish a never-return guarantee.

## Scope and independence

- Clean detached worktree: `/mnt/data/projects/incoming/mod/run/wt-RMT-L2-FINAL`.
- Reviewed the complete diff `1731b35ab..b97580117` (11 files) and surrounding production receipt, tick, ACK, copy/encoding, ingestion and refresh paths. MT-L2 remains the implementation owner; no implementation ownership was transferred.
- Read current TEAM, BOARD, context, newest live entries, AGENTS, applicable client/nuclear/world/compat/network/test rules and relevant canonical memory. The current review brief is the review handoff. Read the explicitly permitted `RMT-L2-report.md` to identify R1–R4, then checked the final code independently.
- Did not read the PR description, author conversation, implementation reports, inbox contents or MT-L2-next. The inbox write below is append-only. This is the separately launched review session, not an implementation continuation.
- Checked HEAD, worktree cleanliness and live processes. Other owners' agents and a client were present; none was operated or interrupted. Started no build, test, Gradle, GPU, server, CI watcher or heavy job, as required by the OL2 quiet window. There are no owned jobs to await.
- `git diff --check 1731b35ab..b97580117` passed. All behavior judgments below are static source reasoning, not a claim of runtime reproduction or a passing test run. No production files, commits, push, public comments or merge.

## Serious finding

### R5 — P2 correctness: an old worker can claim a new session's packet and ingest it into the old dimension

Primary location: `mod/src/main/java/ua/zentix/incoming/client/ClientHorizon.java:88–96`.

Supporting locations: `ClientHorizon.java:36–45,53–59,105–106,121–139`; `client/lod/TimedLodBuffer.java:63–74,87–94`; `client/VoxyIngest.java:104–125`; `client/ClientLodBatches.java:68–80`.

The loop's epoch check and `QUEUE.take(clock)` are separate synchronized operations. `take` has no expected-epoch parameter, and the returned ticket's epoch is never compared with the worker's captured epoch before the external call. The following valid interleaving crosses the newly repaired dimension-retirement path:

1. The worker for level A/epoch E passes the loop condition at line 88, then is descheduled before line 89. A may contain only future columns; the guard does not depend on one being eligible.
2. The game thread ticks level B. The new path calls `reset`, removes A's queued ownership, increments the epoch and sets the current level to B. Production does not wait for the returned worker barrier.
3. The network poll sees that the horizon is no longer full. After any actual Voxy backlog drains, the old batch ACK can progress and the server can send a B batch. Its network handler offers an immediately eligible B column (ordinary updates use `Long.MIN_VALUE`). That ticket belongs to E+1. B's drain is queued behind the still-running A drain on the serial executor.
4. The A worker resumes at `QUEUE.take(clock)`. It takes B's ticket using A's captured clock snapshot. It then calls `VoxyIngest.ingest(A, packetB)`.
5. `ingest` resolves the Voxy world from the supplied level A, not from the packet dimension. `finish` removes B's ticket. Both `remember` and `requestFresh` reject the old worker's level/epoch, so they cannot restore the lost B delivery.

This can write B terrain into A's LOD engine and omit it from B. If the old engine is unavailable, the packet is still consumed without requesting B again. This affects LOD caches, not authoritative Minecraft world blocks. It requires no foreign LSS writer: it is an Incoming ownership error.

The exact supported Voxy 0.2.16 local surrounding source confirms the target matters: `WorldIdentifier.of(level)` obtains that level's identifier; `rawIngest(WorldIdentifier, ...)` resolves/creates that identifier's engine before queueing the sections. Inspected local references: `/mnt/data/projects/incoming/mod/run/vxc2-scratch/voxy216/me/cortex/voxy/commonImpl/WorldIdentifier.java` and `.../common/world/service/VoxelIngestService.java`.

**Provenance:** the split guard/claim pattern already exists at `1731b35ab`; this is an additional defect in the B3 pipeline, not a claim that this final diff introduced the pattern. The new automatic reset fixes R1's deadlock but does not make the worker's claim session-safe. The fresh review found this remaining serious error while checking that repair.

**Narrow correction:** make dequeue conditional on the expected epoch atomically under the buffer lock, or otherwise ensure a worker never consumes a ticket from a different epoch. Keep Voxy calls outside tick-facing locks. A second free-standing `QUEUE.epoch()` check alone has the same race. The distinction is between an already taken A packet completing after reset (covered by the existing asynchronous barrier semantics) and an old worker stealing a newly offered B packet (incorrect).

**Regression needed:** deterministically hold A between its epoch guard and dequeue, run B's production retirement, allow ACK/refill without joining the worker barrier, offer an immediately due B packet, then release A. Assert B is never submitted with A, remains owned until B's worker handles it, is submitted exactly once with B, and accounting drains correctly. This is a proposed check only; no test was added or run in this review.

## Assessment of the requested serious fixes

### R1 — the full-future-queue dimension deadlock is fixed

The actual call chain is registered `IncomingClient.tick(Post)` → `VoxyIngest.tick` → `ClientHorizon.tick`. Both paused and normal branches reach it when a level exists. The normal branch now samples after `ClientNuclear.tick` (`IncomingClient.java:242–244,272–273`); `VoxyIngest.java:158–164` invokes the horizon independently of terrain receipt.

For a different level, `ClientHorizon.tick:55–59` now stops the old queue and establishes B even with no B packet. `TimedLodBuffer.stop` releases queued future tickets while correctly retaining any already taken work until `finish`. With only future held work, this frees capacity immediately. The existing event-loop ACK poll checks current `full()` and backlog on every retry; it does not require another packet or game-thread blocking. On the server, dimension changes clear the waiting column queue but preserve the connection's active batch/ID (`VoxyLods.java:343–353`), and `batchReceived:371–377` accepts its eventual ACK. Thus the original circular dependency is removed.

The new `ClientHorizonTest` genuinely uses the production horizon, charges its production byte cap without allocating 96 MiB, withholds a `ClientLodBatches` ACK, ticks another level without terrain receipt, and checks that capacity and ACK eligibility return. Its limitations matter: it calls `acknowledge` directly rather than running the registered channel poll/server flow, and it joins `barrier` at line 48 before offering B at line 52. It therefore covers the reported all-future deadlock but deliberately serializes away R5. No runtime result for this test is claimed.

### R2 — absent/obsolete recipient baselines now receive a complete column

Verified production path: `FrontLods.send:299` → `DhUpdates.sendHeld:241–258` → `VoxyLods.send:264–290` → `columnData:295` → `LodSections.ofCopy:73–83`.

The server before-image and the `sendChanged` selection have been removed from that path. Every selected recipient receives all sections, including empty section erasures and computed light. The loaded-chunk branch still uses the already full `LodSections.ofLoaded`. No production call to `LodSections.difference` remains; only the codec utility declaration remains under main source.

The new `LodSectionsTest.aPreparedColumnReplacesAnAbsentOrObsoleteRecipientBaseline` calls the same `columnData` encoder, applies decoded sections to both absent and deliberately obsolete terrain, and checks the diamond lower floor plus erased upper roof. It is materially stronger than checking emitted-section count. Static inspection supports closure of R2. It does not establish cross-writer publication ordering (R4).

### R3 — one future event head no longer blocks another event's due head

Tickets now carry the packet's detonation identity. `TimedLodBuffer.take(clock):63–74` compares one head from every nonempty timeline with that timeline's clock, chooses among eligible heads, and removes empty timeline queues. `ClientHorizon.drain:87–90` supplies the captured event clocks instead of taking the globally earliest deadline and breaking on its ineligibility.

The exact first-review example is covered by `independentEventClocksDoNotBlockEachOther`: A deadline 1400/clock 1380 remains held while B deadline 1420/clock 1430 is returned; A is returned only when its own clock reaches 1400. Production supplies the same event ID on reservation and clock lookup. The change neither advances a pressure clock nor rewrites a deadline. The post-nuclear-tick sample also resolves the first report's one-tick phase issue; the paused branch samples the frozen event age.

Static inspection supports closure of the specific R3 failure. This is eligibility/ownership reasoning, not proof of real renderer latency or absent-event fallback behavior under every packet timing.

## R4 — separate acceptance boundary, unchanged

No supported-publication contract was added in this final diff. `VoxyIngest.ingest` still makes unversioned asynchronous `rawIngest` calls; `backlogged` still reads task count; `LssVoxyWatch` still observes rather than vetoes another consumer. The coordinate-only hint bounds and server refresh path remain unchanged.

`FarLods.refresh:226–249` still returns without a replacement when no applicable plan/trace/darkness exists. Hints can be evicted and reconnect clears them. Full-column delivery corrects baseline contents at our submission, but does not prevent older foreign work from committing afterward. No new supported guarantee should be inferred from R1–R3 closure, a successful submission, a later readback, or this review.

R4 remains the previously identified acceptance/API decision, not an additional regression attributed to these final changes. R5 is independently fixable inside Incoming and must not be folded into that upstream limitation.

## Scheduling, memory and optional notes

- Per-event priority queues retain at most the bounded ticket population. Empty timeline queues are removed. Stop visits all queued timelines and preserves accounting for taken/pending work. No new unbounded terrain-image cache or tick-facing lock spanning a Voxy call was found in this diff.
- Full columns restore necessary correctness and increase work relative to sparse delivery. This static review establishes neither final network/frame throughput nor a total heap bound. The existing encoded-byte and ticket caps remain separate from codec scratch, Voxy workers, renderer and database ownership. Final-head production measurements remain the implementation owner's gates.
- Optional documentation cleanup: `.claude/rules/voxy.md:80` still names removed `VoxyLods.sendChanged`; it should name the full-column `send(..., detonation)` overload. This is minor and does not justify another review round.

## Checkpoint

Final independent review is complete on `b97580117`. The specific R1–R3 corrections are sound by static inspection; R5 remains a serious correctness finding. R4 remains separately scoped acceptance work. No third round is requested by this report, and no source changes or production action were performed. The implementation owner owns correction, regression, required validation and the final merge decision under TEAM/AGENTS.
