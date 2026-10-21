# Weapons

English · [Русский](ru/weapons.md)

Six weapons, in the order the designator lists them. Each launch uses [munitions](munitions.md) from your inventory.

| | Shahed | Lancet | Missile | Grad | Bomb (B-2) | Nuclear (ICBM) |
|---|---|---|---|---|---|---|
| Time to impact | ≈50 s | approach plus ≈25 s circling | ≈30 s | 7–10 s | ≈40 s | 90 s |
| Warhead, TNT | 50 kg | 3 kg | ≈150 kg | 6.4 kg per rocket | power 20 underground (TNT = 4) | 1 or 15 kt |
| Follows a moving target | yes, through the camera | yes, through the camera | no | no | no | no |
| Route on the map | yes | yes | yes | no | no | no |
| Shot down by a SAM | yes | yes | yes | no | yes | no |

Times are for a nearby target. The world owner can change flight times and the bomb's underground blast power.

## The real weapons

Each weapon is a real one. Its size, mass, warhead and engines come from open data; where a number is not published
(some engines, the share of explosive in a warhead), it is worked out from the published ones by physics. The warhead
sets everything the blast does: what it breaks and how far, the crater, wounds, and how the explosion looks and sounds.

| | Shahed-136 | Lancet-3 | Tomahawk | Grad 9M22U | GBU-57 (from B-2) | Minuteman III |
|---|---|---|---|---|---|---|
| Length | 3.5 m | 1.65 m | 5.56 m | 2.87 m | 6.2 m | 18.2 m |
| Mass | 200 kg | 12 kg | 1.3 t (1.6 t with booster) | 66.6 kg | 13.6 t | 36 t |
| Warhead, TNT | 50 kg | 3 kg | ≈150 kg | 6.4 kg | 2.4 t of explosive, underground | nuclear |
| Breaks glass up to | ≈70 m | ≈29 m | ≈105 m | ≈37 m | — | — |
| Breaks brick and stone walls up to | ≈3.4 m | ≈0.5 m | ≈7 m | ≈0.9 m | — | — |
| Breaks planks, doors, fences up to | ≈6 m | ≈0.9 m | ≈12 m | ≈1.5 m | — | — |
| Crater in soil, across | ≈2.4 m | ≈0.9 m | ≈3.5 m | ≈1.2 m | — | — |
| Fuel left at impact | ≈30 kg | none | ≈275 kg | none | none | — |
| Real speed | 185 km/h | 110 km/h, ≈300 in a dive | ≈900 km/h | ≈690 m/s at burnout | ≈510 m/s at impact (calculated) | — |
| Real flight height | 1.5–2.8 km | 600–800 m when circling | 30–50 m over the ground | apogee ≈7 km | dropped from ≈12 km | — |
| Speed in the game | ≈150 km/h | ≈115 km/h | ≈290 km/h | by its arc | B-2 ≈860 km/h, 170 blocks up | — |

## What a blast does

A blast is the warhead's charge in TNT, not a game explosion power. Everything follows from it:

- **The shock wave** has the pressure and impulse of a real charge of that size on the ground (Kinney and Graham). A
  block breaks when both are high enough for its material: glass and torches first, then leaves, planks and doors,
  logs, brick and stone. The radii match the British bomb-damage data (Jarrett): a Shahed brings down brick walls within
  about 3 m and wooden ones within about 6 m. A small charge breaks much less than its pressure alone suggests, because
  its wave is too short for a wall to give way. A wall that survives shields what is behind it; the wave does not go
  round houses (an approximation).
- **Inside a room** the hot gases of the explosion keep pushing on every wall until they escape through windows and
  doors. A Shahed that flies into a closed room blows the whole room out, the way a hit floor looks on real footage.
- **The crater** follows the law for a charge on the ground: about 0.65·∛W metres across and a quarter of that deep,
  a little smaller in rock, a little larger in sand and wet soil. A crater is only dug where the blast is at the ground.
  Craters are much smaller than in earlier versions: that is the real size.
- **Fire** starts only from fuel and burning debris. The Shahed and the missile still carry fuel; it burns as a
  fireball (18 m across for the Shahed, ≈38 m for the missile) and sets fire to flammable blocks that the fireball
  touches. The Grad and the Lancet carry no fuel. Burning debris starts a fire only where it lands on something that
  burns. There are no scripted secondary explosions.
- **People and mobs** are hurt by the same wave as in a nuclear strike: pressure on the lungs, being thrown into a
  wall, glass from a window. Close to a Shahed the wave kills; at 30 m in the open it does nothing, but fragments still
  do (below). Someone behind a wall that holds is safe from the wave; inside the room where the charge went off, the
  gases hit everyone.
- **Fragments.** The steel casing of the warhead breaks into thousands of pieces. Their masses follow Mott's law for
  the casing's thickness and diameter, their starting speed Gurney's law for the charge and casing mass, and the air
  slows each one by its size. They fly out in a belt across the warhead's axis. The detonation running from the nose to
  the tail tilts the belt back (Taylor's angle), the warhead's own speed tilts it forward: the Shahed's and Lancet's
  belts lean about 9° back, the Grad's about 6° forward. A warhead diving at 60° sends half of the belt into the ground
  on one side and up into the air on the other. A fragment stops in the first body or solid block on its way. It hurts by its impact energy: from about 79 J
  (the US safety board's threshold for a hazardous fragment) it can wound badly, and a heavy, fast one can kill.
  Fragments go through glass and break it, chip stone bricks, deepslate bricks and tiles, polished blackstone bricks
  and nether bricks (the block becomes its cracked version; other blocks have no cracked look and stay as they are),
  and stop in earth, wood, leaves and water. A shield held towards the blast stops them. Nobody behind a wall is hit. The fragments do not glow: the steel comes out at roughly 500–700 K,
  below the 798 K at which metal starts to glow, so you see where fragments hit (sparks on stone and metal, puffs of
  dust from earth and wood, glass) rather than the fragments themselves.

  | | Shahed-136 | Lancet-3 | Tomahawk | Grad 9M22U |
  |---|---|---|---|---|
  | Fragments | ≈7200 | ≈6500 | ≈4900 | ≈5100 |
  | Starting speed | ≈3.0 km/s | ≈3.0 km/s | ≈1.8 km/s | ≈1.6 km/s |
  | Average fragment | ≈1.4 g | ≈0.1 g | ≈39 g | ≈2.4 g |
  | Hazardous up to | ≈125 m | ≈55 m | ≈205 m | ≈115 m |

  "Hazardous up to" is where one hazardous fragment falls on every 56 m² in the middle of the belt, the distance the
  US safety board uses. Single heavy fragments fly much farther. The casing's shape and the share of explosive are
  estimates for most of these warheads; real ones also have nose and base plates and sometimes ready-made fragments.
- **Aircraft** of Create Aeronautics lose the blocks that the same law breaks at their distance.

Known limits: the injury thresholds are those for long nuclear pulses, so near a small charge they slightly overstate
harm. Fragments fly in straight lines (gravity bends a dangerous fragment's path by less than a metre), do not
ricochet and pass through Create Aeronautics aircraft. A chipped block turns cracked as a whole, while a real
fragment leaves a pit of a few centimetres (not physical).

Speeds and flight heights in the game are not the real ones (not physical): they are lower so that you can see the
approach within a few hundred blocks. A Grad battery stands 600 blocks from the target, not 5–20 km.

## Shahed

![Shahed-136 in flight](img/weapon-shahed.jpg)

A kamikaze drone: it takes off from a trailer next to you, flies with a loud engine buzz and dives at the end. Use it
on buildings and on moving targets. It can be heard long before it arrives and a SAM can shoot it down, so
against air defense send a salvo or route it around. It dives only once it can see the target: if a hill or a
building hides it, the drone flies on at its height and dives more steeply later. It does not aim ahead of a moving
target. A tall building right next to the target or a roof over it can still be in the way: it then hits the
building or the roof.

## Lancet

![Lancet catapult](img/weapon-lancet-launcher.jpg)

A small drone launched from a catapult. Its electric motor is quiet: it is heard only a few hundred blocks away. It
circles over the target while you pick what to hit in its
[camera](camera.md#lancet), and does not miss. The blast is small: enough for a vehicle, a player or a single house.

## Cruise missile

![Cruise missile in flight](img/weapon-missile.jpg)

Flies low over the terrain and dives just before the target. A heavy strike on a target that does not move. Can carry
a nuclear warhead. If a hill, the rim of a pit or a building hides the target, the missile climbs until it can see
it and then dives. It cannot get past a building that hides the target at a steep angle right next to it (for
example, 40 blocks taller than the target and 20 blocks from it), and it cannot reach a target under a roof: use
the bomb there.

## Grad

![Grad launcher](img/weapon-grad.jpg)

A trailer with 40 tubes fires a ripple of unguided rockets. It covers an area, a SAM cannot shoot it down, and the
siren at the target sounds only 8 seconds before impact.

## Bomb (B-2)

![B-2 with its bomb bay open](img/weapon-b2.jpg)

A B-2 arrives from far away and drops a bunker buster. The bomb goes through about 30 blocks of stone and explodes
underground. Use it on bunkers and underground bases. SAM radar detects the B-2 very late. Can carry a nuclear warhead.

## Nuclear (ICBM)

![ICBM during boost](img/weapon-icbm.jpg)

An intercontinental ballistic missile lifts off behind you, and 90 seconds later its warhead detonates at the target.
It climbs like a real ICBM: slowly off the pad, faster as its fuel burns, and stays in the sky for about nine seconds.
Everything about it is in [Nuclear strike](nuke.md).

## What a blast looks like

The fireball, smoke and dust of every strike are drawn as a volume: you can fly into a cloud, the sun lights its thin
edges, its hot core glows at night, and it casts a shadow on the ground. Its size, rise and colour come from the charge
and the fuel left in the tanks:

- **Shahed**: a short flash, then an orange ball of burning petrol about 18 blocks across that burns out in under
  2 seconds, black smoke above it and dust the colour of the ground below. The cloud rises a few hundred blocks and drifts
  with the wind.
- **Cruise missile**: a bigger flash and a fuel ball about 38 blocks across that burns for about 3 seconds; its own
  charge gives little soot, so the smoke is mostly from the burning fuel, with a lot of dust.
- **Grad**: a flash and a small grey-black puff with dust, no fuel ball. A salvo leaves a field of puffs that merge and
  drift downwind.
- **Lancet**: a small flash and a cloud a few tens of blocks high.

In humid air a white condensation cloud flashes around the blast for a moment. By day the cloud rises higher than at
night, when cold air near the ground holds it down. A cloud stays visible for
minutes, until it has spread too thin to see. Close up only solid things fly as particles: sparks, clods of earth,
the dust and sparks of fragment hits, and, where fuel was left in the tank, burning pieces with smoke trails. Past about 600 blocks the same cloud is drawn as flat puffs. The bomb's underground
blast looks as before.

Whatever the blast sets on fire keeps smoking. Over every chunk with burning blocks a column of black smoke rises, leans
downwind and stretches out with it; a bigger fire sends it higher. By day it climbs higher than on a calm night, when
it stays low and drifts flat. At night the flames light the smoke from below. The column grows from the fire at the
speed of the wind, and when the fire goes out the last of the smoke drifts away. Where the fire comes from is up to the
server: the smoke follows any fire your game shows, not only fires from strikes.

## Flames and smoke trails

Every engine flame and smoke trail is worked out from the motor's data, the same way for every weapon, and looks the
same up close and far away.

- **Rocket motors** (the Shahed's and the cruise missile's boosters, the Grad rocket, the ICBM stage) have a short hot
  core at the nozzle and a brighter orange flame behind it, where the exhaust burns in the air. Its length and colour
  come from the motor. The Grad and the ICBM show shock diamonds near the ground. At night the cooling tail glows
  further back, and the flame lights the smoke around it.
- **Engines without a flame**: the Shahed's piston engine, the cruise missile's jet engine and the Lancet's electric
  motor show no flame and leave no visible smoke.
- **Smoke trails**: boosters with aluminium in their fuel and the ICBM leave thick white smoke, and the Grad leaves a
  greyer trail. A slower rocket leaves a denser trail. The hot trail rises a little. By day it spreads and fades sooner;
  on a calm night it stays narrow and hangs much longer.
- **Launch cloud**: while a rocket's exhaust still hits the ground, its smoke stays at the launcher, together with the
  dust it blows up, and rises and drifts as one warm cloud. A Shahed leaves a short white puff at the rail, a cruise
  missile's booster a bigger one. Each Grad rocket adds its own cloud, so a full salvo buries the launcher in smoke and
  dust that hang and drift downwind. Stone and concrete raise no dust. The ICBM leaves a large cloud at the pad.
- **Near and far**: close to you a trail is part of the same smoke volume as blast clouds, so you can fly through it;
  further away, and while it is still too thin for the volume, it is drawn as a ribbon. Both carry the same smoke.
- **Wind**: smoke drifts with the world's wind, the same wind that carries nuclear fallout. Each game day has its own
  wind, and it turns slowly from one day to the next. Near the ground it is weaker, most of all at night.
- **Contrails**: the B-2 leaves a contrail only where the air is cold enough for one to form. At the height it flies
  now the air is too warm, so it leaves none. The falling bomb leaves no trail.

## No room for the launcher

The launcher sets up next to you. If there is no room around you, the designator says "No room for a launcher…" and
the projectile comes in from far away instead. To watch the launch, move to open ground.
