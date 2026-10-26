# Store drafts: Modrinth and CurseForge

These are repository drafts. Do not create, submit or publish a project as part of documentation maintenance.
Account fields and storefront previews require a separate authorized step.

## Text and fields

Public text stays in separate language files. English goes first when assembling a store body, followed by a
separator and the complete Russian text. Both platforms permit additional languages after English; keep the main
title and summary in English.

- Modrinth: [description.md](description.md), then [description.ru.md](description.ru.md).
- CurseForge **Markdown editor**: [curseforge.md](curseforge.md), then [curseforge.ru.md](curseforge.ru.md).
  These exports use absolute media/link URLs and a static JPEG poster; no HTML or animated WebP is required.
- Summaries: [English](summary.md), [Russian](summary.ru.md). `modrinth.json.description` matches the English file.
  Use the Russian summary only where an additional localized field is available; do not replace the main English summary.
- Edit both description languages and their CurseForge exports together.

| Field | Value |
|---|---|
| Title | Incoming: Air Strikes and Air Defense |
| Slug | `incoming` |
| Summary | [summary.md](summary.md) |
| Loader and game | NeoForge, Minecraft 1.21.1; NeoForge 21.1.264+; Java 21 |
| Environment | Client & Server; client required, server required; same Incoming version |
| Modrinth categories | Equipment, Technology, Game Mechanics |
| CurseForge categories | Armor, Tools, and Weapons (main); Technology (additional) |
| License | GPL-3.0-only |
| Source | https://github.com/zentixua/incoming |
| Issues | https://github.com/zentixua/incoming/issues |
| English guide | https://github.com/zentixua/incoming/blob/main/docs/guide/README.md |
| Russian guide | https://github.com/zentixua/incoming/blob/main/docs/guide/ru/README.md |
| Mod id | `incoming` |
| Required dependencies | None |

Content disclosure belongs in the platform's field, separate from the public description. The code and page texts
use AI assistance. Verify the current disclosure controls and saved value in the author's account before submission.

## Optional dependencies and sides

All rows are optional for Incoming. Rendering dependencies belong on clients; they are not dedicated-server requirements.

| Integration | Side and version | Upload relation |
|---|---|---|
| Create | Client and server; recipes use its parts when present | Optional on both stores |
| Create Aeronautics + Sable | Client and server; Sable 2.0.6+ | Both optional on Modrinth; Sable on CurseForge; Aeronautics has no configured CurseForge alias |
| Distant Horizons | Client rendering; 3.3+ with API 7, integration developed against 3.3.3 | Optional on both stores |
| Voxy + Roxy | Clients; original Fabric Voxy 0.2.16-beta for MC 1.21.11, translated by Roxy to NeoForge 1.21.1 | Both optional on Modrinth; Roxy on CurseForge; no verified Voxy CurseForge alias |
| LOD Server Support | Server and clients; Incoming targets NeoForge 0.19.0 | Optional on Modrinth; no verified CurseForge alias |

Roxy's [0.4.1 version](https://modrinth.com/mod/roxy/version/VnJRhuAn) specifies Sodium 0.8.12 for NeoForge 1.21.1,
Forgified Fabric API 0.116.14+2.3.0 for 1.21.1, and the Voxy jar above as required dependencies of **Roxy**. They are not
required dependencies of Incoming. The [README](../../README.md#compatibility) links those exact files and explains
LSS, DH and shader limits. This list is not a claim that every combination has been tested.

The optional upload list in `.github/workflows/publish.yml` uses these same seven integrations. Platform entries
without a verified alias are explicitly skipped, rather than guessing a project or making a dependency mandatory.
Sound Physics Remastered, Iris and Flashback are additional supported integrations described in the README; the
upload list records the terrain/recipe/craft integrations above.

## Media

The Modrinth icon is `docs/media/icon.png` (512×512); the CurseForge avatar is `docs/media/icon-400.png` (400×400 PNG).
The default first screen uses `docs/media/still-2.jpg` (about 188 KiB), rather than loading the 6 MiB animation.
The original `docs/media/hero.webp` remains available by clicking the README poster. `docs/guide/img/remote.gif`
is an existing GIF demonstration of console controls; a GIF conversion of the large hero loop is unnecessary for
the static first screen. There is no verified finished trailer URL to embed.

The draft Modrinth gallery is in `modrinth.json`, in this order:

| File | Caption |
|---|---|
| `docs/media/still-5.jpg` | Cruise missiles strike a factory (featured) |
| `docs/guide/img/remote.gif` | Strike Designator console: weapon, salvo and target |
| `docs/guide/img/map.gif` | Targeting map and flight route |
| `docs/guide/img/weapon-shahed.jpg` | Shahed-136 in flight |
| `docs/guide/img/weapon-lancet-launcher.jpg` | Lancet on its launcher |
| `docs/guide/img/weapon-grad.jpg` | Grad rocket launcher |
| `docs/guide/img/sam.webp` | SAM and radar during a drone raid |
| `docs/guide/img/launcher.webp` | Fixed cruise missile launcher |
| `docs/guide/img/nuke-wave.jpg` | Fireball and blast wave over a city |
| `docs/guide/img/nuke-ruins.jpg` | Ruins and fires after a nuclear strike |

These are existing game captures, not a fresh visual check of the current jar. Before public submission, replace
changed effect/interface captures after the active effect and camera work has settled. For CurseForge, use static
JPEG/PNG images in the description and preview gallery animations separately. Check both language sections, image
loading, tables and links in the actual Markdown editor; a local Markdown check cannot certify its preview.

## Authorized publishing workflow

`.github/workflows/publish.yml` runs only by hand. No mode is part of this documentation task. Even `draft` writes
to Modrinth and uploads a jar; `submit` and `release` can make files public.

It uses the fields/icon/gallery in [modrinth.json](modrinth.json). The page script assembles the English and Russian
body files in that order. CurseForge's description is pasted into its Markdown editor separately; the workflow
uploads version files, not that description.

Repository settings used by an authorized publishing run:

- `MODRINTH_TOKEN`: secret with project read/write/create and version read/create scopes.
- `CURSEFORGE_TOKEN`: secret for CurseForge file uploads.
- `CURSEFORGE_ID`: repository variable containing the existing project's ID.

Without the relevant token/ID the corresponding store is skipped. The version input selects an existing GitHub
release without `v`; empty means latest. Changelog comes from `docs/releases/<version>.md`, or the release body if
that file is absent.

- `draft`: creates or updates a hidden Modrinth project, its text, icon, gallery and jar. It skips an already uploaded jar.
- `submit`: sends the Modrinth draft for review and uploads the jar to CurseForge, where a first file can trigger review.
- `release`: updates the approved Modrinth page and uploads a later version to both stores.

Removed gallery entries are removed remotely on the next applicable update. Repeating `submit` or `release` can
upload a duplicate CurseForge file. A CurseForge project is created in the account, not by this workflow.

## Needs Artem before a public store step

- Identify the existing hidden projects, if any, from the owner account; record their IDs, URLs, owner and visibility.
  A public API 404 does not prove that no private draft exists; a 403 does not establish a CurseForge project's state.
- Compare the account fields, disclosure, dependency relations and assembled English/Russian body with these drafts.
  Confirm any missing CurseForge project aliases before adding relations.
- Check the real store preview, refresh captures affected by ongoing visual/UI work, and choose a finished trailer
  only if one is available. Nothing here claims a new render or sound check.
- Separately authorize any account edit, submission, visibility change or file publication. Existing GitHub Release
  bodies that link the personal pack also need a separately authorized public edit; tracked release notes are fixed here.

Platform references: [CurseForge submission and Markdown editor](https://support.curseforge.com/support/solutions/articles/9000199552-project-submission-guide-and-tips),
[CurseForge language/avatar rules](https://support.curseforge.com/support/solutions/articles/9000197279-moderation-policies),
[Roxy's installation requirements](https://modrinth.com/mod/roxy).
