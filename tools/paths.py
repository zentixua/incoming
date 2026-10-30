"""Project and game paths — the only place they are defined.

Overridden by environment variables:
  PRISM_DIR  Prism Launcher data folder
  MC_DIR     .minecraft folder of the instance

From shell:  python3 tools/paths.py MC
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(ROOT, "mod")
DIST = os.path.join(ROOT, "dist")

INSTANCE = "Open Skies"  # the host's Open Skies pack instance folder (before 22.10 — “Airstrike Pack”)
PRISM = os.path.expanduser(os.environ.get("PRISM_DIR", "~/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"))
MC = os.environ.get("MC_DIR", os.path.join(PRISM, "instances", INSTANCE, "minecraft"))
MODS = os.path.join(MC, "mods")
JAVA = os.path.join(PRISM, "java", "java-runtime-delta")
# deploy.sh moves old mod versions and copies of the datapack/sound pack here; only Artem deletes them
BACKUP = os.path.join(MC, "incoming-backup")

# old datapack (airstrike, shahed) and sound pack: their copies are not needed in the game
LEGACY_DATAPACKS = "airstrike shahed"
LEGACY_RESOURCEPACKS = "Airstrike Sounds|Shahed Sounds"

if __name__ == "__main__":
    print(globals()[sys.argv[1]])
