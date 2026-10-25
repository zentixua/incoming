"""Шлагбаум, разбор и план стенда без игры: python3 -m unittest discover -s tools/stand -p 'test_*.py' (CI «Стенд сцен»)."""
import json
import math
import os
import tempfile
import time
import unittest
from pathlib import Path

import client
import facing
import gate
import plan
import results
import stand


def window(gpu50, thread95, fps=60.0, mspt=6.5):
    return {"fps": fps, "frames": 600, "gpu": {"p50": gpu50, "p95": gpu50 * 1.2}, "thread": {"p50": thread95 * 0.6, "p95": thread95},
            "cpu": {"p50": thread95, "p95": thread95 * 1.8}, "interval": {"p50": gpu50 * 1.1, "p95": gpu50 * 1.4}, "stages": {},
            "mspt": {"mean": mspt}}


def run(scale=1.0, first=40.0, growth=1.10, packs=("none", "complementary"), name="x", mspt=6.5):
    """Прогон: окна 16/40/100 × камеры, первый удар, накопление; scale — во сколько раз дороже кадр."""
    p = {}
    for pack in packs:
        ws = {}
        for n, base in (("16", 5.0), ("40", 8.0), ("100", 12.0)):
            for cam in ("outside", "edge", "inside"):
                ws[f"{n}/{cam}"] = window(base * scale, base * 0.5 * scale, mspt=mspt)
        p[pack] = {"windows": ws, "first": {"worst_ms": first}, "acc": {"growth": {"ratio": growth, "first_ms": 10, "last_ms": 10 * growth}},
                   "checks": [], "meta": {"gl": {"renderer": "RTX 4060", "version": "4.6 NVIDIA 615.78.08", "width": 2560, "height": 1600}}}
    return {"name": name, "label": "x", "context": {"driver": "615"}, "packs": p}


def pair(a1=1.0, a2=1.0, **kw):
    """Принятый парой: A1 и A2 (scale — во сколько раз дороже кадр)."""
    return [run(scale=a1, name="A1", **kw), run(scale=a2, name="A2", **kw)]


def set_gpu(r, v, pack="none", win="100/edge"):
    r["packs"][pack]["windows"][win]["gpu"]["p50"] = v
    return r


class Pair(unittest.TestCase):
    def test_same_version_passes(self):
        v = gate.compare(run(scale=1.03), pair(1.0, 0.98))
        self.assertTrue(v["pass"], v["fails"])
        self.assertEqual(v["state"], gate.PASS)

    def test_base_is_the_mean_and_tolerance_follows_the_spread(self):
        a1, a2 = pair()
        set_gpu(a1, 10.0), set_gpu(a2, 11.0)                 # база 10,5, расхождение 9,5 % → допуск 19 %
        row = next(r for r in gate.compare(set_gpu(run(), 12.3), [a1, a2])["rows"]
                   if r["window"] == "100/edge" and r["metric"] == "gpu.p50")
        self.assertAlmostEqual(row["base"], 10.5)
        self.assertAlmostEqual(row["spread"], 1 / 10.5)
        self.assertAlmostEqual(row["tol"], 2 / 10.5)
        self.assertFalse(row["fail"])                         # +17 % при допуске 19 %
        self.assertTrue(gate.compare(set_gpu(run(), 12.6), [a1, a2])["fails"])   # +20 %

    def test_tolerance_floor(self):
        self.assertEqual(gate.tol_of(0.0), gate.MIN_TOL)
        self.assertEqual(gate.tol_of(None), gate.MIN_TOL)
        self.assertTrue(gate.compare(run(scale=1.09), pair())["pass"])
        self.assertFalse(gate.compare(run(scale=1.12), pair())["pass"])

    def test_drift_between_a1_and_a2_cancels(self):
        # видеокарта грелась весь сеанс: A1 быстрее, A2 медленнее, кандидат между ними — проходит
        v = gate.compare(run(scale=1.08), pair(1.0, 1.09))
        self.assertTrue(v["pass"], v["fails"])

    def test_slower_at_100_fails(self):
        v = gate.compare(run(scale=1.3), pair())
        self.assertEqual(v["state"], gate.FAIL)
        self.assertTrue(any("100/" in f and "gpu.p50" in f for f in v["fails"]))
        self.assertTrue(any("100/" in f and "thread.p95" in f for f in v["fails"]))
        # настенное Pre→Post и кадр — в отчёте, не в шлагбауме: в них ожидание драйвера и вытеснение чужими задачами
        self.assertFalse(any("cpu.p95" in f or "interval.p95" in f for f in v["fails"]))
        self.assertTrue(any(r["metric"] == "cpu.p95" and not r["gated"] for r in v["rows"]))

    def test_regressions_like_3_7_0_fail(self):
        # 3.7.0 против 3.4.1: видеокарта p50 в 2,6–9,3 раза (стенд, 25.10)
        self.assertFalse(gate.compare(run(scale=2.6), pair(1.0, 1.08))["pass"])

    def test_noisy_pair_has_no_verdict(self):
        # A1 1,0 и A2 1,12: база 1,06, расхождение 11 %, допуск 23 % → провал выше 1,30; не хуже A2 + 10 % (1,232) — проход
        v = gate.compare(run(scale=1.25), pair(1.0, 1.12))
        self.assertEqual(v["state"], gate.NOISY)
        self.assertFalse(v["pass"])
        self.assertTrue(any("расхождение" in n for n in v["noisy"]))
        # в пределах худшего прогона принятого — проход и в шумной паре
        self.assertEqual(gate.compare(run(scale=1.0), pair(1.0, 1.12))["state"], gate.PASS)
        self.assertEqual(gate.compare(run(scale=1.2), pair(1.0, 1.12))["state"], gate.PASS)
        # сверх допуска, выросшего с шумом, — провал и в шумной паре
        self.assertEqual(gate.compare(run(scale=2.0), pair(1.0, 1.12))["state"], gate.FAIL)
        self.assertEqual(gate.compare(run(scale=1.35), pair(1.0, 1.12))["state"], gate.FAIL)
        # провал самого кандидата шум не объясняет
        c = run()
        c["packs"]["none"]["checks"] = ["нет события done"]
        self.assertEqual(gate.compare(c, pair(1.0, 1.12))["state"], gate.FAIL)
        c = run()
        del c["packs"]["none"]["windows"]["100/edge"]
        self.assertEqual(gate.compare(c, pair(1.0, 1.12))["state"], gate.FAIL)

    def test_wall_cpu_and_frame_interval_alone_do_not_fail(self):
        c = run()
        c["packs"]["none"]["windows"]["100/edge"]["interval"]["p95"] *= 1.5
        c["packs"]["none"]["windows"]["100/edge"]["cpu"]["p95"] *= 1.5
        self.assertTrue(gate.compare(c, pair())["pass"])

    def test_render_thread_cpu_regression_fails(self):
        c = run()
        c["packs"]["none"]["windows"]["100/edge"]["thread"]["p95"] *= 1.5     # кадр упирается в видеокарту, интервал прежний
        v = gate.compare(c, pair())
        self.assertEqual(len(v["fails"]), 1, v["fails"])
        self.assertIn("thread.p95", v["fails"][0])

    def test_slower_only_below_100_passes(self):
        c = run()
        for cam in ("outside", "edge", "inside"):
            c["packs"]["none"]["windows"][f"16/{cam}"] = window(50, 80)
        v = gate.compare(c, pair())
        self.assertTrue(v["pass"], v["fails"])
        self.assertTrue(any(r["window"] == "16/edge" and not r["gated"] for r in v["rows"]))

    def test_one_camera_one_pack_is_enough(self):
        c = run()
        c["packs"]["complementary"]["windows"]["100/inside"]["gpu"]["p50"] *= 1.2
        v = gate.compare(c, pair())
        self.assertEqual(len(v["fails"]), 1)
        self.assertIn("complementary 100/inside gpu.p50", v["fails"][0])
        self.assertEqual(v["packs"]["none"]["state"], gate.PASS)
        self.assertEqual(v["packs"]["complementary"]["state"], gate.FAIL)

    def test_candidate_without_gpu_numbers_fails(self):
        c = run()
        c["packs"]["none"]["windows"]["100/inside"]["gpu"] = None
        v = gate.compare(c, pair())
        self.assertFalse(v["pass"])
        self.assertIn("нет числа у кандидата", v["fails"][0])

    def test_other_gpu_or_frame_size_fails(self):
        c = run()
        c["packs"]["none"]["meta"]["gl"]["height"] = 1572
        v = gate.compare(c, pair())
        self.assertFalse(v["pass"])
        self.assertIn("A1 снят иначе, чем кандидат: height 1572 против 1600", v["fails"][0])

    def test_faster_passes(self):
        self.assertTrue(gate.compare(run(scale=0.5), pair())["pass"])

    def test_missing_gated_window_fails(self):
        c = run()
        del c["packs"]["none"]["windows"]["100/outside"]
        v = gate.compare(c, pair())
        self.assertFalse(v["pass"])
        self.assertIn("нет окна у кандидата", v["fails"][0])
        a1, a2 = pair()
        del a2["packs"]["none"]["windows"]["100/outside"]
        self.assertIn("нет окна у принятого", gate.compare(run(), [a1, a2])["fails"][0])

    def test_missing_gated_number_at_the_accepted_fails(self):
        a1, a2 = pair()
        a1["packs"]["none"]["windows"]["100/edge"]["thread"] = None
        v = gate.compare(run(), [a1, a2])
        self.assertFalse(v["pass"])
        self.assertIn("нет числа у принятого", v["fails"][0])

    def test_first_strike_without_a_number_fails(self):
        c = run()
        c["packs"]["none"]["first"] = {"worst_ms": None}
        v = gate.compare(c, pair())
        self.assertFalse(v["pass"])
        self.assertIn("первый удар: нет числа у кандидата", v["fails"][0])

    def test_accumulation_without_a_number_fails(self):
        c = run()
        c["packs"]["none"]["acc"]["growth"] = None
        v = gate.compare(c, pair())
        self.assertFalse(v["pass"])
        self.assertIn("накопление: нет числа роста у кандидата", v["fails"][0])

    def test_scenes_outside_the_selection_are_not_judged(self):
        c, bs = run(), pair()
        for r in [c] + bs:
            del r["packs"]["none"]["first"], r["packs"]["none"]["acc"]
        self.assertTrue(gate.compare(c, bs)["pass"])

    def test_pack_without_baseline_is_noted(self):
        v = gate.compare(run(packs=("none", "photon")), pair())
        self.assertTrue(v["pass"])
        self.assertTrue(any("photon" in n for n in v["notes"]))

    def test_invalid_run_of_the_pair_fails(self):
        c = run()
        c["packs"]["none"]["checks"] = ["залп 100: 40 попаданий из 60 (timeout)"]
        self.assertFalse(gate.compare(c, pair())["pass"])
        a1, a2 = pair()
        a2["packs"]["none"]["checks"] = ["нет события done"]
        v = gate.compare(run(), [a1, a2])
        self.assertIn("прогон принятого A2 не годен", v["fails"][0])

    def test_one_accepted_run_is_noted(self):
        v = gate.compare(run(scale=1.05), [run()])
        self.assertTrue(v["pass"])
        self.assertTrue(any("один раз" in n for n in v["notes"]))

    def test_first_strike_needs_double_and_100_ms(self):
        self.assertTrue(gate.compare(run(first=90), pair(first=40))["pass"])     # вдвое, но меньше 100 мс
        self.assertTrue(gate.compare(run(first=150), pair(first=90))["pass"])    # больше 100 мс, но не вдвое
        v = gate.compare(run(first=250), pair(first=90))
        self.assertFalse(v["pass"])
        self.assertIn("первый удар", v["fails"][0])

    def test_accumulation_growth_beyond_accepted_fails(self):
        self.assertTrue(gate.compare(run(growth=1.60), pair(growth=1.58))["pass"])  # растёт, но как у принятого
        v = gate.compare(run(growth=1.80), pair(growth=1.10))
        self.assertFalse(v["pass"])
        self.assertIn("накопление", v["fails"][0])


class Decide(unittest.TestCase):
    def test_second_pair_decides(self):
        bad, ok = gate.compare_pack("none", run(scale=1.3)["packs"]["none"], [x["packs"]["none"] for x in pair()]), \
            gate.compare_pack("none", run()["packs"]["none"], [x["packs"]["none"] for x in pair()])
        self.assertEqual(gate.decide([ok])["state"], gate.PASS)
        # провал не повторился — проходит, первый — в отчёте
        d = gate.decide([bad, ok])
        self.assertEqual(d["state"], gate.PASS)
        self.assertEqual(d["tries"], 2)
        self.assertEqual(d["earlier"][0]["state"], gate.FAIL)
        self.assertEqual(gate.decide([ok, bad])["state"], gate.FAIL)
        self.assertEqual(gate.decide([bad, bad])["state"], gate.FAIL)

    def test_summary(self):
        p, f, n = {"state": gate.PASS}, {"state": gate.FAIL}, {"state": gate.NOISY}
        for x in (p, f, n):
            x.update(fails=[], noisy=[], rows=[], notes=[])
        self.assertEqual(gate.summary({"a": p, "b": n})["state"], gate.NOISY)
        self.assertEqual(gate.summary({"a": f, "b": n})["state"], gate.FAIL)
        self.assertTrue(gate.summary({"a": p})["pass"])


class Look(unittest.TestCase):
    def test_flags(self):
        c = {"none": {"100_edge": {"smoke": 0.40, "luma": 0.50}, "16_edge": {"smoke": 0.20, "luma": 0.50}}}
        b = {"none": {"100_edge": {"smoke": 0.20, "luma": 0.50}, "16_edge": {"smoke": 0.22, "luma": 0.40}}}
        f = gate.look_flags(c, b)
        self.assertEqual(len(f), 2)
        self.assertIn("100_edge", f[0])
        self.assertIn("яркость", f[1])


# нагрузка окна без чужих задач (results.host_in)
QUIET = {"samples": 4, "covered": 1.0, "gpu_covered": 1.0, "cpu_foreign_max": 0.3, "cpu_foreign": 0.2, "gpu_foreign": 0, "units": {}}


def host_sample(epoch, cpu_foreign=0.2, gpu_foreign=0, dt=5.0, profile="performance", units=None):
    """Выборка процессора (client.Host); gpu — только сводка для ожидания тишины, разбор её не читает."""
    return {"kind": "cpu", "epoch": epoch, "dt": dt, "phase": "run", "load1": 1.0, "cpu_total": cpu_foreign + 4, "cpu_own": 4.0,
            "cpu_foreign": cpu_foreign, "units": units or {}, "gpu": {"foreign_sm": gpu_foreign, "who": [], "temp": 70, "clock": 2500},
            "profile": profile}


def gpu_cycles(t0, t1, foreign_sm=0, own_sm=30):
    """Циклы видеокарты по 1 с подряд с концами t0 + 1 … t1 (client.PmonCycles): own_sm None — у своего клиента числа нет."""
    return [{"kind": "gpu", "epoch": float(t), "dt": 1.0, "foreign_sm": foreign_sm, "who": [], "own_sm": own_sm}
            for t in range(int(t0) + 1, int(t1) + 1)]


class Results(unittest.TestCase):
    def test_percentile_like_numpy(self):
        self.assertEqual(results.percentile([1, 2, 3, 4], 50), 2.5)
        self.assertAlmostEqual(results.percentile(list(range(1, 101)), 95), 95.05)
        self.assertIsNone(results.percentile([], 50))

    def test_growth(self):
        bks = [{"t_s": 10 * i, "interval_mean": 10.0 if i < 6 else 15.0} for i in range(24)]
        g = results.growth(bks)
        self.assertAlmostEqual(g["ratio"], 1.5)
        self.assertIsNone(results.growth(bks[:6]))

    def analyze(self, host):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            rows = ["frame,t_ms,prev_interval_ms,cpu_ms,cpu_thread_ms,gpu_ms,prev_gpu_period_ms,shot,stages"]
            for i in range(200):          # 10 мс кадр, 2 с; кадр 50 — со снимком (он сам длится 500 мс), 51 — после него
                shot = 1 if i == 50 else 2 if i == 51 else 0
                prev = 500.0 if i == 51 else 30.0 if i == 100 else 10.0    # кадр 99 длится 30 мс: худший в окне 100/edge
                rows.append(f"{i},{1000 + 10 * i},{prev},4.0,3.0,8.0,10.0,{shot},after_level=1.5;after_level+=3.0;tail=0.5")
            (d / "frames.csv").write_text("\n".join(rows) + "\n")
            # часы стенда: 0 мс = настенные 1 000 000 с
            ev = [{"type": "gl", "t_ms": 900, "renderer": "x", "version": "4.6", "vendor": "n", "width": 2560, "height": 1600},
                  {"type": "window", "t_ms": 1000, "name": "100/edge", "edge": "begin"},
                  {"type": "window", "t_ms": 2000, "name": "100/edge", "edge": "end"},
                  {"type": "window", "t_ms": 2000, "name": "first", "edge": "begin"},
                  {"type": "window", "t_ms": 3000, "name": "first", "edge": "end"},
                  {"type": "srv", "t_ms": 1500, "mspt": 12.0, "game_time": 100, "projectiles": 0, "entities": 5, "epoch_ms": 1_000_001_500},
                  {"type": "probe", "t_ms": 1500, "name": "ua.zentix.incoming.client.fx.layer.FxLayer#describeTiming", "value": "layer 3 ms"},
                  {"type": "await", "t_ms": 900, "salvo": "100", "why": "all", "impacts": 100, "expect": 100, "took_s": 60},
                  {"type": "done", "t_ms": 3100, "frames": 200}]
            (d / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev) + "\n")
            if host is not None:
                (d / "host.jsonl").write_text("\n".join(json.dumps(s) for s in host) + "\n")
            return results.analyze(d, plan.load(), ["first", "100"])

    def test_analyze_cuts_windows_and_drops_shot_frames(self):
        r = self.analyze([host_sample(1_000_002.0, dt=2.0), host_sample(1_000_004.0, dt=2.0)] + gpu_cycles(1_000_000, 1_000_004))
        w = r["windows"]["100/edge"]
        self.assertEqual(w["frames"], 98)            # кадры 0–99 без снимка и следующего
        # длительность кадра 99 (начат в окне) — из строки кадра 100, за концом окна; снимок (500 мс) не в счёт
        self.assertEqual(w["interval"]["max"], 30.0)
        self.assertEqual(w["gpu"]["p50"], 8.0)
        self.assertEqual(w["thread"]["p95"], 3.0)
        self.assertEqual(w["cpu"]["p95"], 4.0)
        self.assertEqual(w["bound"], "cpu")          # видеокарта 8 мс из 10 мс кадра — меньше 85 %
        self.assertEqual(w["stages"]["after_level+"]["mean"], 3.0)
        self.assertEqual(w["mspt"]["mean"], 12.0)
        self.assertEqual(w["probes"]["FxLayer.describeTiming"], "layer 3 ms")
        self.assertEqual(w["host"]["samples"], 1)    # выборка 1 000 000–1 000 002 с = 0–2000 мс стенда
        self.assertEqual(w["host"]["gpu_covered"], 1.0)
        # первый удар: кадр 99 начат до попадания (1990 мс) и шёл в его миг (до 2020 мс) — его 30 мс и есть худший
        self.assertEqual(r["first"]["worst_ms"], 30.0)
        # окна плана обязательны: 100-hit, 100/outside и 100/inside не сняты
        self.assertEqual(r["checks"], ["окно 100-hit: не снято", "окно 100/outside: не снято", "окно 100/inside: не снято"])

    def test_foreign_load_in_a_gated_window_is_reported_not_invalid(self):
        r = self.analyze([host_sample(1_000_002.0, cpu_foreign=3.1, dt=2.0, units={"incoming-job-gametest": 2.9}),
                          host_sample(1_000_004.0, dt=2.0)] + gpu_cycles(1_000_000, 1_000_004))
        # годность нагрузка не решает (от неё защищает пара в одном сеансе) — она в отчёте
        self.assertFalse(any("ядра" in c for c in r["checks"]), r["checks"])
        load = [c for c in r["load"] if "100/edge" in c]
        self.assertEqual(len(load), 1, r["load"])
        self.assertIn("3.1 ядра", load[0])
        self.assertIn("incoming-job-gametest", load[0])
        # чужая задача на видеокарте — один цикл в окне (между выборками процессора): цикл её и показывает
        cycles = gpu_cycles(1_000_000, 1_000_004)
        cycles[1]["foreign_sm"] = 37
        r = self.analyze([host_sample(1_000_002.0, dt=2.0), host_sample(1_000_004.0, dt=2.0)] + cycles)
        self.assertTrue(any("100/edge" in c and "видеокарта у чужих процессов — 37" in c for c in r["load"]), r["load"])

    def test_desktop_background_on_the_gpu_passes_but_a_foreign_job_does_not(self):
        # окно 1–2 с, циклы по 0,2 с: рабочий стол хоста — 11 % в каждом пятом цикле (прогон 25.10: до 12 %, пока рисует
        # клиент) — годен; чужая задача 15 % всё окно — нет (среднее), один цикл 37 % — нет (худший)
        def cycles(values):
            return [{"kind": "gpu", "epoch": 1_000_001.0 + 0.2 * (k + 1), "dt": 0.2, "foreign_sm": v, "who": [], "own_sm": 80}
                    for k, v in enumerate(values)]
        cpu = [host_sample(1_000_002.0, dt=2.0), host_sample(1_000_004.0, dt=2.0)] + gpu_cycles(1_000_000, 1_000_001) + gpu_cycles(1_000_002, 1_000_004)
        for values, bad in (([11, 0, 0, 0, 0], False), ([15] * 5, True), ([37, 0, 0, 0, 0], True)):
            r = self.analyze(cpu + cycles(values))
            load = [c for c in r["load"] if "100/edge" in c]
            self.assertEqual(bool(load), bad, (values, r["load"]))
            self.assertEqual(r["windows"]["100/edge"]["host"]["gpu_covered"], 1.0)

    def test_load_is_reported_in_the_first_strike_window_too(self):
        r = self.analyze([host_sample(1_000_002.0, dt=2.0), host_sample(1_000_004.0, cpu_foreign=7.0, dt=2.0)] + gpu_cycles(1_000_000, 1_000_004))
        self.assertTrue(any("окно first" in c for c in r["load"]), r["load"])

    def test_first_strike_frame_longer_than_its_window_still_reports_the_load(self):
        # кадр 995–6995 мс накрыл всё окно первого удара 1000–6000: кадров, начатых в окне, нет, а нагрузка — 7 ядер
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            rows = ["frame,t_ms,prev_interval_ms,cpu_ms,cpu_thread_ms,gpu_ms,prev_gpu_period_ms,shot,stages",
                    "0,985,10,4,3,8,10,0,", "1,995,10,4,3,8,10,0,", "2,6995,6000,4,3,8,10,0,"]
            (d / "frames.csv").write_text("\n".join(rows) + "\n")
            ev = [{"type": "window", "t_ms": 1000, "name": "first", "edge": "begin"}, {"type": "window", "t_ms": 6000, "name": "first", "edge": "end"},
                  {"type": "srv", "t_ms": 0, "mspt": 6.0, "game_time": 1, "projectiles": 0, "entities": 1, "epoch_ms": 0}]
            (d / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev) + "\n")
            host = [host_sample(7.0, cpu_foreign=7.0, dt=7.0)] + gpu_cycles(0, 7, foreign_sm=37)
            (d / "host.jsonl").write_text("\n".join(json.dumps(x) for x in host) + "\n")
            r = results.analyze(d, plan.load(), ["first"])
            self.assertEqual(r["first"]["worst_ms"], 6000.0)
            self.assertTrue(any("окно first" in c and "ядра" in c for c in r["load"]), r["load"])
            self.assertTrue(any("окно first" in c and "37 % SM" in c for c in r["load"]), r["load"])

    def test_window_tail_without_samples_is_reported(self):
        # последняя выборка кончилась на 1,1 с окна 1–2 с: хвост без записи
        r = self.analyze([host_sample(1_000_001.1, dt=1.1)])
        self.assertTrue(any("100/edge: нагрузка машины записана на 10%" in c for c in r["load"]), r["load"])

    def test_missing_gpu_telemetry_is_not_zero(self):
        # циклов нет вовсе (nvidia-smi нет или упал) — сводка в выборке процессора замером не считается
        r = self.analyze([host_sample(1_000_004.0, dt=4.0)])
        self.assertTrue(any("100/edge: видеокарта чужими процессами записана на 0%" in c for c in r["load"]), r["load"])
        # циклы есть, но у своего рисующего клиента числа нет — драйвер не считал по процессам: «-» у чужих не ноль
        r = self.analyze([host_sample(1_000_004.0, dt=4.0)] + gpu_cycles(1_000_000, 1_000_004, own_sm=None))
        self.assertTrue(any("100/edge: видеокарта чужими процессами записана на 0%" in c for c in r["load"]), r["load"])

    def test_cycles_after_the_client_exits_do_not_cover_its_window(self):
        # окно 1–2 с; клиент и чужая задача вышли на 1,5 с, заключительная выборка процессора — на 4 с: её отрезок
        # накрывает хвост окна (процессор — по накопленным счётчикам, верно), а циклы видеокарты после выхода — без числа
        # клиента и замером хвоста не становятся
        cycles = [{"kind": "gpu", "epoch": 1_000_001.5, "dt": 0.5, "foreign_sm": 40, "who": ["blender:40"], "own_sm": 30}]
        cycles += gpu_cycles(1_000_001.5, 1_000_004, own_sm=None)
        r = self.analyze([host_sample(1_000_001.0, dt=1.0), host_sample(1_000_004.0, dt=3.0)] + cycles)
        bad = [c for c in r["load"] if "100/edge" in c]
        self.assertTrue(any("видеокарта чужими процессами записана на 50%" in c for c in bad), bad)
        self.assertTrue(any("видеокарта у чужих процессов — 40" in c for c in bad), bad)

    def test_no_load_record_is_reported(self):
        r = self.analyze(None)
        self.assertEqual([c for c in r["load"] if "100/edge" in c], ["окно 100/edge: нет записи нагрузки машины (host.jsonl)"])
        self.assertFalse(any("100/edge" in c for c in r["checks"]))

    def test_checks_catch_a_bad_run(self):
        ev = [{"type": "cmd", "t_ms": 1, "cmd": "incoming salvo drone 1 0 at 1000 0 from 800 346 via 1000 0", "tag": "100", "ok": False, "result": 0},
              {"type": "limit", "t_ms": 2, "step": 3},
              {"type": "salvo", "t_ms": 3, "id": "100", "expect": 100, "commands": 4},
              {"type": "salvo", "t_ms": 4, "id": "acc", "expect": 120, "commands": 12}]
        res = {"meta": {"done": None, "impacts": {"100": {"got": 40, "expect": 100, "why": "timeout", "took_s": 300}}},
               "windows": {"100/edge": {"frames": 5, "gpu": None, "thread": None, "host": QUIET}}}
        bad = results.checks(ev, res)
        # нет done, предел, команда, залп 100 не долетел, у acc нет конца, нет строки видеокарты, окно без меток, без
        # процессора потока и без кадров
        self.assertEqual(len(bad), 9, bad)
        self.assertTrue(any("acc" in b for b in bad))


class Host(unittest.TestCase):
    def test_heavy_by_cpu_or_gpu(self):
        self.assertFalse(client.heavy(host_sample(0, cpu_foreign=0.4)))
        self.assertTrue(client.heavy(host_sample(0, cpu_foreign=1.6)))
        self.assertTrue(client.heavy(host_sample(0, gpu_foreign=37)))

    def test_alignment_by_wall_clock(self):
        ev = [{"type": "srv", "t_ms": 10_000.0, "epoch_ms": 5_010_000.0}]       # 0 мс стенда = 5000 с настенных
        h = results.host_in([host_sample(5015.0, cpu_foreign=2.0), host_sample(5025.0, cpu_foreign=0.0)], ev, 10_000, 20_000)
        self.assertEqual(h["samples"], 1)
        self.assertEqual(h["cpu_foreign"], 2.0)
        self.assertEqual(h["covered"], 0.5)                                 # 10–15 с окна — до первой выборки
        h = results.host_in([host_sample(5015.0, cpu_foreign=2.0), host_sample(5020.0, cpu_foreign=0.0)], ev, 12_500, 17_500)
        self.assertEqual(h["samples"], 2)
        self.assertAlmostEqual(h["cpu_foreign"], 1.0)                       # среднее по перекрытию — в отчёт
        self.assertEqual(h["cpu_foreign_max"], 2.0)                         # судит худшая выборка
        self.assertEqual(h["covered"], 1.0)
        self.assertIsNone(results.host_in([], ev, 0, 1))

    def test_a_short_heavy_burst_is_not_averaged_away(self):
        # 5 с чужой нагрузки в 3 ядра и 15 с по 0,2: среднее 0,9 — меньше порога, но задача шла внутри окна
        ev = [{"type": "srv", "t_ms": 0.0, "epoch_ms": 0.0}]
        h = results.host_in([host_sample(5.0, cpu_foreign=3.0)] + [host_sample(5.0 * k, cpu_foreign=0.2) for k in (2, 3, 4)], ev, 0, 20_000)
        self.assertAlmostEqual(h["cpu_foreign"], 0.9)
        self.assertTrue(results.foreign(h))

    PMON = """#Date        Time         gpu         pid   type     sm    mem    enc    dec    jpg    ofa    command
#YYYYMMDD    HH:MM:SS     Idx           #    C/G      %      %      %      %      %      %    name
 20261025    09:09:20       0       2778   C+G      4      1      -      -      -      -    kwin_wayland
 20261025    09:09:20       0       2789   C+G      -      -      -      -      -      -    plasmashell
 20261025    09:09:20       0    1593256   C+G      -      -      0      0      -      -    kwin_wayland
 20261025    09:09:20       0    1593313   C+G     16      6      -      -      -      -    java
 20261025    09:09:21       0       2778   C+G      -      -      -      -      -      -    kwin_wayland
 20261025    09:09:21       0       2789   C+G      -      -      -      -      -      -    plasmashell
 20261025    09:09:21       0    1503885     G     37     11      -      -      -      -    java
 20261025    09:09:21       0    1593313   C+G      -      -      -      -      -      -    java
#Date        Time         gpu         pid   type     sm    mem    enc    dec    jpg    ofa    command
 20261025    09:09:23       0       2778   C+G      -      -      -      -      -      -    kwin_wayland
 20261025    09:09:23       0    1593313   C+G     30      7      -      -      -      -    java
 20261025    09:09:23       0       2778   C+G      2      1      -      -      -      -    kwin_wayland
 20261025    09:09:23       0    1593313   C+G     31      7      -      -      -      -    java
 20261025    09:09:25       0       2778   C+G      -      -      -      -      -      -    kwin_wayland
"""
    @staticmethod
    def mine(pid, name):
        """Своя cgroup — клиент 1593313 (java) и его KWin 1593256 (client.Host.classify с подставной cgroup)."""
        h = client.Host.__new__(client.Host)
        h.own_pid = lambda p: p in ("1593313", "1593256")
        return h.classify(pid, name)

    def cycles(self, text, classify=None):
        p = client.PmonCycles(classify or self.mine)
        return [c for c in (p.feed(line) for line in text.splitlines()) if c is not None]

    def test_pmon_cycles(self):
        cs = self.cycles(self.PMON)
        # 4 законченных цикла; последний (09:09:25) — недописанный, не цикл
        self.assertEqual(len(cs), 4)
        t20 = time.mktime(time.strptime("20261025 09:09:20", "%Y%m%d %H:%M:%S")) + 0.5
        self.assertEqual([c["epoch"] - t20 for c in cs], [0, 1, 3, 3])
        # первый — своей длины цикла; дальше — до конца прошлого (циклы подряд); пропуск секунды (22) — тоже подряд;
        # два цикла в одной секунде (процесс повторился) — второй своей длины
        self.assertEqual([c["dt"] for c in cs], [client.PMON_S, 1.0, 2.0, client.PMON_S])
        # «-» у простаивающих чужих (композитор, plasmashell) — 0; свои (клиент и его KWin) — не чужие
        self.assertEqual((cs[0]["foreign_sm"], cs[0]["own_sm"]), (4, 16))
        self.assertEqual(cs[0]["who"], ["kwin_wayland:4"])
        # чужой клиент 37 %, а у своего числа нет — цикл без замера (решает разбор), чужие 37 — видны
        self.assertEqual((cs[1]["foreign_sm"], cs[1]["own_sm"]), (37, None))
        self.assertEqual((cs[3]["foreign_sm"], cs[3]["own_sm"]), (2, 31))

    def test_own_client_that_exits_before_its_cycle_closes_stays_own(self):
        # строка клиента пришла живой, а к строке следующего цикла (она закрывает цикл) JVM уже вышла и её /proc нет:
        # её 30 % — не чужие, и цикл — замер (тишина на всё окно)
        alive = {"1593313", "1593256"}
        h = client.Host.__new__(client.Host)
        h.own_pid = lambda pid: pid in alive
        p = client.PmonCycles(h.classify)
        out = []
        for t in range(20, 41):
            stamp = f" 20261025    09:09:{t:02d}       0"
            out += [p.feed(f"{stamp}       2778   C+G      -      -      -      -      -      -    kwin_wayland")]
            if t < 40:
                out += [p.feed(f"{stamp}    1593256   C+G      -      -      0      0      -      -    kwin_wayland"),
                        p.feed(f"{stamp}    1593313   C+G     30      7      -      -      -      -    java")]
            if t == 39:
                alive.clear()           # клиент и его KWin вышли сразу после своей последней строки
        cs = [c for c in out if c is not None]
        self.assertEqual(len(cs), 20)
        self.assertEqual({(c["foreign_sm"], c["own_sm"]) for c in cs}, {(0, 30)})
        t0 = cs[0]["epoch"] - 1.0
        h_in = results.host_in([host_sample(t0 + 20, dt=20.0)] + cs, [{"type": "srv", "t_ms": 0.0, "epoch_ms": t0 * 1000}], 0, 20_000)
        self.assertEqual((h_in["gpu_covered"], h_in["gpu_foreign"]), (1.0, 0))
        self.assertEqual(results.foreign(h_in), [])

    def test_only_the_drawing_client_confirms_a_cycle(self):
        # у JVM «-», у своего KWin число (и ноль) — замера нет; JVM нет вовсе — тоже
        dash = self.PMON.replace("1593313   C+G     16      6", "1593313   C+G      -      -").replace(
            "1593256   C+G      -      -      0      0", "1593256   C+G      2      0      0      0")
        cs = self.cycles(dash)
        self.assertIsNone(cs[0]["own_sm"])
        self.assertEqual(cs[0]["foreign_sm"], 4)          # свой KWin — не чужой
        no_jvm = "\n".join(line for line in dash.splitlines() if "1593313" not in line)
        self.assertTrue(all(c["own_sm"] is None for c in self.cycles(no_jvm)))

    def test_reused_pid_is_classified_again(self):
        # pid чужого процесса после его выхода достался своему клиенту: пара (pid, имя) другая — решается заново
        seen = []

        def classify(pid, name):
            seen.append((pid, name))
            return client.CLIENT if name == "java" else client.FOREIGN
        text = """ 20261025    09:09:20       0       4242   C+G     50      1      -      -      -      -    blender
 20261025    09:09:21       0       4242   C+G     30      1      -      -      -      -    java
 20261025    09:09:22       0       4242   C+G     30      1      -      -      -      -    java
 20261025    09:09:23       0       4242   C+G     30      1      -      -      -      -    java
"""
        cs = self.cycles(text, classify)
        self.assertEqual([(c["foreign_sm"], c["own_sm"]) for c in cs], [(50, None), (0, 30), (0, 30)])
        self.assertEqual(seen, [("4242", "blender"), ("4242", "java")])

    def test_pmon_garbage_gives_no_cycles(self):
        self.assertEqual(self.cycles("NVIDIA-SMI has failed\n\n" + self.PMON.splitlines()[0]), [])

    def test_gpu_watch_end_to_end(self):
        # сборщик с поддельным nvidia-smi (CI без видеокарты): циклы → host_in окна; чужая задача 40 % в одном цикле
        lines = self.PMON.splitlines()
        with tempfile.TemporaryDirectory() as d:
            fake = Path(d) / "nvidia-smi"
            fake.write_text("#!/bin/sh\ncat <<'EOF'\n" + "\n".join(lines[:10]) + "\n" + lines[2].replace("09:09:20", "09:09:22") + "\nEOF\n")
            fake.chmod(0o755)
            old = os.environ["PATH"]
            os.environ["PATH"] = d + os.pathsep + old
            try:
                w = client.GpuWatch(self.mine).start()
                w.thread.join(timeout=10)
                cs = w.stop()
            finally:
                os.environ["PATH"] = old
        self.assertEqual(len(cs), 2)
        t20 = cs[0]["epoch"]
        ev = [{"type": "srv", "t_ms": 0.0, "epoch_ms": (t20 - 1.0) * 1000}]       # 0 мс стенда — начало первого цикла
        h = results.host_in([host_sample(t20 + 1.0, dt=2.0)] + cs, ev, 0, 2000)
        self.assertEqual(h["gpu_covered"], 0.5)        # второй цикл — без числа своего клиента
        self.assertEqual(h["gpu_foreign"], 37)
        self.assertIsNone(client.GpuWatch(self.mine).stop() or None)


class Checks(unittest.TestCase):
    def test_power_profile_is_reported_in_every_verdict_window(self):
        # профиль сменился только в первом ударе и в последней минуте накопления, в «100» — нет
        scene, ev = self.acc_events()
        low = dict(QUIET, profiles=["low-power"])
        ok = dict(QUIET, profiles=["performance"])
        res = {"meta": {"done": {"x": 1}, "impacts": {}}, "windows": {"100/edge": {"frames": 500, "gpu": {"p50": 1}, "thread": {"p95": 1}, "host": ok}},
               "first": {"worst_ms": 30.0, "host": low},
               "acc": {"growth": {"ratio": 1.2}, "load": results.acc_load(ev, scene, 0, 240_000), "host": {"first": ok, "last": low}}}
        bad = [b for b in results.load_notes(res, scene, "performance") if "профиль" in b]
        self.assertEqual(bad, ["окно first: профиль питания low-power, а в начале был performance",
                               "накопление acc, последняя минута: профиль питания low-power, а в начале был performance"])
        self.assertEqual([b for b in results.load_notes(res, scene) if "профиль" in b], [])
        self.assertFalse(any("профиль" in b for b in results.checks(ev + [{"type": "gl"}], res, (), scene)))

    def test_load_is_reported_in_the_accumulation_minutes(self):
        scene, ev = self.acc_events()
        quiet = QUIET
        busy = dict(quiet, cpu_foreign_max=7.0, units={"incoming-job-build": 6.8})
        res = {"meta": {"done": {"x": 1}, "impacts": {}}, "windows": {},
               "acc": {"growth": {"ratio": 1.2}, "load": results.acc_load(ev, scene, 0, 240_000), "host": {"first": quiet, "last": busy}}}
        bad = [b for b in results.load_notes(res, scene) if "ядра" in b]
        self.assertEqual(len(bad), 1, bad)
        self.assertIn("последняя минута", bad[0])

    def acc_events(self, late_ms=100, after=0, first=30, last=30, cmds=120, ran_late_ms=0):
        """Накопление: окно 0–240 с, попаданий first в первую минуту, last в последнюю, after — после окна; волна — 10
        команд, пять пусковых по две очереди через 1,5 с."""
        scene = next(s for s in plan.load()["scenes"] if s.get("waves"))
        ev = [{"type": "window", "t_ms": 0, "name": "acc", "edge": "begin"}, {"type": "window", "t_ms": 240_000, "name": "acc", "edge": "end"},
              {"type": "salvo", "t_ms": -10_000, "id": "acc", "expect": 120, "commands": 120}]

        def due(i):
            return 20_000 * (i // 10) + 1500 * (i % 10 // 5)
        # сервер выполняет команду после отправки; ran_late_ms — средние волны (4–7) он выполнил позже (встал)
        ev += [{"type": "cmd", "t_ms": due(i), "tag": "acc", "ok": True, "cmd": "c", "sent_ms": due(i) + late_ms,
                "ran_ms": due(i) + late_ms + 20 + (ran_late_ms if 40 <= i < 80 else 0), "due_ms": due(i)} for i in range(cmds)]
        times = [i * 2000 for i in range(first)] + [100_000 + i * 1000 for i in range(120 - first - last - after)] \
            + [180_000 + i * 1900 for i in range(last)] + [241_000 + i for i in range(after)]
        ev += [{"type": "impact", "t_ms": t, "salvo": "acc"} for t in times]
        return scene, ev

    def acc_checks(self, **kw):
        scene, ev = self.acc_events(**kw)
        res = {"meta": {"done": {"x": 1}, "impacts": {}}, "windows": {},
               "acc": {"growth": {"ratio": 1.2}, "load": results.acc_load(ev, scene, 0, 240_000)}}
        return [b for b in results.checks(ev + [{"type": "gl"}], res, (), scene) if "накопление" in b]

    def test_planned_load_passes(self):
        self.assertEqual(self.acc_checks(), [])

    def test_waves_after_the_window_fail(self):
        bad = self.acc_checks(after=5)
        self.assertTrue(any("после окна" in b for b in bad), bad)

    def test_waves_fired_in_a_burst_fail(self):
        bad = self.acc_checks(late_ms=6000)
        self.assertTrue(any("не по расписанию" in b for b in bad), bad)

    def test_waves_run_late_by_a_stalled_server_fail(self):
        # клиент слал вовремя, а вставший сервер выполнил средние волны на минуту позже: первая и последняя минуты целы
        bad = self.acc_checks(ran_late_ms=60_000)
        self.assertEqual(len(bad), 1, bad)
        self.assertIn("не по расписанию", bad[0])

    def test_missing_wave_commands_fail(self):
        bad = self.acc_checks(cmds=100)
        self.assertTrue(any("команд волн 100 из 120" in b for b in bad), bad)

    def test_thin_last_minute_fails(self):
        bad = self.acc_checks(last=15)
        self.assertTrue(any("последнюю минуту окна 15" in b for b in bad), bad)

    def test_part_of_a_wave_across_the_minute_edge_passes(self):
        # окно начато поздним шахедом волны 0: быстрые шахеды следующих волн ложатся до границы слота (v3.7.0: 35/25)
        self.assertEqual(self.acc_checks(first=35, last=25), [])
        self.assertTrue(self.acc_checks(first=41, last=19))

    def test_expected_windows_come_from_the_plan_the_run_was_shot_with(self):
        with tempfile.TemporaryDirectory() as t:
            d = raw_run(Path(t) / "r") / "none"
            ev = [json.loads(x) for x in (d / "events.jsonl").read_text().splitlines()]
            (d / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev if e.get("name") != "100-hit") + "\n")
            # снят до окна 100-hit: по нынешним сценам оно обязательно, по своему плану — нет
            self.assertIn("окно 100-hit: не снято", results.analyze(d, plan.load(), ["100"])["checks"])
            old = plan.build(plan.load(), ["100"])
            old["steps"] = [x for x in old["steps"] if x["do"] != "impact_window"]
            (d / "stand-plan.json").write_text(json.dumps(old))
            self.assertEqual(results.analyze(d, plan.load(), ["100"])["checks"], [])
            # залп пуска вблизи (без ожидания конца) — тоже по плану прогона
            self.assertEqual(results.shot_plan(d, plan.load())[1], set())
            launch = plan.build(plan.load(), ["grad"])
            (d / "stand-plan.json").write_text(json.dumps(launch))
            self.assertEqual(results.shot_plan(d, plan.load()), (["grad/start", "grad/hang"], {"grad"}))

    def test_a_few_strikes_lost_in_the_air_are_fine(self):
        def bad(got, why="quiet", expect=100):
            res = {"meta": {"done": {"x": 1}, "impacts": {"100": {"got": got, "expect": expect, "why": why, "took_s": 30}}}, "windows": {}}
            return [b for b in results.checks([{"type": "gl"}], res) if "попаданий" in b]
        self.assertEqual(bad(97), [])
        self.assertTrue(bad(96))
        self.assertTrue(bad(99, why="timeout"))
        self.assertTrue(bad(0, expect=1))

    def test_every_plan_window_is_required(self):
        ev = [{"type": "gl"}, {"type": "done"}, {"type": "window", "name": "100/edge", "edge": "begin", "t_ms": 1}]
        res = {"meta": {"done": {"x": 1}, "impacts": {}},
               "windows": {"100/outside": {"frames": 500, "gpu": {"p50": 1}, "thread": {"p95": 1}, "host": QUIET}}}
        bad = results.checks(ev, res, plan.windows(plan.load(), ["first", "100"]))
        self.assertIn("окно 100/edge: нет кадров", bad)
        self.assertIn("окно 100/inside: не снято", bad)
        self.assertIn("первый удар: нет числа (окна нет или ни один кадр не шёл в нём)", bad)

    def test_stalled_first_strike_is_a_number(self):
        # кадр начался до попадания и шёл 6 с: внутри 5-секундного окна начал кадров нет, но худший кадр — 6 с
        frames = [{"frame": 0, "t": 990.0, "shot": 0, "interval": 10.0}, {"frame": 1, "t": 1000.0 - 5, "shot": 0, "interval": 6000.0},
                  {"frame": 2, "t": 6995.0, "shot": 0, "interval": None}]
        fr = results.overlapping(frames, 1000, 6000)
        self.assertEqual([f["frame"] for f in fr], [1])


def raw_run(d, name="r", gpu=8.0, renderer="RTX 4060", done=True, label="v3.7.0", mspt=6.5, power="performance"):
    """Сырые данные прогона выборки «100» (пак none): meta.json, none/frames.csv, events.jsonl и host.jsonl — как пишет стенд."""
    d = Path(d)
    (d / "none").mkdir(parents=True)
    rows = ["frame,t_ms,prev_interval_ms,cpu_ms,cpu_thread_ms,gpu_ms,prev_gpu_period_ms,shot,stages"]
    for i in range(700):
        rows.append(f"{i},{1000 + 10 * i},10.0,4.0,3.0,{gpu},10.0,0,after_level=1.0")
    (d / "none" / "frames.csv").write_text("\n".join(rows) + "\n")
    ev = [{"type": "gl", "t_ms": 900, "renderer": renderer, "version": "4.6", "vendor": "n", "width": 2560, "height": 1600},
          {"type": "salvo", "t_ms": 950, "id": "100", "expect": 100, "commands": 100},
          {"type": "await", "t_ms": 990, "salvo": "100", "why": "all", "impacts": 100, "expect": 100, "took_s": 30}]
    ev += [{"type": "window", "t_ms": 1000, "name": "100-hit", "edge": "begin"}, {"type": "window", "t_ms": 3000, "name": "100-hit", "edge": "end"}]
    for k, cam in enumerate(("outside", "edge", "inside")):
        ev += [{"type": "window", "t_ms": 1000 + 2000 * k, "name": f"100/{cam}", "edge": "begin"},
               {"type": "window", "t_ms": 3000 + 2000 * k, "name": f"100/{cam}", "edge": "end"}]
    ev += [{"type": "srv", "t_ms": 1000.0 * t, "mspt": mspt, "game_time": t, "projectiles": 0, "entities": 5, "epoch_ms": 1_000_000.0 * 1000 + 1000.0 * t}
           for t in range(1, 8)]
    if done:
        ev.append({"type": "done", "t_ms": 8100, "frames": 700})
    (d / "none" / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev) + "\n")
    host = [host_sample(1_000_000.0 + t, dt=1.0, profile=power) for t in range(1, 9)] + gpu_cycles(1_000_000, 1_000_008)
    (d / "none" / "host.jsonl").write_text("\n".join(json.dumps(x) for x in host) + "\n")
    ctx = {"stand": "s", "mods": {}, "packs": {"none": "none"}, "scenes": ["100"], "gpu": "g", "driver": "d", "size": "2560x1600", "java": "21",
           "kwin": "6.8", "power": "performance"}
    (d / "meta.json").write_text(json.dumps({"name": name, "label": label, "jar": {"file": "j", "sha256": "x", "spec": label},
                                             "context": ctx, "key": "k", "started": "t",
                                             "packs": {"none": {"exit": 0, "gpu_minutes": 1.0, "file": None}}}))
    return d


class PairRuns(unittest.TestCase):
    """Вердикт пары — заново из сырых данных каталога (stand.judge_pair), как у stand.py gate, pr и report.py."""

    def out(self, root, gpus, packs=("none",)):
        """Каталог пары: pair.json и прогоны по ролям {каталог: видеокарта p50}."""
        d = Path(root) / "p"
        d.mkdir()
        (d / "pair.json").write_text(json.dumps({"name": "p", "packs": list(packs), "cand": {"label": "PR 1"}, "base": {"label": "v3.7.0"}}))
        for name, gpu in gpus.items():
            raw_run(d / name, name, gpu=gpu)
        return d

    def test_pair_from_raw_data(self):
        with tempfile.TemporaryDirectory() as root:
            v = stand.judge_pair(self.out(root, {"A1": 8.0, "B": 8.3, "A2": 8.1}))
            self.assertEqual(v["state"], gate.PASS, v["fails"])
            self.assertEqual(v["packs"]["none"]["tries"], 1)
            self.assertTrue((Path(root) / "p" / "verdict.json").exists())

    def test_slower_candidate_fails(self):
        with tempfile.TemporaryDirectory() as root:
            v = stand.judge_pair(self.out(root, {"A1": 8.0, "B": 12.0, "A2": 8.1}))
            self.assertEqual(v["state"], gate.FAIL)
            self.assertTrue(any("gpu.p50" in f for f in v["fails"]))

    def test_second_pair_decides(self):
        with tempfile.TemporaryDirectory() as root:
            v = stand.judge_pair(self.out(root, {"A1": 8.0, "B": 12.0, "A2": 8.1, "A1-2": 8.0, "B-2": 8.2, "A2-2": 8.1}))
            self.assertEqual(v["state"], gate.PASS, v["fails"])
            self.assertEqual(v["packs"]["none"]["tries"], 2)
            self.assertEqual(v["packs"]["none"]["earlier"][0]["state"], gate.FAIL)

    def test_a_passing_first_pair_decides_like_play(self):
        # вторая пара, снятая под старым правилом (первая прошла бы под нынешним), — не в счёт
        with tempfile.TemporaryDirectory() as root:
            v = stand.judge_pair(self.out(root, {"A1": 8.0, "B": 8.1, "A2": 8.0, "A1-2": 8.0, "B-2": 12.0, "A2-2": 8.1}))
            self.assertEqual(v["state"], gate.PASS, v["fails"])
            self.assertEqual(v["packs"]["none"]["tries"], 1)

    def test_incomplete_pair_fails(self):
        with tempfile.TemporaryDirectory() as root:
            v = stand.judge_pair(self.out(root, {"A1": 8.0, "B": 8.0}))
            self.assertEqual(v["state"], gate.FAIL)
            self.assertIn("не снята целиком (A2)", v["fails"][0])

    def test_pack_never_shot_fails(self):
        with tempfile.TemporaryDirectory() as root:
            v = stand.judge_pair(self.out(root, {"A1": 8.0, "B": 8.0, "A2": 8.0}, packs=("none", "photon")))
            self.assertEqual(v["packs"]["photon"]["state"], gate.FAIL)
            self.assertIn("photon: пара не снята", v["fails"][0])

    def test_warmup_is_not_judged(self):
        with tempfile.TemporaryDirectory() as root:
            d = self.out(root, {"A1": 8.0, "B": 8.1, "A2": 8.0, "warmup": 30.0})
            self.assertNotIn("warmup", stand.pair_runs(d))
            self.assertTrue(stand.judge_pair(d)["pass"])

    def test_invalid_run_in_the_pair_fails(self):
        with tempfile.TemporaryDirectory() as root:
            d = self.out(root, {"A1": 8.0, "B": 8.0})
            raw_run(d / "A2", "A2", done=False)
            v = stand.judge_pair(d)
            self.assertEqual(v["state"], gate.FAIL)
            self.assertIn("нет события done", v["fails"][0])

    def test_power_profile_changed_mid_run_is_reported(self):
        with tempfile.TemporaryDirectory() as root:
            d = raw_run(Path(root) / "r", power="low-power")
            r = stand.analyze(d, write=False)["packs"]["none"]
            self.assertTrue(any("профиль питания low-power" in c for c in r["load"]), r["load"])
            self.assertEqual(r["checks"], [])

    def test_report_shot_order_follows_the_plan(self):
        try:
            import report
        except ImportError:
            self.skipTest("нет Pillow (report.py — через uv)")
        # сцены без камер (пуск вблизи) — тоже: их кадры — шаги «кадр» плана
        names = report.shot_order(plan.load(), None)
        self.assertIn("100-hit_0.2s", names)
        self.assertIn("grad_40s", names)
        self.assertIn("100_edge", names)

    def test_report_and_gate_share_one_verdict_path(self):
        # report.py берёт вердикт у stand (judge_pair — из сырых данных), а не из сохранённых results.json
        src = (Path(__file__).parent / "report.py").read_text()
        self.assertIn("stand.judge_pair(", src)
        self.assertNotIn("results.json\").read_text", src)


class Pr(unittest.TestCase):
    def test_renamed_file_counts_by_both_names(self):
        rec = [{"filename": "mod/src/main/java/ua/zentix/incoming/util/Look.java",
                "previous_filename": "mod/src/main/java/ua/zentix/incoming/client/fx/Look.java"},
               {"filename": "docs/x.md", "previous_filename": None}]
        self.assertFalse(facing.player_facing([rec[0]["filename"]]))     # по новому имени одному — «не нужен»
        paths = stand.pr_paths(rec)
        self.assertEqual(len(paths), 3)
        self.assertTrue(facing.player_facing(paths))

    def test_player_facing_paths(self):
        self.assertEqual(facing.player_facing(["mod/src/main/java/ua/zentix/incoming/client/fx/A.java", "mod/src/main/resources/assets/incoming/x.png",
                                               "tools/bake_fx/a/b.py", "tools/stand/stand.py", "docs/guide/README.md",
                                               "mod/src/main/java/ua/zentix/incoming/strike/S.java"]),
                         ["mod/src/main/java/ua/zentix/incoming/client/fx/A.java", "mod/src/main/resources/assets/incoming/x.png", "tools/bake_fx/a/b.py"])

    def test_launch_is_in_the_stand_version(self):
        names = {str(f.relative_to(stand.ROOT)) for f in stand.stand_files()}
        for f in ("tools/stand/client.py", "tools/stand/build.gradle", "tools/stand/settings.gradle", "tools/stand/scenes.json",
                  "tools/stand/plan.py", "mod/gradlew", "mod/gradle/wrapper/gradle-wrapper.properties",
                  "tools/stand/src/main/resources/META-INF/accesstransformer.cfg", "mod/gradle/wrapper/gradle-wrapper.jar",
                  "tools/nested_kwin.sh", "tools/nested_kwin_bus.conf"):
            self.assertIn(f, names)
        self.assertNotIn("tools/stand/results.py", names)    # разбор — не съёмка: принятый разбирается заново

    def test_java_of_the_launch_script(self):
        with tempfile.TemporaryDirectory() as d:
            jdk = Path(d) / "jdk"
            (jdk / "bin").mkdir(parents=True)
            (jdk / "release").write_text('JAVA_VERSION="21.0.7"\nIMPLEMENTOR="Eclipse Adoptium"\n')
            script = Path(d) / "client.sh"
            script.write_text(f"#!/usr/bin/env bash\ncd /x\nexec env 'A=b' '{jdk}/bin/java' '@args.txt' 'Main'\n")
            self.assertEqual(client.java_of(script), "21.0.7 Eclipse Adoptium")
            self.assertEqual(client.java_of(Path(d) / "none.sh"), "unknown")


def words(cmd):
    w = cmd.split()
    return {"n": int(w[3]), "spread": int(w[4]), "at": (float(w[6]), float(w[7])), "from": (float(w[9]), float(w[10])),
            "via": (float(w[12]), float(w[13]))}


class Plan(unittest.TestCase):
    def setUp(self):
        self.scenes = plan.load()
        self.p = plan.build(self.scenes)

    def salvos(self, p=None, launch=False):
        ids = plan.launch_ids(self.scenes)
        return [s for s in (p or self.p)["steps"] if s["do"] == "salvo" and (s["id"] in ids) == launch]

    def test_strikes_add_up_to_the_scene_totals(self):
        at_c = [s for s in self.salvos() if s["x"] == 1000.0]
        self.assertEqual([s["expect"] for s in at_c], [1, 15, 24, 60])
        self.assertEqual(sum(s["expect"] for s in at_c), 100)

    def test_one_command_per_strike_at_its_own_point_via_it(self):
        for s in self.salvos():
            self.assertEqual(len(s["cmds"]), s["expect"])
            self.assertEqual(len(s["delays"]), len(s["cmds"]))
            for c in s["cmds"]:
                w = words(c)
                self.assertEqual((w["n"], w["spread"]), (1, 0), c)
                self.assertEqual(w["via"], w["at"], c)

    def test_aim_points_fill_the_spread_disc_evenly_and_never_change(self):
        s = next(x for x in self.salvos() if x["id"] == "100")
        pts = [words(c)["at"] for c in s["cmds"]]
        r = [math.dist(p, (1000, 0)) for p in pts]
        self.assertLessEqual(max(r), 120.1)
        self.assertEqual(len(set(pts)), len(pts))
        self.assertAlmostEqual(sum(r) / len(r) / 120, 2 / 3, delta=0.03)    # равномерно по площади: средний радиус 2/3 R
        self.assertEqual(self.p, plan.build(self.scenes))                  # одни и те же у каждого прогона и версии
        sel = next(x for x in self.salvos(plan.build(self.scenes, ["first", "one", "16", "40", "100"])) if x["id"] == "100")
        self.assertEqual(sel["cmds"], s["cmds"])                          # выборка тех же сцен — те же точки

    def test_launch_points_and_cadence(self):
        gap = self.scenes["launch"]["gap_s"]
        for s in self.salvos():
            if s["id"] == "acc":
                continue
            launchers = min(s["expect"], len(self.scenes["launch"]["bearings"]))
            base = s["delays"][0]
            for i, c in enumerate(s["cmds"]):
                self.assertAlmostEqual(math.dist(words(c)["from"], (s["x"], s["z"])), 400, delta=0.2)
                self.assertAlmostEqual(s["delays"][i], base + (i // launchers) * gap)
            # пуски с одной точки — не чаще раза в gap: одна пусковая
            froms = [words(c)["from"] for c in s["cmds"]]
            for f in set(froms):
                ds = sorted(s["delays"][i] for i, x in enumerate(froms) if x == f)
                self.assertTrue(all(b - a >= gap - 1e-9 for a, b in zip(ds, ds[1:])), (s["id"], f, ds))

    def test_100_alone_still_lands_100(self):
        p = plan.build(self.scenes, ["100"])
        self.assertEqual(sum(s["expect"] for s in self.salvos(p)), 100)
        self.assertEqual(plan.windows(self.scenes, ["100"]), ["100-hit", "100/outside", "100/edge", "100/inside"])

    def test_100_impact_moment_window_from_the_first_hit_outside(self):
        steps = plan.build(self.scenes, ["100"])["steps"]
        i = next(i for i, s in enumerate(steps) if s["do"] == "impact_window")
        w = steps[i]
        self.assertEqual((w["name"], w["salvo"], w["n"], w["s"]), ("100-hit", "100", 1, 10))
        self.assertEqual(steps[i - 1]["eye"], plan.camera(self.scenes, "outside", (1000.0, 0.0), 120)[0])
        self.assertEqual(steps[i + 1]["do"], "await")                     # пока залп ещё ложится: окна «100/*» — после конца
        self.assertFalse(w["name"].startswith(gate.GATED))                # в шлагбаум не входит

    def test_launch_close_ups(self):
        launches = self.salvos(launch=True)
        self.assertEqual([s["id"] for s in launches], ["grad", "missile", "shahed"])
        steps = self.p["steps"]
        for s in launches:
            i = steps.index(s)
            site = plan._site(self.scenes, next(x["site"] for x in self.scenes["scenes"] if x["id"] == s["id"]))
            eye, look = steps[i - 1]["eye"], steps[i - 1]["look"]
            # камера в 30 м сбоку от пусковой, на высоте глаз, смотрит на неё
            self.assertAlmostEqual(math.dist((eye[0], eye[2]), site), 30)
            self.assertAlmostEqual(eye[1] - self.scenes["ground"], 1.6)
            self.assertEqual((look[0], look[2]), site)
            # цель далеко и почти поперёк взгляда: прилёты вне кадра
            target = (s["x"], s["z"])
            self.assertGreaterEqual(math.dist(target, site), 2000)
            view = (look[0] - eye[0], look[2] - eye[2])
            to_t = (target[0] - eye[0], target[1] - eye[2])
            cos = (view[0] * to_t[0] + view[1] * to_t[1]) / math.hypot(*view) / math.hypot(*to_t)
            self.assertLess(cos, math.cos(math.radians(80)))
            for c in s["cmds"]:
                self.assertIn(f"from {site[0]:g} {site[1]:g}", c)
            # окна от команды: 0–15 с и 30–40 с
            self.assertEqual([x["do"] for x in steps[i + 1:i + 8]], ["window", "shot", "wait", "wait", "window", "shot", "wait"])
            self.assertEqual((steps[i + 1]["name"], steps[i + 1]["s"]), (f"{s['id']}/start", 15))
            self.assertEqual(steps[i + 2]["name"], f"{s['id']}_15s")
            self.assertEqual(steps[i + 3]["s"] + steps[i + 4]["s"], 15)    # второе окно — с 30 с от команды
            self.assertEqual((steps[i + 5]["name"], steps[i + 5]["s"]), (f"{s['id']}/hang", 10))
            self.assertEqual(steps[i + 6]["name"], f"{s['id']}_40s")
            self.assertFalse(any(x["do"] == "await" and x["salvo"] == s["id"] for x in steps))
        grad, missile, shahed = launches
        self.assertEqual(grad["cmds"], ["incoming salvo rocket 12 0 at 2500 3000 from 0 3000"])
        self.assertEqual(missile["cmds"], ["incoming salvo missile 1 0 at 2500 6000 from 0 6000 via 2500 6000"])
        self.assertEqual(shahed["cmds"], ["incoming salvo drone 1 0 at 2500 9000 from 0 9000 via 2500 9000"] * 5)
        self.assertEqual(shahed["delays"], [0, 1.5, 3, 4.5, 6])
        # последние в плане: их прилёты не попадают в окна других сцен
        others = [i for i, x in enumerate(steps) if x["do"] == "salvo" and x["id"] not in plan.launch_ids(self.scenes)]
        self.assertLess(max(others), steps.index(grad))
        # конца не ждут — и годность этого не требует
        ev = [{"type": "gl"}, {"type": "salvo", "id": "grad"}]
        res = {"meta": {"done": {"x": 1}, "impacts": {}}, "windows": {}}
        self.assertFalse(any("grad" in b for b in results.checks(ev, res, (), None, plan.launch_ids(self.scenes))))
        self.assertTrue(any("grad" in b for b in results.checks(ev, res)))
        self.assertEqual(plan.windows(self.scenes, ["grad"]), ["grad/start", "grad/hang"])

    def test_gated_windows_are_20_s(self):
        ws = [s for s in self.p["steps"] if s["do"] == "window" and s["name"].startswith("100/")]
        self.assertEqual([w["s"] for w in ws], [20, 20, 20])

    def test_cameras(self):
        eye, look = plan.camera(self.scenes, "outside", (1000.0, 0.0), 120)
        self.assertEqual(eye, [800.0, 95, 0.0])
        eye, look = plan.camera(self.scenes, "edge", (1000.0, 0.0), 120)
        self.assertEqual(eye, [1000.0, 75, -120.0])
        eye, _ = plan.camera(self.scenes, "inside", (1000.0, 0.0), 120)
        self.assertEqual(eye, [1000.0, 69, 0.0])

    def test_waves(self):
        acc = next(s for s in self.salvos() if s["id"] == "acc")
        self.assertEqual(acc["expect"], 90)
        # волна — 10 ударов с пяти пусковых в две очереди, каждые 20 с, 3 минуты; у каждой волны — свой поворот узора
        self.assertEqual(acc["delays"], [20 * (i // 10) + 1.5 * (i % 10 // 5) for i in range(90)])
        waves = [tuple(words(c)["at"] for c in acc["cmds"][10 * k:10 * k + 10]) for k in range(9)]
        self.assertEqual(len(set(waves)), 9)

    def test_one_is_fired_with_first_but_later(self):
        steps = self.p["steps"]
        i = next(i for i, s in enumerate(steps) if s.get("id") == "first")
        self.assertEqual(steps[i + 1]["id"], "one")
        self.assertEqual(steps[i + 1]["delays"], [20])
        self.assertEqual(steps[i + 2]["do"], "impact_window")

    def test_client_keeps_drawing_after_the_last_window(self):
        # хвост: цикл видеокарты, в котором кончилось последнее окно, закрывается и доходит до стенда, пока клиент рисует
        for sel in (None, ["100"]):
            last = plan.build(self.scenes, sel)["steps"][-1]
            self.assertEqual(last, {"do": "wait", "s": self.scenes["end_tail_s"]})
        self.assertGreaterEqual(self.scenes["end_tail_s"], client.PMON_GAP_S)

    def test_far_teleport_waits_longer(self):
        tps = [s for s in self.p["steps"] if s["do"] == "tp"]
        self.assertEqual(tps[0]["settle_s"], self.scenes["settle_far_s"])
        self.assertEqual(tps[1]["settle_s"], self.scenes["settle_far_s"])    # F → C, 1 км
        self.assertEqual(tps[2]["settle_s"], self.scenes["settle_s"])

    def test_unknown_scene(self):
        with self.assertRaises(ValueError):
            plan.build(self.scenes, ["nope"])


if __name__ == "__main__":
    unittest.main()
