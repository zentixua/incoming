#!/usr/bin/env python3
"""Summarize the checked-in CITYBENCH evidence logs; no game/world access."""
import json
import re
from pathlib import Path

root = Path(__file__).parent
phases = {}
for phase in 'cde':
    text = (root / f'city-{phase}-evidence.log').read_text()
    saved = 0
    samples = []
    for line in text.splitlines():
        match = re.search(r'CITYBENCH progress saved=(\d+)', line)
        if match:
            saved = int(match[1])
        match = re.search(r'CITYBENCH seconds=([\d.]+) cursor=(\d+)', line)
        if match:
            samples.append(dict(seconds=float(match[1]), cursor=int(match[2]), saved=saved))
    phases[phase] = samples
last = phases['e'][-1]
complete = 85849
# C/D stop timestamps are second-resolution; E logs its own elapsed nanoseconds.
wall = 366 + 79 + last['seconds']
empty = 484901 - complete
empty_samples = [s for s in phases['e'] if 369 < s['seconds'] < 461]
empty_rate = (empty_samples[-1]['cursor'] - empty_samples[0]['cursor']) / (empty_samples[-1]['seconds'] - empty_samples[0]['seconds'])
saved_seconds = (wall - empty / empty_rate) / complete
city = next(s for s in phases['e'] if 319 < s['seconds'] < 321)
print(json.dumps(dict(
    complete_chunks=complete, active_wall_seconds=wall, raw_complete_chunks_per_second=complete/wall,
    final_head_complete=last, final_head_city_interval=city,
    city_chunks_per_second=city['saved']/city['seconds'],
    empty_positions_per_second=empty_rate,
    modeled_saved_seconds_per_chunk=saved_seconds,
    projected_385k_seconds=385000*saved_seconds+(484901-385000)/empty_rate,
), indent=2))
