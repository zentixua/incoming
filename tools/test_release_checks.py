"""Regression checks for release gates and publishing, using local fake APIs only."""
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
import importlib.util
import io
import itertools
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import textwrap
import threading
import unittest
from unittest.mock import patch
import zipfile

import clean_install
import prepare_publish
import release_checks

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("curseforge_check", ROOT / ".github/scripts/curseforge_check.py")
curseforge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(curseforge)


class BuildFixture:
    """GitHub responses for a release and the CI run that created it."""

    def __init__(self, jar=b"checked jar"):
        self.jar = jar
        self.sha = "a" * 40
        self.release = {"author": {"login": "github-actions[bot]"}, "target_commitish": self.sha,
                        "published_at": "2026-10-26T04:35:34Z"}
        self.asset = {"name": "incoming-1.2.3.jar", "uploader": {"login": "github-actions[bot]"},
                      "created_at": "2026-10-26T04:35:32Z"}
        self.runs = [{"id": 42, "head_sha": self.sha, "event": "workflow_dispatch", "head_branch": "main",
                      "path": ".github/workflows/build.yml", "status": "completed", "conclusion": "success"}]
        self.jobs = [{"name": name, "head_sha": self.sha, "status": "completed", "conclusion": "success",
                      "started_at": "2026-10-26T04:35:23Z", "completed_at": "2026-10-26T04:35:36Z"}
                     for name in ("Итог", "Релиз")]
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr(self.asset["name"], jar)
        self.archive = archive.getvalue()
        self.artifacts = [{"id": 99, "name": "incoming-jar", "expired": False,
                           "digest": "sha256:" + hashlib.sha256(self.archive).hexdigest(),
                           "workflow_run": {"head_sha": self.sha, "id": 42}}]

    @staticmethod
    def key(path):
        return "workflow_runs" if "/workflows/" in path else "jobs" if "/jobs?" in path else "artifacts"

    def items(self, path):
        return {"workflow_runs": self.runs, "jobs": self.jobs, "artifacts": self.artifacts}[self.key(path)]

    def verify(self, sha256=None):
        with patch.object(prepare_publish, "api_pages", side_effect=lambda path, key: self.items(path)), \
                patch("subprocess.check_output", return_value=self.archive):
            return prepare_publish.verify_build(self.release, self.asset, self.sha,
                                                sha256 or hashlib.sha256(self.jar).hexdigest())


class BuildProvenanceTests(unittest.TestCase):
    def test_checked_jar_from_successful_release_dispatch_is_accepted(self):
        self.assertEqual(42, BuildFixture().verify())

    def test_manual_release_and_clobbered_asset_are_rejected(self):
        for owner in ("release", "asset"):
            with self.subTest(owner=owner):
                fixture = BuildFixture()
                (fixture.release["author"] if owner == "release" else fixture.asset["uploader"])["login"] = "maintainer"
                with self.assertRaisesRegex(ValueError, "GitHub Actions release job"):
                    fixture.verify()

    def test_swapped_jar_is_rejected_even_with_bot_uploader_and_matching_release_digest(self):
        with self.assertRaisesRegex(ValueError, "differs from the successful CI build artifact"):
            BuildFixture().verify(hashlib.sha256(b"unchecked local jar").hexdigest())

    def test_moved_tag_is_rejected_even_if_new_commit_has_a_successful_build(self):
        fixture = BuildFixture()
        fixture.release["target_commitish"] = "b" * 40
        with self.assertRaisesRegex(ValueError, "tag moved"):
            fixture.verify()

    def test_run_and_both_jobs_must_be_successful_and_for_the_tag_commit(self):
        for part, field, value in itertools.product(("run", "gate", "release"), ("status", "conclusion", "head_sha"),
                                                    ("failure", "cancelled", "skipped", "in_progress", "b" * 40)):
            with self.subTest(part=part, field=field, value=value):
                fixture = BuildFixture()
                target = {"run": fixture.runs[0], "gate": fixture.jobs[0], "release": fixture.jobs[1]}[part]
                target[field] = value
                with self.assertRaisesRegex(ValueError, "No successful release build"):
                    fixture.verify()

    def test_other_workflows_events_branches_and_missing_jobs_are_rejected(self):
        for field, value in (("event", "pull_request"), ("path", ".github/workflows/other.yml"),
                             ("head_branch", "unreviewed-branch")):
            with self.subTest(field=field):
                fixture = BuildFixture()
                fixture.runs[0][field] = value
                with self.assertRaisesRegex(ValueError, "No successful release build"):
                    fixture.verify()
        for removed in (0, 1):
            fixture = BuildFixture()
            fixture.jobs.pop(removed)
            with self.assertRaisesRegex(ValueError, "No successful release build"):
                fixture.verify()

    def test_bot_release_or_asset_created_outside_the_release_job_is_rejected(self):
        for part, field in (("release", "published_at"), ("asset", "created_at")):
            fixture = BuildFixture()
            getattr(fixture, part)[field] = "2026-10-26T05:00:00Z"
            with self.assertRaisesRegex(ValueError, "No successful release build"):
                fixture.verify()

    def test_missing_expired_wrong_run_and_corrupt_artifacts_fail_closed(self):
        for case in ("missing", "duplicate", "expired", "sha", "run", "archive"):
            with self.subTest(case=case):
                fixture = BuildFixture()
                if case == "missing":
                    fixture.artifacts.clear()
                elif case == "duplicate":
                    fixture.artifacts *= 2
                elif case == "expired":
                    fixture.artifacts[0]["expired"] = True
                elif case in ("sha", "run"):
                    fixture.artifacts[0]["workflow_run"]["head_sha" if case == "sha" else "id"] = "other"
                else:
                    fixture.archive = b"corrupt archive"
                with self.assertRaises(ValueError):
                    fixture.verify()

    def test_paginated_github_responses_are_not_truncated(self):
        with patch.object(prepare_publish, "gh", return_value='[{"jobs":[1]},{"jobs":[2]}]') as api:
            self.assertEqual([1, 2], prepare_publish.api_pages("actions/runs/42/jobs?per_page=100", "jobs"))
            self.assertEqual(("api", "--paginate", "--slurp"), api.call_args.args[:3])


class ReleaseTests(unittest.TestCase):
    def test_partial_store_configuration_fails_before_any_upload(self):
        for token, project, key in itertools.product(("", "set"), repeat=3):
            env = {"MODRINTH_TOKEN": "test", "CURSEFORGE_TOKEN": token,
                   "CURSEFORGE_ID": "123" if project else "", "CURSEFORGE_API_KEY": key}
            with self.subTest(token=bool(token), project=bool(project), key=bool(key)):
                self.assertEqual((True, False), release_checks.store_config("draft", env))
                for step in ("submit", "release"):
                    if project and not (token and key):
                        with self.assertRaises(ValueError):
                            release_checks.store_config(step, env)
                    else:
                        self.assertEqual((True, bool(project)), release_checks.store_config(step, env))
        with self.assertRaises(ValueError):
            release_checks.store_config("release", {})
        with self.assertRaises(ValueError):
            release_checks.store_config("draft", {"CURSEFORGE_TOKEN": "x", "CURSEFORGE_ID": "1", "CURSEFORGE_API_KEY": "x"})

    def test_unapproved_curseforge_does_not_block_modrinth_and_warns_in_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            summary = Path(temp) / "summary"
            env = os.environ | {"MODRINTH_TOKEN": "test", "CURSEFORGE_TOKEN": "test",
                                "CURSEFORGE_ID": "", "CURSEFORGE_API_KEY": "", "GITHUB_STEP_SUMMARY": str(summary)}
            for step in ("draft", "submit", "release"):
                result = subprocess.run(["python3", str(ROOT / "tools/release_checks.py"), "stores", step],
                                        env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("Modrinth: configured", result.stdout)
                if step == "draft":
                    self.assertIn("CurseForge is skipped in draft mode", result.stdout)
                    self.assertNotIn("::warning::", result.stdout)
                else:
                    self.assertIn("::warning::CurseForge is disabled", result.stdout)
            self.assertEqual(2, summary.read_text().count("**Warning:** CurseForge is disabled"))

    def test_semver_rejects_empty_or_leading_zero_identifiers(self):
        for version in ("3.8.1", "1.0.0-rc.1", "0.0.1+build.001"):
            self.assertTrue(release_checks.valid_version(version), version)
        for version in ("3.8", "03.8.1", "1.0.0-01", "1.0.0-rc..1", "1.0.0+"):
            self.assertFalse(release_checks.valid_version(version), version)

    def test_release_inherits_every_aggregate_dependency(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text().split("\njobs:\n", 1)[1]
        jobs = dict(re.findall(r"^  (\w+):\n(.*?)(?=^  \w+:\n|\Z)", workflow, re.M | re.S))
        self.assertRegex(jobs["ci"], r"needs: \[[^\]]*clean_install")
        self.assertRegex(jobs["release"], r"needs: ci\n")
        self.assertIn("needs.ci.result == 'success'", jobs["release"])
        needs = re.search(r"needs: \[([^\]]*)\]", jobs["ci"])[1].split(", ")
        self.assertEqual(set(needs) - {"changes"}, set(jobs) - {"changes", "ci", "release"})
        script = textwrap.dedent(jobs["ci"].split("run: |\n", 1)[1])
        mod = ("build", "unit", "gametest", "clean_install")

        def passes(release, results):
            def expression(match):
                value = match[1].strip()
                if "'failure'" in value:
                    return str(any(r in ("failure", "cancelled") for r in results.values())).lower()
                if "inputs.release" in value:
                    return str(release).lower()
                job = re.fullmatch(r"needs\.(\w+)\.result", value)
                return results[job[1]] if job else "unknown"
            expanded = re.sub(r"\$\{\{(.*?)\}\}", expression, script)
            return subprocess.run(["bash", "-e", "-c", expanded], capture_output=True).returncode == 0

        checked = {job: "success" for job in needs} | {"reuse": "skipped"}
        reused = checked | {job: "skipped" for job in mod} | {"reuse": "success"}
        for release in (False, True):
            self.assertTrue(passes(release, checked), release)
            self.assertTrue(passes(release, reused), release)
            for job in needs:
                for outcome in ("failure", "cancelled"):
                    self.assertFalse(passes(release, checked | {job: outcome}), (release, job, outcome))
        for job in needs:
            if job not in ("changes", "reuse"):
                # Outside a release any check may be unnecessary; a release skips only mod checks it reused.
                self.assertTrue(passes(False, checked | {job: "skipped"}), job)
                self.assertFalse(passes(True, (reused if job not in mod else checked) | {job: "skipped"}), job)

    def test_local_client_is_rejected_before_touching_files(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "false"}):
            with self.assertRaisesRegex(ValueError, "only on GitHub Actions"):
                clean_install.smoke(Path("does-not-exist.jar"), client=True)

    def test_changelog_and_jar_come_from_selected_tag(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "dist"
            contents = io.BytesIO()
            with zipfile.ZipFile(contents, "w") as jar:
                jar.writestr("META-INF/neoforge.mods.toml", '[[mods]]\nmodId="incoming"\nversion="1.2.3"\n')
            digest = "sha256:" + hashlib.sha256(contents.getvalue()).hexdigest()
            ci = BuildFixture(contents.getvalue())
            calls = []

            def output(args, **kwargs):
                calls.append(args)
                if args[0] == "gh" and "/actions/" in args[-1]:
                    if args[-1].endswith("/zip"):
                        return ci.archive
                    return json.dumps([{ci.key(args[-1]): ci.items(args[-1])}])
                if args[:2] == ["gh", "api"]:
                    release = ci.release | {"draft": False, "assets": [ci.asset | {"digest": digest}]}
                    return json.dumps(release)
                if args[:2] == ["git", "rev-parse"]:
                    return "a" * 40
                if args[-1].endswith("gradle.properties"):
                    return "mod_version=1.2.3\n"
                if args[-1].endswith("1.2.3.md"):
                    return b"Notes from tag\n"
                if args[-1].endswith("modrinth.json"):
                    return '{"client_side":"required", "server_side":"required"}'
                self.fail(f"Unexpected command: {args}")

            def run(args, **kwargs):
                if args[:3] == ["gh", "release", "download"]:
                    (destination / "incoming-1.2.3.jar").write_bytes(contents.getvalue())

            env = {"GITHUB_OUTPUT": str(Path(temp) / "output"), "GITHUB_STEP_SUMMARY": str(Path(temp) / "summary"),
                   "GITHUB_SHA": "b" * 40}
            with patch.dict(os.environ, env), patch("subprocess.check_output", side_effect=output), patch("subprocess.run", side_effect=run):
                prepare_publish.prepare("1.2.3", destination)
                self.assertEqual(b"Notes from tag\n", (destination / "changelog.md").read_bytes())
                self.assertIn(["git", "show", "a" * 40 + ":docs/releases/1.2.3.md"], calls)
                self.assertIn("successful build run `42`", (Path(temp) / "summary").read_text())
                digest = "sha256:" + "0" * 64
                with self.assertRaisesRegex(ValueError, "SHA256"):
                    prepare_publish.prepare("1.2.3", destination)

    def test_curseforge_pagination_duplicate_and_conflict(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "incoming-1.2.3.jar"
            jar.write_bytes(b"jar")
            file = {"id": 42, "fileName": jar.name, "fileStatus": 10,
                    "hashes": [{"algo": 1, "value": hashlib.sha1(b"jar").hexdigest()}]}
            def request(path):
                index = int(re.search(r"index=(\d+)", path)[1])
                return {"data": [{"fileName": "older.jar", "hashes": []}] if index == 0 else [file],
                        "pagination": {"resultCount": 1, "totalCount": 2}}
            self.assertEqual(42, curseforge.find_file("1", jar, request))
            file["fileStatus"] = 3
            with self.assertRaisesRegex(ValueError, "moderation"):
                curseforge.find_file("1", jar, request)
            file["hashes"] = []
            with self.assertRaisesRegex(ValueError, "different contents"):
                curseforge.find_file("1", jar, request)

    def test_curseforge_receipt_prevents_repeat_while_file_is_not_public(self):
        with tempfile.TemporaryDirectory() as temp:
            jar = Path(temp) / "incoming-1.2.3.jar"
            jar.write_bytes(b"jar")
            deployments, states, creates = [], {}, []

            def api(path, data=None):
                if path.startswith("deployments?"):
                    return deployments
                if path == "deployments":
                    creates.append(data)
                    deployment = {"id": len(creates), "payload": data["payload"]}
                    deployments.insert(0, deployment)
                    return deployment
                deployment_id = int(path.split("/")[1])
                if data is None:
                    return [{"state": states[deployment_id]}]
                self.assertIs(False, data["auto_inactive"])
                states[deployment_id] = data["state"]
                return {}

            self.assertEqual((True, 1), curseforge.reserve_upload("123", jar, "1.2.3", api))
            with self.assertRaisesRegex(ValueError, "verify its outcome"):
                curseforge.reserve_upload("123", jar, "1.2.3", api)
            self.assertEqual(1, len(creates))
            with patch.dict(os.environ, {"GITHUB_REPOSITORY": "example/mod", "GITHUB_RUN_ID": "1"}):
                curseforge.record_upload("1", "success", api)
            self.assertEqual((False, None), curseforge.reserve_upload("123", jar, "1.2.3", api))
            self.assertEqual(1, len(creates))
            states[1] = "failure"
            with self.assertRaisesRegex(ValueError, "verify its outcome"):
                curseforge.reserve_upload("123", jar, "1.2.3", api)
            states[1] = "inactive"
            self.assertEqual((True, 2), curseforge.reserve_upload("123", jar, "1.2.3", api))
            jar.write_bytes(b"different jar")
            with self.assertRaisesRegex(ValueError, "different jar"):
                curseforge.reserve_upload("123", jar, "1.2.3", api)
            self.assertEqual(2, len(creates))


class ModrinthTests(unittest.TestCase):
    def run_page(self, missing=False, new=False):
        requests = []
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            jar = root / "test.jar"
            jar.write_bytes(b"jar")
            image = root / "present.png"
            image.write_bytes(b"image")
            store = {"slug": "test", "gallery": [{"file": str(image)}]}
            if missing:
                store["gallery"].append({"file": str(root / "missing.png")})
            (root / "store.json").write_text(json.dumps(store))
            (root / "body.md").write_text("Page body")
            project = {"id": "test-id", "status": "draft", "gallery": [{"url": "https://cdn.example/old.png"}]}

            class Handler(BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def handle_request(self):
                    requests.append((self.command, self.path))
                    self.rfile.read(int(self.headers.get("Content-Length", 0)))
                    code, body = 204, {}
                    if self.command == "GET":
                        code, body = (404, {}) if new or self.path.startswith("/version_file/") else (200, project)
                    elif self.command == "POST" and self.path == "/project":
                        code, body = 200, project
                    self.send_response(code)
                    self.end_headers()
                    self.wfile.write(json.dumps(body).encode())

                do_GET = do_POST = do_PATCH = do_DELETE = handle_request

            with HTTPServer(("127.0.0.1", 0), Handler) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                env = os.environ | {"MODRINTH_TOKEN": "test", "MODRINTH_API": f"http://127.0.0.1:{server.server_port}",
                                    "STORE_JSON": str(root / "store.json"), "BODY_MD": str(root / "body.md"),
                                    "BODY_RU_MD": "",
                                    "GITHUB_OUTPUT": str(root / "output"), "GITHUB_STEP_SUMMARY": str(root / "summary")}
                try:
                    result = subprocess.run(["bash", str(ROOT / ".github/scripts/modrinth.sh"), "page", "draft", str(jar)],
                                            env=env, cwd=root, capture_output=True, text=True, timeout=20)
                finally:
                    server.shutdown()
                    thread.join()
            return result, requests, (root / "output").read_text() if (root / "output").exists() else ""

    def test_missing_media_causes_no_remote_mutations(self):
        result, requests, _ = self.run_page(missing=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("required media file is missing", result.stdout)
        self.assertEqual([("GET", "/project/test")], requests)

    def test_new_draft_can_upload_first_version(self):
        result, _, output = self.run_page(new=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("id=test-id", output)
        self.assertIn("upload=true", output)


if __name__ == "__main__":
    unittest.main()
