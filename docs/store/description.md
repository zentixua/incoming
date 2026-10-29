# Incoming

![Cruise missile salvo over a factory in Project Zearth, seen from a fixed camera on a low terrace](https://raw.githubusercontent.com/zentixua/incoming/main/docs/media/hero.webp)

Air strikes and air defense for Minecraft 1.21.1.

Incoming adds air strikes with explosions, smoke, fires and block destruction, from drone attacks to nuclear strikes.
It includes six weapons, air defense and sirens, with a designator for choosing targets and planning salvos.

## Features

- **Explosions and aftermath:** fireballs light up nearby terrain, blasts break blocks and shatter windows, and dust and smoke rise and drift with the wind. Strikes can start fires; leftover fuel burns in the crater.
- **Blackout:** strike a transformer substation to cut electric lights in its district.

![Cruise missile above the factory, seen from a fixed camera on a low terrace](https://raw.githubusercontent.com/zentixua/incoming/main/docs/media/still-5.jpg)

### Nuclear strikes

Flash, blast wave, mushroom cloud, fires and ruins. Ground bursts leave a crater and fallout downwind.
Launch an ICBM or fit a nuclear warhead to a cruise missile or B-2 bomb. Operator-only by default.

![A 6 kt ground burst at dusk, seen from a fixed ground camera: flash, rising cloud and the wave reaching the observer](https://raw.githubusercontent.com/zentixua/incoming/main/docs/media/nuke.webp)

![Rising cloud from a 6 kt ground burst in downtown Newisle at dusk, seen from ground level](https://raw.githubusercontent.com/zentixua/incoming/main/docs/guide/img/nuke-cloud.jpg)

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
