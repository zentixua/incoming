# Design proposal: the first seconds of a nuclear burst — light, eyes, sound

Status: proposal, not implemented. Nothing in the mod changes until Artem approves it.
Scope (Artem, 27.11.2026): the whole first-seconds experience of a nuclear burst, first of all the fireball's light,
judged by AGENTS.md's concept: the picture is the judge, physics is the frame, presentation free within plausibility,
nothing reality lacks. Target reaction: "wow, this is insane, run".

## 1. The reported symptom
Artem, in creative, 27.11: the flash blinds him for the first seconds, then the blindness ends abruptly and the fireball
is there at once. In its first seconds the ball reads as a bright, high-contrast texture pasted on the scene: it does
not light its surroundings like a second sun, there is no radiance, no rays.

Reproduced on `main` (cf465e85c) in the sandbox: flat world, 15 kt airburst, creative player at eye height, vanilla.
Also reproduced under the host's shader pack from a release-candidate take. Section 3 gives the numbers and frames.

## 2. Real fireball light, first 0.1–10 s
Sources:
- Glasstone and Dolan, *The Effects of Nuclear Weapons*, 1977 (G&D);
- eyewitness accounts from Trinity, Hiroshima and the Nevada troop exercises;
- the declassified LLNL/EG&G test films.

Reference frames and clips stay outside the repository. The numbers below are measured on them with the same scripts
as our frames, inside the picture area (one film is pillar-boxed). The films are edited and their playback speed is
unknown, so a reference "time" is a position in the clip, not an age after the burst.

**Light.**
- The first pulse (milliseconds) and the second maximum (0.14 s for 15 kt, 0.5 s for 300 kt; G&D §7.85) merge into
  one blinding flash to the eye. 80 % of the heat is out by ten times that: 1.4 s for 15 kt.
- At the second maximum, the light on a surface facing the ball is about 1,200 / 190 / 21 times noon sunlight at
  2 / 5 / 15 km for 15 kt (P_max = 3.18·W^0.56 kt/s over 4πD², G&D §7.85, before air losses).
- The mod's own model gives 1,045 / 150 / 11.5 after the air losses of a clear day, with the 592 m burst height. Its
  bare source (7,700 K at the maximum radius) is about 25 % above the G&D power before the air; the comparison holds
  within that.
- The spectrum is sun-like (G&D §7.01), so this is visible light: for a moment the ball is a second sun hundreds of
  times stronger than the first.
- It falls fast as the ball cools. In the model at 2 km: 158 noons at 0.5 s, 21 at 1 s, 1.2 at 2 s. At 15 km: 0.24
  noon at 1 s.

**What the light does to the world.**
- Everything facing the ball is lit: "darkness to brilliant sunshine in an instant" (Lawrence, Trinity); "a sheet of
  sun" (Tanimoto, Hiroshima).
- Direct light casts shadows pointing away from the ball: Hiroshima's flash shadows were used to find the burst point
  (G&D §7.44–7.45).
- Scattered light comes "from all directions" (G&D §7.19), so the air, the haze and the sky glow too.
- Colour runs blue-white, then yellow, orange, red-brown (Feynman, Serber).

**What films show.**
- Every camera was overexposed at first (Sandia, Benjamin: "the first pictures were overexposed"). After the white-out,
  the ball sits inside a soft glow several radii wide: halation in the film and the lit haze.
- **Trinity** colour film (before dawn): the ring at 2 ball radii is 0.21–0.33 of the ball, and 0.09–0.16 at 3 radii.
  It shrinks to 0.02 as the ball cools. The ball is 8–48 times the sky and ~50 times the ground, which is lit red.
- **Plumbbob Priscilla** colour film (just after sunrise, from far away): the ball is 2.1–4.0 times the sky and 8–9
  times the ground. The ring at 2 radii is 1.6–1.9 times the sky beside it: the haze around the ball glows white.
- **Upshot-Knothole Grable** colour film (15 kt, morning, daylight). In the first seconds of the clip the whole frame
  washes out: contrast (95th over 5th percentile of luminance, the brightest 5 % excluded) is 1.4–3.3 and colour
  saturation 0.17–0.22. Later in the clip it returns to 3.2–6.3 and 0.35–0.44. The day does not darken: it turns
  pale and glowing, and the ball is a blown white core in a wide glow.

**What people perceive.**
- Flash blindness: seconds to minutes, worse at night (G&D §12.83–12.84). Serber, 20 miles, naked eye before dawn:
  "completely blinded", vision back after 20–30 s. An afterimage (Feynman's "purple splotch"). Through welders'
  goggles: blinded "for an instant". The blink (0.25 s) is too slow for a 15 kt pulse (G&D §12.87).
- Heat on the skin facing the ball within the first second ("like an oven door", Trinity at 10 miles). Derived from
  Hardy's pain threshold (0.22 cal/cm²/s): pain within ~5 km for 15 kt.
- Silence: nothing is heard until the blast arrives at sound speed. "Almost no one in Hiroshima recalls hearing any
  noise" (Hersey); at Trinity, 27–90 s by distance; Spindel: "the most intimidating minute I have ever spent".
- Shaking comes with the ground shock and the blast, seconds after the flash. Apple II trench, 1955: flash, heat,
  then "the earth suddenly jumped", then the blast "like a freight train". People were knocked flat only by the blast.
- Fear and awe: "They've miscalculated and blown up the whole world" (Apple II), "the end of the world" (Castle Bravo
  sailor). No account found reports dizziness or swaying at the flash.

Film: *Oppenheimer* (2023) builds its tension on the real 30–40 s of silence before the shock. Silo S3E09 blinds and
deafens at once: the blinding is real, the instant deafening at a distance is not.

## 3. Ours on `main`
Takes:
- Sandbox: flat grass, 15 kt airburst, creative player at eye height, 1280×720, vanilla, CPU renderer.
- Cameras 2, 5 and 15 km from ground zero, and 40 m behind two houses and three oaks with the burst 2 km beyond them.
- Day and night; frames at fixed ages from 0.03 s to 10 s, and 30 fps sequences from the burst to the blast.

Measured in linear light: ball core, sky corner and ground patch inside the picture, the ring around the ball, and the
frame's contrast and saturation.

| What the frame shows | Number | Code that makes it |
|---|---|---|
| White frame, then the scene back within ~0.6 s | 2 km, night: frame mean ~0.9 at 0.4 s, 0.27 at 0.75 s, 0.13 at 1.0 s | `NukeFlash.overexposed`: X = K^(e^(−u/0.2 s)) after the second maximum; `NukeLight.update`: one 0.2 s constant up and down |
| Nothing after that in creative | no veil, only the orange retina spot | `NukeFlash.eyes`: creative and spectator get no dazzle |
| In survival, a flat veil for up to tens of seconds | veil 0.9 opacity, (1 − t/T)² over `EyeInjury.dazzleSeconds` (up to 60 s by day, 600 s at night; weaker below 10 s); grey by day, black at night | `NukeFlash.veil`: one uniform fill, which hid the whole picture when tried in creative (mock round 1) |
| No glow around the ball once the frame settles, by day | ring at 2 radii / sky beside it: 1.0–1.07 (Priscilla 1.6–1.9); glare 0.007 of white | `NukeRenderer.fireballs` → `FarSprites.glare`: glare and veil sized by the ball's film-curve value and capped by it (`min(HALO, SCATTER·seen)`, veil radius ≤ 10°); the nuclear ball is not in `FxBloom` |
| The frame keeps its full daylight contrast | contrast 6.5–8.5, saturation 0.24–0.29 at 0.64–1.5 s (Grable's first seconds: 1.4–3.3 and 0.17–0.22) | the ball's light is added only to faces, at the flash exposure, over the unchanged daylight; no lit air, no wide veil |
| The ball itself is a mid-bright disc | shown at 0.5–0.8 of white, its pattern readable; ball/sky 1.9–2.0 (Priscilla 2.1–4.0) | `NukeExposure`: the ball on the film curve K·L^β, by design, to keep its pattern |
| A darker ring around the ball by day | Wilson shell visibly darker than the sky beside it (0.75–1.5 s), as a blue-grey dome under the shader pack | the shell's light is capped to the ball's on-screen value (`mushBallCap`) while the sky keeps its daylight |
| The world not lit like by a second sun | the ground gets at most about one noon from the ball | `fx_pall_composite` `relit`: world × kept + albedo × F × exposure; the daylight never passes through the flash exposure |
| No shadows from the ball | — | `ballLight`: "no terrain/building shadows" by design |
| Sky: white, amber ~1 s, blue again by ~2 s | — | vanilla sky flash, `NukeSky.amber` |

Under the host's shader pack the same holds, more strongly. The take is a Flashback export from the 3.14.0 release
candidate in a copy of Artem's instance with his options and shader pack: 15 kt at 1.65 km over water, by day.
- For the first seconds the sky and clouds keep their ordinary daylight and the water is only tinted peach.
- The ball is a crisp golden disc inside a dark blue-grey Wilson dome; ball/sky 1.7–2.6.
- The relit foliage turns neon green from 0.5 to 2 s: the relit path under the pack.

At night `main` works far better: the ring at 2 radii is 0.14–0.27 of the ball at 2–5 km (Trinity 0.21–0.33), and the
ground and sky are lit orange. The defect is mainly a daytime one, plus the abrupt end of the flash at any time of day.

The cause in one line: physics gives the light (the model's thousand noons at the peak at 2 km is right within 25 %),
but the frame shows it through separate cameras:
- the ball has its own exposure, set to keep its pattern;
- the world keeps its daylight exposure and gets the ball's light only as a small addition;
- the glare follows the ball's camera, not the light that reaches the eye;
- the 0.2 s auto-exposure ends the flash long before an eye would.

## 4. Proposal
One idea carries it: **the frame sees the burst through one eye.** The light the model already computes reaches the
world, the air, the glare and the eye through one exposure, and that eye takes the flash at once and recovers at an
eye's pace. Each change is a law fix in code that exists, not an effect painted on top.

Mock: a throwaway probe branch (`fireball-light-probe`, never merged). Its round 4 draws 4.1 (world and sky terms
only), the eye veil of 4.3 and the screen part of 4.5 in the sandbox. Clips compare it with `main`. Measured by day,
2 km, the same scripts as the references:

| | contrast, saturation at 0.5–0.75 s | ring at 2R / sky at 1–1.5 s | ball/sky at 1–2.5 s |
|---|---|---|---|
| `main` | 5.9–8.5, 0.21–0.28 | 1.0–1.07 | 1.9–2.0 |
| mock round 4 | 2.5–4.7, 0.15–0.31 | 1.4–1.7 | 2.4–3.5 |
| Grable (first seconds of the clip) | 1.4–3.3, 0.17–0.22 | — | 1.3–3.1 |
| Priscilla | — | 1.6–1.9 | 2.1–4.0 |

Earlier mock rounds, kept as evidence:
- **Round 1** gave creative players the present dazzle veil: a beige-grey screen.
- **Round 2** fully adapted the frame to the flash: the day turned black.
- **Round 3** adapted it halfway, but on the daylight only: the day turned into a slate dusk. The review measured that
  round's apparent gain against a mis-measured reference.
- **Round 4** applies one exposure to the daylight and to the ball's light together, adds the eye's glare, and moves
  toward the films.

Mechanism rows, as the look skill asks: what causes each feature, where it is born, what it is anchored to, how it
ends.

### 4.1 One exposure for the whole frame (physics; how much the frame follows is presentation)
- **Cause:** the ball's light at the eye, F in noon whites (`FlashLight`, summed in `NukeLight`). Anchored to the eye.
  Ends as F falls.
- **Change:** one frame exposure E_f = E₀·r^p, where r = E/E₀ is the eye's flash exposure against the unflashed one.
  Every term on screen passes through it, each consumer named:
  - the composite's world: the daylight `kept` × r^p, the ball's added light × r^(p−1);
  - the sky pixels;
  - the amber sky and the aureole of 4.3, which are already in flash-exposure units, so × r^(p−1), never dimmed again;
  - the pall, dust wall and mushroom matter light in `fx_pall` and the dust pass: their sun and sky terms × r^p, their
    ball terms × r^(p−1);
  - the layer quads (`FarView.exposure`) and the first-person hand. Vanilla draws the hand after the composite, so it
    needs its own pass.
- **Result by day:** faces toward the ball blow out white, the ordinary day steps back behind the glow, and the frame
  washes out instead of darkening.
- **Presentation:** p, the share of the flash the frame follows. p = 0.5 by day: a camera fully adapted turns the day
  black (round 2), an unadapted one shows nothing (`main`). At night p = 1, which keeps `main`'s working night: a
  dark-adapted eye takes the full flash. p follows the sky's ambient light, as `Sight.adapted` does. One marked
  constant, like the fire film curve's `FOLLOW`.
- **Earlier complaint (18.10):** "in the first seconds the second sun gives sharp shadows across the entire map". The
  fix gave faces turned away from the ball scattered and ground-reflected light, so the world is white at the peak and
  a face toward the ball is 5–6 times a side face by 2 s (`MushroomGlslTest.ballLightsWhatFacesAway`). 4.1 keeps that
  law. Faces turned away are lit by the same scattered light, × r^(p−1). A new screen-level check holds the
  toward/away ratio on screen to the test's, at its geometry (15 kt, 4 km): the old test checks the model, not the
  screen.
- **Where:** `fx_pall_composite` (`relit`, sky pixels), `fx_pall` and the dust pass (matter light), `NukeLight` (the
  ratio and p), the hand, `FarView.exposure`.

### 4.2 One eye: flash blindness that recovers at an eye's pace (physics: bleaching; the veil's look is presentation)
- **Cause:** bleached photopigments (G&D §12.83). The dose at the eye is already computed: `NukeLight.sample`, by
  distance, line of sight, openings and weather.
- **One eye state:** a bleached share B from that dose, recovering by `EyeInjury.dazzleSeconds`' law (10 s at the edge
  of the flash-blindness range, up to 60 s by day and 600 s at night). B drives everything the eye does after the flash:
  - the frame's exposure tail: the white catches up at 0.2 s as now, then the remaining sensitivity loss follows B;
  - the veil's look.
  The mock's separate 1.5 s exposure tail (round 3) was a second law for the same bleaching and is dropped. Round 4
  ran without it.
- **The veil's look** replaces the uniform grey fill at 0.9 opacity, which erased the picture (round 1). It shows what
  flash blindness is:
  - a strong afterimage where the ball was on the retina: white-yellow first, then a coloured negative (Feynman's
    purple). It is larger than the ball and follows the gaze; it grows from the existing retina spot;
  - lower contrast and colour over the whole view, with lifted blacks: Artem's "pale screen". Its strength is B;
  - the world stays visible through it, so the player sees the radiance of 4.1 and 4.3 through damaged eyes, not a
    grey card.
- **Creative players get it too.** Flash blindness is perception, not injury: a creative player cannot be hurt, but
  has eyes. Spectators and replay viewers stay a camera, without it. Permanent retinal burns stay survival-only.
- **Night:** the long night dose (up to 600 s at the full bleach) applies in creative as in survival. It fades as
  (1 − t/T)², so most of it is faint. The `flash` client setting and calm mode scale or disable all of it, as they do
  every flash path today.
- **Where:** `NukeLight` (the exposure tail from B), `NukeFlash` (the veil as a GUI post pass shaped like `Concussion`'s
  `PostChain`; `eyes`).

### 4.3 Radiance: the ball glows as bright as it is (physics: eye scatter and lit air; halation is presentation)
- **Cause:** the ball's true brightness against E_f. Anchored to the ball and the eye. It ends as the ball cools: the
  glow shrinks with brightness, as on the Trinity film (ring at 2 radii 0.3 of the ball at the brightest, 0.02 late).
- **Change 1, glare and veil from true brightness.** Glare and veil are sized by the ball's real brightness × E_f, not
  by its film-curve value. The eye's veiling glare keeps its real tail: Stiles–Holladay 10·E/θ², falling faster
  beyond 30°, instead of the 10° cap. The mock's whole-frame washout comes from this.
  - The glare's peak stays below the ball's on-screen value, so the surface pattern stays readable
    (`NukeExposureTest.fireballKeepsItsPatternFromOneToFiveSeconds`).
  - The wide veil gets the half-step noise the bloom and the smoke veil use. Without it, 8-bit rings show over a dark
    frame (mock round 3, night).
- **Change 2, lit air (aureole).** The air along each view ray scatters the ball's light forward (G&D §7.19), with
  Henyey–Greenstein in the existing `Sight.Air` haze. It is brightest beside the ball and present over the whole sky.
  This is the white glowing haze of the Priscilla film.
- **Change 3, halation.** The nuclear ball enters the bloom pass. The ball is a pall volume, not a layer quad, so this
  needs its own emission write from `fx_pall` into the bloom target: new work with its own cost, not free.
  Presentation, marked. With the player's `bloom` OFF, changes 1 and 2 still carry the radiance.
- **The Wilson shell keeps its law and its cap.** The cap fixed "the shell merged with the sphere as a white disk at
  0.5–1 s". Under 4.1 the shell's sun and sky light dim with the sky behind it, so it no longer stands as a dark ring
  by day. A check holds that the shell never merges with the ball at 0.3–1.5 s for 1, 15 and 250 kt.

### 4.4 Rays (physics; the eye's own streaks are physics of the eye)
"Rays" in real footage and experience are two things:
- **Shafts of light and shadow through lit air.** They form where clouds, the Wilson cloud, smoke, dust or terrain
  stand between the ball and the air, as crepuscular rays from the sun do. The aureole of 4.3 gets the shadow test of
  4.5 along the view ray: where the ball is hidden from a stretch of air, that stretch does not glow. Born at the
  flash, anchored to what casts them, gone when the ball fades. Physical.
- **Streaks around the brightest light in the eye:** the ciliary corona, radial spikes the eye's own optics put on any
  overwhelming point of light. Physical for the eye, never a camera's hexagonal lens flare. Subtle, only while the
  ball is far brighter than everything else, in the glare quad's shape.

### 4.5 Shadows of the second sun (physics)
- **Cause:** direct light travels in straight lines from the ball (G&D §7.18). Anchored to the ground and to whatever
  casts the shadow. Born at the flash; they swing slowly as the cap rises, and end when the ball fades.
- **Only the direct beam is shadowed.** The scattered fill of 4.1 (the 18.10 law) stays. Shadows are visible but not
  black: white everywhere at the peak, then the direction appears, within the screen-level ratio of 4.1.
- **One phenomenon with the scorch.** The server already computes this burst's shadows for the flash marks
  (`FlashShadows`, the 16×16 masks of `FlashMarks`). The live shadow must fall where the scorch silhouette is left.
  - Within the flash-mark range, the masks drive the live shadow on marked faces.
  - Beyond it, a march over the client height map toward the ball. The pall's height map has cells tens of metres
    across: it resolves terrain, not houses, so a finer map near the eye is needed (`FarTerrain`'s client chunks).
  - Near detail: a short screen-space ray over the scene depth, as in the mock. It cannot shadow the player's own
    body, and it leaves the screen at once when the ball is behind the camera, so it is only the near-detail
    complement, never the whole.
- **Where:** `ballLight` in `fx_pall_composite`; `mushBallFace` in `mushroom.glsl` (the direct beam only); the flash
  marks' client mirror.

### 4.6 Water: the second sun's glitter path (physics)
Over water the strongest second-sun cue is the ball's reflection: a glitter path from the ball to the eye, as the sun
makes, white at the flash and orange as the ball cools. The release-candidate take shows only a peach tint on the
water. The water decal (`fx/layer/WaterDecal`) draws the path from the ball's direction and brightness, with the
wave-slope spread of the sun's glitter (Cox–Munk).

### 4.7 Order and time (kept)
The flash, the light, the heat and the fires come first, in silence. The sirens fade and the animals go quiet
(`AnimalSilence`). Then the wave arrives at its own speed with the ground jolt, the body hit, the roar, the dulled
hearing and the concussion blur, all of which exist (`BodySway`, `CameraShake.ground`, `Deafness`, `Concussion`). The
silence between flash and wave is the most frightening part of every account (Spindel: "the most intimidating
minute"). Nothing is added to it.

### 4.8 Conditions
- **Yields:** 1 kt flashes and fades within about half a second. 1 Mt's pulse lasts ~10 s (G&D §2.40), so its day
  washes out and its glow holds for ~10 s, which is what that yield does. The 250 kt rollup case
  (`NukeExposureTest.bigCapsDoNotBlowTheFrameAgainAtRollup`) is re-checked under the new glare sizing.
- **Shader pack:** the relit path's neon foliage seen under the pack gets more weight under 4.1, so the acceptance
  runs under the pack in Artem's instance at acceptance time. The release-candidate take used Photon; `client.md`
  names Complementary, and one of the two is stale.
- **Multiplayer and server:** nothing changes on the server, and no packet or protocol changes. Each player's light,
  eyes and dazzle follow their own position, gaze and game mode, as now.
- **Settings:** the `flash` client setting (0 disables) and calm mode govern every new term. No new setting.
- **Player guide:** creative players now get flash blindness, and the radiance changes the look. Both languages are
  updated in the implementing PR.

## 5. Eyes, ears and body: Artem's question on disorientation
What real people felt in the first seconds:
- a white-out, then an afterimage and seconds to tens of seconds of poor sight;
- heat on the skin facing the ball;
- silence;
- confusion and fear.

No account found reports dizziness or swaying at the flash. Every jolt, shake and fall comes with the ground shock and
the air blast, seconds later.

| Effect | Real? | Recommendation |
|---|---|---|
| Blinding white, then poor sight with an afterimage, recovering slowly | yes, the strongest real effect | **Yes** (4.2), scaled by the dose at the eye and line of sight; creative included |
| Washed-out, pale view while the eyes recover | yes (bleaching) | **Yes**, part of 4.2; its exact look marked presentation |
| The world lit by a second sun, then the ordinary day coming back | yes | **Yes** (4.1, 4.3, 4.5) |
| Camera shake at the flash | no: nothing moves at the flash | **No**. Shake stays with the ground shock and the wave (exists) |
| Swaying or drunk camera after the flash | no account found | **No**. Disorientation comes from not seeing, which 4.2 gives |
| Instant deafening at the flash (Silo) | no: sound comes with the wave | **No**. The silence is kept; deafness, ringing and blur come with the wave (exist) |
| Heat shown on screen (red vignette) | the heat is real, a red screen edge is not | **No**. Heat shows through what it does: flash fires, smoke, burns in survival |
| Seeing one's own hand bones | reported, cause unproven | **No** |

## 6. Cost
| Item | Frame cost (RTX 4060, 1080p) | When |
|---|---|---|
| 4.1 one exposure | multiplies in passes that already run, plus a hand pass | while the ball shines |
| 4.2 eye veil | one GUI post pass, ~0.1 ms (est., the shape of `Concussion`) | while the eyes recover |
| 4.3 glare, veil, aureole | glare quads exist; the veil and aureole are terms in existing passes | while the ball shines |
| 4.3 halation | a new emission write from `fx_pall` into the bloom target, plus the existing bloom pass (0.12–0.19 ms full-frame at 2560×1600, client-fx.md) | while the ball shines |
| 4.4 shafts | the 4.5 shadow test along view rays inside the existing haze march | while the ball shines |
| 4.5 shadows | height-map march, flash-mark masks and a 24–28 step screen ray; not measured (the mock ran on a CPU renderer) | about 10 s per burst |
| 4.6 glitter path | a term in the water decal | while the ball shines over water |

Each item is measured on the GPU during implementation and gets the scene bench on its final head (AGENTS.md).

## 7. Not proposed, and why
- Camera shake, sway or tilt at the flash: reality lacks it (section 5).
- Instant deafening at a distance: sound travels at its speed; the silence is the drama.
- A white or grey screen as the blindness itself: it erases the picture; blindness is shown through the picture.
- A camera's hexagonal lens flare: the player has eyes, not a lens.
- X-ray skeletons, red heat vignettes, fire on every block: invented.
- Making the ball physically brighter or bigger: the model's light is right within 25 %; the defect is how the frame
  shows it.

## 8. Checks a change must pass
- **Day look gate.** Measured inside the picture as on the films, at 2 and 5 km:
  - washout in the light pulse: contrast ≤ 4 and saturation ≤ 0.3 (Grable 1.4–3.3 and 0.17–0.22; `main` 5.9–8.5);
  - glow: ring at 2 radii / sky ≥ 1.4 (Priscilla 1.6–1.9; `main` 1.0–1.07);
  - the gate passes the reference frames and fails `main`.
- **Night is no worse than `main`:** ground, sky and ring-at-2-radii within `main`'s ranges (section 3), against
  Trinity.
- **Earlier complaints keep their tests:**
  - the ball's pattern at 1–5 s, the rolled cap at night through haze, no re-blown frame at rollup for big yields
    (`NukeExposureTest`);
  - the vanilla sky-flash sawtooth (`NukeFlashSkyTest`);
  - the 18.10 ratio on screen (4.1, new);
  - the shell never merging with the ball (4.3, new).
- **The whole first minute in the matrix:** 1 and 15 kt and 1 Mt; day and night; land and water; 2, 5 and 15 km;
  under the host's shader pack.
