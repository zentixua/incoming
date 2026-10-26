#!/usr/bin/env python3
"""Verify a release against its successful CI build before preparing any store upload."""
from datetime import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tomllib
import zipfile

from release_checks import valid_version


def gh(*args):
    return subprocess.check_output(["gh", *args], text=True).strip()


def api_pages(path, key):
    pages = json.loads(gh("api", "--paginate", "--slurp", f"repos/{{owner}}/{{repo}}/{path}"))
    return [item for page in pages for item in page[key]]


def verify_build(release, asset, commit, sha256):
    """Use GitHub's recorded release job and immutable run artifact, not release bytes alone.

    The stored target SHA detects moved tags. Bot ownership and the publication/upload timestamps
    bind this release to its creating job, including when several dispatches checked the same SHA.
    Missing/expired artifacts fail closed: the release must be verifiable before store mutations.
    """
    if release["author"]["login"] != "github-actions[bot]" or asset["uploader"]["login"] != "github-actions[bot]":
        raise ValueError("Release and jar must be created by the GitHub Actions release job")
    if release["target_commitish"] != commit:
        raise ValueError("Release tag moved: its commit differs from the release's pinned target SHA")
    runs = api_pages(f"actions/workflows/build.yml/runs?head_sha={commit}&event=workflow_dispatch&per_page=100",
                     "workflow_runs")
    for run in runs:
        if (run["head_sha"] != commit or run["event"] != "workflow_dispatch"
                or run["path"] != ".github/workflows/build.yml" or run["status"] != "completed"
                or run["conclusion"] != "success"
                or not (run["head_branch"] == "main" or run["head_branch"].startswith("claude/release-"))):
            continue
        jobs = api_pages(f"actions/runs/{run['id']}/jobs?filter=latest&per_page=100", "jobs")
        gates = [job for job in jobs if job["name"] == "Итог"]
        releases = [job for job in jobs if job["name"] == "Релиз"]
        if len(gates) != 1 or len(releases) != 1:
            continue
        if any(job["status"] != "completed" or job["conclusion"] != "success"
               or job["head_sha"] != commit for job in gates + releases):
            continue
        job = releases[0]
        start, end = (datetime.fromisoformat(job[key]) for key in ("started_at", "completed_at"))
        if not all(start <= datetime.fromisoformat(value) <= end
                   for value in (release["published_at"], asset["created_at"])):
            continue
        artifacts = api_pages(f"actions/runs/{run['id']}/artifacts?per_page=100", "artifacts")
        artifacts = [artifact for artifact in artifacts if artifact["name"] == "incoming-jar"]
        if len(artifacts) != 1 or artifacts[0]["expired"]:
            raise ValueError("Release build incoming-jar artifact is missing, ambiguous or expired")
        artifact = artifacts[0]
        if artifact["workflow_run"]["head_sha"] != commit or artifact["workflow_run"]["id"] != run["id"]:
            raise ValueError("Release build artifact belongs to a different commit or run")
        archive = subprocess.check_output(["gh", "api", f"repos/{{owner}}/{{repo}}/actions/artifacts/{artifact['id']}/zip"])
        if artifact.get("digest") != "sha256:" + hashlib.sha256(archive).hexdigest():
            raise ValueError("Release build artifact archive SHA256 does not match GitHub's digest")
        with zipfile.ZipFile(io.BytesIO(archive)) as contents:
            if contents.namelist() != [asset["name"]]:
                raise ValueError("Release build artifact must contain exactly the release jar")
            if hashlib.sha256(contents.read(asset["name"])).hexdigest() != sha256:
                raise ValueError("Release jar SHA256 differs from the successful CI build artifact")
        return run["id"]
    raise ValueError("No successful release build with a successful Итог created this release at its tag commit")


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
    run_id = verify_build(release, asset, commit, sha256)
    with zipfile.ZipFile(jar) as archive:
        metadata = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode())
    if not any(mod["modId"] == "incoming" and mod["version"] == version for mod in metadata["mods"]):
        raise ValueError(f"Downloaded jar metadata does not match Incoming {version}")
    (destination / "changelog.md").write_bytes(notes)
    with open(os.environ["GITHUB_OUTPUT"], "a") as output:
        output.write(f"version={version}\njar={jar}\nenvironment={environment}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as output:
        output.write(f"Incoming {version}: jar from GitHub {tag}, SHA256 `{sha256}`.\n\n"
                     f"Verified against `incoming-jar` from successful build run `{run_id}` (including Итог and release).\n\n"
                     f"Changelog: `docs/releases/{version}.md` at `{commit}`.\n\n"
                     f"Store page input (only when requested): workflow revision `{os.environ['GITHUB_SHA']}`.\n")


if __name__ == "__main__":
    try:
        prepare(os.environ.get("VERSION", ""), Path("dist"))
    except (ValueError, KeyError, StopIteration, OSError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        raise SystemExit(f"::error::{error}") from error
