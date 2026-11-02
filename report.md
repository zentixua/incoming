# RUINLSS investigation

Status: the reported large-world stale LOD failure is not reproduced. No production fix is claimed. PR #554 intentionally adds integration coverage and corrects the documentation, without changing game behavior, versions or protocol. No live server, GPU or user world/settings were used.

## Actual LSS path

Pinned compile/runtime ID `ydDRzPcM` is LSS 0.19.0 NeoForge 1.21.1. Additional tested runtime `jxJ4956L` is 0.20.0. Source commits are `c21e37a85148f903550be73f95bea3c60e0cccd4` and `9af90cfd09e2b62ebe71a2a434840c78981be841`; the relevant hook/filter/store fan-out is unchanged.

[ChunkSaveDataHook:26–29](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/neoforge/src/main/java/dev/vox/lss/mixin/ChunkSaveDataHook.java#L26-L29) → [ServerReceiverGlue:92–118](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java#L92-L118) → [DirtyContentFilter:167–196](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/xplat/src/main/java/dev/vox/lss/networking/server/DirtyContentFilter.java#L167-L196) → immediate store delete + dirty tracker → [DirtyColumnBroadcaster:82–164](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/xplat/src/main/java/dev/vox/lss/networking/server/DirtyColumnBroadcaster.java#L82-L164) → [OffThreadProcessor:949–989](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java#L949-L989) → client re-request/delivery-path deposit. Missing block events do not explain stale rows by themselves.

## Checks

- LSS 0.19.0: real section, ruin, flash and biome-only changes invalidate old SQLite rows, enter the dirty queue, and support fresh replacement bytes after normal fan-out.
- LSS 0.19.0: 100 intact SQLite fixture rows; real saved-zone settlement; 100 saved ruined chunks; 0 stale rows. 310 settlement ticks, 15.558 s wall, nuclear work mean 0.568 ms/tick, max 10.308 ms. Heap used 748 MiB at start, sampled peak 1472 MiB.
- LSS 0.20.0: the same 100-chunk store check passes, 0 stale rows. 317 settlement ticks, 15.852 s wall, nuclear work mean 0.769 ms/tick, max 13.084 ms. Heap used 701 MiB at start, sampled peak 1067 MiB.
- LSS 0.20.0: all 14 selected LSS + `nuke_settle_.*` tests pass, including restart, budget, cache, incomplete windows, neighbors, empty plans and ordinary 100-chunk settlement.
- Bare runtime initially exposed a new test-discovery failure (synthetic lambda signatures referenced absent LSS). Fixed by isolating implementation methods in an unannotated nested class. Bare `nuke_settle_disk` then passes. CI repeats ordinary bare shards separately from two LSS jobs.
- Tests use actual LSS implementation classes only in devtest to arrange and inspect SQLite fixtures. The release jar carries none of them. The replacement-byte check uses a no-op SQLite policy receipt as a control-queue barrier: a queued unconditional delete can otherwise remove a test's later replacement deposit.

These are small CPU-only synthetic fixtures, not a performance guarantee for 367,000 detailed city chunks. Nuclear-work timings exclude LSS's save hook; overall test MSPT includes the deliberate game-pace test parking. No production per-chunk work was added, so no additional runtime pacing cost is introduced by this PR.

## Final validation

Final code head: `1de4d8021d1cbae1084d6be925d43e40c4a284df`. `./gradlew --daemon build -PmcModsDir=run/ruinlss/runtime -PtestForks=4` passed: 1740 unit tests, zero failures/errors/skips, no compiler warnings. Prior full builds also passed. The final-head build passed in 4m 34s. Final bare-runtime GameTest passed after the optional-type isolation fix. Both independent review results contain no serious findings; the follow-up review covered the test-discovery and release-dependency fixes.

## CI fixture isolation discovered during validation

The original final-head attempt failed `nuke_late_bodies` twice. A local diagnostic reproduction identified ordinary `inFire` damage to a born cow (health 9, fire ticks 148), not duplicate pulse/wave exposure. Saved-zone settlement can ignite the synthetic herd's ground. The fixture now disables `nukeFires` only within that test and restores it through `afterTest`; health/dose/burn assertions remain. Both late-exposure tests pass locally after the fix, with unchanged production code. Before/after logs are included.

## Limitations and upstream handoff

No multiplayer client was launched, no large live-world reproduction was attempted, and the original incident remains unresolved. No supported public server invalidation API was found in either LSS release. A command invocation, implementation call or synthetic-save integration would exceed the requested compatibility boundary without a proven cause. `upstream-issue.md` is exact proposed issue text, not an issue posted on the user's behalf.
