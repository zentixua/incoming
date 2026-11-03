# MT-B1 evidence

Evidence only; orphan lineage `look-gate/mt-b1`. Production implementation is PR #557, commit 638c18597de8932728aaf8affa378e2fd2d7d632.

Start with `MT-B1-gate.md`. The staging/activation gate is CLOSED. Public-method inventories are from the actual installed jars identified by `gate-jars.json`. `GateTileProbeTest.java` exercises Incoming's real server viewer with Netty backpressure; its single passing test proves bounded transport, not Voxy renderer activation. It is an experimental fixture, intentionally outside the production PR.

Lifecycle regression sources are in the production PR. `checks.json` summarizes their build/GameTest/CI evidence; raw logs preserve context. The first full build passed. The second build's daemon disappeared without a test assertion; `final-build.log` preserves this infrastructure failure and the final retry uses a single-use daemon. No player world or GPU was used. All GameTest fixtures are disposable local test worlds, moved aside before reruns.

No frame, total heap, dense-city MSPT or 1 Mt performance claim is made. No compiled jars or world data are redistributed. `archive.py` records SHA-256 for every artifact and verifies the committed bytes without checking out the evidence branch.

The first CI head failed only `queuedFarTaskPromoted`: its old assertion required the removed future to stay unfinished. The final test requires cancellation and verifies exactly one execution while preserving queue-order checks. `ci-gametest5.log` preserves that initial diagnostic; the final focused run includes the corrected test. LSS Modrinth IDs: `ydDRzPcM` = 0.19.0, `jxJ4956L` = 0.20.0 (verified from each cached jar metadata). The 45-test extended local run uses 0.19; the focused nine-test run explicitly uses 0.20; CI checks both.
