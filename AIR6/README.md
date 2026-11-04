# Current native airburst frames

Production source: `476355384f944d094856348c31ea0dd6cab31aa9`. These captures use the unchanged model and renderer with a temporary, non-shipping devtest packet/metadata fixture. Fourteen compiled model/renderer/shader entries match the [previously pinned artifact](../artifact-manifest.json) byte for byte; see [compiled hashes](compiled-manifest.json) and [fixture diff](capture-fixture.patch). No production fix is included.

[GAME chronology](previews/game-chronology.png) · [Iris comparison](previews/iris-comparison.png) · [Visibility controls](previews/visibility-controls.png)

Previews are labeled crops/resizes of native game frames, with no generated content or enhancement. All 73 original 2560×1412 PNGs, their capture metadata and seven unmodified 45-second 48 kHz stereo WAV slices are in [raw](raw). [Frame times](frame-times.csv), [image hashes and camera values](frames.json), [audio hashes](audio-manifest.json), [environment](environment.json) and [Iris identity/pipeline evidence](iris-input-manifest.json) accompany them. The source full-frame view was checked to retain both cap and foot. Audio appearance/quality was not evaluated.

## Method

Four continuous-age sequences: 5/15 kt AIR/GROUND, frames requested at 0.1, 0.5, 1, 3, 5, 10, 15, 30, 60 and 120 seconds. Actual `nuclearAge` is authoritative; all chronological frames are within 0.0161 seconds of their requested time. No seek occurs in these sequences. AIR heights are 410.394227 m and 591.890898 m; GROUND height is zero. Ground Y=65, seed1234, detonation wind0, clear visibility20km, noon6000, effects scale1, synthetic fires=false, MEDIUM dust. Camera is8km south/300m AGL, looking toward2000m AGL, FOV70. Only the existing client detonation entry is invoked; no ruin simulation or city fire is included.

Primary packets explicitly use GAME wave0.25. A separate 15kt AIR sequence uses REAL wave1 through60s: sandbox's normal REAL default is not confused with the primary GAME captures. A verified Iris/Sodium/ComplementaryUnbound_r5.9.3 comparison repeats15kt AIR/GROUND through60s; this is not a full-modpack capture. Exact pack warnings are retained in [logs](logs).

Pall-OFF15/30/60s and a4km/1.62m eye-height60s pair are explicit replay-age diagnostics, captured three seconds after their initial-age packet. OFF retains a LOW mushroom, so the comparison also changes sampling quality. It is not a pixel-exact optical subtraction. The123s late frame is a framing probe. In the high-camera no-Iris scene, distant terrain is absent without LOD and nearby ground is below the view; dust appears against sky. In the low-camera OFF scene, vanilla clouds partly occlude the cap.

Power samples changed from low-power at22:01:28UTC to performance at22:06:26UTC; the exact transition was not sampled. Do not derive a throughput comparison from these appearance captures. Nuclear detail is scenario-pinned to the chosen setting.

## Bounded observations

AIR shows a detached cap and ground dust at15s; a low separate stem becomes conspicuous at30–60s and remains separated at120s. Matching GROUND has a continuous-looking stem into the cap. OFF reveals the early low dust body without filling the large gap. At4km eye height, MEDIUM pall obscures the view; OFF reveals the separated bodies. The selected Iris comparison retains the same distinction.

These frames establish actual current visibility in the controlled scene, not the historical player's exact scene or full physical calibration. The model's native60s dusty tops/cap bottoms are5kt620.6/1986.0m and15kt873.1/2998.3m. The renderer cannot show matter absent from this model gap. A possible separated dusty stem is supported by the earlier checked [NRC1985 observation](https://nap.nationalacademies.org/books/540/gif/22.gif) and [NAS2025 discussion](https://www.nationalacademies.org/read/27515/chapter/4); that does not validate the particular transport coefficient, condensation approximation, opacity or gap duration. No automatic opaque connector is justified by this comparison. Known source water/arrival-clock inconsistencies are not experimentally closed here. Independent renderer review remains separate from these author's observations.
