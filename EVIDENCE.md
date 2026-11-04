# RuinPlan material storage correction (MT-MAT)

Source: `codex/nuke-plan-material` at `244f96185` (production diff: RuinPlan, RetainedPlans and the new RuinPlanStorageGameTests only). This is the delegated F1 correction of independent review RMT-S2-OPT at daf620c74, not integrated/release acceptance.

RuinPlan schema 2 stores and requires typed structural material candidates; detach carries the same immutable payload. Old/incomplete pages are rejected. MT-S2 owns both NuclearPrep cache-opening paths and has separately moved them to plans-v2 in f96bb7795. RetainedPlans keeps the production 16 MiB resident target; a package-private constructor permits a zero-target regression through the actual writer, eviction and demand reader.

## Checks

- `compileJava compileDevtestJava --max-workers=2`: PASS, 17 seconds, wrapper exit 0.
- `runGameTestServer -PgametestOnly=nuke_plan_fields|nuke_plan_material|nuke_store_.* --max-workers=2`: 8/8 required tests PASS, 4.676 seconds of tests, 28 seconds Gradle, wrapper exit 0.
- `./gradlew --daemon build compileDevtestJava --max-workers=2`: PASS, 1788 unit tests, 0 failures, 0 errors, 0 skipped; 3 minutes 54 seconds, wrapper exit 0.

All checks use tools/laptop_job.sh with explicit SESSION_TREES and INCOMING_GPU_OWNER. Heavy checks started after MT-L2's explicit quiet release; full build followed MT-BUF's completed serialized slot. No GPU, player client, remote/protected server, release or production merge.

## Ownership regression

Every RuinPlan instance field is classified as authoritative payload or reconstructed/runtime data. Synthetic nonempty fields are compared directly across detach/codec; version-1, absent-material and wrong-tag-type pages must fail. Process-local hashes are rebuilt and derived lookup behavior is checked.

The real solver fixture removes masonry and a live barrel containing 7 diamonds and 11 iron ingots. Its original, detached, direct codec and actual RetainedPlans spill/read plans are independently applied to the same reset source fixture. Every route produces exactly the observed 64 released records, volume 26.199999999999974 m³, mass 58,560 kg (57,960 masonry + 600 barrel), and 64 pending support sources. Before save, aggregate and per-type mass/volume, support, FIFO trajectories and exact live block-entity contents match. Each ledger goes through both an in-memory load and the real asynchronous SavedData file-wrapper/streaming save, then is read from disk and loaded again. Actual spill I/O reservations and consumed-plan bytes return to zero.

The initial fixture failure was an over-strict comparison of unordered ledger map rows across reload, not material loss. The corrected comparator sorts only materials/support_originals/cold_edges map rows; exact moving/support-source FIFO order and all cargo/trajectory fields remain compared. The initial raw failure is retained for provenance.

Fixture results are not a proof of whole-zone heap bounds, 1 Mt preparation throughput, real-map readiness, look or release acceptance. Fresh final integrated review and PR #568 CI belong to MT-S2; this subtask launches no redundant review or separate PR.
