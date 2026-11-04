# Independent review — experimental PR 567

Reviewed `origin/main...40d4bf7b9` in `wt-OL`. The merge base is `659af24a492c277f33a3dd92827542ad49ebbc1c`; reviewed tree is `235764457cd19644ce8690a4ceda450d28ccdd2e`. The owner subsequently moved HEAD to `dd0accf8b`, whose tree is identical. This is a read-only review, with no delegation, production changes, merge, GPU client or desktop control. I did not read the PR description or the author's OL reports/conclusions. The ownership/resource fields of the handoff were checked.

Code verdict: **changes required**. Three actionable correctness regressions are below. Performance acceptance is also **not established**: the completed diagnostic shows a large post-impact regression, with concurrent foreign jobs preventing a clean gate claim. A fixed record/ray count alone does not establish the main-thread/GPU bar.

## Serious findings

### 1. P1 — Apply the fire-to-dust transition to opacity, not dust mass

Location: `mod/src/main/java/ua/zentix/incoming/client/far/SmokePuffs.java:1303-1305`.

The new ground route sends `dust * shown` to the field. The former route retains full optical mass and applies `smokeShare` to opacity. These differ drastically for opaque dust: at an unscaled ray optical depth of 200 and a smoke share of 0.1, the old transition covers 0.1 of the background, whereas the volume covers `1 - exp(-200*0.1) = 0.999999998`. Thus a very small emerging dust share can already hide the fireball, reintroducing the abrupt dark cover/halo that the existing opacity transition prevents. This route also stores no temperature/emission or independent presentation share that could correct that result later.

This affects actual blast material, not only an artificial hot dust source: `CloudModel.blobs` passes ground/crushing material through `inBall` at lines 956-957; that method explicitly gives dust within the fireball its hot temperature so it follows the ball transition. `FarBlasts.Event.emit` now routes both callbacks here. At noon, the existing model gives `shown = 0.1` at 1679.0529 K. The existing `SmokeFillTest.glowShareScalesOpacityNotMass` still tests `blob`, so it does not cover this new route.

Reproduce without a client: use the same 90% glow temperature search as that test, then call a glowing source's `ground` with dust chosen for transverse optical depth 200. Inspect the staged optical mass or integrate the resulting density. Carry the presentation share separately, or retain an opacity-correct path for transitioning material; add a volume-enabled regression that checks the displayed attenuation through the fade, rather than only the sum of stored mass.

### 2. P2 — A second source changes the first layer's height and position

Location: `mod/src/main/java/ua/zentix/incoming/client/fx/smoke/DustGrid.java:98-110`.

The single lattice is refitted to the extrema of every staged source, without a local resolution check. Conserving each cell integral preserves total optical mass but does not preserve where it is rendered. With one cloud at `(0,64.1,0)`, `sh=8`, `sv=0.1`, the vertical cell is 0.01 m. Add a separate cloud 100 m higher and the same cell becomes 1.6766666 m. The first source now deposits essentially its entire vertical profile into one cell, whose linear reconstruction spreads it over roughly 3.35 m, even though that source's actual three-sigma support is only 0.6 m. Its reconstructed peak also moves to that cell's center. A faint positive second source produces the same grid refit; it need not be visibly important.

The standalone probe recompiles the actual `DustGrid` source and prints `cellY=0.01000000` for the first case, then `cellY=1.67666662` for two sources. Evaluating the shader's normalized vertical profile and linear reconstruction for the unchanged first source gives density 7.614 -> 0.405 at y=64.1, and 0 -> 0.432 at y=65.1. This is a discontinuous redistribution when another source enters/leaves preparation selection, including a camera turn between sources on different terrain heights. Horizontal fitting has the same issue for separated strikes. The lighting additionally derives its self-column from the whole field height (`fx_pall.glsl:1455-1458`), so a remote height outlier also changes local shading.

Reproduce with the two `DustGrid.add` calls in `OL2-REV-evidence/DustProbe.java` (the second currently uses only 1e-9 kg to demonstrate that support, not visible importance, controls the result). Keep thin local layers resolved, or use a continuous/local representation or fallback when the shared field cannot resolve them. Add a test that an unchanged source retains its spatial support/density when an independent distant/elevated source appears; table-mass equality alone misses this.

### 3. P2 — The ground route bypasses the source's terrain occlusion

Location: `mod/src/main/java/ua/zentix/incoming/client/far/SmokePuffs.java:1301-1305`.

`Draw.blob` rejects a source hidden by its terrain visibility state (`source.visible(..., ctxVis, ctx)` at line 2806); the new `ground` branch stages its full material without consulting that state. The ground field therefore renders sources the old renderer deliberately hides behind distant terrain. World depth only helps where that terrain has actual depth; it cannot replace the existing ray-derived hiding state beyond client chunks without a LOD depth surface. The source-level bounds/frustum test does not apply that hiding state either.

A client-free probe supplies a source whose two `visible` overloads return zero, calls `SmokePuffs.sink(...).ground`, then `DustGrid.finish`: the resulting field still contains one positive-mass record (`fully hidden source: dust grid count=1`). In game, reproduce with a blast beyond ordinary render distance and behind known higher terrain, without DH/Voxy depth: compare the fully hidden ground channel with sprite fallback. Honor the source's existing hiding contract in the volume route, including a test for fully hidden and partially revealed ground material. Partial visibility must also avoid the mass/opacity mistake in finding 1.

## Optional findings and validation limits

- Main-thread work moved into `DustGrid.add`/`compact`/`finish` before the worker-planned sprite path. Staging/table capacities are fixed, but repeated hash coarsening and layer geometry generation have no cooperative frame deadline or saved-plan reuse. I have not classified a time-budget breach from code shape alone; the paired measurement must establish it. Include the preparation/replay work outside `PallPass.draw`, not only GPU upload time.
- The profile prepass's non-reserved sampler name, shader-object plus GL-program-ID cache, and encoded layer index are internally consistent on inspection. The slice fallback no longer needs `SmokeSlice` because the fragment gets the layer encoded in the vertices. I found no additional definite lifecycle/fallback bug. I did not force a GL compilation failure or resource reload in a client.
- `SmokeFrame.frame` clears `maxRayPixels` on a returned nuclear frame; `NukePall.frame` restores its configured quality and `Detail.ceiling` resets the rung when the setting changes. The legacy nuclear density functions retain their separate path. I found no definite direct nuclear-quality leak in this diff. Mixed nuclear/conventional rendering was not run by this reviewer.
- I inspected raw pairs `ol3-{main,after}-grass/*002.00s.png` and `ol3-{main,after}-raised/*006.00s.png`, then A1/B `100_outside.png` and `100_edge.png` from the completed stand. The after field is visibly smoother and less tan than the sprite layer in these frames. These stills do not establish temporal continuity, mixed-height behavior or the cold/reload/fallback paths. They are not a substitute for the reproductions above, nor an independent approval of the final look.

## Evidence and execution

All reviewer artifacts are local and outside the production worktree:
`/mnt/data/projects/incoming/mod/run/incoming-sessions/OL2-REV-evidence/`.

- `run_probe.py`, `DustProbe.java`, `FarDustProbe.java`: short standalone CPU probes using the existing Java 21/runtime classpath; the affected production sources were compiled into this external evidence directory.
- `dust-probe.txt`: exact vertical fit results from the production implementation.
- `far-dust-probe.txt`: hidden-source routing reproduction and fire/dust opacity calculation.
- `vertical-reconstruction.json`: independent evaluation of the shader profile and linear filtering for finding 2 (not a GPU capture).
- Owned jobs `ol2-rev-probe`, `ol2-rev-route` and `ol2-rev-final-probe` have ended. The first classpath attempts failed before the probes ran; the final probes passed. No build or game suite was launched. A logger's empty temporary file was moved out of the worktree into this evidence folder; tracked and untracked production status is clean.

## Final performance evidence

The full `ol2-first` A1/B/A2 diagnostic has completed: every leg recorded 20/20 steps and 100 confirmed impacts. I independently parsed `frames.csv`, window markers in `events.jsonl`, and `host.jsonl`; I did not use the author's verdict/report. All runs used no shaderpack, 2560×1600, RTX 4060 Laptop, performance power profile. A1/A2 are the baseline jar `8717214b…` (`ci:1aca1654a`); B is candidate `cec5517a…`.

Raw full-frame measurements, milliseconds:

| Window | A1 GPU p50 | B GPU p50 | A2 GPU p50 | A1 render CPU p95 | B render CPU p95 | A2 render CPU p95 |
|---|---:|---:|---:|---:|---:|---:|
| 100-hit | 19.048 | 18.746 | 18.273 | 25.972 | 23.002 | 23.282 |
| 100/outside | 2.327 | 4.060 | 2.306 | 5.762 | 10.682 | 6.361 |
| 100/edge | 2.952 | 5.614 | 3.343 | 8.983 | 11.023 | 5.689 |
| 100/inside | 3.245 | 5.321 | 2.712 | 7.785 | 9.736 | 5.089 |

Post-impact GPU p50 is about 75–79% higher than the midpoint of the two baselines; render-thread CPU p95 is about 50–76% higher. This does not satisfy the requested “no more GPU/main-thread time than sprites” bar. It is a strong reason to simplify or keep the experiment disabled; it is not a clean attribution of every millisecond to one function.

The diagnostic is contaminated and must not be presented as a valid final perf gate: foreign heavy jobs overlap both sides. B's outside/edge/inside windows average approximately 6.64/9.12/4.97 foreign CPU cores; the host log names MT-L2 recovery/hints builds and GameTest. A1 also has foreign jobs, including MT-S2 and LEAD2; A2 still has approximately 1.2–1.5 foreign CPU cores. Some windows have nonzero foreign GPU activity. B's edge window has lower GPU clocks (about 1841 MHz versus 2479/2306), further weakening precise percentage attribution. In contrast, B's outside and inside GPU clocks are comparable to or higher than the controls, while the GPU cost remains much higher there. Thus the regression signal is not explained solely by that edge-clock change.

A clean result after necessary fixes would still need the close single-strike views, plus the 100-strike bar. No GPU rerun was started by this reviewer. The completed author-owned stand job is no longer in the live process list.

Reproducible independent extraction: `OL2-REV-evidence/perf_review.py`; exact inputs, jar metadata, per-window statistics and contamination records: `perf-raw-summary.json`.

## Memory

Updated canonical `ground-dust-volume-validation.md` and its `MEMORY.md` index entry: ground callbacks can be hot, normalized grid mass is insufficient to prove spatial stability, and the alternate ground route must retain source terrain visibility. These facts are pinned to the reviewed experimental snapshot, not to later revisions.


## Independent evidence addendum — 2026-11-04, 08:02 UTC

Scope: raw evidence only; no new code review round, source changes, client or heavy job. HEAD remains `dd0accf8b`. I did not read `SINGLE-VIEWS.md` or the author's conclusions.

**Fresh single views:** I inspected all 30 original PNGs in `OL2-evidence/publish/single/`, then used `single-view-comparison.jpg` only as an overview. All five pairs match their effect, site and camera in `capture.json`; corresponding actual ages differ by at most 0.012 s. Raised view uses a missile, the other four use a drone.

- **Blocking visible artifact:** `single/ol2-volume-night/ol2-volume-night_000.30s.png` turns almost the entire foreground below the horizon into a very bright, flat orange field, washing out the ground and litter. Its main counterpart retains ground texture and separate dusty patches. The 2 s volume frame still has a conspicuous bright orange strip at the source; at 6 s its illuminated foot is much brighter and broader than main. The overview's chosen 6 s night pair alone misses the worst 0.3 s frame. This is a direct frame observation; I have not diagnosed its cause from code in this continuation.
- Grass at 0.3/2/6 s and raised at 2/6 s confirm the replacement of tan, locally billowing dust with a smoother gray field. The city wall remains visibly solid; the cloud beside it loses its distinct billows. Forest trunks stay readable while haze between distant trunks grows grayer. These changes make the volume route easy to distinguish, but the night artifact prevents look approval. Stills do not establish motion continuity, the two-source height case, or distant terrain hiding.
- Capture metadata's isolated FPS readings are not a paired measurement of single-view GPU/main-thread cost; they cannot establish that part of the performance bar.

**Completed `ol2-quiet`:** Independently parsed final A1/B/A2 `frames.csv`, `events.jsonl`, `host.jsonl` and jar/context metadata. Each leg exited 0, completed 20/20 steps and confirmed 100 impacts. Same candidate/base hashes as the first diagnostic (`cec5517a…` / `8717214b…`), no shaderpack, 2560×1600, performance power profile. Milliseconds:

| Window | GPU p50 A1 / B / A2 | Render-thread CPU p95 A1 / B / A2 |
|---|---|---|
| 100-hit | 14.634 / 18.055 / 17.112 | 17.182 / 21.651 / 21.120 |
| outside | 2.302 / 3.740 / 2.433 | 5.126 / 6.052 / 5.430 |
| edge | 4.021 / 5.353 / 3.554 | 4.806 / 5.950 / 5.174 |
| inside | 3.734 / 5.434 / 4.023 | 4.364 / 5.725 / 4.470 |

Post-impact B costs **40–58% more GPU** and **15–30% more render-thread CPU** than the midpoint of the controls, and is worse than both controls in all three windows. This second diagnostic supports rejection of the “no more expensive than sprites” claim.

Actual load is substantially cleaner than `ol2-first`: no concurrent heavy build/GameTest units appear in these windows. B's foreign CPU averages outside/edge/inside are 0.160/0.043/0.087 cores, with only a small `arch-update.service` sample (0.18 core peak); A2 edge also records this read-only reviewer service at 0.20 core peak. It is not perfectly GPU-idle: B outside has one compositor sample at 7%, and B edge has six nonzero samples out of 20 (mean 4.15%, max 22%, `kwin_wayland`). Thus the name “quiet” alone is not proof, and the edge percentage is less precise.

The decisive inside window has **zero foreign GPU in all three legs**, with closely matched average GPU clocks **2081 / 2048 / 2021 MHz** (A1/B/A2), yet B's GPU p50 is 5.434 versus 3.734/4.023 ms. Outside clocks are 2396/2318/2258 MHz; edge clocks are 1805/2100/2006 MHz, so B's extra edge cost also occurs despite higher clocks. GPU temperatures in post-impact windows are about 84.5–87.8 °C. The impact window has wider clock/control variation, so I do not use it alone to attribute a regression.

**Evidence verdict:** the unchanged experiment is not ready: a clear night rendering artifact and a repeatable post-impact GPU/main-thread cost increase remain. Earlier code findings are not reopened or resolved by this evidence-only continuation. No further run was launched by the reviewer. Exact extracted statistics and contamination records are in `OL2-REV-evidence/quiet-perf-raw-summary.json`.
