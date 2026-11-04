"""Offline signal checks, independent of Minecraft and sound downloads.

uv run --with numpy --with scipy --with soundfile python -m unittest discover -s tools -p test_audition_sounds.py
"""
import math
from pathlib import Path
import unittest
from unittest.mock import patch

import numpy as np

import audition_sounds as a


class AuditionTest(unittest.TestCase):
    def test_coherent_overlap_reports_overload_instead_of_normalizing_it_away(self):
        x = np.full((a.RATE, 1), 0.4)
        one, four = a.measure(x), a.measure(a.overlap(x, 4, 0))
        self.assertEqual(0, one["samples_over_full_scale"])
        self.assertEqual(a.RATE, four["samples_over_full_scale"])
        self.assertAlmostEqual(20 * math.log10(4), four["sample_peak_dbfs"] - one["sample_peak_dbfs"])
        self.assertLess(four["headroom_db"], 0)

    def test_offsets_keep_the_complete_last_tail(self):
        x = np.array([[1.0], [0.5], [0.25]])
        actual = a.overlap(x, 3, 2 / a.RATE)
        np.testing.assert_array_equal(actual[:, 0], [1, .5, 1.25, .5, 1.25, .5, .25])

    def test_oversampled_meter_detects_an_intersample_peak(self):
        t = np.arange(a.RATE) / a.RATE
        x = (.8 * np.sin(2 * np.pi * 12000 * t + np.pi / 4))[:, None]
        result = a.measure(x)
        self.assertGreater(result["oversampled_peak_4x_dbfs"] - result["sample_peak_dbfs"], 2.5)

    def test_small_speaker_removes_sub_bass_without_makeup_gain(self):
        t = np.arange(a.RATE * 2) / a.RATE
        low = np.sin(2 * np.pi * 40 * t)[:, None]
        mid = np.sin(2 * np.pi * 1000 * t)[:, None]
        rms = lambda x: np.sqrt(np.mean(x[a.RATE:] ** 2))
        self.assertLess(rms(a.speaker(low)) / rms(low), .06)
        self.assertGreater(rms(a.speaker(mid)) / rms(mid), .98)
        self.assertLessEqual(rms(a.speaker(mid)) / rms(mid), 1)

    def test_mono_audition_preserves_stereo_cancellation(self):
        x = np.linspace(-.8, .8, 1000)
        np.testing.assert_array_equal(a.speaker(np.column_stack((x, -x))), 0)

    def test_mixed_case_adds_one_near_to_the_far_copies(self):
        near = np.full((100, 1), .4)
        far = np.full((100, 2), .05)
        cases = dict(a.cases(near, far, [1, 4], 0))
        np.testing.assert_allclose(cases["mixed-004-full"][:100], .6)
        self.assertEqual((24100, 2), cases["mixed-004-full"].shape)

    def test_every_audition_uses_one_gain_and_measurements_stay_unscaled(self):
        near = np.full((1000, 1), .4)
        far = near / 2
        written = {}
        def write(path, data, rate, subtype):
            written[path.name] = data
            self.assertEqual(a.RATE, rate)
            self.assertEqual("PCM_24", subtype)
        with patch.object(a, "read", side_effect=[near, far]), \
                patch.object(Path, "mkdir"), patch.object(Path, "write_text"), \
                patch.object(Path, "read_bytes", return_value=b"fixture"), patch.object(a.sf, "write", side_effect=write):
            report = a.run(Path("near.wav"), Path("far.wav"), Path("output"), [1, 4], 0, 6)
        np.testing.assert_allclose(written["near-001-full.wav"], 2 * written["far-001-full.wav"])
        self.assertGreater(report["cases"]["near-004-full"]["sample_peak_dbfs"], 0)
        self.assertLess(report["audition_gain_db"], -6)
        for x in written.values():
            peak = a.measure(x)["oversampled_peak_4x_dbfs"]
            self.assertTrue(peak is None or peak <= -6 + 1e-10)

    def test_silence_is_valid_json_and_long_overlap_is_rejected(self):
        result = a.measure(np.zeros((1000, 2)))
        self.assertIsNone(result["headroom_db"])
        self.assertIsNone(result["rms_dbfs"])
        self.assertEqual(0, result["samples_over_full_scale"])
        with self.assertRaises(ValueError):
            a.overlap(np.zeros((1000, 1)), 100, 30)


if __name__ == "__main__":
    unittest.main()
