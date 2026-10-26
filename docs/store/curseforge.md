# Incoming: Air Strikes and Air Defense

Air strikes, a targeting console and defenses for Minecraft 1.21.1 on NeoForge.

**Minecraft 1.21.1 · NeoForge 21.1.264+ · Java 21 · required on the server and every client.**
No other mods are required. Use the same Incoming version on both sides.

![Cruise missile over a city](https://raw.githubusercontent.com/zentixua/incoming/main/docs/media/still-2.jpg)

## Start a strike

Craft a Strike Designator and munitions. Hold RMB for binoculars, choose a weapon with the mouse wheel and launch
with LMB. Shift+RMB opens the console; `,` opens the targeting map. The map reaches 10,000 blocks by default and
supports up to five route points for drones and cruise missiles. `N` opens the projectile camera; Shift exits.

Strikes break blocks and start fires by default. Blast damage affects the shooter too. Nuclear weapons are
operator-only by default; the world owner controls access, destruction and fire separately for conventional and
nuclear strikes. Check the [world rules](https://github.com/zentixua/incoming/blob/main/docs/guide/settings.md#world-settings)
before firing near a build.

[Player guide](https://github.com/zentixua/incoming/blob/main/docs/guide/README.md) ·
[Russian guide](https://github.com/zentixua/incoming/blob/main/docs/guide/ru/README.md) ·
[Recipes](https://github.com/zentixua/incoming/blob/main/docs/guide/munitions.md)

## Weapons

| Weapon | Use |
|---|---|
| Shahed-136 | A drone launched from a nearby trailer; follows a moving target while you hold it in its camera. |
| Lancet | A loitering munition: circle over the target, then choose what to hit from its camera. |
| Cruise missile | Low flight over terrain and a final dive; can carry a nuclear warhead. |
| Grad | A 40-tube launcher firing unguided rockets over an area. |
| B-2 and bunker buster | A bomber drops a guided bomb that penetrates the ground and explodes underground; can carry a nuclear warhead. |
| ICBM | A nuclear strike of 1–15 kt, with a 90 s flight timer by default. |

Each projectile costs one munition item; a Grad pack supplies 40 rockets. A nuclear cruise missile or B-2 bomb also
costs one Nuclear Warhead item per projectile. A salvo is paid in full when ordered, with
unfired munitions returned on abort or launch failure. Creative mode does not need munitions.

Projectile sizes use one block per meter. Blast damage follows charge and material models with approximations.
Flight and air defense use a game model in every preset. GAME keeps full nuclear radii but slows the distant wave
to 0.20 of the speed of sound; REAL uses reference flight speeds/heights and physical wave timing; CUSTOM can shrink
nuclear radii. See [Not physical](https://github.com/zentixua/incoming/blob/main/docs/guide/not-physical.md).

## Warning and defense

Players near the target hear a siren and see an alert. The SAM block shoots down hostile Shaheds, Lancets, cruise
missiles and B-2s, including nuclear carriers; it cannot intercept Grad rockets or ICBMs. Its four ready rails are
fed from two stock slots, one missile every 8 seconds by default. Supply it by hand or hopper.

A fixed launcher fires a mission on a redstone signal. Link it to the designator, assign a target and load it with
munitions. It accepts Shaheds, Lancets, conventional cruise missiles and Grad rockets.

## Nuclear strike and blackout

A nuclear strike produces a flash, mushroom cloud and blast wave, followed by fires. Ground bursts also leave a
crater and radioactive fallout downwind. Take cover under thick ground, avoid windows and do not look at the
fireball. A Geiger Counter measures dose and dose rate. See
[survival and radiation](https://github.com/zentixua/incoming/blob/main/docs/guide/nuke.md#how-to-survive).

If nuclear weapons are allowed for ordinary players, a launch needs another nearby player's second key when
someone else is online. The launching player cannot recall an ordered nuclear strike, even while it waits for the second key; an operator or the host can use `/incoming clear`.

Destroying a transformer substation cuts electric lights in its district: by default 512 blocks, with restoration
starting after 15 minutes and spreading over 90 seconds. Nuclear blackout needs no substation, but each outage is
capped by `grid.max_radius` (4096 blocks by default) and has its own switch.

## Optional mods

- **Create:** recipes use Create parts; vanilla substitutes are provided without it.
- **Create Aeronautics and Sable 2.0.6+:** target craft; explosions break and push their blocks. Fragments pass through craft.
- **Distant Horizons 3.3+ with API 7:** distant targeting terrain on clients. Damage updates also need DH where the world runs (single-player, LAN host or dedicated server); supported chunks update without visiting them, through queues and asynchronous writes.
- **Voxy through Roxy (clients only):** distant terrain for rendering, targeting, sight lines and sound. Roxy uses the original Fabric Voxy 0.2.16-beta for **Minecraft 1.21.11** on NeoForge 1.21.1; install its Sodium and Forgified Fabric API dependencies too.
- **LOD Server Support on server and clients:** server terrain for the targeting map, sight lines and sound; it does not serve a single-player world until opened to LAN.
- **Iris, Sound Physics Remastered and Flashback:** shader rendering, world sound and replays. Sound Physics needs a per-sound setting for Incoming.

[Exact versions, official links and limits](https://github.com/zentixua/incoming#compatibility)

## Server and performance

The server config is `<level-name>/serverconfig/incoming-server.toml`; start once to create it, then stop, edit and
restart. Conventional and nuclear destruction use separate switches.

For slow frames, lower nuclear volume quality or shader settings. If the server tick slows for everyone, reduce
simultaneous orders and distant targets in unexplored terrain. The server's `performance.work_ms_per_tick` budget
is 30 ms by default (5–45): lowering it leaves more tick time for other work and makes destruction finish later.
Memory needs depend on the modpack and scene. See the
[FAQ](https://github.com/zentixua/incoming/blob/main/docs/guide/faq.md).

## License and links

[Source](https://github.com/zentixua/incoming) · [Issues](https://github.com/zentixua/incoming/issues) ·
[Releases](https://github.com/zentixua/incoming/releases)

GNU GPL-3.0-only. Sound recordings use CC0 and CC BY 4.0; authors are in
[SOUND-CREDITS](https://github.com/zentixua/incoming/blob/main/SOUND-CREDITS.md). The jar includes sable-companion
(MIT) and FastNoiseLite by Jordan Peck (MIT). Fireball and smoke textures use JangaFX CC0 simulations;
[sources](https://github.com/zentixua/incoming/blob/main/tools/bake_fx/SOURCES.md).
