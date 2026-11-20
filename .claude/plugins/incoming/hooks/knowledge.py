#!/usr/bin/env python3
"""Knowledge hooks of the `incoming` plugin: make every session read and save project knowledge.

Standard library only. Acts only inside git repositories whose `origin` is a zentixua repository; silent elsewhere
and never blocks a tool. One file for every event (hooks/hooks.json):

  SessionStart        the checkout is behind origin's default branch in AGENTS.md / CLAUDE.md / .claude/rules:
                      inject what changed there (sessions start in a shared checkout that is often days old).
  InstructionsLoaded  remember rule files Claude Code loaded natively, so the pointer below does not repeat them.
  PostToolUse         a tool touched a file covered by an area rule (`.claude/rules/*.md` `paths:`) that this session
                      has neither loaded nor been pointed to: one line naming the rule. Covers shell reads and
                      Grep/Glob, which never load path rules, and readers without path-rule loading. Also notes that
                      the session changed tracked files, and lints Claude memory files (frontmatter, sizes).
  Stop                once per session that changed tracked files: refuse the first stop with the routing checklist,
                      so new knowledge lands in its one home.
CLI: `knowledge.py --stale-report DIR` prints the SessionStart text for DIR (used by `delegate` for Codex).
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import time
from pathlib import Path

OWNER = "zentixua/"  # repo scope: origin URL must contain this
KNOWLEDGE = ["AGENTS.md", "CLAUDE.md", ".claude/rules"]
FETCH_EVERY = 600  # s between fetches of one repo from SessionStart
DIFF_MAX = 6000  # chars of AGENTS.md diff injected; beyond that only the instruction
MEMORY_FILE_MAX = 4096  # B: recall shows the first 4 KB of a memory topic file (standard §3)
MEMORY_INDEX_MAX = 25_000  # B: auto memory loads the first 25 KB / 200 lines of MEMORY.md (docs: memory)
MEMORY_TYPES = {"user", "feedback", "project", "reference"}
EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}

CHECKLIST = (
    "Knowledge check before you finish (asked once per session). Did this session learn something a future agent "
    "on this project must know: a decision of Artem, a rule, a trap (what failed, why, the right way), where "
    "something lives? Give each fact its one home, rewriting the old text instead of appending, no history:\n"
    "1. Mod rule, concept, process step or Artem's engineering/product decision -> incoming AGENTS.md, or the area "
    "rule .claude/rules/<area>.md for one code area, in the PR (public repo: no hosts, IPs, private paths), with a "
    "test or check that fails if it drifts.\n"
    "2. Team or ops rule (roles, what needs Artem's word, laptop, servers, media) -> open-skies "
    ".claude/rules/team.md by PR.\n"
    "3. Status, measurements, open questions -> your report or final message; the lead keeps project memory.\n"
    "4. A procedure -> the skill that owns it.\n"
    "Claude Code auto memory is not a home: no other agent sees it. If nothing is new, say so in one line and finish."
)


# ---------------------------------------------------------------- helpers

def git(root: Path, *args: str, timeout: float = 5) -> str | None:
    try:
        r = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return r.stdout if r.returncode == 0 else None


def repo_root(path: Path) -> Path | None:
    """Nearest ancestor (or the path itself) holding .git; None outside repositories."""
    p = path if path.is_dir() else path.parent
    for d in [p, *p.parents]:
        if (d / ".git").exists():
            return d
    return None


_scope: dict[Path, bool] = {}


def in_scope(root: Path) -> bool:
    if root not in _scope:
        url = git(root, "remote", "get-url", "origin") or ""
        _scope[root] = OWNER in url
    return _scope[root]


def state_file(session: str) -> Path:
    base = Path(os.environ.get("CLAUDE_PLUGIN_DATA") or tempfile.gettempdir()) / "incoming-knowledge"
    base.mkdir(parents=True, exist_ok=True)
    return base / f"{hashlib.sha256(session.encode()).hexdigest()[:20]}.json"


def load_state(session: str) -> dict:
    try:
        return json.loads(state_file(session).read_text())
    except (OSError, ValueError):
        return {}


def save_state(session: str, st: dict) -> None:
    f = state_file(session)
    tmp = f.with_suffix(".tmp")
    tmp.write_text(json.dumps(st))
    tmp.replace(f)


def emit(event: str, text: str) -> None:
    print(json.dumps({"hookSpecificOutput": {"hookEventName": event, "additionalContext": text}}))


# ---------------------------------------------------------------- stale checkout

def default_ref(root: Path) -> str:
    head = (git(root, "symbolic-ref", "-q", "refs/remotes/origin/HEAD") or "").strip()
    return head.removeprefix("refs/remotes/") or "origin/main"


def fetch_if_old(root: Path, ref: str) -> None:
    gitdir = (git(root, "rev-parse", "--git-common-dir") or "").strip()
    if not gitdir:
        return
    fetch_head = (root / gitdir) if not os.path.isabs(gitdir) else Path(gitdir)
    fetch_head = fetch_head / "FETCH_HEAD"
    try:
        if time.time() - fetch_head.stat().st_mtime < FETCH_EVERY:
            return
    except OSError:
        pass
    git(root, "fetch", "-q", "origin", ref.split("/", 1)[1], timeout=8)


def stale_report(cwd: Path, fetch: bool = True) -> str | None:
    root = repo_root(cwd)
    if not root or not in_scope(root):
        return None
    ref = default_ref(root)
    if fetch:
        fetch_if_old(root, ref)
    missing = git(root, "log", "--format=%h %s", f"HEAD..{ref}", "--", *KNOWLEDGE)
    if not missing or not missing.strip():
        return None
    commits = missing.strip().splitlines()
    files = (git(root, "diff", "--name-only", f"HEAD...{ref}", "--", *KNOWLEDGE) or "").split()
    text = [
        f"This checkout ({root}) lacks {len(commits)} commit(s) of {ref} that change project instructions: "
        + ", ".join(files) + ". The instructions loaded at start are outdated there; the current text wins.",
    ]
    diff = git(root, "diff", f"HEAD...{ref}", "--", "AGENTS.md") or ""
    if "AGENTS.md" in files:
        if len(diff) <= DIFF_MAX:
            text.append(f"Changes to AGENTS.md on {ref}:\n{diff}")
        else:
            text.append(f"Read the current AGENTS.md now: git -C {root} show {ref}:AGENTS.md")
    rules = [f for f in files if f.startswith(".claude/rules/")]
    if rules:
        text.append(f"Changed area rules: read them as `git -C {root} show {ref}:<file>` when you work there: "
                    + ", ".join(rules))
    text.append(f"Work in a worktree from {ref} (git worktree add ... {ref}), not in this checkout.")
    return "\n".join(text)


# ---------------------------------------------------------------- area rules

def glob_regex(glob: str) -> re.Pattern:
    out, i = "", 0
    while i < len(glob):
        c = glob[i]
        if glob.startswith("**/", i):
            out += "(?:.*/)?"
            i += 3
            continue
        if glob.startswith("**", i):
            out += ".*"
            i += 2
            continue
        if c == "*":
            out += "[^/]*"
        elif c == "?":
            out += "[^/]"
        elif c == "{":
            j = glob.index("}", i)
            out += "(?:" + "|".join(re.escape(a) for a in glob[i + 1:j].split(",")) + ")"
            i = j + 1
            continue
        else:
            out += re.escape(c)
        i += 1
    return re.compile(out + r"\Z")


def static_prefix(glob: str) -> str:
    m = re.search(r"[*?{\[]", glob)
    head = glob[: m.start()] if m else glob
    return head.rsplit("/", 1)[0] if m else head


def rule_paths(text: str) -> list[str]:
    m = re.match(r"---\n(.*?)\n---", text, re.DOTALL)
    if not m:
        return []
    out, inside = [], False
    for line in m.group(1).splitlines():
        if re.match(r"paths:\s*$", line):
            inside = True
            continue
        if inside:
            item = re.match(r"\s+-\s+[\"']?(.*?)[\"']?\s*$", line)
            if item:
                out.append(item.group(1))
            elif line.strip():
                inside = False
    return out


_rules: dict[Path, list[tuple[Path, list[str], int]]] = {}


def rules_of(root: Path) -> list[tuple[Path, list[str], int]]:
    if root not in _rules:
        found = []
        for f in sorted((root / ".claude" / "rules").glob("*.md")):
            text = f.read_text(encoding="utf-8", errors="replace")
            globs = rule_paths(text)
            if globs:
                found.append((f, globs, text.count("\n")))
        _rules[root] = found
    return _rules[root]


def covering(root: Path, rel: str, is_dir: bool) -> list[tuple[Path, int]]:
    hits = []
    for f, globs, lines in rules_of(root):
        for g in globs:
            if is_dir:
                prefix = static_prefix(g)
                ok = bool(prefix) and (rel == prefix or rel.startswith(prefix + "/"))
            else:
                ok = bool(glob_regex(g).match(rel))
            if ok:
                hits.append((f, lines))
                break
    return hits


PATHLIKE = re.compile(r"^[\w.@~+-]*[/.][\w./@~+*{},-]*$")


def tool_paths(tool: str, ti: dict, cwd: Path) -> list[Path]:
    raw: list[str] = []
    for key in ("file_path", "notebook_path", "path"):
        if isinstance(ti.get(key), str):
            raw.append(ti[key])
    if tool == "Glob" and isinstance(ti.get("pattern"), str):
        base = ti.get("path") or "."
        raw.append(os.path.join(base, static_prefix(ti["pattern"]) or "."))
    if tool == "Bash" and isinstance(ti.get("command"), str):
        try:
            tokens = shlex.split(ti["command"], comments=True)
        except ValueError:
            tokens = ti["command"].split()
        git_c = None
        for i, t in enumerate(tokens):
            if t == "-C" and i + 1 < len(tokens):
                git_c = tokens[i + 1]
            if ":" in t and not t.startswith(("http", "/")):
                t = t.split(":", 1)[1]  # git show REV:path
            if PATHLIKE.match(t) and not t.startswith("-"):
                raw.append(t)
        if git_c:
            raw = [r if os.path.isabs(r) else os.path.join(git_c, r) for r in raw]
    out = []
    for r in raw:
        p = Path(os.path.expanduser(r))
        out.append(p if p.is_absolute() else cwd / p)
    return out


def area_pointers(event: dict, st: dict) -> list[str]:
    cwd = Path(event.get("cwd") or os.getcwd())
    tool = event.get("tool_name", "")
    seen = set(st.get("rules", []))
    notes = []
    for p in tool_paths(tool, event.get("tool_input") or {}, cwd)[:40]:
        try:
            p = p.resolve()
        except OSError:
            continue
        root = repo_root(p) if p.exists() or p.parent.exists() else None
        if not root or not in_scope(root):
            continue
        rel = p.relative_to(root).as_posix() if p != root else ""
        if rel.startswith(".claude/rules/") and rel.endswith(".md"):
            seen.add(str(p))  # the session reads the rule itself
            continue
        for f, lines in covering(root, rel, p.is_dir()):
            if str(f) not in seen:
                seen.add(str(f))
                notes.append(f"Area rule {f.relative_to(root)} ({lines} lines) covers {rel}: read it in full "
                             f"({f}) before changing this area; its pitfalls are not in AGENTS.md.")
    st["rules"] = sorted(seen)
    return notes


# ---------------------------------------------------------------- changes and memory lint

def changed_tracked(event: dict) -> bool:
    tool = event.get("tool_name", "")
    ti = event.get("tool_input") or {}
    cwd = Path(event.get("cwd") or os.getcwd())
    if tool == "Bash":
        cmd = ti.get("command") or ""
        if re.search(r"\bgit\b[^|;&]*\bcommit\b", cmd):
            root = repo_root(cwd)
            return bool(root and in_scope(root))
        return False
    if tool in EDIT_TOOLS:
        p = Path(ti.get("file_path") or ti.get("notebook_path") or "")
        root = repo_root(p) if p.is_absolute() else None
        if root and in_scope(root):
            return git(root, "check-ignore", "-q", str(p)) is None  # ignored files (mod/run/...) don't count
    return False


def frontmatter(text: str) -> dict[str, str] | None:
    m = re.match(r"---\n(.*?)\n---\n", text, re.DOTALL)
    if not m:
        return None
    pairs = (re.match(r"\s*([\w-]+):\s?(.*)$", line) for line in m.group(1).splitlines())
    return {p.group(1): p.group(2) for p in pairs if p}


def lint_memory(path: Path) -> list[str]:
    """Problems of a Claude memory file (a .md beside MEMORY.md, outside git repositories)."""
    if path.suffix != ".md" or not (path.parent / "MEMORY.md").exists() or repo_root(path):
        return []
    raw = path.read_bytes()
    if path.name == "MEMORY.md":
        return [f"MEMORY.md is {len(raw)} B: only the first {MEMORY_INDEX_MAX} B / 200 lines load; move detail "
                "into topic files"] if len(raw) > MEMORY_INDEX_MAX or raw.count(b"\n") > 200 else []
    problems = []
    if len(raw) > MEMORY_FILE_MAX:
        problems.append(f"{path.name} is {len(raw)} B > 4 KB: compress or split it")
    meta = frontmatter(raw.decode("utf-8", "replace"))
    if meta is None:
        return problems + [f"{path.name}: no --- frontmatter (name, description, metadata.type)"]
    for key in ("name", "description", "type"):
        if not meta.get(key):
            problems.append(f"{path.name}: frontmatter lacks `{key}`")
    if meta.get("type") and meta["type"].strip("'\"") not in MEMORY_TYPES:
        problems.append(f"{path.name}: type `{meta['type']}` not in {sorted(MEMORY_TYPES)}")
    desc = meta.get("description", "")
    if desc and not desc.startswith(("'", '"')) and (": " in desc or " #" in desc):
        problems.append(f'{path.name}: description has ": " or " #": wrap it in double quotes (YAML breaks or cuts it)')
    if meta.get("name") and meta["name"].strip("'\"") != path.stem:
        problems.append(f"{path.name}: name differs from the file name")
    return problems


# ---------------------------------------------------------------- events

def main() -> int:
    if sys.argv[1:2] == ["--stale-report"]:
        text = stale_report(Path(sys.argv[2] if len(sys.argv) > 2 else "."))
        if text:
            print(text)
        return 0
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return 0
    name = event.get("hook_event_name")
    session = event.get("session_id") or "unknown"
    if name == "SessionStart":
        text = stale_report(Path(event.get("cwd") or os.getcwd()))
        if text:
            emit("SessionStart", text)
        return 0
    st = load_state(session)
    if name == "InstructionsLoaded":
        fp = event.get("file_path")
        if fp:
            st["rules"] = sorted(set(st.get("rules", [])) | {str(Path(fp).resolve())})
            save_state(session, st)
        return 0
    if name == "PostToolUse":
        notes = area_pointers(event, st)
        if not st.get("changed") and changed_tracked(event):
            st["changed"] = True
        tool = event.get("tool_name")
        if tool in EDIT_TOOLS:
            fp = (event.get("tool_input") or {}).get("file_path")
            if fp and Path(fp).is_file():
                notes += lint_memory(Path(fp))
        save_state(session, st)
        if notes:
            emit("PostToolUse", "\n".join(notes))
        return 0
    if name == "Stop":
        if event.get("stop_hook_active") or not st.get("changed") or st.get("checklist"):
            return 0
        st["checklist"] = True
        save_state(session, st)
        print(json.dumps({"decision": "block", "reason": CHECKLIST}))
        return 0
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # a hook must never break the session
        print(f"incoming knowledge hook: {e!r}", file=sys.stderr)
        sys.exit(0)
