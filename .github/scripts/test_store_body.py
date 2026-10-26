"""Offline regression checks for store body assembly and repository drafts."""
import json
from pathlib import Path
import subprocess
import tempfile
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


if __name__ == "__main__":
    unittest.main()
