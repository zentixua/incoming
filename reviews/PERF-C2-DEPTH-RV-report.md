# PERF-C2-DEPTH-RV independent review

Date: 2026-11-04 09:03 UTC.
Reviewer: Codex, fresh gpt-6.1-sol/high session.
Scope: `36c9b5bdddbad22cc4e418c7b02bb7a141f56e4b..f69381911602154c0ce994f57150881169a4cfe9`.

## Verdict

No serious correctness, crash, rendering, physics or budget defect found in this diff. The source change is suitable as a telemetry-overhead fix. This review does **not** establish an ordinary-gameplay speedup or a completed post-fix performance gate. The promised paired measurement was not available when this report was written.

No optional code issue requires follow-up. Uncovered checks and existing diagnostic assumptions are listed below; they are not new blockers.

## Independence and checkout

Read current TEAM, BOARD, context, newest live entries, repository AGENTS, applicable client/fx/test/tool rules and relevant canonical memory. The review worktree is clean, detached at base `36c9b5bdd`; target `wt-PERF-C2` is clean on `codex/client-perf-readiness` at the exact reviewed head `f69381911602154c0ce994f57150881169a4cfe9`, checked again at completion. PERF-C2 retains implementation ownership. This is the explicit review-only assignment, not an ownership transfer.

The author's full-build wrapper was live at initial process inspection and later completed successfully. No processes were started or stopped by this reviewer. No source edits, public comments, PR-description reads, author report/inbox reads, merge, GPU/heavy jobs or additional agents. Only this report, a brief STATUS milestone and the required shared-memory note were written.

## Source assessment

Line references below are at the reviewed head unless stated otherwise.

- The only executable production change is `SceneDepth.java:216`: `lodShare()` is evaluated only when `DIAG` is true. `DIAG` is the existing `Boolean.getBoolean("incoming.nukeDiag")` startup constant (`:47–48`); absent/false leaves only metadata string construction. `LodDepth.View.describe()` is plain cached-string concatenation (`client/render/LodDepth.java:43–48`), with no texture query, reflection or Minecraft/GL access.
- Explicit `-Dincoming.nukeDiag=true` retains both existing diagnostic routes: the approximately one-second depth-column logger from `update()` (`SceneDepth.java:91,99–102`) and the coverage readback whenever the description is requested (`:213–249`). Coverage is **not** independently rate-limited; its frequency remains the caller's frequency. The startup property must be set before class initialization, as before.
- `SceneDepth.update`, texture/FBO creation, depth uniforms, draw submission, shader files, pass ordering, particles and all simulation/physics paths are unchanged. The source diff therefore changes observation, not intended pixels/audio or physical effects. There is no reason within this scope to request nuclear visual redesign or unrelated game checks.
- The new area-rule warning accurately identifies the expensive observer path. Its lightweight-query wording applies to default telemetry: explicit readback diagnostics remain intentionally intrusive.

### Actual callers and timing scope

`FxLayer.describeTiming()` evaluates `SceneDepth.describe()` while formatting its line (`FxLayer.java:425–433`). Its only internal production caller is the scenario-facing `FarRenderer.describe()` wrapper (`client/far/FarRenderer.java:108–116`), whose repository callers are in devtest scenarios. Other direct callers are devtest scenarios and the external stand's reflection probe. Ordinary main-code rendering does not poll this method. F3 uses `FxLayer.debugText()` → `LayerTimes.debugLines()` (`FxLayer.java:436–438`), not SceneDepth's description.

The retained stand's `Director.onTick(ClientTickEvent.Post)` calls `probes.tick(now)` once a second during steps (`tools/stand/.../Director.java:130–138`); `Probes.tick/call` invokes the requested static method (`Probes.java:27–30,54–58`). These relevant lines also match the retained PERFA runner source. Consequently the blocking observer runs on the client/render thread between rendering brackets. It affects full Pre-to-Pre latency, and can be missed by a Pre-to-Post-only render CPU measure. `FrameClock.java:26–40` documents the shifted interval convention; its `onPre/onPost` implement it (`:110–134,153–160`).

Removing the readback does not make the complete timing system uninstrumented: `LayerTimes.describe()` still keeps timestamp probes active (`LayerTimes.java:199–200`), with explicit drain/F3 behavior unchanged (`:96–117,121–134`). No blanket zero-overhead claim follows from this patch.

## Raw evidence checked

Evidence root: `/mnt/data/projects/incoming/mod/run/wt-PERF-C2/mod/run/perf-c2/`. All run paths in this section are relative to `runs/` beneath that root.

The original manifests, plans, frame/event/host records and native/JFR-derived collapsed CPU stacks were inspected directly. Both diagnostic runs use the same Incoming JAR SHA-256 `9b95ecb54937de1194649918dcf0229aa3b7e4fdfaea35d19c9bbf6a11437ed7`, stand JAR `c3860bdbfbfd04be2b606708b5f04ca8063dfa0351e0e1450cef64aa31bae818`, retained runner `ae5aaf609`, `profile=async`, no shots, and performance power mode. They report historical source `3be78e33b`; SceneDepth/FxLayer/LodDepth are unchanged between that source and review base `36c9b5bdd` (verified with git diff). The actual plan is night city, 100 spread direct warhead impacts, not the generic manifest argument defaults for light/terrain. Plan `day_time=18000`, camera `[-180,95,0]`, and salvo step `expect=100` confirm the scope; each event stream contains 100 blast records.

Both plans request `FxLayer#describeTiming` (`main-profile/stand-plan.json:13`, same line in main-ap-profile). Both launch scripts omit `incoming.nukeDiag`, yet baseline event values contain the computed coverage/no-LOD suffix. Each GL event records active ComplementaryUnbound r5.9.3, 2560×1600, RTX 4060 Laptop, NVIDIA 615.78.08. A coverage read therefore allocates/scans 4,096,000 floats and reads 16,384,000 bytes before formatting its result (`SceneDepth.java:228–245`).

Counts below were recomputed by streaming the original collapsed files, counting each matching render-thread record once, not summing overlapping methods:

| Diagnostic run | Render-thread CPU samples | With lodShare | With lodShare + glGetTexImage |
| --- | ---: | ---: | ---: |
| main-profile | 23,086 | 401 | 339 |
| main-ap-profile | 22,184 | 567 | 483 |

Raw chain examples are at `main-profile/async.jfr.cpu.collapsed:6852` and `main-ap-profile/async.jfr.cpu.collapsed:18322`: `Director.onTick → Probes.tick/call → FxLayer.describeTiming → SceneDepth.describe → lodShare → texImage → GL11.glGetTexImage → NVIDIA driver`. CPU samples also show the Java float scan. These are sampled CPU counts, **not** exact wall/GPU durations and not additive with their ancestors.

The longest clipped intervals were checked against original frame rows, respecting the next-row shift:

- `main-profile/native-longest-frame.json`: cycle 3729→3730 is 381.220 ms, 287.715 ms full-cycle CPU; original `stand-out/frames.csv:3730–3731`. Its raw native clip has 140 render-thread samples and **zero** lodShare samples. This patch does not explain or demonstrate removal of that longest hitch.
- `main-ap-profile/native-longest-frame.json`: cycle 3714→3715 is 381.135 ms, 307.862 ms full-cycle CPU; original `stand-out/frames.csv:3715–3716`. The raw native clip has 153 render-thread samples, 28 inclusive of lodShare, of which 2 contain glGetTexImage. `native-longest-frame.collapsed:548` includes the direct scan; the matching probe starts at t=102009.370 ms inside the 101817.011–102198.146 ms interval (`stand-out/events.jsonl:4430`). This is one contributor among others, not attribution of the entire 381 ms interval to readback.

The diagnostic profile windows are separately pinned by each `profile-window.json`. Host samples inside those windows show only KWin as foreign GPU work, with reported foreign SM up to 4%; these are shared-desktop observations, not an exclusive-GPU guarantee.

The unprofiled runs `main-a1-clean`, `main-ap-b1`, `main-a2` are separate recordings with `profile=none`, no screenshots and the same Incoming/stand JAR hashes. Their plans **still request the timing probe** and their raw event streams contain 83/84/83 timing calls respectively, including the computed coverage suffix. Thus “unprofiled/no-shot” does not mean free of this observer cost. Those recordings do not supply a before/after comparison for f69381911, and the AsyncParticles comparison cannot be repurposed as one. No faster ordinary-gameplay result was inferred.

## GL, state and threading

The default LOD description no longer allocates native buffers, binds textures, queries pack-buffer state, waits for texture readback or dereferences Minecraft options. The no-context regression exercises exactly that distinction.

The explicit path continues to require a current render-thread GL context and a valid previously rendered scene texture. Existing `texImage()` unbinds/restores a foreign pixel-pack buffer and uses pack alignment 4 (`SceneDepth.java:191–204`). It still binds the scene texture and scans synchronously; the patch neither introduces new GL state transitions nor broadens thread safety of the whole FxLayer API. Existing diagnostics do not fully preserve every texture/pixel-store state value; this is pre-existing behavior restricted further by the new guard, not a defect introduced here. No live-GL state/readback validation was performed.

## Regression and validation limits

`SceneDepthTelemetryTest.java:14–33` sets a valid LOD state with a positive texture and nonzero dimensions, invokes `SceneDepth.describe()` without a render context, and restores all four changed static fields in `finally`. That avoids the old early return which would otherwise make a no-context test vacuous. It also asserts default diagnostics are off. The test's fake texture is never used by GL on the fixed path.

Preserved `depth-regression-red.xml:2–23` and `depth-regression-before.log` show the test failing in the old lodShare → `_bindTexture` render-thread assertion, so it catches recurrence of this bug. The completed author build log `depth-fix-build.log` ends with `BUILD SUCCESSFUL in 4m 8s` and wrapper exit 0. Current `mod/build/test-results/test/TEST-ua.zentix.incoming.client.fx.layer.SceneDepthTelemetryTest.xml:2` reports one test, zero failures/errors. These are inspected existing validation artifacts, not reviewer-rerun tests.

The new test does not cover explicit DIAG=true dispatch, actual GL pack/texture-state preservation, context/resource reload behavior, or exact LOD-source/coverage text. Those rendering/diagnostic paths are unchanged and these omissions do not block this one-branch guard. No GameTest, client, GPU gate or CI was launched by this review-only session. Owner-side paired runtime measurements and required CI remain outside this report's acceptance evidence.

## Memory

Updated canonical `memory/perf-measure-pitfalls.md` with the verified between-frame observer/readback path, retained probe cost in unprofiled recordings, and the ordinary-gameplay attribution limit. No production source or evidence was modified.
