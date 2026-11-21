# Dust wall: acceptance scene and checks (binding, 21.11.2026)

Agreed by the lead after Artem's 13:42 Warsaw request that both developers agree on how it must be checked.
Source of the scene: SPEC.md line 5-6 (variant 6, artifact v16) and Artem's Silo frames.

## Judging scene (every look judgment, every take that decides a merge)
- 1 Mt surface burst, wave speed 0.35 (game default).
- Camera 120 m above forest, 4.5 km from ground zero (~5 psi), pitch -6°, ground zero ~27° left of centre (variant 6
  camera). The first frame is checked against silo-texture-c (horizon, ground-zero position) before the rest is shot.
- Low sun / golden light as in the Silo frames (reference/silo-texture-a/b/c); a noon take is extra, never the judge.
- Times: 1, 2, 4, 6, 8 s after the front passes the camera's ground point; plus a 30 fps clip of the whole passage.
- Shown side by side with: reference/silo-texture-c-2026-11-21.png and the variant 6 animation frame at the same time.
- Secondary scenes (do not decide the look, only "nothing broken"): 600 m/250 m noon, far 4.5 km+ terrain view, shader pack.

## Artem's five points (all must hold in the judging scene)
1. No repeating pattern of same-size lumps; variety only in large shape (top rises/dips tens-hundreds of metres).
2. No dark specks or holes.
3. One continuous mass of near-uniform warm colour; no separate bands with sharp transitions.
4. Soft edges: no pixel-stepped silhouette, haze fades gradually, no hard straight haze boundary.
5. No shimmer or flicker in motion.

Goal (Artem 21.11 13:47, above every number): a small hump, the wave rolls on and settles right away, and the mass
reads as real dust, not liquid metal, water or anything else.
Material reference (Artem 13:48): a dust storm (haboob) front, but lower and denser, and born in place: the shock's
post-front wind (~75 m/s at 5 psi) tears dust off the ground exactly where the front passes, not carried from afar.
"Like dust": matte and semi-transparent; ragged, tufted top and edges; no sheen or smooth liquid surface; behind the
front it visibly settles and thins. The reviewer answers this by eye first, then looks at the numbers.
The thresholds below are guides, not gates: if a number and the goal disagree, the goal and the eye win; record why
in the PR and keep going. Never block on a threshold.

Numeric thresholds (measured in the judging scene; the eye still decides):
1. Dominant pattern wavelength within Silo's 142-256 px.
2. Near-black voids <= 0.1 % of the frame.
3. Head vs body hue/value difference and seam edge sharpness no larger than Silo's.
4. Large/small energy 1.9-2.4; edge p99 0.013-0.017 (Silo).
5. Fine change per second at or below the calm baseline; no sub-pixel streaks.

## Order
One instrumented take in the judging scene records every term for all five points at once -> one defect list with
number and cause -> one model fix -> confirming take -> frames to Artem -> scene bench -> merge.
