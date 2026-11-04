# CI-ASTRA evidence — 4 November 2026

Five original MAT2 CI failures, exact source provenance and bounded local controls. This is diagnostic evidence, not integrated acceptance.

- CI591 head dec508cc2e444f0968848faafd385b7e750feb8b and actual merge a7586ff7bf241e5d38f0a582087ba3a0f7b0d5d2 share tree b6d6305ed58460d449842f141ad3b8f3bc53e428.
- RMAT2-ci-shard{1,2,3,4,6}.log are original raw logs. failed-cases.json records failure assertions and batch order.
- dec508-probe.log / old-probe.patch / CiAstraProbe.java record four RED input-lifetime fixtures. hasBlast was false before cancellation as well as after it: the background solver does not populate the server-only cache.
- 23dd-green.log / green-probe.patch record the same four GREEN fixtures after only existing 23dd16fdcfd05cb07e41baa88a200591fd74624a. Production unchanged. Full field comparisons and actual callable ownership are recorded; this pair does not exercise the queued route or prove nonempty fractures/full JVM heap.
- dec508-shard6-probe.log / shard6-probe.patch / CiAstraProbe-prefix.java record ONE 148/148 passing old shard6 reproduction. All99 batch names/order match, including pipeline at batch51. Coordinates, chunk alignment and wall scheduling differ; pipeline passed712ticks. Historical glass cause remains unknown. Sparse observation can perturb worker scheduling.
- 593-shard6.log is the subsequent integrated run41860163812: pipeline passes, source-progress fixture crashes. That distinct issue and its native-epoch regression belong to MT-S3; see its component handoff.
- local-artifacts.json records selected compiled/JAR hashes. Restored clean old source does not describe the leftover instrumented compiled classes. Original CI release-jar artifact13337333904 ZIP SHA256 2ecc780237984faf6ddc185d2b7c173c958f67ef5f2ec5bd7a75450af788bfa1 is separate from GameTest development classes.
- terminal-observer.patch is the final devtest-only candidate against integrated f8481f253c9bed41c886fecc3137eeacc1617440. Original assertions/deadline unchanged. Tick1200 snapshot uses passive getters; a last-tick success can follow it. Logging itself grants worker wall time and cannot prove the historical race fixed.
- RCI-ASTRA-review.md and RCI-TERM-review.md are independent Astra/high reviews, with their own stated scope and limits.

SHA256.json hashes every evidence file except itself. Integration/required CI/main merge remain LEAD2-owned. No runtime production fix is claimed here.

Final aa6e73 validation: build SUCCESS6m25s,1840 unit tests/0 failures/0 errors/0 skipped, original nuke_pipeline1/1 PASS1.370s. Wrapper exit0 awaited. Deadline observer did not execute on this successful case; source review checks its call paths. final-unit-results.json preserves per-suite counts/hashes.
