#!/usr/bin/env python3
"""Годен ли прогон шлагбаума для сравнения с эталоном — по логу клиента (latest.log сценария probe).

  python3 tools/look_gate/check.py <latest.log>   → 0 — годен; 1 — нет (причины в stderr)

Не годен, если:
  - слух оглушается (строки «SCENARIO probe hearing damage off» нет или там «on»): сцены идут подряд одним игроком, и
    глухота от взрыва в 50 м глушит хвосты следующих сцен (F2b7, F2b8 22.10);
  - сцена идёт в другом воздухе, чем в своё эталонное время игры (ProbeScenario.START): ветер и класс устойчивости —
    функция времени игры, и сцена выборки, начатая не в своё время, шла в чужом воздухе (F2b8, F2b9 22.10: дым пожара
    полосой вместо столба); проба сама сравнивает воздух (ProbeScenario.sameAir: «same», «differs», «none» — у версии
    без воздуха мира); лог без этого сравнения — по допуску LATE тиков;
  - прогон сам остановился строкой «SCENARIO probe FAIL»;
  - кадр снят не с той точки, куда сцена поставила глаз (строка «SCENARIO probe shot <кадр> camera … off <блоков>»
    дальше ProbeScenario.CAMERA_SLIP): сервер вернул игрока назад — камера сзади старта до 23.10 стояла ногами на 2 см
    в полу, и кадр 0,5 с снимался с места до старта, без пусковой и облака (LNB 23.10).
"""
import re
import sys

LATE = 200  # лог пробы без сравнения воздуха (до F4): сцена позже своего времени больше этого — другой воздух
EARLY = 5   # ProbeScenario.begin: догоняет до START − 5
SLIP = 1.0  # ProbeScenario.CAMERA_SLIP, блоков

HEARING = re.compile(r"SCENARIO probe hearing damage (on|off)")
START = re.compile(r"SCENARIO probe (\S+) game (-?\d+) \(reference start (null|-?\d+)(?:, air ([^)]*))?\)")
END = re.compile(r"SCENARIO probe (\S+) ends at game (-?\d+) \(reference end (-?\d+), air ([^)]*)\)")
FAIL = re.compile(r"SCENARIO probe FAIL (.*)")
CAMERA = re.compile(r"SCENARIO probe shot (\S+) camera (.*) off (\d+(?:\.\d+)?)")


def problems(lines):
    """Причины, по которым прогон не годится (пусто — годен)."""
    out = []
    hearing = None
    scenes = 0
    for line in lines:
        m = HEARING.search(line)
        if m:
            hearing = m.group(1)
            continue
        m = FAIL.search(line)
        if m:
            out.append("прогон остановлен: " + m.group(1).strip())
            continue
        m = CAMERA.search(line)
        if m:
            if float(m.group(3)) > SLIP:
                out.append(f"{m.group(1)}: кадр снят в {m.group(3)} блоках от места камеры сцены (сервер вернул игрока назад?)")
            continue
        m = END.search(line)
        if m:
            if m.group(4).startswith("differs"):
                out.append(f"{m.group(1)}: к концу сцены другой воздух, чем у эталона: {m.group(4)}")
            continue
        m = START.search(line)
        if m:
            scenes += 1
            case, game, ref, air = m.group(1), int(m.group(2)), m.group(3), m.group(4)
            if ref == "null":
                out.append(f"{case}: у сцены нет времени эталона (ProbeScenario.START)")
            elif game < int(ref) - EARLY - 1:
                out.append(f"{case}: началась в игре {game}, раньше эталона {ref}")
            elif air is not None:
                if air.startswith("differs"):
                    out.append(f"{case}: другой воздух, чем в эталонное время {ref} (игра {game}): {air}")
            elif game > int(ref) + LATE:
                out.append(f"{case}: началась в игре {game}, эталон {ref} — другой воздух (допуск {LATE} тиков)")
    if hearing is None:
        out.append("нет строки «SCENARIO probe hearing damage»: слух не проверен (старый класс ProbeScenario?)")
    elif hearing == "on":
        out.append("слух оглушается (nuke_tinnitus = true): хвосты сцен заглушены прошлыми взрывами")
    if scenes == 0:
        out.append("ни одна сцена не началась")
    return out


def main():
    with open(sys.argv[1], encoding="utf-8", errors="replace") as f:
        bad = problems(f)
    for p in bad:
        print("look_gate: " + p, file=sys.stderr)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
