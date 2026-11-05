# AIR8 source-clock correction

Component PR [#600](https://github.com/zentixua/incoming/pull/600), candidate c156f260588aaa967327ed6577f775e015bc0ec7, readiness base3efac357c. No release or overall runtime-readiness claim.

## Source and checks

The supplying ground layer, transported dust and moving foot use the same immutable detonation FrontProfile clock. Geometry, cap/rise law, estimated inflow and source material are retained. Source onset knots prevent pre-entry interpolation; one-second integer quadrature remains unchanged for the selected clock. Cache identity covers all24 source arrivals; pending/failed ownership is bounded to8.

Initial full local build at68a:1845 unit tests and native nuke_stem_clock1/1 passed. First independent source review found a conditional exceptional-completion race. Corrected c156: deterministic old-guard RED1/1; old physical source/foot clock RED4/7; restored targeted JUnit GREEN7/7. Final native GameTest1/1 and exact-tree CI41932474657 passed; tools/pr_ci exited0. CI merge treeb70bc89946f86200e294b0a49bec14f7a4732bdc equals c156. CI JAR SHA25644fd9cc161e52779fcf972ca860d0aecd8598e57ed502e4de4c1bc25747f8b25. Second fresh independent source review has no blockers; remaining frame/cost phase is pending. See checks/ and reviews/.

## Native paired evidence

[native/frames.json](native/frames.json), [model.csv](native/model.csv), [pair validation](native/pair-validation.json), [original before](native/before/raw), [original after](native/after/raw), [previews](native/previews). 58 original2560x1412 PNGs,10 unchanged45-second stereo48kHz WAV slices.29pairs have exactly matching recorded camera/FOV; maximum requested/pair age difference0.016003s. Previews only resize/crop and label; original bytes are hashed. Both production trees were restored clean after captures.133 candidate model/client-nuclear compiled classes exactly match the CI JAR.

Four primary5/15kt AIR/GROUND pairs: GAME wave0.25, effects_scale1, standard AIR heights410.394227/591.890898m or GROUND0, camera8km/300mAGL/look2000m, FOV70, MEDIUM, seed1234, detonationwind0, clear/noon, firesfalse, dryflatgroundY65. Ages1/5/10/15/30/60s. No Iris/full-pack recording. Geometry remains detached in AIR and connected in GROUND; lower dust/foot changes follow the source clock. No instant connector was added.

High-HOB control1000kt/2400m uses the same fixed8km/300m camera looking5000mAGL, OFF pall/LOW mushroom in BOTH versions to expose lower matter. Ages44/50/60/75/90s. The native actual first-source arrival21.558908s plus inflow40.548013s gives62.106922s; the old physical arrival3.197703s supplied it early. At50/60s the old stem top is180.915/466.182m and tau2025.454/2277.293; corrected stem top/tau are0. At75s corrected top310.634m/tau675.311 versus old858.604m/2121.477. Cap parameters are identical at these logged ages. The independent post-wave skirt remains visible; this is not disappearance of all ground dust. High framing partly crops the lower skirt in both versions.

These controlled client packets do not destroy a server world. Distant terrain is outside ordinary draw distance, so the dust disk is shown against sky; this is not a city/landscape realism reference. Sparse stills and estimated transport do not calibrate exact real-world gap duration or cloud composition. F1 all-water soil remains a known coverage-contract issue; centerwater/hasLand/knownWet are not a complete footprint. Final100-scene comparison and independent raw-frame/cost review will be added before component handback.
