# Mission dust-wall-pass-2, phase 2: finish probe A (Incoming, next release)

Look target: LOOK.md (binding), scene and checks: ACCEPTANCE.md. Phase 1 report: mod/run/agents/dust-wall-pass/report.md,
judge.md, judge-r2.md; probe A on PR #724, branch dust-wall-pass; probe B (dust-wall-p1b) is dropped.

## Artem's verdict on the phase 1 clips (21.11 21:56 Warsaw, his words condensed)
- B flickers terribly: "ultra-boiling liquid", jittery. Dropped.
- A is the best. Fix in A:
  1. No hump visible: the small hump (1.3-1.5x the layer behind it) must be there and read in the judging view.
  2. Still flickers, much less than B but visibly: make it consistent in time.
  3. Behave like real dust from life: move at real dust's speed, with real dust's properties; uneven, small bulges and
     dips, not uniform.
  4. Colour needs fixing (follows the light on ground-coloured dust, LOOK.md; no near-black patches under the front:
     phase 1 measured up to 4.2 % of the frame).

## Decisions already taken (Artem, 21.11)
- One presentation model for the young wall; the look wins; physics only as visible inputs; anything that fights the
  look is removed, not reconciled (AGENTS.md "never glue").
- Handoff to the old pall is a visual crossfade only; if the seam shows, the new model owns the whole visible pall in
  the judging view, including its height (pall rising to ~157 m above the 120 m camera from ~7 s: a presentation height
  marked "presentation, not physical" in code and guide is allowed).
- Judging view: Silo (4.5 km, 120 m, low sun). Add a second view looking back over the passed wall for settling.
- Frame cost: 7 ms for the wall at the highest preset (scene base 61 FPS); A measured 11-19 ms after passage. Must fit.

## How (lessons that bind)
- Flicker is a time problem: fix it in time (world-fixed sample grid, texture fixed in the air, slower change rates,
  temporal stability), never by blurring visible spatial detail (the sketch's "smooth like a baby's bottom" failure).
  Measure frame-to-frame change after best local shift and show it falling before showing a clip.
- Real dust motion: take speeds and billow evolution from haboob footage (front advance, billow roll rate, settle);
  state the numbers used in the PR.
- Fast harness first (seconds per try), game takes only to confirm.

## Work
1. Continue on PR #724 (number in `PR`).
2. Every 2-3 h: frames at 1, 2, 4, 6, 8, 12 s and a 30 fps clip in both views beside silo-texture-c and the phase 1 A
   clip at the same times, frame cost; an independent fresh-session check by eye against LOOK.md and Artem's four points
   above; only a "yes" clip is published (private claude.ai artifact by the laptop lead) for Artem.
3. Stop rule: 3 rounds without a measured shift toward the look, or the timebox → stop, root cause from data, decide.
4. Merge only when Artem approves a clip in words, the wall fits 7 ms, the 100-strike bench is in budget and the
   cross-vendor review has no serious finding. Guide (EN+RU) notes the presentation model.

## Limits
No release, servers, or Artem's game/instances/settings. Do not reuse #707's path.

Budget: time 12h, cpu 12h, gpu 4h
