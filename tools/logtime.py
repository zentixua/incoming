#!/usr/bin/env python3
"""Minecraft log-line times: shared parser for laptop-job extracts (tools/laptop-jobs/*.md).

A line starts with "[HH:MM:SS]", "[HH:MM:SS.mmm]" or "[01Oct2026 HH:MM:SS.mmm]" (date in production client
prod_client.py, modpack log4j format). Lines without a timestamp (exception stack, message continuation) have no time.
Across midnight, time continues beyond 24 h: a step log is one continuous recording (full.log from archives and latest.log).

As a module: ``sys.path.insert(0, "tools"); import logtime`` — ``timeline(lines)``, ``stamp``, ``body``.
From the shell: ``python3 tools/logtime.py LOG REGEX [--since REGEX]`` — lines containing REGEX, formatted as
"HH:MM:SS.mmm +S.s text": +S.s is seconds from the first ``--since`` line (without it, the first matched line);
earlier lines have negative offsets.
"""
import re
import sys

_TIME = re.compile(r"^\[(?:\d{1,2}\S{3,5}\d{4} )?(\d\d):(\d\d):(\d\d)(?:\.(\d{1,3}))?\]")
_BODY = re.compile(r"^\[[^\]]*\](?: \[[^\]]*\])*?: ")


def seconds(line):
    """Seconds from midnight for the log line or None if the line has no time."""
    m = _TIME.match(line)
    if not m:
        return None
    return int(m[1]) * 3600 + int(m[2]) * 60 + int(m[3]) + (int(m[4].ljust(3, "0")) / 1000 if m[4] else 0)


def stamp(t):
    """"HH:MM:SS.mmm" for seconds from timeline (time of day, no days)."""
    t %= 86400
    return f"{int(t // 3600):02d}:{int(t % 3600 // 60):02d}:{t % 60:06.3f}"


def body(line):
    """Message text without time, flow or source."""
    return _BODY.sub("", line, count=1)


def timeline(lines):
    """[(seconds, string)] lines with time in order; transition through midnight - +86400 (back more than 12 hours)."""
    out, day, prev = [], 0, None
    for line in lines:
        s = seconds(line)
        if s is None:
            continue
        if prev is not None and s + day < prev - 43200:
            day += 86400
        prev = s + day
        out.append((prev, line))
    return out


def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read().splitlines()


def main(argv):
    if len(argv) < 2:
        sys.exit(__doc__)
    path, pattern = argv[0], re.compile(argv[1])
    since = re.compile(argv[argv.index("--since") + 1]) if "--since" in argv else None
    rows = timeline(read(path))
    zero = next((t for t, l in rows if (since or pattern).search(l)), None)
    if zero is None:
        print(f"no reference line: {(since or pattern).pattern}")
        return
    for t, l in rows:
        if pattern.search(l):
            print(f"{stamp(t)} {t - zero:+.1f} {body(l)[:300]}")


if __name__ == "__main__":
    main(sys.argv[1:])
