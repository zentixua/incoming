#!/usr/bin/env python3
"""Resolve a GitHub release, verify its jar and read its changelog from the exact tag."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tomllib
import zipfile

from release_checks import valid_version


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True).strip()


def prepare(version, destination):
    version = version.removeprefix("v")
    if not version:
        version = gh("release", "view", "--json", "tagName", "--jq", ".tagName").removeprefix("v")
    if not valid_version(version):
        raise ValueError(f"Invalid release version: {version}")
    tag = f"v{version}"
    release = json.loads(gh("api", f"repos/{{owner}}/{{repo}}/releases/tags/{tag}"))
    if release["draft"]:
        raise ValueError("Only a published GitHub release can be sent to stores")
    assets = [asset for asset in release["assets"] if asset["name"] == f"incoming-{version}.jar"]
    if len(assets) != 1:
        raise ValueError(f"Expected one incoming-{version}.jar asset, found {len(assets)}")
    asset = assets[0]
    digest = asset.get("digest") or ""
    if not digest.startswith("sha256:") or len(digest) != 71 or any(c not in "0123456789abcdef" for c in digest[7:]):
        raise ValueError("GitHub release asset has no SHA256 digest; verify and re-upload it before publishing")
    subprocess.run(["git", "fetch", "--no-tags", "--depth=1", "origin", f"refs/tags/{tag}"], check=True)
    commit = subprocess.check_output(["git", "rev-parse", "FETCH_HEAD^{commit}"], text=True).strip()
    props = subprocess.check_output(["git", "show", f"{commit}:mod/gradle.properties"], text=True)
    if f"mod_version={version}" not in props.splitlines():
        raise ValueError(f"Tag {tag} does not declare mod_version={version}")
    notes = subprocess.check_output(["git", "show", f"{commit}:docs/releases/{version}.md"])
    store = json.loads(subprocess.check_output(["git", "show", f"{commit}:docs/store/modrinth.json"], text=True))
    environment = " | ".join(name + ("?" if store[side] == "optional" else "")
                             for side, name in (("client_side", "client"), ("server_side", "server"))
                             if store[side] in ("required", "optional"))
    destination.mkdir(parents=True, exist_ok=True)
    subprocess.run(["gh", "release", "download", tag, "--pattern", asset["name"], "--dir", str(destination)], check=True)
    jar = destination / asset["name"]
    sha256 = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest != f"sha256:{sha256}":
        raise ValueError("Downloaded jar SHA256 does not match the GitHub release asset")
    with zipfile.ZipFile(jar) as archive:
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())
    if not any(mod["modId"] == "incoming" and mod["version"] == version for mod in metadata["mods"]):
        raise ValueError(f"Downloaded jar metadata does not match Incoming {version}")
    (destination / "changelog.md").write_bytes(notes)
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"version={version}\njar={jar}\nenvironment={environment}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
        output.write(f"Incoming {version}: jar from GitHub {tag}, SHA256 `{sha256}`.\n\n"
                     f"Changelog: `docs/releases/{version}.md` at `{commit}`.\n\n"
                     f"Store page input (only when requested): workflow revision `{os.environ['GITHUB_SHA']}`.\n")


if __name__ == "__main__":
    try:
        prepare(os.environ.get("VERSION", ""), Path("dist"))
    except (ValueError, KeyError, StopIteration, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"::error::{error}") from error
