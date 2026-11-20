# FAQ and troubleshooting

English · [Русский](ru/faq.md)

## What a message means

| Message | What to do |
|---|---|
| "Not enough munitions…" | craft more or make the salvo smaller ([munitions](munitions.md)) |
| "… has not been spotted…" | find them with your own eyes or a projectile camera, or have your team spot them ([spotting](rules.md#spotting)) |
| "No room for a launcher…" | the projectile comes in from afar; to watch the launch, move to open ground |
| "Launch failed: the world near the target is not loaded" | the munition was returned; try again |
| "No target found" | nothing is under the reticle, or the ground there is too far to make out: strike from the map |
| "… cannot fly this route…" | remove or move route points |
| "Only operators can use the designator here…" | the world owner set it up that way (Settings → Launch) |
| "Nothing under the crosshair…" | aim the binoculars at the ground, a block, a mob or an aircraft |
| "Nuclear strikes: operators and the world host only…", "Nuclear weapons are off…", "… is above this world's limit…" | the world owner decides in Settings → Nuclear weapons; in your own single-player world that is you ([nuclear strike](nuke.md)) |
| "Too many strikes in progress…" | the world owner set a limit: wait for impacts or [abort](designator.md#abort) |
| "Mission given to launchers…" | [launchers](launcher.md#mission) are linked to the designator; unlink them with RMB to make it fire by itself |
| "Incoming: your graphics card stalled on the nuclear cloud…" | one frame of the nuclear cloud took your graphics card over a second, so the fireball, mushroom cloud and dust stay off until you restart the game; update the graphics driver, set "Mushroom cloud, dust wall and pall" lower, and [report it](https://github.com/zentixua/incoming/issues) with your log |

## Troubleshooting

**A server will not let you in: different mod versions.** Download the same jar the server has from the
[Releases](https://github.com/zentixua/incoming/releases) list: choose the exact version used by the server.
The latest release is for updating the server and all clients together.

**The map is empty far away.** Far terrain needs LOD Server Support on the server and your client, Voxy, or Distant
Horizons; without them only loaded chunks are shown. Voxy shows only what you have seen: in single-player run
`/voxy import current` once. You can still strike there. Installed LOD Server Support before this
version? Run `/lss clearcache` once on that server.

**No sound from the mod.** Check the Ambient/Environment slider.

**The game stutters during a strike.** If only the picture stutters, lower "Mushroom cloud, dust wall and pall"
or try without shaders. If mobs and redstone slow down for everyone, reduce simultaneous strikes, especially in
unexplored areas. The owner can also adjust [world settings](settings.md#world-settings); slower destruction gives
the rest of the game more time. Memory needs depend on your modpack.

**A launcher or SAM does not fire.** RMB on it with an empty hand shows why. A SAM needs open sky, missiles on its rails and
a player nearby; it does not shoot down its own side's projectiles, ICBMs or Grad rockets.

**The camera says "Nothing in flight".** The camera shows only your own projectiles that are already launched.

**A strike hit your own base.** A blast hurts everyone: check the target on the console before firing. "Around me"
strikes where you stand.

**How do I get rid of the smoke?** After a nuclear blast the host or an operator runs `/incoming clear`
([what it ends](nuke.md#ending-the-aftermath)). The smoke of an ordinary strike comes from fuel burning in the crater
or from a knocked-out substation's oil, and stops by itself when it burns out: about 2.5 minutes after a Shahed, about
a quarter of an hour after a cruise missile ([burning craters](weapons.md#what-a-blast-looks-like)).

**The crater fire won't go out.** It is the fuel left in the crater; it burns out by itself in the time given
above. Neither rain nor punching it out helps:
burning fuel lights again. Pour water over it or place a block on it, and that part goes out for good. `/incoming clear`
does not put it out. Flammable blocks it has set alight burn as ordinary fire
([burning craters](weapons.md#what-a-blast-looks-like)).

**Why do blasts break so little?** Each weapon carries the charge of the real one, and a block is a metre. A Shahed
(50 kg of TNT) leaves a crater in soil about 2.4 m across and breaks brick walls within about 3 m; a cruise missile (about
150 kg) within about 7 m; a Grad rocket (6.4 kg) within about 1 m, so trees next to it can stay standing. What the
blast leaves without support then falls: a missile in a ground-floor flat can bring down the storeys above it. Smoke and
dust reach much farther than the damage, as in real footage. There is no setting to make conventional weapons
stronger ([the real weapons](weapons.md#the-real-weapons), [what a blast does](weapons.md#what-a-blast-does)). The
default 15 kt nuke brings down brick buildings out to about 700 m and wooden ones out to about 1.3 km.

**Nuke too big or too small for my world?** Choose the yield on the console, from 1 kt up to the world's limit
(100 kt by default). The world owner can raise the limit up to 1 Mt with `nuclear.max_yield` (at most 1000), or
shrink every nuclear distance: set `scale.scale_preset` to `CUSTOM` and lower `nuclear.effects_scale` (1.0 is true to
life; at 0.1 a 15 kt blast brings down brick out to about 70 m). Settings open with the Settings button on the console
or under Mods → Incoming → Config; world settings can be changed there in a single-player world
not opened to LAN, otherwise in `incoming-server.toml` ([world settings](settings.md#world-settings)).
