# MT-CHECK fixture diagnosis — 4 November 2026

Source: `5c75254548dd7803d08d4ec62b82fc018146ab22` on `codex/nuke-core-fixtures`, based on exact `7c28de912`.
Only three devtest files changed. Production, release and server state are untouched.

## Findings and bounded changes

- `FlashGameTests.preparedStrikeMarksOneLayer` consumed its 300-tick preflight without pacing, despite asynchronous resource-policy/layout/planning. `NuclearPrep.handOff` intentionally refuses an unfinished layout. The fixture now paces until its exact strike's real preparation-ready log. The original flight, physical wall/scorch/shadow layer, event time, handoff log and retirement assertions remain; no timeout increase. Registered cleanup also runs after unrelated assertion failures/timeouts.
- The DH section-budget sink previously counted every `ProtoChunk`, including ordinary FarLods/saved-update paths, and charged them to the FrontLods-only clock. Exact CI585 reported FrontLods built/sent164 with no copy failure, but the assertion counted an additional section at9068 after the normal9016..9032 burst. The fixture now observes the actual `FrontLods.send` caller through read-only StackWalker. Same whole-section/deadline/consecutive-tick/once-only/count-equality assertions. This is the legacy DH fixture still present in the clean composition, not a restored LSS publisher.
- `NuclearGameTests.ruinsPreparedDuringFlightFallWithFront` formatted state after `NuclearStrikes.clear`, concealing the actual remaining plans/tiles. It now formats before registered cleanup and paces until the first actual preflight plan. Physical glass/front assertions and original850/900-tick deadlines are unchanged. The owner's earlier release-deadline failure was NOT reproduced locally; no production cause/fix is claimed.
- `RuinSettleGameTests.diskAdmissionNeverLoadsSynchronouslyOrGeneratesPartialChunks` passed in every local focused run. Temporary diagnostics showed all dimensions idle, admission true and266052864 bytes of room; preserved diagnostic patch/logs. Source returned exactly to baseline. Earlier failing authority fixtures and cross-dimension preparation admission were investigated as a hypothesis, not proven as a persistent leak. LEAD2 subsequently reported exact integrated83d97edea CI41831615353 ALLGREEN, including settle_load andflash_prep, plus153 local integration GameTests. No speculative settlement cleanup/bypass was added.

## Executed checks

Every launch used `tools/laptop_job.sh`, explicit unique `INCOMING_GPU_OWNER=MT-CHECK-20261104` and `SESSION_TREES` for this checkout.

- `diagnostic.log`:5/5 PASS; baseline assertions plus temporary observation, settle_load/flash_prep/inputs_clear/inputs_forget/lifecycle_pool.
- `authority-red.log`:2/2 PASS on core7c (core authority tests differ from control composition); this filename is historical, NOT a red result.
- `focused.log`:5/5 PASS; flash_prep/settle_load/nuke_prep/prelaunch_surface/lifecycle_memory.
- `ci-pace.log`:6/6 PASS,28s Gradle; `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4 -Xmx2g`; dh_front/dh_front_budget/flash_prep/nuke_prep/settle_load/prelaunch_surface. Both DH fixtures observed separate ordinary copies. Held results:164 copied/sent,21 sections each; all original exact assertions pass.
- `dh-red.log`: attempted reverse control restoring the original DH source only;1/1 PASS. Thus no deterministic local red reproduction is claimed. Final DH source restored byte-for-byte, SHA2567ccf6f8f684502568264ddace4719c120749a426c27de58a01fa88f73d7251ed.
- `git diff --check` passed.

Final tested source differs from the6-test run only by removing the temporary settlement diagnostic logging. All owned jobs ended and were collected; active incoming-job-mt-check-* units0 at12:56:19UTC. No GPU, desktop, Crafty, PR, CI watcher, main merge, release or extra reviewer was used. Task-specific instruction waived additional reviewer/PR for simple fixture changes; integration review/CI ownership returns to MT-S2/LEAD2.
