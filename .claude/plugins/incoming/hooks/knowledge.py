#!/usr/bin/env python3
"""Knowledge hooks of the `incoming` plugin: make every session read and save project knowledge.

Standard library only. Acts only inside git repositories whose `origin` is a github.com/zentixua repository; silent
elsewhere and never blocks a tool. One file for every event (hooks/hooks.json):

  SessionStart        the checkout is behind origin's default branch in AGENTS.md / CLAUDE.md / .claude/rules:
                      inject what changed there (sessions start in a shared checkout that is often days old). Also
                      records the checkout's state, so Stop can see changes made by shell commands.
  InstructionsLoaded  remember rule files Claude Code loaded natively, so the pointer below does not repeat them.
  PostToolUse         a tool touched a file covered by an area rule (`.claude/rules/*.md` `paths:`) that this session
                      has neither loaded nor been pointed to: one line naming the rule. Covers shell reads and
                      Grep/Glob, which never load path rules, and readers without path-rule loading. Also notes edits
                      of tracked files and lints Claude memory files (frontmatter, sizes).
  Stop                once per session that changed tracked files (edit tools, or the checkout's HEAD/status moved):
                      refuse the first stop with the routing checklist, so new knowledge lands in its one home.
CLI: `knowledge.py --stale-report DIR` prints the SessionStart text for DIR (used by `delegate` for Codex).

Session state is a directory of small files, each written by one atomic create/append, so concurrent hook processes
(InstructionsLoaded runs asynchronously) never overwrite each other. Network work at SessionStart is one bounded
fetch per repository every FETCH_EVERY seconds, attempted or not.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shlex
import stat
import subprocess
import sys
import tempfile
import time
from pathlib import Path

# Repo scope: an origin on github.com under zentixua (KNOWLEDGE_ORIGIN_RE overrides it for tests).
DEFAULT_ORIGIN_RE = r"^(?:https://github\.com/|git@github\.com:|ssh://git@github\.com/)zentixua/[\w.-]+?(?:\.git)?/?$"
ORIGIN = re.compile(os.environ.get("KNOWLEDGE_ORIGIN_RE", DEFAULT_ORIGIN_RE))
KNOWLEDGE = ["AGENTS.md", "CLAUDE.md", ".claude/rules"]
FETCH_EVERY = 600  # s between fetch attempts for one repository
FETCH_TIMEOUT = 4  # s: a slow or offline remote delays a session start at most this long
DIFF_MAX = 6000  # chars of AGENTS.md diff injected; beyond that only the instruction
RULE_MAX = 1_000_000  # B: larger "rules" are not read (and FIFOs or devices never)
MEMORY_FILE_MAX = 4096  # B: recall shows the first 4 KB of a memory topic file (standard §3)
MEMORY_INDEX_MAX = 25_000  # B: auto memory loads the first 25 KB / 200 lines of MEMORY.md (docs: memory)
MEMORY_TYPES = {"user", "feedback", "project", "reference"}
EDIT_TOOLS = {"Edit", "Write", "MultiEdit", "NotebookEdit"}
SHOW_COMMANDS = {"cat", "head", "tail", "sed", "less", "more", "bat", "nl", "awk"}  # print the file, not matches

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
        r = subprocess.run(["git", "-C", str(root), *args], capture_output=True, text=True, timeout=timeout,
                           stdin=subprocess.DEVNULL)
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
        url = (git(root, "remote", "get-url", "origin") or "").strip()
        _scope[root] = bool(ORIGIN.match(url))
    return _scope[root]


def data_dir() -> Path:
    return Path(os.environ.get("CLAUDE_PLUGIN_DATA") or Path(tempfile.gettempdir()) / "incoming-knowledge")


class State:
    """Per-session state as separate files: `rules` (appended lines), `changed`, `checklist`, `start` (markers)."""

    def __init__(self, session: str):
        self.dir = data_dir() / "sessions" / hashlib.sha256(session.encode()).hexdigest()[:20]
        self.dir.mkdir(parents=True, exist_ok=True)

    def rules(self) -> set[str]:
        try:
            return set((self.dir / "rules").read_text().splitlines())
        except OSError:
            return set()

    def add_rules(self, paths: list[str]) -> None:
        if paths:
            with open(self.dir / "rules", "a") as f:  # one small append: atomic between processes
                f.write("".join(p + "\n" for p in paths))

    def has(self, name: str) -> bool:
        return (self.dir / name).exists()

    def mark(self, name: str, text: str = "") -> bool:
        """Create the marker; False if it already existed (exactly one process wins)."""
        try:
            fd = os.open(self.dir / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        except FileExistsError:
            return False
        with os.fdopen(fd, "w") as f:
            f.write(text)
        return True

    def read(self, name: str) -> str:
        try:
            return (self.dir / name).read_text()
        except OSError:
            return ""


def emit(event: str, text: str, session: str = "?") -> None:
    print(json.dumps({"hookSpecificOutput": {"hookEventName": event, "additionalContext": text}}))
    log(f"{event}:{'stale' if event == 'SessionStart' else 'pointer'}", session, text)


def log(kind: str, session: str, text: str = "") -> None:
    """Evidence that the hook acted in a real session (read by the weekly knowledge self-test)."""
    if os.environ.get("CLAUDE_PLUGIN_DATA"):
        first = " | ".join(text.splitlines())[:400] if text else ""
        with open(data_dir() / "fired.log", "a") as f:
            f.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} {kind} {session} {first}\n")


# ---------------------------------------------------------------- stale checkout

def default_ref(root: Path) -> str:
    head = (git(root, "symbolic-ref", "-q", "refs/remotes/origin/HEAD") or "").strip()
    return head.removeprefix("refs/remotes/") or "origin/main"


def fetch_if_due(root: Path, ref: str) -> None:
    common = (git(root, "rev-parse", "--path-format=absolute", "--git-common-dir") or "").strip()
    if not common:
        return
    stamp = data_dir() / "fetch" / hashlib.sha256(common.encode()).hexdigest()[:20]
    stamp.parent.mkdir(parents=True, exist_ok=True)
    try:
        if time.time() - stamp.stat().st_mtime < FETCH_EVERY:
            return
    except OSError:
        pass
    stamp.touch()  # an attempt counts even if it fails: an offline remote costs one timeout per FETCH_EVERY
    git(root, "fetch", "-q", "origin", ref.split("/", 1)[1], timeout=FETCH_TIMEOUT)


def stale_report(cwd: Path, fetch: bool = True) -> str | None:
    root = repo_root(cwd)
    if not root or not in_scope(root):
        return None
    ref = default_ref(root)
    if fetch:
        fetch_if_due(root, ref)
    missing = git(root, "log", "--format=%h", f"HEAD..{ref}", "--", *KNOWLEDGE)
    if not missing or not missing.strip():
        return None
    commits = missing.split()
    files = (git(root, "diff", "--name-only", f"HEAD...{ref}", "--", *KNOWLEDGE) or "").split()
    q, r = shlex.quote(str(root)), shlex.quote(ref)
    text = [
        f"This checkout lacks {len(commits)} commit(s) of {ref} that change project instructions ("
        + ", ".join(files) + "). The instructions loaded at start are outdated there; the current text wins.",
    ]
    diff = git(root, "diff", f"HEAD...{ref}", "--", "AGENTS.md") or ""
    if "AGENTS.md" in files:
        if len(diff) <= DIFF_MAX:
            text.append(f"Changes to AGENTS.md on {ref}:\n{diff}")
        else:
            text.append(f"Read the current AGENTS.md now: git -C {q} show {shlex.quote(ref + ':AGENTS.md')}")
    rules = [f for f in files if f.startswith(".claude/rules/")]
    if rules:
        text.append(f"Changed area rules (read with git -C {q} show {r}:<file> when you work there): "
                    + ", ".join(rules))
    text.append(f"Work in a worktree from {ref} (git -C {q} worktree add <dir> {r}), not in this checkout.")
    return "\n".join(text)


def checkout_state(root: Path) -> str:
    head = (git(root, "rev-parse", "HEAD") or "").strip()
    diff = git(root, "diff", "HEAD", "--no-color", "--no-ext-diff") or ""  # content, so edits to dirty files count
    return head + " " + hashlib.sha256(diff.encode()).hexdigest()


# ---------------------------------------------------------------- area rules

def glob_regex(glob: str) -> re.Pattern:
    """Claude Code `paths:` glob → regex: `**` any depth, `*`/`?` within a segment, `{a,b}`, `[...]`; a glob without
    `/` matches the file name at any depth."""
    out, i = "", 0
    if glob.startswith("/"):
        glob = glob[1:]  # anchored at the project root
    elif "/" not in glob:
        out = "(?:.*/)?"
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
        elif c == "{" and "}" in glob[i:]:
            j = glob.index("}", i)
            out += "(?:" + "|".join(re.escape(a) for a in glob[i + 1:j].split(",")) + ")"
            i = j + 1
            continue
        elif c == "[" and "]" in glob[i + 1:]:
            j = glob.index("]", i + 1)
            body = glob[i + 1:j]
            out += "[" + ("^" + body[1:] if body.startswith("!") else body).replace("\\", "\\\\") + "]"
            i = j + 1
            continue
        else:
            out += re.escape(c)
        i += 1
    return re.compile(out + r"\Z")


def static_prefix(glob: str) -> str:
    m = re.search(r"[*?{\[]", glob)
    head = glob[: m.start()] if m else glob
    return head.rsplit("/", 1)[0] if m and "/" in head else ("" if m else head)


HEADER_LINE = re.compile(
    r"^(?:paths:\s*|\s+-\s+(?:\"[^\"]+\"|'[^']+'|[^\s\"'#\[{][^#]*?)\s*"
    r"|description:\s+(?:\"[^\"]*\"|'[^']*'|[^\s\"'\[{&*!|>%@`][^#]*?)\s*|)$")


def rule_paths(text: str) -> list[str] | None:
    """The `paths:` globs of a rule file; None when the frontmatter is missing or not the strict form we use."""
    m = re.match(r"---\n(.*?)\n---(?:\n|\Z)", text, re.DOTALL)
    if not m:
        return None
    lines = m.group(1).splitlines()
    if not all(HEADER_LINE.match(line) for line in lines) or not any(line.startswith("paths:") for line in lines):
        return None
    out, inside = [], False
    for line in lines:
        if line.startswith("paths:"):
            inside = True
            continue
        item = re.match(r"\s+-\s+[\"']?(.*?)[\"']?\s*$", line)
        if inside and item:
            out.append(item.group(1))
        elif line.strip():
            inside = False
    return out


def regular(f: Path) -> bool:
    try:
        st = f.stat()
    except OSError:
        return False
    return stat.S_ISREG(st.st_mode) and st.st_size <= RULE_MAX


_rules: dict[Path, list[tuple[Path, list[str], int]]] = {}


def rules_of(root: Path) -> list[tuple[Path, list[str], int]]:
    if root not in _rules:
        found = []
        for f in sorted((root / ".claude" / "rules").glob("*.md")):
            if not regular(f):
                continue
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


def shell_paths(command: str, cwd: Path) -> list[tuple[Path, bool]]:
    """(path, shown) for path-like words of a shell command, resolved against `cd`/`git -C` dirs as they change;
    shown = the word is an argument of a command that prints file content."""
    try:
        tokens = shlex.split(command, comments=True, posix=True)
    except ValueError:
        tokens = command.split()
    out: list[tuple[Path, bool]] = []
    here, cmd, git_c, i = cwd, None, None, 0
    while i < len(tokens):
        t = tokens[i]
        if t in ("&&", "||", ";", "|", "&"):
            cmd, git_c = None, None
            i += 1
            continue
        ends = t.endswith(";")  # `cd dir; …`: the separator sticks to the word
        t = t.rstrip(";")
        i += 1
        if cmd is None:
            cmd = os.path.basename(t)
            if cmd == "cd" and i < len(tokens):
                d = tokens[i]
                ends = d.endswith(";")
                d = Path(os.path.expanduser(d.rstrip(";")))
                here = d if d.is_absolute() else here / d
                i += 1
                cmd = None
            if ends:
                cmd, git_c = None, None
            continue
        if cmd == "git" and t == "-C" and i < len(tokens):
            git_c = tokens[i]
            i += 1
            continue
        word = t.split(":", 1)[1] if ":" in t and not t.startswith(("http", "/")) else t  # git show REV:path
        if PATHLIKE.match(word) and not word.startswith("-"):
            p = Path(os.path.expanduser(word))
            base = Path(git_c) if git_c else here
            if not base.is_absolute():
                base = here / base
            out.append((p if p.is_absolute() else base / p, cmd in SHOW_COMMANDS or (cmd == "git" and word != t)))
        if ends:
            cmd, git_c = None, None
    return out


def tool_paths(tool: str, ti: dict, cwd: Path) -> list[tuple[Path, bool]]:
    out: list[tuple[Path, bool]] = []
    for key in ("file_path", "notebook_path", "path"):
        if isinstance(ti.get(key), str):
            out.append((Path(ti[key]), tool == "Read"))
    if tool == "Glob" and isinstance(ti.get("pattern"), str):
        out.append((Path(ti.get("path") or ".") / (static_prefix(ti["pattern"]) or "."), False))
    if tool == "Bash" and isinstance(ti.get("command"), str):
        out += shell_paths(ti["command"], cwd)
    return [(p if p.is_absolute() else cwd / p, shown) for p, shown in out]


def area_pointers(event: dict, st: State) -> list[str]:
    cwd = Path(event.get("cwd") or os.getcwd())
    seen = st.rules()
    notes, new = [], []
    for p, shown in tool_paths(event.get("tool_name", ""), event.get("tool_input") or {}, cwd)[:40]:
        try:
            p = p.resolve()
        except OSError:
            continue
        root = repo_root(p) if p.exists() or p.parent.exists() else None
        if not root or not in_scope(root):
            continue
        rel = p.relative_to(root).as_posix() if p != root else ""
        if rel.startswith(".claude/rules/") and rel.endswith(".md"):
            if shown and str(p) not in seen:
                seen.add(str(p))
                new.append(str(p))  # the session printed the rule itself
            continue
        for f, lines in covering(root, rel, p.is_dir()):
            if str(f) not in seen:
                seen.add(str(f))
                new.append(str(f))
                notes.append(f"Area rule .claude/rules/{f.name} ({lines} lines) covers {rel}: read it in full before "
                             "changing this area; its pitfalls are not in AGENTS.md.")
    st.add_rules(new)
    return notes


# ---------------------------------------------------------------- changes and memory lint

def edited_tracked(event: dict) -> bool:
    if event.get("tool_name") not in EDIT_TOOLS:
        return False
    ti = event.get("tool_input") or {}
    p = Path(ti.get("file_path") or ti.get("notebook_path") or "")
    root = repo_root(p) if p.is_absolute() else None
    return bool(root and in_scope(root) and git(root, "check-ignore", "-q", str(p)) is None)


def frontmatter(text: str) -> dict[str, str] | None:
    m = re.match(r"---\n(.*?)\n---\n", text, re.DOTALL)
    if not m:
        return None
    pairs = (re.match(r"\s*([\w-]+):\s?(.*)$", line) for line in m.group(1).splitlines())
    return {p.group(1): p.group(2) for p in pairs if p}


def lint_memory(path: Path) -> list[str]:
    """Problems of a Claude memory file (a .md beside MEMORY.md, outside git repositories)."""
    if path.suffix != ".md" or not (path.parent / "MEMORY.md").exists() or repo_root(path) or not regular(path):
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
    cwd = Path(event.get("cwd") or os.getcwd())
    st = State(session)
    if name == "SessionStart":
        root = repo_root(cwd)
        if root and in_scope(root):
            st.mark("start", f"{root}\n{checkout_state(root)}")
        text = stale_report(cwd)
        if text:
            emit("SessionStart", text, session)
        return 0
    if name == "InstructionsLoaded":
        fp = event.get("file_path")
        if fp:
            st.add_rules([str(Path(fp).resolve())])
            log("InstructionsLoaded", session, fp)
        return 0
    if name == "PostToolUse":
        notes = area_pointers(event, st)
        if not st.has("changed") and edited_tracked(event):
            st.mark("changed")
        if event.get("tool_name") in EDIT_TOOLS:
            fp = (event.get("tool_input") or {}).get("file_path")
            if fp:
                notes += lint_memory(Path(fp))
        if notes:
            emit("PostToolUse", "\n".join(notes), session)
        return 0
    if name == "Stop":
        if event.get("stop_hook_active") or st.has("checklist"):
            return 0
        changed = st.has("changed")
        start = st.read("start").splitlines()
        if not changed and len(start) == 2 and Path(start[0]).is_dir():
            changed = checkout_state(Path(start[0])) != start[1]  # shell edits and commits in the session's checkout
        if changed and st.mark("checklist"):
            print(json.dumps({"decision": "block", "reason": CHECKLIST}))
            log("Stop:checklist", session)
        return 0
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # a hook must never break the session
        print(f"incoming knowledge hook: {e!r}", file=sys.stderr)
        sys.exit(0)
