# WT2 evidence (PR #445) — not for merge

Sandbox (`tools/sandbox.sh`, Iris + the host's shaderpack), same commands on both rows:

- grad-day: `raid 12 15 1 rocket water`, `--cam '30 14 0 6'` (12 Grad shells into the pond within 15 m, 1 s apart; camera 30 m away, 14 m up), frames at 3/6/12/20 s from the first blast.
- water-day-50: `blast drone 1 water`, `--cam '50 18 0 8'`, frames at 0.1/1/3/10/30 s.

Top row `main` 46135bf3f (mod sources of origin/main with #444, same sandbox), bottom row the PR head dfe499529 (cone from the whole-flight envelope, review round 2). Full frames in `frames/`.
Layer timing from the `SANDBOX shot` lines (62 fps, vsync), salvo 3/6/12/20 s: main CPU 1.12/1.20/1.11/0.34 ms, GPU 1.34/2.85/3.18/0.93 ms; PR CPU 1.19/1.50/1.53/0.21 ms, GPU 1.13/2.23/2.42/0.97 ms.

Other users of segment puffs (now merged/budgeted, each with its own frame): `launch missile` (`--cam '60 4 90 6'`, launch ground dust row, 0.5/1.5/3 s) — `launch.jpg`; `nuke 15 near` (`--cam '60 15 0 5'`, debris-scene dust streaks, 0.1/1/3 s) — `nuke-near.jpg`. Top main, bottom the PR head.
