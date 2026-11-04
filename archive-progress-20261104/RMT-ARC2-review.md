# RMT-ARC2 independent second scoped review

## Serious findings and disposition

No serious finding established in the scoped correction or cumulative archive change. The first-round P2 admission-counter defect is resolved at the reviewed head. This clears the second source-review gate for this change; it does not certify CI, integrated runtime, whole-JVM memory, or release readiness. No third review round is requested.

- Correction base: `9b93e305dd90e6f7d2928373fcf60000437b9969`.
- Cumulative base: `f9d1ac8ee13f612f7774d9a5e72dfb6a4e3c96e0`.
- Exact head: `5d431b337563be9f4fa7bae23e5af14ff535d80b`.
- Worktree: `/mnt/data/projects/incoming/mod/run/wt-MT-OPT`.
- Branch: `codex/nuke-archive-progress`, clean at inspection and final source check.
- Reviewer: Codex, fresh independent second scoped session. Completed: 2026-11-04 16:57 UTC.

## Complete admission and successive writes

`RetainedPlans.java:88–100`: for a plan with stable payload estimate B and encoder allowance R = max(4096, 3B), each admission now requires and subtracts R + B + 48 from the pump's local room. Those are exactly the increments counted by the preparation atomic I/O counter (R), `writeBytes` (B), and `writes.size()` metadata (48). The original resident plan and its map entry were already counted before the call. Subtracting the full increment for every accepted plan prevents the second write from reusing the first write's source/receipt headroom.

The pump starts by retiring completed receipts and optionally evicting already-written hot plans. Its input room was sampled before that retirement, so not adding these newly freed bytes back is conservative; it can defer a write until the next pump, but cannot over-admit it. Asynchronous completion only refunds R; B and the receipt entry remain counted until the server-thread pump retires them. Immediate completion is therefore also safe. A failed receipt retains the resident result and queues another write instead of certifying an archive copy. Writes of a replacement at the same key wait for the outstanding receipt; a successful old receipt marks a plan hot only if the map still holds that same plan.

`RuinSnapshots.java:39–56`: admission now includes R + 48. There is no additional `writeBytes` owner in this consumer: the original snapshot remains in the context until its exact successful serial receipt allows eviction. Each successive call obtains fresh resident room, including the preceding write's charge and receipt entry. When a successful receipt moves from `writes` to `saved`, its counted metadata stays equal or decreases if a saved entry already existed. Different-serial replacement requires another separately admitted receipt; failures cannot authorize eviction.

The asynchronous encoders capture detached payloads. The R charge remains in the existing NuclearPrep lifetime counter until I/O completion, including cancellation/close; changing the supplier does not move it into a disposable context-local counter. Source inspection covers these lifetimes; the new boundary fixtures do not experimentally exercise cancellation or failed I/O.

## Supplier scope and cumulative behavior

`NuclearPrep.java:555–558,616,863` gives existing plan writes resident headroom. The two snapshot archives at lines 748–749 and 882–883 use the same supplier. Resident ownership includes registered contexts/preparations/material across dimensions, detached worker inputs, result owners and resident worker workspace; it does not reserve hypothetical future solver capacity or the transition margin.

In the correction, the `RetainedPlans.spill` supplier at lines 746–747 and 879–880 is explicitly returned to `PLANS_CAP - memory(level)`. That field serves `load`, `probe` and `prefetch`; writes receive their room as the explicit pump argument. Thus the broadened room is confined to existing-cache writes, with plan demand/read-ahead admission retaining its previous policy. I checked all production pump/spill/snapshot-construction call sites.

The cumulative change still removes the source dependency that prevented an unpinned original from reaching disk when future solver reservations exhausted admission but resident write room existed. `RuinContext.trimShots` still respects pending-window/task pins and exact successful archive receipts. No snapshot serial, disk format, source-validation, worker-admission or plan-application semantics are changed by this diff.

## Boundary regressions and raw evidence

`RuinCacheAdmissionGameTests.java` registers a real world context with a production restored archive, fixed external pressure in the Nether, and two real nonempty plans or snapshots. It preserves the actual directory probe and inserts a gate into the ordered writer predecessor. Both receipts therefore remain outstanding while ownership is asserted. Separate batches, unique event IDs, game-paced async waiting and cleanup of the gate/reservation/context/archive string isolate the fixtures.

The initial room is the complete cost of both writes minus one byte. The test requires exactly one admitted write and no cap breach, explicitly funds the final byte, then requires both receipts and resident ownership exactly equal to 536,870,912 bytes. After opening the gate it waits for real completion and a lower resident count without refunding the external owner again. For plans, the second write is attempted in the same pump's loop, so this detects an incomplete local debit as well as a wrong per-write admission check. Snapshot calls re-evaluate the production supplier independently.

- `spill-boundary-red.log:113–124`: both fixtures admit two held writes and report 536,870,913 bytes, exceeding the cap by exactly one; both fail.
- `spill-boundary-green.log:113–126`: snapshot held count 536,819,847, plan held count 536,625,969; each has one write with gate closed. All three required tests, including archive progress, pass; BUILD SUCCESSFUL at line 151.
- The preserved `spill-boundary-red-test.java` is byte-identical to the reviewed boundary test. Plan fixture costs differ between red and green (502,432 versus 490,144 bytes); these runs use different platform positions/batch composition. The proof uses each run's actual computed cost, not identical plan sizes.
- `archive-red4.log:114–129`: fixed owner 530,276,862, resident 532,676,608, future admission 626,327,552; after 200 callbacks no write started and the original remained cached.
- `archive-green4.log:114–119`: the same pressure/input figures reclaim and recover the exact original after three callbacks. The corrected green boundary run also passes archive progress, with peak sampled ownership 532,727,674.
- `archive-final.log:361,387` records 28 required tests passed and BUILD SUCCESSFUL for the earlier validation. It is not the final corrected validation.

At the last inspection, `archive-corrected-final.log` was still at `:test`, with no terminal build/GameTest result. Its owner must collect that result. These raw logs contain no embedded exact source SHA/class hash; their source association is supplied evidence, not an independently authenticated exact-head run. I launched no tests and performed no CI/PR operations. Exact correction `git diff --check` passed.

The fixtures prove the counted admission boundary for these restored consumers and progress after real successful receipts. They do not prove sustained multi-strike throughput, normal-flight preparation under load, failed-I/O recovery, a clean process restart, material/plan application, or far-LOD convergence. The accounting cap remains a known-ownership estimate, not a bound on every JVM allocation.

## Production observation limits

I inspected the supplied raw `wt-LEAD2/mod/run/lead2/ownership-probe2/lead2-ownership2.txt` and `mod/run/mt-s3/probe/OwnershipAgent2.java`. The sample has an idle actual pool (one resident thread, no active/queued task), 435,036,688 snapshot bytes and 1,247 unpinned originals totaling 113,013,570 bytes without matching saved/completed receipts. It also has 348 pending windows and six retained completed task/result owners. Those independent owners are not free merely because the pool is idle.

The probe classifies existing fields/maps without invoking eviction/admission getters and uses `getChunkNow` for selected readiness observations. Its server-thread traversal takes 48,207,066 ns; it is not an unperturbed tick benchmark. This single sample supports the presence of originals needing archive progress, but supplies neither an exact denied-write headroom trace nor a post-fix drain measurement. The boundary tests establish the local correction; real integrated recovery remains LEAD2's acceptance work.

## Independence and handoff

Read TEAM/newest live, repository AGENTS/applicable rules, canonical MEMORY index only, the allowed prior independent RMT-ARC finding, exact diffs, surrounding code and raw evidence. Did not read author conversation, PR body, STATUS, BOARD conclusions, task-specific memory, narrative checkpoints or earlier inbox messages. No source edits, local test/heavy/server/GPU jobs or PR actions. Only this requested review file and an append-only completion message are written. MT-S3 retains delivery; LEAD2 retains integration/runtime ownership.

В память: no canonical memory changes during this read-only review. The durable boundary is recorded here: admit the complete counted increment for each write, debit it across successive writes, and keep read-admission policy separate from cache-write reclamation.

## Completed validation addendum — 2026-11-04 16:59 UTC

The previously pending `mod/run/mt-s3/archive-corrected-final.log` now ends with all 30 required GameTests passed, `BUILD SUCCESSFUL in 4m 21s`, and `laptop_job mt-s3-archive-corrected-final: exit 0`. The corresponding user systemd service is inactive/dead with Result=success and ExecMainStatus=0. The worktree remains clean at `5d431b337563be9f4fa7bae23e5af14ff535d80b` on `codex/nuke-archive-progress`. This supersedes the pending-validation observation above; the source-review disposition is unchanged. No new tests, source edits, CI/PR actions or runtime work were performed by the reviewer. CI and integrated acceptance remain MT-S3/LEAD2 responsibilities.
