# Time in Incoming: one principle for how long consequences last (design note)

Status: adopted (Artem, 26.11: the principle and the points of §11); the design for the follow-up PRs of §9 and §10.
Code references are `path:line` against `main` at the time of writing; `J/` is `mod/src/main/java/ua/zentix/incoming/`.
The numbers come from code reading and from throwaway probes over the shipped models (`PallField`, `CloudModel`,
`DustModel`, `FalloutModel`; 15 kt surface burst, defaults, game scale), quoted where used.

How this note was made:
- an inventory of every time-dependent phenomenon (§3);
- twelve candidate principles (§5), run through twelve player stories (§6);
- four player personas, briefed only on the stories and real-life facts, never on the candidates (§7; their answers
  are in the mission folder `personas/`);
- the choice with its root cause and rules (§8), and the one conflict no principle removes, by number (§8.2);
- the follow-up changes (§9) and the pack-condition mismatches (§10).

Four independent review rounds shaped it: two on an earlier draft, two on this one. Each round's probes are in its
mission folder. The choice is P4, "the air in real time, the ground on the world's day". Another candidate, P12
(compress only the sources and sinks of everything), was refuted by probes (§8.1).

## 1. The question

In 3.14.0 the haze left by a nuclear burst (the dust blanket) visibly thins within 1.5–2 real minutes, and parts of the
map clear; real dust hangs for tens of minutes to hours. Artem asked for the time rules to be set up properly, from one
principle: which phenomena run in real time, which follow the Minecraft day and which follow something else, with no
hand-picked durations, consistent in every pack (vanilla day, day-length mods, stopped day, sleep, servers). His raw idea
was to tie consequences to the day length, so a 24-hour day plays out as in real life and a vanilla day compresses it.
He also said the blast wave and flight cannot follow the day.

### 1.1 The cause of the 1.5–2 minutes, by number

Two things show the dust blanket, and neither is the clock:

- The dust's age is real time (the pass's age is `DustSources.source`, `a.seconds(partial)`: ticks / (20 · scale),
  outside the detonation clock), so the law alone would keep it. Fine dust from 15 kt lives 5673 s (1.6 h, `lifetimeEnd`).
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

Between 43 s and 63 s after the front the drawn column goes from 21 % to 55 % see-through (closer in, at 782 m, from 17 % to
58 %), which is the thinning Artem saw. Seen slant from a hill (path about 3× the vertical) the uncapped column stays
opaque for about 20 minutes; the capped one passes 17 % of the light after one minute.

So the symptom has its own cause (a presentation that belongs to the eye inside the dust is applied to the whole
column), separate from the time question. Change 1 in §9 fixes it; no time principle alone would cure it.

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

Grouped by clock (real durations: vanilla day / 24-hour day / stopped day; 15 kt, game preset; conventional clouds
with `CloudModel.Air(n 0.01, wind 3 or 8 m/s, shear 0.2, stability class 3, humidity 0.5)`; other air inputs move the
numbers by up to a quarter):

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

**Detonation clock** (real for 20 min, then the world's day): firestorm (full strength at 20 min, held about 3 h, then decaying, out by 9–12 h),
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
3. **Dust and mushroom outlast the fires under a vanilla day.** The storm and black rain end at about 23–30 min, the
   15 kt mushroom stays to 27 min and the dust law to 1.6 h: the dust hangs over the area about an hour after the
   fires are out, three vanilla skies after the burst. The mushroom's few minutes are its own law; the dust's hour is
   the complaint this note answers (§8.2).
4. **One conventional strike, three clocks.** Its crater fire on ticks (2.5–16 min), its cloud on client ticks (up to
   60 min), the hearing damage it causes on the world clock (13 min vanilla, 16 h on a 24-hour day).
5. **The longest conventional effects ignore the day.** A Shahed cloud stays up to an hour under a vanilla day (three
   in-game days), drifting 10–20 km downwind by its end; a trail is capped at a fixed 1200 s labelled "an in-game day";
   sleep leaves both.
6. **Client-only state.** Conventional clouds and crater smoke are not shown to a late joiner and vanish on a replay
   seek; hearing loss is cured by relogging (it is client state), while burns and sickness persist.
7. **Wind transport on the detonation clock.** The black-rain band starts at a downwind point at `START_S` + distance /
   wind in detonation-clock seconds (`nuclear/model/BlackRainModel.java:40-49`), fallout arrives at distance / wind in
   clock hours (`FalloutModel`), while the haze plume that carries them grows on real ticks (`PallField.plume`). Under a
   vanilla day, after the show the band and the fallout sweep downwind at 72× the wind (121× at the join's peak): they
   reach a point before the smoke that carries them is drawn there, an effect ahead of its cause.
8. **The guide is ambiguous.** `nuke.md:194-195` says that under a vanilla day "the fires burn out in about 10
   minutes", which reads as 10 minutes after the burst; the storm is out 27–30 min after it (about 10 min after the
   show).


## 4. What a good principle must satisfy

From the concept (AGENTS.md "Principles"), the player feedback (survival players and a YouTuber: too much smoke for too
long, `/incoming clear` after a nuke "because it will hang around"; the crater fuel fire "burns forever and is loud";
one voice: "the smoke goes away too quickly") and the personas (§7):

1. **Cause first, no seams.** No effect before its cause; nothing races (moves faster than its real speed), pops or
   vanishes in front of an awake player; nothing carried (rain, fallout) without its carrier or ahead of it.
2. **The show is the product.** What a player watches form (flight, flash, wave, mushroom, the dust rolling in, fires
   taking hold) is the same real-time event in every pack and every take.
3. **Lived-with consequences follow the world.** A 24-hour day plays out as real life; a vanilla day compresses the
   aftermath with its sky; sleep moves it on; nothing holds a base or a spawn hostage for real hours.
4. **One law per phenomenon, no hand-picked durations.** Durations come from the laws; the only time constants are the
   sourced show length and marked presentation blends.
5. **Same for everyone.** Server-defined: every player, a late joiner and a replay seek see the same state.
6. **Frame and tick cost bounded** at any strike count; no new time setting.
7. **Every kind of player gets what it wants by default, or by a lever it already uses** (sleep, its day length, a
   vanilla gamerule, the existing `/incoming clear`), never by a setting named after a consequence.

## 5. Candidate principles

Twelve, deliberately different. "Show" means the strike's first, forming phase; "the day" means the world clock's
rate k (vanilla 72, a 2-hour day 12, a 24-hour day 1).

| | Principle | In one line |
|---|---|---|
| P1 | Everything on the day | Every consequence, the show included, runs at k (Artem's raw idea). |
| P2 | Everything real | Ticks for everything; sleep and `/time` change nothing (vanilla's own tick rule). |
| P3 | Real, the vanilla levers move it | Everything real; sleep and `/time add` jump it forward, as vanilla sleep clears the weather. |
| P4 | Air real, ground on the day | Every body in the air lives its whole law in real time; what stays in place runs real during the show, then at k (**chosen**, §8). |
| P5 | Show real, then the whole law on the day | After the show every phenomenon, its motion included, runs at k (the earlier player-view proposal). |
| P6 | Show real, then appearance on the day | After the show a body's look (density, colour) reads its law at the day's age while its geometry stays real. |
| P7 | Watched real, unwatched on the day | A consequence ages at k only while no player can see it. |
| P8 | Near real, far on the day | Real within the players' view distance, k beyond it. |
| P9 | Per-player time | Each client ages the aftermath by its own player's sleeps and absence. |
| P10 | A fixed game pace | After the show, always the vanilla pace (72), whatever the pack's day. |
| P11 | Weather washes the air | Air real; rain (vanilla weather) scavenges smoke and dust; sleep clears it. |
| P12 | Motion real, amounts on the strike's clock | Every position, speed and spread is real; every source and sink (burning, settling out, rain-out, decay, healing, repair) runs real during the show, then at k. |

## 6. Stories, run through every principle

Twelve stories, vanilla day unless stated; 15 kt surface burst; the mod's wind is 5–15 m/s at 1 km, about half that at
10 m in neutral air (`J/util/Wind`). Each cell names what the player meets first that he would object to; "ok" means nothing. The chosen principle's stories
are told minute by minute in §8.4.

| Story | P1 | P2 | P3 | P4 | P5 | P6 | P7 | P8 | P9 | P10 | P11 | P12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 Survival nuke 2 km from base | the show over in 20 s | storm 12 real h, sleep does nothing | storm until he sleeps | dust half-clear at 45 min, gone ~95; the next night's sleep clears it | dust races at 180–540 m/s | dust fades in ~1.5 min | base sees it: real, 1 h | same as P7 | ok alone | ok | dust stays on a dry day | dust still ~1 h (it ends by dilution); fallout hot spot 12× at 8 km |
| 2 YouTuber sequence | show differs per pack | ok | ok | ok | clouds race in frame | Shahed smoke gone 3–6 min after the hit | seek shows another state | same | camera and actor differ | ok | weather changes takes | missile crater fire switches off in 4 s |
| 3 Realism fan, 24-h day | ok | ok | ok | ok | ok | ok | ok | ok | ok | aftermath 72× | ok | rain needs drawn smoke 22–36 km out for 2 h |
| 4 Friends' server, one sleeps | ok | sleeper's night does nothing | ok | ok (with players awake a night skip moves only the sky) | cut | cut | ok | ok | players disagree | cut | cut | clouds crossfade out for watchers |
| 5 War server, dozens of strikes an hour | show lost | firestorms 12 h, fallout days | nobody sleeps: same as P2 | dust ~1 h per nuke | racing smoke | ok | one watcher keeps all real | boundary moves with players | dangers differ per player | ok | random | black-rain band 55× shorter |
| 6 Creative tests, sun stopped | ok | 12-h fire on the range | same | dust ~1 h | ok | ok | real while watched | real | ok | ok | same as P4 | ok (vanilla pace kept) |
| 7 2-hour day | show lost | as P2 | as P3 | dust ~1 h | racing at 12× | ok | as P7 | as P8 | ok | aftermath 6× too fast | as P4 | ok |
| 8 Conventional raids on a village | Shahed fire 2 s | ok | ok | ok | racing | smoke gone 3–6 min after the hit | ok | ok | ok | ok | ok | ok |
| 9 Late joiner and replay | ok | ok | ok | ok | ok | ok | state depends on who watched | depends on positions | differs per client | ok | random | ok (needs change 11) |
| 10 Always-noon server, `doFireTick false` | ok | 12-h fire | same | dust ~1 h | ok | ok | real | real | ok | ok | as P4 | ok |
| 11 Two nukes 10 min apart | show lost | as P2 | as P3 | two dust hours overlapping | racing | ok | real | real | ok | ok | as P4 | ok |
| 12 Sleep 2 min after a dusk nuke | ok | wakes to a minute-2 mushroom | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |

Notes on the cells:
- P1: under a vanilla day the dust's opaque phase lasts 17 s and the firestorm forms in 17 s; the same nuke is a
  20-second event in one pack and a 20-minute one in another.
- P5: compressing the motion multiplies every drift by k; the dust drifting at half the wind (2.5–7.5 m/s) would cross
  the map at 180–540 m/s.
- P6: a conventional cloud has no physical loss of its soot; reading its density at the day's age while its size grows
  in real time draws mass it never lost (a Shahed cloud two minutes after a 2-min apex keeps about 1/760 of its drawn
  mass; probe `review-probes/blend.py`). Its smoke is gone 3–6 min after the hit, about a minute after its apex, which the realism persona names a
  dealbreaker.
- P7–P9 make the state depend on who looks, stands or sleeps where, which no shared world can hold: a replay seek
  cannot know who was watching, and fallout or fire cannot differ between two players standing side by side.
- P10 ignores the pack's day, so a 24-hour day no longer plays as real life (Artem's own way to get long effects).
- P11 ties the outcome to random weather; on a dry day it is P4.
- P12 was the first choice of this note; the independent review's probes (`review-probes/` in the mission folder)
  refuted it by number (§8.1). What ends the dust is dilution and transport, so P12 leaves it ~1 h like P4, or, read as
  compressing that term too, loses 1/245 of the drawn mass by 92 s with nowhere to land. Compressing deposition shrinks
  every footprint by about k: 29 % of all fallout activity dumps on a 2 km stretch at 8 km (12× the law's dose there),
  the black-rain band shrinks 55×. A compressed fuel fire's last fifth (192 s) passes in 4 s.

## 7. The personas

Four fresh sessions, each given only its persona, the stories and real-life durations (`personas/stories.md`), none of
the candidates. Their wants, in real minutes on a vanilla day:

| | Survival player | Realism fan | Content creator | Server admin |
|---|---|---|---|---|
| Nuclear show | full, real | real | real, identical in every take and pack | real |
| Dust over a place | thick 3–5 min, gone by 8–15 | opaque 20, gone 1.5 h, even on vanilla ("the price") | 10–20 | thick ~10, gone by 20–25 |
| Conventional smoke | 2–5, no buildup over the village | 10–60; gone in 1–2 min is a dealbreaker | 10–20 (3–5 usable in frame), no pile-up | 5–15, no haze over spawn |
| Crater fuel fire | 1–3, short | 2–16, loud throughout | ≥ 3 | 2–5, the loop ends with the fire |
| Firestorm | at the fort, not over the farm | 12 world h; ~10 real min on vanilla accepted if it burns out | game days | 20–40 visible |
| Sleep | moves everything; nothing pops | an honest jump for everything | never cuts a watched show; a morning cut shows a plausible state | moves the slow parts, never the show |
| Stopped sun | aftermath still ends in minutes | slow things advance (in real time) rather than freeze | must not change any length; still ends | "no night, not frozen aftermath" |
| `doFireTick false` | no block fire, crater fire ends | own fires follow their law | own fires burn their time | nothing that never goes out |
| Never | day-length mod, `/time add`, stopping the sun | a duration slider; a clear command in survival | a duration slider, a day mod just to film | duration sliders, `/time add` to clean up |

What they share: the show real and identical; motion never sped up; sleep an honest jump; one shared state for
everyone; nothing that never ends; no consequence slider. Where they split: only how long what the strike leaves *over
a place* lasts on a vanilla day (the dust after the show, the crater fire), survival and admin against the realism fan.

Scores: each persona's five top priorities and three dealbreakers, from its own answer (`personas/*.md`). A cell gives
the priorities met by default, "+L" for one met only by a lever that persona already uses, and the dealbreakers hit
("DB").

| | Survival | Realism | Creator | Admin | Dealbreakers hit |
|---|---|---|---|---|---|
| P1 everything on the day | 3 | 3, DB | 2, DB | 5 | 2 |
| P2 everything real | 3, DB ×2 | 4, DB | 5 | 3, DB | 4 |
| P3 real, levers move it | 4 +L | 5 | 5 | 3, DB | 1 |
| **P4 air real, ground on the day (chosen)** | **4 +L** | **4** | **5** | **4** | **0; 2 under a one-sleeper rule** |
| P5 whole law on the day | 4 | 3, DB | 3, DB | 5 | 2 |
| P6 appearance on the day | 5 | 3, DB | 4 | 5 | 1 |
| P7 watched real | 4 +L | 4 | 3, DB | 3 | 1 |
| P8 near real | 4 +L | 4 | 3, DB | 3 | 1 |
| P9 per-player time | 5 | 3 | 3, DB | 2, DB | 2 |
| P10 fixed game pace | 5 | 3, DB | 5 | 5 | 1 |
| P11 weather washes the air | 4 +L | 5 | 4 | 4 | 0 |
| P12 motion real, amounts on the strike's clock | 4 | 3 | 4, DB | 4 | 1 |

How the deciding cells come out:
- P2 hits survival's "smog over the base with no way to sleep it off" and "sleeping changes nothing", the realism
  fan's "a fresh mushroom after I slept", and the admin's "never ends, piled up over an evening" (12-h firestorms
  on a server where nobody sleeps). P3 removes the sleep cases, but a war server still piles them up.
- P1, P5 and P10 hit the realism fan's "conventional smoke gone in a minute or two" or "a firestorm dying in 20 min
  on a 24-hour day"; P1 and P5 hit the creator's "a fast-forward in frame".
- P7–P9 hit the creator's "replays that disagree with the live game"; P9 also hits the admin's "danger differs
  between players".
- P4 leaves survival's "aftermath over the base for minutes" to the next night's sleep, which on a server needs
  every player asleep, and misses the admin's "every part ends within a known limit" (the dust ~1 h on a vanilla day).
- P11 is P4 on every dry day.
- P12 (scores after the review's probes) leaves the dust as P4 does and adds the creator's "a pop in frame" (a
  missile's crater fire off in 4 s), the realism fan's "effects that disagree" (fallout and rain footprints shrinking
  with the day) and the survival player's and admin's "danger where I did not aim" (a 12× fallout hot spot).

P4 and P11 hit no dealbreaker in single-player and under the default sleep rule; P11 is P4 on every dry day and ties
the rest to random weather. P4 leaves two wishes open:
- The survival player's and the admin's wish for the nuclear dust to be gone by 20–25 min on a vanilla day. §8.2 shows
  by number that no principle grants it without a seam.
- Under a one-sleeper rule with others awake, the sleeper's wish that the night moves the fires and the air
  (survival's and the realism fan's dealbreaker 2, in that case only). The watchers' wish not to see things vanish wins
  (rule 7); doses, injuries and repairs still move.

## 8. The choice: the air in real time, the ground on the world's day

**The principle, as a player can say it:** *Everything a strike puts into the air (clouds, dust, the mushroom, smoke,
rain and fallout on their way down) lives in real time and leaves with the wind, like Minecraft's own clouds and
weather. What it leaves in place (fires, contamination, power, your body) plays out in real time while the strike is
still playing out, then ages with the world's day: a 24-hour day is real life, a vanilla day compresses it with its
sky. When everyone sleeps, the night moves both on.*

### 8.1 Why the split is by body (root cause, confirmed twice)

The earlier draft's two review rounds found one defect class when a body's terms were split across clocks: its
motion and what it carries, or its size and its density. This note's first choice, P12, split each body's transport
equation instead: transport real, sources and sinks compressed. The review probes (`review-probes/`) refuted it with
numbers:

- **The dust ends by transport.** What clears the nuclear dust over a place is `DustModel.FINE_S` (800 s e-folding,
  "dilution and transport", fitted to Hiroshima: dark ~20 min at 1.42 km, lighter by 30). Settling alone keeps 82–92 %
  of the extinction through 95 min. Compressing settling only leaves the dust ~1 h. Compressing `FINE_S` too draws a
  blanket that has lost 1/245 of its mass by 92 s with nowhere to land.
- **Deposition is transport too.** Compressing the settling out of a moving cloud shrinks its footprint by about k. On
  a vanilla day 29 % of all fallout activity dumps within ~1.5 min on a 2 km stretch at 8 km (12× the law's dose
  there), and the black-rain band shrinks from 21.6–36 km to 0.39–0.65 km.
- **A compressed fire's end pops.** The missile's crater fuel fire, compressed after its cloud's apex, burns at full
  strength for 31–35 s and then fades through its law's last fifth (192 s) in 4 s.

So the cause holds at every level: with space and speeds real, no term of anything in the air can be compressed. It
would race, lose mass it never lost, shrink its footprint or pop. Compression is seamless only for what stays in place
and changes in place: a fire burning down at the speed it would after hours, contamination decaying, a body healing,
a district repaired. The split therefore follows the body, as P4 has it. This section's rules (§8.3) are P4 with the
first draft's defects and the review's findings fixed.

### 8.2 The conflict no principle removes, by number

On a vanilla day, at 1.5 km from 15 kt, the dust's law is still dark when the 20-minute show ends: 1 % of the light
passes at 21 min, 11 % at 31 min, 47 % at 45 min, 86 % at 65 min, gone by 95 min, while it drifts downwind.
- Survival player and admin want it gone over their base or spawn by about 20–25 min.
- The realism fan wants its physical hour, on any day.

By §8.1 the dust cannot be shortened without a seam. What the principle gives each side:
- **Survival in single-player.** The night always comes at most ~11 min after the show, because the show lasts exactly
  one vanilla day. Sleeping then clears the air (rule 7). So the murk over the base lasts at most until ~31 min after
  the burst, by which time 11 % of the light passes anyway. His dealbreaker ("more than ~20 min with no way to sleep it
  off") is not hit.
- **Admin on a server.** The night skip clears it only when every player in the overworld sleeps (rule 7). Otherwise the dust
  keeps its ~1 h, drifting away. This is his one unmet wish, not a dealbreaker: it ends by a known law, at ~95 min at
  most.

Two physical laws, not time rules, could shorten it under some conditions: rain washing the dust out on rainy days,
and an advective clearing that ties the dust's hour to the wind instead of the fitted 800 s. Both are separate law
work with their own references.

Shortening the dust on short days is possible only as a marked deviation against "smoke or fire shorter than its law"
(Artem, 19.11): the drawn blanket would lose its mass without landing it anywhere. Visibly, after the show it clears in
about 1.5 min on a vanilla day (11 % at 36 s, 47 % at 50 s, 86 % at 67 s, gone at 92 s). Nothing is carried by the
dust (black rain comes from fire smoke, fallout from `FalloutModel`), so no other seam follows. This note does not
take it: it is the one decision left to Artem (§11).

### 8.3 The rules

1. **Two ages per strike.** The motion age M is real time plus every counted jump (rule 7) since the strike. The
   strike clock S equals M during the show and runs at the world's rate after it; S ≥ M always.
2. **The air on M, whole.** Every body in the air lives its whole law on M: rise, spread, drift, settling, thinning,
   light, and what falls from it. This covers the blast and conventional clouds, ground dust, the nuclear dust blanket,
   the mushroom and its stem, trails, fire smoke, black rain and fallout in flight. Such a body leaves by its own
   motion. All bodies share this rate, so two strikes' dust in the same air ages alike.
3. **In place on S.** What stays in place reads S:
   - fires (growth, strength, burning down), their light and heat;
   - contamination and fallout on the ground, and their decay;
   - outages and repairs;
   - injuries (rule 8).

   Ruins are permanent; the staged collapse is part of the blast (real).
4. **Handoffs carry the state at the moment.**
   - Smoke leaving a fire is emitted per real second at the law's rate for the fire's strength at its S, then lives on
     M. A fire never flares when the day compresses it; on a short day it emits less in total, because it burns for
     less real time.
   - The haze over and downwind of the fires is these parcels. Black rain is their rain-out, where they are, while they
     are drawn; the rain's window (`BlackRainModel.END_S`) becomes the parcels' law. At k = 1 a point gets its 90 min;
     on a vanilla day the fires stop feeding the parcels within ~10 min of the show, so a point gets at most ~8–10 min.
   - Fallout arrives where and when its law carries it (M), also from the stabilized cloud after it is too thin to
     see, as in reality. Its age on the ground at a place is its arrival age (M) plus the strike clock that has passed
     there since it landed (S now − S at arrival). The same holds for black-rain deposit. S at any past real time comes
     from a saved history of the world clock per detonation (one sample a real minute until its last arrival, at
     most ~11 h).
5. **The show is the strike's own phase, from its laws.**
   - A detonation's show lasts 20 min: Hiroshima's firestorm formed about 20 min after the burst.
   - A conventional strike's show lasts until its cloud has stopped rising (the cloud law's apex, π/N: 2–5 min) and its
     own crater fuel has burnt out (`FuelFires`: Shahed 2.5 min, missile 16 min), so a crater fire's fade (its last
     fifth, 192 s for a missile) is never compressed. The firestorm burns down on S by design: it lasts hours of world
     time.
   - After a show, S joins the world's rate by a new rate blend: dS/dt = 1 + (k − 1)·u, with u a smoothstep of r / 60 s
     (presentation), r the real seconds since the show's end and k the world's rate without jumps. It never overshoots
     k and never catches up the show. Today's join catches up instead and peaks at 120.8× real under a vanilla day.
6. **The world's rate k** is the day's measured advance per real time, never below 1 (`util/WorldClock`).
   - When the day stops advancing (the `doDaylightCycle` gamerule, or a mod that holds the time), k keeps the last
     running rate, vanilla 72 if it never ran. Stopping the sun stops the light, not the world.
   - A mod that turns the vanilla cycle off but advances the time itself is measured as running.
7. **Jumps.** A jump is a forward step of the day of at least an hour in one tick: a sleep skip, `/time add`, or,
   while the day runs, a `/time set` whose forward distance (modulo one day) is under 12 hours (a night skip).
   - With no awake player (spectators and idle players included) in its dimension (single-player sleep, or every
     player there asleep), a jump adds its hours once to M and S of every strike in that dimension and ends their
     shows.
   - With an awake player there, nothing drawn changes in front of anyone: the air (M), the fires and the shows keep
     their time. The jump still moves what nobody sees change: ground dose and contamination, injuries, outages and
     repairs (each a recovery clock of its own, rule 8). So a sleep or an op's `/time add` heals and repairs as a night
     does, as the 10.11 rule has it, while the watchers' picture stays whole.
   - A step under an hour is a rate, not a jump: the in-place clocks follow it through the world clock, the air ignores
     it. With the day stopped, `/time set` only chooses the light. A backward set never invents days.
   - Flight and the blast wave are never jumped: they finish in seconds at their speed, and their damage stays in the
     work budget. A jump that falls while a front is still crossing is counted when the front ends (within ~1 min).
   - Gradual time-lapse sleep mods are a rate: the ground follows it and the air keeps real time (limit: after a lapsed
     night the plumes are where real time puts them).
8. **Shared states and bodies.** A state with several causes (a body's hearing, burns and sickness; a district's
   outage) recovers at real rate while any cause is in its show, then at the world's rate through the same blend;
   counted jumps count. A body's injuries advance only while it is in the world. Exposure windows (the ear's 2-min
   dose) are event time, M.
9. **Real time is game time.** Lag, `/tick rate`, `/tick freeze` and pause slow or stop every clock with the world, on
   the server and on the client (the client level's tick, the replay position).
10. **Server-defined.** The clocks are functions of server anchors: the world clock at the strike, the real time and
    world clock at the show's end, and the saved counter of counted jumps. Conventional strikes are recorded on the
    server and sent to late joiners and on a replay seek like detonations, so everyone sees one state.
11. **`doFireTick false`.** Strikes light no block fire that the rule would keep burning forever. The crater fuel fire
    removes all of its own blocks at burn-out, and the firestorm runs its law.

### 8.4 The stories under P4, as a player lives them

Vanilla day, 15 kt, wind 5–15 m/s at 1 km unless stated. A world hour is 50 real seconds on a vanilla day.
1. **Survival nuke 2 km from the base.**
   - 0–20 min (the sky passes one day): the flash, then the wave reaches the base at ~17 s and the dust rolls over it.
     From outside the blanket is dark (change 1). Inside you see about 200 m after one minute and 700 m after five.
     The mushroom rises and drifts, and the fort's fires grow into a firestorm at full strength at 20 min.
   - Dust: 11 % of the light at 31 min, half clear at 45, mostly clear at 65, gone by ~95 min, drifting downwind
     meanwhile.
   - Firestorm: declines from ~23 min and is out by 28–30 min. Its smoke drifts downwind with black rain under it; if
     the base is upwind, none comes near.
   - Mushroom: gone at ~27 min, 8–24 km downwind.
   - Fallout: lands downwind where its law carries it, and on the ground decays over 7 world days (2.3 real hours).
   - Body, if hit: hearing heals in 16 world hours (13 min after the show), burns in weeks of world time.
   - Power: a tripped zone is back 2–24 world hours after the show (100 s–20 min), a damaged belt in 2–14 world days
     (40 min–4.7 h).
   - The next night comes at most ~11 min after the show. Sleeping then (up to 11.5 h) clears the dust and smoke,
     leaves the firestorm smouldering out (it is out by 9–12 h of its clock) and lowers the dose.
2. **YouTuber's sequence.**
   - Shahed: the cloud rises for 2–5 min, the crater fire burns its 2.5 min, and the cloud leaves the village with the
     wind in a minute or two, then recedes for tens of minutes (about 40 at 8 m/s).
   - Missile: the crater fire burns its 16 min and goes quiet when it is out.
   - Nuke: the same 20-minute event in every pack and take.
   - A Flashback seek or pause shows the true state; `/incoming clear` resets the set between takes.
3. **Realism fan, 24-hour day (k = 1).** Everything is real: dust dark ~20 min and gone in ~1.5 h, mushroom ~27 min,
   firestorm out by 9–12 h, black rain ~1.5 h under the smoke, fallout for days, burns for weeks, the substation
   repaired in 18 h. A sleep shared by everyone skips its hours for everything at once.
4. **Friends' server.**
   - With the default sleep share, the night is skipped only when everyone sleeps; then everything moves on together.
   - With a one-sleeper rule and friends awake, the sky moves and so do doses, injuries and repairs. The show, the
     air and the fires go on in front of the watchers; the sleeper wakes to a morning sky over the aftermath at its
     real age. This is the second conflict no principle removes: one shared world cannot both keep the watchers'
     picture and advance the sleeper's; the watchers win (no vanishing in front of anyone).
5. **War server.**
   - Conventional clouds leave each site with the wind in a minute or two and recede in one direction. The smoke
     budget (at most 128 cloud sources, 2 ms per frame) bounds the cost at any count.
   - Crater fires burn at most 16 min.
   - A nuke: dust ~1 h over its area (half clear at 45 min), fires out by ~30 min, one fallout zone for everyone. Late
     joiners see the current state.
6. **Creative tests with the sun stopped.** The ground keeps the vanilla pace and the air its real time, so a
   conventional test site is clear in a few minutes. A nuke's dust takes its hour; `/incoming clear` resets the range
   at once.
7. **2-hour day (k = 12).**
   - The show and the air are as everywhere.
   - The firestorm declines from ~35 min and is out 64–79 min after the burst.
   - Hearing heals in 80 min, a tripped zone in 10 min–2 h, fallout on the ground in ~14 h.
   - A seasons mod changes this only if it changes the day's length.
8. **Conventional raids on a village.** Each hit's cloud rises for 2–5 min while its crater fire burns, then drifts out
   of the village with the wind. Houses burn as ordinary game fire. Hearing heals 16–19 min after a Shahed hit and about 30 min after a missile's
   (its show includes the 16-min crater fire), or at once with a sleep.
   Raids do not pile up over the village: every cloud leaves downwind.
9. **Late joiner and replay.** A friend joining at minute 10 sees minute 10: the same clouds in the same places, the
   fire at the same stage. A replay seek shows that moment's state; a pause stops everything.
10. **Server rules.**
    - On an always-noon server, durations are as on a vanilla day.
    - With `doFireTick false` no block fire is left burning forever; the crater fire and the firestorm burn and end by
      their laws.
11. **Two nukes 10 min apart.**
    - The air of both ages in real time: the first town's dust is half clear at 45 min, the second's at 55.
    - Each firestorm is out 8–10 min after its own show.
    - A body or district hit by both recovers at real rate until both shows are over.
12. **Sleep 2 min after a dusk nuke (single-player).** The wave finishes at its speed, and the sleep ends the show. On
    waking, about 9.6 h later:
    - mushroom, dust and black rain are gone;
    - the firestorm is smouldering out (out by 9–12 h);
    - the fallout on the ground is that much older;
    - burns are still there;
    - part of the power is back.

## 9. What P4 changes in the mod, and what a player will notice

Each item is one follow-up PR with its tests; "Formats" names packets (`IncomingNetwork.PROTOCOL` bump), saves and
replays. Frame-cost changes are benched where marked.

| # | Change | Code areas | Formats | Player-visible effect |
|---|---|---|---|---|
| 1 | The inside-sight cap becomes a camera property (§1.1): it weighs each ray's segment inside the layer by the eye's depth below the local layer top; outside the dust every ray reads the law through the transfer. Not a time change; marked presentation. | `PallField.visible/lifetime`, `dustwall.glsl`, `DustSources`, `DustWallGlslTest`, `PallFieldTest`, `tools/dust_wall_gl.py`; guide `not-physical.md` | none | Every pack: from outside, the blanket stays dark through the show instead of 55 % see-through after a minute; inside, the view is unchanged (200 m → 700 m). Bench: no new work per ray. |
| 2 | Clocks: M and S per strike, the new rate blend (rule 5), counted jumps (rule 7), the measured stopped-day rate (rule 6), the `/time set` rule, the saved counter and show-end anchors. | `WorldClock` (k without jumps, the counter, the stopped rate), `ClientClock`, `NukeClock`, `NukeClockTest` (no overshoot), `NuclearEvents.Anchor`/`endShows`, `ClientNuclear.clockTicksAt`, `S2C.NukeClock` | Clock packet with the counter to every client once a second; `ShowEnd` gains the show's real end; PROTOCOL bump. Save: counter and stopped rate in `WorldClock`, two `Anchor` fields. Old replays: a 20-min show, no jumps | Sleeping alone during a show wakes you to the aftermath at its world age; a night skip with friends awake changes nothing in front of them; a stopped sun no longer turns a 30-min aftermath into 12 real hours. |
| 3 | The air reads M: dust, mushroom, clouds, ground dust, trails and smoke add counted jumps to their real age. | `DustSources.source`, `ClientNuclear` (mushroom, `CloudPuffs`), `client/fx/smoke` (`SmokeGrid`, `BlastTime`), `FireSmoke`, `TrailModel`; texts `nuclear.md`, `dust-wave.md` | uses change 2 | After a night everyone slept through, the sky is clear of the strike's dust and smoke; otherwise the air runs as today. |
| 4 | Handoffs (rule 4): the haze over and downwind of the fires becomes smoke parcels (emission per real second at the fire's current strength, motion on M), replacing today's plume capped at 2 fire zones; black rain is their rain-out while drawn; fallout and rain deposit arrive on M and age on the ground by arrival age plus S since landing (world-clock history per detonation). | `PallField` (`hazeEnvelope`, `plume`, `PLUME_MAX`), `pall.glsl`, `BlackRainModel`, `FalloutModel`, `RadiationTicker`, `NukeSky` | none (from anchors) | Rain only under drawn smoke, never kilometres ahead of it; fallout with its cloud at the visible wind. Bench under a 24-hour day (worst case: the band out to 22–36 km for 2 h) and a vanilla day. |
| 5 | Shared states and outages (rule 8); the conventional show (rule 5) sets the hearing clock of a conventional blast. | `RadiationTicker`, `SicknessModel`, `BurnsEffect`/`BurnInjury`, `Deafness`/`HearingModel`, `grid/Outage`, `Blackouts`, `PowerGrid`, `S2C.Blast` (show end); `client-sound.md` | Save: a recovery clock per body and district, old times read as world clock. Packet: blast show end, PROTOCOL bump | During a show, hearing, burns, sickness and power no longer recover 72× fast. |
| 6 | Bodies age while present; hearing kept across relog. | `RadiationTicker`, `SicknessModel`, `Deafness`, `HearingModel`, `BurnsEffect` | Save: body clock per player and mob. Packet: ear state, PROTOCOL bump | No fatal-stage login after a night offline; relogging no longer cures deafness. |
| 7 | Client clocks follow the client level's tick and the replay position; heat shimmer leaves the wall clock. | `IncomingClient` smoke, trail and blast clocks, `HeatPass` | none | `/tick freeze` and Flashback pause stop conventional smoke; exported video shimmers at the right speed. |
| 8 | Fallout follows its law to the end: after the trace's last arrival above the radiation model's own floor (out to ~270 km, ~11 h), a detonation keeps only a compact ground field (bounded cells, evaluated only near players) that decays by its law and is dropped when its strongest cell falls below that floor. That is years of world time at ground zero, as for a real surface burst; `/incoming clear` removes it. Crater progress kept until done. | `NuclearEvents`, `FORGET_AFTER_S`, `FalloutModel`, `RadiationTicker` | Save: the compact field, crater progress, the world-clock history (rule 4) | No one-step drop of the dosimeter at day 7; the ground near ground zero stays measurably hot, decaying. |
| 9 | `doFireTick false` (rule 11). | `strike/StrikeRules`, `Warheads`, `StagedExplosion`, `RuinPlan`, `DebrisSpawner`, `FuelFires` | none | No permanent static flames on such servers. |
| 10 | Conventional strikes recorded on the server and sent to late joiners and on a seek. | `strike/`, `S2C.Blast`, `IncomingClient` | Packet and record, PROTOCOL bump | A friend joining sees the clouds still up. |

Guide (both languages, with the PRs that change behavior): `nuke.md` "Time" states the principle in its player form
(above). It adds conventional strikes. It says that a stopped sun keeps the day's pace, that a night everyone sleeps
through ends the show and clears the air, and that on a vanilla day the fires burn out about 10 minutes after the show.
`not-physical.md` describes the inside-sight row as "only for a camera inside the dust".

AGENTS.md "Time" would read (replacing the current paragraph):

> **Time** (Artem, 10.11; principle 26.11): the air in real time, the ground on the world's day. Everything a strike
> puts into the air (clouds, dust, mushroom, trails, smoke, rain and fallout in flight) lives its whole law in real
> time and leaves by its own motion; no term of it is ever compressed. What stays in place (fires, contamination,
> outages, injuries) runs real during the strike's show (20 min for a detonation; a conventional strike until its
> cloud's apex and its fuel's burn-out), then at the world's rate (`util/WorldClock`: never slower than real, never
> backward; vanilla 72×, day-length mods 1×; a stopped day keeps its last rate), joined by a rate blend. Handoffs
> carry the state: smoke at its fire's strength, rain and fallout with their carrier. A jump (sleep, `/time add`, a
> night-skipping `/time set`) with no awake player in the dimension counts once on both and ends a show; with someone
> awake it moves only what nobody sees change (doses, injuries, repairs). Shared states
> recover at real rate while any cause is in its show; bodies age only while present. Real time is game time. No new
> time settings; durations come from the laws.

What P4 keeps: the 20-minute nuclear show, the world clock and its floor, the movement axis (`scale_preset`, flight
and wave speed), every law's own durations, the existing caps `CloudModel.LIFE_MAX` (1 h) and the trail's 1200 s (a
separate question for the laws' visibility thresholds). Two decisions of 10.11 change. A stopped day keeps its last
rate instead of running real time. A jump moves the show, the air and the fires only when nobody in the dimension
is awake; otherwise it moves only doses, injuries and repairs.

## 10. Every mechanic against pack conditions: mismatches, ranked

From the inventories and a separate audit of pack conditions (tick rate, time control, gamerules, pause, servers,
replays, dimensions). Most player-visible first; the "Fix" column names the §9 change.

| # | Condition | What happens now | Fix | Setup |
|---|---|---|---|---|
| 1 | Any nuke, every pack | The dust blanket is drawn see-through from outside a minute after the front (§1.1) | 1 | every game |
| 2 | Vanilla day, after a nuke | The dust hangs about an hour over the area; a night's sleep does not clear it | its law stays (§8.2); a night everyone sleeps through clears it (3) | every vanilla game |
| 3 | `doFireTick false` | No gamerule is read before placing fire (`strike/StrikeRules.java:30`); ruin, flash, blast and debris fires (up to 20 000 per detonation, `nuclear/world/RuinPlan.java:1175`, `warhead/Warheads.java:489`, `StagedExplosion.java:1447`, `DebrisSpawner.java:51`) never go out | 9 | common on servers |
| 4 | `/time set day` at night (the usual op skip) | `dayTime` moves backward and the world clock counts that tick as real (`util/WorldClock.java:106-125`, `ClientClock.java:30`): no night passes for the aftermath | 2 (rule 7: counted as a jump; with the op awake it moves doses, injuries and repairs) | common with ops |
| 5 | Vanilla day, during the nuke's show | Hearing, burns, sickness and power recover 72× fast while the show runs in real time (§3.1, 1) | 5 | every vanilla game |
| 6 | Stopped sun (always-noon servers, creative) | Real time: a nuke's firestorm burns 12 real hours, fallout lasts 7 real days | 2 (rule 6) | common |
| 7 | Dedicated server, player offline | Sickness advances offline (login at the fatal stage); burns are frozen offline; hearing is cured by relogging | 6 | common |
| 8 | Flashback pause, `/tick freeze` | Conventional blast clouds, ground shake and crater smoke keep running (`client/IncomingClient.java:232,259,264,270`) while particles, trails and the nuke hold | 7 | common for filming |
| 9 | Flashback export, pause, freeze | Heat shimmer runs on the wall clock (`client/fx/layer/HeatPass.java:644,733`) | 7 | creators |
| 10 | A missile hit | The crater fuel fire burns 16 real minutes and stays loud ("burns forever") | the time principle keeps the law; whether `FuelFires`' 16 min is right is a separate law check | every game |
| 11 | Sleep during a show | Fires, rain and fallout stay at the minute of the sleep while the sky jumped hours | 2 | common in single-player |
| 12 | Late joiner after a conventional strike | Clouds and crater smoke are client-only and not sent | 10 | common on servers |
| 13 | Day-length mods that set `dayTime` themselves | Without detonations the server does not send its clock, so the client's hearing clock counts vanilla steps (`ClientClock.java:30`, `NuclearStrikes.java:355`) | 2 | depends on the mod |
| 14 | Day 7 of a detonation | Fallout drops to zero in one step when the detonation is forgotten; unfinished crater progress is dropped | 8 | every long game |
| 15 | `/tick freeze` | The world clock keeps counting real time on frozen ticks (`WorldClock.java:112-125`) | 2 (rule 9) | rare |
| 16 | Day mods stepping more than one tick at a time | A step of +N every M ticks counts N plus the real floor already credited: a 24-hour day as +10 every 720 ticks runs 2× (`WorldClock.java:107-111`) | 2: subtract the real time credited since the last step | rare |
| 17 | `/tick rate`, `/tick sprint`, Flashback speed | The client assumes 20 ticks per second (`ClientNuclear.java:213,266-270`, `client/sound/OwnSources.java:380`) | 7: the tick manager's tick length | rare |
| 18 | Long days | Wind changes every 24 000 game ticks (`util/Wind.java:23-31`): 72 winds per world day under a 24-hour day | limit: the wind stays on game ticks, so a sleep never swings the live wind in one tick | low |
| 19 | Other packs | Sea level fixed at 63 (`util/Air.java`, `WaterDecal.SEA_LEVEL`); `randomTickSpeed 0` disables lamp relight in Sable plots (`grid/block/Unlit.java:55`) | read the level's sea level | rare |
| 20 | 24-hour day | `restore_spread_seconds` is world-clock time but its tooltip says seconds | fix the tooltip | common |

Handled correctly (checked): single-player pause, sleep with any sleep share and gradual time-lapse sleep mods (every
tick's day advance counts as rate), a 2-hour day in single steps (exactly 12×), a 24-hour day (1:1), late join and
dimension change for detonations, Nether and End (the overworld day is shared), the Flashback nuclear clock, and the
gamerules the mod needs. Server lag slows the show and the server's work alike.

## 11. Decided (Artem, 26.11)

- P4, "the air in real time, the ground on the world's day", with the rules of §8.3 and the AGENTS.md "Time" wording
  of §9.
- On a vanilla day the nuclear dust keeps its physical hour (half clear at 45 min after the burst) and a night everyone
  sleeps through clears it; no shortening deviation (§8.2).
- A stopped day keeps its last rate instead of real time (replaces the 10.11 clause).
- A night skip with no awake player in the dimension ends the show. With someone awake, nothing drawn changes; doses,
  injuries and repairs still move. Artem's accepted wording was "otherwise only the sky moves"; the second review
  showed that this would silently drop the 10.11 rule that a sleep heals as a night does, so the invisible states keep
  it.
- With `doFireTick false` strikes leave no block fire burning forever; the crater fire and the firestorm burn and end
  by their laws.
- Owner's call within the principle: fallout follows its law to the end (change 8), so the ground near ground zero
  stays measurably hot for years of world time, as for a real surface burst.
