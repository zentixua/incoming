#!/usr/bin/env python3
"""Проверка «Записки» в описании PR (CI, .github/workflows/note.yml).

PR, который меняет то, что игрок видит или слышит (пути — PLAYER_FACING), несёт в описании раздел `## Записка`:
какой закон взят, где он верен, что видно и слышно в ключевые моменты. Правке, которую можно описать одной фразой, —
одна строка. Шаблон — .github/PULL_REQUEST_TEMPLATE.md.

  python3 tools/pr_note.py < файлы-PR      # описание — в переменной PR_BODY; код 1 — раздела нет или он пуст
"""
import os
import re
import sys

# Что видит или слышит игрок: код клиента и звука, ресурсы, скрипты, которые эти ресурсы собирают. Список один — здесь.
PLAYER_FACING = (
    "mod/src/main/java/**/client/**",
    "mod/src/main/java/**/sound/**",
    "mod/src/main/resources/assets/**",
    "tools/build_sounds.py",
    "tools/synth_mod_sounds.py",
    "tools/freesound.py",
    "tools/gen_textures.py",
    "tools/gen_particles.py",
    "tools/gen_models.py",
    "tools/gen_grid_assets.py",
    "tools/bake_fx/**",
)

TEMPLATE = ".github/PULL_REQUEST_TEMPLATE.md"
HEADING = "Записка"
# Содержимое раздела, которое ничего не говорит: заглушки, оставленные вместо текста.
PLACEHOLDERS = {"нет", "-", "—", "–", "n/a", "na", "none", "todo", "tbd", "...", "…"}


def _glob(pattern: str) -> re.Pattern:
    """Глоб путей git в регулярку: `**/` — любые папки (и ни одной), `**` — что угодно, `*` — внутри одной папки."""
    out, i = [], 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out.append("(?:.*/)?")
            i += 3
        elif pattern.startswith("**", i):
            out.append(".*")
            i += 2
        elif pattern[i] == "*":
            out.append("[^/]*")
            i += 1
        else:
            out.append(re.escape(pattern[i]))
            i += 1
    return re.compile("".join(out) + r"\Z")


_PATTERNS = [_glob(p) for p in PLAYER_FACING]


def player_facing(paths) -> list:
    """Пути из `paths`, которые меняют то, что игрок видит или слышит, в исходном порядке."""
    return [p for p in paths if any(r.match(p) for r in _PATTERNS)]


def note(body: "str | None"):
    """Текст раздела «Записка» без комментариев HTML; None — раздела нет.

    Раздел — заголовок `##` или `###`, текст которого начинается со слова «Записка», до следующего заголовка того же
    или более высокого уровня. Заголовки внутри блоков кода не считаются."""
    lines = (body or "").replace("\r\n", "\n").split("\n")
    fence = None
    level = None
    found = []
    for line in lines:
        stripped = line.strip()
        m = re.match(r"(`{3,}|~{3,})", stripped)
        if m:
            if fence is None:
                fence = m.group(1)[0]
            elif stripped.startswith(fence * 3):
                fence = None
        h = None if fence else re.match(r"(#{1,6})\s+(.*?)\s*#*\s*$", stripped)
        if level is not None:
            if h and len(h.group(1)) <= level:
                break
            found.append(line)
        elif h and len(h.group(1)) in (2, 3) and re.match(HEADING + r"(?![\w])", h.group(2), re.IGNORECASE):
            level = len(h.group(1))
    if level is None:
        return None
    text = re.sub(r"<!--.*?(-->|\Z)", "", "\n".join(found), flags=re.DOTALL)
    return text.strip()


def meaningful(text: str) -> bool:
    """В разделе есть хоть одна строка по делу, а не заглушка."""
    for line in text.split("\n"):
        s = re.sub(r"^\s*(?:[-*+]|\d+[.)])\s+", "", line).strip().strip("*_`").strip()
        if s and s.lower().rstrip(".") not in PLACEHOLDERS and re.search(r"\w", s):
            return True
    return False


def check(paths, body: str, template_url: str = TEMPLATE):
    """(прошла ли проверка, сообщение по-русски)."""
    hit = player_facing(paths)
    if not hit:
        return True, "PR не меняет того, что видит или слышит игрок: «Записка» не нужна."
    shown = "\n".join("  " + p for p in hit[:20]) + (f"\n  … и ещё {len(hit) - 20}" if len(hit) > 20 else "")
    text = note(body)
    if text is None:
        why = "в описании нет раздела `## Записка`"
    elif not meaningful(text):
        why = "раздел `## Записка` пуст"
    else:
        return True, f"«Записка» есть; PR меняет то, что видит или слышит игрок:\n{shown}"
    return False, (f"PR меняет то, что видит или слышит игрок, а {why}.\n"
                   f"Нужно: какой закон взят, где он верен, что видно и слышно в ключевые моменты "
                   f"(правке в одну фразу — одна строка). Шаблон: {template_url}\n"
                   f"Пути:\n{shown}")


def main() -> int:
    paths = [p.strip() for p in sys.stdin.read().splitlines() if p.strip()]
    server, repo = os.environ.get("GITHUB_SERVER_URL"), os.environ.get("GITHUB_REPOSITORY")
    url = f"{server}/{repo}/blob/main/{TEMPLATE}" if server and repo else TEMPLATE
    ok, message = check(paths, os.environ.get("PR_BODY", ""), url)
    print(message)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write(("" if ok else "**Записки нет.** ") + message.replace("\n", "  \n") + "\n")
    if not ok:
        first = message.split("\n")[0] + " Шаблон: " + url
        print(f"::error title=Записка::{first}")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
