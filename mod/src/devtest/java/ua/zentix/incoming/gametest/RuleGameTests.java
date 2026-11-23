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
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.grid.Node;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.SalvoData;
import ua.zentix.incoming.strike.ServerActions;
import ua.zentix.incoming.strike.StrikeRules;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.DebrisSpawner;
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
 * flight, scheduled nuclear strike and their saves; with the arena rules off, conventional strikes of every weapon change no
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
    @GameTest(template = "range", timeoutTicks = 40, batch = "rule_command", skyAccess = true)
    public static void ruleCommandReadsSetsAndSaves(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        restoreAfter(h);
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
        h.succeed();
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
     * salvo made after the change carries the new ones; a scheduled ICBM keeps {@code grid.enabled} across its save.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "rule_capture", skyAccess = true)
    public static void ordersKeepTheirRules(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        restoreAfter(h);
        boolean enabled = IncomingConfig.SERVER.nukeEnabled.get();
        int flight = IncomingConfig.SERVER.nukeFlightTime.get();
        String before = "b4before", after = "b4after", nuke = "b4nuke";
        StrikeGameTests.afterTest(h, () -> {
            for (String t : new String[]{before, after, nuke}) ServerActions.clearTag(level.getServer(), t, "test");
            IncomingConfig.SERVER.nukeEnabled.set(enabled);
            IncomingConfig.SERVER.nukeFlightTime.set(flight);
        });
        IncomingConfig.SERVER.nukeEnabled.set(true);
        IncomingConfig.SERVER.nukeFlightTime.set(6000);
        CommandSourceStack op = console(h, "rule_capture");
        BlockPos c = h.absolutePos(CENTER);
        String at = " at " + c.getX() + " " + c.getY() + " " + c.getZ();

        setAll(h, op, true);
        h.assertValueEqual(run(h, op, "incoming salvo drone 3 20" + at + " tag " + before), 3, "salvo before");
        h.assertValueEqual(run(h, op, "incoming nuke" + at + " 1 air tag " + nuke), 1, "ICBM before");
        setAll(h, op, false);
        h.assertValueEqual(run(h, op, "incoming salvo drone 2 20" + at + " tag " + after), 2, "salvo after");

        // queued salvo rounds, live and read back from the save
        h.assertValueEqual(salvoRules(SalvoData.get(level), before), List.of(StrikeRules.ALL), "queued rounds of the earlier salvo");
        h.assertValueEqual(salvoRules(SalvoData.readBackForTest(level), before), List.of(StrikeRules.ALL), "earlier salvo after a restart");
        h.assertValueEqual(salvoRules(SalvoData.get(level), after), List.of(OFF), "queued rounds of the later salvo");
        h.assertValueEqual(salvoRules(SalvoData.readBackForTest(level), after), List.of(OFF), "later salvo after a restart");
        // the scheduled nuclear strike, live and read back
        h.assertValueEqual(scheduledGrid(NuclearEvents.get(level), nuke), List.of(true), "scheduled ICBM grid rule");
        h.assertValueEqual(scheduledGrid(NuclearEvents.readBackForTest(level), nuke), List.of(true), "scheduled ICBM grid rule after a restart");

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
        h.succeedWhen(() -> h.assertTrue(seen[0] && seen[1], "rounds checked in flight: before " + seen[0] + ", after " + seen[1]));
    }

    /**
     * The arena guarantee: with the five {@code world.*} rules and {@code grid.enabled} off when the rounds are made, a
     * conventional impact of every weapon, a shot-down wreck's debris and a console strike that flies in change no block
     * on an arena of glass, planks, wool, hay, grass, a lantern and a substation, and cut no power, although the rules are
     * switched back on before anything hits.
     */
    @GameTest(template = "range", timeoutTicks = 4000, batch = "rule_arena", skyAccess = true)
    public static void arenaKeepsItsBlocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        restoreAfter(h);
        CommandSourceStack op = console(h, "rule_arena");
        BlockPos substation = arena(h);
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(substation));
        h.assertTrue(node != null, "the substation is not a grid node");
        Map<BlockPos, BlockState> was = snapshot(h);

        setAll(h, op, false);
        BlockPos c = h.absolutePos(CENTER);
        h.assertValueEqual(run(h, op, "incoming salvo missile 1 0 at " + c.getX() + " " + c.getY() + " " + c.getZ() + " tag b4arena"), 1, "console strike");
        List<RoundRef> rounds = new ArrayList<>();
        for (int i = 0; i < 6; i++) rounds.add(RoundRef.of(UUID.randomUUID(), i, "b4arena"));
        h.assertTrue(rounds.stream().allMatch(r -> r.rules().equals(OFF)), "rounds made with the rules off carry " + rounds.getFirst().rules());
        // the config turns everything back on: the rounds keep what they were made under
        setAll(h, op, true);
        StrikeGameTests.afterTest(h, () -> ServerActions.clearTag(level.getServer(), "b4arena", "test"));

        Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 24))), null, null, rounds.get(0));
        Warheads.detonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 24))), null, null, rounds.get(1));
        Warheads.detonate(level, WeaponType.ROCKET, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 40))), null, null, rounds.get(2));
        Warheads.detonate(level, WeaponType.LOITER, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 40))), null, null, rounds.get(3));
        Vec3 bunker = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 4, 20)));
        Warheads.bunker(level, bunker, bunker.add(0, 7, 0), null, null, rounds.get(4));
        DebrisSpawner.wreck(level, Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 30, 44))), new Vec3(0.5, 0, 0), 6, rounds.get(5).rules());

        boolean[] struck = {false};
        long start = level.getGameTime();
        h.onEachTick(() -> {
            if (!struck[0] && level.getGameTime() > start + 2 && StrikeWorld.get(level).idle() && tagged(level, "b4arena") == 0) struck[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(struck[0], "strikes still running");
            h.assertTrue(level.getEntitiesOfClass(DebrisEntity.class, new AABB(h.absolutePos(BlockPos.ZERO)).inflate(80)).isEmpty(), "debris still falling");
            h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isEmpty(), "the substation was knocked out");
            List<String> changed = changes(h, was);
            h.assertTrue(changed.isEmpty(), changed.size() + " blocks changed, first " + changed.subList(0, Math.min(8, changed.size())));
        });
    }

    /**
     * Control for the arena: rounds made with every rule on keep them after the config switches everything off: the
     * same arena loses blocks and its substation.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "rule_arena_control", skyAccess = true)
    public static void roundsMadeUnderRulesOnStillDamage(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        restoreAfter(h);
        CommandSourceStack op = console(h, "rule_arena_control");
        BlockPos substation = arena(h);
        Node node = PowerGrid.get(level).nodeAt(h.absolutePos(substation));
        h.assertTrue(node != null, "the substation is not a grid node");
        Map<BlockPos, BlockState> was = snapshot(h);
        setAll(h, op, true);
        RoundRef drone = RoundRef.of(UUID.randomUUID(), 0, "b4control"), missile = RoundRef.of(UUID.randomUUID(), 1, "b4control");
        setAll(h, op, false);
        Warheads.detonate(level, WeaponType.DRONE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(24, 12, 24))), null, null, drone);
        Warheads.detonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(40, 12, 24))), null, null, missile);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "strikes still running");
            h.assertTrue(PowerGrid.get(level).downOutage(node.id(), level.getGameTime()).isPresent(), "the substation kept power");
            List<String> changed = changes(h, was);
            h.assertTrue(changed.size() > 20, "only " + changed.size() + " blocks changed under rounds made with every rule on");
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

    /** Every block of the platform and the air above it. */
    private static Map<BlockPos, BlockState> snapshot(GameTestHelper h) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int x = 0; x < 64; x++) for (int y = 0; y < 40; y++) for (int z = 0; z < 64; z++) {
            BlockPos p = new BlockPos(x, y, z);
            out.put(p, h.getBlockState(p));
        }
        return out;
    }

    private static List<String> changes(GameTestHelper h, Map<BlockPos, BlockState> was) {
        List<String> out = new ArrayList<>();
        for (var e : was.entrySet()) {
            BlockState now = h.getBlockState(e.getKey());
            if (!now.equals(e.getValue())) out.add(e.getKey().toShortString() + " " + e.getValue() + " -> " + now);
        }
        return out;
    }

    // ---------------------------------------------------------------- helpers

    /** Every exposed rule back to its value before the test, saved. */
    private static void restoreAfter(GameTestHelper h) {
        Map<ModConfigSpec.ConfigValue<?>, Object> was = new HashMap<>();
        for (RuleCommand.Rule r : RuleCommand.rules()) was.put(r.config(), r.config().get());
        StrikeGameTests.afterTest(h, () -> {
            was.forEach(RuleGameTests::put);
            IncomingConfig.SERVER_SPEC.save();
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> void put(ModConfigSpec.ConfigValue<T> v, Object value) {
        v.set((T) value);
    }

    /** The arena rules, by command. */
    private static void setAll(GameTestHelper h, CommandSourceStack op, boolean on) {
        for (String key : ARENA) h.assertValueEqual(run(h, op, "incoming rule " + key + " " + on), 1, "rule " + key);
    }

    private static List<StrikeRules> salvoRules(SalvoData data, String tag) {
        List<StrikeRules> out = new ArrayList<>();
        data.rounds(r -> {
            if (tag.equals(r.ref().tag()) && !out.contains(r.ref().rules())) out.add(r.ref().rules());
        });
        return out;
    }

    private static List<Boolean> scheduledGrid(NuclearEvents events, String tag) {
        return events.scheduled().stream().flatMap(s -> s.round().stream()).filter(r -> tag.equals(r.tag())).map(r -> r.rules().grid()).toList();
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

    private static CommentedConfig file(GameTestHelper h) {
        ModConfig config = ModConfigs.getConfigSet(ModConfig.Type.SERVER).stream()
                .filter(c -> Incoming.MOD_ID.equals(c.getModId())).findFirst().orElseThrow();
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
