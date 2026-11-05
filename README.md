# Nuclear runtime progress — MT-S8 source evidence

Source: `d2f3854df0221d1a69cbd24c869f881ea9b1dafc`, based on production
`56f735f2b6ba7c5c30fec8d148359c75c0373372`. Component PR: [604](https://github.com/zentixua/incoming/pull/604).

This component retires consumed terrain-header ownership, admits FAR source reads using one actual worker
restart, and matches cold-import reservations to the existing compact material format. The 512 MiB shared
ownership cap, 32 MiB encoding margin, 64 MiB material-transition margin, physics, cargo and common clock remain.
Malformed cargo types are rejected before preflight and decoding can disagree about their representation.

## Controlled results

| Control | Before | After | Evidence |
|---|---|---|---|
| Complete 1-Mt header cohort, 277,736 replies | 48,705,508 charged bytes remain after consumed replies; fails | 14,966,796 with the last reply pending; 3,697,436 on completion; all 10,920 tile masks preserved | [before](raw/header-red.log.gz), [after](raw/header-green.log.gz) |
| Exact 135,695,543-byte saved material input | Normal cold entry preserves the file on disk because its estimate exceeds the cap | Scan 423,967,983 B; published owner 342,756,623 B through real placeholder, scan, reservation and publication | [old entry](raw/old-cold-entry.log.gz), [final valid input](raw/native-corrective-valid.log.gz) |
| Same native two-consumer cohort and constant 115,403,309 B competing owner | Both plans remain current and capacity-blocked for 120 controlled ticks | Both commit in 27 ticks; 34,617 actual material records released; sampled ownership peak 508,146,876 B | [before](raw/native-old-header.log.gz), [after](raw/native-corrective-valid.log.gz) |
| Seeded FAR source/archive read with 65,536 moving records and fixed 280 MiB competing owner | Configured-future forecast blocks progress through the unchanged 120-tick bound | Equal complete plan, followed by actual application of 2,432 material records; 67,176 remain pending | [before](raw/far-same-input-before.log.gz), [after](raw/far-same-input-after.log.gz), [identical input](checks/future-pinned-input.json) |
| One native state change, then stable sources through the real queue | One expected stale rejection, old snapshot serial retained initially | New serial, actual scar and material release; plain and retained-flash variants pass | [queue retry](raw/native-one-change-retry.log.gz) |
| Compact import with 100,000 records and cargo | Prior source refuses a 32 MiB import room | Live 16,085,280 B; admitted 22,593,480 B; count, trajectory, mass/volume and cargo parity pass | [before](raw/packed-red.log.gz), [final checks](raw/final-build-touched.log.gz) |
| Cargo type disagreement found by independent review | Prior scan and decoder both accept a scalar cargo tag | Both reject all 15 malformed type/representation pairs while preserving input bytes | [before](raw/cargo-types-before.log.gz), [after](raw/cargo-types-after.log.gz) |

The FAR pair uses the same native origin and complete reference-plan SHA256
`e67f7f338e35c3fe782c451e796ab9d876abfdc126c289f0233b209620abbfcf` in both runs.
The header control validates partial masks with an independent world-availability oracle and retains a
withheld final reply. Moving-source expansion, rather than an external refund of the competing owner,
funds the second native consumer. The large native file uses its own stopped epoch and counts, separate
from the earlier post-clean ownership snapshot.

## Validation and review

The final local build has 1,850 unit tests, zero failures/errors/skips, and 32 touched native GameTests.
[Build receipt](checks/local-build.json), [complete console](raw/final-build-touched.log.gz),
[production/test diff](source.diff).

The first fresh Sol/xhigh review found one conditional cargo-type ownership blocker. Its old-source
counterexample was reproduced, then corrected and checked. The fresh second Sol/xhigh review found
no serious issue in the corrective delta: [first report](reviews/RMT-S8-review.md),
[second report](reviews/RMT-S8B-review.md). The second report records an inbox-display boundary breach
after its source/raw-evidence verdict and handback were already written; its conclusion was formed
before that closure display. Neither review is an integrated runtime acceptance receipt.

The superseded `f506c1ea` CI run failed an existing salvo-ticket lifetime fixture; the final source run
`41972699466` failed the existing `nuke_send_hold` fixture. Their original logs remain here:
[salvo failure](raw/ci-f506-shard1.log.gz), [send-hold failure](raw/ci-d2f-shard3.log.gz).
Those failures remain validation work, with separate explicit ownership; a later shard reshuffle does
not erase either original failure. Final required-CI closure is pending in this evidence revision.

## Scope and limits

All byte values above are the mod's known-owner accounting model, not total JVM heap or a measured
allocation peak. Counting ticks are service controls, not server-frame deadlines. The native fixture
uses 30 original/stopped block-state and block-entity chunks, the exact complete material file and
all registry providers needed by those inputs (115,814 registered states in this controlled runtime).
GameTest still supplies its biome and tick environment; this is not a complete pack/world restart.
It does not reconstruct the entire stopped world, unsaved live overlays or all cold terrain. Its two
consumers and old cold backlog do not establish sustainable whole-world transport; the successful
native transaction has zero old-material deposits during its short controlled window. The separate
FAR control requires actual material advancement and application while backlog remains.

The failed integrated 1-Mt run is still a failure. Complete loaded-player front timing, all successful
and pending cohorts, moving/rejoining into crossed terrain, full world save/reload and integrated
delivery remain with the integration owner. This source evidence makes no release/readiness claim.

Private world/NBT/cargo bytes, epoch observers, personal harness bodies, launchers and session records
are excluded. [Input hashes](checks/input-pins.json) and [control-source hashes](checks/control-source-hashes.json)
bind the retained local inputs and controls. Compressed console logs contain outcomes and counts,
not the private cargo payload. `manifest.sha256` covers every delivered evidence file.
