# Commands

English · [Русский](ru/commands.md)

The `/incoming` command orders conventional strikes and ICBMs and provides administration tools. It does not select
a nuclear warhead for a cruise missile or B-2; use the designator for that. Its entity argument selects a player or
mob, rather than the designator's aircraft picker. For a regular player the command uses
munitions and follows the same [rules](rules.md) as the designator; for operators and command blocks it does not.

Conventional `<weapon>` values: `drone` (or `shahed`), `loiter` (`lancet`), `missile`, `rocket` (`grad`), `bunker`.
The separate `nuke` syntax is below; it has no entity target, `at <x z>` form or salvo command.

## Conventional strikes

| Command | What it does |
|---|---|
| `/incoming <weapon>` | strike where you are looking |
| `/incoming <weapon> <player or mob>` | strike at a target |
| `/incoming <weapon> at <x z>` | strike at a place on the ground; the server finds its height, as for a place picked [on the map](map.md) |
| `/incoming <weapon> at <x y z>` | strike at exactly this point; above the ground the round bursts in the air, a bomb falls to the ground |
| `/incoming salvo <weapon> <count> <spread> [target]` | salvo; the target is `me` (where you stand, the default), `look`, a player or mob, `at <x z>` or `at <x y z>` |
| `… via <x z> [<x z> …]` | route: up to five points, for the Shahed, missile, Lancet and B-2 only |
| `… from <x z>` | launch point: a launcher sets up there (or at the nearest open, level spot within about 100 blocks) and stays until an abort; if there is none, the rounds take off from there without it (operators only) |
| `… tag <name>` | name a group of orders, to check or recall them together (see below) |

`from` and `via` go after the salvo target, `from` first; `tag` comes last. A single round on a route is a salvo of
one: `salvo drone 1 0 …`.

Every strike command returns how many rounds its order was given: a salvo of 20 returns 20 at once, although its
rounds launch over the next seconds; an ICBM returns 1; a refused order returns 0. Command blocks and
`execute store result` read this number. The chat line names the order with its id. A strike command run from inside
another mod's check of an order is carried out on the next tick: it returns 0 and names its order once it is given.

## Nuclear strike

| Command | What it does |
|---|---|
| `/incoming nuke [kt [air\|ground]]` | ICBM where you are looking |
| `/incoming nuke at <x y z> [kt [air\|ground]]` | ICBM at a point |
| `/incoming nuke [at <x y z>] [kt [air\|ground]] from <x z>` | ICBM from a chosen ground pad (operator only); no waypoints |
| `/incoming nuke now [at <x y z>] [kt [air\|ground]]` | detonation at once, without a flight (operators only) |
| `… tag <name>` | at the end of any `nuke` command: name a group of orders |

Without a number: the world's `nuclear.default_yield` (15 kt by default). Without a burst mode: ground burst.
`air` or `ground` only after the number: `/incoming nuke 15 air` selects an air burst.

## Other commands

| Command | What it does |
|---|---|
| `/incoming clear` | cancel your conventional salvos and recall eligible projectiles; operators or the host abort every player's strikes, including nuclear ones, and [end the aftermath](nuke.md#ending-the-aftermath) of past nuclear blasts |
| `/incoming clear tag <name>` | the same for one group of orders: your own rounds in it, or, for operators and the host, all of them; launchers and past nuclear blasts stay |
| `/incoming status` | your unfinished orders (operators and the host: everyone's): rounds waiting to launch, in flight and still exploding, and when the last one arrives |
| `/incoming status tag <name>`, `status <player>` | one group of orders; one player's orders (operators only) |
| `/incoming menu` | console |
| `/incoming help` | short help in chat |
| `/incoming give [players]` | give a Strike Designator (operators only) |
| `/incoming radiation [player]`, `radiation clear [players]` | radiation dose, clear radiation (operators only) |

For ordinary players, Grad rockets already in flight and released bombs continue to their targets; nuclear orders
cannot be recalled, including those waiting for a second key.

A tag is one word of up to 32 letters, digits and `_ - . +`; capital letters count. Any number of orders, from any
player, may share a tag, and it stays with them after a server restart. `status` returns the number of unfinished
rounds: a datapack can wait for it to reach 0 (see [For server owners and pack makers](packs.md#command-results-status-and-tags)).

## World rules

Operators only (the console and command blocks too). `/incoming rule` reads and changes the world rules below while
the world runs and saves them at once to the world's settings file, the same values as in
[World settings](settings.md#world-settings): they stay after a restart and show in the settings screen.

| Command | What it does |
|---|---|
| `/incoming rule` | list every rule with its value and what it accepts |
| `/incoming rule <key>` | show one rule; returns its value as a number (on 1, off 0) for `execute store result` |
| `/incoming rule <key> <value>` | change it: `true` or `false`, or a whole number in the rule's range; returns 1 |

An unknown rule or a value it does not accept is refused with what it accepts, and nothing changes.

A change applies to orders given after it. Orders already given keep the rules they were given under: the rest of a
salvo still launching, rounds in flight (also out of sight), a scheduled ICBM, and what a strike that has already hit
still does (its debris landing, its fuel burning). `nuclear.enabled` is also the off switch for nuclear launches not yet
under way: switched off, a nuclear order waiting for a second key or its launch site does not leave and its munitions
come back; an ICBM already flying still arrives. `nuclear.ops_only` and `launch.designator_for_everyone` decide who has
the right to launch, and a nuclear launch waiting for a second key asks its owner's rights again when the key turns
([permissions](packs.md#permissions)): a right lost in the meantime cancels it and returns the munitions.

| Rule | What it governs |
|---|---|
| `world.block_damage` | conventional blasts remove blocks: craters, breached walls, floors and slabs, the bunker-buster's bore and cavity, fragment damage to blocks. Off: blasts still hurt players and mobs |
| `world.shatter_glass` | the blast wave and fragments break glass beyond the crater |
| `world.fire` | the fuel fireball sets grass and foliage alight, fuel in the crater burns, burning debris lights what it lands on |
| `world.debris_stay` | ground clods, collapse rubble, airframe parts and burning debris stay as blocks. Off: they vanish on landing |
| `world.collapse` | what a blast leaves without support falls (needs `world.block_damage`) |
| `grid.enabled` | a strike near a [substation](blackout.md) knocks it out and darkens its area; a nuclear strike's blackout needs it too. TNT and creepers near a substation follow its current value |
| `nuclear.enabled` | nuclear weapons: the ICBM, nuclear warheads, `/incoming nuke` |
| `nuclear.ops_only` | only operators and the host launch nuclear strikes; off: other players need a second key |
| `nuclear.carrier_nukes` | a nuclear warhead on the cruise missile and the B-2 |
| `launch.designator_for_everyone` | the designator works for every player, not only operators |
| `launch.max_salvo` | largest salvo, 1–100 rounds |
| `launch.max_spread` | largest salvo spread, 0–500 blocks |
| `launch.max_active_per_player` | how many unfinished rounds a player may have for a new order to be accepted, 0–100000; 0: no limit |

With the five `world.*` rules and `grid.enabled` off, conventional strikes change no block and cut no power: an arena
keeps its shape round after round ([example](packs.md#world-rules-for-a-minigame)). Nuclear strikes have their own
settings and are outside this: switch `nuclear.enabled` off for such an arena. Other settings (flight times, nuclear
effects, scale, performance) stay in the [settings file](settings.md#world-settings).

## Power grid

Operators only. Coordinates and node IDs refer to the command's dimension, except `status` and a bare `restore`,
which cover all dimensions. Optional coordinates default to your position; optional radii default to
`grid.node_radius` (512 blocks by default). New nodes and outages are capped by `grid.max_radius` (4096 blocks by default).

| Command | What it does |
|---|---|
| `/incoming grid status` | outages, nodes and pending light updates in all dimensions |
| `/incoming grid blackout [<x y z> [radius]]` | cut power without a substation; requested radius 16–16384 blocks |
| `/incoming grid restore` | restore every outage in all dimensions |
| `/incoming grid restore at <x y z> <radius>` | restore outages whose centers lie in this area; radius 1–1000000 blocks |
| `/incoming grid node add [<x y z> [radius]]` | add a grid node without placing a substation block; requested radius 16–16384 blocks |
| `/incoming grid node list` | list nodes and their IDs in this dimension |
| `/incoming grid node remove <id>` | remove a node |
| `/incoming grid node knockout <id>` | knock a node out as a strike would |

For example, `/incoming grid blackout 100 64 -200 128` selects an area around X=100, Z=-200. Afterwards,
`/incoming grid restore at 100 64 -200 128` starts restoring outages centered within 128 blocks of that point.
Their whole districts recover over 10 seconds; restoration is not clipped to the circle.

## Fixed launcher

Operators only. The launcher has to be in a loaded chunk.

| Command | What it does |
|---|---|
| `/incoming launcher <x y z>` | status: mission, stock, last signal |
| `… fire` | order by the mission, like a redstone signal; returns how many rounds the order was given, like a strike command |
| `… mission <weapon> <count> <spread> <x z> [via <x z> …]` | mission without player rules; the target is a place on the ground or, with `<x y z>`, a point |
| `… clear` | remove the mission |
| `… load <N>` | put N munitions of the mission's weapon into stock |
| `… owner <player>`, `owner none` | change the owner or make the launcher ownerless |

A launcher without an owner (for example, one placed with `/setblock`) fires without player rules, like the server
console.

## Examples

```
/incoming missile Steve
/incoming salvo rocket 20 25 at 120 70 -340
/incoming salvo loiter 5 20 look
/incoming salvo drone 1 0 at 800 64 200 via 300 -100 600 -50
/incoming salvo missile 4 10 at 120 70 -340 from -900 400
/incoming salvo rocket 40 30 at 120 70 -340 tag wave1
/incoming status tag wave1
/incoming nuke at 2000 64 0 1 ground
/incoming launcher 100 64 -20 mission missile 2 10 120 70 -340
/incoming rule world.block_damage false
```

1. A cruise missile at the player Steve (if spotted).
2. A ripple of 20 Grad rockets with a spread of 25 blocks at a point.
3. A swarm of five Lancets where you are looking.
4. One Shahed at a point, detouring through two route points.
5. Four cruise missiles at a point from a launcher at -900 400 (operator).
6. A Grad ripple of 40 rockets in the group `wave1`.
7. How many rounds of `wave1` are still waiting, flying or exploding.
8. A 1 kt ground burst at a point.
9. A mission for the launcher at 100 64 -20: two cruise missiles with a spread of 10 blocks at a point (operator).
10. From now on, conventional strikes break no blocks (operator).
