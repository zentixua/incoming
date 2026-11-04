# MT-P2 backend validation

Source checkpoint bcf53f23f0a8a48f6c41baa67ffd623d2af758fa. Backend checkpoint for MT-S2 integration, not merge/release acceptance.

check.log: first full build (1783 units) and six prelaunch GameTests. final.log: 94ec0756c broader build (1783 units, 3m49s) and sixteen prelaunch/lifecycle/store GameTests (22s), before the writer-isolation change. last.log: exact final-head compilation and six prelaunch GameTests including same-handle writer isolation (22s). All exited zero. CI and integrated independent review are separate gates.

No whole-zone throughput/total-heap result; no GPU/client/Crafty/player-world actions. Test logs contain only generated test-world data. manifest.json records source and file hashes.
