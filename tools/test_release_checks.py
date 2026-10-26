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


class ReleaseTests(unittest.TestCase):
    def test_partial_store_configuration_fails_before_any_upload(self):
        for token, project, key in itertools.product(("", "set"), repeat=3):
            env = {"MODRINTH_TOKEN": "test", "CURSEFORGE_TOKEN": token,
                   "CURSEFORGE_ID": "123" if project else "", "CURSEFORGE_API_KEY": key}
            with self.subTest(token=bool(token), project=bool(project), key=bool(key)):
                if bool(token) == bool(project) == bool(key):
                    self.assertEqual((True, bool(token)), release_checks.store_config("release", env))
                else:
                    with self.assertRaises(ValueError):
                        release_checks.store_config("release", env)
        with self.assertRaises(ValueError):
            release_checks.store_config("release", {})
        with self.assertRaises(ValueError):
            release_checks.store_config("draft", {"CURSEFORGE_TOKEN": "x", "CURSEFORGE_ID": "1", "CURSEFORGE_API_KEY": "x"})

    def test_semver_rejects_empty_or_leading_zero_identifiers(self):
        for version in ("3.8.1", "1.0.0-rc.1", "0.0.1+build.001"):
            self.assertTrue(release_checks.valid_version(version), version)
        for version in ("3.8", "03.8.1", "1.0.0-01", "1.0.0-rc..1", "1.0.0+"):
            self.assertFalse(release_checks.valid_version(version), version)

    def test_release_inherits_every_aggregate_dependency(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text()
        jobs = dict(re.findall(r"^  (\w+):\n(.*?)(?=^  \w+:\n|\Z)", workflow, re.M | re.S))
        self.assertRegex(jobs["ci"], r"needs: \[[^\]]*clean_install")
        self.assertRegex(jobs["release"], r"needs: ci\n")
        self.assertIn("needs.ci.result == 'success'", jobs["release"])
        self.assertIn("contains(needs.*.result, 'skipped')", jobs["ci"])
        script = textwrap.dedent(jobs["ci"].split("run: |\n", 1)[1])
        for release, outcome in itertools.product((False, True), ("success", "failure", "cancelled", "skipped")):
            def expression(match):
                value = match[1]
                if "'failure'" in value:
                    return str(outcome in ("failure", "cancelled")).lower()
                if "'skipped'" in value:
                    return str(outcome == "skipped").lower()
                if "inputs.release" in value:
                    return str(release).lower()
                return outcome
            expanded = re.sub(r"\$\{\{(.*?)\}\}", expression, script)
            result = subprocess.run(["bash", "-e", "-c", expanded], capture_output=True)
            expected = outcome == "success" or (outcome == "skipped" and not release)
            self.assertEqual(expected, result.returncode == 0, (release, outcome))

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
            calls = []

            def output(args, **kwargs):
                calls.append(args)
                if args[:2] == ["gh", "api"]:
                    return json.dumps({"draft": False, "assets": [{"name": "incoming-1.2.3.jar", "digest": digest}]})
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
                                    "GITHUB_OUTPUT": str(root / "output"), "GITHUB_STEP_SUMMARY": str(root / "summary")}
                try:
                    result = subprocess.run(["bash", str(ROOT / ".github/scripts/modrinth.sh"), "page", "draft", str(jar)],
                                            env=env, capture_output=True, text=True, timeout=20)
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
