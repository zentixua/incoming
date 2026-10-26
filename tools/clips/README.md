# clips - short videos (previews) based on replay data

Five vertical videos of 20–35 s, each a chain of “launch → flight → hit the ground”: missiles and “Grad”, Shahed and
 “Lancet” (and shot down before the player’s eyes), chaos in one shot (20 s, where there are the most hits on the ground in one place), strikes from afar with a long lens (up to three
 in a row), nuclear strike (launch, flash through the eyes of the player, the same flash and mushroom from the side). Moments, cameras and frame checking - only according to
 replay data (`tools/replayscan`), without selecting by eye. Separately, there are short segments through the eyes of the players (10–15 s only
 the player’s camera, as he himself moved his gaze): the player chooses which ones will go.

```sh
uv run tools/clips/moments.py <base>.duckdb... --out <clips> --prefer <main replay> [--star player:<nik>] # candidates
       [--at 25:02]... [--fly 'at=25:02/25:40 from=x,y,z to=x,y,z']...
uv run tools/clips/plan.py <clips>/candidates.json [--all pov,fly] # shooting plans
# shooting: replayscan with -Dreplayscan.film=<clips>/film/<repeat>/plan.json (README replayscan, “Shooting”)
uv run tools/clips/edit.py <clips>/film [--music track --credit "author"] # videos, captions, report
```

- `moments.py` — candidates for each clip from `replaydb.py` databases: projectiles, salvos, ground impacts
  (`impact_kinds.place` is `ground`; airbursts and intercepts only as drone shootdowns), player views (launch,
  flyby, impact in frame and unobstructed), loud off-screen versus loud on-screen. A shootdown is a drone downed
  by an interceptor or player fire (armed drone bursts in the air), through the eyes of a player who saw it for
  at least 3 of the last 6 s and at shootdown; `--star` viewers first. Overlapping shots through one player's
  eyes merge into one (`flyby+shootdown`: kind and subject from the more important one, all events), avoiding
  repeated seconds. Missile and Shahed clips last at least 20 s; missing time goes to the impact or shootdown
  shot (before it, no earlier than the previous shot's end). Chaos is filmed through a player's eyes (impacts
  in frame are shot events) and from a wide tripod, including above (60°: street impacts; tripod stays below
  clouds); long lens from player position and above roofs (`rise`). Through players' eyes (`pov`, `--pov-top`
  best across all replays, without `--prefer`): segments where a real player (recorder, Mojang account with
  version-4 UUID, or player without Mojang login with vanilla name-based UUID; bots have their own) saw most:
  nuclear detonation weight 6, shootdown 4, ground impact in frame 1 (centered and within 120 blocks, up to 2),
  launch 1.5, nearby flyby 1 (within 30 blocks, 1.5; shootdown credited to every real player who saw it).
  Segment from 3 s before the first event to 4 s after the last (10 s after detonation), 10–15 s, shifted at
  recording boundaries; best among overlapping segments (including different players at the same time).
  A recording with recorder-marked moments supplies only those, through the recorder's eyes: Flashback marker
  ("Create marker" key, pressed just after the moment; Flashback dimension-change markers do not count) takes
  15 s before and 2 s after; nuclear detonation within a minute before a marker starts at flash and lasts at
  least 10 s after it. `--at` time uses the Flashback timeline (`25:02`, `1:02:03`) or wall clock
  (`2026-10-14T19:05:30`; recording clock from save time in filename): moment takes 10 s before and 5 s after
  (nuclear detonation in that segment also takes 10 s after flash); `--at <from>/<to>` is used as is (Flashback
  timeline times and `--fly` require a single database). A detonation in the segment the player did not see
  (out of frame or all rays hit blocks within 16 blocks, as in a subway) is captioned with its distance.
  Free-camera flight (`--fly`, clip "7. Camera flyby", filming camera `path`): `at=<from>/<to>` on the Flashback
  timeline, `from` and `to` (`x,y,z`), `to_at` (when camera is at `to`, default end; afterward continues on the
  same line), `focus` (`<from>/<to>`: impacts required in frame, e.g. "on the port at 25:16–25:22"), `look`
  (`south`, `west`, `north`, `east` or player-style heading: 0 south, 90 west), `turn` (degrees the view may turn
  from `look`, default 25), `fov` (70), `dim`, `reach`, `ground`. View is fixed toward the most `focus` impacts
  in frame, then ground impacts, then all impacts; pitch toward them (median angles, 2–30° down). Impacts in
  frame are shot events; projectiles in frame and each window impact (distance, in frame or not) are in
  moments.py output. Replay chunks are everything sent by the recording before this tick (replay server does
  not forget them, filming starts from the beginning; in another dimension, since last entry there): within
  recorder render distance of places already visited. Analysis lacks render distance, so infer it from block
  updates (server sends them only in client chunks; `reach` sets it in blocks). If eye or ground beneath the
  lower half-frame (at `ground`, 63, within 192 blocks) is outside them, slow the path (around `focus` center)
  and shift at most 64 blocks, taking the first speed and shift that fit while keeping at least as many
  `focus` impacts in frame. If it still does not fit, use the same place at another recording time (moving
  windows too) where it fits and has the most ground impacts in frame (otherwise DH terrain under the camera
  shows seabed and caves without water). Output includes render distance, when chunks at `from` and `to`
  entered the replay world, when recorder was nearest them, ground impacts along the path over the entire
  recording by minute; speed, shift and time shift also as numbers (`speed`, `shift`, `moved`).
  `search=<from>/<to>` selects the same flight at the time between `from` and `to` where it is entirely in
  replay chunks and has most impacts in frame (then most on ground), line "search … most impacts in frame";
  `time` is video time of day (`night` is midnight, 18000, or day ticks as in `/time set`): projectile lights
  and fires are visible at night; Flashback takes it from export-task state (Visuals window).
  FPV passes (`--fpv`, same clip "7. Camera flyby", several cuts): `search` (recording window), `near=x,z,r`
  (impacts within `r` blocks of location), `shots` (4), `seconds` (9), `fov` (80), `time`. Pass goes by the
  hottest ground impact (most nearby impacts during the pass; passes do not overlap in time) with a known
  projectile (it enters frame) and room to fly around: impact in replay chunks, with chunks at least in a
  half-circle within 128 blocks (9 consecutive directions of 16: pilot follows the edge), at pass start and
  end, with a 2-chunk render-distance margin. At a `near` site without such an impact, take the impact with
  most room (pilot and frame checks decide). Impact occurs at 45% of the pass. No camera path is specified:
  filming lays it out, camera `pilot` (replayscan README, "Filming"), through the client's replay world like
  an FPV-drone pilot. Recordings with Incoming older than `--min-version` (2.10.2) are excluded.
  `candidates.json` and `candidates.md` (table). Game journal `--notes` (from server and host journals) adds its
  entry and `--note-bonus` score to candidates within its segment:
  `{"replays": {"<replay name fragment>": [{"src": "nuke_detonation", "at": "2026-10-06T23:10:35"}, {"end": true, "at": "name"}]},
  "moments": [{"from": "2026-10-06T22:27", "to": "2026-10-06T22:33", "what": "…"}]}` — replay clock anchors
  (first event by `src` or recording end; `name` is save time from filename); ticks interpolate linearly
  between anchors.
- `plan.py` — filming plan per replay: projectile paths (`tracks` and far records `far`), events, previous
  impacts (smoke), cameras (player eyes; for impact, shootdown and launch, backup free camera; for chaos, wide
  tripod; for nuke, lens first from player position: fireball from detonation-packet yield fits in frame with
  elevation; without yield, ball occupies 35% of frame width; player-view segments have no backup; flyby has
  only its path), check thresholds by shot kind (shootdown: surrounding fight, up to three loud off-screen
  explosions per on-screen one; player eyes: world built only, camera is player and DH; flyby: world built
  and DH); `film/runs.json` holds replay, plan and recording's Incoming version (filmed with that day's jar).
- `edit.py` — clip from recorded shots (`film/<replay>/film/best.json`; player-view segment is its own clip,
  8–20 s), with simple cuts, game audio −14 LUFS and −1 dBTP, quiet-music version, EN/RU captions (`<clip>.txt`;
  unchecked shots are excluded if checked shots exist, captions cover only included shots; before mod release,
  `--soon`: "coming soon to Modrinth and CurseForge"; player-marked moment can be any length), and `report.md`:
  whether each shot passed frame checks and whether each event flash is visible in the video frames themselves
  (what block rays miss: Distant Horizons terrain, smoke, shaders). `--clip far=<video>` (or `nuke=`) is a clip
  filmed outside a replay (live, with its own frame checks and game audio): same loudness, music version and caption.

Tests: `uv run --with 'duckdb>=1.1,<2' --with 'numpy>=1.26' python -m unittest discover -s tools/clips/tests`
 (need ffmpeg).
