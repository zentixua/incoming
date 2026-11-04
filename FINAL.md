# PERF-C2 measured outcome, 4 November 2026

AsyncParticles was evaluated and rejected for this preparation. Pack PR50 is closed without merge; its branch and evidence remain. The exact AP21.1.4.10 artifact has the independently verified Sable light-scan counter defect. Official4.4 lacks that limiter but did not enter a world during the bounded copied-pack trial; the startup cause is unclassified. This is not proof that AP caused that failure. Neither artifact was installed in the playing instance. No release or author contact occurred.

Incoming PR576 fixes an observer cost: ordinary depth descriptions no longer read and scan the full-resolution scene texture. The existing explicit `incoming.nukeDiag` path retains coverage diagnostics. No particle, rendering, sound or physics policy changes. Normal gameplay without this probe is not claimed to become faster.

## Frozen final comparison

Retained native100 city/night scene, seed20261025, Complementary Unbound r5.9.3, 2560×1600, full copied pack with Aeronautics1.3.2/Sable2.0.6, no profiler or screenshots. Fresh JVM/world per arm. Source36c9 / f693 /36c9; exact JAR ZIP payload differs only in `SceneDepth.class`. The JAR filename is deliberately identical in all three copies; manifest hashes identify its actual content. Same plans, helper, settings and validated disposable Connector cache. This cache contains no fresh-install proof of Connector beta17's separately diagnosed cold-transform race.

| Total effect window | Before A1 | Fixed B | Before A2 |
|---|---:|---:|---:|
| Frame p50 / p99 / max, ms |18.016 /88.634 /261.829|17.326 /86.926 /209.505|16.930 /89.210 /235.796|
| Frames >33.333 / >50 ms |314 /196|302 /188|333 /178|
| Frames >100 ms |13|13|14|
| Render-bracket CPU p50 / p99, ms |15.986 /53.891|14.969 /48.002|15.205 /47.729|
| Full-cycle CPU p50 / p99, ms |17.289 /85.064|16.592 /82.294|16.320 /84.449|
| GPU timestamp span p50, ms |16.484|15.164|15.601|
| Active3–15s frame median, ms |59.639|55.860|53.451|
| Foreign CPU mean / max, cores |0.08 /0.15|0.08 /0.51|0.12 /0.43|
| Foreign GPU mean / peak SM, % |0.10 /5|0.16 /5|0.94 /8|
| Ordinary LOD probes / coverage suffix |83 /83|82 /0|84 /84|

All three completed normally, each with100 native detonation entries and100 blast events; shader, renderer, dimensions, commands and workload contracts pass. CPU/GPU host telemetry covers each full effect window. Performance power profile throughout. Foreign peaks include desktop/agent activity, so this is not an exclusive-machine or fixed-clock benchmark. Exact window loads and frame rows are retained.

The code fix is functionally confirmed; the noisy end-to-end distribution does not establish a robust ordinary-game speedup. The fixed active median lies between controls, and >50ms counts overlap their range. Long frames remain. Fixed B's worst209.505ms interval has185.381ms full-cycle CPU but only74.377ms render-bracket CPU (78.251ms bracket wall time); reporting only the render bracket would hide much of the stall. GPU timestamp spans include CPU-starvation gaps and are not pure GPU busy time. CPU-cycle values belong to the next row's preceding Pre→Pre interval.

## Attribution and limits

Separate native/JFR recordings identify the synchronous `Probes → FxLayer.describeTiming → SceneDepth.lodShare → glGetTexImage` path and the float scan. In the main diagnostic it appears in401 of23086 render CPU samples; AP diagnostic567 of22184. These are inclusive sampled counts, not exact durations. The main diagnostic's381.220ms cold frame has zero lodShare samples; its work is mixed client-tick/render/cold initialization. The AP diagnostic's381.135ms frame includes28/153 render samples through the probe. This does not attribute the whole hitch to readback.

The main native window has foreign CPU0.01mean/0.06max cores. The AP native window co-ran other work:1.42mean/3.01max cores, including a build. Its stacks establish active paths, not comparative latency. A separate AP late-frame clip concentrates render CPU in animated-texture upload; no supported-setting A/B was performed, and AP was rejected for correctness first. Main whole-window BlastPulse has only15 sampled render records; no DSP change was justified.

Smoke preparation, cloud drawing and vanilla particles remain substantial current costs. Existing source already caches unchanged per-source merges; no speculative optimization or removal of visible phenomena was introduced. These measurements do not claim all client hitches are resolved or certify nuclear/real-map readiness, which have separate owners and trials.

## Source validation

The no-context regression fails on the original GL readback and passes with the guard. Full build1784 units and touched blast-radius GameTest pass; fresh independent source review has no serious finding. Final PR head0744ff00d includes checked main fixture fixes and required green CI41812062730. Original AP compatibility PNGs and lossless audio, raw gzip frame/event/host rows, exact manifests/plans, review reports and native clips accompany this document. No new look/audio capture is required for this telemetry-only source change.
