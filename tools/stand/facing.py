"""Видит ли игрок правку PR: пути кода клиента и звука, ресурсов и скриптов, которые эти ресурсы собирают. Такому PR
нужен стенд (stand.py pr); остальным статус «Стенд сцен» ставится сразу — «не нужен». Список один — здесь."""
import re

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
