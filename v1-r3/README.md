# PR 426, review round 3

Sandbox under Iris 1.8.14 + Complementary Unbound r5.9.3, quiet laptop power profile; feature commit 91119c43
(tree identical to the tested e864c6e9, main 195633b0). Each capture folder has its frames, capture.json (requested
time, actual age, camera) and a WAV where one was recorded. Motion folders (bomb, missile, night missile) are JPEG q94
to keep the evidence small; static frames are the original PNGs.

Sheets (`drone50`, `night50`, `missile50`, `bomb50`, `bomb100`, `drone150`): archived 3.4.1 (other terrain and framing,
from FX-frames), main (round-1 capture of the main runtime), round 2 (archived), round 3 (this round), at 0.1/0.5/1/3 s.
Client-only blasts use seed 1; bombs are chosen surface detonations ("real bunker") at fresh sites with server seeds.

Animations: `bomb-motion.webp` 0.05–4.60 s and `missile-motion.webp` 0.05–4.00 s, every 0.05 s; `*-strip.jpg` are the
same frames; `*-burn-end.jpg` zoom on the end of burning (missile 2.66 s, bomb 3.06 s); `missile-night-cooling.jpg`
shows the night cooling 2.40–4.20 s. `atlas-sheet.jpg` is the packed 48-frame atlas over sky; `atlas-blend.jpg` shows,
for seven frame pairs, frame k / plain crossfade / motion-vector blend / frame k+1 at the midpoint.
No sound code or assets change in V1; WAVs are unmodified recordings.
