#!/usr/bin/env python3
# /// script
# dependencies = ["pillow", "numpy"]
# ///
"""Check final media intervals against replay events, camera geometry and rendered pixels.

uv run tools/media/check.py MANIFEST.json --out REPORT.json
Input files stay private. This reads native Flashback/replayscan output; it never runs a game.
Geometric/contrast checks are necessary conditions, not a replacement for tools/media/review.md.
"""
from __future__ import annotations

import argparse
import bisect
import csv
import gzip
import hashlib
import io
import json
import math
import os
from pathlib import Path
import subprocess
from fractions import Fraction


def dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def sub(a, b):
    return [x - y for x, y in zip(a, b)]


def length(v):
    return math.sqrt(dot(v, v))


def project(point, camera, fov, width, height):
    """Minecraft yaw/pitch, vertical FOV; pixel center and forward depth."""
    yaw, pitch = map(math.radians, camera[3:5])
    forward = [-math.sin(yaw) * math.cos(pitch), -math.sin(pitch), math.cos(yaw) * math.cos(pitch)]
    right = [-math.cos(yaw), 0, -math.sin(yaw)]
    up = [-math.sin(yaw) * math.sin(pitch), math.cos(pitch), math.cos(yaw) * math.sin(pitch)]
    v = sub(point, camera[:3])
    depth = dot(v, forward)
    if depth <= 0:
        return None, None, depth
    focal = height / (2 * math.tan(math.radians(fov) / 2))
    return width / 2 + focal * dot(v, right) / depth, height / 2 - focal * dot(v, up) / depth, depth


def longest_run(values):
    best = count = 0
    for value in values:
        count = count + 1 if value else 0
        best = max(best, count)
    return best


def projected_box(center, radius, bottom, top, camera, fov, width, height):
    """Conservative cap bounds: the near edge can project above its top on the central axis."""
    points = [project([x, y, z], camera, fov, width, height)
              for x in (center[0]-radius, center[0]+radius)
              for y in (bottom, top)
              for z in (center[2]-radius, center[2]+radius)]
    if any(p[2] <= 0 for p in points):
        return None
    return (min(p[0] for p in points), min(p[1] for p in points),
            max(p[0] for p in points), max(p[1] for p in points))


def timing_ok(seconds, fps):
    """An unchanged frame may last longer, but a video must retain the native export clock."""
    return bool(seconds) and all(abs((b-a)-1/fps) <= .002 for a, b in zip(seconds, seconds[1:]))


def native_boundary(milliseconds, fps, precision_ms=2):
    """An animation may combine identical native frames, but cannot invent an intermediate boundary."""
    frame = milliseconds * fps / 1000
    if milliseconds <= 0 or abs(frame-round(frame)) * 1000 / fps > precision_ms:
        raise ValueError("Animation timing is not aligned with the native export frames")
    return round(frame)


def phase_ok(phase, age, pulse, rollup, wave):
    # Deliberately disjoint meanings: an orange ring at +16 s cannot be a flash.
    return {
        "flash": 0 <= age <= pulse,
        "rising-cap": rollup <= age <= 120,
        "wave": abs(age - wave) <= 2,
    }.get(phase, False)


def proof_matches(proof, tick, source_hash):
    """Proof records the paused replay state and binds it to this particular ordinary F2 file."""
    return (proof.get("paused") is True and proof.get("tick") == tick
            and proof.get("f2_sha256") == source_hash and bool(proof.get("state_source")))


def nuclear_checks(ticks, event, cameras, frames, phases, gui, complete=True):
    failures = []
    ages = [(t - event["tick"]) / 20 for t in ticks]
    d = event["detonation"]
    if d["yieldKt"] != 5 or d["scale"] != 1:
        failures.append("Expected the recorded 5 kt strike at effects scale 1")
    if not 13000 <= d["dayTime"] % 24000 <= 23000:
        failures.append("The recorded detonation is not at night")
    for row in cameras:
        distance = math.hypot(row["cam"][0] - d["burst"][0], row["cam"][2] - d["burst"][2])
        if not 1600 <= distance <= 1700:
            failures.append("Observer leaves the required 1.6–1.7 km horizontal range")
            break
    if not gui:
        failures.append("F1/hideGui suppresses Incoming's screen flash")
    if complete and (ages[0] > -0.5 or ages[-1] < 180):
        failures.append("Continuous sequence must include pre-flash and at least 180 s after detonation")
    pulse = phases["pulse_s"]
    flash = [f for a, f in zip(ages, frames) if 0 <= a <= pulse]
    before = [f["mean"] for a, f in zip(ages, frames) if a < 0]
    if not flash or not before or not any(f["bright_fraction"] >= 0.5 and f["mean"] >= max(100, 3 * sum(before) / len(before)) for f in flash):
        failures.append("No visible frame-wide flash in the recorded pulse interval")
    rise = [f for a, f in zip(ages, frames) if phases["rollup_s"] <= a <= 120]
    if not rise or sum(f.get("cap_visible", False) for f in rise) < 0.9 * len(rise):
        failures.append("Rising cap is cropped, obscured or has insufficient measured contrast")
    tops = [f["cap_world_y"] for f in rise if f.get("cap_visible", False)]
    if not tops or max(tops) - min(tops) < 500:
        failures.append("Visible cap does not cover at least 500 m of the model's rise")
    cloud_sequence = [f for a, f in zip(ages, frames) if a >= phases["rollup_s"]]
    if not cloud_sequence or not all(f.get("ground_in_frame", False) for f in cloud_sequence):
        failures.append("Cloud sequence loses the source ground or crops its base")
    developed = [f for a, f in zip(ages, frames) if a >= 120]
    if complete and (not developed or sum(f.get("cap_visible", False) for f in developed) < .9 * len(developed)):
        failures.append("Developed cloud is cropped, obscured or has insufficient measured contrast")
    wave = [f for a, f in zip(ages, frames) if abs(a - phases["wave_s"]) <= 1]
    if not wave or not all(f.get("ground_in_frame", False) for f in wave):
        failures.append("Wave-arrival interval or the ground ahead of the observer is missing")
    return failures


def missile_checks(samples, fps, impact_tick):
    """Every sample belongs to the same actual projectile UUID, at one final output frame."""
    failures = []
    before = [s for s in samples if s["tick"] < impact_tick]
    behind = any(s["depth"] < -2 for s in before)
    if not behind:
        failures.append("Projectile never passes from behind the camera")
    good = [s["tick"] < impact_tick and s["known"] and s["visible"] and s["depth"] > 0
            and s["body_px"] >= 18 and s["width_px"] >= 3 and s["contrast"] >= 10 for s in samples]
    required = math.ceil(fps)  # at least one continuous second, never zero or one lucky frame
    # A native tracking camera can move with the missile. Relative camera depth then stays constant,
    # although the missile travels away from the launch side. Measure world travel across each readable
    # interval; adjacent packet samples can repeat and are not independent velocity measurements.
    count = run = 0
    for i, readable in enumerate(good):
        run = run + 1 if readable else 0
        if run < 2:
            continue
        first, last = samples[i-run+1], samples[i]
        travel = sub(last["position"], first["position"])
        if dot(travel, first["forward"]) > 5 and first["target_distance"] - last["target_distance"] > 5:
            count = max(count, run)
    if count < required:
        failures.append(f"Only {count} consecutive readable approach frames; need {required}")
    if not samples or not samples[0]["tick"] < impact_tick < samples[-1]["tick"]:
        failures.append("Final interval must retain both the approach and impact")
    return failures, count


def table(path):
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rt") as stream:
        return list(csv.DictReader(stream))


def model_rows(jar, event, rows):
    d = event["detonation"]
    args = [d["yieldKt"], (d["burst"][1]-d["groundY"])/d["scale"], str(d["surface"]).lower(),
            d["scale"], d["wave"], d["windSpeed"], d["windDir"], d["burst"][0], d["groundY"], d["burst"][2], event["tick"]]
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
    inputs = "\n".join(f"{r['t']} {math.hypot(r['cam'][0]-d['burst'][0], r['cam'][2]-d['burst'][2])}" for r in rows)
    output = subprocess.check_output([java, "-cp", str(jar), str(Path(__file__).with_name("PhaseProbe.java")), *map(str, args)], input=inputs, text=True)
    return list(csv.DictReader(io.StringIO(output)))


def trajectory(directory, uuid):
    """Use game-decoded entity samples, else distant packets. Never interpolate an unobserved long gap."""
    ids = {r["eid"] for r in table(directory / "entities.csv.gz") if r["uuid"] == uuid}
    data = {}
    for r in table(directory / "far.csv.gz"):
        if r["uuid"] == uuid:
            p = json.loads(r["data"])
            # The same table also contains the designator's HUD summaries. Their positions may be stale
            # and they carry no orientation; only an actual flight pose can support body projection.
            if "yaw" not in p or "pitch" not in p:
                continue
            data[int(r["t"])] = [*[float(r[k]) for k in ("x", "y", "z")], float(p["yaw"]), float(p["pitch"])]
    for r in table(directory / "tracks.csv.gz"):
        if r["eid"] in ids:
            data[int(r["t"])] = [float(r[k]) for k in ("x", "y", "z", "yaw", "pitch")]
    ticks = sorted(data)

    def sample(tick):
        at = bisect.bisect_left(ticks, tick)
        if at < len(ticks) and ticks[at] == tick:
            return data[ticks[at]]
        if at == 0 or at == len(ticks) or ticks[at] - ticks[at-1] > 5:
            return None
        a, b = ticks[at-1], ticks[at]
        fraction = (tick-a)/(b-a)
        first, last = data[a], data[b]
        angle = (last[3]-first[3]+180) % 360 - 180
        return [first[i]+(last[i]-first[i])*fraction for i in range(3)] + [first[3]+angle*fraction, first[4]+(last[4]-first[4])*fraction]
    return sample


def projectile_sample(pose, qa, target, width, height):
    if pose is None:
        return dict(tick=qa["t"], depth=0, target_distance=0, known=False, visible=False, body_px=0, width_px=0, contrast=0, x_px=0, y_px=0)
    x, y, depth = project(pose[:3], qa["cam"], qa["fov"], width, height)
    yaw, pitch = map(math.radians, pose[3:5])
    axis = [-math.sin(yaw)*math.cos(pitch), -math.sin(pitch), math.cos(yaw)*math.cos(pitch)]
    # 3.10.0 cruise-missile dimensions (WeaponSpec.Article): body 5.56 m, diameter .52 m.
    ends = [project([pose[i]+sign*2.78*axis[i] for i in range(3)], qa["cam"], qa["fov"], width, height) for sign in (-1, 1)]
    body = math.hypot(ends[0][0]-ends[1][0], ends[0][1]-ends[1][1]) if all(e[0] is not None for e in ends) else 0
    diameter = .52 * height / (2 * math.tan(math.radians(qa["fov"])/2) * depth) if depth > 0 else 0
    # Contrast uses the actual render camera's subject projection, not the packet interpolation above.
    camera_yaw, camera_pitch = map(math.radians, qa["cam"][3:5])
    forward = [-math.sin(camera_yaw)*math.cos(camera_pitch), -math.sin(camera_pitch),
               math.cos(camera_yaw)*math.cos(camera_pitch)]
    return dict(tick=qa["t"], depth=depth, target_distance=length(sub(pose[:3], target)), known=True,
                position=pose[:3], forward=forward,
                visible=False, body_px=body, width_px=diameter, contrast=0,
                axis_px=[ends[1][j]-ends[0][j] for j in range(2)] if body > 0 else [0, 0],
                x_px=(qa["x"]+1)*width/2, y_px=(1-qa["y"])*height/2)


def pixel_stats(video, width, fps):
    """Decode every final frame. Pixel diagnostics are taken from the delivered encoding."""
    import numpy as np
    from PIL import Image
    if video.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".gif"}:
        with Image.open(video) as image:
            if width > image.width:
                raise ValueError("Do not upscale media to pass the projected-size check")
            elapsed = 0
            for frame in range(getattr(image, "n_frames", 1)):
                image.seek(frame)
                pixels = np.asarray(image.convert("RGB").resize((width, round(width * image.height / image.width)))).copy()
                duration = image.info.get("duration", 1000/fps)
                # WebP can combine identical frames. Restore their displayed duration on the native frame clock.
                end = elapsed + duration
                boundary = native_boundary(end, fps, 5.01 if image.format == "GIF" else 2)
                for _ in range(boundary-round(elapsed*fps/1000)):
                    yield pixels
                elapsed = end
        return
    probe = json.loads(subprocess.check_output(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                                               "stream=width,height,r_frame_rate:frame=best_effort_timestamp_time", "-of", "json", str(video)]))
    stream = probe["streams"][0]
    seconds = [float(f["best_effort_timestamp_time"]) for f in probe["frames"]]
    if abs(float(Fraction(stream["r_frame_rate"]))-fps) > .01 or not timing_ok(seconds, fps):
        raise ValueError("Final video clock differs from the native 30–60 fps mapping")
    if width > stream["width"]:
        raise ValueError("Do not upscale media to pass the projected-size check")
    height = round(width * stream["height"] / stream["width"])
    process = subprocess.Popen(["ffmpeg", "-v", "error", "-threads", "2", "-i", str(video), "-vf", f"scale={width}:{height}", "-threads", "2", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"], stdout=subprocess.PIPE)
    try:
        while raw := process.stdout.read(width * height * 3):
            if len(raw) != width * height * 3:
                raise ValueError("Truncated decoded frame")
            yield np.frombuffer(raw, dtype=np.uint8).reshape(height, width, 3)
        if process.wait() != 0:
            raise ValueError("Video decoder failed")
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
        process.wait()


def patch_contrast(image, x, y, radius):
    """Local RGB contrast, not an assertion that the subject is recognizable."""
    import numpy as np
    h, w = image.shape[:2]
    r = max(2, math.ceil(radius))
    x, y = round(x), round(y)
    if x - 2 * r < 0 or y - 2 * r < 0 or x + 2 * r >= w or y + 2 * r >= h:
        return 0.0
    center = image[y-r:y+r+1, x-r:x+r+1].astype(float)
    outer = image[y-2*r:y+2*r+1, x-2*r:x+2*r+1].astype(float)
    return float(np.linalg.norm(center.mean(axis=(0, 1)) - outer.mean(axis=(0, 1))))


def body_contrast(image, sample):
    """Median contrast along the actual projected body against its two immediate sides.

    A square patch averages the narrow missile together with the buildings behind it; opposite background
    colours can cancel even when its outline is readable. Use the recorded axis and native subject center.
    This remains a diagnostic, not object recognition or a substitute for looking at the final image.
    """
    import statistics
    dx, dy = sample.get("axis_px", [0, 0])
    size = math.hypot(dx, dy)
    if size < 1:
        return 0.0
    side = max(3, sample["width_px"])
    px, py = -dy / size * side, dx / size * side
    height, width = image.shape[:2]
    contrasts = []
    for i in range(9):
        fraction = (i-4) * .075  # Central 60% avoids nose/tail taper.
        x, y = sample["x_px"] + dx*fraction, sample["y_px"] + dy*fraction
        points = [(round(x+sign*px), round(y+sign*py)) for sign in (0, -1, 1)]
        if any(not (1 <= x < width-1 and 1 <= y < height-1) for x, y in points):
            continue
        colours = [[sum(float(image[y+oy, x+ox][c]) for oy in (-1, 0, 1) for ox in (-1, 0, 1))/9
                    for c in range(3)] for x, y in points]
        contrasts.append(max(length(sub(colours[0], background)) for background in colours[1:]))
    return statistics.median(contrasts) if len(contrasts) >= 5 else 0.0


def audit(manifest, base):
    """Join explicit native frame numbers, game events and measurements; fail on missing provenance."""
    reports = []
    for asset in manifest["assets"]:
        failures = []
        media = (base / asset["file"]).resolve()
        qa = [json.loads(line) for line in (base / asset["qa"]).read_text().splitlines()]
        first, count = asset["first_frame"], asset["frame_count"]
        rows = qa[first:first+count]
        if count < 1 or len(rows) != count:
            raise ValueError(f"{asset['file']}: missing frame/tick mapping")
        ticks = [r["t"] for r in rows]
        for still in asset.get("stills", []):
            if "export_frame" in still:
                index = still["export_frame"]
                if not isinstance(index, int) or not first <= index < first+count:
                    raise ValueError(f"{still['file']}: selected native frame is outside this export interval")
                still["tick"] = qa[index]["t"]
        fps = asset["fps"]
        if not 30 <= fps <= 60 or any(abs((b-a) - 20/fps) > .005 for a, b in zip(ticks, ticks[1:])):
            failures.append("Native frame times are missing, discontinuous or outside 30–60 fps")
        if any(not r["ready"] or r["inside"] or r["low_out"] for r in rows):
            failures.append("Unbuilt terrain, camera in blocks or unknown foreground")
        events = table(base / asset["events"])
        frames = []
        samples, shapes = [], []
        event_key = None
        plan = json.loads((base / asset["plan"]).read_text())
        shot = next(s for s in plan["shots"] if s["name"] == asset["shot"])
        if asset["kind"] == "nuclear-sequence":
            event_rows = [r for r in events if r["src"] == "incoming:nuke_detonation" and int(r["t"]) == asset["event_tick"]]
            if len(event_rows) != 1:
                raise ValueError("Expected one recorded nuclear event")
            event = {"tick": int(event_rows[0]["t"]), **json.loads(event_rows[0]["data"])}
            event_key = hashlib.sha256(json.dumps(event, sort_keys=True).encode()).hexdigest()
            jar = base / asset["jar"]
            if hashlib.sha256(jar.read_bytes()).hexdigest() != asset["jar_sha256"]:
                raise ValueError("Shipped model jar hash does not match provenance")
            shapes = model_rows(jar, event, rows)
            phases = {k: float(shapes[0][k]) for k in ("pulse_s", "rollup_s", "wave_s")}
        elif asset["kind"] == "factory-approach":
            event_rows = [r for r in events if r["src"] == "incoming:blast" and json.loads(r["data"]).get("projectile") == asset["uuid"]]
            if len(event_rows) != 1 or shot["subject"].get("uuid") != asset["uuid"]:
                raise ValueError("Expected one impact and native QA tracking that same projectile UUID")
            impact = json.loads(event_rows[0]["data"])
            if impact["weapon"] != 1:
                raise ValueError("Missile geometry is for the 3.10.0 cruise missile")
            sample = trajectory(base / asset["scan"], asset["uuid"])
        output_width = asset["width"]
        still_matches = {}
        for index, image in enumerate(pixel_stats(media, output_width, fps)):
            if index >= count:
                raise ValueError(f"{media}: more decoded frames than mapped frames")
            import numpy as np
            mean = float(image.mean())
            f = {"frame": index, "tick": ticks[index], "mean": mean,
                 "bright_fraction": float(np.mean(np.min(image, axis=2) >= 180))}
            for still in asset.get("stills", []):
                if still.get("tick") is not None and abs(still["tick"]-ticks[index]) <= 10/fps + .001:
                    from PIL import Image
                    source = np.asarray(Image.open(base / still.get("source_f2", still["file"])).convert("RGB").resize((image.shape[1], image.shape[0]))).astype(float)
                    error = float(np.abs(source-image.astype(float)).mean())
                    still_matches[still["file"]] = min(still_matches.get(still["file"], math.inf), error)
            if shapes:
                s = shapes[index]
                if abs(float(s["tick"]) - ticks[index]) > .005:
                    raise ValueError("Model and native frame ticks disagree")
                cam = rows[index]["cam"]
                center = [float(s[k]) for k in ("x", "y", "z")]
                x, y, depth = project(center, cam, rows[index]["fov"], output_width, image.shape[0])
                bounds = projected_box(center, float(s["radius"]), float(s["bottom_y"]), float(s["top_y"]),
                                       cam, rows[index]["fov"], output_width, image.shape[0])
                radius = float(s["radius"]) * image.shape[0] / (2 * math.tan(math.radians(rows[index]["fov"]) / 2) * max(depth, .01))
                fits = bounds is not None and 0 < bounds[0] < bounds[2] < output_width and 0 < bounds[1] < bounds[3] < image.shape[0]
                # Compare the cap-sized patch with its surroundings, not two patches inside the same cloud.
                contrast = patch_contrast(image, x, y, radius) if depth > 0 else 0
                f.update(cap_visible=bool(fits and radius >= 6 and contrast >= 10), cap_world_y=float(s["top_y"]),
                         cap_bounds=bounds, cap_contrast=contrast)
                ground = project([float(s["ground_x"]), float(s["ground_y"]), float(s["ground_z"])], cam, rows[index]["fov"], output_width, image.shape[0])
                # Leave foreground below the source instead of accepting a base exactly on the bottom edge.
                f["ground_in_frame"] = ground[0] is not None and 0 < ground[0] < output_width and 0 < ground[1] < .95 * image.shape[0]
            if asset["kind"] == "factory-approach":
                s = projectile_sample(sample(ticks[index]), rows[index], impact["pos"], output_width, image.shape[0])
                s["contrast"] = body_contrast(image, s) if s["depth"] > 0 else 0
                s["visible"] = bool(rows[index]["in"] and rows[index]["seen"] >= .9 and rows[index]["clear"] >= .8 and rows[index]["drawn"] and rows[index]["unknown"] < 0 and not rows[index]["fog"] and not rows[index]["cloud"])
                samples.append(s)
                f["projectile"] = s
            frames.append(f)
        if len(frames) != count:
            raise ValueError(f"{media}: fewer decoded frames than mapped frames")
        if asset["kind"] == "nuclear-sequence":
            full = asset.get("full_sequence")
            pulse_rows = [r for r in rows if 0 <= (r["t"]-event["tick"])/20 <= phases["pulse_s"]]
            failures += nuclear_checks(ticks, event, rows, frames, phases,
                                       bool(pulse_rows) and all(r.get("gui", False) for r in pulse_rows), complete=full is None)
            if full is not None:
                parents = [r for r in reports if r["file"] == full and r.get("complete") and r.get("event_key") == event_key]
                if len(parents) != 1 or not parents[0]["pass"]:
                    failures.append("Page excerpt needs an earlier passing full sequence of the same recorded event")
        elif asset["kind"] == "factory-approach":
            more, run = missile_checks(samples, fps, int(event_rows[0]["t"]))
            failures += more
        else:
            raise ValueError("Unknown content kind")
        for still in asset.get("stills", []):
            tick = still.get("tick")
            if "export_frame" in still:
                from PIL import Image
                with Image.open(base / still["file"]) as delivered:
                    if delivered.size != (1920, 1080):
                        failures.append(f"{still['file']}: retain the full 1920x1080 exported frame")
                if still_matches.get(still["file"], math.inf) > 10:
                    failures.append(f"{still['file']}: image does not match its stated native export frame")
                selected = frames[still["export_frame"]-first]
                if asset["kind"] == "nuclear-sequence":
                    if not phase_ok(still["phase"], (tick-asset["event_tick"])/20, phases["pulse_s"], phases["rollup_s"], phases["wave_s"]):
                        failures.append(f"{still['file']}: selected native frame is outside captioned phase")
                    if still["phase"] == "flash" and selected["bright_fraction"] < .5:
                        failures.append(f"{still['file']}: selected frame does not show a frame-wide flash")
                    if still["phase"] == "rising-cap" and not selected.get("cap_visible"):
                        failures.append(f"{still['file']}: selected frame does not contain the visible cap")
                continue
            proof = still.get("tick_proof")
            if tick is None or not proof or not (base / proof).is_file():
                failures.append(f"{still['file']}: exact paused replay tick is not evidenced")
                continue
            if not (base / still["source_f2"]).is_file():
                failures.append(f"{still['file']}: missing original F2")
                continue
            captured = json.loads((base / proof).read_text())
            if not proof_matches(captured, tick, hashlib.sha256((base / still["source_f2"]).read_bytes()).hexdigest()):
                failures.append(f"{still['file']}: source tick/hash is not confirmed by the paused-state proof")
            if not captured.get("state_source") or not (base / captured["state_source"]).is_file():
                failures.append(f"{still['file']}: raw paused-state evidence is missing")
            if still_matches.get(still["file"], math.inf) > 10:
                failures.append(f"{still['file']}: F2 does not match the claimed native frame (mean RGB error >10/255)")
            from PIL import Image
            import numpy as np
            with Image.open(base / still["source_f2"]) as original, Image.open(base / still["file"]) as delivered:
                if delivered.size != original.size or original.size != (1920, 1080):
                    failures.append(f"{still['file']}: retain the full 1920x1080 F2 frame")
                else:
                    error = float(np.abs(np.asarray(original.convert("RGB"), dtype=float)-np.asarray(delivered.convert("RGB"), dtype=float)).mean())
                    if error > 10:
                        failures.append(f"{still['file']}: delivered image differs from the original F2 (mean RGB error >10/255)")
            if asset["kind"] == "nuclear-sequence" and not phase_ok(still["phase"], (tick-asset["event_tick"])/20, phases["pulse_s"], phases["rollup_s"], phases["wave_s"]):
                failures.append(f"{still['file']}: selected tick is outside captioned phase")
        reports.append({"file": asset["file"], "sha256": hashlib.sha256(media.read_bytes()).hexdigest(), "pass": not failures,
                        "failures": failures, "first_tick": ticks[0], "last_tick": ticks[-1], "frames": frames,
                        "complete": asset["kind"] == "nuclear-sequence" and asset.get("full_sequence") is None,
                        "event_tick": asset.get("event_tick"), "event_key": event_key})
    return {"pass": bool(reports) and all(r["pass"] for r in reports), "assets": reports,
            "limit": "Content prerequisites only. Independent visual review at page size is still required."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = audit(json.loads(args.manifest.read_text()), args.manifest.resolve().parent)
    except (OSError, ValueError, KeyError, StopIteration, subprocess.SubprocessError) as error:
        result = {"pass": False, "assets": [], "error": f"Incomplete or invalid capture evidence: {error}"}
    args.out.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({"pass": result["pass"], "error": result.get("error"),
                      "failures": {r["file"]: r["failures"] for r in result["assets"]}}, indent=2))
    raise SystemExit(0 if result["pass"] else 1)


if __name__ == "__main__":
    main()
