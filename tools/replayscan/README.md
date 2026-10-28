# replayscan - parsing replay Flashback into tables

Client mod NeoForge and `replaydb.py`: replay Flashback is parsed by the game itself, without a picture, into tables of events,
paths and visibility, and queries to them find plans for videos (launch, flyovers, ground strikes, chaos, long-range strikes).
Numbers are taken from replay data, not from screen.

The client opens a replay, Flashback plays it on its replay server, and at the end of each tick the mod:
 - parses the recording actions of this tick with the same codecs that the client uses when playing: game packages - with the game protocol, packages of
  mods from the actions of Flashback NeoForge Fixed - with the codec that the mod was registered with NeoForge (therefore, you need jar
  Incoming of the version with which the recording was made);
 - reads the replay world: blocks for rays and ground measurements, entities for paths.

The mod does not depend on Incoming: everything about Incoming is in the data (entity types, id packages) and in the queries of `replaydb.py`.

## What you need
- Flashback 0.39 and Flashback NeoForge Fixed; Flashback is a mod Fabric, so also Sinytra Connector and Forgified Fabric
  API (all this is in the assembly “Open” Skies").
- Recording modes: the same as those used during recording (otherwise the replay registers will not match), and jar Incoming its version
  (`meta.json` → `replay_meta` and `mods`; version Incoming on the day of recording - according to release notes).
- Replay - only a copy: the mod reads zip itself, Flashback opens it during parsing.

## Running
Build and unit tests (from repository root):
```sh
mod/gradlew -p tools/replayscan build        # jar: tools/replayscan/build/libs/replayscan-<version>.jar
```

**Cloud, Gradle** (windowless client, `xvfb-run`): runtime mods are the `-PmodsDir` directory (all jars there are
copied to `run/mods`). In Gradle launches, Connector looks for a "clean" client jar as in the launcher layout;
the NeoForge installer provides it (`java -jar neoforge-21.1.264-installer.jar --installClient <directory>`;
the directory needs `launcher_profiles.json` containing `{"profiles": {}}`).
```sh
cd tools/replayscan
CONNECTOR_CLEAN=<directory>/libraries/net/minecraft/client/1.21.1-20240808.144430/client-1.21.1-20240808.144430-srg.jar \
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a ../../mod/gradlew -p . runClient \
  -PmodsDir=<mods> -Preplay=<replay copy>.zip [-Pout=<directory>] [-Pask=cameras.json] [-Pfrom=… -Pto=…] \
  [-PrunDir=<game directory>] [-Pxmx=8g]
```
Each analysis has its own `runDir` (mods and `config/` go there too); two Gradle launches in one project directory
cannot run concurrently, so concurrent analyses need copies of `tools/replayscan`.

**Full modpack** (as for a player, `tools/prod_client.py`: instance copy, without Prism): first copy, place the
analysis jar in its `mods`, then launch without a scenario; client reaches the menu and analysis starts automatically:
```sh
tools/prod_client.py --copy-only --dir mod/run/replayscan
cp tools/replayscan/build/libs/replayscan-*.jar mod/run/replayscan/mods/
tools/prod_client.py --no-copy --dir mod/run/replayscan --incoming-jar <incoming-<recording version>.jar> \
  --prop replayscan.replay=<replay copy>.zip --prop replayscan.out=<directory>
```
Client exits automatically when the replay ends; tables go to `out` (default `replayscan/<replay name>` in the
game directory).

## Settings
JVM properties `replayscan.<name>`; in Gradle launches, `-P<name>`.

| name       | default                              | meaning                                                         |
|------------|--------------------------------------|-----------------------------------------------------------------|
| `replay`   | —                                    | replay (zip); mod does nothing without it                        |
| `out`      | `replayscan/<replay name>`            | table directory                                                 |
| `speed`    | 200                                  | replay ticks per second (20 is game speed)                       |
| `viewers`  | `recorder`                           | visibility through whose eyes: `recorder` is recorder, `players` is all players |
| `every`    | 2                                    | entity visibility every this many ticks (events and distant projectiles on each packet) |
| `range`    | 1500                                 | visibility not computed beyond this distance (blocks)            |
| `subjects` | `(incoming\|airstrike):(?!debris$).*` | entity types whose paths and data are logged (players always)    |
| `ask`      | —                                    | camera file: visibility also from these points                   |
| `from`, `to` | 0, 0                               | tick segment (before `from`, seek without tables; `to` 0 means end) |

Camera file - points from where to shoot (free camera in Flashback):
```json
{"cameras": [
  {"id": "street", "dim": "minecraft:overworld", "eye": [120.5, 72, -340.5], "look": [180, 10], "from": 36000, "to": 38400},
  {"id": "roof", "eye": [80, 95, -300], "from": 36000, "to": 38400}
]}
```
`look` — `[yaw, pitch]` as a player (yaw 0 - south, pitch down - plus); without it, there is no point on the frame (`cx`, `cy` are empty),
 only distance and ray. The mod holds chunks around the camera from 200 tick to `from`.

## Tables
CSV to gzip; `t` — replay tick (1/20 s the beginning), location — world blocks.

- `events` - tick events: game packages (explosion, sound, entity appearance, damage, entity and world events, chat...) and packages of
   mods (`kind` - `packet` or `payload`, `src` - package class or id package mode, `data` - its fields JSON). Place -
   from the package; for a mod package - field `pos`/`position`/`burst`/`center`/`origin` (and in the attached entry) or the first vector. (`pre` = 1; 0 - after: first
   tick of parsing or after rewind): `clear` - blocks down to the block with the collision shape (0 - point in the block, −1 -
   unknown chunk, 65 - not in 64 blocks), `solid` - distance to the center of the nearest such block in the cube ±6 (−1 -
  unknown, 7 - not).
- `blocks` - changes to blocks from the server (`state` - as in `/setblock`).
- `far` - shells from the lists in mod packages (entry with UUID and `pos`): for Incoming - `far_flights` (projectiles further
   entity tracking) and `flights` (operator flights); the remaining fields are in `data`.
- `entities` - entities of the replay world: lifetime (`first`, `last`), beginning and end of the path. A projectile that left the client's
   range and returned - a new line with the same `uuid`.
- `tracks` - paths of entities from `subjects` and players: location, turn, speed (line - when something changed).
- `states` - entity data from `subjects` (`SynchedEntityData`, by field numbers) when changed.
- `viewers` - viewer's eye by tick (recorder, players, cameras `ask:<id>`).
- `sight` - visibility: viewer, target (`kind`: `e` - entity, number `eid`; `p` - event, number `e`; `f` - long-range
   projectile, UUID), `dist`, `ahead` (depth along the axis of view; the camera without `look` is empty), `cx`, `cy` - location in the plane
   of the frame per depth unit
   (to the right and up; frame with a vertical angle φ - `|cy| ≤ tan(φ/2)`), beam to goals: `hard` - up to the first opaque
   block (−1 - no), `soft` - how much is transparent with a shape on the way (foliage, glass, water), `unknown` - where the unknown chunk began
   (−1 - no). The beam to the entity and the long-range projectile - without the last block before the target, to the event - to the
   point on the 3 block above the place and without the last 3 blocks: the explosion is visible as a fireball above the point of impact, and the point
   itself is in the ground, and a gentle beam to it would go along the ground. Targets further than 75° from the axis of view are not written, neither is the viewer’s body
  .
- `time` - time of the game and day.
- `meta.json` - whether the analysis has reached the end (`complete`), tick, settings, how much of what disassembled, codec errors, mods.
  Tables and `meta.json` are added when the game crashes (at the output JVM).

What is not in the data:
- The replay world is what the recording client received: there are no blocks beyond its drawing (an unknown chunk, not air).
   The replay server Flashback does not forget chunks (there is no “forget chunk” package in the recording): for the launch in the upper world everything is accumulated,
   that the recording has already sent, and rewinding the photo does not remove this. It closes the world of another dimension when the one who recorded
   leaves from there: there are only chunks from the last entry.
 - Entities - only within the client’s tracking range; long-range projectiles Incoming - lines `far`.
- The projectile camera, binoculars and free camera are not recorded during the game: `recorder` - the eye of the player's body.
- Areas Sable (ships, flying craft) the mod does not put a replay in the world: the rays do not see them.

## Database and queries
```sh
uv run tools/replayscan/replaydb.py build <parsing directory> # replay.duckdb nearby
uv run tools/replayscan/replaydb.py --aspect 0.5625 report <db> # plan candidates → report/*.csv
uv run tools/replayscan/replaydb.py sql <db> "SELECT … FROM impact_views"
```
Frame - `--fov` (vertical, 70) and `--aspect` (16/9; 9:16 - 0.5625), “middle of frame” - `--center` (0.7). Ray
 is pure - no opaque block, no unknown chunk and transparent with a shape no larger than `--soft` blocks (2).

Views:
- `impacts`: strikes, one row for all explosions of one strike, position from the mod packet. Group
  explosions within 6 blocks of strike position and 3 ticks of previous explosion (mod packets must
  name the same projectile); game explosions within 20 ticks/12 blocks after a mod packet are its
  secondary detonations (cruise missile up to 10,6 blocks; game packet is sent when a stage is removed,
  later than tick nine on a busy server). `material`: server material from Incoming explosion packet
  (8 is gravel, also returned for air/unready chunks). `impact_kinds.place`: `intercept`, `ground`
  (surface within 2.5 blocks, bottom within 2 blocks; crater is blocks becoming air within 6 blocks/
  20 ticks, or server material other than gravel, including water), `air`, `unknown` (position absent
  from recorded world, beyond recorder render distance, and no server material or gravel).
- `impact_views`: strike from each viewer, distance, screen position, clear ray, sound arrival
  (17.15 blocks/tick, like Incoming).
- `launches`: first projectile appearance by UUID, nearly stationary or at launcher; visibility for
  first 3 s (`samples`: measured ticks while present; `seen`: centred/in view and unobscured; out-of-frame
  ticks are not visible).
- `flybys`: ticks within 10–60 blocks, in frame and unobscured.
- `far_views`: distant projectiles from each viewer.

Report `report`: `launches` (30–200 blocks, in the middle of the frame and not closed ≥ 80 % of the first 3 s), `flybys` (≥ 1.5 s),
`ground_impacts` (impact near the surface in 40–300 blocks, in the frame, the beam is clear), `chaos` (windows along 10 s: impacts on the ground in the frame,
 loud events in the frame and behind the scenes), `far_quiet` (impacts in 300–1500 blocks in the frame and how many other explosions are heard
 nearby in time).

## Filming
`-Dreplayscan.film=<plan>` (Gradle: `-Pfilm=…`) makes the client film shots of a Flashback replay (MKV, H264, AAC)
and check every frame against game data instead of analyzing. `tools/clips/plan.py` writes the plan; rendering
matches the filming client (Sodium, shaders, Distant Horizons, whatever is installed), with the same mods as for
analysis of this replay. With Distant Horizons, client window size must differ from plan frame size: Flashback
export recreates the game framebuffer at export size, and Iris leaves world LOD depth at that same size, causing
stripes over the near world. Filming refuses a window matching frame size (error `Film: the window is …`).
Client parses packets on arrival in its network thread, but creates the world when the client thread reaches the
login packet; replay server is in the same process and sends to the viewer immediately after login. A mod packet
whose parser reads the client world (Point Blank: weapon in other players' hands) crashed and the client left the
replay. Therefore everything the replay server sends to the viewer after the login packet waits in that server
connection's channel (in the same order) until the client completes login through it (its player and world;
`ViewerHold`, line `Film: replay viewer got … packets held after login …`). Flashback `ignoredCustomPayloads`
cannot do this: it would discard packets from the replay itself too.

Plan (`plan.json`): `replay`, `out`, `width`, `height`, `fps`, `bitrate`, `tries` (cameras to try per shot),
`ready` (fraction of frames with a built world, 0 disables checking), `clouds` (shaderpack cloud base), `distance`
(render distance), `loud` and `engine` (loud-sound and flight-sound IDs, regular expressions; default flight sounds
are engines, dive, whistle, bomb release, Grad approach), and `shots`: `name`, `a`, `b` (tick), `dim`, `warm`
(ticks of live playback before the start, for previous impact smoke in frame; impacts up to and including
`a − warm` are reached by seeking in export, and Incoming does not render them: no particles or column. Pilot
and camera evaluation include impact smoke from `a − warm`, with a margin on this tick), `hold` (wait at camera
after world build, for DH), `far` (subject beyond render distance), `subject` (`uuid`, `size`, `path`
`[[t, x, y, z]…]`, `keys` `[{t, pos, size}]` — impacts, `area` `{pos, size}`), `smoke` (impacts
`[t, x, y, z, fireball diameter, kind]`; Incoming explosion kind: 1 is MLRS, 2 drone, 3 rocket, 4 bomb with breakout,
5 bomb deeper than 10 blocks (column without ball; bomb position is above the charge), 0 no smoke; without kind,
use diameter; without diameter, 16 blocks), `need` (thresholds; `loud` below 0 disables loud off-screen checking;
`smother` is the fraction of frames with smoke near the camera itself, unchecked by default), and `cameras` in order:
- `pov` — through the player's eyes (`player`: `recorder`, name or UUID; `gui` includes hotbar; `fov`): replay viewer
  spectates that player on the replay server too, as after Flashback's "Spectate" button; server sends replay sounds
  at the viewer position (player shots and footsteps audible within 16 blocks). Player is found on the replay
  server: after seeking, viewer remains at the previous shot, and a player beyond tracking reaches the client
  only after the server moves the viewer there; client does not spawn that player's effect particles (potion
  spirals would obscure half the frame near the eye);
- `fixed` — stationary at `eye`, looking at `aim` (point) or tracking the subject (`"aim": "subject"`), angle `fov`;
  `rise` gives candidates this many blocks above ground under the eye (0 means eye itself; eye in a wall moves
  into air, upward and toward the target);
- `tripod` — tripod on a circle around `around`: distances `dist`, elevation angles `elev`, azimuth step `az_step`;
  lens makes an event of diameter `size` occupy `share` of frame width; `follow` tracks subject, otherwise looks
  at the site;
- `path` — flies along `keys` `[{t, eye, aim}]` (tick, eye, look-at point; linearly between keys, at the first/last
  key before/after them). Optional `roll` and `fov` on each key interpolate into Flashback's native camera and
  lens tracks; a key without `fov` uses the camera's `fov`. `rise` gives backup copies of the same path this many blocks higher (plan
  order, not score): a person set the path, so it also goes where the client has no chunks (only DH renders
  terrain there), and "camera in blocks" is checked only where a chunk exists;
- `pilot` — FPV drone pass by an impact (`subject.keys`, the one nearest the end of the projectile path), angle
  `fov`: pilot (`Pilot`) lays out a path through the replay world as a person seeing everything ahead would fly;
  drone controls (speed, turn, climb) at nodes every 24 ticks, cross-entropy search. Cost considers clearance from
  ground, houses and treetops, low flight (5–14 blocks), replay chunks beneath the frame, impact smoke over time
  from Incoming rendering code (`Plume`: fireball cloud, dust ring, crater wisps and column lasting minutes and
  drifting with wind); drone stays outside it, frame is not full of smoke (same ray grid to 40 blocks as `smother`
  frame check), impact not hidden behind it, impact at a readable distance, projectile in frame before impact,
  drone limits (speed up to 1.5 blocks per tick, acceleration, bank with turns, look not sideways to heading and
  no faster than 2.2° per tick). Three best paths are candidates; operator hands and explosion jolts overlay
  each (`Hands`, like trailer FPV style); Incoming's own shake is disabled before filming in its client setting
  (`camera_shake = 0`; filming does not change it). Before evaluation, pilot waits until chunk count within 16
  of impact is unchanged for 40 ticks: all terrain is needed. Pilot does not know burning-debris smoke (tails
  arcing tens of blocks from impact, billows live 4.5–7 s): debris are replay entities, and analysis does not log
  their paths (`subjects`). In clips9, the Grad pass at 03:57, 3 s after impact, was in smoke the model misses
  (105 of 540 frames, from 7.25 s), including debris tails.

Shots run chronologically; before each, play the replay forward from its start in 1200-tick steps
(Flashback plays packets for this step without a chunk snapshot; log `Film: <shot>: replay played from
the start to tick …`). Free camera then has everything shown before the shot, like a viewer watching
from the start. Jumping directly restored only recorder chunks at the chunk start; flights away from
it crossed void. Chunks delivered later retain their late state after backward seek, so shots run in
time order. Before scoring, viewer stands at `fixed` camera eye (`path`: path start; distant subject
may be outside replay chunks, camera stays inside); without one, at shot site while world exists there.
Score cameras before export against replay world using the frame-check rays/view: subject visible,
not behind blocks/clouds/fog, each event visible in first 12 ticks, camera outside blocks, impact smoke
from the pilot model clear of sightline. Try predicted passes first (plan `pov`/`fixed` first among them),
then others; never place tripods above clouds. With Distant Horizons, wait until DH textures differ
from export frame size (after prior export, until DH renders LOD in the window; `Film: Distant Horizons
textures …` on each recreation). Every export frame (`qa.jsonl`) records subject screen position/width,
rays, Incoming effects-layer smoke (particle and `FarBlasts` smoke-column fields by reflection; skipped
without Incoming) on sightline/at camera (`smother`: frame-grid ray fraction blocked by smoke within
40 blocks; ≥0,6 is smoke-filled), clouds, render-edge fog, actual rendering (entity section built;
Incoming draws absent client projectiles by server track), full-world readiness, audible engine
(mod sound source trails projectile by travel during propagation), each event visibility in first
12 ticks, whether camera is the player, free-camera block collision (`inside`: any frame fails; `path`
can enter chunks absent during scoring), lower frame terrain outside replay chunks (`low_out`: lower
ray within 128 blocks crosses an absent client chunk and reaches ground by 128; only DH terrain there,
seabed/caves without water, or void; >2 % of frames fails), DH LOD depth (`dh`: 1 own, −1 world, 0 no
LOD/neither; world-depth frame fails). `sounds.jsonl` records loud sounds inside/outside frame.
`verdict.json` applies `need` thresholds; failure tries next camera. Output `<out>/<shot>/<camera>/`,
`<out>/report.jsonl` (each exported camera), `<out>/best.json` (shot → best with shot ticks). Restart
skips passed shots with identical ticks. Camera missing player, replay stalled before start, or export
not starting tries next camera; unreadable plan exits the client immediately.
