---
name: look
description: "Any work on how an effect looks, moves or sounds: a new effect, a look complaint from Artem or a player, matching reference footage, building an approved animation or prototype. Fix the agreed traits and the mechanism before code, choose the technique by probe clips, judge clips in motion by number and by eye."
---

# Look work: one agreed target, the right mechanism, judged in motion

A look converges when the target is written once and inherited, the model draws the phenomenon's mechanism (not its
finished shape), the technique can resolve the detail, and every clip is judged in motion the same way as the
reference. Days go when a model draws a shape that moves where the real thing is born in place, when an approved
sketch is ported as the specification, or when tuning continues inside a renderer that cannot draw the target.
Once the mechanism is right, the rest takes hours.

## 1. Inherit and fix the target (plan, before code)
- Collect what is already agreed about the phenomenon: its row in the design document (`docs/DESIGN-nuke.md` for the
  nuke), its area rule, the tests that guard earlier complaints, and Artem's words in earlier briefs and lead notes.
  Write them into the plan as one numbered trait list. Artem's later words amend that list (newest wins); they never
  live only in a side note.
- A new model of the same phenomenon keeps every earlier complaint's check: port the test to the new model.
- An approved animation, sketch or prototype is a sketch. Write down which of its traits Artem approved and build
  those; never port it one to one (its sky, light, resolution and shortcuts are not the target).
- Reference: real footage first, then film VFX that stays plausible; never another renderer's look, such as a shader
  pack's clouds. Judge in vanilla (AGENTS.md "Vanilla first").
- Reproduce the reference scene before any look change: distance, camera height, framing, time of day, matched on
  the reference frame (horizon row, subject position), one frame beside the reference in the PR.
- A player's screenshot: measure on the original, name the object by what it is ("the wave's dark band", not "the
  edge"), quote the player's words beside any marked copy.

## 2. Mechanism before shape
For each visible feature, one row in the plan: its cause; where and when it is born; what it is anchored to (ground,
air, the moving front, the camera); how fast its matter moves against how fast its pattern moves; how it ends. Take the
rows from the footage (track features frame by frame; a top-down or side probe in the harness) and from the law.
- A moving front is usually a moving line of births, not moving matter: features are born where the front passes and
  stay there; nothing rides with the front (AGENTS.md concept 2: nothing appears finished). Each point needs its own
  birth (delay, amount, height from its own ground): one age profile shared by every point is a rigid shape moving at
  the front's speed, whatever its texture does.
- A shape number (height, hump ratio, contrast, lobe spacing) checks the mechanism; it is never the model.
- Before the first take, state as numbers the feature speeds the model produces against those rows (for the young
  dust wall: radial speed near the ground tens of m/s, never the front's 470 m/s; no net turning around ground zero).

## 3. Technique: existing system, then probes, chosen by clip
- Name what in the mod or the community already draws the nearest phenomenon and state its ray step, resolution and
  temporal filtering against the feature size and change rate. A low-pass renderer (half resolution, coarse steps,
  per-frame jitter averaged by history) cannot draw 15 m billows; it blurs them or turns them into grain.
- When it cannot, probe two techniques in parallel in game, each within a stated timebox (at most 4 h), each to a
  30 fps clip in the judging view with its frame cost; the owner picks by clip and records why in the PR.
- The drawing takes where, when and how much from the law; physics, sound and gameplay read the law, never the
  drawing. A law and look corrections on one model are glue (AGENTS.md).
- Iterate in a shader harness that runs the mod's shader include verbatim (moderngl or Mesa, as `tools/dust_wall_gl.py`
  and `tools/see_through_gl.py` do): seconds per try. A game take confirms; it is not the loop.

## 4. Judge in motion
- Each round: frames at fixed times and a 30 fps clip in the judging view, beside the reference and the last version
  Artem saw at the same times. Check `capture.json` ages: the clip plays in real time.
- Measure our take and the reference footage with the same tool: `tools/clip_metrics.py change` (detail change per
  0.1 s), `flicker` (frame-to-frame pulsing), `period --edge` (copied pieces, staircases), plus feature speeds from
  the probe. Before any look gate, run it on the reference: it must pass the reference and fail `main` on the
  reported defect.
- A fresh Claude session that built neither judges the clip at full rate against the numbered traits, one verdict per
  trait, flicker rated explicitly; it gets the clips, the reference and the traits, never the author's notes. Stills
  never pass motion. Codex reviews the code only.
- A shader pack gets one "not broken" check on the last head.

## 5. Rounds and stop
- One run per round answers all of its questions (deep-debug skill): probe channels for every suspect term in one
  instrumented take, never one guessed toggle per take.
- Three rounds or the timebox without a measured shift toward the traits: stop and name the cause by number (AGENTS.md).
  The mechanism table is the first suspect: does any feature move where the real one stays?
