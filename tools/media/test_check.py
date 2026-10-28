"""Regression cases for the content failures missed in PR #518; no game/GPU needed."""
import unittest
from pathlib import Path
from unittest.mock import patch

from check import body_contrast, longest_run, missile_checks, native_boundary, nuclear_checks, phase_ok, project, projected_box, proof_matches, timing_ok, trajectory


class ContentTest(unittest.TestCase):
    def test_body_outline_is_measured_separately_from_its_background(self):
        class Pixels:
            shape = (40, 80, 3)
            def __init__(self, body):
                self.body = body
            def __getitem__(self, point):
                y, x = point
                value = self.body if 17 <= y <= 23 and 10 <= x <= 70 else 100
                return [value]*3
        sample = dict(axis_px=[60, 0], width_px=6, x_px=40, y_px=20)
        self.assertEqual(body_contrast(Pixels(100), sample), 0)
        self.assertGreater(body_contrast(Pixels(180), sample), 100)

    def test_hud_summary_cannot_replace_a_recorded_flight_pose(self):
        rows = {
            "entities.csv.gz": [], "tracks.csv.gz": [],
            "far.csv.gz": [
                dict(uuid="rocket", t="10", x="1", y="2", z="3", data='{"yaw": 20, "pitch": 5}'),
                dict(uuid="rocket", t="10", x="999", y="999", z="999", data='{"eta": 50}'),
            ],
        }
        with patch("check.table", side_effect=lambda path: rows[path.name]):
            sample = trajectory(Path("unused"), "rocket")
        self.assertEqual(sample(10), [1, 2, 3, 20, 5])
        self.assertIsNone(sample(11))

    def test_wall_clock_offset_cannot_prove_a_still_tick(self):
        self.assertFalse(proof_matches({"seconds_after_log": 16.453}, 2604, "abc"))
        proof = {"paused": True, "tick": 2604, "f2_sha256": "abc", "state_source": "paused.json"}
        self.assertTrue(proof_matches(proof, 2604, "abc"))
        self.assertFalse(proof_matches(proof, 2605, "abc"))
        self.assertFalse(proof_matches(proof, 2604, "different-image"))

    def test_final_video_must_keep_native_frame_clock(self):
        self.assertTrue(timing_ok([0, .033, .067, .1], 30))
        self.assertFalse(timing_ok([0, .083, .167, .25], 30))
        self.assertFalse(timing_ok([0, .033, .1], 30))
        self.assertFalse(timing_ok([], 30))

    def test_animation_timing_cannot_disguise_twelve_hz_capture(self):
        self.assertEqual([native_boundary(t, 30) for t in [33, 67, 100]], [1, 2, 3])
        self.assertEqual(native_boundary(200, 30), 6)  # Six identical frames may be combined.
        for t in [0, 83, 250]:
            with self.subTest(milliseconds=t), self.assertRaises(ValueError):
                native_boundary(t, 30)

    def test_late_orange_ring_is_not_a_flash(self):
        self.assertFalse(phase_ok("flash", 16.453, .71554, 9.214, 18.024))
        self.assertTrue(phase_ok("flash", .1, .71554, 9.214, 18.024))
        self.assertFalse(phase_ok("rising-cap", .1, .71554, 9.214, 18.024))
        self.assertFalse(phase_ok("unknown", 16, .71554, 9.214, 18.024))

    def test_projection_distinguishes_behind_camera(self):
        self.assertEqual(project([0, 0, -5], [0, 0, 0, 0, 0], 60, 800, 450)[2], -5)
        self.assertEqual(project([0, 0, 5], [0, 0, 0, 0, 0], 60, 800, 450), (400, 225, 5))
        self.assertLess(project([0, 2, 5], [0, 0, 0, 0, 0], 60, 800, 450)[1], 225)

    def test_near_cap_edge_can_be_cropped_while_axis_top_fits(self):
        camera = [0, 0, 0, 0, -30]
        axis_top = project([0, 10, 10], camera, 60, 800, 450)
        self.assertGreater(axis_top[1], 0)
        bounds = projected_box([0, 8, 10], 5, 6, 10, camera, 60, 800, 450)
        self.assertLess(bounds[1], 0)
        wide = projected_box([0, 8, 10], 5, 6, 10, camera, 100, 800, 450)
        self.assertTrue(0 < wide[0] < wide[2] < 800 and 0 < wide[1] < wide[3] < 450)

    def approach(self, n=62):
        return [dict(tick=i*2/3, depth=i*2-10, target_distance=200-i*2, known=True,
                     position=[0, 0, i*2], forward=[0, 0, 1],
                     visible=i>5, body_px=30, width_px=4, contrast=20) for i in range(n)]

    def test_normal_readable_approach_and_impact(self):
        failures, run = missile_checks(self.approach(), 30, 38)
        self.assertEqual(failures, [])
        self.assertGreaterEqual(run, 30)

    def test_tracking_camera_does_not_erase_real_forward_travel(self):
        rows = self.approach()
        for row in rows[6:]:
            row["depth"] = 30  # Native camera follows at a constant offset.
        failures, run = missile_checks(rows, 30, 38)
        self.assertEqual(failures, [])
        self.assertGreaterEqual(run, 30)
        for row in rows:
            row["position"] = [0, 0, 0]
        self.assertEqual(missile_checks(rows, 30, 38)[1], 0)

    def test_trim_to_first_impact_cannot_pass(self):
        rows = self.approach()
        trimmed = [r for r in rows if r["tick"] >= 38]
        failures, _ = missile_checks(trimmed, 30, 38)
        self.assertTrue(any("both the approach" in x for x in failures))

    def test_wrong_camera_side_cannot_pass(self):
        rows = self.approach()
        for r in rows:
            r["depth"] = 200-r["depth"]
            r["forward"] = [0, 0, -1]
        failures, run = missile_checks(rows, 30, 38)
        self.assertEqual(run, 0)
        self.assertTrue(any("behind" in x for x in failures))

    def test_tiny_or_obscured_missile_cannot_pass(self):
        for key, value in [("width_px", 2), ("body_px", 4), ("visible", False), ("known", False), ("contrast", 2)]:
            with self.subTest(key=key):
                rows = self.approach()
                for r in rows:
                    r[key] = value
                failures, run = missile_checks(rows, 30, 38)
                self.assertTrue(failures)
                self.assertEqual(run, 0)

    def test_disjoint_glimpses_do_not_sum_to_continuous_approach(self):
        self.assertEqual(longest_run([True]*20+[False]+[True]*20), 20)
        rows = self.approach()
        rows[26]["visible"] = False
        rows[47]["visible"] = False
        failures, run = missile_checks(rows, 30, 38)
        self.assertLess(run, 30)
        self.assertTrue(any("consecutive" in x for x in failures))

    def test_ground_and_cap_must_remain_visible_after_wave_arrival(self):
        ticks = [80+i*2/3 for i in range(5581)]
        event = {"tick": 100, "detonation": {"yieldKt": 5, "scale": 1, "dayTime": 17000, "burst": [0, 63, 0]}}
        cameras = [{"cam": [0, 129.62, -1650, 0, -20]} for _ in ticks]
        frames = [dict(mean=255 if 100<=t<=114 else 10, bright_fraction=1 if 100<=t<=114 else 0,
                       cap_visible=True, cap_world_y=t, ground_in_frame=True) for t in ticks]
        phases = {"pulse_s": .71554, "rollup_s": 9.214, "wave_s": 18.024}
        self.assertEqual(nuclear_checks(ticks, event, cameras, frames, phases, True), [])
        for t, frame in zip(ticks, frames):
            frame["ground_in_frame"] = t < 100+50.8*20
        self.assertTrue(any("source ground" in x for x in nuclear_checks(ticks, event, cameras, frames, phases, True)))
        for t, frame in zip(ticks, frames):
            frame["ground_in_frame"] = True
            frame["cap_visible"] = t < 100+120*20
        self.assertTrue(any("Developed cloud" in x for x in nuclear_checks(ticks, event, cameras, frames, phases, True)))

    def test_old_short_nuclear_export_with_hidden_flash_fails(self):
        ticks = [2270+i*2/3 for i in range(682)]
        event = {"tick": 2284, "detonation": {"yieldKt": 5, "scale": 1, "dayTime": 17000, "burst": [3300.5, 474.394, 640.5]}}
        cameras = [{"cam": [1866.5, 102.62, -153.5, -61.03, -17]} for _ in ticks]
        # Even ideal-looking pixel metrics cannot waive the missing duration or disabled flash layer.
        frames = [dict(mean=255 if 2284<=t<=2298 else 10, bright_fraction=1, cap_visible=True,
                       cap_world_y=t, ground_in_frame=True) for t in ticks]
        failures = nuclear_checks(ticks, event, cameras, frames,
                                  {"pulse_s": .71554, "rollup_s": 9.214, "wave_s": 18.024}, False)
        self.assertTrue(any("180 s" in x for x in failures))
        self.assertTrue(any("F1" in x for x in failures))


if __name__ == "__main__":
    unittest.main()
