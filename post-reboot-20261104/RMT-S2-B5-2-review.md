# Independent second review: PR #565 serious fixes

Reviewed HEAD: `4ec7e0e626c7b389c72aa4a5222712ccf72fb688`.
Reviewed diff: `63e759b25..4ec7e0e62`.
Reviewer: fresh independent Codex RMT-S2-B5-2 session, 4 November 2026.
**Changes requested: one serious admission/accounting defect in the new progress path.**

Read current TEAM/context/BOARD/live, repository instructions, applicable area rules and the first independent findings as bug reports. Did not read author handoffs, PR description or MT-S2 conclusions. Static review only: no production edits, new test/probe execution, delegation, merge, heavy jobs, GPU, client, desktop or server operations. The review worktree is clean at the pinned detached HEAD. MT-S2 retains implementation and delivery ownership.

## Serious finding

### [P2] Polling the cold stores after the progress capacity check can spend the same headroom twice

Primary reference: `mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinMemory.java:28-31`, specifically the newly added `NuclearWorld.get(level)` at line 30.
Related references: `NuclearWorld.java:108-117`; `LedgerLoads.java:101-105`; `RuinMatter.java:386-390`; `RuinSupport.java:80-89`.

`reserveProgress` takes a resident-memory snapshot and checks the requested bytes, then calls `NuclearWorld.get` before charging them. That getter is an admission operation: it polls the sibling crater/progress stores, and `LedgerLoads.poll` can reserve their entire measured import workspace. Both checks then succeed against the same previously free bytes. The progress reservation is returned without rechecking the updated resident count.

Concrete state and execution trace (static reproducer, not an executed test):

1. A dimension has writable resident material and a sibling cold crater/progress placeholder. Its scan is complete, but import has not been admitted. Total cross-dimension resident ownership is `PLANS_CAP - R`; the sibling import requires `R` bytes. For example, use `R = 64 MiB` and a 32 KiB progress request.
2. `RuinMatter.get(level)` at line 27 only polls the material store. The sibling remains uncharged. Lines 28-29 see 64 MiB free and accept 32 KiB.
3. Line 30 calls `NuclearWorld.get`, which polls all three stores. The sibling's `LedgerLoads.poll` sees those same 64 MiB free, adds `R` to `materialReservation`, and queues its decoder.
4. Line 31 then adds the progress request. The returned live reservation takes the shared ownership estimate to `PLANS_CAP + 32 KiB`. No concurrent mutation of server state is needed.

This state is reachable in the actual cold-drain tick: its initial poll refuses an import just short of room; one transport batch frees enough room; the next support unit calls `reserveProgress` with the newly sufficient headroom. It is also reachable when a scan finishes after the tick's initial poll. The source-release caller is not limited to 32 KiB: it passes `removalBytes(piece)`, including three times the saved block-entity cargo size. If a sibling scan becomes ready between the outer graph reservation and that call, the duplicate admission can exceed the cap by that payload-sized request, then remove the source under the invalid reservation. Thus this is a bypass of the shared admission boundary, not merely a diagnostic counter sampled after release.

Fix direction: resolve/poll `NuclearWorld.get(level)` before measuring headroom, then check writability, take the resident snapshot and charge the reservation without another admission-capable getter between the check and increment. Retain the resident-only calculation so unknown disk ownership still permits bounded continuation progress.

Regression needed: leave a sibling scan complete but uncollected, arrange headroom sufficient for either import or progress individually and insufficient for both, call the real `reserveProgress`, and assert the shared count while the returned reservation is live. Include the support/source-release route or a controlled scan-completion boundary if covering the cargo-sized case. A refused reservation must leave the source and handoff untouched.

The new `loadedTransportFreesColdImportWorkspace` does not catch this: its direct progress reservation runs while the sibling import is still 64 KiB short of room, and its resident fixture has no support seeds. Later transport therefore never calls the progress helper at the point when import becomes admissible. The final assertion is after those temporary reservations would close.

## Original findings and regression assessment

- **Original transport/import deadlock:** the blanket return now runs a loaded material ledger first (`NuclearWorld.java:415-419`). `RuinMatter.work` still checks `writable`, so a frozen save is not mutated. The new drain regression invokes real `NuclearWorld.tick`, places real pieces in a real level, reads a real sibling storage file through `LedgerLoads`, and keeps its artificial pressure reservation fixed until cleanup. This exercises the originally missing dependency; capacity is supplied by deposition rather than a manual refund. Support continuation now uses resident-only admission across dimensions, subject to the defect above.
- **Original crater aggregate loss:** `CraterMatter.read:145-149` now adds all eight saved fields into existing totals. Streaming `LedgerReader` retirement and normal `CraterMatter.load` share this method. The new fixture exercises both actual NBT field orders through the streaming scanner/reader and checks 17 / 45,900 totals after another save/load. No remaining serious issue found in that fix. The second round trip uses the compound overload, rather than the asynchronous file wrapper; it still traverses the corrected aggregation method.
- Traced the unchanged frozen-save wrapper, import reservation/publication, scanner/reader, cross-dimension resident/cold accounting, ordinary new-removal admission, support graph/release, and prepared/parked-plan ownership. Ordinary `reserve` still closes new plan/crater removals behind unknown stores. Support captures before source replacement and publishes after successful removal; the new reservation must preserve that boundary.

## Evidence and limits

The supplied raw log `/mnt/data/projects/incoming/mod/run/wt-MT-B2/mod/run/mt-s2-b5-fixes.log` reports **all 46 required tests passed** (line 351) and `BUILD SUCCESSFUL` (line 377). It includes `nuke_ledger_drain`, `nuke_ledger_totals`, cold/save/legacy/parked coverage and the selected memory/material/support/lifecycle/settlement batches. These are owner-run results inspected as raw evidence, not tests run by this reviewer. The prior scanner probe's output was also read; it establishes the original workspace/live-size mismatch, not this new finding.

No new CI or owned laptop job was launched. This second review does not judge visuals, whole-JVM heap bounds or 1 Mt completion time. No additional review round is requested for cosmetic changes.

## В память

Updated shared ledger-review memory with the admission ordering rule: resolve admission-capable owner getters before checking and charging headroom. A server-thread-only helper can still double-spend room through reentrant cold-store polling. The finding is pinned to this SHA.
