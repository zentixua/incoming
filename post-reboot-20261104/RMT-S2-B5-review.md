# Independent review: MT-B5 persistence and ownership

Reviewed commit: `42afbcac859ddeff5b464bba76287ff6bae7c2f3`.
Primary diff: `490a2aa84..42afbcac8`; surrounding material, support, preparation, settlement and queue code also checked against `origin/main...42afbcac8` where relevant.
Reviewer: fresh Codex RMT-S2-B5 session, Sol / xhigh. **Changes requested: two serious findings.**

This was an independent read-only production review. Current TEAM/context/BOARD/live, repository instructions and applicable area rules were read. Author handoffs, PR description and author conclusions were not read. No production edits, merge, heavy job, client, GPU or server launch occurred. The review worktree remains clean at the pinned detached HEAD. Only a small standalone JDK probe (64 MiB heap) was run.

## Serious findings

### 1. [P1] A cold ledger can prevent the resident material from freeing its import workspace

Primary reference: `mod/src/main/java/ua/zentix/incoming/nuclear/world/NuclearWorld.java:414-416`, especially line 415.
Related references: `LedgerLoads.java:92-105`; `NuclearWorld.java:464-474`; `RuinMatter.java:349-350,403-418`; `NuclearPrep.java:312-326`.

`tick` returns until **all three** ledgers have loaded. If the moving ledger imports first but a crater/progress import needs more temporary room than remains, this also suppresses the already loaded moving ledger's `work`. The cold loader keeps waiting for room that only that work can free. Both files can individually fit, and their final resident representation can fit together; the extra import workspace is enough to make the dependency permanent. This is separate from the documented deliberate pause of one oversized active journal.

Concrete reproducer:

1. In one dimension, save 550,000 ordinary moving stone pieces without cargo, in a scene where some can deposit. Have no live support graph and no worker/preparation cache. Keep an active legacy crater pass with 900,000 unique entries in `columns`, `ground` and `done` (the supported pre-compaction format). Keep the crater event in `NuclearEvents.craters`.
2. Cleanly reload. `NuclearWorld.get` starts both cold imports. The moving ledger takes its reservation first; after publication it owns about 299,467,776 bytes.
3. The unchanged production `LedgerScan.measure` requires 252,017,530 bytes for the crater import. Even ignoring the other small ledger/dimension charges, only 237,403,136 bytes remain under the 536,870,912-byte cap, so `LedgerLoads.poll` refuses it every time.
4. The final two loaded ledgers would own about 472,531,968 bytes, below the cap. Nevertheless every nuclear tick returns at line 415 before moving material can advance/deposit, so the crater never imports and the resident material never drains. Repeated polling/restarting does not free this ownership. Pulse/front/other queues in the same tick are also held behind this gate.

The exact scanner calculation was executed, not estimated from encoded file size. Probe directory: `/mnt/data/projects/incoming/mod/run/wt-RMT-S2-B5/mod/run/rmt-s2-b5-probes/`. Output: `cold-admission.txt`; source: `LedgerScanProbe.java`. `LedgerScan.java` is an unchanged copy of the reviewed source (SHA-256 `388b42249633f7631f8af4b4e046ecb3740d651a609686998b982b3672a85a83`); its only stub is `LedgerData.IO_BYTES = 256L << 10`. This proves the admission numbers; the tick deadlock is established statically from the unconditional return and the only material work calls below it. No integrated GameTest was run by this reviewer.

Fix direction: keep unknown ownership closed to new removals/admission, while allowing already loaded ownership to drain safely without needing every other ledger loaded. Preserve the cap and the cold file. Add a real `NuclearWorld.tick` regression in which a cold import depends on resident material progress; capacity must reopen without an external test reservation being manually refunded. Also consider resident support work in another dimension: the global cold sentinel currently refuses its progress reservation too.

### 2. [P2] Streaming crater retirement can overwrite material totals already imported from a retired pass

Primary reference: `mod/src/main/java/ua/zentix/incoming/nuclear/world/LedgerReader.java:63-65`.
Related references: `CraterMatter.java:29-37,142-149,194-224`; `NuclearStrikes.java:139-143`; `NuclearEvents.java:155-162`.

For an inactive `passes` row, the streaming reader immediately calls `complete(event)`, adding its eight material totals into `result.settled`. A subsequent saved `settled` row for the same block state uses `result.settled.put(state, balance)`, replacing that addition. The new file writer always emits `passes` before `settled`, so this is a normal current-format ordering, not malformed NBT.

Concrete reproducer (small fixture, static data-flow proof):

1. Create a crater ledger containing an already settled stone total `[7, 18900, 0, 0, 7, 18900, 0, 0]` and an unfinished pass for event 7 with stone totals `[10, 27000, 0, 0, 10, 27000, 0, 0]` (remaining stock may be 10).
2. Cancel event 7 with `NuclearStrikes.clear`, then save/reload normally. Clear removes the event from `NuclearEvents.craters` but leaves the crater material pass available for post-load retirement. Equivalently, write this valid `passes`-then-`settled` fixture and call `LedgerReader.read(..., activeCraters = Set.of())`.
3. Reading `passes` retires event 7 and adds 10 stone / 27,000 mass into the settled map. Reading the following `settled` row replaces it with 7 / 18,900.
4. Expected final source/export totals are 17 / 45,900. Actual totals are 7 / 18,900; the next save makes the loss permanent. Every individual row remains balanced, so existing validation does not catch it.

Fix direction: merge saved aggregate rows with retired-pass totals, or defer retirement until all saved aggregate rows have been imported. The result must be independent of compound field order. Extend `retiredLegacyCraterSkipsLargeArraysBeforeAllocation` (or add a small companion) with a pre-existing `settled` row for the same state, both field orders, and another save/read round trip. Its present fixture has no saved `settled` section, so it cannot catch this loss.

## Optional findings

None raised separately. The serious findings above include the necessary regression gaps; no cosmetic changes are requested.

## Validation and scope limits

- Checked the real NeoForge 1.21.1 `SavedData.save(File, Provider)`, `DimensionDataStorage.save` and `IOUtilities` sources in the local Gradle source cache. The override targets the real whole-tree/copy wrapper. `writing` is set before enqueue, the live ledger stays charged, repeated saves are suppressed, and caught I/O failures retain a retry flag. Static review found no additional serious issue in that wrapper/frozen-save interval or the parked-plan ownership changes.
- Traced material mutation guards, source capture/release, support continuation, prepared/parked plan retention, cross-dimension reservations, replacement invalidation and shutdown. These checks are static, not new runtime coverage.
- Inspected raw provided `review-memory/ledger-final-tests.log`: it reports 44 required tests passed and 13,184 caller-allocated bytes for the 10,001-record real-wrapper save. These results do not exercise either reproducer above. No new CI/build/GameTest was started; the owner retains validation/merge responsibility.
- No visual/performance acceptance or full 1 Mt completion-time claim is made by this persistence-focused review.

## В память

Recorded the durable regression pattern in shared memory: a cold import must not prevent already resident ownership from performing the progress that supplies its import workspace; streaming retirement and saved aggregates must be order-independent. The findings are pinned to this SHA and are not a claim about a later fixed head.
