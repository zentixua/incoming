# Nuclear GameTest lifecycle and send-budget evidence

Production baseline: `36c9b5bdd`. Diagnostic commit: `4aadc5c33`. Final source checkpoint: `42f6387b4` (PR #577).

- `diagnostic-shard3.log`: GitHub run 41804817248, job 126489170492. Same baseline code and original assertions; added ticket diagnostics only. Shutdown fails with an ownerless `incoming_nuclear_matter` load ticket at chunk `[4, 0]`. The log preserves the batch composition and raw ownership details.
- `focused.log`: final source, all 6 selected GameTests passed, Gradle 58 seconds. Exact-deadline case sent all 164 copies; budget-exhaustion case sent 164 copies in 18 sections, exactly one whole section per tick. Shutdown observed zero held areas/tickets.
- `red.log` and `red-control.patch`: final source with two deliberate reversions, limited to the old cache-only cleanup and old `expected + 2` assertion. Both selected tests failed as expected in 38 seconds: `Replaced imported fixture left its load ticket behind`, and a copy sent at tick 238 with expected tick 230. The final source files were restored byte for byte from the green checkpoint before committing.
- `build.log` and `unit-results.json`: final local full build and devtest compilation, 1783 unit tests, zero failures/errors/skips, Gradle 4m55s.
- `review.md`: fresh independent Sol high review of exact 42f6387b4; no serious blocking issues, one optional future-deadline coverage suggestion.

Commands (Java 21, repository laptop_job wrapper, explicit SESSION_TREES and unique INCOMING_GPU_OWNER):

```sh
cd mod
./gradlew runGameTestServer '-PgametestOnly=dh_front.*|nuke_ledger_cold|nuke_ledger_cleanup|nuke_stop|nuke_send_hold' --console=plain
# Apply red-control.patch only for this deliberate regression proof:
./gradlew runGameTestServer '-PgametestOnly=dh_front_budget|nuke_ledger_cleanup' --console=plain
# Restore final files, then:
./gradlew --daemon build compileDevtestJava --console=plain
```

The front send-budget clock defaults to System.nanoTime in production. Tests replace it per existing preparation: a stationary clock checks exact deadlines; a clock charged by the test sink checks bounded section progress. No production deadline, send budget, timeout, packet, physics or renderer behavior was changed. No GPU or external test server was used.
