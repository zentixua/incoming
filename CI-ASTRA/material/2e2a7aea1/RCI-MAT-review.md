# RCI-MAT independent source review

Time: 2026-11-04 16:52:45 UTC
Reviewer: Codex, gpt-6-astra / high (verified in this session's running command).
Base: f9d1ac8ee13f612f7774d9a5e72dfb6a4e3c96e0
Head: 2e2a7aea1c281c47be318b4b35f26858833bd452
Worktree: /mnt/data/projects/incoming/mod/run/wt-RCI-MAT (clean detached HEAD, checked at start and finish).

## Verdict

PASS for this material-admission correction: no serious correctness, capacity, material/cargo loss, or newly introduced performance defect found in the pinned diff. This is a source review of the new reservation calculation, not approval of the complete preparation stack, a total JVM-heap bound, or real 1 Mt acceptance. Validation and integration remain with CI-ASTRA/LEAD2.

Read TEAM/context/BOARD/live, repository AGENTS, nuclear/world/work/chunk/GameTest rules and memory index. Inspected the diff and surrounding production/test code directly. Did not read PR595 description, author inbox/conversations/checkpoint/handoff or earlier review reports. No source changes, builds, jobs, GPU/server actions, CI reruns or merge. The author's active build was observed only, not controlled or awaited.

## Independent ownership check

Source references below are relative to this pinned worktree under mod/src/main/java/ua/zentix/incoming/nuclear/world/.

- RuinMatter.java:48–75 now reserves 5,536 bytes per candidate plus both compact queues' actual growth. The terms are 512 for the captured Piece, 4,096 for its deferred support source, 256 for original provenance, 512 for a new material balance, 96 for a new palette state and 64 for a new inventory slot. These correspond to actual producers and retained accounting, rather than accepting the comment as a proof.
- RuinMatter.java:332–355 publishes each Piece once by its ID. Non-fine material adds one 157-byte moving record; fine material adds only conserved dispersed totals. RuinMatter.java:171–178 adds at most one original and one 16-byte support source per bearing Piece. The palette is shared by the two encoders (lines 750–773), so there is at most one new state per removal, not two. Cargo retains the same CompoundTag reference in inventories and contributes the unchanged 3 * sizeInBytes charge through movingBytes. No serialization, conservation, trajectory, classification or cargo representation changed.
- RuinRecords.java:39–46 includes whole new pages, page metadata and replacement of a restored partial tail while the old array may still live. Queue growth is additional to the per-candidate allowance, including a single removal that creates a full 1,024-row page. This avoids treating packed row width alone as an allocation bound.
- RuinMatter.java:180–195 expands a support seed into at most five contact keys (DOWN excluded). Even five distinct support entries plus five cold-face entries charge 5 * (256 + 256) = 2,560 bytes, below the 4,096 deferred-source term; original provenance is separate. Later unbounded graph exploration is not paid by that term: workBatch independently reserves a bounded 32 KiB graph step. The high-water supportBacking accounting is unchanged.
- Both production entry paths were checked: RuinPlan.java:941–990 reserves the whole batch before source mutation, extends for cargo before write, retains refused plans with memoryWaiting and publishes actual removed sources in finally; RuinSupport.java:80–91 uses the same new calculation for one captured Piece through reserveProgress, before replacement. RuinMemory.java retains writable/cold-ledger checks, the shared dimension counter and try-with-resources lifetime. ScarQueue.java:958–1014 preserves prepared/parked plan ownership on MEMORY rather than treating refusal as staleness or success. No new owner-release gap was found.

For 22,054 candidates with empty compact queues, independently calculated queue growth is 3,901,568 bytes. Old admission requests 195,859,584 bytes; new admission requests 125,992,512 bytes. The decrease is 69,867,072 bytes. Retained publication growth remains below the new reservation in the supplied fixture; the reservation is not simply the measured fixture growth copied into a constant.

## Raw evidence and limits

Raw GameTest logs: /mnt/data/projects/incoming/mod/run/wt-CI-ASTRA/mod/run/ci-astra/near-queue/.

- material-red.log:113–114 uses 128 MiB room and fails its own funding prerequisite: allocation 47,338,768 + retained growth 99,980,392 exceeds 134,217,728. It is NOT evidence of an admission refusal with demonstrated room.
- material-red160.log:112–114 uses 167,772,160 bytes room, allocation 47,339,000, retained growth 99,980,392. With initial owned 369,098,752 it reports applied=false, memoryWaiting=true and fails the admission assertion.
- material-green160.log:113–117 has the same cardinality, room, initial ownership and retained growth; allocation is 47,339,008. It reports applied=true, memoryWaiting=false, final ownership 469,079,144 and all one required tests passed. This is a useful consumption-boundary red/green pair with a fixed external owner.
- The pinned test (mod/src/devtest/java/ua/zentix/incoming/nuclear/world/RuinQueueProgressGameTests.java:78–115) checks all 22,054 source blocks removed, exact released/pending/support counts, equal volume and per-block volume/mass balance, and final accounting below the unchanged cap. It builds the payload through the real section encoder but injects material candidates; it does not reproduce fracture generation or whole-queue scheduling.
- Actual-runtime raw file /mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/ownership-probe2/lead2-ownership2.txt:77–83 shows the live parked plan at chunk (-3,-57), event 2, material/cells length 22,054, memoryWaiting=true, task=null and loadedMask=1ffffff. Lines 39 and 82 also show 138 parked owners and this plan's retained state. This corroborates the affected consumption boundary, not the fixed-version real-runtime outcome. The snapshot contains other task/readiness states too; it does not establish this calculation as the sole cause of the whole 1 Mt stall.

Evidence boundaries (nonblocking for this diff): the new test uses homogeneous stone bricks without cargo and samples retained ownership before/after application. ThreadMXBean reports cumulative allocation on one server thread, not peak live memory or other-thread allocation. Its allocation-plus-growth comparison is a conservative fixture-room check, not a proof of the exact 125,992,512-byte admission threshold: the measured sum is 147,319,400 bytes and can double-count published allocations. The source ownership derivation above is therefore necessary. Existing capture serializes arbitrary block-entity NBT before grow/reserveProgress can price that tag; this ordering is unchanged and prevents claiming a universal allocator-before-allocation guarantee. No new cargo-loss path was found. A future exact-threshold mixed-state/cargo/restored-tail fixture would strengthen coverage, but is not a blocker found here.

The raw logs do not themselves pin a source commit/JAR hash, so their execution provenance is not independently certified by this review. No runtime artifact or server was controlled. The author still owns final build/touched-area regressions, required CI and integrated real-world acceptance; this review neither reran nor substitutes for them.

SHA-256 of read evidence:
- material-red.log: 8df03fafcce8ba3fe43c155d84860d2bfa69689b48428e18fa49aa8ca4388f28
- material-red160.log: 9867b051462e280ae8ad5d2da4726fc49e56c76cba7bdf08684a59549caed284
- material-green160.log: 9c060646cf8524827fce037cd3e84fdc4b2c5cc2998742b23ca94613ff4b75db
- lead2-ownership2.txt: 31a2ba747df4abbef62307b58f6ca8fd9299747f3d80c067f00210c97b4f6b7b

## В память

Shared memory unchanged, as requested. Durable review evidence is recorded here; no new general project claim was promoted from this narrow fixture.
