"""Measure recorded arrival stems; Python standard library only. No playback or asset edits."""
import array
import json
import math
import sys
import wave
from pathlib import Path

out = []
for arg in sys.argv[1:]:
    path = Path(arg)
    with wave.open(str(path), 'rb') as w:
        assert w.getsampwidth() == 2
        rate, channels = w.getframerate(), w.getnchannels()
        samples = array.array('h', w.readframes(w.getnframes()))
    pre = samples[:int(12 * rate * channels)]
    nonzero = next((i / (rate * channels) for i, x in enumerate(samples) if abs(x) > 16), None)
    rms = math.sqrt(sum(x*x for x in pre) / len(pre)) / 32768
    out.append(dict(file=path.name, seconds=len(samples)/(rate*channels), pre_flash_peak=max(map(abs, pre)),
                    pre_flash_rms_dbfs=20*math.log10(rms) if rms else None,
                    first_above_dither_seconds=nonzero, peak=max(map(abs, samples))))
print(json.dumps(out, indent=2))
