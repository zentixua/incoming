# Final CI result — not accepted

Exact75aaa3c11906ccb4098540de2c90522ef5e92436; run41858550142: FAILURE, required Итог red. tools/pr_ci.sh592 returned1 and was awaited. Earlier92122 run41857539672 was superseded/cancelled by the final push.

- GameTest4/6, job126675204338: server crash in nuke_source_progress/disjointColdWindowsFinishWithoutExternalRefund. IllegalStateException “Ruin inputs already detached”. Stack: RuinInputs.acquire:13 → RuinWorkers.submit:388 → RuinContext.submitPlanWorker:911 → RuinSourceProgressGameTests.lambda$disjointColdWindowsFinishWithoutExternalRefund$4:115.
- GameTest6/6, job126675204510: exactly1required test failed: cachedFallbackPublicationFailureCannotBecomeReady (nuke_source_cached_failure), “Injected source snapshot publication failure”; RuinSourceFailureGameTests.java:105. Other162tests in that shard passed.
- Other4GameTest shards, both LSS integrations, both unit-test groups, build, store documentation and clean NeoForge installation passed.

These producer/test files have no changes in own7cb9d1cb..75aaa diff. LEAD2 instructed that inherited producer/union failures remain with MT-S2 and owns their correction/final combined CI. No overlapping source fix or fixture relaxation performed here. This evidence does not establish a fully green integration or actual100/1000kt runtime acceptance.
