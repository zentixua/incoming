# Scene bench — frame time and captures beside the baseline

The bench captures one Incoming version in identical scenes of real strikes, comparing it with a baseline
(PRs: `main` head with a green build; acceptance and one-off comparisons: a release) captured as a pair in
the same session: CPU/GPU frame time by render stage, server tick, reviewer frames. A change that can alter
frame cost (render/FX code, shaders, particle or draw counts) cannot merge if frame time at 100 strikes is worse than
`main` (AGENTS.md, Workflow; Artem, 24.10); the bench runs once on its final head, and other changes skip it (Artem, 30.10).
Below, "accepted" means the pair baseline. The sandbox (`tools/sandbox.sh`) remains for frames during work;
the bench is for PRs and releases.

## Why a separate bench
Sandbox and `ProbeScenario` live in the captured version devtest; neither exists in 3.4.1/3.5.0, and the
sandbox explosion packet is internal. The harness is therefore a separate client mod without an Incoming
dependency (its own MDG Gradle project, like `tools/replayscan`). Incoming-specific data live in `scenes.json`:
commands, projectile type, probe method names. The identical bench captures any Incoming 3.4.1+ jar;
only that jar changes between columns.

## Structure
- `scenes.json`: sites (`F`, `C`, `D`, `L1`–`L3`), cameras, salvos, measurement windows. `plan.py` expands them
  into harness steps (`stand-plan.json`: camera, command, salvo, wait for hits, shot, window). All geometry
  lives here and is covered by a test.
- Mod `stand` (`src/main/java/ua/zentix/stand`) creates a fresh flat world from the title screen (seed,
  layers and noon match the Incoming sandbox; no day cycle, weather or mobs; spectator), then executes steps:
  - `FrameClock`, every frame: interval between frames; render-thread CPU from `RenderFrameEvent.Pre` to
    `Post` (`ThreadMXBean`, like mod `FxLayer.cpuNanos`; excludes buffer swap, preemption and driver sleep),
    plus wall-clock duration for reporting; GPU `glQueryCounter(GL_TIMESTAMP)` at frame start/end and
    each `RenderLevelStageEvent` before/after handlers (`after_level+` is the Incoming effects layer).
    Read timestamps several frames later without waiting for the GPU.
  - `ServerWatch` (rule: `Impacts`, test `ImpactsTest`): a hit requires a projectile (`incoming:drone`)
    removed or killed inside its salvo target radius, and its warhead detonation starting
    (`ExplosionEvent.Start`, damage type `incoming:strike` from `scenes.json`; a pre-arming crash uses a
    vanilla explosion, not a hit). Explosion projectile is its source or direct damage source; in
    3.4.1–3.7.0 the warhead supplies it only to damage, exposed by `accesstransformer.cfg`. Either order,
    no timeout (impact work runs under the tick budget). A nearby different projectile explosion,
    secondary detonation of the same projectile, or explosion without one confirms nothing. Removal
    without explosion means off-world flight (`VirtualFlights.park` also removes the entity); rejoining
    with the same UUID cancels it. Once a second: server tick, entity count and wall time, used to align
    events with machine-load recording.
  - `Probes`: once a second, reflect `FxLayer.describeTiming` and `FarRenderer.describeSmoke` ("n/a" if
    absent); once, Iris pack and whether it renders (`isPackInUseQuick`: Iris keeps the name even if
    shader compilation failed and vanilla renders; wrong pack or absent pack pipeline invalidates a run).
- Real strikes: `/incoming salvo drone 1 0 at x z from x z via x z` (same syntax since 3.4.1), one zero-spread
  command per strike. Incoming spread uses shared world RNG (`SalvoData.fire`: `level.random`; no command
  or world-rule seed in 3.4.1 or later), so runs would land differently: the edge camera alternates inside
  and beside the dust wall, GPU time differs 30 % between same-version runs. Plan-defined impact points
  follow a Vogel sunflower in a spread disk (uniform area, per-salvo/wave rotation), identical across
  versions; Shahed miss remains (σ 3 m from spec). Launch from 10 points at 400 m, each once per 1,5 s
  (one launcher releases a Shahed every 1–2 s); route through the impact point itself (without a waypoint
  it loops and approaches from behind the shooter). Bench world rule `drone_flight_time = 5`
  (`config/incoming-server.toml`; default 50 s, never shorter than the straight path): irrelevant after
  impact, shortens each pack run by ~3 min. Salvo ends when all hits arrive; otherwise when no world
  projectiles or removals awaiting explosion remain for 30 s after the last hit; otherwise timeout
  (valid only with all hits). Off-world projectiles (`VirtualFlights`) are not entities, so "no
  projectiles" alone is insufficient.
- Alongside mods: only open-skies Sodium and Iris; packs: no pack (Iris disabled), Complementary Unbound,
  Photon; files by sha512 from modpack `pack/*.pw.toml`. Window 2560×1600, vsync off, unlimited fps,
  render distance 12, particles all. Driver shader cache disabled (`__GL_SHADER_DISK_CACHE=0`): every
  first strike includes shader compilation, like a fresh game. `__GL_YIELD` is player default: inside
  Pre → Post the driver does not wait for GPU (A/B 25.10: thread CPU equals wall-clock Pre → Post within
  0,5 %, with or without `USLEEP`). Audio goes to a file, removed after the run.
- Machine load (pack `host.jsonl`), report only: every 5 s, whole-machine CPU and job cgroup CPU
  (`client.Host`, containing client, KWin and JVM), foreign = all − own; neighbouring services (who),
  power profile, load average, GPU temperature/frequency. Continuous GPU (`client.GpuWatch`:
  `nvidia-smi pmon -d 1 -o DT`), foreign % SM per cycle; measured only with our client SM number. Before
  client: wait for quiet (10 s without heavy foreign load, at most 2 min; pair waits only before A1,
  B/A2 follow immediately). Load does not determine validity: sampling every few seconds cannot prove
  quiet at every instant (two review rounds 25.10 on cycle coverage); the same-session pair protects
  the look gate from steady foreign load and GPU heating ("Look gate"). Verdict-window load lines
  (`results.load_notes`) explain non-reproduced failures in the report. Client renders another 3 s
  after the last window (`end_tail_s`).

## Scenes (one run per pack, strikes accumulate)
1. `first`: first strike after loading, Shahed 60 m ahead; worst frame from impact through +5 s.
2. `one`: 1 strike at `C` (launched with first, 20 s later), outside camera; frames +0,5 / +3 / +15 s.
3. `16`, `40`, `100`: add up to the count at `C`, spread 40 / 80 / 120 m; cameras outside (200 m, 30 m
   above ground), edge (spread boundary, 10 m, inward), inside (centre, 4 m). Each has a frame and 10 s
   window, 20 s for "100", at the same time since last salvo explosion in every run: first camera after
   15 s (`since_last_s`), others after frame, window and 10 s move (`tp_gap_s`). Salvo end and moves vary,
   smoke changes over tens of seconds (longer gated windows reduce scene-content noise). Move camera
   over ready ground before the salvo, not alongside it; otherwise camera chunks race off-world
   projectiles and server stalls at 50 ms/tick. "100" also has impact window `100-hit`, 0–10 s from hit
   10 (not first: a Shahed arriving 6 s early shifts it), outside camera (ball, flash, wave, flying
   debris), frames +0,2 / +1 / +3 / +10 s. Salvo still lands; `100/*` windows follow its end. Not gated.
4. `acc`: 3 minutes accumulating at `D` (3 km from `C`), waves of 10 Shaheds (five launchers, two rounds
   1,5 s apart) every 20 s, edge camera. Frame time in 10 s bins from first hit; growth is last minute
   against first. Not 4 minutes as in the spec: 20 s "100" windows would push three packs beyond 30 GPU
   minutes (cut scene, not pack), while the last minute after 3 minutes already follows 60 strikes
   versus 30 in the first. Valid only if load falls within the window: all wave commands executed
   within 2 s of schedule, no hits after it, first/last minute counts follow plan (30 ± one wave:
   window begins at first hit, Shahed flight varies ±3 s, part of a wave crosses the minute boundary).
   Otherwise the minutes are incomparable.
5. Nearby launch: `grad` (12 Grad rockets in one command), `missile` (1 cruise missile), `shahed`
   (5 boosted Shaheds, every 1,5 s), each at its own `L1`–`L3` (3, 6, 9 km south of `F`). Camera 30 m
   north at eye height faces the launcher; target 2,5 km east (nearly across view, impacts off-screen).
   Command-relative windows: `start` 0–15 s (ground jet, launch cloud forming), `hang` 30–40 s (cloud
   hangs); frame at each end (`<id>_15s`, `<id>_40s`). No completion wait, so these scenes are last and
   later cameras face south away from earlier impacts. Not gated; capture subsets
   (`--scenes 100,grad,missile,shahed`).

## Run
```sh
tools/stand/run.sh <name> <jar> [--against <accepted jar>] [--packs none,complementary,photon] [--scenes 100] [--size 2560x1600] [--label …] [--tries 1]
```
`<jar>`: release (`v3.7.0`), `ci:<branch|SHA>` (`incoming-jar` artifact of build), or jar path. Prepare
without GPU: jar (and accepted jar), mods, packs, client script (`stand.py prepare`). Clients run in one
`tools/laptop_job.sh --gpu --wait` job (queued lease, waits for Artem game to close), in windowless nested
KWin. Release the lease on every exit, including signals (if a job outlives its wrapper, when its client
exits). Bench directories: `mod/run/stand/` in main repo copy, shared by git worktrees (override with
`INCOMING_STAND_ROOT`). Output: `mod/run/stand/out/<name>/`; single run: `results.json`, per-pack
`frames.csv`, `events.jsonl`, `host.jsonl`, `shots/`, `latest.log`; pair: `pair.json`, `verdict.json`,
`A1/`, `B/`, `A2/` (second pair: `A1-2/`, `B-2/`, `A2-2/`, further by `--tries`; warm-up `warmup/`
excluded from verdict). During work, use `--packs none --scenes 100` (~2–3 GPU min; triple with
`--against`). `--tries 1`: one pair per pack, no repeat (acceptance: a regression the bench must catch
does not deserve a second pair).

```sh
python3 tools/stand/stand.py pr <number> --post          # Visual PR: head CI jar paired with main (--against
                                                       # overrides baseline), scene "100", verdict,
                                                       # "Стенд сцен" status and comment
python3 tools/stand/stand.py gate <name>                 # Rejudge raw pair data → verdict.json
uv run tools/stand/report.py <name> [--ref <name>=3.4.1]  # Pair report, extra runs in tables/sheets
```
`report.py` writes `report.md` (scene/camera tables, GPU stages in "100" windows, first strike,
accumulation), `comment.md` (PR table), frame sheets `sheet-<pack>.png` (version rows, scene columns),
accumulation plot `acc-<pack>.png`, and `look.json` in the run directory.

## Look gate (`gate.py`, test `test_gate.py`)
Laptop GPU reaches 85–88 °C in windows, clocks vary 1700–2550 MHz between runs (cannot lock without root);
two quiet same-version runs in separate sessions differed 6–29 % in GPU p50. Steady foreign CPU load of
3–6 cores raises render-thread CPU by 30–45 % (measurements 25.10). A previously captured cached accepted
run is incomparable: two v3.7.0 calibrations required up to 80 % tolerance. Capture accepted in the same
session as candidate, surrounding it: per pack A1 (accepted), B (candidate), A2 (accepted), consecutively
under one lease; warm-up before first pack (cold first-run GPU clocks higher). Heating, clocks and steady
foreign load affect A/B equally; baseline is the worse A1/A2, what accepted produced this session. The
same jar differs in "100" windows by 10–15 % inside smoke, up to 1,7 times outside (smoke alternates
in/out of frame; acceptance 25.10, v3.7.0 against itself); targeted regressions (3.5.0/3.7.0 against 3.4.1)
are 2,6–9,3 times.

A pack fails when candidate GPU p50 or render-thread CPU p95 in any `100/*` camera window exceeds the
worse A1/A2 by 25 % (exactly 25 % passes), or a window/number is missing; when a pair run is invalid
(command failure, incomplete salvo: up to 10 % dying in air over other explosions allowed; camera fails
within 30 s to reach ready terrain (`Terrain`: all chunks Sodium will render arrived, server view with
3×3 neighbours; every section Sodium included is built/uploaded; readiness holds throughout the step
pause; failure states what camera awaited); salvo camera frame mistimed, earlier than due since last
run hit (projectile arrives after salvo ended by silence) or over 2 s late; missing plan window or too
few frames; wrong Iris pack, shader compilation failure, wrong frame size); or capture uses another
GPU. If captured, also gate first strike (worst frame over twice baseline and over 100 ms) and
accumulation growth beyond accepted growth. Wall-clock Pre → Post and frame p95 are report-only.
v3.7.0 against itself stayed within +16 % of worse A (GPU, `100/edge`); 2,6-fold regression on the same
numbers fails (`test_same_jar_in_one_session_passes`). Failed packs (metrics or invalid run) immediately
get a second pair, which decides: a failure absent on repeat passes (one false failure must not cost a
PR its merge; true regression repeats). Numbers do not judge visuals (10–40 % pixel noise); noticeably
different smoky-pixel share or brightness against nearest A1/A2 is flagged for the reviewer. Verdicts
(`stand.py gate`, `pr`, `report.py`: one path, `stand.judge_pair`) always reparse raw pair data; parser
changes need no recapture.

PR look gate uses scene "100" (merge condition: frame time at 100 strikes; ~2–3 min per run, 9 runs plus
warm-up). First strike and accumulation belong to a full run (`run.sh` without `--scenes`) and its report;
they gate only if included in the captured pair. "Стенд сцен" status (`stand.py pr --post`) requires all
packs, scene "100", default window. PRs changing nothing a player sees/hears (`facing.py`, both old/new
renamed paths) receive status immediately: "bench not needed". `stand.py pr <number> --rejudge --post`
rejudges/posts an already captured pair for the same head without capture (after parser changes); only
if that captured pair itself is complete (all packs, scene "100", extra scenes allowed, default window).

## Budget
Target: at most 30 GPU min per PR (3 packs). A "100" pair: warm-up and 9 runs of ~2–3 min, ~25–30 min;
each pack needing a second pair adds ~8 min. If longer, cut scenes, not packs.
