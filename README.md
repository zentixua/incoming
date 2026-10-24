Revision 2 (Claude round-1 fixes): [new captures, movies, WAVs and limits](r2/README.md).

The material below is the round-1 archive.

# WV — conventional shock front and camera evidence (PR #425)

Ground-hit Shahed, 50 kg TNT in the current passport, seed 7. Captured in the dev sandbox, without Iris, on the laptop in its quiet power profile. Requested window: 1280×720; actual framebuffer: **1280×692**. Camera shake: 1.0, music: 0.0, tinnitus enabled. No original Prism instance, game settings or user world was changed.

## Sources

- WV footage: `8b0ff5c8`, branch `claude/wv-blast-front`.
- Main footage: `3a8b6eb1`, plus the identical dev-only `stance` / `weather` commands in [harness.patch](harness.patch). Production main code was not changed for the reference.
- Final PR head: `c02dbc13`, rebased onto main `4498ecfc` (#429, salvo route lanes). The captured WV production effect classes and nuclear models are unchanged by that rebase. The reference worktree's temporary harness patch was removed after capture.
- 3.4.1 visual reference: existing FX/RV2 archive, `compare-final/drone-day-50`. Newisle with Iris, different scenery/camera height. There is no matched 3.4.1 sandbox recording at 20 or 100 m in that archive; it is a presentation reference at 50 m.

## Files

- `compare-20.jpg`, `compare-50.jpg`, `compare-100.jpg`: main above WV, same requested moments. Every cell gives requested / **actual effect age**.
- `main/d*/`, `wv/d*/`: original PNGs, original 4-second WAVs, capture manifests. Early cold screenshots are delayed on both runs; the manifest age, rather than the requested filename, is the actual sample time.
- `standing-main-wv-50.mp4`: actual standing-camera movement, main left / WV right, no audio in the comparison montage.
- `main-motion50.mp4`, `wv-motion50.mp4`: individual captures with blast audio. 241 captured frames over approximately 4 seconds; playback uses actual capture-age intervals, output at 60 fps and half resolution (640×346). It starts at the first observed frame; WAV audio is trimmed by that same offset. Original untrimmed WAVs and motion manifests are also included.
- `motion-poses-50.jpg`: selected actual frames of that movement. The view returns to its initial horizon by the end.
- `front-crop-50.jpg`: 2× nearest-neighbour crops at actual ages 0.028 and 0.051 seconds, before the front reaches the standing viewer. Only pixels from the recorded frames; clouds also drift between runs, so this is not a pixel-difference test.
- `rain-50.jpg`, `main/wet50/`, `wv/wet50/`: humid-air check after `weather rain` and `wait 100`. The early pale haze overlaps the flash/glare; it is short-lived. Existing CloudModel condensation is unchanged.
- `reference-341-50.jpg`: archived 3.4.1 presentation alongside WV, at 0.1/0.5/1/3 seconds.
- `camera.png`, `camera.csv`: **calculated curves**, not a screen recording. 3.4.1 shake reconstructed from its distance bands, unchanged main body lean, WV tremor + body + neck. Time zero is each model's front arrival. Generator and plot script are included.
- `front.csv`: model predictions for 3, 30, 50, 150 and 450 kg TNT at 20, 50, 100 and 300 m (pressure, arrival, phase and impulse).

## What the capture shows

WV adds torn green clumps where main leaves a bare field. At 20/50 m the view shakes and rocks more strongly; at 100 m the response is smaller. The subsequent return is visible in the motion capture. Grass pieces settle close to where they were torn, rather than following an enlarged blast trajectory. The existing fireball, smoke and GroundDust remain the baseline for this change.

The refraction is a brief shimmer while the front moves through the frame; at 20 m the front arrives in 0.033 s, at 50 m in 0.115 s and at 100 m in 0.258 s. Broad ground dust is maintained by its own layer, outside WV's changes.

## Reproduction

Run the same commands on each tree (main needs the supplied dev harness patch):

```sh
tools/sandbox.sh start --wait
tools/sandbox.sh fx 'stance stand' 'wait 10'
tools/sandbox.sh shot d50 'blast drone 7' \
  --cam '50 1.62 0 3' --at 0,0.05,0.1,0.15,0.25,0.5,1,3 --wav 4
tools/sandbox.sh fx 'weather rain' 'wait 100'
tools/sandbox.sh shot wet50 'blast drone 7' \
  --cam '50 1.62 0 3' --at 0,0.03,0.05,0.1,0.15,0.25,0.5,1 --wav 3
tools/sandbox.sh stop
tools/gpu_lease.sh release
```

The spectator default flies and suppresses standing body sway. `stance stand` uses a creative player with flying disabled. Only dev capture controls were added; the actual camera and effects are the production paths.

## Validation

Local build: **1,230 tests passed**, zero failures/errors/skips. Local `runGameTestServer -PgametestOnly='blast_.*'`: **19/19 required tests passed**. [Final CI run 40039841315](https://github.com/zentixua/incoming/actions/runs/40039841315), head `c02dbc13`: build and all six GameTest shards passed. PR «Записка» passed.

Earlier attempts on earlier heads encountered the unchanged `SurfaceGameTests.missileNearby` fixture (a near wooden post remained), and one attempt also failed to load a distant launch region. Only failed jobs were repeated once on those heads, and passed; those server paths were not changed to make WV pass. Final head passed its first CI attempt.

Audio: all six dry WAVs contain 4.0 s of 48 kHz stereo signed 16-bit audio, without clipping or added normalisation. Source recordings and credits: [SOUND-CREDITS.md](https://github.com/zentixua/incoming/blob/c02dbc13/SOUND-CREDITS.md). The camera comparison MP4 deliberately carries no mix; individual clips and WAVs retain the captured sound.