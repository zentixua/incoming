---
name: deep-debug
description: "Any lag, freeze, FPS drop, bug from Artem's game or odd mod behavior: collect absolutely all debug data in the first pass, name the cause with a number, no guesses."
---

# Deep debug: collect everything in the first pass

Artem's rule (08.11.2026, 07:16 and 08:40): "don't drag it out: collect absolutely all possible debug data at once, so
you know exactly what is happening and why", "as complete and rich as possible… no guessing at all". Every
"measured → not enough → rerun" loop costs hours. The first run must already show where, what and why; then the fix
takes minutes.

Use it for: server lag, freezes, stutter, FPS drops, graphical artifacts, a bug from Artem's or friends' game, a crash,
"ruins in the wrong place / at the wrong time", any behavior that does not match expectations.
Do not use it for: an approved change without a problem. That has its own CI and merge; extra runs are not needed
(AGENTS.md workflow: no repeated verification).

## Main rules

1. **One run, the full set.** A second run only verifies the fix; it does not collect missing data. Before starting,
   go through the checklist below and ask: "if my hypothesis fails, what will I need?" Collect that now too.
2. **Data first, conclusion second.** A cause is named with a number: ms, share of samples, tick, chunk, stack, class.
   Without a number it is a hypothesis, and we call it that. An eyeballed model was wrong twice (smoke estimate,
   PR #460).
3. **Check that the gauge measures what you think.** Trap examples below. Before concluding, make sure every probe
   actually recorded (JFR not empty, Almighty rules not disabled by budget, flags visible in the log).
4. **Separate layers.** Inside a tick or between ticks; our code, vanilla, other mods, GC, host. Server or client. CPU
   or GPU. Each share with its own number.
5. **Truth from outside, not only our counters.** Check world state directly (chunk status via `getChunkNow`, NBT of a
   world copy, bot packets), not only our "done" counters.
6. **Each term separately.** The first "cause" is often false (a "chunk edges" defect can be samples below ground,
   visible only to a per-term probe). Measure each term, not the total.
7. **Root, not symptom.** Fix the cause found by a number. Then a test that catches a recurrence, and a repeat of the
   same run on the same rig: before and after numbers in one table.

## Checklist: server (ticks, freezes, stutter)

Where: netcup (Crafty test server 25571 or `./gradlew runServer`), not the cloud: JFR `ExecutionSample` barely works
in the cloud VM. Never touch the play servers (25565–25567) or their worlds without Artem's word.

- **Always on in every Gradle run and `prod_client.py`** (build.gradle): a JFR ring of the last 30 minutes dumped on
  exit to `<run dir>/run.jfr` and the GC/safepoint log `<run dir>/gc.log` (`prod_client.py`: in its `logs/`); while running, `jcmd <pid> JFR.dump
  name=incoming filename=…` saves the window around an event without stopping. `INCOMING_JFR=profile` for the profile
  settings: `ExecutionSample` on Server thread, GC and safepoint pauses, `ThreadPark`/`JavaMonitorEnter`/`ThreadSleep`
  > 10 ms, `CPULoad`, `ThreadCPULoad` (thread running or waiting), `ObjectAllocationSample`, file IO. A Crafty server
  needs the same flags added to its JVM arguments. A Gradle run crashes at its first heap OutOfMemoryError
  (`-XX:+CrashOnOutOfMemoryError`; `hs_err_pid*.log` and `.jfr` beside `run.jfr`) instead of hanging with its other
  threads alive.
- **spark** (copied from the host's instance into `runClient`, `runServer`, client scenarios and the sandbox; not into
  GameTest, flight sweeps, the stress rig or machines without the instance): `tickmonitor` and the server profiler. Remember: tickmonitor measures intervals between tick **starts**,
  not tick length.
- **Mod log**: DEBUG for `ua.zentix.incoming`, `-Dincoming.nukeDiag=true` for the nuke. Lines: "Mod work over 30 s"
  (tick, mod lanes, "the rest", unload debt), "Server thread stalled" (StallWatch, stacks), "Can't keep up",
  "Ruin settlement #", "DIAG", "Ruins for detonation", autosaves, RuinWorkers count changes.
- **Stacks**: a series of jstack at event moments (detonation, wave arrival, player jump) plus a short JFR window
  around them. Never `jstack -l` or `dumpAllThreads(true, true)` as a periodic probe: finding ownable synchronizers
  walks the heap at a safepoint, 115–250 ms per call on an 8 GB JVM; use plain `jstack`, log `-Xlog:safepoint` and
  subtract thread-dump time from stall windows.
- **Host**: vCPU, load, who else uses CPU (second server, Artem's Docker services), RAM and swap, disk
  (`vmstat 1`, `pidstat 1`, `iostat 1` to a file for the whole run).
- **Players and world**: Almighty bots stand where the player stood; positions and mode every 20 ticks. Almighty rules
  as the truth log: `ChunkWatchEvent.Watch/UnWatch` (what was queued for the player and in which state; `Watch` fires at
  queueing, before `PlayerChunkSenderMixin` may withhold the send: never count it as "sent intact"),
  `ChunkEvent.Load/Unload`, `ChunkDataEvent.Load`. Own `state` keys per rule, raised `budget_ms`; after start check
  that no rule is `off`.
- **Queue state**: a dump through `script` at event moments (+1 s, +5 s, +20 s): queue sizes, the state of each
  "stuck" object and why it waits.
- **Version**: jar sha256, commit, configs (`incoming-server.toml`, heap, JVM flags) in the run README.

Analysis: Server thread samples first inclusive (top 40) and by self (top 30), then by category: our code by class,
`ChunkMap` (read/`ChunkSerializer`, write, `processUnloads`, `protoChunkToFullChunk`), `DistanceManager`, light, level
tick, network, other mods by package (DH, LSS, MapSyncer, Sable, Create…). Separately for long and normal ticks. How
many long ticks coincided with GC. Server ms per loaded chunk. Distribution in 5-minute buckets: p50, p95, max, share
> 60 ms; before the event, during, after (baseline).

## Checklist: client (FPS, graphics, shaders)

Where: Artem's laptop, only when he is not playing (`tools/gpu_lease.sh --wait`), only world copies.

- **GPU time per pass**: `LayerTimes` (`-Dincoming.fx.drain=true` or F3), the bench line.
- **RenderDoc**: frame capture in `mod/run/renderdoc/`, cost per draw call, overdraw, analysis with its Python
  (`renderdoccmd` needs `-d "$PWD"`; run the qrenderdoc Python analysis outside nested KWin).
- **Client CPU**: spark on the client, render thread time, frame p50/p95.
- **GPU clocks**: `nvidia-smi` log once a second; 45 s warm-up; the clock is written beside each view.
- **Frames**: the same spot, the same moment after the strike, against `main` (`tools/sandbox.sh shot`); the scene
  bench for frame cost.
- **Shaders**: the hold rig (age the cloud once, keep the client, hot reload), a probe channel per term.

Traps: Dynamic FPS caps to 15 FPS exactly after 300 s (`dynamic_fps.json` `{"idle":{"timeout":0}}`); scenarios pin a
quality ladder step, compare only equal steps; in the `mushroom` scenario the firestorm sometimes dies out (check
`burning` in SCENARIO lines before comparing); GPU clocks jump.

## Checklist: look defect (our picture against the reference)

A look task converges only when the target is fixed, measured the same way on both sides, and drawn by a representation
that can produce it. Tuning constants inside a representation that cannot is the slow failure: no number moves.

- **Target, frozen.** In the plan, before code: the reference frames (real footage, then film VFX; never another
  renderer's look, such as a shader pack's clouds, as the reference), Artem's decisions, the judge's views and the done
  numbers. Judge in vanilla (AGENTS.md "Vanilla first"); a shader pack gets one "not broken" check.
- **Gate script proven on the reference.** The script that gates our frames (crop, mask, luma, hue/saturation, contrast
  p90/p10, feature size, churn per second) first runs on the reference: it must pass the reference and fail the current
  `main` frame on the reported defect. Otherwise it does not measure the defect.
- **Existing system first.** Name the mod system that already draws the nearest phenomenon and show one frame of it at the
  target's scale: billows, dust rings and smoke are lit puffs (`far/SmokePuffs`, six-way light maps; sources
  `FrontDust`, `BlastDust`); seeing inside dust is distance fog; thin haze is the pall march. A new renderer only when
  that frame shows why the existing one fails.
- **Picture apart from physics.** The drawing takes where, when and how much from the law; probes, sound and gameplay
  read the law, never the drawing, so a look change cannot move a physical quantity or break its tests.
- **Prototype before integration** (about 2 h): CPU vanilla frames of the chosen representation at the judge's views,
  beside the reference and `main`, before any law, probe or budget work.
- **Rounds.** One short CPU job per question (minutes), read as soon as it ends; each round changes the cause the last
  one named by number. Three rounds or the timebox without a measured shift: stop for the root cause (AGENTS.md).
- **Judge.** A fresh Claude session gets `main`|PR pairs, the reference frames, the law's numbers for the scene and the
  done numbers; never the author's notes. Codex reviews the code only.

## Checklist: bug from Artem's game

- His whole log: `latest.log` and rotated `logs/*.log.gz` for the session, `crash-reports/`, the server log for the
  same time. `tools/logscan.py --since HH:MM`, `tools/logtime.py LOG REGEX --since REGEX` for offsets from an event.
- A copy of his world and `server_map_cache` (copy first, then read; never touch the original). NBT of the needed
  chunks.
- His positions and teleports over time, game mode, distance to the event point.
- jar sha256 on the server and on his side, pack version.
- Clocks: MC log and laptop in UTC+2, file mtimes in UTC.
- Then a bot repeat on test server 25571 following his scenario (same coordinates, moments, mode) with the whole
  server checklist at once.

## Checklist: logic and behavior (not performance)

- Per-object log: tick, chunk/entity, state, reason for the decision.
- Internal state dump at key moments (`script`, DIAG methods).
- Truth from outside (world, packets, NBT) compared with what the mod thinks.
- A GameTest that reproduces the bug before the fix and passes after.

## Brief for a run (RC on netcup, laptop, Codex)

One brief holds everything at once:
- the goal and what exactly must become visible; the main hypothesis **and** alternatives;
- all probes from the relevant checklist and the time window (for example, from −5 s to +6 min after detonation);
- a new results folder (`~/airstrike-work/<run>/analysis-*/`); do not change existing files;
- heavy analysis under `nice -n 19`, netcup host keeps ≥ 4 GB free memory;
- at the start, check that every probe records; if something is missing, stop and restart rather than make it up
  later;
- a text report: top 10 numbers first, then tables, the folder path; then stop the server.

## Answer to Artem

In Russian, briefly: the cause in one sentence with a number, what we fix, when the check happens. Tables and raw data
go in run files and the PR, not in chat. New knowledge (a gauge trap, a new probe, the path to analysis scripts) goes
into that area's `.claude/rules` or this skill by PR in the same thread; status and numbers go into the report.

## Gauges that lie
- `MinecraftServer.getTickTimesNanos` misses work done in `ServerTickEvent.Post`: take tick cost from spark
  tickmonitor or tick-start intervals. GC-log "Reaching safepoint" lines inside ZGC concurrent phases are not game
  stops: game threads keep running.
- When the data looks clean but the player still sees the defect, probe the exact drawn output first (the per-frame
  value of the string or pixel he sees).
- A bug from Artem's game: his Flashback replay (instance `flashback/replays`, copy first) is a data source: the login
  packet gives the game mode, snapshots `set_time`, `move_entities` positions; read where he stood and when.
- Shader probes: the hold rig ages the cloud once (dust off, render distance 2), then the client polls command files
  (`tp <up> <pitch>`, `prop <key> <value>`, `reload`, `shot <name>`, `quit`); copy edited shaders into
  `mod/build/resources/main` and `reload` (~50 s); after toggling a mode wait ~80 ticks (temporal history); a probe
  channel writes one term's value as the pall output with alpha 0 (channel 39 = linear calibration ramp).
