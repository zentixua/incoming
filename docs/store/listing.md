# Store drafts: Modrinth and CurseForge

These are repository drafts. Do not create, submit or publish a project as part of documentation maintenance.
Account fields and storefront previews require a separate authorized step.

## Text and fields

Public text stays in separate language files. English goes first when assembling a store body, followed by a
separator and the complete Russian text. Both platforms permit additional languages after English; keep the main
title and summary in English.

- Modrinth: [description.md](description.md), then [description.ru.md](description.ru.md).
- CurseForge **Markdown editor**: [curseforge.md](curseforge.md), then [curseforge.ru.md](curseforge.ru.md).
  These exports use absolute media/link URLs. A small logo and tagline lead into the introduction; feature images sit between bullet groups. The logo uses an HTML image size.
- Summaries: [English](summary.md), [Russian](summary.ru.md). `modrinth.json.description` matches the English file.
  Use the Russian summary only where an additional localized field is available; do not replace the main English summary.
- Edit both description languages and their CurseForge exports together.

To assemble the complete CurseForge body locally, without contacting a store:

```sh
bash .github/scripts/store_body.sh docs/store/curseforge.md docs/store/curseforge.ru.md
```

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

All rows are optional for Incoming. Voxy and Roxy belong on clients. DH rendering also belongs on clients, but
Incoming's DH damage updates need DH where the world runs, including a dedicated server.

| Integration | Side and version | Upload relation |
|---|---|---|
| Create | Client and server; recipes use its parts when present | Optional on both stores |
| Create Aeronautics + Sable | Client and server; Sable 2.0.6+ | Both optional on Modrinth; Sable on CurseForge; Aeronautics has no configured CurseForge alias |
| Distant Horizons | Clients for rendering; also where the world runs for DH damage updates; 3.3+ with API 7, developed against 3.3.3 | Optional on both stores |
| Voxy + Roxy | Clients; original Fabric Voxy 0.2.16-beta for MC 1.21.11, translated by Roxy to NeoForge 1.21.1 | Both optional on Modrinth; Roxy on CurseForge; no verified Voxy CurseForge alias |
| LOD Server Support | Server and clients; Incoming targets NeoForge 0.19.0 | Optional on Modrinth; no verified CurseForge alias |

Roxy's [0.4.1 version](https://modrinth.com/mod/roxy/version/VnJRhuAn) specifies Sodium 0.8.12 for NeoForge 1.21.1,
Forgified Fabric API 0.116.14+2.3.0 for 1.21.1, and the Voxy jar above as required dependencies of **Roxy**. They are not
required dependencies of Incoming. The [compatibility guide](../guide/compatibility.md) links those exact files and explains
LSS, DH and shader limits. This list is not a claim that every combination has been tested.

The optional upload list in `.github/workflows/publish.yml` uses these same seven integrations. Platform entries
without a verified alias are explicitly skipped, rather than guessing a project or making a dependency mandatory.
Sound Physics Remastered, Iris and Flashback are additional supported integrations described in the compatibility guide; the
upload list records the terrain/recipe/craft integrations above.

## Media

The Modrinth icon is `docs/media/icon.png` (512×512); the CurseForge avatar is `docs/media/icon-400.png` (400×400 PNG).
The first screen uses the existing logo (`docs/media/icon.png`) at 128×128, a tagline and a three-sentence introduction.
The feature list has three illustrations: console controls, the 3.10.0 factory-strike loop and a nuclear blast.
The factory loop is 9 seconds and about 2.23 MiB; each refreshed animation is below 5 MB. Extra weapon screenshots are in the Gallery.
There is no verified finished video URL, so the description has no video section or video placeholder.

The draft Modrinth gallery is in `modrinth.json`, in this order:

| File | Caption |
|---|---|
| `docs/media/still-5.jpg` | Cruise missiles strike a factory (featured) |
| `docs/guide/img/remote.gif` | Choose a weapon and set up a salvo |
| `docs/guide/img/map.gif` | Targeting map with distant city terrain |
| `docs/guide/img/weapon-shahed.jpg` | Shahed-136 on the way to its target |
| `docs/guide/img/weapon-lancet-launcher.jpg` | Lancet ready to launch |
| `docs/guide/img/weapon-grad.jpg` | Grad ready for a rocket salvo |
| `docs/guide/img/sam.webp` | SAM defending against a drone raid |
| `docs/guide/img/launcher.webp` | A cruise missile on a fixed launcher |
| `docs/guide/img/nuke-wave.jpg` | Fireball and blast wave of a 15 kt airburst |
| `docs/guide/img/nuke-ruins.jpg` | Ruined city in the dust after a 15 kt airburst |
| `docs/media/still-2.jpg` | Cruise missiles strike a factory in Project Zearth |
| `docs/guide/img/weapon-b2.jpg` | B-2 in flight |
| `docs/guide/img/weapon-icbm.jpg` | ICBM in flight |

The factory, nuclear and instructional effect captures were recorded with Incoming 3.10.0 on a copy of Project
Zearth. Unchanged console, binocular, recipe and model images are retained. Verify animated WebP in the CurseForge editor before publication; if unsupported, the media thread should supply
a GIF for the strike feature image. Preview gallery animations separately. Check both language sections, image
loading, tables and links in the actual Markdown editor; a local Markdown check cannot certify its preview.

## Authorized publishing workflow

`.github/workflows/publish.yml` runs only by hand. No mode is part of this documentation task. Even `draft` writes
to Modrinth and uploads a jar; `submit` and `release` can make files public.

It uses the fields/icon/gallery in [modrinth.json](modrinth.json). The page script assembles the English and Russian
body files in that order. CurseForge's description is pasted into its Markdown editor separately; the workflow
uploads version files, not that description.

The publishing job uses the `store-publish` GitHub environment. Before publishing, restrict that environment to the
`main` branch and move the store tokens out of repository secrets into environment secrets. Dispatch the workflow
from `main`: an older branch runs its older checks, so a condition in the current workflow alone cannot protect
repository-level tokens. Removing the repository copies prevents old workflows without this environment from
obtaining the upload credentials.

Settings used by an authorized publishing run:

- `MODRINTH_TOKEN`: environment secret with project read/write/create and version read/create scopes.
- `CURSEFORGE_TOKEN`: environment secret for CurseForge file uploads.
- `CURSEFORGE_ID`: repository variable containing the existing project's ID.
- `CURSEFORGE_API_KEY`: separate environment secret for the read API used to check existing files before uploading.

Without `CURSEFORGE_ID`, CurseForge is disabled with a warning in the job summary, even if its token or read API key
already exists. A configured ID requires both credentials; missing either fails before mutations. Modrinth is
disabled without its token; `draft` requires Modrinth, and every mode requires at least one configured store.

The version input selects an existing GitHub release without `v`; empty means latest. The release and jar uploader
must be GitHub Actions, and the resolved tag commit must match the release's pinned target SHA. A completed,
successful `build.yml` release dispatch for that exact commit must have successful `Итог` and `Релиз` jobs. The
publication and asset-upload timestamps must fall within that release job. The downloaded jar must match its release
SHA256 digest, embedded version and the jar inside that run's `incoming-jar` artifact; the artifact archive digest
and run/commit association are also checked. Hand-made releases, replaced jars, moved tags, and missing or expired
CI artifacts stop publishing. Changelog and client/server environment come from the verified tag commit, rather
than the current workflow revision.

- `draft`: creates or updates a hidden Modrinth project, its bilingual text, icon, gallery and jar. It skips an already uploaded jar.
- `submit`: sends the Modrinth draft for review and uploads the jar to CurseForge, where a first file can trigger review.
- `release`: uploads a later version; the Modrinth page/media change only when `update_page` is explicitly enabled.

All configured media must exist before a page changes. Removed gallery entries are removed remotely on an
applicable page update. The stores handle upload results independently. CurseForge checks file hashes and a durable
GitHub deployment receipt before uploading; an uncertain prior submission stops for owner reconciliation instead
of uploading again during moderation. Automatic CurseForge upload retries are disabled. A CurseForge project is
created in the account, not by this workflow.

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
