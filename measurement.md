# RUINZONE validation and cost

Final implementation: 9e9769ef0 (02 November 2026). Initial measurements preceded the live-worker memory-accounting correction. CPU-only local GameTest server; Create 6.0.10, Sable 2.0.6, Aeronautics 1.3.2, NeoForge 21.1.264. No player, DH, Voxy, LSS or GPU. No user/server world accessed.

The persistent fixture is a 10 x 10 group of final-block chunks, written straight to the test world's chunk storage before the pass. Each has stone ground, grass, plants and a glass wall. The 7 kt detonation uses effects scale 0.1 to keep the scan small (6,241 bounding-square positions). It is retained as a past detonation; no onDetonation call prepares it. The test reads storage until all 100 chunks have their scar. This measures the automatic migration path, not an artificial direct call to apply ruins.

| Run | Chunks confirmed in storage | Wall time | Game ticks | Total nuclear lane work | Mean per tick | Maximum per tick |
|---|---:|---:|---:|---:|---:|---:|
| Initial bounded polling (32 of 128 entries per tick) | 100 | 71.812 s | 1,430 | 190.870 ms | 0.133 ms | 10.019 ms |
| Poll the bounded window within the common deadline | 100 | 20.091 s | 403 | 100.067 ms | 0.248 ms | 3.106 ms |
| Combined local regression batches | 100 | 20.441 s | 409 | 60.440 ms | 0.148 ms | 1.402 ms |
| After first review correction | 100 | 19.991 s | 400 | 95.474 ms | 0.239 ms | 2.369 ms |
| Final, including neighbor handoff | 100 | 20.395 s | 409 | 59.578 ms | 0.146 ms | 1.214 ms |

Times are observations, never CI assertions. Nuclear lane work includes settlement, scar planning/snapshot/application on the server thread and other nuclear work; it excludes background solver threads, vanilla chunk deserialization/saving between ticks and the intentional GameTest 50 ms pacing. They are not total server MSPT. Counting-clock tests enforce admission/polling budget and bounds deterministically.

Limits: this sparse synthetic fixture is not a dense city or a 385,000-chunk server map. No netcup throughput estimate follows from it. Full-scale completion time depends on existing chunks, structures, storage, tick headroom and heap pressure. No realistic world copy was supplied in this worktree, and the task prohibits accessing the user's worlds and servers.

The load path intentionally keeps vanilla/NeoForge save and block-entity behavior. Skipping empty plans would still require persisting CHUNK_SCAR, so an optimization that skips loading would need a separate safe chunk-save path; this PR does not introduce direct region rewrites. Final-block old-world retrogen may still require asynchronous vanilla generation; wholly absent and PARTIAL chunks are skipped.

The restart GameTest serializes progress, reconstructs SavedData and NuclearWorld, and drops unsaved work/tickets. It exercises the production restore path in one JVM, not a separate process crash. Unconfirmed positions survive even when the scan cursor is ahead; old saves have no progress and start from zero.

## Regression history

- Initial local build: 1,736 unit tests passed. The first three settlement GameTests passed.
- The expanded ground-trace fixture initially placed the target inside the heavy zone and failed its precondition. Moving it back outside the heavy zone and including plants exercises both glass removal and a ground trace; the whole saved block plan is compared.
- Twenty touched local GameTests passed, including nuke_send_hold, nuke_zone_edge, nuke_zone_heap and shutdown/drain checks.
- The first full CI run failed nuke_tile_hold: its deliberate unloaded-neighbor fixture was reached by the new background pass. That legacy handoff test now marks its synthetic detonation's automatic pass complete; its 200-tick wait and radius-2 ticket assertions remain. The five settlement tests exercise automatic loading separately. The known nuke_send_hold flake did not occur.
- Independent review found permanent admission blocking on 48-core hosts: configured worker-buffer estimates alone exceeded the 256 MiB admission threshold. Settlement now counts live worker threads, which expire after 30 idle seconds. A regression holds the configured core count at 46 while live count drops to zero and verifies the estimate recovers.
- The focused worker unit test and six GameTests (five settlement batches plus nuke_tile_hold) passed after that correction.
- One intermediate local full build could not write XML test reports because two author-started Gradle test tasks overlapped in the same build directory. It is not counted as successful validation; the final full build is run serially.

- The second independent review found a missing ScarQueue neighbor-ticket handoff. The new nuke_settle_neighbours test reproduces a resumed pass whose only pending chunk has mayHold=false: before the fix it failed after 600 ticks without ruins. The fix uses ScarQueue.holdForTile within tileHoldsLeft. The unchanged test then passed, including stale Create-block replanning requiring radius 2. Final touched-area run: all 22 GameTests passed.
- Two review rounds were performed, as required by the repository's two-round maximum. Both findings are addressed and have regression coverage; no third review was requested. See independent-review.md for findings and resolutions, rather than interpreting the original reports as final approvals.

- Final serialized local validation on 9e9769ef0: all 22 touched GameTests passed; full `./gradlew --daemon build` passed in 6m 17s. JUnit XML totals: {'tests': 1737, 'failures': 0, 'errors': 0, 'skipped': 0}.
- Final CI on 9e9769ef0: all six GameTest shards, both unit-test groups, build, clean installation and required Итог passed. Run: https://github.com/zentixua/incoming/actions/runs/41416395390
