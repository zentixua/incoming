"""amd_shader_check: Minecraft include expansion and the budget rule, without the AMD compiler."""
import tempfile
import unittest
from pathlib import Path

from amd_shader_check import GROWTH, NEW_CODE, NEW_MEMORY_MB, MissingInclude, expand, judge, sources

# fx_pall_composite on main before the budget, then as it was at 3.13.0 (RGA 2.13, gfx1032)
MAIN = {"vgpr": 168, "sgpr": 102, "scratch": 0, "code": 804844, "memory_mb": 4972, "seconds": 26.5}
RELEASED = {"code": 320052, "memory_mb": 709, "scratch": 0}


class Judge(unittest.TestCase):
    def test_the_dust_wall_regression_fails(self):
        # 3.13.0 → main: the composite's AMD code ×2.5, compiler memory ×7 (the AMD freeze); the budget would have stopped it
        fails = judge("fx_pall_composite", MAIN, RELEASED)
        self.assertEqual(2, len(fails), fails)
        self.assertIn("AMD code 785 KB, budget 312 KB", fails[0])
        self.assertIn("compiler memory 4972 MB", fails[1])

    def test_growth_within_allowance_passes(self):
        budget = {"code": 400000, "memory_mb": 900, "scratch": 208}
        ok = dict(MAIN, code=int(400000 * (1 + GROWTH)), memory_mb=int(900 * (1 + GROWTH)), scratch=208)
        self.assertEqual([], judge("fx_pall", ok, budget))
        self.assertEqual(1, len(judge("fx_pall", dict(ok, code=ok["code"] + 4), budget)))

    def test_new_scratch_fails(self):
        budget = {"code": 400000, "memory_mb": 900, "scratch": 0}
        fails = judge("fx_pall_foot", dict(MAIN, code=1000, memory_mb=60, scratch=16), budget)
        self.assertEqual(["fx_pall_foot: spills 16 bytes per lane to scratch memory, budget 0"], fails)

    def test_new_program_has_absolute_limits(self):
        small = dict(MAIN, code=NEW_CODE, memory_mb=NEW_MEMORY_MB)
        self.assertEqual([], judge("fx_new", small, None))
        self.assertEqual(2, len(judge("fx_new", dict(small, code=NEW_CODE + 1, memory_mb=NEW_MEMORY_MB + 1), None)))

    def test_compiler_error_fails_and_skip_passes(self):
        self.assertEqual(1, len(judge("fx", {"error": ["ERROR: 0:12: syntax error"]}, None)))
        self.assertEqual([], judge("far_model", {"skipped": "source outside this tree: minecraft:light.glsl"}, None))


class Expand(unittest.TestCase):
    def tree(self, files):
        root = Path(tempfile.mkdtemp())
        for name, text in files.items():
            (root / name).parent.mkdir(parents=True, exist_ok=True)
            (root / name).write_text(text)
        return root

    def test_each_include_once_nested(self):
        t = self.tree({"include/a.glsl": "A\n#moj_import <incoming:b.glsl>\n", "include/b.glsl": "B\n"})
        src = "#version 150\n#moj_import <incoming:a.glsl>\n#moj_import <incoming:b.glsl>\nmain\n"
        lines = [line for line in expand(src, t / "include").splitlines() if line]
        self.assertEqual(["#version 150", "A", "B", "main"], lines)

    def test_vanilla_include_is_outside_the_tree(self):
        t = self.tree({"core/m.json": '{"vertex": "incoming:m", "fragment": "incoming:m"}', "core/m.vsh": "#moj_import <light.glsl>\n",
                       "core/m.fsh": "x\n"})
        with self.assertRaises(MissingInclude):
            sources(t, "m")

    def test_vanilla_vertex_shader_is_outside_the_tree(self):
        t = self.tree({"core/m.json": '{"vertex": "minecraft:position", "fragment": "incoming:m"}', "core/m.fsh": "x\n"})
        with self.assertRaises(MissingInclude):
            sources(t, "m")


if __name__ == "__main__":
    unittest.main()
