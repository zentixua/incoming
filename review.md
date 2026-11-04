# RRELCAP2 independent source review

Date: 2026-11-04, 15:30 UTC. Reviewer session: RRELCAP2, GPT-6.1 Sol / high.

Exact reviewed diff:
- Base: `7cb9d1cb049b868374024102b66618fc1d019b7d`
- Head: `92122d62db1dcd30843b18c6d1ecf03a2ffb222a`
- Checkout: `/mnt/data/projects/incoming/mod/run/wt-RRELCAP2`, clean detached HEAD at the head above.
- Implementation checkout observed separately: `wt-RELCAP2`, branch `codex/nuclear-yield-release-candidate`, same head at inspection. No implementation ownership transfer.

Read current TEAM/context/BOARD/live, checkout AGENTS and applicable area rules, shared memory index and relevant source-provenance facts. Did not read the author's session, PR description, handoff conclusions or claimed check results. The explicit review-only brief is this session's assignment. Process inspection confirmed the reviewer launcher selects Sol/high and this worktree. No owned jobs, GPU/client/server operations, tests, build, CI, PR or merge actions were started. No production files changed.

## Serious findings

None identified in the specified diff and inspected surrounding source. No concrete introduced wrong behavior, crash, budget breach, release-note factual error or EN/RU mismatch established by this source review.

This clears only the bounded cap/config/codec/control/documentation source change. It does **not** accept the integrated 100/1000 kt runtime, total heap bound, preparation duration, client frame cost, server tick cost, network cost or distant convergence. Those require the delivery owner's actual integration evidence. Raising the admission range is not evidence that these runtime gates passed.

## Optional findings

1. **Low: remaining comments describe the obsolete 15 kt ceiling.** `mod/src/main/java/ua/zentix/incoming/nuclear/model/Yield.java:4` still states that the mod has no yields above 15 kt. `mod/src/main/java/ua/zentix/incoming/nuclear/NuclearEvents.java:52` also describes the scheduled-save clamp as the 15 kt limit, although the next line now uses 1000 via `MAX_YIELD`. Update their wording to the supported ceiling/world-policy distinction when convenient. Neither comment changes execution.
2. **Low: expanded range is not directly covered through reviewed launch authority.** `mod/src/test/java/ua/zentix/incoming/strike/LoadoutTest.java:13` exercises constructor clamping and NBT/network round trips, not review/confirmation/launch. Existing `NuclearReviewGameTests.java:25` fixtures set cap 15, and the new config test only corrects an in-memory config. An isolated test using the existing READY fixture could accept 100/1000 at the matching cap, reject an over-cap request, and revoke an already READY/begun high-yield review after a cap reduction before payment. This is a coverage improvement, not an observed defect: the inspected production guards already implement those checks. No extra review round requested for these optional items.

## Inspected behavior and assertion boundaries

| Area | Source assessment at the exact head |
| --- | --- |
| New/existing config | `IncomingConfig.java:222-225` leaves omitted-command default 15, changes missing `max_yield` to 100, and accepts 1–1000. Registration is standard SERVER `ModConfigSpec`; no migration overwrites a present valid 15. Missing keys receive defaults, so a missing key in an older world also receives 100. |
| Actual new config assertions | `NuclearYieldConfigTest.java:12-30` runs standard `spec.correct`, checks missing cap/default, preserves caps 1/15/100/1000, and checks corrections for -1/0/1001. It does not load a real world/TOML, bind live config or prove runtime capacity. |
| Loadout/codecs/carriers | `Loadout.Nuke` clamps on construction to 1–1000; NBT INT and network VAR_INT retain the full range. NUKE, MISSILE and BUNKER nuclear selection is still spec-driven. New tests check -1/1/15/100/500/1000/1001 on all three and compare whole round-tripped loadouts. Invalid inputs are clamped before encoding; these are not malformed raw-payload decoder tests. Existing burst/default tests remain. |
| Carrier persistence | `StrikeProjectile` saves/restores `Loadout.Nuke.CODEC`; B-2 transfers the nuclear object and preparation identity to its bomb. No byte/short or separate 15 kt carrier cap found in these paths. Scheduled ICBM save decoding also uses the shared ceiling. |
| Server authority | `NuclearReview.allowed/act` rejects over-cap orders, snapshots policy, rechecks it on PREPARE/BEGIN/COMMIT, requires exact current READY and a fresh 60-server-tick hold, and consumes authority before payment. `ServerActions.strikePrepared`, `NuclearCommands.allowed/valid`, `NuclearKeys.launch` and `StrikeService.launchPrepared` recheck current cap in their respective continuations. Operator diagnostic `nuke now` retains its existing server-cap clamp. |
| Numeric UI | Remote input reads the synchronized server cap, accepts whole digits through `ImpactPreview.parseYield`, and presets filter 1/5/15/50/100/300/1000 by cap. Four-digit 1000 fits the existing input limit. Review/map previews use accepted loadout yield. The 1000 label is `1 Mt (1000 kt)`; model preview tests already contain 100 and 1000 radius rows. No changed-range inconsistency found. |
| Version/protocol | `mod_version=3.11.0`; composed `IncomingNetwork.PROTOCOL` is 55, matching notes. Last shipped 3.10.4 notes say 51. This diff changes no codec layout requiring another independent format bump. |
| EN/RU | Changed nuke, settings and weapons guides agree on supported 1–1000, fresh default cap 100 and omitted-command default 15. Release-note paragraphs and installation instructions match between languages. Almighty notes describe the same limits. |

## Release claims against composed source

- Unpaid Prepare → READY → fresh hold, keyboard/mouse confirmation and close cancellation: `NuclearReview` and `NuclearReviewScreen`. The notes correctly separate accepted paid orders from unpaid cancellation.
- Automatic accepted command dispatch after readiness: `NuclearCommands.tick`; flight scheduling starts only when dispatch reaches `NuclearStrikes.schedule`. The default flight is 1800 ticks. Preparation adds no ammunition payment or warning itself.
- Flat-ground pressure/thermal/glass contours and map fit: `ImpactPreview`/`MapScreen`. Notes qualify shelter/material/terrain limits. Carrier miss is sampled in preparation and retrieved by `StrikeService`, with actual-impact fallback rather than a promise to snap unexpected collisions to cached geometry.
- Structural support, original coarse material/cargo, clean-save persistence and streaming: inspected `RuinMatter`, `RuinSupport`/ruin rules and `LedgerData`. Capture serializes block-entity contents, deposition restores them, completed pieces retire into totals, and the real save wrapper streams a frozen live owner while mutation pauses. Notes say normal save/reload; they do not promise power-loss atomicity.
- Compact terminal head/wake and terrain/haze hiding: `IcbmArrivalRenderer`/`IcbmArrival` use the existing depth-tested effects path and attenuation. No arrival-whistle call is introduced; wave sound remains in the nuclear sound path. Source support is not a new visual/audio acceptance run.
- Deferred cloud work and shader warmup: `Thermal.drift`, `CloudModel.shear`, `GroundDust.support`, `FxLayer`/`PallPass.cold`. Notes promise the mechanisms, not elimination of every first-frame stall or all eager work.
- Standard saved-world distant delivery: `RuinSettle` feeds ordinary scar replacement and observes saved chunk scar receipts. The notes explicitly permit updates after the wave, do not claim immediate horizon publication or a bounded convergence time, and do not describe this already-existing mechanism as wholly new. No LSS/Voxy end-to-end run was performed here.

Final pin rechecked; worktree remains clean. Findings apply only to the head above and must not be interpreted as approval of later integration changes or publication.
