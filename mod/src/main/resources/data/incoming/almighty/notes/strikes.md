Incoming (https://github.com/zentixua/incoming): strikes by Shahed and Lancet drones, cruise missiles, Grad rocket salvos, a B-2, and a nuclear strike.
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
  a refused order 0) and its output names the order id. salvo and nuke take «tag <name>» last: a group name that many
  orders may share and that survives a restart. «incoming status tag <name>» returns the group's unfinished rounds
  (waiting to launch, in flight, or still exploding; a nuclear blast until its wave has passed) and lists each order
  with its arrival time or «unknown»; 0 means the group is over. «incoming status» covers all orders.
  «incoming clear tag <name>» recalls one group, nuclear ones too, and leaves other strikes.
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
