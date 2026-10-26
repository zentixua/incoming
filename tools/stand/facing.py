"""Whether a player can see/hear a PR change: client/sound code paths, resources and scripts generating them.
Such PRs need the scene bench (stand.py pr); others immediately get "Стенд сцен": "not needed". This is the single list."""
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
    """Convert a git path glob to regex: `**/` means any directories or none, `**` any text, `*` within one directory."""
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
    """Paths from `paths` affecting what players see/hear, in original order."""
    return [p for p in paths if any(r.match(p) for r in _PATTERNS)]
