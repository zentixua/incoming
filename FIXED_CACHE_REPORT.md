# MT-RESULT fixed fracture-cache budget — 4 November 2026

## Source

Final component `768e68c6898073418cb70910d2b0e730a982965b`, `codex/nuke-fragment-cache-budget`, fresh from accepted plan base `a5b7ac60b9d092596f27939b163e221a766b39a8`. Production-only first compilable commit `ac0865eb4`; numeric test follow-up `768e68c68`. Worktree `/mnt/data/projects/incoming/mod/run/wt-MT-RESULT`.

The diff changes ONLY RuinResults.java and adds RuinFragmentCacheBudgetTest.java. No context, cache map, worker, plan, existing test or other source edits. The richer per-entry experiment948628962 is not an ancestor and was not revived.

## Exact contract

`RuinResults.tryReserveCache(long limit, long room)` returns nullable `CacheBudget`. It charges `limit + OWNER_BYTES` with OWNER_BYTES128, including a zero-capacity/empty cache. Negative limits throw IllegalArgumentException; insufficient room or scalar/global counter overflow refuses without changing ownership. Caller supplies current shared free room on the server thread. Global counter claim uses the same atomic CAS as result admission; refactoring that CAS changes no result API or Lease fields/lifetime.

`CacheBudget.bytes()` returns the current full charged amount including metadata, then0 after close. `release()` and AutoCloseable.close() are idempotent, synchronized, and refund exactly once. The budget holds only one primitive long; it retains neither payload, map, context, lease nor world. Capacity stays fully charged for the budget lifetime, independently of map population/eviction and of every result Lease. There is NO per-entry carving/splitCache/token API. Root clears/closes its map before closing this scalar budget.

`RuinResults.producing(Lease, Callable<T>) throws Exception` installs the current producer only for task.call, then restores the outer producer or removes the ThreadLocal in finally. `currentProducer()` returns that lease or null. The lazily initialized private nested ProducerScope owns the ThreadLocal; no static context/world registry is introduced. Success, checked exceptions, cancellation and Error all run cleanup. Nested scopes restore correctly and another thread does not inherit the lease. Caller wraps actual budgeted task.call, returns from producing, then markResult; the actual pool exit callback must run after finally cleanup. Generic callable submissions are unscoped and cannot implicitly borrow the cache.

Every existing result Lease still reserves all nine temporary fragment maxima through actualExit. That already covers a worker's borrowed/evicted/context-closed fragment references; this component adds no cache-owned reference or mutable cache state to Lease. The fixed cache budget separately owns retained map data between workers. Root owns actual entry/borrow/eviction/context-cancel/late-publication correctness and comparison measurements.

## Verification

All jobs via tools/laptop_job.sh, explicit SESSION_TREES=/mnt/data/projects/incoming/mod/run/wt-MT-RESULT and unique INCOMING_GPU_OWNER=MT-RESULT-FIXED-CACHE-20261104. Four CPUs,2GiB Java, no GPU/real server/profiler/desktop use.

- compileJava PASS6s, first source checkpoint ac0865eb4.
- Final three focused unit filters PASS9s: existing RuinResult groups19, temporary fragments6, new fixed-cache budget/scope11 =36 total, zero failures/errors/skips. Source/test compilation was part of this job.
- New tests: fixed full empty/zero cache charge, scalar overflow/refusal, shared atomic counter overflow against result admission, independent cache/result release in both orders,16concurrent close threads (each100iterations), primitive-only budget/unchanged Lease inventory, scope success-before-mark, nested success/failure restore, checked/cancellation/Error cleanup, cross-thread/generic-callable isolation, running cancellation that verifies scope clears before actual exit.
- Existing25 tests are unchanged and pass.

No fullbuild/GameTest repeat/CI/PR/reviewer/subagents launched by explicit component scope. Root owns the actual borrowed-reference/eviction/context-cancel/late-publication GameTests and fresh second producer review with F1/source-progress work. Numeric component tests alone do not establish a working LRU map, actual cache hits, whole-city/1Mt timing, complete JVM heap or512MiB whole-heap bounds. Root's760case cached/uncached corpus is independent evidence and was not rerun here.

## State

Production/test commits pushed; clean checkout. Owned compile/unit jobs completed and collected, no active incoming-job-mt-result-* services. No owned GPU/server/CI/review resource. Explicit component handback returns RuinResults ownership to MT-S2 for integration and review.
