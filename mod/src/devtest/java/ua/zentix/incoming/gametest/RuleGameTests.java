package ua.zentix.incoming.gametest;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.command.RuleCommand;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeRules;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.Warheads;

import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World rules by command (roadmap B4, {@link RuleCommand}): a set value is read back by the command and by the code that
 * uses it and is in the world's config file; an order keeps the rules it was made under through its salvo, flight, off-world
 * flight and their saves; with the arena rules off, conventional strikes of every weapon change no
 * block and cut no power even when the rules are switched back on before they hit; a player without permission is refused.
 * The rules are server-wide: each probe is its own batch.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RuleGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 12, 32);
    private static final String[] ARENA = {"world.block_damage", "world.shatter_glass", "world.fire", "world.debris_stay", "world.collapse", "grid.enabled"};
    private static final StrikeRules OFF = StrikeRules.of(0);

    private RuleGameTests() {}

    /**
     * Read, set, validate and save: {@code rule} lists every key (returns their count); a set returns 1, its read returns
     * the value as a number (also through {@code execute store}); the code reads it ({@link StrikeRules#live}); the
     * world's config file holds it and loads without correction (a restart keeps it); unknown keys and bad values fail
     * and change nothing.
     */
    @GameTest(template = "range", timeoutTicks = 20000, batch = "rule_command", skyAccess = true)
    public static void ruleCommandReadsSetsAndSaves(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Fence fence = Fence.open(h);
        CommandSourceStack op = console(h, "rule_command");

        h.assertValueEqual(run(h, op, "incoming rule"), RuleCommand.rules().size(), "list result");
        h.assertValueEqual(RuleCommand.rules().size(), 13, "exposed keys");
        h.assertValueEqual(run(h, op, "incoming rule world.fire false"), 1, "set result");
        h.assertValueEqual(run(h, op, "incoming rule world.fire"), 0, "read of false");
        h.assertFalse(IncomingConfig.SERVER.fire.get(), "config value");
        h.assertFalse(StrikeRules.live().fire(), "rules an order would capture");
        h.assertValueEqual(run(h, op, "incoming rule World.Fire TRUE"), 1, "keys and booleans ignore case");
        h.assertValueEqual(run(h, op, "incoming rule world.fire"), 1, "read of true");
        h.assertValueEqual(run(h, op, "incoming rule world.fire false"), 1, "set back");

        h.assertValueEqual(run(h, op, "incoming rule launch.max_salvo 7"), 1, "set number");
        h.assertValueEqual(run(h, op, "incoming rule launch.max_salvo"), 7, "read number");
        Objective o = objective(level, "b4rule");
        level.getServer().getCommands().performPrefixedCommand(op, "execute store result score #salvo b4rule run incoming rule launch.max_salvo");
        h.assertValueEqual(level.getServer().getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly("#salvo"), o).get(), 7, "execute store");

        refused(h, op, "incoming rule launch.max_salvo 0");
        refused(h, op, "incoming rule launch.max_salvo 101");
        refused(h, op, "incoming rule launch.max_salvo 1.5");
        refused(h, op, "incoming rule world.fire maybe");
        refused(h, op, "incoming rule world.nope true");
        refused(h, op, "incoming rule world.nope");
        refused(h, op, "incoming rule visibility.far_range 1000");
        h.assertValueEqual(IncomingConfig.SERVER.maxSalvo.get(), 7, "a refused value changed the rule");
        h.assertFalse(IncomingConfig.SERVER.fire.get(), "a refused value changed the rule");

        // one home: the world's own server config file holds the values, valid for the spec, so a restart loads them as set
        CommentedConfig file = file(h);
        h.assertValueEqual(file.get("world.fire"), Boolean.FALSE, "world.fire in the file");
        h.assertValueEqual(((Number) file.get("launch.max_salvo")).intValue(), 7, "launch.max_salvo in the file");
        h.assertTrue(IncomingConfig.SERVER_SPEC.isCorrect(file), "the saved file would be corrected on load");
        h.succeedWhen(() -> fence.settle(h));
    }

    /** A player without operator permission cannot read or set a rule (the branch does not exist for them). */
    @GameTest(template = "range", timeoutTicks = 40, batch = "rule_permission", skyAccess = true)
    public static void playerWithoutPermissionIsRefused(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        FakePlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes("b4-player".getBytes()), "b4player"));
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(CENTER)));
        PermissionGameTests.online(h, p);
        boolean fire = IncomingConfig.SERVER.fire.get();
        CommandSourceStack source = p.createCommandSourceStack();
        h.assertFalse(source.hasPermission(2), "the probe player is an operator");
        refused(h, source, "incoming rule world.fire " + !fire);
        refused(h, source, "incoming rule world.fire");
        refused(h, source, "incoming rule");
        refused(h, console(h, "rule_permission").withPermission(1), "incoming rule world.fire " + !fire);
        h.assertValueEqual(IncomingConfig.SERVER.fire.get(), fire, "the refused player changed the rule");
        h.succeed();
    }

    /**
     * Orders keep the rules they were made under: a console salvo made with every captured rule on keeps them for its
     * queued and flying rounds (also off-world, also read back from their saves) after the rules are switched off, and a
     * salvo made after the change carries the new ones. (A scheduled ICBM's save: {@code StrikeRulesTest}; a real one here
     * would start nuclear ruin preparation that later batches' nuclear tests share.)
     */
    @GameTest(template = "range", timeoutTicks = 20000, batch = "rule_capture", skyAccess = true)
    public static void ordersKeepTheirRules(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        keepRules(h);
        String before = "b4before", after = "b4after";
        StrikeGameTests.afterTest(h, () -> {
            for (String t : new String[]{before, after}) ServerActions.clearTag(level.getServer(), t, "test");
        });
        CommandSourceStack op = console(h, "rule_capture");
        BlockPos c = h.absolutePos(CENTER);
        String at = " at " + c.getX() + " " + c.getY() + " " + c.getZ();

        setArena(true);
        h.assertValueEqual(run(h, op, "incoming salvo drone 3 20" + at + " tag " + before), 3, "salvo before");
        setArena(false);
        h.assertValueEqual(run(h, op, "incoming salvo drone 2 20" + at + " tag " + after), 2, "salvo after");

        // queued salvo rounds, live and read back from the save
        h.assertValueEqual(salvoRules(SalvoData.get(level), before), List.of(StrikeRules.ALL), "queued rounds of the earlier salvo");
        h.assertValueEqual(salvoRules(SalvoData.readBackForTest(level), before), List.of(StrikeRules.ALL), "earlier salvo after a restart");
        h.assertValueEqual(salvoRules(SalvoData.get(level), after), List.of(OFF), "queued rounds of the later salvo");
        h.assertValueEqual(salvoRules(SalvoData.readBackForTest(level), after), List.of(OFF), "later salvo after a restart");

        // debris keeps the rules of the strike that threw it across a save; one saved without them takes the config
        DebrisEntity debris = DebrisEntity.create(level, Vec3.atCenterOf(c), Blocks.COBBLESTONE.defaultBlockState(), false, false, Vec3.ZERO).under(OFF);
        net.minecraft.nbt.CompoundTag debrisSave = new net.minecraft.nbt.CompoundTag();
        debris.saveWithoutId(debrisSave);
        DebrisEntity debrisBack = new DebrisEntity(ModEntities.DEBRIS.get(), level);
        debrisBack.load(debrisSave);
        h.assertValueEqual(debrisBack.rules(), OFF, "debris rules after a restart");
        debrisSave.remove("rules");
        DebrisEntity legacy = new DebrisEntity(ModEntities.DEBRIS.get(), level);
        legacy.load(debrisSave);
        h.assertValueEqual(legacy.rules(), StrikeRules.live(), "debris saved without rules");

        boolean[] seen = {false, false};
        h.onEachTick(() -> {
            for (StrikeProjectile p : flying(level)) {
                RoundRef r = p.round();
                if (r == null || !before.equals(r.tag()) && !after.equals(r.tag())) continue;
                StrikeRules want = before.equals(r.tag()) ? StrikeRules.ALL : OFF;
                if (!p.rules().equals(want)) throw new GameTestAssertException("round " + r.tag() + " flies under " + p.rules() + ", made under " + want);
                net.minecraft.nbt.CompoundTag saved = new net.minecraft.nbt.CompoundTag();
                if (!p.save(saved)) continue;
                StrikeProjectile copy = (StrikeProjectile) EntityType.create(saved, level).orElseThrow();
                if (!copy.rules().equals(want)) throw new GameTestAssertException("round " + r.tag() + " read back under " + copy.rules());
                seen[before.equals(r.tag()) ? 0 : 1] = true;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen[0] && seen[1], "rounds checked in flight: before " + seen[0] + ", after " + seen[1]);
        });
    }

    /**
     * The arena guarantee: with the five {@code world.*} rules and {@code grid.enabled} off when the rounds are made, a
     * conventional impact of every weapon (the B-2's bomb dropped as an entity: its bore too) and a Shahed shot down over
     * the arena (its wreck) change no block on an arena of glass, planks, wool, hay, grass, a lantern and a substation,
     * and cut no power, although the rules are switched back on before anything hits.
     */
    @GameTest(template = "range", timeoutTicks = 20000, batch = "rule_arena", skyAccess = true)
    public static void arenaKeepsItsBlocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        keepRules(h);
        GridQuiet grid = new GridQuiet();
        BlockPos substation = arena(h);
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(substation));
        h.assertTrue(node != null, "the substation is not a grid node");
        Map<BlockPos, BlockState> was = snapshot(h);

        setArena(false);
        List<RoundRef> rounds = new ArrayList<>();
        for (int i = 0; i < 6; i++) rounds.add(RoundRef.of(UUID.randomUUID(), i, "b4arena"));
        h.assertTrue(rounds.stream().allMatch(r -> r.rules().equals(OFF)), "rounds made with the rules off carry " + rounds.getFirst().rules());
        // the config turns everything back on: the rounds keep what they were made under
        setArena(true);

        Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 24))), null, null, rounds.get(0));
        Warheads.detonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 24))), null, null, rounds.get(1));
        Warheads.detonate(level, WeaponType.ROCKET, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 40))), null, null, rounds.get(2));
        Warheads.detonate(level, WeaponType.LOITER, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 40))), null, null, rounds.get(3));
        // the B-2's bomb as an entity carrying its round: the bore through the ground, then the buried charge
        Vec3 surface = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(32, 12, 20)));
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        h.assertTrue(bomb != null, "no bomb");
        bomb.drop(surface.add(0, 26, 0), new Vec3(0, -4, 0), surface, null, null);
        bomb.attachRound(rounds.get(4));
        level.addFreshEntity(bomb);
        // a Shahed shot down over the arena: its wreck through StrikeProjectile.intercepted
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        h.assertTrue(drone != null, "no drone");
        drone.moveTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 34, 44))));
        drone.attachRound(rounds.get(5));
        level.addFreshEntity(drone);
        drone.intercepted(level, "rule_arena", null, null);

        boolean[] struck = {false};
        long start = level.getGameTime();
        h.onEachTick(() -> {
            if (!struck[0] && bomb.isRemoved() && level.getGameTime() > start + 2 && StrikeWorld.get(level).idle() && tagged(level, "b4arena") == 0) struck[0] = true;
        });
        boolean[] held = {false};
        h.succeedWhen(() -> {
            // the checks once (the cleanup below changes what they read), then the cleanup and its waits
            if (!held[0]) {
                h.assertTrue(bomb.isRemoved(), "the bomb has not gone off");
                h.assertTrue(struck[0], "strikes still running");
                h.assertTrue(level.getEntitiesOfClass(DebrisEntity.class, overPlatform(h)).isEmpty(), "debris still falling");
                h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isEmpty(), "the substation was knocked out");
                List<String> changed = changes(h, was);
                h.assertTrue(changed.isEmpty(), changed.size() + " blocks changed, first " + changed.subList(0, Math.min(8, changed.size())));
                held[0] = true;
            }
            grid.settle(h, level, node);
        });
    }

    /**
     * Control for the arena: rounds made with every rule on keep them after the config switches everything off: the
     * same arena loses blocks and its substation.
     */
    @GameTest(template = "range", timeoutTicks = 20000, batch = "rule_arena_control", skyAccess = true)
    public static void roundsMadeUnderRulesOnStillDamage(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        keepRules(h);
        GridQuiet grid = new GridQuiet();
        BlockPos substation = arena(h);
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(substation));
        h.assertTrue(node != null, "the substation is not a grid node");
        Map<BlockPos, BlockState> was = snapshot(h);
        setArena(true);
        RoundRef drone = RoundRef.of(UUID.randomUUID(), 0, "b4control"), missile = RoundRef.of(UUID.randomUUID(), 1, "b4control");
        setArena(false);
        Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 24))), null, null, drone);
        Warheads.detonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 24))), null, null, missile);
        boolean[] held = {false};
        h.succeedWhen(() -> {
            // the checks once (the cleanup below changes what they read), then the cleanup and its waits
            if (!held[0]) {
                h.assertTrue(StrikeWorld.get(level).idle(), "strikes still running");
                h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isPresent(), "the substation kept power");
                List<String> changed = changes(h, was);
                h.assertTrue(changed.size() > 20, "only " + changed.size() + " blocks changed under rounds made with every rule on");
                held[0] = true;
            }
            grid.settle(h, level, node);
        });
    }

    /**
     * Control for the arena's entity legs: a bomb and a shot-down Shahed whose rounds were made with every rule on still
     * bore the post and leave wreck blocks after the config switches everything off.
     */
    @GameTest(template = "range", timeoutTicks = 20000, batch = "rule_arena_control_entities", skyAccess = true)
    public static void entityLegsMadeUnderRulesOnStillChangeBlocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        keepRules(h);
        GridQuiet grid = new GridQuiet();
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(arena(h)));
        h.assertTrue(node != null, "the substation is not a grid node");
        Map<BlockPos, BlockState> was = snapshot(h);
        setArena(true);
        RoundRef bombRound = RoundRef.of(UUID.randomUUID(), 0, "b4control2"), droneRound = RoundRef.of(UUID.randomUUID(), 1, "b4control2");
        setArena(false);
        Vec3 surface = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(32, 12, 20)));
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        h.assertTrue(bomb != null, "no bomb");
        bomb.drop(surface.add(0, 26, 0), new Vec3(0, -4, 0), surface, null, null);
        bomb.attachRound(bombRound);
        level.addFreshEntity(bomb);
        DroneEntity drone = ModEntities.DRONE.get().create(level);
        h.assertTrue(drone != null, "no drone");
        drone.moveTo(Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 34, 44))));
        drone.attachRound(droneRound);
        level.addFreshEntity(drone);
        drone.intercepted(level, "rule_arena_control_entities", null, null);
        boolean[] held = {false};
        h.succeedWhen(() -> {
            // the checks once (the cleanup below changes what they read), then the cleanup and its waits
            if (!held[0]) {
                h.assertTrue(bomb.isRemoved() && StrikeWorld.get(level).idle(), "the bomb has not gone off");
                h.assertTrue(level.getEntitiesOfClass(DebrisEntity.class, overPlatform(h)).isEmpty(), "debris still falling");
                long bore = 0, wreck = 0;
                for (var e : was.entrySet()) {
                    if (h.getBlockState(e.getKey()).equals(e.getValue())) continue;
                    BlockPos p = e.getKey();
                    if (Math.abs(p.getX() - 32) <= 1 && Math.abs(p.getZ() - 20) <= 1 && p.getY() <= 13) bore++;
                    if (p.getZ() >= 36) wreck++;
                }
                h.assertTrue(bore > 0, "the bomb made under rules on left its column whole");
                h.assertTrue(wreck > 0, "the wreck made under rules on left no block");
                held[0] = true;
            }
            grid.settle(h, level, node);
        });
    }

    // ---------------------------------------------------------------- fixture

    /** Glass, planks, wool, hay and grass around the impact points, a lantern and a substation; returns the substation. */
    private static BlockPos arena(GameTestHelper h) {
        for (int x = 14; x <= 50; x += 6) {
            for (int z = 14; z <= 50; z += 6) {
                BlockPos base = new BlockPos(x, 12, z);
                if (Math.abs(x - 32) + Math.abs(z - 32) < 4) continue;
                h.setBlock(base, Blocks.OAK_PLANKS);
                h.setBlock(base.above(), Blocks.GLASS);
                h.setBlock(base.east(), Blocks.WHITE_WOOL);
                h.setBlock(base.south(), Blocks.HAY_BLOCK);
                h.setBlock(base.west(), Blocks.GLASS_PANE);
                h.setBlock(base.north(), Blocks.SHORT_GRASS);
            }
        }
        h.setBlock(new BlockPos(30, 12, 30), Blocks.LANTERN);
        BlockPos substation = new BlockPos(32, 12, 28);
        h.setBlock(substation, ModBlocks.SUBSTATION.get());
        return substation;
    }

    /**
     * The platform and the air above it, where the tests compare blocks: debris that drifts beyond it lands where entities
     * do not tick (GameTest chunks past the platform) and never goes away, but cannot touch the compared blocks either.
     */
    static AABB overPlatform(GameTestHelper h) {
        return new AABB(h.absolutePos(BlockPos.ZERO).getCenter(), h.absolutePos(new BlockPos(63, 39, 63)).getCenter()).inflate(1);
    }

    /** Every block of the platform and the air above it. */
    static Map<BlockPos, BlockState> snapshot(GameTestHelper h) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int x = 0; x < 64; x++) for (int y = 0; y < 40; y++) for (int z = 0; z < 64; z++) {
            BlockPos p = new BlockPos(x, y, z);
            out.put(p, h.getBlockState(p));
        }
        return out;
    }

    static List<String> changes(GameTestHelper h, Map<BlockPos, BlockState> was) {
        List<String> out = new ArrayList<>();
        for (var e : was.entrySet()) {
            BlockState now = h.getBlockState(e.getKey());
            if (!now.equals(e.getValue())) out.add(e.getKey().toShortString() + " " + e.getValue() + " -> " + now);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The world's server config as it was before the test, put back and settled. {@code /incoming rule} saves the file,
     * and FML's watcher reloads it on its own thread about 500 ms after the last write (NightConfig debounce), replacing
     * every value in memory: a reload landing in a later batch would undo that batch's own settings, and a save would
     * carry other tests' temporary values into the file. So the test puts back the original bytes, waits for the reload
     * of that write, then sets every value back as it was in memory before the test ({@link #settle}): GameTest runs
     * with values only in memory ({@code TestScale.realWaveOnStart}: CUSTOM preset, wave 1.0, over the file's GAME), and
     * a reload alone would hand later batches the file's. Without a working watcher it times out.
     */
    /**
     * The arena's substation leaves the grid as it found it: power back everywhere, its node gone, the blackout queue
     * idle, so no outage cascade or node outlives the test into later batches (an outage left by the controls held back
     * the nuclear settlement's admission in the next batch, `nuke_settle_budget`).
     */
    private static final class GridQuiet {
        private boolean restored;

        void settle(GameTestHelper h, ServerLevel level, Node node) {
            if (!restored) {
                restored = true;
                ua.zentix.incoming.grid.Blackouts.restore(level, null, 0, 0);
                PowerGrid.get(level).removeNode(node.id());
            }
            h.assertTrue(ua.zentix.incoming.grid.BlackoutWorld.get(level).idle(), "blackout work still queued");
        }
    }

    private static final class Fence {
        private static final java.util.concurrent.atomic.AtomicLong LAST_RELOAD = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);
        private static boolean listening;
        /** NightConfig's debounce is 500 ms: a reload this long after the write follows it. */
        private static final long AFTER_WRITE_NANOS = 450_000_000L;
        /** No reload for this long after the last one: the write's file events are all reloaded. */
        private static final long QUIET_NANOS = 2_000_000_000L;
        private final java.nio.file.Path file;
        private final byte[] bytes;
        /** Every server config value as it was in memory when the test began. */
        private final Map<ModConfigSpec.ConfigValue<?>, Object> values = new HashMap<>();
        private long writtenAt = Long.MIN_VALUE;
        private boolean reapplied;

        private Fence(GameTestHelper h) {
            listen();
            file = config().getFullPath();
            try {
                bytes = Files.readAllBytes(file);
            } catch (java.io.IOException e) {
                throw new GameTestAssertException("config file " + file + ": " + e);
            }
            collect(IncomingConfig.SERVER_SPEC.getValues(), values);
            // a failed test still leaves the file and values as they were (no wait: it has failed)
            StrikeGameTests.afterTest(h, this::restore);
        }

        static Fence open(GameTestHelper h) {
            return new Fence(h);
        }

        private static void collect(com.electronwill.nightconfig.core.UnmodifiableConfig config, Map<ModConfigSpec.ConfigValue<?>, Object> out) {
            for (com.electronwill.nightconfig.core.UnmodifiableConfig.Entry entry : config.entrySet()) {
                Object o = entry.getValue();
                if (o instanceof com.electronwill.nightconfig.core.UnmodifiableConfig sub) collect(sub, out);
                else if (o instanceof ModConfigSpec.ConfigValue<?> v) out.put(v, v.get());
            }
        }

        private static synchronized void listen() {
            if (listening) return;
            listening = true;
            var bus = net.neoforged.fml.ModList.get().getModContainerById(Incoming.MOD_ID).orElseThrow().getEventBus();
            if (bus == null) throw new IllegalStateException("no mod event bus");
            bus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading e) -> {
                if (e.getConfig().getType() == ModConfig.Type.SERVER && Incoming.MOD_ID.equals(e.getConfig().getModId())) LAST_RELOAD.set(System.nanoTime());
            });
        }

        private void restore() {
            values.forEach(RuleGameTests::put);
            try {
                Files.write(file, bytes);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("config file " + file, e);
            }
        }

        /** In {@code succeedWhen}, after the test's checks: put the config back once, then wait for its reload. */
        void settle(GameTestHelper h) {
            if (writtenAt == Long.MIN_VALUE) {
                restore();
                writtenAt = System.nanoTime();
            }
            h.assertTrue(LAST_RELOAD.get() - writtenAt >= AFTER_WRITE_NANOS, "waiting for the config file's reload");
            h.assertTrue(System.nanoTime() - LAST_RELOAD.get() >= QUIET_NANOS, "waiting until the config file stops reloading");
            if (!reapplied) {
                reapplied = true;
                values.forEach(RuleGameTests::put);
            }
            for (var e : values.entrySet()) {
                h.assertValueEqual(e.getKey().getRaw(), e.getValue(), "config after its reload: " + String.join(".", e.getKey().getPath()));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(ModConfigSpec.ConfigValue<T> v, Object value) {
        v.set((T) value);
    }

    /**
     * The six arena rules set in memory, as the command sets them (`rule_command` proves the command does exactly this
     * and saves the file); tests that write the file need a {@link Fence}.
     */
    private static void setArena(boolean on) {
        for (RuleCommand.Rule r : RuleCommand.rules()) if (java.util.Arrays.asList(ARENA).contains(r.key())) put(r.config(), on);
    }

    /** Every exposed rule back to its value before the test, in memory. */
    private static void keepRules(GameTestHelper h) {
        Map<ModConfigSpec.ConfigValue<?>, Object> was = new HashMap<>();
        for (RuleCommand.Rule r : RuleCommand.rules()) was.put(r.config(), r.config().get());
        StrikeGameTests.afterTest(h, () -> was.forEach(RuleGameTests::put));
    }

    private static List<StrikeRules> salvoRules(SalvoData data, String tag) {
        List<StrikeRules> out = new ArrayList<>();
        data.rounds(r -> {
            if (tag.equals(r.ref().tag()) && !out.contains(r.ref().rules())) out.add(r.ref().rules());
        });
        return out;
    }

    /** Projectiles in the world and off-world. */
    private static List<StrikeProjectile> flying(ServerLevel level) {
        List<StrikeProjectile> out = new ArrayList<>(VirtualFlights.get(level).flights());
        for (var e : level.getAllEntities()) if (e instanceof StrikeProjectile p) out.add(p);
        return out;
    }

    private static int tagged(ServerLevel level, String tag) {
        return ua.zentix.incoming.strike.OrderStatus.rounds(level.getServer(), new ua.zentix.incoming.strike.OrderStatus.Filter(null, tag)).size();
    }

    /** This mod's server config: the world's settings file. */
    private static ModConfig config() {
        return ModConfigs.getConfigSet(ModConfig.Type.SERVER).stream().filter(c -> Incoming.MOD_ID.equals(c.getModId())).findFirst().orElseThrow();
    }

    private static CommentedConfig file(GameTestHelper h) {
        ModConfig config = config();
        try {
            return new TomlParser().parse(new StringReader(Files.readString(config.getFullPath())));
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("config file " + config.getFullPath() + ": " + e);
        }
    }

    private static Objective objective(ServerLevel level, String name) {
        var board = level.getServer().getScoreboard();
        Objective o = board.getObjective(name);
        return o != null ? o : board.addObjective(name, ObjectiveCriteria.DUMMY, Component.literal(name), ObjectiveCriteria.RenderType.INTEGER, false, null);
    }

    private static CommandSourceStack console(GameTestHelper h, String name) {
        ServerLevel level = h.getLevel();
        return new CommandSourceStack(CommandSource.NULL, Vec3.atCenterOf(h.absolutePos(CENTER)), Vec2.ZERO, level, 4, name, Component.literal(name),
                level.getServer(), null);
    }

    private static int run(GameTestHelper h, CommandSourceStack source, String command) {
        try {
            return h.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
        } catch (CommandSyntaxException e) {
            throw new GameTestAssertException("/" + command + ": " + e.getMessage());
        }
    }

    private static void refused(GameTestHelper h, CommandSourceStack source, String command) {
        try {
            int r = h.getLevel().getServer().getCommands().getDispatcher().execute(command, source);
            throw new GameTestAssertException("/" + command + " was accepted (" + r + ")");
        } catch (CommandSyntaxException expected) {
            // refused: brigadier answers with the error line, execute store gets 0
        }
    }
}
