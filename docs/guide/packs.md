# For server owners and pack makers

English · [Русский](ru/packs.md)

Incoming tells other mods and scripts what happens to every strike through NeoForge events on the game event bus.
A pack can charge its own price for strikes, add cooldowns, count hits for a scoreboard or let a game master react
to a launch. Everything here works on a dedicated server, a LAN world and single-player.

## Strike events

Every strike order goes through the same steps, whether it comes from the designator, a command, a fixed launcher,
a nuclear launch that waits for a second key or warning, or `/incoming nuke now`.

| Event (class in `ua.zentix.incoming.api`) | When it comes |
|---|---|
| `StrikeOrderEvent$Request` | The order passed Incoming's own checks (permissions, targeting rules, nuclear rules, the active-strike limit, a launcher's mission and stock). Cancel it to refuse the order; nothing is paid yet. |
| `StrikeOrderEvent$Rejected` | The order was refused after its request: cancelled by a listener, or the player lacked munitions when paying. |
| `StrikeOrderEvent$Accepted` | The order is recorded and paid. It says how many rounds it has and how they are paid: upfront from the player's inventory, one by one from a fixed launcher's magazine, or not at all (console, command blocks, operators, creative). |
| `StrikeRoundEvent$Released` | A round leaves: a projectile takes its rail or starts from afar, an ICBM launches, a `nuke now` burst begins. |
| `StrikeRoundEvent$Ended` | A round is over: its warhead went off, it was shot down, it failed (crashed before arming, a failed launch), or it was recalled or cancelled. It also says whether the round's munitions came back and whether its owner steered it through its camera. |
| `NuclearDetonationEvent` | A nuclear warhead bursts: position, yield, air or ground burst, and the order and round it belongs to. |
| `GridOutageEvent` | A power outage begins: a substation was knocked out or a nuclear burst cut the grid. |
| `GridRestoreEvent` | This outage no longer darkens any district (another outage may still darken some). An outage around a destroyed area ends only when power is restored by command. |

Every request ends in exactly one `Rejected` or `Accepted`, and every round of an accepted order ends in exactly one
`Ended`. A round keeps its id while it flies outside the loaded world, comes back into it, and across a server
restart. A round that never left (a cancelled salvo, a failed launch, a nuclear launch without its second key) ends
without `Released`. All events come from the server thread; their values are plain numbers, ids, names and
positions. `Request`, `Rejected` and `Accepted` arrive while the order is being made, so the next order already
sees the last one accepted. The other events arrive at the end of the same tick, in the order things happened. A
nuclear burst or an outage that belongs to no order (another mod's, or one saved before you added your script) is
reported too, with no order or round id.

To charge your own price or add a cooldown, check it in `Request`, take it in `Accepted`, and give it back in
`Ended` if you wish. Incoming does not undo another mod's charges. A strike started from inside a `Request` listener
is made on a later tick, with all its checks and its own request.

## KubeJS example

KubeJS listens to these events with `NativeEvents.onEvent`. This server script refuses a player's strikes for
30 seconds after their last accepted one:

```js
const Request = Java.loadClass('ua.zentix.incoming.api.StrikeOrderEvent$Request')
const Accepted = Java.loadClass('ua.zentix.incoming.api.StrikeOrderEvent$Accepted')
const last = {}

NativeEvents.onEvent(Request, event => {
  const player = event.getActorId()
  if (player != null && Date.now() - (last[player] || 0) < 30000) event.setCanceled(true)
})

NativeEvents.onEvent(Accepted, event => {
  const player = event.getActorId()
  if (player != null) last[player] = Date.now()
})
```

The player sees "Strike order refused" above the hotbar. A Java mod can show its own line with `setRefusal`.

## Command results, status and tags

Strike commands return numbers that command blocks and `execute store result` read, so a datapack or a game master
can run a minigame round with strikes and no other mod.

- A strike command (`/incoming <weapon> …`, `salvo`, `nuke`, `nuke now`, `launcher … fire`) returns how many rounds its order was given:
  a salvo of 12 returns 12 on the tick it is given, although its rounds launch over the next seconds; an ICBM returns 1;
  a refused order returns 0. Its chat line names the order with the id the strike events carry.
- `salvo …` and `nuke …` take `tag <name>` at the end. A tag names a group of orders: any number of orders, from
  anyone, in any dimension, may share it, and it stays with their rounds across a server restart.
- `/incoming status` lists unfinished orders and returns the number of their unfinished rounds; `status tag <name>`
  counts one group, `status <player>` one player's orders (operators only). A round is unfinished while it waits to
  launch (the rest of a salvo, a nuclear launch waiting for its second key), while it flies, and after it hits while
  its blast is still being worked out: a conventional warhead's explosion, crater, collapse, spilled fuel, debris and
  broken glass;
  a nuclear blast wave until it has passed its full reach. Ruins that appear later, in chunks loaded afterwards, do
  not count. Each order shows when its last round arrives, or "unknown" while that cannot be told (a salvo still
  launching, a second key, a strike waiting for its ground to load).
- `/incoming clear tag <name>` recalls one group: operators, the host and the console cancel all its rounds, nuclear
  ones included; a player without operator rights recalls only their own rounds in it, as with `/incoming clear`.
  Launchers stay until a full `/incoming clear`.

A player without operator rights sees only their own orders in `status`. When `status tag <name>` returns 0, every
round of the group has ended (its `StrikeRoundEvent$Ended` arrives by the end of that tick) and its explosions are
over.

```mcfunction
# start a wave
execute store result score #wave arena run incoming salvo rocket 20 25 at 120 70 -340 tag wave1
# every second: rounds of the wave still waiting, flying or exploding
execute store result score #left arena run incoming status tag wave1
```

## A game master

Incoming carries a note for the [Almighty](https://github.com/zentixua/almighty) AI game master:
`data/incoming/almighty/notes/strikes.md`. It tells the game master the strike, grid and radiation commands and what
each returns, the strike events its rules can follow, and two ready rules: raid waves, each started once
`status tag` of the last returns 0, and a reaction to a nuclear detonation. A datapack replaces the note with its own
file at the same path; an empty file removes it.

## World rules for a minigame

`/incoming rule` (operators, the console, command blocks and functions) changes world rules while the world runs and
saves them to the world's settings file; the [list of rules](commands.md#world-rules) says what each one governs. A
change applies to orders given after it: a wave already in the air keeps the rules it was ordered under, so switching
the rules back on right after the wave is safe. With `world.block_damage`, `world.shatter_glass`, `world.fire`,
`world.debris_stay`, `world.collapse` and `grid.enabled` off, conventional strikes change no block and cut no power.
Nuclear strikes have their own rules: turn `nuclear.enabled` off for the arena.

```mcfunction
# arena round: strikes hurt players but leave the map as it is
incoming rule world.block_damage false
incoming rule world.shatter_glass false
incoming rule world.fire false
incoming rule world.debris_stay false
incoming rule world.collapse false
incoming rule grid.enabled false
incoming rule nuclear.enabled false
execute store result score #wave arena run incoming salvo rocket 20 25 at 120 70 -340 tag wave1
```

`/incoming rule <key>` returns the value as a number (on 1, off 0), so a function can check the arena state with
`execute store result`.

## Advancements and statistics

Incoming's advancements and statistics are vanilla ones, so quest mods (FTB Quests advancement and stat tasks),
scoreboards, datapacks and a game master that follows advancements use them as they are. What players see:
[Advancements and statistics](progress.md).

| Advancement | Criterion trigger | Granted when |
|---|---|---|
| `incoming:strikes/root` (the tab) | `minecraft:inventory_changed`, `incoming:round_released` | the player holds any Incoming item or launches a round |
| `incoming:strikes/first_strike` | `incoming:round_released` | a round of the player's leaves |
| `incoming:strikes/camera_strike` | `incoming:camera_guided_hit` | a round the player steered through its camera goes off |
| `incoming:strikes/first_interception` | `incoming:round_intercepted` | the player's SAM or weapon shoots down a hostile round |
| `incoming:strikes/nuclear_survivor` | `incoming:nuclear_survived` with `overpressure_psi` at least 5 | the player is alive once a nuclear blast wave that hit them at 5 psi or more has passed |
| `incoming:strikes/lights_back` | `incoming:blackout_restored` | an outage ends while the player stands in one of its districts and that district is lit again |

Your own advancements can use the triggers with these conditions (all optional, besides the usual `player`):

| Trigger | Conditions |
|---|---|
| `incoming:round_released` | `weapon`: `drone`, `loiter`, `rocket`, `missile`, `bunker` or `nuke` |
| `incoming:camera_guided_hit` | `weapon` |
| `incoming:round_intercepted` | `weapon` (of the round shot down), `by_sam` (true for a SAM, false for the player's own weapon) |
| `incoming:nuclear_survived` | `overpressure_psi`, `yield_kt` (number ranges, as `{"min": 5}`) |
| `incoming:blackout_restored` | `nuclear` (true for the outage a nuclear burst caused over its whole reach) |

| Statistic | Scoreboard criterion | Counts |
|---|---|---|
| `incoming:rounds_launched` | `minecraft.custom:incoming.rounds_launched` | rounds the player launched |
| `incoming:rounds_launched.<weapon>` | `minecraft.custom:incoming.rounds_launched.drone` and the other weapon ids | the same for one weapon |
| `incoming:interceptions` | `minecraft.custom:incoming.interceptions` | hostile rounds the player shot down |
| `incoming:nuclear_detonations_witnessed` | `minecraft.custom:incoming.nuclear_detonations_witnessed` | nuclear detonations the player was within reach of (glass breaks or skin burns there) |

- A round credits its owner, the same player the strike events name, and only while they are online: nothing is kept
  for an offline owner. Rounds from the console, command blocks, ownerless launchers and fake players with their own
  identity credit no one. `/execute as <player>` makes the round that player's, and so does a machine acting under the
  player's own profile (a Create deployer for its owner), as with a fixed launcher and with permissions.
- A round shot down counts for the SAM's owner or for the player whose weapon brought it down, unless the round is
  theirs or a teammate's (by `/team`, as the SAM decides whom to shoot).
- Still Standing starts when the wave front actually hits the player: it reads the pressure at their position from
  the blast model, shelter or not, and waits until the positive phase there has passed plus one second for a thrown
  body to land. A player who left before the front, a cleared strike, creative and spectator mode get nothing; a
  death, logout or change of dimension before then cancels it.
- Lights Back On follows the grid's districts: the player's district must have gone dark in the outage and must be
  lit once it ends (another outage over it keeps it dark).

```mcfunction
# a sidebar of rounds shot down
scoreboard objectives add shot_down minecraft.custom:incoming.interceptions "Shot down"
scoreboard objectives setdisplay sidebar shot_down
```

A datapack advancement for surviving a 20 psi blast of an ICBM-sized yield:

```json
{
  "criteria": {
    "close": {
      "trigger": "incoming:nuclear_survived",
      "conditions": { "overpressure_psi": { "min": 20 }, "yield_kt": { "min": 10 } }
    }
  }
}
```

## Kill credit

- Every injury Incoming deals is vanilla damage of its own types (`incoming:strike`, `incoming:shockwave`,
  `incoming:kinetic`, `incoming:fragment`, `incoming:debris`, the nuclear ones and their `incoming:player_…` pairs)
  with the round's owner as the attacker (`DamageSource.getEntity()`) when the owner is online, in any dimension, at
  the moment of the damage; the projectile or the debris, where there is one, is the direct source. Vanilla gives the
  credit from there: death messages, `minecraft:player_killed_entity`, the kill statistics and their scoreboard
  criteria (`playerKillCount`, `totalKillCount`, `minecraft.killed:minecraft.<entity>`), player-only loot and
  experience. `LivingDeathEvent` listeners read the owner the same way.
- The owner is kept as a UUID on everything that carries a strike, whoever is online: orders, rounds and their events,
  projectiles in flight and off-world, debris, and a burn that will end in shock; all but debris (it lands within
  seconds and is not saved) survive a restart. Only the attacker needs the owner online: an offline owner's strike
  hurts with no attacker, and nothing is credited later.
- One cause has one attacker: the blast, the B-2 bomb's hit and shockwave, fragments, debris (the falling parts of a
  round shot down belong to the round's owner), the nuclear wave, its light, prompt radiation, and burn shock from the
  burst whose light caused it. No attacker: radiation sickness from the gathered dose and fallout (bursts and the rain
  add up), a round that crashes before its fuze arms (a plain vanilla explosion), fire spreading as game fire, mobs
  that load after a burst and receive what their place received, and the wave's blasts on Create Aeronautics craft.
- PvP and `/team` friendly fire apply to that damage as to an arrow. A mob hurt by an owner in another dimension takes
  no revenge target: it cannot reach them.
- Rounds from the console, command blocks and ownerless launchers have no owner. A fake player is credited only under
  an online player's profile, as in [Advancements and statistics](#advancements-and-statistics).

## Permissions

Who may launch is decided by NeoForge permission nodes. Without a permission mod they follow the settings
(`launch.designator_for_everyone`, `nuclear.ops_only`, operator rights), so nothing changes. A permission mod that is
the active NeoForge permission handler (LuckPerms sets itself up when NeoForge's default handler is selected) can grant
or deny each right per player or group, and per dimension through its contexts.

| Node | Without a permission mod | What it allows |
|---|---|---|
| `incoming.launch` | everyone with `launch.designator_for_everyone` on, otherwise operators | the designator, its map and abort, strike commands |
| `incoming.launch.<weapon>` | everyone | one weapon: `drone` (Shahed-136), `loiter` (Lancet), `rocket` (Grad), `missile` (cruise missile), `bunker` (B-2 with its bomb), `nuke` (ICBM) |
| `incoming.nuclear` | everyone with `nuclear.ops_only` off, otherwise operators and the host of a single-player or LAN world | nuclear orders, a nuclear warhead on a cruise missile or B-2 included, and turning a second key |
| `incoming.launcher.link` | as `incoming.launch` | linking fixed launchers to a designator and giving them missions |

- The nodes apply to every order a player gives: the designator, the commands they type (an operator's too, and
  through `/execute as`), launchers linked to their designator, and a fixed launcher they own, which fires under its
  owner's rights. An order started inside another order's event runs a tick later and asks again then. A nuclear launch
  waiting for a second key asks its owner again when the key turns: a right taken away in the meantime cancels it and
  returns the munitions. A `nuke now` started a tick later asks again too and still needs an operator.
- The console, command blocks and functions they run, and launchers placed by command (without an owner), are not
  asked. A flying nuclear strike they make for a player (`/execute as`) needs that player's rights; `nuke now` does
  not.
- With `nuclear.enabled` off nobody launches nuclear weapons, whatever the nodes say; a carrier already warning its
  target does not leave and its munitions come back (to an offline owner when they join again). `incoming.nuclear` does not make a player an operator: they still
  need a second key from a player nearby, and only operators and the host abort every nuclear strike, retarget a
  nuclear warhead or pick a launch site on the map. Only players who may turn a second key are asked to.
- A permission mod cannot be asked about a player who is offline without risking a pause of the server, so a fixed
  launcher whose owner is offline follows the column "Without a permission mod": what the permission mod grants or
  denies the owner applies to their launcher while they are online.
- A refused order shows its reason above the hotbar; a fixed launcher shows it in its status line. A player without
  `incoming.launch` and without operator rights does not get the `/incoming` command at all.

With LuckPerms, for example:

```
/lp group default permission set incoming.launch.bunker false
/lp group default permission set incoming.launch.nuke false
/lp group veterans permission set incoming.launch.nuke true
/lp group veterans permission set incoming.nuclear true
```

## Claim protection

Claim mods that protect chunks from explosions (Open Parties and Claims, FTB Chunks and any other mod that filters
explosion damage) protect them from every change Incoming makes to blocks: the warhead's blast, its crater, broken
glass, fire from the fireball and the burning fuel, fragment marks, the B-2 bomb's bore and underground cavity,
buildings that collapse and the rubble they drop, debris that lands; for a nuclear burst also the flash (scorched
ground and walls, fires it lights, scorched biome colors), the ruins behind the wave, the crater, toppled trees and the
far terrain seen through Voxy or Distant Horizons. In a protected chunk the wave, light, sound and injuries happen as
everywhere else; only the blocks stay.

- The claim mod decides as for any explosion: in Open Parties and Claims the claim's explosion protection, in FTB
  Chunks the team's "Allow explosions" setting. A conventional strike carries its projectile as the source of the
  explosion, not its owner. A nuclear burst has no source, so FTB Chunks protects against it only with
  `protect_unknown_explosions` on (its default).
- A nuclear burst decides each chunk for good: a chunk found protected when the flash or the wave reaches it keeps its
  blocks for that whole burst, even if the claim is removed a moment later, and a claim placed after the flash still
  keeps the wave out. The decision is saved with the world: ruins built after a restart, or when a far chunk loads
  later, follow it. The next burst decides again.
- Nuclear ruins change a chunk as a whole: a mod that protects part of a chunk keeps the whole chunk from the nuclear
  ruins. A conventional strike leaves exactly the protected blocks.
- The far terrain of a chunk that is not loaded shows the ruins unless that chunk was already found protected; the
  chunk itself is decided when it loads, and the far view follows it once the chunk is seen.
- Fire that spreads afterwards is ordinary game fire: whether it crosses into a claim is up to the claim mod's fire
  settings. Power outages are not block damage: a claim does not keep a substation from being knocked out, and a
  blackout darkens claimed chunks too.

For mod authors: Incoming asks through `ExplosionEvent.Detonate` with an explosion of class
`ua.zentix.incoming.api.ClaimQuery` (zero radius, no entities) whose block list holds the positions about to change,
only in chunks already loaded. It is a question, not an explosion: a listener that reacts to explosions themselves
(effects, sounds) should skip it.

## Redstone

Two blocks give redstone, so contraptions and computers react without any script. Both give weak power of strength 15
from all six sides, like a daylight detector: dust, repeaters, lamps, pistons, doors, Create's Redstone Link and
ComputerCraft's `redstone.getInput(side)` next to them read it; it does not pass through the block they touch. Neighbours
learn of a change on the tick it happens, observers included. It works the same in single-player, a LAN world and on a dedicated server.

| Block | Signal on | Signal off |
|---|---|---|
| SAM | its radar's latest sweep (every half second) saw a hostile projectile, whether or not it would fire at it | no hostile projectile in its radar's sight; a solid roof over it blinds it |
| Transformer Substation | it works (hums) | it is knocked out (sparks and burning oil), until power starts coming back to its area |

- The SAM's comparator output is still its stock. A SAM without an owner counts every projectile hostile; one without
  missiles still gives the signal. Details: [Defense](defense.md#redstone).
- The substation's signal follows the substation itself, not its district: another substation's outage over it leaves
  it on. Details: [Blackout](blackout.md).
- A block in an unloaded chunk keeps the signal it was saved with and updates within a second once its chunk works
  again. Within 32 blocks of ground that is not loaded, its neighbours learn of a change once that ground loads: the
  circuit's synchronous update would otherwise load chunks during the server tick.

A bunker door that closes on alarm, with Create: put a Redstone Link (transmitter) against the SAM and a receiver on
the same frequency at the bunker. The receiver powers a Gearshift between the shaft and a Mechanical Piston that holds
the door wall: while hostile targets are in the air the gearshift reverses the rotation and the wall slides shut; when
the sky is clear it opens again. A ComputerCraft computer with the SAM behind it can do the same in a script:

```lua
-- the SAM behind the computer, a siren or door circuit on its left
local function check()
  local alarm = redstone.getInput("back")
  redstone.setOutput("left", alarm)
  print(alarm and "Air raid" or "Sky clear")
end
check()
while true do
  os.pullEvent("redstone")
  check()
end
```

## Jade

With Jade on the server and the client, looking at Incoming's blocks and projectiles shows what the block itself does
not:

| Looking at | Everyone sees | The owner's side and operators also see |
|---|---|---|
| Fixed launcher | rounds of its mission's weapon in stock (munitions held, without a mission); ready to fire, or cannot fire, with the reason when it is about the launcher itself: no mission, busy, on an aircraft | the owner, the mission (weapon, count, target, spread) and every reason it cannot fire |
| SAM | missiles on the rails and in stock; how many hostile targets its radar tracks, or that a roof blinds it | |
| Transformer Substation | working; knocked out, and in how many hours of the world's day repairs start; or without power in a destroyed zone | |
| Strike projectile | its weapon | its owner |

The owner's side is the owner and the players on the owner's team (`/team`); operators have permission level 2. A
launcher placed or a projectile launched by command has no owner: its owner line and mission are for operators. The
launcher's own status line (a right click with an empty hand) follows the same rule. Each of the four can be turned off
in Jade's plugin settings.

## Computers (CC:Tweaked)

With CC:Tweaked, a computer next to a fixed launcher or a SAM, or connected to one by a wired modem, uses it as a
peripheral. Every call runs on the server's main thread, so it takes at least one tick.

Fixed launcher, peripheral type `incoming_launcher`:

| Method | Returns |
|---|---|
| `getStatus()` | a table: `weapon` (the mission's, as on the rack; absent without a mission), `ready` (a signal now would fire it), `busy`, and when it is not ready, `reason` (a code) and, for a reason about the launcher itself, `message` (the English text) |
| `getStock()` | rounds held per weapon: `drone` (Shahed-136), `missile`, `rocket` (Grad), `loiter` (Lancet) |
| `fire()` | `true` when the order is given, otherwise `false, reason, message` (`false, "private"` for a reason about the owner or the mission) |

`fire()` is a redstone signal: the same order in the owner's name with the owner's rights and limit, the same refusals,
the same strike events, munitions paid as each round takes the rail. A computer cannot set or clear a mission: that
stays the designator's and `/incoming launcher … mission`. A refused `fire()` clicks like a refused signal.

Anyone can put a computer at a launcher, so a computer learns what a player without rights would: never the owner or
the mission's count, target and spread. A reason about the launcher itself comes as its code, the refusal's language key
without `incoming.`: `fixed_launcher.no_mission.short`, `fixed_launcher.busy`, `fixed_launcher.on_craft`,
`fixed_launcher.sector` (buildings block the launch) or `fixed_launcher.failed`; any other reason (stock short of the
mission, the owner's rights or limit, the target's range or route) comes as `private`.

SAM, peripheral type `incoming_sam`:

| Method | Returns |
|---|---|
| `getStock()` | `ready` (missiles on the rails), `rails`, `stored` |
| `getTracking()` | from its radar's latest sweep: `tracking` (a hostile target, as the redstone signal), `hostile`, `tracks` (everything on the radar), `covered` (a roof blinds it) |
| `isTracking()` | `true` while its radar tracks a hostile target |

While its radar tracks a hostile target, every sweep (twice a second) sends the event `incoming_alert` to the computers
attached to this SAM: the attachment name, the number of hostile targets and the distance to the nearest, in blocks.
The events stop with the first sweep that sees none. No other computer receives them.

```lua
-- a SAM on the left, fixed launchers on the computer's wired modem network: one salvo per raid
local sam = peripheral.wrap("left")
local launchers = { peripheral.find("incoming_launcher") }
while true do
  local _, _, hostile, distance = os.pullEvent("incoming_alert")
  print(("Air raid: %d hostile, nearest %d blocks"):format(hostile, distance))
  for _, launcher in ipairs(launchers) do
    local ok, reason, message = launcher.fire()
    if not ok then print(message or reason) end
  end
  while sam.isTracking() do sleep(1) end
end
```

## Blocks from other mods

How a block holds up in a strike comes from data that a datapack or a mod can extend: a data map for what a bomb and a
charge in contact go through, block tags for what a blast wave breaks, and block tags for what the heat of a burst
sets alight. A file in your datapack at the same path adds its entries to Incoming's own (a block or a `#tag`); with
`"replace": true` at its top it drops Incoming's entries instead. Strikes made after `/reload` use the new data.

### Material: `incoming:penetration`

`data/incoming/data_maps/block/penetration.json` gives a block its material:

| Field | Values | What it sets |
|---|---|---|
| `kind` | `soil`, `rock`, `concrete`, `fluid`, `stop` | the medium the B-2's bomb goes through (Young's equations): soil right under `concrete` is twice as hard for the first 5 blocks; `fluid` slows the bomb by density alone; `stop` stops the bomb and every charge |
| `s` | number | Young's penetrability, larger is softer (for `fluid`, the density in kg/m³). Vanilla: dirt 12, sand 7.5, gravel 5, stone and concrete 0.84, deepslate 0.67 |
| `breach` | `earth`, `poor`, `rock`, `dense`, `reinforced` | how hard a charge in contact finds it to blow through (U.S. Army FM 5-250): earth; timber and poor masonry; stone, brick and plain concrete; dense concrete; reinforced concrete. Without it: `soil` is earth, `rock` and `concrete` are rock |
| `thickness` | metres, 1 if left out | how much material one full block stands for: a vanilla concrete block is a 0.2 m wall, a plank 0.04 m of boards. Slabs, stairs and other partial blocks count only the part of the path inside their shape |

An entry changes how far the B-2's bomb goes into the block, how far the charge of any warhead that goes off against it
blows through into a room or cave behind, and whether the bomb's underground blast crushes it: blocks of kind `rock` or
`concrete` turn to gravel around the charge (natural stone to cobblestone or cobbled deepslate). A block without an entry
counts as brick for the bomb (`s` 1, a full metre), is not crushed (chests and machines stay), and resists a charge by the tool that mines
it: axe as timber, shovel or hoe as earth, pickaxe up to cobblestone's hardness as stone, anything harder as reinforced
concrete. No charge blows through a block with blast resistance 1200 or more.

A mod's reinforced concrete as a bunker wall, one full metre of reinforced concrete per block:

```json
{
  "values": {
    "examplemod:reinforced_concrete": {
      "kind": "concrete",
      "s": 0.84,
      "breach": "reinforced",
      "thickness": 1.0
    }
  }
}
```

With it the B-2's bomb goes about 4 blocks into this concrete at game flight speeds (about 9 with real ones, preset
`REAL`), where it goes through about 20 blocks of vanilla concrete. A cruise missile's warhead blows through a wall of up to 2
blocks of it into the room behind, a Shahed-136's through 1 block, a Grad rocket's or a Lancet's through none.

### Blast classes: `incoming:nuke_*` tags

A blast wave breaks a block once its overpressure passes the block's threshold; the threshold of each block varies by
±15 % with its position, so a row of the same blocks does not break along a straight line. The same classes hold for
the wave of every warhead, nuclear or not (a short conventional wave also needs enough impulse). In a nuclear wave the
thresholds are those of a wall or floor one block thick: two blocks thick hold twice the pressure, three blocks 3.5
times, and a wall facing the burst takes the stronger reflected pressure.

| Tag | Breaks at | Vanilla blocks in it |
|---|---|---|
| `incoming:nuke_fragile` | 0.8 psi | glass and panes, torches, lanterns, candles, flower pots, cobwebs, snow layers |
| `incoming:nuke_plants` | 8 psi, when the wave strips the turf; the light of a nuclear burst burns them farther out | grass, ferns, flowers, saplings, crops, vines |
| `incoming:nuke_light` | 4 psi | doors, fences, trapdoors, planks, wool, beds, signs, chests, barrels, bookshelves |
| `incoming:nuke_masonry` | 12 psi | bricks, stone bricks, cobblestone, smooth stone, concrete |
| `incoming:nuke_ground` | 15 psi, but thick ground withstands the wave and shields what is behind it; a crater digs it out | dirt, sand, gravel, stone, deepslate, terracotta, ores, ice |

A block in several of them takes the first in this order: `nuke_ground`, `nuke_fragile`, `nuke_plants`, then leaves
(`minecraft:leaves`, 2 psi), tree trunks (`minecraft:logs`, which fall by the blowdown of a forest:
`incoming:nuke_conifers` as a conifer stand, which takes more, the rest as broadleaf trees), `nuke_light`,
`nuke_masonry`. A block in none of them breaks by its hardness: up to 0.5 at 1 psi, up to 3 at 5 psi, up to 6 at
12 psi, up to 9 at 25 psi, anything harder never. Blocks of hardness 50 or more and unbreakable blocks (obsidian,
bedrock) never break, whatever their tags. The flash of a nuclear burst marks the faces it lights: it chars
`incoming:nuke_chars` black (wood, wool, hay) and leaves soot on `incoming:nuke_soots` (masonry, concrete, terracotta).

A mod's sheet-metal wall as a light structure, in `data/incoming/tags/block/nuke_light.json`:

```json
{
  "values": [
    { "id": "examplemod:sheet_metal_wall", "required": false }
  ]
}
```

A wall of it one block thick then breaks at about 4 psi (3.4 to 4.6 by its position) instead of the threshold of its
hardness, 12 psi for a block as hard as an iron block. Keep `"required": false` on blocks of other mods: when such a
mod is not installed, an entry without it empties the whole tag, Incoming's own doors, planks and chests included. In
the data map a block that does not exist only skips its own entry.

### Ignition: `incoming:pulse_*` tags

The heat of a burst sets fire only to thin, dry fuel; each class needs its own amount of heat.

| Tag | Fuel | Vanilla blocks in it | Set alight by |
|---|---|---|---|
| `incoming:pulse_paper` | paper | bookshelves, lecterns | a nuclear flash, a fuel fireball |
| `incoming:pulse_fabric` | fabric | wool, carpets, beds, banners | a nuclear flash, a fuel fireball |
| `incoming:pulse_dry` | dry plants | hay bales, dried kelp blocks, dead bushes, scaffolding | a nuclear flash, a fuel fireball |
| `incoming:pulse_grass` | thin plants: everything in `pulse_dry` and `nuke_plants`, azaleas, bamboo | | a fuel fireball |
| `incoming:pulse_leaves` | leaves | leaves | a fuel fireball |

The flash of a nuclear burst ignites only paper, fabric and dry plants, and the turf of dry biomes; live grass, leaves
and wood char and smoke but do not catch. The fireball of a Shahed-136's or a cruise missile's burning fuel ignites
every class in the table, but only blocks that vanilla fire can burn; a block in none of them counts as grass when
vanilla fire spreads over it quickly, and otherwise does not catch from a flash that short (wood only chars).

A mod's straw bale as dry fuel, in `data/incoming/tags/block/pulse_dry.json`:

```json
{
  "values": [
    { "id": "examplemod:straw_bale", "required": false }
  ]
}
```

A nuclear flash then sets it alight like a hay bale, and a fuel fireball like dry grass if the mod makes the bale
flammable, as hay is. To break it like a hay bale too, put it in `incoming:nuke_light` as well.

## Items from other mods

### Radiation shielding: `incoming:radiation_shielding`

`data/incoming/data_maps/item/radiation_shielding.json` gives an item the share of radiation it stops while it is
worn, a number from 0 to 1 (an item or a `#tag`). The shares of the pieces a body wears add up, capped at 1: four
pieces at 0.25 stop everything, one of them a quarter. A piece counts in the four armor slots and in the armor slot of a
horse or a wolf, not in a hand. It works against all the radiation of a nuclear strike, for players and mobs alike:
the radiation of the burst, fallout, and the black-rain dust that settles on a player. It stops only what comes while
the piece is worn: a dose already taken, a sickness already begun and dust already on the body stay as they are.

With Mekanism installed, its items that shield from radiation (the hazmat suit, the MekaSuit with its radiation shielding unit) count without a
datapack, at Mekanism's own shares; an entry here replaces Mekanism's share for that item. Incoming lists no items of
its own. A value outside 0 to 1 makes the whole file fail to load, with an error in the server log, so none of its
entries apply; an item that does not exist only skips its own entry. After `/reload` the new shares apply at once.

A mod's protective suit, a quarter per piece:

```json
{
  "values": {
    "examplemod:hazmat_helmet": 0.25,
    "examplemod:hazmat_chestplate": 0.25,
    "examplemod:hazmat_leggings": 0.25,
    "examplemod:hazmat_boots": 0.25
  }
}
```

With all four pieces on, a player in the fallout trail or in black rain takes no new dose; with only the helmet,
three quarters of it.
