# Per-chunk CPU probe

All times are local elapsed milliseconds on the laptop, not CI thresholds. Each row is one live 16×16 chunk. Snapshot, blast, collapse, plan completion and commit are timed separately. Light is asynchronous barrier latency (not light-thread CPU). Encoding is a real full chunk-with-light packet into a buffer; network delivery/LOD ingestion is not included.

Three consecutive rebuilds of each fixture share the same location. Later runs include prior deposited rubble outside the rebuilt footprint; they are workload samples, not identical-state repetitions. The first hall has negligible failure and is excluded from the dense-frame summary. Setup and JVM startup are excluded. An independent build was running during the final branch probe; these are local observations, not isolated hardware capacity estimates.

| Dense-frame stage (samples 1,2,4,5,7,8) | main median | branch median | branch p95* | branch max |
|---|---:|---:|---:|---:|
| snapshotMs | 1.31 | 0.91 | 2.20 | 2.20 |
| blastMs | 27.96 | 38.39 | 115.73 | 115.73 |
| collapseMs | 16.04 | 27.29 | 82.72 | 82.72 |
| finishMs | 8.68 | 7.32 | 23.01 | 23.01 |
| applyMs | 1.91 | 7.68 | 11.41 | 11.41 |
| lightBarrierMs | 1.69 | 4.31 | 11.21 | 11.21 |
| encodeMs | 0.50 | 0.42 | 0.74 | 0.74 |

*Nearest-rank p95 with only six dense samples equals the maximum; it is not a population percentile estimate.

| Following 240 game ticks | total material work ms | calls | maximum call ms |
|---|---:|---:|---:|
| Sample 0 | 1.35 | 831 | 0.03 |
| Sample 1 | 102.75 | 720 | 8.11 |
| Sample 2 | 110.83 | 720 | 3.02 |
| Sample 3 | 12.08 | 720 | 0.11 |
| Sample 4 | 102.12 | 720 | 2.58 |
| Sample 5 | 120.78 | 720 | 2.63 |
| Sample 6 | 12.30 | 720 | 0.12 |
| Sample 7 | 114.96 | 720 | 3.24 |
| Sample 8 | 142.18 | 720 | 2.69 |

The branch transports and deposits thousands of actual blocks, while main produces a small decorative fraction. Post-commit work is additional to the stage table. The fixture sits in the GameTest void; material leaving its platform is retained in the outside-height reservoir. Pending counters include continuations from earlier samples. They are not a claim that every pending record settled in this probe.

Source: [PipelineBench.java](PipelineBench.java). Baseline: `292f3153636952a8168da493e717952ec680c396`. Branch: `adc1c354489a9a18a16c0be2f0437bf6578ce23f`. The disposable branch adds only a `System.nanoTime` wrapper around `RuinMatter.work`; see `pipeline-instrumentation.patch`. Raw logs and JSON are beside this report.

The subsequent commits only exclude unchanged crater glazing and correct the sign of carried scorch rotation. This probe uses an airburst field and no populated flash-scorch prepass, so those paths are not exercised. Their targeted GameTests are in `checks/`; this timing sample is not a maximum-populated-scorch benchmark.
