---
name: look
description: "Any work on how an effect looks, moves or sounds: a new effect, a look or sound complaint from Artem or a player, matching reference footage or recordings, building an approved animation or prototype. Fix the agreed traits and the mechanism before code, choose the technique by probe clips, judge clips and WAVs in motion by number and by ear and eye."
---

# Look work: one agreed target, the right mechanism, judged in motion

A look converges when the target is written once and inherited, the model draws the phenomenon's mechanism (not its
finished shape), the technique can resolve the detail, and every take is judged in motion the same way as the
reference. Days go when a model draws a shape that moves where the real thing is born in place, when an approved
sketch is ported as the specification, or when tuning continues inside a renderer that cannot draw the target.
Once the mechanism is right, the rest takes hours.

**Small change** (a colour, size, level or timing tweak of an accepted look, nothing new): frames or WAV beside `main`
and the reference at the same times, one fresh judge, done. The full procedure is for a new effect, a complaint, or a
new model of a phenomenon.

## 1. Inherit and fix the target (plan, before code)
- Collect what is already agreed about the phenomenon from its homes: its row in the design document
  (`docs/DESIGN-nuke.md` for the nuke), its area rule, the tests that guard earlier complaints, and Artem's own words
  quoted with their time. Approved look traits are decisions, not older items to drop; a lead's paraphrase is never a
  trait. Write them into the plan as one numbered trait list.
- Artem's corrections of the agreed outcome amend that list (newest wins); a new view, criterion or feature goes to a
  follow-up PR (AGENTS.md: the player outcome stays fixed).
- A new model of the same phenomenon keeps every earlier complaint's check: port the test to the new model.
- An approved animation, sketch or prototype is a sketch. Write down which of its traits Artem approved and build
  those; never port it one to one (its sky, light, resolution and shortcuts are not the target).
- Reference: real footage or recordings first, then film VFX that stays plausible; never another renderer's look,
  such as a shader pack's clouds. Judge in vanilla (AGENTS.md "Vanilla first"). Reference frames are copyrighted:
  they stay in the mission folder or a private artifact, never in a repository; the PR states the scene match by number.
- Reproduce the reference scene before any look change: distance, camera height, framing, time of day, matched on the
  reference frame (horizon row, subject position).
- A player's screenshot: measure on the original, name the object by what it is ("the wave's dark band", not "the
  edge"), quote the player's words beside any marked copy.
- On merge, the agreed traits go into the phenomenon's home (design row or area rule), each binding one with its test.

## 2. Mechanism before shape
For each visible feature, one row in the plan: its cause; where and when it is born; what it is anchored to (ground,
air, the moving front, the camera); how fast its matter moves against how fast its pattern moves; how it ends. Take the
rows from the footage (track features frame by frame; `clip_metrics.py change` gives their apparent speed up to 8 px
per lag, so shorten `--lag` for fast fronts) and from the law.
- A moving front is usually a moving line of births, not moving matter: features are born where the front passes and
  stay there; nothing rides with the front (AGENTS.md concept 2: nothing appears finished). Each point needs its own
  birth (delay, amount, height from its own ground): one age profile shared by every point is a rigid shape moving at
  the front's speed, whatever its texture does.
- A shape number (height, hump ratio, contrast, lobe spacing) checks the mechanism; it is never the model.
- Every take reports the measured feature speeds against the rows (for the young dust wall: radial speed near the
  ground tens of m/s, never the front's 470 m/s; no net turning around ground zero); a broken row fails the round, and a
  row that holds becomes a test (`DustWallGlslTest`, `tools/dust_wall_gl.py`).

## 3. Technique: existing system, then probes, chosen by clip
- Name what in the mod or the community already draws the nearest phenomenon (`client-fx.md`: lit smoke puffs,
  distance fog inside dust, the pall march, the young dust wall's pass) and state its ray step and resolution against
  the feature size and change rate. Coarse steps over a long span cannot draw 15 m billows (the pall's 28 steps over
  ~12 km); temporal accumulation is fine when the sample grid is world-fixed and the history reprojected and clamped.
- When it cannot, probe two techniques one after the other, each within a stated timebox (at most 4 h), each to a
  30 fps clip in the judging view with its GPU frame cost; the owner picks by clip and records why in the PR.
- The drawing takes where, when and how much from the law; physics, sound and gameplay read the law, never the
  drawing. A law and look corrections on one model are glue (AGENTS.md).
- Iterate in a shader harness that runs the mod's shader include verbatim on Mesa llvmpipe (EGL), as
  `tools/dust_wall_gl.py` and `tools/see_through_gl.py` do: seconds per try. A game take confirms; it is not the loop.

## 4. Judge in motion
- Each round: frames at fixed times and a 30 fps clip in the judging view, beside the reference and the last version
  Artem saw at the same times. Check `capture.json` ages: the clip plays in real time.
- Measure our take and the reference footage with the same tool: `tools/clip_metrics.py change` (detail change per
  0.1 s, apparent speed), `flicker` (frame-to-frame pulsing, `--map`), `period --edge` (copied pieces, staircases).
  Before any look gate, run it on the reference: it must pass the reference and fail `main` on the reported defect.
- A fresh Claude session that built neither judges against the numbered traits, one verdict per trait. It gets
  consecutive full-rate frame strips at the fixed times, the flicker map and the numbers for both sides, the reference
  and the traits, never the author's notes, and rates flicker explicitly. Stills never pass motion. Codex reviews the
  code only.
- Under a shader pack the effect works and looks at least as good (AGENTS.md); one check on the last head, which does
  not replace a pack-specific functional test.

## 5. Sound
- Take: a sandbox WAV (`tools/sandbox.sh shot … --at none`) or a scenario's `audio.wav`, beside `main`'s at the same
  distance and times; the reference is a real recording of the phenomenon at a stated distance.
- Same measure on both, same settings: short-term loudness over time (`ffmpeg -af ebur128`), a spectrogram
  (`sox … spectrogram`), onset and Doppler timing read from them; offline near/far and small-speaker check with
  `tools/audition_sounds.py`. Propagation and reach come from `strike/Hearing`
  and the recording spectra of `tools/build_sounds.py`, never a tuned gain.
- A fresh Claude session judges by the numbers and the waveform and spectrogram images against the traits.

## 6. Rounds and stop
- One run per round answers all of its questions (deep-debug skill): probe channels for every suspect term in one
  instrumented take, never one guessed toggle per take.
- Three rounds or the timebox without a measured shift toward the traits: stop and name the cause by number (AGENTS.md).
  The mechanism table is the first suspect: does any feature move where the real one stays?
