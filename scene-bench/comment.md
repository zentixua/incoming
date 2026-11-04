### Scene stand, pair with ab2741fa11d5@ab2741fa: FAIL

| pack | window | value | PR | base A1 / A2 | vs worse base | tolerance |
|---|---|---|---|---|---|---|
| none | 100/edge | gpu.p50 | 3.4 | 3.5 / 3.1 | -3% | 25% |
| none | 100/edge | thread.p95 | 5.2 | 5.2 / 8.9 | -41% | 25% |
| none | 100/inside | gpu.p50 | 3.2 | 3.8 / 3.1 | -16% | 25% |
| none | 100/inside | thread.p95 | 4.9 | 6.2 / 5.8 | -21% | 25% |
| none | 100/outside | gpu.p50 | 2.3 | 2.3 / 2.3 | +0% | 25% |
| none | 100/outside | thread.p95 | 5.9 | 5.7 / 6.3 | -7% | 25% |
| complementary | 100/edge | gpu.p50 | 11.7 | 11.2 / 11.6 | +1% | 25% |
| complementary | 100/edge | thread.p95 | 6.5 | 6.2 / 6.5 | +0% | 25% |
| complementary | 100/inside | gpu.p50 | 11.1 | 10.4 / 10.8 | +3% | 25% |
| complementary | 100/inside | thread.p95 | 9.7 | 5.9 / 5.8 | +64% | 25% **FAIL** |
| complementary | 100/outside | gpu.p50 | 10.1 | 10.0 / 10.2 | -1% | 25% |
| complementary | 100/outside | thread.p95 | 7.1 | 6.9 / 7.1 | -0% | 25% |
| photon | 100/edge | gpu.p50 | 12.2 | 12.2 / 12.2 | +0% | 25% |
| photon | 100/edge | thread.p95 | 6.7 | 7.2 / 6.8 | -7% | 25% |
| photon | 100/inside | gpu.p50 | 14.1 | 13.2 / 13.2 | +7% | 25% |
| photon | 100/inside | thread.p95 | 6.2 | 6.2 / 6.1 | +0% | 25% |
| photon | 100/outside | gpu.p50 | 11.0 | 10.8 / 11.0 | +0% | 25% |
| photon | 100/outside | thread.p95 | 7.3 | 7.1 / 7.2 | +1% | 25% |

- complementary 100/inside thread.p95: 9.66 ms versus 5.89 for worst accepted (+64%, tolerance 25%)

Values: GPU p50 and CPU p95 (render thread), ms. Report and frames: `mod/run/stand/out/mt-b5-final-dba2/` on the laptop.
