# Laptop job: war 06.10 — TikTok preview, night FPV passes past the hottest strikes

Commit zentixua/incoming — in the coordinator's message. Repeat — copy of the war `2026-10-09T15_24_55` (Incoming
2.10.3). The GPU — one heavy task (between trailer parts, not next to them), not during Artem's game
(`pgrep -f 'instances/Airstrike Pack'` is empty). Do not touch the instance, its worlds or settings. Paths — from
`/mnt/data/projects/incoming`, `JAVA_HOME` — `java-runtime-delta` from Prism,
`W=/mnt/data/projects/incoming/mod/run/claude-work`, `T=$W/tiktok/war`, `R=2026-10-09T15_24_55`.

`$T/clips`…`$T/clips9` — do not touch. Shot into `$T/clips10`. Artem, 13.10: a video of several camera passes, like an
FPV drone's, at night (Flashback's time of day — the projectiles' lights are visible), low over the hottest strikes; one
pass — over the strikes on the port at 25:17–25:21, the rest — where strikes are most numerous across the whole
recording. Music edit — in the cloud, so each pass is delivered as a separate video. The path is laid by the shoot's
pilot (camera `pilot`) using the replay world on the client, the smoke of the strikes over time, and the projectile —
like an FPV pilot who sees everything ahead; over it — hands and explosion jolts. Smoke — by the code of Incoming's
rendering (fireball cloud, dust wall, `FarBlasts` column). Previous shoot (`clips9`, 3 of 9): the pilot went around the
smoke of the strikes before the start of the live replay, which is not in the frame (the output passes them by
fast-forward; Incoming does not draw them), held off to the side, the projectile was in the frame for a fifth of its
approach; but the frame in smoke was checked only by the middle of the frame over 24 blocks, and two best passes at the
end flew into the cloud. Now the pilot takes only the smoke of the strikes of the live replay and measures the frame in
smoke with the same grid of rays as the frame check (`smother`). Shaking of Incoming itself is turned off by the shoot
(`camera_shake = 0` in the client setting of this game, not the instance).

## 1. Build
```sh
git -C $W/tiktok-war-wt fetch origin <commit> && git -C $W/tiktok-war-wt checkout --detach <commit>; S=$W/tiktok-war-wt/tools
$W/tiktok-war-wt/mod/gradlew -p $S/replayscan build
G=$W/tiktok/game/2.10.3; J=$(ls $G/day/*.jar); echo "$J"            # client of the first shoot; mod jar — one
rm $G/mods/replayscan-*.jar && cp $S/replayscan/build/libs/replayscan-1.0.0.jar $G/mods/
C=$G/config/airstrike-client.toml                                    # Incoming 2.10.3: mod id still airstrike
if grep -q '^\s*camera_shake' $C 2>/dev/null; then sed -i 's/^\(\s*\)camera_shake\s*=.*/\1camera_shake = 0.0/' $C
else { echo 'camera_shake = 0.0'; cat $C 2>/dev/null; } > $C.new && mv $C.new $C; fi
grep -n camera_shake $C                                              # camera_shake = 0.0
```

## 2. Moments and plan
```sh
uv run $S/clips/moments.py $W/replayscan/out/$R/replay.duckdb --out $T/clips10 --pov-top 0 \
  --fpv 'search=25:14/25:26 near=-320,-405,90 shots=1 seconds=9 time=night' \
  --fpv 'shots=8 seconds=9 time=night' | tee $T/moments10.txt
uv run $S/clips/plan.py $T/clips10/candidates.json --all fly --replays $W/replayscan/replays
python3 -c "import json; s=json.load(open('$T/clips10/film/$R/plan.json'))['shots']; print([(x['name'], x['a'], x['b'], x.get('time'), x['cameras'][0]['kind'], len(x['smoke']), len(x['smoke'][0]) if x['smoke'] else 0, x['need'].get('smother')) for x in s])"
```
In `moments10.txt` — lines “FPV …” and “pass N: <s>–<to>: FPV past strike …” (up to nine passes: one at the port at
25:1x and eight of the hottest). Each plan has, in the output of its last command, `time` 18000, camera `pilot`, smoke
lines of 6 numbers (strike and explosion view) and `smother` 0.03. Passes fewer — shoot as is; in the report, a line
“skipped strikes without a projectile …, tight …”. The line “at strike … chunks within 128 blocks only in N of 16
directions — is shot: the place is named” — the pass at the port, where the recorder did not fly all around: shoot as
is.

## 3. Shoot (GPU)
```sh
$S/laptop_job.sh tiktok-war10 -- python3 $S/prod_client.py --no-copy --dir $G --incoming-jar $J --jvm=-Xmx12g \
  --prop replayscan.film=$T/clips10/film/$R/plan.json
```
Success: in `$G/logs/latest.log` — `Film: done`; before each plan —
`Film: <plan>: replay played from the start to tick …` (no `replay stopped at tick … on the way` lines),
`Film: <plan>: pilot planned 3 flights past the hit …` and above it, one line per flight (`pilot-h…: seed … → …`, which
holds `smoke fills the frame N`); per plan — `Film: verdict <plan> <camera>: PASS` (in `verdict.json` — `dh_world` 0,
`low_out` — up to 2 % of frames, `smoke_columns` true, `smothered` — no more than 3 % of frames); no
`effect particles stay` line. A plan that did not pass all three flights does not need re-shooting: its best output
still goes into the video (step 4, with `-FAIL`); in the report, its `why`.

## 4. Pass videos and delivery
```sh
uv run $S/clips/edit.py $T/clips10/film --out $T/clips10/out --soon
mkdir -p $T/clips10/passes && python3 - <<EOF
import json, os, subprocess
for name, b in json.load(open("$T/clips10/film/$R/film/best.json")).items():
    # and failed checks: at night the bottom of the frame behind chunks is sometimes not visible — editing decides by frames
    if b.get("dir") and os.path.exists(b["dir"] + "/video.mkv"):
        out = "$T/clips10/passes/" + name + ("" if b.get("pass") else "-FAIL") + ".mp4"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", b["dir"] + "/video.mkv", "-c:v", "libx264", "-preset", "slow",
                        "-crf", "20", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", out], check=True)
        print(name, b.get("candidate"), b.get("pass"))
EOF
ls -la $T/clips10/passes
```
Into the project files: each `$T/clips10/passes/*.mp4` (pass ~9 s; over 20 MiB — CRF 23), `report.md`, `moments10.txt`,
`$T/clips10/film/$R/plan.json` and `$T/clips10/film/$R/film` as an archive without video and frames
(`tar --exclude='*.mkv' --exclude='*.png' -czf …`: `qa.jsonl`, `verdict.json`, `path.json` and the reports — by them and
the plan, the passes are checked against frames in the cloud). Send: `report.md`, the “pass” lines from `moments10.txt`,
the `Film: verdict` lines, `replay played from the start`, `pilot planned` and the flight lines above them (`pilot-h…`),
errors (`Exception`, `ERROR`) of the shoot's log; a frame at 1, 3, 5 and 7 s of each pass
(`ffmpeg -ss <s> -frames:v 1`); per pass — the number of `qa.jsonl` frames with `smother` from 0.6, and the first second
of such a frame.
