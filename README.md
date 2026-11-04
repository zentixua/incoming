# PERF-C2 client performance evidence

Checkpoint 2026-11-04. This orphan evidence branch is never merged into production. Original raw JFR recordings, copied worlds and dependency jars stay in ignored local test data; sampled method stacks and original frame/event/host streams are included here. Streams are gzip-compressed without changing rows. Exact jar/config hashes and actual plans accompany each completed recording.

The initial main/AP10/main timing pair still polled `FxLayer.describeTiming`, which synchronously read LOD pixels. It is observer-contaminated and does not establish ordinary-gameplay latency. The two native runs are separate instrumented diagnostics. Full-cycle CPU is the following row's `prev_cpu_cycle_ms`, whereas `cpu_thread_ms` is only the render bracket. GPU timestamp spans include starvation gaps.

Incoming PR576 only makes ordinary depth labels cheap; it does not change rendering or physical events. The baseline regression failed at the old GL readback, then the full build (1784 unit tests), one touched GameTest and fresh independent review passed. Current merged head8ebca2117 has green required Итог41809418210. A final matched native100 scene pair is pending; no whole-game improvement claim.

AsyncParticles21.1.4.10 is blocked by the exact artifact's Sable light-scan counter defect (independent review). AP4.4 lacks that limiter but the bounded full-pack run `dark-ap-v44` never entered a world before its owned client was stopped; cause is unclassified. Neither artifact is accepted for deployment. `dark-main-v3` is geometrically inside an opaque craft, but original Sable particles already report skylight15 inside/outside, so this fixture does not validate the required light contrast. No moving-craft correctness claim.

All clients used full copied pack/Aeronautics, muted host audio and nested virtual KWin. Essential saved properties were changed only in test copies. Successful identical disposable Connector caches contain a known beta17 cold-transform race; they are not fresh-install proof. Protected servers, playing instance and releases were untouched.
