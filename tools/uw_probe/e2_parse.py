#!/usr/bin/env python3
"""E2 numbers from e2_run.sh's frametimes.txt ("tick ms" per frame, tick = world game time): every take is a run of rising
ticks through the burst's game time (each `nuke … near` take sets it to 6000); its window starts at the front at the houses
(6000 + the front's delay, --front-ticks) and spans the take (--above / --inside ticks). Takes in order: HIGH above, HIGH
inside, MEDIUM above, MEDIUM inside. Per take: outer-frame mean, p95 and the worst 10 s window's mean.
    e2_parse.py <frametimes.txt> [--front-ticks 153] [--above 2000] [--inside 1900]"""
import sys
a = sys.argv
front = 6000 + (int(a[a.index("--front-ticks") + 1]) if "--front-ticks" in a else 153)
spans = [int(a[a.index("--above") + 1]) if "--above" in a else 2000, int(a[a.index("--inside") + 1]) if "--inside" in a else 1900]
rows = []
for line in open(a[1]):
    p = line.split()
    if len(p) == 2:
        try: rows.append((int(float(p[0])), float(p[1])))
        except ValueError: pass
# a line torn by a concurrent flush reads as one tick far off its neighbours: drop it
rows = [r for i, r in enumerate(rows) if not (0 < i < len(rows) - 1 and abs(r[0] - rows[i - 1][0]) > 300
        and abs(r[0] - rows[i + 1][0]) > 300 and abs(rows[i - 1][0] - rows[i + 1][0]) <= 300)]
runs, cur, last = [], [], None
for t, ms in rows:
    if last is not None and t < last - 300:
        runs.append(cur); cur = []
    cur.append((t, ms)); last = t
runs.append(cur)
takes = []
for i, r in enumerate(runs):
    w = [(t, ms) for t, ms in r if front <= t <= front + spans[len(takes) % 2]]
    if len(w) > 300: takes.append(w)
names = ["HIGH above", "HIGH inside", "MEDIUM above", "MEDIUM inside"]
for i, w in enumerate(takes):
    v = sorted(ms for _, ms in w)
    p95 = v[int(0.95 * (len(v) - 1))]
    worst, at = 0, 0
    span = spans[i % 2]
    for k in range(front, front + span - 200 + 1, 20):
        x = [ms for t, ms in w if k <= t < k + 200]
        if len(x) > 20 and sum(x) / len(x) > worst: worst, at = sum(x) / len(x), (k - front) / 20
    last_t = max(t for t, _ in w)
    print(f"r{i // 4 + 1} {names[i % 4]:14s} frames {len(w):5d} to +{(last_t - front) / 20:5.1f} s  mean {sum(v)/len(v):6.1f} ms  p95 {p95:6.1f} ms  worst 10 s mean {worst:6.1f} ms (from +{at:.0f} s)")
