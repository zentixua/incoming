# NeoForge 1.21.1: investigate persistent stale store rows after bulk saved chunk edits; supported server invalidation API

LSS 0.20.0, NeoForge 1.21.1, Incoming 3.10.3, Voxy through Roxy; no Distant Horizons.

## Reported server symptom

After a 7 kt Incoming detonation, approximately 367,000 chunks in the affected zone were loaded once, processed and saved by vanilla. Approximately 66,000 store rows were dropped, but distant clients still showed intact terrain in much of the zone. An administrator's `store invalidate all` followed by backfill (410,881 rows) corrected the terrain. These are the server operator's observations; the server/world was not accessed during this investigation. The number of loaded or scar-marked chunks is not the number of LOD-visible changes, and store rows can be repopulated, so the counts alone do not establish a missed-invalidation rate.

## What the source and local checks show

The initial suspicion was that Incoming's direct section writes miss block-change events. That is insufficient: [ChunkSaveDataHook:26–29](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/neoforge/src/main/java/dev/vox/lss/mixin/ChunkSaveDataHook.java#L26-L29) hooks `ChunkSerializer.write` at RETURN; [ServerReceiverGlue:92–118](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java#L92-L118) calls [DirtyContentFilter:167–196](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/xplat/src/main/java/dev/vox/lss/networking/server/DirtyContentFilter.java#L167-L196), hashing section block states, biomes and light. Changed content deletes the old SQLite row immediately and marks the dirty tracker. [DirtyColumnBroadcaster:82–164](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/xplat/src/main/java/dev/vox/lss/networking/server/DirtyColumnBroadcaster.java#L82-L164) drains at 200 ticks by default, and [OffThreadProcessor:949–989](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java#L949-L989) invalidates timestamps, the store and in-flight state before clients re-request data.

The same relevant code is present in 0.19.0 (`c21e37a85148f903550be73f95bea3c60e0cccd4`) and 0.20.0 (`9af90cfd09e2b62ebe71a2a434840c78981be841`). Incoming marks directly modified chunks unsaved. Its new integration checks with both actual released jars pass: direct section write, real ruin plan, real flash plan, biome-only plan; a 100-chunk disk-only RuinSettle pass first populates SQLite with intact columns, then verifies all 100 old rows are gone after vanilla saves scarred chunks. The direct-plan checks also wait for the real dirty fan-out and verify replacement store bytes. They do not run a Voxy client or reproduce the original large-world symptom.

Reproduction commands and tests are in zentixua/incoming PR #554. At this point the original stale-row failure is **not reproduced**, and we are not claiming a confirmed root cause in LSS.

## Support/API request

Could you advise which counters/traces distinguish missing save observations, suppression by the load-seeded hash, and later deposits of old data in this situation? In particular, [ServerReceiverGlue:143–159](https://github.com/VoX/lod-server-support/blob/c21e37a85148f903550be73f95bea3c60e0cccd4/xplat/src/main/java/dev/vox/lss/networking/server/ServerReceiverGlue.java#L143-L159) seeds the live load baseline rather than checking the corresponding SQLite row: a previously stale row is not necessarily repaired merely by loading and saving identical current content. That is a persistence/recovery concern, not proof of how the first stale row arose.

Is there a supported server API for a bulk editor to invalidate changed column coordinates through the complete normal LSS path? `dev.vox.lss.api.LSSApi` exposes client consumers/status, but no such server contract in these versions. We have deliberately not called `LSSServerNetworking`, `DirtyContentFilter`, store internals, or commands from Incoming production code, and have not added a mixin or synthetic saves to trigger your hook.

If no API exists, a documented bounded/batched entry point accepting server level + changed chunk coordinates would let editors explicitly invalidate the store, timestamp cache, served-session state and obsolete in-flight results, and notify present clients through your existing scheduler. It should not load/generate chunks or synchronously reread their contents. Persisted invalidation should also protect later joiners and shutdown/restart. Please indicate whether this should be an upstream feature or whether an existing standard event/path already supplies that contract.

No issue has been posted automatically.
