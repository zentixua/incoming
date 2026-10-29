Incoming (https://github.com/zentixua/incoming): strikes by Shahed and Lancet drones, cruise missiles, Grad rocket salvos, a B-2, and a nuclear strike.
- A strike is the command «incoming salvo <weapon> <count> <spread> at x z [from x z] [via x z …]»: at x z is a place
  on the ground, and the server finds its height itself, also where the chunks are not loaded. at x y z is exactly
  that point: above the ground a projectile bursts in the air (a bunker bomb is guided to a point underground and goes
  into the ground toward it as far as its speed and the rock allow). With spread, the salvo lands on the ground around the point, unless at launch the point is in
  the air over a loaded place.
  from is the launch site (a launcher stands there until incoming clear), via is up to 5 waypoints in order (drone,
  missile, loiter). A single projectile is count 1, spread 0.
- Nuclear strike: «incoming nuke [at x y z] [kt [air|ground]]» launches an ICBM; «incoming nuke now [at x y z]
  [kt [air|ground]]» detonates immediately (operator only). Without kt, use nuclear.default_yield (15 kt by default);
  without a burst mode, use ground (surface contact, crater and fallout). Explicit «15 air» selects the existing
  airburst at its yield-dependent height. Burst mode is not stored in incoming-server.toml.
- Fixed launcher (block incoming:fixed_launcher): «incoming launcher x y z [mission <weapon> <count> <spread> x z
  [via …] | load N | fire | clear | owner <player>|none]»; the mission target is x z or x y z, as in salvo. A redstone
  signal at the block does the same as fire.
- Strikes load their own areas: there is no need to load the place in advance.
- Routes at the default GAME scale: drone (shahed) flies 45 blocks above the launch site and the target, loiter (lancet) circles 45 blocks above
  the target, missile flies 12 blocks above the terrain; all three follow via. rocket (grad) is ballistic and bunker is
  a bomb from a B-2: they take no via, and a command with via fails.
- incoming clear removes all launchers and every projectile in flight at once: an emergency stop. To stop a campaign,
  remove its rule first.
- A campaign (a salvo every N seconds) is a tick rule with a name, interval and run limit; pauses count toward the
  limit too. In the script, pass the whole command as one string to gm.command and pass numbers as numbers. Stop the
  campaign with rule remove before using incoming clear, or the next rule run will launch again.
- Measure server tick time and client frame time for the actual scene before increasing the rate or salvo size.
  Reduce or pause new orders when either becomes too slow. Incoming budgets its own heavy work with
  performance.work_ms_per_tick; lowering it makes destruction finish later. There is no scene-independent safe
  projectile count. Do not delete nearby item entities to control load: they may belong to players or other mods.
