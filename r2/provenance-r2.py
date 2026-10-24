#!/usr/bin/env python3
"""Record unmodified sandbox evidence for AS2 review revision 2."""
import hashlib
import json
import struct
import wave
from pathlib import Path

root = Path(__file__).resolve().parent
out = root if (root / 'r2-road-wide' / 'capture.json').exists() else root / 'r2'
cases = {p.parent.name: json.loads(p.read_text()) for p in sorted(out.glob('*/capture.json'))}
previous = json.loads((out.parent / 'provenance.json').read_text())
for scene in ['road-wide', 'road-close', 'slab-wide', 'slab-close']:
    old = previous['cases'][scene + '-after']
    new = cases['r2-' + scene]
    assert old['camera'] == new['camera'], (scene, 'camera differs')
    assert old['site'] == new['site'], (scene, 'site differs')
files = {}
for path in sorted(out.rglob('*')):
    if not path.is_file() or path.suffix not in {'.png', '.wav'}:
        continue
    entry = {'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
    if path.suffix == '.png':
        entry['size'] = struct.unpack('>II', path.read_bytes()[16:24])
    else:
        with wave.open(str(path)) as sound:
            entry.update(rate=sound.getframerate(), channels=sound.getnchannels(), seconds=sound.getnframes()/sound.getframerate())
    files[str(path.relative_to(out))] = entry
meta = {
    'after': 'ce5aee5d5f1d9989d67c3c95021dbeca679e2a93',
    'before': 'a9891ad674d830a4802522b99718bf80b2866895',
    'renderer': 'vanilla development sandbox, no Iris, no full pack',
    'world_seed': 20261022,
    'host_power_profile': 'unchanged; no FPS comparison',
    'processing': 'original PNG and WAV, no edits or mastering',
    'cases': cases,
    'files': files,
    'max_age_error_seconds': max(abs(f['age']-f['t']) for c in cases.values() for f in c['frames']),
}
(out / 'provenance.json').write_text(json.dumps(meta, ensure_ascii=False, indent=2) + '\n')
print(f'{len(cases)} captures, {len(files)} image/audio files, max age error {meta["max_age_error_seconds"]:.3f}s')
