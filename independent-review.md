# Independent Codex CLI review

The author ran fresh `codex exec review` processes at xhigh reasoning. They received the diff and repository context, not the PR description or author's conversation. The following findings are translated from the Russian CLI reports; full local originals remain in the author's ignored run directory. Neither report is represented as an approval of the later fixes.

## Round 1

Command: `codex exec review --uncommitted -c model_reasoning_effort='"xhigh"'`.

**P2: Avoid permanently blocking settlement with the pool's configured memory estimate.** A dedicated server with 48 logical CPUs selects 46 worker threads. The existing `bufferBytes` estimate is 271.7 MiB at world height 384, greater than the new 256 MiB admission threshold even without retained plans. Configured size does not shrink with adaptation or idle-thread exit, so admission could remain false forever.

Author resolution (`63fb3f78c`): settlement uses `residentBufferBytes`, based on the live pool size. Core threads already expire after 30 idle seconds, releasing their ThreadLocal buffers. Regression `expiredPoolThreadsDoNotPermanentlyBlockSettlementOnLargeCoreServers` retains configured size 46 while live count falls to zero, then returns to eight. The original conservative estimate for preparation is unchanged.

## Round 2

Command: `codex exec review --base origin/main -c model_reasoning_effort='"xhigh"'`.

**P2: Give chunks waiting for neighbors their own ScarQueue tickets.** A chunk loaded under another scar job's neighbor ticket has `mayHold=false`. If that ticket leaves and only settlement's radius-zero ticket remains, the chunk cannot acquire required neighbors itself. Settlement could hold all its slots until the 6,000-tick timeout. Reuse the bounded `ScarQueue.holdForTile` handoff already used by NuclearPrep/FarZone.

Author resolution (`9e9769ef0`): a held entry waiting for neighbors calls `holdForTile` only while `tileHoldsLeft > 0`, retaining the root admission bound. Regression `settlementHoldsWaitingNeighbours` resumes a completed scan with just this kind of chunk still pending. On the prior code it failed after 600 ticks without ruins; on the corrected code it passes, including a stale Create-block plan requiring radius 2. The existing manual ticket-handoff test remains, with its assertions intact.

No third round, in accordance with the repository rule. Final evidence includes the red reproduction, the green 22-GameTest run and full build/CI results.
