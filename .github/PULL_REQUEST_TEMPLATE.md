## Goal
<!-- What changes for the player, in one or two sentences. Which visible thing as a whole owns the result (a blast from
flash to dispersal, a launch, a flight), and whose note to check against if this PR changes only part of it. -->

## Записка
<!-- Required when the PR changes what the player sees or hears (check «Записка», paths in tools/pr_note.py).
Written before the code. A change that fits in one sentence gets one line here.
- Law: which law is used. First one that already exists in the mod (nuclear/model, client/fx/smoke, guidance, sound …);
  a new one only when there is none, with the reason and the source.
- Where it holds: time (first fractions of a second, seconds, minutes), scale (charge, distance), conditions (ground,
  air, water, night, rain). An empirical formula for the end state does not describe the early moments.
- What is seen and heard at the key moments (a blast: 0.1 s, 1 s, 10 s, 60 s; a launch: lift-off, 1 s, departure)
  and under the conditions above: shape, colour, size, brightness, loudness. These lines are what the frames are
  checked against. -->

## What changed
<!-- Point by point, from the cause. -->

## Invariants and tests
<!-- What in the note is a number is a unit test (the law is not called outside its range, nothing at the ground
without ground, nothing ahead of the front, no jumps in brightness …). A promise in a comment or the guide is held by
a test. -->

## Check
<!-- CI and an independent review of the code against the note. Seen or heard by the player: frames and sound of the
client scenario at the note's moments next to the last accepted release, looked at once, not by the author. -->
