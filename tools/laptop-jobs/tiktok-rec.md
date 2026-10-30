# Laptop job: Artem's recordings on Zearth Rec — TikTok previews through his eyes

Commit zentixua/incoming — in the coordinator's message; the same message says which recordings to take and the moment
times, if Artem named times rather than setting markers. Not during Artem's game: start when `pgrep -f 'instances/Airstrike Pack'`
is empty; on the GPU, one heavy task, the client with the whole pack — one. The instance, its worlds and settings are
read-only; replays and Distant Horizons data — copies. Paths — from `/mnt/data/projects/incoming`, `JAVA_HOME` —
`java-runtime-delta` from Prism, `W=/mnt/data/projects/incoming/mod/run/claude-work`, `T=$W/tiktok/rec`,
`MC=$(python3 tools/paths.py MC)`.

Recordings — from the Zearth Rec server: Open Skies pack 0.1.26, Incoming 3.0.1. A recording from a separate instance with
the mod from main (if one appears) — its own game directory (`$T/game-main`) with that instance's jar, the rest the same.

## 1. Build and game directory
```sh
git fetch origin <commit> && git worktree add --detach $W/tiktok-rec-wt <commit>; S=$W/tiktok-rec-wt/tools
$W/tiktok-rec-wt/mod/gradlew -p $S/replayscan build
python3 $S/prod_client.py --copy-only --dir $T/game        # mods without Incoming, config, shader packs, options.txt
mkdir -p $T/game/day && cp "$MC"/mods/incoming-3.0.1.jar $T/game/day/
sha256sum $T/game/day/incoming-3.0.1.jar                  # c1b10e6e4f934917c06e97770f1c21aa65fb9e7565df7647ff59f10120adf506
cp $S/replayscan/build/libs/replayscan-1.0.0.jar $T/game/mods/
```
In `$T/game/config/DistantHorizons.toml`, section `[common.worldGenerator]`: `generatorPlan = "DISABLED"`,
`chunkGeneratorMode = "PRE_EXISTING_ONLY"`, as for the trailer's clients (`tools/trailer/record.sh` in the trailer branch):
Zearth is a map; the world generator would draw forest and sea LODs over it.

## 2. Copies of replays
Replays — `"$MC"/flashback/replays/<name>.zip` from the coordinator's message; copies — under the same name (the recording
time for `--at` is in the file name). LOD — what Artem saw: each directory from `distantHorizonPaths` in the replay's
`metadata.json` — its own copy in `$T/dh/` (one copy for all replays with the same directory), the path in the replay
copy points to it (like `patch_replay.py`, but each dimension has its own directory). Then, without Point Blank packets,
as in `replayscan.md`:
```sh
mkdir -p $T/replays $T/dh
python3 - "$MC/flashback/replays/$N.zip" $T/replays/$N.dh.zip $T/dh <<'EOF'
import hashlib, json, os, shutil, subprocess, sys, zipfile
src, dst, root = sys.argv[1:4]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_STORED, allowZip64=True) as zout:
    for info in zin.infolist():
        if info.filename != 'metadata.json':
            with zin.open(info) as fin, zout.open(zipfile.ZipInfo(info.filename, info.date_time), 'w', force_zip64=True) as fout:
                shutil.copyfileobj(fin, fout, 1 << 24)
            continue
        meta = json.loads(zin.read(info))
        dh = meta.get('distantHorizonPaths') or {}
        for key, path in dh.items():
            copy = os.path.join(root, key.replace(':', '_').replace('/', '_') + '-' + hashlib.sha1(path.encode()).hexdigest()[:8])
            dh[key] = copy
            if not os.path.isdir(path):
                print(key, 'no directory', path, '- this dimension will have no LOD')
                continue
            if os.path.isdir(copy):
                print(key, '->', copy, '(copy already exists)')
                continue
            subprocess.run(['cp', '-a', '--reflink=auto', path, copy], check=True)
            size = lambda d: sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(d) for f in fs)
            print(key, path, '->', copy, 'bytes:', size(path), size(copy))
        if not dh:
            print('no distantHorizonPaths: no LOD in the frame')
        zout.writestr('metadata.json', json.dumps(meta, indent=2))
EOF
python3 $W/epic-index/strip_payloads.py $T/replays/$N.dh.zip $T/replays/$N.zip pointblank:mainheldsimplifiedstatebroadcastpacket
rm $T/replays/$N.dh.zip
```
Bytes in the source and in the new LOD copy — equal; no `distantHorizonPaths` or no source directory — say so in the report.

## 3. Analysis (GPU not needed)
During analysis, in `$T/game/config/iris.properties` — `enableShaders=false`. For each replay:
```sh
timeout -k 60 2h $S/laptop_job.sh scan-$N -- python3 $S/prod_client.py --no-copy --dir $T/game \
  --incoming-jar $T/game/day/incoming-3.0.1.jar --size 640x360 --jvm=-Xmx8g --prop replayscan.replay=$T/replays/$N.zip \
  --prop replayscan.out=$T/out/$N --prop replayscan.viewers=players --prop replayscan.every=2
uv run $S/replayscan/replaydb.py build $T/out/$N
```
On success — as in `replayscan.md`: `complete: true`, `gaps: 0`, `payloads` contains `incoming:blast`. Otherwise — the first
`ERROR` lines and exceptions of the log, `errors` from `meta.json`; one rerun of the replay.

## 4. Moments and plans
```sh
uv run $S/clips/moments.py $T/out/*/replay.duckdb --out $T/clips --pov-top 50 [--at <time>]...
uv run $S/clips/plan.py $T/clips/candidates.json --all pov
```
A recording with markers (Flashback "Create marker", the `--at` time) gives only those, through Artem's eyes; a recording
without markers — the best segments through the eyes of live players. Time is local, as Artem named it, with the date:
`--at 2026-10-13T17:05:30` (moment: 10 s before, 5 s after) or `--at 2026-10-13T17:05:00/2026-10-13T17:06:10` (as is).
Section "6. Through the eyes of the players" from `$T/clips/candidates.md` — into the report; it has as many marked moments as
there are markers.

## 5. Filming (GPU)
`enableShaders=true` in `$T/game/config/iris.properties`; shader pack — as Artem's, his are off — Complementary
Reimagined from `shaderpacks/`. Window — not the frame size (default 1280×720). For each row of `$T/clips/film/runs.json`:
```sh
$S/laptop_job.sh tiktok-rec -- python3 $S/prod_client.py --no-copy --dir $T/game \
  --incoming-jar $T/game/day/incoming-3.0.1.jar --jvm=-Xmx12g --prop replayscan.film=<plan from runs.json>
```
Success: in `$T/game/logs/latest.log` — `Film: done`; for each plan — `Film: the replay viewer spectates <player> on the
replay server` and `Film: verdict <plan> <camera>: PASS` (in `verdict.json` — `dh_world` 0); for a replay with an LOD copy,
`"dh":1` in `qa.jsonl` (otherwise into the report: no LOD). Do not push a failed plan further.

## 6. Editing and delivery
```sh
uv run $S/clips/edit.py $T/clips/film --out $T/clips/out --soon
```
Clips `$T/clips/out/pov-<n>.mp4`, captions `pov-<n>.txt` and `report.md` — into the project's files, `tiktok-rec/` (up to
20 MiB; larger — H.264 CRF 20, sound as is). Send: `report.md`, lines `Film: verdict` and `spectates`, errors (`Exception`,
`ERROR`) from the analysis and filming logs, for each clip the number of `step`, `incoming:` and `pointblank:` lines in
its `sounds.jsonl`.
