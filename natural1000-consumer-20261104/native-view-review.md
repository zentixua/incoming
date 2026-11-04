# RB7ALL2B independent source review

## Pins and verdict

- Reviewer: fresh Codex gpt-6.1-sol / high session RB7ALL2B.
- Worktree: `/mnt/data/projects/incoming/mod/run/wt-LEAD2-NATURAL`.
- Branch: `codex/mt-natural-trial`.
- Base: `fc19f16d193905b39a27d1a899fde0a359a72b0a`.
- Reviewed final component: `cb65e39e0f602e7a7c994b26690295cb69a02953`, matching `RB7ALL2B-pin.txt` and HEAD before and after review.
- Exact delta: four files, 266 insertions / 31 deletions. Working tree clean. `git diff --check base head` passed.
- **Verdict: no serious source defect found in this diagnostic-only delta.** No production/main merge, runtime-cost approval, census-completeness result for the actual scene, or readiness claim follows.

## Independence and inputs

Read current TEAM/context, latest live, current BOARD assignment/explicit handback, repository AGENTS, applicable nuclear/nuclear-world/server-chunks/gametest rules, canonical memory index and diagnostic/accounting facts. Checked branch/worktree and live processes: LEAD4 was active; no B7ALL2 implementation process was running; this reviewer was the fresh Sol/high process. No implementation ownership was assumed.

Reviewed the exact git diff and surrounding MtB7Pending, ScarQueue, MtB7Metrics, NuclearPrep distance calculation and tests. Did not read author conversation, B7ALL2 handoff/RESULTS/SCHEMA, PR body, STATUS, or interrupted RB7ALL2 transcript/verdict. Disclosure: the required canonical diagnostic-memory file also contained a B7ALL2 checkpoint section; its assertions/test-pass claims were not used as evidence for this verdict. Every conclusion below is derived from the pinned code and direct dependency sources. Optional old runtime input was not needed or interpreted.

Direct dependency source: local `mod/build/moddev/artifacts/neoforge-21.1.264-sources.jar`, SHA-256 `613bb0eaaaac37a14810d75c5df22889d3321c69112266d6fe6c1cde61fa17b4`. Read ChunkTrackingView, relevant ChunkMap/ServerChunkCache methods, GenerationChunkHolder lookup, and attachment lookup API. This is the checkout's generated cached source artifact; no remote documentation substituted for implementation.

## Findings and severity

No blocking correctness, crash, admission, ownership or demonstrated budget defect found.

**Informational — traversal work counters are method-specific.** `MtB7Pending.java:132-143` counts only watched visible holders on the native path, whereas fallback counts all visited holders before the watched filter. `row` preserves old keys and appends `traversal`/`candidates` at lines 163-173, making this distinction observable. Treat `holders` as traversal work, not a stable denominator across methods. Relevant cohort measures (`watched_loaded`, `affected`, `scarred`, `pending`) retain their original predicates when complete.

**Informational — bounds establish honest truncation, not hard latency or fair partial coverage.** `visitViews` calls the callback for every square cell, including corners and duplicate-view cells (lines 85-103); `next` charges all of those toward the 20,000 cap and polls the clock every 32 cells (122-129). Candidate containment/union evaluation occurs before the callback, so one further cell's bounded membership work may run before stop. Fallback custom `contains`/override callbacks are also indivisible. A late final clock sample marks the census incomplete even if enumeration finished. These are cooperative bounds, not a strict <=1 ms execution guarantee. Enumeration restarts in the same order each sample: repeated partial prefixes need not reach later players, and their union/maxima cannot certify full coverage. No increase to the observer allowance was introduced.

**Optional test strengthening — movement coverage is helper-level.** `MtB7PendingTest.java:37-44` proves disjoint moved native unions and stop propagation through the traversal helper, but does not move players between two calls on the same observer's `census`. Current production implementation does rebuild its view array and Scan per call, so this is not an observed source bug. If persistent per-observer view caching is introduced later, add an actual same-observer moved-player census check.

## Source evidence

1. **Native boundary and union:** cached ChunkTrackingView lines 58-71 make default `contains` use `true`, while `isInViewDistance` uses `false`. Positioned bounds are centre +/- (distance + 1), lines 91-105; its enumeration filters default contains, lines 121-129. New lines 89-99 match that exact finite square and predicate. Each member emits `watched=true` only for its first containing view. EMPTY contributes nothing. Positioned is a record; unknown implementations select fallback instead of trusting their enumeration.
2. **Current visible-holder/FULL cohort:** ChunkMap lines 279-280 lookup `visibleChunkMap`; getChunks lines 875-876 iterates that same map. New candidate lookup therefore preserves the old holder-domain restriction. ServerChunkCache.getChunkNow lines 173-202 performs main-thread/cache/visible-holder/currentlyLoading/FULL nonblocking checks. GenerationChunkHolder lines 248-255 uses Future.getNow, not joining/requesting generation. `getChunkNow` can alter vanilla profiler/cache state; passivity means no world loads/admission/gameplay changes, not zero internal cache effects.
3. **Pending semantics:** lines 146-159 preserve current getChunkNow eligibility, nearest-horizontal ruin radius, existing scar >= event, pending even without a job, overdue only for positive `now - ScarQueue.due(d,pos)`, and explicit job_events cutoff. `ScarQueue.due` lines 733-735 uses centre wave arrival; retry due is not substituted. `mtB7Queued` lines 58-64 examines this event anywhere in the remaining bounded event list. Scar attachment lookup does not create a missing attachment.
4. **Fallback:** lines 51-60 require both all-native views and no additive seen override for the fast path. Otherwise current holder scan delegates to the existing `ScarQueue.watched`, preserving extra visibility even with real players. The only ScarQueue delta is the read-only override-presence accessor at line 580. No custom forEach runs.
5. **History/schema:** new Scan is call-local and no view/player/world/chunk escapes into retained observer fields or Census. Census retains scalars/strings. Applied/drop/unload/failure/clear history is unchanged. ScarQueue success callback remains after scar commit (1272-1274); dropPrepared/clear do not reset the observer. MtB7Metrics still selects only cached NuclearEvents and existing world attachment, retaining event/dimension/session/time provenance. New traversal keys do not remove prior row keys.
6. **Discriminating tests:** native unit expected sets come from Minecraft's own forEach, with negative coordinates, distances 2/8/16/32, overlapping/duplicate views and a duplicate-output assertion. The GameTest compares against an independently enumerated current-holder cohort, checks missing jobs, unchanged reservation/queue state, immediate and 32-cell time cutoffs, custom-view fallback that throws on forEach, an additive override outside EMPTY real-player views, candidate cap and player cap. Existing real material-refusal fixture distinguishes blocked, job-omitted and actual scar-committed chunks, and history after prepared/queue reset. These test assertions distinguish the relevant wrong implementations; their execution was not repeated by this reviewer.

## Limits and completion

No Gradle, GameTest, GPU, server, client, nested agent, new runtime experiment or PR/CI operation was launched. No author-reported test or CI outcome is accepted here. Build/test/CI execution and actual-scene cost/coverage remain LEAD4's integration responsibility. Review did not validate arbitrary mod mixins overriding vanilla chunk APIs, actual client visibility, continuous player waiting, or end-to-end physical-front readiness. Complete zero pending is only a complete current cohort observation, not overall strike readiness; an empty cohort is vacuous.

All reviewer commands completed. No reviewer-owned jobs, processes, GPU leases or server resources remain. Source untouched. Report and inbox notices explicitly return this review-only scope to LEAD4; no component author/main merge.

Completed: 2026-11-04T22:30:30.968271+00:00
