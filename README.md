> Final-head follow-up completed (4 November): [new registered apply-plus-support cost, B5 memory deferral, and fresh f92dabaaa/main paired frames](final-head-pairs/README.md). The earlier capture status below is historical.

> Review correction (4 November): the original PipelineBench did not register its synthetic events. Its MTMAT rows exclude live support reconciliation. See [registered continuation measurements and B1–B4 fixes](review-fixes/README.md). Earlier frames/source identity below belong to reviewed head ad1f7c064, not the later fixes.

# MT-B2 structural material and support

Production PR: https://github.com/zentixua/incoming/pull/560. Baseline: `292f3153636952a8168da493e717952ec680c396`. The production source and capture hashes are recorded in `source-identity.json`. No merge or release is authorized by this evidence.

## What already exists

| Existing implementation | Reuse and boundary |
|---|---|
| [Orbital Laser](https://github.com/Stink-O/orbital-laser), Hurricane and Sinkhole, source `8ae6409af90335e461ba473c92a3e56798f30d38` | Adopt material continuity and the order source removal → movement → rest. No source was copied. Its Rust/Horizon runtime and scripted vortex/sinkhole motion are not Minecraft nuclear physics. |
| [Block Physics](https://modrinth.com/mod/block-physics), BlockCollapse / Simple Block Physics | General block gravity, not ownership of material released by Incoming's pulse or its staged chunk commits. No new required mod. |
| Incoming's penetration data map, collision shapes and snapshot workers | Reuse represented thickness and geometry, rather than introduce another hand-maintained map-specific material table. |
| Incoming's block-textured fragments and nuclear dust | Keep the existing bounded cosmetic renderer. It has no additional authoritative material mass. No nuke shader or sound changes. |

## Implementation

The plan records structural source positions, not a decorative finished pile. Successful replacement transfers the actual surviving block state, contents, solid volume and mass into saved ownership. A triangular dynamic-pressure pulse accelerates loose material relative to the air while gravity acts. Placement keeps the block type and occurs only after travel/collision; logs rotate horizontally, and loose piles spill to lower neighboring space. Leaves and shattered glazing enter a dispersed fine-material reservoir. Per-type and aggregate balances include deposited, dispersed and still-pending material. These are cumulative transfers across the ownership boundary, not a census of every block currently in the world. Charred face tiers are saved and rotate with log axes; settled marks merge once per chunk/tick. Below-world material is a saved dormant reservoir, not a repeated load request or silent deletion.

Pressure response uses the elastic triangular-pulse SDOF solution, including motion after pressure ends. The existing long-pulse pressure proxies remain the capacities; represented thickness, collision volume and inferred span affect response. Connection strain and drag are explicitly estimated. Rooted connected timber with a crown includes branches and trunks above 24 blocks. Actual removed support contacts seed a budgeted continuation across chunk borders; a missing boundary is pending, never a foundation.

## Paired sandbox evidence

[Paired frames and full sequences](paired-frames.md).

Both copies use `instrument_sandbox.py`: two 25×25 roofs (stone / white concrete) on five-block glass walls, a bare 16-block timber column and a 30-block tree. Camera `85 28 25 10`, 15 kt airburst, near distances 2835 m and 1000 m. The ordinary muted virtual sandbox uses its test wave speed of one sound speed; this is not a full-pack Iris or LOD test.

Capture ages: −0.25, +0.1, +0.5, +1, +2, +3, +6 and +10 seconds from wave arrival; six-second WAV. **At +8 s the disposable fixture clears client effects only. The +10 s image is a diagnostic view of authoritative geometry, not a claim about normal dust visibility.** Earlier frames and WAV retain ordinary effects. No server blocks are cleared by this diagnostic operation. A separate 15 kt ground-burst view records the crater through +30 s; its camera and timing are listed with the pairs.

The baseline leaves a floating stone patch and upright bare timber. With material transfer, the roof material and timber settle at the base rather than vanish. The weak-zone tree stays standing; the strong-zone tree falls. Existing cosmetic fragments show the removed block textures during the dust front; they are representative fragments, not a one-to-one rendering of every server-owned moving record.

## CPU and validation

[Per-chunk pipeline measurements](pipeline-results.md) include live snapshot extraction, blast, collapse, plan completion, commit, asynchronous lighting barrier and actual packet encoding. Raw logs and the disposable probe are retained. The table reports median, nearest-rank p95 and maximum for the measured implementation, with its exact source commit and post-commit material work separately reported. The two later path-specific corrections and the probe’s scorch-prepass limitation are disclosed in that report. These are local workload samples, not an arbitrary-world throughput guarantee. Post-commit material work is separately reported in `MTMAT` rows.

Regression coverage includes floating roofs, bare timber, tall/branched trees, protected blocks, partial contact faces, support over more than three chunks, containers, ordered re-removal/retry, clean save/reload, material-specific balances, stable piles, dormant outside-height material, rotated scorch after save, crater source stock, protected bowls and idempotent crater resume. Response laws have pure unit tests. The complete local build passed 1748 unit tests. The structural/ruin set passed 57 GameTests, the material/scorch/crater set passed 19, and the later unchanged-glazing and signed-scorch fixes passed their targeted cases. Final-head CI, including all six GameTest shards and both LSS variants, is green. Logs and source identities are under `checks/` and linked in the PR.

## Model and evidence limits

This is a reduced block-scale model, not a calibrated engineering simulation of reinforcement, individual joints or nonlinear fracture. The existing span rules remain the local capacity model. Coarse pile repose is a one-block estimate. A separate crater ledger partitions actual source material into rim deposits, exported ejecta and thermal transformation. The rim consumes source stock with the same block state; original ground/soil and completed columns persist across a clean restart. The existing crater shape and nuclear dust presentation remain. Carried scorch keeps face tiers; silhouette shadow masks remain attached to their original surfaces. Normal save/reload is covered; atomic recovery from power loss across region files and SavedData is not claimed. No 100/1000 kt, whole-zone memory/service-rate or staged LOD guarantee is inferred from these tests. The yield ceiling and default remain 15 kt.

The earlier connectivity-only draft is archived under `history-partial/` and the preceding evidence commit. Its B1-dependent stop is superseded by Artem's instruction: the closed LOD gate does not block these B2 changes. An independent fresh-session review remains pending; this draft must not be merged by this session.
