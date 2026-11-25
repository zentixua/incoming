# Development pipeline

Use NeoForge, Gradle and established community tools where they meet the project's needs. Keep custom helpers
only for capabilities the standard tools do not provide. The workflow and architecture rules are in
[AGENTS.md](../AGENTS.md); the contributor steps are in [contributor workflow](dev-process.md#prepare-a-focused-change).

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

Visual, audio and gameplay changes follow the standard modder loop: one development client stays running in a
saved test world and each change goes into it in seconds.

- `./gradlew runClient` starts the mod with only its dependencies (Create, Sable, Aeronautics), no modpack.
  `-PquickPlay=<save>` passes the vanilla `--quickPlaySingleplayer <save>` flag and opens that world of
  `run/client/saves` without the menu; AE2 and Mekanism launch their test worlds the same way. A defect that
  depends on terrain is checked in a copy of the real map where it shows.
- `-PhotSwap` runs the client on JetBrains Runtime with `-XX:+AllowEnhancedClassRedefinition` and a JDWP port on
  loopback. An IDE's "reload changed classes", or `jdb`'s `redefine` after `./gradlew compileJava`, replaces the
  classes in the running game; new fields, methods and lambdas swap too. Create sets the same flag in its runs.
  Restart for registrations, static initializers, packet formats and mixins.
- F3+T reloads textures, models, sounds, language files and the mod's shaders; Iris reloads a shader pack with R;
  `/reload` reloads data. Values tuned by eye live in the config files, which the game rereads when they change.
- Vanilla commands set the scene: `/tp`, `/time set`, `/weather`; `/tick freeze` holds a moment (entities,
  particles and the mod's nuclear show stand still; ordinary strike smoke runs on) and `/tick step <ticks>` moves it.
- Logic is held by GameTest (section 3); the client is for what a test cannot judge: picture, sound, feel.

`tools/dev_client.sh` runs this loop without a screen, for agents: the same `runClient` on a headless display, whose
keyboard the game never sees, so the commands, F3+T, F2 and F1 go in as files a devtest listener reads
([client-scenario.md](../.claude/rules/client-scenario.md), "Standard dev client").
`tools/sandbox.sh` adds what the loop lacks: frames and WAV at exact effect seconds, repeated against `main`.
`tools/client_scenario.sh` runs scripted client scenarios. A whole-pack client is for the final acceptance set
only (`tools/laptop_job.sh --full --acceptance`).

Sources: [ModDevGradle](https://docs.neoforged.net/toolchain/docs/plugins/mdg/),
[Quick Play](https://minecraft.wiki/w/Quick_Play),
[AE2 build.gradle](https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/1.21.1/build.gradle),
[Mekanism build.gradle](https://github.com/mekanism/Mekanism/blob/1.21.x/build.gradle),
[Create build.gradle](https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/build.gradle),
[Fabric hotswapping](https://wiki.fabricmc.net/tutorial:hotswapping),
[Iris debugging](https://github.com/IrisShaders/Iris/blob/1.21.1/docs/usage/debugging.md),
[/tick](https://minecraft.wiki/w/Commands/tick).

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

The main visual target is vanilla rendering without shader packs; a shader pack adds on top and must not break
it (AGENTS.md, "Vanilla first"). Vanilla frames take a shared GPU slot when one is free (up to three clients,
about five times faster than the CPU) and otherwise render at once on the CPU with Mesa llvmpipe
(`tools/soft_gl.sh`), the way Fabric API's client GameTest takes screenshots on GPU-less CI runners and Godot and
NeoForge start clients there: the sandbox and headless scenarios default to it, need no GPU lease, never queue and
run side by side, on the laptop or netcup. The GPU alone (the lease) is reserved for shader-pack (Iris), Voxy/DH
frames, frame time and the scene bench.

Changes that can affect frame cost also require the scene bench (`tools/stand`), with the scope and exceptions
in AGENTS.md: compare frame cost at 100 strikes against `main`
in the same GPU session. The reviewer checks the bench frames and the relevant WAV alongside the code.
See [the scene bench instructions](../tools/stand/README.md) for sampling and paired runs.

Only one session may use the GPU at a time. `tools/gpu_lease.sh` coordinates ownership and caps its queue at
two waiting sessions; the GPU-mode sandbox and headless client wrappers use it. Stop the sandbox and release the
lease when finished. CPU clients take no lease; `soft_gl.sh` admits one only while memory allows it. Headless checks must
not open a desktop window or host audio; use the provided wrappers, as described in
[rigs.md](../.claude/rules/rigs.md).

Full-pack recording is reserved for checks that need the full pack, rather than every visual edit. Flashback
can reproduce a recorded moment, but seeking must preserve the replay event rules in
[replay.md](../.claude/rules/replay.md).

For page media, record the complete event in Flashback and export with its native camera and exporter. Bind
selected frames to replay events and check the final trimmed interval against the requested scene. File validity,
frame rate and loaded terrain do not prove that a flash, rising cloud or approaching missile is visible. Use the
[content checklist](../tools/media/README.md) and give the independent reviewer the shot requirements and frames.

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
through Gradle, moving the sandbox's own swap agent to the dev client's JDWP hot swap, or sharing command plans
between the sandbox and scripted scenarios.
These are implementation options, not prerequisites for contributing.

Useful upstream references:

- [NeoForge ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [ModDevGradle documentation](https://docs.neoforged.net/toolchain/docs/plugins/mdg/)
- [NeoForge GameTest documentation](https://docs.neoforged.net/docs/1.21.1/misc/gametest)
- [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime)
- [HotswapAgent](https://github.com/HotswapProjects/HotswapAgent)
- [Flashback](https://github.com/Moulberry/Flashback)
