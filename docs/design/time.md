# Time in Incoming: one principle for how long consequences last (design note)

Status: proposal for Artem's decision ("обсуждаем"). Analysis only; nothing here is adopted until he decides. Code
references are `path:line` against `main` at the time of writing; `J/` is `mod/src/main/java/ua/zentix/incoming/`.
The numbers come from code reading and from a throwaway unit probe over the shipped models (`PallField`, `CloudModel`;
15 kt surface burst, defaults, game scale), quoted where used.
Review: two independent rounds. The defect class both found (a body's motion and what it carries on different clocks)
led to the root-cause analysis in §6.0 and the split by body in §6. The project's review rule allows no third round, so
§6's final form has not been through an independent review of its own; the follow-up design PRs review it.

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
   15 kt mushroom stays to 27 min and the dust law to 1.6 h. This is not a defect: the dust and the mushroom are air
   raised by the blast, not the fires' smoke, and they drift away by their own motion (§6.0). The defect is the
   carried rain (item 7).
4. **One conventional strike, three clocks.** Its crater fire on ticks (2.5–16 min), its cloud on client ticks (up to
   60 min), the hearing damage it causes on the world clock (13 min vanilla, 16 h on a 24-hour day).
5. **The longest conventional effects ignore the day.** A Shahed cloud stays up to an hour under a vanilla day (three
   in-game days), drifting 10–20 km downwind by its end; a trail is capped at a fixed 1200 s labelled "an in-game day";
   sleep leaves both.
6. **Client-only state.** Conventional clouds and crater smoke are not shown to a late joiner and vanish on a replay
   seek; hearing loss is cured by relogging (it is client state), while burns and sickness persist.
7. **Wind transport on the detonation clock.** The black-rain band starts at a downwind point at `START_S` + distance /
   wind in detonation-clock seconds (`nuclear/model/BlackRainModel.java:40-49`), fallout arrives at distance / wind in
   clock hours (`FalloutModel`), while the haze plume that carries them grows on real ticks (`PallField.plume`). Under V,
   after the show the band and the fallout sweep downwind at 72× the wind (121× at the join's peak): they reach a point
   before the smoke that carries them is drawn there, an effect ahead of its cause.
8. **The guide is ambiguous.** `nuke.md:194-195` says that under a vanilla day "the fires burn out in about 10
   minutes", which reads as 10 minutes after the burst; the storm is out 27–30 min after it (about 10 min after the
   show).

## 4. What a good principle must satisfy

Taken from the concept (AGENTS.md "Principles") and from an independent player-view analysis (an agent briefed only on
what players see, without our candidates; its answer is summarized in §5.5):

1. **Cause first, no seams.** No effect before its cause; nothing jumps, pops or time-lapses in front of a player unless
   the sky jumps with it (sleep, `/time add`).
2. **The show is the product.** What a player watches (flight, flash, wave, mushroom, the dust rolling in, fires taking
   hold) looks the same in every pack.
3. **Lived-with consequences follow the world.** A 24-hour day plays out as in real life; a vanilla day compresses the
   aftermath with its sky; sleep moves it on; nothing holds a base hostage for real hours the world cannot skip.
4. **Related effects agree.** What one strike causes never disagrees on screen. A body (a cloud, the dust) never has
   its size and its density on different clocks, and a carried thing (rain, fallout) is never separated from its
   carrier or ahead of it.
5. **No hand-picked durations.** Durations come from the laws; the only other time constant is a marked presentation
   one (the 60-s join that keeps a rate change from showing as a step).
6. **Same for everyone.** Server-defined: every player, a late joiner and a replay seek see the same state.
7. **Frame cost bounded** at any strike count, and no new time setting.

## 5. Principles compared

Conditions: V vanilla day; D24 a 24-hour day; D2 a 2-hour day; S stopped day; sleep during the show; sleep later;
`/time add`; a server where some players sleep; late join; Flashback seek; several detonations; conventional-only play;
single-player versus server. Reference phenomena: the 15 kt dust blanket (law: opaque about 20 min, gone 1.6 h), the
firestorm (out at 12 h), a Shahed cloud (up to 1 h), hearing after a near miss (16 h), a substation outage (18 h).

### 5.1 A: every consequence follows the world's day; only motion is real (Artem's raw idea)

- V: the dust blanket's opaque phase lasts 17 s and it is gone in 80 s; the firestorm reaches full strength in 17 s,
  declines from 2.5 min, is out by 10 min; a Shahed cloud fades in 50 s; hearing 13 min; outage 15 min. D2: dust opaque 100 s, storm formed in
  100 s. D24 and S: everything real.
- Sleep at any time jumps everything; consistent everywhere; late join and seek are functions of the world clock.
- Seams: under V and D2 the show itself time-lapses, and what travels either races (its spread and drift on the day)
  or splits from its own size (density on the day, size real: the defect of §6.0). Dust behind the wave clears while the front is still crossing
  (the front at 0.35 of sound speed takes about 40 s to 5 km); the mushroom thins before it has risen. The same nuke is
  a 20-second pop in one pack and a 20-minute event in another.
- Fails 2 (the show) on the most common setup. Passes 3–7. Cheapest frames.

### 5.2 B: everything in real time (vanilla's own tick rule)

- Every day setting: the firestorm burns 12 real hours, fallout lasts 7 real days, hearing 16 h, an outage 18 h, the
  dust 1.6 h, a Shahed cloud 1 h.
- Sleep and `/time add` change nothing: after three nights the fire is at the same stage; the zone blocks a base for
  weeks of evening sessions; the sky says days passed, the world says minutes.
- Fails 3. Passes 1, 2, 4–6. The most frame cost (12 h of storm haze over every session).

### 5.3 C: the air in real time, the ground on the world's day (recommended; §6)

Everything a strike puts into the air lives its whole law in real time (plus time jumps) and leaves by its own motion.
Everything that stays in place ages on the strike's clock: real during the strike's show, then at the world's rate.
§6 gives the rules and why the split is by body, not by quantity.

- V:
  - The nuke's show is 20 real min. The dust blanket is opaque for about 20–25 min at 1.5 km, half clear by about
    45 min and gone by about 65 min, drifting downwind at half the wind (the law, drawn through the transfer). The
    mushroom lasts its 22–27 min.
  - The storm is at full strength at the show's end, declines from 2–3 min later and is out by 28–30 min after the
    burst.
  - The smoke the fires gave off before they went out drifts on as a detaching band; black rain falls under it as it
    passes. Fallout travels with its cloud at the real wind, decaying in flight by its physical age; on the ground it
    decays at the world's rate.
  - A Shahed cloud lives its law (up to an hour at light wind) while it drifts away, 10–20 km downwind by its end.
  - Hearing, burns, sickness and outages recover at real rate during a show and at the world's rate after it.
- D2: the same air; the storm burns out over about an hour after the show. D24 and S: everything real, which is real
  life.
- Sleep or `/time add` (a jump) adds its hours at once to both clocks of every strike, so a night's sleep clears the
  air and moves the ground on. During a show a jump also ends the show (rule 6).
- Server with some sleepers: the jump happens only when the server's sleep share is met; awake players see the sky, the
  air and the aftermath jump together (vanilla's own behavior for the sky and the weather). With the default share
  nobody in the overworld is watching. Strikes in fixed-time dimensions (Nether, End) ignore jumps.
- Late join and seek, nuclear: the clocks are functions of server anchors (the world clock at the burst, the real time
  and world clock where the show ended, a saved jump counter). Conventional: today's clouds are client-only and not
  re-sent; their clock comes from the blast packet and the clock packet; re-sending them is a separate change (§6.1,
  change 10).
- Several detonations: each has its own clocks; their dust and smoke add up by their own ages. A body or a district hit
  by several strikes recovers at real rate while any of them is in its show (rule 7).
- Conventional-only play: clouds and trails as in real life, drifting off; sleep clears them.
- Seams:
  - At a show's end, the ground's processes (fires burning down, recoveries) speed up with the sky over a 60-s rate
    blend. The air never speeds up, so nothing visibly balloons, races or arrives ahead of what carries it.
  - Today's join blends positions and overshoots to 1.68× the world's rate (121× real under V, `NukeClock.java:39-41`);
    C blends rates.
- Passes requirements 1–5 and 7, and 6 for nuclear strikes; conventional clouds stay unseen by late joiners until change 10 (as
  today).
- Frame cost: as today for dust, mushroom, clouds and trails. The haze band that carries black rain is drawn further
  than today's 2-fire-zone plume; bench under V and S.
- It differs from the player view on one point. The player view wanted the dust to thin at the world's pace after
  20 min; C keeps the dust's physical hour, because compressing it would race it across the map (§6.0).

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

E and C agree on most points: the watched phase in real time, a rate change without catch-up, sleep moving the
aftermath on, nothing backward. They differ on the air. E compresses everything after the window, the dust and smoke
included. That runs into the problem of §6.0: what travels cannot be compressed without racing it, shrinking its
footprint or losing its carrier. C keeps the air on real time and compresses only what stays in place. E also leaves the
body and the grid on the world's day from the burst, which is inconsistency 1.

### 5.6 Summary

| | A world day | B real | C air real, ground on the day | D per phenomenon | E player view |
|---|---|---|---|---|---|
| Show the same in every pack | no | yes | yes | yes | yes |
| 24-hour day = real life | yes | yes | yes | yes | yes |
| Sleep moves the aftermath | yes | no | yes | partly | yes |
| Nothing in the air races or loses its carrier | no | yes | yes | no | no |
| Related effects agree | no | yes | yes | no | partly |
| No hand-picked durations | yes | yes | one fixed show length | no | one fixed window |
| Frame cost under V | lowest | highest | as today (+ the haze band) | high | low |

## 6. Recommendation: C, the air in real time, the ground on the world's day

**The principle, as a player can state it:** *What a strike puts into the air (clouds, dust, smoke, rain, fallout)
lives in real time and drifts away with the wind, like Minecraft's own clouds and weather. What it leaves in place
(fires, contamination, ruins, power, your body) plays out in real time while the strike's own energy drives it, then
ages with the world's day, never slower than real time and never backward. Sleep moves both on.*

### 6.0 Why the split is by body (root cause after two review rounds)

The first two drafts split each strike's consequences by quantity: positions and speeds on a real-time age M, amounts
and densities on a compressed clock S. Both review rounds found the same class of defect: a body's motion and the body
it causes disagreeing.
- Round 1: rain and fallout carried ahead of their smoke.
- Round 2: a cloud's size on one clock and its density on another, and rain under a carrier that has already gone.

The cause, with its numbers:
- **Every visible end in the air is made by motion.** A conventional cloud lives while τ = m / (2π σh σv) ≥ `SEEN`,
  and its σ's grow with turbulent spread (`CloudModel.java:830-841`, `GroundDust.java:1425-1437`). A trail lives until
  σ reaches a target (`TrailModel.java:383-387`), the mushroom until Richardson dispersal (`MushroomModel.java:136-148`),
  the dust until its size bins settle (`PallField.settledBy`).
  - "Amount" in the air is therefore a function of how far the body has spread or fallen.
  - Reading size on M and density on S draws a cloud that loses mass it never lost. Two minutes after a Shahed's apex
    under V, σ ∝ t gives a drawn mass of (4 min / 74 min)², about 1/340 of the cloud's.
  - Reading both on S races the spread 72× on screen.
- **A carried thing exists only with its carrier.** Moving the black-rain band at the real wind while its carrier
  lives on the compressed clock puts rain under a clear sky. The review's probe, for 15 kt at 3 m/s under V: rain at
  20 km at 119 min after the burst, while the haze is out at 28–30 min. Moving both on S races them.
- **With space real and speeds real, time cannot be compressed for anything that travels.** Its footprint would shrink,
  or its motion would time-lapse, or its carrier would outlive its cause. In place, compression costs nothing visible
  but the rate at which things burn down and heal.

So the split follows the body. A body in the air lives its whole law on one age, real time plus jumps. A thing in
place ages on the strike clock. Where one feeds the other (fires emitting smoke, smoke raining, rain and fallout
landing), the handoff carries the state across at that moment.

Rules:

1. **Two ages per strike.** The *motion age* M is real time plus every jump since the strike. The *strike clock* S
   equals M during the show and runs at the world's rate after it. S ≥ M always.
2. **The air on M, whole.** A body in the air lives its whole law on M: rise, spread, settling, thinning, drift, light,
   and what falls from it. This covers the blast and conventional clouds, ground dust, the nuclear dust blanket, the
   mushroom and its stem, trails, smoke parcels, black rain and fallout in flight. Such a body leaves the scene by its
   own motion.
3. **In place on S.** What stays in place reads S:
   - the fires (their growth, strength, burning out and emission), their light;
   - contamination and fallout on the ground and its decay;
   - ruins and late collapse;
   - outages and repairs;
   - injuries (rule 7).
4. **Handoffs carry the state.** Smoke leaving a fire carries the fire's strength at its S at that moment, then lives
   on M; the drawn haze over and downwind of the fires is these parcels, so black rain falls only under drawn smoke.
   Fallout and rain carry their in-flight age M to the ground, then decay there on S. Nothing carried exists without
   its carrier, and nothing arrives before it.
5. **The show is the strike's own phase, from its laws.** It lasts while the strike's own energy drives what is in
   place.
   - Conventional blast: until its crater fuel has burnt down (`FuelFires`: Shahed 2.5 min, missile 16 min), taken from
     the server's fire, not the client's pool model. Without fuel, until the blast is over.
   - Detonation: one fixed show length, 20 min, fires or not. The firestorm forms in it (Hiroshima); the mushroom
     (17–27 min by yield) and the dust are air and do not depend on it.

   A show that ends by its length joins the world's rate by a rate blend, dS/dt = 1 + (k − 1)·u(r / 60 s), with u a
   smoothstep, r real seconds since the show's end and k the world's rate without jumps. It never overshoots k and
   never catches up the show.
6. **A jump is a forward step of the day in one tick of at least one hour (1000 ticks)**, from any source: sleep,
   commands, functions, mods. It adds its hours once to M and S (not again through k), with no blend, and ends a show
   it falls in.
   - Fast-day and stepping day mods and gradual time-lapse sleep are rates, never jumps.
   - A backward `/time set` counts as its forward distance only when that is under 12 hours (a night skip); otherwise
     it counts as real time. "Set day" in the morning and a periodic "set noon" invent no days.
   - Strikes in fixed-time dimensions ignore jumps.
7. **Shared states and bodies.** A state with several causes (a body's hearing, burns and sickness; a district's
   outage) recovers at real rate while any cause is in its show, then at the world's rate through the same rate blend;
   jumps count (test: two strikes in different phases). A body's injuries advance only while it is in the world, as
   vanilla effects do. Exposure (the ear's 2-minute dose window, `HearingModel.Ear`) is the event's own time: M.
8. **Real time is game time.** Lag, `/tick rate`, `/tick freeze` and pause slow or stop every clock with the world, on
   the server and on the client (the client level's tick, the replay position).
9. **Server-defined.** The clocks are functions of server anchors: the world clock at the burst, the real time and world
   clock where the show ended, and the world's jump counter (saved) at the burst. The clock packet carries the
   counter, so every player, a late joiner and a replay seek see the same state. Hearing is the one client-held state
   (change 6): a client effect behind a client setting.

Why C:
- It passes every requirement in §4 (except the conventional late-join gap that exists today) without splitting any
  body or carrier across clocks.
- It is Artem's idea for what stays and burns in the world (the day sets how long it lasts; a 24-hour day is real
  life) with his exception (what moves stays real) taken to its root: what travels cannot be compressed.
- It matches vanilla: clouds and weather run on ticks and sleep clears the weather.
- It is closest to today's code. The dust, clouds and mushroom already run on real time and the storm on `NukeClock`;
  C adds jumps, handoffs, the body and grid rules, and fixes the cap.

### 6.1 What C changes, and what a player will notice

Ordered by what a player notices first. Each item is one follow-up PR with its tests. "Formats" names the packet
(`IncomingNetwork.PROTOCOL` bump), save and replay effects. Frame-cost changes are measured on the scene bench where
marked.

| # | Change | Code areas | Formats | Player-visible effect |
|---|---|---|---|---|
| 1 | The inside-sight cap becomes a camera property. It weighs each ray's segment inside the layer by the eye's depth below the local layer top, smoothed over the top's softness (`BUMPS`, about 0.3 of the layer height); with the eye outside the dust every ray reads the law through the transfer. Not a time change; an accepted presentation, marked. | `PallField.visible/lifetime` (cap out of the shared table), `dustwall.glsl` (+ `wall_layer`, harness preludes), `DustSources`, `DustWallGlslTest`, `PallFieldTest`, `tools/dust_wall_gl.py`; guide `not-physical.md` row | none | Every pack: from outside and above, the blanket near ground zero stays dense (straight down 4 % of light at 1 min, 18 % at 20 min at 1.5 km; opaque seen slant) instead of 55 % see-through after a minute. Inside, the view is unchanged (200 m → 700 m). Two players see different densities only when one stands inside the dust. Walking out, the far blanket thickens over the top's softness, tens of metres of eye height (test: the drawn depth along a fixed far ray stays continuous in eye height). Bench: no new work per ray. |
| 2 | Jumps (rule 6), the rate blend (rule 5), and the jump counter and show-end anchors (rule 9). | `WorldClock` (k without jumps, the jump counter, the backward-set rule), `ClientClock`, `NukeClock`, `NukeClockTest` (no overshoot), `NuclearEvents.Anchor`/`endShows`, `ClientNuclear.clockTicksAt`, `S2C.NukeClock` | Packet: clock packet with the jump counter, sent once a second to every client (today only while a dimension holds detonations); `ShowEnd` gains the show's real end. PROTOCOL bump. Save: the counter in `WorldClock`, two `Anchor` fields. Replays without them: a 20-min show and no jumps, as today | Sleep during the show: wake to the aftermath at its world age instead of a minute-5 mushroom in a morning sky. `/time set day` at night moves the aftermath on (§7 #3); in the morning it does not. |
| 3 | The air reads M: dust, mushroom, clouds, ground dust, trails and smoke add the jumps to their real age. | `DustSources.source`, `ClientNuclear` (mushroom, `CloudPuffs`), `client/fx/smoke` (`SmokeGrid`, `BlastTime`), `FireSmoke`, `TrailModel`; texts `nuclear.md`, `dust-wave.md`, `PallField.java:42,479` ("real time plus jumps") | uses change 2 | After a night's sleep the sky is clear of the strike's dust and smoke. Otherwise unchanged: the air runs as today. |
| 4 | Handoffs (rule 4). The haze over and downwind of the fires becomes smoke parcels: each carries the fire's strength at its S at emission and moves on M, replacing today's plume, which shows the fires' current smoke out to 2 fire zones. Black rain falls under drawn parcels; rain and fallout carry their in-flight age to the ground. | `PallField` (`hazeEnvelope`, `plume`, `PLUME_MAX`), `pall.glsl`, `BlackRainModel` (band = parcels), `FalloutModel` (arrival on M, decay on S after landing), `RadiationTicker`, `NukeSky` | none (derived from anchors) | V: after the fires burn out, their last smoke drifts away as a band and black rain falls only under it, no longer from a clear sky or kilometres ahead of it; fallout lands with its cloud at the visible wind and at its physical age. D24: as physics. Bench: the longer haze band, under V and S. |
| 5 | Shared states and outages (rule 7). | `RadiationTicker`, `SicknessModel`, `BurnsEffect`/`BurnInjury`, `Deafness`/`HearingModel` (`Ear` window on M), `grid/Outage`, `Blackouts`, `PowerGrid`; text `client-sound.md` | Save: a recovery clock per body and per district; existing `exposedAt` and outage times are read as world clock (the new clock starts at the world clock) | V: during a show hearing, burns, sickness and power no longer recover 72× fast; the zone stays dark through the show. The raid hearing numbers in `client-sound.md` are measured again (today's 2-min window is 1.7 s of real time under V). |
| 6 | Bodies age while present (rule 7). Sickness stops progressing offline (today it reads the absolute world clock, `RadiationTicker.java:223`, so a player can log back in at the fatal stage). Hearing is kept across relog: the client sends its ear state to a player attachment and gets it back on join. | `RadiationTicker`, `SicknessModel`, `Deafness`, `HearingModel`, `BurnsEffect` | Save: a body clock per player and mob (attachment). Packet: ear state both ways, PROTOCOL bump | No death on login after a night offline; relogging no longer cures deafness. |
| 7 | Client clocks follow the client level's tick (frozen by `/tick freeze`, the replay position in Flashback); heat shimmer leaves the wall clock. | `IncomingClient` smoke, trail and blast clocks, `HeatPass` | none | Under `/tick freeze` and a Flashback pause, conventional smoke stands still with the rest of the world, as the guide says; exported video shimmers at the right speed. |
| 8 | A detonation is forgotten only after its last arrival on M and once its ground fallout is below the natural background (a physical threshold), not at a fixed 7 days; crater progress is kept until the crater is done. | `NuclearEvents` pruning, `FORGET_AFTER_S` | Save: crater progress outlives the detonation | Dosimeters no longer drop in one step at day 7. |
| 9 | `doFireTick false`: no fire placement and the detonation's `fires` off (§7 #2). | `strike/StrikeRules`, `NuclearWarhead` | none | No permanent static flames on servers with fire tick off. |
| 10 | Live conventional strikes are recorded on the server and re-sent to a late joiner and on a seek (optional, closes C's one gap). | `strike/`, `S2C.Blast`, `IncomingClient` | Packet and record, PROTOCOL bump | A friend joining sees the clouds still up. |

Guide (both languages, with the PRs that change behavior):
- the "Time" passage in `nuke.md` states the principle in its player form (the air in real time, what stays in place
  on the day, sleep moves both), adds conventional strikes, and says that sleep during the show ends it. It also makes
  two sentences exact: under a vanilla day the fires burn out about 10 minutes after the show (half an hour after the
  burst); and black rain starts 20 real minutes after the burst unless a sleep ends the show first;
- `not-physical.md` describes the inside-sight row as "only for a camera inside the dust".

AGENTS.md "Time" would read (replacing the current paragraph):

> **Time** (Artem, 10.11; principle 26.11): the air in real time, the ground on the world's day. Everything a strike
> puts into the air (clouds, dust, mushroom, trails, smoke, rain and fallout in flight) lives its whole law in real
> time plus jumps and leaves by its own motion: what travels is never compressed. What stays in place (fires and their
> emission, contamination, ruins, outages, injuries) runs real during the strike's show (20 minutes for a detonation;
> a conventional blast until its fuel burns down), then at the world's rate (`util/WorldClock`: time of day, never
> slower than real, never backward: vanilla 72×, day-length mods 1×, stopped day real), joined by a rate blend.
> Handoffs carry the state: smoke carries its fire's strength at emission, rain and fallout their age in flight. A jump
> (the day stepping at least an hour in a tick: sleep, commands) counts once on both and ends a show. A state with
> several causes recovers at real rate while any cause is in its show; a body ages only while it is in the world. Real
> time is game time (lag, `/tick`, pause). No new time settings; durations come from the laws.

### 6.2 What C keeps

The 20-minute nuclear show and its 60-s join (now a rate blend), the world clock and its floor, the movement axis
(`scale_preset`, flight speed, wave speed), and every law's own durations. Kept with them, as today: real-time dust,
clouds, mushroom and trails, and the existing caps `CloudModel.LIFE_MAX` (1 h) and the trail's 1200 s, which are a
separate question for the laws' own visibility thresholds. No new setting.

## 7. Every mechanic against pack conditions: mismatches, ranked

From the inventories and a separate audit of pack conditions (tick rate, time control, gamerules, pause, servers,
replays, dimensions). Most player-visible first; "C" marks what the recommended principle fixes, the others are
independent fixes.

| # | Condition | What happens now | Fix | Setup |
|---|---|---|---|---|
| 1 | Any nuke, every pack | The dust blanket is drawn see-through from outside a minute after the front (§1.1) | C change 1 | every game |
| 2 | `doFireTick false` | No gamerule is read before placing fire (`strike/StrikeRules.java:30`); our ruin, flash, blast and debris fires (up to 20 000 per detonation, `nuclear/world/RuinPlan.java:1175`, `warhead/Warheads.java:489`, `StagedExplosion.java:1447`, `DebrisSpawner.java:51`) never go out, while the drawn storm burns out by its law: permanent static flames over a dead city. Crater fuel fires remove themselves at burn-out (`warhead/FuelFires.java:341`) | Treat `doFireTick false` as `world.fire false` for placement (the admin asked for no fire to burn), and set the detonation's `fires` off with it (`NuclearWarhead.java:98` reads only `nuclear.fires`), so the client draws no storm over a city with no fire (C change 9) | common on servers |
| 3 | `/time set day` at night (the usual op skip) | `dayTime` is absolute, so the set moves it backward; the world clock counts that tick as real (`util/WorldClock.java:106-125`, `ClientClock.java:30`): fires, fallout, injuries and repairs see no night pass | Count a backward set as the forward distance to that time of day (`floorMod(delta, 24000)`); only under 12 h (a night skip), otherwise real; such a set is a jump under C rule 6 and ends a show it falls in (C change 2) | common in single-player, with ops |
| 4 | Vanilla day, during the nuke's show | Hearing, burns, sickness and power recover 72× fast while the show runs in real time (§3.1, 1) | C change 5 | every vanilla game |
| 5 | Dedicated server, player offline | Sickness advances offline (login at the fatal stage); burns are frozen offline; hearing is cured by relogging | C change 6 | common |
| 6 | Flashback pause, `/tick freeze` | Conventional blast clouds, ground shake and crater smoke keep running (`client/IncomingClient.java:232,259,264,270`) while particles, trails and the nuke hold; the guide says clouds stand still | C change 7 (rule 8) | common for filming |
| 7 | Flashback export, pause, freeze | Heat shimmer runs on the wall clock (`client/fx/layer/HeatPass.java:644,733`): boils while paused, churns too fast in exported video | Drive it by game ticks plus partial tick or the replay position (C change 7, rule 8) | creators |
| 8 | Vanilla day, conventional play | A Shahed or missile cloud stays up to an hour (three in-game days) while drifting off; a trail 20 min; sleep leaves them | C change 3 (sleep clears them; their real-time life stays) | every vanilla game |
| 9 | Sleep during a show | Fires, rain and fallout stay at the minute of the sleep while the sky jumped hours (the guide says the fires are out after a night's sleep) | C change 2 | common in single-player |
| 10 | Day-length mods that set `dayTime` themselves (not NeoForge `dayTimePerTick`) | Without detonations the server does not send its clock, so the client's hearing clock counts vanilla steps (`ClientClock.java:30`, `NuclearStrikes.java:355`): hearing after conventional blasts heals about 72× too fast | The clock packet to every client once a second (C change 2, rule 9) | depends on the mod |
| 11 | 24-hour or stopped day | Outages 18 real hours, the storm 12 real hours, fallout real days: real life by design; `restore_spread_seconds` is world-clock time but its tooltip says seconds (90 s → 1.8 real h) | Fix the tooltip; the guide says plainly that a stopped day means real-time consequences and that `/time add` moves them on | common |
| 12 | Day 7 of a detonation | Fallout drops to zero in one step when the detonation is forgotten; unfinished crater progress is dropped with it | C change 8 | every long game |
| 13 | `/tick freeze` | The world clock keeps counting real time on frozen ticks (`WorldClock.java:112-125`): after the show, storm, rain, fallout, sickness and repairs advance in a frozen world and jump on unfreeze | Skip counting while the tick manager is frozen (rule 8) | rare |
| 14 | Day mods stepping more than one tick at a time | A step of +N every M ticks counts N plus the real floor already credited: a 24-hour day as +10 every 720 ticks runs 2× (`WorldClock.java:107-111`) | Subtract the real time credited since the last step | rare |
| 15 | `/tick rate`, `/tick sprint`, Flashback speed | The client assumes 20 ticks per second (`ClientNuclear.java:213,266-270`, `client/sound/OwnSources.java:380`): catch-up spurts, sound anchors drifting | Use the tick manager's tick length | rare |
| 16 | Long days | Wind changes every 24 000 game ticks (`util/Wind.java:23-31`): 72 winds per world day under a 24-hour day; sleep does not move it | Change the wind with the world clock | low |
| 17 | Other packs | Sea level fixed at 63 (`util/Air.java`, after `SURFACE_CLEAR`; `WaterDecal.SEA_LEVEL`); `randomTickSpeed 0` disables lamp relight in Sable plots (`grid/block/Unlit.java:55`); rain does not wet fuel for ignition | Read the level's sea level | rare |
| 18 | Fast-day and stepping day mods, gradual time-lapse sleep | Today they are counted as rates (`WorldClock.Steps`, apart from #14); under C they stay rates: rule 6's jumps are single-tick steps of at least an hour, so they never end a show | — | depends on the mod |

Handled correctly (checked): single-player pause (server and client clocks stop), sleep with any sleep share and
gradual time-lapse sleep mods (every tick's day advance counts), a 2-hour day in single steps (exactly 12×), a 24-hour
day (1:1), `doDaylightCycle false` (real), late join, dimension change and respawn (detonations re-sent, aged dust fades
in), Nether and End (the overworld day is shared, no black rain under a roof), the Flashback nuclear clock (replay
position, clock kept in snapshots), and the gamerules the mod needs (`keepInventory`, `doTileDrops`, spawn chunks).
Server lag slows the show and the server's work alike (game ticks on both sides).

## 8. Needs Artem's word

- Adopt C (or another principle) as the time rule; the AGENTS.md "Time" wording above.
- The split itself: the air (dust, clouds, mushroom, smoke) keeps its physical real-time life in every pack, and only
  what stays in place follows the day. Under a vanilla day the dust blanket therefore hangs about an hour, drifting off,
  rather than lifting at minute 20.
- A jump (sleep, time commands) during the show ends it (today the show ignores sleep).
- Under rule 6, `/time set day` during a show (with mismatch 3 fixed) or `/time add` ends the show at once, like
  sleep.
- Mismatch 2 (`doFireTick false` stops our fire placement and the drawn storm) changes what servers with fire tick off
  see: no burning ruins and no firestorm instead of permanent flames.
