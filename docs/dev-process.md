# How we develop: retrospective, fixes, next steps

Analysis as of 22.10.2026 (session D): what went wrong since 01.10, why, what is fixed and planned. Numbers come from
GitHub history, laptop journal, session transcripts and measurements; estimates are marked `(est.)`.

## Summary

- **CI is not the bottleneck.** Full checks take 4.5 min; PRs merge on average 0.5 h after opening.
- **Time went to the laptop and waiting.**
  - Full-pack recording took 11.4 h over 21–22.10. The 3.4.1 reference was rerecorded 12 times.
  - Local GameTest reruns took 8.6 h.
  - Heavy jobs overlapped for 5.7 h, interfering with each other.
  - Sessions spent 17.5 h polling.
  - Sessions ending turns mid-job were manually resumed 13+ times.
- **Main loss: reworking 3.5.0.** Visuals and sound merged without comparison to the previous release. Almost all of the
  21–22.10 batch (~$635 by session counters, ~15 laptop hours, (est.)) went to restoring 3.4.1 quality or better.
- **All cases share one root.** Rules followed Artem's complaints and lived as text in memory. No tool enforced them.
  Thus the fixes below are in tools, not new rule lines.

## 1. Measurement method

- **GitHub:**
  - PRs across three repositories since 01.10;
  - 1000 incoming `build` runs (since 01.10 15:31);
  - jobs and steps of 688 runs since 12.10 (`gh api`).
- **Laptop systemd journal** (since 21.10 20:28): 248 `incoming-job-*` jobs with time, CPU and memory.
- **Session transcripts and results:**
  - `~/.claude/projects/-mnt-data-projects-airstrike/*.jsonl` transcripts (27.09–02.10 and 21–22.10);
  - `mod/run/incoming-sessions/*.json` results;
  - 37 memory files.
- **Data gap.** Worktree session transcripts for 03.10–20.10 were not analyzed; this period relies on memory and GitHub.
- **Own build measurements** on the laptop at load 12–33 (other sessions recording/running GameTest alongside).
  - Absolute seconds are therefore inflated.
  - Before/after measured at equal load.

## 2. Retrospective: what went wrong and why

### 2.1. The 3.5.0 regression reached Artem (21.10 14:04)

**What happened.** Strikes looked/sounded worse than 3.4.1: cubic smoke, missing fire, Shahed like a “fly”. Pack rolled
back to 3.4.1.

**Cost.** I estimate probe P, sessions V, A, F, C, F2a/b/c and rebuilding S sessions on the new base as most of the
21–22.10 batch.

**Why.**
1. Why worse? Smoke moved to a nuclear-scale grid, sphere light was cut off, recording sped up.
2. Why did it pass? PRs #350–#354 checked law tests, unit tests and code review.
3. Why no visual comparison? Cloud sessions do not render under Iris; scenario frames were checked as proof of
   operation, not beside the previous release.
4. Why was that allowed? Process had no “no worse than previous release” step.
5. Why absent? Checks guarded laws; look/sound were considered consequences of laws. Model was correct, rendering bad.

**Previously fixed.**
- Comparison with 3.4.1 before merge (`tools/look_gate`).
- “Записка” check (`note.yml`).
- “How we work” section in CLAUDE.md.

**Remaining.** Comparison costs 25–77 GPU minutes per version and is not automatic (plan 5.2, 5.6).

### 2.2. Protection stopped sessions (21.10 18:58–21:01)

**What happened.** About 10 S2/S6 launches failed at the filter.

**Cost.** ~$38 and ~2 h delay. Artem had to redefine scope (“our own game model”).

**Why.** Specs in the thread described “real physics”: real SAM data, kill probability, ballistics. The rule “do not
put real flight logic in prompts” was in memory but applied only to cloud prompts.

**Previously fixed.** “Flight and air defense use our own game model” decision (“Principles” in CLAUDE.md).

### 2.3. Heavy jobs interfered with each other

**How much.** In 20 h (21.10 20:28 → 22.10 16:32), 248 jobs totaled 33.9 h; laptop busy 15.1 h:
- recording: 72 runs, 11.4 h;
- flight runs, baselines and look gates: 34 runs, 9.7 h;
- local GameTest: 41 runs, 8.6 h.

**Jobs using 6+ GB ran simultaneously.** 45 pairs overlapped for over a minute, totaling 5.7 h. Examples:
- F2a5 and F2b3 recordings overlapped 14 min;
- V recording overlapped S7 run and S2 look gate.

**Cost.** At load 40+, the probe server stalls, camera loses the missile, scene rerecorded (F2c).

**Why.** “One heavy job at a time” was a sentence in CLAUDE.md:
- `laptop_job.sh` launched immediately;
- `look_gate` checked other clients every 30 s, so two waiting recordings started together.

**Fixed:** #381.

### 2.4. Recording repeated after every edit; new rule did not reach sessions

**What happened.** Artem, 22.10 14:24: “everything is recorded again after every tiny change, seriously?” The rule
“record once at the end” entered session brief (`mod/run/incoming-sessions/context.md`, outside git) at 14:26.

**Why ineffective.** `context.md` attaches only at startup; running sessions never saw it:
- F2a recorded 7 more versions (~1.7 h);
- H ran ~2 h of GameTest on the laptop.

Each session rerecorded 3.4.1: 12 times, 1.4 h.

**Fixed:**
- new decisions for running sessions: #382 (`live.md`);
- GameTest reruns on GitHub: #385;
- quick single-effect preview: sandbox X (#379, in progress).

Reference storage is planned (5.2).

### 2.5. Background-session lifecycle is fragile

**Sessions died with parent** (22.10 01:16). S5/S8 stopped with their launching session, stalled ~3 h. Previously fixed:
`go.sh` launches sessions as separate systemd services.

**Editing `run.sh` live erased P/S3 results.** Bash resumes reading the edited file at the old offset.

**Sessions ended turns while laptop jobs ran.** Coordinator manually resumed:
- S5: 6 times;
- S6: 7 times;
- S4 and F2b: 2 times each.

One unnecessary S6 continuation cost $18.6.

Verified: a `-p` session waits for its background command within the same turn. Turn ends when job is detached or its
background command's `timeout` stops it (default 10 min, recording takes 25–77 min).

**Polling.** 249 `sleep`/poll calls, 17.5 h. Another 1.5 h watching CI.

**Fixed:** #382 (launcher in git, auto-resume, `timeout` rule), #383 (one-command CI wait).

### 2.6. Parallel PRs in one subsystem

**Conflicts.** Rebases conflicted on 21–22.10 for V, S5, S2, S6 (twice), G, H, W. Of 17 PRs that day:
- `docs/guide/weapons.md` changed in 10 PRs (same for Russian);
- `WeaponSpec.java` in 8;
- language files, `StrikeGameTests`, `CLAUDE.md` in 7 each.

**CI silently never starts.** GitHub does not run checks on conflicted PRs. V/S5 waited 10–20 min for such CI.

**Why.** 8–10 sessions worked simultaneously in one subsystem; rules require each PR to also update spec, guide and
notes.

**Fixed:** #383 (conflict immediately visible). Batch order planned (5.5).

### 2.7. Flaky GameTest

**How much.** Since 12.10, 51 of 506 full GameTest runs failed (10 %), 9 on `main` immediately after merge. Two causes:
flakes or PRs tested on old bases.

**Tests.** `stopFinishesQueue`, `radiusTest`, `craterFallsShareWorldCap`, `mobsFleeTheBlastAndCalmDown`,
`droneBreaksWoodToJarrett`, `loiter_rail.player.none.0`.

**Cost.** Every affected session reran CI shards. H has been chasing them for 7+ h.

**Why expensive.** One test could be repeated only via full CI or on the laptop.

**Fixed:** #385. Quarantine/current base planned (5.3, 5.4).

### 2.8. Local builds slowed unnoticed

**How much.** `./gradlew build` in a fresh tree took 2 min 46 s, unit tests 2 min 29 s. One test,
`AutopilotPropertiesTest.randomFlightsHitWithoutCircling`, took 117 of 140 s (84 %).

**Why.** Its comment promised “ten thousand scenarios in seconds”. Since S7 (#363, 21.10), every flight tick surveys
terrain ahead. JFR profile: 82 % `Autopilot.reliefAhead → Corridor.highest → GridWalk`. Test was single-threaded;
nobody watched test durations.

**Fixed:**
- #380: test on all cores;
- #384: warm daemon for edits.

Test duration in CI summary planned (5.7).

### 2.9. Lessons from memory (01–18.10)

- A finished job went unnoticed for ~1 h.
- `pgrep -f` matched itself: look gate waited ~50 min needlessly.
- Nested KWin broke Artem's Prism.
- Trailer looped for 24 min through 13 clients.
- Five TikTok previews completely redone.
- Two unnecessary full GameTest runs (#290).
- Disk cleanup deleted `film-war` and trailer music.
- Built a local server instead of Crafty.
- Coordinator ran 92 h over 28.09–02.10 ($518): 685 wakeups, 553 “no reply needed”.

**Common pattern:** rule follows complaint, held by memory. Once a rule became a tool (`nested_kwin.sh`, “Итог” and
“Записка” checks), the error did not recur. Before #381, `laptop_job.sh` managed the service but not the queue, so heavy
jobs overlapped (2.3).

## 3. Where time goes: by stage

| Stage | Measurement | Assessment |
|---|---|---|
| CI, full check | 4.5 min median, p90 5.2; 23 machine-minutes/run; `docs/`, `tools/` edits skip mod build | good |
| CI GameTest shard | 4 min 16 s: setup 30 s, compile 31 s, server 21 s, tests 2 min 51 s | good |
| CI flakes | 10 % of GameTest runs, 9 red `main` runs | bad → #385, plan 5.3–5.4 |
| Local no-change build | 13–16 s without daemon, 2 s warm | → #384 |
| Edit → compile | 15–18 s → 3–6 s with daemon | → #384 |
| Edit → `build` with tests | 220 s → 103 s (#380) → 75–92 s (also daemon); load 25–33 | → #380, #384 |
| Gradle configuration | 1.3 s; new-tree compilation from shared build cache | configuration cache would save ~1 s, not worth it |
| Flight sweep, 16 seeds | ≈2.7 h | rarely needed in full |
| Full-pack recording | 25–77 min/version | main expense → sandbox X, plan 5.2 |
| Sound | build seconds, comparison uses same recording | good |
| Review/merge | median 0.5 h, p90 2.6 h | good |
| Release/pack | 21 releases in October, `release=true` and pack PR take minutes | good |
| Background sessions | 17.5 h waiting, 13+ manual resumptions | → #382, #383 |
| Session brief | `context.md` 63 KB (~16 thousand tokens) per session/continuation | plan 5.6 |

## 4. Completed (22.10)

Each in a separate PR, with before/after measurements.

| PR | Change | Before | After |
|---|---|---|---|
| [#380](https://github.com/zentixua/incoming/pull/380) | 10 000 `AutopilotPropertiesTest` flights on all cores; same result (10 000 hits, 757 terrain-blocked) | test 76.5 s (load 12) / 117 s (load 24); edit → `build` 220 s | 11.1 s / 19 s; edit → `build` 103 s |
| [#381](https://github.com/zentixua/incoming/pull/381) | `laptop_job.sh`: heavy jobs one at a time (lock inside service), `--gpu` also waits for Artem's game to close, `--light` bypasses queue, `--status`, final exit-code line | 45 heavy-job overlaps, 5.7 h | second job waits for first; no overlap by design |
| [#382](https://github.com/zentixua/incoming/pull/382) | `tools/sessions/go.sh`: session launcher in git, turn result in `sessions.log`, auto-resumes after its jobs end; `live.md` for new decisions reaching running sessions | 13+ manual resumptions; 14:26 rule missed F2a/H | trial session ended turn with running job and resumed automatically on completion |
| [#383](https://github.com/zentixua/incoming/pull/383) | `tools/pr_ci.sh <PR>` waits for “Итог”/“Записка”, code 3 on conflict, failed-log tail | 1.5 h watching CI; 10–20 min of “silent” CI on conflicts | one background command; #380: “green, waited 3 min”; uses conclusion for jobs GitHub leaves “running” after completion (as happened to #383's own “Итог”) |
| [#384](https://github.com/zentixua/incoming/pull/384) | edits/tests on warm Gradle daemon (`./gradlew --daemon build`), game runs without it; daemon exits after 10 min | no changes 13–16 s, compile 15–18 s | 2 s, 3–6 s |
| [#385](https://github.com/zentixua/incoming/pull/385) | manual CI “only these GameTest batches, N repeats on six machines” and `tools/ci_repeat.sh` | laptop flake reruns (H: 41 runs, 8.6 h) or full CI | 18 batch runs in 2.6 min on GitHub, laptop free; no matching batch fails instead of “0 of 0 passed” |

## 5. Plan: next steps

Ordered by gain per effort. Gains estimated from measurements above.

**5.1. New sessions only via `tools/sessions/go.sh`, not `mod/run/incoming-sessions/*.sh`.**
- Gain: fewer manual resumptions and edit-erased results.
- Cost: coordinator change, minutes.

**5.2. Record reference once.** `look_gate` stores frames in `look-gate/ref/<key>/<scene>`. Key is tree's mod-code hash +
`ProbeScenario` hash + frame size + hearing state. Existing key means scene skipped. Same for `main` (recorded as
`Cmain`, `S6main`, `Fmain`).
- Gain: 25–40 GPU minutes/session (1.4 h on 3.4.1 in one day).
- Cost: ~2 h.
- Waits for #369 (folder rename touches `look_gate/run.sh`) and sandbox X (also records).

**5.3. Flake quarantine.** Known unstable GameTest gets `required = false` and a `gametest.md` line: test, fixer, deadline.
Sessions stop rerunning others' flakes.
- Gain: almost no red runs from flakes.
- Cost: rule + one line per test.
- Risk: a flake becoming a real bug will not block merge. Therefore deadline required.

**5.4. Merge only with current base.** PR tested on current `main`. As far as I know, GitHub merge queues are only for
organization repositories (not checked). Thus either branch setting “Require branches to be up to date” or merge-script
check.
- Gain: 9 red `main` runs in 10 days → 0.
- Cost: +1 CI run (4.5 min) when `main` advances.

**5.5. Session batches without collisions.** Same-subsystem sessions in a chain. If parallel, guide, spec and rule notes
in one final batch PR.
- Gain: conflicts in 8 of 17 PRs → rare.
- Cost: changes “guide in same PR” rule, Artem's decision.

**5.6. Short brief and visuals without GPU.**
- Brief: rules/links, details in on-demand files: ~16 thousand tokens per session/continuation → ~3 thousand (est.).
- Visuals without GPU, after sandbox X:
  - reference frames of one effect at fixed times (0.1/1/10/60 s) with comparison threshold (SSIM/FLIP, as studios use);
  - Flashback strike replay instead of new flight, comparing versions on one trajectory.
- Cost: brief one hour; visuals a large task.

**5.7. Visible test durations.** Tests over 10 s appear in CI step summary, preventing silent accumulation as in 2.8.
- Cost: ~10 lines in `build.yml`.

**Not taking:**
- **Gradle configuration cache:** configuration 1.3 s, would require rewriting build script.
- **More GameTest shards:** each has ~1 min overhead, CI already 4.5 min.
- **Parallel JUnit classes:** other tests total ~20 s, shared static code risks flakes.

## 6. What works and stays

- **CI:**
  - checks selected by changed paths;
  - 6 GameTest shards by duration table;
  - “Итог” as single status;
  - median 4.5 min.
- **Shared Gradle build cache:** new-tree compilation from cache.
- **Small PRs:** median 467 lines, 0.5 h to merge.
- **Release:** manual `release=true` and open-skies pack PR in minutes.
- **Sessions/jobs in separate systemd services.**
- **Gates:** “no worse than previous release” and “Записка”.
- **Fresh-context review.** Across these six PRs it found four errors CI would miss:
  - recording waiting for game held the queue;
  - a departed launcher's job would start later unattended;
  - `go.sh` failed from main checkout;
  - repeating by test instead of batch name reported success while running nothing.
