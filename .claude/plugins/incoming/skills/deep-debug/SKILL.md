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
  name=incoming filename=…` saves the window around an event without stopping. In `prod_client.py` launches the render
  thread is named `main` in JFR (not `Render thread` as in its log); `jfr print` truncates stacks to 5 frames without
  `--stack-depth`. `INCOMING_JFR=profile` for the profile
  settings: `ExecutionSample` on Server thread, GC and safepoint pauses, `ThreadPark`/`JavaMonitorEnter`/`ThreadSleep`
  > 10 ms, `CPULoad`, `ThreadCPULoad` (thread running or waiting), `ObjectAllocationSample`, file IO. A Crafty server
  needs the same flags added to its JVM arguments. A Gradle run crashes at its first JVM-raised OutOfMemoryError
  (`-XX:+CrashOnOutOfMemoryError`) instead of hanging with its other threads alive: then the recording is
  `hs_err_pid*.jfr` beside `hs_err_pid*.log` in the run directory (`run.jfr` stays empty on the JDK 21 runtime).
  `prod_client.py` and Gradle's own daemon do not carry the flag.
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
- **Pixel defects** (dark spots, specks, holes, bands, flicker on an effect; Artem, 24.11: "no guessing, find the
  exact cause"): a RenderDoc capture of the defect frame, then pixel history and shader debug of 2–3 bad pixels beside
  a good neighbour. Log every term per pixel (march samples, density, transmittance, light and shadow terms, history
  blend) and name the term that differs, with its numbers, before any fix attempt. Changing a knob and re-judging
  frames is guessing.
- **Client CPU**: spark on the client, render thread time, frame p50/p95. More work or slower work: per-thread
  hardware counters, `LC_ALL=C perf stat -e instructions:u,cycles:u,task-clock --per-thread -p <pid> -I 100 -x,`
  (user processes may count their own threads), joined with the frame log: instructions per frame and IPC on the
  render thread split a heavier frame from one starved by other threads. `-p` counts only threads alive when perf
  attaches: start it after the world loads; take compiler-thread load from schedstat.
- **GPU clocks**: `nvidia-smi` log once a second; 45 s warm-up; the clock is written beside each view.
- **Frames**: the same spot, the same moment after the strike, against `main` (`tools/sandbox.sh shot`); the scene
  bench for frame cost.
- **Other vendors' drivers** (AMD, no such GPU here): `tools/amd_shader_check.py` gives AMD's own compiler output
  per program (VGPRs, scratch, code size, compiler memory and seconds); Mesa llvmpipe is the second compiler.
- **A cloud that pops, vanishes in a step or appears whole**: `-Dincoming.dev.smoke=true` (scenario jar; also a Flashback
  export through `prod_client.py --prop`) or `tools/dev_client.sh smoke on` logs every smoke source twice a second
  (kind, age, bounds, selection, sample age, clumps, masses): the source and the tick it ends, against its law.
- **Shaders**: the dev client held at the defect (look skill section 4: the burst once, `/tick freeze`, F3+T per
  change), a probe channel per term.

Traps: Dynamic FPS caps to 15 FPS exactly after 300 s (`dynamic_fps.json` `{"idle":{"timeout":0}}`); scenarios pin a
quality ladder step, compare only equal steps (frame time as in the game: `-Dincoming.pall.adaptive=true`);
`-Dincoming.nukeDiag=true` logs a client line per debris piece, 170–190 ms/s of the render thread as the wave reaches
the viewer: frame-time windows at the front run without it; a recorder or a starved client drops debris as late
(`nukeEvent … drop,server_late`), so its breach dust and their cost vanish from that run; in the `mushroom` scenario the firestorm sometimes dies out (check
`burning` in SCENARIO lines before comparing); GPU clocks jump; a single-player run adds the integrated server's
threads and its JIT to the client's CPU, so a hitch seen there is checked on a dedicated server too (`incoming.server`,
client-scenario.md) before it is called the player's.

## Checklist: look defect

The look skill holds the whole procedure (target, mechanism, technique probes, judging in motion); this skill's
rule of one full run per round applies to every look round.

## Checklist: bug from Artem's game

- His whole log: `latest.log` and rotated `logs/*.log.gz` for the session, `crash-reports/`, the server log for the
  same time. `tools/logscan.py --since HH:MM`, `tools/logtime.py LOG REGEX --since REGEX` for offsets from an event.
- A copy of his world and `server_map_cache` (copy first, then read; never touch the original). NBT of the needed
  chunks.
- His positions and teleports over time, game mode, distance to the event point.
- jar sha256 on the server and on his side, pack version.
- Clocks: the client log, the laptop and the Crafty server logs run on Warsaw time (UTC+1 in winter, UTC+2 in
  summer); file mtimes on netcup are UTC.
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
- Lag a bulk sender adds to game packets on one connection is the socket's unsent bytes at line rate plus `rtt` minus
  the base round trip, never `rtt` or `notsent` alone: with `tcp_notsent_lowat` the same queue leaves `notsent` (0 KB)
  and appears in `rtt` (300 ms+ on an LTE rig while LSS streamed). Compare runs only with the other senders' wire rate
  beside it: LSS's traffic in the same phase differed 3× between runs. Mod payload sizes are not wire bytes: Voxy
  chunk sections compress 5.7× on the connection.
- JFR names the client's render thread `main` (its OS name, in `/proc` schedstat and `top`, is `Render thread`);
  filtering JFR samples by `Render thread` finds none. Render-thread stall stacks carry Mojang's line numbers: map them
  with `javap -l -c` on the runtime class (work.md), never on the decompiled sources.
- Client JFR: the render thread is `main` in `ExecutionSample` (not "Render thread"), and JFR samples only a few
  threads per period, so with the client's dozens of busy threads its render-thread sample count is a small, uneven
  fraction of wall time: use it for shares inside the thread, never for milliseconds. Per-frame cost comes from a
  render-thread sampler and frame log in the scenario client (the `incoming.hitch` probe).
- The probe's per-frame GPU ms (GL timestamps) counts the GPU's idle gaps while the render thread waits for a core:
  at a 1 Mt city front frames showed 57–61 ms "GPU" at 8–46 % `nvidia-smi` utilisation, CPU-bound. Read GPU utilisation
  and `hitch-sched` run-queue wait before calling frames GPU-bound.
- A JIT burst at an event is mostly recompiles, not warm-up: split C2 compile time in the window by each method's
  history in the compile recording (first compile; recompile after a recorded `jdk.Deoptimization`; recompile without
  one: cold code flushed after `CodeCache GC Threshold` collections, or a dependency invalidation, `-Xlog:codecache*,
  dependencies`). At a 1 Mt city front 70–78 % of 20–27 CPU-s of C2 in 4 s were recompiles. Our own long methods compile
  to units 50× their bytecode (`javap -c` sizes): a 2,550-byte one recompiled at 100–135 kB each time.
- `MinecraftServer.getTickTimesNanos` misses work done in `ServerTickEvent.Post`: take tick cost from spark
  tickmonitor or tick-start intervals. GC-log "Reaching safepoint" lines inside ZGC concurrent phases are not game
  stops: game threads keep running.
- When the data looks clean but the player still sees the defect, probe the exact drawn output first (the per-frame
  value of the string or pixel he sees).
- A bug from Artem's game: his Flashback replay (instance `flashback/replays`, copy first) is a data source: the login
  packet gives the game mode, snapshots `set_time`, `move_entities` positions; read where he stood and when.
- Shader probes: in the dev client (look skill section 4) the cloud ages once, then `/tick freeze` holds it; each edited
  shader goes in by `tools/dev_client.sh swap` (F3+T); after toggling a mode wait ~80 ticks (temporal history); a
  probe channel writes one term's value as the pall output with alpha 0 (channel 39 = linear calibration ramp).
