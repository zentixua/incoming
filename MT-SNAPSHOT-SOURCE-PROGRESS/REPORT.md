# Source producer progress under a fixed owner

Final component: `0f3019a258b59758e958e81bb3cdaa6e210e3446`, `codex/nuke-source-progress`, exact base `4e1f8f6021c10d586758f30639efec6c0de7fe17`. Commits ede72a84c (production),5b21d44c3 (new regressions),0f3019a25 (requested shared publication helpers). Root owns NuclearPrep adoption, integrated second independent review/full checks/runtime. No PR/review/agent/fullbuild/CI/GPU/realserver launched by this component.

## Correction

New RuinContext window source reads leave one max-dimension `threadBufferBytes` startup margin. At H384 that is13,271,040B. This is a new-source gate only, not a reservation for the configured pool, a permanent material gate, a64MiB floor, or a policy/heap/thread/timeout change. It deliberately does not infer usable current-pool credit from global worker residency, which can include old shutdown survivors.

A completed inbox head can fund its known publication from its existing4MiB reservation. `RuinInbox.takeForPublication(expected,knownBytes,room)` refuses positive excess without touching the head, or removes the ready head while retaining pending ownership and resizing the counted source reservation to the combined known bound. Its synchronous Publication lease remains counted through priming/validation/publication or discard, including invalidation. A consumer charges the new data before releaseToCountedOwner; close refunds only remaining source/scratch. Unfinished/late callbacks still retain their original reservations until delivery/invalidation completion.

Known combined bound includes detached Read palette serialization/array metadata/heights, new snapshot/lazy property/old-plan arrays, priming/motion/height growth, archived NBT including invalid-dimension tags, and archived decoded publication bound. Existing source-failure/absent classification is unchanged; MEMORY never polls/drops a refused head or changes source failure/absence. No physical or disk-format payload changes.

Shared package helpers requested by root: static `diskSourceBytes(Read)`, instance `publicationBytes(Read, nullable Optional<CompoundTag>)`, `publishReserved(Read, nullable Optional<CompoundTag>)`. The latter requires an already charged owner and rejects skipped sources; archive validation/warn/fallback matches the original collector. Existing standalone putDisk/restoreArchived(level,...) retain full guards.

## Regressions and exact receipts

Initial compile main+devtest PASS13s atede72. New unit fixture had one checked-Future.get compilation error; changed to resultNow after actual finished. Successful GREEN receipts:

-2/2 RuinInboxPublicationTest units: ready-slot funding at capacity; positive excess refusal preserving exact head; invalidation during owned publication; cancelled late callback and duplicate delivery; exact-once final refund.
-1 actual GameTest `nuke_source_progress` (3 nonempty physical results), GREEN on5b21 source: tests3.478s/Gradle21s; wrapperexit0.

The GameTest creates75 real chunk sources in three disjoint5x5 windows via ordinary chunk storage writes/reads, strips all disk heightmaps to exercise actual priming, and never loads the cold world chunks. All three target contexts are REGISTERED in NuclearWorld.ruins, so retained snapshots/heights participate in the cross-dimension counter. Real background Collapse/finish jobs carry existing result permits, and each full stored physical/material output equals the same-input reference; material and changed-block sets must be nonempty.

After source setup, it shuts down to zero live worker bytes, creates an unstarted pool, and fixes other-dimension materialReservation at **484,156,928B**. Initial room is only48MiB, so a blanket64MiB floor cannot pass. That external owner remains numerically unchanged until AFTER all three plans complete. No external refund or cap change opens progress.

GREEN initial8 real read slots, pool0,16,777,216B left for decoder startup. At tick20 one plan complete. All three complete in38 callbacks, no read owner orphan/no source error/no cold world load. Maximum sampled known counter **533,364,736B <=536,870,912B**. This is sampled after drain steps, not a continuous object-heap or all-instruction counter peak. Consumer-before-producer ownership transfer may briefly double-count identical objects; no new admission occurs inside that synchronous server-thread handoff, and the combined bound already covers the unique payload.

Deterministic RED control uses IDENTICAL GameTest5b21 assertions and replaces ONLY the three production files with exact old4e sources: **12 real read slots, pool0, room0**, then600tick timeout with `Source reads spent the last decoder restart: initial room=0`. Tests31.44s/Gradle50s, expectedwrapperexit1. No blind rerun. The local restoration trap had a wrong relative cwd after cdmod; manual restoration completed immediately after job exit and git verified byte-identical HEAD/clean. Raw red log retains this ancillary script error; the GameTest's expected failure is logged before it. The script was corrected on disk, not rerun.

Final requested helper extraction and invalid-NBT source bound compileJava/compileDevtestJava +2units PASS6s on0f301. The root explicitly requested no redundant actualGT repeat for this internal extraction; root integrated far collector GT/review remains required. All owned jobs were waited and ended; no active source-progress units remain.

## Integration and limits

For far plain96KiB/archive4MiB read collectors, root computes publicationBytes, takes an owned ready lease using current all-dimension room, records ctx.retainedBytes, invokes publishReserved, releases only the positive newly-counted delta, then closes remaining source/scratch. Retain a refused head for retry and run existing re-readable cache trimming; never classify capacity as missing terrain or I/O failure.

Existing RuinSnapshotAdmissionGameTests.refusedInboxHeadKeepsOwnerUntilActualPublication assumes a fully charged4MiB ready head MUST refuse when shared room0 although its known combined payload fits the slot. That old fixture expectation conflicts with the corrected funded transition. Root must update that fixture to assert funded publication at capacity or use a genuinely larger-than-slot source to test refusal. This component was forbidden from editing existing other fixtures.

If a single combined known source bound exceeds its ready credit plus available room, safe refusal remains necessary. Existing cache eviction may open progress; an irreducible held base that cannot fund one read slot/worker/window/result has no unconditional progress guarantee. This correction does not page arbitrarily oversized components or promise completion under every immutable external reservation.

Raw ChunkStorage/upgrader/NBT/codec transient heap before detached payload availability remains the explicit API limit. Known-array/serialized-palette ownership is not complete JVM heap, exact continuous peak, end-to-end100/1000kt throughput or main/release readiness. Root runtime15 sourcead13 is different and is not retroactively assigned this cause from these tests.
