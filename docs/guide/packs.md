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
positions. `Request` arrives while the order is being made; every other event arrives at the end of the same tick,
in the order things happened.

To charge your own price or add a cooldown, check it in `Request`, take it in `Accepted`, and give it back in
`Ended` if you wish. Incoming does not undo another mod's charges. A strike started from inside a `Request` listener
is made on the next tick, with all its checks and its own request.

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
