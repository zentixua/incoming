# MT-CHECK read-only preparation-tile diagnosis

Exact source `6eb589d59df2a1ebdd41a3e2f8577dcd04448c32` (`6eb589d59`), new branch `codex/nuke-prep-tile-diagnosis`. Prior5c and463 branches/evidence are preserved. Only temporary observation inside assigned `NuclearGameTests.ruinsPreparedDuringFlightFallWithFront` was used; production, assertions, clocks and deadlines were unchanged.

## Scope and executed runs

The assigned original repeated failure is in wt-MT-OPT/mod/run/mt-s2-frontier-final.log:78cases, nuke_prep ready33/fromplan59/left0/squares1 beforeclear at now+850. That original log used14workers and approximately8GiB heap. This bounded diagnosis used the explicitly assigned4CPU/2GiB via laptop_job; automaticworkers2. No resource/config override was introduced to imitate14workers.

- focused.log: isolated nuke_prep PASS1/1,22s Gradle. It completed before500, so late probe did not execute. This passing isolation establishes no cause for the repeated union failure.
- prefix.log: one20-case prefix matching the logged batches/order through nuke_prep, PASS20/20,49s Gradle. Batch list is prefix.txt. Only a fixture-local read-only probe changed; no fullbuild/agents/review/productionchanges/world or region scans.
- Both owned jobs completed/collected. No active mt-check-tile job remained after13:55:29UTC.

## Actual captured pre-clear state

At elapsed500 in prefix.log lines312–381:

-3retained preparation tiles, ALL stateREADY. Centres [874017,-7108], [874012,-7103], [874012,-7108]. Held masks1ffffff/739ce7/1ffffff correspond25+15+25=65held chunks. Partial masks0/18c6318/0; unknown0; residentfalse. Ready times1614/1615/1645. heldAt remains-1 on this no-NukeDiag path by existing production implementation; it is not an observed negative load duration.
- ALL65held chunks have getChunkNow, holderstatusFULL, generationRefs0, pendingUnloadfalse, scar9 (current event). NONE have a queued scar job or pending prepared plan. No held-chunk task/source-plan future remains.
- Prep strike8/event9, drainingfalse, heapStopfalse. order37,nextFar37,nextRead37,nextScan241,scanning0. submitted/farRunning/planReads/planWrites/contextTasks/contextReading/contextWindows allEMPTY.
- Shared counting clock used29,000,000ns, units29; fakeNow5,771,000,000 and deadline5,772,000,000. Counting-unit cost is1,000,000ns. In this clock, `end` advances by the unit cost and `record` sets estimate at least that cost; `fits` requires now+estimate STRICTLY BEFORE deadline. Thus another release unit is denied at this point.
-79GLOBAL scar jobs existed and scarInspectedLastTick0 was recorded. This is NOT a material-job count. The probe did not attribute which caller consumed those29units, and did not capture global ledger load/freeze state. No material-producer blame is inferred from this count.
- The prefix eventually obtained room and passed before the original deadline. It did NOT reproduce the final850-tick failure. captured-state.json independently summarizes all65rows:0unready/0queued/0pendingPlan/0nonzero-generation, scarValues[9].

## Exact observed owner and limits

For the captured state, the remaining owner is `NuclearPrep.releaseDone`, not chunk loading, neighbour waiting or plan I/O. The tiles are physically complete, but cleanup remains budget-gated. Source path: NuclearWorld.tick processes earlier nuclear work/material/scorch before prep.tick; behindWave calls releaseDone using the SAME shared clock. releaseDone returns before inspecting/releasing a READY tile if clock.canStart is false. This proves an actual budget-denied cleanup delay in the prefix; it does NOT prove which state/caller persists at850 in the owner's original environment.

Do not classify the original failure as fixed, or call a persistent production defect established from this passing prefix. In particular, no frontier/window collector problem is demonstrated by this captured empty-input state. MT-S2 retains NuclearPrep/RuinContext ownership; nearREADY old-cursor hold and worker workspace corrections are outside this task.

## Minimal next action / correction proposal

Apply the supplied method-only probe.patch to the owner's NEXT ALREADY-REQUIRED reproducing union, retaining its actual resource profile, to get the final850state. The probe is bounded to4preparations/64candidate tiles/8heldtiles and their25held positions; it only reads corresponding holders, attachments and owned maps/futures. It does not scan the world or region files and does not enable/mutate NukeDiag.

If the terminal850snapshot confirms the same completed READY/no-work state and budget denial, the minimal production candidate is a bounded retirement opportunity BEFORE sustained lower-priority work exhausts the existing shared allowance (e.g. before additional material progress), charged to that same clock. Do not add budget, reset clocks, weaken release checks or increase the timeout. Exact placement/fairness tradeoff and a regression proving release under sustained work belong to MT-S2 after confirmation. If terminal state differs, fix its measured holder/job/source dependency instead. No correction was implemented here.

## Handoff / artifact policy

Temporary probe is preserved here as exact source+patch; production checkout is restored to clean6eb before handback. No source implementation commit/PR/merge is proposed. Evidence lives only on look-gate/MT-CHECK-TILE. No GPU, desktop, Crafty/protectedserver actions, extra agents/reviewer, fullbuild, release or deletion. Root/MT-S2 own follow-up runtime/CI and implementation.
