# Independent review — PR #572

Reviewed: `659af24a4..325ed64ebc8624a78b96462d27594d79318954d0`.
Worktree: `/mnt/data/projects/incoming/mod/run/wt-R572`, detached at the requested HEAD; clean at the start and final source check.
Reviewer: Codex, fresh independent R572 session.

## Verdict

No serious findings in the reviewed diff. No demonstrated production crash, incorrect cross-world refresh, unbounded coordinate retention, or new work-budget breach was found. One optional lifecycle/test-hardening point follows.

## Optional finding

### P3 — Test the VoxyIngest facade across an active-session reset

Locations: `mod/src/main/java/ua/zentix/incoming/client/VoxyIngest.java:110-112,142-144`; `mod/src/main/java/ua/zentix/incoming/client/lod/LodRefreshHints.java:17-21`; `mod/src/test/java/ua/zentix/incoming/client/lod/LodRefreshHintsTest.java:24-41`.

The new reconnect test exercises sequential helper calls. It does not exercise the separate volatile `ingested` field plus `HINTS.begin/reset` in the real facade. If `resetSession()` is used while the connection is still active (the method explicitly supports the development sandbox), this interleaving is possible:

1. Network `accept` captures the level, passes the guards, and writes `ingested = level`.
2. Main-thread reset clears `ingested` and calls `HINTS.reset()`.
3. The network call resumes at `HINTS.begin(level)` and subsequently remembers its coordinate.

Afterward, the helper again owns that level while `ingested` is null. An LSS callback is rejected at the facade guard until another Incoming packet reestablishes it. The helper's synchronization prevents collection corruption but does not make these two facade fields one reset boundary. A latch-based facade test would document this case; an epoch or an explicit active-session reset contract could address it if needed. Keep decoding/raw ingestion outside a long main-thread-shared lock.

This is **not a demonstrated ordinary logout defect**: in the local Minecraft/NeoForge 21.1.264 sources, PauseScreen calls `ClientLevel.disconnect()` first, which calls `Connection.disconnect()` and awaits channel close; Minecraft then fires LoggingOut. A remote disconnect likewise closes the connection before disconnect processing. Thus the normal exit path drains the network handler before the new reset hook. The sandbox-specific interleaving is an optional hardening point, not a production merge blocker.

## Review evidence and coverage

- Read the complete diff and surrounding VoxyIngest, LssVoxyWatch, IncomingClient reset/login/tick/hooks, ClientLodBatches, network registration, request codec/rate and server stale/refresh paths. Read current TEAM/BOARD/live, repository rules, shared memory facts; did not read the PR description, author reports/conversations/inboxes, or MT-L2-next.
- `LodRefreshHints` serializes every mutation/drain on its monitor. Single-item insertions trim before releasing it; repeated remembered coordinates move to the tail, and pending requests deduplicate. Limits remain 65,536 recent coordinates and 4,096 pending coordinates; no section arrays or terrain copies enter those collections.
- LSS callbacks carry the ClientLevel identity. Both the facade guard and helper owner check reject stale callbacks after a completed reset/new-world begin. Client tick also drains only its current level, at the unchanged 12-coordinate rate (240/s at 20 TPS, below the existing server allowance of 256/s).
- A repeated Incoming submission no longer removes an already pending foreign-overwrite hint. Eviction and missed first-submission crossings remain best effort as documented; the new code does not claim a fence on foreign ingestion or durable terrain authority.
- With no concurrent active-connection begin, logout clears both level references (`ingested` and helper `session`) and both coordinate collections without waiting for a subsequent world packet. Login also resets, including a reconnect with no subsequent LOD delivery.
- The four added unit tests cover bounds/eviction, helper owner rejection/reset, request deduplication/preservation, and direct refresh requests. They do not execute VoxyIngest, the actual network/LSS/tick interleaving, or renderer delivery. Their names should not be treated as integration/concurrency evidence.
- Verified normal exit ordering directly in the cached patched 21.1.264 source archive: `PauseScreen.java:122-129`, `ClientLevel.java:376-378`, `Connection.java:471-479`, `Minecraft.java:2146-2164`, and `ClientCommonPacketListenerImpl.java:302-304,327-330`. Cache metadata identifies `neoforge-21.1.264-sources.jar`.
- Per requested read-only review scope: no build, tests, GPU, server, CI waiter, public comment, merge, or source edits. Existing unrelated jobs were observed only; no jobs/resources were started or modified. No runtime correctness/performance result is claimed.

## Handoff

Review is complete for the pinned HEAD only. MT-L2 remains the implementation/PR owner. No serious finding requires a second review round from this report. Reassess if the production diff or client reset threading changes.
