# Paired 15 kt snapshot diagnostic

| Fixture | Floating main → PR | Logs left main → PR | Trees recognized main → PR | Added cells main → PR | Median solve ms main → PR |
| --- | ---: | ---: | ---: | ---: | ---: |
| hall-stone-natural | 49 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 45.95 → 49.39 |
| hall-concrete-natural | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 47.40 → 50.90 |
| hall-stone-controlled | 49 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 22.51 → 41.08 |
| hall-concrete-controlled | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 22.66 → 34.41 |
| tower-concrete-6 | 0 → 0 | 0 → 0 | 0 → 0 | 392 → 392 | 88.04 → 123.46 |
| tower-concrete-20 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 100.04 → 147.16 |
| tower-stone-20 | 0 → 0 | 0 → 0 | 0 → 0 | 392 → 392 | 80.64 → 141.22 |
| tower-log-20 | 0 → 0 | 2304 → 0 | 0 → 0 | 0 → 0 | 81.15 → 207.32 |
| column-concrete-6 | 0 → 0 | 0 → 0 | 0 → 0 | 0 → 0 | 53.33 → 81.99 |
| bare-log-column | 0 → 0 | 16 → 0 | 0 → 0 | 0 → 0 | 20.63 → 23.64 |
| tree-16 | 0 → 0 | 8 → 8 | 1 → 1 | 7 → 7 | 22.68 → 33.66 |
| tree-30 | 0 → 0 | 30 → 8 | 0 → 1 | 0 → 7 | 22.62 → 27.22 |

`Added cells` includes all added non-air blocks (including fire); it is not a conserved rubble volume. Tree transfers outside the decoded region are counted in the JSON but not applied. Timings cover fracture/collapse/plan finishing for nine chunks only, exclude extraction and commit, and are not an isolated performance gate. See README for limits.
