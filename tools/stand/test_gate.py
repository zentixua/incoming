"""Шлагбаум, разбор и план стенда без игры: python3 -m unittest discover -s tools/stand -p 'test_*.py' (CI «Стенд сцен»)."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

import gate
import plan
import results
import stand


def window(gpu50, iv95, fps=60.0):
    return {"fps": fps, "frames": 600, "gpu": {"p50": gpu50, "p95": gpu50 * 1.2}, "cpu": {"p50": iv95 * 0.3, "p95": iv95 * 0.5},
            "interval": {"p50": iv95 * 0.8, "p95": iv95}, "stages": {}}


def run(scale=1.0, first=40.0, growth=1.10, packs=("none", "complementary")):
    """Прогон: окна 16/40/100 × камеры, первый удар, накопление; scale — во сколько раз дороже кадр."""
    p = {}
    for pack in packs:
        ws = {}
        for n, base in (("16", 5.0), ("40", 8.0), ("100", 12.0)):
            for cam in ("outside", "edge", "inside"):
                ws[f"{n}/{cam}"] = window(base * scale, base * 1.5 * scale)
        p[pack] = {"windows": ws, "first": {"worst_ms": first}, "acc": {"growth": {"ratio": growth, "first_ms": 10, "last_ms": 10 * growth}},
                   "checks": [], "meta": {"gl": {"renderer": "RTX 4060", "version": "4.6 NVIDIA 615.78.08", "width": 2560, "height": 1600}}}
    return {"name": "x", "label": "x", "packs": p}


class Tolerance(unittest.TestCase):
    def test_floor_is_five_percent(self):
        tol = gate.tolerance(run(), run())
        self.assertEqual(tol["none"]["windows"]["100/edge"]["gpu.p50"], gate.MIN_TOL)
        self.assertEqual(tol["none"]["acc"], gate.MIN_TOL)

    def test_three_times_the_spread(self):
        a, b = run(), run()
        b["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"] = 12.0 * 1.04   # разброс 4 % → допуск 12 %
        tol = gate.tolerance(a, b)
        self.assertAlmostEqual(tol["none"]["windows"]["100/edge"]["gpu.p50"], 3 * 0.48 / 12.24, places=6)


class Compare(unittest.TestCase):
    def test_same_version_passes(self):
        a, b = run(), run(scale=1.03)
        v = gate.compare(b, gate.mean_runs(a, b), gate.tolerance(a, b))
        self.assertTrue(v["pass"], v["fails"])

    def test_slower_at_100_fails(self):
        v = gate.compare(run(scale=1.3), run(), gate.tolerance(run(), run()))
        self.assertFalse(v["pass"])
        self.assertTrue(any("100/" in f and "gpu.p50" in f for f in v["fails"]))
        self.assertTrue(any("100/" in f and "cpu.p95" in f for f in v["fails"]))
        # кадр p95 — в отчёте, не в шлагбауме (спека: видеокарта p50 и процессор p95)
        self.assertFalse(any("interval.p95" in f for f in v["fails"]))
        self.assertTrue(any(r["metric"] == "interval.p95" and not r["gated"] for r in v["rows"]))

    def test_frame_interval_alone_does_not_fail(self):
        c = run()
        c["packs"]["none"]["windows"]["100/edge"]["interval"]["p95"] *= 1.5
        self.assertTrue(gate.compare(c, run(), None)["pass"])

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
        v = gate.compare(c, run(), gate.tolerance(run(), run()))
        self.assertEqual(len(v["fails"]), 1)
        self.assertIn("complementary 100/inside gpu.p50", v["fails"][0])

    def test_within_noise_tolerance_passes(self):
        a, b = run(), run()
        b["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"] *= 1.05       # шум 5 % → допуск ~15 %
        c = run()
        c["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"] *= 1.12
        self.assertTrue(gate.compare(c, gate.mean_runs(a, b), gate.tolerance(a, b))["pass"])

    def test_cpu_regression_hidden_by_gpu_bound_frame_fails(self):
        c = run()
        c["packs"]["none"]["windows"]["100/edge"]["cpu"]["p95"] *= 1.5     # кадр упирается в видеокарту, интервал прежний
        v = gate.compare(c, run(), None)
        self.assertEqual(v["fails"], [f for f in v["fails"] if "cpu.p95" in f])
        self.assertEqual(len(v["fails"]), 1)

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
        b["packs"]["none"]["windows"]["100/edge"]["cpu"] = None
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


class Look(unittest.TestCase):
    def test_flags(self):
        c = {"none": {"100_edge": {"smoke": 0.40, "luma": 0.50}, "16_edge": {"smoke": 0.20, "luma": 0.50}}}
        b = {"none": {"100_edge": {"smoke": 0.20, "luma": 0.50}, "16_edge": {"smoke": 0.22, "luma": 0.40}}}
        f = gate.look_flags(c, b)
        self.assertEqual(len(f), 2)
        self.assertIn("100_edge", f[0])
        self.assertIn("яркость", f[1])


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

    def test_analyze_cuts_windows_and_drops_shot_frames(self):
        with tempfile.TemporaryDirectory() as d:
            d = Path(d)
            rows = ["frame,t_ms,prev_interval_ms,cpu_ms,gpu_ms,prev_gpu_period_ms,shot,stages"]
            for i in range(200):          # 10 мс кадр, 2 с; кадр 50 — со снимком (он сам длится 500 мс), 51 — после него
                shot = 1 if i == 50 else 2 if i == 51 else 0
                prev = 500.0 if i == 51 else 30.0 if i == 100 else 10.0    # кадр 99 длится 30 мс: худший в окне 100/edge
                rows.append(f"{i},{1000 + 10 * i},{prev},{4.0},{8.0},{10.0},{shot},after_level=1.5;after_level+=3.0;tail=0.5")
            (d / "frames.csv").write_text("\n".join(rows) + "\n")
            ev = [{"type": "gl", "t_ms": 900, "renderer": "x", "version": "4.6", "vendor": "n", "width": 2560, "height": 1600},
                  {"type": "window", "t_ms": 1000, "name": "100/edge", "edge": "begin"},
                  {"type": "window", "t_ms": 2000, "name": "100/edge", "edge": "end"},
                  {"type": "window", "t_ms": 2000, "name": "first", "edge": "begin"},
                  {"type": "window", "t_ms": 3000, "name": "first", "edge": "end"},
                  {"type": "srv", "t_ms": 1500, "mspt": 12.0, "game_time": 100, "projectiles": 0, "entities": 5},
                  {"type": "probe", "t_ms": 1500, "name": "ua.zentix.incoming.client.fx.layer.FxLayer#describeTiming", "value": "layer 3 ms"},
                  {"type": "await", "t_ms": 900, "salvo": "100", "why": "all", "impacts": 100, "expect": 100, "took_s": 60},
                  {"type": "done", "t_ms": 3100, "frames": 200}]
            (d / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev) + "\n")
            r = results.analyze(d, plan.load(), ["first", "100"])
            w = r["windows"]["100/edge"]
            self.assertEqual(w["frames"], 98)            # кадры 0–99 без снимка и следующего
            # длительность кадра 99 (начат в окне) — из строки кадра 100, за концом окна; снимок (500 мс) не в счёт
            self.assertEqual(w["interval"]["max"], 30.0)
            self.assertEqual(w["gpu"]["p50"], 8.0)
            self.assertEqual(w["bound"], "cpu")          # видеокарта 8 мс из 10 мс кадра — меньше 85 %
            self.assertEqual(w["stages"]["after_level+"]["mean"], 3.0)
            self.assertEqual(w["mspt"]["mean"], 12.0)
            self.assertEqual(w["probes"]["FxLayer.describeTiming"], "layer 3 ms")
            # первый удар: кадр 99 начат до попадания (1990 мс) и шёл в его миг (до 2020 мс) — его 30 мс и есть худший
            self.assertEqual(r["first"]["worst_ms"], 30.0)
            # окна плана обязательны: 100/outside и 100/inside не сняты
            self.assertEqual(r["checks"], ["окно 100/outside: не снято", "окно 100/inside: не снято"])

    def test_checks_catch_a_bad_run(self):
        ev = [{"type": "cmd", "t_ms": 1, "cmd": "incoming salvo drone 30 120 at 1000 0 from 800 346", "tag": "100", "ok": False, "result": 0},
              {"type": "limit", "t_ms": 2, "step": 3},
              {"type": "salvo", "t_ms": 3, "id": "100", "expect": 100, "commands": 4},
              {"type": "salvo", "t_ms": 4, "id": "acc", "expect": 120, "commands": 12}]
        res = {"meta": {"done": None, "impacts": {"100": {"got": 40, "expect": 100, "why": "timeout", "took_s": 300}}},
               "windows": {"100/edge": {"frames": 5, "gpu": None}}}
        bad = results.checks(ev, res)
        # нет done, предел, команда, залп 100 не долетел, у acc нет конца, нет строки видеокарты, окно без меток и без кадров
        self.assertEqual(len(bad), 8, bad)
        self.assertTrue(any("acc" in b for b in bad))


def raw_run(d, name="r", gpu=8.0, renderer="RTX 4060", done=True, label="v3.7.0"):
    """Сырые данные прогона выборки «100» (пак none): meta.json, none/frames.csv и none/events.jsonl — как пишет стенд."""
    d = Path(d)
    (d / "none").mkdir(parents=True)
    rows = ["frame,t_ms,prev_interval_ms,cpu_ms,gpu_ms,prev_gpu_period_ms,shot,stages"]
    for i in range(400):
        rows.append(f"{i},{1000 + 10 * i},10.0,4.0,{gpu},10.0,0,after_level=1.0")
    (d / "none" / "frames.csv").write_text("\n".join(rows) + "\n")
    ev = [{"type": "gl", "t_ms": 900, "renderer": renderer, "version": "4.6", "vendor": "n", "width": 2560, "height": 1600},
          {"type": "salvo", "t_ms": 950, "id": "100", "expect": 100, "commands": 10},
          {"type": "await", "t_ms": 990, "salvo": "100", "why": "all", "impacts": 100, "expect": 100, "took_s": 30}]
    for k, cam in enumerate(("outside", "edge", "inside")):
        ev += [{"type": "window", "t_ms": 1000 + 1000 * k, "name": f"100/{cam}", "edge": "begin"},
               {"type": "window", "t_ms": 2000 + 1000 * k, "name": f"100/{cam}", "edge": "end"}]
    if done:
        ev.append({"type": "done", "t_ms": 5100, "frames": 400})
    (d / "none" / "events.jsonl").write_text("\n".join(json.dumps(e) for e in ev) + "\n")
    ctx = {"stand": "s", "mods": {}, "packs": {"none": "none"}, "scenes": ["100"], "gpu": "g", "driver": "d", "size": "2560x1600", "java": "21"}
    (d / "meta.json").write_text(json.dumps({"name": name, "label": label, "jar": {"file": "j", "sha256": "x", "spec": label},
                                             "context": ctx, "key": "k", "started": "t",
                                             "packs": {"none": {"exit": 0, "gpu_minutes": 1.0, "file": None}}}))
    return d


class Checks(unittest.TestCase):
    def acc_events(self, late_ms=100, after=0, first=30, last=30, cmds=24):
        """Накопление: окно 0–240 с, попаданий first в первую минуту, last в последнюю, after — после окна."""
        scene = next(s for s in plan.load()["scenes"] if s.get("waves"))
        ev = [{"type": "window", "t_ms": 0, "name": "acc", "edge": "begin"}, {"type": "window", "t_ms": 240_000, "name": "acc", "edge": "end"},
              {"type": "salvo", "t_ms": -10_000, "id": "acc", "expect": 120, "commands": 24}]
        ev += [{"type": "cmd", "t_ms": 20_000 * (i // 2), "tag": "acc", "ok": True, "cmd": "c", "sent_ms": 20_000 * (i // 2) + late_ms,
                "due_ms": 20_000 * (i // 2)} for i in range(cmds)]
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

    def test_missing_wave_commands_fail(self):
        bad = self.acc_checks(cmds=20)
        self.assertTrue(any("команд волн 20 из 24" in b for b in bad), bad)

    def test_thin_last_minute_fails(self):
        bad = self.acc_checks(last=15)
        self.assertTrue(any("последнюю минуту окна 15" in b for b in bad), bad)

    def test_every_plan_window_is_required(self):
        ev = [{"type": "gl"}, {"type": "done"}, {"type": "window", "name": "100/edge", "edge": "begin", "t_ms": 1}]
        res = {"meta": {"done": {"x": 1}, "impacts": {}}, "windows": {"100/outside": {"frames": 500, "gpu": {"p50": 1}}}}
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


class Calibration(unittest.TestCase):
    def test_good_pair(self):
        cal = gate.calibrate(run(), run(scale=1.03))
        self.assertEqual(cal["problems"], [])
        self.assertTrue(cal["self_check"]["pass"])

    def test_invalid_second_run_is_rejected(self):
        b = run()
        b["packs"]["complementary"]["checks"] = ["залп 100: 59 попаданий из 60 (quiet)"]
        self.assertTrue(any("не годен" in x for x in gate.calibrate(run(), b)["problems"]))

    def test_other_gpu_or_context_is_rejected(self):
        b = run()
        b["packs"]["none"]["meta"]["gl"]["renderer"] = "Intel"
        self.assertTrue(any("renderer" in x for x in gate.calibrate(run(), b)["problems"]))
        a, b = run(), run()
        a["context"], b["context"] = {"driver": "1"}, {"driver": "2"}
        self.assertTrue(any("сняты иначе: driver" in x for x in gate.calibrate(a, b)["problems"]))

    def test_failed_self_check_is_rejected(self):
        cal = gate.calibrate(run(first=90), run(first=250))
        self.assertFalse(cal["self_check"]["pass"])
        self.assertTrue(any("второй прогон против первого" in x for x in cal["problems"]))


class Cache(unittest.TestCase):
    """Кэш принятого читается заново из сырых данных и только годный (stand.cached)."""

    def test_valid_cache(self):
        with tempfile.TemporaryDirectory() as d:
            raw_run(Path(d) / "run1", "a")
            raw_run(Path(d) / "run2", "b", gpu=8.2)
            cal = stand.cached(Path(d))
            self.assertAlmostEqual(cal["base"]["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"], 8.1)
            self.assertEqual(cal["base"]["packs"]["none"]["checks"], [])

    def test_invalid_second_run_rejects_the_cache(self):
        with tempfile.TemporaryDirectory() as d:
            raw_run(Path(d) / "run1", "a")
            raw_run(Path(d) / "run2", "b", done=False)
            with self.assertRaises(SystemExit) as e:
                stand.cached(Path(d))
            self.assertIn("нет события done", str(e.exception))

    def test_second_run_on_another_gpu_rejects_the_cache(self):
        with tempfile.TemporaryDirectory() as d:
            raw_run(Path(d) / "run1", "a")
            raw_run(Path(d) / "run2", "b", renderer="Intel")
            with self.assertRaises(SystemExit):
                stand.cached(Path(d))

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
                  "tools/stand/src/main/resources/META-INF/accesstransformer.cfg"):
            self.assertIn(f, names)
        self.assertNotIn("tools/stand/results.py", names)    # разбор — не съёмка: принятый разбирается заново

    def test_java_of_the_launch_script(self):
        import client
        with tempfile.TemporaryDirectory() as d:
            jdk = Path(d) / "jdk"
            (jdk / "bin").mkdir(parents=True)
            (jdk / "release").write_text('JAVA_VERSION="21.0.7"\nIMPLEMENTOR="Eclipse Adoptium"\n')
            script = Path(d) / "client.sh"
            script.write_text(f"#!/usr/bin/env bash\ncd /x\nexec env 'A=b' '{jdk}/bin/java' '@args.txt' 'Main'\n")
            self.assertEqual(client.java_of(script), "21.0.7 Eclipse Adoptium")
            self.assertEqual(client.java_of(Path(d) / "none.sh"), "unknown")


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

    def test_commands_split_evenly_and_match_expect(self):
        for s in self.salvos():
            counts = [int(c.split()[3]) for c in s["cmds"]]
            self.assertTrue(all(n <= min(self.scenes["salvo_max"], self.scenes["salvo_split"]) for n in counts), s["cmds"])
            self.assertLessEqual(max(counts) - min(counts), 1 if "waves" not in s["id"] else 0)
            self.assertEqual(sum(counts), s["expect"])
            self.assertEqual(len(s["delays"]), len(s["cmds"]))

    def test_each_command_from_its_own_point_via_the_target(self):
        for s in self.salvos():
            froms = [c.split(" from ")[1].split(" via ")[0] for c in s["cmds"]]
            if s["id"] != "acc":
                self.assertEqual(len(set(froms)), len(froms), s["cmds"])
            for c in s["cmds"]:
                self.assertTrue(c.endswith(f" via {s['x']:g} {s['z']:g}"), c)

    def test_launch_points_are_400_m_from_the_target(self):
        for s in self.salvos():
            for c in s["cmds"]:
                w = c.split()
                fx, fz = float(w[w.index("from") + 1]), float(w[w.index("from") + 2])
                self.assertAlmostEqual(((fx - s["x"]) ** 2 + (fz - s["z"]) ** 2) ** 0.5, 400, delta=0.2)

    def test_100_alone_still_lands_100(self):
        p = plan.build(self.scenes, ["100"])
        self.assertEqual(sum(s["expect"] for s in self.salvos(p)), 100)
        self.assertEqual(plan.windows(self.scenes, ["100"]), ["100/outside", "100/edge", "100/inside"])

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
        # волна — две пусковые по 5, каждые 20 с
        self.assertEqual(acc["delays"], [20 * (i // 2) for i in range(24)])
        self.assertTrue(all(int(c.split()[3]) == 5 for c in acc["cmds"]))

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
    def test_mean_runs_averages_numbers(self):
        a, b = run(), run(scale=1.1)
        m = gate.mean_runs(a, b)
        self.assertAlmostEqual(m["packs"]["none"]["windows"]["100/edge"]["gpu"]["p50"], 12.0 * 1.05)
        self.assertEqual(copy.deepcopy(a), a)    # входы не меняются


if __name__ == "__main__":
    unittest.main()
