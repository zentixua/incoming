#!/usr/bin/env python3
"""CI gate for the agent knowledge base: AGENTS.md, CLAUDE.md, .claude/rules, the `incoming` plugin.

Every agent (claude.ai project threads, Claude Code, Codex) starts from these files, so a structural slip silently
changes what agents know. Checks:
  - CLAUDE.md is exactly `@AGENTS.md` (Claude Code reads AGENTS.md through it; Codex reads AGENTS.md directly).
  - AGENTS.md <= AGENTS_MAX bytes: Codex reads at most 32 KiB of AGENTS.md files in total (project_doc_max_bytes),
    and the private team rules (~/.codex/AGENTS.md, <= 8 KiB) share that budget.
  - Every .claude/rules/*.md has a `paths:` list whose globs each match a tracked file (a rename leaves no dead
    rule), and appears in AGENTS.md's rule list; every rule AGENTS.md lists exists.
  - The plugin manifest, marketplace and hooks JSON parse; every hook script they name exists.
  - Public repository: no IPv4 addresses (except loopback/any/multicast), private mounts, e-mail addresses or
    claude.ai links in AGENTS.md, rules or plugin files.
Run: python3 tools/check_knowledge.py [repo root]; exit 1 with the list of problems.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

AGENTS_MAX = 24 * 1024
PLUGIN = Path(".claude/plugins/incoming")
PRIVATE = [
    (re.compile(r"\b(?!127\.0\.0\.1\b|0\.0\.0\.0\b|224\.)(?:\d{1,3}\.){3}\d{1,3}\b"), "IPv4 address"),
    (re.compile(r"/mnt/project-files"), "private claude.ai project files path"),
    (re.compile(r"claude\.ai/(?:chat|project|artifact)"), "claude.ai link"),
    (re.compile(r"[\w.+-]+@(?:gmail|proton|yahoo|outlook)\.\w+"), "e-mail address"),
]


def tracked(root: Path) -> list[str]:
    out = subprocess.run(["git", "-C", str(root), "ls-files"], capture_output=True, text=True, check=True).stdout
    return out.splitlines()


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
    for f in rules:
        text = f.read_text(encoding="utf-8")
        globs = rule_paths(text)
        if not globs:
            problems.append(f"{f.name}: no `paths:` frontmatter (an unpathed rule loads into every session and "
                            "every project thread; put session-wide rules in AGENTS.md)")
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
        if j.name == "hooks.json":
            for event, groups in data.get("hooks", {}).items():
                for g in groups:
                    for h in g.get("hooks", []):
                        for script in re.findall(r"\$\{CLAUDE_PLUGIN_ROOT\}/([\w./-]+)", h.get("command", "")):
                            if not (plugin / script).is_file():
                                problems.append(f"hooks.json {event}: {script} does not exist in the plugin")

    public = [agents_path, *rules, *[p for p in plugin.rglob("*") if p.is_file() and p.suffix in (".md", ".json", ".py")]]
    for f in public:
        for n, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
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
