# Settings and keys

English · [Русский](ru/settings.md)

Settings open with the Settings button on the console or under Mods → Incoming → Config. Every option has a tooltip.

## Your settings

Each player has their own, stored in `config/incoming-client.toml`.

| Setting | Default | What it does |
|---|---|---|
| Camera shake | 1 | how hard blast waves jolt and lean the view; 0: no shake; also scaled by the vanilla Distortion Effects |
| Flash brightness | 1 | 0: no on-screen flash from explosions; with the vanilla Hide Lightning Flashes on, the flash stays dim |
| Concussion on screen | 1 | how strongly a blast wave that stuns you blurs, greys and darkens the picture for one to three seconds; 0: off; also scaled by the vanilla Distortion Effects; hidden with F1, like the game's own screen overlays |
| Show strikes in flight | on | list of your projectiles and marks in the world |
| Projectile camera on launch | off | the camera shows every launch by itself |
| Binocular zoom | 4 | from 1.5 to 10 |
| Mushroom cloud, dust wall and pall | MEDIUM | the fireball and the mushroom cloud with the water column of a burst on water, the dust wall of a nuclear blast wave, the dust and smoke over the area after it, the base surge over water, and the firestorm over a burning town, drawn as a volume; lower is lighter on a weak graphics card, and MEDIUM and HIGH lower their own detail when the card falls behind (not while Flashback exports a video); OFF: no dust wall, no pall, no base surge and no firestorm, only the dust close around you and falling ash, and the fireball, the mushroom cloud and the water column at LOW |
| Glow around fire | FAST | glow around fire, fireballs, flashes and rocket flames; FAST works it out from a coarser image and is cheaper, FULL is finer close to small and fine-detailed flames, OFF: no glow |
| Hearing damage | on | muffled hearing and ringing ears after a blast wave that hurts the ears; torn eardrums from 5 psi |
| Incoming's blast effects | on | the flash, fireball, smoke, dust, debris, blast wave, sound, echo, jolt and hearing loss of strike blasts, and the bunker bomb's impact, ground tremor, gas vent and collapse; turn off only when another mod shows explosions instead (for example Explosion Overhaul, see the README); only your picture and sound change, destruction and damage are the server's; air defence hits and nuclear blasts stay |
| Muffle distant and hidden strikes | on | distant strikes lose high tones, hidden ones sound muffled, and inside the dust pall all distant sounds are muffled |

The mod's sounds follow the Ambient/Environment volume slider.

## Keys

Options → Controls → Key Binds → Incoming: launch in the binoculars (LMB), projectile camera (`N`), targeting map
(`,`), second key for a nuclear launch (`.`), open console (unbound by default). The binoculars and the console use the
regular Use Item key (RMB and Shift+RMB).

## World settings

The world owner changes these: in singleplayer in the file `saves/<world>/serverconfig/incoming-server.toml`, on a
server its owner does. The most useful ones are below; the rest are in the same file, each with a comment.

| Key | Default | What it does |
|---|---|---|
| `launch.designator_for_everyone` | on | the designator works for all players, not only operators |
| `launch.max_salvo`, `max_spread` | 30, 150 | largest salvo and spread |
| `launch.max_active_per_player` | 0 | how many of one player's rounds may be in progress; 0: no limit |
| `launch.sight_rules` | on | [spotting](rules.md#spotting): strike only spotted targets |
| `world.block_damage`, `fire` | on | destruction and fires |
| `nuclear.enabled`, `ops_only` | on, on | nuclear weapons exist; only operators launch them |
| `scale.scale_preset` | GAME | GAME: flight is slower and lower than in life, and the nuclear blast wave away from the fireball travels at 0.20 of the speed of sound (both not physical: so that you can watch the approach and the wave roll in, and the server has more time for ruins); REAL: true to life; CUSTOM: the three numbers below as they are. Everything at the target is real with any preset |
| `scale.flight_speed` | 0 | with CUSTOM: [flight speeds and heights](weapons.md#how-they-fly), 0 the game values, 1 true to life; in between every weapon goes the same share of the way. Turns stay game values. Flight times stay, so a faster weapon starts further away |
| `nuclear.effects_scale` | 1.0 | with CUSTOM: scale of nuclear strike radii (1.0 is true to life) |
| `nuclear.wave_speed` | 0.20 | with CUSTOM: speed of the blast wave away from the fireball, as a share of the speed of sound (1.0 is true to life) |
| `grid.enabled` | on | [blackout](blackout.md) |
| `performance.work_ms_per_tick` | 30 | shared ceiling in milliseconds for impact destruction, nuclear world work and blackouts; the server also reserves time for world simulation |

During dense salvos, ready impact destruction gets time before optional chunk saving. Work on older fires and
windows takes turns with newer blast fronts, so one long sweep does not hold up the next blast's main destruction.
Very busy servers can still spread destruction over several ticks.
