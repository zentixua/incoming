# RPROD-S2B final producer review

Final source verdict: **no remaining serious finding in the reviewed producer/cache scope at corrective head `a76c115eddc3d7f8a8cd5efd1ff615833687c9c7`**. Initial652 had two P2 findings; both were corrected and inspected within this same authorized second round. First-round cold-source F1 is also addressed for its demonstrated state. Full build/touched union/CI and actual acceptance remain owner gates; the old97-GT union is red and is not reclassified by the focused receipts below.

Initial reviewed pin: `652324ecbe5a6452afb0d8305085a26e1711207b`.
Correction range: `4e1f8f602..652324ecb`; full surrounding producer range: `2a42059da..652324ecb`.
Fresh independent Codex GPT-6.1 Sol / xhigh session, `wt-RPROD-S2B`. The initial clean checkout was at `7bdb5b2c1`; after cleanliness/process checks it was explicitly detached at the requested exact pin. No author conversation, handoff or PR #589 description was used to form these conclusions.

Initial verdict at 652: **two P2 findings in the newly added cache scope; no P1 established**. Both were sent promptly to MT-S2 and LEAD2. The corrections and final disposition are recorded below; no third old worker-core review was performed or requested.

## F1 — [P2] Close cache ownership in owners of fresh diagnostic contexts

Primary locations: `mod/src/main/java/ua/zentix/incoming/nuclear/world/RuinPlanner.java:142`, `:191`, `:236`. New resource acquisition: `RuinContext.java:925`–`:931`.

`planFresh(..., background=true)` and `planWithWindow` allocate an unregistered `new RuinContext`. Their worker now acquires an optional fixed cache reservation of 16 MiB + 128 B. `await` collects/discards the plan's result lease but never closes the context's independently owned cache. These contexts are absent from `NuclearWorld.ruins`, so ordinary idle sweeping/world shutdown cannot call their `cancelTasks`. `RuinResults.BYTES` has no GC-based refund: making the context/cache unreachable or shutting down the pool cannot subtract the reservation.

Bounded source reproduction: with otherwise ample room, record `RuinResults.bytes()`, call `RuinPlanner.planFresh(level, d, chunk, true)`, wait for its synchronous return and discard references. The counter remains baseline + 16,777,344 B. Repeat on fresh contexts: each cache admitted while room permits leaves another permanent global reservation, affecting subsequent fixtures and admission across dimensions/world sessions. Existing real callers include `RuinBackgroundGameTests.backgroundPlanMatchesServerPlan` (four fresh background contexts per call) and multiple disk/window comparison tests. This is a concrete **diagnostic/helper lifetime leak**, not evidence of an ordinary registered-player-context leak.

Correction belongs in the methods that own the fresh contexts, with cleanup on success and error/timeout. Do not indiscriminately close `ctx` in shared `await`: `testPlanOverFlash(..., way=1)` passes a registered context which has other users. A regression should repeat actual nonempty fresh helper plans, compare full physical/material output, and assert the global cache/result counter returns to its baseline, including failure cleanup.

## F2 — [P2] Include captured solver flags when validating a cached fracture

Primary locations: `RuinContext.java:739`–`:745`, `RuinFragmentCache.java:21`–`:26`.
Supporting locations: `RuinContext.java:203`; `RuinWindow.java:135`–`:145`; `Blast.java:272`–`:275`, `:450`–`:453`; `ChunkShot.java:327`–`:329`, `:418`–`:419`; `Collapse.java:120`–`:129`, `:178`–`:189`.

The cache validates only chunk coordinate and nine snapshot serials. The producing function also depends on the Grid's `blockDamage` and `treeFall` flags. `captureGrid` reads current config values on each capture, while existing valid snapshots retain their serials across flag changes. Those config values have no restart requirement and no handler clears this cache/snapshot generation on config changes.

Bounded source counterexample: warm a nonempty fracture under an admitted Grid with `blockDamage=true`; then create another Grid in the same context with identical snapshots and `blockDamage=false`. The cache returns the old nonempty `removed` array before reaching the new `Blast.solve` call. `Collapse` consumes those removed positions even when its own `blockDamage` is false (the flag only disables the additional remnants pass). The uncached 4e path would solve an empty fracture for the second Grid. A tree-fall flag change can likewise reuse old tree records. This violates same-input caching when current solver inputs change, without requiring geometry mutation, a tag reload or different terrain.

Include both flags in entry validation/keying (or use an explicit immutable policy lifetime consistently for all callers). Keep stale in-flight tasks from overwriting a different policy's valid entry as a false hit. Add same-snapshots/different-flags regressions; existing repeated/overlapping-cache fixtures exercise only the same flags.

## First-round cold-decoder finding and inspected source seams

The first-round F1 dependency is addressed for its stated cold-window state: `RuinMemory.sourceReadRoom` reserves one real worker at the maximum server-dimension height before opening each window source slot; the captured parser still relies on server-thread birth service, but cannot spend that allowance on additional window slots. `RuinInbox.takeForPublication` converts the completed slot into a source/copy/output owner, requiring only positive growth and preserving the exact head on refusal. Window, far plain, far archive and geometry/terrain collectors all use this transition, retain pending ownership through publication, and distinguish MEMORY waiting from absent/error sources. This is not an all-possible-payload progress or whole-JVM-heap theorem: plain reads remain an initial 96 KiB estimate, archives/window slots fixed estimates, and raw NBT/upgrade/codec allocation precedes the detached publication boundary.

Other inspected seams, with no additional established serious defect in the stated scope:

- `RuinResults.bytes()` is added once outside the dimension loop in both resident and worker-inclusive admission, alongside detached inputs; no global world/context registry was introduced.
- Result admission prepays the final plan maximum plus all nine private fragment maxima and owner overhead. Marking shrinks only plan ownership; private fragments persist until actual callable exit. EMPTY, failure, early cancellation, already-completed/uncollected results and counted-consumer transfer have separate terminal paths.
- Promotion transfers the same callback/callable and rebinds the result owner; collection uses `RuinWorkers.finished`, not cancellation/isDone as proof of execution exit. The old worker birth/height implementation was inspected only at these new caller/service seams, not reopened as a third core review.
- `RetainedPlans.put`, `ScarQueue.park`, applied-plan ownership and flash-plan ownership charge the consumer before releasing result ownership. Flash discard paths cancel their result lease. Detached/store/restore plans do not copy mutable result ownership onto copies or shared EMPTY.
- Snapshot admission preflights the whole capture window; current/retired snapshots, embedded archived old plans, both old-top bands and lazy local/global/ruined property arrays are included in the supplied element laws. Loaded shadow-height bands and stored-flash height preflight remain separate from raw source decoding.
- Cache payload accounting uses the actual fragment field/capacity law and a fully prepaid fixed capacity. Oversized fragments use the private task allowance. Duplicate concurrent fills, eviction and context close can coexist with borrowed immutable fragments because each borrowing producer independently retains all nine fragment allowances through actual exit. Per-entry ownership is not required.
- `producing` restores nested ThreadLocal scope and clears it in finally before marking/actual-exit callbacks. Numeric cache ownership retains no payload/context. Normal context cancel closes the cache before detaching inputs; closed caches reject late puts. The optional cache is attempted only after result admission and leaves a first-worker allowance plus the existing 64 MiB transition reserve, so refusal does not reject the already-admitted solver.
- Geometry is immutable per context (`bindEvent` changes only the event marker). Snapshot replacement/fingerprint changes and tag/data-map reloads produce different serials for fresh Grids. Old in-flight entries then miss against new stamps. The missing flag inputs are F2 above.
- No default physical law, material ownership rule, RNG source or storage schema was intentionally changed by the producer diff. The stale-flag reuse in F2 can nevertheless change physical output when settings change.

## Independently inspected raw evidence and limits

No tests, build, CI, server, GPU or nested agents were launched by this reviewer. `git diff --check` passed for both reviewed ranges; tracked source remained clean. Raw log results below are observations of supplied owner jobs, not independently rerun acceptance:

| Local raw log | Observed result / limit |
| --- | --- |
| `wt-MT-OPT/mod/run/mt-s2-source-collectors.log` | Compile/test phase succeeded in 11 s; GT 15/15 passed; wrapper exit 0. Actual SOURCE_PROGRESS starts with zero worker threads, admits 8 reads and retains 16,777,216 B startup room; 3 nonempty equal full plans finish in 37 callbacks with the other-dimension 484,156,928 B owner held. Sampled known estimate peak 533,364,736 B. This is a bounded fixed-pressure fixture, not a whole-zone or continuous heap maximum. The log does not enumerate the selected unit-test count. |
| `mt-s2-cache-check.log` | 10/10 GT passed; wrapper exit 0. |
| `mt-s2-cache-optional.log` | 3/3 GT passed; wrapper exit 0. Source shows actual six-fragment overlap, fixed-pressure uncached solver progress and borrowed-fragment eviction/cancel/late-put cases. No changed-flags or fresh-diagnostic-context cleanup case. |
| `mt-s2-input-fixture.log` | 9/9 GT passed; wrapper exit 0. |
| `mt-s2-producer-final.log` | Earlier build phase succeeded; the 92-GT union has **4 required failures** for cancelled/cleared/forgotten/expired actual Grid fixture assertions. It is not a final green union. Later focused fixture results do not turn that raw union green. |
| `wt-MT-CHECK/mod/run/mt-check-corpus/paired.log` | 760 cases / 3040 pairs / 1520 timed pairs, `fullEqual=true`; endpoint uses `view=Blast.SERVER`, `cacheMode=clear-only-blasts`. This is the older prewarmed-all-nine comparison, not actual new-LRU hit-rate, zone throughput, final-652 correctness or whole-heap proof. |

The old/new source-progress logs were not present at the specified `wt-MT-SNAPSHOT/mod/run` path during this inspection (only `source-progress/red.sh` was found); no missing raw result was inferred. Actual15 on other production and LEAD2 actual100/1000 work were neither operated nor used as final-652 acceptance. Private NBT/worlds/RAW_PATH data were not copied or uploaded.

Raw log SHA256, in table order (first five):

- `7bde11648e9dbb4a2bb6bf4a0d1b266f98cb36f0a87668d507048c1ec33eec80`
- `bcd87a912324adc4d00be44dbe1139d0ee281f5069b7980f9e1a5b7cba3da842`
- `661e3c8f5d0c2be19611c9157b077322981251646f693200a856811bd6429d5a`
- `4f0590e96121284d9e532b6d0fecea70e1790ba63014eed5d48b298ef7c41e8a`
- `9150ca76551e0b3b16db142f7e0a1d8c8f4d9f435f5e7d81de053d1669138202`

Corpus: `2a82b341347d89f3e6dedb107d88270bafd12de8fbf34b96bbdfe8dffdd8467c`.

## Current resources and ownership

Review source ownership remains MT-S2; integrated build/union/CI and actual acceptance remain LEAD2/MT-S2 as assigned. Own laptop jobs, test JVMs, clients, servers, CI waiters and subagents: none launched. No GPU lease acquired or released. Protected servers, other worktrees, source and user worlds/settings remain untouched. The two cache corrections were reviewed before the final explicit handback recorded below.


## Additional raw union receipt (2026-11-04 15:30:28 UTC)

The owner supplied `wt-MT-OPT/mod/run/mt-s2-producer-final2.log` for tree-identical normal-merge commit `167632a71`. Verified tree identity directly with `git rev-parse`; this is the same source/test tree as initial652. Build phase passed; actual97-GT union has **four required failures**, wrapper exit1: `cachedFallbackPublicationFailureCannotBecomeReady`, `coldDimensionsReserveBeforeDecodingAndResumeAfterCapacityReturns`, `highYieldHandlesWaitForCancelledActualBuilderExit`, and `cacheRefusalDoesNotBlockAnAdmittedSolver`. Do not call this union green. SHA256: `0131a28d2b56f2f93140ff5f925bd187c7f7b5fff22f278f9995bd1a625fbcda`.

Source distinction: archived collection computes `publicationBytes` before its inner error catch (`NuclearPrep:983`); a size-preflight exception therefore bypasses its local `failedSource` boundary. The production outer tick catch (`:649`) still records IO and drains the preparation. The direct-reflection fixture's thrown exception alone is not evidence of a production crash or false READY. The plain collector already has an enclosing catch. The owner is correcting this terminal-source collector boundary alongside the new cache findings; final changed code will be reviewed in this same round. The other union failures are owner validation work; no cause was inferred merely from their test names.


## Corrective source inspection within the same second round

Final corrective pin: `a76c115eddc3d7f8a8cd5efd1ff615833687c9c7` (contains `600944d60` and the fresh-helper correction). Inspected the complete `652324ecb..a76c115ed` changed code and new test callers via immutable git objects, without modifying the review checkout. Both range whitespace checks passed. This is continuation of the initially requested second/final review, including the serious new cache findings, not a new review round of old workers.

- **F1 closed:** `RuinPlanner.java:142`–`:151` and `:196`–`:212` now put fresh-context ownership in method-local try/finally and call `ctx.cancelTasks` on every exit. Shared `await`/registered `testPlanOverFlash` ownership is untouched. Closing the cache does not erase the returned plan's immutable old cells/height fingerprints. Success regressions compare full stored physical/material output, store/restore, current/sameBlocks and actual apply after close. Failure/timeout cleanup is established by the same finally control flow; an executed exception-path fixture is not part of the initial a76 evidence.
- **F2 closed:** `RuinFragmentCache.java:10`, `:22`–`:32`, `:34`–`:46` retain and validate both captured solver flags. `Grid.blast` supplies the same flags at get/put (`RuinContext:741`, `:745`). The extra Entry record is charged as 32 B in the cache's actual entry-size law before admission/eviction. A stale in-flight producer can replace an entry and cause a later miss, but cannot produce a false hit under another policy. The fixed cache plus private worker fragment bounds remain conservative and separate.
- **Collector terminal exception boundary inspected:** `NuclearPrep:983`–`:1002` includes archived source-bound preflight in the local failure boundary. On terminal preflight failure it consumes only the identical still-ready head. A transitioned publication is not polled twice; MEMORY refusal retains the same head and reading membership. The plain path has the corresponding identity-checked terminal poll at `:1066`–`:1072`. IO failure receives no absent-terrain completion credit. The modified assertions explicitly verify pending/read membership is drained after the injected terminal error.
- The held-pressure optional-cache fixture now keeps an independently inspectable numeric CacheBudget and asserts that owner's bytes remain held, rather than equating the entire process-global result counter with that one owner. Its actual solver still runs with pressure retained and cache refused.

No remaining serious source defect was established in those corrections. No optional style request is a merge condition.

### Corrective raw receipts inspected independently

`wt-MT-OPT/mod/run/mt-s2-cache-policy.log`: all10 required tests passed, wrapper exit0, Gradle30s. The included source test exercises true/true → false/true → true/false → true/true on the same snapshot arrays with an actual30-block tree, compares ReferenceCollapse and material-aware plan equality, and requires a cache. Eviction/lifetime requires the initial borrowed entry to be actually installed before eviction. Cold-source progress again finishes three equal nonempty plans with the fixed other-dimension owner held (39 callbacks, sampled known peak533,364,736 B). The cold-ledger/layout failures from the old union did not reproduce in this focused run; their full-union disposition still belongs to the owner.

`wt-MT-CHECK/mod/run/mt-check-fresh/red.log`: both actual fresh/helper cases fail at baseline0 → result/cache16,777,344 B on old167, wrapper exit1. Each expected-red case closes only its returned fresh context in test cleanup. `green.log`: both tests pass, each repeats three nonempty physical/material-equal results with result/cache/detached0, then applies the returned plan; wrapper exit0. The fresh-helper production/test files at component `c5f1eebec` and finala76 are identical (verified directly by git diff). Different test world coordinates mean raw changed-block totals differ between separate red/green runs; the unchanged expected-output assertions are evaluated within each run.

These are focused component receipts, not a final combined a76 build/union/CI result, an actual LRU hit-rate/zone throughput result, an arbitrary archive-size liveness proof or a512MiB whole-JVM heap certificate. The two earlier red union receipts remain red.

Corrective raw SHA256:

- `mt-s2-cache-policy.log` (run): `66ba8a8f1a8f73a4a4c361003c36ce59c2bc4c0deafa111d735a9c72bb913007`
- `red.log` (mt-check-fresh): `e450c245e09ec8802014a57bcfb1f89347228ee8f954a205846a4fc54be0a60d`
- `green.log` (mt-check-fresh): `a9b1b96092b1c408ea4a0b228a4f7d21a453f650398d747ca6f888a4dcc5c1a0`

## Final explicit handback — 2026-11-04 15:36:37 UTC

Handing back completed independent source review to MT-S2 and LEAD2. Source implementation/combined validation/merge ownership remains theirs. Review checkout `wt-RPROD-S2B` remains clean and detached at the originally requested exact652; correctivea76 was read through git objects. No source edits or commits were made by this reviewer.

Own laptop jobs, test JVMs, CI waiters, helper/review subagents, clients and servers: **none launched; none pending**. No GPU lease was acquired; final read-only status says **GPU is free**. No other owner's process/lease or the LEAD2 runtime runner was stopped or operated. Protected servers25565/25566, private NBT/worlds/settings and publication were untouched. Only the authorized review report, inbox/status entries and canonical factual memory were written.

В память: canonical `ruin-result-ownership.md` records explicit fixed-cache close for unregistered context owners, all captured solver flags in cache identity, and the exact unmerged corrective review boundary.
