# Worker workspace admission evidence

Production candidate: `153e4f86a4a3dba2a4a6e3dce4b0a791e6a47827`; base `097efc4ee419f5e4b5572f2fecc7e80ce04dd822`. Component PR #588. This archive contains source review and owned headless tests only; no worlds, jars, game settings or release artifacts.

- Exact final full build: 1,785 units, zero failures/errors/skips; 13 focused GameTests passed. Build and GameTest JVMs used 4 processors and 2 GiB, through the repository laptop-job wrapper, without daemon reuse. `MT-WORKER-final2.log`, `unit-summary.json` and worker-suite XML retain the receipts.
- Discriminating base control: only the new GameTest added to original base097 fails with `workspace producer exceeded global cap`. `MT-WORKER-red.log`.
- Discriminating correction controls: the two new startup/stop tests against e70 worker source each fail; identical tests pass on153. `MT-WORKER-stop-red.log`, `MT-WORKER-stop-unit.log`.
- RWORKER1 independently reviews the e70 candidate. Its P2 F1 concerns stop before the height wait; optional O1 concerns a factory-created Thread discarded before start. The153 delta and executed regressions cover these paths. Final-source second review is pending and will be archived separately.

At height384 a worker's conservative workspace is13,271,040B. The numeric non-worker fixture417,653,525B admits8 workers (523,821,845B total);9 would exceed536,870,912B. The paired unpressured owned-pool control admits10. Real shared-owner GameTest also fills the cap, enqueues an already-captured callback at zero workspace room, then frees exactly one workspace and observes tick-driven completion.

The production change gives new workers and per-thread height upgrades a server-side grant from existing resident ownership, with no callback traversal of worlds. Numeric credit remains until actual thread exit, including cancellation, idle residency and old shutdown survivors. The same executor lifetime, task priorities and captured-input ownership remain in use. No permanent future configured-pool charge is restored to material admission.

These are workspace/resident admission estimates, not allocated or retained JVM heap measurements. This component does not establish all-producer512MiB accounting, real-map performance, whole-zone completion or100/1000kt acceptance. Snapshot/result publication and combined actual trials have separate owners. Production remains held for LEAD2 integration and actual trials; this helper does not merge main or release.
