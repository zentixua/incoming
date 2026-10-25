"""План прогона стенда: сцены scenes.json → шаги обвязки (stand-plan.json, их исполняет мод tools/stand).

Вся геометрия здесь: где цель, откуда пуск, где камера и куда она смотрит, сколько ударов досыпать до числа и как
делить их на команды. Залп — командами не больше чем по `salvo_split` шахедов (`max_salvo` Incoming — 30), у каждой —
своя точка пуска: одна пусковая выпускает шахеды по одному раз в 1–2 с, и залп в 100 с четырёх пусковых шёл бы почти
минуту. Шахед летит через саму цель (`via`): без точки маршрута он делает петлю и заходит из-за спины стреляющего.
Обвязка только исполняет числа — поэтому план можно проверить тестом без игры (test_gate.py).

Оси Minecraft: +X — восток, +Z — юг. Направление камеры `toward` — единичный вектор (dx, dz) от места; пуск — по
пеленгу от юга к востоку (0 — с юга, 90 — с востока).
"""
import json
import math
from pathlib import Path

HERE = Path(__file__).resolve().parent


def load(path=HERE / "scenes.json"):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _site(scenes, name):
    x, z = scenes["sites"][name]
    return float(x), float(z)


def camera(scenes, spec, site, spread):
    """Глаз и точка взгляда камеры {toward, dist (число или "spread"), height, look_height, [look_toward, look_dist]}."""
    if isinstance(spec, str):
        spec = scenes["cameras"][spec]
    g = scenes["ground"]
    dist = spread if spec["dist"] == "spread" else spec["dist"]
    tx, tz = spec["toward"]
    eye = [site[0] + tx * dist, g + spec["height"], site[1] + tz * dist]
    if "look_toward" in spec:
        lx, lz = spec["look_toward"]
        look = [eye[0] + lx * spec["look_dist"], g + spec["look_height"], eye[2] + lz * spec["look_dist"]]
    else:
        look = [site[0], g + spec["look_height"], site[1]]
    return [round(v, 2) for v in eye], [round(v, 2) for v in look]


def launch_point(scenes, site, k):
    """k-я точка пуска у места: по кругу пеленгов на дальности `launch.dist`."""
    bearings = scenes["launch"]["bearings"]
    b = math.radians(bearings[k % len(bearings)])
    d = scenes["launch"]["dist"]
    return round(site[0] + d * math.sin(b), 1), round(site[1] + d * math.cos(b), 1)


def salvo_commands(scenes, site, count, spread, first_point, split=None):
    """Команды залпа: поровну, не больше `split` (по умолчанию `salvo_split`) в команде, у каждой — своя точка пуска
    (своя пусковая)."""
    split = min(split or scenes["salvo_split"], scenes["salvo_max"])
    n_cmd = -(-count // split)
    sizes = [count // n_cmd + (1 if i < count % n_cmd else 0) for i in range(n_cmd)]
    cmds, k = [], first_point
    for n in sizes:
        fx, fz = launch_point(scenes, site, k)
        cmds.append(f"incoming salvo {scenes['weapon']} {n} {spread} at {site[0]:g} {site[1]:g} from {fx:g} {fz:g} via {site[0]:g} {site[1]:g}")
        k += 1
    return cmds, k


def build(scenes, only=None):
    """План из сцен (only — ид сцен выборки, порядок — как в scenes.json)."""
    chosen = [s for s in scenes["scenes"] if only is None or s["id"] in only]
    unknown = set(only or ()) - {s["id"] for s in scenes["scenes"]}
    if unknown:
        raise ValueError(f"нет сцен: {', '.join(sorted(unknown))}")
    margin = scenes["impact_margin"]
    struck = {}     # удары у места до этой сцены
    point = {}      # следующая точка пуска у места
    steps = []
    ids = {s["id"] for s in chosen}
    launched = set()
    where = [None]  # глаз камеры перед шагом: далёкий перенос ждёт чанки и их сетки дольше

    def tp(eye, look):
        far = where[0] is None or math.dist(where[0], eye) > scenes["far_m"]
        where[0] = eye
        return {"do": "tp", "eye": eye, "look": look, "settle_s": scenes["settle_far_s"] if far else scenes["settle_s"]}

    def salvo_step(s):
        site_name = s["site"]
        site = _site(scenes, site_name)
        spread = s.get("spread", 0)
        if "waves" in s:
            w = s["waves"]
            cmds, delays = [], []
            for i in range(w["count"]):
                # волна — с нескольких пусковых (split): её удары ложатся за ~8 с, и последняя волна целиком в окне
                c, point[site_name] = salvo_commands(scenes, site, w["strikes"], spread, point.get(site_name, 0), w.get("split"))
                cmds += c
                delays += [i * w["every_s"]] * len(c)
            count = w["strikes"] * w["count"]
        else:
            count = s["strikes"] if "strikes" in s else s["total"] - struck.get(site_name, 0)
            if count <= 0:
                raise ValueError(f"сцена {s['id']}: у места {site_name} уже {struck.get(site_name, 0)} ударов")
            cmds, point[site_name] = salvo_commands(scenes, site, count, spread, point.get(site_name, 0))
            delay = s.get("launch_delay_s", 0) if s.get("launch_with") in ids else 0
            delays = [delay] * len(cmds)
        struck[site_name] = struck.get(site_name, 0) + count
        launched.add(s["id"])
        return {"do": "salvo", "id": s["id"], "x": site[0], "z": site[1], "radius": spread + margin, "expect": count,
                "cmds": cmds, "delays": delays}

    for s in chosen:
        site = _site(scenes, s["site"])
        spread = s.get("spread", 0)
        if s.get("impact_window"):
            eye, look = camera(scenes, s["camera"], site, spread)
            steps.append(tp(eye, look))
            if s["id"] not in launched:
                steps.append(salvo_step(s))
            # сцены, что пускаются вместе с этой (со сдвигом), — сразу за её залпом
            for t in chosen:
                if t.get("launch_with") == s["id"] and t["id"] not in launched:
                    steps.append(salvo_step(t))
            steps.append({"do": "impact_window", "name": s["id"], "salvo": s["id"], "n": 1, "s": s["window_s"],
                          "shots": s.get("shots", []), "prefix": s["id"], "timeout_s": scenes["await_timeout_s"]})
            # конец залпа — событием: годность прогона проверяет, что долетели все (у волн накопления — все волны)
            steps.append({"do": "await", "salvo": s["id"], "timeout_s": scenes["tail_timeout_s"], "quiet_s": scenes["quiet_s"]})
            continue
        cams = s["cameras"]
        eye, look = camera(scenes, cams[0], site, spread)
        steps.append(salvo_step(s))
        steps.append(tp(eye, look))
        steps.append({"do": "await", "salvo": s["id"], "timeout_s": scenes["await_timeout_s"], "quiet_s": scenes["quiet_s"]})
        for cam in cams:
            eye, look = camera(scenes, cam, site, spread)
            steps.append(tp(eye, look))
            steps.append({"do": "shot", "name": f"{s['id']}_{cam}"})
            steps.append({"do": "wait", "s": scenes["after_shot_s"]})
            steps.append({"do": "window", "name": f"{s['id']}/{cam}", "s": s["window_s"]})
    w = scenes["world"]
    return {"seed": w["seed"], "day_time": w["day_time"], "settle_s": w["settle_s"], "limit_s": w["limit_s"],
            "projectiles": scenes["projectiles"], "probes": scenes["probes"], "once": scenes["once"], "steps": steps}


def windows(scenes, only=None):
    """Имена окон замера плана — в порядке сцен (для отчёта и шлагбаума)."""
    out = []
    for s in scenes["scenes"]:
        if only is not None and s["id"] not in only:
            continue
        if s.get("impact_window"):
            out.append(s["id"])
        else:
            out += [f"{s['id']}/{c}" for c in s["cameras"]]
    return out


if __name__ == "__main__":
    import sys
    sel = sys.argv[1].split(",") if len(sys.argv) > 1 and sys.argv[1] != "all" else None
    print(json.dumps(build(load(), sel), indent=1, ensure_ascii=False))
