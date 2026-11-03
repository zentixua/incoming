### Scene stand, pair with main 292f3153: pass

| pack | window | value | PR | base A1 / A2 | vs worse base | tolerance |
|---|---|---|---|---|---|---|
| none | 100/edge | gpu.p50 | 3.6 | 3.6 / 3.3 | -1% | 25% |
| none | 100/edge | thread.p95 | 5.3 | 5.7 / 5.7 | -9% | 25% |
| none | 100/inside | gpu.p50 | 4.0 | 3.4 / 2.8 | +17% | 25% |
| none | 100/inside | thread.p95 | 4.9 | 5.2 / 5.2 | -6% | 25% |
| none | 100/outside | gpu.p50 | 2.3 | 2.3 / 2.4 | -3% | 25% |
| none | 100/outside | thread.p95 | 5.7 | 5.9 / 5.9 | -3% | 25% |
| complementary | 100/edge | gpu.p50 | 11.8 | 11.6 / 11.8 | -0% | 25% |
| complementary | 100/edge | thread.p95 | 6.4 | 6.7 / 6.7 | -5% | 25% |
| complementary | 100/inside | gpu.p50 | 11.5 | 12.4 / 11.2 | -7% | 25% |
| complementary | 100/inside | thread.p95 | 5.9 | 6.1 / 8.8 | -33% | 25% |
| complementary | 100/outside | gpu.p50 | 10.4 | 10.1 / 10.2 | +2% | 25% |
| complementary | 100/outside | thread.p95 | 7.2 | 7.2 / 7.1 | +0% | 25% |
| photon | 100/edge | gpu.p50 | 12.4 | 12.2 / 11.9 | +2% | 25% |
| photon | 100/edge | thread.p95 | 6.8 | 9.6 / 8.2 | -28% | 25% |
| photon | 100/inside | gpu.p50 | 12.1 | 11.9 / 12.2 | -0% | 25% |
| photon | 100/inside | thread.p95 | 6.0 | 11.1 / 6.6 | -46% | 25% |
| photon | 100/outside | gpu.p50 | 11.0 | 10.6 / 11.0 | +0% | 25% |
| photon | 100/outside | thread.p95 | 11.8 | 7.9 / 11.9 | -1% | 25% |

Frame time at 100 strikes within tolerance of the base.

Values: GPU p50 and CPU p95 (render thread), ms. Report and frames: `mod/run/stand/out/perf-559-final/` on the laptop.
