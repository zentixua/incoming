# Dense ruin consumer ownership

Baseline: 452f1f78af23779200baf4aef804e8e92d6dca40. Reviewed production: b57c36061db305408c25e07d9e563fc37bd64dde; prepared/fresh provenance follow-up: e00fdfb71443905079eb09730737c6261b07f68c. Local 1840 units/82 touched GameTests, three strict matched pairs, two independent source reviews and e00 exact full CI are complete. Final integration-fixture closure and root-owned runtime acceptance remain separate.

## Recorded failure and interpretation

The original 1 Mt post-clean probe at 21:07:29.902 UTC scans all 2,965 scar jobs and finds 84 watched, FULL, overdue jobs, maximum 10,264 ticks. A complete job scan does not prove that every affected watched chunk has a job. The probe is outside the 480-second clean phase and costs 73.75 ms; it is not clean timing evidence.

The worker pool has nine resident threads, zero active/queued tasks. One completed NEAR result remains owned with 25 pinned snapshots; 14 demanded stored-plan reads reserve 58,720,256 bytes. The probe does not expose those reads' keys or future states. Their being completed is not assumed.

The support ledger has no deferred sources, 11,417 support seeds and 44,361 original states. Its mixed historical counter is 190,669,824 bytes; evaluating the current-size expression would produce 14,281,216. Neither number measures actual heap. A disjoint recorded ownership subtotal is at least 505,804,091 bytes, so the two sampled dense plans' minimum initial requests of 82,043,520 and 51,008,704 bytes cannot fit.

Later raw logs show ongoing delayed progress, not permanent deadlock: positive partial samples still have 91 overdue watched chunks at roughly +483 seconds and 89 at +540 seconds. A later partial sample first records 453 successful watched applications and no sampled pending work at 21:11:53.457; successful maximum lag is 15,462 ticks. Periodic censuses always stop at their observer cutoff, so zero is not a full-holder acceptance certificate. Preparation retains the 12,174 far-plan plateau until its 21:08:24 retirement. No final runtime acceptance follows from these observations.

## Choice

Keep the planner, physical material/support rules, durable ledger schema and 512 MiB cap. Separate actual table backing/live payload from prospective source expansion. At most five neighbour contacts are created by each deferred source. Reserve the resulting fastutil table capacities and cold-face payload before removing blocks, including old/new table scratch during resizing. During expansion the potential contacts become actual entries; any unused future capacity is released. Retained arrays remain counted from their actual capacity even when maps are cleared or entries removed. No whole-support-drain reset is needed to retire spent source credit.

The live registry states are borrowed; original records, cold-face arrays, primitive tables, compact source queues, material rows and cargo retain their respective owners. Capture reservations transfer their published part to the ledger before further admission, while still-private capture data stays reserved. Saved source/provenance/cargo fields are unchanged.

Use separate bounded maintenance opportunities for requested plan reads and completed scar tasks, sharing the existing clock. Rotate a refused publication so other funded reads remain reachable. Collecting a task parks its result and releases its input pins; ordinary scar ordering still decides destructive application. Unfinished I/O retains its reservation. Completed reads with no current viewer enter the same spillable plan cache.

## Alternatives and limits

Subtracting all historical support bytes or replacing them with logical map size alone would refund retained arrays. Keeping the old 4096-byte source credit forever prevents reclaimed source work from funding consumers. Raising the cap does not fix those lifetime errors. Rewriting the physical solver or rolling back to the released rubble model changes the requested behavior without explaining the recorded ownership state.

The released v3.10.4 (e15df6) already continues far planning after detonation and has a saved-zone settlement pass. Its rubble rule keeps 12% of removed column height with a one-to-four-block decoration; it has no equivalent complete material/support lifecycle. Existing scheduling/storage is reused here. The additional bookkeeping comes from preserving real released material, cargo and cross-chunk support, not merely from increasing yield.

Ready-made measurement/reference: OpenJDK JOL (https://github.com/openjdk/jol) describes JVM object layout/footprints. This change needs array capacity, which the existing fastutil protected fields expose directly, following NuclearPrep's existing metadata subclasses. No new runtime memory-profiler dependency is needed. Actual native fixture array lengths are recorded separately from conservative eight-byte-reference accounting; no total-JVM-heap claim is made.

The policy still requires a fundable simultaneous working set. It does not promise arbitrary-size authoritative graphs at capacity, unconstrained arrival rates, immediate whole-city completion or native client delivery. Source validity still requires replanning after real edits. The raw stale-plan count does not identify the responsible mutation; no stale check was bypassed.

## Discriminators

- Native 22,054-cell removal, consume its deferred sources while cold support/original maps remain, then require every 14,820/9,214-cell watched consumer to apply under unchanged competing ownership. Live barrels contain cargo; all material, support and cargo fields must survive the real SavedData file wrapper and reload.
- Real stored-page reads and an actual completed unseen solver must be collected under a capacity-blocked 14,820-cell visible plan. Check all supplied queues, actual changed blocks, scar marks and released material, not only callback counts.
- Preserve the exact same test source and fixed competing ownership on baseline and candidate. Failed fixture setups remain evidence but are not causal RED results. Build, touched GameTests, fresh independent review and exact required CI remain mandatory; root-owned actual 1 Mt/100 kt acceptance is separate.
