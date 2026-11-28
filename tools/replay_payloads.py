#!/usr/bin/env python3
"""Bytes of a Flashback replay by action and by mod payload: what a recording keeps and who sends it.

  python3 tools/replay_payloads.py <replay.zip> [--top 25] [--seconds]

Reads each `c*.flashback` chunk of the zip (Flashback 0.39 layout: magic, action names, snapshot size, snapshot
actions, then actions as varint id + int size + data; Flashback NeoForge Fixed's `modded_payload*` actions carry
protocol, payload id and its bytes). `--seconds` adds bytes per recorded second (`next_tick` actions / 20).
Snapshot actions are listed with a `snap` prefix; vanilla game packets by their packet id.
"""
import argparse
import collections
import struct
import sys
import zipfile


def varint(b, i):
    r = s = 0
    while True:
        x = b[i]
        i += 1
        r |= (x & 0x7F) << s
        s += 7
        if not x & 0x80:
            return r, i


def string(b, i):
    n, i = varint(b, i)
    return b[i:i + n].decode(), i + n


def scan(b, total, count):
    """Adds one chunk file's actions to the counters; returns its recorded ticks."""
    i = 4
    k, i = varint(b, i)
    actions = []
    for _ in range(k):
        name, i = string(b, i)
        actions.append(name)
    snapshot, = struct.unpack(">i", b[i:i + 4])
    i += 4
    snapshot_end = i + snapshot
    ticks = 0
    while i < len(b):
        a, i = varint(b, i)
        size, = struct.unpack(">i", b[i:i + 4])
        i += 4
        name = actions[a]
        key = name
        if name.endswith("next_tick"):
            ticks += 1
        if "modded_payload" in name:
            _, j = varint(b, i)
            payload, _ = string(b, j)
            key = "payload " + payload
        elif "game_packet" in name:
            packet, _ = varint(b, i)
            key = f"{name} #{packet}"
        if i < snapshot_end:
            key = "snap " + key
        total[key] += size + 5
        count[key] += 1
        i += size
    return ticks


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("replay")
    ap.add_argument("--top", type=int, default=25)
    ap.add_argument("--seconds", action="store_true", help="also bytes per recorded second")
    args = ap.parse_args()
    total, count = collections.Counter(), collections.Counter()
    ticks = 0
    with zipfile.ZipFile(args.replay) as z:
        for info in z.infolist():
            if info.filename.endswith(".flashback"):
                ticks += scan(z.read(info), total, count)
            else:
                total["file " + info.filename] += info.file_size
                count["file " + info.filename] += 1
    everything = sum(total.values()) or 1
    seconds = max(ticks / 20, 1e-9)
    print(f"{everything / 1e6:.1f} MB in {ticks} ticks ({ticks / 20:.0f} s)")
    for key, v in total.most_common(args.top):
        rate = f"  {v / seconds / 1e3:9.1f} kB/s" if args.seconds else ""
        print(f"{v / 1e6:10.1f} MB {100 * v / everything:5.1f} % {count[key]:8d}{rate}  {key}")


if __name__ == "__main__":
    sys.exit(main())
