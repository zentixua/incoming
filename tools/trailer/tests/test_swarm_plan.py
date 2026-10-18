"""Расстановка стаи шахедов (swarm_plan.py): модель полёта, ряды в кадре, пуски врозь, перехваты и стая n02 в сценарии.

    python3 -m unittest discover -s tools/trailer/tests
"""
import math
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import swarm_plan as P  # noqa: E402

STRIKE = {"weapon": "drone", "from": [0, 1000], "via": [[100, 500]], "to": [0, 100, 0], "eta": 30.0}


class SwarmPlanTest(unittest.TestCase):
    def test_flight_runs_the_path_at_cruise(self):
        """Пуск — в месте пуска за длину пути до срока, у цели — в срок, 2,1 блока за тик на высоте цель+45."""
        lt = P.launch_tick(STRIKE)
        start, end = P.position(STRIKE, lt), P.position(STRIKE, 20 * STRIKE["eta"])
        self.assertAlmostEqual(0, math.dist((start[0], start[2]), (0, 1000)), places=6)
        self.assertAlmostEqual(0, math.dist((end[0], end[2]), (0, 0)), places=6)
        self.assertEqual(145, start[1])
        a, b = P.position(STRIKE, lt + 10), P.position(STRIKE, lt + 11)
        self.assertAlmostEqual(P.SPEED, math.dist(a, b), places=6)
        self.assertIsNone(P.position(STRIKE, lt - 1))
        self.assertIsNone(P.position(STRIKE, 20 * STRIKE["eta"] + 1))

    def test_rows_find_a_line_and_not_a_scatter(self):
        line = [(t, {i: (-0.8 + 0.4 * i, 0.1 + 0.05 * i) for i in range(5)}) for t in range(0, 40, 2)]
        n, ids = P.rows(line, 4)
        self.assertGreaterEqual(n, 38)
        self.assertEqual(5, len(ids))
        scatter = [(t, {0: (-0.8, 0.7), 1: (0.6, -0.5), 2: (0.1, 0.3), 3: (-0.3, -0.8), 4: (0.9, 0.9)}) for t in range(0, 40, 2)]
        self.assertEqual(0, P.rows(scatter, 4)[0])

    def test_launches_spread_apart(self):
        """Пуски ближе LAUNCH_GAP тиков разносчик постановщика сдвинул бы — расстановка их раздвигает сроком."""
        a = dict(STRIKE)
        b = dict(STRIKE, eta=STRIKE["eta"] + 3 / 20)
        self.assertEqual(1, P.close_launches([a, b]))
        spread = P.spread_launches([a, b], eta_max=60)
        self.assertEqual(0, P.close_launches(spread))
        self.assertLess(spread[1]["eta"] - b["eta"], 0.5)

    def test_first_entrants_are_shot_down(self):
        """Ракеты ЗРК — первым вошедшим в зону ПВО, сколько ракет; падение — через SAM_LAG тиков."""
        near = dict(STRIKE, eta=10.0)
        far = dict(STRIKE, eta=40.0)
        defense = [((0.0, 65.0, 300.0), 170)]
        got = P.engagements([far, near], defense, 1, range(0, 2000))
        self.assertEqual(1, len(got))
        self.assertEqual(1, got[0][0])

    def test_n02_swarm_passes_before_shooting(self):
        """Стая n02 в сценарии проходит ту же проверку, что вывод (swarm_verdict), без рядов, пуски врозь, 3–4 сбитых
        в разных местах неба — по модели, до съёмки."""
        strikes, cam, fov, window, swarm, defense, _, _ = P.load_plan("n02")
        problems, r4, _, _ = P.score(strikes, cam, fov, window, swarm, defense)
        self.assertEqual([], problems)
        self.assertLessEqual(r4, P.ROW_MAX)


if __name__ == "__main__":
    unittest.main()
