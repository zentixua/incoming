# Design proposal: the first seconds of a nuclear burst — light, eyes, sound

Status: proposal, not implemented. Nothing in the mod changes until Artem approves it.
Scope (Artem, 27.11.2026): the whole first-seconds experience of a nuclear burst, first of all the fireball's light,
judged by AGENTS.md's concept: the picture is the judge, physics is the frame, presentation free within plausibility,
nothing reality lacks. Target reaction: "wow, this is insane, run".

## 1. The reported symptom
Artem, in creative, 27.11: the flash blinds him for the first seconds, then the blindness ends abruptly and the fireball
is there at once; in its first seconds the ball reads as a bright, high-contrast texture pasted on the scene: it does
not light its surroundings like a second sun, there is no radiance, no rays.

Reproduced on `main` (cf465e85c), sandbox, flat world, 15 kt airburst, creative player at eye height, vanilla
(section 3 has the numbers and frames).

## 2. Real fireball light, first 0.1–10 s
Sources: Glasstone and Dolan, *The Effects of Nuclear Weapons*, 1977 (G&D); eyewitness accounts from Trinity, Hiroshima
and the Nevada troop exercises; the declassified LLNL/EG&G test films. Reference frames and clips stay outside the
repository; numbers below are measured on them.

**Light.** One blinding flash to the eye: the first pulse (milliseconds) and the second maximum (0.14 s for 15 kt,
0.5 s for 300 kt; G&D §7.85) merge. 80 % of the heat is out by ten times that (1.4 s for 15 kt). At the second maximum
the light on a surface facing the ball is roughly 1,200 / 190 / 21 times noon sunlight at 2 / 5 / 15 km for 15 kt
(P_max/4πD², G&D §7.85, before air losses; the mod's own model gives 1,158 / 154 / 12 after them). The spectrum is
sun-like (G&D §7.01), so this is visible light: for a moment the ball is a second sun hundreds of times stronger than
the first. It falls fast as the ball cools: in the mod's model, 2 km: 175 noons at 0.5 s, 23 at 1 s, 1.4 at 2 s;
15 km: 0.2 noon at 1 s.

**What the light does to the world.** Everything facing the ball is lit, "darkness to brilliant sunshine in an instant"
(Lawrence, Trinity); "a sheet of sun" (Tanimoto, Hiroshima). Direct light casts hard shadows pointing away from the
ball: Hiroshima's flash shadows were used to find the burst point (G&D §7.44–7.45). Scattered light comes "from all
directions" (G&D §7.19): the haze and sky around the ball glow too. Colour runs blue-white, yellow, orange, red-brown
(Feynman, Serber).

**What films show.** Every camera was overexposed at first (Sandia, Benjamin: "the first pictures were overexposed").
After the white-out the ball sits inside a soft glow several radii wide: halation in the film and the lit haze. On the
Trinity colour film (pre-dawn), measured after the white-out: the ring at 2 ball radii is 0.2–0.33 of the ball's
brightness and at 3 radii 0.09–0.16, falling to 0.02 as the ball cools; the ball is ~20 times brighter than the scene
around it, which is lit red. On the Plumbbob Priscilla colour film (just after sunrise, from far away): the ring at
2 radii is 0.47–0.7 of the ball and the ball is 4–5 times brighter than the haze, which glows white over a ground lit
warm.

**What people perceive.**
- Flash blindness: seconds to minutes, worse at night (G&D §12.83–12.84); Serber, 20 miles, naked eye before dawn:
  "completely blinded", vision back after 20–30 s; an afterimage (Feynman's "purple splotch"); welders' goggles:
  blinded "for an instant". The blink (0.25 s) is too slow for a 15 kt pulse (G&D §12.87).
- Heat on the skin facing the ball within the first second ("like an oven door", Trinity at 10 miles; pain within
  ~5 km for 15 kt, Hardy et al.).
- Silence: nothing is heard until the blast arrives at sound speed ("almost no one in Hiroshima recalls hearing any
  noise", Hersey; Trinity: 27–90 s by distance; Spindel: "the most intimidating minute I have ever spent").
- Shaking never comes with the flash: the ground jolt and the air blast come seconds later (Apple II trench, 1955:
  flash, heat, then "the earth suddenly jumped", then the blast "like a freight train"; people knocked flat only by the
  blast).
- Fear and awe: "They've miscalculated and blown up the whole world" (Apple II), "the end of the world" (Castle Bravo
  sailor). Nobody reports dizziness or swaying at the flash.

Film: *Oppenheimer* (2023) builds its tension on the real 30–40 s of silence before the shock; Silo S3E09 blinds and
deafens at once (the blinding is real; instant deafening at a distance is not).

## 3. Ours on `main`
Takes: sandbox (flat grass, oaks and two houses in the `near` scene), 15 kt airburst, creative player at eye height,
1280×720, vanilla, CPU renderer; camera 2, 5 and 15 km from ground zero and 40 m behind the houses with the burst 2 km
beyond them; day and night; frames at fixed ages from 0.03 s to 10 s. Measured in linear light: ball core, sky corner
and ground patch, and the radial profile around the ball, with the same scripts on the reference films.

| What the frame shows | Number | Code that makes it |
|---|---|---|
| White frame, then the scene back within ~0.6 s | 2 km, night: frame mean 0.87 at 0.4 s, 0.27 at 0.75 s, 0.13 at 1.0 s | `NukeFlash.overexposed`: X = K^(e^(−u/0.2 s)) after the second maximum; `NukeLight.update`: exposure follows the flash with one 0.2 s constant up and down |
| Nothing after that in creative | no veil, only the orange retina spot | `NukeFlash.eyes`: creative and spectator get no dazzle |
| In survival, a flat grey screen for tens of seconds | veil 0.9 opacity, fading over `EyeInjury.dazzleSeconds` (10–60 s by day, up to 600 s at night) | `NukeFlash.veil`: one uniform fill |
| The ball barely brighter than the sky by day | ball/sky 1.2–2.0, ball/ground 5–10 at 0.75–2.5 s (2 km); references: Trinity 8–48 and ~50, Priscilla 20–36 and 13–16 | `NukeExposure`: the ball is shown on the film curve K·L^β, 0.5–0.8 of white, to keep its pattern |
| No halo once the frame settles | by day the sky around the ball is as bright as the ring at 2 radii; glare intensity min(0.6, 0.01 × 0.7) = 0.007 of white (computed from `Sight.light`) | `NukeRenderer.fireballs`: glare and veil sized by the film-curve value and capped by it; the ball is not in `FxBloom` |
| A dark ring around the ball by day | Wilson shell 0.37–0.49 against sky 0.50–0.66 at 0.75–1.5 s | the shell's light is capped to the ball's on-screen value (`mushBallCap`) |
| The world not lit by a second sun | houses facing the camera (away from the ball) keep full daylight; ground at most one extra noon | `fx_pall_composite`: ball light is added on top of the unchanged vanilla daylight (`relit`: world × kept + albedo × F × exposure); the daylight never passes through the flash exposure |
| No shadows from the ball | — | `ballLight`: "no terrain/building shadows" by design |
| Sky: white, amber ~1 s, blue again by ~2 s | sky corner back to its blue by 2 s at 2 km | vanilla sky flash, `NukeSky.amber` |

Under the host's shader pack the same holds, more strongly. The take is a Flashback export from the 3.14.0 release
candidate in a copy of Artem's instance with Photon and his options: 15 kt at 1.65 km over water, by day. For the
first seconds the sky and clouds keep their ordinary daylight, the water is only tinted peach, and the ball is a crisp
golden disc inside a dark blue-grey Wilson dome. Ball/sky is 2.0–3.5 at 1–2.5 s after the burst.

At night the halo works (ring at 2 radii 0.27–0.53 of the ball at 2–5 km; Trinity 0.2–0.33) and the ball dominates
(ball/sky 4–24): the defect is mainly a daytime one, plus the abrupt end of the flash at any time of day.

The cause, in one line: physics gives the light (the model's 1,158 noons at the peak at 2 km is right), but the frame
shows it through two separate cameras. The ball has its own exposure, set to keep its pattern. The world keeps its
daylight exposure and gets the ball's light only as a small addition. The glare follows the ball's camera, not the
light that reaches the eye. So the brightest thing in the world reads as a mid-grey disc on an ordinary day, and the
camera's 0.2 s auto-exposure ends the flash before the eye would.

## 4. Proposal
One idea carries it: **the frame sees the burst through one eye.** The light the model already computes reaches the
world, the sky, the glare and the eye through the same exposure, and that eye takes the flash at once and recovers at
an eye's pace. Each change below is a law fix in the code that already exists, not a new effect painted on top.

Mock: a throwaway probe branch (`fireball-light-probe`, never merged) drew items 1–3 roughly in the sandbox. Clips
compare it with `main`, and the numbers below are measured on them with the same scripts as the references. Ball
against the sky corner, by day, 0.75–2.5 s after the burst:

| | ball/sky | ball/ground |
|---|---|---|
| `main`, 2 km | 1.2–2.0 | 5–10 |
| probe, 2 km | 6–18 | 25–57 |
| Trinity (before dawn) | 8–48 | ~50 |
| Priscilla (just after sunrise) | 20–36 | 13–16 |

Mechanism rows (look skill): what each feature is caused by, where it is born, what it is anchored to, how it ends.

### 4.1 One exposure for the whole frame (physics; partial adaptation is presentation)
- Cause: the ball's light at the eye, F (noon whites; the model's `FlashLight`, already summed in `NukeLight`). Anchored
  to the eye. Ends as F falls and the eye recovers.
- Change: vanilla daylight, the sky, the vanilla clouds and the sun pass through the same flash exposure as the ball's
  added light, instead of keeping their normal brightness under it. While F is far above daylight, the ordinary day
  dims and what faces the ball dominates. Faces turned away from the ball stand dark against it (backlit houses and
  trees), and the ground in front of the camera is lit from the ball's side.
- Presentation: the frame follows the eye's exposure partly, as a power of 0.5 (the eye adapts locally; a camera fully
  adapted turned the day sky black, probe round 2: ball/sky ~100). The power is one marked presentation constant, like
  the fire film curve's `FOLLOW`.
- Light scattered by the air from the ball (the existing amber sky, the aureole of 4.3) stays in its own exposure
  units and is not dimmed again. The probe dimmed it and turned the night sky black; the real change must not.
- Where: `fx_pall_composite` (`relit`: the world's own light × the exposure ratio), and the sky pixels of the same
  pass; `NukeLight` exposes the ratio.

### 4.2 The eye recovers at an eye's pace (physics: bleaching; look of the veil is presentation)
- Cause: bleached photopigments (G&D §12.83). Strength and length come from the flash dose at the eye, which the mod
  already computes (`EyeInjury.dazzleSeconds`: 10 s at the edge of the flash-blindness range, up to 60 s by day and
  600 s at night). Anchored to the eye: it moves with the gaze. It ends by recovery, never by a cut.
- Change 1: the frame's exposure takes the flash in 0.2 s (as now) and recovers with the eye. The probe used 1.5 s for
  the exposure itself; the veil below carries the long tail. The white frame no longer ends in 0.6 s.
- Change 2: the dazzle veil stops being a uniform grey fill at 0.9 opacity, which erased the whole picture in the probe
  (round 1). It becomes what flash blindness looks like:
  - a strong afterimage where the ball was on the retina, positive (white-yellow) first, then coloured and negative
    (Feynman's purple), larger than the ball and following the gaze (the existing retina spot, made the main element);
  - lowered contrast and colour over the whole view, lifted blacks ("washed-out", Artem's "побледневший экран"), fading
    with the dose's recovery time;
  - the world stays visible through it, so the radiance of 4.1 and 4.3 is what the player sees through damaged eyes,
    not a grey card.
- Change 3: creative players get it too. Flash blindness is perception, not injury: a creative player cannot be hurt,
  but has eyes. Spectators and replay viewers stay a camera, without it. Retinal burns (permanent) stay survival-only.
- Where: `NukeLight.update` (asymmetric follow), `NukeFlash.veil` (a GUI post pass like `Concussion`, reusing its
  `PostChain` shape: desaturate, lift, the afterimage), `NukeFlash.eyes`.

### 4.3 Radiance: the ball glows as bright as it is (physics: eye scatter and haze; halation is presentation)
- Cause: the ball's true brightness against the eye's exposure. The Stiles–Holladay law the mod already uses for every
  other bright source (`Sight.light`: glare ∝ brightness, veil ∝ E/θ²) applies to the nuke too. Anchored to the ball and
  the eye. It ends as the ball cools: the halo shrinks with brightness, as on the Trinity film (ring at 2 radii 0.3 of
  the ball at the brightest, 0.02 at 12 s).
- Change 1: glare and veil are sized by the ball's real brightness times the frame exposure, not by its film-curve
  value. Their peak stays below the ball's on-screen value, so the surface pattern (an earlier complaint,
  `NukeExposureTest.fireballKeepsItsPatternFromOneToFiveSeconds`) stays readable.
- Change 2: the nuclear ball enters the bloom pass (`FxBloom`) with its true emission (the conventional fireball's
  path), so the glow spills over its edge: halation, as on every test film. Presentation, marked.
- Change 3: the Wilson shell is lit by the ball as droplets scattering forward. By day it glows white around the ball,
  as in the probe; it is not capped to a grey ring darker than the sky.
- Change 4: an aureole. Haze around the ball glows by forward scattering (G&D §7.19, "from all directions"), brightest
  next to the ball, fading with angle (Henyey–Greenstein in the existing `Sight.Air` haze). This is the white sky
  around the Priscilla ball, and the pink sky of the Trinity film.
- Target, measured as on the references: by day ball/sky 8–40 and ring at 2 radii 0.2–0.5 of the ball in the first
  2 s at 2–5 km. `main` fails it (1.2–2.0), probe round 3 is inside it (6–18).

### 4.4 Shadows of the second sun (physics)
- Cause: direct light travels in straight lines from the ball (G&D §7.18; Hiroshima's flash shadows). Anchored to the
  ground and to whatever casts them. Born at the flash; they swing slowly as the cap rises, and end when the ball fades.
- Change: the ball's direct light on the world gets a shadow. Two parts, the way modern renderers do it:
  - terrain and buildings at range: a march over the client height map toward the ball (the heights the pall already
    uses), with a penumbra from the ball's disc;
  - near detail (trees, fences, the player's own body and hand): a short screen-space ray toward the ball over the scene
    depth, which the probe used.
  Long shadows stream toward the camera when the ball is behind houses, the strongest single cue of a second sun.
- The probe's screen-space part leaves streaks at the frame edges, where the depth has no information. The real change
  needs both parts.
- Where: `ballLight` in `fx_pall_composite` and `mushroom.glsl` (`mushBallFace`: the direct beam only).

### 4.5 Order and time (no change, kept as they are)
The flash, the light, the heat and the fires come first, in silence. The sirens fade and the animals go quiet
(`AnimalSilence`). Then the wave arrives at its own speed with the ground jolt, the body hit, the roar, the dulled
hearing and the concussion blur, all of which exist (`BodySway`, `CameraShake.ground`, `Deafness`, `Concussion`). The
silence between flash and wave is the most frightening part of every account (Spindel: "the most intimidating
minute"). Nothing is added to it.

## 5. Eyes, ears and body: Artem's question on disorientation
What real people felt in the first seconds: a white-out, then an afterimage and seconds to tens of seconds of poor
sight; heat on the skin facing the ball; silence; confusion and fear. Nobody reports dizziness or swaying at the flash.
Every jolt, shake and fall comes with the ground shock and the air blast, seconds later.

| Effect | Real? | Recommendation |
|---|---|---|
| Blinding white, then poor sight with an afterimage, recovering slowly | yes, the strongest real effect | **Yes** (4.2): scaled by the flash dose at the eye and line of sight; creative included |
| Washed-out, pale view while the eyes recover | yes (bleaching) | **Yes**, part of 4.2; marked presentation for its exact look |
| The world lit by a second sun, then the ordinary day "coming back" | yes | **Yes** (4.1, 4.3, 4.4) |
| Camera shake at the flash | no: nothing moves at the flash | **No**. Shake stays with the ground shock and the wave (exists) |
| Swaying or drunk camera after the flash | no source; flash blindness does not unbalance | **No**. Disorientation comes from not seeing, which 4.2 gives |
| Instant deafening at the flash (Silo) | no: sound comes with the wave | **No**. The silence is kept; deafness, ringing and blur come with the wave (exists) |
| Heat shown on screen (red vignette) | the heat is real, the red edge is not | **No**. Heat shows through what it does: flash fires, smoke, burns in survival |
| Seeing one's own hand bones | reported, cause unproven | **No** |

## 6. Cost
| Item | Frame cost (estimate, RTX 4060, 1080p) | When |
|---|---|---|
| 4.1 one exposure | a multiply in a pass that already runs | while the ball shines |
| 4.2 eye veil | one GUI post pass, ~0.1 ms (est., the shape of `Concussion`) | while the eyes recover |
| 4.3 glare, bloom, shell, aureole | glare quads exist; the bloom pass exists (0.12–0.19 ms full-frame at 2560×1600, client-fx.md); shell and aureole are terms in existing shaders | while the ball shines |
| 4.4 shadows | height-map march plus a 24–28 step screen ray: estimated 0.3–0.6 ms; quality presets drop the screen part | about 10 s per burst |
Each item gets the scene bench on its final head, as AGENTS.md requires.

## 7. Not proposed, and why
- Camera shake, sway or tilt at the flash: reality lacks it (section 5).
- Instant deafening at a distance: sound travels at its speed; the silence is the drama.
- A white screen as the blindness itself: it erases the picture; blindness is shown through the picture.
- X-ray skeletons, red heat vignettes, fire on every block: invented.
- Making the ball physically brighter or bigger: the model's light (1,158 noons at the peak at 2 km) is right; the
  defect is how the frame shows it.

## 8. Checks a change must pass
- The look gate on the reference: ball/sky and ring-at-2-radii measured on the Trinity and Priscilla frames pass; `main`
  fails by day (section 3).
- Earlier complaints keep their tests: the ball's pattern at 1–5 s, the rolled cap at night through haze, no re-blown
  frame at rollup for big yields (`NukeExposureTest`), the vanilla sky-flash sawtooth (`NukeFlashSkyTest`).
- The whole first minute in the matrix: 1 and 15 kt and 1 Mt, day and night, over land and water, 2/5/15 km, under the
  host's shader pack.
