# /// script
# requires-python = ">=3.10"
# dependencies = ["numpy", "pillow", "scipy"]
# ///
"""clip_metrics.py on synthetic takes whose answer is known. Run: uv run tools/test_clip_metrics.py"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import clip_metrics  # noqa: E402

RNG = np.random.default_rng(7)
SIZE = (160, 256)


def texture(seed: int) -> np.ndarray:
    from scipy.ndimage import gaussian_filter
    noise = np.random.default_rng(seed).normal(size=(SIZE[0] + 40, SIZE[1] + 40))
    return 128 + 60 * gaussian_filter(noise, 1.5) / gaussian_filter(noise, 1.5).std()


def take(frames, d: Path) -> list[Path]:
    """frames as a numbered PNG take in d"""
    out = []
    for k, f in enumerate(frames):
        out.append(d / f"f{k:04d}.png")
        Image.fromarray(np.clip(f, 0, 255).astype(np.uint8)).save(out[-1])
    return out


class ChangeTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.d = Path(tmp.name)

    def test_sliding_detail_is_not_change_and_its_speed_is_measured(self):
        base = texture(1)
        files = take([base[20:20 + SIZE[0], 20 + k:20 + k + SIZE[1]] for k in range(12)], self.d)
        value, blocks, lag, v50, _ = clip_metrics.change(files, 30.0, 0.1, (0, 0, 1, 1))
        self.assertEqual(lag, 3)
        self.assertGreater(blocks, 0)
        self.assertLess(value, 0.05)
        self.assertAlmostEqual(v50, 30.0, delta=1.0)   # 1 px per frame at 30 fps

    def test_an_odd_pixel_shift_is_found(self):
        a = np.random.default_rng(314).integers(0, 256, (48, 48)).astype(np.float32)
        files = take([a, np.roll(a, 1, axis=1)], self.d)
        value, blocks, lag, _, _ = clip_metrics.change(files, 10.0, 0.1, (0, 0, 1, 1))
        self.assertEqual((blocks, lag), (1, 1))
        self.assertLess(value, 0.02)

    def test_new_detail_every_frame_is_full_change(self):
        files = take([texture(10 + k)[:SIZE[0], :SIZE[1]] for k in range(12)], self.d)
        self.assertGreater(clip_metrics.change(files, 30.0, 0.1, (0, 0, 1, 1))[0], 0.6)

    def test_detail_vanishing_into_a_flat_frame_is_full_change(self):
        a = np.random.default_rng(3).integers(0, 256, (48, 48)).astype(np.float32)
        files = take([a, np.full_like(a, 100)], self.d)
        self.assertAlmostEqual(clip_metrics.change(files, 10.0, 0.1, (0, 0, 1, 1))[0], 1.0)

    def test_sandbox_ages_set_the_rate_and_tall_frames_are_taken_to_720_rows(self):
        for k in range(7):
            Image.fromarray(np.full((1080, 64), 100, np.uint8)).save(self.d / f"wall_{-1 + k * 0.05:+.3f}s.png")
        files, fps = clip_metrics.frame_files(self.d, 30.0, None, self.d)
        self.assertEqual(len(files), 7)
        self.assertAlmostEqual(fps, 20.0, places=3)
        self.assertEqual(clip_metrics.luma(files[0]).shape[0], clip_metrics.ROWS)


class FlickerTest(unittest.TestCase):
    def test_steady_motion_does_not_flicker_and_pulsing_does(self):
        with tempfile.TemporaryDirectory() as d:
            ramp = np.tile(np.linspace(0, 200, SIZE[1], dtype=np.float32), (SIZE[0], 1))
            (Path(d) / "s").mkdir()
            (Path(d) / "p").mkdir()
            steady = take([ramp + k for k in range(10)], Path(d) / "s")
            pulsing = take([ramp + (8 if k % 2 else 0) for k in range(10)], Path(d) / "p")
            _, steady_mean, _, _ = clip_metrics.flicker(steady, (0, 0, 1, 1))
            _, pulse_mean, _, share = clip_metrics.flicker(pulsing, (0, 0, 1, 1))
        self.assertLess(steady_mean, 0.01)
        self.assertGreater(pulse_mean, 10)
        self.assertGreater(share, 0.99)


class PeriodTest(unittest.TestCase):
    def test_copied_teeth_have_a_dominant_period(self):
        x = np.arange(1024)
        teeth = (x % 48) / 48.0 * 6.0  # sharp rise, straight slope: one piece copied in a row
        px, peak = clip_metrics.period(teeth)
        self.assertEqual(px, 48)
        self.assertGreater(peak, 0.8)

    def test_multiscale_noise_has_none(self):
        from scipy.ndimage import gaussian_filter
        edge = sum(gaussian_filter(RNG.normal(size=1024), s) * s for s in (2, 6, 18))
        _, peak = clip_metrics.period(edge)
        self.assertLess(peak, 0.5)

    def test_a_staircase_shrinking_with_perspective_is_caught_by_its_slope_only(self):
        spacing = np.linspace(181, 40, 12)  # the 4-8 px steps of a reported wave edge, closer toward the far end
        edge = np.concatenate([np.full(int(w), 6.0 * i) for i, w in enumerate(spacing)])
        _, peak = clip_metrics.period(edge)
        self.assertLess(peak, 0.5)
        self.assertGreater(clip_metrics.slope_kurtosis(edge), clip_metrics.STAIRCASE)

    def test_a_smooth_multiscale_edge_is_no_staircase(self):
        from scipy.ndimage import gaussian_filter
        edge = sum(gaussian_filter(RNG.normal(size=1500), s) * s for s in (3, 9, 27))
        self.assertLess(clip_metrics.slope_kurtosis(edge), clip_metrics.STAIRCASE)

    def test_a_straight_gentle_slope_is_no_staircase(self):
        x, y = np.arange(1024), np.arange(64)[:, None]
        edge = 20 + x / 64
        band = (32 + 190 / (1 + np.exp((edge - y) / 0.7))).astype(np.float32)
        rows = clip_metrics.edge_rows(band)
        self.assertLess(np.abs(rows - edge).max(), 0.5)
        self.assertLess(clip_metrics.slope_kurtosis(rows), clip_metrics.STAIRCASE)

    def test_a_short_period_is_its_fundamental(self):
        px, peak = clip_metrics.period(np.cos(2 * np.pi * np.arange(1024) / 6))
        self.assertEqual(px, 6)
        self.assertGreater(peak, 0.9)

    def test_edge_rows_follow_a_dark_band_over_light_ground(self):
        band = np.full((60, 200), 200.0, np.float32)
        lower = (30 + 5 * np.sin(np.arange(200) / 7.0)).astype(int)
        for col, row in enumerate(lower):
            band[:row, col] = 40.0
        rows = clip_metrics.edge_rows(band)
        self.assertLess(np.abs(rows - lower).max(), 1.5)


if __name__ == "__main__":
    unittest.main()
