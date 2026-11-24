"""amd_shader_check: Minecraft include expansion, program discovery and the budget rule, without the AMD compiler."""
import tempfile
import unittest
from pathlib import Path

from amd_shader_check import (GROWTH, NEW_CODE, NEW_MEMORY_MB, BuildError, expand, judge, programs, record, roots,
                              sources)

# fx_pall_composite on main before the budget, then as it was at 3.13.0 (RGA 2.13, gfx1032)
MAIN = {"vgpr": 168, "scratch": 0, "code": 805320, "code_vert": 476, "code_frag": 804844, "memory_mb": 4972, "seconds": 26.5}
RELEASED = {"code_vert": 476, "code_frag": 320052, "memory_mb": 709, "scratch": 0}


def tree(files):
    root = Path(tempfile.mkdtemp())
    for name, text in files.items():
        (root / name).parent.mkdir(parents=True, exist_ok=True)
        (root / name).write_text(text)
    return root


class Judge(unittest.TestCase):
    def test_the_dust_wall_regression_fails(self):
        # 3.13.0 → main: the composite's AMD code ×2.5, compiler memory ×7 (the AMD freeze); the budget would have stopped it
        fails = judge("fx_pall_composite", MAIN, RELEASED)
        self.assertEqual(2, len(fails), fails)
        self.assertIn("AMD frag code 785 KB, budget 312 KB", fails[0])
        self.assertIn("compiler memory 4972 MB", fails[1])

    def test_growth_within_allowance_passes(self):
        budget = {"code_vert": 476, "code_frag": 400000, "memory_mb": 900, "scratch": 208}
        ok = dict(MAIN, code_frag=int(400000 * (1 + GROWTH)), memory_mb=int(900 * (1 + GROWTH)), scratch=208)
        self.assertEqual([], judge("fx_pall", ok, budget))
        self.assertEqual(1, len(judge("fx_pall", dict(ok, code_frag=ok["code_frag"] + 4), budget)))

    def test_each_stage_has_its_own_budget(self):
        # review 2 of #751: fx's vertex stage is 169 KB, its fragment 9 KB; a budget on the sum let the fragment grow ×3
        budget = {"code_vert": 169496, "code_frag": 8800, "memory_mb": 390, "scratch": 0}
        grown = dict(MAIN, code_vert=169496, code_frag=26600, memory_mb=390, scratch=0)
        self.assertEqual(["fx: AMD frag code 25 KB, budget 8 KB (+10% allowed)"], judge("fx", grown, budget))
        # a tiny stage may move by a few hundred bytes
        tiny = {"code_vert": 476, "code_frag": 500, "memory_mb": 60, "scratch": 0}
        self.assertEqual([], judge("fx_see", dict(MAIN, code_vert=476, code_frag=2500, memory_mb=60, scratch=0), tiny))

    def test_old_budget_entry_asks_for_a_record(self):
        fails = judge("fx", dict(MAIN), {"code": 178296, "memory_mb": 391, "scratch": 0})
        self.assertEqual(["fx: budget entry has no code_vert, code_frag (record it: --record --jobs 1)"], fails)

    def test_small_program_memory_has_slack(self):
        budget = {"code_vert": 476, "code_frag": 1000, "memory_mb": 60, "scratch": 0}
        small = dict(MAIN, code_vert=476, code_frag=1000, scratch=0)
        self.assertEqual([], judge("fx_see", dict(small, memory_mb=92), budget))
        self.assertEqual(1, len(judge("fx_see", dict(small, memory_mb=93), budget)))

    def test_new_scratch_fails(self):
        budget = {"code_vert": 476, "code_frag": 400000, "memory_mb": 900, "scratch": 0}
        fails = judge("fx_pall_foot", dict(MAIN, code_frag=1000, memory_mb=60, scratch=16), budget)
        self.assertEqual(["fx_pall_foot: spills 16 dwords per lane to scratch memory, budget 0"], fails)

    def test_new_program_has_absolute_limits(self):
        small = dict(MAIN, code=NEW_CODE, memory_mb=NEW_MEMORY_MB)
        self.assertEqual([], judge("fx_new", small, None))
        self.assertEqual(2, len(judge("fx_new", dict(small, code=NEW_CODE + 1, memory_mb=NEW_MEMORY_MB + 1), None)))

    def test_budgeted_program_that_does_not_build_fails(self):
        # review of #751: a program the tool could not expand was "skipped" and the required check stayed green
        self.assertEqual(1, len(judge("fx_pall_composite", {"error": ["include incoming:pal.glsl does not exist"]}, RELEASED)))
        self.assertEqual(1, len(judge("fx", {"error": ["ERROR: 0:12: syntax error"]}, None)))


class Record(unittest.TestCase):
    def test_refused_while_a_program_fails(self):
        results = {"fx": dict(MAIN), "fx_pall": {"error": ["x"]}}
        with self.assertRaises(BuildError):
            record({"programs": {}}, results, ["fx", "fx_pall"], None)

    def test_only_keeps_the_rest(self):
        budget = {"programs": {"fx_pall": RELEASED, "gone": RELEASED}}
        new = record(budget, {"fx": dict(MAIN)}, ["fx"], "fx$")
        self.assertEqual({"fx", "fx_pall", "gone"}, set(new["programs"]))
        self.assertEqual({"fx"}, set(record(budget, {"fx": dict(MAIN)}, ["fx"], None)["programs"]))


class Expand(unittest.TestCase):
    def test_each_include_once_nested(self):
        t = tree({"include/a.glsl": "A\n#moj_import <incoming:b.glsl>\n", "include/b.glsl": "B\n"})
        src = "#version 150\n#moj_import <incoming:a.glsl>\n#moj_import <incoming:b.glsl>\nmain\n"
        lines = [line for line in expand(src, roots(t, None)).splitlines() if line]
        self.assertEqual(["#version 150", "A", "B", "main"], lines)

    def test_vanilla_include_and_stage_resolve_from_minecraft(self):
        # no namespace is minecraft (fx_pall_composite with <fog.glsl>, far_model with <light.glsl>, a post program with
        # vertex "blur"): taken from the client jar's shaders, never skipped
        t = tree({"core/m.json": '{"vertex": "minecraft:position", "fragment": "incoming:m"}',
                  "core/m.fsh": "#moj_import <fog.glsl>\nF\n", "program/p.json": '{"vertex": "blur", "fragment": "incoming:p"}',
                  "program/p.fsh": "P\n"})
        mc = tree({"core/position.vsh": "V\n", "include/fog.glsl": "FOG\n", "program/blur.vsh": "BLUR\n"})
        self.assertEqual(["V\n", "FOG\nF\n"], [s.replace("\n\n", "\n") for s in sources(t, mc, "m", "core")])
        self.assertEqual(["BLUR\n", "P\n"], sources(t, mc, "program/p", "program"))
        self.assertEqual([("m", "core"), ("program/p", "program")], programs(t))

    def test_included_version_line_is_dropped(self):
        # vanilla light.glsl starts with #version 150; GlslPreprocessor keeps only the importing file's (glc rejects two)
        t = tree({"include/light.glsl": "#version 150\nL\n"})
        lines = [line for line in expand("#version 150\n#moj_import <light.glsl>\n", roots(None, t)).splitlines() if line]
        self.assertEqual(["#version 150", "L"], lines)

    def test_missing_own_include_is_an_error(self):
        t = tree({"core/m.json": '{"vertex": "incoming:m", "fragment": "incoming:m"}', "core/m.vsh": "V\n",
                  "core/m.fsh": "#moj_import <incoming:pal.glsl>\n"})
        with self.assertRaisesRegex(BuildError, "incoming:pal.glsl does not exist"):
            sources(t, tree({}), "m", "core")

    def test_missing_vanilla_include_is_an_error(self):
        t = tree({"core/m.json": '{"vertex": "incoming:m", "fragment": "incoming:m"}', "core/m.vsh": "V\n",
                  "core/m.fsh": "#moj_import <nope.glsl>\n"})
        with self.assertRaisesRegex(BuildError, "minecraft:nope.glsl does not exist"):
            sources(t, tree({}), "m", "core")


if __name__ == "__main__":
    unittest.main()
