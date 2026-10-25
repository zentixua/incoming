"""Шлагбаум, разбор и план стенда без игры: python3 -m unittest discover -s tools/stand -p 'test_*.py' (CI «Стенд сцен»)."""
import copy
import json
import math
import tempfile
import unittest
from pathlib import Path

import client
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


class Tolerance(unittest.TestCase):
    def test_floor_is_five_percent(self):
        tol = gate.tolerance([run(), run(), run()])
        self.assertEqual(tol["none"]["windows"]["100/edge"]["gpu.p50"], gate.MIN_TOL)
        self.assertEqual(tol["none"]["windows"]["100/edge"]["thread.p95"], gate.MIN_TOL)
        self.assertEqual(tol["none"]["acc"], gate.MIN_TOL)

    def test_one_and_a_half_ranges(self):
        rs = [run(), run(), run()]
        for r, v in zip(rs, (12.0, 12.6, 12.3)):
            r["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"] = v
        tol = gate.tolerance(rs)
        self.assertAlmostEqual(tol["none"]["windows"]["100/edge"]["gpu.p50"], 1.5 * 0.6 / 12.3, places=6)

    def test_only_gated_metrics_get_a_tolerance(self):
        self.assertEqual(set(gate.tolerance([run(), run()])["none"]["windows"]["100/edge"]), {"gpu.p50", "thread.p95"})


class Compare(unittest.TestCase):
    def test_same_version_passes(self):
        rs = [run(), run(scale=1.03), run(scale=0.98)]
        v = gate.compare(rs[1], gate.median_runs([rs[0], rs[2]]), gate.tolerance(rs))
        self.assertTrue(v["pass"], v["fails"])

    def test_slower_at_100_fails(self):
        v = gate.compare(run(scale=1.3), run(), gate.tolerance([run(), run()]))
        self.assertFalse(v["pass"])
        self.assertTrue(any("100/" in f and "gpu.p50" in f for f in v["fails"]))
        self.assertTrue(any("100/" in f and "thread.p95" in f for f in v["fails"]))
        # настенное Pre→Post и кадр — в отчёте, не в шлагбауме: в них ожидание драйвера и вытеснение чужими задачами
        self.assertFalse(any("cpu.p95" in f or "interval.p95" in f for f in v["fails"]))
        self.assertTrue(any(r["metric"] == "cpu.p95" and not r["gated"] for r in v["rows"]))

    def test_wall_cpu_and_frame_interval_alone_do_not_fail(self):
        c = run()
        c["packs"]["none"]["windows"]["100/edge"]["interval"]["p95"] *= 1.5
        c["packs"]["none"]["windows"]["100/edge"]["cpu"]["p95"] *= 1.5
        self.assertTrue(gate.compare(c, run(), None)["pass"])

    def test_render_thread_cpu_regression_fails(self):
        c = run()
        c["packs"]["none"]["windows"]["100/edge"]["thread"]["p95"] *= 1.5     # кадр упирается в видеокарту, интервал прежний
        v = gate.compare(c, run(), None)
        self.assertEqual(len(v["fails"]), 1, v["fails"])
        self.assertIn("thread.p95", v["fails"][0])

    def test_slower_only_below_100_passes(self):
        c = run()
        for cam in ("outside", "edge", "inside"):
            c["packs"]["none"]["windows"][f"16/{cam}"] = window(50, 80)
        v = gate.compare(c, run(), None)
        self.assertTrue(v["pass"], v["fails"])
        self.assertTrue(any(r["window"] == "16/edge" and not r["gated"] for r in v["rows"]))

    def test_one_camera_one_pack_is_enough(self):
        c = run()
        c["packs"]["complementary"]["windows"]["100/inside"]["gpu"]["p50"] *= 1.2
        v = gate.compare(c, run(), gate.tolerance([run(), run()]))
        self.assertEqual(len(v["fails"]), 1)
        self.assertIn("complementary 100/inside gpu.p50", v["fails"][0])

    def test_within_noise_tolerance_passes(self):
        rs = [run(), run(), run()]
        rs[1]["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"] *= 1.05       # размах 5 % → допуск 7,5 %
        c = run()
        c["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"] *= 1.07
        self.assertTrue(gate.compare(c, gate.median_runs(rs), gate.tolerance(rs))["pass"])

    def test_candidate_without_gpu_numbers_fails(self):
        c = run()
        c["packs"]["none"]["windows"]["100/inside"]["gpu"] = None
        v = gate.compare(c, run(), None)
        self.assertFalse(v["pass"])
        self.assertIn("нет числа у кандидата", v["fails"][0])

    def test_other_gpu_or_frame_size_fails(self):
        c = run()
        c["packs"]["none"]["meta"]["gl"]["height"] = 1572
        v = gate.compare(c, run(), None)
        self.assertFalse(v["pass"])
        self.assertIn("height 1572 против 1600", v["fails"][0])

    def test_faster_passes(self):
        self.assertTrue(gate.compare(run(scale=0.5), run(), None)["pass"])

    def test_missing_gated_window_fails(self):
        c = run()
        del c["packs"]["none"]["windows"]["100/outside"]
        v = gate.compare(c, run(), None)
        self.assertFalse(v["pass"])
        self.assertIn("нет окна у кандидата", v["fails"][0])
        v = gate.compare(run(), c, None)
        self.assertIn("нет окна у принятого", v["fails"][0])

    def test_missing_gated_number_at_the_accepted_fails(self):
        b = run()
        b["packs"]["none"]["windows"]["100/edge"]["thread"] = None
        v = gate.compare(run(), b, None)
        self.assertFalse(v["pass"])
        self.assertIn("нет числа у принятого", v["fails"][0])

    def test_first_strike_without_a_number_fails(self):
        c = run()
        c["packs"]["none"]["first"] = {"worst_ms": None}
        v = gate.compare(c, run(), None)
        self.assertFalse(v["pass"])
        self.assertIn("первый удар: нет числа у кандидата", v["fails"][0])

    def test_accumulation_without_a_number_fails(self):
        c = run()
        c["packs"]["none"]["acc"]["growth"] = None
        v = gate.compare(c, run(), None)
        self.assertFalse(v["pass"])
        self.assertIn("накопление: нет числа роста у кандидата", v["fails"][0])

    def test_scenes_outside_the_selection_are_not_judged(self):
        c, b = run(), run()
        for r in (c, b):
            del r["packs"]["none"]["first"], r["packs"]["none"]["acc"]
        self.assertTrue(gate.compare(c, b, None)["pass"])

    def test_pack_without_baseline_is_noted(self):
        v = gate.compare(run(packs=("none", "photon")), run(), None)
        self.assertTrue(v["pass"])
        self.assertTrue(any("photon" in n for n in v["notes"]))

    def test_invalid_candidate_fails(self):
        c = run()
        c["packs"]["none"]["checks"] = ["залп 100: 40 попаданий из 60 (timeout)"]
        self.assertFalse(gate.compare(c, run(), None)["pass"])

    def test_first_strike_needs_double_and_100_ms(self):
        self.assertTrue(gate.compare(run(first=90), run(first=40), None)["pass"])     # вдвое, но меньше 100 мс
        self.assertTrue(gate.compare(run(first=150), run(first=90), None)["pass"])    # больше 100 мс, но не вдвое
        v = gate.compare(run(first=250), run(first=90), None)
        self.assertFalse(v["pass"])
        self.assertIn("первый удар", v["fails"][0])

    def test_accumulation_growth_beyond_accepted_fails(self):
        self.assertTrue(gate.compare(run(growth=1.60), run(growth=1.58), None)["pass"])  # растёт, но как у принятого
        v = gate.compare(run(growth=1.60), run(growth=1.10), None)
        self.assertFalse(v["pass"])
        self.assertIn("накопление", v["fails"][0])


class Calibration(unittest.TestCase):
    def runs(self):
        return [run(name="a"), run(scale=1.03, name="b"), run(scale=0.98, name="c")]

    def test_good_triple(self):
        cal = gate.calibrate(self.runs())
        self.assertEqual(cal["problems"], [])
        self.assertEqual(len(cal["self_check"]), 3)
        self.assertTrue(all(v["pass"] for v in cal["self_check"]))
        self.assertAlmostEqual(cal["base"]["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"], 12.0)   # медиана

    def test_fewer_than_three_runs(self):
        self.assertTrue(gate.calibrate([run()])["problems"])
        self.assertTrue(gate.calibrate([run(name="a"), run(name="b")])["problems"])

    def test_baseline_refuses_fewer_than_three_runs(self):
        import argparse
        with self.assertRaises(SystemExit) as e:
            stand.cmd_baseline(argparse.Namespace(version="v0", runs=2, packs=["none"], scenes=None, size="2560x1600", force=False))
        self.assertIn("не меньше 3", str(e.exception))

    def test_invalid_run_is_rejected(self):
        rs = self.runs()
        rs[2]["packs"]["complementary"]["checks"] = ["залп 100: 59 попаданий из 60 (quiet)"]
        self.assertTrue(any("c complementary: не годен" in x for x in gate.calibrate(rs)["problems"]))

    def test_other_gpu_or_context_is_rejected(self):
        rs = self.runs()
        rs[1]["packs"]["none"]["meta"]["gl"]["renderer"] = "Intel"
        self.assertTrue(any("renderer" in x for x in gate.calibrate(rs)["problems"]))
        rs = self.runs()
        rs[2]["context"] = {"driver": "616"}
        self.assertTrue(any("снят иначе, чем a: driver" in x for x in gate.calibrate(rs)["problems"]))

    def test_server_tick_apart_means_a_busy_machine(self):
        # одна версия: тик сервера в окнах шлагбаума разошёлся на 12–15 % (25.10: рядом шли чужие задачи)
        rs = [run(name="a", mspt=7.2), run(name="b", mspt=6.2), run(name="c", mspt=6.3)]
        self.assertTrue(any("тик сервера прогонов разошёлся" in x for x in gate.calibrate(rs)["problems"]))
        rs = [run(name="a", mspt=6.5), run(name="b", mspt=6.3), run(name="c", mspt=6.6)]
        self.assertEqual(gate.calibrate(rs)["problems"], [])

    def test_failed_self_check_is_rejected(self):
        cal = gate.calibrate([run(first=90, name="a"), run(first=250, name="b"), run(first=95, name="c")])
        self.assertTrue(any("b против медианы остальных" in x for x in cal["problems"]))


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
    return {"epoch": epoch, "dt": dt, "phase": "run", "load1": 1.0, "cpu_total": cpu_foreign + 4, "cpu_own": 4.0,
            "cpu_foreign": cpu_foreign, "units": units or {}, "gpu": {"foreign_sm": gpu_foreign, "who": [], "temp": 70, "clock": 2500},
            "profile": profile}


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
        r = self.analyze([host_sample(1_000_002.0, dt=2.0), host_sample(1_000_004.0, dt=2.0)])
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
        # первый удар: кадр 99 начат до попадания (1990 мс) и шёл в его миг (до 2020 мс) — его 30 мс и есть худший
        self.assertEqual(r["first"]["worst_ms"], 30.0)
        # окна плана обязательны: 100/outside и 100/inside не сняты
        self.assertEqual(r["checks"], ["окно 100/outside: не снято", "окно 100/inside: не снято"])

    def test_foreign_load_in_a_gated_window_makes_the_run_invalid(self):
        r = self.analyze([host_sample(1_000_002.0, cpu_foreign=3.1, dt=2.0, units={"incoming-job-gametest": 2.9}),
                          host_sample(1_000_004.0, dt=2.0)])
        load = [c for c in r["checks"] if c.startswith(results.LOAD_TAG) and "100/edge" in c]
        self.assertEqual(len(load), 1, r["checks"])
        self.assertIn("3.1 ядра", load[0])
        self.assertIn("incoming-job-gametest", load[0])
        r = self.analyze([host_sample(1_000_002.0, gpu_foreign=37, dt=2.0), host_sample(1_000_004.0, dt=2.0)])
        self.assertTrue(any("видеокарта у чужих процессов — 37" in c for c in r["checks"]))

    def test_load_is_checked_in_the_first_strike_window_too(self):
        r = self.analyze([host_sample(1_000_002.0, dt=2.0), host_sample(1_000_004.0, cpu_foreign=7.0, dt=2.0)])
        self.assertTrue(any(c.startswith(results.LOAD_TAG) and "окно first" in c for c in r["checks"]), r["checks"])

    def test_first_strike_frame_longer_than_its_window_still_checks_the_load(self):
        # кадр 995–6995 мс накрыл всё окно первого удара 1000–6000: кадров, начатых в окне, нет, а нагрузка — 7 ядер
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            rows = ["frame,t_ms,prev_interval_ms,cpu_ms,cpu_thread_ms,gpu_ms,prev_gpu_period_ms,shot,stages",
                    "0,985,10,4,3,8,10,0,", "1,995,10,4,3,8,10,0,", "2,6995,6000,4,3,8,10,0,"]
            (d / "frames.csv").write_text("\n".join(rows) + "\n")
            ev = [{"type": "window", "t_ms": 1000, "name": "first", "edge": "begin"}, {"type": "window", "t_ms": 6000, "name": "first", "edge": "end"},
                  {"type": "srv", "t_ms": 0, "mspt": 6.0, "game_time": 1, "projectiles": 0, "entities": 1, "epoch_ms": 0}]
            (d / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev) + "\n")
            (d / "host.jsonl").write_text(json.dumps(host_sample(7.0, cpu_foreign=7.0, gpu_foreign=37, dt=7.0)) + "\n")
            r = results.analyze(d, plan.load(), ["first"])
            self.assertEqual(r["first"]["worst_ms"], 6000.0)
            self.assertTrue(any(c.startswith(results.LOAD_TAG) and "окно first" in c for c in r["checks"]), r["checks"])

    def test_window_tail_without_samples_is_invalid(self):
        # последняя выборка кончилась на 1,1 с окна 1–2 с: хвост без записи
        r = self.analyze([host_sample(1_000_001.1, dt=1.1)])
        self.assertTrue(any("100/edge: нагрузка машины записана на 10%" in c for c in r["checks"]), r["checks"])

    def test_missing_gpu_telemetry_is_not_zero(self):
        s = host_sample(1_000_004.0, dt=4.0)
        s["gpu"] = None
        r = self.analyze([s])
        self.assertTrue(any("100/edge: видеокарта чужими процессами записана на 0%" in c for c in r["checks"]), r["checks"])

    def test_no_load_record_is_invalid_but_not_retried(self):
        r = self.analyze(None)
        bad = [c for c in r["checks"] if "100/edge" in c]
        self.assertEqual(bad, ["окно 100/edge: нет записи нагрузки машины (host.jsonl) — тишину не проверить"])
        self.assertFalse(any(c.startswith(results.LOAD_TAG) for c in r["checks"]))

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

    def test_pmon(self):
        text = """# gpu         pid   type     sm    mem    enc    dec    jpg    ofa    command
# Idx           #    C/G      %      %      %      %      %      %    name
    0       2778   C+G      -      -      -      -      -      -    kwin_wayland
    0    1503885     G     37     11      -      -      -      -    java
    0    1600000     G     52     20      -      -      -      -    java
"""
        mine = lambda pid: pid == "1600000"     # noqa: E731
        self.assertEqual(client.parse_pmon(text, mine, True), (37, ["java:37"]))
        # «-» у простаивающего чужого процесса (композитор) — 0, а не «замера нет»
        self.assertEqual(client.parse_pmon(text, lambda pid: pid == "1503885", False), (52, ["java:52"]))
        # свой клиент идёт, а числа SM нет ни у кого (и у него) — драйвер по процессам не считает: замера нет
        no_sm = text.replace("37     11", " -      -").replace("52     20", " -      -")
        self.assertIsNone(client.parse_pmon(no_sm, mine, True))
        self.assertIsNone(client.parse_pmon(text.splitlines()[0] + "\n", mine, True))      # один заголовок
        self.assertEqual(client.parse_pmon(text.splitlines()[0] + "\n", mine, False), (0, []))   # до клиента — тихо
        self.assertIsNone(client.parse_pmon("", mine, False))
        self.assertIsNone(client.parse_pmon("NVIDIA-SMI has failed\n", mine, False))


class Checks(unittest.TestCase):
    def test_load_is_checked_in_the_accumulation_minutes(self):
        scene, ev = self.acc_events()
        quiet = QUIET
        busy = dict(quiet, cpu_foreign_max=7.0, units={"incoming-job-build": 6.8})
        res = {"meta": {"done": {"x": 1}, "impacts": {}}, "windows": {},
               "acc": {"growth": {"ratio": 1.2}, "load": results.acc_load(ev, scene, 0, 240_000), "host": {"first": quiet, "last": busy}}}
        bad = [b for b in results.checks(ev + [{"type": "gl"}], res, (), scene) if b.startswith(results.LOAD_TAG)]
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
    for k, cam in enumerate(("outside", "edge", "inside")):
        ev += [{"type": "window", "t_ms": 1000 + 2000 * k, "name": f"100/{cam}", "edge": "begin"},
               {"type": "window", "t_ms": 3000 + 2000 * k, "name": f"100/{cam}", "edge": "end"}]
    ev += [{"type": "srv", "t_ms": 1000.0 * t, "mspt": mspt, "game_time": t, "projectiles": 0, "entities": 5, "epoch_ms": 1_000_000.0 * 1000 + 1000.0 * t}
           for t in range(1, 8)]
    if done:
        ev.append({"type": "done", "t_ms": 8100, "frames": 700})
    (d / "none" / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev) + "\n")
    (d / "none" / "host.jsonl").write_text("\n".join(json.dumps(host_sample(1_000_000.0 + t, dt=1.0, profile=power)) for t in range(1, 9)) + "\n")
    ctx = {"stand": "s", "mods": {}, "packs": {"none": "none"}, "scenes": ["100"], "gpu": "g", "driver": "d", "size": "2560x1600", "java": "21",
           "kwin": "6.8", "power": "performance", "gl_yield": "USLEEP"}
    (d / "meta.json").write_text(json.dumps({"name": name, "label": label, "jar": {"file": "j", "sha256": "x", "spec": label},
                                             "context": ctx, "key": "k", "started": "t",
                                             "packs": {"none": {"exit": 0, "gpu_minutes": 1.0, "file": None}}}))
    return d


class Cache(unittest.TestCase):
    """Кэш принятого читается заново из сырых данных и только годный (stand.cached)."""

    def cache(self, root, **kw):
        d = Path(root) / kw.pop("dirname", "v3.7.0-k")
        raw_run(d / "run1", "a")
        raw_run(d / "run2", "b", gpu=8.2)
        raw_run(d / "run3", "c", gpu=8.1, **kw)
        return d

    def test_valid_cache(self):
        with tempfile.TemporaryDirectory() as root:
            cal = stand.cached(self.cache(root))
            self.assertAlmostEqual(cal["base"]["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"], 8.1)     # медиана
            self.assertEqual(cal["base"]["packs"]["none"]["checks"], [])
            self.assertEqual(len(cal["self_check"]), 3)

    def test_invalid_run_rejects_the_cache(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(SystemExit) as e:
                stand.cached(self.cache(root, done=False))
            self.assertIn("нет события done", str(e.exception))

    def test_cache_under_another_key_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(SystemExit) as e:
                stand.cached(self.cache(root, dirname="v3.7.0-other"))
            self.assertIn("не под ключом", str(e.exception))

    def test_run_on_another_gpu_rejects_the_cache(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(SystemExit):
                stand.cached(self.cache(root, renderer="Intel"))

    def test_run_with_the_server_tick_apart_rejects_the_cache(self):
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(SystemExit) as e:
                stand.cached(self.cache(root, mspt=7.6))
            self.assertIn("тик сервера", str(e.exception))

    def test_power_profile_changed_mid_run_is_invalid(self):
        with tempfile.TemporaryDirectory() as root:
            d = raw_run(Path(root) / "r", power="low-power")
            r = stand.analyze(d, write=False)
            self.assertTrue(any("профиль питания low-power" in c for c in r["packs"]["none"]["checks"]), r["packs"]["none"]["checks"])

    def test_report_and_gate_share_one_verdict_path(self):
        # report.py берёт принятый и вердикт у stand (against, judge), а не из сохранённых results.json
        src = (Path(__file__).parent / "report.py").read_text()
        self.assertIn("stand.against(", src)
        self.assertIn("stand.judge(", src)
        self.assertNotIn("results.json\").read_text", src)


class Pr(unittest.TestCase):
    def test_renamed_file_counts_by_both_names(self):
        import pr_note
        rec = [{"filename": "mod/src/main/java/ua/zentix/incoming/util/Look.java",
                "previous_filename": "mod/src/main/java/ua/zentix/incoming/client/fx/Look.java"},
               {"filename": "docs/x.md", "previous_filename": None}]
        self.assertFalse(pr_note.player_facing([rec[0]["filename"]]))     # по новому имени одному — «не нужен»
        paths = stand.pr_paths(rec)
        self.assertEqual(len(paths), 3)
        self.assertTrue(pr_note.player_facing(paths))

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

    def salvos(self, p=None):
        return [s for s in (p or self.p)["steps"] if s["do"] == "salvo"]

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
        self.assertEqual(plan.windows(self.scenes, ["100"]), ["100/outside", "100/edge", "100/inside"])

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
        self.assertEqual(acc["expect"], 120)
        # волна — 10 ударов с пяти пусковых в две очереди, каждые 20 с; у каждой волны — свой поворот узора
        self.assertEqual(acc["delays"], [20 * (i // 10) + 1.5 * (i % 10 // 5) for i in range(120)])
        waves = [tuple(words(c)["at"] for c in acc["cmds"][10 * k:10 * k + 10]) for k in range(12)]
        self.assertEqual(len(set(waves)), 12)

    def test_one_is_fired_with_first_but_later(self):
        steps = self.p["steps"]
        i = next(i for i, s in enumerate(steps) if s.get("id") == "first")
        self.assertEqual(steps[i + 1]["id"], "one")
        self.assertEqual(steps[i + 1]["delays"], [20])
        self.assertEqual(steps[i + 2]["do"], "impact_window")

    def test_far_teleport_waits_longer(self):
        tps = [s for s in self.p["steps"] if s["do"] == "tp"]
        self.assertEqual(tps[0]["settle_s"], self.scenes["settle_far_s"])
        self.assertEqual(tps[1]["settle_s"], self.scenes["settle_far_s"])    # F → C, 1 км
        self.assertEqual(tps[2]["settle_s"], self.scenes["settle_s"])

    def test_unknown_scene(self):
        with self.assertRaises(ValueError):
            plan.build(self.scenes, ["nope"])


class Baseline(unittest.TestCase):
    def test_median_runs(self):
        a, b, c = run(), run(scale=1.1), run(scale=1.5)
        m = gate.median_runs([a, b, c])
        self.assertAlmostEqual(m["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"], 12.0 * 1.1)
        self.assertAlmostEqual(gate.median_runs([a, b])["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"], 12.0 * 1.05)
        self.assertEqual(copy.deepcopy(a), a)    # входы не меняются


if __name__ == "__main__":
    unittest.main()
