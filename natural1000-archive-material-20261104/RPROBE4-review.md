# RPROBE4 — independent diagnostic/evidence review

Date: 2026-11-04. Fresh session: Astra/high (verified running command), read-only review; no author verdict, STATUS, inbox, checkpoint, PR description or author conversation used. Current TEAM/live and assignment govern this review. LEAD3 owns runtime/integration; MT-S4 owns the production diagnosis/fix. No transfer of either ownership.

## Verdict

No serious correctness or gameplay-mutation defect found in the **OwnershipAgent3 → OwnershipAgent4 delta** (class rename, one call, `watchedJobs`). The recorded clean4 pending-job census is usable with the scope below. This is **not production approval, near-wave acceptance, a review of MT-S4's fix, or a zero-overhead certification**.

At clean4, the existing overworld ScarQueue had 5,642 current jobs, all scanned; 415 satisfied the actual current player tracking-view predicate, all 415 returned non-null from `getChunkNow`, and all 415 had `now > job.wave`, maximum 13,551 ticks. Separately, event 2's `preparedStats[25]=13407` proves a successful fresh-plan application while watched at that lag. Neither measurement is the player's continuous waiting time or a client-rendering measurement. These results cannot support a near-wave PASS.

## Exact inputs and provenance

- Private new source: `/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/ownership-probe4/OwnershipAgent4.java` (362 lines), SHA256 `135e3d792156c23a37bf7148789aceb77ff1761b993260a5750964f52ae9877b`.
- Compared directly against `/mnt/data/projects/incoming/mod/run/wt-MT-OPT/mod/run/mt-s4/probe3/OwnershipAgent3.java`, SHA256 `18022d00b9cb3cb25b57db8ce4370e635b9042d11d8ef8252bd29c9734d48c7f`. No other source delta.
- New agent jar: `ownership-probe4/ownership-agent4.jar`, SHA256 `79e8febc64ff2e3de82353d25d6848f9052db1c58c5fc31036df68e892bcd25d`. Jar contains only manifest and OwnershipAgent4.class; manifest names this agent. Class hash `b932345a08742aca1e547e32d504f3a73f6a548b2075b627395d971c84b7b140`, identical to the supplied loose class. Inspected `javap -p -c` for the added method; loop cap, predicate, coordinate decoding and comparison match source. No compilation performed.
- Surrounding production: `/mnt/data/projects/incoming/mod/run/wt-LEAD2-NATURAL`, HEAD `0f1a77ff88b4e6546212aec9ab34a834eda525d1`, `mod/src/main/java/ua/zentix/incoming/nuclear/world/ScarQueue.java`, no local modification to that file. Working review tree is wt-LEAD2 / `codex/stand-passive-profile` at `ac4631357`; its production code was not substituted for the specified NATURAL pin.
- Evidence directory E: `/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/lead3-actual1000-evidence/`.
- `E/corrected1000kt-archive-material-on-post-clean4.txt`: SHA256 `8759dd098ff196102cad7ed5b643cc5c37c6f2fa957863df2f3c604afeaa764a`.
- `E/corrected1000kt-archive-material-on-post-clean3.txt`: SHA256 `44e75588cedf4dfee49683673282f2ff158b17d4cd02ffe427c28ba6a11873b4`.
- Attach receipt: `/mnt/data/projects/incoming/mod/run/wt-LEAD2/mod/run/lead2/lead3-actual1000-postprobe4.log`, first JSON line. It records target PID 54565, start_ticks 105233660, successful attach response 0, matching new agent hash, source pin above, production jar hash `c468e9ccece478f8039f398a369ebe1c5a51be9398f7b5b585e0d30fa8ee7e10`. Clean3's `E/post-clean3-attach.json` records the same PID/start/source/production hash, but the prior agent hash. Target identity is receipt evidence, not a new live remote-process inspection or an independent rebuild of the production jar.
- `E/phase-cut/progress.log`: SHA256 `84898a36cf093f491709cd544a1259ee968fe839d97109835742fddd2066098c`; `E/phase-cut/latest.log`: `5e81eee8dedec3f0736828b053f611dfba7f736ccdbfd5a5f7db049d907a27dd`.

## Passivity, execution and bounds

`agentmain` uses already-loaded classes, obtains the current server in the attached JVM, submits one snapshot to `server.execute`, and writes the resulting text from a daemon writer (lines 12–37). No transformer, repeated timer, RCON/game command, world mutation, queue advancement or load request is added. Existing attachment access is guarded by `hasData` (60–62). The actual snapshot header says `thread=Server thread`; queue membership and player view are read without intervening server ticks. Background worker/accounting fields in the larger inherited snapshot are not one atomic transaction.

The new method (259–290) reads the existing jobs map, world clock, player list/view/position and parked map. It invokes the exact production `ScarQueue.watched`, not `withholds`, `sent`, planner, admission, snapshot, or eviction methods. The recorded `seenForTest=null` in all three dimensions excludes fake-view additions and arbitrary test-predicate execution. With a non-null override, the same code would need qualification; it is not universally a pure predicate for every possible test callback.

Primary method sources were inspected in cached combined source jar:
`/home/artem/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_db3b02017dab00ea5971868812997b371000dda1_output.jar`:

- `ServerLevel.players` 1438–1439 and `ServerPlayer.getChunkTrackingView` 1869–1870 return existing fields.
- `ChunkTrackingView.contains(ChunkPos)` delegates to `contains(x,z,true)`; Positioned performs the distance arithmetic, including the extra outer border. This is the server tracking view, not camera frustum, line of sight, already-sent set, or client mesh availability. The recorded concrete view is Positioned.
- `ServerChunkCache.getChunkNow` 173–202 uses the current holder/cache and `getChunkIfPresent(FULL)`; `GenerationChunkHolder` 248–255 uses `future.getNow`, not `join` or a load. NeoForge also has its `currentlyLoading` bypass. Therefore `loaded=true` means exactly “this getter returned a LevelChunk now,” the same center gate production uses at ScarQueue:1169, not neighbor readiness or client receipt.
- This getter increments a profiler counter and may update the four-entry chunk lookup cache. Thus passivity here means no gameplay/planner/load mutation, **not literally zero writes or zero timing/cache perturbation**.

At most 20,000 jobs are evaluated per dimension, 16 matching jobs have details dumped; the containing snapshot visits at most 16 dimensions with existing attachments. The first over-limit iteration increments `scanned` to 20,001 and exits before evaluating that job, making `complete=false`. Exactly 20,000 jobs gives true; there is no false-complete off-by-one in this logic. The actual 5,642-job scan is complete. Players have no explicit numeric cap: complexity is O(min(J,20000) × P), plus player dumps and inherited snapshot work. No elapsed-time budget interrupts this server-thread task; the writer's 15-second future wait does not cancel it. It is a one-shot diagnostic, not a guaranteed tick-budget-safe recurring collector.

Recorded whole-snapshot time: clean3 28.820682 ms, clean4 40.207782 ms. The latter is real observer cost, not negligible; it is not just watchedJobs timing. Their difference is not a controlled overhead comparison because live state/work differed. A 40 ms callback may extend a tick. No claim that the runtime was observer-free or that this cost caused a 13,551-game-tick backlog is warranted.

## Job identity and comparison math

Production source references below are all at the exact NATURAL pin.

- `jobs` is the authoritative current map (193). Ready sets can contain stale jobs; production skips them using object identity (822), and drop removes only the map entry while ready-set cleanup is lazy (629–635). Iterating `jobs.values()` avoids treating stale ready-set entries as pending jobs. `readySeen.size` is not the same as current watched jobs; visibility can also change after enqueue.
- A Job's `event` is an index into `events`, **not a detonation ID** (53–59). `wave` is initialized from `due(events[event], pos)` (507) and changes on advancing to the next event (1248–1255). Retries modify `due`, retaining `wave` (1197–1201, 1216–1221). `due(d,pos)` is detonation game time plus truncated front-arrival ticks at chunk center (709–711). The diagnostic correctly uses `wave`, not the next retry's `due`.
- `lag=now-wave` is in server game ticks; overdue is strictly `lag>0`. A job exactly at wave time is not counted overdue; production may apply one tick early. `maxLag` is maximum positive pending lag among watched jobs, zero when none is positive. Negative/future rows remain watched and loaded if otherwise eligible.
- `(int)key` and `(int)(key >>> 32)` reconstruct signed chunk X/Z correctly; the final cast makes unsigned versus signed shift equivalent here. Independently checked all 16 printed coordinates against packed keys and every printed lag against gameTime minus wave; all match.
- Summary has no per-event filter/grouping and no original admission time. Each current chunk contributes once for its current event; later queued events, jobs not yet enqueued, absent chunks, already-completed chunks and removed/failed/unloaded jobs are outside it. No global whole-zone completeness follows from `complete=true`.
- The 16 detail samples show `events:size=1`, `event:0`, `aheadEvent:2`; existing queue-head details separately resolve current events to ID 2. This corroborates the recorded event-2 case, but the aggregate does not itself prove that every unsampled job belongs to detonation 2 in a general multi-event run.
- `loaded` and `overdue` are separate subset counts of watched, not a combined loaded-and-overdue count. In this particular row both equal watched=415, so all 415 satisfy both. In another row their equality/intersection could not be assumed.

## What the evidence establishes

Clean4 lines 1, 7 and 131–183: UTC 17:49:12.476, gameTime 2040659058, one player at X=-96.5/Z=-816.5, no test override; `scanned=5642 complete=true jobs=5642 watched=415 loaded=415 overdue=415 maxLag=13551`. This is genuine pending server work inside the player's current tracking view, not merely a count of an old readySeen set. The 16 printed jobs have `asked=Long.MIN_VALUE`; that field means no first post-wave send request has been recorded for those jobs, not that ruins succeeded or that the player never saw their chunks.

The supplied latest.log places Dev at exactly this X/Z at 18:32:58 local (line 483), before the event-2 1000 kt ground burst at 3104/63/560 at 18:37:23 (509). This corroborates a stationary-view test context. It does not record continuous view membership/load status for every job through clean4, nor prove no movements/reloads in the later interval. These facts must not be replaced by an invented “13,551 ticks continuously visible and waiting” history.

Clean3 is UTC 17:47:07.125, gameTime 2040656551: clean4 is 2,507 game ticks / 125.351 wall seconds later. The phase-cut progress log ends at gameTime 2040655939, before **both** snapshots; it cannot supply the missing clean3→clean4 timeline. Its event-2 rows bind the earlier preparation/queue state and the 5357 far-plan plateau, but do not prove the cause of the later watched backlog. The current diagnostic addition neither measures client rendering nor identifies why each job is stalled.

## Applied statistics are a different population and lifetime

Source: ScarQueue:294–314, 941–1037, 1215–1255.

| Field | Actual meaning at this source | clean3 | clean4 |
|---|---|---:|---:|
| st[0] | Successful prepared-plan applications | 216 | 0 |
| st[6] | Successful fresh/parked/background-plan applications | 191 | 37 |
| st[2] | Maximum watched lag reaching the ruin attempt, before apply success | 1 | 13500 |
| st[24] | Maximum watched lag after successful prepared apply | 1 | 0 |
| st[25] | Maximum watched lag after successful fresh apply | 0 | 13407 |
| st[12] | Recorded distinct sends before ruins, current stats lifetime | 0 | 0 |
| st[23] | Maximum request-to-ruins interval, updated after success | 0 | 0 |

`st[2]/st[5]` are updated at 950–952 before `apply` and before several later refusal/staleness/wait paths. They are **not successful-only counters**, despite broad wording in summaries. `st[24]` is written inside successful prepared apply at 958–966; `st[25]` only after the fresh apply succeeded at 1011–1033. Clean4 therefore additionally proves at least one successful application while watched at 13,407 ticks after its scheduled wave; it does not prove 37 watched successes or 415 successful applications. Counts include successful empty/no-change plans; they do not quantify destroyed blocks or completed structural-material settling.

`dropPrepared` removes `preparedStats` and at-detonation membership at 295–314; later work can recreate a zeroed stats array for the same event. Clean3 has `prepared:size=1` and the old at-detonation sets; clean4 has `prepared:size=0` and those sets empty. The reset array cannot be subtracted as if it were a single cumulative epoch. The class's separate lifetime totals change from 216 prepared / 191 fresh to 217 / 228: these totals and the fresh post-reset 37 are different scopes. The supplied cut does not contain the precise intervening summary/reset timestamp.

Withholding measures from first send request (`asked`), not wave time (526–534); `st[23]` updates on successful completion (1239–1242). A still-pending already-sent chunk can have no `asked` value. `st[12]=0` and `st[23]=0` therefore cannot certify that watched chunks were timely, and pending wave lag cannot be substituted for request waiting or client-visible delay.

## Non-blocking diagnostic limitations

The added `dump(playerView)` emits only Positioned class/identity: dump walks fields only for `ua.zentix.incoming` classes (304–317), so the actual center/viewDistance are not captured. The invoked predicate remains correct; offline reconstruction of the whole 415-set is unavailable. For a future stronger receipt, printing center/viewDistance and actual event ID per sampled row/per-event aggregate would improve attribution; this review does not require another run or authorize any edits.

The agent itself has no target-PID/source-hash restriction: it sees the current server of whatever JVM receives it. Scope protection is in attachment targeting and receipts. Matching the receipt and supplied artifact establishes this capture's provenance to that extent, not permission to attach it elsewhere.

## Completion

Only local reads, hashing, source/bytecode inspection and offline arithmetic were performed. No build, test, GPU/client/server action, attach, upload, production/source change or PR operation. No owned background jobs, nested review sessions, CI waiters, GPU lease or server resources were created. Review result handed to LEAD3; MT-S4 fix and root runtime/delivery gates remain outside this review.

Memory: recorded the source-supported distinction between pending wave lag, pre-success attempt maxima, success-only lag fields and removable statistics epochs in canonical `diagnostic-method-counters.md`.
