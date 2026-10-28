# Integration reference

Implementation and version details moved from the project overview. For installation, see the
[player compatibility page](../guide/compatibility.md).


All of these are optional:

- **Create Aeronautics** with **Sable** 2.0.6 or newer: aircraft can be targeted, and explosions break and push them.
- **Create**: munition recipes use Create parts when Create is installed and vanilla items otherwise.
- **[LOD Server Support](https://modrinth.com/mod/lod-server-support)** on the server and the client (with Voxy or without it): the targeting map, sight lines and
  sound around hills use the distant terrain the server sends. Not in single-player (a world opened to LAN serves it).
- **[Distant Horizons](https://modrinth.com/mod/distanthorizons)** 3.3 or newer, with API 7: the targeting map shows distant terrain where LOD Server Support is not serving.
  DH must also be installed where the world runs (single-player, the LAN host or the dedicated server) for its damage
  updates. Supported distant damage updates appear without visiting the chunk, through queues and asynchronous LOD writes;
  [preparation and delays](../guide/nuke.md#what-happens) depend on the carrier and the terrain.
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
  per-source `VANILLA`/blacklist setting. The "Incoming's blast effects" switch hides its conventional
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


## Building from source

Java 21 is required.

```sh
python3 tools/fetch_runtime_mods.py                # Create, Sable and Create Aeronautics for GameTest
cd mod
./gradlew build                                    # build and unit tests, jar in build/libs
./gradlew runGameTestServer -PmcModsDir=run/ci-mods
```

## Map cache identity

The LOD Server Support terrain cache is shared when you enter the same server with or without `:25565`, or change
the host name's letter case. Other ports keep separate caches.

## Server work and performance

First distinguish a slow frame from a slow server tick:

- If the view stutters while the world keeps moving normally, lower "Mushroom cloud, dust wall and pall" to LOW,
  reduce shader quality or try without shaders. Client graphics settings affect frame cost.
- If mobs, redstone and impacts slow down for everyone, the server is behind: 20 TPS needs ticks within 50 ms.
  Reduce simultaneous orders and avoid many far targets in unexplored terrain, which need chunk generation.
  The owner can lower [`performance.work_ms_per_tick`](../guide/settings.md#world-settings) from its 30 ms default
  (range 5–45); destruction and ruins then take longer to finish. It does not limit every other mod or chunk generation.
- Memory depends on the modpack and the scene. Check actual usage and pauses before changing the allocation.

