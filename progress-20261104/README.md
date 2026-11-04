# Nuclear ruin progress evidence

This is component evidence for PR #596. It does not certify real-city, client/LOD, save/rejoin or private-delivery readiness.

## Source and policy

Base: `b5610b3c91a2c1e2e2d09d28c0fd503464a43982`. Core follow-ups: `ce2411f82`, `e87ad40be`, `f9d9f209c`, `476355384`. Final combined source: `452f1f78af23779200baf4aef804e8e92d6dca40`; the only addition after476 is CI6’s validated devtest fixture. The `mod/src/main` tree is identical (`aa5e3f6923d80a959b91b66a049ff5dfb80a5a37`).

The dependency graph is source read → published original window → background solver → retained plan → block/material application. Collection and reclamation must keep getting service even when later consumers exhaust the work clock. They alternate with material under the same clock after pulse/front work. Collection and trimming have separate turns in each registered context, so a refused publication cannot suppress its own or another context's reclamation.

One assembling window is protected server-wide. Other demand remains lightweight queue entries; admitted tasks keep their independent input owners. An active near assembly stays ahead of newly sorted demand. Unload, loss of FULL readiness, terminal failure, heap stop, cancellation and consumer-removal paths release abandoned claims without pretending outstanding callbacks have finished. Preparation retirement transfers retained plans to the surviving context before dropping the old owner, and cancels results that lost their sole collector. A funded live worker reuses its workspace. Ruined snapshot untouched sections reuse palette properties with the same fire/flowing-water normalization.

The existing executor remains; increasing the cap or adding a scheduling framework would not address stage ownership. Serial assembly reduces speculative input concurrency, while admitted solvers remain concurrent. These are finite-workload progress checks when the complete known working set fits, not an arbitrary-arrival guarantee or a complete JVM heap bound.

## Discriminating results

| Case | Before | After / exact scope |
| --- | --- | --- |
| Ready original source versus 8,192 moving pieces | Exact b561 keeps the ready source pending at tick 8 | Source is published and its slot released while material is still pending |
| All watched demand | Exact b561 leaves 15/16 pending at tick 240 | All 16 glass blocks damaged and 16 material releases; competing 8,192 pieces remain. Final core run: 66 ticks, sampled resident peak 534,993,127 B < 536,870,912 B |
| Preparation retirement | Exact b561 abandons a completed FAR result/window | Unclaimed task retired; retained plan transferred, applied and material recorded |
| Demand page publication | Exact-byte boundary refuses before source/decode ownership fits | Same future/credit retained on refusal, real page payload unchanged, decoded owner counted before refund |
| Abandoned prelaunch assembly | ce241 unload and unready-todo paths block the next consumer | Registered paths retain late callbacks and complete another loaded required centre |
| Ready source needing growth | ce241 cannot publish 7,026,288 B from its 4,194,304 B slot despite 8,467,264 B of saved originals | Same funded case publishes after bounded trimming; another-context variant also completes, resident 529,810,512 B |
| Near-queue refresh | f9 strands the live assembly behind a new nearer head | Real refresh preserves its service position; both loaded required centres produce nonempty plans after the held callback |

`final-base-red.log` runs the three base regressions against unchanged b561 production. Their definitions are byte-identical to the candidate files (`final-pair-test-hashes.json`). Generated GameTest positions and derived byte totals may vary; this is not a wall-time speedup comparison. Visibility uses the existing test hook; network/rendered delivery is not established.

At `476355384`, `refresh-validation.log` records full build with 1,840 unit tests and 56/56 affected GameTests, with normal save/stop and wrapper exit 0. The preceding f9 run passed 55/55 locally but failed CI run 41896220491 in the worker-pressure fixture. That failure is preserved, not relabeled as green. [CI6 evidence](https://github.com/zentixua/incoming/blob/54704af1ec9fb37033e3382a8da4d016b4daf288/README.md) records the previous-cache calibration drift, unchanged bounds, populated-ledger/held-header controls and review closure. Original CI count uncertainty remains. After integration, local compile and3/3workerGT pass; exact combined [CI41907477225](https://github.com/zentixua/incoming/actions/runs/41907477225) is GREEN, pr_ci exit0. Artifact metadata is included; its SHA-256 is the archive digest, not a separately measured JAR digest.

## Review and negative evidence

Two fresh independent implementation reviews checked ce241 and f9. They requested changes for abandoned assembly, collection-versus-trim starvation, and refresh reachability. Scoped technical excerpts preserve their findings and evidence qualifications. Operational session details and personal memory sections remain outside version control; original report hashes are recorded. `476355384` closes the second-round refresh finding with the paired regression and owner reconciliation, without a third routine review round.

Development filenames containing `green` do not imply success. `sustained-green`, `reuse-green`, `section-green`, `complete-path`, `review-green` and the first `validation` contain failures. The first cohort lacked a held source neighbourhood and later exposed an unfundable smaller working set; it is not the paired final cohort. The first `reclaim-red` filled every byte without funding the moving FIFO's next page rotation and failed the cap assertion. The corrected `reclaim-funded-red` retains that existing 177,344 B transition allowance, still far below publication growth, and fails the intended starvation assertion. `flow-terminal.log` shows the old material fixture ending after two ticks with glass already air and deposition still pending; its corrected loop waits for both outcomes under the original 160-iteration bound and exact material assertions.

Historical real 1 Mt evidence belongs to the integration owner: 415 currently watched, loaded queued chunks overdue at the old post-clean census, maximum 13,551 ticks. Successful-application counters alone do not establish all-pending convergence. None of the component tests above replaces a corrected actual run.


A final audit hypothesis about an active preparation being lost to idle sweeping was rejected: `ScarQueue.pending(int)` already includes the prepared-plan map, independently of queued jobs. The temporary710-tick probe reached its ownership/admission checks and then failed its own late nested final-clause setup. Its raw log and patch are preserved as a rejected probe, not a passed regression or a production defect; no production guard was added.
