#!/usr/bin/env python3
"""Tests of the knowledge gate (tools/check_knowledge.py) and the plugin's knowledge hooks (hooks/knowledge.py).

Run: python3 -m unittest tools/test_check_knowledge.py
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# Test repositories use a local bare origin under .../zentixua/; the real scope is github.com/zentixua (tested below).
os.environ["KNOWLEDGE_ORIGIN_RE"] = r"^/.*/zentixua/[\w.-]+$"
HOOK = ROOT / ".claude/plugins/incoming/hooks/knowledge.py"
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(HOOK.parent))

import check_knowledge  # noqa: E402
import knowledge  # noqa: E402

RULE = '---\npaths:\n  - "src/a/**"\n  - "tools/{x,y}.py"\n---\n\n# A\nline\n'
AGENTS = "# T\n\n## `.claude/rules/` files\n- `area.md` (a)\n"


def sh(cwd: Path, *cmd: str) -> str:
    return subprocess.run(cmd, cwd=cwd, check=True, capture_output=True, text=True).stdout


class Repo:
    """A git repository whose origin URL contains zentixua/ (a local bare repo), with one area rule."""

    def __init__(self, tmp: Path):
        self.origin = tmp / "zentixua" / "t.git"
        self.origin.mkdir(parents=True)
        sh(self.origin, "git", "init", "-q", "--bare", "-b", "main")
        self.root = tmp / "work"
        self.root.mkdir()
        sh(self.root, "git", "init", "-q", "-b", "main")
        sh(self.root, "git", "config", "user.email", "t@example.org")
        sh(self.root, "git", "config", "user.name", "t")
        sh(self.root, "git", "remote", "add", "origin", str(self.origin))
        self.write("CLAUDE.md", "@AGENTS.md\n")
        self.write("AGENTS.md", AGENTS)
        self.write(".claude/rules/area.md", RULE)
        self.write("src/a/B.java", "class B {}\n")
        self.write("tools/x.py", "")
        self.write(".gitignore", "run/\n")
        plugin = ROOT / ".claude/plugins/incoming"
        for rel in [".claude-plugin/plugin.json", "hooks/hooks.json", "hooks/knowledge.py"]:
            self.write(f".claude/plugins/incoming/{rel}", (plugin / rel).read_text())
        self.write(".claude-plugin/marketplace.json", (ROOT / ".claude-plugin/marketplace.json").read_text())
        self.commit("init")
        sh(self.root, "git", "push", "-q", "origin", "main")
        sh(self.root, "git", "fetch", "-q", "origin")
        sh(self.root, "git", "remote", "set-head", "origin", "main")

    def write(self, rel: str, text: str) -> Path:
        p = self.root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(text)
        return p

    def commit(self, msg: str) -> None:
        sh(self.root, "git", "add", "-A")
        sh(self.root, "git", "commit", "-q", "-m", msg)


class GateTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(Path(self.tmp.name))

    def tearDown(self):
        self.tmp.cleanup()

    def problems(self):
        return check_knowledge.check(self.repo.root)

    def test_clean_repo_passes(self):
        self.assertEqual(self.problems(), [])

    def test_this_repository_passes(self):
        self.assertEqual(check_knowledge.check(ROOT), [])

    def test_size_inside_the_agreed_look_is_ours(self):
        # Artem 22.11: he sets the goal, not a height; a mission must never stop to ask him a size, height or shape
        agents = " ".join((ROOT / "AGENTS.md").read_text(encoding="utf-8").split())
        self.assertIn("A size, height or shape serving the agreed look is the owner's call, never Artem's question", agents)
        self.assertNotIn("changes only by Artem's word", agents)
        self.assertNotIn("real size/shape", agents)

    def test_claude_md_must_import_agents(self):
        self.repo.write("CLAUDE.md", "# rules here\n")
        self.assertTrue(any("CLAUDE.md" in p for p in self.problems()))

    def test_agents_size_cap(self):
        self.repo.write("AGENTS.md", AGENTS + "x" * check_knowledge.AGENTS_MAX)
        self.assertTrue(any("AGENTS.md is" in p for p in self.problems()))

    def test_rule_size_cap(self):
        rule = self.repo.root / ".claude/rules"
        name = next(f.name for f in sorted(rule.glob("*.md")))
        text = (rule / name).read_text(encoding="utf-8")
        self.repo.write(f".claude/rules/{name}", text + "x" * check_knowledge.RULE_MAX)
        self.assertTrue(any(f"{name} is" in p for p in self.problems()))

    def test_oversize_ceiling_only_falls(self):
        for name, ceiling in check_knowledge.RULE_OVERSIZE.items():
            self.assertGreater(ceiling, check_knowledge.RULE_MAX)
            size = len((ROOT / ".claude/rules" / name).read_bytes())
            self.assertLessEqual(size, ceiling)
            self.assertGreaterEqual(size, ceiling - check_knowledge.RULE_SLACK)

    def test_crlf_counts_as_read(self):
        body = ("x" * 60 + "\r\n") * (check_knowledge.RULE_MAX // 61)
        self.repo.write(".claude/rules/area.md", RULE + body)
        self.assertTrue(any("area.md is" in p for p in self.problems()))

    def test_rule_without_paths(self):
        self.repo.write(".claude/rules/area.md", "# no frontmatter\n")
        self.assertTrue(any("no strict `paths:`" in p for p in self.problems()))

    def test_dead_glob_after_rename(self):
        self.repo.write(".claude/rules/area.md", RULE.replace("src/a/**", "src/gone/**"))
        self.assertTrue(any("src/gone/**" in p for p in self.problems()))

    def test_rule_list_both_ways(self):
        self.repo.write(".claude/rules/other.md", RULE)
        self.repo.write("AGENTS.md", AGENTS + "- `missing.md`\n")
        self.repo.commit("x")
        found = self.problems()
        self.assertTrue(any("other.md: missing from" in p for p in found))
        self.assertTrue(any("lists `missing.md`" in p for p in found))

    def test_hook_script_must_exist(self):
        hooks = self.repo.root / ".claude/plugins/incoming/hooks/hooks.json"
        hooks.write_text(hooks.read_text().replace("hooks/knowledge.py", "hooks/nope.py"))
        self.assertTrue(any("nope.py does not exist" in p for p in self.problems()))

    def test_hooks_must_stay_registered(self):
        hooks = self.repo.root / ".claude/plugins/incoming/hooks/hooks.json"
        hooks.write_text('{"hooks": {}}')
        found = self.problems()
        self.assertEqual(sum("does not run hooks/knowledge.py" in p for p in found), 4)

    def test_hooks_must_run_the_script(self):
        hooks = self.repo.root / ".claude/plugins/incoming/hooks/hooks.json"
        text = hooks.read_text()
        hooks.write_text(text.replace('"python3 ', '"true ').replace("Grep|Glob|Bash", "Grep|Glob"))
        found = self.problems()
        self.assertEqual(sum("does not run hooks/knowledge.py" in p for p in found), 4)
        hooks.write_text(text.replace("Grep|Glob|Bash", "Grep|Glob"))
        self.assertTrue(any("matcher lacks Bash" in p for p in self.problems()))

    def test_malformed_description_is_unscoped(self):
        self.repo.write(".claude/rules/area.md", RULE.replace("---\n\n#", "description: [unfinished\n---\n\n#"))
        self.assertTrue(any("no strict `paths:`" in p for p in self.problems()))

    def test_marketplace_must_list_plugin(self):
        self.repo.write(".claude-plugin/marketplace.json", '{"name": "m", "plugins": []}')
        self.assertTrue(any("does not list plugin" in p for p in self.problems()))

    def test_nested_rules_rejected(self):
        self.repo.write(".claude/rules/sub/x.md", RULE)
        self.assertTrue(any("nested rule" in p for p in self.problems()))

    def test_broken_frontmatter_is_unscoped(self):
        self.repo.write(".claude/rules/area.md", RULE.replace("---\n\n#", "oops: [\n---\n\n#"))
        self.assertTrue(any("no strict `paths:`" in p for p in self.problems()))

    def test_non_ascii_tracked_file_matches(self):
        self.repo.write(".claude/rules/area.md", RULE.replace("src/a/**", "src/é/*.java"))
        self.repo.write("src/é/É.java", "")
        self.repo.commit("x")
        self.assertEqual(self.problems(), [])

    def test_private_facts(self):
        self.repo.write("AGENTS.md", AGENTS + "server 10.1.2.3, loopback 127.0.0.1, see /mnt/project-files/x\n")
        self.repo.write(".claude/plugins/incoming/hooks/run.sh", "curl https://192.168.4.4/ # me@company.dev\n")
        found = self.problems()
        self.assertTrue(any("IPv4" in p and "AGENTS.md" in p for p in found))
        self.assertTrue(any("IPv4" in p and "run.sh" in p for p in found))
        self.assertTrue(any("e-mail" in p for p in found))
        self.assertTrue(any("project files" in p for p in found))
        self.assertEqual(sum("IPv4" in p for p in found), 2)  # loopback allowed


class HookTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.repo = Repo(Path(self.tmp.name))
        self.data = Path(self.tmp.name) / "data"
        knowledge._rules.clear()
        knowledge._scope.clear()

    def tearDown(self):
        self.tmp.cleanup()

    def run_hook(self, event: dict) -> dict | None:
        event.setdefault("session_id", "s1")
        event.setdefault("cwd", str(self.repo.root))
        env = dict(os.environ, CLAUDE_PLUGIN_DATA=str(self.data))
        out = subprocess.run([sys.executable, str(HOOK)], input=json.dumps(event), capture_output=True, text=True,
                             env=env, check=True).stdout
        return json.loads(out) if out.strip() else None

    def tool(self, name: str, **ti) -> dict | None:
        return self.run_hook({"hook_event_name": "PostToolUse", "tool_name": name, "tool_input": ti})

    def context(self, out: dict | None) -> str:
        return (out or {}).get("hookSpecificOutput", {}).get("additionalContext", "")

    def test_glob_matching(self):
        rx = knowledge.glob_regex("mod/{a,b}/**")
        self.assertTrue(rx.match("mod/a/x/Y.java"))
        self.assertFalse(rx.match("mod/c/Y.java"))
        self.assertTrue(knowledge.glob_regex("src/**/*.py").match("src/t.py"))
        self.assertFalse(knowledge.glob_regex("tools/*.py").match("tools/sub/t.py"))
        self.assertTrue(knowledge.glob_regex("*.md").match("docs/a/b.md"))  # no slash: any depth
        self.assertTrue(knowledge.glob_regex("/*.md").match("AGENTS.md"))  # leading slash: root only
        self.assertFalse(knowledge.glob_regex("/*.md").match("docs/a.md"))
        self.assertTrue(knowledge.glob_regex("src/[ab]/x.py").match("src/b/x.py"))
        self.assertFalse(knowledge.glob_regex("src/[!ab]/x.py").match("src/a/x.py"))

    def test_scope_is_github_zentixua_only(self):
        rx = __import__("re").compile(knowledge.DEFAULT_ORIGIN_RE)
        self.assertTrue(rx.match("https://github.com/zentixua/incoming.git"))
        self.assertTrue(rx.match("git@github.com:zentixua/open-skies.git"))
        self.assertFalse(rx.match("https://evil.example/zentixua/incoming.git"))
        self.assertFalse(rx.match("https://github.com/other/zentixua/x.git"))

    def test_pointer_once_per_session(self):
        out = self.tool("Read", file_path=str(self.repo.root / "src/a/B.java"))
        self.assertIn(".claude/rules/area.md", self.context(out))
        self.assertIsNone(self.tool("Edit", file_path=str(self.repo.root / "src/a/B.java")))

    def test_shell_and_grep_paths(self):
        out = self.tool("Bash", command="cd src; sed -n 1,20p a/B.java | head")
        self.assertIn("area.md", self.context(out))
        self.data = Path(self.tmp.name) / "data2"  # fresh session state
        self.assertIn("area.md", self.context(self.tool("Grep", pattern="class", path=str(self.repo.root / "src/a"))))

    def test_git_show_rev_path(self):
        out = self.tool("Bash", command=f"git -C {self.repo.root} show origin/main:tools/y.py")
        self.assertIn("area.md", self.context(out))

    def test_ancestor_directory_is_not_an_area(self):
        self.assertIsNone(self.tool("Grep", pattern="x", path=str(self.repo.root / "src")))

    def test_natively_loaded_rule_is_not_repeated(self):
        self.run_hook({"hook_event_name": "InstructionsLoaded", "file_path": str(self.repo.root / ".claude/rules/area.md"),
                       "load_reason": "path_glob_match"})
        self.assertIsNone(self.tool("Read", file_path=str(self.repo.root / "src/a/B.java")))

    def test_listing_the_rule_does_not_count(self):
        self.tool("Bash", command="ls .claude/rules/area.md")
        self.assertIn("area.md", self.context(self.tool("Read", file_path=str(self.repo.root / "src/a/B.java"))))

    def test_rg_listing_the_rule_does_not_count(self):
        self.tool("Bash", command="rg -l paths .claude/rules/area.md")
        self.assertIn("area.md", self.context(self.tool("Read", file_path=str(self.repo.root / "src/a/B.java"))))

    def test_shell_edit_of_dirty_file_counts(self):
        with open(self.repo.root / "src/a/B.java", "a") as f:
            f.write("// dirty before the session\n")
        self.run_hook({"hook_event_name": "SessionStart"})
        with open(self.repo.root / "src/a/B.java", "a") as f:
            f.write("// shell edit in the session\n")
        self.assertEqual(self.run_hook({"hook_event_name": "Stop"})["decision"], "block")

    def test_reading_the_rule_counts(self):
        self.tool("Bash", command="cat .claude/rules/area.md")
        self.assertIsNone(self.tool("Read", file_path=str(self.repo.root / "src/a/B.java")))

    def test_outside_scope_silent(self):
        other = Path(self.tmp.name) / "other"
        other.mkdir()
        sh(other, "git", "init", "-q")
        (other / ".claude/rules").mkdir(parents=True)
        (other / ".claude/rules/area.md").write_text(RULE)
        (other / "src/a").mkdir(parents=True)
        self.assertIsNone(self.tool("Read", file_path=str(other / "src/a/B.java")))

    def test_stop_checklist_once_after_change(self):
        self.assertIsNone(self.run_hook({"hook_event_name": "Stop"}))  # nothing changed: no checklist
        self.tool("Edit", file_path=str(self.repo.root / "src/a/B.java"))
        out = self.run_hook({"hook_event_name": "Stop"})
        self.assertEqual(out["decision"], "block")
        self.assertIn("team.md", out["reason"])
        self.assertIsNone(self.run_hook({"hook_event_name": "Stop"}))

    def test_shell_edit_counts_as_change(self):
        self.run_hook({"hook_event_name": "SessionStart"})
        with open(self.repo.root / "src/a/B.java", "a") as f:
            f.write("// shell append\n")
        self.assertEqual(self.run_hook({"hook_event_name": "Stop"})["decision"], "block")

    def test_rule_load_does_not_erase_change(self):
        self.tool("Edit", file_path=str(self.repo.root / "src/a/B.java"))
        self.run_hook({"hook_event_name": "InstructionsLoaded", "file_path": str(self.repo.root / ".claude/rules/area.md")})
        self.assertEqual(self.run_hook({"hook_event_name": "Stop"})["decision"], "block")

    def test_stop_respects_stop_hook_active(self):
        self.tool("Bash", command="git commit -qm x")
        self.assertIsNone(self.run_hook({"hook_event_name": "Stop", "stop_hook_active": True}))

    def test_ignored_files_do_not_count(self):
        p = self.repo.write("run/report.md", "x")
        self.tool("Write", file_path=str(p))
        self.assertIsNone(self.run_hook({"hook_event_name": "Stop"}))

    def test_stale_checkout_report(self):
        self.assertIsNone(self.run_hook({"hook_event_name": "SessionStart"}))
        clone = Path(self.tmp.name) / "clone"
        sh(Path(self.tmp.name), "git", "clone", "-q", str(self.repo.origin), str(clone))
        sh(clone, "git", "config", "user.email", "t@example.org")
        sh(clone, "git", "config", "user.name", "t")
        (clone / "AGENTS.md").write_text(AGENTS + "- new rule\n")
        sh(clone, "git", "commit", "-qam", "rule")
        sh(clone, "git", "push", "-q", "origin", "main")
        sh(self.repo.root, "git", "fetch", "-q", "origin")
        out = self.run_hook({"hook_event_name": "SessionStart"})
        text = self.context(out)
        self.assertIn("lacks 1 commit", text)
        self.assertIn("+- new rule", text)

    def test_memory_lint(self):
        mem = Path(self.tmp.name) / "memory"
        mem.mkdir()
        (mem / "MEMORY.md").write_text("- [a](a.md)\n")
        (mem / "a.md").write_text("---\nname: a\ndescription: When x: do y\nmetadata:\n  type: project\n---\nfact\n")
        out = self.tool("Write", file_path=str(mem / "a.md"))
        self.assertIn("double quotes", self.context(out))
        (mem / "a.md").write_text('---\nname: a\ndescription: "When x: do y"\nmetadata:\n  type: project\n---\nfact\n')
        self.assertIsNone(self.tool("Write", file_path=str(mem / "a.md")))

    def test_hook_never_fails_on_garbage(self):
        r = subprocess.run([sys.executable, str(HOOK)], input="not json", capture_output=True, text=True)
        self.assertEqual(r.returncode, 0)


if __name__ == "__main__":
    unittest.main()
