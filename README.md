# Incoming

<img src="docs/media/icon.png" alt="Incoming" width="128" height="128">

Air strikes and air defense for Minecraft 1.21.1.

[Русский](README.ru.md)

Incoming adds air strikes with explosions, smoke, fires and block destruction, from drone attacks to nuclear strikes.
It includes six weapons, air defense and sirens, with a designator for choosing targets and planning salvos.

## Features

- **Explosions and aftermath:** fireballs light up nearby terrain, blasts break blocks and shatter windows, and dust and smoke rise and drift with the wind. Strikes can start fires; leftover fuel burns in the crater.
- **Blackout:** strike a transformer substation to cut electric lights in its district.

![Cruise missiles strike a factory in Project Zearth](docs/media/hero.webp)

### Nuclear strikes

Flash, blast wave, mushroom cloud, fires and ruins. Ground bursts leave a crater and fallout downwind.
Launch an ICBM or fit a nuclear warhead to a cruise missile or B-2 bomb. Operator-only by default.

![Fireball and blast wave of a 15 kt airburst over Project Zearth](docs/guide/img/nuke-wave.jpg)

![Ruined city in the dust after a 15 kt airburst](docs/guide/img/nuke-ruins.jpg)

### Weapons

- **Shahed-136:** send a drone toward a target, alone or in a salvo.
- **Lancet:** circle above the target, look through its camera and choose where to dive.
- **Cruise missile:** fly low over the terrain, then climb and dive into the target.
- **Grad:** fire unguided rockets across an area.
- **B-2:** call in a bomber with a bunker buster that penetrates the ground before exploding.
- **ICBM:** carries a nuclear warhead to the target after a warning period.

### Air defense and sirens

- **SAM:** shoots down hostile drones, cruise missiles and B-2s; it cannot intercept Grad rockets or ICBMs.
- **Sirens:** warn players near a target.

### Launching

- **Strike Designator:** aim through binoculars or use the console map. Set salvos and routes, or watch through a projectile's camera.
- **Fixed launchers:** load ammunition, assign a target with the designator and fire with redstone.

## Requirements and dependencies

**Minecraft 1.21.1 · NeoForge 21.1.264+ · Java 21.** Incoming is required on the server and every client.
No other mods are required.

Create, Create Aeronautics with Sable, Distant Horizons, Voxy through Roxy and Iris are optional.
See [compatibility and supported versions](docs/guide/compatibility.md).

## Installation

Install NeoForge for Minecraft 1.21.1, then put the [Incoming jar](https://github.com/zentixua/incoming/releases/latest)
in `mods`. For multiplayer, install the same version on the server and every client.
Start with the [player guide](docs/guide/README.md).

## FAQ

**Fabric or Forge?** This version is for NeoForge only.

**Other Minecraft versions?** Incoming currently supports 1.21.1.

**Can I use it in a modpack?** Yes, under the GPL-3.0 license.

**Client or server?** Both. It works in single-player, LAN worlds and dedicated servers. Use the same Incoming version on both sides.

**How demanding is it?** Large salvos, nuclear strikes and shaders can be demanding. See [performance help](docs/guide/faq.md#troubleshooting).

**Can I protect builds?** The owner can restrict launches and disable destruction and fires in [world settings](docs/guide/settings.md#world-settings). Destruction is on by default.

## Links

[Player guide](docs/guide/README.md) · [Issues](https://github.com/zentixua/incoming/issues) · [Source](https://github.com/zentixua/incoming)

## License and credits

Made by ZentixUA. [GPL-3.0-only](LICENSE). [Sound credits](SOUND-CREDITS.md) · [Asset credits](tools/bake_fx/SOURCES.md).
Includes sable-companion and FastNoiseLite by Jordan Peck (MIT); fireball and smoke textures use JangaFX CC0 simulations.
