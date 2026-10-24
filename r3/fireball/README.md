# Fireball frames with the mip-level inset

Main `3d45467f` (top) and the PR head `7396552d` (bottom), one Shahed, camera at 100 and 300 m, same sandbox fixture.
From 0.3 s the fireball matches main. At ~0.1 s the PR frame is one tick younger: the birth-clock fix of revision 2
(main ages an ordinary effect by a tick in its birth tick), not the inset. At these sizes the inset is 1–2 sheet
texels at the transparent frame edge.
