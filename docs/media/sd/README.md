# SD sound evidence (PR #436)

The WAV files retain the capture gain; none are loudness-normalized.

| Set | Environment | Time reference | Purpose |
| --- | --- | --- | --- |
| `rv2-*-3.4.1.wav` / `rv2-*-main.wav` | Archival RV2 full-pack captures, identical camera sites within that pair | Blast and launch at 1 s; flyby closest approach at 10 s | The reported 3.4.1/main level and timbre differences |
| `main_*` / `sd_*` | New sandbox captures, same flat world, camera, seed and shaderpack within that pair | Effect start at 0 s, flyby at closest approach | Isolate SD's fuel-fire playback change |
| `fire-envelope.png` / `.json` | Offline asset diagnostic, constant source heat, files calibrated to the same reference | Fire starts at 0 s | Demonstrate the old recording's fades/gaps without any propagation or hearing effects |

RV2 is not the sandbox: terrain, reflections and mod environment differ. Do not treat these sets as a controlled
three-way measurement. RV2 segments are cut directly from the original OpenAL WAVs, with the offsets recorded in
`rv2-segments.json`; they are not decoded from the Ogg listening previews. The paired JPGs are the original RV2
frames at 0.1, 1 and 10 seconds where available.

The asset diagnostic uses main's 1.1× playback and 13-second retrigger interval, compared with SD's native-speed
12-second loop. Both recordings are calibrated to −19 LUFS at their maximum, as the existing source transfer does.
At constant heat, main dips to −62.1 LUFS; SD's range is −19.1 to −23.1 LUFS. This diagnostic is not a game capture.

Listen to the sandbox drone at 9–14 s and 22–27 s: fuel is still burning, so the recording must not fade/restart.
The peak of the hit, launch and motor level keep the existing source/propagation scale. A quieter physically modelled
source is not raised to the level of 3.4.1's hand-tuned recording.

## Sandbox result

Iris used `ComplementaryUnbound_r5.9.3.zip`. Each version booted in a fresh client. After clearing a scene, a
17-second quiet interval let main's untracked one-shots finish before the next capture. Blast seed, camera and site
match; launch/flyby keep their native randomness. The exact source-file substitution is recorded in
[sandbox/provenance.json](sandbox/provenance.json). The compared main files are identical across main commits
`3a8b6eb1` and `4498ecfc`; all other code was held constant in the pair. The PR was then rebased onto `4498ecfc`.

| Scene | Main WAV | SD WAV | Momentary maximum, main / SD |
| --- | --- | --- | --- |
| Drone blast, 50 m, 32 s | [WAV](sandbox/main_drone50/main_drone50.wav) | [WAV](sandbox/sd_drone50/sd_drone50.wav) | −15.1 / −15.0 LUFS |
| Missile blast, 50 m, 16 s | [WAV](sandbox/main_missile50/main_missile50.wav) | [WAV](sandbox/sd_missile50/sd_missile50.wav) | −16.0 / −16.1 LUFS |
| Missile launch, 12 s | [WAV](sandbox/main_launch/main_launch.wav) | [WAV](sandbox/sd_launch/sd_launch.wav) | −17.6 / −17.7 LUFS |
| Drone flyby, 20 s | [WAV](sandbox/main_flyby/main_flyby.wav) | [WAV](sandbox/sd_flyby/sd_flyby.wav) | −24.0 / −24.1 LUFS; the maximum is the later distant blast |

For the motor itself, the 1–3 s band RMS is −43.02 / −43.05 dBFS. The fire's 30–3000 Hz band avoids the 4874 Hz
hearing-loss tone that dominates the full-band late tail:

| Fire interval | Main | SD | Change |
| --- | --- | --- | --- |
| Drone, 9–12 s | −57.4 | −52.9 | +4.5 dB |
| Drone, 12.5–13.5 s | −72.2 | −52.6 | +19.6 dB: fills the imposed gap |
| Drone, 22–27 s | −58.5 | −52.7 | +5.9 dB |
| Missile, 12.5–13.5 s | −60.0 | −52.9 | +7.2 dB |

Values above are band RMS in dBFS, without gain normalization. All eight WAVs have zero full-scale samples.
[Full measurements](sandbox/metrics.json) and [momentary loudness plot](sandbox/loudness.png) are included.
The plot includes hearing loss; use the band measurements to judge the fire envelope.

Frames and capture manifests are alongside each WAV. Compare the drone at
[main 0.1 s](sandbox/main_drone50/main_drone50_000.10s.png) /
[SD 0.1 s](sandbox/sd_drone50/sd_drone50_000.10s.png), then
[main 10 s](sandbox/main_drone50/main_drone50_010.00s.png) /
[SD 10 s](sandbox/sd_drone50/sd_drone50_010.00s.png).
The source image is unchanged; the continuing fuel fire supplies the corrected continuous sound.
