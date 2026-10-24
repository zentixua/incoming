# NK, review 1 (Codex): rolled-up cap back to the old compositing

Same conditions as `../README.md` (1.38 km, near mode, same wind and seed as the main frames). Shader at 0eefe174:
the film curve only while the ball is a ball (ring share 0.15 → 0.45, ~5 → 7 s at 15 kt), then the old pall
compositing; the finished pictures are blended in between.

- `late-1380.jpg` — 3 / 5 / 7 / 10 s, day and night: main, NK before the fix (curve for all, `../frames/nk/`), NK fixed
  (`frames/`).
- `late-diff.txt` — mean |difference| from main in the cap region (top centre) and over the frame: night 10 s 0.186 →
  0.008, night 7 s 0.091 → 0.002, day 10 s 0.040 → 0.009 (run-to-run noise of identical code is of this order); the
  in-ball spread at 1 and 3 s is kept (day 0.045 and 0.351).
