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
- **Upshot-Knothole Grable** colour film (15 kt, morning, daylight). For several seconds of the clip, with the cap
  already risen over its stem, the whole frame carries a uniform pale lavender cast: contrast 1.4–3.3 and saturation
  0.17–0.36, against 3.2–6.3 and 0.35–0.44 later in the same clip. The ball's light falls more than ten times over
  those frames while the cast holds, so the cast is the film (fogging, flare, the print), not lit air or an eye. It is
  film look, not a target for the light.

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
| No lit air: the frame keeps its full daylight contrast | contrast 6.5–8.5 at 0.64–1.5 s against 9.8 in the same scene unflashed | the ball's light is added only to faces, at the flash exposure, over the unchanged daylight; no lit air, no wide eye veil |
| The ball itself is a mid-bright disc | shown at 0.5–0.8 of white, its pattern readable; ball/sky 1.9–2.0 (Priscilla 2.1–4.0) | `NukeExposure`: the ball on the film curve K·L^β, by design, to keep its pattern |
| A darker ring around the ball by day | Wilson shell visibly darker than the sky beside it (0.75–1.5 s), as a blue-grey dome under the shader pack | the shell's ball light is capped to the ball's on-screen value (`mushBallCap`) while its sun and sky light and the sky behind it keep the daylight exposure |
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
world, the air and the glow through one exposure. The eye's own after-effects are drawn over the picture and never dim
it. No new physics: every change is in how the frame shows light the model already has (concept 3). Each is a fix of
code that exists, not an effect painted on top.

**Core (small):** 4.1–4.3. **Later, each on its own if wanted:** 4.4–4.6.

### Mock
A throwaway probe branch, `fireball-light-probe`, never merged. Its last round draws 4.1 on the world and sky terms
and the eye's veiling glare of 4.3, plus the screen-space part of 4.5. It is shown in the clips beside `main`.

Measured by day at 2 km with the same scripts as the references. The ring is taken at 2 ball radii, with the ball's
radius from `main`'s take of the same geometry:

| | contrast at 0.75–1.5 s (unflashed scene: 9.8) | ring at 2R / sky, at 1–1.5 s | ring at 2R / ball | ball pattern (variation in the disc) |
|---|---|---|---|---|
| `main` | 7.8–8.5 | 1.04 | 0.62–0.70 | 0.11–0.13 |
| mock, last round | 4.3–8.4 | 2.6–2.8 | 0.96–1.3 | 0.19–0.25 (incl. a mask crescent) |
| Priscilla | — | 1.4–1.75 | — | — |
| Trinity | — | — | 0.15–0.33 | — |

The mock lights the day and keeps the ball's pattern, but its glow is too strong: about twice Priscilla's ring over
the sky, as bright as the ball at 2 radii. Its disc mask is analytic and leaves a dark crescent where it misses the
drawn ball. The implementation masks by the ball's drawn coverage, as the bloom pass does, and tunes the veil's
strength to the gate of section 8.

**Not in the mock:**
- the afterimage and pale-eye overlay of 4.2;
- the aureole, the halation and the shafts;
- the hand and the pall matter terms of 4.1;
- the water path;
- the mock dims the amber sky by day, which the real change must not.

**Mock rounds:**
- Round 1 gave creative players the present dazzle veil: a beige-grey screen.
- Round 2 fully adapted the frame: the day turned black.
- Round 3 dimmed the daylight without raising the ball's light: a slate dusk.
- Round 4 lit the day but erased the ball's pattern under the glare, and darkened the night sky.
- Rounds 5–6 apply the exposure by day only, keep the night as `main`, and keep the glow off the ball's own disc.

The two reviews measured each round. Their findings are why the proposal reads as it does.

### 4.1 One exposure for the world by day (physics; how far the frame follows is presentation)
- **Cause:** the ball's light at the eye, F in noon whites (`FlashLight`, summed in `NukeLight`). Anchored to the eye.
  Ends as F falls: about 2 s at 2 km for 15 kt.
- **Change:** by day, one frame exposure E_f = E₀·r^½, where r = E/E₀ is the flash exposure against the unflashed
  one. Every term on screen passes through it, each consumer named:
  - the composite's world: the daylight `kept` × r^½, the ball's added light × r^−½;
  - the sky pixels and the vanilla clouds × r^½;
  - the amber sky and the aureole (4.3), already in flash-exposure units, × r^−½;
  - the pall, dust wall and mushroom matter in `fx_pall` and the dust pass: their sun and sky terms × r^½, their ball
    terms × r^−½;
  - the layer quads (`FarView.exposure`) and the first-person hand. Vanilla draws the hand after the composite, so it
    needs its own pass.
- **Result:** faces toward the ball blow out white and the ordinary day steps back behind the light. Then the frame
  returns at the present 0.2 s camera follow.
- **At night nothing changes.** `main`'s night works (section 3): a dark-adapted view already takes the ball's light
  in full.
- **Presentation:** the power ½, one marked constant like the fire film curve's `FOLLOW`. Full adaptation turns the
  day black (mock round 2), none shows nothing (`main`). Its weight follows the sky's ambient light (`FarView.ambient`),
  1 by day and 0 at night.
- **Earlier complaint (18.10):** "in the first seconds the second sun gives sharp shadows across the entire map". The
  fix gave faces turned away scattered and ground-reflected light, so the world is white at the peak and a face toward
  the ball is 5–6 times a side face by 2 s (`MushroomGlslTest.ballLightsWhatFacesAway`). 4.1 keeps that law: faces
  turned away get the same scattered light × r^−½. A new screen-level check holds the toward/away ratio on screen to
  the test's at its geometry (15 kt, 4 km). The old test checks the model, not the screen.
- **Wilson shell:** dimming its sun and sky light with the sky would keep it as dark against the sky as now; only its
  ball term can lift it. The exposure factor applies before `mushBallCap`, so the cap still holds the shell's ball
  light under the ball's on-screen value. The shell lightens against the dimmed sky without merging into the ball
  (the fix of "the shell merged with the sphere as a white disk at 0.5–1 s"). A check holds that the shell never
  merges with the ball at 0.3–1.5 s for 1, 15 and 250 kt.
- **Where:** `fx_pall_composite` (`relit`, the sky pixels), `fx_pall` and the dust pass (matter light), `NukeLight`
  (the ratio and its weight), the hand, `FarView.exposure`.

### 4.2 The eye: flash blindness drawn over the picture, recovering at an eye's pace (physics: bleaching; its look is presentation)
- **Cause:** bleached photopigments (G&D §12.83). The dose at the eye is already computed (`NukeLight.sample`: distance,
  line of sight, openings, weather), and so is its recovery time (`EyeInjury.dazzleSeconds`: 10 s at the edge of the
  flash-blindness range, up to 60 s by day and 600 s at night).
- **One eye state, an overlay only.** A bleached share B from that dose, fading by that law, drives one GUI post pass
  over the finished picture. It never lowers the frame's exposure: an exposure tail would hold the world dark while the
  ball's light falls fifty times within a second, which is how mock round 3 darkened the night. The pass draws:
  - a strong afterimage where the ball was on the retina: white-yellow first, then a coloured negative (Feynman's
    purple). It is larger than the ball, follows the gaze, and grows from the existing retina spot;
  - lifted blacks, lower contrast and colour, each at most a bounded share of B: Artem's "pale screen". The world
    always stays visible through it;
  - the bleached eye's own glow first: a white clearing that holds for 0.5 % of the dazzle time (at most 0.3 s) and
    fades over 0.7 s. Facing the ball (its image on the retina) at full strength; turned away, from scattered light
    alone, a third. The positive afterimage of an intense flash lasts seconds, and the spot carries that.
  - In the sandbox the full view could take far less than first planned. A 2 s near-white hold with desaturation 0.75,
    and then a 0.6 s hold with desaturation 0.35 and a night dimming of 0.4, turned the frames of the second sun into a
    pale card by day and killed the orange-lit night; a desaturation of 0.15 with a dimming of 0.25 still turned the
    night brownish grey. The full-view part is now a light desaturation by day (≤ 0.15) and the white clearing; at night
    only the clearing, so the orange-lit night keeps its colour. The afterimage spot, local and colour-shifting, carries
    the long dazzle.
  - The film's white-out is overexposed film and is not a target (Artem, 27.11).
  - Near-white seconds for 15 kt (lift ≥ 0.4) after the pulse maximum: about 0.6 s facing the ball at 2 km and 0.4 s at
    15 km; turned away, none beyond the white frame itself.
- **It replaces** the uniform grey or black fill at 0.9 opacity (`NukeFlash.veil`), which erased the picture (mock
  round 1). The white frame of the flash itself keeps its present 0.2 s camera catch-up, so the end of the white is
  carried by the afterimage and the pale view, never by a cut.
- **Creative players get it too.** Flash blindness is perception, not injury: a creative player cannot be hurt, but
  has eyes. Spectators and replay viewers stay a camera, without it. Permanent retinal burns stay survival-only.
- **Night:** the long night dose (up to 600 s at the full bleach) applies in creative as in survival: the white clearing
  and the afterimage, whose own time (5–30 s) is the existing retina-spot law. The `flash` client setting and calm mode
  scale or disable it, as they do every flash path today.
- **Where:** `NukeFlash` (the veil as a post pass shaped like `Concussion`'s `PostChain`; `eyes`).

### 4.3 Radiance: a glow around the ball, as bright as the ball is (physics: eye scatter and lit air)
- **Cause:** the ball's true brightness against E_f. Anchored to the ball and the eye. It ends as the ball cools: the
  glow shrinks with brightness, as on the Trinity film (ring at 2 radii 0.3 of the ball at the brightest, 0.02 late).
- **Change 1, the eye's veiling glare.** It is sized by the ball's real light at the eye × E_f, with the
  Stiles–Holladay tail (10·E/θ², falling faster beyond 30°) instead of the present 10° cap. It is drawn only outside
  the ball's disc: the existing rule of the bloom pass, no glow over the glowing body. So the ball's own pixels show
  the ball and its pattern (`NukeExposureTest.fireballKeepsItsPatternFromOneToFiveSeconds`). Its peak stays below the
  ball's on-screen value, with the half-step noise the bloom and the smoke veil use against 8-bit rings.
- **Change 2, lit air (aureole).** The air along each view ray scatters the ball's light forward (G&D §7.19), with
  Henyey–Greenstein in the existing `Sight.Air` haze. It is brightest beside the ball and spread over the whole sky:
  the glowing haze around the ball on the Priscilla film. An analytic term like the present `Haze` extinction: no
  march.
- **Change 3, the eye's ciliary corona.** Faint radial streaks around the brightest light: the eye's own optics, never
  a camera's hexagonal flare. A shape for the existing glare quad, only while the ball outshines everything else.
- **Halation** (the nuclear ball in the bloom pass) is not needed: changes 1 and 2 carry the glow. It would need a new
  emission write from `fx_pall` into the bloom target.

### 4.4 Later: shafts of light through clouds, smoke and dust (physics)
Where a cloud, the Wilson cloud, smoke, dust or terrain stands between the ball and a stretch of air, that stretch does
not glow: crepuscular rays, as the sun makes. The pall's haze has a short march to carry it. Shafts behind vanilla
clouds or terrain in clear air need new march steps, a new cost.

### 4.5 Later: shadows of the second sun (physics)
Status: not shipped. A screen-space march over the frame's depth was built and taken out in review: a caster off
screen casts nothing, so a shadow came and went as the head turned (concept 2: the same in every view). The world-
anchored parts below are the follow-up.

- **Cause:** direct light travels in straight lines from the ball (G&D §7.18). Anchored to the ground and to whatever
  casts the shadow.
- **Only the direct beam is shadowed.** The scattered fill of the 18.10 law stays, so the shadows are soft-filled,
  never black, within the screen-level ratio of 4.1.
- **One phenomenon with the scorch.** The server already computes this burst's shadows for the flash marks
  (`FlashShadows`, `FlashMarks`), so the live shadow falls where the scorch silhouette is left:
  - within the flash-mark range, the marks' masks drive the shadow on marked faces;
  - beyond it, a march over a fine client height map toward the ball (the pall's map is too coarse for houses);
  - near detail would need a world-anchored source too: a screen-space ray changes with the view (removed, above).

### 4.6 Later: water, the second sun's glitter path (physics)
Over water the ball's reflection draws a glitter path to the eye, as the sun does: white at the flash, orange as the
ball cools. The release-candidate take shows only a peach tint on the water. The path is a term in the water decal
(`fx/layer/WaterDecal`), with the sun glitter's wave-slope spread (Cox–Munk).

### 4.7 Order and time (kept)
The flash, the light, the heat and the fires come first, in silence. The sirens fade and the animals go quiet
(`AnimalSilence`). Then the wave arrives at its own speed with the ground jolt, the body hit, the roar, the dulled
hearing and the concussion blur, all of which exist (`BodySway`, `CameraShake.ground`, `Deafness`, `Concussion`). The
silence between flash and wave is the most frightening part of every account (Spindel: "the most intimidating
minute"). Nothing is added to it.

### 4.8 Conditions
- **Yields:** 1 kt flashes and fades within about half a second. 1 Mt's pulse lasts ~9 s (10·t_max), so its day
  stays lit and glowing about that long, which is what that yield does. The 250 kt rollup case
  (`NukeExposureTest.bigCapsDoNotBlowTheFrameAgainAtRollup`) is re-checked under the new glow.
- **Shader pack:** the relit path's neon foliage under the pack gets more weight under 4.1. The acceptance runs under
  the pack in Artem's instance: Photon at the time of this proposal.
- **Multiplayer and server:** nothing changes on the server, and no packet or protocol changes. Each player's light,
  eyes and dazzle follow their own position, gaze and game mode, as now.
- **Settings:** the `flash` client setting (0 disables) and calm mode govern every new term. No new setting.
- **Player guide:** creative players now get flash blindness, and the glow changes the look. Both languages are
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
| 4.1 one exposure | multiplies in passes that already run, plus a hand pass | by day, while the ball shines |
| 4.2 eye overlay | one GUI post pass, ~0.1 ms (est., the shape of `Concussion`) | while the eyes recover |
| 4.3 veil, aureole, corona | terms in the composite pass and the glare quad's shape | while the ball shines |
| 4.4 shafts | new march steps through clear air | while the ball shines |
| 4.5 shadows (follow-up) | flash-mark masks and a height-map march; not built | about 10 s per burst |
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
- **Day look gate.** At 2 and 5 km, 0.75–2 s after the burst, measured inside the picture as on the films:
  - glow: the ring at 2 ball radii (radius from the model's projected cap) is 1.3–2.0 times the sky beside it
    (Priscilla 1.4–1.75; `main` 1.04). The ring against the ball is not a day gate: by day it is dominated by the sky
    (Priscilla ~0.5, `main` 0.65–0.73); Trinity's 0.15–0.33 is a night film.
  - Shipped head, 2 km by day, 1–2 s, 1080p GPU takes:
    - ring/sky 1.39–1.49 (`main` 1.08–1.24);
    - ring/ball 0.59–0.80;
    - ball pattern variation 0.14–0.16 (`main` 0.10–0.11);
    - contrast in the light pulse 1.9–2.1 (`main` 8.2–8.9), back to 7.5 by 3 s.
  - the ball's pattern: luminance variation inside the disc stays at `main`'s level;
  - the gate passes the reference frames and fails `main`.
- **Night is unchanged:** ground, sky and ring within `main`'s ranges (section 3).
- **Earlier complaints keep their tests:**
  - the ball's pattern at 1–5 s, the rolled cap at night through haze, no re-blown frame at rollup for big yields
    (`NukeExposureTest`);
  - the vanilla sky-flash sawtooth (`NukeFlashSkyTest`);
  - the 18.10 ratio on screen (4.1, new);
  - the shell never merging with the ball (4.1, new).
- **The whole first minute in the matrix:** 1 and 15 kt and 1 Mt; day and night; land and water; 2, 5 and 15 km;
  under the host's shader pack.
