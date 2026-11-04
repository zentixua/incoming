# Nuclear preparation control evidence

Main reference: `36c9b5bdd`. Candidate UI: `d7def3da2` (UI source unchanged in `f0ece8bc0`).
Authority and lifecycle GameTests: `f0ece8bc0`, 35 tests passed; raw run in `checks/gametest.log`.

Images use the same flat-world UI scenario. Main has the old immediate hold review; candidate has Preparing, Ready and Paused states. EN frames are 1280px wide, RU frames 854px wide. Status counts in these display fixtures are deterministic and do not measure terrain preparation throughput. Server readiness, payment, cancellation and binding are tested separately by the GameTests.

Commands: `tools/sandbox.sh shot <name> 'ui preparing|ready|paused|keyboard' --at 0.5 --wav 1 --no-swap`, isolated virtual compositor. Keyboard scenario asserts focused Space begins a ready hold and release cancels it, without a commitment. The rotated client log contains that assertion. UI makes no audible effect changes; one-second WAV files preserve each capture.

No 100-strike render bench: this change adds UI/server control and unsynchronized server ownership, with no rendering/particle/draw-count change. Backend full-yield preparation throughput and normal carrier geometry reuse remain separate acceptance work.
