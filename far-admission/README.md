# Far admission under a bounded shared clock

Follow-up source component for [PR 603](https://github.com/zentixua/incoming/pull/603), after PR 601 and the client component were combined. Base `fa2b09d3ea9aea3b5c4d5168a5b809285d2b5fdd`; final source `fd7652e864bb01a9e40f6ebaf72e8e367ecf787a`. The measured and independently reviewed source is `55a64d2e0a7fce31d24c80b7495fb14ca9b1a158`; the final change adds only test cleanup after the measured phase. Both have the same production main-source tree `ef856cf47a489c1113efafc5b4f4a0a6b7612137`.

The combined diagnostic CI failed the existing material/frontier progress test. A native replay ran the same 138-test shard at the original grid and reproduced the failure at the same target coordinates. Bounded test observations showed all 25 current source snapshots present, no pending reads or active worker, the current FAR assembly held by its consumer, and no other nuclear work in any dimension. The 65,536 moving records remained active. The private observer and logs are included; the observer is not production telemetry.

`NuclearPrep.far` visited its cold cache before attempting solver admission. `RuinContext.trimShots` charges every visited snapshot, including snapshots protected by the current consumer. That protection is necessary, but the repeated visits can exhaust the shared clock before the task that uses the protected window starts. A separate deterministic 26 ms control reproduced the stall with all 25 snapshots fresh, 25 ms used, 1 ms left, zero preparation work and an admission estimate of 487,101,373 B. The evidence does not identify a single historical reserve deduction as the sole reason for the original CI allowance.

The correction tries far admission first, preserves the next consumer's pin, and then trims using remaining shared time. Memory refusal still reaches cleanup; the independent bounded reclamation turn remains. No clock allowance, 512 MiB cap, physical calculation, persistence format or work deadline changes.

## Matched native control

Both runs use the exact committed `55a64d2e0` test at a 26 ms counting budget. The old run substitutes only `NuclearPrep.java` from the base during compilation; the new run compiles the candidate normally. A disposable first-native-tick seed selects the same test grid without jumping world time. Both use world seed zero, grid seed `65439824719800`, origin `12347285,-59,-6468245`, four active processors and a 4 GiB game heap. All native worlds and private scripts remain preserved outside this evidence branch. Seeds and coordinates do not make asynchronous scheduling identical.

| Observation | Base implementation | Candidate |
|---|---|---|
| Existing 120-tick progress condition | Failed through fixture timeout | Completed in 22 ticks |
| Moving material at completion | No completed plan | 65,536 records still active, actual simulated movement verified |
| Complete physical plan vs reference | Not reached | Equal |
| Source region loading | Disk/archive path | Disk/archive path; no live source load/generation |
| Reported resident estimate at completion | Not reached | 423,210,663 B |

The committed test still compares the full stored plan, enforces the shared admission cap and retains its original deadline. This is a scheduler regression with real source-region/archive I/O and material ownership. It is not a real-city throughput measurement, exact JVM heap measurement or actual 1 Mt player-experience acceptance.

The [fresh independent source/raw review](review.md) found no serious blocker. Its inherited test-cleanup observation was corrected in the final test-only commit. Final local build passed 1,850 unit tests and all 15 touched GameTests at `fd7652e864bb01a9e40f6ebaf72e8e367ecf787a`; [counts](final-local-validation.json) and [raw log](cleanup-validation.log.gz). The final ordinary frontier case completed in 21 ticks with 65,536 records still active. [Final CI 41960223533](https://github.com/zentixua/incoming/actions/runs/41960223533) completed successfully, including all six GameTest shards, both LSS versions, unit/build checks and a clean NeoForge server/client. Required `Итог` job `127027272171` is green; the [full receipt](final-ci.json) names checkout `4735a0e04375d86964accc735598f8e79c9c23bb` and the exact tested tree. `pr_ci.sh` returned 0 after the integration owner merged the PR; a separate run watch also returned 0 for actual run success. The [artifact receipt](final-artifact.json) separately identifies the JAR and artifact ZIP hashes. Source/log hashes are in [provenance.json](provenance.json). No jar, private city/cargo data or personal launcher/session record is published here.

The final CI JAR is 23,490,088 B, SHA256 `f5a3a0c60a77a27eebd34d69b2c057cb3e998902c14e570195fdef149d309672`. The artifact ZIP is 23,091,321 B, SHA256 `e16f47182af3117b0cea5baa55995eaf335fc87379af56af868d8ca3b427827d`. All uncompressed JAR entries equal the reviewed-source artifact; archive metadata makes their whole-file hashes differ. The earlier source run was superseded with a cancelled job status and is not reported as required-green CI.
