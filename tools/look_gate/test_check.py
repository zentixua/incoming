"""Checking the validity of the look gate run (check.py): hearing, scene playing time and camera point."""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402

P = "[22Oct2026 19:46:45.169] [Render thread/INFO] [ua.zentix.incoming.Incoming/]: "


def log(*rows):
    return [P + r for r in rows]


class CheckTest(unittest.TestCase):
    def test_good_run(self):
        self.assertEqual([], check.problems(log(
            "SCENARIO probe hearing damage off",
            "SCENARIO probe drone-day-50 game 902 (reference start 902, air same: wind 1.20/1.20 m/s)",
            "SCENARIO probe launch-night-behind game 10344 (reference start 10084, air same: wind 3.10/3.12 m/s)",
            "SCENARIO probe grad-night game 7717 (reference start 7722)",
            "SCENARIO probe water-day-50 game 11044 (reference start 11047)")))

    def test_hearing_on_fails(self):
        bad = check.problems(log("SCENARIO probe hearing damage on", "SCENARIO probe flyby game 10440 (reference start 10440)"))
        self.assertTrue(any("hearing" in p for p in bad), bad)

    def test_hearing_unknown_fails(self):
        # running with the old class without the hearing line (F2b7: nuke_tinnitus = true, but there was nothing to check with)
        bad = check.problems(log("SCENARIO probe flyby game 10440 (reference start 10440)"))
        self.assertTrue(any("hearing" in p for p in bad), bad)

    def test_scene_out_of_its_time_fails(self):
        # F2b8: sample is not in the order of the reference - night scene in the game 21772 instead of 7722
        bad = check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe grad-night game 21772 (reference start 7722)"))
        self.assertEqual(1, len(bad), bad)
        self.assertIn("grad-night", bad[0])
        # the scene does not start before its time (it reaches START − 5); on clearance - fit
        self.assertEqual([], check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe sam-side game 21890 (reference start 21690)")))
        self.assertTrue(check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe sam-side game 21891 (reference start 21690)")))
        self.assertTrue(check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe sam-side game 21000 (reference start 21690)")))

    def test_scene_in_another_air_fails(self):
        # F4c: start at night later than the standard on 260 tick - good as long as the air is the same; other air - no
        bad = check.problems(log("SCENARIO probe hearing damage off",
                                 "SCENARIO probe launch-night-behind game 10344 (reference start 10084, air differs: class 3.40/3.05)"))
        self.assertTrue(any("air" in p for p in bad), bad)
        # for the version without world air (3.4.1) time does not affect the frame
        self.assertEqual([], check.problems(log("SCENARIO probe hearing damage off",
                                                "SCENARIO probe flyby game 11000 (reference start 10440, air none)")))

    def test_scene_ending_in_another_air_fails(self):
        bad = check.problems(log("SCENARIO probe hearing damage off",
                                 "SCENARIO probe debris-night-50 game 12321 (reference start 12236, air same: x)",
                                 "SCENARIO probe debris-night-50 ends at game 13500 (reference end 13415, air differs: class 3.12/3.00)"))
        self.assertTrue(any("towards the end" in p for p in bad), bad)
        self.assertEqual([], check.problems(log("SCENARIO probe hearing damage off",
                                                "SCENARIO probe flyby game 10536 (reference start 10440, air same: x)",
                                                "SCENARIO probe flyby ends at game 11000 (reference end 10904, air same: x)")))

    def test_unreadable_air_fails(self):
        # the air class was redone, the reflection did not read it: not “none” (version without air), but a failure
        bad = check.problems(log("SCENARIO probe hearing damage off",
                                 "SCENARIO probe flyby game 10536 (reference start 10440, air differs: air unreadable (java.lang.NoSuchMethodException: speed))"))
        self.assertTrue(bad)

    def test_scene_without_reference_fails(self):
        bad = check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe flyby game 9902 (reference start null)"))
        self.assertTrue(any("flyby" in p for p in bad), bad)

    def test_stopped_run_fails(self):
        bad = check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe FAIL grad-night starts at game 21772"))
        self.assertTrue(any("stopped" in p for p in bad), bad)

    def test_camera_put_back_fails(self):
        # LNB 23.10 (run LFX11): eye of the scene in 7190 202 −38, frame - from the player’s place to the start, in 22 blocks
        rows = ("SCENARIO probe hearing damage off",
                "SCENARIO probe launch-night-behind game 10169 (reference start 10084, air same: x)",
                "SCENARIO probe shot launch-night-behind_0.5s camera 7169 202 -32 off 22.40",
                "SCENARIO probe shot launch-night-behind_1s camera 7181 213 -48 off 0.00")
        bad = check.problems(log(*rows))
        self.assertEqual(1, len(bad), bad)
        self.assertIn("launch-night-behind_0.5s", bad[0])

    def test_camera_on_its_place_passes(self):
        self.assertEqual([], check.problems(log(
            "SCENARIO probe hearing damage off",
            "SCENARIO probe launch-behind game 9519 (reference start 9519, air same: x)",
            "SCENARIO probe shot launch-behind_0.5s camera 5791 226 233 off 0.00")))

    def test_empty_run_fails(self):
        self.assertTrue(check.problems(log("SCENARIO probe hearing damage off")))

    def test_reference_logs_of_the_rv1_refilm(self):
        # time lines from run RV1 (cb1b4a71, reference order): all in tolerance
        rows = ["SCENARIO probe hearing damage off"] + [f"SCENARIO probe {c} game {g} (reference start {r})" for c, g, r in [
            ("drone-day-50", 902, 902), ("grad-day", 5821, 5821), ("launch-behind", 9519, 9519), ("water-day-50", 11044, 11047),
            ("sam-behind", 22299, 22182)]]
        self.assertEqual([], check.problems(log(*rows)))


class ProbeFormatTest(unittest.TestCase):
    """check.py reads the lines that ProbeScenario writes: the format in Java and the parsing here are the same."""

    def test_probe_writes_what_check_reads(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        java = open(os.path.join(root, "mod/src/devtest/java/ua/zentix/incoming/scenario/ProbeScenario.java"), encoding="utf-8").read()
        for fmt, sample in [('"SCENARIO probe hearing damage {}"', "SCENARIO probe hearing damage off"),
                            ('"SCENARIO probe {} game {} (reference start {}, air {})"', "SCENARIO probe flyby game 10440 (reference start 10440, air same: x)"),
                            ('"SCENARIO probe FAIL {}"', "SCENARIO probe FAIL x")]:
            self.assertIn(fmt, java, "there is no line in ProbeScenario")
        self.assertTrue(check.HEARING.search("SCENARIO probe hearing damage off"))
        m = check.START.search("SCENARIO probe flyby game 10440 (reference start 10440, air same: wind 1.00/1.00 m/s)")
        self.assertTrue(m and m.group(4).startswith("same"))
        self.assertTrue(check.FAIL.search("SCENARIO probe FAIL x"))
        self.assertIn('return (same ? "same: " : "differs: ")', java, "the sample writes same/differs - this is what reads check.py")
        self.assertIn('"SCENARIO probe {} ends at game {} (reference end {}, air {})"', java)
        self.assertTrue(check.END.search("SCENARIO probe flyby ends at game 11000 (reference end 10904, air same: x)"))
        self.assertIn('"SCENARIO probe shot {} camera {} off {}"', java)
        self.assertTrue(check.CAMERA.search("SCENARIO probe shot launch-side_1s camera 1 2 3 off 0.00"))
        self.assertIn("CAMERA_SLIP = " + ("%g" % check.SLIP), java, "the threshold in check.py is the same as in the sample")


if __name__ == "__main__":
    unittest.main()
