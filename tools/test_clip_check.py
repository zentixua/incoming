"""clip_check.py on synthetic takes whose answer is known. Run: python3 -m unittest tools/test_clip_check.py"""
import os
import random
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import clip_check  # noqa: E402

W, H, DW, DH = clip_check.W, clip_check.H, clip_check.DW, clip_check.DH


def picture(k, w, h):
    """a lit landscape whose bright bar moves 3 px a frame: every frame differs from the one before"""
    rows = []
    for y in range(h):
        base = 60 + 120 * y // h
        rows.append(bytes(min(255, base + (90 if (x - 3 * k * w // W) % w < w // 8 else 0)) for x in range(w)))
    return b"".join(rows)


def take(n, black=(), torn=(), repeat=()):
    frames, src = [], 0
    for k in range(n):
        src = src if k in repeat else k
        full, small = picture(src, W, H), picture(src, DW, DH)
        if k in black:
            full, small = bytes(W * H), bytes(DW * DH)
        if k in torn:
            full = full[:W * 40] + bytes(W * 30) + full[W * 70:]
        frames.append((full, small))
    return frames


class ClassifyTest(unittest.TestCase):
    def test_clean_take_passes(self):
        f = clip_check.classify(take(30))
        self.assertEqual(0, sum(x["black"] or x["torn"] or x["repeat"] for x in f))

    def test_recorder_faults_are_named(self):
        # a freeze longer than one game tick at 60 fps (3 frames)
        f = clip_check.classify(take(45, black={10}, torn={20}, repeat={30, 31, 32, 33}))
        self.assertEqual([10], [x["i"] for x in f if x["black"]])
        self.assertEqual([20], [x["i"] for x in f if x["torn"]])
        self.assertEqual([30, 31, 32, 33], [x["i"] for x in f if x["repeat"]])
        self.assertEqual(4, clip_check.longest(x["repeat"] for x in f))

    def test_one_tick_of_unchanged_frames_is_the_game_rhythm(self):
        f = clip_check.classify(take(45, repeat={30, 31}))
        self.assertEqual(0, sum(x["repeat"] for x in f))

    def test_dark_scene_is_not_black(self):
        # a night take: every frame dark, none of them a dropout
        dark = [(bytes([8 + k % 3]) * (W * H), bytes([8 + k % 3]) * (DW * DH)) for k in range(20)]
        f = clip_check.classify(dark)
        self.assertEqual(0, sum(x["black"] for x in f))


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "needs ffmpeg")
class VideoTest(unittest.TestCase):
    def encode(self, frames, d, name, fps=30):
        path = os.path.join(d, name)
        p = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{W}x{H}", "-r", str(fps),
                              "-i", "-", "-c:v", "libx264", "-qp", "0", "-pix_fmt", "yuv420p", path], stdin=subprocess.PIPE)
        for full, _ in frames:
            p.stdin.write(full)
        p.stdin.close()
        self.assertEqual(0, p.wait())
        return path

    def test_whole_clip(self):
        with tempfile.TemporaryDirectory() as d:
            good = self.encode(take(30), d, "good.mp4")
            report, _ = clip_check.check(good, fps=30, size=f"{W}x{H}")
            self.assertEqual([], report["fails"], report)
            bad = self.encode(take(30, black={12}, repeat={20, 21, 22}), d, "bad.mp4")
            report, _ = clip_check.check(bad, fps=30, audio=True)
            self.assertEqual(1, report["black"], report)
            self.assertEqual(3, report["repeat"], report)
            self.assertIn("no audio stream", report["fails"])


# Review probes (rc-video review 1): each reproduced a wrong verdict of the first version.

def encode_file(frames, path, fps=60, extra=()):
    """grey W×H frames → a real file through libx264 (default settings: B-frames, CRF 23), like an ordinary export"""
    p = subprocess.Popen(["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "gray", "-s", f"{W}x{H}", "-r", str(fps),
                          "-i", "-", *extra, "-pix_fmt", "yuv420p", path], stdin=subprocess.PIPE)
    for f in frames:
        p.stdin.write(f)
    p.stdin.close()
    assert p.wait() == 0
    return path


def moving(n, start=0):
    return [picture(start + k, W, H) for k in range(n)]


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "needs ffmpeg")
class StaticSceneTest(unittest.TestCase):
    """S1: a fixed camera on a still scene (the page-media rule: fixed camera; the seconds before a launch or a flash)."""

    def test_still_landscape_before_the_event_is_not_a_recorder_repeat(self):
        still = [picture(0, W, H)] * 60          # one second of a still landscape at 60 fps, then motion
        with tempfile.TemporaryDirectory() as d:
            path = encode_file(still + moving(60, 1), os.path.join(d, "fixed_camera.mp4"))
            report, _ = clip_check.check(path, fps=60)
        self.assertEqual([], report["fails"], report)

    def test_slow_drift_is_not_a_repeat(self):
        # vanilla clouds / a slowly rising cloud: one column of 160x90 changes by 2 levels a frame (mean diff 0.022)
        base = bytearray(picture(0, W, H))
        frames = []
        for k in range(60):
            f = bytearray(base)
            for y in range(H):
                f[y * W + (k % W)] = min(255, f[y * W + (k % W)] + 40)
            frames.append(bytes(f))
        with tempfile.TemporaryDirectory() as d:
            report, _ = clip_check.check(encode_file(frames, os.path.join(d, "drift.mp4")), fps=60)
        self.assertEqual(0, report["repeat"], report)


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "needs ffmpeg")
class FrameClockTest(unittest.TestCase):
    """S2: 60 fps in Matroska/WebM (1 ms time base: steps of 16 and 17 ms)."""

    def test_60fps_mkv_is_60fps(self):
        with tempfile.TemporaryDirectory() as d:
            path = encode_file(moving(120), os.path.join(d, "take.mkv"))
            report, _ = clip_check.check(path, fps=60)
        self.assertEqual([], report["fails"], report)

    def test_60fps_webm_is_60fps(self):
        with tempfile.TemporaryDirectory() as d:
            path = encode_file(moving(120), os.path.join(d, "take.webm"), extra=("-c:v", "libvpx-vp9", "-b:v", "1M"))
            report, _ = clip_check.check(path, fps=60)
        self.assertEqual([], report["fails"], report)

    def test_uneven_steps_are_not_a_constant_clock(self):
        # 30 frames at 1/120 s among 1/60 s, plus one repeated timestamp: no step exceeds 1.5 x the median, so it passes
        with tempfile.TemporaryDirectory() as d:
            src = encode_file(moving(120), os.path.join(d, "src.mkv"))
            vfr = os.path.join(d, "vfr.mp4")
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", src, "-vf",
                            "setpts='if(lt(N,30),N/60,if(lt(N,60),0.5+(N-30)/120,0.75+(N-60)/60))/TB'",
                            "-fps_mode", "passthrough", "-c:v", "libx264", vfr], check=True)
            pts = clip_check.probe(vfr)[1]
            steps = [round(b - a, 4) for a, b in zip(pts, pts[1:])]
            self.assertIn(0.0, steps)                       # the file really has a zero step
            report, _ = clip_check.check(vfr, fps=60)
        self.assertTrue(any("clock" in f for f in report["fails"]), report)


class NightFlashTest(unittest.TestCase):
    """S3: a night take, then the flash of a nuke: the last dark frame before it is called black."""

    def test_dark_frame_before_a_flash_is_not_a_dropout(self):
        dark = [bytes([17 + (k % 3)]) * (W * H) for k in range(30)]       # vanilla night, HUD off: every pixel ≤ 24
        flash = [bytes([200 + (k % 3)]) * (W * H) for k in range(30)]     # the flash: whole screen white from one frame
        f = clip_check.classify([(a, a[:DW * DH]) for a in dark + flash])
        self.assertEqual([], [x["i"] for x in f if x["black"]])


class LetterboxTest(unittest.TestCase):
    """S4: a letterboxed (cinema bars) clip: every frame has whole black rows."""

    def test_letterbox_bars_are_not_tears(self):
        frames = []
        for k in range(30):
            p = bytearray(picture(k, W, H))
            p[:W * 20] = bytes([16]) * (W * 20)
            p[-W * 20:] = bytes([16]) * (W * 20)
            frames.append((bytes(p), picture(k, DW, DH)))
        f = clip_check.classify(frames)
        self.assertEqual(0, sum(x["torn"] for x in f))


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "needs ffmpeg")
class TruncatedTest(unittest.TestCase):
    """S5: an export cut short (disk full, killed encoder) still passes: frames are not compared with the container."""

    def test_truncated_mkv_fails(self):
        with tempfile.TemporaryDirectory() as d:
            path = encode_file(moving(240), os.path.join(d, "full.mkv"))
            data = open(path, "rb").read()
            cut = os.path.join(d, "cut.mkv")
            open(cut, "wb").write(data[: len(data) // 3])
            report, _ = clip_check.check(cut)   # no --fps: S2 would mask it
        self.assertNotEqual([], report["fails"], report)

    def test_unreadable_file_is_a_report_not_a_traceback(self):
        with tempfile.TemporaryDirectory() as d:
            empty = os.path.join(d, "empty.mp4")
            open(empty, "wb").close()
            try:
                report, _ = clip_check.check(empty)
            except subprocess.CalledProcessError as e:
                self.fail(f"traceback instead of a failing report: {e}")
            self.assertNotEqual([], report["fails"])


# Review probes (rc-video review 2).

def pair(full):
    return full, b"".join(full[y * W:(y + 1) * W:2] for y in range(0, H, 2))


class BlackRunTest(unittest.TestCase):
    """A dropout of 6 or more black frames (100 ms at 60 fps) inside a lit, moving scene is a broken capture."""

    def test_black_run_of_six_is_named(self):
        frames = [pair(picture(k, W, H)) for k in range(60)]
        for k in range(20, 26):
            frames[k] = (bytes(W * H), bytes(DW * DH))
        f = clip_check.classify(frames)
        self.assertNotEqual([], [x["i"] for x in f if x["black"]], "a 6-frame black dropout passes")

    def test_black_run_of_twenty_is_named(self):
        frames = [pair(picture(k, W, H)) for k in range(80)]
        for k in range(30, 50):
            frames[k] = (bytes(W * H), bytes(DW * DH))
        f = clip_check.classify(frames)
        self.assertNotEqual([], [x["i"] for x in f if x["black"]], "a 20-frame black dropout passes")


class TickSceneTest(unittest.TestCase):
    """A 60 fps export of a 20 tps game with a fixed camera: what changes only per tick changes on every third frame
    (block texture animation, block changes, fire). An explosion that starts on a tick is a large change after two
    unchanged frames: a correct export, not a frozen recorder."""

    def test_explosion_after_tick_only_motion_is_not_frozen(self):
        rnd = random.Random(1)
        base = bytearray(picture(0, W, H))
        frames, cur = [], bytearray(base)
        for k in range(120):
            if k % 3 == 0:                                   # a tick: animated water over the lower third
                cur = bytearray(cur)
                for y in range(2 * H // 3, H):
                    for x in range(0, W, 2):
                        cur[y * W + x] = 60 + rnd.randrange(12)
            if k >= 90 and k % 3 == 0:                       # the blast appears on a tick and grows tick by tick
                r = 10 + 12 * (k - 90) // 3
                for y in range(max(0, 60 - r), min(H, 60 + r)):
                    for x in range(max(0, 160 - r), min(W, 160 + r)):
                        cur[y * W + x] = 250
            frames.append(pair(bytes(cur)))
        f = clip_check.classify(frames)
        self.assertEqual([], [x["i"] for x in f if x["repeat"]])


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "needs ffmpeg")
class DamagedFileTest(unittest.TestCase):
    """A damaged file whose decode writes more than a pipe buffer (64 KiB) of errors must give a report, not hang:
    decode() reads stdout to the end before it reads stderr. A real 60 s 640x360 MKV with scattered byte damage makes
    `ffmpeg -v error` write 94 KB of errors; clip_check.py on it did not finish in 120 s (its clean twin: seconds).
    Deterministic here: an ffmpeg wrapper that writes 128 KiB to stderr first, then runs the real ffmpeg."""

    def test_large_stderr_does_not_hang(self):
        with tempfile.TemporaryDirectory() as d:
            clip = encode_file(moving(30), os.path.join(d, "clip.mkv"))
            bindir = os.path.join(d, "bin")
            os.mkdir(bindir)
            fake = os.path.join(bindir, "ffmpeg")
            with open(fake, "w") as f:
                f.write("#!/bin/bash\nyes '[h264] error while decoding' | head -c 131072 >&2\nexec %s \"$@\"\n" % shutil.which("ffmpeg"))
            os.chmod(fake, 0o755)
            env = dict(os.environ, PATH=bindir + os.pathsep + os.environ["PATH"])
            try:
                p = subprocess.run([sys.executable, "-W", "ignore", os.path.join(os.path.dirname(clip_check.__file__), "clip_check.py"),
                                    clip], capture_output=True, text=True, timeout=60, env=env)
            except subprocess.TimeoutExpired:
                self.fail("clip_check.py hangs when ffmpeg writes more than a pipe buffer to stderr")
            self.assertIn("damaged file", p.stdout)


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "needs ffmpeg")
class AudioPeakTest(unittest.TestCase):
    """Flashback's float export carries sound over full scale (no output limiter): +2.3 dBFS at a 15 kt wave's arrival."""

    def clip(self, d, amplitude, codec="pcm_f32le", ext="mkv"):
        path = os.path.join(d, f"a{amplitude}.{ext}")
        video = encode_file(moving(60), os.path.join(d, "v.mkv"))
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", video, "-f", "lavfi", "-i",
                        f"aevalsrc={amplitude}*sin(2*PI*440*t):s=48000:d=1", "-map", "0:v", "-map", "1:a", "-c:v", "copy",
                        "-c:a", codec, "-shortest", path], check=True)
        return path

    def test_over_full_scale_fails(self):
        with tempfile.TemporaryDirectory() as d:
            report, _ = clip_check.check(self.clip(d, 1.3))
        self.assertTrue(any("audio peak" in f for f in report["fails"]), report)
        self.assertAlmostEqual(2.28, report["audio_peak_db"], delta=0.1)

    def test_limited_sound_passes(self):
        with tempfile.TemporaryDirectory() as d:
            report, _ = clip_check.check(self.clip(d, 0.84))
        self.assertFalse(any("audio peak" in f for f in report["fails"]), report)

    def test_aac_over_full_scale_fails(self):
        # the delivered path: AAC in MP4 keeps the float export's overs
        with tempfile.TemporaryDirectory() as d:
            report, _ = clip_check.check(self.clip(d, 1.3, "aac", "mp4"))
        self.assertTrue(any("audio peak" in f for f in report["fails"]), report)


if __name__ == "__main__":
    unittest.main()
