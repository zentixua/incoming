"""Compare exact snapshot outputs; report median local diagnostic times separately."""
from pathlib import Path
import re, statistics, json
root = Path(__file__).resolve().parent
all_rows = {}
for name in ('main', 'branch'):
    rows = []
    for line in (root / f'cpu-{name}' / 'run.log').read_text().splitlines():
        if 'MTRUIN case=' not in line:
            continue
        rows.append(dict(re.findall(r'(\w+)=([^ ]+)', line.split('MTRUIN ', 1)[1])))
    all_rows[name] = rows
(root / 'snapshot-results.json').write_text(json.dumps(all_rows, indent=2) + '\n')
lines = ['# Paired 15 kt snapshot diagnostic', '', '| Fixture | Floating main → PR | Logs left main → PR | Trees recognized main → PR | Added cells main → PR | Median solve ms main → PR |', '| --- | ---: | ---: | ---: | ---: | ---: |']
for case in dict.fromkeys(r['case'] for r in all_rows['main']):
    group = {name: [r for r in rows if r['case'] == case] for name, rows in all_rows.items()}
    assert all(len(g) == 3 for g in group.values()), case
    def pair(key):
        for g in group.values():
            assert len({r[key] for r in g}) == 1, (case, key)
        return ' → '.join(group[n][0][key] for n in ('main', 'branch'))
    ms = ' → '.join(f'{statistics.median(float(r["totalMs"]) for r in group[n]):.2f}' for n in ('main', 'branch'))
    lines.append(f'| {case} | {pair("floating")} | {pair("logsAfter")} | {pair("trees")} | {pair("added")} | {ms} |')
lines += ['', '`Added cells` includes all added non-air blocks (including fire); it is not a conserved rubble volume. Tree transfers outside the decoded region are counted in the JSON but not applied. Timings cover fracture/collapse/plan finishing for nine chunks only, exclude extraction and commit, and are not an isolated performance gate. See README for limits.', '']
(root / 'snapshot-results.md').write_text('\n'.join(lines))
