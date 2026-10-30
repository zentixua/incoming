# Laptop task: Flashback replays into databases (tools/replayscan)

Commit zentixua/incoming — in the coordinator's message. Goal: parse replays of games with the game itself
(`tools/replayscan/README.md`) into tables and a DuckDB database: events, projectile paths, strikes on the ground, in
the air and interceptions, visibility through the eyes of all players. The databases are needed by the “TikTok preview”
thread to pick moments.

Do not touch: Artem's instance, his worlds and settings; read the original replays only, parse copies. Not during
Artem's game (`pgrep -af java` — no client of the “Airstrike Pack” instance). The parsing client runs with the full pack
(about 10 GB): it does not run at the same time as another full-pack client (trailer steps `record`, `render`); it can
run alongside `cut` (editing). The GPU is not needed for parsing: it does not render (shaders are off in its copy).

Paths start at `/mnt/data/projects/incoming`, `W=mod/run/claude-work`, `JAVA_HOME` — `java-runtime-delta` from Prism.

## Setup (once)
1. Working tree and build:
   ```sh
   git fetch origin <commit>
   git worktree add --detach $W/replayscan-wt <commit>
   $W/replayscan-wt/mod/gradlew -p $W/replayscan-wt/tools/replayscan build   # → tools/replayscan/build/libs/replayscan-1.0.0.jar
   ```
2. Game directories — `$W/replayscan/game/<version>`: a copy (`cp -a --reflink=auto`) of `$W/epic-index/render/<version>`
   (2.10.2, 2.10.3), without `saves`, `flashback`, `logs`, `screenshots`, `crash-reports`, `audio.wav`. In each copy:
   - the mod's jar for the day (`airstrike-*.jar` or `incoming-*.jar`) — from `mods/` into `day/`: `prod_client.py --no-copy`
     deletes `incoming-*.jar` in `mods/` and puts the one given by `--incoming-jar`;
   - the parsing jar — in `mods/`;
   - `config/iris.properties`: `enableShaders=false`.
3. Replay copies — `$W/replayscan/replays/`: ready copies from `$W/epic-index/render/<version>/flashback/replays/`
   (already without Point Blank packets, DH path pointing to the copy); the rest — as `render_windows.replay_copy`:
   `patch_replay.py` (DH path → `$W/release-media/dh-airstrike-overworld`; no `distantHorizonPaths` — the step is not
   needed), then `strip_payloads.py <src> <dst> pointblank:mainheldsimplifiedstatebroadcastpacket` (`$W/epic-index`).
   Source — `flashback/replays/` of the “Airstrike Pack” instance.

## Parsing one replay
```sh
cd $W/replayscan
V=<version>; R=<replay name without .zip>; G=$PWD/game/$V; T=$PWD/../replayscan-wt/tools
timeout -k 60 4h $T/laptop_job.sh scan-$R -- python3 $T/prod_client.py --no-copy --dir $G --incoming-jar $G/day/<jar> \
  --size 640x360 --jvm=-Xmx8g --prop replayscan.replay=$PWD/replays/$R.zip --prop replayscan.out=$PWD/out/$R \
  --prop replayscan.viewers=players --prop replayscan.every=2
uv run $T/replayscan/replaydb.py build out/$R
uv run $T/replayscan/replaydb.py --aspect 0.5625 report out/$R/replay.duckdb
uv run $T/replayscan/replaydb.py sql out/$R/replay.duckdb "SELECT place, count(*) FROM impact_kinds GROUP BY place"
rm -f $G/audio.wav
```
Progress — `Replay scan: tick N of M` lines in `$G/logs/latest.log` (every 30 s); the client exits by itself. If the replay
stalls for 5 minutes, the parse ends by itself (`complete: false`).

**Success:** in `out/$R/meta.json` `complete: true`, `gaps: 0`; `errors` has no game packets and no `airstrike:*` or
`incoming:*` packets; `payloads` has `<airstrike|incoming>:blast`; `build` prints the number of rows. Otherwise — the first
`ERROR` lines and exceptions from the log and `errors` from `meta.json`; one rerun of the launch, no more.

## Order
Only the 06.10 games (Artem: do not parse early mod versions or the 12.10 recordings made without the mod):
1. `2026-10-09T15_24_55` (2.10.3, 60 min) — the 06.10 war with Artem, MartinFrances and ClyuDan, the most interesting game.
   It is also the trial run: after 10 minutes — speed from the `tick N of M` lines and client memory; after it ends — the
   `build` result, `seconds`, size of `out/$R`, impact kinds.
2. 06.10 22:13 (2.10.3, 75 min) — Artem, MartinFrances and ClyuDan: Zearth One, ClyuJet, Homelander, “War for Newisle”.
3. 06.10 09:59 (2.10.2), 06.10 16:31 (2.10.3).

## Report
After each replay — one line to the coordinator: the path to `replay.duckdb`, `complete`, the ticks and minutes of
parsing, impact kinds, errors if any. `meta.json` and `report/*.csv` — in one archive `replayscan-<replay>.tar.gz` in the
project files. The databases and tables stay on the laptop (`$W/replayscan/out/`).
