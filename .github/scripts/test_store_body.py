"""Offline regression checks for store body assembly and repository drafts."""
import json
import hashlib
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import re
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, urlsplit


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".github/scripts/store_body.sh"
STORE = ROOT / "docs/store"


class StoreBodyTest(unittest.TestCase):
    def test_store_headers_use_the_shared_banner_and_keep_the_tagline(self):
        banner = "https://raw.githubusercontent.com/zentixua/incoming/main/docs/media/hero.webp"
        for stem in ("description", "curseforge"):
            for suffix, tagline in ((".md", "Air strikes and air defense for Minecraft 1.21.1."),
                                    (".ru.md", "Удары с воздуха и ПВО для Minecraft 1.21.1.")):
                with self.subTest(store=stem, language=suffix):
                    source = (STORE / (stem + suffix)).read_text()
                    header = source.split("\n\n", 3)
                    if suffix == ".md":
                        self.assertTrue(header[1].startswith("!["))
                        self.assertTrue(header[1].endswith(f"]({banner})"))
                        self.assertEqual(header[2], tagline)
                    else:
                        self.assertEqual(header[1], tagline)
                        self.assertNotIn("![", source)

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

    def run_page(self, exists, missing_translation=False, with_gallery=False,
                 duplicate_remote=False, duplicate_same_url=False, duplicate_local=False, repeat=False,
                 duplicate_index=0, renamed=False, pinned=False):
        """Exercise the real page script against a loopback-only fake store."""
        writes = []
        gallery = []
        cdn_files = set()

        class Store(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def reply(self, status, body):
                self.send_response(status)
                self.end_headers()
                self.wfile.write(json.dumps(body).encode())

            def do_GET(self):
                if self.path == "/project/fixture" and exists:
                    self.reply(200, {"id": "fixture", "status": "draft", "gallery": gallery})
                else:
                    self.reply(404, {})

            def valid_gallery_query(self):
                # Labrinth rejects present-but-empty optional strings on POST and PATCH.
                query = parse_qs(urlsplit(self.path).query, keep_blank_values=True)
                if any(query.get(key) == [""] for key in ("title", "description")):
                    self.reply(400, {"error": "validation", "description": "Gallery text must not be empty"})
                    return False
                return True

            def do_POST(self):
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                if urlsplit(self.path).path.endswith("/gallery"):
                    if not self.valid_gallery_query():
                        return
                    query = parse_qs(urlsplit(self.path).query)
                    sha = hashlib.sha1(raw).hexdigest()
                    gallery.append({"url": f"https://cdn.example/{sha}.jpg",
                                    "raw_url": f"https://cdn.example/{sha}.jpg",
                                    "title": query.get("title", [""])[0],
                                    "description": query.get("description", [""])[0]})
                    writes.append({"method": "POST", "path": self.path, "stored": gallery[-1].copy()})
                    cdn_files.add(gallery[-1]["url"])
                    self.reply(204, {})
                    return
                message = BytesParser(policy=policy.default).parsebytes(
                    ("Content-Type: " + self.headers["Content-Type"] + "\r\n\r\n").encode() + raw)
                data = json.loads(next(message.iter_parts()).get_payload(decode=True))
                writes.append(data)
                self.reply(200, {"id": "fixture", "status": "draft", "gallery": []})

            def do_PATCH(self):
                if urlsplit(self.path).path.endswith("/gallery"):
                    if not self.valid_gallery_query():
                        return
                    query = parse_qs(urlsplit(self.path).query, keep_blank_values=True)
                    image = next(image for image in gallery if image["url"] == query["url"][0])
                    for key in ("title", "description"):
                        if key in query:
                            image[key] = query[key][0]
                    writes.append({"method": "PATCH", "path": self.path, "stored": image.copy()})
                else:
                    writes.append(json.loads(self.rfile.read(int(self.headers["Content-Length"]))))
                self.reply(204, {})

            def do_DELETE(self):
                writes.append({"method": "DELETE", "path": self.path})
                url = parse_qs(urlsplit(self.path).query)["url"][0]
                # Labrinth removes both files, even when another entry shares the original.
                image = next(image for image in gallery if image["url"] == url)
                cdn_files.discard(image["url"])
                cdn_files.discard(image.get("raw_url"))
                # The API resolves one gallery item and deletes that row by its database id.
                gallery.remove(image)
                self.reply(204, {})

        with tempfile.TemporaryDirectory() as folder:
            folder = Path(folder)
            en, ru = folder / "en.md", folder / "ru.md"
            en.write_text('English "body"\n', encoding="utf-8")
            if not missing_translation:
                ru.write_text("\u0420\u0443\u0441\u0441\u043a\u0438\u0439 \u0442\u0435\u043a\u0441\u0442\n", encoding="utf-8")
            configured_gallery = []
            if with_gallery:
                for index in range(3):
                    image = folder / f"image-{index}.jpg"
                    image.write_bytes(f"fixture image {index}".encode())
                    sha = hashlib.sha1(image.read_bytes()).hexdigest()
                    raw_url = f"https://cdn.example/{sha}.jpg"
                    gallery.append({"url": f"https://cdn.example/optimized-{index}.jpg", "raw_url": raw_url,
                                    "featured": False, "ordering": 4 + index, "title": "Old title",
                                    "description": "Old description"})
                    configured_gallery.append({"file": str(image), "featured": index == 0,
                                               "title": 'New "cover" & caption' if index == 0 else "Second image"})
                configured_gallery[0]["description"] = "New description & details"
                configured_gallery[1]["title"] = ""
                configured_gallery[2].update(title=None, description=None)
                image = folder / "new-image.jpg"
                image.write_bytes(b"new fixture image")
                configured_gallery.append({"file": str(image), "description": ""})
                gallery.append({"url": "https://cdn.example/obsolete.jpg", "featured": True, "ordering": 0})
                if duplicate_remote:
                    gallery.append(gallery[duplicate_index] | {"url": "https://cdn.example/duplicate.jpg"})
                if duplicate_same_url:
                    gallery.append(gallery[0].copy())
                if duplicate_local:
                    alias = folder / "same-image-another-name.jpg"
                    alias.write_bytes(Path(configured_gallery[0]["file"]).read_bytes())
                    configured_gallery.append({"file": str(alias)})
            for image in gallery:
                cdn_files.update(url for url in (image["url"], image.get("raw_url")) if url)
            metadata = {"slug": "renamed-fixture" if renamed else "fixture", "gallery": configured_gallery,
                        "client_side": "required", "server_side": "required"}
            if pinned:
                metadata["project_id"] = "fixture"
            (folder / "store.json").write_text(json.dumps(metadata))
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
                    if repeat and result.returncode == 0:
                        self.assertTrue(all(image["url"] in cdn_files and image.get("raw_url", image["url"]) in cdn_files
                                            for image in gallery), "Reconciliation deleted a retained CDN file")
                        writes.append({"repeat": True, "gallery": len(gallery)})
                        result = subprocess.run(["bash", str(SCRIPT.with_name("modrinth.sh")), "page", "draft",
                                                 str(folder / "fixture.jar")], env=env, text=True,
                                                capture_output=True, timeout=20, check=False)
                    if result.returncode == 0:
                        self.assertTrue(all(image["url"] in cdn_files and image.get("raw_url", image["url"]) in cdn_files
                                            for image in gallery), "A gallery entry points to a deleted CDN file")
                finally:
                    server.shutdown()
                    thread.join()
        return result, writes

    def test_slug_change_updates_pinned_project_instead_of_creating_another(self):
        result, writes = self.run_page(exists=True, renamed=True, pinned=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(writes), 1)
        self.assertNotIn("is_draft", writes[0])
        self.assertEqual(writes[0]["slug"], "renamed-fixture")
        self.assertEqual(writes[0]["client_side"], "required")
        self.assertEqual(writes[0]["server_side"], "required")

    def test_missing_pinned_project_fails_without_creating_another(self):
        result, writes = self.run_page(exists=False, renamed=True, pinned=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("refusing to create another draft", result.stdout)
        self.assertEqual(writes, [])

    def test_duplicate_local_content_fails_before_remote_mutation(self):
        result, writes = self.run_page(exists=True, with_gallery=True, duplicate_local=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("duplicate gallery image", result.stdout)
        self.assertEqual(writes, [])

    def test_gallery_reconciliation_removes_duplicates_and_is_idempotent(self):
        result, writes = self.run_page(exists=True, with_gallery=True, duplicate_remote=True, repeat=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        boundary = next(i for i, entry in enumerate(writes) if "repeat" in entry)
        self.assertEqual(writes[boundary]["gallery"], 4)
        removed = [parse_qs(urlsplit(w["path"]).query)["url"][0]
                   for w in writes[:boundary] if w.get("method") == "DELETE"]
        self.assertCountEqual(removed, ["https://cdn.example/obsolete.jpg", "https://cdn.example/duplicate.jpg",
                                       "https://cdn.example/optimized-0.jpg"])
        first_upload = next(i for i, w in enumerate(writes[:boundary]) if w.get("method") == "POST")
        self.assertEqual([w["method"] for w in writes[1:first_upload]], ["DELETE", "DELETE"])
        second = writes[boundary + 1:]
        self.assertEqual([w["method"] for w in second if "method" in w], ["PATCH"] * 4)

    def test_duplicate_identical_urls_are_replaced_once(self):
        result, writes = self.run_page(exists=True, with_gallery=True, duplicate_same_url=True, repeat=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        boundary = next(i for i, entry in enumerate(writes) if "repeat" in entry)
        self.assertEqual(writes[boundary]["gallery"], 4)
        removed = [parse_qs(urlsplit(w["path"]).query)["url"][0]
                   for w in writes[:boundary] if w.get("method") == "DELETE"]
        self.assertEqual(removed.count("https://cdn.example/optimized-0.jpg"), 2)
        self.assertEqual([w["method"] for w in writes[boundary + 1:] if "method" in w], ["PATCH"] * 4)

    def test_duplicate_replacement_preserves_omitted_empty_and_null_captions(self):
        for index in (1, 2):
            with self.subTest(index=index):
                result, writes = self.run_page(exists=True, with_gallery=True, duplicate_remote=True,
                                              duplicate_index=index, repeat=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                upload = next(w for w in writes if w.get("method") == "POST")
                self.assertEqual(upload["stored"]["title"], "Old title")
                self.assertEqual(upload["stored"]["description"], "Old description")

    def test_repository_gallery_contains_all_media_once(self):
        gallery = json.loads((STORE / "modrinth.json").read_text())["gallery"]
        files = [image["file"] for image in gallery]
        self.assertEqual(len(files), len(set(files)))
        hashes = [hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in files]
        self.assertEqual(len(hashes), len(set(hashes)))
        self.assertEqual(files[:2], ["docs/media/hero.webp", "docs/media/nuke-dusk.webp"])
        self.assertEqual(len(files), 14)
        self.assertEqual(sum(Path(path).suffix == ".webp" for path in files), 4)
        self.assertEqual(sum(image.get("featured", False) for image in gallery), 1)
        self.assertTrue(gallery[0]["featured"])
        listing = (STORE / "listing.md").read_text()
        rows = re.findall(r"^\| `(docs/[^`]+)` \| ([^|]+) \| ([^|]+) \|$", listing, re.M)
        self.assertEqual([(path, title.removesuffix(" (featured)"), description)
                          for path, title, description in rows],
                         [(image["file"], image["title"], image["description"]) for image in gallery])
        curseforge = json.loads((STORE / "curseforge.json").read_text())
        self.assertEqual(curseforge["gallery_manifest"], "docs/store/modrinth.json")

    def test_page_bodies_show_only_the_two_shared_gallery_animations(self):
        gallery = json.loads((STORE / "modrinth.json").read_text())["gallery"]
        gallery_files = {entry["file"] for entry in gallery}
        prefix = "https://raw.githubusercontent.com/zentixua/incoming/main/"
        bodies = {name: (ROOT / name).read_text() for name in ("README.md", "README.ru.md")}
        for stem in ("description", "curseforge"):
            result = self.assemble(STORE / f"{stem}.md", STORE / f"{stem}.ru.md")
            self.assertEqual(result.returncode, 0, result.stderr)
            bodies[stem] = result.stdout
        for name, body in bodies.items():
            with self.subTest(page=name):
                self.assertNotRegex(body, r"(?i)<(?:img|picture|video)\b|!\[[^\]]*\]\[")
                images = re.findall(r"!\[[^\]]*\]\(([^)]+)\)", body)
                if name in ("description", "curseforge"):
                    self.assertTrue(all(url.startswith(prefix) for url in images))
                files = [url.removeprefix(prefix) for url in images]
                self.assertEqual(files, ["docs/media/hero.webp", "docs/media/nuke-dusk.webp"])
                self.assertTrue(set(files) <= gallery_files)

    def test_curseforge_manual_settings_match_upload_configuration(self):
        config = json.loads((STORE / "curseforge.json").read_text())
        modrinth = json.loads((STORE / "modrinth.json").read_text())
        for key in ("title", "license_id", "source_url", "issues_url", "wiki_url"):
            self.assertEqual(config[key], modrinth[key])
        self.assertEqual(config["summary"], (STORE / "summary.md").read_text().strip())
        self.assertTrue((ROOT / config["avatar"]).is_file())
        workflow = (ROOT / ".github/workflows/publish.yml").read_text()
        aliases = re.findall(r"\{curseforge:([^}]+)\}", workflow)
        self.assertCountEqual(config["optional_dependencies"], aliases)
        for key, value in (("loaders", config["loaders"][0]),
                           ("game-versions", config["game_versions"][0]),
                           ("java", str(config["java_versions"][0])),
                           ("version-type", config["release_type"])):
            self.assertEqual(workflow.count(f"          {key}: {value}\n"), 2)

    def test_new_draft_gets_both_languages(self):
        result, writes = self.run_page(exists=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0]["body"], 'English "body"\n\n\n---\n\n\u0420\u0443\u0441\u0441\u043a\u0438\u0439 \u0442\u0435\u043a\u0441\u0442\n')

    def test_existing_draft_gets_both_languages(self):
        result, writes = self.run_page(exists=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(writes), 1)
        self.assertEqual(writes[0]["body"], 'English "body"\n\n\n---\n\n\u0420\u0443\u0441\u0441\u043a\u0438\u0439 \u0442\u0435\u043a\u0441\u0442\n')

    def test_missing_translation_prevents_page_mutation(self):
        result, writes = self.run_page(exists=True, missing_translation=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(writes, [])

    def test_retained_gallery_updates_before_obsolete_cover_is_removed(self):
        result, writes = self.run_page(exists=True, with_gallery=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        mutations = writes[1:]  # The first write updates the project body.
        self.assertEqual([entry["method"] for entry in mutations], ["PATCH", "PATCH", "PATCH", "POST", "DELETE"])
        for index, entry in enumerate(mutations[:3]):
            url = urlsplit(entry["path"])
            self.assertEqual(url.path, "/project/fixture/gallery")
            expected = {
                "url": [f"https://cdn.example/optimized-{index}.jpg"],
                "featured": ["true" if index == 0 else "false"], "ordering": [str(index)],
            }
            if index == 0:
                expected |= {"title": ['New "cover" & caption'], "description": ["New description & details"]}
            self.assertEqual(parse_qs(url.query, keep_blank_values=True), expected)
            self.assertEqual(entry["stored"]["title"], 'New "cover" & caption' if index == 0 else "Old title")
            self.assertEqual(entry["stored"]["description"], "New description & details" if index == 0 else "Old description")
        self.assertEqual(parse_qs(urlsplit(mutations[3]["path"]).query, keep_blank_values=True),
                         {"ext": ["jpg"], "ordering": ["3"], "featured": ["false"]})
        self.assertEqual(parse_qs(urlsplit(mutations[4]["path"]).query),
                         {"url": ["https://cdn.example/obsolete.jpg"]})


if __name__ == "__main__":
    unittest.main()
