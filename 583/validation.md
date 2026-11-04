# PR 583: retire Incoming's Voxy publication path

Current owner goal (4 Nov11:23): nearby world destruction follows the pressure wave. Distant terrain follows normal changed/saved-chunk LOD Server Support delivery and may arrive later; old buildings must eventually converge. Exact-front/zero-stale-frame staging and an upstream request are no longer goals. This report is source/check evidence, not a real client convergence result.

## Change

The candidate removes Incoming's reflective Voxy writer, custom server-to-client section generation/lighting, per-player queues, custom acknowledgements, overwrite recovery subscriber/coordinate hints and obsolete timed-buffer probe. No current server sender, client terrain writer or repair queue remains. Read-only Voxy terrain/depth, the independent public LSS map subscriber and supported optional DH integration remain.

The three shipped clientbound payload codecs remain with unchanged golden bytes and no-op registrations for existing Flashback recordings. They cannot publish terrain or reply on an obsolete C2S channel. Removed client-to-server transport controls make protocol55 incompatible with old live peers; root reserved55 after the separate accepted control54. The removed attachment was non-persistent, not saved-world data.

DH keeps its512-copy offered limit, worker-based held window/acknowledgements,20 sends and256 inspected marks per tick, and at most four asynchronous saved-source reads. FarLods loses only Voxy-targeted repair routing. Its generic savedCopy helper remains. The useful unloaded saved-gold and superseded-read/latest-diamond regressions are ported to DhSavedUpdateGameTests; only tests for the retired protocol/probe disappear. Shipped replay .hex samples are not removed.

FrontLods already clears before starting copy work when DhUpdates has no supported consumer. Removing Voxy from that activation condition therefore avoids speculative copies on this no-DH pack without adding a new preparation algorithm. Supported DH/test sinks still use the existing path. Physical archive/material geometry and full prelaunch scope belong to the separately accepted server core.

## Source and integration

Initial reviewed candidate2bdef799eec41fdff49dc6f346c4899c5ce65373 is based on main1126ffa3bf3f5f2c8eee1b2340da49c2a5663f98. The fresh independent Sol high review inspected all39 changed/deleted files, callers, actual save seam, optional DH and replay resources; no blocking introduced defect was found. Its post-report disclosure is retained. Source clearance does not establish a real client result.

Accepted core4d89b5b113a53b3cfe8822092e1813750516c9e9 was merged normally without conflicts into current5e68952d1a054ed90f1a48e36fbd76823076e350. All39 owned-path diffs remain byte-identical to the reviewed change, SHA256858c9dcef70009032407162a9338dbd3d01702d1620c2b5786d5bad5726170cc. integration-identity.json records the scope. The PR is stacked on codex/nuke-prep-speed while core568 has a main hold; it must not promote held core through a side PR. Already-main579 accounting changes also appear until that base catches up.

## Checks

| Source | Check | Result |
|---|---|---|
| Initial2b | Full build +devtest compile |1,760 units, zero failures/errors/skips;4m44s |
| Initial2b | Real LSS0.20 +all selected DH/LSS GameTests |23 required pass;2m58s |
| Integrated5e | Jar +devtest compile +existing packet/DH/map/depth units |77 units, zero failures/errors/skips;11s |
| Integrated5e | Same real LSS0.20 DH/LSS GameTests |23 required pass;3m1s |
| Integrated5e | Required Итог41824520213 |Green; tools/pr_ci completed0 |

Both owned wrappers, the independent review and all owned CI waiters ended before this report. No own client/server/GPU was launched. The integrated jar SHA256 is540c7396f4f01c7ca6a000f56521c2aa3b05dda5bbeb747ae3a6cb0f2c8ba7a8. Entry inspection confirms retired publisher/queue classes are absent and the three legacy replay payload classes remain. All shipped golden resources are unchanged. No binary/world/cache is published here; original XML remains privately retained with hashes and counts, with integrated selected XML included.

## Remaining real-run gate

The current LSS tests establish save-hook invalidation, dirty-notice/store behavior and durable saved-zone source changes. Some fixtures explicitly deposit replacement store rows. They do NOT exercise actual normal client requests, decoding, Voxy storage/meshing or visible convergence. A missing stale store row alone is not a received or displayed revision.

The real server/client trial must follow genuinely changed remote source through saving, ordinary LSS re-request/receipt and Voxy database/rendered terrain, including an existing warm cache and later join. Measure convergence delay and check that buildings do not stay old permanently. Root owns that trial; no result is invented here and no strict-front contract is revived. Nearby wave timing/physical changes remain server authority and require the integrated real-run check too.

This component's checks/review are complete, but its PR remains a draft under the core hold and this runtime gate. No release or full B3 readiness is claimed. No new GPU/performance/screenshot run was requested; render code and visual design are unchanged. Root's diagnostic standard-path mode precedes the production removal; final composition must keep these deleted classes removed and put any natural-LSS telemetry in an independent public consumer.
