# Time in Incoming: one principle for how long consequences last (design note)

Status: proposal for Artem's decision. Analysis only; nothing here is adopted until he decides. Code references are
`path:line` against `main` at the time of writing; `J/` is `mod/src/main/java/ua/zentix/incoming/`. The numbers come
from code reading and from a throwaway unit probe over the shipped models (`PallField`, `CloudModel`; 15 kt surface
burst, defaults, game scale), quoted where used.

How this note was made: an inventory of every time-dependent phenomenon (§3); twelve candidate principles (§5) run
through twelve player stories (§6); four player personas, briefed only on the stories and real-life facts, never on
the candidates (§7, answers in the mission folder `personas/`); the choice with its rules (§8), the one conflict no
principle removes, by number (§8.2), the follow-up changes (§9) and the pack-condition mismatches (§10). An earlier
draft recommended "the air in real time, the ground on the world's day" (candidate P4 here); two review rounds on it
established the root cause in §8.1, which the chosen principle keeps.

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
| P4 | Air real, ground on the day | Every body in the air lives its whole law in real time; what stays in place runs real during the show, then at k (the earlier draft). |
| P5 | Show real, then the whole law on the day | After the show every phenomenon, its motion included, runs at k (the earlier player-view proposal). |
| P6 | Show real, then appearance on the day | After the show a body's look (density, colour) reads its law at the day's age while its geometry stays real. |
| P7 | Watched real, unwatched on the day | A consequence ages at k only while no player can see it. |
| P8 | Near real, far on the day | Real within the players' view distance, k beyond it. |
| P9 | Per-player time | Each client ages the aftermath by its own player's sleeps and absence. |
| P10 | A fixed game pace | After the show, always the vanilla pace (72), whatever the pack's day. |
| P11 | Weather washes the air | Air real; rain (vanilla weather) scavenges smoke and dust; sleep clears it. |
| P12 | **Motion real, amounts on the strike's clock** | Every position, speed and spread is real; every source and sink (burning, settling out, rain-out, decay, healing, repair) runs real during the show, then at k. |

## 6. Stories, run through every principle

Twelve stories, vanilla day unless stated; 15 kt surface burst; the mod's wind is 5–15 m/s at 1 km, about half that at 10 m in neutral air
(`J/util/Wind`).
Each cell names what the player meets first that he would object to; "ok" means nothing. The chosen principle's stories
are told minute by minute in §8.3.

| Story | P1 | P2 | P3 | P4 | P5 | P6 | P7 | P8 | P9 | P10 | P11 | P12 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 Survival nuke 2 km from base | the show over in 20 s | storm 12 real h, sleep does nothing | storm until he sleeps | dust over base ~1 h (3 skies) | dust races at 180–540 m/s | dust fades in ~1.5 min | base sees it: real, 1 h | same as P7 | ok alone | ok | dust stays on a dry day | ok (dust clears 1.5 min after the show) |
| 2 YouTuber sequence | show differs per pack | ok | ok | ok | clouds race in frame | Shahed smoke gone 3–6 min after the hit | seek shows another state | same | camera and actor differ | ok | weather changes takes | ok |
| 3 Realism fan, 24-h day | ok | ok | ok | ok | ok | ok | ok | ok | ok | aftermath 72× | ok | ok |
| 4 Friends' server, one sleeps | ok | sleeper's night does nothing | ok | watchers' mushroom cut | cut | cut | ok | ok | players disagree | cut | cut | ok (show kept for watchers) |
| 5 War server, dozens of strikes an hour | show lost | firestorms 12 h, fallout days | nobody sleeps: same as P2 | dust 1 h per nuke | racing smoke | ok | one watcher keeps all real | boundary moves with players | dangers differ per player | ok | random | ok |
| 6 Creative tests, sun stopped | ok | 12-h fire on the range | same | 1-h dust, 12-h fire | ok | ok | real while watched | real | ok | ok | same as P4 | ok (vanilla pace kept) |
| 7 2-hour day | show lost | as P2 | as P3 | dust ~1 h | racing at 12× | ok | as P7 | as P8 | ok | aftermath 6× too fast | as P4 | ok |
| 8 Conventional raids on a village | Shahed fire 2 s | ok | ok | ok | racing | smoke gone 3–6 min after the hit | ok | ok | ok | ok | ok | ok |
| 9 Late joiner and replay | ok | ok | ok | ok | ok | ok | state depends on who watched | depends on positions | differs per client | ok | random | ok (needs change 11) |
| 10 Always-noon server, `doFireTick false` | ok | 12-h fire | same | 12-h fire, 1-h dust | ok | ok | real | real | ok | ok | as P4 | ok |
| 11 Two nukes 10 min apart | show lost | as P2 | as P3 | 2 h of dust | racing | ok | real | real | ok | ok | as P4 | ok |
| 12 Sleep 2 min after a dusk nuke | ok | wakes to a minute-2 mushroom | ok | ok | ok | ok | ok | ok | ok | ok | ok | ok |

Notes on the cells:
- P1: under a vanilla day the dust's opaque phase lasts 17 s and the firestorm forms in 17 s; the same nuke is a
  20-second event in one pack and a 20-minute one in another.
- P5: compressing the motion multiplies every drift by k; the dust drifting at half the wind (2.5–7.5 m/s) would cross
  the map at 180–540 m/s.
- P6: a conventional cloud has no physical loss of its soot; reading its density at the day's age while its size grows
  in real time draws mass it never lost (a Shahed cloud two minutes after its apex keeps about 1/340 of its drawn mass,
  §8.1). Its smoke is gone 3–6 min after the hit, about a minute after its apex, which the realism persona names a
  dealbreaker.
- P7–P9 make the state depend on who looks, stands or sleeps where, which no shared world can hold: a replay seek
  cannot know who was watching, and fallout or fire cannot differ between two players standing side by side.
- P10 ignores the pack's day, so a 24-hour day no longer plays as real life (Artem's own way to get long effects).
- P11 ties the outcome to random weather; on a dry day it is P4.

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
| P4 air real, ground on the day | 4 +L | 5 | 5 | 4 | 0 |
| P5 whole law on the day | 4 | 3, DB | 3, DB | 5 | 2 |
| P6 appearance on the day | 5 | 3, DB | 4 | 5 | 1 |
| P7 watched real | 4 +L | 4 | 3, DB | 3 | 1 |
| P8 near real | 4 +L | 4 | 3, DB | 3 | 1 |
| P9 per-player time | 5 | 3 | 3, DB | 2, DB | 2 |
| P10 fixed game pace | 5 | 3, DB | 5 | 5 | 1 |
| P11 weather washes the air | 4 +L | 5 | 4 | 4 | 0 |
| **P12 motion real, amounts on the strike's clock** | **5** | **4 +L** | **5** | **5** | **0** |

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
- P12 leaves only the realism fan's "never compressed on any day length" to his 24-hour day: on a vanilla day the dust
  thins with the sky after the show.

The one point P12 leaves to a lever: the realism fan's wish for the dust's physical hour on a vanilla day. He gets it
with the 24-hour day he plays with (§8.2).

## 8. The choice: motion in real time, amounts on the strike's clock

**The principle, as a player can say it:** *Everything you see move moves at its real speed in every pack: flight, the
wave, the rise, the wind carrying smoke and dust. How fast things burn down, settle out, decay and heal follows the
world's day once a strike has formed: a 24-hour day is real life, a vanilla day compresses the aftermath with its sky,
and sleep moves it on.*

### 8.1 Why this split (the root cause it keeps)

The earlier draft split consequences first by quantity (positions real, amounts compressed), then by body (the air
real, the ground compressed). Its two review rounds found one defect class: a body's motion and what it carries on
different clocks, with numbers. Rain at 20 km at 119 min after the burst while the haze was out at 28–30 min. A Shahed
cloud drawn with its size on real time and its density compressed keeps (4 min / 74 min)², about 1/340, of its mass
two minutes after its apex. The cause holds: with space and speeds real, nothing that travels can be compressed
without racing it. And density read off a compressed clock, over a size grown in real time, draws mass that was never
lost.

P12 keeps that cause and splits where physics splits: the transport equation of anything in the air has transport
terms (wind, rise, turbulent spread, the fall of visible things) and source and sink terms (emission, burning,
settling out to the ground, rain-out, radioactive decay). P12 compresses only the sources and sinks.
- Transport is never compressed, so nothing races, at any k.
- Density is always amount (sources and sinks) over spread (transport). A body without a physical sink keeps its real
  density, so P12 draws no invented loss. A Shahed cloud's soot has no sink: it lives by its spread, in real time, and
  leaves with the wind.
- What a carrier loses is what lands under it (mass is conserved), so rain and fallout exist only under their drawn
  carrier, never ahead of it.
- At k = 1 P12 is the physics unchanged.

What the eye reads as "time passing" in a vanilla world is the sky; P12 makes everything that lasts age with the sky
while every speed stays the eye's own. The one visible price is in §8.2.

### 8.2 The conflict no principle removes, by number

On a vanilla day, at 1.5 km from 15 kt, the dust's law is still opaque when the 20-minute show ends (vertical optical
depth 4.7 at 21 min, 1 % of the light passes) and fades over the next 45–70 min (47 % at 45 min, 86 % at 65 min, gone
by 95 min).
- Survival and admin want it gone over their base or spawn by about 20–25 min. The survival player's dealbreaker is
  more than ~20 min of murk with no way to sleep it off.
- The realism fan wants its physical hour even on a vanilla day.

One shared world cannot do both. Either the dust outlives the show by its law: 45–70 more real minutes, two to three
more vanilla skies (P4). Or its settling runs at the day's pace after the show (P12). Then, under the 60-s rate blend:
- 11 % of the light passes 36 s after the show's end, 47 % at 50 s, 86 % at 67 s;
- it is gone at 92 s.

This is a fade at the sky's pace: an in-game hour every 50 s, the length of a vanilla sunset. It is not a pop, and it
is 18 times slower than vanilla rain stopping (5 s).

P12 loses least:
- The losing persona has a lever he already uses by preference: the 24-hour day, under which the dust keeps its full
  hour.
- Under P4 the losers (survival, admin) have only the next night's sleep. On a server that needs every player asleep,
  that lever is out of their hands.

The missile's crater fuel fire is the same case on a smaller scale. Its law burns 16 min; under P12 on a vanilla day it
burns through the 2–5-min show, then dies down over about 40 s.

### 8.3 The rules

1. **Two ages per strike.** The motion age M is real time plus every jump since the strike. The strike clock S equals
   M during the show and runs at the world's rate after it; S ≥ M always.
2. **Motion on M, always.** Every position, speed and spread reads M: flight, the blast wave, the fireball and cloud
   rise, the mushroom, drift with the wind, turbulent spread, trails, the fall of visible rain and debris. Nothing is
   ever compressed.
3. **Amounts on S.** Every source and sink reads S:
   - fires growing and burning down, and their emission of smoke and heat;
   - settling out to the ground and rain-out of dust, soot and fallout;
   - radioactive decay in the air and on the ground;
   - contamination, ruins and late collapse;
   - outages and repairs;
   - injuries.

   A body's drawn density is its amount (S) over its spread (M).
4. **What falls comes from its carrier.** Black rain is the rain-out of drawn smoke, where that smoke is. Fallout is
   the settling out of the drawn cloud, where the cloud is. Each carries its in-flight age to the ground and decays
   there on S. Smoke leaving a fire carries the fire's strength at its S at that moment.
5. **The show is the strike's forming phase, from its laws.** A detonation's show lasts 20 min: Hiroshima's firestorm
   formed about 20 min after the burst; the mushroom and the dust have formed well before. A conventional strike's
   show lasts until its cloud stops rising (the cloud law's apex, π/N: 2–5 min); the crater fire has taken hold by
   then. After a show S joins the world's rate by the existing 60-s rate blend (presentation), dS/dt = 1 + (k − 1)·u,
   with u a smoothstep of r / 60 s, r the real seconds since the show's end and k the world's rate without jumps. It
   never overshoots k and never catches up the show.
6. **The world's rate k.** The day's advance per real time, never below 1 (`util/WorldClock`). A stopped sun
   (`doDaylightCycle false`) keeps the rate the day had while it ran (vanilla 72 if it never ran): stopping the sun
   stops the light, not the world.
7. **Jumps.** A jump is a forward step of the day of at least an hour in one tick: a sleep skip, `/time add`, or, while
   the sun runs, a `/time set` whose forward distance is under 12 hours (a night skip). With the sun stopped, `/time
   set` only chooses the light, and a backward set never invents days.
   - A jump adds its hours once to M and S of every strike in its dimension.
   - It ends a show it falls in, except while an awake player is in that dimension. A show is never cut in front of an
     awake player; such strikes count the jump as real time.
   - Flight and the blast wave are never jumped: they finish in seconds at their speed, and their damage stays within
     the work budget.
   - What a jump changes on screen crossfades over about 2 s (presentation, as vanilla rain fades).
   - Strikes in fixed-time dimensions ignore jumps.
8. **Shared states and bodies.** A state with several causes (a body's hearing, burns and sickness; a district's
   outage) recovers at real rate while any cause is in its show, then at the world's rate through the same blend. A
   body's injuries advance only while it is in the world. Exposure windows (the ear's 2-min dose) are event time, M.
9. **Real time is game time.** Lag, `/tick rate`, `/tick freeze` and pause slow or stop every clock with the world, on
   the server and on the client (the client level's tick, the replay position).
10. **Server-defined.** The clocks are functions of server anchors: the world clock at the strike, the real time and
    world clock at the show's end, the saved jump counter. Conventional strikes are recorded on the server and sent to
    late joiners and on a replay seek like detonations, so everyone sees one state.
11. **`doFireTick false`.** Strikes light no block fire (it would never go out). The mod's own fires, the crater fuel
    fire and the firestorm, run their laws and end.

### 8.4 The stories under P12, as a player lives them

Vanilla day, 15 kt, wind 5–15 m/s unless stated. "World hour" = 50 real seconds on a vanilla day.
1. **Survival nuke 2 km from the base.**
   - 0–20 min (the sky passes one day): the flash, then the wave reaches the base at ~17 s and the dust rolls over it.
     Inside the dust you see about 200 m after one minute and 700 m after five. The mushroom rises and drifts, and the
     fort's fires grow into a firestorm at full strength at 20 min.
   - 20–21.5 min: the dust over the base thins (half clear 50 s after the show) and is gone at about 92 s.
   - 23–30 min: the firestorm declines from ~23 min and is out by 29–30 min. Its last smoke drifts off downwind, and black rain
     falls under that smoke only. If the base is upwind, none of it comes near.
   - Mushroom: gone by 22–27 min, 6–18 km downwind.
   - Fallout: near the fort it lands by the law during the show; the rest falls 6–18 km downwind. On the ground it
     decays over 7 world days: about 2.3 real hours.
   - Body (if hit): hearing shift heals in 16 world h (13 min), burns in weeks of world time.
   - Power: a tripped zone comes back 2–24 world h after the show (100 s–20 min); a damaged belt 2–14 world days
     (40 min–4.7 h).
   - The next night comes at most ~11 min after the show; one sleep (~11 h) puts the fires out, the rain over and the
     dose lower.
2. **YouTuber's sequence.**
   - The Shahed's fireball and cloud: the cloud rises to its apex in 2–5 min, the crater fire burns its 2.5 min, and the
     cloud leaves the village with the wind in a minute or two. It then stays visible as a receding plume for its law
     (tens of minutes: about 40 at 8 m/s).
   - The missile's crater fire burns through its show and dies over ~40 s.
   - The nuke is the same 20-minute event in every pack. A seek or pause in Flashback shows the true state.
     `/incoming clear` resets the set between takes.
3. **Realism fan, 24-hour day (k = 1).** Everything is real: dust opaque ~20 min and gone in ~1.5 h, mushroom ~25 min,
   firestorm out by 9–12 h, black rain ~1.5 h under the smoke, fallout for days, burns for weeks, substation repaired
   in 18 h. A sleep skips its hours for everything at once.
4. **Friends' server.**
   - With the default sleep share, the night is skipped only when everyone sleeps.
   - With a one-sleeper rule, the sleeper's skip leaves the watched mushroom running in real time. Older strikes'
     aftermath moves on, crossfading over 2 s with the sky.
   - The sleeper wakes to a morning sky over a show that is still running.
5. **War server.**
   - Conventional clouds leave each site with the wind in a minute or two and recede in one direction. The existing
     smoke budget (at most 128 cloud sources, 2 ms per frame) bounds the cost at any count.
   - Crater fires last at most ~6 min.
   - A nuke: dust gone at ~21.5 min, fires out by ~30, one fallout zone for everyone. Late joiners see the current
     state.
6. **Creative tests with the sun stopped.** The aftermath keeps the vanilla pace, so a conventional test site is clear
   in a few minutes. A nuke's needs 20 min plus ~10 for its fires; `/incoming clear` resets it at once.
7. **2-hour day (k = 12).**
   - The show is unchanged.
   - Then the dust is gone ~6.7 min after the show (86 % clear at 4.2 min) and the firestorm is out 45–60 min after it.
   - Hearing heals in 80 min, a tripped zone in 10 min–2 h, and fallout over about 14 h.
   - A seasons mod changes this only if it changes the day's length.
8. **Conventional raids on a village.** Each hit's cloud rises for 2–5 min while its crater fire burns its 2.5 min,
   then drifts out of the village with the wind. Houses burn as ordinary game fire, and hearing heals in ~13 min (at
   once with a sleep). Raids do not pile up over the village: every cloud leaves downwind.
9. **Late joiner and replay.** A friend joining at minute 10 sees minute 10: the same clouds in the same places, the
   fire at the same stage. A replay seek shows that moment's state; a pause stops everything.
10. **Server rules.**
    - On an always-noon server, durations are as on a vanilla day.
    - With `doFireTick false` no block fire is lit; the crater fire and the firestorm burn and end by their laws.
11. **Two nukes 10 min apart.**
    - Each strike has its own show: the first town's dust clears at its minute 20 (the second's minute 10), the
      second's at minute 40. Each firestorm is out ~10 min after its own show.
    - A body or district hit by both recovers at real rate until both shows are over.
12. **Sleep 2 min after a dusk nuke (single-player).** The wave finishes at its speed, and the sleep ends the show. On
    waking, about 11 h later:
    - mushroom, dust and black rain are over;
    - the firestorm is smouldering out (out by 9–12 h);
    - the fallout on the ground is 11 h older;
    - burns are still there;
    - part of the power is back.

## 9. What P12 changes in the mod, and what a player will notice

Each item is one follow-up PR with its tests; "Formats" names packets (`IncomingNetwork.PROTOCOL` bump), saves and
replays. Frame-cost changes are benched where marked.

| # | Change | Code areas | Formats | Player-visible effect |
|---|---|---|---|---|
| 1 | The inside-sight cap becomes a camera property (§1.1): it weighs each ray's segment inside the layer by the eye's depth below the local layer top; outside the dust every ray reads the law through the transfer. Not a time change; marked presentation. | `PallField.visible/lifetime`, `dustwall.glsl`, `DustSources`, `DustWallGlslTest`, `PallFieldTest`, `tools/dust_wall_gl.py`; guide `not-physical.md` | none | Every pack: from outside, the blanket stays dense through the show instead of 55 % see-through after a minute; inside, the view is unchanged (200 m → 700 m). Bench: no new work per ray. |
| 2 | Clocks: M and S per strike, jumps (rule 7) with the awake-player exception, the rate blend, the stopped-sun rate (rule 6), the `/time set` rule, the saved jump counter and show-end anchors. | `WorldClock`, `ClientClock`, `NukeClock`, `NukeClockTest`, `NuclearEvents.Anchor`/`endShows`, `ClientNuclear.clockTicksAt`, `S2C.NukeClock` | Clock packet with the jump counter to every client once a second; `ShowEnd` gains the show's real end; PROTOCOL bump. Save: counter and stopped-sun rate in `WorldClock`, two `Anchor` fields. Old replays: a 20-min show, no jumps | Sleeping during a show (alone) wakes you to the aftermath at its world age; an op's night skip moves the aftermath on; a stopped sun no longer turns a 30-min aftermath into 12 real hours. |
| 3 | Amounts on S in the air: the dust's settling out, the clouds' and ground dust's settling, the mushroom's fallout read S; their motion and spread read M. | `PallField` (`settledBy`, column mass), `DustSources`, `client/fx/smoke` (`CloudModel`, `GroundDust` settling), `MushroomModel`, `TrailModel`; texts `nuclear.md`, `dust-wave.md`, `client-smoke.md` | none (from anchors) | Vanilla day: the nuclear dust over a place clears within ~1.5 min of the show's end instead of ~1 h; conventional clouds drop their dust faster and drift off as today. 24-hour day: as today. Bench: no new work. |
| 4 | What falls from its carrier (rule 4): the haze over and downwind of the fires becomes smoke parcels (emission on S, motion on M) replacing today's fixed plume; black rain is their rain-out; fallout is the cloud's settling out. | `PallField` (`hazeEnvelope`, `plume`, `PLUME_MAX`), `pall.glsl`, `BlackRainModel`, `FalloutModel`, `RadiationTicker`, `NukeSky` | none | Rain only under drawn smoke; fallout where its cloud was. Bench: the haze band, under a vanilla and a stopped day. |
| 5 | The conventional show (rule 5): the crater fuel fire burns real until the cloud's apex, then on S; its loop ends with the fire. | `warhead/FuelFires`, `S2C.Blast` (apex), client fire sound | none | Vanilla day: a missile's crater fire lasts ~3–6 min instead of 16, quiet when out. |
| 6 | Shared states and outages (rule 8). | `RadiationTicker`, `SicknessModel`, `BurnsEffect`/`BurnInjury`, `Deafness`/`HearingModel`, `grid/Outage`, `Blackouts`, `PowerGrid`; `client-sound.md` | Save: a recovery clock per body and district, old times read as world clock | During a show, hearing, burns, sickness and power no longer recover 72× fast. |
| 7 | Bodies age while present; hearing kept across relog. | `RadiationTicker`, `SicknessModel`, `Deafness`, `HearingModel`, `BurnsEffect` | Save: body clock per player and mob. Packet: ear state, PROTOCOL bump | No fatal-stage login after a night offline; relogging no longer cures deafness. |
| 8 | Client clocks follow the client level's tick and the replay position; heat shimmer leaves the wall clock. | `IncomingClient` smoke, trail and blast clocks, `HeatPass` | none | `/tick freeze` and Flashback pause stop conventional smoke; exported video shimmers at the right speed. |
| 9 | A detonation is forgotten only after its last fallout lands and its ground dose is below the natural background, not at a fixed 7 days; crater progress kept until done. | `NuclearEvents`, `FORGET_AFTER_S` | Save: crater progress | No one-step drop of the dosimeter. |
| 10 | `doFireTick false`: no block fire lit by strikes; the mod's own fires unchanged. | `strike/StrikeRules`, `Warheads`, `StagedExplosion`, `RuinPlan`, `DebrisSpawner` | none | No permanent static flames on such servers. |
| 11 | Conventional strikes recorded on the server and sent to late joiners and on a seek. | `strike/`, `S2C.Blast`, `IncomingClient` | Packet and record, PROTOCOL bump | A friend joining sees the clouds still up. |

Guide (both languages, with the PRs that change behavior): `nuke.md` "Time" states P12 in its player form (above),
adds conventional strikes, says that a stopped sun keeps the day's pace, that sleep alone during a show ends it, and
that on a vanilla day the fires burn out about 10 minutes after the show. `not-physical.md`: the inside-sight row "only
for a camera inside the dust", and one row "the vanilla day compresses the aftermath: dust, fires and fallout settle
and burn out at the day's pace while everything moves at its real speed".

AGENTS.md "Time" would read (replacing the current paragraph):

> **Time** (Artem, 10.11; principle 26.11): motion in real time, amounts on the strike's clock. Every position, speed
> and spread (flight, wave, rise, wind drift, spread, falling things) runs in real seconds in every pack and is never
> compressed. Every source and sink (fires growing and burning down, settling out, rain-out, decay, contamination,
> ruins, outages, injuries) runs real during the strike's show (20 min for a detonation, the firestorm's formation; a
> conventional strike until its cloud's apex), then at the world's rate (`util/WorldClock`: never slower than real,
> never backward; vanilla 72×, day-length mods 1×; a stopped sun keeps the day's rate), joined by a rate blend. A
> body's density is its amount over its spread; rain and fallout are their carrier's loss, where the carrier is. A jump
> (sleep, `/time add`, a night-skipping `/time set`) counts once and ends a show unless an awake player is in the
> dimension. Shared states recover at real rate while any cause is in its show; bodies age only while present. Real
> time is game time. No new time settings; durations come from the laws.

What P12 keeps: the 20-minute nuclear show and its 60-s blend, the world clock and its floor, the movement axis
(`scale_preset`, flight and wave speed), every law's own durations, the existing caps `CloudModel.LIFE_MAX` (1 h) and
the trail's 1200 s (a separate question for the laws' visibility thresholds). One decision of 10.11 changes: a stopped
sun keeps the day's rate instead of running real time.

## 10. Every mechanic against pack conditions: mismatches, ranked

From the inventories and a separate audit of pack conditions (tick rate, time control, gamerules, pause, servers,
replays, dimensions). Most player-visible first; the "Fix" column names the §9 change.

| # | Condition | What happens now | Fix | Setup |
|---|---|---|---|---|
| 1 | Any nuke, every pack | The dust blanket is drawn see-through from outside a minute after the front (§1.1) | 1 | every game |
| 2 | Vanilla day, after a nuke | The dust hangs about an hour over the area (three vanilla skies) | 3 | every vanilla game |
| 3 | `doFireTick false` | No gamerule is read before placing fire (`strike/StrikeRules.java:30`); ruin, flash, blast and debris fires (up to 20 000 per detonation, `nuclear/world/RuinPlan.java:1175`, `warhead/Warheads.java:489`, `StagedExplosion.java:1447`, `DebrisSpawner.java:51`) never go out | 10 | common on servers |
| 4 | `/time set day` at night (the usual op skip) | `dayTime` moves backward and the world clock counts that tick as real (`util/WorldClock.java:106-125`, `ClientClock.java:30`): no night passes for the aftermath | 2 (rule 7) | common with ops |
| 5 | Vanilla day, during the nuke's show | Hearing, burns, sickness and power recover 72× fast while the show runs in real time (§3.1, 1) | 6 | every vanilla game |
| 6 | Stopped sun (always-noon servers, creative) | Real time: a nuke's firestorm burns 12 real hours, fallout lasts 7 real days | 2 (rule 6) | common |
| 7 | Dedicated server, player offline | Sickness advances offline (login at the fatal stage); burns are frozen offline; hearing is cured by relogging | 7 | common |
| 8 | Flashback pause, `/tick freeze` | Conventional blast clouds, ground shake and crater smoke keep running (`client/IncomingClient.java:232,259,264,270`) while particles, trails and the nuke hold | 8 | common for filming |
| 9 | Flashback export, pause, freeze | Heat shimmer runs on the wall clock (`client/fx/layer/HeatPass.java:644,733`) | 8 | creators |
| 10 | Vanilla day, a missile hit | The crater fuel fire burns 16 real minutes and stays loud | 5 | every vanilla game |
| 11 | Sleep during a show | Fires, rain and fallout stay at the minute of the sleep while the sky jumped hours | 2 | common in single-player |
| 12 | Late joiner after a conventional strike | Clouds and crater smoke are client-only and not sent | 11 | common on servers |
| 13 | Day-length mods that set `dayTime` themselves | Without detonations the server does not send its clock, so the client's hearing clock counts vanilla steps (`ClientClock.java:30`, `NuclearStrikes.java:355`) | 2 | depends on the mod |
| 14 | Day 7 of a detonation | Fallout drops to zero in one step when the detonation is forgotten; unfinished crater progress is dropped | 9 | every long game |
| 15 | `/tick freeze` | The world clock keeps counting real time on frozen ticks (`WorldClock.java:112-125`) | 2 (rule 9) | rare |
| 16 | Day mods stepping more than one tick at a time | A step of +N every M ticks counts N plus the real floor already credited: a 24-hour day as +10 every 720 ticks runs 2× (`WorldClock.java:107-111`) | 2: subtract the real time credited since the last step | rare |
| 17 | `/tick rate`, `/tick sprint`, Flashback speed | The client assumes 20 ticks per second (`ClientNuclear.java:213,266-270`, `client/sound/OwnSources.java:380`) | 8: the tick manager's tick length | rare |
| 18 | Long days | Wind changes every 24 000 game ticks (`util/Wind.java:23-31`): 72 winds per world day under a 24-hour day | change the wind with the world clock | low |
| 19 | Other packs | Sea level fixed at 63 (`util/Air.java`, `WaterDecal.SEA_LEVEL`); `randomTickSpeed 0` disables lamp relight in Sable plots (`grid/block/Unlit.java:55`) | read the level's sea level | rare |
| 20 | 24-hour day | `restore_spread_seconds` is world-clock time but its tooltip says seconds | fix the tooltip | common |

Handled correctly (checked): single-player pause, sleep with any sleep share and gradual time-lapse sleep mods (every
tick's day advance counts as rate), a 2-hour day in single steps (exactly 12×), a 24-hour day (1:1), late join and
dimension change for detonations, Nether and End (the overworld day is shared), the Flashback nuclear clock, and the
gamerules the mod needs. Server lag slows the show and the server's work alike.

## 11. Needs Artem's word

- Adopt P12, "motion in real time, amounts on the strike's clock", with the AGENTS.md "Time" wording in §9.
- On a vanilla day the nuclear dust over a place clears within about 1.5 min after the 20-minute show, and a missile's
  crater fire dies within about 40 s after its cloud's apex. A 24-hour day keeps both at their physical length.
- A stopped sun keeps the day's pace instead of real time (changes the 10.11 rule).
- Sleep alone during a show ends it; with players awake in the dimension a show is never cut.
- With `doFireTick false` strikes light no block fire; the mod's own fires still burn and end.
