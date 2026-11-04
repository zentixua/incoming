# CI5: fragment-optional CI diagnosis

Original failure remains unexplained. No fixture or production correction is established; no readiness or CI waiver is claimed.

## Pins

PR586 CI41889061857, failed job126781325132, GameTest6/6. The checkout was merge2178866fcbd5168e035f6f6e2d1a2b97df70cbce, whose complete tree d0b1cc1241f3e4f81df7ea49332be4589e8a023a is identical to headf53ecbe1c3cfec751c1aa978362897a07adc2e7d. Production source was unchanged for this investigation.

Temporary devtest diagnostic delta: diagnostic.patch. Full modified Java files and hashes accompany it. GameTestShards trims only after constructing the normal complete shard's batch list, at ServerStartingEvent; no regex regrouping. prefix-order.txt checks all38 batch names against the original failed CI prefix. The prefix contains76tests, ending with nuke_fragment_optional. Later batches are omitted, so this is not a155-test full-shard pass.

## Raw outcome

One original-prefix run passed76/76, normal server save/shutdown, laptop job exit0. The prior isolated1/1 pass belongs B7ALL and was not repeated. This run used assigned4CPU/2GiB, single-use Gradle768MiB and2workers; CI used4CPU, roughly4GiB reported heap, Temurin21.0.13+9; local Microsoft21.0.7+6-LTS. GameTest coordinates differ. Exact command/service in provenance.txt.

Optional test at native worldepoch5808: actual callable entered0.694ms after submission tracking, exited19.925ms, completed/collected at20.397ms and77callbacks. At submission: one active worker, queue0, startedtrue, cancelledfalse, donefalse. At terminal: actualfinishedtrue, done true, no error/cancel, worker idle, queue/inflight0. Fixed pressure511646352B remained held throughout. Initial native headroom534357376B; producer bound1051376B; worker startup/workspace13271040B; cache and detached-input bytes0. The full physical-output and fixed-pressure assertions passed unchanged.

These observed transitions distinguish real execution from Future.isDone. They do not establish the historical failed task's state. Original CI supplied no execution/owner/terminal dump: its400tick assertion expired around0.4s after batch start. A game-clock/background scheduling mismatch, prior ownership or actual resource gating remain unproven hypotheses. Do not change timeout/cap/pressure/assertions or production code on this evidence.

## Probe/evidence limits

Logging and atomic timing wrap the existing callable; logs can alter scheduling. Source equality does not make this an uninstrumented run. The probe only reads numeric counters/private pool state and terminal worker stacks, never calls residentMemory or task service from diagnostic callbacks. The fixture's existing initial residentMemory calculation is unchanged. Counter snapshots do not include a fresh complete per-owner headroom census and are not a JVM heap measurement. Terminal stack enumeration happens before cleanup, after pass/fail decision. No diagnostic clock controls or sleeps were added.

No GPU, desktop, remote server or player resources were used. No owned job remains. Production/main/delivery integration remains LEAD4-owned; MT-S5 owns any production concurrency correction. Fresh independent review is pending availability of the fourth session slot; its report will be appended separately.
