## Contract diagnosis (post-reboot audit, 4 November)

Exact supported LSS source is still the clean release checkout at 9af90cfd09e2b62ebe71a2a434840c78981be841. Current upstream main, retrieved on 4 November, is 45100da848135ac248cc4c70c32c6d98c7ef7b8f. Its public LSSApi still offers consumer registration, failure reporting/deferred acceptance, server status/distance and backlog observation. Dispatch is explicitly internal and fans out every column to every consumer. A consumer exception or failure report triggers a retry; it does not stop Voxy's consumer. Delaying acceptance does not prevent another consumer from submitting the old bytes.

Primary sources:
- [Release LSSApi](https://github.com/VoX/lod-server-support/blob/9af90cfd09e2b62ebe71a2a434840c78981be841/xplat/src/main/java/dev/vox/lss/api/LSSApi.java)
- [Release VoxelColumnConsumer](https://github.com/VoX/lod-server-support/blob/9af90cfd09e2b62ebe71a2a434840c78981be841/xplat/src/main/java/dev/vox/lss/api/VoxelColumnConsumer.java)
- [Voxy bridge](https://github.com/VoX/lod-server-support/blob/9af90cfd09e2b62ebe71a2a434840c78981be841/xplat/src/main/java/dev/vox/lss/compat/VoxyCompat.java)
- [Disk-result arbitration](https://github.com/VoX/lod-server-support/blob/9af90cfd09e2b62ebe71a2a434840c78981be841/common/src/main/java/dev/vox/lss/common/processing/OffThreadProcessor.java#L1339)
- [Current Roxy README](https://github.com/RasaNovum/Roxy/blob/3531a506f15f7f20f1ab72f8e987099cad17a57f/README.md)
- [Voxy upstream](https://github.com/MCRcortex/voxy)

The exact Voxy 0.2.16 runtime bytecode remains in wt-MT/mod/run/mt-b3/voxy-ingest-bytecode.txt. rawIngest returns queue admission, not publication. processJob pops work before conversion and WorldUpdater publication; getTaskCount is not a running-worker/render completion barrier. This audit did not copy or modify Voxy. Roxy's current README still identifies 0.2.16-beta/MC1.21.11 as the only fully verified translated version. A newer Voxy branch is therefore not a demonstrated drop-in fix for this NeoForge 1.21.1 pack.

### Alternatives examined

| Route | Useful capability | Remaining boundary |
|---|---|---|
| Ordinary NeoForge world edits + normal saves + LSS dirty handling | Removes durable intact region/store sources; composes normal player changes | Does not retract pre-edit in-flight data or atomically reject its later mesh publication; dirty broadcast is asynchronous. This is MT-S2/LEAD2's authoritative path, not duplicated here. |
| LSS public consumer + failure/deferred-acceptance/backlog signals | Observe arrivals, retry lost ingestion, provide bounded pressure | Observational fan-out, no replacement result or veto. A governor signal is not a fence against already admitted work; using it to disable LSS would not fix the cause. |
| Distant Horizons API 7.2 terrain repository + chunk processing event | Supported optional data submission and block/biome transformation before DH conversion | overwriteChunkDataAsync explicitly queues and allows later chunk updates to replace it. ChunkProcessingEvent is concurrent and occurs on value changes, not a versioned renderer commit. BeforeBufferRender has transform parameters but cannot cancel or substitute a versioned buffer. No demonstrated zero-return barrier. A pack swap also changes Artem's selected renderer. |
| Voxy WorldGen v2 / Chunksmith | Compatible 1.21.1 generation/pre-generation routes; official metadata/source inspected | Produces/generates terrain, not version arbitration across an already populated LSS/Voxy city. No suitable replacement/publication contract found in their documented interfaces. No install/config changes made. |
| Voxy Extra | Per-world utility integration | Official Modrinth metadata currently has no 1.21.1 release; not a verified provider for this runtime. |
| Client computes only its own deterministic visual plan | Safe trust direction: no client result becomes server world truth | Needs base/snapshot inputs and chronology of later material/player edits, duplicates server work still required for saved truth, and hits the same unversioned Voxy sink. No current throughput proof; not a substitute for the missing sink contract. |
| Own independent mesh/render library through loader render events | Can draw Incoming-owned geometry | Cannot remove old foreign terrain at newly empty building positions without forbidden terrain masking/replacement. Reject as a fake repair. |

DH interface details were checked against the bundled .java sources inside the exact local distanthorizonsapi-7.2.0.jar, not inferred from current online API docs. The primary API example is https://gitlab.com/distant-horizons-team/distant-horizons-api-example . Addon metadata came from Modrinth project/version endpoints; upstream sources are https://github.com/iSeeEthan/voxy_worldgen_v2 and https://github.com/Kishku7/Chunksmith . This is an inspected-options result, not a theorem that every possible addon lacks a solution.

### Exact missing supported contract

A provider/sink must arbitrate **all** terrain sources by world-session identity, coordinate and monotonically increasing authoritative content revision (not merely strike ID or arrival timestamp). Incoming needs a supported immutable replacement/veto before stale source data enters Voxy, plus rejection of obsolete in-flight conversion/mesh results at commit. Front-time correctness additionally needs a prebuilt revision that can become render-visible on the shared event clock, with an observable completion/publication receipt. Ordinary later edits and RuinMatter support/fall/settlement must advance that same coordinate authority. Client-only replays cannot invent it.

Neither inspected supported runtime provides this combination. No public author contact or patch to another mod has been made. The strict B3 publication goal is therefore still blocked at this integration boundary; server preparation and bounded client correctness remain useful independent work.

