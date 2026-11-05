# RCI8 independent source review

No serious finding in the assigned two-file diff. This is bounded source-review clearance, not CI, integration, production or runtime-readiness acceptance.

Recorded: 2026-11-05T05:33:26.223157+00:00. Fresh independent Codex session; process census confirmed `gpt-6.1-sol` with `model_reasoning_effort="high"`.

## Pins and independence

- Base: `f506c1eae6e868f7a6a6256caac581d6f6534a38`.
- Head: `2e9717de77fa0a2d19a1481ff544ec06628eddaf`.
- Worktree: `/mnt/data/projects/incoming/mod/run/wt-RCI8`, detached HEAD; clean at entry and after source/evidence inspection.
- Exact diff: `.claude/rules/gametest.md` and `mod/src/devtest/java/ua/zentix/incoming/gametest/NuclearGameTests.java`, 14 insertions / 7 deletions. No production source change.
- Read TEAM, context, newest live entries, only the BOARD entry containing RCI8 ASSIGNMENT, repository AGENTS/applicable rules, canonical memory index and relevant historical fixture-lifetime facts. No LEAD4/RT8/MT-S8 inbox, checkpoint, author report, PR body, conversation or transcript was read. Historical facts were checked against the pinned source, not used as a verdict.

## Source reasoning

1. `NuclearGameTests.java:1655-1664` registers completion cleanup before the first ticket acquisition at 1665. Every ticket tuple created by this fixture is covered: anchors radius 0; C/D radius 0 and radius 1; E radius 0. The additional radius-0 removals cover failure before the normal drop/reacquire sequence. Removing a tuple already absent is safe. No blanket AreaLoader clear or shutdown-counter waiver is introduced.
2. `StrikeGameTests.java:3713-3733` invokes this registered Runnable on both testPassed and testFailed. Native `GameTestInfo.tick/startTest/tickInternal` routes ordinary setup exceptions, sequence failures and timeout completion through those listeners. The callback therefore covers the relevant failed-test exits as well as success; abrupt JVM/process termination is outside this guarantee. A forced-failure run was not supplied or started.
3. `NuclearStrikes.java:171-175` clears events and nuclear work; `NuclearWorld.java:400-420` unloads the current material ticket, cancels producers and clears queues/contexts. `RuinMatter.java:262-267` unloads runtime ticket/search state without deleting saved moving/support records. `RuinMatter.request` at 243-252 can acquire another ticket; `NuclearWorld.tick` retrieves and works material independently of active strikes (541-546, 586-587). Thus ordinary nuclear clear deliberately preserves material and cannot isolate this finished fixture by itself.
4. The subsequent `RuinMatterGameTests.clearFixture` (`RuinMatterGameTests.java:25-28`) unloads the current ledger and installs an empty SavedData owner. Clear-before-replacement stops further fixture producers; replacement prevents preserved material from resuming next tick. The helper is devtest-only. `NuclearWorld.get` refreshes replaced ledgers (115-122); tick also fetches material before working it. Its direct `matter` field may remain stale until a getter refresh, so this is not an all-owner accounting/quiescence fence for a later fixed-pressure fixture. Pending worker/I/O retirement can likewise outlive clear under existing ownership accounting.
5. Teardown is dimension-wide nuclear teardown, consistent with the existing clear and this singleton `nuke_stripes` batch. It runs after the C/D/E loading, actual glass-removal and scar assertions at 1697-1705; those assertions, real strike path, clock pacing, 60-tick drop interval and 1200-tick timeout are unchanged. Discarding leftover fixture material is appropriate here because settlement/save persistence is not its assertion. This helper must not be generalized to concurrent same-dimension nuclear fixtures or used before persistence/settlement assertions.
6. `.claude/rules/gametest.md:12-19` accurately extends the isolation warning to this real-strike lifecycle fixture and distinguishes material-ticket release from ledger retirement. It does not change gameplay clear semantics.

## Inspected raw evidence and interpretation

- Original CI41966271699 shard1: lines 629/648 place nuke_stripes before salvo_busy_tickets, with dh_far_zone_proto between them; line 776 reports salvo projectiles gone, release timestamp -1, one `incoming_nuclear_matter [861100, -103705]` held, and zero queued hits. Lines 2927-2929 show 132 completed / one required failure. This establishes the observed foreign material ticket at failure, not every historical cause.
- Private failing prefix: lines 277-300 show entry to nuke_stripes with no pending material, 9,677 pending after it, and 4,628 pending plus a material ticket entering salvo. Lines 427-432 reproduce the same target failure and 15 completed / one failure. This supports cross-batch material contamination in this controlled run.
- Private successful prefix: lines 288-299 still show 9,373 pending at the nuke_stripes after-hook / next before-hook, then zero material and no held areas after dh_far_zone_proto. Lines 319-323 show salvo's areas released at 329 ticks after launch and all 15 required tests passing; line 349 is BUILD SUCCESSFUL. Do not interpret the earlier nonzero observer row as proof cleanup failed or as an instantaneous after-cleanup sample.
- Listener order explains that observation: cached Minecraft/NeoForge 21.1.264 source (`sourcesWithNeoForge_0e9be3e3bbc647eb9af1266fec36029e4755faff_output.zip`, provenance txt inspected) has GameTestRunner's tracker listener run the batch after-hook and start the next batch at lines 97-125, before this later-registered afterTest listener. GameTestInfo iterates its listeners at 108-113. GameTestTicker uses a copy-on-write test list, so newly added next-batch tests are not invoked in that current iteration. There are no repository before/after-batch hooks in the reviewed devtest directories. Cleanup completes before the next test body/world tick, although batch observation and structure setup can precede it.
- Read the private replay source, prefix list, init script and launcher. It filters/reorders 15 single-test batches, wraps batch observers and replaces only the two grid-origin `nextIntBetweenInclusive(-14999992,14999992)` results while consuming the original draws, then restores the original random source. This pins origin and batch order; it does not fix later RNG, worker scheduling, timing, or reproduce the complete CI shard. The observer calls live ledger getters/accounting and formats area descriptions, so it is not a strictly passive or continuous trace. Raw logs do not themselves embed the compiled source SHA: their pass/fail labels are not independent final-head provenance.
- Inspected owner `validate.sh` and a snapshot of `validation.log`: it had reached Gradle `:test`, with no completed BUILD SUCCESSFUL/final normal GameTest summary in the inspected snapshot. The script requests build then normal `nuke_stripes|salvo_busy_tickets` checks without the private init script. Their completion/final source linkage and PR605 CI are LEAD4's gates; no pass is claimed here and no waiting/poll loop was started.

## Evidence identity

- `/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s8/ci-f506-shard1.log` — SHA-256 `f38da51549ca1c667a31e0338191ebe2158e876bc0ed9d0cd4b0576fd4552bb2`
- `/mnt/data/projects/incoming/mod/run/wt-CI-S6/mod/run/ci-material-ticket/prefix-red.log` — SHA-256 `353a8b5fee57f0f32aa187f50c810812cd08d5c99ff1c97e80a82e93bae0455a`
- `/mnt/data/projects/incoming/mod/run/wt-CI-S6/mod/run/ci-material-ticket/prefix-green.log` — SHA-256 `560d0aebfb439bed14e06d91e2184195cccce962113ce4e5c71a502e870744c3`
- `/mnt/data/projects/incoming/mod/run/wt-CI-S6/mod/run/ci-material-ticket/prefix.txt` — SHA-256 `c9216fc1d708c02c549ac8cc125a3c7c8c9fa0fe788a672da13f56900f201f3e`
- `/mnt/data/projects/incoming/mod/run/wt-CI-S6/mod/run/ci-material-ticket/replay.init.gradle` — SHA-256 `0ce8395bd8d04b5c13cd81eccae5ee985dc686b108336c84c1559cceb71c7777`
- `/mnt/data/projects/incoming/mod/run/wt-CI-S6/mod/run/ci-material-ticket/run-prefix.sh` — SHA-256 `e83fada9536fa2bce0905e7af1ccce333ca199c5fc1c8e3d3f198b2d553835aa`
- `/mnt/data/projects/incoming/mod/run/wt-CI-S6/mod/run/ci-material-ticket/private-src/ua/zentix/incoming/nuclear/world/CiS8Replay.java` — SHA-256 `296c5d9ecb1c47cd1705f746bde83dd4b3249c41e56fc6c0ed19ba0da673754a`

## Limits and resource handback

No source edits, branch switches, commits, JVM/build/tests, heavy jobs, GPU, remote/server/UI operations or helper agents were started. Only the review report and append-only handbacks/status/memory notes were written outside production source. LEAD4's observed ci8-validation service and pr_ci.sh process were not touched. Final checks/CI/merge remain LEAD4-owned; separate nuclear source remains MT-S8-owned; integration/runtime/private delivery remain RT8-owned.

RCI8 has no owned jobs, clients, servers, GPU lease or helper processes. The RCI8 review assignment is complete and its fourth agent slot is explicitly returned on this session's completion. No tool/model capacity failure occurred.
