# MT-B6 arrival evidence

Production head: `d20c74eca766510ea300973f8b496e0c0abf2985` (PR #563). Baseline: `ec326bcf0`, including PERF #559 and console #558. All clients ran under the GPU lease in virtual KWin, with host audio disabled; no player instance or world was used.

## Existing tools and implementation

| Available component | Use |
|---|---|
| Incoming far effects layer, exposure, scene depth and analytic glow | Batched compact head and two emitted wake segments; no new shader or dependency |
| Photon particle/trail runtime | Considered; it does not solve the scheduled burst endpoint or event clock, so adding it would not replace this small change |
| Existing FlightScale / NUKE airframe | GAME dive speed 25 blocks/tick; REAL takes the existing article value. No new setting or flight data |
| Existing nuclear sound schedule | Unchanged. No approach loop, whistle or additional crack |

A maximum twelve-second visual window fits after the existing nine-second boost, including the minimum ten-second flight setting. This is a game approach, not physical flight. A ready target's burst geometry is saved in the scheduled strike and consumed by the actual detonation. The client renders one tick behind batched authoritative snapshots and never extrapolates to a guessed deadline. Server stalls therefore freeze motion upstream, like entity interpolation. Terrain edits after commitment cannot move the burst endpoint. Head removal uses the scheduled strike ID; emitted wake lasts 0.2 seconds independently.

An unready target has no speculative head. The previous 600-tick fallback remains for strikes that never committed a path; changing pre-launch admission/readiness belongs to B1/B4. This PR does not claim to solve that fallback. Existing old recordings keep the legacy graphic; new snapshots suppress it. Snapshot and burst application share replay marker ordering.

## Paired frames and sound

`head/depth-day`, `head/depth-night` and `main/main-day`, `main/main-night-clean` use the same arrival fixture, 15 kt AIR, camera `2000 2 0 800`, burst at fixture age 12 s, frames at 10 / 11 / 11.5 / 11.95 / 12.1 / 12.5 s. Iris + Complementary Unbound r5.9.3, 1920×1052 actual framebuffer (requested window 1920×1080), 60 fps. The baseline only receives equivalent warning/burst packets; its production code is unchanged. The baseline harness patch and its two added devtest classes are included. New fixture code is in the PR.

The new head is a small point in daylight and an amber-white head with a short cooling wake at night. In the sampled baseline views the arrival is absent before the flash; the former 60 km/3 s countdown graphic offers a very narrow view interval and uses a different clock. These sparse frames do not claim to characterize every baseline animation frame. The 12.1/12.5 s images include the existing flash/fireball without changes to their renderer, shader, model or audio.

WAVs are 35-second stereo 48 kHz stems, with siren/boost excluded and actual nuclear bang retained. `audio_measure.py` reads PCM without playback. Before flash, clean captures have only ±1 quantization dither (about −96 dBFS). Head day/night first signal above 16 PCM units: 30.826/30.842 s, about 18.83 s after flash; baseline day/night: 30.877/30.876 s. This preserves the existing GAME wave delay. It is a timing/silence measurement, not a subjective listening verdict. The initial baseline night take retained previous-blast audio and is excluded; the clean take is from a fresh client.

The wall probe exposed glow pixels over masonry: positive source-sized softness now selects the shared scene-depth sample for head and wake. Compare `head/final-wall` (before) with `head/depth-wall` (fixed); only the part above the roof edge remains.

Additional sequences (rain/128 captured before the depth correction, same geometry): `head/final-rain`, `head/arrival-100-final`, `head/final-128`, and `head/final-wall`. No Voxy integration or opaque shader-cloud acceptance is claimed by this reduced sandbox. Existing shared depth integration is reused; actual full-pack Voxy/Flashback playback remain outside the captured evidence.

## Checks and costs

- Full local `build` passed, including unit tests; `runGameTestServer -PgametestOnly=icbm_arrival`: 1/1 passed. It changes terrain after endpoint commitment, checks exact scheduled-time detonation and saved-state roundtrip.
- Final focused run: 5 arrival tests, 48 packet golden tests, 9 replay tests passed. Arrival tests cover minimum/default/maximum flight durations, large coordinates, 15/100/1000 kt endpoint geometry in air/surface modes, late/bunched clocks, backward seek, cancellation and finite wake. Existing packet goldens are unchanged; new approach and trailing strike-ID fixtures were added. Protocol 53.
- One arrival emits at most 4 quads, 100 emits 400; 128 is a stress fixture, not a newly imposed admission cap. No new entities, chunk tickets or sound voices. Already launched events are not dropped.
- In the final 100-arrival Iris sandbox (`head/depth-100`), quad GPU mean was 0.100–0.104 ms, with existing world-depth copy about 0.05 ms and whole measured effects passes about 0.16 ms. Effects-layer wall time was 0.211–0.213 ms; this includes collection/sorting/upload and is **not** an isolated 0.10 ms path-selection measurement. No such CPU target achievement is claimed. Raw `SANDBOX shot depth-100` log lines are included.
- Full snapshots cost about 107 bytes for the one-record golden fixture; 100 concurrent paths at 20 Hz are approximately 0.2 MB/s per observing connection before framing/compression. They are sent only while terminal approaches are active, plus launch/join/completion state. This avoids a second sparse state protocol and restores replay/late joins.

Final paired scene bench and CI results are added below when complete. The normal scene bench uses 100 conventional impacts to catch regressions in the shared layer; arrival-only GPU work is measured separately above. No merge or release is authorized by this handoff.


| View | Main | PR head |
|---|---|---|
| Day, 11.95 s | [Frame](main/main-day/main-day_011.95s.png) | [Frame](head/depth-day/depth-day_011.95s.png) |
| Night, 11.95 s | [Frame](main/main-night-clean/main-night-clean_011.95s.png) | [Frame](head/depth-night/depth-night_011.95s.png) |
| Day, flash +0.1 s | [Frame](main/main-day/main-day_012.10s.png) | [Frame](head/depth-day/depth-day_012.10s.png) |
| Night audio | [WAV](main/main-night-clean/main-night-clean.wav) | [WAV](head/depth-night/depth-night.wav) |
| Day audio | [WAV](main/main-day/main-day.wav) | [WAV](head/depth-day/depth-day.wav) |
| Masonry cover | [Before depth correction](head/final-wall/final-wall_011.95s.png) | [Corrected](head/depth-wall/depth-wall_011.95s.png) |
