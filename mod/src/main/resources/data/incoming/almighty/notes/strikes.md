Incoming (https://github.com/zentixua/incoming): strikes by Shahed and Lancet drones, cruise missiles, Grad rocket salvos, a B-2, and a nuclear strike.
- Your commands run at permission level 4 with no player, at world spawn in the Overworld (gm.command always; the
  command tool takes pos and dimension). A form without a target uses that position: «incoming salvo <weapon> <count>
  <spread>» and «… me» strike world spawn, where players often are, and grid node add and grid blackout without x y z
  act there. Give every target as coordinates and every player by name. Forms that need a player fail: look,
  «incoming <weapon>» without a target, «incoming nuke» without at, «incoming radiation» without a player. For
  another dimension run «execute in <dimension> run incoming …».
- A strike is the command «incoming salvo <weapon> <count> <spread> at x z [from x z] [via x z …]»: at x z is a place
  on the ground, and the server finds its height itself, also where the chunks are not loaded. at x y z is exactly
  that point: above the ground a projectile bursts in the air (a bunker bomb is guided to a point underground and goes
  into the ground toward it as far as its speed and the rock allow). With spread, the salvo lands on the ground around the point, unless at launch the point is in
  the air over a loaded place.
  from is the launch site (rack weapons set up a launcher there until incoming clear; the ICBM boosts there; B-2 approaches from that side), via is up to 5 waypoints in order (drone,
  missile, loiter, bunker). A single projectile is count 1, spread 0.
- Nuclear strike: «incoming nuke [at x y z] [kt [air|ground]] [from x z]» launches an ICBM; from selects its ground pad (operator only; no via); «incoming nuke now [at x y z]
  [kt [air|ground]]» detonates immediately (operator only). Without kt, use nuclear.default_yield (15 kt by default);
  without a burst mode, use ground (surface contact, crater and fallout). Explicit «15 air» selects the existing
  airburst at its yield-dependent height. Burst mode is not stored in incoming-server.toml.
- Fixed launcher (block incoming:fixed_launcher): «incoming launcher x y z [mission <weapon> <count> <spread> x z
  [via …] | load N | fire | clear | owner <player>|none]»; the mission target is x z or x y z, as in salvo. A redstone
  signal at the block does the same as fire.
- Strikes load their own areas: there is no need to load the place in advance.
- Routes at the default GAME scale: drone (shahed) flies 45 blocks above the launch site and the target, loiter (lancet) circles 45 blocks above
  the target, missile flies 12 blocks above the terrain; all three follow via. bunker is a bomb from a B-2: it also follows via, then lines up a straight bomb run.
  rocket (grad) and nuke (ICBM) are ballistic: they take no via, and a command with via fails.
- incoming clear removes all launchers and every projectile in flight at once: an emergency stop. To stop a campaign,
  remove its rule first.
- Results: every strike command returns the rounds its order admitted (a salvo of 12 returns 12 at once, an ICBM 1,
  a refused order 0, also an order deferred to the next tick when given from a rule on StrikeOrderEvent.Request) and
  its output names the order id. salvo and nuke take «tag <name>» last: a group name that many orders may share and
  that survives a restart (one word of letters, digits and _ - . +, at most 32 characters). «incoming status tag <name>» returns the group's unfinished rounds
  (waiting to launch, in flight, or still exploding; a nuclear blast until its wave has passed) and lists each order
  with its arrival time or «unknown»; 0 means the group is over. «incoming status» covers all orders, `incoming status Steve` one
  player's. «incoming clear tag <name>» recalls one group, nuclear ones too, leaves other strikes and
  returns the rounds it recalled. Example: `incoming salvo grad 12 30 at 200 -150 tag raid1` returns the rounds
  admitted; `incoming status tag raid1` returns 0 once that wave is over; `incoming clear tag raid1` stops it early.
- World rules: «incoming rule» lists them, «incoming rule <key>» returns the value (true 1, false 0, numbers as they
  are), «incoming rule <key> <value>» sets and saves it (true/false or a whole number in range; returns 1, a bad key or
  value fails and changes nothing). Keys: world.block_damage, world.shatter_glass, world.fire, world.debris_stay,
  world.collapse, grid.enabled, nuclear.enabled, nuclear.ops_only, nuclear.carrier_nukes, launch.designator_for_everyone,
  launch.max_salvo, launch.max_spread, launch.max_active_per_player. A change reaches only orders given after it;
  salvos still launching and rounds in flight keep the rules they were ordered under. Exceptions: nuclear.enabled false
  also stops nuclear launches that have not left yet (munitions return), and nuclear.ops_only and
  launch.designator_for_everyone are asked again when a waiting nuclear launch's second key turns. With the five world.* rules and
  grid.enabled false, conventional strikes change no block and cut no power (an arena keeps its shape); nuclear strikes
  are outside that, so turn nuclear.enabled false for an arena and back on after it.
- Progress: advancements incoming:strikes/first_strike, camera_strike, first_interception, nuclear_survivor
  (alive after a nuclear wave of at least 5 psi), lights_back; statistics as scoreboard criteria
  minecraft.custom:incoming.rounds_launched (also .drone .loiter .rocket .missile .bunker .nuke), .interceptions,
  .nuclear_detonations_witnessed. Your strikes have no owner and credit no player; a player shooting one down is credited.
- A campaign (a salvo every N seconds) is a tick rule with a name, interval and run limit; pauses count toward the
  limit too. In the script, pass the whole command as one string to gm.command and pass numbers as numbers. Stop the
  campaign with rule remove before using incoming clear, or the next rule run will launch again.
- Measure server tick time and client frame time for the actual scene before increasing the rate or salvo size.
  Reduce or pause new orders when either becomes too slow. Incoming budgets its own heavy work with
  performance.work_ms_per_tick; lowering it makes destruction finish later. There is no scene-independent safe
  projectile count. Do not delete nearby item entities to control load: they may belong to players or other mods.
- world.early_warning (default true) controls the early-warning network: off suppresses automatic sirens, target warnings and nuclear launch notices. Owner telemetry, second-key consent, visible/audible flight and SAM radar remain.
- Power grid (operator commands, in the dimension the command runs in): electric lamps go dark district by district
  outward from the centre; torches, candles, campfires and furnaces keep burning.
  `incoming grid node add 100 64 -200 512` places a substation marker (no block) powering that radius (16–16384,
  without it grid.node_radius, at most grid.max_radius) and returns its number. `incoming grid node list` lists the
  dimension's nodes, working or down, and returns their count. `incoming grid node knockout 1` knocks node 1 out as a
  strike would and returns 1 (an unknown number fails); power returns by itself after grid.restore_hours hours of the
  world's day. `incoming grid node remove 1` returns 1. `incoming grid blackout 100 64 -200 300` cuts power around a
  point without a node and returns the outage number. `incoming grid restore at 100 64 -200 300` brings power back
  over about 10 seconds in outages centred within that radius and returns how many; `incoming grid restore`
  does so in every dimension. `incoming grid status` returns the number of outages in all dimensions and lists each with
  when power returns (a nuclear outage returns by damage: never near the burst).
- Radiation (operator): `incoming radiation Steve` reports the player's dose in Gy, dose rate and contamination, and
  returns the dose in hundredths of a gray (150 = 1.5 Gy; below 2 Gy almost nobody dies, 4.5 Gy kills half, 10 Gy
  everyone). `incoming radiation clear @a` clears dose and contamination and returns the number of players.
- Events for rules: rule add subscribes to Incoming's events by full class name; without a script the feed gets these
  fields. ua.zentix.incoming.api.StrikeRoundEvent.Released (a round leaves): roundId, orderId, tag, weapon, ownerId,
  dimension, origin, target. ua.zentix.incoming.api.StrikeRoundEvent.Ended (exactly once per admitted round): roundId,
  orderId, tag, weapon, ownerId, dimension, outcome (impact, intercepted, failed, recalled), position, chargeKg,
  interceptorPos, interceptorOwnerId, cameraGuided, released, refunded. ua.zentix.incoming.api.NuclearDetonationEvent:
  dimension, detonationId, position, yieldKt, airBurst, ownerId, orderId, roundId, tag.
  ua.zentix.incoming.api.GridOutageEvent and ua.zentix.incoming.api.GridRestoreEvent: dimension, outageId, centreX,
  centreZ, radius, nodePos, nuclear. ua.zentix.incoming.api.StrikeOrderEvent.Request (cancellable), .Rejected and
  .Accepted come while an order is made. Your own orders carry no owner (ownerId null).
- Bulk events need a filter or a script: a rule turns itself off above 200 feed entries in 20 ticks. A salvo of up to
  100 rounds posts a Released and an Ended per round within seconds, and several salvos together pass 200; a blast posts
  game block and explosion events by the thousand; a nuclear burst posts one GridOutageEvent for its reach and one more
  per substation it knocks out. On such events use a script that keeps only what you need (one tag, one outcome),
  counts in state and emits a summary, or set every, or read incoming status instead. A rule without a script suits
  one-per-burst events such as NuclearDetonationEvent.
- Rule recipe: raid waves. One tick rule starts wave 1, checks incoming status tag every 5 s, and starts the next wave
  once the last one returns 0 (every round ended and its blast worked out). Replace the targets with yours; remove the
  rule by name when the feed says the raid is over. Its progress and tags carry the rule's id, so the recipe can run
  again later, or as two raids under two names, without one disturbing the other.
```json
{"event": "tick", "name": "raid", "every": 100}
```
```groovy
def waves = ['incoming salvo grad 12 30 at 200 -150',
             'incoming salvo shahed 6 20 at 200 -150 from 900 -150',
             'incoming salvo missile 4 10 at 200 -150']
def raid = state['raid' + rule] ?: (state['raid' + rule] = [wave: 0, done: false])
if (raid.done) return
int wave = raid.wave
if (wave > 0) {
    def left = gm.command('incoming status tag raid' + rule + '-' + wave)[0]
    if (!left.ok || left.result > 0) return
    gm.emit([raid: 'wave over', wave: wave])
}
if (wave == waves.size()) {
    raid.done = true
    gm.emit([raid: 'over', waves: wave])
    return
}
String tag = 'raid' + rule + '-' + (wave + 1)
def order = gm.command(waves[wave] + ' tag ' + tag)[0]
if (!order.ok || order.result == 0) {
    raid.done = true
    gm.emit([raid: 'refused', wave: wave + 1, errors: order.errors, output: order.output])
    return
}
raid.wave = wave + 1
gm.emit([raid: 'wave started', wave: wave + 1, tag: tag, rounds: order.result])
```
- Rule recipe: a nuclear detonation. One feed entry per burst with the players within 3000 blocks of it (your choice
  of distance), for your follow-up (warn them, check incoming radiation <player> later). It persists with the world.
```json
{"event": "ua.zentix.incoming.api.NuclearDetonationEvent", "name": "nuke-watch", "persist": true}
```
```groovy
def at = event.position
def near = server.playerList.players.findAll { it.level().dimension() == event.dimension && it.position().distanceTo(at) < 3000 }
gm.emit([nuke: event.yieldKt, air: event.airBurst, at: at, dimension: event.dimension, tag: event.tag,
         near: near.collectEntries { [(it.gameProfile.name): Math.round(it.position().distanceTo(at))] }])
```
