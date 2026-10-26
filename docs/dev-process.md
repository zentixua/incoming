# Contributor workflow

Architecture and area-specific rules are in [AGENTS.md](../AGENTS.md) and `.claude/rules/`.
The build, testing and visual tools are described in [dev-pipeline.md](dev-pipeline.md).

## Prepare a focused change

Create a branch and read every area rule covering the files you will touch. Prefer established tools before
adding custom infrastructure. For a large change, describe what players will see or hear and the law behind it
in a few PR-description lines before implementation. Small changes can proceed directly.

Keep code, comments, logs, tools, commits and PRs in English. Player-facing documentation and interface text
have English and Russian versions. Update both guide languages in the same PR for player-visible changes.
Shared documentation describes the current contributor workflow; personal session records and job notes stay
outside the repository.

## Build and test

Use Java 21 and run from `mod/`:

```sh
./gradlew --daemon build
./gradlew runGameTestServer -PgametestOnly='<touched batch regex>'
```

For environments without a configured game instance, fetch runtime mods with
`python3 tools/fetch_runtime_mods.py` from the repository root and add `-PmcModsDir=run/ci-mods` to the GameTest
command. Flight changes may also need the scenario sweep; see
[gametest.md](../.claude/rules/gametest.md).

Add a regression check for each behavior fix. Numeric claims and promises in comments or the guide must be
supported by tests. Fix unstable tests at their cause rather than repeatedly rerunning the whole suite.
Keep test timing based on ticks or counting clocks so runner load cannot decide the result.

For visible or audible changes, inspect sandbox frames and WAV during work and include before/after evidence
in the PR. Effect changes require the 100-strike scene bench comparison against `main` on the latest head.
Follow the GPU lease and headless-client rules; do not interrupt another user's game.

## Open and validate the PR

Open a draft PR after the local build and relevant checks pass. Branches without PRs do not run CI.
Describe the problem, resulting behavior, validation and material limitations using the PR template.
Keep related guide and translation updates with the implementation.

Wait for `Итог` on the current PR head with `tools/pr_ci.sh <PR>`. A conflict with the base prevents GitHub from
starting checks; rebase and resolve it before waiting again. Preserve upstream behavior when resolving comments
or documentation conflicts. Use failing check logs to diagnose failures and rerun only the checks justified by
the fix.

## Review and merge

Use one independent review round. The reviewer reads the diff and surrounding code, plus frames and WAV when
relevant. Wrong behavior, crashes and tick-budget or frame-cost breaches block merging. Fix minor findings
without another round; serious fixes need a second round, with no third round.

The author merges after green `Итог` and a review without serious errors. If CI misses a bug, add a check that
would catch it. Do not substitute repeated manual verification for that regression check.

## Release separately

Merging a PR does not authorize a public release. Releases and store/video publishing require the maintainer's
explicit instruction. Release preparation includes a new version, player-facing notes in both languages,
compatibility checks and the network protocol bump required by the repository rules. The manual release
workflow runs the full checks before publishing.
