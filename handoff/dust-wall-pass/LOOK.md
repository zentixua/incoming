# Dust wall: what it must look like (Artem, 21.11.2026, 17:30–17:46 Warsaw)

This page replaces SPEC.md's element list as the look target. The variant 6 animation
(reference-animation-v16.html) is a sketch of the agreed look, not a spec: port its method where useful, never its defects
or its unimportant details. ACCEPTANCE.md's judging scene and order stay.

## The look (his words, condensed)
1. The front tears dust up from the ground exactly where it touches the ground: the dust is visibly born at the contact
   line, not appearing ready-made.
2. It curls over a small hump: hump 1.3–1.5x the height of the layer behind it, uneven along the front.
3. It settles right behind the hump, in about 1 s or a little less (supersedes SPEC.md item 3's 0.6–4.5 s sag).
4. The dust reads as one natural mass, like real dust (a haboob front, lower and denser): billows, uneven, no repeating
   pattern, no holes, no rippling, not liquid metal, not water.

## Physics vs presentation
- Physics (mod laws, unchanged): where and when dust is born, how much, layer height, what falls and what stays, wind drift.
  Coarse dust falls; fines stay as a low haze that thins slowly and drifts with the wind. Billowing is violent in the first
  seconds while the post-shock wind is strong, then calms with it.
- Presentation (judged by eye against references): billow shape, light, texture, the hump height above.

## Settings he chose
- Judging view: as in Silo, 4.5 km from ground zero, 120 m above ground, low sun. Other views only "nothing broken".
- Colour by light: warm amber from the fireball at dusk as in Silo; by day grey-brown in the ground's colour.
- Frame cost: one wall may cost up to 20 FPS on his PC (RTX 4060) at the highest preset; lower presets for weaker PCs;
  the 100-strike bench limit still holds.
- References: Silo frame (../reference/silo-texture-*.png, Artem's complaint frames) for the scene; haboob footage for the
  dust material.

## Process (fixed; this is the guarantee)
1. Two in-game trials in parallel, 3–4 h each: (A) a dedicated dust-wall volume pass using the animation's method
   (dust top heightfield + density, ray-marched), not the shared pall renderer; (B) soft particles with real-dust
   flipbook textures, born at the contact line, hump, settle.
2. Two short clips in the judging view beside the Silo frame go to Artem; he picks one.
3. Only the picked one is finished; a clip to Artem every 2–3 h; fix only what he names.
4. Frame cost, review, merge. Nothing is merged that does not look like the clip he approved.
