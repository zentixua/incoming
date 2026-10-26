# Contributing to Incoming

Read the [player guide](docs/guide/README.md) and search existing
[issues](https://github.com/zentixua/incoming/issues) before reporting a problem.
Use the bug or crash form, include exact versions and reproduction steps, and
link the complete log. Remove tokens, private addresses and other personal data
before sharing logs.

For a local build, install JDK 21 and clone only the main branch:

```sh
git clone --single-branch --branch main --depth 1 https://github.com/zentixua/incoming.git
cd incoming/mod
./gradlew --daemon build
```

This builds the jar in `mod/build/libs` and runs the unit tests. It does not
require a personal launcher instance or an AI assistant. To run GameTest without
an existing instance, from `mod/`:

```sh
python3 ../tools/fetch_runtime_mods.py run/ci-mods
./gradlew runGameTestServer -PmcModsDir=run/ci-mods
```

GameTest uses optional integration mods; CI also checks the built jar on a clean
NeoForge installation. Keep temporary work in ignored `scratch/`, `fzc/` or
`mod/run/` directories. Do not commit worlds, logs, credentials or launcher data.

Keep changes focused and write code, comments and development documentation in
English. Update player documentation and language files in English and Russian
when behavior changes. Add a regression check for a behavior fix. Open a draft
pull request with the change and relevant validation; CI must pass before merge.
For changes to visuals or sound, include before/after frames and audio, and
performance measurements where relevant. Store review media on a separate
`look-gate/<session>` orphan branch and link it from the PR instead of adding it
to the code diff. The architecture and area rules are in [CLAUDE.md](CLAUDE.md).
