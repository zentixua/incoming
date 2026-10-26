#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = ["numpy>=1.26"]
# ///
"""Edit previews from recorded shots (replayscan film using plan.py plans): a clip joins a candidate's shots in order
with simple cuts, game audio −14 LUFS, peaks no higher than −1 dBTP; alongside are a quiet-music version, EN/RU
captions and a report.

  uv run tools/clips/edit.py <film> [--music track.mp3 --credit "author line"] [--out <film>/previews]
                            [--clip far=<finished clip.mp4>]

<film> is the plan.py directory (runs.json, <replay>/plan.json, <replay>/film/best.json). In addition to filming frame
checks (qa.jsonl, verdict.json), another check uses the video frames themselves: each event flash is visible where
the game renders it (brightness in the event box just after exceeds that before), catching what block rays miss
(Distant Horizons terrain, smoke, shaders). Requires ffmpeg. `--clip far=<video>` (or `nuke=`) is a clip filmed outside
a replay (live, with its own frame checks and game audio): same loudness, music version and captions.
"""
from __future__ import annotations

import argparse
import json
import math
import subprocess
import sys
from pathlib import Path

import numpy as np

ORDER = ["rockets", "drones", "chaos", "far", "nuke", "pov", "fly"]
# Length of the video, with: preview – 20–35, “through the eyes of the players” – segment of one player
LENGTH = {"pov": (8, 20)}
# The brightness (0–255) in the frame of the event immediately after it is so much higher than before: the flash is visible
FLASH = 18
# Flash check frame: Video is reduced to this width (grayscale)
PROBE_W = 270

WEAPON = {
    "grad": ("Grad rockets", "«Град»"), "missile": ("cruise missiles", "крылатые ракеты"), "shahed": ("Shahed drones", "шахеды"),
    "lancet": ("Lancet drones", "«Ланцеты»"), "b2": ("a B-2 bomber", "B-2"), "bomb": ("a bunker buster", "бетонобойная бомба"),
    "icbm": ("an ICBM", "МБР"),
}
TAIL_EN = "Incoming mod for Minecraft Java 1.21.1, free on Modrinth and CurseForge."
TAIL_RU = "Мод Incoming для Minecraft Java 1.21.1, бесплатно на Modrinth и CurseForge."
# before release on Modrinth and CurseForge (--soon)
SOON_EN = "Incoming is a mod for Minecraft Java 1.21.1, coming soon to Modrinth and CurseForge."
SOON_RU = "Incoming — мод для Minecraft Java 1.21.1, скоро на Modrinth и CurseForge."
TAGS = "#minecraft #minecraftmods #moddedminecraft #майнкрафт #моды"


def run(cmd: list[str], **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=True, capture_output=True, **kw)


def duration(f: Path) -> float:
    out = run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(f)], text=True).stdout
    return float(out.strip())


def gray_frames(video: Path, fps: float, aspect: float) -> np.ndarray:
    """Grayscale video frames of width PROBE_W (array frame × row × column)."""
    h = round(PROBE_W / aspect / 2) * 2
    raw = run(["ffmpeg", "-v", "error", "-i", str(video), "-vf", f"fps={fps},scale={PROBE_W}:{h},format=gray", "-f", "rawvideo", "-"]).stdout
    return np.frombuffer(raw, np.uint8).reshape(-1, h, PROBE_W)


def project(cam: list[float], fov: float, aspect: float, p: list[float]) -> tuple[float, float, float] | None:
    """The location of the point in the frame (−1...1 to the right and up) and the depth along the axis of view; behind the camera - None. Turn like a player."""
    x, y, z, yaw, pitch = cam
    yr, pr = math.radians(yaw), math.radians(pitch)
    fwd = (-math.sin(yr) * math.cos(pr), -math.sin(pr), math.cos(yr) * math.cos(pr))
    right = (-math.cos(yr), 0.0, -math.sin(yr))
    up = (right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0])
    d = (p[0] - x, p[1] - y, p[2] - z)
    depth = sum(a * b for a, b in zip(d, fwd))
    if depth <= 0.1:
        return None
    ty = math.tan(math.radians(fov / 2))
    return sum(a * b for a, b in zip(d, right)) / depth / (ty * aspect), sum(a * b for a, b in zip(d, up)) / depth / ty, depth


def flash_check(shot: dict, cand: Path, fps: float, aspect: float) -> list[dict]:
    """Flash of each event by video frames: brightness in its frame for 12 tick after it versus 20 tick before."""
    qa = [json.loads(line) for line in (cand / "qa.jsonl").read_text().splitlines() if line.strip()]
    keys = shot["subject"].get("keys", [])
    if not qa or not keys:
        return []
    frames = gray_frames(cand / "video.mkv", fps, aspect)
    h, w = frames.shape[1:]
    out = []
    for k in keys:
        # an event before the start of a plan (continuation of another plan) cannot be checked with a flash in it
        if not shot["a"] <= k["t"] <= shot["b"]:
            continue
        before, after = [], []
        for f in qa:
            # video frame k - replay tick a + k·20/fps (output Flashback)
            i = round((f["t"] - shot["a"]) * fps / 20)
            if not (k["t"] - 20 <= f["t"] <= k["t"] + 12) or not 0 <= i < len(frames):
                continue
            p = project(f["cam"], f["fov"], aspect, k["pos"])
            if p is None or abs(p[0]) > 1 or abs(p[1]) > 1:
                continue
            half = max(2, int(k["size"] / (2 * p[2] * math.tan(math.radians(f["fov"] / 2)) * aspect) * w / 2))
            cx, cy = int((p[0] + 1) / 2 * w), int((1 - p[1]) / 2 * h)
            box = frames[i, max(0, cy - half):cy + half + 1, max(0, cx - half):cx + half + 1]
            if box.size:
                (after if f["t"] >= k["t"] else before).append(float(box.mean()))
        if not before or not after:
            out.append({"t": k["t"], "seen": False, "why": "not in frame"})
            continue
        rise = max(after) - float(np.median(before))
        out.append({"t": k["t"], "seen": rise >= FLASH, "rise": round(rise, 1)})
    return out


def loudnorm(inputs: list[str], graph: str, label: str, out: Path, extra: list[str]):
    """Sound for −14 LUFS and −1 dBTP in two passes (measured, then linear)."""
    probe = run(["ffmpeg", "-hide_banner", *inputs, "-filter_complex", f"{graph};[{label}]loudnorm=I=-14:TP=-1:LRA=11:print_format=json[x]",
                 "-map", "[v]", "-map", "[x]", "-f", "null", "-"], text=True).stderr
    m = json.loads(probe[probe.rindex("{"):probe.rindex("}") + 1])
    norm = (f"loudnorm=I=-14:TP=-1:LRA=11:measured_I={m['input_i']}:measured_TP={m['input_tp']}:measured_LRA={m['input_lra']}"
            f":measured_thresh={m['input_thresh']}:offset={m['target_offset']}:linear=true")
    run(["ffmpeg", "-y", "-hide_banner", *inputs, "-filter_complex", f"{graph};[{label}]{norm},aresample=48000[a]", "-map", "[v]", "-map", "[a]",
         *extra, "-c:v", "libx264", "-crf", "18", "-preset", "slow", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
         "-movflags", "+faststart", str(out)])


def assemble(videos: list[Path], out: Path, music: Path | None):
    inputs = [x for v in videos for x in ("-i", str(v))]
    n = len(videos)
    graph = "".join(f"[{i}:v][{i}:a]" for i in range(n)) + f"concat=n={n}:v=1:a=1[v][g]"
    if music is None:
        loudnorm(inputs, graph, "g", out, [])
        return
    total = sum(duration(v) for v in videos)
    # music quietly under the game sound: 14 dB lower, soft start and end
    graph += (f";[{n}:a]atrim=0:{total:.3f},volume=-14dB,afade=t=in:d=1,afade=t=out:st={max(0.0, total - 2):.3f}:d=2[m]"
              ";[g][m]amix=inputs=2:normalize=0:duration=first[mix]")
    loudnorm(inputs + ["-i", str(music)], graph, "mix", out, [])


def cap(s: str) -> str:
    """The first letter is capital, the rest is as is (“Grad”, ICBM, B-2; `str.capitalize` spoils them)."""
    i = next((j for j, ch in enumerate(s) if ch.isalpha()), len(s))
    return s[:i] + s[i:i + 1].upper() + s[i + 1:]


def caption(preview: str, cand: dict) -> tuple[str, str]:
    """EN/RU caption from candidate data (weapon, distances), without promotional wording."""
    w = cand["weapon"].split("+")
    en = " and ".join(WEAPON.get(x, (x, x))[0] for x in w)
    ru = " и ".join(WEAPON.get(x, (x, x))[1] for x in w)
    n = cand["numbers"]
    if preview == "rockets":
        hit = n.get("ground", 1) != 0
        if n.get("no_launch"):
            return ((f"{cap(en)}: flight and impacts on the ground.", f"{cap(ru)}: полёт и удары по земле.") if hit
                    else (f"{cap(en)} in flight.", f"{cap(ru)} в полёте."))
        return ((f"{cap(en)}: launch, flight and impacts on the ground.", f"{cap(ru)}: пуск, полёт и удары по земле.") if hit
                else (f"{cap(en)}: launch and flight.", f"{cap(ru)}: пуск и полёт."))
    if preview == "drones":
        if n.get("shot_down") and n.get("ground"):
            return (f"{cap(en)} over the players: one is shot down, the rest hit the streets nearby.",
                    f"{cap(ru)} над игроками: один сбивают, остальные бьют по соседним улицам.")
        if n.get("shot_down"):
            return (f"{cap(en)} over the players: one is shot down in front of them.",
                    f"{cap(ru)} над игроками: один сбивают у них на глазах.")
        if n.get("ground"):
            return (f"{cap(en)} fly over the players and hit the streets nearby.",
                    f"{cap(ru)} пролетают над игроками и бьют по соседним улицам.")
        return (f"{cap(en)} fly low over the players.", f"{cap(ru)} пролетают низко над игроками.")
    if preview == "chaos":
        k, sec = n.get("ground") or n.get("ground_in_frame"), n.get("seconds", 20)
        return (f"{k} ground strikes in {sec} seconds, one camera.", f"Ударов по земле за {sec} секунд: {k}. Одна камера.")
    if preview == "far":
        strikes = n.get("strikes") or [n]
        if not strikes[0]:
            return ("Strikes far away through binoculars: each flash comes before its sound.",
                    "Удары вдали в бинокль: каждая вспышка раньше своего звука.")
        if len(strikes) == 1:
            d = strikes[0].get("dist", 0)
            return (f"A strike {d} blocks away: the flash comes first, the sound {strikes[0].get('boom_after_s', 0)} s later.",
                    f"Удар в {d} блоках: сначала вспышка, звук — через {strikes[0].get('boom_after_s', 0)} с.")
        ds = [str(x.get("dist", 0)) for x in strikes]
        return (f"Strikes {', '.join(ds[:-1])} and {ds[-1]} blocks away: each flash comes before its sound.",
                f"Удары в {', '.join(ds[:-1])} и {ds[-1]} блоках: каждая вспышка раньше своего звука.")
    if preview == "pov":
        return pov_caption(en, ru, n)
    if preview == "fly":
        return fly_caption(en, ru, n)
    if n.get("no_launch"):
        return ("A nuclear strike: flash, shock wave and mushroom cloud.", "Ядерный удар: вспышка, ударная волна и гриб.")
    return ("A nuclear strike: launch, flash, shock wave and mushroom cloud.", "Ядерный удар: пуск, вспышка, ударная волна и гриб.")


def pov_caption(en: str, ru: str, n: dict) -> tuple[str, str]:
    """The signature of the segment through the eyes of the player is the main thing in it: detonation, shooting down, impacts, launch, flight."""
    k, sec = n.get("ground", 0), round(n.get("seconds", 0))
    if n.get("nuke"):
        return "A nuclear strike, seen through a player's eyes.", "Ядерный удар глазами игрока."
    if n.get("nuke_off"):
        # explosion not in the player's frame (in the subway, in the house): how far
        d = n["nuke_off"]
        return f"A nuclear strike {d} blocks away, through a player's eyes.", f"Ядерный удар в {d} блоках: вид глазами игрока."
    if n.get("shot_down"):
        return "A drone is shot down in front of a player, seen through their eyes.", "Дрон сбивают у игрока на глазах: вид его глазами."
    if k > 1:
        return (f"{k} strikes around a player in {sec} seconds, seen through their eyes.",
                f"Ударов вокруг игрока за {sec} секунд: {k}. Вид его глазами.")
    if k == 1:
        return "A strike in front of a player, seen through their eyes.", "Удар у игрока на глазах: вид его глазами."
    if n.get("launch"):
        return f"{cap(en)}: the launch, seen through a player's eyes.", f"{cap(ru)}: пуск глазами игрока."
    if n.get("flyby"):
        return f"{cap(en)} overhead, seen through a player's eyes.", f"{cap(ru)} над игроком: вид его глазами."
    # a moment marked by the player in which the replay data did not find anything of its kind
    return "Seen through a player's eyes.", "Вид глазами игрока."


def fly_caption(en: str, ru: str, n: dict) -> tuple[str, str]:
    """Signature of the flight of the free camera: blows in the frame and weapons of the raid."""
    k, sec = n.get("impacts", 0), round(n.get("seconds", 0))
    raid_en, raid_ru = (f"a raid of {en}", f"налёта ({ru})") if en and en != "fly" else ("an air raid", "налёта")
    if n.get("passes"):
        # passes FPV - gluing passes past impacts
        p = n["passes"]
        return ((f"FPV drone passes by {p} strikes during {raid_en}." if p > 1 else f"An FPV drone pass by a strike during {raid_en}."),
                (f"Проходы FPV-дрона мимо ударов во время {raid_ru}: {p}." if p > 1 else f"Проход FPV-дрона мимо удара во время {raid_ru}."))
    if k > 1:
        return (f"A slow camera flight during {raid_en}: {k} strikes in view in {sec} seconds.",
                f"Медленный пролёт камеры во время {raid_ru}: ударов в кадре за {sec} секунд — {k}.")
    if k == 1:
        return f"A slow camera flight during {raid_en}: a strike in view.", f"Медленный пролёт камеры во время {raid_ru}: удар в кадре."
    return f"A slow camera flight during {raid_en}.", f"Медленный пролёт камеры во время {raid_ru}."


def shown(cand: dict, kept: list[dict]) -> dict:
    """The candidate's numbers for signature are only about the plans that were included in the video (the plan through the eyes of one player can be merged from
     of several: `drones-0-flyby+shootdown`)."""
    if cand.get("preview") in ("pov", "fly"):
        # the segment through the eyes of the player and the flight - one shot, all its events; passages FPV - how many of them are included
        if cand.get("numbers", {}).get("passes"):
            return {**cand, "numbers": {**cand["numbers"], "passes": len(kept)}}
        return cand
    n = dict(cand.get("numbers", {}))
    has = lambda part: any(part in s["name"].rsplit("-", 1)[-1].split("+") for s in kept)  # noqa: E731
    if not has("shootdown"):
        n["shot_down"] = 0
    if cand.get("preview", "") in ("drones", "rockets") and not has("impact"):
        n["ground"] = 0
    if not has("launch"):
        n["no_launch"] = True
    if n.get("strikes"):
        # strikes from afar - one per plan far<i>, in order
        n["strikes"] = [x for i, x in enumerate(n["strikes"]) if has(f"far{i + 1}")]
    return {**cand, "numbers": n}


def finish(cid: str, preview: str, cand: dict, videos: list[Path], ok: bool, lines: list[str], out: Path, a) -> list[str]:
    """Clip from video plans: clean, with music, signature; report lines."""
    clean, tuned = out / f"{cid}.mp4", out / f"{cid}-music.mp4"
    assemble(videos, clean, None)
    if a.music:
        assemble(videos, tuned, Path(a.music))
    length = duration(clean)
    en, ru = caption(preview, cand)
    tail_en, tail_ru = (SOON_EN, SOON_RU) if a.soon else (TAIL_EN, TAIL_RU)
    text = f"{en} {tail_en}\n{ru} {tail_ru}\n{TAGS}\n"
    if a.music and a.credit:
        text += f"Music: {a.credit}\n"
    (out / f"{cid}.txt").write_text(text)
    # the moment noted by the player - the length as he noted
    lo, hi = (0, math.inf) if cand.get("numbers", {}).get("mark") else LENGTH.get(preview, (20, 35))
    return [f"## {cid} — {'good' if ok else 'HAS NOTES'}", "", f"File {clean.name}, {length:.1f} s"
            + ("" if lo - 1 / 30 <= length <= hi + 1 / 30 else f" (outside {lo}–{hi} s)") + f"; caption: {cid}.txt.", *lines, ""]


def clip_spec(spec: str) -> tuple[str, Path]:
    """`<clip>=<video>` for --clip: only the distant and nuke clips have captions without replay numbers."""
    name, sep, video = spec.partition("=")
    if not sep or name not in ("far", "nuke"):
        raise argparse.ArgumentTypeError(f"{spec!r}: you need far=<video> or nuke=<video>")
    return name, Path(video)


def has_audio(f: Path) -> bool:
    out = run(["ffprobe", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", str(f)],
              text=True).stdout
    return bool(out.strip())


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("film")
    p.add_argument("--out", default=None)
    p.add_argument("--music", default=None, help="track for the version with music (CC BY, etc.)")
    p.add_argument("--credit", default="", help="track attribution line for caption")
    p.add_argument("--soon", action="store_true", help="the mod has not yet been released: in the caption “soon on Modrinth and CurseForge”")
    p.add_argument("--clip", action="append", default=[], type=clip_spec,
                   help="far=<video> or nuke=<video>: non-replay video with game audio")
    a = p.parse_args()
    for name, video in a.clip:
        if not video.is_file() or not has_audio(video):
            p.error(f"--clip {name}: {video} - there is no file or sound in it")
    film = Path(a.film)
    out = Path(a.out) if a.out else film / "previews"
    out.mkdir(parents=True, exist_ok=True)
    cands = {c["id"]: c for v in json.loads((film.parent / "candidates.json").read_text()).values() for c in v} \
        if (film.parent / "candidates.json").exists() else {}
    report = ["# Preview: summary of filming and editing", ""]
    for r in json.loads((film / "runs.json").read_text()):
        plan = json.loads(Path(r["plan"]).read_text())
        shots = {s["name"]: s for s in plan["shots"]}
        best = json.loads((Path(plan["out"]) / "best.json").read_text()) if (Path(plan["out"]) / "best.json").exists() else {}
        aspect = plan["width"] / plan["height"]
        for cid in sorted(plan["previews"], key=lambda c: ORDER.index(c.split("-")[0]) if c.split("-")[0] in ORDER else 9):
            preview = cid.split("-")[0]
            videos, names, lines, ok, passed = [], [], [], True, []
            for name in plan["previews"][cid]:
                b = best.get(name)
                s = shots[name]
                cand = Path(b["dir"]) if b and "dir" in b else None
                why = (b.get("why") if b else "not in best.json") if cand is None \
                    else "filmed with another plan (wrong ticks)" if (b.get("a"), b.get("b")) != (s["a"], s["b"]) \
                    else "no video or frame check" if not (cand / "video.mkv").is_file() or not (cand / "qa.jsonl").is_file() else None
                if why:
                    lines.append(f"- {name}: not filmed — {why}")
                    ok = False
                    continue
                verdict = json.loads((cand / "verdict.json").read_text()) if (cand / "verdict.json").exists() else {}
                flashes = flash_check(s, cand, plan["fps"], aspect)
                dark = [f for f in flashes if not f["seen"]]
                ok &= bool(b.get("pass")) and not dark
                videos.append(cand / "video.mkv")
                names.append(name)
                passed.append(bool(b.get("pass")))
                lines.append(f"- {name}: camera {b.get('candidate')}, frame check {'yes' if b.get('pass') else 'NO: ' + str(b.get('why'))}; "
                             f"frames {verdict.get('frames')}, object {verdict.get('approach', 0):.0%}, events {verdict.get('keys_ok')}/{verdict.get('keys')}, "
                             f"loud in the frame {verdict.get('loud_on')}, off-screen {verdict.get('loud_off')}; "
                             f"flash visible {len(flashes) - len(dark)}/{len(flashes)}")
            if not videos:
                report += [f"## {cid}", "", *lines, ""]
                continue
            # a shot that has not passed the frame check (there is no object in the frame) does not go into the video if it contains passed
            if any(passed) and not all(passed):
                videos = [v for v, p in zip(videos, passed) if p]
                names = [n for n, p in zip(names, passed) if p]
                lines.append(f"- shots without frame checks excluded: {passed.count(False)}")
            report += finish(cid, preview, shown(cands.get(cid, {"weapon": "", "numbers": {}}), [shots[n] for n in names]), videos, ok, lines, out, a)
    for name, video in a.clip:
        report += finish(name, name, {"weapon": "", "numbers": {}}, [video], True,
                         [f"- filmed outside a replay: {video} (frame checks from its recording)"], out, a)
    (out / "report.md").write_text("\n".join(report))
    print(out / "report.md")


if __name__ == "__main__":
    sys.exit(main())
