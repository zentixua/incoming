Review completed: 2026-11-04 11:48:56 UTC

# R583 independent source review

## Verdict

No blocking introduced behavior, crash, side-safety, or budget defect found in the reviewed diff. This is source-review clearance for the removal, not acceptance of real-world LSS/Voxy convergence or release readiness. The parent owns build/GameTest/CI and the real saved-source/client trial.

- Candidate: `2bdef799eec41fdff49dc6f346c4899c5ce65373`.
- Base: `1126ffa3bf3f5f2c8eee1b2340da49c2a5663f98`.
- Checkout: `/mnt/data/projects/incoming/mod/run/wt-R583`, clean detached HEAD at the candidate, checked before and after review.
- Session: fresh Codex CLI, `gpt-6.1-sol`, high; `SESSION_TREES=/mnt/data/projects/incoming/mod/run/wt-R583`, `INCOMING_GPU_OWNER=R583`, verified in the session environment. No nested reviewer was started: this session is the independent reviewer.
- Scope: all 39 changed files, including every deleted implementation/test, plus relevant callers and surviving packet/store/world lifecycle paths. TEAM/context/BOARD/current live/AGENTS and area rules were read. The 11:23 live goal governs the verdict: near-world wave timing, eventual standard saved-source distant delivery, no strict Voxy front-time machinery, read-only map/depth and optional DH retained.
- Independence: no PR description, author report/inbox/transcript, or `MT-L2-clean-scope` was read during review or verdict/report preparation. No author test logs or conclusions were used as evidence. After the completed report and handback were written, a verification `tail` accidentally exposed two neighboring author inbox entries (11:43:23 and 11:47:13). This was a scope mistake; they did not inform the completed review or alter its verdict. No further author material was read.

## Removal and side/lifecycle checks

`Incoming.java` removes both Voxy transport event listeners. `IncomingClient.java` removes initialization, login advertisement, ingestion ticking/reset and the three hook overrides. `IncomingNetwork.java` removes all three C2S transport registrations. `ModAttachments.java` removes `VOXY_LODS`, which was explicitly non-persistent (`AttachmentType.builder(...).build()` without a serializer); this is not deletion of saved world ownership. The entire server `VoxyLods` viewer/build/held queue, client `VoxyIngest`, connection-attribute `ClientLodBatches`, LSS overwrite subscriber, coordinate hints, section serializer/custom copy lighting and batch-flow helper are removed together.

Repository-wide symbol searches found no surviving references to these removed classes or C2S records, including devtest sources. `FarLods.refresh`, targeted recipient state and its special lighting branch disappear with their only producer/caller. No orphan active sender, stale callback registration or retained client queue was found. Retained common packet handlers reference no client implementation class, so the removal does not introduce a dedicated-server client-class dependency.

The experimental `LodBufferScenario`, `LodProbeWire`, timed buffer and witnesses plus the scenario dispatch arm are removed. Their deleted tests exercise the removed implementation, not a surviving production front-timing promise. Some unused development helpers remain outside this diff (for example the panorama/readback helpers); this is not a functional blocker.

## DH behavior retained

`DhUpdates.active` now requires supported DH or a test sink (`DhUpdates.java:115`); with Voxy only, `room` and `heldRoom` return zero and the ordinary mark/copy path does not activate. This is the intended removal of speculative Voxy delivery, not a switch to another terrain source.

For active DH, `room` remains the offered-copy capacity minus waiting copies (`:187`, limit 512); FarLods subtracts in-flight reads before starting more (`FarLods.java:348`). `heldRoom` retains the DH window minus outstanding held copies (`DhUpdates.java:210`). `sendHeld` retains duplicate/pending-copy suppression, installs the DH listener, uses the sink, and records the held acknowledgement (`:226`). Removing the Voxy capacity minimum no longer lets an unrelated Voxy connection stall DH. FrontLods still checks a whole section against this DH window and its send-time budget; that supported optional DH behavior was not deleted or applied to Voxy.

The remaining ordinary drain still caps delivery at 20/tick and scans at most 256 due marks. Unloaded marks retain at most four saved-copy reads. A new mark obsoletes the old result while retaining its read slot until completion; I/O failures retry and missing/non-final saved data can retire. `FarLods.savedCopy` (`:604`) continues to use DiskShots asynchronously and builds from saved sections without reapplying ruins. Removed recipient/refresh state did not remove this generic DH freshness path.

Acknowledgements still marshal attachment access onto the server thread and reject stopped/replaced worlds or absent attachments (`DhUpdates.java:246`). DhQueue's timeout, window and lifecycle accounting and DhChunks' optional API/version guards are unchanged. Existing limitations remain: DH API submission/refusal or a timeout is not proof of a visible mesh, and this change does not add revision-authoritative acknowledgements or stronger DH queue guarantees. These limitations predate the candidate; I found no new producing case caused by the removal.

## Saved world and standard LSS path

The candidate does not change RuinPlan's world writes, scar/biome persistence, vanilla section delivery, pressure clock or geometry. The common write path still marks the chunk unsaved (`RuinPlan.java:1023`). NuclearWorld still runs durable RuinSettle (`NuclearWorld.java:523`), which visits saved-zone chunks independently of DH/Voxy presence, applies through ScarQueue and confirms saved scars. Its admission/retry/history handling is unchanged. Removing `DhUpdates.enabled` for Voxy does not gate this settlement path. FrontLods and FarLods synthetic copies remain DH-only; the normal world is the source for LSS.

For the external save seam I inspected existing decompiled classes and checked their extracted class bytes against the actual local NeoForge LSS 0.20.0 artifact:

`/mnt/data/projects/incoming/mod/run/incoming-sessions/gold2/jars/lss-0.20.0.jar`

SHA-256: `2bc87a9f65b7b9450455035d1ec3502e6ab338ab736af90dada484de3fd0139e`.

Exact extracted-byte matches were confirmed for `ChunkSaveDataHook`, `ServerReceiverGlue`, `DirtyContentFilter`, and `DirtyColumnBroadcaster` under `/mnt/data/projects/incoming/mod/run/pl2-investigation/classes`; corresponding decompiled source was read. The save mixin observes `ChunkSerializer.write` at RETURN. A changed serialized-content hash marks dirty and deletes the old store row; the ordinary broadcaster invalidates timestamps/in-flight state, clears served bookkeeping and sends paginated dirty notices to in-range clients when enabled. No Incoming block-event dependency or new implementation call into LSS was introduced.

I also inspected existing decompiled client dispatch/processing and the local upstream source checkout (`vxa-src/VoX_lod-server-support/repo`, clean HEAD `178d9cd4411ba9e3423e0278d6a39bf755db171a`) for request reopening/consumer ingestion. That checkout is auxiliary source context, not proof that every newer upstream helper exists in the pinned runtime. Exact-byte correspondence was established only for the four server classes above. Provider enablement/range, dirty-notice configuration, cache seeding, request completion, final lighting and Voxy ingestion/rendering still matter. A deleted store row alone does not establish any of those downstream outcomes.

**Remaining acceptance evidence, owned by the parent:** on the actual server/client pair, follow genuinely changed remote columns from final saved source through ordinary re-request and receipt into Voxy database/rendered terrain, including a warm existing cache and later join. Measure convergence delay and check that the source/visible buildings do not remain old. The diff removes a competing speculative producer; source review cannot establish eventual delivery merely from that removal. This is a runtime-evidence limit, not a newly identified code defect or a request to restore strict-front machinery.

## Flashback and protocol

The three retired clientbound IDs remain registered with the same codecs, now no-op (`IncomingNetwork.java:61-63`): `voxy_chunk`, `lod_batch_start`, `lod_batch_finished`. The Voxy packet still decodes dimension ResourceLocation, varint X/Z and a bounded byte array (1 MiB); its section bytes are opaque to the outer codec. Therefore deleting LodSections does not prevent decoding the recorded outer payload. Markers retain their original varint fields. No codec field, order or payload ID changed in S2C; changes there are comments only.

The shipped samples `voxy_chunk.1.hex`, `lod_batch_start.1.hex`, `lod_batch_finished.1.hex` and all other golden resources are unchanged between base and candidate. The marker samples are `ac02` and `ac0220`; the Voxy sample still contains overworld, X=-3, Z=70000 and its original five-byte section array. S2CGoldenTest still covers all three (`:167-170`) and checks that every registered S2C has samples. Current server source has no sender for these payloads. No-op handlers cannot mutate terrain or reply on an obsolete C2S channel during replay.

The live protocol moves from 53 to 55, so removing C2S registrations does not silently admit an old live peer. I did not execute Flashback or the golden tests; preservation is established here by the diff, retained registrations/codecs and unchanged byte vectors.

## Read-only terrain, documentation and coverage

TerrainTiles still registers its independent LSS API consumer (`TerrainTiles.java:644,660`), initializes Voxy terrain separately, and subscribes to supported DH. IncomingClient still calls `LodDepth.init()` (`:111`). The map source stack, Voxy database probe, Voxy/DH depth implementations and their unit fixtures are unchanged. LssVoxyWatch's removed recovery subscriber was not the targeting-map consumer. No physics/model/config/terrain-source selection edit is hidden in this diff.

All changed EN/RU guide paragraphs and integration/rule updates were inspected. They describe removal of custom transport and delayed ordinary provider delivery without claiming at-wave far convergence; the optional DH integration remains documented.

Live coverage after removal includes DhQueueTest, DhCallbacksTest, DhAttachmentGameTests and DhGameTests (coalescing/resend, scanning, copy equivalence, far queue/zone lifecycle, section/front budget). New `DhSavedUpdateGameTests` retains the useful saved-copy gold-block case and old-read/new-diamond invalidation case without the removed Voxy viewer fixture. These are DH sink checks, not a real DH engine/mesh test. Deleted custom transport/lighting/buffer tests no longer apply to live behavior.

LssRuinGameTests and LssSettleCheck remain. Their useful scope is actual save-hook invalidation, dirty drain and saved-zone source persistence. In `LssRuinGameTests.java:98` the fixture itself deposits the replacement bytes after a store barrier; that assertion does not exercise a real client request or prove end-to-end fresh delivery. `LssSettleCheck.saved` asserts old-row absence, not eventual client receipt. These boundaries must stay explicit in the parent's readiness claim.

## Execution and handback

Only light read/hash/diff inspection was performed. `git diff --check` found no whitespace errors. No tracked file was edited, no build/test/profile/client/server/GPU/CI/PR/network publication was started, and no teammate process or resource was changed. Author checks and its PR waiter were observed in the separate MT-L2-CLEAN worktree at startup and remain parent-owned. There are no owned jobs, nested agents, GPU leases or server resources to await or release. No merge action.
