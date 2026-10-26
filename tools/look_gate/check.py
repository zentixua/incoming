#!/usr/bin/env python3
"""Is the look gate run valid for comparison with the standard - according to the client log (latest.log script probe).

  python3 tools/look_gate/check.py <latest.log> → 0 - valid; 1 - no (reasons in stderr)

 Not valid if:
  - hearing is stunned (lines “SCENARIO probe hearing damage off" no or there is "on"): scenes go in a row by one player, and
     deafness from the explosion in 50 m suppresses the tails of the following scenes (F2b7, F2b8 22.10);
   - the scene goes in different air than in its reference game time (ProbeScenario.START): wind and stability class -
     function of game time, and the sampling scene, started at the wrong time, was in foreign air (F2b8, F2b9 22.10: fire smoke
     stripe instead of a pillar); sample itself compares the air (ProbeScenario.sameAir: “same”, “differs”, “none” - for the version
     without world air); log without this comparison - according to the tolerance LATE tick;
   - the run itself stopped with the line “SCENARIO” probe FAIL";
   - the frame was taken from a different point where the scene placed the eye (line "SCENARIO probe shot <frame> camera ... off <blocks>»
    further ProbeScenario.CAMERA_SLIP): the server returned the player back - the camera behind the start before 23.10 stood with its feet on 2 cm
     in the floor, and the frame 0,5 was filmed from the place before the start, without launcher and clouds (LNB 23.10).
"""
import re
import sys

LATE = 200  # sample log without air comparison (up to F4): scene later than its time, more than this - different air
EARLY = 5   # ProbeScenario.begin: catches up to START − 5
SLIP = 1.0  # ProbeScenario.CAMERA_SLIP, blocks

HEARING = re.compile(r"SCENARIO probe hearing damage (on|off)")
START = re.compile(r"SCENARIO probe (\S+) game (-?\d+) \(reference start (null|-?\d+)(?:, air ([^)]*))?\)")
END = re.compile(r"SCENARIO probe (\S+) ends at game (-?\d+) \(reference end (-?\d+), air ([^)]*)\)")
FAIL = re.compile(r"SCENARIO probe FAIL (.*)")
CAMERA = re.compile(r"SCENARIO probe shot (\S+) camera (.*) off (\d+(?:\.\d+)?)")


def problems(lines):
    """Reasons why the run is not good (empty - good)."""
    out = []
    hearing = None
    scenes = 0
    for line in lines:
        m = HEARING.search(line)
        if m:
            hearing = m.group(1)
            continue
        m = FAIL.search(line)
        if m:
            out.append("run stopped: " + m.group(1).strip())
            continue
        m = CAMERA.search(line)
        if m:
            if float(m.group(3)) > SLIP:
                out.append(f"{m.group(1)}: shot taken in {m.group(3)} blocks from the scene camera location (server brought the player back?)")
            continue
        m = END.search(line)
        if m:
            if m.group(4).startswith("differs"):
                out.append(f"{m.group(1)}: towards the end of the scene there is a different air than the standard: {m.group(4)}")
            continue
        m = START.search(line)
        if m:
            scenes += 1
            case, game, ref, air = m.group(1), int(m.group(2)), m.group(3), m.group(4)
            if ref == "null":
                out.append(f"{case}: scene has no reference time (ProbeScenario.START)")
            elif game < int(ref) - EARLY - 1:
                out.append(f"{case}: started in game {game}, earlier than the standard {ref}")
            elif air is not None:
                if air.startswith("differs"):
                    out.append(f"{case}: different air than at reference time {ref} (game {game}): {air}")
            elif game > int(ref) + LATE:
                out.append(f"{case}: started in game {game}, standard {ref} - other air (tolerance {LATE} tick)")
    if hearing is None:
        out.append("no line \"SCENARIO probe hearing damage\": hearing not checked (old class ProbeScenario?)")
    elif hearing == "on":
        out.append("hearing is deafened (nuke_tinnitus = true): scene tails are muffled by past explosions")
    if scenes == 0:
        out.append("no scene started")
    return out


def main():
    with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
        bad = problems(f)
    for p in bad:
        print("look_gate: " + p, file=sys.stderr)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
