# PR #559 review follow-up

## The +16.77% no-shader GPU row

**Conclusion: this is a real increase in elapsed GPU timestamps in that run, but the paired evidence points to scene/clock variation, not an added steady GPU cost of the PR.** It is not a single bad sample and it is not dismissed merely because it passed the 25% gate. No avoidable extra draw/shader work from this change was identified to remove.

Recomputed directly from `perf-559-final/{A1,B,A2}/none/frames.csv`, with the original `100/inside` begin/end events and `shot == 0`. The original compressed rows are in [the paired capture](bench/). [Extraction script](scripts/paired_rows.py), [extracted rows, markers and host samples](review/paired-rows.json). A1/A2 are the identical main jar; B is `3d3f9387`.

| Quantity | Main A1 | PR B | Main A2 |
| --- | ---: | ---: | ---: |
| Frames in window | 3,838 | 4,034 | 3,801 |
| GPU elapsed p50, ms | 3.4430 | 4.0205 | 2.7870 |
| Effects-stage elapsed mean, ms | 3.5060 | 4.0117 | 2.7352 |
| Heat marker mean, ms | 1.7047 | 2.1268 | 1.1126 |
| Quads marker mean, ms | 1.7263 | 1.7679 | 1.5163 |
| Submitted quads, mean of probes | 526.47 | 500.79 | 536.79 |
| Half-resolution / full quads, mean | 426.89 / 99.58 | 404.68 / 96.11 | 443.47 / 93.32 |
| Half-resolution composite layers | 1 | 1 | 1 |
| Pall marker, ms | 0 | 0 | 0 |
| Median sampled GPU clock, MHz | 2,250 | 2,092.5 | 2,400 |
| Whole-frame interval p95, ms | 7.0938 | 6.9750 | 7.2430 |

The heat/quads/count rows average the one-second probes timestamped inside the window; the frame and effects-stage rows use per-frame CSV data.

`4.0205 / 3.443 - 1 = 16.773%`. In four consecutive five-second bins, GPU p50 is A1 `[3.4985, 3.3920, 3.6150, 3.3980]`, B `[3.9135, 4.2690, 4.0580, 3.9225]`, A2 `[2.7610, 2.8210, 2.8055, 2.7865]`. Thus the difference persists throughout the window.

What explains the classification:

- About 0.422 ms of the 0.506 ms increase in mean effects-stage elapsed lands at the **heat** marker; the quads marker changes only 0.042 ms. The nuclear volume marker stays zero: the startup warmup is not continuing to draw in this window. Submitted particle counts are lower, and the number of half-resolution composites is unchanged.
- B's sampled GPU clock is 7% below A1 and 12.8% below A2. These are only four host samples per window, not enough to apply a precise per-frame clock correction. A1 also has desktop-compositor GPU activity in its host samples. B/A2 have none in this window.
- The two **identical main jars** already differ by 23.54% in GPU p50 (A1 relative to A2) and by 53.21% at the heat marker. That control variation exceeds B's increase over A1. This is consistent with a scene-dependent heat/shadow workload and clock variation, rather than a fixed version-dependent cost.
- These are real flights with different actual impact coordinates and different debris/crater geometry, not an identical frozen rendered frame. For example, the first recorded impacts are A1 `(1012.968,67.721,9.783)`, B `(1015.272,67.721,8.540)`, A2 `(1023.535,67.832,4.892)`. The heat pass follows selected light positions, frame depth and the local block occluder grid; ray exit/occlusion work can change with that geometry. Its source, `Solids`, and all shaders are byte-identical between this PR and the paired main. The cloud-law reference checks retain the same numerical outputs.
- The player's whole-frame p95 is lower in B than either control. The 16.77% GPU row is therefore not a 16.77% slowdown of displayed frames in this test.

**Limit:** GPU timestamps include possible pipeline gaps; adjacent pass markers can contain the previous pass's tail. The retained data do not count individual shadow-ray iterations or record complete shader inputs, so the exact share caused by clocks versus occluder geometry cannot be separated. Attribution of the residual heat difference to scene variation is an inference, not a measured ray-iteration count. A causal claim of a 16.77% code regression is likewise unsupported by these three different rendered scenes. Reducing cloud detail or changing the heat law to cancel this row would change the effect without addressing a demonstrated PR regression; neither is done.

## Optional review items

Both were taken:

- Warmup now records both the shader object and its successfully drawn GL program ID. A replacement program in the same `ShaderInstance` makes it cold again; a new shader object still does too. This handles in-place runtime recompilation without depending on Veil classes or APIs. It adds no recurring GPU work when the program has not changed.
- Three permanent numerical tests retain compact baseline samples: nonzero sheltered wind shear around the thermal apex/final table node; the cloud's eager midpoint shear integral across interpolation/last-node boundaries; street emission moments before and after a backward age request. Reference values come from the eager implementation/integral at `341c2ea6f`; the old full reference classes stay outside the production diff.

## Integration and validation

Main `9b099a981` was merged with merge commit `4e53302c0`; no rebase or force-push. The follow-up changes only the program-generation predicate, numerical tests and area notes. The cloud/render equations, shaders, draw counts and steady-state passes are unchanged, so the existing three-pack capture remains the performance evidence for those paths; it is not relabeled as a capture of the new head. No new GPU run is needed to remeasure an unchanged draw path.

Follow-up head: `4e1c513ab`. Full local build passed: **1,755 tests, 280 suites, zero failures/errors/skips**, including all six `CloudBuildTest` cases. Final **Итог is green** on this merged head (`4e1c513ab`), confirmed with `tools/pr_ci.sh 559`. [Build log](review/build.log), [CI receipt](review/ci.log), [six permanent cloud-build tests](review/CloudBuildTest.xml). The PR remains open; no PR merge was performed. No release, server action or MT-B6 work.
