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
    if step == "draft":
        # The draft step only writes the private Modrinth page; CurseForge is never contacted.
        if not modrinth:
            raise ValueError("The draft step requires MODRINTH_TOKEN")
        return True, False
    cf = [bool(env.get(key)) for key in ("CURSEFORGE_TOKEN", "CURSEFORGE_ID", "CURSEFORGE_API_KEY")]
    if cf[1] and not all(cf):
        raise ValueError("CurseForge is partially configured: set CURSEFORGE_TOKEN, CURSEFORGE_ID and "
                         "CURSEFORGE_API_KEY (read API key for duplicate checks), or unset CURSEFORGE_ID")
    if all(cf) and not re.fullmatch(r"[1-9][0-9]*", env["CURSEFORGE_ID"]):
        raise ValueError("CURSEFORGE_ID must be a positive numeric project ID")
    if not modrinth and not all(cf):
        raise ValueError("No store is configured")
    return modrinth, all(cf)


def git(*args, root=ROOT):
    return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()


# Commit status a release candidate gets from the scene bench when it holds the performance target
# (tools/stand/stand.py release --post; tools/stand/README.md, "Performance target")
TARGET_STATUS = "Цель производительности"
# Commits back from HEAD searched for a measured one with the same mod/ tree (a notes-only commit on top of it)
TARGET_DEPTH = 30


def target_status(sha, repo):
    """Latest state of the performance-target status on a commit (None: never posted)."""
    out = subprocess.check_output(["gh", "api", f"repos/{repo}/commits/{sha}/statuses", "--jq",
                                   f'[.[] | select(.context == "{TARGET_STATUS}")][0].state // ""'], text=True).strip()
    return out or None


def pr_commits(sha, repo):
    """Commits of the pull requests that brought a commit into its branch, newest first: a squash merge is a new commit
whose measured PR head is not its ancestor."""
    out = []
    for n in subprocess.check_output(["gh", "api", f"repos/{repo}/commits/{sha}/pulls", "--jq", ".[].number"],
                                     text=True).split():
        shas = subprocess.check_output(["gh", "api", "--paginate", f"repos/{repo}/pulls/{n}/commits", "--jq", ".[].sha"],
                                       text=True).split()
        out += reversed(shas)
    return out


def mod_tree(sha, repo):
    """mod/ tree of a commit on GitHub (a PR commit is not in the shallow checkout)."""
    root = subprocess.check_output(["gh", "api", f"repos/{repo}/commits/{sha}", "--jq", ".commit.tree.sha"], text=True).strip()
    return subprocess.check_output(["gh", "api", f"repos/{repo}/git/trees/{root}", "--jq",
                                    '.tree[] | select(.path == "mod") | .sha'], text=True).strip() or None


def performance_target(root=ROOT, repo=None, status=target_status, prs=pr_commits, tree_of=mod_tree):
    """The jar being released held the performance target: a commit whose mod/ tree (the jar's sources, version
included) equals HEAD's carries a successful "Цель производительности" status. Candidates, newest first: HEAD's
first-parent commits while their mod/ tree is HEAD's (a notes-only commit on top of the measured one), each followed
by the commits of the pull request that brought it (a squash merge: the measured PR head is not an ancestor). The
newest result decides: a failure on a newer candidate wins; a measured older tree does not count."""
    repo = repo or os.environ.get("GITHUB_REPOSITORY", "zentixua/incoming")
    head = git("rev-parse", "HEAD", root=root)
    # Deepen only the released commit's history: a bare remote fetch would bring every branch of origin
    subprocess.run(["git", "-C", str(root), "fetch", "--no-tags", f"--deepen={TARGET_DEPTH}", "origin", head],
                   check=False, capture_output=True)
    tree = git("rev-parse", "HEAD:mod", root=root)
    seen = set()

    def candidates():
        for sha in git("rev-list", "--first-parent", f"--max-count={TARGET_DEPTH}", "HEAD", root=root).split():
            if git("rev-parse", f"{sha}:mod", root=root) != tree:
                return
            yield sha
            for c in prs(sha, repo):
                if c not in seen and tree_of(c, repo) == tree:
                    yield c

    for sha in candidates():
        if sha in seen:
            continue
        seen.add(sha)
        state = status(sha, repo)
        if state == "success":
            return sha
        if state:
            raise ValueError(f"Performance target {state} on {sha[:12]}: this jar must not ship "
                             "(tools/stand/README.md, \"Performance target\")")
    raise ValueError("No performance target result for this jar: run tools/stand/stand.py release ci:<SHA> --post on the "
                     "release commit before releasing (tools/stand/README.md, \"Performance target\")")


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
    measured = performance_target(root)
    print(f"Release preflight: PASS (v{version}, protocol {protocol}, previous {previous}, performance target on {measured[:12]})")


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
            print(f"{name}: {'configured' if enabled else 'disabled'}")
        if args.step == "draft":
            print("CurseForge is skipped in draft mode; only the private Modrinth page is updated.")
        elif not curseforge:
            warning = "CurseForge is disabled: CURSEFORGE_ID is not set. Modrinth publishing can continue."
            print(f"::warning::{warning}")
            if os.environ.get("GITHUB_STEP_SUMMARY"):
                with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
                    output.write(f"\n**Warning:** {warning}\n")
    else:
        release_preflight()


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"::error::{error}") from error
