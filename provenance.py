#!/usr/bin/env python3
"""Write hashes and capture timing for the unchanged AS2 sandbox PNG/WAV artifacts."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parent
cases = {}
for path in sorted(root.glob('*/capture.json')):
    data = json.loads(path.read_text())
    cases[path.parent.name] = data
files = {}
for path in sorted(root.rglob('*')):
    if path.is_file() and path.suffix in {'.png', '.wav'}:
        files[str(path.relative_to(root))] = hashlib.sha256(path.read_bytes()).hexdigest()
result = {
    'before': 'cd310f2b',
    'after': 'a9891ad674d830a4802522b99718bf80b2866895',
    'renderer': 'development sandbox; vanilla renderer; no Iris; no full-pack run',
    'host_power_profile': 'unchanged; no timing or FPS comparison',
    'camera_note': 'distance is horizontal; close 4 m / height 3 m, wide 18 m / height 5 m',
    'image_edits': False,
    'audio_mastering': False,
    'cases': cases,
    'sha256': files,
}
(root / 'provenance.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
print(f'{len(cases)} captures, {len(files)} original image/audio files')
