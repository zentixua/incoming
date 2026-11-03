# MT-B2 partial repair evidence

This evidence does not close MT-report §33.2 B2. See [OPEN-GATES.md](OPEN-GATES.md) for the exact missing implementation and the B1 staging gate.

## Paired sandbox

Baseline: `origin/main` at `341c2ea6fdda1a9ed6f941dc00c5065b1353bc7e`.
Captured B2 production code: `eeb19e219`. Final PR head after rebasing onto B1 (`292f31536`): `8226e88eead475734dec6e22c6454bfc916e29c3`. `source-identity.json` verifies that all three changed production classes are byte-identical to the captured files. The visual worktree started at `58ad02bc5` and received the exact final `Blast.java` before the `final-*` captures; the other production files are identical.

Both disposable worktrees use the same `instrument_sandbox.py`: two 25×25 roofs (stone and concrete) on five-block-high glass walls, a bare 16-block timber column, and a 30-block trunk with a leaf crown. The camera, load and timestamps are identical. Sandbox sites have matching block-threshold hashes and fixed detonation ID/time; see each `capture.json` for actual coordinates and image ages.

Commands (replace shot-name prefix `main` with `final` for the paired result):

```sh
INCOMING_GPU_OWNER=MT-B2 tools/sandbox.sh start --wait
INCOMING_GPU_OWNER=MT-B2 tools/sandbox.sh shot main-roof 'nuke 15 air near 2835' --cam '85 28 25 10' --at -0.25,0.1,1,3,10 --wav 4
INCOMING_GPU_OWNER=MT-B2 tools/sandbox.sh shot main-tree 'nuke 15 air near 1000' --cam '85 28 25 10' --at -0.25,0.1,1,3,10 --wav 4
INCOMING_GPU_OWNER=MT-B2 tools/sandbox.sh stop
INCOMING_GPU_OWNER=MT-B2 tools/gpu_lease.sh release
```

Times are relative to arrival of the wave at the fixtures. This is the ordinary muted virtual sandbox, without Iris, at its explicit test wave speed of 1× sound. It is not a full-pack, real-time-scale or LOD proof.

**The +10 s images are diagnostic geometry views:** the disposable instrumentation clears client effect state after +8 s, exposing the authoritative blocks. The earlier images and four-second WAV use the ordinary effect. The final images must not be presented as ordinary dust visibility or a motion-quality acceptance. No server blocks are cleared by that diagnostic operation.

## Snapshot diagnostic

`install_bench.py` adapts the retained Stage A `MtRuinBench` to 15 kt only. Each solver receives the same immutable synthetic geometry and load, and the probe decodes final chunk plans over the central 3×3 chunks. Three repetitions preserve the original diagnostic methodology. Exported voxel tables record before/after blocks. Outside-chunk tree transfers are counted separately and are not applied by this probe.

The measurements exclude snapshot construction, world commit, lighting, encoding and LOD receipt. Other laptop jobs can overlap. They are local diagnostic timings, **not full-pipeline CPU/MSPT, bounded-memory or deadline acceptance**. Connectivity is a six-neighbor diagnostic, not a pressure/impulse element model. No results for a higher yield are claimed.

## Validation

Initial regression run failed on main for the floating roof, bare timber and tall-tree crown. The new tests also cover a translated chunk seam, a preexisting floating roof beside an actually collapsing pedestal, and protected/unknown hard materials. Thermal tests retain their scorch/save/packet/layer assertions without relying on timber immunity.

Final build, GameTest and CI results are recorded in the PR and accompanying logs. The independent fresh-session review is deliberately still pending; this draft is not authorized to merge.

Snapshot output: [paired results](snapshot-results.md) and [raw counters](snapshot-results.json). All three repetitions agree on block counts. The stone roof changes from 49 newly floating blocks to zero; bare timber from 16 surviving logs to zero; the tall tree from zero recognized trunks to one. The missing rubble remains unresolved.

Local validation: 1740 unit tests passed with no skipped tests; 44 of 45 affected GameTests passed in `final-checks.log`, then the corrected exposed-pedestal preservation fixture passed in `anchor-check.log`. Its earlier failure and both thermal-fixture corrections are retained in the logs. CI must independently pass the final head.

## Visual findings

At 2835 m, main leaves a small floating stone roof patch and both upright trunks. The final branch removes the roof patch and the bare timber column, while the recognized tree remains standing below its whole-tree toppling load. At 1000 m, main keeps both upright trunks; the final branch leaves the recognized 30-block trunk horizontal downrange. The thin roofs still leave no material pile: these images do not close the missing-rubble case or demonstrate staged falling motion.

The first intermediate capture exposed an error: recognized trees below their whole-tree threshold fell through the new timber-member path. `rejected-intermediate/` retains that rejected result. The final implementation recognizes trees before checking load or `nukeTreeFall` and excludes surviving trunks from every fracture round. Both new regressions first failed, then all eight structural fixtures passed in `tree-rule-check.log`. The final paired images are exclusively under `sandbox/main-*` and `sandbox/final-*`.

No renderer/FX code was changed; this is not a GPU performance proof. Full causal motion, conserved rubble, and the complete B2 gate remain open. All four WAV files contain four seconds of stereo PCM at the rates recorded in `audio-metadata.json`; no filtering or host playback was used.

The complete pre-rebase build passed all 1740 unit tests and all 47 affected GameTests (`checks/verified-head.log`). Rebase preserves the B1 lifecycle changes; the integration run and final-head CI are recorded separately. The starting-main capture remains at the explicitly named baseline rather than claiming a new capture of B1.

After rebasing onto B1, all 47 affected GameTests passed again (`checks/rebased-check.log`). [PR #560](https://github.com/zentixua/incoming/pull/560) carries the CI result for the final head and the subsequent independent review.
