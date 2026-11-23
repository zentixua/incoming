# Incoming as a building block: universal integration (proposal)

Status: proposal, no code. Artem picks items; each picked item becomes its own PR with tests and guide updates.
Russian owner version: the mission report. Code references are `path:line` against `main` at the time of writing;
`J/` is `mod/src/main/java/ua/zentix/incoming/`, `D/` is `mod/src/main/resources/data/`.

## 1. Verdict

Incoming owns the phenomenon: launch, flight, air defense, the blast, the nuke and its aftermath (fallout, fires,
blackout). It does not grow bunkers, radios, factions, economy or quests. It becomes universal by speaking the
languages packs already speak, so that any other mod, datapack, script or game master can see what happens and set
the rules:

- **Signals out**: events for order, launch, interception, impact and detonation; advancements and statistics;
  command results; redstone from the blocks that sense something.
- **Rules in**: permission nodes, claim protection for every kind of block damage, world rules settable by command.
- **Data in**: tags and data maps for what protects (bunker materials, radiation shielding), what lights (blackout
  lamps) and what air defense engages.
- **Optional bridges**: small `compat/` modules for the few mods that need an API call (claims, ComputerCraft,
  Jade), loaded only with that mod.

The largest gap today: Incoming posts no events of its own, nuclear destruction is invisible to claim mods, and there
is no permission or statistics surface. These are cheap compared with the mod itself and unlock servers, minigames and
the Almighty game master at once.

## 2. What players say

Sources reached: CurseForge comments (34 of 41 returned by its API), YouTube comments (316, of which 307 under one
review video, "This Minecraft Nuke Mod is INSANE (Incoming 1.21.1)", 315k views), player GitHub issues (1).
Modrinth returns 404 for the project (no public listing is visible to its API); Reddit blocks unauthenticated access.
Counts are approximate, one comment can carry several themes.

| Theme | Count | Representative quote |
|---|---|---|
| Visuals and sound praised | ~24 | "sick particle effects though" (135 likes) |
| Compared with HBM's Nuclear Tech, Kiloton and others | ~25 | usually as the deeper mod |
| Conventional damage too small | ~20 | "all bark no bite. INSANE graphics but the damage is way too small" (327 likes) |
| Another version or loader (1.20.1 Forge, Fabric) | 15 | "1.20.1 forge PLS i need this for my war modpack" |
| Combine with other mods | ~15 | Superb Warfare (4), Create/Aeronautics (4), TACZ (3), physics mod (3) |
| Performance | ~14 | nuke freezes on some machines (GitHub #656) |
| Servers, SMP, PvP | ~8 | "Somebody should make a smp with that mod" |
| How to use it | ~8 | "I just didn't have any clue how to use it" |
| Survival, crafting | ~5 | reviewer: "fun to mess with in creative mode but I do not need to fill my survival world with smoke"; "crafts are too easy… HBM's NTM adds whole process lines" |
| Minigames, events | ~3 | "Civilization videos are drooling rn" (top comment, 262 likes) |

What follows from it:
- Nobody reports trying survival and giving up; the "creative only" impression comes from the reviewer and from
  comparisons with HBM, where weapons sit at the end of a long production chain. Players do not ask Incoming for a
  production chain of its own; they ask to put it next to their other mods (Superb Warfare, Create, TACZ) and on a
  server with friends ("civilization" videos, "war modpack").
- Nobody mentions permissions, claims or admin controls: server players do not get that far. The server owner who
  would need them meets an all-or-nothing switch today (section 3).
- The damage complaint is outside this proposal: blasts carry real charges by design (AGENTS.md concept, no damage
  sliders). Section 6 notes where it touches integration.

## 3. Where Incoming is a closed box today

What already connects well:
- Conventional warheads build a real vanilla `Explosion` and fire `ExplosionEvent.Start`/`Detonate`
  (`J/warhead/StagedExplosion.java:428-433`, `:583`); a cancelled Start skips the crater, fire and debris too
  (`J/warhead/Warheads.java`). Collapse offers its falling positions as `Detonate` events (`warhead.md`).
- Own damage types tagged into vanilla tags (`D/minecraft/tags/damage_type/`): Blast Protection, fire resistance and
  armor rules apply; kill messages work.
- Recipes are ordinary datapack JSON with a Create variant and a vanilla fallback through `neoforge:mod_loaded`;
  Create mechanical crafters build munitions.
- Fixed launcher: hopper and Create loading (item handler capability, insert-only), redstone fire on a rising edge,
  comparator output of stock (`J/launcher/FixedLauncherBlock.java:89-103`, `:149`). SAM: item handler and comparator.
- Data: block tags for nuclear wave classes, ignition, glass, passable blocks; data map `incoming:penetration` for
  how far bombs and charges get through each block (bunker materials); entity tag `aim_ignore`.
- Teams: sides use vanilla `/team` (`J/target/Sides.java`).
- Commands: `/incoming` covers single strikes, salvos with routes, nukes, launchers, the grid, radiation; command
  blocks and the console are not charged munitions.
- Almighty note `D/incoming/almighty/notes/strikes.md` teaches the game master the command syntax.

Closed points, most blocking first:
1. **No events.** Incoming posts no event of its own; launch, interception, impact, detonation and blackout cannot
   be observed or vetoed by mods, KubeJS scripts or Almighty rules. A designator launch arrives as a network packet,
   so a game master does not see it at all.
2. **Nuclear destruction ignores claims.** Ruins are written straight into chunk sections, no `ExplosionEvent`, no
   block events (`nuclear-world.md`). A server owner can only turn nuclear damage off for everyone. Conventional
   crater digging is gated only by the Start event, not per position.
3. **Claims and loggers cannot see the player behind a blast.** The explosion's source entity is the projectile,
   which extends `Entity`, not vanilla `Projectile` (`J/entity/StrikeProjectile.java:76`), so vanilla
   `Explosion.getIndirectSourceEntity()` resolves no player. Claim mods then see a non-player explosion, and loggers
   cannot blame anyone. The damage source does carry the owner, but only when the owner is online in the same level
   (`J/warhead/Warheads.java:601`); debris, radiation ticks and burn shock never carry it.
4. **No permission nodes.** Launch rights are two global switches (`launch.designator_for_everyone`,
   `nuclear.ops_only`); no per-weapon or per-rank rights, so LuckPerms and FTB Ranks cannot help.
5. **Command results are 1 or 0.** No count, no ETA, no way to ask what is in flight; datapacks and the game master
   cannot follow a wave.
6. **No gameplay advancements or statistics.** Only recipe-unlock advancements; quest mods, scoreboards and the
   game master's advancement feed get nothing.
7. **No protection hook for radiation.** Shielding is block mass along rays, density from the block's sound type
   (`J/nuclear/radiation/Shielding.java:163-175`); armor and items never help, and no tag or data map lets a pack
   declare a hazmat suit.
8. **Air defense sees only Incoming projectiles** (`J/defense/Radar.java`), and sends no signal when it engages.
9. **Blackout lamps are a fixed list of 19 vanilla lamps** with unlit twins (`J/registry/ModBlocks.java:124-144`);
   modded lamps stay lit.
10. **Survival gaps.** The Geiger counter has no recipe; recipes name exact items, never common `c:` tags.
11. **World rules are config only.** A datapack or command block cannot switch block damage off for one arena round.

## 4. Principle: what stays ours, what belongs to the pack

Ours, because it is the phenomenon or tied directly to it:
- Weapons, launch and targeting (designator, fixed launcher, spotting rules), flight, air defense (SAM).
- Blast physics, crater, collapse, fires at our sources, debris; the nuke with flash, wave, cloud, ruins, fallout,
  radiation dose and sickness; blackout of the grid we knock out; early warning and sirens.
- The crafting of our own items, and the Geiger counter that reads our radiation.

The pack's, through other mods:
- Bunkers and doors (vanilla, Create, any building mod; our `incoming:penetration` data map makes their materials
  count), radios and voice (Simple Voice Chat and its addons), teams and factions (vanilla teams, FTB Teams, Open
  Parties and Claims), claims, economy and shops, quests (FTB Quests and others), ranks and permissions (LuckPerms,
  FTB Ranks), hazmat suits and gas masks (Mekanism and others), block logging (GriefLogger and others), computers
  (CC:Tweaked), guns and vehicles (Superb Warfare, TACZ, Create Big Cannons).

Rules for every item below (AGENTS.md "Mod boundaries"): loader channels first (NeoForge events, tags, data maps,
PermissionAPI, vanilla advancements and statistics); a mod-specific call only in an optional `compat/` module that
loads with that mod; settings stay causes, not consequences; nothing returns a damage or crater multiplier.

## 5. Proposals

Cost: S = up to a day with tests, M = a few days, L = a week or more. Each item lists what it enables, the mechanism,
the mods it lights up, and the risk.

### A. Signals out

**A1. Public events (M).** A small `ua.zentix.incoming.api` package of NeoForge events posted on
`NeoForge.EVENT_BUS` from the server thread:
- `StrikeOrderEvent` (cancellable, before munitions are charged): who or which launcher, weapon, count, target,
  nuclear yield. A pack can veto, add a cooldown or a cost, keep strikes out of a spawn zone.
- `StrikeLaunchEvent` per round: projectile id, weapon, owner, origin, target, salvo tag.
- `StrikeInterceptEvent`: SAM position and owner, weapon shot down, its owner.
- `StrikeImpactEvent`: position, weapon, owner, charge; after the blast is staged.
- `NuclearLaunchEvent` (cancellable) and `NuclearDetonationEvent`: position, yield, burst height.
- `GridOutageEvent` / `GridRestoreEvent`: node, radius.
Plain getters only, so that KubeJS (built-in `NativeEvents` binding subscribes to any NeoForge event) and Almighty
rules (scan all `Event` classes and serialize getters into the game master's feed) use them without plugins.
Lights up: KubeJS, Almighty, Java addons, minigame mods. Risk: low; events are API, so names and fields stay stable
once shipped (documented in `docs/dev/integration-reference.md`).

**A2. Command results and status (S).** Strike commands return the number of rounds launched; a new
`/incoming status [player|tag]` lists rounds in flight and pending nukes with ETA, returning the count; salvo and
nuke commands take an optional `tag <name>` that `clear` and the events carry. Lights up: `execute store`, datapack
minigames, command blocks, Almighty (it reads command output). Risk: low.

**A3. Advancements and statistics (S–M).** A few visible advancements tied to our mechanics (first strike, first
interception, a strike on a moving target through the camera, surviving a nuclear blast inside the light-damage
ring, a blackout restored) with custom criterion triggers, and custom statistics (rounds launched per weapon,
interceptions, nuclear detonations witnessed). Lights up: FTB Quests advancement and stat tasks, scoreboard criteria
`minecraft.custom:incoming.*`, the Almighty advancement feed, the vanilla advancement screen in survival.
Player-visible: guide update in both languages. Risk: low.

**A4. Redstone from sensing blocks (S).** The SAM gives a redstone signal while it tracks a hostile target (strength
by time to its arrival); the substation gives a signal while its district has power. Comparator outputs stay stock.
Lights up: vanilla redstone, Create doors and contraptions (a bunker door that closes on alarm, built from Create,
not from us), CC:Tweaked redstone, minigame maps. Risk: low.

### B. Rules in

**B1. Permission nodes (S–M).** Register NeoForge `PermissionNode`s in `PermissionGatherEvent.Nodes`:
`incoming.launch`, `incoming.launch.<weapon>`, `incoming.nuclear`, `incoming.launcher.link`,
`incoming.command.<branch>`. Defaults reproduce today's switches (`launch.designator_for_everyone`,
`nuclear.ops_only`), so nothing changes without a permission mod. Lights up: LuckPerms (registers itself as the
NeoForge permission handler; world contexts give per-dimension rights), FTB Ranks. Risk: low; one place resolves
rights (`ServerActions.mayUse`, `NuclearKeys`).

**B2. Claim protection for every kind of block damage (M–L).** One cancellable `StrikeBlockDamageEvent` per chunk
(level, chunk, owner, cause: crater, collapse, nuclear ruin, scorch) asked before a plan writes that chunk, plus the
existing `ExplosionEvent` path for conventional blasts. Optional `compat/` modules answer it through public APIs:
Open Parties and Claims (`IChunkProtectionAPI.onPosAffectedByAnotherPos`), FTB Chunks (`ClaimedChunkManager`).
A protected chunk keeps its blocks; the wave, light and sound still pass (the picture stays one model; only the
world change is withheld). Lights up: OPAC, FTB Chunks, KubeJS (veto by area), any claim mod that listens.
Risk: medium; ruin planning is batched per chunk already, so the check is one call per chunk, but every ruin path
must ask it (test: a protected chunk inside the 15 kt ring stays intact).

**B3. Player behind the blast (M).** Make claim mods and loggers resolve the launching player:
- carry the owner's UUID independent of online state into every damage path (debris, radiation, burn shock);
- give the explosion a source that vanilla resolves to the owner. Options to decide by probe: our projectile as a
  vanilla `Projectile` subclass (`getOwner()`), or the optional claim `compat/` modules passing the owner directly.
  Mixins into vanilla `Explosion` are ruled out (our rule against vanilla mixins).
Lights up: claim mods' owner rules (strike into your own claim), loggers (GriefLogger blames
`getIndirectSourceEntity()`), kill credit and PvP rules. Risk: medium (entity base class change touches flight and
networking; a probe first).

**B4. World rules by command (S).** `/incoming rule <key> [value]` reads and sets the server's world rules
(`world.block_damage`, `world.fire`, `nuclear.enabled`, …) at runtime, saved to the same config file. One home for
each setting; no duplicated gamerules. Lights up: minigame datapacks (no block damage in the arena round), command
blocks, Almighty. Risk: low.

**B5. Sides from party mods (M).** Air defense and spotting decide friend or foe through vanilla `/team`
(`J/target/Sides.java`). Post a `SideEvent` (are these two players on one side?) and answer it in optional
`compat/` modules for FTB Teams and OPAC parties. Lights up: FTB Teams, OPAC parties, KubeJS. Risk: low.

### C. Data in

**C1. Radiation shielding for items (M).** An item data map `incoming:radiation_shielding` (fraction 0–1 per armor
piece, summed per body) read by the radiation ticker; optional `compat/` reading Mekanism's
`RADIATION_SHIELDING` item capability, so Mekanism hazmat protects. Incoming ships no suit of its own. Lights up:
Mekanism, any armor mod through a datapack. Risk: low; dose stays one model.

**C2. Blackout lamps as data (M).** A block data map `incoming:unlit` (lit lamp → its dark state) replacing the
hard-coded list; our 19 twins become its default entries, packs add modded lamps that have a dark state. Lights up:
lamp and decoration mods through datapacks. Risk: medium (the grid's light update path).

**C3. Air-defense targets (M).** Entity type tags `incoming:air_defense_targets` (other mods' aircraft, drones,
missiles the SAM may engage) and `incoming:air_defense_ignore`. Lights up: Superb Warfare drones and missiles, Create
Big Cannons shells (if a pack wants), Create Aeronautics craft by choice. Risk: medium; radar and interceptor
guidance must handle ordinary entities (no virtual flight), with the same targeting law.

**C4. Common tags in recipes (S).** Recipes accept `c:` tags (`c:ingots/iron`, `c:ingots/copper`, `c:gunpowder`,
`c:storage_blocks/iron`) where an exact item is not essential. Lights up: every mod that tags its materials. Risk:
low; same counts.

**C5. Survival gaps (S).** A Geiger counter recipe. Pack-author documentation for the data that already exists:
`incoming:penetration` (make a mod's reinforced concrete a real bunker), the nuclear block classes, ignition tags.

### D. Optional bridges

**D1. CC:Tweaked peripheral (M).** In `compat/computercraft`: the fixed launcher (status, stock, set mission, fire),
the SAM (stock, tracks), and a computer event when an alert covers the computer. Registered through CC:Tweaked's
`PeripheralCapability`. Lights up: ComputerCraft and everything automated through it. Risk: low.

**D2. Jade plugin (S).** Hover over a launcher (mission, stock, owner, why it cannot fire), a SAM (stock, rails,
side), a substation (district power), a projectile (weapon, owner). Lights up: Jade, the information mod most packs
carry. Risk: low.

**D3. Create Display Link source (M, later).** Air-raid state and launcher stock on Create display boards.

### E. Almighty

The game master already reaches Incoming through commands at level 4 and the note `strikes.md`; it cannot see
player launches, impacts, interceptions or detonations. A1 and A2 close this without Almighty changes: Almighty
rules subscribe to any event class and put its getters into the feed. Add to the note: the grid, radiation and
launcher owner commands, `status` and `tag`, and two ready rule recipes (a raid wave with its end detected by
`status`, a reaction to `NuclearDetonationEvent`). Cost S after A1/A2.

### F. Later, with care

- **Component items for progression (M, player-visible):** guidance unit, rocket motor, warhead core as
  intermediate items used by several recipes, so a pack gates all guided weapons by changing one recipe (Create
  sequenced assembly, Mekanism, Immersive Engineering). Changes existing recipes for current players.
- **Data-driven munition variants (L):** pack-defined variants of our airframes and warheads. Weapon numbers live in
  Java `WeaponSpec` with tight coupling to the client (`WeaponType` ordinal); real weapons keep real specs.
- **Launch from Create contraptions and Create Aeronautics craft (L):** the fixed launcher refuses to fire from a
  craft today.
- **KubeJS event group (S, after A1):** named `IncomingEvents.*` for nicer scripts; `NativeEvents` already works.

## 6. Play scenarios

1. **Survival with Create.** A player crafts the designator and Shaheds with Create parts, automates the munition
   line with mechanical crafters, feeds fixed launchers through chutes, fires them with a lever. Advancements (A3)
   mark the steps; a quest pack builds its chapter from them and from statistics. The Geiger counter (C5) is
   craftable; a Mekanism hazmat suit (C1) helps in fallout. Uses: A3, C1, C4, C5, today's launcher.
2. **PvP server with claims.** LuckPerms gives Grad and Shahed to everyone, cruise missiles to a rank, the nuke to
   admins (B1). Bases in OPAC claims are safe from conventional strikes (works today through `ExplosionEvent`) and from
   nuclear ruins (B2); a war season unlocks strikes on claims through the claim mod's own explosion option. Parties
   count as one side for the SAM (B5); kills credit the launcher (B3). A KubeJS script adds a cooldown on
   `StrikeOrderEvent` (A1). Uses: A1, B1–B3, B5.
3. **Minigame "survive the raid".** The map maker places owner-less fixed launchers with commands, a function starts
   waves `/incoming salvo … tag wave3`, waits until `/incoming status tag wave3` returns 0, then starts the next.
   Players build cover from map blocks; SAMs give redstone when tracking (A4) and points come from an interception
   statistic (A3) on the scoreboard. `/incoming rule world.block_damage false` keeps the arena reusable (B4).
   Uses: A2–A4, B4.
4. **Create-automated launch site.** A ComputerCraft computer reads the SAM and launcher peripherals (D1), assigns
   missions and fires a battery; a Create bunker door closes on the SAM's alarm signal (A4); Jade shows why a launcher
   is not firing (D2). Uses: A4, D1, D2.
5. **Game master event through Almighty.** The GM announces a war, runs raid waves by tag, watches the feed for
   `StrikeImpactEvent` and `StrikeInterceptEvent`, knocks out a substation for a blackout night, rewards the side that
   shot down most rounds (statistic), and ends the evening with a warned nuclear strike outside the town. Uses: A1–A3,
   E.

## 7. Roadmap

First wave (small, high value; together roughly one to two weeks):
1. A1 public events.
2. A2 command results, `status`, `tag`.
3. B1 permission nodes.
4. A3 advancements and statistics.
5. A4 redstone from the SAM and the substation.
6. B4 world rules by command.
7. C4 common tags in recipes, C5 Geiger counter recipe and pack-author documentation.
8. E Almighty note update.

Second wave:
9. B2 claim protection for all block damage, with OPAC and FTB Chunks compat.
10. B3 player behind the blast.
11. C1 radiation shielding data map, Mekanism compat.
12. D2 Jade, D1 CC:Tweaked.
13. B5 sides from party mods.
14. C2 blackout lamps as data, C3 air-defense targets.

Later: section 5F, D3.

A public page "For server owners and pack makers" in the guide (both languages) grows with each shipped item.

## 8. We do not do

- **Bunkers, shelters, blast doors:** building mods and Create already make them; the penetration data map makes
  their materials count against our weapons.
- **Radios, alert networks, voice:** Simple Voice Chat and its addons; we give redstone, events and a computer API.
- **Teams, factions, parties, claims:** vanilla teams, FTB Teams, OPAC, FTB Chunks; we read their answers.
- **Economy, shops, munition prices:** economy mods and KubeJS through `StrikeOrderEvent`.
- **Our own quests or progression trees:** quest mods use our advancements and statistics.
- **Hazmat suits, gas masks, medicine:** armor and medical mods through the shielding data map.
- **Cooldown and cost sliders, per-weapon limits in config:** permission nodes and `StrikeOrderEvent` cover them for
  those who want them, without new settings.
- **Damage or crater multipliers, "game-size" blasts:** real charges by design (AGENTS.md concept); a pack that wants
  stronger blocks or weaker blocks changes block data (penetration, nuclear classes), which is a cause.
- **HBM-style production chains:** component items (5F) let a pack plug us into its own chains instead.
- **Code for specific mods in the main code:** only optional `compat/` modules through public APIs.

## 9. Sources

Player feedback:
- CurseForge comments: https://www.curseforge.com/minecraft/mc-mods/incoming/comments
- Review video: https://www.youtube.com/watch?v=WSElk2YKfUU (quotes linked by comment id in the mission report)
- GitHub issue #656: https://github.com/zentixua/incoming/issues/656

Other mods' interop (source on GitHub, 1.21.1 branches):
- Create API (`api/registry/CreateRegistries.java`, `compat/computercraft/`): https://github.com/Creators-of-Create/Create
- Create Big Cannons (`munition_properties`, `block_armor`, `events/ProjectileDamageEvent.java`):
  https://github.com/Cannoneers-of-Create/CreateBigCannons
- Superb Warfare (`api/event/`, `compat/kubejs/`, `compat/jade/`): https://github.com/Mercurows/SuperbWarfare
- TACZ (`api/event/common/`, gun packs): https://github.com/MCModderAnchor/TACZ
- Ballistix (`api/event/BlastEvent`, `api/missile/MissileManager`): https://github.com/aurilisdev/Ballistix
- Mekanism (`api/radiation/IRadiationManager`, `RADIATION_SHIELDING` capability): https://github.com/mekanism/Mekanism
- Immersive Engineering (`api/wires/redstone/`, `cctweaked/`): https://github.com/BluSunrize/ImmersiveEngineering
- HBM's Nuclear Tech (`api/hbm/entity/IRadarDetectableNT`, `HazmatRegistry`): https://github.com/HbmMods/Hbm-s-Nuclear-Tech-GIT
- CC:Tweaked (`PeripheralCapability`, `ComputerCraftAPI`): https://github.com/cc-tweaked/CC-Tweaked
- KubeJS (`NativeEvents`, `EventGroup`): https://github.com/KubeJS-Mods/KubeJS (branch 2101)
- Jade (`IWailaPlugin`): https://github.com/Snownee/Jade
- Open Parties and Claims (`ChunkProtection.onExplosionDetonate`, `IChunkProtectionAPI`):
  https://github.com/thexaero/open-parties-and-claims
- FTB Chunks (`explosionDetonate`, `ClaimedChunkManager`): https://github.com/FTBTeam/FTB-Chunks
- FTB Quests (advancement, stat and command task/reward types): https://github.com/FTBTeam/FTB-Quests
- NeoForge (`PermissionAPI`, `DataMapType`, `ExplosionEvent`, `FakePlayerFactory`): https://github.com/neoforged/NeoForge
- LuckPerms NeoForge permission handler: https://github.com/LuckPerms/LuckPerms
- Almighty (rules on any event class, notes, feed): https://github.com/zentixua/almighty
