"""Offline checks for selected-version staging and repeat submission/release."""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/modrinth.sh"


class StorePublishTest(unittest.TestCase):
    def run_store(self, step, status, existing=True, other_project=False, mode="version", renamed=False):
        requests = []
        project_id = "IuLUSNeU"
        jar_bytes = b"Incoming 3.10.2 fixture"
        digest = hashlib.sha1(jar_bytes).hexdigest()
        project = {"id": project_id, "slug": "incoming", "status": status,
                   "versions": ["7EO53M7j"], "gallery": []}

        class Store(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def reply(self, code, data):
                self.send_response(code)
                self.end_headers()
                self.wfile.write(json.dumps(data).encode())

            def do_GET(self):
                requests.append(("GET", self.path))
                if self.path == f"/project/{project_id}":
                    self.reply(200, project)
                elif self.path == f"/version_file/{digest}?algorithm=sha1" and existing:
                    self.reply(200, {"id": "7EO53M7j", "version_number": "3.10.2",
                                     "project_id": "other" if other_project else project_id})
                else:
                    self.reply(404, {})

            def do_PATCH(self):
                data = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                requests.append(("PATCH", self.path, data))
                if data.get("slug") == project["slug"]:
                    self.reply(400, {"error": "invalid_input", "description": "Slug collides with other project's id!"})
                    return
                project.update(data)
                self.reply(204, {})

        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            jar = folder / "incoming-3.10.2.jar"
            jar.write_bytes(jar_bytes)
            config = folder / "store.json"
            config.write_text(json.dumps({"project_id": project_id, "slug": "incoming-renamed" if renamed else "incoming"}))
            body = folder / "body.md"
            body.write_text("Store page body\n")
            output = folder / "output"
            with ThreadingHTTPServer(("127.0.0.1", 0), Store) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                env = os.environ | {"MODRINTH_TOKEN": "fixture", "STORE_JSON": str(config),
                                    "BODY_MD": str(body), "BODY_RU_MD": "",
                                    "MODRINTH_API": f"http://127.0.0.1:{server.server_port}",
                                    "GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(folder / "summary")}
                try:
                    command = ["page", step, str(jar)] if mode == "page" else ["version", str(jar), step]
                    result = subprocess.run(["bash", str(SCRIPT), *command],
                                            env=env, capture_output=True, text=True, timeout=20)
                    if result.returncode == 0 and step == "submit":
                        self.assertIn("upload=false", output.read_text())
                        result = subprocess.run(["bash", str(SCRIPT), "submit"], env=env,
                                                capture_output=True, text=True, timeout=20)
                finally:
                    server.shutdown()
                    thread.join()
            return result, requests, output.read_text() if output.exists() else ""

    def test_unchanged_slug_is_omitted_from_pinned_project_page_patch(self):
        result, requests, output = self.run_store("draft", "draft", mode="page")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(requests[0], ("GET", "/project/IuLUSNeU"))
        self.assertEqual(requests[1], ("PATCH", "/project/IuLUSNeU", {"body": "Store page body\n"}))
        self.assertEqual(output, "id=IuLUSNeU\nupload=false\n")

    def test_intentional_slug_change_still_patches_the_permanent_project_id(self):
        result, requests, _ = self.run_store("draft", "draft", mode="page", renamed=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(requests[0], ("GET", "/project/IuLUSNeU"))
        self.assertEqual(requests[1], ("PATCH", "/project/IuLUSNeU",
                                       {"slug": "incoming-renamed", "body": "Store page body\n"}))

    def test_submit_existing_3102_jar_and_retry_only_requests_review_once(self):
        for status in ("draft", "rejected", "processing", "approved"):
            with self.subTest(status=status):
                result, requests, output = self.run_store("submit", status)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(output, "id=IuLUSNeU\nupload=false\n")
                patches = [request for request in requests if request[0] != "GET"]
                self.assertEqual(patches, [("PATCH", "/project/IuLUSNeU", {"status": "processing"})]
                                 if status in ("draft", "rejected") else [])

    def test_release_existing_3102_jar_never_uploads_again(self):
        for status in ("processing", "approved", "unlisted", "private", "archived"):
            with self.subTest(status=status):
                result, requests, output = self.run_store("release", status)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(output, "id=IuLUSNeU\nupload=false\n")
                self.assertEqual([request[0] for request in requests], ["GET", "GET"])

    def test_missing_release_jar_is_uploaded_and_other_projects_jar_is_rejected(self):
        result, _, output = self.run_store("release", "approved", existing=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("upload=true", output)
        result, _, output = self.run_store("release", "approved", other_project=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("another project", result.stdout)
        self.assertEqual(output, "")


if __name__ == "__main__":
    unittest.main()
