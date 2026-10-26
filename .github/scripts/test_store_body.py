"""Offline regression checks for store body assembly and repository drafts."""
import json
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/store_body.sh"
STORE = ROOT / "docs/store"


class StoreBodyTest(unittest.TestCase):
    def assemble(self, *paths):
        return subprocess.run(["bash", str(SCRIPT), *map(str, paths)],
                              capture_output=True, text=True, check=False)

    def test_languages_keep_their_contents_and_order(self):
        for stem in ("description", "curseforge"):
            with self.subTest(store=stem):
                en, ru = STORE / f"{stem}.md", STORE / f"{stem}.ru.md"
                result = self.assemble(en, ru)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, en.read_text() + "\n\n---\n\n" + ru.read_text())

    def test_missing_translation_fails_without_partial_output(self):
        with tempfile.TemporaryDirectory() as folder:
            result = self.assemble(STORE / "description.md", Path(folder) / "missing.md")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, "")

    def test_single_language_override(self):
        en = STORE / "description.md"
        result = self.assemble(en)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, en.read_text())

    def test_curseforge_exports_and_summary_match_sources(self):
        for suffix in (".md", ".ru.md"):
            self.assertEqual((STORE / ("curseforge" + suffix)).read_text(),
                             (STORE / ("description" + suffix)).read_text())
        metadata = json.loads((STORE / "modrinth.json").read_text())
        self.assertEqual(metadata["description"], (STORE / "summary.md").read_text().strip())
        self.assertLessEqual(len(metadata["description"]), 256)

    def run_page(self, exists, missing_translation=False):
        """Exercise the real page script against a loopback-only fake store."""
        writes = []

        class Store(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def reply(self, status, body):
                self.send_response(status)
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_GET(self):
                if self.path == "/project/fixture" and exists:
                    self.reply(200, {"id": "fixture", "status": "draft", "gallery": []})
                else:
                    self.reply(404, {})

            def do_POST(self):
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                message = BytesParser(policy=policy.default).parsebytes(
                    ("Content-Type: " + self.headers["Content-Type"] + "\r\n\r\n").encode() + raw)
                data = json.loads(next(message.iter_parts()).get_payload(decode=True))
                writes.append(data)
                self.reply(200, {"id": "fixture", "status": "draft", "gallery": []})

            def do_PATCH(self):
                writes.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.reply(204, {})

        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            en, ru = folder / "en.md", folder / "ru.md"
            en.write_text('English "body"\n', encoding="utf-8")
            if not missing_translation:
                ru.write_text("Русский текст\n", encoding="utf-8")
            (folder / "store.json").write_text(json.dumps({"slug": "fixture", "gallery": []}))
            (folder / "fixture.jar").write_bytes(b"fixture jar")
            with ThreadingHTTPServer(("127.0.0.1", 0), Store) as server:
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                env = os.environ | {"MODRINTH_TOKEN": "fixture", "MODRINTH_API": f"http://127.0.0.1:{server.server_port}",
                                    "STORE_JSON": str(folder / "store.json"), "BODY_MD": str(en), "BODY_RU_MD": str(ru),
                                    "GITHUB_OUTPUT": str(folder / "output"), "GITHUB_STEP_SUMMARY": str(folder / "summary")}
                try:
                    result = subprocess.run(["bash", str(SCRIPT.with_name("modrinth.sh")), "page", "draft",
                                             str(folder / "fixture.jar")], env=env, text=True,
                                            capture_output=True, timeout=20, check=False)
                finally:
                    server.shutdown()
                    thread.join()
        return result, writes

    def test_new_draft_gets_both_languages(self):
        result, writes = self.run_page(exists=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0]["body"], 'English "body"\n\n\n---\n\nРусский текст\n')

    def test_existing_draft_gets_both_languages(self):
        result, writes = self.run_page(exists=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0]["body"], 'English "body"\n\n\n---\n\nРусский текст\n')

    def test_missing_translation_prevents_page_mutation(self):
        result, writes = self.run_page(exists=True, missing_translation=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(writes, [])


if __name__ == "__main__":
    unittest.main()
