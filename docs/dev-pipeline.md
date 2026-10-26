# Development pipeline: community standards instead of custom tools

Research and proposal as of 23.10.2026 (session DL), revised after Artem's decision, 23.10 07:40–07:41.
- **07:40.** Use the standard community pipeline (NeoForge, Fabric, proven tools). Differences only for temporary,
  one-off steps such as comparison with 3.4.1.
- **07:41.** Custom tools only for real needs not covered by standards. Not when they work around architectural errors
  that first-principles thinking would remove at the root.

Thus each custom tool/rule here receives one verdict:
- **(a)** replace with community standard;
- **(b)** consequence of a root error: fix root, remove tool;
- **(c)** real need without a standard: keep, with reason.

Sources at the end; unsourced items marked “estimate”. Retrospective since 01.10: `docs/dev-process.md`.

## Summary

- **How modders work.**
  - Edit in a running dev game: hot swap on JetBrains Runtime, F3+T for resources, Iris R for shaders. Changes visible
    in seconds.
  - CI checks build and server GameTest, 2–5 min.
  - Author checks visuals while working. No major rendering mod compares frames in CI (Sodium, Iris, Distant Horizons,
    Veil, Flywheel, Flashback).
  - Betas and players find bugs.
- **We have 24 custom tools/rules** (section 3): ready-made alternatives replace 10 (a), 8 result from root errors (b),
  8 serve real needs (c); three rows mixed. Two main roots.
  - **First error:** up to four visual sessions sharing one GPU. Hence queue, locks, sandbox killed by other jobs,
    15 h waiting overnight 22–23.10.
  - **Second error:** checking visuals after work rather than during. Hence full-pack Iris recording per PR: 130 runs
    overnight, 9.9 GPU hours.
- **Proposal.** Adopt standards, remove custom tools where standards exist.
  - One visual session on the GPU at a time.
  - Check frames in dev client while working.
  - Full-pack comparison only once against 3.4.1 until reaching its quality.
  - Keep custom tools where NeoForge has no standard: Claude's precisely timed frame, headless client on someone
    else's desktop, protection of Artem's game.

## 1. Stages: modders, us, what to adopt

| Stage | Experienced modders (late 2026) | Us | Adopt |
|---|---|---|---|
| Project | NeoForge 1.21.1 MDK: ModDevGradle 2.0.148, Parchment 2024.11.17. Create, Mekanism, AE2, JEI, Sodium, Iris, Veil on MDG 2.0.14x. Multiloader (MultiLoader-Template, Architectury, Stonecutter) only for multiple loaders/versions | MDG 2.0.147, Parchment 2024.11.17, one version | keep; no multiloader needed (22.10 decision: stay on NeoForge 1.21.1) |
| Build speed | MDK: `daemon`, `parallel`, `caching`, `configuration-cache` all `true`; MDG supports configuration cache; Mekanism, AE2, JEI enable it; Create has no daemon | warm daemon for `build` (#384), compile 3–6 s; shared build cache; configuration cache off | MDK settings as is: daemon/configuration cache on (check in part B) |
| Launch/debug | IDE runs, debugger; method-body hot swap; JetBrains Runtime `-XX:+AllowEnhancedClassRedefinition` supports fields/methods too (Create sets flag for all runs, Mekanism for JetBrains JVM); mixin swaps unreliable (Mixin #592, #408), restart | sandbox: own swap agent, JBR 21 (`sandbox.sh jbr`), `restart` ~20 s | JBR + HotswapAgent (`autoHotswap`) instead of own agent; Claude needs neither debugger nor IDE: `SCENARIO` lines and `overlay` instead of breakpoints |
| In-game reload | F3+T resources, `/reload` data, Iris R shaderpack; Veil watches files/rebuilds shaders | `swap` does F3+T; effects-layer shaders are our resources | already so; also settings: NeoForge rereads changed TOML, sends `ModConfigEvent.Reloading` without restart |
| Visual tuning | ImGui sliders (Veil F6, ImGuiMC), settings screens | number in code or `/fx` argument, frame via `shot` | no sliders: Claude cannot move them, needs a frame at a specified moment |
| Automated tests | JUnit; JUnit with game (MDG `unitTest` + `testframework`); server GameTest in CI: Create, JEI, AE2, Mekanism; client screenshot/template tests only Fabric (since 1.21.5); absent in NeoForge (#3624, opened 10.10.2026); `mc-runtime-test` runs client in CI without screenshots | JUnit laws, six-shard GameTest, flight scenarios, headless screenshot client | standard already present; NeoForge lacks client screenshot tests, get frames from dev client (section 3) |
| Visual checks | eyes in own game; RenderDoc/Nsight frame analysis (Linux `renderdoccmd`; NeoForge RenderNurse needs Java 25); outside Minecraft, Bevy captures example frames in CI, NVIDIA FLIP measures difference | full-pack Iris `look_gate` per visible PR, visual comparison (10–40 % pixels noisy) | community approach: author checks frames in dev client during work, frames in PR; full pack only one-off comparison with 3.4.1 |
| CI | GitHub Actions + `gradle/actions/setup-gradle` (v6.4.0; cache writes only main branch). Times: Create 2–4 min, Sodium 1.5–3, AE2 3–4, Mekanism 2–3, JEI 5–14 | median 4.9 min, p90 7.1; six-shard GameTest; `setup-gradle@v5` | keep; v6 a small change |
| Release | `mod-publish-plugin` 2.2.1 (Create, Sodium), `mc-publish` 3.3.2, Minotaur; changelog Keep a Changelog, git-cliff | `mc-publish` 3.3.1, `docs/releases/<version>.md`, `release=true`, pack PR | keep, takes minutes |
| Betas/feedback | Sodium: 39 betas, 11 alphas on Modrinth; Distant Horizons 191 betas, Iris 12; CI nightlies; issue forms with mclo.gs log; Crash Assistant mod | friends receive only releases through pack; post-game `logscan.py` | friends' betas are Artem's decision (section 6) |
| AI tools | HeadlessMC MCP (CLI client, JSON screen without image), minecraft-mod-mcp, mcpfabric are young projects; NeoForge/Fabric say nothing about agents | sandbox: `/fx`, frame/WAV files | skip: same capability here, frame more precise |

Table conclusion: modders have no magic tool we lack. Difference is **where** they check visuals (author's live game)
and **how often** they run heavy work (CI build/tests only, visuals through beta).

## 2. Our losses, 22–23.10 (from data)

Data:
- PRs #379–#406 (`gh`), 172 CI `build` runs since 22.10 14:00 UTC;
- laptop job journal: 146 jobs from 22.10 17:51 to 23.10 07:28 UTC (earlier journal empty after reboot);
- transcripts of X, F4, N1–N4, LFX, F5, VXG, LNB, H, PK and `mod/run/incoming-sessions/*.json` results.

No `sessions.log`: overnight sessions used old `mod/run/incoming-sessions/go.sh`, not `tools/sessions/go.sh`
(`dev-process.md` 5.1 not implemented).

### 2.1. Night 22.10 19:51 – 23.10 09:28 (13.6 h, local time)

- **Sessions.** Eleven visual sessions overnight: F4, VXC4, VXG, N1, N4, LFX, F5, N2; by morning N3, LNB, PK; up to four
  concurrent (transcripts: N1, N4, LFX, VXG at 20:30 UTC; N4, LFX, F5, N2 at 22:05).
- **GPU.** 130 full-pack client launches; GPU busy 9.9 of 13.6 h (73 %).
- **Queue.** Jobs waited 15.0 h total: N4 186 min, LFX 179, F5 162, N2 140. Run/wait separated by queue lock (heavy jobs
  run singly); journal-derived calculation, not direct measurement.
- **Reference.** `main`/3.4.1 recorded 43 times, 2.65 of 9.9 GPU hours. N1, N2, N3, N4 each recorded the same `main`
  nuclear scenes; N2/LFX recorded `main` three times each.
- **Own-branch rounds.** LFX recorded its branch full-pack 11 times (4–74 min each), F5 7 times.
- **Sandbox.** N1, N2 (twice), F5 tried it. Lasted 5 s/16 s, failed for N2: any GPU job kills it (`tools/sandbox.sh`,
  `run_client`). N2: “could not start the sandbox for a fireball close-up: other sessions kept the GPU queue busy”.
  F5: “separate sandbox was no use”.
- **Session waiting.** `sleep`/polling: N2 101 of 214 min, N1 75 of 108, LFX 72 of 353 (plus 163 in laptop jobs).

### 2.2. N3 (#404): code in 18 minutes, checks in 2.5 hours

Session ran 04:36–07:24 UTC, 2.8 h (brief said “~6 h”; transcript/journal 2.8).
- **Code.** Code/draft PR ready in 18 min, PR opened 04:54.
- **Checks.** Remaining 2.5 h: 39 full-pack Iris scene launches, 4 branch rounds, 2 `main`; 100 GPU minutes, 34 queued.
- **Scene length.** `nuke` 342 s/run, `mushroom`/`firestorm` 5 min each, `wall`/`debris` 1–1.5 min.
- **Sandbox.** N3 did not launch it. It has the nuke (`nuke <kt>`), but desired moments (wave near camera seconds after
  front arrival, pall minutes later) require playing the whole scene in real time.
- **Finding cost.** Each recording finding required another full-scene round: trunk dust, beige veil, particle limit,
  subpixel leaf.
- **Frame comparison.** Numeric comparison impossible. N1: “nuclear frames vary between runs, 10–40 % of pixels differ
  even without edits”. Thus `main` recorded twice to see noise, then compared by eye.

### 2.3. PRs #379–#406

- **Merged:** 27 PRs.
- **Opening to merge:**
  - process tools (#380–#388), up to 45 min;
  - overnight visuals 1.5–5.5 h: #398 1.5 h, #401 1.7, #404 2.5, #403 2.75, #400 3.9, #402 4.6, #399 5.5.
- **CI `build`:** median 4.9 min, p90 7.1.
- **CI reruns.** “Branch updated to current `main`” requires rerunning after each batch merge: LFX 16 runs, N3 12,
  F5/VXG 8 each. Each run is 5 min waiting, not hours.

### 2.4. Losses by stage

| Stage | Loss, 22–23.10 | Cause |
|---|---|---|
| Full-pack recording per PR | 9.9 GPU hours, 130 launches; visual PRs 1.5–5.5 h to merge | K2: checks after work instead of frames during work |
| Rerecording reference | 2.65 of 9.9 h | K1/K2: every concurrent session recorded its own reference |
| GPU queue | 15 job-wait hours | K1: up to four visual sessions on one GPU |
| Sandbox dies | no useful overnight launch | K1: any other session's GPU job kills it |
| Desired nuclear moment | 342 s/run for “5 s after camera arrival” frame | standard Flashback replay not adopted |
| Frame comparison | 2 `main` runs/session, by eye | K2: full-pack comparison instead of frame during work; frames not repeatable either |
| Session waiting | 1–2 h/session | K1: waiting for GPU queue |
| CI | 5 min, up to 16 runs/PR | strict “up to date with main” (GitHub standard) during batch merges grows with parallel PR count (K1) |

## 3. Custom tools: verdict for every tool and rule

Roots (section 4): **K1** multiple visual sessions on one GPU; **K2** visuals checked after work, not during;
**K3** flaky GameTest; **K4** runtime mods from Artem's instance rather than Gradle.

| Custom item | Purpose | Verdict | Replacement or reason to keep |
|---|---|---|---|
| `tools/look_gate` + `ProbeScenario`: full-pack Iris recording per visible PR | frames/sound beside 3.4.1 | **(b) K2**, also one-off | Standard: author checks frames in dev client while working, puts frames in PR. Comparison to 3.4.1 is temporary/one-off (Artem 07:40): bring all scenes to 3.4.1 quality in one pass, then remove `look_gate`/`ProbeScenario` |
| Frame comparison beside reference per PR (rule “Checked by someone other than author … frames beside release”) | catch visual regressions before merge | **(c) Artem's decision 24.10**: scene bench `tools/stand` | Former verdict (b) K2. On 24.10 Artem required scene bench on every visual PR as merge gate: frame time with 100 strikes no worse than accepted release (3.5.0/3.7.0 frame-time regressions show only under strike load, not sandbox frames). Different from `look_gate`: minutes, not hours, a wrapper mod without Incoming dependency in a flat world, only Sodium/Iris (not whole pack), accepted release captured as a pair in same session, only “100” scene (~30 GPU min; reference cache tried, laptop GPU heat shifts sessions by 6–29 %); one GPU session at a time (lease). No numeric visual verdict: bench frames beside accepted release go to reviewer like PR screenshots |
| 3.4.1 reference recorded by every session | comparison base | **one-off**, rerecording **(b) K1** | record once during 3.4.1 comparison, unneeded afterward |
| Sandbox `tools/sandbox.sh` | flat-world dev client, `/fx` effects, timed frame/WAV (`shot --at`) | core **(c)**, wrapper **(b) K1** | Core like standard MDG `runClient` with dev source set (JEI uses `clientGameTest` this way). Custom need: Claude cannot see screen/type in chat, needs frame file at exact tick; NeoForge lacks client screenshot tests (#3624). Remove “wait queue, die to another job” wrapper, a K1 consequence |
| Custom hot-swap agent `src/sandboxAgent` (`HotswapAgent` in `ua.zentix.hotswap`) | replaces classes after Gradle writes them | **(a)** | JetBrains Runtime + HotswapAgent (HotswapProjects, `-XX:HotswapAgent=fatjar`, `autoHotswap=true`: watches/swaps `.class`). If it cannot see mod classes in NeoForge loader, standard IDE path: JDWP (`-agentlib:jdwp`) and `redefine`. Trial part B |
| `tools/client_scenario.sh` + `ClientScenario` (headless client scenarios) | guide/trailer frames, nuclear scenes | **(c)**, merge with sandbox | No NeoForge standard (Fabric has client GameTest). Two custom clients (scenarios/sandbox) are redundant: scenario = sandbox-command list (`commands` exists). One dev client |
| Nuclear moment (`nuke` scene 342 s for frame after 5 s) | frame at time t | **(a)** | Flashback (in pack): record once, seek, capture. Seek skips one-shot events (`replay.md`) but renders nuke by detonation clock. Vanilla `/tick` cannot help: client max 20 ticks/s (`Minecraft.getTickTargetMillis`: max of 50 ms and `TickRateManager` step) |
| `tools/prod_client.py` (full-pack client, instance copy) | “as for player”: pack loads, Iris visuals | **(a)** + one-off | “Pack loads”: `mc-runtime-test` in open-skies CI (real full-pack client). Iris visuals only one-off 3.4.1 comparison |
| `tools/x11_record.py` (window video for `prod_client.py --video`) | real-time full-pack client video | **(a)** | Remove with `prod_client.py --video`. Replay clips already use Flashback export (`tools/clips`); trailer records frame by frame (`trailer/record.sh`), no `x11_record.py` |
| `tools/nested_kwin.sh --virtual` | GPU client without window/audio on Artem's desktop | **(c)** | Standard CI `xvfb-run` (Fabric Loom, Bevy) renders on CPU (llvmpipe, 3–10 fps, no shaders). Real need: laptop is Artem's desktop. KWin `--virtual` is standard KDE mode; standard fallback `gamescope --backend headless` (installed) |
| `tools/laptop_job.sh` | separate systemd service, queue lock, wait for Artem's game to close | queue **(b) K1**, service/game **(c)** | Separate service: `systemd-run --user` standard, thin wrapper. Game protection is real need on shared machine. Remove queue lock with K1 |
| `tools/gpu_busy.sh` | another job holds GPU | **(b) K1** | remove with K1 |
| `tools/sessions/go.sh`, `supervise.sh` | background claude2 session in separate service, auto-resume after jobs | **(c)** | Claude Code lacks “session as service surviving parent”; `claude -p`, `--resume`, `systemd-run` standard, thin wrapper. K1 limits session count/length |
| `live.md` (outside repo: `mod/run/incoming-sessions/`) | new decisions for running sessions | **(b) K1**, removed 25.10 | Long parallel sessions miss new decisions. Put decisions in task before startup, keep sessions short |
| `mod/run/incoming-sessions/go.sh` (old, outside git) | same | **(a)** | already replaced by `tools/sessions/go.sh` (#382), but used overnight 22–23.10; remove |
| `tools/pr_ci.sh` | wait for “Итог”, code 3 on conflict | **(a)** | “Записка” removed 25.10, only “Итог” required. Also `pr_ci.sh` reads conclusion of jobs GitHub leaves “running” after completion via `completedAt`; `--watch` would wait until its limit. Replacement `gh pr checks <PR> --watch --fail-fast --required` (laptop gh 2.102), conflict `gh pr view --json mergeable`; stalled job then visible by wait duration, rerun `gh run rerun` |
| `tools/ci_repeat.sh`, manual CI “repeat batches N times” | find flakes | **(b) K3** | Fix unstable tests (root); until then standard GitHub `gh run rerun --failed`, `@GameTest(required = false)` for known flakes |
| “Записка” check (`note.yml`, `pr_note.py`) | visible-change PR has expectations section | **(a)**, removed 25.10 | Standard PR template (`.github/PULL_REQUEST_TEMPLATE.md`) and review. Large-change plan is a few PR-description lines, no CI check |
| `copyRuntimeMods_*` from instance `mods/` (`build.gradle`:53–65, 325–338) + CI `tools/fetch_runtime_mods.py` | runtime mods: Create, Sable, Aeronautics; flags for Sodium/Iris (`INCOMING_SHADERS`), Distant Horizons (`INCOMING_DH`), Voxy/Roxy/Forgified Fabric API (`INCOMING_VOXY`), LOD Server Support (`INCOMING_LSS`), Lithium (`-PwithLithium`). Host shaderpack also comes from instance (`sandbox.sh shaders`, `trailer/record.sh`, `client_scenario.sh`), as does FML dependency override (`build.gradle`:271, enabled if `mcModsDir` contains Aeronautics) | **(b) K4** → **(a)** | MDK standard: Gradle dependencies `localRuntime "maven.modrinth:<mod>:<version>"` (Modrinth Maven), each group behind its own flag as now. One source for laptop/cloud/CI; remove instance copying and “align pins in `fetch_runtime_mods.py`” rule (`live.md` 07:18, outside repo). `fetch_runtime_mods.py` already fetches Sable/Aeronautics from Modrinth. FML override by same flag, not directory. Shaderpack is resource, not mod: keep instance copy (c) |
| `org.gradle.daemon=false` + `--daemon` only for `build`, “never call `gradlew --stop`” | `run*` game outside daemon cgroup; sessions do not kill each other's daemons | no daemon for `run*` **(c)**, rest **(a)** | `daemon=false` reason (`mod/gradle.properties`:2–4, #384): daemon `run*` game would live in daemon cgroup (often Claude app), not `laptop_job.sh` service; shared-machine need absent in MDK, keep. Warm daemon already used for `build`/tests (`--daemon`). Adopt MDK `configuration-cache=true` (trial part B). “Never call `gradlew --stop`” remains after K1: GPU-free sessions still parallel, `--stop` kills all daemons |
| GameTest shards (`GameTestShards`, `gametest_durations.py`) | CI 5 min instead of ~20 | **(c)** | Custom for speed: MDG/NeoForge have no GameTest sharding; matrix is standard GitHub |
| “Что изменилось” in `build.yml` | skip mod build for `docs/` edits | **(a)** | `dorny/paths-filter` standard job-level path filter; “Итог” always runs/reports. Not `on.pull_request.paths`: required “Итог” would never report when skipped, preventing merge |
| `tools/stress.sh`, `mp_scenario.sh`, `rig_procs.sh`, `free_port.py`, `rig_config.py` | headless multiplayer/load | **(c)** | No NeoForge automated-multiplayer standard; manual `runServer`/`runClient`. Keep, run as needed |
| `tools/deploy.sh`, `logscan.py`, `paths.py`, `chunk_status.py` | jar to instance, log digest, paths | **(c)** | small helpers; player delivery already standard (release → packwiz pack) |
| `build_sounds.py`, `gen_*.py`, `bake_fx/`, `freesound.py`, `guide_images.sh` | mod resources | **(c)**, outside pipeline | This mod's asset generators, not pipeline. Code models (`gen_models.py`) versus Blockbench/Blender a separate question |
| `replayscan/`, `clips/`, `trailer/` | videos | outside pipeline | video production, not mod development |
| My previous proposal (reference store, GPU frame queue, FLIP difference metric, recording every release candidate) | — | **withdrawn** | custom layers over K1/K2; not proposing after 07:40–07:41 decision |
| Rules “Ready-made first”, “Expectation first”, “Test invariants”, fresh-context review, “No checks beyond these” | — | standard | libraries/tests/review ordinary practice; “expectation first” is PR template |

## 4. Root errors (first principles)

**K1. Multiple visual sessions on one GPU.**
- **Resource.** One GPU, like any modder. Visual work bottlenecks there: frames, recording, Blender.
- **Past.** Up to four such sessions concurrently overnight 22–23.10.
- **Workarounds built.** `laptop_job.sh` queue, `gpu_busy.sh`, sandbox killed by others' jobs, repeated references,
  `live.md` for long sessions. Cost: 15 job-wait hours overnight.
- **Root.** Parallelizing work on one shared resource.
- **Fix.** One visual session at a time, as a modder works. Only GPU-free sessions run in parallel: server, flight,
  air defense, tools, text.
- **Remove.** Queue, `gpu_busy.sh`, sandbox wrapper, reference rerecording, `live.md` (outside repo). Keep “never call
  `gradlew --stop`”: GPU-free sessions remain parallel.

**K2. Checking visuals after work instead of during.**
- **3.5.0.** PRs checked law tests/code review, nobody saw a frame (`dev-process.md`, 2.1).
- **Added workaround.** Full-pack look gate per PR, “Записка” check. After-the-fact checks cost hours/PR.
- **Root.** Author did not look at results while working.
- **Fix.** Community approach: edit → dev-client frame in seconds. Frames in PR, reviewer checks against note.
- **Remove.** Full-pack recording per PR, CI “Записка”. 3.4.1 comparison is one-off until all scenes reach its quality.

**K3. Flaky GameTest.**
- **Amount.** 10 % of full runs failed (`dev-process.md`, 2.7).
- **Added workaround.** N batch repeats on six machines finds flakes, does not fix them.
- **Root.** Tests depend on timing, order or shared statics.
- **Fix.** Fix each test; until then standard `@GameTest(required = false)`, `gh run rerun --failed`.

**K4. Runtime mods from Artem's instance.**
- **Now.** `build.gradle` copies Create/Sable/Aeronautics from instance `mods/`; flags add Sodium, Iris, Distant Horizons,
  Voxy, Roxy, Forgified Fabric API, LOD Server Support, Lithium. FML override enabled if directory has Aeronautics.
  CI/cloud fetch Create/Sable/Aeronautics from Modrinth via own `fetch_runtime_mods.py`.
- **Result.** Two sources for one dependency. Pack updates require manual pin alignment (`live.md` 07:18, outside repo).
- **Fix.** MDK approach: Gradle `localRuntime "maven.modrinth:<mod>:<version>"`, each group behind own flag, one version
  across runs. Host shaderpack is resource, not mod: keep copy.

## 5. Standard pipeline and target times

| Step | Method (standard; custom verdict (c)) | Target | Now |
|---|---|---|---|
| Method body, number or shader edit → frame | JBR + HotswapAgent dev client (sandbox), F3+T; frame `shot --at` | ≤ 10 s | 10 s (22.10 measurement), but sandbox died overnight (K1) |
| Edit not supported by hot swap (registration, mixin, static field) | restart dev client, like everyone (mixin swaps unreliable: Mixin #592, #408) | ≤ 40 s with Iris | 20–39 s |
| Nuclear frame at time t | Flashback replay: record once, seek | ≤ 1 min (estimate, trial part B) | 342 s full scenario per round |
| “Done” to merge | CI (`setup-gradle`, sharded GameTest) + fresh-agent review of PR frames; wait `pr_ci.sh`, after “Записка” removal `gh pr checks --watch --required` | ≤ 20 min | 1.5–5.5 h for visual PRs |
| Player pack loads | `mc-runtime-test` in open-skies CI | minutes, every pack PR | no client-launch check |
| Full-pack Iris look | one-off 3.4.1 comparison, one pass/session | once | every visible PR |
| Release/feedback | `mc-publish`, notes, pack PR; betas only on Artem's instruction (section 6) | minutes | minutes |

Custom items kept and why, only (c) from section 3:
- **Frame file at a specified tick, dev-client commands without chat.** Claude cannot see screen; NeoForge has no
  client screenshot tests.
- **Headless GPU client.** Laptop is Artem's desktop; `xvfb` renders on CPU.
- **Protecting Artem's game.** Shared machine.
- **Background sessions as services, CI GameTest shards.**

## 6. How we actually differ from a modder

- **Claude looks, not a person.** Sliders, debugger, ImGui do not help; needs frame file at a specified tick. Sole reason
  to retain custom sandbox core.
- **Machine is Artem's desktop.** Hence headless client and game protection.
- **No betas.** Friends play releases. Community standard is betas (Sodium 39 on Modrinth, Distant Horizons 191).
  Publishing betas is a public step, Artem's decision.
- **Multiple sessions.** Not a difference to support with tools: one visual session at a time (K1).

## 7. Plan: adopt standards, remove custom tools (gain per effort)

1. **K1: one visual session on GPU at a time.**
   - CLAUDE.md rule (this PR), coordinator batch order (`mod/run/incoming-sessions/DL-context-patch.md`, outside repo).
   - Gain: 15 queue hours overnight → 0, sandbox survives.
   - Then remove `laptop_job.sh` queue lock, `gpu_busy.sh`, “die” wrapper in `sandbox.sh`. Keep “never call
     `gradlew --stop`”.
   - Cost: rule now, ~1 h script cleanup.
2. **K2: frames during work, dev-client frames in PR; remove full-pack recording per PR.**
   - Rule/PR template replaced in this PR: dev-client frames/WAV at note's moments, no “beside release”. On 24.10 Artem
     added scene bench (`tools/stand`) for every visual PR: 100-strike frame time against accepted release, paired
     within same session, is merge gate; bench frames go to reviewer. Not full-pack recording (section 3).
   - Finish one-off 3.4.1 comparison in one pass, then remove `look_gate`, `ProbeScenario`, `prod_client.py --video` and
     `x11_record.py`. CI “Записка” removed 25.10.
   - Gain: 9.9 GPU hours overnight → minutes/PR; “done → merged” 1.5–5.5 h → ~20 min (estimate).
3. **Simple (a) replacements, one hour or less each:**
   - `pr_ci.sh` → `gh pr checks --watch --fail-fast --required` (already one required check);
   - “Что изменилось” → job-level `dorny/paths-filter` (not `on.pull_request.paths`);
   - `gradle.properties` → MDK `configuration-cache=true` (trial part B); retain `daemon=false` for `run*`;
   - old `mod/run/incoming-sessions/go.sh` (outside repo) → `tools/sessions/go.sh`;
   - `mc-publish` 3.3.1 → 3.3.2 (released 22.10.2026), `setup-gradle@v5` → `v6`.
4. **K4: runtime mods through Modrinth Maven `localRuntime`.**
   - Everything now copied from instance: Create, Sable, Aeronautics; flags for Sodium/Iris, Distant Horizons,
     Voxy/Roxy/Forgified Fabric API, LOD Server Support, Lithium.
   - Same flags (`INCOMING_SHADERS`, `INCOMING_DH`, `INCOMING_VOXY`, `INCOMING_LSS`, `-PwithLithium`); sandbox still
     without Create/Sable.
   - FML dependency override (`build.gradle`:271) by Aeronautics flag, not instance directory.
   - Remove `copyRuntimeMods_*`, `fetch_runtime_mods.py`; it already fetches Sable/Aeronautics from Modrinth, whose Maven
     serves any Modrinth project.
   - Host shaderpack (`sandbox.sh shaders`, `trailer/record.sh`, `client_scenario.sh`) is resource; keep copy.
   - Cost: ~2–3 h (estimate).
5. **Own swap agent → HotswapAgent (`autoHotswap`) on JBR.**
   - Put `hotswap-agent.jar` in JBR's own `lib/hotswap` (HotswapAgent README), flags:
     `-XX:+AllowEnhancedClassRedefinition -XX:HotswapAgent=fatjar`.
   - Part B trial: sees mod classes in NeoForge loader? Otherwise JDWP `redefine`, like IDE.
   - Then remove `src/sandboxAgent`.
6. **One dev client: `client_scenario.sh` scenarios as sandbox-command lists.**
   - Cost: large (estimate one day), gain one custom client instead of two. After 1–5.
7. **Nuclear moment via Flashback replay** (trial part B).
8. **K3: fix flakes one by one.** Then remove `ci_repeat.sh` and manual repeat CI.
9. **open-skies (other repo): `mc-runtime-test` in pack CI.** “Pack loads” check now substituted by laptop launch.

**Part B (laptop measurements after release, used to revise this report):**
- HotswapAgent `autoHotswap` with sandbox JBR: supported changes, seconds;
- Flashback nuclear replay in sandbox: command to “after 60 s” frame time;
- `./gradlew build` with MDK settings (daemon, configuration cache);
- sandbox startup/edit without queue;
- `gamescope --backend headless` versus nested KWin, only if KWin becomes a problem.

## 8. Sources

Project and build:
- MDK NeoForge 1.21.1: https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle (`build.gradle`, `gradle.properties`)
- ModDevGradle: https://github.com/neoforged/ModDevGradle , https://docs.neoforged.net/toolchain/docs/plugins/mdg/ ,
  https://plugins.gradle.org/plugin/net.neoforged.moddev , https://neoforged.net/news/moddevgradle2/
- Fabric Loom: https://docs.fabricmc.net/develop/loom ; Architectury Loom: https://github.com/architectury/architectury-loom ;
  Stonecutter: https://github.com/stonecutter-versioning/stonecutter-template-neoforge ;
  MultiLoader-Template: https://github.com/jaredlll08/MultiLoader-Template/tree/1.21.1
- Large-mod builds: Create https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/build.gradle ,
  Mekanism https://github.com/mekanism/Mekanism/blob/26.3/build.gradle ,
  AE2 https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/main/settings.gradle ,
  JEI https://github.com/mezz/JustEnoughItems/blob/26.3/build.gradle.kts ,
  Sodium https://github.com/CaffeineMC/sodium/blob/dev/neoforge/build.gradle.kts ,
  Iris https://github.com/IrisShaders/Iris/blob/26.1/neoforge/build.gradle.kts ,
  Veil https://github.com/FoundryMC/Veil/blob/1.21/build.gradle ,
  Distant Horizons https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/main/buildSrc/build.gradle

Launch, hot swap, reload:
- Fabric launch and hot swap: https://docs.fabricmc.net/develop/getting-started/intellij-idea/launching-the-game ;
  debugging, F3+T and `/reload`: https://docs.fabricmc.net/develop/debugging ; mixin swaps: https://wiki.fabricmc.net/tutorial:mixin_hotswaps
- MDG chooses launch JVM only by language version: https://github.com/neoforged/ModDevGradle/blob/main/src/main/java/net/neoforged/moddevgradle/internal/ModDevRunWorkflow.java
- JetBrains Runtime: https://github.com/JetBrains/JetBrainsRuntime/releases ; HotswapAgent: https://github.com/HotswapProjects/HotswapAgent
- Mixin swap issues: https://github.com/SpongePowered/Mixin/issues/592 , https://github.com/SpongePowered/Mixin/issues/408
- Iris keys (R, K, O): https://github.com/IrisShaders/Iris/blob/1.21.1/common/src/main/java/net/irisshaders/iris/Iris.java
- Veil editor and file watching: https://github.com/FoundryMC/Veil/blob/1.21/common/src/main/java/foundry/veil/impl/client/imgui/VeilImGuiCompat.java ,
  https://github.com/FoundryMC/Veil/blob/1.21/common/src/main/java/foundry/veil/impl/resource/VeilResourceManagerImpl.java ;
  ImGuiMC: https://github.com/FoundryMC/imguimc
- NeoForge config file watching: https://github.com/neoforged/FancyModLoader/blob/1.21.1/loader/src/main/java/net/neoforged/fml/config/ConfigWatcher.java ,
  https://docs.neoforged.net/docs/misc/config/

Tests and visual checks:
- NeoForge GameTest 1.21.1: https://docs.neoforged.net/docs/1.21.1/misc/gametest
- Fabric client screenshot tests: https://fabricmc.net/2025/03/24/1215.html , https://docs.fabricmc.net/develop/automatic-testing ,
  https://github.com/FabricMC/fabric/blob/HEAD/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext.java ,
  https://github.com/FabricMC/fabric/blob/HEAD/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/screenshot/TestScreenshotComparisonAlgorithm.java
- NeoForge has no client screenshot tests: https://github.com/neoforged/NeoForge/issues/3624
- mc-runtime-test: https://github.com/headlesshq/mc-runtime-test ; HeadlessMC: https://github.com/headlesshq/headlessmc/blob/HEAD/headlessmc-lwjgl/README.md
- Rendering-mod CI (build only): Sodium https://github.com/CaffeineMC/sodium/blob/dev/.github/workflows/build-pull-request.yml ,
  Iris https://github.com/IrisShaders/Iris/blob/26.1/.github/workflows/build.yml ,
  Veil https://github.com/FoundryMC/Veil/blob/1.21/.github/workflows/build.yml ,
  Flywheel https://github.com/Engine-Room/Flywheel/blob/26.1.2/dev/.github/workflows/build.yml ,
  Distant Horizons https://gitlab.com/distant-horizons-team/distant-horizons/-/blob/main/.gitlab-ci.yml ,
  Flashback https://github.com/Moulberry/Flashback
- RenderDoc: RenderNurse https://github.com/neoforged/RenderNurse , GFX-Debuggers https://modrinth.com/mod/gfx-debuggers ,
  shaderLABS https://shaderlabs.org/wiki/Attaching_Graphics_Debuggers_to_Minecraft
- Outside Minecraft: NVIDIA FLIP https://github.com/NVlabs/flip ; Bevy https://github.com/bevyengine/bevy/blob/main/.github/workflows/example-run.yml ;
  Mesa CI https://docs.mesa3d.org/ci/index.html

CI and release:
- setup-gradle: https://github.com/gradle/actions/blob/main/docs/setup-gradle.md , https://github.com/gradle/actions/releases
- CI with GameTest: Create https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/.github/workflows/build.yml ,
  JEI https://github.com/mezz/JustEnoughItems/blob/26.3/.github/workflows/tests.yml ,
  AE2 https://github.com/AppliedEnergistics/Applied-Energistics-2/blob/main/.github/workflows/build.yml ,
  Mekanism https://github.com/mekanism/Mekanism/blob/26.3/.github/workflows/gametests.yml
- Publishing: https://modmuss50.github.io/mod-publish-plugin/ , https://github.com/Kir-Antipov/mc-publish ,
  https://github.com/modrinth/minotaur ; changelog: https://keepachangelog.com/en/1.1.0/ , https://github.com/orhun/git-cliff
- Modrinth betas: https://api.modrinth.com/v2/project/sodium/version?loaders=%5B%22neoforge%22%5D ;
  Create issue forms: https://github.com/Creators-of-Create/Create/blob/mc1.21.1/dev/.github/ISSUE_TEMPLATE/crash.yml ;
  Crash Assistant: https://modrinth.com/mod/crash-assistant
- NeoForge advice: https://docs.neoforged.net/docs/1.21.1/gettingstarted/ , https://docs.neoforged.net/docs/1.21.1/gettingstarted/structuring
- AI tools: https://github.com/headlesshq/headlessmc-mcp , https://github.com/langyo/minecraft-mod-mcp , https://github.com/denfry/mcpfabric

Standard replacements (section 3):
- HotswapAgent, `autoHotswap`: https://github.com/HotswapProjects/HotswapAgent
- MDK `localRuntime`: https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/blob/main/build.gradle
- `gh pr checks --watch --fail-fast`: https://cli.github.com/manual/gh_pr_checks
- Path filter: https://github.com/dorny/paths-filter , https://docs.github.com/en/actions/writing-workflows/workflow-syntax-for-github-actions#onpushpull_requestpull_request_targetpathspaths-ignore
- Vanilla `/tick`: https://minecraft.wiki/w/Commands/tick ; client does not tick more than 20 times per second — `Minecraft.getTickTargetMillis`
  in NeoForge 21.1 sources (`Math.max(defaultValue, tickratemanager.millisecondsPerTick())`)
- gamescope, `--backend headless`: https://github.com/ValveSoftware/gamescope (backend list: `gamescope --help`)
- `mc-runtime-test`: https://github.com/headlesshq/mc-runtime-test

Not found: published modder measurements for “edit → game” (seconds for hot swap, 30–90 s restart are estimates);
Reddit posts from 2025–2026 (site would not open); Iris RenderDoc guide.
