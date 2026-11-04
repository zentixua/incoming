# Near-first launch preparation evidence

Source: `4d89b5b113a53b3cfe8822092e1813750516c9e9..4f1f18115a16d08ff081592ced823b2e56e9b14a`, stacked PR585. The accepted PR568 base stays unchanged.

The initial source compiled and passed 1800 unit tests, but the new near/bind integration failed both locally and in CI. Raw failures are retained. The independent first source review identified collector loss on unload, failed window sources credited as absence, and an uncollected failed near task. Corrections also distinguish ready chunks from FULL data retained during generation/unload: the original test repeatedly moved from READY82/82 to PLANNING82/163 at bind. The corrected focused union passed23/23 in12.80s; that run predates the additional delayed actual page-write discriminator. The final validation chronology below distinguishes each source and does not substitute earlier green checks for a later head.

The full physical layout, plan math, far traversal, partial handoff and settlement remain active. The launch-critical membership is the target plus ready loaded chunks within ruin radius. Only its plan receipts block launch; failed sources/global memory still deny admission. READY is live and does not persist over restart. No full1Mt preparation time, total heap bound, near-wave runtime or natural LSS convergence is inferred from this evidence.

## Offline ledger audit

`ledger_audit.py` is a bounded stdlib reader pinned to the PR568 disk schema. Run it only on immutable clean-stopped disposable-copy cuts, selecting each dimension data directory explicitly:

```sh
python3 ledger_audit.py CUT/data/incoming_ruin_matter.dat CUT/data/incoming_crater_matter.dat CUT/data/incoming_ruin_settle.dat CUT/data/incoming_nuclear.dat > report.json
```

The optional fourth file contains the crater traversal cursor; it is distinct from crater column checkpoints and settlement cursors. Disk moving motion is145 bytes big-endian (live compact pages use157). The auditor emits nonempty moving/stranded/material/support/cargo/progress summaries, typed content digests, file hashes and conservation residuals. Exit0 is supported/balanced,1 malformed or unbalanced,2 unsupported. Missing files never imply empty ownership. Eight substantive generated-fixture tests and independent review passed; actual Crafty cuts were not acquired by this artifact task.

Unchanged file hashes before any new write do not prove successful restore. Compare a post-import clean save with elapsed game ticks and logs; movement/new removals may legitimately change pending rows while conservation remains required. Archive references alone do not prove archive page availability; preserve/hash those files separately. Ledger balance does not prove the actual world/inventory mutation or cross-file crash atomicity. No world/jar/private raw NBT is included here.

Final exact4f local validation:1800 units/0failures/errors/skips, fullbuild3m33, all66 core+near GameTests pass in1.176min (Gradle1m25), wrapper0. Exact PR585 required Итог is green; pr_ci watcher ended0. Two independent source rounds and the same second-round composition continuation are complete; later author corrections and their checks are recorded below.

## Terminal ownership corrections and current production checkpoint

The second source round found two more terminal paths: a todo overtaken by another planner can orphan its late window completion, and instance replacement could hide a completed failed/cancelled future. Sources now drain independently under the existing budget, hold READY while pending, and observe terminal outcome before stale-instance retry. Per-preparation status includes the already-global pending-window reservation. New actual-input/payload/error/cancellation/different-instance and success controls passed; no native world-holder reload is claimed by the instance-boundary fixture.

Partial handoff also explicitly supersedes flight-window callbacks/cursors. A still-outstanding callback retains its reservation until it finishes; its invalidated payload cannot contaminate the scar owner. Plans, workers, snapshots and pages keep their original ownership. The real nonempty handoff test proves this1-to0 pending ownership transition.

Current frozen production10d2b145f passed19/19near/source/stale/late/reload/handoff controls in11.90s (Gradle31s), ownedwrapper0. Prior7c fullbuild1800units passed in3m57, but its73-case union had one existing preparation-release-deadline fixture failure, and standaloneCI failed only an existing asynchronous DH section-gap fixture. Raw failures are preserved. The root's separate cleanLSS/control composition83d/7c later passed153affectedGT and all exactCI41831615353 checks; that result is not relabeled as a10d run. Final standalone/component CI and actual100/1000kt runtime gates remain pending. Test-only anti-flake corrections are owned separately.
