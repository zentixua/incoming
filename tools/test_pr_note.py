"""Проверка «Записки» (tools/pr_note.py): какие пути требуют записки и как читается описание PR.

  python3 -m unittest discover -s tools -p 'test_pr_note.py'
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import pr_note  # noqa: E402

JAVA = "mod/src/main/java/ua/zentix/incoming/"


class Paths(unittest.TestCase):
    def test_player_facing(self):
        for p in (JAVA + "client/fx/FxLayer.java", JAVA + "client/sound/EngineSound.java", JAVA + "sound/Noise.java",
                  "mod/src/main/resources/assets/incoming/sounds.json",
                  "mod/src/main/resources/assets/incoming/shaders/core/fx.fsh",
                  "tools/build_sounds.py", "tools/gen_particles.py", "tools/bake_fx/bake.py"):
            self.assertEqual(pr_note.player_facing([p]), [p], p)

    def test_not_player_facing(self):
        for p in (JAVA + "strike/WeaponSpec.java", JAVA + "nuclear/model/Fireball.java",
                  "mod/src/test/java/ua/zentix/incoming/sound/NoiseTest.java",
                  "mod/src/devtest/java/ua/zentix/incoming/client/ProbeScenario.java",
                  "mod/src/main/resources/data/incoming/recipe/shahed.json",
                  "tools/logscan.py", "tools/gen_particles.py.bak", "docs/guide/README.md", "CLAUDE.md",
                  ".github/workflows/build.yml", "tools/bake_fx"):
            self.assertEqual(pr_note.player_facing([p]), [], p)

    def test_keeps_order(self):
        paths = ["CLAUDE.md", "tools/gen_models.py", "tools/logscan.py", JAVA + "client/Hud.java"]
        self.assertEqual(pr_note.player_facing(paths), ["tools/gen_models.py", JAVA + "client/Hud.java"])


class Body(unittest.TestCase):
    def test_section_until_next_heading(self):
        body = "Intro\n\n## Записка\nLaw: Briggs plume rise.\nValid: 1–60 s.\n\n## Gate\nframes"
        self.assertEqual(pr_note.note(body), "Law: Briggs plume rise.\nValid: 1–60 s.")

    def test_heading_with_suffix_and_level_three(self):
        self.assertEqual(pr_note.note("### Записка ожиданий (до кода)\nодна строка"), "одна строка")
        self.assertEqual(pr_note.note("## записка — expectations\nx"), "x")

    def test_subheadings_stay_inside(self):
        body = "## Записка\n### Закон\nCCPS\n### Где верен\n0–3 s\n## Done"
        self.assertEqual(pr_note.note(body), "### Закон\nCCPS\n### Где верен\n0–3 s")

    def test_no_section(self):
        self.assertIsNone(pr_note.note(""))
        self.assertIsNone(pr_note.note(None))
        self.assertIsNone(pr_note.note("Записка: inline mention is not a section"))
        self.assertIsNone(pr_note.note("# Записка\ntop-level title is not a section"))
        self.assertIsNone(pr_note.note("## Записками не считается\nx"))

    def test_heading_in_code_block_ignored(self):
        self.assertIsNone(pr_note.note("```md\n## Записка\ntext\n```\n"))

    def test_crlf(self):
        self.assertEqual(pr_note.note("## Записка\r\nline\r\n## Next\r\n"), "line")

    def test_comments_stripped(self):
        self.assertEqual(pr_note.note("## Записка\n<!-- закон, где верен,\nчто видно -->\n"), "")
        self.assertEqual(pr_note.note("## Записка\n<!-- hint -->\nLaw: Kirchhoff"), "Law: Kirchhoff")

    def test_meaningful(self):
        self.assertTrue(pr_note.meaningful("Fix a typo in the HUD key, nothing to model."))
        self.assertTrue(pr_note.meaningful("- **Закон.** CCPS"))
        for text in ("", "нет", "- нет", "N/A", "TODO", "…", "-", "**—**"):
            self.assertFalse(pr_note.meaningful(text), text)


class Check(unittest.TestCase):
    def test_not_needed(self):
        ok, msg = pr_note.check(["CLAUDE.md", JAVA + "strike/Orders.java"], "")
        self.assertTrue(ok, msg)

    def test_missing(self):
        ok, msg = pr_note.check([JAVA + "client/fx/FxLayer.java"], "Some PR text", "URL")
        self.assertFalse(ok)
        self.assertIn("нет раздела", msg)
        self.assertIn("URL", msg)
        self.assertIn("client/fx/FxLayer.java", msg)

    def test_empty(self):
        ok, msg = pr_note.check(["tools/build_sounds.py"], "## Записка\n<!-- template hint -->\n## Gate\n")
        self.assertFalse(ok)
        self.assertIn("пуст", msg)

    def test_present(self):
        ok, msg = pr_note.check(["tools/build_sounds.py"], "## Записка\nRecordings at native speed, shift ≤ ±25 %.")
        self.assertTrue(ok, msg)

    def test_template_unfilled_is_red(self):
        """Шаблон PR, оставленный как есть, не проходит: подсказки в нём — комментарии HTML."""
        template = (Path(__file__).resolve().parent.parent / pr_note.TEMPLATE).read_text(encoding="utf-8")
        self.assertEqual(pr_note.note(template), "")
        ok, msg = pr_note.check([JAVA + "client/Hud.java"], template)
        self.assertFalse(ok)
        self.assertIn("пуст", msg)

    def test_many_paths_shortened(self):
        paths = [f"mod/src/main/resources/assets/incoming/textures/t{i}.png" for i in range(25)]
        ok, msg = pr_note.check(paths, "")
        self.assertFalse(ok)
        self.assertIn("ещё 5", msg)
        self.assertNotIn("t24.png", msg)


if __name__ == "__main__":
    unittest.main()
