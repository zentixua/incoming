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
| `… via <x z> [<x z> …]` | route: up to five points, for the Shahed, missile and Lancet only |
| `… from <x z>` | launch point: a launcher sets up there (or at the nearest open, level spot within about 100 blocks) and stays until an abort; if there is none, the rounds take off from there without it (operators only) |

`from` and `via` go after the salvo target, `from` first. A single round on a route is a salvo of one:
`salvo drone 1 0 …`.

## Nuclear strike

| Command | What it does |
|---|---|
| `/incoming nuke [kt [air\|ground]]` | ICBM where you are looking |
| `/incoming nuke at <x y z> [kt [air\|ground]]` | ICBM at a point |
| `/incoming nuke now [at <x y z>] [kt [air\|ground]]` | detonation at once, without a flight (operators only) |

Without a number: the world's `nuclear.default_yield` (15 kt by default). Without a burst mode: ground burst.
`air` or `ground` only after the number: `/incoming nuke 15 air` selects an air burst.

## Other commands

| Command | What it does |
|---|---|
| `/incoming clear` | cancel your conventional salvos and recall eligible projectiles; operators or the host abort every player's strikes, including nuclear ones |
| `/incoming menu` | console |
| `/incoming help` | short help in chat |
| `/incoming give [players]` | give a Strike Designator (operators only) |
| `/incoming radiation [player]`, `radiation clear [players]` | radiation dose, clear radiation (operators only) |

For ordinary players, Grad rockets already in flight and released bombs continue to their targets; nuclear orders
cannot be recalled, including those waiting for a second key.

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
| `… fire` | order by the mission, like a redstone signal |
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
/incoming nuke at 2000 64 0 1 ground
/incoming launcher 100 64 -20 mission missile 2 10 120 70 -340
```

1. A cruise missile at the player Steve (if spotted).
2. A ripple of 20 Grad rockets with a spread of 25 blocks at a point.
3. A swarm of five Lancets where you are looking.
4. One Shahed at a point, detouring through two route points.
5. Four cruise missiles at a point from a launcher at -900 400 (operator).
6. A 1 kt ground burst at a point.
7. A mission for the launcher at 100 64 -20: two cruise missiles with a spread of 10 blocks at a point (operator).
