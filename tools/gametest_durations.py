"""Time batch GameTest according to logs - a table by which CI divides GameTest between machines (devtest GameTestShards).

    python3 tools/gametest_durations.py <log>... → mod/src/devtest/resources/gametest-durations.json

Log - run/gametest/logs/latest.log or task log GameTest from GitHub Actions; for a run in parts - logs of all parts,
 for a batch of several runs - the average. batch lasts from its line “Running test batch” until the next batch or
 until the end of the tests. The table only equalizes the parts in time: the outdated one does not lose tests, the parts come out more unevenly.
"""
import json
import os
import re
import sys
from datetime import datetime

from paths import MOD

OUT = os.path.join(MOD, "src", "devtest", "resources", "gametest-durations.json")
# line time: log GitHub Actions (“2026-10-01T16:29:59.2208167Z ...”) or latest.log (“[01Oct2026 16:40:47.028] ...")
GITHUB_TIME = re.compile(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d+))?Z ")
LOG_TIME = re.compile(r"^\[(\d\d\w{3}\d{4} \d\d:\d\d:\d\d\.\d{3})\] ")
BATCH = re.compile(r"Running test batch '(.+):\d+' \(\d+ tests\)")
END = "GAME TESTS COMPLETE"


def time_of(line):
    m = GITHUB_TIME.match(line)
    if m:
        return datetime.fromisoformat(m.group(1)).timestamp() + float("0." + (m.group(2) or "0"))
    m = LOG_TIME.match(line)
    if m:
        return datetime.strptime(m.group(1), "%d%b%Y %H:%M:%S.%f").timestamp()
    return None


def batches(path):
    """batch → seconds in one log."""
    out, current, start = {}, None, None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            at = time_of(line)
            if at is None:
                continue
            m = BATCH.search(line)
            if (m or END in line) and current is not None:
                out[current] = out.get(current, 0) + at - start
                current = None
            if m:
                current, start = m.group(1), at
    if current is not None:
        raise SystemExit(f"{path}: batch {current} did not end - the log is torn off")
    return out


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    runs = {}
    for path in sys.argv[1:]:
        for name, seconds in batches(path).items():
            runs.setdefault(name, []).append(seconds)
    if not runs:
        raise SystemExit("there are no lines in the logs “Running test batch”")
    table = {name: round(sum(s) / len(s), 1) for name, s in sorted(runs.items())}
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(table, f, ensure_ascii=False, indent=1, sort_keys=True)
        f.write("\n")
    print(f"batch {len(table)}, total {sum(table.values()):.0f} s → {OUT}")


if __name__ == "__main__":
    main()
