# Nuclear journal and consumer progress

Source component evidence for [PR 601](https://github.com/zentixua/incoming/pull/601). Production base `3efac357cc368045f6fe821ac02c561ab99af4c8`; candidate `96648b0d730a93977ffe97b047d67f03885676ee`, tree `99920198c2a5a5429b7c8608280fe503faa23be7`, `mod/src/main` tree `5004180da7fb5a6062080a9025208412466e83c3`.

This is not acceptance of the actual 1 Mt player experience. The integration owner must run the new combined artifact through the full native visible cohort, 100/1000 kt, traversal, normal save/rejoin, LOD and runtime-budget checks before delivery. Final source/CI receipts are below.

## Demonstrated boundaries

The preceding actual run failed: the last complete clean sample had 453 affected loaded/viewed chunks, 430 scarred and 23 still overdue, with maximum pending lag 8,933 ticks. Far preparation remained at 3,290 throughout the fixed 480-second interval. The [integration owner's raw report](https://github.com/zentixua/incoming/blob/0e489fc46ee4079eb12147b5c03e3586ceb0b324/natural1000-journal-20261104/REPORT.md) owns those runtime observations and their native-view/timing limits.

Its later post-clean probe found 1,570,626 original contacts in a capacity-2,097,152 table, with only 2,238 unused slots. A ready 22,144-material chunk crossed a 64 MiB backing-growth boundary. Zero deferred support sources and zero plan reads distinguish this from the preceding spent-credit/completed-page defects. That probe does not alone explain early-wave latency or prove permanent deadlock.

The correction stores the same first removed state/event in primitive section pages. A section contains at most 4,096 positions; a singleton needs no inner hash table. Runtime registry IDs remain local to the process. Normal saves stream named palettes in rows of at most 64 originals, and legacy named rows keep earliest-event semantics. Admission includes fixed owners, intermediate old/new rehash arrays and the section index. Noncanonical numeric capacity headers are rejected before allocation.

The remaining material turn now follows flash layers and preparation. The existing first bounded material batch remains before visible work. All stages retain the shared clock and existing preparation share. Neither the physical solver nor the 512 MiB cap changed.

## Same-input real-ledger/city control

The private input is the exact stopped native `incoming_ruin_matter.dat`: 67,058,459 bytes, SHA256 `de1369f99d1f8d9debec70deb87cb8c224b4d4e9cdc13325210915024700007f`. It is newer than the post-clean probe; their contact counts must not be substituted for one another. The file and its cargo/world data are not published here.

The replay combines this ledger with 35 verified original Zearth chunk inputs, translated by 100,000 chunks into a fresh native test world. Three required dense consumers use real background solvers and actual plan spill/read/commit paths. Both sides use native world seed 0, grid seed 57263017, origin `6853326,-59,9086224`, bootstrap game epoch `2040660000`, activation `2040660400`, and a fixed 145 MiB competing owner. Spatial/epoch control does not make asynchronous scheduling identical.

| Observation | Base 3ef | Candidate 1fe9d |
|---|---:|---:|
| Native input released / pending / support | 1,628,235 / 662,086 / 47,013 | Same |
| Imported live ledger estimate | 347,410,073 B | 149,039,765 B |
| Normal cold-import estimate | Above 512 MiB (`cap+1` sentinel) | 285,610,347 B |
| Physical plan material candidates | 23,715 / 7,503 / 17,829 | Same counts |
| Required native chunk commits in the fixed 160-tick interval | 0 / 3 | 3 / 3 in 10 ticks |
| Additional captured material | 0 at the blocked consumers | 48,623 |
| Sampled retained peak in the consumer phase | Cap respected; plans remain fresh and memory-blocked | 436,969,892 B |

The candidate completed a normal save and streaming reload of the entire resulting ledger; aggregate counts/released volume and the loader's full balance checks passed. Exact normalized material/cargo/support comparison is separately supplied by `nuke_support_capacity` (46,089 released pieces and three live barrel cargos). The real-file replay does not compare every individual trajectory/cargo row or reconstruct the stopped world's terrain. Its old backlog is at its original coordinates; it is a real ownership-pressure control, not proof that all of that backlog is making useful progress. Initial replay decoding precedes the fixture's resident permit and is not a production `LedgerLoads` admission receipt.

`succeedWhen` keeps retrying after the 160-tick acceptance boundary; the baseline process stopped at the fixture timeout, not at tick 160. V1's mid-test epoch jump incorrectly ran queued pacing callbacks in one long tick. That failed attempt is preserved and excluded; v2 bootstraps before test registration. Counting-clock aggregate tick wall times, including the 177 ms sample, are not production deadline measurements.

## Independent service control

`nuke_frontier_material` uses real source/archive reads, 65,536 co-located coarse moving records, and a fixed shared counting clock. The original ordering fails its 120-tick progress condition. Moving only the remaining material turn lets the far plan complete in 20 ticks while all 65,536 records remain pending and at least one has actually advanced in simulated time. Complete plan storage equals its reference. The observed resident estimate is 436,432,260 B.

This separates a scheduling dependency from journal capacity. It is not a city throughput benchmark, a flash-layer timing test, or a universal fairness guarantee under arbitrary arrivals.

## Review and accounting controls

- `nuke_original_sections`: 1,570,626 original entries, exact retained-state checks, a further 22,144-position dense chunk and full journal retirement.
- `nuke_original_stream`: 8,195 distinct singleton sections, earliest-event handling, actual current/legacy file imports and normalized complete journal comparison. The legacy scan exceeds its bounded section-count cache.
- `nuke_original_bounds`: invalid/missing/oversized/incomplete headers, exact numeric header types and the former per-original legacy admission bound over every section density.
- `nuke_original_growth`: an existing table grows through multiple capacities. Actual old arrays are retained across replacement. The old isolated grant was 98,508 B for 147,484 B of modeled final growth plus overlapping arrays; corrected grant is 147,612 B. This is an isolated representation bound, not a reproduced aggregate heap overflow.
- `nuke_original_min`: the old empty-file scan charged 272,256 B for a 272,792 B fixed owner. Corrected empty/singleton measurements are 276,352 / 276,576 B versus resident 272,792 / 272,888 B.
- The failed CI run `41944861561` exposed a direct-handoff test leaving a 16,777,344 B optional cache permit. A native `nuke_near_ready` → `nuke_worker_admission` sequence reproduced `inputs=0, results=16777344, workspace=0`. Its fixture now closes the returned plans/context; gameplay ownership is unchanged.

First independent review: fresh Astra/xhigh, exact 3ef..1fe9d. Its numeric-header mismatch was a conditional import-budget blocker; it was not attributed to the native failure. The intermediate-rehash observation did not itself prove a whole-cap breach. A fresh corrective review covers 1fe9d..96648. Technical receipts and exact source hashes accompany this evidence; personal task/session records are kept outside version control.

## Architecture choice

| Alternative | Relevance to this failure |
|---|---|
| Keep the per-block object map | Retains the observed dominant journal and its whole-city resize boundary. |
| One global primitive map | Removes object payload, but still has a resize proportional to the entire journal. |
| Primitive section pages | Preserves exact provenance, bounds inner growth, handles sparse sections and keeps the normal streaming persistence channel. Selected. |
| Partial chunk application | Can reduce capture peaks, but alone does not remove the global journal resize and adds partial-commit/edit/restart obligations. Not required by the supplied corrected dense transition. |
| Revert the released material behavior | [3.10.4 already continued far planning after detonation](https://github.com/zentixua/incoming/blob/v3.10.4/mod/src/main/java/ua/zentix/incoming/nuclear/world/NuclearPrep.java#L328). Its [12%/four-block rubble](https://github.com/zentixua/incoming/blob/v3.10.4/mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinPlanner.java#L535) is not equivalent to the current complete material lifecycle. |
| Replace the mutation engine with an editor library | [FAWE](https://intellectualsites.gitbook.io/fastasyncworldedit/customization/configuration) documents streaming available chunks and disk history; [WorldEdit](https://worldedit.enginehub.org/en/latest/api/concepts/edit-sessions/) provides batching/extents and queue flushing. The demonstrated owner is this mod's additional material/support journal. This is a scope comparison, not a performance ranking of those libraries. |

Authoritative work is retained at capacity. Ordinary successful save/reload is covered; power-loss atomicity across region files and SavedData, arbitrary unbounded components, exact total JVM heap and guaranteed zero arrival delay are not claimed.

## Final receipts

- [CI 41949145779](https://github.com/zentixua/incoming/actions/runs/41949145779) completed successfully for source `96648b0d730a93977ffe97b047d67f03885676ee`. Checkout `b662cf552589fedd712639894a8a6bd1568749fe` has exactly the source tree `99920198c2a5a5429b7c8608280fe503faa23be7`; tested-tree artifact ID `13380206749` confirms it. All six normal GameTest jobs, both LSS runtimes, build/unit jobs, clean NeoForge server/client and store checks passed. Required `Итог` job `126991428112` is green. See [receipt](receipts/final-ci.json).
- Fresh independent Astra/xhigh [first review](reviews/first.md) and [corrective review](reviews/corrective.md). The second reviewed exact 1fe9d..96648 and found no serious corrective-source defect. Original report hashes are in [review receipts](receipts/review-hashes.json); personal session records are not included.
- Local full build at 1fe9d: **1,840 unit tests and 95 affected GameTests passed**. The corrective native run passed **7/7**, covering the final production changes and synthetic cleanup. Later test-only assertions/fences are included in the successful final CI; see [pin boundaries](receipts/provenance.json).
- Final CI artifact `13379488095`: ZIP 23,087,422 B, SHA256 `67788936e30011947e428fbb6e5fd09e4ee5abb28483458e0ba8f448d74c5965`. Its verified member `incoming-3.11.0.jar` is 23,486,800 B, SHA256 **`0f4b5e969d79c15e6207ae15fa2700f4c324b0cf25b3486c0fced8fc006601e3`**. These are different hashes. The jar contains production classes and excludes private replay, seed/epoch, GameTest and diagnostic classes. [Artifact receipt](receipts/artifact.json).

Source component checks are complete. The unreleased jar remains a local/CI integration input; this evidence branch contains no jar or private world/cargo data. Runtime acceptance and delivery remain separate.

## Combined-CI follow-up

After this component was combined with client work, the real shared-clock frontier regression exposed a second ordering dependency: pinned-cache maintenance ran before funded FAR admission. The separate [PR 603 evidence](far-admission/README.md) records exact old/new controls, source review and final follow-up validation. The original component receipts above remain unchanged; they were not combined runtime acceptance.
