# Weapons

English · [Русский](ru/weapons.md)

Six weapons, in the order the designator lists them. Each launch uses [munitions](munitions.md) from your inventory.

| | Shahed | Lancet | Missile | Grad | Bomb (B-2) | Nuclear (ICBM) |
|---|---|---|---|---|---|---|
| Time to impact | ≈50 s | approach plus ≈25 s circling | ≈30 s | 7–10 s | ≈40 s | 90 s |
| Warhead, TNT | 50 kg | 3 kg | ≈150 kg | 6.4 kg per rocket | 2.4 t, underground | 1 to 15 kt |
| Follows a moving target | yes, through the camera | yes, through the camera | no | no | no | no |
| Route on the map | yes | yes | yes | no | no | no |
| Shot down by a SAM | yes | yes | yes | no | yes | no |

Times are for a nearby target. The world owner can change flight times.

Fuel burning in a crater produces a continuous roar and crackle from where it burns. It fades as the fuel runs out; the
recording does not fade and restart every few seconds. Its level and arrival time depend on the heat output and the path
to you.

A fireball rises upright. While it burns, orange flame with bright cores churns among dark soot; as it cools, it
darkens into smoke without a jump.

## The real weapons

Each weapon is a real one. Its size, mass, warhead and engines come from open data; where a number is not published
(some engines, the share of explosive in a warhead), it is worked out from the published ones by physics. The warhead
sets everything the blast does: what it breaks and how far, the crater, wounds, and the size, timing and course of
what you see and hear.

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
| Goes through stone | — | — | — | — | ≈4 m in the game, ≈8–9 m in real life | — |
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
  a little smaller in rock, a little larger in waterlogged ground under water; dry sand and the gravel of a rail bed dig
  like soil. A crater is only dug where the blast is at the ground. A missile crater is a pit two to three blocks across
  and about a block deep: craters are much smaller than in earlier versions, and that is the real size.
- **Paving on a solid backing** cannot bend like a free wall. It is excavated with its backing, using the crater
  law for its material. In cohesive stone and concrete, a fraction of a block is saved as surface damage and shown
  with pale fresh aggregate, small material chips and an uneven scorched edge, plus nearby cracks.
  Deeper damage exposes a wider fracture and leaves taller chips.
  Up to 256 composite surface displays are shown per dimension near players; only the damage data is saved.
  The displays return when players revisit. With debris persistence enabled, ground clods remain on loose ground, and large aircraft parts and burning debris can also remain.
  Small surface chips disperse; ground/charred fallout does not plant metre-sized blocks on intact cohesive paving.
  This approximates a fractured surface; the remaining block keeps its collision shape. A shallow crater cannot open a metre-thick slab.
- **A slab over a void** (a bridge deck, a roof over a room, the ceiling of a cave) is breached when the charge
  touches it and the slab is thinner than the breaching radius of the US Army demolition manual (FM 5-250): everything
  between the charge and the void within that radius is blown out, and the blast carries on into the void. This works
  the same for every block, natural or built; what matters is the material and how thick it is down to the void. A
  missile breaches about 2.7 m of stone or masonry and 4.2 m of earth: a 1 m stone bridge deck gets a hole about five
  blocks across, 3 m of stone holds. A Shahed breaches about 1.75 m of stone, a Grad rocket less than a metre. The
  bomb, which explodes deep in the ground, breaches into a bunker or cave up to about 8 m of rock away. The manual's
  rule is meant to be sure of a breach, so a real charge may break somewhat more. The span around the hole stays up:
  there is no collapse of a weakened span.
- **Fire** starts only from fuel and burning debris. The Shahed and the missile still carry fuel; three quarters of it
  burns as a fireball (≈16 m across for the Shahed, ≈34 m for the missile) for one to three seconds. That flash ignites
  thin, dry fuel it touches: leaves, dry grass, hay, books, and with the missile now and then cloth and wool. Planks, logs
  and other thick wood only char in a flash that short. The flash leaves its fire only on what burns: sand, stone,
  earth, metal and rails never burn, and no flames from it are left standing on them. So a Shahed in a town leaves a
  few fires on dry plants near the impact, not a burning circle.
- **Fuel in the crater**: the last quarter of the fuel stays in the crater and burns there as ordinary Minecraft fire on
  real blocks: about 5 blocks for the Shahed for about 2.5 minutes, about 9 blocks for the missile for about a quarter of
  an hour. The fuel falls to the first solid surface under the impact (the bottom of the crater, the floor under a
  broken roof), runs downhill and over edges, but not uphill or through walls; on water it does not burn. On bare earth
  or stone the flames stand on top of the block, as on netherrack. The fuel keeps them alight for its whole life, rain
  included; a block placed on it or water poured over it puts that part out. If the block under the fuel burns or
  breaks away, the fuel drips down to the next surface, so fuel fire never hangs in the air. Planks, logs and anything
  else that burns catch fire from it by the game's own rules. Near the end the flames go out from the edge inward, and
  when the fuel is gone they die, unless something flammable beside them is still burning.
- The Grad and the Lancet carry no fuel. Burning debris starts a fire only where it lands on something that burns. Fire
  then spreads by the game's own rules. With "Blasts start fires" off, neither the flash nor the fuel leaves fire.
  There are no scripted secondary explosions.
- **People and mobs** are hurt by the same wave as in a nuclear strike: pressure on the lungs, being thrown into a
  wall, glass from a window. Close to a Shahed the wave kills; at 30 m in the open it does nothing, but fragments still
  do (below). Someone behind a wall that holds is safe from the wave; inside the room where the charge went off, the
  gases hit everyone. The front resolves blocks and bodies from the charge outwards: a wall or slab broken before
  the front reaches someone behind it no longer shields that person. An intact wall still does.
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

## How they fly

Flight is the mod's own game model, not a copy of the real weapons: the same rules for every weapon, with each one's
own game numbers (not physical, chosen for blocks and for how a flight reads on screen). Everything at the target
(charge, blast, damage) is real.

By default speeds and flight heights are game values, lower than the real ones (not physical): a block is a metre,
but you see only a few hundred blocks, and at real speed a fast weapon crosses that in a second or two. The world
setting [`scale.flight_speed`](settings.md#world-settings) (or the REAL preset) brings them up to the real ones: 0 is
the game values, 1 is true to life, and in between every weapon goes the same share of the way, so their order stays.

| | Game (0) | Real (1) |
|---|---|---|
| Shahed | 42 m/s, dive 60 m/s, 45 blocks up | 51 m/s, dive 105 m/s, 2 km up |
| Lancet | 32 m/s, dive 80 m/s, circles 45 blocks up | 31 m/s, dive 83 m/s, circles 700 m up |
| Cruise missile | 80 m/s, dive 100 m/s, 12 blocks over the terrain | 250 m/s, 40 m over the terrain |
| Grad, leaving the tubes | 80 m/s from a battery 600 blocks away | 683 m/s from about 19 km |
| B-2 | 240 m/s, 170 blocks over the drop point | 250 m/s, 12 km up |
| SAM missile | 280 m/s | 1000 m/s |

Turns and climb rates stay game values, so a faster weapon turns wider. Flight times stay, so a faster weapon starts
further away. A Grad battery near you still fires from where it stands; only a battery placed without you stands
further away. At 1 the B-2 bomb falls for about 50 seconds after the drop, so it arrives that much later.

- **Turns** build up and ease off smoothly; nothing turns on the spot. A Shahed or a missile banks into a turn, the
  harder the turn the steeper the bank.
- **Climbs and dives** trade height for speed: a drone climbing at 15° loses about 15 % of its speed, a dive speeds it up
  faster than the engine alone, and afterwards it settles back to its cruising speed.
- **A salvo flies as a wave**, not a string. Shaheds, missiles and Lancets leave the launcher one after another, and
  each takes its own lane beside the path: up to 32 blocks to either side for the Shahed and the Lancet, 48 for the
  missile (not physical: about the gap between rounds, so the salvo reads as a wave). A round moves into its lane over
  the first 100 blocks of the route (missile 300, Lancet 80) and comes back to its own aim point over the last 300
  (missile 500, Lancet 150), so accuracy does not change. A route on the map is shared by the whole salvo, each round
  in its lane. The rounds still arrive a second or two apart.
- **Grad rockets** fly by gravity and air drag: the way down is steeper than the way up. The fire control knows only the
  wind at the ground; higher up it blows harder, so a salvo lands a little downwind. Each rocket leaves its tube with a
  small spread in angle and speed, so the impacts scatter by about 1 % of the range. The Grad also fires at targets
  closer than its real minimum of about 5 km (not physical: the rockets leave the tubes slower than real ones).
- **The bomb** falls under gravity and drag; its fins steer it to the aim point. It leaves the B-2 with the bomber's own speed,
  so the B-2 releases it about 145 blocks before the target (at 12 km up, about 10 km), and it comes down almost
  vertically. The wind barely moves it. Towards real speeds the drag of the Grad rocket and the bomb also goes from
  the game value to the real one, with the rise near the speed of sound.

## Shahed

![Shahed-136 in flight](img/weapon-shahed.jpg)

A kamikaze drone: it takes off from a trailer next to you, flies with a loud engine buzz and dives at the end. Use it
on buildings and on moving targets. Its two-stroke engine has no muffler: on a quiet night it can be heard about 12 km away, by day about 6 km, long
before it arrives. A SAM can shoot it down, so
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
a nuclear warhead. It sees the terrain along its whole route, also where nobody is near and the world is not
loaded: it reads the saved height of each place, and for land nobody has explored yet it uses the world generator, which
knows the ground but not trees or buildings. Chunks still saved in the pre-1.18 format stay unknown to it until they load. A heightmap already
converted to the current format remains usable even if the chunk still needs terrain below zero. It flies over a hill on its way and dives behind it. It does not look for a
way around through valleys: the route is yours to set. If a hill, the rim of a pit or a building hides the target, the
missile climbs until it can see it and then dives. It cannot get past a building that hides the target at a steep angle right next to it (for
example, 40 blocks taller than the target and 20 blocks from it), and it cannot reach a target under a roof: use
the bomb there.

## Grad

![Grad launcher](img/weapon-grad.jpg)

A trailer with 40 tubes fires a ripple of unguided rockets. It covers an area, a SAM cannot shoot it down, and the
siren at the target sounds only 8 seconds before impact.

## Bomb (B-2)

![B-2 with its bomb bay open](img/weapon-b2.jpg)

A B-2 arrives from far away and drops a GBU-57 bunker buster. Use it on bunkers and underground bases. SAM radar
detects the B-2 very late. Can carry a nuclear warhead.

**How deep it goes.** The bomb hits the ground with the speed and angle its fall gives it and keeps going in a straight
line. How far depends on that speed, on the bomb (13.6 t, 0.8 m across, a pointed nose) and on what it goes through:
Young's penetration equations (Sandia, 1997), layer by layer. With game flight speeds the B-2 flies 170 blocks up
instead of about 12 km (not physical, so you can see it). The bomb leaves it at the bomber's 240 m/s and falls with
gravity and drag, so it hits at about 235 m/s instead of about 510 m/s. With `scale.flight_speed` 1 the B-2 flies at
12 km and the bomb hits at about 510 m/s. So it goes through:

| | Game flight speeds (≈235 m/s) | Real drop, `flight_speed` 1 (≈510 m/s) |
|---|---|---|
| Stone, concrete | ≈4 blocks | ≈8–9 m |
| Deepslate | ≈3 blocks | ≈7 m |
| Gravel | ≈23 blocks | ≈40–65 m |
| Dirt and grass | ≈55 blocks | ≈100–200 m |

Dirt right under a concrete slab is twice as hard for the first 5 blocks. Water slows the bomb only a little. Bedrock
and reinforced deepslate stop it. For rock the equations are good only to within a factor of two. Which block is what
material is in the data map `incoming:penetration` (`data/incoming/data_maps/block/penetration.json`): a data pack or
mod can add its own blocks; a block without an entry counts as brick. A thin block (carpet, slab, stairs) counts only
as thick as it is.

**The fuze.** If the bomb breaks into a cave or a bunker room after at least 4 blocks of solid matter, it goes off once
it is all the way inside. Leaves, wool and powder snow barely slow it down and do not count. Otherwise it goes off where
it stops.

**The underground blast.** 2.4 t of TNT. What it does depends on how deep it is for its size, the depth divided by the
cube root of the charge (all radii here are estimates with a wide range, U.S. Army TM 5-855-1):

- Deeper than about 13 blocks the blast stays underground. It leaves a cavity about 3 blocks in radius, crushes rock to
  cobblestone or gravel out to about 10 blocks (chests and other blocks without a material entry stay), and brings down parts of the roofs of tunnels and caves out to about 27 blocks.
  Gas and dust shoot out of the hole the bomb made, and the ground shakes. If the charge is less than about 17 blocks
  deep, the cavity roof caves in all the way up a second later and leaves a pit on the surface.
- Shallower than about 13 blocks it blows a crater open: near the surface as wide as a surface blast would make, widest
  (about one and a half times that) at around half that depth, and shrinking to nothing towards 13 blocks. The closer to the surface, the more of the charge goes into
  the air: blast wave, broken windows and the cloud above are those of a surface blast of that part of the charge.
- Anything in a void next to the charge, a bunker room or a cave, takes the blast wave and the gas pressure like a
  room hit by any warhead. Anyone in the crushed zone dies.

## Nuclear (ICBM)

![ICBM during boost](img/weapon-icbm.jpg)

An intercontinental ballistic missile lifts off behind you, and 90 seconds later its warhead detonates at the target.
It climbs like a real ICBM: slowly off the pad, faster as its fuel burns, and stays in the sky for about nine seconds.
Everything about it is in [Nuclear strike](nuke.md).

## What a blast looks like

The size, rise, colour and light of every blast come from the charge and the fuel left in the tanks; the smoke and the
fireball are drawn from simulations of real gas, baked in Blender, the same way up close and far away. A film curve
keeps the bright fireball's folds and dark soot visible as it billows and cools. This changes how its light is shown,
not its physical size, lifetime or heat. The sun lights
the smoke from its side and shines through its thin edges, and a fireball lights the smoke around it. When the fuel has
burnt, the fireball does not switch off: it cools as it mixes with air, from yellow through orange and dark red into
smoke. You see it as fire while its glow outshines the light on its smoke, so by day it turns into smoke sooner than at
night: the bomb's ball is smoke by day 3 s after the blast and still glows at night. A growing cloud keeps its billowing
clumps the whole time, near and far. The air mixes the cloud from the first second, so it widens and thins as it rises.
The wind is stronger higher up: the top of the cloud runs ahead of its base, and the cloud leans and stretches downwind
instead of drifting as one ball.

- **Shahed**: a short flash, then a ball of burning petrol about 16 blocks across that burns for about 1.3 seconds and
  glows for about another second as it cools. About a quarter of the petrol stays in the crater, soaked into the soil, and
  burns for about 2.5 minutes as ordinary fire on about five blocks of the crater floor; at night it lights the ground
  around with an orange glow. Far away, where the fire blocks fade out, the fire is a glowing spot of the same colour
  and brightness, with a glare at night. Its black smoke rises from the fire the whole time and leans downwind: in a stiff breeze it
  trails low, in calm air it stands taller. The smoke of the ball, with the dust
  torn up under it, rises a few hundred blocks as a dark cloud.
- **Cruise missile**: a bigger flash and a fuel ball about 34 blocks across that burns for about 2.7 seconds; the fuel left
  in the crater burns for about a quarter of an hour on about nine blocks. Its own charge gives little soot, so the smoke is mostly from the burning fuel,
  with a lot of dust.
- **Grad**: a flash and a small grey-black puff with dust, no fuel ball. A salvo leaves a field of puffs that merge and
  drift downwind, over a carpet of dust from all the hits.
- **Lancet**: a small flash and a cloud a few tens of blocks high.

The blast wind tears dust off every surface it reaches, ground, roof or hillside alike, the same way as the wave of a
nuclear strike does. Bare dirt, sand, gravel and snow give it up easily: a low ring of light dust races out with the
wave, a few tens of blocks for a Shahed, then thickens to several blocks, hangs for minutes and drifts with the wind near
the ground. Grass holds the soil, so on a meadow the dust rises only around the crater; stone, brick and planks
carry only a thin film of settled dust. A leafy canopy is permeable: the surface beneath it supplies the forest
dust. Water gives none: by a shore the dust lies mostly over the land. Dusty air is heavier
than clean air, so the dust spreads out along the surface it came from and follows the ground up and down gentle
slopes; where it drifts past an edge (a roof, a cliff) it sinks to the surface below: thick dust in seconds, thin dust
slower. It climbs gentle slopes but not walls: in a street between houses it stays in the street, in wind or calm. While the cloud rises, a
thin column of dust is drawn up under it. An interceptor bursting in the air leaves no crater and no dust, unless it
bursts low over the ground. Some of what a hit crushes rises with the hot gas, while cold
ejecta and falling material feed the low, heavy dust layer (the split is an estimate: half in each). The wave can also
lift dust from the street below where it is still strong enough there.

What the blast looks like depends on what it hits, not on whether it is a roof, a road or a field: the server goes
from the face the warhead struck straight into the block, square to that face, with the same breach rule it uses for
damage, and tells every player what it found. So a roof the charge breaks through looks like one at any distance, and
a slab the charge cannot break looks like a hit on solid ground.

- **Soil, sand, gravel, snow or clay:** the crater throws a curtain of dark earth jets up and out at about 45°. Most of
  it is slow: for a Shahed nine tenths is back on the ground within about 1.7 seconds, and at one second the curtain
  stands about as high as the crater is wide. Sand gives a light, dense curtain, gravel a sparse one of lumps. The
  curtain is drawn at any distance, not only as particles up close.
- **Stone, concrete, brick or planks on solid ground:** no cone of earth. The crater in rock is smaller, and what the
  charge crushes rises as dust of its own colour: light grey from stone and concrete, darker from deepslate, the colour
  of mortar from masonry, ochre sawdust from planks. Chips fly on short arcs; fragments strike sparks on stone.
- **A slab over empty space** (a roof, a bridge deck, a floor) that the charge breaks through: pieces fly off the face
  that was hit, and the back of the slab is punched straight out of its far side into the space behind, at any angle
  of the hit (down under a roof, sideways through a wall): the blast pushes the plate square to its face. Dust of the
  slab's material comes out of the hole. A Shahed through a metre of planks drives the pieces behind it at about
  30 m/s (an estimate, the upper bound of the Gurney formula). Where the hot gas goes inside a room is not modelled.
- **Water:** the column of spray below.
- **In the air** (an interceptor, a hit high above the ground): a ball of hot gas centred where it burst, with no
  crater, no earth and no column of dust under it.

The masses of crushed material, the grain size that
makes the curtain visible, the share thrown as clods and the split of slab pieces up and down are estimates; the
sources give no measurements for these targets and charges.

A blast on water throws up a white column of spray instead of dust. A surface hit gives about 78 blocks for the Shahed,
113 for the missile and 39 for Grad, estimated from Swisdak's conventional-charge plume table with its safety margin
reduced. These are the visible top and width of the spray, as in the table; the Shahed's column is about 47 blocks
wide at its widest, as the spray falls back (about three quarters of that at the top). Charge depth changes the height and width. The column is a bundle of jets, each a chain of round spray puffs. The
Shahed's column reaches its top in under four seconds and is back in the water by about seven and a half; slower jets
fall earlier, leaving foam and a low ring of water mist. The mist droplets are about 0.1 mm across and settle at
0.3 m/s, and an ordinary wind over water cannot hold them up: the mist stays a thin layer on the water, drifts
downwind and thins out: half of it settles in about 5 seconds for Grad and 10 for the Shahed. Only a gale lifts it higher. A wave ring spreads across the water at a
speed that depends on water depth. Clumps of water fly as particles. Fuel that lands on water spreads too
thin to keep burning, so no fire is left after the ball.

The fireball lights the ground and walls around it, like the fireball of a nuclear strike: at night an orange glow
spreads over the blocks around the crater and fades as the ball cools. The fuel burning in the crater lights its
surroundings the same way, from where it burns, until it runs out. Blocks cast shadows from this
light: a house or a hill between the fire and the ground leaves it dark, even when the house is outside your view.
Shadows are worked out for about 90 blocks around you and 40 up and down; further away the light has
none. Hot gas over the fire, over the fireball and in a rocket's exhaust bends light, so whatever is behind it
shimmers. The shimmer is shown as large as your eye would see it: about a pixel over a fire or a fireball on a normal
screen, so it shows on edges behind them, and less in a rocket's exhaust, whose fast gas blurs; through binoculars it
is the true angle.

Fire, the fireball, flashes and rocket flames glow on the screen: a soft halo of light scattered by the air around the
flame, which follows its shape and fades with distance from it. It is the same whether you use a shader pack or not. It
shows best at night against the dark; by day it is faint. Smoke in front of the fire dims both the fire and its halo,
and fire hidden behind a wall or a hill gives none. This changes how the light is shown, not how bright the
fire is or how much it lights around it. The glow can be made cheaper or turned off in the settings (Glow around fire).

The shock front itself is seen the way it is on film. The compressed air behind it bends light, so a thin ring of
shimmer runs out from the fireball over the sky and the ground: a few pixels for a fraction of a second, fading as the
pressure drops. On grass and under trees the front tears off grass, turf and leaves as it arrives and flicks them up,
so a ring of flying bits races out at the speed of the front and settles right behind it: the air behind the front
moves only a few tenths of a block, so nothing is carried far. A Shahed tears them within about 50 blocks, a cruise
missile within about 85, a Lancet within about 20. Pale puffs from the torn turf mark the travelling ring, then drift
and settle over seconds. They stay in the air after the pressure pulse has passed.
Refraction, clump size, lift and contrast are emphasised for presentation; the ring keeps the pressure front’s radius
and timing. The shimmer shifts the image by no more than three pixels. Bare soil, sand and snow give dust instead.

When the front reaches you, you feel it. It throws your head back for a moment and the picture shakes briefly.
Everything light within about 16 blocks of you, grass, leaves, grit and fine dust, jumps and is blown away from the
blast in one puff. The air moves only that one short push, so the bits hang, settle and drift off with the ordinary
wind. A wave strong enough to stun you blurs the picture, drains its colour and darkens its edges for one to three
seconds. A weaker wave leaves the screen alone. When and how strongly this happens follows the wave at your head: its
pressure and impulse, the hearing loss it causes and how hard it moves your head. The blur, grey and dark edges are
presentation. You can weaken or turn it off in the settings (Concussion on screen), and the vanilla Distortion Effects
option scales it and the camera shake too.

The bunker bomb also sends a wave through the ground. It reaches you at the speed of the ground under the bomb: through
rock and wet clay before the bang in the air; through loam and loose sand it is slower than the air wave and fades
sooner, and a few hundred blocks away you no longer feel it. The ground jolts you up and down, harder the faster it
moves, and you hear it rumble.

In sufficiently humid air a white condensation shell appears briefly behind the pressure front, after the air expands
and cools below its dew point. It evaporates as pressure recovers. Rain makes it much easier to see; dry air may give
no shell. Its negative-phase history and outer extent are estimates. By day the cloud rises higher than at
night, when cold air near the ground holds it down. A cloud stays visible for minutes, until it has spread too thin to
see. Sparks, clods of earth, the dust and sparks of fragment hits and, where fuel was left in the tank, burning
pieces are particles. A burning piece in flight leaves puffs of grey-black wood smoke that rise with their own heat and
drift away, and its flame lights the smoke around it. The bomb's blast is drawn the same way: a
breach gives the cloud of the part of the charge that went into the air, a deeper blast a smaller cloud from the mouth of
the hole; the dust of the impact and of a caved-in pit stays low and drifts.

Whatever the blast sets on fire keeps smoking. Over every fire a column of black smoke rises, leans downwind and
stretches out with it; a bigger fire sends it higher. Burning blocks close together, the fuel burning in a crater and an
oil fire next to them are one fire: a burning house sends up one tall column, not several weak ones. By day it climbs higher than on a calm night, when
it stays low and drifts flat. Near or kilometres away, the column is drawn the same way as a smoke trail: one unbroken,
billowing tube, never a row of separate puffs; where it is narrower than about two pixels it is a thin line. At night the
flames light the smoke from below. The column grows from the fire at the
speed of the wind, and when the fire goes out the last of the smoke drifts away. Where the fire comes from is up to the
server: the smoke follows any fire your game shows, not only fires from strikes.

In a big raid each blast is heard as its share of the din: the nearest one sounds about as loud as it would alone, the
far ones lie under it about as much quieter as in life and come back up once it has faded. When more blasts sound at
once than the game has sound channels for, the quietest stop being separate sounds and merge into a low rumble around
you, just as loud.

Past your render distance the mod draws projectiles, blasts and smoke itself, as far as the server's visibility range
(8 km by default), launches included: the booster flame, the launch cloud and the smoke trail of a launch kilometres away. With **Distant Horizons** or **Voxy** the distant terrain hides them the way a real hill would: a
blast behind a far ridge is hidden by the ridge, one in front of it is seen whole. This works with and without a shader
pack. Without either mod there is no distant terrain to hide behind.

## Flames and smoke trails

Every engine flame and smoke trail is worked out from the motor's data, the same way for every weapon, and looks the
same up close and far away.

- **Rocket motors** (the Shahed's and the cruise missile's boosters, the Grad rocket, the ICBM stage) have a short hot
  core at the nozzle and a brighter orange flame behind it, where the exhaust burns in the air. Its length and colour
  come from the motor, and so does its brightness: a booster's flame is many times brighter than the daylight sky, so by
  day as well as at night it is white at the nozzle and turns yellow, then orange where it cools. Shock diamonds form in
  the core, but the burning flame around it is 10 to 1000 times brighter, so they are not seen. At night the cooling tail
  glows further back, and the flame lights the smoke around it. The flame also lights the ground, the launcher and walls
  nearby along its whole length above the ground, from the nozzle to the end of the glowing tail, and blocks cast shadows
  from it. A booster does not switch off: in its last moments the remaining propellant burns out and the pressure in the
  motor falls, so the flame shortens and dims and the roar fades over about half a second on the cruise missile (about a
  tenth of a second on the Shahed's small booster) before the empty booster drops away. The nozzle stays hot after
  that: seen from behind, the cruise missile's spent booster glows yellow-white in the nozzle as it separates, then
  reddens as it cools. The Shahed's booster burns for only 2.5 seconds and its nozzle is small, so it fades within a few
  seconds. By day the glow is visible only in the first seconds; at night it stays visible longer. Empty boosters are
  cleaned up after 5 seconds on the ground or 20 seconds after separation, whichever comes first. Before cleanup,
  both the body and its glow fade together over 2 seconds, becoming invisible one second before removal. This cleanup
  is **not physical**; the physical cooling model continues beneath the fade, but its longer cooling times are not shown.
- **Engines without a flame**: the Shahed's piston engine, the cruise missile's jet engine and the Lancet's electric
  motor show no flame and leave no visible smoke.
- **Smoke trails**: boosters with aluminium in their fuel and the ICBM leave thick white smoke, and the Grad leaves a
  greyer trail. A slower rocket leaves a denser trail. The hot trail rises a little. By day it spreads and fades sooner;
  on a calm night it stays narrow and hangs much longer. Turbulence bends its axis while the smoke itself spreads more
  slowly: a SAM trail remains visible for tens of seconds by day and minutes on a calm night, drifting with the wind
  at its height. The SAM's trail continues beyond entity tracking distance, including its sustainer section.
  A trail is a round, billowing tube of smoke, lit by the sun and
  by the flame, near and kilometres away alike, unbroken along its length; only where it is narrower than about two
  pixels is it a thin line. At
  dusk and at night it takes the light of the open sky the way the clouds do: grey at sunset, pale lilac under the moon.
  The smoke starts where the visible flame ends: closer to the nozzle the exhaust is still the flame itself.
  A Grad trail covers the real burn path at any `scale.flight_speed`: a real 9M22U speeds up to about 690 m/s over its
  1.9 s burn and leaves about 650 m of smoke, with the smoke of all its fuel spread over that length. At game speed the
  rocket's flame goes out after 2 s, about 160 blocks from the tubes, and the smoke keeps coming without a flame until
  the rocket has flown the whole 650 blocks (not physical: at game speed the motor would burn out sooner). With
  `scale.flight_speed` 1 the rocket leaves the tubes at full speed (not physical), and the trail ends at 650 blocks while
  the flame still burns.
- **Launch cloud**: while a rocket's exhaust still hits the ground, its smoke stays at the launcher, together with the
  dust it blows up, and rises and drifts as one warm cloud. A Shahed leaves a short white puff at the rail, a cruise
  missile's booster a bigger one. Each Grad rocket adds its own cloud, so a full salvo buries the launcher in smoke and
  dust that hang and drift downwind. Stone and concrete raise no dust. The ICBM leaves a large cloud at the pad. The
  jet blows the dust along the ground the way it points: from a slanted rail or tube (the Shahed, the Grad, the cruise
  missile) the dust streams out ahead of the jet in a long tongue, from a jet straight down it spreads all around.
- **From behind**: looking along a trail or straight at a nozzle you see the round end of the smoke and the flame, not a
  flat strip.
- **Your eyes adjust to fire**: next to a launch at night or a fireball, your eyes adjust to the fire's light. The flame
  is bright but does not wash the whole screen out; a flame far away does not change how your eyes see the night.
- **Wind**: smoke drifts with the world's wind, the same wind that carries nuclear fallout. Each game day has its own
  wind, and it turns slowly from one day to the next. Near the ground it is weaker, most of all at night.
- **Contrails**: the B-2 leaves a contrail only where the air is cold enough for one to form. At the height it flies
  now the air is too warm, so it leaves none. The falling bomb leaves no trail. A white vapour collar forms around a body
  only near the speed of sound in humid air; the bomb falls slower in the game, so it has none.

## No room for the launcher

The launcher sets up next to you. If there is no room around you, the designator says "No room for a launcher…" and
the projectile comes in from far away instead. To watch the launch, move to open ground.
