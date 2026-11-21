# Dust wall: agreed reference look (variant 6)

Reference animation: https://claude.ai/artifact/2qPu5ng6xLVk8txeeLMaK9 (top stage "variant 6", artifact version 16,
id 1795215857-118d). Shader source: reference-animation-v16.html (functions `tops`, `dens`, `wisps`, march in `main`;
knobs `uPhys=1, uTurb=1.0, uWisp=1.0, uRise=0.4, uHump=1, uSurge=1, uExtra=1, uSize=1, uSpd=1`). Scene: 1 Mt surface
burst, camera 120 m above forest at 4.5 km (~5 psi, post-shock wind ~75 m/s). Wave speed: 0.35 (game default, unchanged).
Artem's texture reference: ../reference/silo-texture-a/b-2026-11-20.png (fibrous streaks along the wind, lumpy top).

Laws stay the mod's (lofting under the front, Kuhl layer height, size bins, fines haze, Hiroshima-calibrated mass).
Look to reach, by element ("pres." = marked presentation):

1. Birth: dust lofts at each point when the front passes it; rise time constant 0.4 s; layer ~58 m at the 5 psi ring.
2. Head (hump, pres.): +38 % above the layer, held ~0.75 s after passage, then decays with e-fold 0.8 s.
3. Sag: dense core loses ~50 % of its height between 0.6 s and 4.5 s after passage (~7 m/s, gravel fall speed).
4. Haze: fines haze stays over the core and grows slowly (+20 % in ~12 s), kept below a 120 m camera; thinner than
   the core (pres.: haze opacity lowered so the lumps stay visible).
5. Unevenness: head height +-30 % along the front, +-45 % 1-5 s behind it; soft lumps ~170 m, carried by the eddies.
6. Live turbulence: eddy strength = decaying wind energy (e-fold 8 s, floor 30 %) + violent first seconds (e-fold 4 s);
   phase = integral of energy. Eddies 32x20 m (warp, amplitude 55/38 m), texture 20x13 m fbm; turnover slow: warp phase
   rate 0.15, texture scroll 0.9 x phase; violent term 3.5 x (1 - e^-a/4). Big rolls drift, never boil.
7. Streaks: fibrous texture stretched along the radial wind, ~28 m along x 4.5 m across x 5 m tall, modulating density
   (0.45 + 1.1 s) and fraying the top edge.
8. Bulk slosh: outward, then back with the negative phase; top moves more (rolls). ~18 m e-fold 3 s + slow 6 m drift.
9. Wisps: half of 60 m cells release a rising wisp 0.6-9 s after passage, 3-7 m/s, fading over ~14 s.
10. Light (variant 6): dust shadow on the ground toward the fireball; backlit rim on thin edges toward the fireball;
    light smoke ahead of the front from the flash: use the mod's existing pre-wave pyrolysis smoke, no duplicate.

Rendering requirements (these caused the "boiling" in the reference and must hold in the mod):
- Samples along each ray sit on a world-fixed grid (not anchored to the front circle, march start or span): the
  pattern must not slide through noise as the front grows. Check: frame-to-frame change after best local shift
  (morph metric, scratchpad morph.py idea) on the far front at 0.35 and at real speed must stay well below the static-texture change.
- Texture is fixed in the air mass: the rising/settling top reveals it, it is not scrolled through the edge.
- Filter only detail finer than a pixel near (< ~0.8 km); far (> ~2.5 km) also detail finer than ~1/3 of the step.
  Never blur visible near detail (v12-v14 lesson: "smooth like a baby's bottom").

Artem: APPROVED 21.11.2026 00:31 Warsaw (thread message cmsg_01U1E42ZxayBQ8GE6s1yrHaqBSYeGfZJz7KCYgNXTdz1MV): variant 6, version 16, wave speed default stays 0.35 (he may later move it to 0.40-0.50 before release; not now).
