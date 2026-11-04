# MT-LIFE solver input lifetime evidence

Checked source: `8c521e51edf5bb49fdf7bfec485544376cccca03` on `codex/nuke-context-lifetime`, based on integrated OPT `819a91e73`.
Created 2026-11-04T10:35:43.199382+00:00.

`RuinInputs` transfers the existing context estimate to scalar ownership before detachment. Actual worker exit or removal of the queued callable refunds it. Promotion transfers the same exit callback. Replaced/removed snapshots are conservatively retained in the attached estimate until all actual context workers exit. Numeric static ownership has no context/world references and is not reset on a new pool lifetime. Ruin and flash-layer submissions use the same owner. Existing NuclearWorld remove/sweep/clear paths already call cancelTasks, so no NuclearWorld edit was needed.

The alternative of keeping a preparation attached only while its task map is nonempty misses forgotten tasks, world-context eviction and flash work outside that map. Clearing shadow/fracture caches would change the inputs of a running Grid. The selected owner preserves those inputs and the existing known estimate. Snapshot retirement can temporarily overestimate shared inputs while workers overlap; the last actual consumer resets it. This does not prove a whole-JVM or complete nuclear heap bound. Worker workspace estimates and META layout accounting are unchanged.

## Executed checks

Through tools/laptop_job.sh, own mt-life-inputs job, explicit SESSION_TREES and unique INCOMING_GPU_OWNER, after the actual C2 quiet release and before L2:

- `./gradlew --daemon --console=plain test --tests ua.zentix.incoming.nuclear.world.RuinInputsTest --tests ua.zentix.incoming.nuclear.world.RuinWorkersTest compileDevtestJava`: PASS, 14 s. 3 new input lifetime +4 existing worker units, zero failures/errors. Java and devtest compile pass.
- `./gradlew --console=plain runGameTestServer '-PgametestOnly=nuke_inputs_.*'`: ALL 5 required tests PASS, 18 s.
- Whole wrapper exit0 was awaited. No full build, GPU/client or Crafty operation. No helper PR/CI watcher or third core review. Root owns final integrated build/CI and acceptance under the completed second independent review.

GameTests populate real snapshots, shadow heights and a fracture, capture that Grid in an actual RuinWorkers callable and install its real future in the preparation task map. Registered cancel/drain, explicit prep clear, forgotten task followed by world context removal, interrupted expiry and queued promotion/cancellation are covered. Running callable exceptions are recorded independently because a cancelled Future hides them. The callable consumes its original Grid after the gate opens, including real Collapse and plan completion.

Four running routes each retain 342240 known bytes after Future.isDone=true with actualFinished=false. Another dimension has zero available settlement room while gated. After actual execution exits, detached charge is exactly0 and room reopens to171120 bytes. Repeated cancelTasks does not refund again. The promoted queued callable never runs and immediately releases its charge.

The headroom-filling material reservation in the fixture is only a deterministic admission boundary; it is not a substitute for the populated Grid payload. Logs and JUnit XML are raw; source.diff is the implementation and regressions. No full1Mt, total heap, player MSPT or prep throughput acceptance is claimed.
