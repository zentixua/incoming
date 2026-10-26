package ua.zentix.incoming.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.defense.DefenseWorld;
import ua.zentix.incoming.defense.Interceptor;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.defense.Radar;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.FlightLog;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Sides;
import ua.zentix.incoming.target.Sightings;
import ua.zentix.incoming.target.Target;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * SAM ({@link SamBlockEntity}): shoots down other people's projectiles from the mod within the firing range - one rocket at the target, with a reloading
 *  guide; does not touch his own, team, distant ones, ICBM and “Grad”; does not fire without rockets; the target outside of loading hits without
 *  loading a single chunk; The radar does not see the target behind the hill. The block is charged with a crater (it cannot be removed), breaks and
 *  explodes with its contents, the recipe depends on whether Create is worth it. Each check has its own batch: SAM sees all the
 *  shells in the world, and the next check would throw off its missile count. Ranges - spec SAM; guides and reloading - during the
 *  check ({@link #air}), everything returns after it. The walls of the site (barriers along its height) block the radar from low
 *  targets behind them: distant targets are higher.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SamGameTests {
    /** SAM on the runway "runway": the ground of the runway is y 3, Shahed fly over it at +Z to its end (z 200). */
    private static final BlockPos SAM = new BlockPos(16, 4, 100);
    /** The height of Shahed above the beginning of the site. */
    private static final int DRONE_Y = 50;
    /** The middle of the site “range” and its ground (first air - y 11). */
    private static final BlockPos RANGE_CENTER = new BlockPos(32, 11, 32);

    private SamGameTests() {}

    // ---------------------------------------------------------------- knocks down strangers

    /**
     * Two alien Shaheds (without an owner) are flying over SAM: each has one missile, both are shot down in the air (debris, log line
     *  “shot down by an anti-aircraft missile”), the targets at the end of the runway are intact. There is one guide, reload time is a second: the first rocket
     *  - no earlier than a second after installation, the second - a second after the first; of the three missiles, one remains.
     * The missile is visible (its appearance in the world). Anxiety - to the crew (the owner of SAM), to someone else nearby - no; targets - on the map of the calculation side.
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "sam_kill", skyAccess = true)
    public static void shootsDownHostileDrones(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 1, 1);
        Listener crew = listener(h, "sam_crew", SAM.east(3)), stranger = listener(h, "sam_stranger", SAM.west(3));
        long placed = level.getGameTime();
        SamBlockEntity sam = sam(h, SAM, crew.getUUID(), 3);
        FlightLog log = StrikeWorld.get(level).flightLog();
        int intercepted = log.total(FlightLog.Event.INTERCEPTED);
        DroneEntity a = drone(h, 10, null), b = drone(h, 22, null);
        Launches launches = new Launches(level, crew.getUUID());
        AABB runway = new AABB(Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(-8, 0, -8))), Vec3.atLowerCornerOf(h.absolutePos(new BlockPos(40, 120, 264))));
        boolean[] debris = {false};
        h.onEachTick(() -> {
            launches.watch();
            if (!debris[0]) debris[0] = !level.getEntitiesOfClass(DebrisEntity.class, runway).isEmpty();
        });
        h.succeedWhen(() -> {
            h.assertTrue(a.isRemoved() && b.isRemoved(), "shahed are still flying: " + h.relativeVec(a.position()) + ", " + h.relativeVec(b.position()));
            h.assertTrue(log.total(FlightLog.Event.INTERCEPTED) - intercepted == 2, "shot down " + (log.total(FlightLog.Event.INTERCEPTED) - intercepted) + " from 2");
            h.assertTrue(!BlastLog.near(h, new BlockPos(10, 3, 200), 8) && !BlastLog.near(h, new BlockPos(22, 3, 200), 8), "shahed reached the target");
            h.assertTrue(launches.targets().equals(Set.of(a.getUUID(), b.getUUID())) && launches.count() == 2,
                    "missiles " + launches.count() + " by " + launches.targets());
            List<Long> at = launches.ticks();
            h.assertTrue(at.get(0) - placed >= 20, "first rocket through " + (at.get(0) - placed) + " tick after installation - no recharging");
            h.assertTrue(at.get(1) - at.get(0) >= 20, "second rocket through " + (at.get(1) - at.get(0)) + " tick after the first - no reload");
            h.assertTrue(sam.ready() + sam.stockCount() == 1, "missiles left " + (sam.ready() + sam.stockCount()) + " from 1");
            h.assertTrue(launches.viewed, "the rocket was not visible in the world");
            h.assertTrue(debris[0], "the downed Shahed did not fall to pieces");
            h.assertTrue(crew.alerts() >= 1, "there was no alarm");
            h.assertTrue(stranger.alerts() == 0, "stranger anxiety");
            String side = Sides.side(crew);
            h.assertTrue(Sightings.contact(level, side, a.getUUID()) != null && Sightings.contact(level, side, b.getUUID()) != null,
                    "radar targets are not on the side map");
        });
    }

    // ---------------------------------------------------------------- does not touch his own

    /**
     * Shahed of the SAM owner and Shahed of his teammate ({@code /team}) are flying over the SAM - there is no launch, the missiles are intact,
     *  there is no alarm, both reach the target. The same alien radar sees them as aliens and would hit them (checking that they are visible at all).
     */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "sam_friend", skyAccess = true)
    public static void ignoresOwnAndTeam(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 4, 0);
        Scoreboard board = level.getScoreboard();
        PlayerTeam blue = board.addPlayerTeam("sam_blue");
        StrikeGameTests.afterTest(h, () -> board.removePlayerTeam(blue));
        Listener owner = listener(h, "sam_owner", SAM.east(3)), mate = listener(h, "sam_mate", SAM.west(3));
        board.addPlayerToTeam(owner.getScoreboardName(), blue);
        board.addPlayerToTeam(mate.getScoreboardName(), blue);
        h.assertTrue(Sides.friendly(level.getServer(), owner.getUUID(), mate.getUUID()), "the team is not our own");
        SamBlockEntity sam = sam(h, SAM, owner.getUUID(), 4);
        DroneEntity own = drone(h, 10, owner.getUUID()), team = drone(h, 22, mate.getUUID());
        Launches launches = new Launches(level, owner.getUUID());
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SAM)).add(0, 1, 0);
        UUID stranger = UUID.randomUUID();
        Set<UUID> seenFriendly = new HashSet<>(), seenHostileByStranger = new HashSet<>();
        h.onEachTick(() -> {
            launches.watch();
            for (Radar.Track t : scan(level, antenna, owner.getUUID())) {
                h.assertFalse(t.hostile() || t.engageable(), "your Shahed " + t.id() + " alien to the owner's radar");
                seenFriendly.add(t.id());
            }
            for (Radar.Track t : scan(level, antenna, stranger)) if (t.engageable()) seenHostileByStranger.add(t.id());
        });
        h.succeedWhen(() -> {
            h.assertTrue(own.isRemoved() && team.isRemoved(), "shahed are still flying");
            h.assertTrue(launches.count() == 0, "launched missiles at friendly forces: " + launches.count());
            h.assertTrue(sam.ready() + sam.stockCount() == 4, "missiles " + (sam.ready() + sam.stockCount()) + " from 4");
            h.assertTrue(seenFriendly.containsAll(Set.of(own.getUUID(), team.getUUID())), "the owner’s radar did not see his own");
            h.assertTrue(seenHostileByStranger.containsAll(Set.of(own.getUUID(), team.getUUID())), "someone else's radar would not hit them - the check is empty");
            h.assertTrue(owner.alerts() == 0 && mate.alerts() == 0, "worry about your own");
            // explosions at both targets (there is no crater on the stone - according to the law of charge)
            h.assertTrue(BlastLog.near(h, new BlockPos(10, 3, 200), 8) && BlastLog.near(h, new BlockPos(22, 3, 200), 8), "your Shahed did not reach");
        });
    }

    // ---------------------------------------------------------------- long-range, ICBM and "Grad"

    /**
     * Alien Shahed out of the world between the fire range and the radar range (spec SAM) walks by: the radar sees it - it is on the
     *  side map - but there is no launch. It is to +Z, behind the far wall of the site (to −Z and −X there are the former batch with their
     *  buildings), and higher than it covers.
     */
    @GameTest(template = "runway", timeoutTicks = 100, batch = "sam_range", skyAccess = true)
    public static void ignoresOutOfRange(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 4, 0);
        UUID owner = UUID.randomUUID();
        SamBlockEntity sam = sam(h, SAM, owner, 4);
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SAM)).add(0, 1, 0);
        double between = (InterceptorSpec.SAM.engageRange() + InterceptorSpec.SAM.radarRange()) / 2;
        DroneEntity far = virtualDrone(h, antenna.add(0, 150, between), antenna.add(4000, 150, between), null);
        Launches launches = new Launches(level, owner);
        boolean[] seen = {false};
        h.onEachTick(() -> {
            launches.watch();
            for (Radar.Track t : scan(level, antenna, owner)) {
                if (!t.id().equals(far.getUUID())) continue;
                h.assertTrue(t.hostile() && !t.engageable(), "distant Shahed: alien " + t.hostile() + ", beat " + t.engageable());
                seen[0] = true;
            }
        });
        h.runAtTickTime(80, () -> {
            h.assertTrue(seen[0], "radar didn't see Shahed " + (int) between + " blocks");
            h.assertTrue(Sightings.contact(level, Sides.side(level.getServer(), owner), far.getUUID()) != null, "distant Shahed is not on the side map");
            h.assertTrue(launches.count() == 0, "launch at a target beyond the firing range");
            h.assertTrue(sam.ready() + sam.stockCount() == 4, "missiles spent");
            h.succeed();
        });
    }

    /**
     * The hill covers the target: a wall of stone 12 blocks from the radar, 20 blocks high. Shahed low above her does not see the radar,
     *  Shahed high above her does; the one behind the wall is visible to the radar on top of it (it's the wall that obscures it, not the range).
     * The wall is removed after checking.
     */
    @GameTest(template = "range", timeoutTicks = 40, batch = "sam_terrain", skyAccess = true)
    public static void hillHidesLowTarget(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        UUID owner = UUID.randomUUID();
        BlockPos from = new BlockPos(20, 11, 20), to = new BlockPos(22, 30, 44);
        for (BlockPos p : BlockPos.betweenClosed(from, to)) h.setBlock(p, Blocks.STONE);
        StrikeGameTests.afterTest(h, () -> {
            for (BlockPos p : BlockPos.betweenClosed(from, to)) level.setBlock(h.absolutePos(p), Blocks.AIR.defaultBlockState(), 3);
        });
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(new BlockPos(8, 11, 32))).add(0, 1, 0);
        Vec3 hilltop = Vec3.atCenterOf(h.absolutePos(new BlockPos(21, 34, 32))).add(0, 1, 0);
        DroneEntity low = hovering(h, new BlockPos(40, 14, 32)), high = hovering(h, new BlockPos(40, 80, 32));
        h.runAtTickTime(2, () -> {
            Set<UUID> seen = new HashSet<>(), fromTop = new HashSet<>();
            for (Radar.Track t : scan(level, antenna, owner)) seen.add(t.id());
            for (Radar.Track t : scan(level, hilltop, owner)) fromTop.add(t.id());
            h.assertFalse(seen.contains(low.getUUID()), "shahed is visible behind the hill: " + h.relativeVec(low.position()));
            h.assertTrue(seen.contains(high.getUUID()), "shahed is not visible above the hill: " + h.relativeVec(high.position()));
            h.assertTrue(fromTop.contains(low.getUUID()), "from the top of the Shahed hill is not visible behind it: " + h.relativeVec(low.position()));
            h.succeed();
        });
    }

    /** Shahed at {@code at}, flies at +X to the edge of the area; removed after inspection. */
    private static DroneEntity hovering(GameTestHelper h, BlockPos at) {
        ServerLevel level = h.getLevel();
        DroneEntity d = ModEntities.DRONE.get().create(level);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(at));
        Vec3 aim = Vec3.atCenterOf(h.absolutePos(new BlockPos(60, at.getY(), at.getZ())));
        d.launch(start, new Target.Point(aim), aim, null);
        level.addFreshEntity(d);
        StrikeGameTests.afterTest(h, () -> {
            if (!d.isRemoved()) d.discard();
        });
        return d;
    }

    /**
     * ICBM takes off in 30 blocks from SAM, "Grad" falls nearby: the radar sees both as strangers, but does not hit - ICBM is not its target,
     *  "Grad" is not worth a missile (spec: {@code Signature.intercept}).
     */
    @GameTest(template = "range", timeoutTicks = 800, batch = "sam_skip", skyAccess = true)
    public static void skipsIcbmAndGrad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 4, 0);
        UUID owner = UUID.randomUUID();
        BlockPos at = new BlockPos(10, 11, 10);
        SamBlockEntity sam = sam(h, at, owner, 4);
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(at)).add(0, 1, 0);
        Vec3 pad = Vec3.atBottomCenterOf(h.absolutePos(RANGE_CENTER));
        IcbmEntity icbm = ModEntities.ICBM.get().create(level);
        // target - on the site: the target area ICBM takes from 7500 blocks, the distant target would generate the world
        icbm.prepare(pad, pad.add(20, 0, 0), null);
        level.addFreshEntity(icbm);
        Vec3 point = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(52, 11, 52)));
        RocketEntity grad = ModEntities.ROCKET.get().create(level);
        grad.launchFrom(point.add(0, 0, -300), new Target.Point(point), point, null);
        VirtualFlights.launch(level, grad);
        StrikeGameTests.afterTest(h, () -> {
            if (!icbm.isRemoved()) icbm.discard();
            VirtualFlights.get(level).clear(level, p -> p == grad);
        });
        UUID gradId = grad.getUUID();
        Launches launches = new Launches(level, owner);
        Set<UUID> seen = new HashSet<>();
        h.onEachTick(() -> {
            launches.watch();
            for (Radar.Track t : scan(level, antenna, owner)) {
                if (!t.id().equals(icbm.getUUID()) && !t.id().equals(gradId)) continue;
                h.assertTrue(t.hostile() && !t.engageable(), t.projectile().weapon() + ": stranger " + t.hostile() + ", beat " + t.engageable());
                seen.add(t.id());
            }
            h.assertTrue(launches.count() == 0, "launch by " + launches.targets());
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen.contains(icbm.getUUID()), "ICBM radar did not see");
            h.assertTrue(seen.contains(gradId), "\"Grad\" was not seen by radar");
            h.assertTrue(level.getEntity(gradId) == null && VirtualFlights.get(level).flights().stream().noneMatch(p -> p.getUUID().equals(gradId)),
                    "\"Grad\" is still flying");
            h.assertTrue(sam.ready() + sam.stockCount() == 4, "missiles spent");
        });
    }

    // ---------------------------------------------------------------- without missiles

    /** SAM without missiles: alien Shahed passes over it and hits the target, no launch. */
    @GameTest(template = "runway", timeoutTicks = 600, batch = "sam_empty", skyAccess = true)
    public static void emptyDoesNotFire(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 4, 0);
        UUID owner = UUID.randomUUID();
        SamBlockEntity sam = sam(h, SAM, owner, 0);
        DroneEntity d = drone(h, 16, null);
        Launches launches = new Launches(level, owner);
        boolean[] engageable = {false};
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SAM)).add(0, 1, 0);
        h.onEachTick(() -> {
            launches.watch();
            for (Radar.Track t : scan(level, antenna, owner)) if (t.id().equals(d.getUUID()) && t.engageable()) engageable[0] = true;
        });
        h.succeedWhen(() -> {
            h.assertTrue(d.isRemoved(), "shahed is still flying");
            h.assertTrue(engageable[0], "shahed was not in firing range - check is empty");
            h.assertTrue(launches.count() == 0 && sam.ready() == 0, "launch without rockets");
            // detonation at the target (there is no crater on the stone - according to the law of charge; there is nothing to break there)
            h.assertTrue(BlastLog.near(h, new BlockPos(16, 3, 200), 8), "shahed missed the target");
        });
    }

    // ---------------------------------------------------------------- Doesn't load chunks

    /**
     * Alien Shahed is outside the world - in 600 blocks to +X (there are no sites there), goes further: SAM hits him, the missile flies outside the
     *  loading and knocks him down there; its appearance is only above the ticking chunks of SAM. Not a single chunk of the strip from SAM to Shahed became loaded during the entire flight - neither the radar,
     * , nor the rocket flight, nor the gap in the chunks was loaded or a ticket was issued. The streak starts in 96 blocks from SAM: tickets
     *  sites (level 31) give holders to chunks up to ~13 chunks around, but only the closest two are loaded.
     */
    @GameTest(template = "runway", timeoutTicks = 400, batch = "sam_far", skyAccess = true)
    public static void loadsNoChunks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 4, 0);
        UUID owner = UUID.randomUUID();
        SamBlockEntity sam = sam(h, SAM, owner, 1);
        Vec3 antenna = Vec3.atCenterOf(h.absolutePos(SAM)).add(0, 1, 0);
        // above the site wall in 16 blocks to +X (its top is 3,5 blocks above the antenna): below the radar does not see it behind the wall
        DroneEntity far = virtualDrone(h, antenna.add(600, 200, 0), antenna.add(8000, 200, 0), null);
        FlightLog log = StrikeWorld.get(level).flightLog();
        int intercepted = log.total(FlightLog.Event.INTERCEPTED);
        // strip of chunks from 96 blocks behind SAM to Shahed at the end of the check and behind it, in three rows
        int cz = Math.floorDiv((int) Math.floor(antenna.z), 16);
        List<ChunkPos> strip = new ArrayList<>();
        for (int cx = Math.floorDiv((int) antenna.x + 96, 16); cx <= Math.floorDiv((int) antenna.x + 600 + 400, 16); cx++) {
            for (int dz = -1; dz <= 1; dz++) strip.add(new ChunkPos(cx, cz + dz));
        }
        List<String> loaded = loaded(level, strip);
        h.assertTrue(loaded.isEmpty(), "chunks of the strip are loaded before checking: " + loaded);
        Launches launches = new Launches(level, owner);
        boolean[] distantSustain = {false};
        h.onEachTick(() -> {
            launches.watch();
            var missileFlights = DefenseWorld.get(level).flights();
            var packets = ua.zentix.incoming.strike.FarFlights.sampleInterceptors(level, missileFlights, antenna, null, true);
            for (var packet : packets) {
                h.assertTrue(packet.interceptor(), "the marching path is marked SAM");
                var f = missileFlights.stream().filter(x -> x.id().equals(packet.id())).findFirst().orElseThrow();
                if (f.view() != null) h.assertTrue(f.view().getUUID().equals(packet.id()), "one UUID for the entity and the distant path");
                if (f.view() == null && f.age() > InterceptorSpec.SAM.boostTicks()) distantSustain[0] = true;
            }
            List<String> now = loaded(level, strip);
            h.assertTrue(now.isEmpty(), "SAM loaded chunks: " + now);
        });
        h.succeedWhen(() -> {
            h.assertTrue(VirtualFlights.get(level).flights().stream().noneMatch(p -> p.getUUID().equals(far.getUUID())), "shahed is still flying: "
                    + far.position().subtract(antenna));
            h.assertTrue(log.total(FlightLog.Event.INTERCEPTED) - intercepted == 1, "shahed out of the world is not shot down");
            h.assertTrue(launches.count() == 1 && sam.ready() + sam.stockCount() == 0, "missiles " + launches.count());
            h.assertFalse(launches.viewedUnloaded, "view of the rocket outside of loading");
            h.assertTrue(distantSustain[0], "the marching path behind the ticking chunks did not reach the smoke packets");
        });
    }

    // ---------------------------------------------------------------- block

    /**
     * SAM block: the crater above it puts anti-aircraft missiles in stock, the ability accepts only them (16 per cell, two
     *  cells), it cannot be removed with it - and the crater under it does not pull out; the comparator sees the margin. Broken - the
     *  itself and everything in it falls out (from the guides too); exploded - drops rockets. Pickaxe, stone or better. The recipe is with the exact
     *  mechanism Create, if Create is worth it, otherwise - vanilla.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "sam_block", skyAccess = true)
    public static void blockLoadsBreaksAndCrafts(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        air(h, 2, 0);
        BlockPos at = new BlockPos(20, 11, 20);
        SamBlockEntity sam = sam(h, at, null, 0);
        h.assertTrue(level.getBlockState(h.absolutePos(at)).is(BlockTags.MINEABLE_WITH_PICKAXE)
                && level.getBlockState(h.absolutePos(at)).is(BlockTags.NEEDS_STONE_TOOL), "SAM is not for stone pickaxe");
        IItemHandler cap = level.getCapability(Capabilities.ItemHandler.BLOCK, h.absolutePos(at), Direction.UP);
        h.assertTrue(cap != null, "sAM doesn't have item ability");
        h.assertTrue(cap.getSlots() == SamBlockEntity.SLOTS, "cells " + cap.getSlots());
        ItemStack dirt = new ItemStack(Items.DIRT, 5);
        h.assertTrue(insert(cap, dirt.copy()).getCount() == 5, "SAM took the land");
        h.assertTrue(insert(cap, new ItemStack(ModItems.INTERCEPTOR.get(), 40)).getCount() == 40 - 2 * 16, "not included in stock 32");
        for (int i = 0; i < cap.getSlots(); i++) h.assertTrue(cap.extractItem(i, 64, false).isEmpty(), "the rocket was taken out by the ability");
        int comparator = level.getBlockState(h.absolutePos(at)).getAnalogOutputSignal(level, h.absolutePos(at));
        h.assertTrue(comparator > 0, "the comparator does not see the reserve");
        // the crater under the SAM pulls up but won’t pull it out; crater puts on top
        BlockPos below = at.below(), above = at.above();
        h.setBlock(below, Blocks.HOPPER);
        h.setBlock(above, Blocks.HOPPER);
        int before = sam.stockCount() + sam.ready();
        h.startSequence()
                .thenExecute(() -> {
                    // make room: SAM places missiles on guides (2), stock - 30
                    h.assertTrue(sam.ready() <= 2, "more than two guides");
                    HopperBlockEntity top = h.getBlockEntity(above);
                    top.setItem(0, new ItemStack(ModItems.INTERCEPTOR.get(), 2));
                })
                .thenWaitUntil(() -> {
                    HopperBlockEntity top = h.getBlockEntity(above);
                    h.assertTrue(top.getItem(0).isEmpty(), "crater from above did not release the missiles");
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    HopperBlockEntity bottom = h.getBlockEntity(below);
                    h.assertTrue(bottom.isEmpty(), "crater under SAM pulled out missiles");
                    h.assertTrue(sam.stockCount() + sam.ready() == before + 2, "missiles " + (sam.stockCount() + sam.ready()) + " instead of " + (before + 2));
                    h.assertTrue(sam.ready() == 2, "on guides " + sam.ready());
                    level.setBlock(h.absolutePos(above), Blocks.AIR.defaultBlockState(), 3);
                    level.setBlock(h.absolutePos(below), Blocks.STONE.defaultBlockState(), 3);
                    AABB box = new AABB(h.absolutePos(at)).inflate(3);
                    level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
                    int loaded = sam.stockCount() + sam.ready();
                    level.destroyBlock(h.absolutePos(at), true);
                    h.assertTrue(level.getBlockState(h.absolutePos(at)).isAir(), "SAM is not broken");
                    h.assertTrue(dropped(level, box, ModItems.SAM.get().getDefaultInstance()) == 1, "broken SAM did not fall out");
                    h.assertTrue(dropped(level, box, ModItems.INTERCEPTOR.get().getDefaultInstance()) == loaded, "rockets fell "
                            + dropped(level, box, ModItems.INTERCEPTOR.get().getDefaultInstance()) + " from " + loaded);
                    level.getEntitiesOfClass(ItemEntity.class, box).forEach(ItemEntity::discard);
                    // explosion nearby: SAM breaks, missiles fall out
                    SamBlockEntity again = sam(h, at, null, 5);
                    Vec3 c = Vec3.atCenterOf(h.absolutePos(at));
                    level.explode(null, c.x + 1, c.y, c.z, 4f, Level.ExplosionInteraction.BLOCK);
                    h.assertTrue(level.getBlockState(h.absolutePos(at)).isAir(), "SAM survived an explosion nearby");
                    h.assertTrue(again.isRemoved(), "the SAM block entity remains");
                    h.assertTrue(dropped(level, box.inflate(3), ModItems.INTERCEPTOR.get().getDefaultInstance()) == 5, "exploded SAM did not drop missiles");
                    // recipe: with Create - precise mechanism, without it - vanilla; both SAM and anti-aircraft missile
                    var recipes = level.getServer().getRecipeManager();
                    boolean create = ModList.get().isLoaded("create");
                    for (String name : new String[]{"sam", "interceptor"}) {
                        h.assertTrue(recipes.byKey(Incoming.id(name)).isPresent() == create, "recipe " + name + " with Create: " + create);
                        h.assertTrue(recipes.byKey(Incoming.id(name + "_vanilla")).isPresent() == !create, "recipe " + name + "_vanilla without Create: " + !create);
                    }
                })
                .thenSucceed();
    }

    // ---------------------------------------------------------------- assistants

    /** Air defense rules for the duration of the test: guides and reload in seconds; return after it. */
    private static void air(GameTestHelper h, int rails, int reloadSeconds) {
        IncomingConfig.Server c = IncomingConfig.SERVER;
        int rails0 = c.samRails.get(), reload0 = c.samReloadSeconds.get();
        c.samRails.set(rails);
        c.samReloadSeconds.set(reloadSeconds);
        StrikeGameTests.afterTest(h, () -> {
            c.samRails.set(rails0);
            c.samReloadSeconds.set(reload0);
            DefenseWorld.get(h.getLevel()).clear();
        });
    }

    /** What the SAM radar sees in {@code antenna}: ranges - spec SAM. */
    private static List<Radar.Track> scan(ServerLevel level, Vec3 antenna, @Nullable UUID owner) {
        return Radar.scan(level, antenna, InterceptorSpec.SAM.radarRange(), InterceptorSpec.SAM.engageRange(), owner);
    }

    /**
     * Place SAM (the owner - as if this player had placed it) and put missiles in the reserve. After the check, the SAM is removed
     *  without falling out: the sites of the old checks are still there, and a charged SAM at the adjacent site would shoot down the shells of other people’s checks.
     */
    private static SamBlockEntity sam(GameTestHelper h, BlockPos at, @Nullable UUID owner, int missiles) {
        h.setBlock(at, ModBlocks.SAM.get());
        SamBlockEntity be = h.getBlockEntity(at);
        BlockPos abs = h.absolutePos(at);
        StrikeGameTests.afterTest(h, () -> {
            // without the block entity onRemove there is nothing to drop
            h.getLevel().removeBlockEntity(abs);
            h.getLevel().setBlock(abs, Blocks.AIR.defaultBlockState(), 3);
        });
        be.setOwner(owner);
        if (missiles > 0) h.assertTrue(be.load(new ItemStack(ModItems.INTERCEPTOR.get(), missiles)).isEmpty(), "missiles were not included in reserve");
        return be;
    }

    /** Shahed above the beginning of the strip, on the line {@code x}, - to its end. */
    private static DroneEntity drone(GameTestHelper h, int x, @Nullable UUID owner) {
        ServerLevel level = h.getLevel();
        DroneEntity d = ModEntities.DRONE.get().create(level);
        Vec3 start = Vec3.atCenterOf(h.absolutePos(new BlockPos(x, DRONE_Y, 4)));
        Vec3 aim = Vec3.atCenterOf(h.absolutePos(new BlockPos(x, 3, 200))).add(0, 0.5, 0);
        d.launch(start, new Target.Point(aim), aim, owner);
        level.addFreshEntity(d);
        StrikeGameTests.afterTest(h, () -> {
            if (!d.isRemoved()) d.discard();
        });
        return d;
    }

    /** Shahed, flying out of world from {@code from} to {@code to}; removed after inspection. */
    private static DroneEntity virtualDrone(GameTestHelper h, Vec3 from, Vec3 to, @Nullable UUID owner) {
        ServerLevel level = h.getLevel();
        DroneEntity d = ModEntities.DRONE.get().create(level);
        d.launch(from, new Target.Point(to), to, owner);
        VirtualFlights.launch(level, d);
        StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, p -> p == d));
        return d;
    }

    /** The ground of {@code at} is intact: not a single empty block in the ground layer 5×5 (there is no crater). */
    private static ItemStack insert(IItemHandler cap, ItemStack stack) {
        for (int i = 0; i < cap.getSlots() && !stack.isEmpty(); i++) stack = cap.insertItem(i, stack, false);
        return stack;
    }

    private static int dropped(ServerLevel level, AABB box, ItemStack like) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) if (e.getItem().is(like.getItem())) n += e.getItem().getCount();
        return n;
    }

    /**
     * Chunks from the list that are loaded or being loaded: ready chunk or holder with ticket loading level (≤ 33,
     * {@link FullChunkStatus#FULL}); above - only the neighbors ticket distribution holder, without loading.
     */
    private static List<String> loaded(ServerLevel level, List<ChunkPos> chunks) {
        int full = ChunkLevel.byStatus(FullChunkStatus.FULL);
        List<String> out = new ArrayList<>();
        for (ChunkPos c : chunks) {
            ChunkHolder holder = level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong());
            boolean ready = level.getChunkSource().getChunkNow(c.x, c.z) != null;
            if (ready || holder != null && holder.getTicketLevel() <= full) out.add(c + (holder != null ? " level " + holder.getTicketLevel() : ""));
        }
        return out;
    }

    /** The owner's anti-aircraft missiles {@code owner}, which were in flight for inspection, ticked when they were seen. */
    private static final class Launches {
        private final ServerLevel level;
        @Nullable
        private final UUID owner;
        private final Map<Interceptor, Long> seen = new IdentityHashMap<>();
        private final List<Interceptor> order = new ArrayList<>();
        /** For once the rocket had a view in the world. */
        boolean viewed;
        /** The view was when the rocket was not flying over a ticking chunk. */
        boolean viewedUnloaded;

        Launches(ServerLevel level, @Nullable UUID owner) {
            this.level = level;
            this.owner = owner;
        }

        void watch() {
            for (Interceptor f : DefenseWorld.get(level).flights()) {
                if (!Objects.equals(f.owner(), owner)) continue;
                if (seen.putIfAbsent(f, level.getGameTime()) == null) order.add(f);
                if (f.view() == null) continue;
                viewed = true;
                if (!level.isPositionEntityTicking(BlockPos.containing(f.position()))) viewedUnloaded = true;
            }
        }

        int count() {
            return order.size();
        }

        Set<UUID> targets() {
            Set<UUID> out = new HashSet<>();
            for (Interceptor f : order) out.add(f.target());
            return out;
        }

        /** tick launch in order. */
        List<Long> ticks() {
            List<Long> out = new ArrayList<>();
            for (Interceptor f : order) out.add(seen.get(f));
            return out;
        }
    }

    /**
     * The player is “online” for checking: in the list of world players (alarm, radar screen) and in the server player map by UUID
     *  (using it, {@link Sides} recognizes the team of the SAM owner; the server GameTest does not have a profile cache). Without tickets and packages;
     *  the chat lines that he received are in {@link #heard}. Removed after inspection.
     */
    private static Listener listener(GameTestHelper h, String name, BlockPos at) {
        ServerLevel level = h.getLevel();
        Listener p = new Listener(level, name);
        p.moveTo(Vec3.atBottomCenterOf(h.absolutePos(at)));
        level.players().add(p);
        Map<UUID, ServerPlayer> byUuid = playersByUuid(level.getServer().getPlayerList());
        byUuid.put(p.getUUID(), p);
        StrikeGameTests.afterTest(h, () -> {
            level.players().remove(p);
            byUuid.remove(p.getUUID(), p);
        });
        return p;
    }

    /** Map of server players by UUID (using it {@link Sides} recognizes the player’s team “online”). */
    @SuppressWarnings("unchecked")
    static Map<UUID, ServerPlayer> playersByUuid(PlayerList list) {
        try {
            Field f = PlayerList.class.getDeclaredField("playersByUUID");
            f.setAccessible(true);
            return (Map<UUID, ServerPlayer>) f.get(list);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Listener extends FakePlayer {
        final List<Component> heard = new ArrayList<>();

        Listener(ServerLevel level, String name) {
            super(level, new GameProfile(UUID.randomUUID(), name));
        }

        @Override
        public void sendSystemMessage(Component component, boolean bypassHiddenChat) {
            heard.add(component);
        }

        /** Lines "Air raid warning". */
        int alerts() {
            int n = 0;
            for (Component c : heard) if (c.getContents() instanceof TranslatableContents t && t.getKey().startsWith("incoming.sam.alert")) n++;
            return n;
        }
    }
}
