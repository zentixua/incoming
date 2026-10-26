# Development pipeline

Use NeoForge, Gradle and established community tools where they meet the project's needs. Keep custom helpers
only for capabilities the standard tools do not provide. The workflow and architecture rules are in
[AGENTS.md](../AGENTS.md); the contributor steps are in [dev-process.md](dev-process.md).

## 1. Build and runtime dependencies

The mod targets Minecraft 1.21.1 on NeoForge, using ModDevGradle and Java 21. Use the Gradle wrapper in `mod/`.
A warm daemon and the shared build cache reduce repeated compilation work:

```sh
cd mod
./gradlew --daemon build
```

Use `--daemon` for compilation and tests, not game launch tasks. Do not run `gradlew --stop`: it stops other
contributors' active daemons on a shared machine.

Development runs copy runtime mods from the configured instance. `MC_DIR` overrides the instance path; the path
resolver is `tools/paths.py`. Without an instance, fetch the pinned runtime mods with
`python3 tools/fetch_runtime_mods.py`, then pass `-PmcModsDir=run/ci-mods` to the Gradle run task.

## 2. Edit and inspect

Use a running development client for visual and audio work. `tools/sandbox.sh` provides a flat world, effect
commands and timed frame/WAV capture. `tools/client_scenario.sh` runs scripted client scenarios. See
[client-scenario.md](../.claude/rules/client-scenario.md) for commands and capture timing.

JetBrains Runtime supports enhanced class redefinition; resource reload updates textures and effects-layer
shaders. Restart the client for changes that cannot be hot-swapped reliably, including mixins and registration.
The sandbox currently uses the project's swap agent. A replacement with HotswapAgent requires checking that it
can redefine classes loaded through NeoForge before removing the existing agent.

## 3. Automated checks

JUnit covers Minecraft-free laws and codec/data contracts. Server GameTest exercises behavior in a loaded game.
Flight scenarios compare trajectories and check flight properties under disturbances.

CI selects checks by changed paths and splits GameTest batches across six shards using the duration table.
The `Итог` job combines the required results; it must report even when a path filter skips other jobs.
GameTest assertions use game ticks or counting clocks, not elapsed wall time on a shared runner.

A failed test needs a reproducible cause and a regression check. Repeating a flaky batch helps diagnose it but
is not a fix. Local batch selection, scenario controls and test pitfalls are documented in
[gametest.md](../.claude/rules/gametest.md).

## 4. Visual and audio checks

Inspect frames and audio during implementation and include before/after evidence in the PR. Unit tests and
code review alone cannot judge appearance, sound or frame cost.

Effect changes also require the scene bench (`tools/stand`): compare frame cost at 100 strikes against `main`
in the same GPU session. The reviewer checks the bench frames and the relevant WAV alongside the code.
See [the scene bench instructions](../tools/stand/README.md) for sampling and paired runs.

Only one visual session may use a GPU at a time. `tools/gpu_lease.sh` coordinates ownership; the sandbox and
headless client wrappers use it. Stop the sandbox and release the lease when finished. Headless checks must
not open a desktop window or host audio; use the provided wrappers, as described in
[rigs.md](../.claude/rules/rigs.md).

Full-pack recording is reserved for checks that need the full pack, rather than every visual edit. Flashback
can reproduce a recorded moment, but seeking must preserve the replay event rules in
[replay.md](../.claude/rules/replay.md).

## 5. Review and merge

Open a draft PR so CI can run. Keep it focused and describe the resulting behavior and relevant validation.
After CI, use one independent review round. Serious findings require fixes; another round is needed only for
serious first-round fixes. Merge after a green `Итог` and a review without serious errors.

`tools/pr_ci.sh <PR>` waits for the current head's `Итог`, reports failed checks and detects base conflicts.
GitHub does not start PR checks while the branch conflicts with its base. Resolve the conflict, preserve the
base's code, and rerun checks on the updated head.

## 6. Tool boundaries

Keep shared build, testing, resource-generation, benchmark and replay-production tools in the repository.
Personal session launchers, agent transcripts and machine-specific job notes belong outside version control.
Contributing must not require a particular agent executable or personal session service.

Custom helpers serve concrete gaps: timed frames and audio, automated multiplayer runs, GPU coordination,
log extraction and GameTest sharding. Prefer a standard replacement when it preserves these capabilities.

## 7. Evaluating tool changes

Measure a demonstrated bottleneck before replacing a tool. Check the proposed replacement on the relevant
workload and preserve the existing checks until it works. Potential changes include resolving runtime mods
through Gradle, using HotswapAgent, or sharing command plans between the sandbox and scripted scenarios.
These are implementation options, not prerequisites for contributing.

Useful upstream references:

- [NeoForge ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [ModDevGradle documentation](https://docs.neoforged.net/toolchain/docs/plugins/mdg/)
- [NeoForge GameTest documentation](https://docs.neoforged.net/docs/1.21.1/misc/gametest)
- [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime)
- [HotswapAgent](https://github.com/HotswapProjects/HotswapAgent)
- [Flashback](https://github.com/Moulberry/Flashback)
