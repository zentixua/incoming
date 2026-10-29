# Store drafts: Modrinth and CurseForge

These are repository drafts. Do not create, submit or publish a project as part of documentation maintenance.
Account fields and storefront previews require a separate authorized step.

## Text and fields

Public text stays in separate language files. English goes first when assembling a store body, followed by a
separator and the complete Russian text. Illustrations appear once in the English section; the Russian section
translates the text without embedding the same images again. Both platforms permit additional languages after English; keep the main
title and summary in English.

- Modrinth: [description.md](description.md), then [description.ru.md](description.ru.md).
- CurseForge **Markdown editor**: [curseforge.md](curseforge.md), then [curseforge.ru.md](curseforge.ru.md).
  These exports use absolute media/link URLs. The factory-strike animation and tagline lead into the introduction; effect images sit beside their descriptions.
- Summaries: [English](summary.md), [Russian](summary.ru.md). `modrinth.json.description` matches the English file.
  Use the Russian summary only where an additional localized field is available; do not replace the main English summary.
- Edit both description languages and their CurseForge exports together.

To assemble the complete CurseForge body locally, without contacting a store:

```sh
bash .github/scripts/store_body.sh docs/store/curseforge.md docs/store/curseforge.ru.md
```

| Field | Value |
|---|---|
| Title | Incoming: Air Strikes & Nukes |
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
The first screen uses the factory-strike animation (`docs/media/hero.webp`), a tagline and a three-sentence introduction.
The effect and nuclear sections lead, followed by weapons and controls. The body shows the factory approach/impacts and the 6 kt dusk burst as two animations.
The gallery contains both body animations, the other animations and all stills, using the same files.
Each refreshed animation is below 5 MB. Extra weapon screenshots are in the Gallery.
There is no verified finished video URL, so the description has no video section or video placeholder.

The shared gallery contains four animations and ten stills, with each file included once. Only the factory-strike
and dusk nuclear animations also appear in the page bodies.

| File | Title | Description |
|---|---|---|
| `docs/media/hero.webp` | Factory strike (featured) | A missile salvo hits the factory. |
| `docs/media/nuke-dusk.webp` | Dusk nuclear strike | Flash, rising cloud and the blast wave reaching the observer. |
| `docs/media/factory-aftermath.jpg` | Factory aftermath | Smoke and drifting dust after the missile salvo. |
| `docs/media/nuke-dusk-wide.jpg` | Dusk blast wave | A glowing mushroom cap above the approaching ground wave. |
| `docs/guide/img/sam.webp` | Air defense | A SAM site shoots down a drone raid. |
| `docs/guide/img/launcher.webp` | Missile launch | Cruise missiles take off from a runway. |
| `docs/guide/img/weapon-shahed.jpg` | Shahed flight | A drone on its way to the target. |
| `docs/guide/img/weapon-lancet-launcher.jpg` | Lancet catapult | Ready to launch from an airport. |
| `docs/guide/img/weapon-grad.jpg` | Grad MLRS | Ready for a rocket salvo. |
| `docs/guide/img/weapon-b2.jpg` | B-2 bomber | On its way to drop a bunker buster. |
| `docs/guide/img/weapon-icbm.jpg` | ICBM ascent | A ballistic missile in flight. |
| `docs/guide/img/scope.png` | Binocular targeting | Aim at anything you can see. |
| `docs/guide/img/remote.png` | Strike remote | Pick a weapon and set up a salvo. |
| `docs/guide/img/map.png` | Targeting map | Choose a target on the city map. |

The factory and nuclear scenes were recorded with Incoming 3.10.1 on copies of Project Zearth and Newisle.
Other instructional captures retain their Incoming 3.10.0 sources. Motion clips use Flashback exports at 30 fps; stills are native export frames or plain F2 screenshots at 1920×1080 with Complementary Unbound.
World shots hide the HUD; guide interface shots retain it. The map shows the city before the strike.
The nuclear sequence uses [City of Newisle by Blip Noir](https://www.planetminecraft.com/project/city-of-newisle-v-07032015-solo-modern-build).
Unchanged recipe and interface images are retained. Verify animated WebP in the CurseForge editor before publication; if unsupported, the media thread should supply
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

All configured media must exist before a page changes. Duplicate image contents are rejected before any remote
change. Page updates reconcile the gallery: one entry per configured image, obsolete and duplicate entries removed;
repeating the update leaves the same gallery. The stores handle upload results independently. CurseForge checks file hashes and a durable
GitHub deployment receipt before uploading; an uncertain prior submission stops for owner reconciliation instead
of uploading again during moderation. Automatic CurseForge upload retries are disabled. A CurseForge project is
created in the account, not by this workflow.

## Account settings and remaining release steps

[CurseForge settings](curseforge.json) is the complete manual project-field payload; the workflow uploads files,
not these settings. Project 1770261 already exists. The coordinator applies and verifies its title, summary,
categories, GPL license, 400 px avatar, source/issues/wiki links, comments enabled, experimental disabled,
assembled bilingual body and the gallery above. There is no public Discord invite or donation URL to add.
Verify any platform AI disclosure field (AI-assisted code/text; media is actual gameplay), visibility and the
rendered Markdown/animated WebP in the account. Do not submit as part of this setup.

The version upload path already declares NeoForge, Minecraft 1.21.1, Java 21, release type and the optional
relations above. CurseForge needs its separate Core API key (`CURSEFORGE_API_KEY`) before upload; the upload
token alone cannot perform the duplicate-file check. Submission and public release each need Artem’s word.

### Final account verification

- Modrinth project `IuLUSNeU` (`incoming`): keep the private draft until submission is explicitly authorized.
  The draft workflow uses the stable `project_id` and applies the slug, title, summary, sides, categories, license, links,
  icon, body and gallery from the repository. A missing configured project ID fails instead of creating a second draft.
  Verify the resulting API values and gallery originals; anonymous 404 responses are expected for a hidden draft.
- CurseForge project `1770261`: the coordinator verifies every field in `curseforge.json` in the account, including
  the actual gallery/Markdown preview. Do not assume that a successful file-upload workflow changes project settings.
- Store versions must retain the upload relations listed above. Do not guess aliases for the three integrations
  missing a verified CurseForge mapping; they are optional and already linked in the compatibility guide.
- The Core API key and explicit authorization for submission/release remain separate owner steps. No trailer,
  Discord invite or donation link is required when none has been supplied.

Platform references: [CurseForge submission and Markdown editor](https://support.curseforge.com/support/solutions/articles/9000199552-project-submission-guide-and-tips),
[CurseForge language/avatar rules](https://support.curseforge.com/support/solutions/articles/9000197279-moderation-policies),
[Roxy's installation requirements](https://modrinth.com/mod/roxy).
