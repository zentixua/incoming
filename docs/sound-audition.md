# Offline sound audition

Use `tools/audition_sounds.py` after capturing the same strike from a near and a far
listener with `tools/sandbox.sh shot … --wav …`. Run it once for a conventional
strike and once for the nuke. Keep each pair's world, yield/weapon, clock origin,
weather, category/master sliders and recording duration consistent; retain the
captures' `capture.json` alongside the report. The recordings carry Incoming's
actual timing, propagation, deafness, echoes and sound mix. The tool does not
reimplement those laws.

```sh
uv run tools/audition_sounds.py \
  --near mod/run/sandbox/fx/out/strike-near/strike-near.wav \
  --far mod/run/sandbox/fx/out/strike-far/strike-far.wav \
  --out mod/run/sound-audition/strike
```

The destination must be new. Source WAV/OGG files and game assets are read only.
Mono/stereo recordings up to 180 seconds are accepted and resampled to 48 kHz.
Nothing plays through the host speakers automatically.

Each perspective gets full-range and illustrative small-speaker WAVs, with 1, 4
and 16 overlapping copies by default. `mixed-N` adds one near recording to N far
copies. Copy starts are 75 ms apart; `--spacing 0` checks coherent simultaneous
overlap, and `--copies 1,4,16,100` includes a hundred copies. This sums signals at
their captured levels, without Incoming's runtime voice allocation or dynamic
mix. It is a stress audition, not a prediction of a hundred-strike game's output.

The small speaker averages stereo to mono, then applies second-order 180 Hz
high-pass and 4 kHz low-pass filters. No added harmonics, limiter or makeup gain:
listen for whether the attack and texture survive loss of low bass, and whether
stereo cancellation removes detail. This response is illustrative, not a model
of Artem's laptop speakers. Both versions include a 0.5-second filter tail.

`report.json` measures decoded signals **before audition attenuation**: sample
peak, a 4× FIR oversampled peak estimate, RMS, samples over full scale, headroom,
and peak increase over one copy. These are digital levels, not sound pressure or
LUFS; the peak estimate is not a certified BS.1770 meter. `null` means silence.
A negative headroom value identifies overload in the summed signal. A captured
WAV may already include OpenAL/output limiting; this check cannot recover peaks
that were removed before capture. Individual built OGG files can also be supplied
for asset-only audition, which does not include runtime timing or mixing.

All output WAVs receive **one common attenuation**, with 6 dB of peak margin by
default (`--margin`). No file is boosted or independently normalized: the near/far
and full/small level differences survive. Compare those files at one playback
volume; do not interpret their deliberately reduced output levels as game levels.
The report records the gain and hashes both inputs for repeatability.

```sh
uv run --with numpy --with scipy --with soundfile \
  python -m unittest discover -s tools -p test_audition_sounds.py -v
```

The check borrows the offline audition idea from Stink-O's MIT-licensed Orbital
Laser, commit `8ae6409a`. Its implementation is independent; no upstream code or
audio is included. It changes neither sound assets nor runtime playback.
