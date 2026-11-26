# Time in Incoming: one principle for how long consequences last (design note)

Status: proposal for Artem's decision ("обсуждаем"). Analysis only; nothing here is adopted until he decides. Code
references are `path:line` against `main` at the time of writing; `J/` is `mod/src/main/java/ua/zentix/incoming/`.
The numbers come from code reading and from a throwaway unit probe over the shipped models (`PallField`, `CloudModel`;
15 kt surface burst, defaults, game scale), quoted where used.

## 1. The question

In 3.14.0 the haze left by a nuclear burst (the dust blanket) visibly thins within 1.5–2 real minutes, and parts of the
map clear; real dust hangs for tens of minutes to hours. Artem asked for the time rules to be set up properly, from one
principle: which phenomena run in real time, which follow the Minecraft day and which follow something else, with no
hand-picked durations, consistent in every pack (vanilla day, day-length mods, stopped day, sleep, servers). His raw idea
was to tie consequences to the day length, so a 24-hour day plays out as in real life and a vanilla day compresses it.
He also said the blast wave and flight cannot follow the day.

### 1.1 The cause of the 1.5–2 minutes, by number

Two things show the dust blanket, and neither is the clock:

- The dust's age is real time (`J/client/nuclear/PallField.java:1034`, ticks / (20 · scale), outside the detonation
  clock), so the law alone would keep it. Fine dust from 15 kt lives 5673 s (1.6 h, `lifetimeEnd`).
- The inside-sight cap (`PallField.visible`, `:359`, a presentation deviation from #735: "inside old dust you see about
  200 m after one minute and about 700 m after five") is baked into the column's optical depth in the shared table that
  every ray reads (`lifetime`, `:262`). It therefore thins the whole column for every view, not only the view of a camera
  inside the dust.

Vertical optical depth τ of the column at 1564 m from 15 kt, and the share of light that passes straight down
(T = e^−τ):

| Age after the front | Law τ (T) | Drawn now, with cap (T) | Drawn with the transfer only, no cap (T) |
|---|---|---|---|
| 30 s | 25.5 (0) | 2.67 (7 %) | 3.2 (4 %) |
| 63 s | 24.2 (0) | 0.60 (55 %) | 3.2 (4 %) |
| 3 min | 20.0 (0) | 0.42 (66 %) | 3.0 (5 %) |
| 10 min | 11.5 (0) | 0.29 (75 %) | 2.2 (11 %) |
| 21 min | 4.7 (1 %) | 0.22 (81 %) | 1.7 (18 %) |
| 31 min | 2.2 (11 %) | 0.19 (83 %) | 1.5 (22 %) |
| 45 min | 0.75 (47 %) | 0.09 (91 %) | 0.75 (47 %) |
| 65 min | 0.16 (86 %) | 0.02 (98 %) | 0.16 (86 %) |

Between 43 s and 63 s after the front the drawn column goes from 21 % to 55 % see-through (closer in, at 782 m, from 3 % to
58 %), which is the thinning Artem saw. Seen slant from a hill (path about 3× the vertical) the uncapped column stays
opaque for about 20 minutes; the capped one passes 17 % of the light after one minute.

So the symptom has its own cause (a presentation that belongs to the eye inside the dust is applied to the whole
column), separate from the time question. Section 6 fixes both; the time principle alone would not cure it.

## 2. The two rates

- **Real time** is the game's running time: game ticks on the server, unpaused client ticks on the client. Lag,
  `/tick rate` and `/tick freeze` slow or stop it with the rest of the world; single-player pause stops it.
- **The world's day** is `J/util/WorldClock` (1000 clock ticks = one hour of the day, never slower than real, never
  backward; sleep and `/time add` jump it forward). Its rate k relative to real time: vanilla 72 (an hour in 50 s), a
  2-hour day 12 (an hour in 5 min), a 24-hour day 1, a stopped day 1 (the real-time floor).
- **The detonation clock** `J/nuclear/NukeClock` is the one place that combines them: real for the 20-minute show
  (`SHOW_S`, Hiroshima's firestorm formed about 20 min after the burst), then the world's rate, joined over 60 s
  (`RAMP_S`) without catching up the show.

Vanilla Minecraft itself runs almost everything on ticks: crops, fire, mobs and weather do not move on during sleep;
only the sky and the weather timer jump. Players read the sky as "hours passed" and the world's state as "what the
game simulated".

## 3. Inventory

Full tables per phenomenon with file:line are in the PR. Grouped by clock (real durations: vanilla day / 24-hour day /
stopped day; 15 kt, game preset):

**Real time (ticks), all days alike**

| Phenomenon | Real duration |
|---|---|
| Flight, warnings, launchers, air defense, salvos, sirens | seconds to minutes |
| Flash, fireball, blast front, damage, ruins, mob panic, EMP arcs | seconds |
| Mushroom cloud (`MushroomModel.lifeSeconds`) | 1 kt 22 min, 15 kt 27 min |
| Nuclear dust blanket (`PallField`) | lifts by the law over 1.6 h, but drawn see-through after 1 min (§1.1) |
| Conventional blast cloud (`CloudModel.life`, capped at 1 h) | wind 3 m/s: Shahed and missile 60 min (cap), Grad 24 min, Lancet 16 min; wind 8 m/s: Shahed 41 min, missile 60, Grad 9, Lancet 6 |
| Conventional ground dust (`GroundDust.life`) | wind 3 m/s: 3–22 min; wind 8 m/s: 1–9 min |
| Trails and contrails (`TrailModel`, capped at 1200 s "no longer than an in-game day") | ≤ 20 min |
| Crater fuel fire (server) and its smoke | Shahed 2.5 min, missile 16 min |
| Fires lit by blasts, flash fires | vanilla fire rules (never out with `doFireTick false`) |
| Client fire model (roar, inward wind), secondary explosions | ~20 min after the last ignition; ≤ 10 min |

**Detonation clock** (real for 20 min, then the world's day): firestorm (grows 20 min, peaks at 3 h, out by 9–12 h),
fire-zone haze and fire light, black rain (from min 20, 90 min at each point), fallout arrival and decay (t^−1.2,
forgotten after 7 days), contamination decay, mob fallout. Real totals: storm 27–30 min / 12 h / 12 h; fallout
forgotten after 2.6 h / 7 days / 7 days.

**World clock directly** (the world's rate from the burst, not from the end of the show): radiation sickness (36 h to
6 weeks), burns (heal 7–35 days, shock 0.5–12 h), hearing (shift heals in 16 h, ×2 per 10 dB above 40; eardrum 28–56
days), power repairs (nuclear: tripped zone 2–24 h, damaged belt 2–14 days; substation strike: `restore_hours` 18 h),
substation oil smoke (while the outage lasts). Vanilla: hearing back in 13 min, power in a tripped zone back from 100 s,
burn shock kills in 25 s–10 min.

### 3.1 Inconsistencies today

1. **Two clocks inside one show.** Under a vanilla day, during the nuke's real 20 minutes, hearing heals in 13 min,
   power in tripped zones comes back from 100 s, burns and sickness advance 72× (world clock from the burst), while the
   mushroom, the forming fires and the dust run in real time.
2. **Sleep is selective.** Sleeping during the show heals burns, hearing and sickness and restores power, but the
   fires, rain and fallout stay at the minute they were (`NukeClock` ignores the world during the show). Sleeping after
   a conventional strike heals hearing but leaves the clouds, trails and crater fire as they were. The guide says
   "after a night's sleep the fires are out" without the exception.
3. **Dust and mushroom outlive what they belong to.** Vanilla: the storm and black rain end at about 23–30 min, the
   15 kt mushroom stays to 27 min and the dust law to 1.6 h. 24-hour day: black rain falls for hours after the 27-min
   mushroom has gone (consistent there, since the storm is real too).
4. **One conventional strike, three clocks.** Its crater fire on ticks (2.5–16 min), its cloud on client ticks (up to
   60 min), the hearing damage it causes on the world clock (13 min vanilla, 16 h on a 24-hour day).
5. **The longest conventional effects ignore the day.** A Shahed cloud stays up to an hour under a vanilla day (three
   in-game days); a trail is capped at a fixed 1200 s labelled "an in-game day".
6. **Client-only state.** Conventional clouds and crater smoke are not shown to a late joiner and vanish on a replay
   seek; hearing loss is cured by relogging (it is client state), while burns and sickness persist.

## 4. What a good principle must satisfy

Taken from the concept (AGENTS.md "Principles") and from an independent player-view analysis (an agent briefed only on
what players see, without our candidates; its answer is summarized in §5.5):

1. **Cause first, no seams.** No effect before its cause; nothing jumps, pops or time-lapses in front of a player unless
   the sky jumps with it (sleep, `/time add`).
2. **The show is the product.** What a player watches (flight, flash, wave, mushroom, the dust rolling in, fires taking
   hold) looks the same in every pack.
3. **Lived-with consequences follow the world.** A 24-hour day plays out as in real life; a vanilla day compresses the
   aftermath with its sky; sleep moves it on; nothing holds a base hostage for real hours the world cannot skip.
4. **One cause, one clock.** Everything one strike causes (smoke, dust, fires, rain, fallout, injuries, outages) ages
   together, so related things never disagree.
5. **No hand-picked durations.** Durations come from the laws; the only time constants are physical ones.
6. **Same for everyone.** Server-defined: every player, a late joiner and a replay seek see the same state.
7. **Frame cost bounded** at any strike count, and no new time setting.

## 5. Principles compared

Conditions: V vanilla day; D24 a 24-hour day; D2 a 2-hour day; S stopped day; sleep during the show; sleep later;
`/time add`; a server where some players sleep; late join; Flashback seek; several detonations; conventional-only play;
single-player versus server. Reference phenomena: the 15 kt dust blanket (law: opaque about 20 min, gone 1.6 h), the
firestorm (out at 12 h), a Shahed cloud (up to 1 h), hearing after a near miss (16 h), a substation outage (18 h).

### 5.1 A: every consequence follows the world's day; only motion is real (Artem's raw idea)

- V: the dust blanket's opaque phase lasts 17 s and it is gone in 80 s; the firestorm forms in 17 s, peaks at 2.5 min,
  is out by 10 min; a Shahed cloud fades in 50 s; hearing 13 min; outage 15 min. D2: dust opaque 100 s, storm formed in
  100 s. D24 and S: everything real.
- Sleep at any time jumps everything; consistent everywhere; late join and seek are functions of the world clock.
- Seams: under V and D2 the show itself time-lapses. Dust behind the wave clears while the front is still crossing
  (the front at 0.35 of sound speed takes about 40 s to 5 km); the mushroom thins before it has risen. The same nuke is
  a 20-second pop in one pack and a 20-minute event in another.
- Fails 2 (the show) on the most common setup. Passes 3–7. Cheapest frames.

### 5.2 B: everything in real time (vanilla's own tick rule)

- Every day setting: the firestorm burns 12 real hours, fallout lasts 7 real days, hearing 16 h, an outage 18 h, the
  dust 1.6 h, a Shahed cloud 1 h.
- Sleep and `/time add` change nothing: after three nights the fire is at the same stage; the zone blocks a base for
  weeks of evening sessions; the sky says days passed, the world says minutes.
- Fails 3. Passes 1, 2, 4–6. The most frame cost (12 h of storm haze over every session).

### 5.3 C: one strike, one clock (recommended; §6)

Each strike has one clock. It runs in real time while the strike's own energy drives what happens (the show), then at
the world's rate. Everything the strike leaves ages on it; motion on screen is always real.

- V: the nuke's show is 20 real min with an opaque dust blanket throughout; the dust then lifts over about 1.5 min,
  the storm peaks about 2–3 min after the show and is out by 28–30 min; fallout is forgotten after 2.6 h. A Shahed
  cloud rises to its apex in real time (2–5 min) and fades within about 1–2 min after; a missile's show lasts while
  its crater fuel burns (16 min), so its cloud goes at about 18 min. Hearing and outages take their
  world hours after the show (not during it). D2: after the show, the dust lifts in about 4–6 min, the storm burns out over
  1 h. D24 and S: everything real (the dust 1.6 h, the storm 12 h), which is real life.
- Sleep or `/time add` during the show ends the show at the jump (§6, rule 4): the sleeper wakes to the aftermath at
  its world age, which is what the sky says. Later sleep jumps every strike's consequences with the sky.
- Server with some sleepers: the jump happens only when the server's sleep share is met; awake players see the sky and
  the aftermath jump together (vanilla's own behavior for the sky). With the default (all players), nobody is watching.
- Late join and seek: the clock is a function of server anchors (the world clock at the burst and at the end of the
  show), already sent for detonations (`S2C.NukeClock`, kept in Flashback snapshots).
- Several detonations: each has its own clock; their dust and smoke add up by their own ages.
- Conventional-only play: every cloud fades within minutes under V and after an hour under D24.
- Seams: the end of the show is a rate change, not a catch-up; anything still changing then speeds up with the sky over
  the 60-s join (under V, the dust lifting at min 20–22). That is the one visible compression, and it is the day's own.
- Passes 1–7 (frame cost under V falls: dust 1.6 h → 22 min; clouds up to 1 h → 4–18 min; under D24 unchanged).

### 5.4 D: each phenomenon real while it visibly changes, then the world's day

The window is set per phenomenon by its own rate of visible change (dust while it thins, clouds while they drift).

- V: the dust stays real for its whole 1.6 h (it thins continuously), the storm on its own window, the clouds real for
  their hour. The dust then hangs over a town whose fires went out an hour earlier; sleep clears the fire but not the
  dust over it.
- Related phenomena of one strike run on different clocks, so their ages diverge after the first window: black rain can
  fall from smoke the haze model has already cleared, the opposite of cause first. A per-phenomenon window also needs a
  threshold for "visibly", which is a hand-picked number.
- Fails 1, 4 and 5.

### 5.5 E: the player-view proposal

The independent player-view agent proposed: "You watch a strike in real time; after that, its aftermath ages with the
world's day. Nothing ever ages slower than real time, and nothing ever goes backward." It wants the watched window
derived from physics (mushroom stabilization, smoke dispersal), the hand-off to change the rate without catching up,
and sleep during the window to move the aftermath on. It ranked "everything on the day" and "everything real" below,
for the reasons in 5.1 and 5.2. Its worst cases: something changing in front of the eye for a bookkeeping reason, two
clocks disagreeing on screen, and a long phase holding the game hostage with no way forward.

E is C reached independently. C adds three things E leaves open: one clock for everything a strike causes, including
injuries and outages (E leaves the body and the grid on the world day from the burst, which is inconsistency 1); a
definition of the window (the strike's own energy), so it is not a per-phenomenon choice; and motion staying real.

### 5.6 Summary

| | A world day | B real | C one strike, one clock | D per phenomenon | E player view |
|---|---|---|---|---|---|
| Show the same in every pack | no | yes | yes | yes | yes |
| 24-hour day = real life | yes | yes | yes | yes | yes |
| Sleep moves the aftermath | yes | no | yes | partly | yes |
| Related effects agree | yes | yes | yes | no | partly |
| No hand-picked durations | yes | yes | yes | no | yes |
| Frame cost under V | lowest | highest | low | high | low |

## 6. Recommendation: C, one strike, one clock

**The principle, as a player can state it:** *A strike plays out in real time while its own energy drives it; what it
leaves behind then ages with the world's day, never slower than real time and never backward. Everything one strike
causes shares its clock.*

Rules:

1. **One clock per strike.** Every consequence of a strike reads that strike's clock: smoke, dust, fire models, rain,
   fallout, contamination, injuries it caused (burns, sickness, hearing), outages it caused. Nothing a strike causes
   reads raw ticks or the raw world clock for its age.
2. **The show is the strike's own phase, from its laws.** It lasts while the strike's own energy drives the picture:
   for a conventional blast until its thermal reaches its apex (`Thermal`, Morton–Taylor–Turner with the air's
   Brunt–Väisälä frequency: 2–5 min) and its crater fuel has burnt down; for the nuke the existing 20 min (the
   firestorm's formation; the 1–15 kt mushroom stabilizes within it and the dust's dark phase at 1.4 km ends at about
   the same time). After the show the atmosphere, gravity, decay and healing carry what is left: the world's processes,
   on the world's day.
3. **Motion is always real.** Positions and speeds on screen (flight, the front, rise, drift with the wind, rain
   falling, sound) move in real time; the clock sets how far each process has gone (amount, density, decay, healing),
   never how fast something crosses the screen.
4. **A jump ends the show.** Sleep and `/time add` during a show end it at the jump: the strike's clock continues at
   the world's rate from the clock's value at the jump. `/time set` backward never moves it back.
5. **Real time is game time.** Lag, `/tick rate`, `/tick freeze` and pause slow or stop every strike clock with the
   world, on the server and on the client alike.
6. **Server-defined.** The clock is a function of server anchors (world clock at the burst and where the show ended),
   so every player, a late joiner and a replay seek see the same state.
7. **A body ages while it is in the world.** Injuries (burns, sickness, hearing) advance by their strike's clock only
   over the ticks the body is present, as vanilla effects do: a player who logs off, or a mob in an unloaded chunk,
   resumes where it left. The world's consequences (fires, fallout, outages) age whether anyone is there or not.

Why C: it is the only candidate that passes every requirement in §4; it is Artem's idea (consequences follow the day,
a 24-hour day is real life) with the exception he named (what moves stays real) made into a rule; the player-view
analysis reached the same principle on its own; and it is the current nuclear rule (`NukeClock`) applied to everything,
so it adds no new concept and no setting. The one visible compression (the end of a show under a short day) is the
same compression the sky shows.

### 6.1 What C changes, and what a player will notice

Ordered by what a player notices first. Each item is one follow-up PR with its tests; frame cost changes are measured
on the scene bench where marked.

| # | Change | Code areas | Player-visible effect (V / D24) |
|---|---|---|---|
| 1 | The inside-sight cap applies only to a camera inside the dust (weighted by the eye's depth in the layer); the column keeps the law's optical depth through the transfer. Not a time change. | `PallField.visible/lifetime`, `dustwall.glsl`, `DustSources`, `DustWallGlslTest`, `PallFieldTest`; guide `not-physical.md` row | From outside and above, the blanket near ground zero stays dense (straight down 4 % of light at 1 min, 18 % at 20 min at 1.5 km; opaque when seen slant) instead of 55 % see-through after a minute. Inside it, the view is unchanged (200 m → 700 m). Bench: no new work per ray. |
| 2 | The dust's age moves to the detonation clock (rule 1). | `PallField` age rows, `DustSources` frame input, `ClientNuclear` | V: dense 20 min, lifts over about 1.5 min at min 20–22. D24 and S: unchanged (1.6 h). Sleep after the show clears it. Bench: shorter life, less cost under V. |
| 3 | Injuries and outages caused by a strike read that strike's clock (rule 1). | `RadiationTicker`, `SicknessModel`, `BurnsEffect`/`BurnInjury`, `Deafness`/`HearingModel`, `grid/Outage`, `Blackouts` | V: during the nuke's show, hearing, burns, sickness and power no longer recover 72× fast; power in the zone stays off through the show. Sleep during the show no longer heals the body while fires stay. D24: unchanged. |
| 4 | A jump ends the show (rule 4). | `NukeClock`, `NuclearEvents.Anchor`, `ClientClock`, `S2C.NukeClock` | Sleep during the show: wake to the aftermath at its world age (fires burning down or out, fallout decayed) instead of a minute-5 mushroom in a morning sky. |
| 5 | The mushroom's lifetime on the detonation clock. | `MushroomModel.lifeSeconds` readers, `CloudPuffs`, `ClientNuclear` | V: a 15 kt cloud disperses at about min 21 instead of 27, with the storm haze it stood over. D24: unchanged. |
| 6 | Conventional strikes get a strike clock: show to the thermal's apex and the fuel's burn-down, then the world's rate. | `client/fx/smoke` (`CloudModel`, `GroundDust`, `SmokeGrid`, `BlastTime`), `FireSmoke`, `warhead/FuelFires`, `TrailModel` (drop the fixed 1200 s cap) | V: a Shahed cloud fades 1–2 min after its apex (about 4–7 min in total instead of up to 60), a missile's after its fuel fire (about 18 min); trails go with them. D24: unchanged (up to an hour). Sleep clears them. Bench: less cost under V. |
| 7 | Bodies age while present (rule 7): sickness stops progressing while the player is offline (today it runs on the absolute world clock, `RadiationTicker.java:223`, so a player can log back in at the fatal stage); hearing becomes server state, held across relog like burns. | `RadiationTicker`, `SicknessModel`, `Deafness`, `HearingModel`, `BurnsEffect` | No death on login after a night offline; relogging no longer cures deafness. |
| 8 | Client strike clocks follow the client level's tick (frozen by `/tick freeze`). | `IncomingClient` smoke, trail and blast clocks | Under `/tick freeze` conventional smoke stands still with the rest of the world, as the guide already says. |
| 9 | Forgotten detonations fade instead of cutting off: fallout at the 7-day mark goes to zero smoothly. | `NuclearEvents` pruning | Dosimeters no longer drop in one step at day 7. |

Guide (both languages, with the PRs that change behavior): the "Time" passage in `nuke.md` states the principle in its
player form, adds conventional strikes, and says that sleep during the show ends it; `not-physical.md` describes the
inside-sight row as "only for a camera inside the dust".

AGENTS.md "Time" would read (replacing the current paragraph):

> **Time** (Artem, 10.11; principle 26.11): one strike, one clock. A strike plays out in real time while its own energy
> drives it (the show: the nuke's 20 minutes, a conventional cloud until its thermal's apex and its fuel's burn-down);
> what it leaves then ages at the world's rate (`util/WorldClock`: time of day, never slower than real, never backward:
> vanilla 72×, day-length mods 1×, stopped day real). Everything a strike causes (smoke, dust, fires, rain, fallout,
> injuries, outages) reads that strike's clock; motion on screen is always real. A time jump (sleep, `/time add`) ends
> a show; real time is game time (lag, `/tick`, pause); a body's injuries age only while it is in the world. No time
> settings; durations come from the laws.

`.claude/rules/dust-wave.md` and `nuclear.md` name the dust's clock; change 2 rewrites both.

### 6.2 What C keeps

The 20-minute nuclear show and its 60-s join (`SHOW_S`, `RAMP_S`), the world clock and its floor, the movement axis
(`scale_preset`, flight speed, wave speed), and every law's own durations. No new setting.

## 7. Every mechanic against pack conditions: mismatches, ranked

From the inventories and a separate audit of pack conditions (tick rate, time control, gamerules, pause, servers,
replays, dimensions). Most player-visible first; "C" marks what the recommended principle fixes, the others are
independent fixes.

| # | Condition | What happens now | Fix | Setup |
|---|---|---|---|---|
| 1 | Any nuke, every pack | The dust blanket is drawn see-through from outside a minute after the front (§1.1) | C change 1 | every game |
| 2 | `doFireTick false` | No gamerule is read before placing fire (`strike/StrikeRules.java:30`); our ruin, flash, crater, fuel and debris fires (up to 20 000 per detonation, `nuclear/world/RuinPlan.java:1175`, `warhead/Warheads.java:489`, `StagedExplosion.java:1447`, `DebrisSpawner.java:51`) never go out, while the drawn storm burns out by its law: permanent static flames over a dead city | Treat `doFireTick false` as `world.fire false` for placement (the admin asked for no fire to burn) | common on servers |
| 3 | `/time set day` at night (the usual op skip) | `dayTime` is absolute, so the set moves it backward; the world clock counts that tick as real (`util/WorldClock.java:106-125`, `ClientClock.java:30`): fires, fallout, injuries and repairs see no night pass | Count a backward set as the forward distance to that time of day (`floorMod(delta, 24000)`) | common in single-player, with ops |
| 4 | Vanilla day, during the nuke's show | Hearing, burns, sickness and power recover 72× fast while the show runs in real time (§3.1, 1) | C change 3 | every vanilla game |
| 5 | Dedicated server, player offline | Sickness advances offline (login at the fatal stage); burns are frozen offline; hearing is cured by relogging | C change 7 | common |
| 6 | Flashback pause, `/tick freeze` | Conventional blast clouds, ground shake and crater smoke keep running (`client/IncomingClient.java:232,259,264,270`) while particles, trails and the nuke hold; the guide says clouds stand still | C change 8 (rule 5) | common for filming |
| 7 | Flashback export, pause, freeze | Heat shimmer runs on the wall clock (`client/fx/layer/HeatPass.java:644,733`): boils while paused, churns too fast in exported video | Drive it by game ticks plus partial tick or the replay position (rule 5) | creators |
| 8 | Vanilla day, conventional play | A Shahed or missile cloud stays up to an hour (three in-game days); a trail 20 min; sleep leaves them | C change 6 | every vanilla game |
| 9 | Sleep during a show | Fires, rain and fallout stay at the minute of the sleep while the sky jumped hours (the guide says the fires are out after a night's sleep) | C change 4 | common in single-player |
| 10 | Day-length mods that set `dayTime` themselves (not NeoForge `dayTimePerTick`) | Without detonations the server does not send its clock, so the client's hearing clock counts vanilla steps (`ClientClock.java:30`, `NuclearStrikes.java:355`): hearing after conventional blasts heals about 72× too fast | Send the world clock while any strike clock or injury is live (rule 6) | depends on the mod |
| 11 | 24-hour or stopped day | Outages 18 real hours, the storm 12 real hours, fallout real days: real life by design; `restore_spread_seconds` is world-clock time but its tooltip says seconds (90 s → 1.8 real h) | Fix the tooltip; the guide says plainly that a stopped day means real-time consequences and that `/time add` moves them on | common |
| 12 | Day 7 of a detonation | Fallout drops to zero in one step when the detonation is forgotten; unfinished crater progress is dropped with it | C change 9 | every long game |
| 13 | `/tick freeze` | The world clock keeps counting real time on frozen ticks (`WorldClock.java:112-125`): after the show, storm, rain, fallout, sickness and repairs advance in a frozen world and jump on unfreeze | Skip counting while the tick manager is frozen (rule 5) | rare |
| 14 | Day mods stepping more than one tick at a time | A step of +N every M ticks counts N plus the real floor already credited: a 24-hour day as +10 every 720 ticks runs 2× (`WorldClock.java:107-111`) | Subtract the real time credited since the last step | rare |
| 15 | `/tick rate`, `/tick sprint`, Flashback speed | The client assumes 20 ticks per second (`ClientNuclear.java:213,266-270`, `client/sound/OwnSources.java:380`): catch-up spurts, sound anchors drifting | Use the tick manager's tick length | rare |
| 16 | Long days | Wind changes every 24 000 game ticks (`util/Wind.java:23-31`): 72 winds per world day under a 24-hour day; sleep does not move it | Change the wind with the world clock | low |
| 17 | Other packs | End's fixed time read as noon by the eye-adaptation model (`nuclear/radiation/FlashEyes.java:40`); sea level fixed at 63 (`util/Air.java:22`); `randomTickSpeed 0` disables lamp relight in Sable plots (`grid/block/Unlit.java:51-53`); rain does not wet fuel for ignition | Read the dimension's fixed time and the level's sea level | rare |

Handled correctly (checked): single-player pause (server and client clocks stop), sleep with any sleep share and
gradual time-lapse sleep mods (every tick's day advance counts), a 2-hour day in single steps (exactly 12×), a 24-hour
day (1:1), `doDaylightCycle false` (real), late join, dimension change and respawn (detonations re-sent, aged dust fades
in), Nether and End (the overworld day is shared, no black rain under a roof), the Flashback nuclear clock (replay
position, clock kept in snapshots), and the gamerules the mod needs (`keepInventory`, `doTileDrops`, spawn chunks).
Server lag slows the show and the server's work alike (game ticks on both sides).

## 8. Needs Artem's word

- Adopt C (or another principle) as the time rule; the AGENTS.md "Time" wording above.
- Rule 4 changes a stated behavior ("sleep during the show changes nothing" in `NukeClock`).
- Mismatch 2 (`doFireTick false` stops our fire placement) changes what servers with fire tick off see: no burning
  ruins at all instead of permanent flames.
