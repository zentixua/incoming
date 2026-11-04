# MT-B5 material review corrections

Head: `42afbcac859ddeff5b464bba76287ff6bae7c2f3`. Main: `1aca1654a` (already merged; unchanged at final fetch).

The author stopped for the team-wide clean restart requested in live.md (04 November 07:04 UTC). No new experiment follows this handoff. GitHub CI run 41790478188 was in progress at handoff, not claimed green. The existing PR remains a draft.

Local full build passed (4m 30s); final touched-area GameTest passed all 44 tests (50.71 s test time, 1m 10s Gradle run). The final wrapper regression with 10,001 records measured 13,184 bytes allocated on the caller/server thread; ledger ownership was 5,709,414 bytes. This measures wrapper-call allocation, not total heap or whole-strike time. Encoding and atomic persistence occur on the I/O worker while the live owner is frozen.

Regressions cover delayed real SavedData wrapper I/O and repeated autosave calls; packed/named-row parity and cargo; three collected capacity-refused plans and retry/clear accounting; cold disk admission in two real dimensions, paused until a shared reservation is returned; and a legacy crater with 3,000,000 completed column/done entries plus an active checkpoint. Retired arrays are skipped before allocation, totals retained, active checkpoint preserved.

Raw logs preserve earlier attempts too: compile2 rejected a Java `var` compound declaration; the first GameTest fixture did not submit its completed worker results, then was corrected to exercise production collect/apply. These were fixture/setup failures, not claimed reproductions of the review's original defects. Later 19/30/39-test runs and the final 44-test run passed.

No client, shader, sound or geometry-law edits were made in this correction. The previous paired media and scene-bench evidence remain historical and correctly labelled; no GPU/client/server was launched for these memory corrections. Tests use only the isolated local GameTest runtime.

Limits: the counter covers these owned representations, not total JVM heap or all preparation owners. An active legacy component larger than the global budget remains preserved and paused on disk; no support-graph paging or full-world rerun is claimed. MT-OPT owns wider plan/snapshot persistence, world reads and LOD delivery. MAX_YIELD/default/max remain 15.
