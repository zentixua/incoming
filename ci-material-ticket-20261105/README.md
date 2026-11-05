# Material-ticket fixture regression — 5 November 2026

The `nuke_stripes` test completed its chunk-loading, glass-removal and scar assertions while its saved material remained active. Ordinary nuclear clear unloaded the current material ticket but preserved that ledger, which acquired another ticket during a later salvo test. The fixture now registers its complete ticket/nuclear teardown before acquisition and retires its material on successful or failed completion. Gameplay clear semantics and the salvo assertions/deadlines are unchanged.

Source [PR605](https://github.com/zentixua/incoming/pull/605): `f506c1eae6e868f7a6a6256caac581d6f6534a38` → `2e9717de77fa0a2d19a1481ff544ec06628eddaf`. This is a two-file test/rule change, stacked on PR604.

| Control | Original fixture | Corrected fixture |
|---|---|---|
| Recorded prefix and grid | 15 batches, `13777285,-59,-1659328` | Same batches, order and grid |
| Material entering `nuke_stripes` | None pending | None pending |
| Material entering the salvo test | 4,628 pending and a material ticket | Zero pending; no held area |
| Salvo result | All rockets gone on fixture tick 329; areas never reported released; same `[861100,-103705]` material ticket as original CI | Areas released 329 ticks after launch; release queue empty immediately |
| Prefix result | 14 pass, one required failure | All 15 pass |

The original failure at `13777699,-60,-1659258` is from [CI41966271699](https://github.com/zentixua/incoming/actions/runs/41966271699). Its exact 15-batch prefix reproduces the failure, ticket and projectile completion. The native replay is a controlled same-origin/order experiment; worker timing and the complete world/RNG history are not identical across runs.

Final normal validation, without the private replay init script: build passed with 1,850 unit tests (zero failures/errors/skips), followed by both required `nuke_stripes|salvo_busy_tickets` GameTests. The [independent source review](independent-review.md) found no serious issue. Final [CI41973940019](https://github.com/zentixua/incoming/actions/runs/41973940019) succeeded on the PR composition with base `d2f3854df0221d1a69cbd24c869f881ea9b1dafc`; its `tested-tree-bad2d7a97842d58ab7ad1cb1c47ee6eea10fc8cb` artifact matches the locally computed merge tree.

Raw logs, input pins, source/harness hashes and limits are in [input.json](input.json) and [raw](raw). Personal replay launchers and worlds remain outside the evidence branch. The native logs do not embed a compiled source SHA; source provenance comes from the clean checkout and explicit command sequence. A batch after-hook runs before the later `afterTest` listener, so its immediate material sample is not an after-cleanup assertion; the subsequent salvo entry and completion show the relevant result.

This component does not establish actual 1 Mt/100 kt, save/rejoin or native LOD readiness. The separate earlier `nuke_send_hold` failure from CI41972699466 remains under independent diagnosis; this later green run does not erase that witness.
