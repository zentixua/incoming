# Compatibility

English · [Русский](ru/compatibility.md)

Incoming needs **Minecraft 1.21.1, NeoForge 21.1.264+ and Java 21**. It works without any of the mods below.
On a server, install the same Incoming version for everyone.

## Optional mods

| Mod | What it adds to Incoming | Install it on |
|---|---|---|
| Create | Munition recipes using Create parts; vanilla recipes are available without it | Server and clients |
| Create Aeronautics with Sable 2.0.6+ | Flying craft as targets; explosions break and push them | Server and clients |
| Distant Horizons 3.3.3 | Distant terrain on the map and visible changes after strikes | Clients; also the host or dedicated server for damage updates |
| Voxy through Roxy | Distant terrain on the map and in the world | Clients only |
| LOD Server Support | Server terrain on the targeting map, even without Voxy | Server and clients; for a local world, open it to LAN |
| Iris | Nuclear effects with shaders | Clients only |
| Flashback | Recording and replaying strikes | Clients only |

## Voxy on NeoForge 1.21.1

Use [Roxy 0.4.1](https://modrinth.com/mod/roxy/version/VnJRhuAn) with **Fabric Voxy 0.2.16-beta for Minecraft 1.21.11**.
That version number is intentional: Roxy makes it run on 1.21.1. Its other requirements are
[Sodium 0.8.12 for NeoForge 1.21.1](https://modrinth.com/mod/sodium/version/S3DUMfBo) and
[Forgified Fabric API 0.116.14+2.3.0 for 1.21.1](https://modrinth.com/mod/forgified-fabric-api/version/dAxle9F7).
None of these are required if you do not use Voxy.

For LOD Server Support, Incoming targets [NeoForge 0.19.0](https://modrinth.com/mod/lod-server-support/version/ydDRzPcM).
If the map is blank, see [map troubleshooting](map.md#if-the-map-is-empty).
Distant damage can take time to appear; see [nuclear strikes](nuke.md#what-happens).
Incoming sends its Voxy terrain updates in batches, waiting for your client to confirm receipt before sending the next.
The batch size adapts to your client’s measured receive rate; delivery pauses while the connection is busy.
On a slow connection, distant changes arrive gradually.
These versions do not guarantee that every shader and mod combination works together.

## Other sound and explosion mods

Sound Physics Remastered can add a second set of echoes to Incoming's sounds. If you use it, exclude Incoming's
sounds from its processing. Setup details are in the [integration reference](../dev/integration-reference.md).

If you prefer another mod's blast effects, turn off **Incoming's blast effects** in Incoming's settings.
This hides Incoming's conventional blast picture and sound; it does not guarantee that another mod will replace them.
Damage still happens, and SAM and nuclear effects remain visible.
