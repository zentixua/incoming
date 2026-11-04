### Scene stand, pair with 1aca1654a71a@1aca1654: pass

| pack | window | value | PR | base A1 / A2 | vs worse base | tolerance |
|---|---|---|---|---|---|---|
| complementary | 100/edge | gpu.p50 | 11.8 | 11.8 / 11.7 | -0% | 25% |
| complementary | 100/edge | thread.p95 | 6.6 | 6.4 / 6.5 | +1% | 25% |
| complementary | 100/inside | gpu.p50 | 11.0 | 11.3 / 11.0 | -2% | 25% |
| complementary | 100/inside | thread.p95 | 6.7 | 5.9 / 5.7 | +13% | 25% |
| complementary | 100/outside | gpu.p50 | 10.3 | 10.3 / 10.3 | -0% | 25% |
| complementary | 100/outside | thread.p95 | 7.2 | 7.1 / 7.0 | +1% | 25% |

Frame time at 100 strikes within tolerance of the base.

Values: GPU p50 and CPU p95 (render thread), ms. Report and frames: `mod/run/stand/out/mt-b5-complementary-isolated/` on the laptop.
