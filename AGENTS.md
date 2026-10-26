# AGENTS.md — for agents other than Claude Code (Codex and others)

Project rules live in the root `CLAUDE.md` and the area files in `.claude/rules/`. They apply to everyone working on
the mod, human or agent; this file only explains how to load them and how other agents' work differs.

- Before working, read all of `CLAUDE.md`: people, physics and presentation principles, workflow, checks, and general
  code rules.
- Area files do not load automatically. Before reading or editing files, open every area file whose `paths:` (in the
  header) covers them: a path often belongs to several areas. The list is in the “`.claude/rules/` files” section of
  `CLAUDE.md`; when planning work before opening files, read the area files based on the descriptions there.
- Where `CLAUDE.md` names Claude Code tools (Read, Edit, Write), it refers to Claude Code; the underlying rule applies
  to you too.

Differences:
- One review round, by a model that did not write the code, without the author's conversation or conclusions: use
  the diff and surrounding code, not the PR description; for anything players see or hear, also use the PR's frames
  and WAV files. Only serious errors block (wrong behavior, crashes, exceeding the tick budget or frame cost limit);
  everything else is optional.
- End the commit with a `Co-Authored-By:` line naming your own agent (its name and address), not another one.
