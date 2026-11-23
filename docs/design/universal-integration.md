# Incoming as a building block: universal integration (roadmap)

Status: approved by Artem 23.11.2026 as the long-term plan; done item by item, one PR each, in roadmap order.
Order and finished items: the table in section 7. Items under way: the tracking issue zentixua/incoming#739.
Each item's PR writes its contract out first, with tests and guide updates. Code references are `path:line` against
`main` at the time of writing; `J/` is `mod/src/main/java/ua/zentix/incoming/`, `D/` is
`mod/src/main/resources/data/`.

## 1. Verdict

Incoming owns the phenomenon: launch, flight, air defense, the blast, the nuke and its aftermath (fallout, fires,
blackout). It does not grow bunkers, radios, factions, economy or quests. It becomes universal by speaking the
languages packs already speak, so that other mods, datapacks, scripts and a game master can see what happens and set
the rules:

- **Signals out**: events with stable order and round identifiers; advancements and statistics; command results;
  redstone from the blocks that sense something.
- **Rules in**: permission nodes behind one authorization policy; claim protection for every path that changes
  blocks; world rules settable by command.
- **Data in**: tags and data maps for what protects (bunker materials, radiation shielding) and which ingredients a
  recipe accepts.
- **Optional bridges**: small `compat/` modules for the few mods that need an API call (ComputerCraft, Jade,
  Mekanism, party mods), loaded only with that mod.

The largest gaps today: Incoming has no events of its own with order and owner identity, its block damage is only
partly visible to claim mods (nuclear ruins and several conventional side effects not at all), and there are no
permission nodes or statistics. Closing them unlocks servers, minigames and the Almighty game master at once. Each
item needs a written contract before code (section 5 names the hard parts). The order in section 7 is fixed; the
cost estimates in section 5 are tentative until each contract is written.

## 2. What players say

Sources reached: CurseForge comments (34 returned by its API; the site counts 41), YouTube comments (316, of which
307 under one review video, "Minecraft's Deadliest Nuke Mod? (Incoming 1.21.1)" by Dave Kratzy, 315k views), player
GitHub issues (1). Modrinth returns 404 for the project (no public listing visible to its API); Reddit blocks
unauthenticated access. Themes were coded by hand; counts are approximate and one comment can carry several themes.

| Theme | Count | Representative quote |
|---|---|---|
| Visuals and sound praised | ~24 | "sick particle effects though" (135 likes) |
| Compared with HBM's Nuclear Tech, Kiloton and others | ~25 | usually as the deeper mod |
| Conventional damage too small | ~20 | "all bark no bite. INSANE graphics but the damage is way too small" (327 likes) |
| Another version or loader (1.20.1 Forge, Fabric) | 15 | "1.20.1 forge PLS i need this for my war modpack" |
| Combine with other mods | ~15 | Superb Warfare (4), Create/Aeronautics (4), TACZ (3), physics mod (3) |
| Performance | ~14 | a freeze after a nuclear detonation (GitHub #656) |
| Servers, SMP, PvP | ~8 | "Somebody should make a smp with that mod" |
| How to use it | ~8 | "I just didn't have any clue how to use it" |
| Survival, crafting | ~5 | reply comment by @DaveKratzy: "fun to mess with in creative mode but I do not need to fill my survival world with smoke"; "crafts are too easy… HBM's NTM adds whole process lines" |
| Minigames, events | ~3 | "Civilization videos are drooling rn" (top comment, 262 likes) |

What the sample shows:
- No sampled comment reports trying survival and giving up; the "creative only" impression comes from the reviewer's
  reply and from comparisons with HBM, where weapons sit at the end of a long production chain. The sampled requests
  are to put Incoming next to other mods (Superb Warfare, Create, TACZ) and on a server with friends ("civilization"
  videos, "war modpack").
- No sampled comment mentions permissions, claims or admin controls. That is a finding about these comments, not
  proof that server owners do not need them; a server owner meets the all-or-nothing switches in section 3.
- The damage complaint is outside this proposal: blasts carry real charges by design (AGENTS.md concept, no damage
  sliders). Section 8 notes where it touches integration.
- Most YouTube comments react to one showcase, not to play; Discord, Telegram, TikTok and Russian-language video
  were not searched.

## 3. Where Incoming is a closed box today

What already connects:
- The main charge of a conventional warhead builds a real vanilla `Explosion` and fires `ExplosionEvent.Start` and
  `Detonate` (`J/warhead/StagedExplosion.java:428-433`, `:583`). A cancelled Start vetoes that staged explosion and the
  follow-up jobs gated on it: the surface crater, impact ignition and fuel fire, debris (`J/warhead/Warheads.java:
  644-670`). Collapse offers its falling positions as `Detonate` events (`J/warhead/StrikeCollapse.java:421-435`).
- Own damage types in vanilla tags (`D/minecraft/tags/damage_type/`): strike, shockwave and nuclear blast are
  `is_explosion` (Blast Protection applies), nuclear thermal is `is_fire`; radiation, thermal, shockwave and burn
  shock bypass armor; burn shock also bypasses effects; debris damages helmets. Death messages are localized.
- Recipes are datapack JSON. The Shahed, Lancet, cruise missile, ICBM, interceptor, fixed launcher and SAM have a
  Create variant and a vanilla fallback through `neoforge:mod_loaded`; the rest are vanilla-only. Create mechanical
  crafters build them.
- Fixed launcher: insert-only item handler (hoppers, Create chutes), fires its mission on a rising redstone edge,
  comparator reads the fullness of its exposed magazine slots (`J/launcher/FixedLauncherBlock.java:89-103`, `:149`).
  SAM: item handler and a comparator on its magazine. A launcher placed by a non-player has no owner; only an
  operator can give it a mission, and it then fires without player rules: a ready tool for map makers.
- Data: block tags for nuclear wave classes, ignition, glass, passable blocks; data map `incoming:penetration` for
  how far bombs and charges get through each block (bunker materials); entity tag `aim_ignore`.
- Sides: identity plus vanilla `/team` (`J/target/Sides.java`).
- Commands: `/incoming` covers single strikes, salvos with routes, nukes, launchers, the grid and radiation; console
  and command blocks pay no munitions.
- Almighty note `D/incoming/almighty/notes/strikes.md`: strike, salvo, nuke and launcher syntax (with owner),
  `clear`, campaigns, load advice, early warning.

Closed points, most blocking first:
1. **No semantic events.** Incoming posts no event of its own. Other mods see partial signals (vanilla explosion
   events of the main charge, entity joins of materialized rounds, deaths), without order or owner correlation and
   missing virtual flights and delayed work. A designator launch arrives as a network packet, so Almighty's command
   feed does not see it.
2. **Block damage only partly visible to claims.** Claim mods filter the `Detonate` lists. Several conventional
   writes do not go through those lists: the glass job (it runs even after a cancelled Start), surface ignition and
   fuel fire, fragment material changes, bunker cavity and fracture work (`J/warhead/Shatter.java:268-306`,
   `J/warhead/Craters.java:194-226`, `J/warhead/FragmentStrike.java:247-267`). Nuclear ruins, flash scorch and the
   nuclear crater write chunk sections directly with no event at all (`J/nuclear/world/RuinPlan.java:818-946`,
   `FlashScorch.java:510-535`, `CraterJob.java:280-384`). A server owner can only switch nuclear damage off for all.
3. **Owner identity is fragile.** The explosion's source entity is the projectile, which extends `Entity`, not
   vanilla `Projectile` (`J/entity/StrikeProjectile.java:76`), so vanilla `Explosion.getIndirectSourceEntity()`
   resolves no player. Blast damage carries the owner only when the owner is online in the same level
   (`J/warhead/Warheads.java:601`; the bunker buster's kinetic hit looks the owner up server-wide); debris, fatal radiation sickness, burn shock and catch-up nuclear exposure carry
   none.
4. **No permission nodes.** Launch rights are global switches (`launch.designator_for_everyone`, `nuclear.ops_only`)
   plus the nuclear gates (feature flag, second key, trust of operators and the host).
5. **Strike commands return 1 or 0** (accepted or not). Other branches return counts (`clear`, `give`, `radiation`,
   `launcher … load`, grid). There is no way to ask what is queued or in flight.
6. **No gameplay advancements or statistics.** The 19 shipped advancements are hidden recipe unlocks; no custom stats.
7. **No item protection from radiation.** Shielding is block and fluid mass along rays (density from a heuristic on
   sound type, leaves, ice, snow, glass, shape and liquid; `J/nuclear/radiation/Shielding.java:50-177`); no armor or
   item input, so a pack cannot declare a hazmat suit.
8. **Air defense engages only Incoming projectiles** (`J/defense/Radar.java`) and gives no redstone while engaging.
9. **Blackout covers 19 vanilla lamp types** with dark twin blocks (`J/registry/ModBlocks.java:75-94`).
10. **Survival gaps.** The Geiger counter has no recipe; no recipe uses common `c:` tags.
11. **World rules are config only.** A datapack or command block cannot change them for one arena round.

## 4. Principle: what stays ours, what belongs to the pack

Ours, because it is the phenomenon or tied directly to it:
- Weapons, launch and targeting (designator, fixed launcher, spotting rules), flight, air defense (SAM).
- Blast physics, crater, collapse, fires at our sources, debris; the nuke with flash, wave, cloud, ruins, fallout,
  radiation dose and sickness; blackout of the grid we knock out; early warning and sirens.
- The crafting of our own items, and the Geiger counter that reads our radiation.

The pack's, through other mods:
- Bunkers and doors (vanilla, Create, building mods; our `incoming:penetration` data map makes their materials
  count), radios and voice (Simple Voice Chat and its addons), teams and factions (vanilla teams, FTB Teams, Open
  Parties and Claims), claims, economy and shops, quests (FTB Quests and others), ranks and permissions (LuckPerms),
  hazmat suits and gas masks (Mekanism and others), block logging, computers (CC:Tweaked), guns and vehicles (Superb
  Warfare, TACZ, Create Big Cannons).

Rules for every item (AGENTS.md "Mod boundaries" and concept): loader channels first (NeoForge events, tags, data
maps, PermissionAPI, vanilla advancements and statistics); a mod-specific call only in an optional `compat/` module
that loads with that mod; no vanilla mixins; settings stay causes, not consequences; nothing returns a damage or
crater multiplier. Events are posted only on the server thread, never from worker phases. A PR that changes packet
or entity-data formats bumps `IncomingNetwork.PROTOCOL`; persisted fields get old-save
defaults; a release whose behavior or lang keys differ from the last shipped jar bumps it too.

How the popular mods do it (from their 1.21.1 source unless noted): Create Big Cannons keeps munition and block-armor
numbers in datapacks and posts a cancellable per-block projectile damage check; Superb Warfare, TACZ and Ballistix
post their own shot, hit and blast events, and Superb Warfare exposes weapon data to KubeJS and Jade; Mekanism
exposes radiation shielding as an item capability any armor can provide, and computer methods for CC:Tweaked; Create,
Immersive Engineering and Mekanism drive their blocks from CC:Tweaked and redstone; HBM's Nuclear Tech (Forge 1.7.10
original) lets other mods' missiles appear on its radar. None of them ships its own claims, teams or economy.

## 5. Proposals

Cost: S = up to a day with tests, M = a few days, L = a week or more.

### A. Signals out

**A1. Order and round events (M–L).** A small `ua.zentix.incoming.api` package of NeoForge events on
`NeoForge.EVENT_BUS`, posted on the server thread. The contract follows one order lifecycle, the same for every
entry (designator packet, command, fixed launcher, queued second-key and warning orders, immediate operator burst):
- `StrikeOrderEvent.Request` (cancellable, validation only): order UUID, actor (player, launcher, command source),
  weapon, count, target, nuclear yield. Listeners decide yes or no here and debit nothing. Hard nuclear gates
  (feature flag, second key, trust) stay separate from this veto.
- `StrikeOrderEvent.Rejected` for every request that is not admitted (vetoed, short of munitions, refused by the
  world), so no request is left without an answer.
- `StrikeOrderEvent.Accepted` after the order is recorded: the admitted round count (from the clamped loadout) and
  the payment mode: paid upfront (player salvo), paid per release from the launcher magazine (fixed launcher, as
  today), or waived (console, operator).
- `StrikeRoundEvent.Released` per round: round UUID (stable across virtual flight and rematerialization; the world
  entity is optional), order UUID, owner UUID, origin, target, optional tag.
- `StrikeRoundEvent.Ended` per round: one terminal outcome: impact (position, charge), intercepted (SAM position and
  owner), failed or recalled (refunded or not).
- `NuclearDetonationEvent`: position, yield, burst, order UUID. For a nuclear order the authoritative round is the
  saved scheduled strike, which carries the round UUID; the booster entity, if any, is only its visible part, and
  timer-only or no-flight bursts (`nuke now`) still get `Released` and `Ended`.
- `GridOutageEvent` and `GridRestoreEvent`: outage UUID, dimension, centre, radius, optional node; posted when the
  outage is accepted and when restoration completes.
Each request ends in exactly one `Rejected` or `Accepted`, each admitted round in exactly one `Ended`. A pack that
charges its own cost or cooldown checks in `Request`, debits in `Accepted` and settles in `Ended`; Incoming promises no
rollback of foreign debits. Listeners must not start a new order synchronously inside `Request`; a nested order is
queued to the next tick. Getters return immutable values (primitives, UUIDs, strings, enums, `Vec3`, `BlockPos`, the
dimension key), never live game objects.
Lights up: KubeJS (its built-in `NativeEvents` subscribes to any game-bus event), Almighty (explicit rules subscribe
to discoverable game-bus event classes and serialize public getters; a rule disables itself above 200 emits in
20 ticks, so per-round events suit it and bulk block events do not), Java addons. Risk: medium; the order paths are
several and some state is persisted (`SalvoData`, virtual flights, scheduled nuclear strikes), so the PR first writes
the lifecycle down and probes a tagged ICBM during boost, after burnout and after restart, a `nuke now` waiting for
its chunk, a fixed-launcher shot that fails after admission, and a veto by a second listener after a first one.

**A2. Command results, status and tags (S–M).** Strike commands return the admitted round count (the salvo
launches over later ticks, so this is not the launched count; one ICBM counts once) and store the order UUID in feedback. New
`/incoming status [player|tag]` reports orders by phase: queued (including unlaunched rounds and pending keys),
in flight, impact work in progress; it returns the number of unfinished rounds (impact work counts until the round's own jobs finish; nuclear ruin
work that resumes on a later chunk load does not), and ETA shows "unknown" where the
scheduler or a second key cannot predict it. Salvo and nuke commands take `tag <name>`; `clear tag <name>` recalls
it. A tag is a world-wide group name: several orders may share it; it persists with the order; owner rules for
`clear` stay. Lights up: `execute store`, minigame datapacks,
command blocks, Almighty (it reads command output). Risk: low once A1's lifecycle exists.

**A3. Advancements and statistics (S–M).** A few visible advancements tied to our mechanics (first strike, first
interception, a strike guided through the projectile camera, surviving a nuclear blast close in, a restored
blackout) with custom criterion triggers, and custom statistics (rounds launched per weapon, interceptions, nuclear
detonations witnessed). Credit goes to the owner when online; offline and ownerless automation gives none.
Lights up: FTB Quests advancement and stat tasks (command rewards too), scoreboard criteria
`minecraft.custom:incoming.*`, the Almighty advancement feed, the vanilla advancement screen. Player-visible: guide
in both languages. Risk: low.

**A4. Redstone from sensing blocks (S).** The SAM gives a redstone signal while it tracks a hostile target (on/off;
radar tracks have distance and hostility but no time to arrival); the magazine comparator stays as it is. The
substation gives a signal while its node is not in outage, from the authoritative grid state, notifying neighbors on
change. Lights up: vanilla redstone, Create doors and contraptions (a bunker door that closes on alarm, built from
Create), CC:Tweaked redstone, minigame maps. Risk: low.

### B. Rules in

**B1. Permission nodes behind one policy (M).** One authorization policy with action, effective actor, automation
identity and level, used by every entry: designator packet, player commands, queued orders, fixed launcher (owner
by UUID, possibly offline), launcher linking, second key. Register NeoForge `PermissionNode`s
(`incoming.launch`, `incoming.launch.<weapon>`, `incoming.nuclear`, `incoming.launcher.link`) whose defaults
reproduce today's switches, so nothing changes without a permission mod. The nuclear feature flag, second key and
host trust stay hard gates on top. Offline owners resolve only from the handler's cache or fall back to the node
default; no synchronous storage lookup on the server tick. Lights up: LuckPerms when it is the active NeoForge
permission handler (it installs itself when the default handler is selected; dimension contexts give per-dimension
rights). FTB Ranks does not install a NeoForge handler on 1.21.1, so it needs a separate optional bridge, not
included. Risk: medium (many entry points; one test per entry).

**B2. Claim protection for every block-changing path (L).** Goal: a chunk where the claim mods forbid explosion
damage keeps its blocks against every Incoming write, conventional and nuclear; the wave, light, sound and entity
effects stay one model and only the world write is withheld. Today only the real charges' `Start`/`Detonate` and the
collapse candidates are asked. The mechanism is decided in its own design PR with probes; this proposal fixes the
requirements it must meet:
- every writer is covered or explicitly excluded: conventional crater, glass, ignition and fuel fire, fragments,
  bunker boring, cavity and fracture, collapse landing, debris placement, flash scorch and biome recolor, ruins,
  nuclear crater, distant LOD copies; grid outages are not block damage and stay outside the claim policy;
- a real charge keeps its own `Start` veto; extra writers do not reopen a chunk that the real `Detonate` denied;
- candidate events that are not physical explosions are kept apart from listeners that react to explosions (our own
  blackout listener, effect mods), and a listener can never make Incoming read an unloaded chunk on the tick;
- `Detonate` filtering is position-level for an arbitrary listener; a per-chunk representative is valid only for the
  documented chunk policy of OPAC and FTB Chunks, and FTB Chunks protects against a sourceless explosion only with
  its unknown-explosion protection on (operator `nuke now` has no source entity);
- decisions are taken on the server thread inside the work budget and stored per detonation and chunk, durable across
  restart, shared by live, settlement, crater and LOD paths, so a disk-only chunk denied for a LOD copy is not ruined
  later on load, and another detonation is decided on its own.
Candidates: candidate `Detonate` batches as collapse uses, a dedicated `StrikeTerrainEvent` with optional claim
`compat/` modules answering through public APIs, or both. OPAC's `IChunkProtectionAPI.onPosAffectedByAnotherPos` is
not used: it answers a different, anonymous cross-chunk question. Risk: high: many writers, LOD consistency, volume
(a 15 kt scorch reach covers hundreds of thousands of chunks before filtering). Tests: a protected chunk at each
writer, a claim change between flash and wave, a denied disk-only chunk after restart.

**B3. Owner identity (M).** Three separate contracts:
- persistent strike attribution: the owner UUID on orders, rounds and events (A1), independent of online state;
- live combat credit: vanilla damage sources carry the owner entity when the owner is online, in any level (today the
  blast looks only in the same level), for debris and burn paths that have one cause; merged dose and injuries stay unattributed;
- claims and logs: claim mods decide explosions by claim policy, not by owner (FTB Chunks ignores the owner; OPAC
  uses its explosion option), so this item makes no claim or logger promise.
Making the projectile a vanilla `Projectile` is not proposed: it changes owner persistence, game events, spawn
packets and foreign `instanceof Projectile` handling for little gain. Risk: medium.

**B4. World rules by command (S).** `/incoming rule <key> [value]` (permission level 2) reads and sets
runtime-safe world rules and saves them to the same config file; one home per setting, no duplicated gamerules.
A change applies to orders accepted after it; queued and flying rounds keep the rules they were accepted under. The
change is world-wide and survives restart. The guarantee is for conventional strikes: with `world.block_damage`, `world.shatter_glass`,
`world.fire`, `world.debris_stay` and `world.collapse` off and `grid.enabled` off (blasts near a substation otherwise
cause an outage), an arena keeps its blocks. Nuclear orders are excluded from that guarantee (`nuclear.enabled` off):
the flash still scorches, ignites and recolors biomes under `nuclear.fires`, and nukes have their own blackout. The
guide lists exactly what each rule governs. Lights up: minigame datapacks, command blocks,
Almighty. Risk: low.

**B5. Sides from party mods (M).** Spotting shares contacts by a side key, and air defense compares sides
pairwise (`J/target/Sides.java`, `J/target/Sightings.java:148-159`). Replace both with one side resolution: a
`SideResolveEvent` that returns a stable side key for a player UUID (vanilla team by default), cached and
invalidated on team change, symmetric by construction. Optional `compat/` modules answer it from FTB Teams
(`TeamManager` by player UUID) and OPAC parties (`IPartyManagerAPI`) with provider-namespaced keys, and abstain for a
personal or absent membership (FTB Teams returns a personal team for every player). With several real shared
memberships the precedence is documented; the same key serves spotting, radar, targeting and launcher links, also for
offline owners. Lights up: FTB Teams, OPAC, KubeJS. Risk: medium (radar
budget, old contacts on party change).

### C. Data in

**C1. Radiation shielding for items (M).** An item data map `incoming:radiation_shielding` (fraction per worn
piece, each in [0, 1], rejected otherwise). The worn fractions add up, capped at 1, as Mekanism combines its own
equipment, so a full Mekanism hazmat set protects fully here too; they apply to every exposure Incoming gives
a living body: prompt dose, fallout and black rain. Protection applies to new exposure only, never to the
accumulated dose. An optional `compat/mekanism` reads Mekanism's `RADIATION_SHIELDING` item capability through its
public interface; when both describe an item, the data map wins. Incoming ships no suit or medicine. Lights up:
Mekanism hazmat, any armor through a datapack. Risk: low; dose stays one model.

**C2. Common tags in recipes (S).** Accept `c:ingots/iron`, `c:ingots/copper`, `c:gunpowders`,
`c:storage_blocks/iron` where the exact item is not essential, resolving each tag against the default datapack with
and without Create. Lights up: every mod that tags its materials. Risk: low; same counts.

**C3. Survival gaps (S).** A Geiger counter recipe. Pack-author documentation for existing data:
`incoming:penetration` (make a mod's reinforced concrete a real bunker), the nuclear block classes, ignition tags.

### D. Optional bridges

**D1. CC:Tweaked peripheral (M).** In `compat/computercraft`, registered through `PeripheralCapability`: the fixed
launcher (status, stock, fire its existing mission) and the SAM (stock, tracking state). Lua calls run on the main
thread; firing goes through the same order validation, owner rules and work budget as a redstone signal; changing a
mission is not exposed (it is an operator or designator action). A computer attached to a SAM receives an
`incoming_alert` event while it tracks; no location-wide broadcast. Lights up: ComputerCraft and automation built
on it. Risk: low.

**D2. Jade plugin (S–M).** Server data providers send what the client does not have: launcher mission, stock,
owner and why it cannot fire; SAM stock and tracking; substation outage state; a projectile's weapon and owner.
Owner and mission are shown to the owner's side and operators only. Lights up: Jade. Risk: low.

### E. Almighty

The game master reaches Incoming through commands at level 4 and the note `strikes.md`; it does not see designator
launches, rounds, interceptions or detonations as events. After A1 and A2 an explicit Almighty rule subscribes to the
round and detonation events with no Almighty change. Add to the note: the grid and radiation commands, `status`,
`tag`, and two rule recipes (a raid wave whose end is detected by `status tag`, a reaction to
`NuclearDetonationEvent`), with the advice that bulk events need a filter or a script. Cost S after A1/A2.

### F. Later, with care

- **Component items for progression (M, player-visible):** guidance unit, rocket motor, warhead core as
  intermediate items used by several recipes, so a pack gates all guided weapons by changing one recipe (Create
  sequenced assembly, Mekanism, Immersive Engineering). Changes existing recipes for current players.
- **Blackout of modded lamps (M):** an opt-in, reversible state pairing (lit state ↔ dark state) validated for
  property preservation, save and load and data reload; the 19 twins stay. Only lamps whose mechanics survive the
  pairing.
- **Foreign air-defense targets (L):** an entity tag is only eligibility; each supported family (for example Superb
  Warfare drones and missiles, in optional compat) needs discovery in ready chunks, owner and side, kinematics,
  signature and a hit contract. Create Aeronautics craft are Sable sub-levels, not entities, and stay out.
- **Create Display Link source (M):** air-raid state and launcher stock on Create display boards, in optional compat.
- **Data-driven munition variants (L):** a separate design decision under the real-product rules: weapon numbers
  live in `WeaponSpec`, client ids follow `WeaponType` ordinals, real weapons keep real specs.
- **Launch from Create contraptions and Create Aeronautics craft (L):** the fixed launcher refuses on a craft today.
- **KubeJS event group (S, after A1):** named `IncomingEvents.*`; `NativeEvents` already works without it.

## 6. Play scenarios

1. **Survival with Create.** A player crafts the designator, then Shaheds whose recipe uses Create propellers,
   automates the munition line with mechanical crafters, feeds fixed launchers through chutes, fires them with a
   lever. Advancements (A3) mark the steps; a quest pack builds a chapter from them and from statistics. The Geiger
   counter is craftable (C3); a Mekanism hazmat suit lowers fallout dose (C1). Uses: A3, C1–C3.
2. **PvP server with claims.** LuckPerms gives Grad and Shahed to everyone, cruise missiles to a rank, the nuke to
   admins (B1). Claimed bases keep their blocks against every Incoming write (B2); a war season enables explosions in
   claims through the claim mod's own option. Party members share spotting and count as one side for the SAM (B5);
   kills by an online owner credit them (B3). A KubeJS script adds a cooldown in `StrikeOrderEvent.Request` (A1).
   Uses: A1, B1–B3, B5.
3. **Minigame "survive the raid".** The map maker places owner-less fixed launchers by command, a function starts a
   wave `/incoming salvo … tag wave3`, waits until `/incoming status tag wave3` returns 0, then starts the next.
   Players build cover from map blocks; SAMs give redstone while tracking (A4); points come from the interception
   statistic (A3) on the scoreboard. The conventional world rules and the grid are switched off by command for the
   arena, and nuclear orders are off (B4). Uses: A2–A4, B4.
4. **Create-automated launch site.** A ComputerCraft computer reads the SAM and launcher peripherals and fires a
   battery when the SAM tracks (D1); a Create bunker door closes on the SAM's redstone (A4); Jade shows why a launcher
   is not firing (D2). Uses: A4, D1, D2.
5. **Game master event through Almighty.** The GM announces a war, runs raid waves by tag, follows round outcomes in
   its feed, knocks out a substation for a blackout night, rewards the side with the most interceptions (statistic),
   and ends the evening with a warned nuclear strike outside the town. Uses: A1–A3, E.

## 7. Roadmap

| # | Item | Status | PR |
|---|---|---|---|
| 1 | A1 order and round events | done | #741 |
| 2 | A2 command results, `status`, `tag` | done | #744 |
| 3 | B1 permission nodes behind one policy | done | #745 |
| 4 | A3 advancements and statistics | done | #746 |
| 5 | A4 redstone from the SAM and the substation | done | #747 |
| 6 | B4 world rules by command | done | #748 |
| 7 | C2 common tags in recipes, C3 Geiger counter recipe and pack-author documentation | planned | |
| 8 | E Almighty note update | planned | |
| 9 | B2 claim protection for every block-changing path | planned | |
| 10 | B3 owner identity | planned | |
| 11 | C1 radiation shielding, Mekanism compat | planned | |
| 12 | D2 Jade, D1 CC:Tweaked | planned | |
| 13 | B5 sides from party mods | planned | |
| later | 5F Component items for progression | planned | |
| later | 5F Blackout of modded lamps | planned | |
| later | 5F Foreign air-defense targets | planned | |
| later | 5F Create Display Link source | planned | |
| later | 5F Data-driven munition variants | planned | |
| later | 5F Launch from Create contraptions and Create Aeronautics craft | planned | |
| later | 5F KubeJS event group | planned | |

Items 1–8 are the first wave (small or foundational, high value), 9–13 the second wave, "later" the items of
section 5F; each is done in table order. A1's lifecycle comes first, since A2, D1 and E build on it. One item = one
mission = one PR. When the item's PR opens, it links itself on the item's line in zentixua/incoming#739; the PR sets
its row here to done with its link and ticks the box when it merges. Status is planned or done. The second wave
starts only after Artem's word.

A guide page "For server owners and pack makers" (both languages) grows with each shipped item.

## 8. We do not do

- **Bunkers, shelters, blast doors:** building mods and Create make them; the penetration data map makes their
  materials count against our weapons.
- **Radios, alert networks, voice:** Simple Voice Chat and its addons; we give redstone, events and a computer API.
- **Teams, factions, parties, claims:** vanilla teams, FTB Teams, OPAC, FTB Chunks; we read their answers.
- **Economy, shops, munition prices:** economy mods and scripts through `StrikeOrderEvent.Request`.
- **Our own quests or progression trees:** quest mods use our advancements and statistics.
- **Hazmat suits, gas masks, medicine:** armor and medical mods through the shielding data map.
- **Cooldown and cost sliders, per-weapon limits in config:** permission nodes and the order event cover them for
  those who want them, without new settings.
- **Damage or crater multipliers, "game-size" blasts:** real charges by design (AGENTS.md concept); a pack that wants
  different results changes block data (penetration, nuclear classes), which is a cause.
- **HBM-style production chains:** component items (5F) let a pack plug us into its own chains instead.
- **Code for specific mods in the main code:** only optional `compat/` modules through public APIs.

## 9. Open contract cases

Every claim here is checked against Incoming's code, NeoForge 21.1.264 and the other mods' 1.21.1 source. The
deepest items have open contract cases, written into each item as requirements for its own PR: B2's mechanism
(position-level listeners, sourceless explosions, durable per-chunk decisions across restart and LOD copies, all
writers covered), A1's settlement (no debit before admission, per-release launcher payment, the scheduled nuclear
strike as the authoritative round), B5's personal teams and B4's nuclear writes. A1's and B2's mechanisms are decided
in their PRs with probes: an implementation contract for a multi-path subsystem needs that subsystem's own study.

## 10. Sources

Player feedback:
- CurseForge comments: https://www.curseforge.com/minecraft/mc-mods/incoming/comments
- Review video: https://www.youtube.com/watch?v=WSElk2YKfUU
- GitHub issue #656: https://github.com/zentixua/incoming/issues/656

Other mods' interop (source on GitHub):
- Create (`api/registry/CreateRegistries.java`, `compat/computercraft/`): https://github.com/Creators-of-Create/Create
- Create Big Cannons (`munition_properties`, `block_armor`, per-block `ProjectileDamageEvent`):
  https://github.com/Cannoneers-of-Create/CreateBigCannons
- Superb Warfare (`api/event/`, `compat/kubejs/`, `compat/jade/`): https://github.com/Mercurows/SuperbWarfare
- TACZ (`api/event/common/`, gun packs): https://github.com/MCModderAnchor/TACZ
- Ballistix (`api/event/BlastEvent`, `api/missile/MissileManager`): https://github.com/aurilisdev/Ballistix
- Mekanism (`RADIATION_SHIELDING` capability, `IRadiationShielding`): https://github.com/mekanism/Mekanism
- Immersive Engineering (`api/wires/redstone/`, `cctweaked/`): https://github.com/BluSunrize/ImmersiveEngineering
- HBM's Nuclear Tech, Forge 1.7.10 original (`IRadarDetectableNT`, `HazmatRegistry`):
  https://github.com/HbmMods/Hbm-s-Nuclear-Tech-GIT
- CC:Tweaked (`PeripheralCapability`, `ComputerCraftAPI`, `LuaFunction`): https://github.com/cc-tweaked/CC-Tweaked
- KubeJS, branch 2101 (`NativeEvents`, `EventGroup`): https://github.com/KubeJS-Mods/KubeJS
- Jade (`IWailaPlugin`, `IServerDataProvider`): https://github.com/Snownee/Jade
- Open Parties and Claims, branch 1.21 (`ChunkProtection.onExplosionDetonate`, `IPartyManagerAPI`):
  https://github.com/thexaero/open-parties-and-claims
- FTB Chunks (`explosionDetonate`, `canExplosionsDamageTerrain`): https://github.com/FTBTeam/FTB-Chunks
- FTB Teams (`TeamManager`): https://github.com/FTBTeam/FTB-Teams
- FTB Quests (advancement and stat tasks, command reward): https://github.com/FTBTeam/FTB-Quests
- FTB Ranks (NeoForge entry point without a permission handler): https://github.com/FTBTeam/FTB-Ranks
- NeoForge (`PermissionAPI`, `DataMapType`, `ExplosionEvent`): https://github.com/neoforged/NeoForge
- LuckPerms NeoForge permission handler: https://github.com/LuckPerms/LuckPerms
- Almighty (rules on game-bus events, notes, feed): https://github.com/zentixua/almighty
