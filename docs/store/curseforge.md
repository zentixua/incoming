# Incoming

<img src="https://raw.githubusercontent.com/zentixua/incoming/main/docs/media/icon.png" alt="Incoming" width="128" height="128">

Air strikes and air defense for Minecraft 1.21.1.

Call in a strike with the Strike Designator, choose a target through binoculars or on the map, and watch from the
projectile's camera. Incoming adds drones, missiles, a bomber and nuclear weapons, along with sirens and air defense.
At a distance, you see the explosion before you hear it.

## Features

- **Strike Designator:** aim at blocks, mobs, players or Create Aeronautics craft. Set up a salvo or plot a flight route on the map.

![Choose a weapon and set up a salvo](https://raw.githubusercontent.com/zentixua/incoming/main/docs/guide/img/remote.gif)

- **Shahed-136:** send a drone toward a target, alone or in a salvo.
- **Lancet:** circle above the target, look through its camera and choose where to dive.
- **Cruise missile:** fly low over the terrain, then climb and dive into the target.
- **Grad:** fire unguided rockets across an area.
- **B-2:** call in a bomber with a bunker buster that penetrates the ground before exploding.

![Cruise missiles strike a factory](https://raw.githubusercontent.com/zentixua/incoming/main/docs/media/hero.webp)

- **Nuclear strikes:** launch an ICBM or fit a nuclear warhead to a cruise missile or B-2 bomb. Flash, mushroom cloud and blast wave; ground bursts leave a crater and fallout downwind. Operator-only by default.

![Nuclear fireball and blast wave over a city](https://raw.githubusercontent.com/zentixua/incoming/main/docs/guide/img/nuke-wave.jpg)

- **Air defense:** sirens warn players near a target. A SAM shoots down hostile drones, cruise missiles and B-2s; it cannot intercept Grad rockets or ICBMs.
- **Fixed launchers:** load ammunition, assign a target with the designator and fire with redstone.
- **Blackout:** strike a transformer substation to cut electric lights in its district.

## Requirements and dependencies

**Minecraft 1.21.1 · NeoForge 21.1.264+ · Java 21.** Incoming is required on the server and every client.
No other mods are required.

Create, Create Aeronautics with Sable, Distant Horizons, Voxy through Roxy and Iris are optional.
See [compatibility and supported versions](https://github.com/zentixua/incoming/blob/main/docs/guide/compatibility.md).

## FAQ

**Fabric or Forge?** This version is for NeoForge only.

**Other Minecraft versions?** Incoming currently supports 1.21.1.

**Can I use it in a modpack?** Yes, under the GPL-3.0 license.

**Client or server?** Both. It works in single-player, LAN worlds and dedicated servers. Use the same Incoming version on both sides.

**How demanding is it?** Large salvos, nuclear strikes and shaders can be demanding. See [performance help](https://github.com/zentixua/incoming/blob/main/docs/guide/faq.md#troubleshooting).

**Can I protect builds?** The owner can restrict launches and disable destruction and fires in [world settings](https://github.com/zentixua/incoming/blob/main/docs/guide/settings.md#world-settings). Destruction is on by default.

## Links

[Player guide](https://github.com/zentixua/incoming/blob/main/docs/guide/README.md) · [Issues](https://github.com/zentixua/incoming/issues) · [Source](https://github.com/zentixua/incoming)

## License and credits

Made by ZentixUA. [GPL-3.0-only](https://github.com/zentixua/incoming/blob/main/LICENSE). [Sound credits](https://github.com/zentixua/incoming/blob/main/SOUND-CREDITS.md) · [Asset credits](https://github.com/zentixua/incoming/blob/main/tools/bake_fx/SOURCES.md).
Includes sable-companion and FastNoiseLite by Jordan Peck (MIT); fireball and smoke textures use JangaFX CC0 simulations.
