# RFLAKE2 independent review

Reviewed exact diff `36c9b5bdd..42f6387b4` in clean detached worktree `/mnt/data/projects/incoming/mod/run/wt-RFLAKE2`.
Full head: `42f6387b4c68f51a75ba5c829535fcc4b2cea640`.
Reviewer session: fresh Codex, explicitly launched with `gpt-6.1-sol`, reasoning effort `high` (verified running process).
Scope: review only; no source edits, builds, GameTest/server/GPU jobs, PR actions or external contact.

## Verdict

No serious blocking issues found in this diff. One optional test-coverage improvement below.

## Optional finding

- **Cover a section whose deadline is strictly after detonation.** `mod/src/devtest/java/ua/zentix/incoming/gametest/DhGameTests.java:603` fixes both modes to a 1 kt / 0.25-scale fixture; `:667-670` clamps their deadlines to detonation (the budget mode explicitly requires this). The stationary-clock mode therefore meaningfully checks exact detonation-tick release and the budget mode checks whole-section progress, but this geometry does not exercise waiting for a future `sectionDue - LEAD` boundary. An additional larger/slower-front fixture or focused schedule test with `expected > d.gameTime()` would catch a regression that sends every prepared section immediately at detonation. This is an existing coverage limitation, not a production regression or a merge blocker for the budget/isolation fixes.

## Correctness and lifetime assessment

- `FrontLods.java:81-83,111-113,266-273`: the injected supplier belongs to one FrontLods/preparation, is non-null, and replaces only the send-budget interval. Default production behavior remains `System.nanoTime`. Game-time deadlines, actual DH retention/accounting timestamps (`:143-144,312`), worker clocks and other preparations stay independent. No static override or retained world callback was introduced.
- `NuclearPrep.java:689-697` and `NuclearWorld.java:97-100`: lookup targets an existing preparation by strike; a missing preparation reports failure instead of silently installing a global clock. Handoff retains the same preparation/front instance. Removing the preparation removes the injected closure with it.
- `DhGameTests.java:609-626`: clock installation follows completed async copy prerequisites, before the detonation is armed. The stationary mode strengthens the former wall-time-sensitive +2-tick assertion to equality. The charged mode advances by a full send budget on each received copy and checks section atomicity, no duplicate chunk delivery, and one section on each successive tick; existing volume/accounting/failure assertions remain.
- `LedgerStorageGameTests.java:35-42,154-160`: the current fixture owner is unloaded before the previous SavedData owner is restored, in both dimensions. `RuinMatter.unload` releases its own recorded material ticket without deleting saved material. The subsequent `NuclearWorld.get` refreshes the live references; `LedgerLoads.Load.poll` refuses stale cache publication and keeps an in-flight read charged until it finishes. No production shutdown assertion is bypassed.
- `LedgerStorageGameTests.java:44-69`: the added regression acquires and observes a real material ticket through the imported ledger, then checks its absence and restored cache identity. Cleanup is registered before cache replacement and applies on failure as well as success.
- `RuinBackgroundGameTests.java:387-422`: shutdown still must report success, stop all ruin threads, empty all queues and release every matching nuclear ticket. Changing the ticket count to a descriptive list preserves the assertion while identifying an orphan's actual owner. Configuration restoration and clear now run through the completion listener on assertion failure/timeout too.
- `RuinBackgroundGameTests.java:1208-1234`: worker saturation is at the highest (SEEN) admission priority, so lower-priority work cannot race the send-gate observation. The latch is released only after the post-wave hold is observed, and failure cleanup also releases it before clearing nuclear work. Success still requires prior withholding, a scar, send-queue readmission and actual glass removal.

## Evidence inspected and verification limits

Only diff, surrounding code, current workflow/rules, canonical memory and the four allowed raw logs were consulted; no PR description or author's report/session conclusions were read.

- Original `MT-L2-p2-ci-shard3.log`: line 1192 reports the front copy 3 ticks after its expected tick; line 1797 reports one surviving nuclear ticket after shutdown.
- `diagnostic-shard3.log:1685,1696`: the residual ticket is `incoming_nuclear_matter` at chunk `[4,0]`, consistent with an imported fixture owner being detached from the SavedData cache. This supports fixing fixture lifetime rather than excluding material tickets from shutdown checks.
- `focused.log:175,383`: six selected tests ran and passed, including both front modes, cold import cleanup, shutdown and send holding. The raw log is evidence for that run, not proof that this exact committed tree passed all CI.
- `red.log:111,151`: the cleanup and front-budget regression assertions fail in the supplied red run. The log alone does not establish which mutation or tree produced it, so this review does not assert a specific mutation-to-failure causal proof.
- `git diff --check 36c9b5bdd 42f6387b4` passed; the review worktree remains clean.

I did not execute runtime tests under the review-only restrictions. Full final-head CI/build acceptance remains with FLAKE2; this report supplies the independent code-review result only.

## В память

Recorded the durable fixture lesson in canonical memory: replacing a RuinMatter SavedData cache owner must release the displaced owner's live material ticket; deterministic FrontLods budget tests must leave retention/worker/game clocks real. Candidate references are pinned, not a statement that this head is merged.
