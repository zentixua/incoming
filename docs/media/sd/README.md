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
