# Incoming

English · [Русский](README.ru.md)

[![Cruise missile over a city](docs/media/still-2.jpg)](docs/media/hero.webp)

A Minecraft 1.21.1 mod for NeoForge that adds modern air strikes and air defense. The weapons are the Shahed-136
kamikaze drone, the Lancet loitering munition, a cruise missile, the Grad rocket launcher, a B-2 with
a bunker-buster bomb and an ICBM with a nuclear warhead. A surface-to-air missile system shoots down drones, cruise
missiles and bombers.

![Fireball and blast wave of a 15 kt airburst over a city](docs/guide/img/nuke-wave.jpg)

Projectiles and launchers use one block per meter. Blast damage is calculated from the charge and material, with
approximations described in the [weapons guide](docs/guide/weapons.md). Nuclear effects use reference models:
GAME and REAL keep full radii, while CUSTOM can shrink them. GAME slows the distant nuclear wave to 0.20 of the
speed of sound. Flight and air defense use a game model in every preset, with game flight speeds and heights by
default. See [Not physical](docs/guide/not-physical.md) for the exceptions and [settings](docs/guide/settings.md)
for scale controls. Light, contrast, smoke and camera effects make strikes easier to read in the game.

## What it adds

- **Strike Designator.** One item aims and launches every weapon. Hold right-click to look through its binoculars
  (up to 400 blocks), pick the weapon with the mouse wheel and launch with left-click. Its console sets up a salvo
  of up to 30 projectiles spread over up to 150 blocks.
- **Targets.** Blocks, mobs, players and Create Aeronautics aircraft, including aircraft in flight. Another
  player, or an aircraft that is not yours, can only be targeted after you or your team have spotted it.
- **Targeting map.** Strikes anywhere within 10,000 blocks, with up to five waypoints for the drones and the cruise
  missile.
- **Projectile camera.** Watch a strike from the projectile and pick a new target on the way. The Shahed and the
  Lancet follow a moving target while you keep it in view.
- **Munitions.** Each launch uses a crafted munition item from your inventory.
- **Air defense.** Air raid sirens and on-screen warnings near the target. The surface-to-air missile system block
  shoots down drones, cruise missiles and the B-2.
- **Fixed launcher.** A block that launches a preset strike on a redstone signal.
- **Nuclear strike.** A warhead of 1 to 15 kt on the ICBM, the cruise missile or the B-2: flash, fireball, mushroom
  cloud, a blast wave that ruins buildings as it passes, fires, and for a surface burst a crater and radioactive
  fallout. Operators only by default.
- **Blackout.** Destroying a transformer substation turns off the electric lights in its district.
- **Sound.** Engines and explosions are heard from kilometers away and arrive at the speed of sound, after the flash.

The [player guide](docs/guide/README.md) covers all of this in detail.

## Installation

1. Use Java 21 and install Minecraft 1.21.1 with NeoForge 21.1.264 or newer. No other mods are required.
2. Download `incoming-<version>.jar` from [Releases](https://github.com/zentixua/incoming/releases/latest) and put it
   in the `mods` folder. Remove any older `incoming-*.jar`.
3. The server and every player need the same version of the mod. A client with a different version cannot join.

For a dedicated server, install the NeoForge 1.21.1 server with Java 21 and put the same jar in its `mods` folder.
Start it once to create the world config: `<level-name>/serverconfig/incoming-server.toml`, relative to the server
folder (`level-name` in `server.properties`, usually `world`). Stop the server before editing it and restart to apply
changes. Conventional and nuclear destruction have separate switches; see [world settings](docs/guide/settings.md#world-settings).

## Compatibility

All of these are optional:

- **Create Aeronautics** with **Sable** 2.0.6 or newer: aircraft can be targeted, and explosions break and push them.
- **Create**: munition recipes use Create parts when Create is installed and vanilla items otherwise.
- **[LOD Server Support](https://modrinth.com/mod/lod-server-support)** on the server and the client (with Voxy or without it): the targeting map, sight lines and
  sound around hills use the distant terrain the server sends. Not in single-player (a world opened to LAN serves it).
- **[Distant Horizons](https://modrinth.com/mod/distanthorizons)** 3.3 or newer, with API 7: the targeting map shows distant terrain where LOD Server Support is not serving.
  Supported distant damage updates appear without visiting the chunk, through queues and asynchronous LOD writes;
  [preparation and delays](docs/guide/nuke.md#what-happens) depend on the carrier and the terrain.
- **[Voxy](https://modrinth.com/mod/voxy)** 0.2.16-beta (on NeoForge through **[Roxy](https://modrinth.com/mod/roxy)**): distant terrain hides projectiles, blasts and smoke beyond your
  render distance, with and without shaders, and strike damage far away (craters, nuclear ruins and scorch, dark
  districts in a blackout) shows up in it: the server sends those chunks to players with Voxy. Where LOD Server
  Support is not serving (single-player, a server without it), the targeting map, sight lines and sound around hills
  read distant terrain from Voxy's own LOD: everything Voxy has seen, or the whole save after `/voxy import current`.
- **Sound Physics Remastered**: it handles the world's sounds. Incoming's own sounds keep Incoming's distance, hill
  and wall muffling, since Incoming knows where each blast and engine really is. To keep Sound Physics from adding its
  echoes on top of Incoming's, set every `incoming:` line in `config/sound_physics_remastered/sound_rates.properties`
  to `0`.
- **Explosion mods**: every explosion of a conventional strike carries its
  projectile as the source (`incoming:drone`, `incoming:loiter`, `incoming:cruise_missile`, `incoming:rocket`,
  `incoming:bunker_buster`). [Explosion Overhaul](https://modrinth.com/mod/explosionoverhaul) 1.21.1-2.1.0 has no
  per-source `VANILLA`/blacklist setting. Incoming's "Incoming's blast effects" switch hides its conventional
  picture and sound; it does not guarantee replacement effects from another mod. Destruction and damage remain
  server-side; SAM and nuclear effects keep working.
- **Iris**: the nuclear effects render with shaders.
- **Flashback**: replays show strikes, and seeking through a replay does not set off explosions again.

For Voxy on this NeoForge version, use the original **Fabric Voxy 0.2.16-beta for Minecraft 1.21.11**, rather than
looking for a Voxy 1.21.1 jar. Roxy translates it to 1.21.1. The
[Roxy 0.4.1 dependency set](https://modrinth.com/mod/roxy/version/VnJRhuAn) also requires
[Sodium 0.8.12 for NeoForge 1.21.1](https://modrinth.com/mod/sodium/version/S3DUMfBo) and
[Forgified Fabric API 0.116.14+2.3.0 for 1.21.1](https://modrinth.com/mod/forgified-fabric-api/version/dAxle9F7).
Install this rendering stack on clients, not the dedicated server. LOD Server Support is separate; Incoming's
integration targets [NeoForge 0.19.0](https://modrinth.com/mod/lod-server-support/version/ydDRzPcM) on both sides.
These are specific integration targets and upstream dependency requirements, not a guarantee for every shader or mod combination.

## Player guide

- [Getting started](docs/guide/README.md) and all keys
- [Strike Designator](docs/guide/designator.md), [targeting map](docs/guide/map.md), [projectile camera](docs/guide/camera.md)
- [Weapons](docs/guide/weapons.md), [munitions and recipes](docs/guide/munitions.md), [strike rules](docs/guide/rules.md)
- [Air defense](docs/guide/defense.md), [fixed launcher](docs/guide/launcher.md), [nuclear strike](docs/guide/nuke.md), [blackout](docs/guide/blackout.md)
- [Commands](docs/guide/commands.md), [settings](docs/guide/settings.md), [FAQ and troubleshooting](docs/guide/faq.md)

## Building from source

Java 21 is required.

```sh
python3 tools/fetch_runtime_mods.py                # Create, Sable and Create Aeronautics for GameTest
cd mod
./gradlew build                                    # build and unit tests, jar in build/libs
./gradlew runGameTestServer -PmcModsDir=run/ci-mods
```

## Credits and license

Made by ZentixUA. Incoming is free software under the GNU General Public License version 3
([LICENSE](LICENSE)). You may use, modify and redistribute it, including in modpacks, under the same license and with
its source code. Sounds are made from Freesound recordings under CC0 and CC BY 4.0; the authors are listed in
[SOUND-CREDITS.md](SOUND-CREDITS.md). The jar bundles the sable-companion library (MIT) and
a copy of FastNoiseLite by Jordan Peck (MIT).

Fireball and smoke textures are rendered from JangaFX’s CC0 Gasoline Explosion and Ground Explosion simulations;
[sources and bake recipe](tools/bake_fx/SOURCES.md).
