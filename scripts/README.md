# Diagnostic scripts

`audit.py` reuses the retained PERFA harness, profile settings and exact `inc-100-async-plan.json`; only the Incoming jar and output directory change. It does not modify that harness or the player instance. The snapshot records laptop paths intentionally, to reproduce this measurement on the same host.

`ReferenceThermal.java` and `ReferenceGroundDust.java` are renamed copies of the baseline classes. `ThermalComparisonTest.java` and `DustComparisonTest.java` compare physical samples, extinction, bounds and street clump output. `probe.gradle` adds only this scratch source directory to a diagnostic test invocation. These files are evidence, not part of the production PR.

`cold-java` disables NVIDIA's on-disk shader cache for a fresh sandbox process. Pass its absolute path as `INCOMING_SANDBOX_JAVA` to `tools/sandbox.sh start --wait shaders`. The laptop job deliberately filters arbitrary shell environment variables, so exporting `__GL_SHADER_DISK_CACHE` outside the launcher is insufficient. The before/after logs contain the diagnostic timings from temporary instrumentation around `PallPass.quad`; that instrumentation is absent from the final code. It timed `ShaderInstance.apply` and `BufferUploader.draw` separately and logged draws over 10 ms. The final code logs the total real-pass preparation once per shader instance.
