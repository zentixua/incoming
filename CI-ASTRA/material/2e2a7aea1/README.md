# Dense material admission — CI-ASTRA

Source base f9d1ac8ee13f612f7774d9a5e72dfb6a4e3c96e0; source candidate2e2a7aea1c281c47be318b4b35f26858833bd452, PR595. This component does not claim full actual-run acceptance or historical far-cursor cause proof.

actual-probe2.txt is root-owned actual server snapshot after preparation expiry. Visible parked chunk(-3,-57) has22054material positions,25ready chunks,memoryWaiting=true. The original plan admission needs22054*8704=191958016bytes before compact row growth. Original snapshot/archive contention is a separate PR594 component. Agent2 classification is a diagnostic observation outside clean measurement phases; do not use its48ms cost as normal gameplay performance.

material-red.log is a fixture PRECONDITION failure:128MiB did not fund the measured reference allocation+retained sum. It is not causal RED. It is preserved to avoid hiding the initial error.

material-red160.log pins the actual RED: same22054source positions encoded through production Sections at the consumer boundary, fixed160MiB room, external owner held. Before production correction, application refuses with memoryWaiting=true. Reference allocation47339000B+retainedGrowth99980392B fits the167772160B room. Initialowned369098752B staysunchanged on refusal. Fixture c5ad8813c, production unchanged frombase.

material-green160.log is SAMEconditions/testbody after cause-correction.patch ONLY: true application,memoryWaiting=false, finalowned469079144B<536870912B;22054source removals,22054material records,22054support seeds, mass/volume identical to unpressured reference. Reference allocation47339008B, retainedGrowth99980392B.21s wrapperexit0. Finalcandidate later adds only restoration of the allocation-measurement toggle; finalvalidation covers exactcandidate.

This constructs the measured payload cardinality at the plan consumption boundary; it is not a fracture/geometry reproduction. Server-thread allocated bytes plus retained-accounting growth is a conservative fixture funding check, not total JVM peak heap. The new formula preserves the existing deferred support allowance and allledger accounting while deriving producer admission from those actual charged terms. Cap and physics unchanged.

All local jobs use laptop_job, inner JAVA_TOOL_OPTIONS ActiveProcessorCount4/Xmx2g, Gradle no-daemon/max-workers2 and identical2g/4CPU JVMargs. Old worlds moved aside, not deleted. Fresh independent Astra/high source review and final build/touched-area/CI results will be added before handback.

Exact candidate2e2a7aea final build passed4m16s:1840unit/0fail/0error/0skip;28touchedGT passed7.201s. Final dense case again passes with fixed160MiB/allconservation checks. Raw logs, current compiled classes/JAR hashes and unit-suite hashes included. Historical red160/firstgreen devclass hashes were not captured before overwrite; their source/command provenance is the recorded worktree changes and red-fixture/correction patch, not an independent binary attestation. RCI-MAT independentAstra/high PASS; reviewer launcher awaitedexit0. CI outcome remains pending.

Final CI: tools/pr_ci595 completedexit0 after7min, ИтогSUCCESS run41871195148/job126723831578. Actual build checkout5e0980d64fcde1938e98145c2135845575705d33 has identical tree035eed3a204be9ea9addea264e9e89171e8e8b12 to candidate2e2a7aea1 (fetched and compared). incoming-jar artifact13345425384 ZIP digest d6b6e6c0ec89ed1f507488c33e42962f7dba21cb45f57c65640c6f9f273f0642. Raw checkout/build log, CI run/jobs/artifacts metadata and waiter output included. LEAD2 explicitly accepted integration at16:57:42UTC; main/newactualacceptance remains its separate work. No owned jobs/watchers remain.
