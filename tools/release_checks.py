#!/usr/bin/env python3
"""Release preflight, store configuration and production jar checks (no game launch)."""
import argparse
import io
import os
from pathlib import Path
import re
import subprocess
import tomllib
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SEMVER = re.compile(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
                    r"(?:-(?P<pre>[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
                    r"(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?")


def valid_version(version):
    match = SEMVER.fullmatch(version)
    return bool(match and all(not (part.isdigit() and len(part) > 1 and part.startswith("0"))
                              for part in (match["pre"] or "").split(".")))


def properties(root=ROOT):
    return dict(line.split("=", 1) for line in (root / "mod/gradle.properties").read_text().splitlines()
                if "=" in line and not line.lstrip().startswith("#"))


def check_jar(path, version=None):
    with zipfile.ZipFile(path) as jar:
        names = jar.namelist()
        bad = [name for name in names if name.endswith((".java", ".wav", ".flac", ".mp4", ".vdb", ".blend",
                                                       ".ttf", ".otf", ".log", ".py", ".sh"))
               or re.search(r"ua/zentix/incoming/(gametest|scenario|rig|stress)/", name)]
        if bad:
            raise ValueError(f"Development or source files in production jar: {bad}")
        for name in ("LICENSE", "META-INF/LICENSE-FastNoiseLite.txt", "META-INF/ASSET-NOTICES.txt",
                     "incoming.png", "assets/incoming/sounds/credits.txt"):
            if name not in names:
                raise ValueError(f"Missing jar resource: {name}")
        metadata = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode())
        mod = next(mod for mod in metadata["mods"] if mod["modId"] == "incoming")
        if version and mod["version"] != version:
            raise ValueError(f"Jar version {mod['version']} does not match {version}")
        if metadata["license"] != "GPL-3.0-only":
            raise ValueError("Unexpected mod license")
        credits = jar.read("assets/incoming/sounds/credits.txt").decode()
        for line in credits.splitlines():
            if "https://freesound.org/s/" in line and "https://creativecommons.org/" not in line:
                raise ValueError(f"Missing direct sound license URI: {line}")
        nested = [name for name in names if name.startswith("META-INF/jarjar/") and name.endswith(".jar")]
        if len(nested) != 1 or "sable-companion" not in nested[0]:
            raise ValueError(f"Unexpected bundled libraries: {nested}")
        with zipfile.ZipFile(io.BytesIO(jar.read(nested[0]))) as companion:
            if "LICENSE" not in companion.namelist():
                raise ValueError("Missing sable-companion license")
    print(f"Production jar contents and metadata: PASS ({Path(path).name})")


def store_config(step, env=os.environ):
    modrinth = bool(env.get("MODRINTH_TOKEN"))
    cf = [bool(env.get(key)) for key in ("CURSEFORGE_TOKEN", "CURSEFORGE_ID", "CURSEFORGE_API_KEY")]
    if any(cf) and not all(cf):
        raise ValueError("CurseForge is partially configured: set CURSEFORGE_TOKEN, CURSEFORGE_ID and "
                         "CURSEFORGE_API_KEY (read API key for duplicate checks), or remove all three")
    if all(cf) and not re.fullmatch(r"[1-9][0-9]*", env["CURSEFORGE_ID"]):
        raise ValueError("CURSEFORGE_ID must be a positive numeric project ID")
    if step == "draft" and not modrinth:
        raise ValueError("The draft step requires MODRINTH_TOKEN")
    if not modrinth and not all(cf):
        raise ValueError("No store is configured")
    return modrinth, all(cf)


def git(*args, root=ROOT):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


def release_preflight(root=ROOT):
    version = properties(root)["mod_version"]
    if not valid_version(version):
        raise ValueError(f"mod_version is not SemVer: {version}")
    notes = root / f"docs/releases/{version}.md"
    text = notes.read_text()
    if not re.search(r"[A-Za-z]{3}", text) or not re.search(r"[А-Яа-яЁё]", text):
        raise ValueError(f"Release notes must contain English and Russian: {notes}")
    tags = git("ls-remote", "--tags", "origin", f"refs/tags/v{version}", root=root)
    if tags:
        raise ValueError(f"Release tag v{version} already exists; choose a new mod_version")
    previous = subprocess.check_output(["gh", "release", "view", "--json", "tagName", "--jq", ".tagName"],
                                       cwd=root, text=True).strip()
    subprocess.run(["git", "-C", str(root), "fetch", "--no-tags", "--depth=1", "origin",
                    f"refs/tags/{previous}"], check=True)
    protocol_path = "mod/src/main/java/ua/zentix/incoming/net/IncomingNetwork.java"
    old = git("show", f"FETCH_HEAD:{protocol_path}", root=root)
    current = (root / protocol_path).read_text()
    pattern = r'PROTOCOL\s*=\s*"([^"]+)"'
    old_protocol, protocol = (re.search(pattern, text).group(1) for text in (old, current))
    changed = git("diff", "FETCH_HEAD", "HEAD", "--", "mod/src/main/java",
                  "mod/src/main/resources/assets/incoming/lang", root=root)
    # Conservative: changes to Java or language resources need an explicit protocol decision before release.
    if changed and old_protocol == protocol:
        raise ValueError(f"Java or language resources changed since {previous}, but protocol is still {protocol}; "
                         "bump it for behavior/language changes before releasing")
    print(f"Release preflight: PASS (v{version}, protocol {protocol}, previous {previous})")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    jar = sub.add_parser("jar")
    jar.add_argument("path", type=Path)
    jar.add_argument("--version")
    config = sub.add_parser("stores")
    config.add_argument("step", choices=("draft", "submit", "release"))
    sub.add_parser("preflight")
    args = parser.parse_args()
    if args.command == "jar":
        check_jar(args.path, args.version)
    elif args.command == "stores":
        modrinth, curseforge = store_config(args.step)
        for name, enabled in (("Modrinth", modrinth), ("CurseForge", curseforge)):
            print(f"{name}: {'configured' if enabled else 'disabled (no credentials)'}")
    else:
        release_preflight()


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"::error::{error}") from error
