# CI-S6: two old fixture failures at exact b57

Component source: `b57c36061db305408c25e07d9e563fc37bd64dde` → `0798edf0859b8dfa3ce8f8879de9b33d0e66a35e`, [PR599](https://github.com/zentixua/incoming/pull/599). Only two old GameTest fixtures and their rule changed. No production mutation or runtime acceptance.

## Original failure provenance

[CI41925168739](https://github.com/zentixua/incoming/actions/runs/41925168739), PR598, failed `craftBlocksGoRightAfterDamage` (4/6) and `ruinsOverFlashWithoutShots` (6/6). Checkout4810f018af773ff40f1e3a6ff7ee3dae28f5a75d and b57 have identical tree `cf04fd1c964e24d18fd8a93b36027d36124ba64a`; API metadata is preserved. Original failed-job logs are unchanged in raw/ci41925168739-original-failed-jobs.log.

## Discriminating native controls

- `original.log`: original two tests in isolation PASS2/2. This is not the historical causal control.
- `native-six.log`: complete original 29-batch/31-test prefix through flash, obtained by pruning the already-grouped full shard. RED at flash: tasks0/inFlight0, resident65,766,711B, foreign NEAR assembly owner. A generic “background threads busy” exception does not identify which admission condition failed.
- `pair-red.log` / `pair-green.log`: same first-tick seed42, same explicit look-ahead→flash batch sequence and observed origin; originalRED, correctedGREEN2/2. The old look-ahead fixture finishes with8 Tasks and a pending assembly in its world-owned synthetic context. Its local queue.clear releases jobs/tickets but does not retire that context. Explicit forget does; the added independent nonempty background plan verifies progress afterward.
- `retirement-mutant.log`: remove ONLY context retirement while keeping the new guard. RED2/2; the new independent-consumer step itself now fails, then flash fails. This is a regression discriminator, not a timeout relaxation.
- `craft-position.log`, `craft-trace.log`, `craft-wave.log`: original source reproduces the original craft error in isolation at exact failed structure coordinate13112835,-60,13954388. Event includes body, all45 chosen hull blocks disappear, readiness true/ignoreExplosion false, but body keeps10HP/zeroimpulse. Actual charge centre is at the bottom of a grass cell, placementNONE/breachnull; pressure828.9486kPa, windfalse/reachesfalse/woundnull. The old initial shield assertion used a different point one metre above the actual charge.
- `craft-green.log`: corrected fixture at the same original coordinate. Actual charge is at that above-platform point; body position,45-cell hull and150kg charge stay as before. Source is air, initial hull shielding still zero; after hull removal windtrue, damage callback observes every selected hull block gone. GREEN1/1. Horizontal separation stays7m; slant distance changes from√50 to7m as the source moves vertically. No physical law or threshold changed.
- `native-four.log`: original full41-batch/50-test prefix at a different pinned origin passes. This shows why an isolated or differently located green run could not resolve the original craft cause.
- `final-prefix-six.log`: final fixture methods, original shard6 prefix, GREEN31/31.
- `final-prefix-four.log`: final fixture methods, original shard4 prefix and original starting grid13112766,-59,13953392, GREEN50/50.

Native prefix selectors, seed control, two-draw coordinate interception and diagnostic reflection were disposable devtest edits and are preserved under controls/. Final method/rule source equals0798; final clean build uses no instrumentation. The coordinate interceptor replaces only the two start-grid draws and restores the original RandomSource. It does not reconstruct the historical body UUID, wave RNG, thread scheduling or wall time. Raw starts must be compared; --spawnPos does not control GameTestServer's random test grid.

## Invalid/intermediate probes preserved

`prefix6.log` failed to compile because a temporary diagnostic used a nonexistent method. `prefix6-native.log` accidentally selected all851 tests because laptop_job's environment allowlist excludes digits from INCOMING_ variable names; it was stopped early. `prefix-six.log` selected the31 tests but regrouping changed batch order; stopped early. None is a failure/pass control or evidence for fixture causality. All raw is retained.

## Scope and resources

Heavy commands use laptop_job.sh, --no-daemon, --max-workers=4, ActiveProcessorCount4 and Xmx2g per JVM. This is not a measured2GiB total process/RSS ceiling. Power profile was read-only verified as performance; no profile changed. No GPU, UI, remote test server, protected port, player world/settings, release or delivery action occurred. Saved local test worlds were moved aside before subsequent runs.

Final full build/touched GameTest, independent review and CI receipts are added below after they finish. Parent S6 progressed to e00fdfb while PR599 ran: CI mergeec2c3de includes that parent correction; its tree638d3b7 is different from local component0798/tree7ea2999. Do not claim tree identity or runtime acceptance from that combined CI.

## Independent review

Fresh RCI-S6 Sol/high completed2026-11-04T22:54:34Z, exactb57..0798: PASS, no serious/optional change requested. Full report RCI-S6-review.md. Reviewer independently traced cancellation/assembly ownership, actual background admission, preserved craft damage-order assertions and the raw negative controls. Mandatory BOARD read exposed a short author checkpoint; reviewer disclosed this and grounded the verdict in source/raw. It was a fresh isolated session, not a read of the author conversation/PR description.

Diagnostic accounting caveat: CiS6Probe's residentMemory call is not passive; it can update accounting/reclamation. The ownership fields/pool counts were sampled before it. The later reservation figure is not a continuous/atomic heap census or clean timing measurement. Temporary red.java files contain diagnostics and are not byte-identical to the base. Logs plus saved controls support the stated fixture mechanisms; no immutable per-run source manifest authenticates every intermediate log.

## Final clean local validation

At clean0798, laptop_job wrapper257796 exited0: build PASS1840 units/0failure/0error/0skip (296 suites, final-unit-suites.json), followed8/8 touched GameTests. Clean build3m48s; focusedGT21s. Commands use4ActiveProcessorCount/Xmx2g per JVM and no shared Gradle daemon. Full prefix helpers had been removed; working tree clean. Full original-order final prefixes were31/31 and50/50, with only the disposable selector outside the committed diff. No visual/frame-cost gate applies to this fixture-only delta.

## Final remote CI and resource end

[CI41928780767](https://github.com/zentixua/incoming/actions/runs/41928780767) completedSUCCESS2026-11-04T22:58:38Z; requiredИтогSUCCESS. tools/pr_ci.sh599 waiter90884 collectedexit0. All6 normal GameTest shards pass851/851; both optional LSS runtimes pass5/5 each; both unit partitions, build and clean NeoForge install succeed. Full raw run/job metadata and full CI log are preserved. Mergecheckoutec2c3de79123f96cb8a344c7932a7e4fa7738a31/tree638d3b7c1b077e2aec3a395b22d204144703d839 contains parente00fdfb71443905079eb09730737c6261b07f68c plus component0798. Local clean1840units/8GT apply to exact0798; do not mislabel the combined CI as its identical tree.

All CI-S6 heavy wrappers/GameTest JVMs, reviewer and CI waiters ended and waits were collected. No owned heavy/GPU/client/server resource remains. Component stays an unmerged draft for MT-S6/LEAD4 integration; only0798 is the transferable source commit. The orphan evidence history contains no parent production tree.
