# Worker admission and actual-exit ownership evidence

Final component: `ad0e786fb22c29940bfc794632399141b569b2dd`, PR #588, base `097efc4ee419f5e4b5572f2fecc7e80ce04dd822`.

## Result and scope

New/reborn workers and per-thread height upgrades receive a server-thread grant from current shared resident ownership. Captured I/O executors queue without world traversal or stale grants. Numeric workspace credits remain through task completion, cancelled futures, idle residency and old shutdown survivors until their thread wrappers exit. Existing material admission continues to use resident ownership rather than permanently reserving an idle configured pool.

The additive actual-exit observer composes with input ownership and survives promotion, queued removal and running cancellation. Observer faults are logged and isolated so FutureTask clears its real private Callable and shutdown releases the entire drained queue. Synchronous diagnostic waits now service the same admission while the normal server tick is blocked. Their deadlines are unchanged.

This component establishes the tested workspace/task lifetime behavior. It does not establish all-producer 512 MiB containment, full JVM heap, real-map performance or 100/1000 kt acceptance. Snapshot/result publication and combined actual trials remain separately owned. No main merge or release is performed by this helper.

## Validation

- Final CI `41853072881`, exact `ad0e786fb`: SUCCESS, required Итог green. Raw summary and waiter receipt are included.
- Worker model at `153e4f86a`: full build 1,785 units, 13 targeted GameTests, two fresh independent Sol xhigh review rounds. RWORKER2 resolves the first height/stop finding; no serious remaining component finding.
- Final callback model at `87d412c15`: full build 1,787 units, zero failures/errors/skips; all 15 targeted GameTests. Two independent callback-only review rounds; RWORKERHOOK2 resolves F1 and establishes no serious remaining blocker. This separate scope did not reopen the earlier worker model.
- Final compatibility deltas: 8 selected near/worker/prelaunch/background GameTests pass, plus the actual cold diagnostic case `nuke_edge_apply` and disk-plan parity, 2/2. The far-read gate is copied from accepted `834382f68`; the two diagnostic pump calls/comment are copied from accepted `04b196307`. Earlier hold-centre fixture matches accepted `6eb589d59` exactly. Exact configured/pool size, future policy, idle expiry and READY assertions remain.
- All local heavy checks use the laptop-job wrapper, 4 processors, 2 GiB, no daemon reuse. No GPU/client/Crafty/desktop/player-world action, worlds or jars are in this archive.

## Discriminating controls

At height 384 one workspace estimate is 13,271,040 B. The numeric non-worker base 417,653,525 B admits eight workers, totaling 523,821,845 B. A ninth would total 537,092,885 B against 536,870,912 B. The unpressured owned-pool control admits ten. Actual shared-owner GameTest also fills the cap, enqueues a captured callback at zero room, frees exactly one workspace and observes tick-driven completion.

- Base097 plus the new worker GameTest fails specifically with `workspace producer exceeded global cap`.
- The new startup/height-stop units both fail on the pre-correction e70 source and pass on the corrected source.
- The new queued-callable and shutdown-drain fault fixtures both fail against pre-correction172. One reads the actual FutureTask superclass field; the other proves a later drained future is abandoned. Both pass on87. An expected-red wrapper exit0 denotes successful negative control, not passing underlying tests.

## Files and provenance

`MT-WORKER-final2.log` and `unit-summary.json` retain the153 full-build receipt. The unprefixed worker XML belongs to that checkpoint. `MT-WORKER-hook-fault-final.log`, `87-unit-summary.json` and `87-TEST-*` retain the exact87 receipt. Review reports pin their own scopes; their earlier failed findings remain preserved with their final corrective reviews.

Earlier failed CI logs document the direct-prestart, near-window, far-cursor fixture and synchronous-helper seams. The final CI summary supersedes their status. Source estimates and sampled/runtime heap must not be conflated; this archive contains no combined real-runtime acceptance result.
