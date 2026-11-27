package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import net.minecraft.world.level.ChunkPos;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.model.FuelPool;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.warhead.FuelFires;
import ua.zentix.incoming.warhead.Warheads;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The fuel in the crater burns with fire Minecraft on blocks ({@link FuelFires}). Class of complaint (photo by Artem 24.10: billows of flame
 *  hung in the air where there was no roof anymore): there is no fuel fire without support - in each tick under each burning cell there is a block
 *  with a shape and in the cell there is fire; and it burns no longer than its reserve - the last cell goes out within the period of the law
 *  ({@link FuelPool#seconds}), and after that there is no fire left, which was kept only by fuel.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FuelFireGameTests {
    /** The middle of the site “range”: grass (floor - stone, earth, grass) and the first air above it. */
    private static final BlockPos GRASS = new BlockPos(32, 11, 32), CENTER = GRASS.above();
    /** Cells of other people's batch checks do not count: your own are closer to your own impact, blocks. */
    private static final double MINE = 24;

    private FuelFireGameTests() {}

    /**
     * Debris does not remain (clumps on the cell would extinguish its fuel - this is the case in the game, but this is not what the test is about), the weather is clear:
     *  the vanilla fire goes out in the rain, and the cell would wait for a new fire until {@code FuelFires.RELIGHT} tick.
     */
    private static void calm(GameTestHelper h) {
        boolean debris = IncomingConfig.SERVER.debrisStay.get(), damage = IncomingConfig.SERVER.blockDamage.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        IncomingConfig.SERVER.blockDamage.set(true);
        h.getLevel().setWeatherParameters(24000, 0, false, false);
        fireTicks(h);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.debrisStay.set(debris);
            IncomingConfig.SERVER.blockDamage.set(damage);
        });
    }

    /**
     * During the test, the vanilla fire goes on as in the game ({@code doFireTick}: the server GameTest has it turned off): it grows old, goes out
     *  on the bare ground, in the rain, grows on fuel. Without this, the fire holding test would be empty.
     */
    private static void fireTicks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        net.minecraft.world.level.GameRules.BooleanValue rule = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
        boolean was = rule.get();
        rule.set(true, level.getServer());
        StrikeGameTests.afterTest(h, () -> rule.set(was, level.getServer()));
    }

    /** {@code at}'s fuel cells (their own), their current locations. */
    private static List<BlockPos> cells(ServerLevel level, Vec3 at) {
        return FuelFires.get(level).cells().stream().filter(p -> Vec3.atCenterOf(p).distanceTo(at) < MINE).toList();
    }

    /** Each burning cell is a fire on a support: under it is a block with a collision form, in it is a fire block. */
    private static void standsOnSupport(GameTestHelper h, ServerLevel level, Vec3 at, Set<BlockPos> ever) {
        for (BlockPos c : cells(level, at)) {
            ever.add(c);
            BlockPos below = c.below();
            h.assertTrue(!level.getBlockState(below).getCollisionShape(level, below).isEmpty(),
                    "fuel fire in the air: " + c.toShortString() + " over " + level.getBlockState(below));
            h.assertTrue(level.getBlockState(c).getBlock() instanceof BaseFireBlock,
                    "fuel cell without fire: " + c.toShortString() + " — " + level.getBlockState(c));
        }
    }

    /**
     * Hit {@code weapon} into the grass: fuel falls on the bottom of the crater - as many cells as the area of the law; while it burns,
     * each is a fire on a support; they go out according to the law (all burn until 0,8), the last one is on time, and there is no fire left on the bare ground after
     * . For the duration of the test, the vanilla fire runs as in the game ({@link #fireTicks}).
     */
    private static void groundTest(GameTestHelper h, WeaponType weapon) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        FuelPool law = weapon.spec().pool();
        long born = level.getGameTime(), end = born + Math.round(20 * law.seconds());
        Set<BlockPos> ever = new HashSet<>();
        long[] out = {Long.MIN_VALUE};
        int[] most = {0};
        calm(h);
        Warheads.detonate(level, weapon, at, null, null);
        h.onEachTick(() -> {
            standsOnSupport(h, level, at, ever);
            int n = cells(level, at).size();
            most[0] = Math.max(most[0], n);
            long t = level.getGameTime();
            if (t < born + Math.round(16 * law.seconds()) && t > born + 40) {
                h.assertTrue(n == law.cells(), String.format(Locale.ROOT, "%s: at %.0f s, %d of %d cells are burning", weapon, (t - born) / 20.0, n, law.cells()));
            }
            if (n == 0 && !ever.isEmpty() && out[0] == Long.MIN_VALUE) out[0] = t;
        });
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "hit queue is not empty");
            h.assertTrue(out[0] != Long.MIN_VALUE, "the fuel is still burning");
            for (BlockPos c : ever) {
                h.assertTrue(!(level.getBlockState(c).getBlock() instanceof BaseFireBlock), "after the fuel the fire remained: " + c.toShortString());
            }
            String got = String.format(Locale.ROOT, "%s: %d cells (law %d), extinguished at %.1f s (law %.1f s)", weapon, most[0], law.cells(),
                    (out[0] - born) / 20.0, law.seconds());
            Incoming.LOG.info("FUELFIRE {}", got);
            h.assertTrue(most[0] == law.cells(), got);
            h.assertTrue(Math.abs(out[0] - end) <= 2, got);
        });
    }

    /**
     * Rules 3 and 7 of the time note: the crater fuel burns in place on its strike's clock, within its show, so a night
     * nobody saw (8 h, counted) burns it out: a Shahed's fuel burning 10 s after impact has no cell and no fire left two
     * ticks after the count, and no packet tells a client it still burns. A night someone saw moves it not at all.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "fuel_fire_counted_night", skyAccess = true)
    public static void aCountedNightBurnsTheCraterFuelOut(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        net.minecraft.server.MinecraftServer server = level.getServer();
        ua.zentix.incoming.util.WorldClock clock = ua.zentix.incoming.util.WorldClock.get(server);
        String dim = level.dimension().location().toString();
        java.util.Map<String, double[]> saved = new java.util.HashMap<>();
        for (ServerLevel l : server.getAllLevels()) saved.put(l.dimension().location().toString(), clock.jumpsForTest(l.dimension().location().toString()));
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        calm(h);
        StrikeGameTests.afterTest(h, () -> {
            ua.zentix.incoming.util.WorldClock.holdJumpsWhile(ua.zentix.incoming.nuclear.NuclearStrikes::frontCrossing);
            saved.forEach(clock::restoreJumpsForTest);
        });
        FuelPool law = WeaponType.DRONE.spec().pool();
        h.assertTrue(FuelFires.testPour(level, at, WeaponType.DRONE, 200) > 0, "no fuel lit");
        List<BlockPos> lit = new ArrayList<>(cells(level, at));
        long[] unseen = {Long.MIN_VALUE}, counted = {Long.MIN_VALUE};
        h.onEachTick(() -> {
            long t = level.getGameTime();
            if (counted[0] != Long.MIN_VALUE) {
                h.assertTrue(cells(level, at).isEmpty() || t - counted[0] <= 2,
                        "the fuel burns on " + (t - counted[0]) + " ticks after an 8-hour night: " + cells(level, at).size() + " cells");
                return;
            }
            h.assertTrue(cells(level, at).size() == law.cells(), "the fuel is out before the night nobody saw: " + cells(level, at).size());
            if (unseen[0] == Long.MIN_VALUE && t % 20 == 7) {
                // a night someone in the dimension saw: it moves only what nobody sees change, the fire burns on
                double[] j = clock.jumpsForTest(dim);
                clock.restoreJumpsForTest(dim, new double[]{j[0], j[1] + 8000, j[2]});
                unseen[0] = t;
            } else if (unseen[0] != Long.MIN_VALUE && t - unseen[0] >= 20) {
                // a night nobody saw: counted
                ua.zentix.incoming.util.WorldClock.holdJumpsWhile(l -> false);
                clock.recordAwakeForTest(server);
                clock.jumpForTest(server, 8000);
                h.assertTrue(clock.jumpsForTest(dim)[0] == saved.get(dim)[0] + 8000, "the night was not counted: "
                        + java.util.Arrays.toString(clock.jumpsForTest(dim)));
                counted[0] = t;
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(counted[0] != Long.MIN_VALUE, "no night yet");
            h.assertTrue(cells(level, at).isEmpty(), "the fuel still burns after the night");
            for (BlockPos c : lit) {
                h.assertTrue(!(level.getBlockState(c).getBlock() instanceof BaseFireBlock), "fire left at " + c.toShortString());
            }
            h.assertTrue(FuelFires.burning(level).stream().noneMatch(b -> b.at().distanceTo(at) < MINE), "clients are still told it burns");
        });
    }

    @GameTest(template = "range", timeoutTicks = 3600, batch = "fuel_fire_drone", skyAccess = true)
    public static void droneFuelBurnsOnTheCraterFloorAsLongAsItLasts(GameTestHelper h) {
        groundTest(h, WeaponType.DRONE);
    }

    /**
     * Rule 11 of the time note (docs/design/time.md §8.3), {@code doFireTick} off, strike fire as in the game
     * ({@code StrikeRules.lightFrozenFireForTest(false)}), debris staying: the Shahed's fuel ball, blast and burning debris
     * light no block fire (it would stand forever); the crater fuel fire still burns its law's cells and time, and at
     * burn-out removes every one of its blocks, though logs stand beside the cells (with fire tick on a log keeps a
     * game fire going, {@link #droneFuelBurnsOnTheCraterFloorAsLongAsItLasts}). After the law's end no fire block is left
     * within {@link #MINE} of the impact.
     */
    @GameTest(template = "range", timeoutTicks = 3600, batch = "fuel_fire_firetick_off", skyAccess = true)
    public static void withoutFireTickNoFireOutlivesTheStrike(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        FuelPool law = WeaponType.DRONE.spec().pool();
        net.minecraft.world.level.GameRules.BooleanValue rule = level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOFIRETICK);
        boolean was = rule.get(), debris = IncomingConfig.SERVER.debrisStay.get();
        rule.set(false, level.getServer());
        IncomingConfig.SERVER.debrisStay.set(true);
        ua.zentix.incoming.strike.StrikeRules.lightFrozenFireForTest(false);
        level.setWeatherParameters(24000, 0, false, false);
        StrikeGameTests.afterTest(h, () -> {
            rule.set(was, level.getServer());
            IncomingConfig.SERVER.debrisStay.set(debris);
            ua.zentix.incoming.strike.StrikeRules.lightFrozenFireForTest(true);
        });
        long born = level.getGameTime(), end = born + Math.round(20 * law.seconds());
        Set<BlockPos> ever = new HashSet<>();
        int[] most = {0}, logs = {0};
        Warheads.detonate(level, WeaponType.DRONE, at, null, null);
        h.onEachTick(() -> {
            List<BlockPos> now = cells(level, at);
            most[0] = Math.max(most[0], now.size());
            ever.addAll(now);
            // once every cell burns (explosion work spans ticks, longer on a busy runner): a log beside every cell (a fuel
            // beside a combustible), in the bowl's wall where the cell touches it, else in an open neighbour: the crater's
            // shape follows the position hash, and a floor whose cells touched no wall placed no log (4 CI runs, 27.11)
            if (logs[0] == 0 && now.size() == law.cells() && level.getGameTime() < end - 200) {
                Set<BlockPos> cellSet = new HashSet<>(now);
                for (BlockPos c : now) {
                    BlockPos open = null;
                    int walls = 0;
                    for (net.minecraft.core.Direction d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                        BlockPos n = c.relative(d);
                        BlockState s = level.getBlockState(n);
                        if (s.getBlock() instanceof BaseFireBlock || !s.getFluidState().isEmpty() || cellSet.contains(n)) continue;
                        if (s.isAir()) {
                            if (open == null) open = n;
                            continue;
                        }
                        level.setBlock(n, Blocks.OAK_LOG.defaultBlockState(), 2);
                        logs[0]++;
                        walls++;
                    }
                    if (walls == 0 && open != null) {
                        level.setBlock(open, Blocks.OAK_LOG.defaultBlockState(), 2);
                        logs[0]++;
                    }
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getGameTime() > end + 2, "the law's burn time is not over");
            h.assertTrue(StrikeWorld.get(level).idle(), "the strike is not over");
            int left = 0;
            BlockPos lo = BlockPos.containing(at.x - MINE, at.y - 12, at.z - MINE), hi = BlockPos.containing(at.x + MINE, at.y + 12, at.z + MINE);
            for (BlockPos p : BlockPos.betweenClosed(lo, hi)) if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) left++;
            String got = String.format(Locale.ROOT, "doFireTick off: fuel cells %d (law %d), logs beside them %d, fire blocks left after %.1f s: %d",
                    most[0], law.cells(), logs[0], law.seconds(), left);
            Incoming.LOG.info("FUELFIRE {}", got);
            h.assertTrue(most[0] == law.cells(), got);
            h.assertTrue(logs[0] > 0, got);
            h.assertTrue(cells(level, at).isEmpty(), got);
            h.assertTrue(left == 0, got);
        });
    }

    @GameTest(template = "range", timeoutTicks = 21000, batch = "fuel_fire_missile", skyAccess = true)
    public static void missileFuelBurnsOnTheCraterFloorAsLongAsItLasts(GameTestHelper h) {
        groundTest(h, WeaponType.MISSILE);
    }

    /**
     * Shahed into the plank roof of a stone brick house: the roof breaks through, fuel falls through the hole onto the floor of the house and burns
     *  there - not a single cell in the air at the height of the former roof (complaint 24.10), and while it burns, each one is on a support.
     */
    @GameTest(template = "range", timeoutTicks = 600, batch = "fuel_fire_roof", skyAccess = true)
    public static void fuelFallsThroughABrokenRoofToTheFloor(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        calm(h);
        BlockPos c = h.absolutePos(CENTER);
        int roof = c.getY() + 5;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, 0, -6), c.offset(6, 4, 6))) {
            boolean wall = Math.abs(p.getX() - c.getX()) == 6 || Math.abs(p.getZ() - c.getZ()) == 6;
            level.setBlock(p, wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-6, 5, -6), c.offset(6, 5, 6))) level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        Vec3 at = new Vec3(c.getX() + 0.5, roof + 1, c.getZ() + 0.5);
        Set<BlockPos> ever = new HashSet<>();
        Warheads.detonate(level, WeaponType.DRONE, at, null, null);
        h.onEachTick(() -> standsOnSupport(h, level, at, ever));
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "hit queue is not empty");
            List<BlockPos> now = cells(level, at);
            String got = "cells " + now.stream().map(BlockPos::toShortString).toList() + ", floor — y " + c.getY() + ", roof - y " + roof;
            Incoming.LOG.info("FUELFIRE roof: {}", got);
            h.assertTrue(now.size() == WeaponType.DRONE.spec().pool().cells(), got);
            for (BlockPos p : now) h.assertTrue(p.getY() < roof, "fuel at roof height: " + got);
        });
    }

    /**
     * Rain wets the fuel it falls on, and a fuel fireball does not ignite wet fuel ({@code Warheads.soaked}, Kerr et al.
     * 1971): grass and a hay bale under open sky are soaked while it rains; the same under a roof stay dry; in clear
     * weather nothing is soaked. Weather is server-wide: own batch, clear weather restored.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "fuel_fire_rain", skyAccess = true)
    public static void rainSoaksExposedFuelOnly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos open = h.absolutePos(CENTER.offset(-4, 0, 0)), hay = h.absolutePos(CENTER.offset(-4, 0, 3)),
                covered = h.absolutePos(CENTER.offset(4, 0, 0));
        level.setBlock(open, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        level.setBlock(hay, Blocks.HAY_BLOCK.defaultBlockState(), 2);
        level.setBlock(covered, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        for (BlockPos p : BlockPos.betweenClosed(covered.offset(-1, 3, -1), covered.offset(1, 3, 1))) level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
        StrikeGameTests.afterTest(h, () -> level.setWeatherParameters(24000, 0, false, false));
        h.assertTrue(level.getBiome(open).value().getPrecipitationAt(open) == net.minecraft.world.level.biome.Biome.Precipitation.RAIN,
                "the platform's biome has no rain: " + level.getBiome(open));
        level.setWeatherParameters(24000, 0, false, false);
        h.startSequence()
                .thenWaitUntil(() -> h.assertFalse(level.isRaining(), "still raining"))
                .thenExecute(() -> h.assertFalse(Warheads.soaked(level, open), "fuel soaked in clear weather"))
                .thenExecute(() -> level.setWeatherParameters(0, 6000, true, false))
                .thenWaitUntil(() -> h.assertTrue(level.isRaining(), "no rain yet"))
                .thenExecute(() -> {
                    h.assertTrue(Warheads.soaked(level, open), "grass under the sky stays dry in rain");
                    h.assertTrue(Warheads.soaked(level, hay), "a hay bale under the sky stays dry in rain");
                    h.assertFalse(Warheads.soaked(level, covered), "grass under a roof is soaked");
                })
                .thenExecute(() -> level.setWeatherParameters(24000, 0, false, false))
                .thenSucceed();
    }

    /**
     * Fuel on the boardwalk above the ground; the flooring is gone (burnt out, broken) - the fuel drips down in its column to the ground and burns there; there is no fire left at the height of the flooring (there is no fuel nearby). A nearby player receives this via the network (review
     * FV, circle 2: checking the server status did not catch the sending): the first packet is a crater on the flooring, then on the ground,
     *  the same as the server; the crater chunk goes to him (entered, returned) - the current state is with him. As the client applies the
     *  package, holds the {@code FuelBurnTest.serverUpdateMovesTheClientSource}.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "fuel_fire_drip", skyAccess = true)
    public static void fuelDripsDownWhenItsSupportGoes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fireTicks(h);
        BlockPos c = h.absolutePos(CENTER);
        int deck = c.getY() + 3;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, 3, -2), c.offset(2, 3, 2))) level.setBlock(p, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        Vec3 at = new Vec3(c.getX() + 0.5, deck + 1, c.getZ() + 0.5);
        Watcher<S2C.FuelFire> watcher = Watcher.near(h, Vec3.atCenterOf(c.offset(8, 0, 0)), S2C.FuelFire.class);
        FuelFires.testPour(level, at, WeaponType.DRONE, 0, 64);
        int n = WeaponType.DRONE.spec().pool().cells();
        h.assertTrue(cells(level, at).size() == n, "not on the flooring " + n + " cells: " + cells(level, at));
        for (BlockPos p : cells(level, at)) h.assertTrue(p.getY() == deck + 1, "the cage is not on the floor: " + p.toShortString());
        Set<BlockPos> ever = new HashSet<>();
        long gone = level.getGameTime() + 5;
        level.setWeatherParameters(24000, 0, false, false);
        // the flooring disappears in one check tick, and the cells catch up with it in the next tick of the world: in that tick the support is not checked
        h.onEachTick(() -> {
            if (level.getGameTime() == gone) {
                for (BlockPos p : BlockPos.betweenClosed(c.offset(-2, 3, -2), c.offset(2, 3, 2))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
            } else standsOnSupport(h, level, at, ever);
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getGameTime() > gone + 2, "the flooring is still standing");
            List<BlockPos> now = cells(level, at);
            h.assertTrue(now.size() == n, "cells " + now.size());
            for (BlockPos p : now) h.assertTrue(p.getY() == c.getY(), "fuel not on the ground: " + p.toShortString());
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-3, 4, -3), c.offset(3, 4, 3))) {
                h.assertTrue(!(level.getBlockState(p).getBlock() instanceof BaseFireBlock), "fire at the height of the missing flooring: " + p.toShortString());
            }
            // the client knows where the fuel is burning now: there are new places in his condition (the source of smoke, light and hum are there)
            S2C.FuelFire state = state(level, at);
            h.assertTrue(state != null && state.cells().stream().allMatch(p -> p.getY() == c.getY()) && state.smothered() == 0,
                    "the state for the client has not moved: " + state);
            // and he received it: first the crater on the flooring, last - the current state
            List<S2C.FuelFire> got = watcher.got;
            h.assertTrue(got.size() >= 2, "the player nearby received crater packages: " + got.size());
            h.assertTrue(got.getFirst().cells().stream().allMatch(p -> p.getY() == deck + 1), "the first package is not a crater on the flooring: " + got.getFirst());
            h.assertTrue(got.getLast().equals(state), "the last thing the player received was not the server state: " + got.getLast() + " against " + state);
            int before = got.size();
            FuelFires.onChunkSent(new ChunkWatchEvent.Sent(watcher.player, level.getChunkAt(state.cells().getFirst()), level));
            h.assertTrue(got.size() == before + 1 && got.getLast().equals(state), "with the crater chunk the player did not receive its state: " + got.subList(before, got.size()));
        });
    }

    /**
     * Records one custom payload type sent to a nearby player. The player is on the server list consulted by
     * {@code PacketDistributor.sendToPlayersNear}, without joining the level or receiving chunks and entities.
     */
    static final class Watcher<T extends CustomPacketPayload> extends ServerGamePacketListenerImpl {
        final List<T> got = new ArrayList<>();
        private final Class<T> payloadType;

        private Watcher(ServerPlayer player, Connection connection, Class<T> payloadType) {
            super(player.server, connection, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
            this.payloadType = payloadType;
        }

        static <T extends CustomPacketPayload> Watcher<T> near(GameTestHelper h, Vec3 pos, Class<T> payloadType) {
            return in(h, h.getLevel(), pos, payloadType);
        }

        /** A watcher at {@code pos} in {@code level} (any dimension). */
        static <T extends CustomPacketPayload> Watcher<T> in(GameTestHelper h, ServerLevel level, Vec3 pos, Class<T> payloadType) {
            FakePlayer player = new FakePlayer(level, new GameProfile(UUID.randomUUID(), "fuel-fire-watcher"));
            player.setPos(pos);
            Connection connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            Watcher<T> watcher = new Watcher<>(player, connection, payloadType);
            // the list of players NeoForge is read-only: the list itself is a field
            List<ServerPlayer> players = ObfuscationReflectionHelper.getPrivateValue(PlayerList.class, level.getServer().getPlayerList(), "players");
            players.add(player);
            StrikeGameTests.afterTest(h, () -> players.remove(player));
            return watcher;
        }

        @Override
        public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
            if (packet instanceof ClientboundCustomPayloadPacket custom && payloadType.isInstance(custom.payload())) {
                got.add(payloadType.cast(custom.payload()));
            }
        }
    }

    /**
     * The cell has been filled up (the block is on fire) or flooded with water - its fuel has run out ahead of schedule: it goes off the register, and the client
     *  receives it in a crater state (a smaller area is burning), the others burn further.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "fuel_fire_smother", skyAccess = true)
    public static void coveredOrWashedCellsAreOutForTheClientToo(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fireTicks(h);
        level.setWeatherParameters(24000, 0, false, false);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        FuelFires.testPour(level, at, WeaponType.DRONE);
        S2C.FuelFire first = state(level, at);
        h.assertTrue(first != null && first.cells().size() == 5, "no crater: " + first);
        BlockPos sand = first.cells().get(1), water = first.cells().get(2);
        long when = level.getGameTime() + 3;
        h.onEachTick(() -> {
            if (level.getGameTime() != when) return;
            level.setBlock(sand, Blocks.SAND.defaultBlockState(), 3);
            level.setBlock(water, Blocks.WATER.defaultBlockState(), 3);
        });
        h.succeedWhen(() -> {
            h.assertTrue(level.getGameTime() > when + 1, "haven't fallen asleep yet");
            S2C.FuelFire now = state(level, at);
            h.assertTrue(now != null && now.smothered() == 0b110L, "the filled and flooded cells did not go out for the client: " + now);
            List<BlockPos> live = cells(level, at);
            h.assertTrue(live.size() == 3 && !live.contains(sand) && !live.contains(water), "in accounting " + live);
        });
    }

    /**
     * Rain does not extinguish a burning fuel pool (`FuelFires.RELIGHT`), but vanilla rain extinguishes fire
     * in its tick: 30 missile craters in rain burn almost all the time (fire in each cell for at least 90 % of ticks),
     * while each cell reignites no more than once per second (vanilla extinguishes rain-exposed fire once every
     * ~9 s on average). Report bookkeeping cost per tick in FUELFIRE (do not measure time on CI machines).
     */
    @GameTest(template = "range", timeoutTicks = 800, batch = "fuel_fire_rain", skyAccess = true)
    public static void rainDoesNotPutOutTheFuelOrStartALoop(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fireTicks(h);
        BlockPos c = h.absolutePos(CENTER);
        level.setWeatherParameters(0, 24000, true, false);
        List<Vec3> craters = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) for (int k = 0; k < 5; k++) craters.add(Vec3.atBottomCenterOf(c.offset(-20 + 8 * i, 0, -16 + 8 * k)));
        long relights0 = FuelFires.get(level).relights();
        for (Vec3 at : craters) FuelFires.testPour(level, at, WeaponType.MISSILE);
        long start = level.getGameTime();
        long[] cellTicks = {0}, fireTicks = {0};
        boolean[] rained = {false};
        h.onEachTick(() -> {
            for (Vec3 at : craters) {
                for (BlockPos p : cells(level, at)) {
                    cellTicks[0]++;
                    if (level.getBlockState(p).getBlock() instanceof BaseFireBlock) fireTicks[0]++;
                    rained[0] |= level.isRainingAt(p);
                }
            }
        });
        h.succeedWhen(() -> {
            long t = level.getGameTime() - start;
            h.assertTrue(t >= 600, "it's raining");
            double share = fireTicks[0] / (double) Math.max(1, cellTicks[0]);
            long relights = FuelFires.get(level).relights() - relights0;
            int cells = craters.size() * WeaponType.MISSILE.spec().pool().cells();
            String got = String.format(Locale.ROOT, "rain: %d crater, %d cells, fire %.1f %% tick, fire again %d for %.0f s (%.2f per cell per second); %s",
                    craters.size(), cells, 100 * share, relights, t / 20.0, relights / (cells * t / 20.0), FuelFires.get(level).stats());
            Incoming.LOG.info("FUELFIRE {}", got);
            h.assertTrue(rained[0], "there is no rain over the cells - the check is empty: " + got);
            h.assertTrue(share >= 0.9, got);
            h.assertTrue(relights <= cells * t / 20.0, got);
            level.setWeatherParameters(24000, 0, false, false);
        });
    }

    /**
     * The world extinguishes the fire of the cell with the next tick after ignition (Burnt in the rain extinguishes its fire every tick, try FV): the crater
     *  does not fight it - each subsequent attempt is twice as late, up to once in 10 s ({@code FuelFires.RELIGHT_MAX}). For 30 s this
     * 8 ignites the cell; tolerance - 10, and without indentation ~120 (once in {@code RELIGHT}, 5 tick, - review FV, circle 2: former
     *  check under vanilla rain, which extinguishes the fire no earlier than after 30 tick, I didn’t see the indentation). No less than 3:
     *  the fuel tries to burn to the end and for the client it burns according to the law - not a single cell runs out prematurely. batch -
     *  is the same as for rain: the ignition check counts itself according to its own cells, and not with the general counter of the world.
     */
    @GameTest(template = "range", timeoutTicks = 800, batch = "fuel_fire_rain", skyAccess = true)
    public static void aWorldThatPutsTheFireOutAtOnceIsNotFought(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        FuelFires.testPour(level, at, WeaponType.MISSILE);
        int n = WeaponType.MISSILE.spec().pool().cells();
        h.assertTrue(cells(level, at).size() == n, "fuel did not go down: " + cells(level, at));
        long start = level.getGameTime();
        Set<BlockPos> burning = new HashSet<>();
        int[] fires = {0};
        h.onEachTick(() -> {
            for (BlockPos p : cells(level, at)) {
                if (!(level.getBlockState(p).getBlock() instanceof BaseFireBlock)) {
                    burning.remove(p);
                } else if (burning.add(p)) {
                    // lit - goes out with the next tick
                    fires[0]++;
                } else {
                    level.removeBlock(p, false);
                    burning.remove(p);
                }
            }
        });
        h.succeedWhen(() -> {
            long t = level.getGameTime() - start;
            h.assertTrue(t >= 600, "checking in progress");
            S2C.FuelFire state = state(level, at);
            String got = String.format(Locale.ROOT, "kill: %d cells, igniting %d for %.0f s (%.1f per cell); %s", n, fires[0], t / 20.0,
                    fires[0] / (double) n, state);
            Incoming.LOG.info("FUELFIRE {}", got);
            h.assertTrue(fires[0] <= 10 * n, "the world extinguishes the fire, and the cells are lit again and again: " + got);
            h.assertTrue(fires[0] >= 3 * n, "the fuel stopped burning: " + got);
            h.assertTrue(cells(level, at).size() == n && state != null && state.smothered() == 0, "fuel ran out ahead of schedule: " + got);
        });
    }

    /**
     * A cell is only where the neighboring chunks are ready. On a partial support (bottom plate: the top is not solid) vanilla, deciding whether
     *  holds the fire, looks at all the neighbors of the cell to see if something is burning nearby, and at the edge of the chunk next to the unready neighbor
     *  this would raise it right into the tick (review FV, circle 2: on solid ground Vanilla does not read the neighbor’s previous check, and
     *  did not see the protection). There is a stone slab at the eastern edge of the far chunk, to the west of the cage there is a board (the fire has something to hold on to): while the neighbors of the chunk are below full load, the fuel does not go there and the neighbor does not rise; ready - vanilla
     *  holds fire there (check is not empty).
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "fuel_fire_edge", skyAccess = true)
    public static void fuelAtAChunkEdgeDoesNotLoadTheNeighbour(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        var chunks = level.getChunkSource();
        ChunkPos f = new ChunkPos(h.absolutePos(CENTER).east(1600));
        BlockPos slab = new BlockPos(f.getMaxBlockX(), 250, f.getMiddleBlockZ()), cell = slab.above();
        Vec3 at = Vec3.atBottomCenterOf(cell);
        chunks.addRegionTicket(HOLD, f, 1, f);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(Terrain.neighbourhoodReady(level, f.x, f.z), "chunk with neighbors is not ready"))
                .thenExecute(() -> {
                    // builds while neighbors are ready (vanilla and Sable read them when placing a block)
                    level.setBlock(slab, Blocks.STONE_SLAB.defaultBlockState(), 3);
                    level.setBlock(cell.west(), Blocks.OAK_PLANKS.defaultBlockState(), 3);
                    // and neighbors - below full load
                    chunks.addRegionTicket(HOLD, f, 0, f);
                    chunks.removeRegionTicket(HOLD, f, 1, f);
                    chunks.runDistanceManagerUpdates();
                    h.assertTrue(chunks.getChunkNow(f.x + 1, f.z) == null, "neighbor is still ready - check is empty");
                    int lit = FuelFires.testPour(level, at, WeaponType.DRONE);
                    h.assertTrue(chunks.getChunkNow(f.x + 1, f.z) == null, "fuel at the edge raised the neighboring chunk to tick");
                    h.assertTrue(lit == 0, "cells at the edge without ready neighbors: " + lit);
                    chunks.addRegionTicket(HOLD, f, 1, f);
                    chunks.removeRegionTicket(HOLD, f, 0, f);
                })
                .thenWaitUntil(() -> h.assertTrue(Terrain.neighbourhoodReady(level, f.x, f.z), "chunk with neighbors is not ready"))
                .thenExecute(() -> {
                    h.assertTrue(BaseFireBlock.getState(level, cell).canSurvive(level, cell), "vanilla does not hold fire on the stove near the board - check is empty");
                    chunks.removeRegionTicket(HOLD, f, 1, f);
                })
                .thenSucceed();
    }

    /**
     * The fuel burns out while the cell chunk is not ready (the player has left) - the cell waits, and the fire is removed when the chunk is ready again
     *  (for vanilla with doFireTick = false it would last forever). Chunk is far from the site: ticket with neighbors - ready, without neighbors -
     *  not.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "fuel_fire_unload", skyAccess = true)
    public static void fuelThatBurnsOutUnloadedIsPutOutWhenItLoads(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        fireTicks(h);
        StrikeGameTests.gameSpeed(h);
        var chunks = level.getChunkSource();
        ChunkPos f = new ChunkPos(h.absolutePos(CENTER).west(1600));
        BlockPos mid = new BlockPos(f.getMiddleBlockX(), 250, f.getMiddleBlockZ());
        Vec3 at = Vec3.atBottomCenterOf(mid.above());
        long[] pouredAt = {0};
        chunks.addRegionTicket(HOLD, f, 1, f);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(Terrain.neighbourhoodReady(level, f.x, f.z), "chunk with neighbors is not ready"))
                .thenExecute(() -> {
                    for (BlockPos p : BlockPos.betweenClosed(mid.offset(-3, 0, -3), mid.offset(3, 0, 3))) level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
                    // the fuel is almost burned out: the cells go out according to the law in the next 10 tick
                    long burned = Math.round(20 * WeaponType.DRONE.spec().pool().seconds()) - 10;
                    h.assertTrue(FuelFires.testPour(level, at, WeaponType.DRONE, burned) == 5, "fuel did not reach the site");
                    pouredAt[0] = level.getGameTime();
                    // and the chunk immediately ceases to be ready: neighbors are below full load
                    chunks.addRegionTicket(HOLD, f, 0, f);
                    chunks.removeRegionTicket(HOLD, f, 1, f);
                    chunks.runDistanceManagerUpdates();
                    h.assertTrue(!Terrain.neighbourhoodReady(level, f.x, f.z), "chunk is still ready");
                })
                .thenWaitUntil(() -> h.assertTrue(level.getGameTime() > pouredAt[0] + 60, "we are waiting for the deadline"))
                .thenExecute(() -> {
                    h.assertTrue(cells(level, at).size() == 5, "burnt-out cells of an unfinished chunk are no longer counted: " + cells(level, at));
                    for (BlockPos p : cells(level, at)) h.assertTrue(level.getBlockState(p).getBlock() instanceof BaseFireBlock, "the fire in the unfinished chunk is removed");
                    chunks.addRegionTicket(HOLD, f, 1, f);
                    chunks.removeRegionTicket(HOLD, f, 0, f);
                })
                .thenWaitUntil(() -> {
                    h.assertTrue(cells(level, at).isEmpty(), "the cells are not removed when the chunk is ready");
                    for (BlockPos p : BlockPos.betweenClosed(mid.offset(-3, 1, -3), mid.offset(3, 1, 3))) {
                        h.assertTrue(!(level.getBlockState(p).getBlock() instanceof BaseFireBlock), "fire over fuel supply: " + p.toShortString());
                    }
                })
                .thenExecute(() -> chunks.removeRegionTicket(HOLD, f, 1, f))
                .thenSucceed();
    }

    /** check ticket: holds the distant chunk while the check is in progress. */
    private static final net.minecraft.server.level.TicketType<ChunkPos> HOLD = net.minecraft.server.level.TicketType.create("incoming_test_fuel",
            java.util.Comparator.comparingLong(ChunkPos::toLong));

    /** The state of the crater is {@code at} for the client (its own), null is not there. */
    private static S2C.FuelFire state(ServerLevel level, Vec3 at) {
        return FuelFires.get(level).state().stream().filter(s -> Vec3.atCenterOf(s.cells().getFirst()).distanceTo(at) < MINE).findFirst().orElse(null);
    }

    /** On water, the fuel does not retain combustion as a film (like the client’s smoke): there is no fire. */
    @GameTest(template = "range", timeoutTicks = 40, batch = "fuel_fire_water", skyAccess = true)
    public static void fuelOnWaterDoesNotBurn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(CENTER);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-4, -2, -4), c.offset(4, -1, 4))) level.setBlock(p, Blocks.WATER.defaultBlockState(), 2);
        Vec3 at = new Vec3(c.getX() + 0.5, c.getY() - 0.1, c.getZ() + 0.5);
        int lit = FuelFires.testPour(level, at, WeaponType.DRONE);
        h.assertTrue(lit == 0 && cells(level, at).isEmpty(), "burning on the water " + lit + " cells");
        h.succeed();
    }
}
