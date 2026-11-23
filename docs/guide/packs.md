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
| `StrikeRoundEvent$Ended` | A round is over: its warhead went off, it was shot down, it failed (crashed before arming, a failed launch), or it was recalled or cancelled. It also says whether the round's munitions came back. |
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
