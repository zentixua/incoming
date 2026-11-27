"""clip_check.py on synthetic takes whose answer is known. Run: python3 -m unittest tools/test_clip_check.py"""
import os
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
        f = clip_check.classify(take(40, black={10}, torn={20}, repeat={30, 31}))
        self.assertEqual([10], [x["i"] for x in f if x["black"]])
        self.assertEqual([20], [x["i"] for x in f if x["torn"]])
        self.assertEqual([30, 31], [x["i"] for x in f if x["repeat"]])
        self.assertEqual(2, clip_check.longest(x["repeat"] for x in f))

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
            bad = self.encode(take(30, black={12}, repeat={20}), d, "bad.mp4")
            report, _ = clip_check.check(bad, fps=30, audio=True)
            self.assertEqual(1, report["black"], report)
            self.assertEqual(1, report["repeat"], report)
            self.assertIn("no audio stream", report["fails"])


if __name__ == "__main__":
    unittest.main()
