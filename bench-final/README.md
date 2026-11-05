# Final df40 standard cost gate

Source df40a1e369306d21cad94677aadd99e77e235eba; CI JAR SHA2563cb9666c52723f93d702b19ff96a8763954a4bb73091fa4c3a3d9203f5221685. Baseline greenmain7bdb5b2c12c9c387aa346046250a9fd62fdf3990. One standard100-strike none-pack A1/B/A2 with warmup, all3runs valid and100/100 impacts. Raw context, events, host observations, frame samples and screenshots are preserved. Final driver exited0 and GPU was released. Same independent second reviewer reparsed these records and accepted the sampled gate; see reviews/RAIR8B-source-and-frames-review.md.

| Window | GPU p50 candidate / worse A (ms) | Render-thread CPU p95 candidate / worse A (ms) |
|---|---:|---:|
| edge |3.492 /3.720|4.89030 /5.06300|
| inside |3.024 /3.946|4.47800 /4.80300|
| outside |2.392 /2.447|5.36295 /5.38515|

All6 gated metrics pass. Differences are not attributed as a general optimization: this is a conventional-strike workload, not a nuclear multi-source workload, fullpack/allshaderpack benchmark or unlimited-demand memory/latency guarantee. The changed-path JVM probe and24source consumption regression have their own narrower scopes under ownership/.
