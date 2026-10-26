# Defense

English · [Русский](ru/defense.md)

## Sirens

Everyone within 350 blocks of the target hears a siren and sees a warning on screen before the strike. A nuclear strike
is announced to everyone within 20 km.

| Warning | Weapon | Before impact |
|---|---|---|
| ⚠ AIR RAID ALERT ⚠ | Shahed, Lancet, B-2 | 20–25 s |
| ⚠ MISSILE THREAT ⚠ | cruise missile, Grad | 15 and 8 s |
| ☢ MISSILE ATTACK ☢ | ICBM | 90 s, with a countdown |

Projectiles can be seen from up to 8 km away. How far you hear one depends on its engine and on the weather. On a quiet
night a Shahed is heard about 12 km away (its two-stroke has no muffler), a Lancet only a few hundred blocks away, and a cruise missile's turbine or a
burning rocket motor many kilometres away. By day the warm ground bends sound upwards, and in rain the noise hides it,
so you hear everything from closer. Damp air takes less of the high notes: in rain a distant engine sounds brighter,
even though you lose it sooner. An air defence missile's motor is heard by the same law, far away too. Sound travels at the speed of sound: a distant flash shows up before you hear the
explosion, and you hear a fast projectile only shortly before it arrives. A projectile faster than sound is not heard
until its shock wave reaches you: first comes the crack, then its own sound. At GAME speeds, a Grad rocket fired from a fixed launcher at a target more than about 7 km away can already be
supersonic: its exit speed is solved for the distance (about 480 m/s at 10 km). With REAL, or CUSTOM toward real
speed, Grad rockets, SAM interceptors and B-2 bombs can be supersonic too, so their sound can arrive after they pass.

An explosion is heard as far as its charge carries: on a quiet night even a Lancet or a Grad rocket rumbles tens of
kilometres away, up to 32 km, the farthest the mod sends anything; by day, behind hills and in rain much less. A small
charge sounds short and sharp, a big one low and long; its echo comes back from the hills after the blast itself.

## Cover

A blast hits everyone within its radius, you included, and debris flies far.

The blast wave reaches further than the damage. When it passes you, the view shakes for a moment, then the wave shoves
you, your head rocks on your neck and you come back upright over two or three seconds. A Shahed within about 20 blocks
knocks you off balance, at 100 blocks the view swings by a few degrees, a few hundred blocks away you barely feel it.
The shake is stronger the higher the pressure and lasts longer the bigger the charge (0.3–1.7 s). A wave strong enough to hurt the
ears muffles your hearing and makes your ears ring for game hours, like after a nuclear blast: a Shahed within about 75
blocks, a Lancet within about 35, a cruise missile within about 150. A wall, a house or a hill between you and the blast
softens both. Hearing damage can be turned off in the [settings](settings.md).

- **Get underground.** Earth and thick walls stop a blast; underground and under water even glass stays intact.
- **The bunker buster** goes through about 4 blocks of stone or concrete, or tens of blocks of dirt, and explodes there;
  it crushes rock about 10 blocks around and brings down tunnel roofs about 27 blocks away
  ([details](weapons.md#bomb-b-2)). Depth in rock protects against it, or a SAM that shoots down the B-2.
- **Nuclear strike**: see [how to survive](nuke.md#how-to-survive).

## Surface-to-Air Missile System (SAM)

The SAM is a block that shoots down other players' Shaheds, Lancets, cruise missiles and B-2s by itself. It leaves the
projectiles of the player who placed it, and of that player's team, alone.

The block itself is the radar cabin. Four missile canisters stand upright on top of it, so the whole SAM is about five
and a half blocks tall. An interceptor leaves its canister straight up, then turns toward the target in a short arc.

![A SAM shooting down incoming Shaheds; the radar screen in the corner](img/sam.webp)

- Its radar reaches 3 km and it fires from 1.5 km, one interceptor per target. These are game ranges, much shorter
  than a real system's, so that the fight happens within sight (not physical). Harder-to-detect targets are seen closer:
  a cruise missile from 2.1 km, a Lancet from 1.5 km, the B-2 only from 450 blocks.
- The radar sees only what is in line of sight. A hill or a building between the SAM and the target hides it: a cruise
  missile flying low behind a hill is not seen until it comes out. Ground in chunks the server has not loaded hides
  nothing.
- An interceptor explodes where it passes closest to its target. If that point is within its fuze radius (6 blocks
  plus half the target's length), the target is shot down. There is no dice roll. If it passes farther away, the target
  flies on and the interceptor turns back after it until its fuel runs out (15 s).
- It does not shoot down ICBMs or Grad rockets. It does not see players, mobs or aircraft.
- It needs open sky above it and a player nearby: it does not work in an unloaded chunk.
- Its own side gets "Air raid alert!" with the direction to the target. Near the SAM, a radar screen appears in the
  corner of the screen.
- RMB on it with an empty hand shows how many missiles are ready and how many are in stock.

How often it shoots targets down follows from the game model; nothing is tuned. In a batch of 400 simulated raids per
target over flat ground, with the target point up to 600 blocks from the SAM, every interceptor it fired hit:
all Shaheds, cruise missiles and Lancets were shot down, and 78 % of B-2s. The other B-2s never came within the 450
blocks at which the radar sees them. In a real game targets get through behind hills, in salvos larger than its four
rails while it reloads, and when it runs out of missiles.

Against a SAM: a salvo (it has only four rails), a route around it or behind hills, the hard-to-detect B-2, or the Grad.

### Crafting and loading

![SAM recipe](img/recipe-sam.png)

![SAM Interceptor recipe (two per craft)](img/recipe-interceptor.png)

SAM ingredients: 2 iron bars, 1 compass, 4 iron ingots, 1 precision mechanism and 1 iron block; makes 1 SAM.
Interceptor ingredients: 1 precision mechanism, 4 iron ingots and 2 gunpowder; makes **2 interceptors**.
Without Create, use a clock in the SAM and a comparator in the interceptor instead of the precision mechanism.

1. Place the SAM under open sky and keep its chunk ticking by staying nearby.
2. Load interceptors with RMB or a hopper. Stock has two slots of 16 missiles each, separate from the four ready rails.
3. Wait for loading: by default one missile moves from stock to a free rail every 8 seconds, including the first one.
   Filling four empty rails takes 32 seconds if nothing fires (`air_defense.reload_seconds` and `air_defense.rails` change this).
4. RMB with an empty hand checks ready missiles and stock. If the SAM is covered, it shows only "blocked from above";
   clear the space above it to see the counts. Stock alone cannot fire until a missile reaches a rail.

Missiles cannot be taken back out, but a broken SAM drops its stock and ready missiles.
