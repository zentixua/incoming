# Salvo frame cost: 8 Shaheds at once, camera at 50 m

Review request: measure the frame cost of a salvo against main. Dev sandbox, native GPU, no Iris, quiet power profile,
VSync off with a 60 FPS cap (the same dev-only capture patch as `../capture-settings.patch`). `raid 8 15 0`: eight
ground-hit Shahed blast packets in the same tick within 15 m of the point (fixed layout, `Random(7)`), camera 50 m
south, 1.62 m high. Main is `195633b0`; "before" is the PR head `c563e0a7`; "after" is `87ef5198`.

| | main | before | after |
|---|---:|---:|---:|
| Birth-tick frame gap, ms (6 runs; runs 3–6 after warm-up) | 134 94 82 77 75 75 | 198 119 122 125 152 122 | 87 75 77 80 78 81 |
| Mean frame interval, first 0.2 s, ms (same runs) | 36 25 23 22 22 22 | 59 46 49 49 55 56 | 30 22 24 24 24 25 |
| FPS over the second ending at 1 s (2 runs) | 55, 56 | 44, 52 | 51, 56 |
| Effects layer CPU per frame, first second, ms (2 runs) | 3.8, 2.8 | 16.2, 14.7 | 6.7, 4.9 |
| Effects layer CPU per frame at 3–6 s, ms | 1.5–1.8 | 8.5–13.7 | 3.1–3.8 |
| Layer quads at 0.5 s | 4554 | 8380 | 5513 |

Causes found by measurement (CPU bench and a run with the dust emission switched off: layer CPU then equals main):

- `FrontDust` gave each blast 864 puffs (24 × 64 polar grid; 2.2 m across the ring, 5.6 m along it), and
  `SmokePuffs.merge` merges a source's puffs pairwise every frame (~0.9 ms per blast), for 20 s although the puffs are
  culled after ~8 s. Now: one 5.6 m step both ways (σ = 0.6 step), at most 400 patches per blast and 800 visible over all
  blasts (a new blast takes what is free, at least a quarter), and a patch fainter than what `SmokePuffs` draws emits
  an empty puff; the source ends with the last visible patch (~10 s). Merge per blast: 0.11–0.14 ms.
- `Fragments` scanned all 1200 live pieces for the farthest on every new piece once full; a salvo spawned 8 × 900 grass
  clumps in one tick. Now the farthest-first order is sorted once per tick, and a new source asks for places with
  `Fragments.share` (free places, at least a quarter of the cap). Same eviction rule: a nearer piece replaces the farthest.

`single-*-new-grid.jpg`: the single-blast look with the new grid (same as revision 3). `salvo-8-at-50m.jpg`: the salvo.
