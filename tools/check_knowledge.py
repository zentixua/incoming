#!/usr/bin/env python3
"""CI gate for the agent knowledge base: AGENTS.md, CLAUDE.md, .claude/rules, the `incoming` plugin.

Every agent (claude.ai project threads, Claude Code, Codex) starts from these files, so a structural slip silently
changes what agents know. Checks:
  - CLAUDE.md is exactly `@AGENTS.md` (Claude Code reads AGENTS.md through it; Codex reads AGENTS.md directly).
  - AGENTS.md <= AGENTS_MAX bytes: Codex stops reading project AGENTS.md files at 32 KiB (project_doc_max_bytes;
    its global ~/.codex/AGENTS.md is not counted, measured 20.11), and every line costs every session attention.
  - Every .claude/rules/*.md has strict frontmatter with a `paths:` list (Claude Code treats a rule with broken
    frontmatter as unscoped: it would load into every session) whose globs each match a tracked file (a rename leaves
    no dead rule), and appears in AGENTS.md's rule list; every rule AGENTS.md lists exists; no nested rule folders
    (Claude Code reads .claude/rules recursively, the list and the hooks do not).
  - The marketplace lists the `incoming` plugin at its folder; the plugin's hooks.json registers knowledge.py for
    SessionStart, InstructionsLoaded, PostToolUse and Stop; every hook script named exists.
  - Public repository: no IPv4 addresses (except loopback/any/multicast), private mounts, personal e-mail addresses
    or claude.ai links in AGENTS.md, rules or any plugin file.
Run: python3 tools/check_knowledge.py [repo root]; exit 1 with the list of problems.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

AGENTS_MAX = 28 * 1024
HOOK_EVENTS = {"SessionStart", "InstructionsLoaded", "PostToolUse", "Stop"}
HOOK_COMMAND = 'python3 "${CLAUDE_PLUGIN_ROOT}/hooks/knowledge.py"'
POST_TOOLS = {"Read", "Edit", "Write", "MultiEdit", "NotebookEdit", "Grep", "Glob", "Bash"}
PLUGIN = Path(".claude/plugins/incoming")
PRIVATE = [
    (re.compile(r"\b(?!127\.0\.0\.1\b|0\.0\.0\.0\b|224\.)(?:\d{1,3}\.){3}\d{1,3}\b"), "IPv4 address"),
    (re.compile(r"/mnt/project-files"), "private claude.ai project files path"),
    (re.compile(r"claude\.ai/(?:chat|project|artifact)"), "claude.ai link"),
    (re.compile(r"[\w.+-]+@(?!(?:example\.(?:com|org)|anthropic\.com|users\.noreply\.github\.com)\b)[\w-]+\.[\w.-]+"),
     "e-mail address"),
]


def tracked(root: Path) -> list[str]:
    out = subprocess.run(["git", "-C", str(root), "ls-files", "-z"], capture_output=True, text=True, check=True).stdout
    return [f for f in out.split("\0") if f]


def glob_regex(glob: str) -> re.Pattern:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / PLUGIN / "hooks"))
    import knowledge  # the hook's own matcher: the gate checks exactly what the hook uses

    return knowledge.glob_regex(glob)


def rule_paths(text: str) -> list[str] | None:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / PLUGIN / "hooks"))
    import knowledge

    if not text.startswith("---\n"):
        return None
    return knowledge.rule_paths(text)


def check(root: Path) -> list[str]:
    problems: list[str] = []
    claude = root / "CLAUDE.md"
    if not claude.is_file() or claude.read_text(encoding="utf-8").strip() != "@AGENTS.md":
        problems.append("CLAUDE.md must contain exactly `@AGENTS.md`")
    agents_path = root / "AGENTS.md"
    agents = agents_path.read_text(encoding="utf-8")
    size = len(agents.encode("utf-8"))
    if size > AGENTS_MAX:
        problems.append(f"AGENTS.md is {size} B > {AGENTS_MAX} B: move area detail into .claude/rules or a skill")

    files = tracked(root)
    listed = set(re.findall(r"`([\w-]+\.md)`", agents.split("## `.claude/rules/` files", 1)[-1]))
    rules = sorted((root / ".claude/rules").glob("*.md"))
    for f in sorted((root / ".claude/rules").rglob("*")):
        if f.parent != root / ".claude/rules" and f.is_file():
            problems.append(f"{f.relative_to(root)}: nested rule files are not supported; keep rules flat")
    for f in rules:
        text = f.read_text(encoding="utf-8")
        globs = rule_paths(text)
        if not globs:
            problems.append(f"{f.name}: no strict `paths:` frontmatter (only `paths:` with `  - \"glob\"` items and an "
                            "optional `description:`); an unscoped rule loads into every session and every project "
                            "thread: put session-wide rules in AGENTS.md")
        for g in globs or []:
            rx = glob_regex(g)
            if not any(rx.match(t) for t in files):
                problems.append(f"{f.name}: paths glob `{g}` matches no tracked file")
        if f.name not in listed:
            problems.append(f"{f.name}: missing from AGENTS.md's `.claude/rules/` files list")
    for name in sorted(listed - {f.name for f in rules}):
        problems.append(f"AGENTS.md lists `{name}` but .claude/rules/{name} does not exist")

    plugin = root / PLUGIN
    for j in [plugin / ".claude-plugin/plugin.json", plugin / "hooks/hooks.json", root / ".claude-plugin/marketplace.json"]:
        try:
            data = json.loads(j.read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            problems.append(f"{j.relative_to(root)}: {e}")
            continue
        if j.name == "marketplace.json":
            listed = {(p.get("name"), (root / p.get("source", "")).resolve()) for p in data.get("plugins", [])}
            if ("incoming", plugin.resolve()) not in listed:
                problems.append(f"marketplace.json does not list plugin `incoming` at ./{PLUGIN}")
        if j.name == "hooks.json":
            registered = set()
            for event, groups in data.get("hooks", {}).items():
                for g in groups:
                    if event == "PostToolUse" and any(h.get("command") == HOOK_COMMAND for h in g.get("hooks", [])):
                        missing = POST_TOOLS - set(g.get("matcher", "").split("|"))
                        if missing:
                            problems.append(f"hooks.json PostToolUse matcher lacks {', '.join(sorted(missing))}")
                    for h in g.get("hooks", []):
                        for script in re.findall(r"\$\{CLAUDE_PLUGIN_ROOT\}/([\w./-]+)", h.get("command", "")):
                            if not (plugin / script).is_file():
                                problems.append(f"hooks.json {event}: {script} does not exist in the plugin")
                            elif script == "hooks/knowledge.py" and h.get("command") == HOOK_COMMAND:
                                registered.add(event)
            for event in sorted(HOOK_EVENTS - registered):
                problems.append(f"hooks.json: {event} does not run hooks/knowledge.py")

    public = [agents_path, *rules, *[p for p in plugin.rglob("*") if p.is_file() and "__pycache__" not in p.parts]]
    for f in public:
        try:
            text = f.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue  # binary
        for n, line in enumerate(text.splitlines(), 1):
            for rx, what in PRIVATE:
                if rx.search(line):
                    problems.append(f"{f.relative_to(root)}:{n}: {what} in a public file")
    return problems


def main() -> int:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    problems = check(root)
    print("\n".join(problems) or "knowledge base OK")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
