# Passive frame observer validation

PR #571. Reviewed implementation8d9463eca; final9447c069d also qualifies loading-loop flush behavior and adds an explicit JVM monotonic anchor. No mod mechanics, world commands or renderer changes.

- Fresh independent read-only review: no serious findings. It verified exact NeoForge21.1.264 lifecycle/event contracts and surrounding FrameClock/Out behavior. Optional loading-loop documentation finding was fixed; no second round for that clarification or scalar clock metadata.
- Final standalone harness Gradle build/check passed (8 seconds). Required final CI passed.
- Disposable full modpack client joined a remote NeoForge server using `stand.observe=true`. A deliberately malformed stale `stand-plan.json` remained present: it was ignored, no scene Director/world creation/commands ran.
- Observation produced59,837 CPU/GPU frame rows and553 periodic/lifecycle events,493 of them in-world. GPU timestamp ring forced waits remained0. Correct world/dimension/camera fields were present, and after server disconnect the observer continued with `world=false` and null dimension/tick.
- This smoke used the pre-monotonic-field jar; final addition passes the same sampled `System.nanoTime()` value as one scalar event field. The field is not a new timing mechanism. Loading/menu and multiplayer observation paths were exercised; local-world loading loops and normal game-exit event were source-verified rather than runtime-tested here. Client termination was controlled after disconnect, so no normal-exit receipt is claimed.

This is instrumentation validation, not a game-performance or visual acceptance result. Native screenshot readbacks and another diagnostic observer ran alongside the harness, so raw frame maxima are perturbed. GPU elapsed includes submission gaps; render-thread CPU excludes client ticks/tasks outside the render bracket. Repeated stage callbacks in a shader pipeline remain visible in the diagnostics rather than being treated as independent GPU-busy time. Final unresolved query rows and data since the last actual flush can be lost on termination, as documented.
