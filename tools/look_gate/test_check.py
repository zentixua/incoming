"""Проверка годности прогона шлагбаума (check.py): слух и время игры сцен."""
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
        self.assertTrue(any("слух" in p for p in bad), bad)

    def test_hearing_unknown_fails(self):
        # прогон старым классом без строки слуха (F2b7: nuke_tinnitus = true, а проверять было нечем)
        bad = check.problems(log("SCENARIO probe flyby game 10440 (reference start 10440)"))
        self.assertTrue(any("слух" in p for p in bad), bad)

    def test_scene_out_of_its_time_fails(self):
        # F2b8: выборка не в порядке эталона — ночная сцена в игре 21772 вместо 7722
        bad = check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe grad-night game 21772 (reference start 7722)"))
        self.assertEqual(1, len(bad), bad)
        self.assertIn("grad-night", bad[0])
        # раньше своего времени сцена не начинается (догоняет до START − 5); на допуске — годна
        self.assertEqual([], check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe sam-side game 21890 (reference start 21690)")))
        self.assertTrue(check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe sam-side game 21891 (reference start 21690)")))
        self.assertTrue(check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe sam-side game 21000 (reference start 21690)")))

    def test_scene_in_another_air_fails(self):
        # F4c: старт ночью позже эталона на 260 тиков — годен, пока воздух тот же; другой воздух — нет
        bad = check.problems(log("SCENARIO probe hearing damage off",
                                 "SCENARIO probe launch-night-behind game 10344 (reference start 10084, air differs: class 3.40/3.05)"))
        self.assertTrue(any("воздух" in p for p in bad), bad)
        # у версии без воздуха мира (3.4.1) время на кадр не влияет
        self.assertEqual([], check.problems(log("SCENARIO probe hearing damage off",
                                                "SCENARIO probe flyby game 11000 (reference start 10440, air none)")))

    def test_scene_without_reference_fails(self):
        bad = check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe flyby game 9902 (reference start null)"))
        self.assertTrue(any("flyby" in p for p in bad), bad)

    def test_stopped_run_fails(self):
        bad = check.problems(log("SCENARIO probe hearing damage off", "SCENARIO probe FAIL grad-night starts at game 21772"))
        self.assertTrue(any("остановлен" in p for p in bad), bad)

    def test_empty_run_fails(self):
        self.assertTrue(check.problems(log("SCENARIO probe hearing damage off")))

    def test_reference_logs_of_the_rv1_refilm(self):
        # строки времени из прогона RV1 (cb1b4a71, эталонный порядок): все в допуске
        rows = ["SCENARIO probe hearing damage off"] + [f"SCENARIO probe {c} game {g} (reference start {r})" for c, g, r in [
            ("drone-day-50", 902, 902), ("grad-day", 5821, 5821), ("launch-behind", 9519, 9519), ("water-day-50", 11044, 11047),
            ("sam-behind", 22299, 22182)]]
        self.assertEqual([], check.problems(log(*rows)))


class ProbeFormatTest(unittest.TestCase):
    """check.py читает строки, которые пишет ProbeScenario: формат в Java и разбор здесь — одни."""

    def test_probe_writes_what_check_reads(self):
        root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
        java = open(os.path.join(root, "mod/src/devtest/java/ua/zentix/incoming/scenario/ProbeScenario.java"), encoding="utf-8").read()
        for fmt, sample in [('"SCENARIO probe hearing damage {}"', "SCENARIO probe hearing damage off"),
                            ('"SCENARIO probe {} game {} (reference start {}, air {})"', "SCENARIO probe flyby game 10440 (reference start 10440, air same: x)"),
                            ('"SCENARIO probe FAIL {}"', "SCENARIO probe FAIL x")]:
            self.assertIn(fmt, java, "строки нет в ProbeScenario")
        self.assertTrue(check.HEARING.search("SCENARIO probe hearing damage off"))
        m = check.START.search("SCENARIO probe flyby game 10440 (reference start 10440, air same: wind 1.00/1.00 m/s)")
        self.assertTrue(m and m.group(4).startswith("same"))
        self.assertTrue(check.FAIL.search("SCENARIO probe FAIL x"))
        self.assertIn('return (same ? "same: " : "differs: ")', java, "проба пишет same/differs — это и читает check.py")


if __name__ == "__main__":
    unittest.main()
