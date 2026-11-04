# Fresh ruin helper context lifetime

Base: `167632a71a0880343e45dcca45db7baa260dbe7a`.
Correction: `c5f1eebece712dbde3c92462714d0f321b031522` (two owner methods and success fixtures), plus `aa500e902` (exception fixture only).

## Cause and correction

Both fresh diagnostic helpers create an unregistered RuinContext. Background submission can independently reserve a fixed 16,777,344 B fragment cache. The shared await releases the result lease, but its caller previously abandoned the context without closing that cache. An exceptional await also abandoned its uncollected result owner. These numeric reservations cannot be reclaimed by GC or worker shutdown.

The two owners now call ctx.cancelTasks() in finally. Shared await and the registered-context caller are unchanged. Existing cancellation and actual-exit callbacks retain running inputs/results until execution really exits. Context closure leaves physical snapshots/old states/heights available to the returned plan.

## Actual regression evidence

| Source / same assertions | Result |
| --- | --- |
| Base167, original two new tests | Required FAIL2/2, each completed helper adds exactly16,777,344 B; full plan comparisons pass before the ownership assertion |
| Correctedc5, same two tests | PASS2/2, both helpers repeated3times, six zero result/cache/detached deltas |
| Correctedaa, final three tests | PASS3/3, six successful calls plus both post-admission exception paths,22s, wrapper0 |
| Base167, identical final test source | Required FAIL3/3,20s, wrapper1; exception leaves16,838,676 B, each success adds another16,777,344 B |
| Restore corrected source/compiled classes | compileDevtestJava PASS,3s, wrapper0 |

All six successful final calls preserve complete physical/material NBT, round-trip it unchanged, and match a direct same-input Collapse plan whose full output is checked against ReferenceCollapse. Final fixtures have2350/2510 changed blocks and structural material candidates. Each third returned plan actually applies after context closure; durable output is unchanged by apply. Result/cache and detached-input counters return to their baseline after every call. The negative control cleans only a successfully returned context after measuring the failure, so its second success batch still detects a precise cache delta. The exceptional old path has no returned context to clean and remains isolated to its disposable process.

The exception fixture passes a narrow LevelChunk argument whose position access throws only upon entry to await, after admission. Real input snapshots are still taken from the existing loaded fixture centre. Both corrected paths show18,247,024 B admitted, then result/detached baseline0 after actual worker exit. No production injection or world mutation is involved.

## Scope and limits

Only planFresh and planWithWindow production bodies changed; one dedicated GameTest file was added. The existing RuinStorageGameTests dense fixture loads its own5x5 area; no extra world/region scans. Background adaptation, deadlines, assertions, cache capacity and shared await behavior are unchanged. The timeout uses the same finally but no60-second timeout was deliberately induced. Different isolated processes have different random fixture coordinates/noise; equality is exact within each call's actual input, not a claim of cross-process identical geometry.

Focused jobs used laptop_job,4CPU/2GiB and --no-daemon, explicit SESSION_TREES/unique owner; no CPU-quiet guarantee, full build, CI, review, GPU or real server. All jobs were awaited. The exact createpropulsion1.1.5 runtime dependency was copied only into disposable GameTest mod directories and moved aside afterwards. Raw complete local logs remain preserved; public numeric receipts and SHA-256 provenance omit NBT, runtime jars and worlds. Root owns the existing second review, integration, CI and final runtime acceptance.
