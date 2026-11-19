package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.defense.DefenseWorld;
import ua.zentix.incoming.defense.Interceptor;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.defense.Radar;
import ua.zentix.incoming.defense.SamBlockEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Homing;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.registry.ModBlocks;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.strike.FlightScale;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SAM against a cruise missile in the world, as a survival player meets it (CurseForge report 19.11.2026: "misses
 * every time, even a wave of one missile"): an unowned missile comes from 2.6 km out of the world toward the SAM or
 * past it, the SAM stands on open ground, in a forest clearing, among tall trees or in hills, with the chunks a
 * player's view would hold around it (the rest of the path is out of the world). Every interceptor's launch range,
 * height, closest approach and end are printed ({@code grep SAMCRUISE}); the missile must be shot down.
 * <p>
 * Sites are far from test platforms and flight scenarios, 16 384 blocks apart (more than the radar's 3 km): SAMs of
 * one batch never see each other's missiles. Flight scale is world-wide and {@link StrikeGameTests#flightScale} restores
 * the value it found, so each real-scale case has its own batch.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SamCruiseGameTests {
    private static final TicketType<ChunkPos> SITE = TicketType.create("incoming_test_sam_cruise", Comparator.comparingLong(ChunkPos::toLong));
    /** Chunks held around the SAM: a player's view (ticking to two chunks less, like simulation distance). */
    private static final int VIEW = 10;
    private static final int ORIGIN = -4_000_000, SPACING = 16_384;
    /** The missile starts this far from its aim, out of the world, beyond the radar's reach. */
    private static final double START = 2600;

    private SamCruiseGameTests() {}

    enum Ground {
        FLAT,
        /** Oak-like forest, crowns 7–10 blocks up; a clearing around the SAM. */
        FOREST,
        /** Spruce-like forest, crowns 12–20 blocks up. */
        TALL_FOREST,
        /** Rolling hills, ±15 blocks. */
        HILLS
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void flatOverhead(GameTestHelper h) {
        engage(h, 0, Ground.FLAT, 0);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void flatCrossing(GameTestHelper h) {
        engage(h, 1, Ground.FLAT, 300);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void forestOverhead(GameTestHelper h) {
        engage(h, 2, Ground.FOREST, 0);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void forestCrossing(GameTestHelper h) {
        engage(h, 3, Ground.FOREST, 300);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void tallForestOverhead(GameTestHelper h) {
        engage(h, 4, Ground.TALL_FOREST, 0);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void hillsOverhead(GameTestHelper h) {
        engage(h, 5, Ground.HILLS, 0);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void forestNear(GameTestHelper h) {
        engage(h, 9, Ground.FOREST, 120);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void hillsCrossing(GameTestHelper h) {
        engage(h, 10, Ground.HILLS, 300);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "flight_scale_sam_cruise_overhead", skyAccess = true)
    public static void realFlatOverhead(GameTestHelper h) {
        StrikeGameTests.flightScale(h, 1);
        engage(h, 6, Ground.FLAT, 0);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "flight_scale_sam_cruise_crossing", skyAccess = true)
    public static void realFlatCrossing(GameTestHelper h) {
        StrikeGameTests.flightScale(h, 1);
        engage(h, 7, Ground.FLAT, 300);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "flight_scale_sam_cruise_forest", skyAccess = true)
    public static void realForestOverhead(GameTestHelper h) {
        StrikeGameTests.flightScale(h, 1);
        engage(h, 8, Ground.FOREST, 0);
    }

    /** One interceptor's flight as the test saw it. */
    private static final class Shot {
        final long launched;
        final double range;
        Vec3 pos;
        double top = Double.NEGATIVE_INFINITY, closest = Double.POSITIVE_INFINITY;

        Shot(long launched, double range, Vec3 pos) {
            this.launched = launched;
            this.range = range;
            this.pos = pos;
        }
    }

    /**
     * SAM at site {@code cell} on {@code ground}; an unowned cruise missile from {@code START} blocks out (from +X)
     * at a point {@code aside} blocks from the SAM across its course.
     */
    private static void engage(GameTestHelper h, int cell, Ground ground, double aside) {
        ServerLevel level = h.getLevel();
        String name = h.testInfo.getTestName();
        ChunkPos centre = new ChunkPos((ORIGIN - cell * SPACING) >> 4, ORIGIN >> 4);
        level.getChunkSource().addRegionTicket(SITE, centre, VIEW, centre);
        StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(SITE, centre, VIEW, centre));
        for (int dx = -VIEW; dx <= VIEW; dx++)
            for (int dz = -VIEW; dz <= VIEW; dz++) level.getChunk(centre.x + dx, centre.z + dz);
        BlockPos base = new BlockPos(centre.getMiddleBlockX(), 0, centre.getMiddleBlockZ());
        int groundY = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, base.getX(), base.getZ());
        shape(level, centre, groundY, ground);
        BlockPos samPos = new BlockPos(base.getX(), Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, base.getX(), base.getZ()), base.getZ());
        level.setBlock(samPos, ModBlocks.SAM.get().defaultBlockState(), Block.UPDATE_ALL);
        SamBlockEntity sam = (SamBlockEntity) level.getBlockEntity(samPos);
        h.assertTrue(sam != null, "no SAM block entity");
        StrikeGameTests.afterTest(h, () -> {
            level.removeBlockEntity(samPos);
            level.setBlock(samPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        });
        sam.setOwner(null);
        h.assertTrue(sam.load(new ItemStack(ModItems.INTERCEPTOR.get(), 8)).isEmpty(), "missiles not stocked");
        Vec3 antenna = Vec3.atCenterOf(samPos).add(0, 1, 0);
        double aimX = samPos.getX() + 0.5, aimZ = samPos.getZ() + 0.5 + aside;
        Vec3 aim = new Vec3(aimX, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(aimX), Mth.floor(aimZ)) + 0.5, aimZ);

        StrikeProjectile[] missile = {null};
        Vec3[] missileAt = {null};
        long[] seen = {-1}, launchedAt = {-1};
        double[] seenRange = {-1};
        boolean[] gone = {false}, told = {false};
        Map<Interceptor, Shot> shots = new IdentityHashMap<>();
        List<Interceptor> order = new ArrayList<>();
        h.onEachTick(() -> {
            long now = level.getGameTime();
            if (missile[0] == null) {
                // a player's SAM has stood loaded: launch once all four rails are up and the site ticks
                if (sam.ready() < 4 || !level.isPositionEntityTicking(samPos)) return;
                StrikeProjectile p = ModEntities.CRUISE_MISSILE.get().create(level);
                Vec3 start = new Vec3(aim.x + START, aim.y, aim.z);
                p.launch(start, new Target.Point(aim), aim, null);
                p.setRoute(Route.direct());
                VirtualFlights.launch(level, p);
                StrikeGameTests.afterTest(h, () -> VirtualFlights.get(level).clear(level, q -> q.getUUID().equals(p.getUUID())));
                missile[0] = p;
                launchedAt[0] = now;
                Incoming.LOG.info("SAMCRUISE {} scale {} ground {}: missile launched at {} toward {}, cruise {} blocks/tick at {} blocks", name,
                        FlightScale.x(), ground, BlockPos.containing(p.position()), BlockPos.containing(aim), String.format(Locale.ROOT, "%.2f", p.airframe().cruiseSpeed()),
                        String.format(Locale.ROOT, "%.1f", p.position().y - aim.y));
                return;
            }
            StrikeProjectile m = find(level, missile[0]);
            if (m != null) {
                missile[0] = m;
                if (seen[0] < 0) {
                    for (Radar.Track t : Radar.scan(level, antenna, InterceptorSpec.SAM.radarRange(), InterceptorSpec.SAM.engageRange(), null)) {
                        if (t.id().equals(m.getUUID())) {
                            seen[0] = now;
                            seenRange[0] = t.distance();
                        }
                    }
                }
            } else if (!gone[0]) {
                gone[0] = true;
            }
            Vec3 mPrev = missileAt[0], mNow = m != null ? m.position() : mPrev;
            for (Interceptor f : DefenseWorld.get(level).flights()) {
                if (!f.target().equals(missile[0].getUUID()) || shots.containsKey(f)) continue;
                shots.put(f, new Shot(now, mNow == null ? -1 : mNow.distanceTo(antenna), f.position()));
                order.add(f);
            }
            for (Interceptor f : order) {
                Shot s = shots.get(f);
                if (f.done() && s.pos == null) continue;
                Vec3 next = f.position();
                if (mPrev != null && mNow != null) {
                    double k = Homing.closest(s.pos, next, mPrev, mNow);
                    s.closest = Math.min(s.closest, Homing.distanceAt(s.pos, next, mPrev, mNow, k));
                }
                s.top = Math.max(s.top, next.y - groundY);
                s.pos = f.done() ? null : next;
            }
            missileAt[0] = mNow;
        });
        h.succeedWhen(() -> {
            boolean flying = DefenseWorld.get(level).flights().stream().anyMatch(f -> missile[0] != null && f.target().equals(missile[0].getUUID()));
            h.assertTrue(gone[0] && !flying, "missile still flying");
            List<String> lines = new ArrayList<>();
            boolean kill = false;
            for (Interceptor f : order) {
                Shot s = shots.get(f);
                kill |= f.end() == Interceptor.End.KILL;
                lines.add(String.format(Locale.ROOT, "SAMCRUISE %s shot at tick %d, range %.0f: %s at %s, %.0f blocks above the SAM ground, top %.0f, closest %.1f",
                        name, s.launched - launchedAt[0], s.range, f.end(), BlockPos.containing(f.position()), f.position().y - groundY, s.top, s.closest));
            }
            lines.add(String.format(Locale.ROOT, "SAMCRUISE %s: radar saw it at tick %d, %.0f blocks; %d interceptors; missile ended at %s (%s)", name,
                    seen[0] < 0 ? -1 : seen[0] - launchedAt[0], seenRange[0], order.size(), missileAt[0] == null ? "?" : BlockPos.containing(missileAt[0]),
                    kill ? "shot down" : "not shot down"));
            if (!told[0]) lines.forEach(Incoming.LOG::info);
            told[0] = true;
            h.assertTrue(kill, name + ": " + order.size() + " interceptors, none hit: " + String.join("; ", lines));
        });
    }

    @Nullable
    private static StrikeProjectile find(ServerLevel level, StrikeProjectile p) {
        if (level.getEntity(p.getUUID()) instanceof StrikeProjectile e && !e.isRemoved()) return e;
        for (StrikeProjectile e : VirtualFlights.get(level).flights()) if (e.getUUID().equals(p.getUUID()) && !e.isRemoved()) return e;
        return null;
    }

    /** Terrain around the SAM within the held chunks: trees and hills; a 9×9 clearing around the SAM. */
    private static void shape(ServerLevel level, ChunkPos centre, int groundY, Ground ground) {
        if (ground == Ground.FLAT) return;
        int cx = centre.getMiddleBlockX(), cz = centre.getMiddleBlockZ(), r = VIEW * 16;
        BlockState stone = Blocks.STONE.defaultBlockState(), grass = Blocks.GRASS_BLOCK.defaultBlockState();
        if (ground == Ground.HILLS) {
            // a shell: each column from its lowest neighbour's top up (heightmaps, collisions and sight only need the surface)
            int n = 2 * r;
            int[][] top = new int[n + 2][n + 2];
            for (int i = 0; i < n + 2; i++)
                for (int j = 0; j < n + 2; j++) {
                    double u = i - 1 - r, v = j - 1 - r;
                    boolean clearing = Math.abs(u) <= 4 && Math.abs(v) <= 4;
                    top[i][j] = (int) Math.round(9 * Math.sin((clearing ? 0 : u) / 41.0 + 0.7) * Math.cos((clearing ? 0 : v) / 57.0)
                            + 6 * Math.sin((clearing ? 0 : u + v) / 23.0) + 15);
                }
            for (int i = 1; i <= n; i++)
                for (int j = 1; j <= n; j++) {
                    int low = Math.min(Math.min(top[i - 1][j], top[i + 1][j]), Math.min(top[i][j - 1], top[i][j + 1]));
                    int x = cx - r + i - 1, z = cz - r + j - 1;
                    for (int y = Math.max(0, Math.min(low, top[i][j]) - 1); y < top[i][j]; y++)
                        level.setBlock(new BlockPos(x, groundY + y, z), y == top[i][j] - 1 ? grass : stone, Block.UPDATE_CLIENTS);
                }
            return;
        }
        BlockState log = Blocks.OAK_LOG.defaultBlockState(), leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        boolean tall = ground == Ground.TALL_FOREST;
        for (int gx = cx - r + 3; gx < cx + r - 3; gx += 6)
            for (int gz = cz - r + 3; gz < cz + r - 3; gz += 6) {
                long hash = (gx * 3129871L) ^ (gz * 116129781L);
                hash = hash * hash * 42317861L + hash * 11L;
                int x = gx + (int) Math.floorMod(hash, 3L) - 1, z = gz + (int) Math.floorMod(hash >> 8, 3L) - 1;
                if (Math.abs(x - cx) <= 6 && Math.abs(z - cz) <= 6) continue;
                int trunk = tall ? 10 + (int) Math.floorMod(hash >> 16, 9L) : 5 + (int) Math.floorMod(hash >> 16, 4L);
                for (int y = 0; y < trunk; y++) level.setBlock(new BlockPos(x, groundY + y, z), log, Block.UPDATE_CLIENTS);
                for (int dy = -2; dy <= 1; dy++) {
                    int rad = dy <= -1 ? 2 : 1;
                    for (int dx = -rad; dx <= rad; dx++)
                        for (int dz = -rad; dz <= rad; dz++) {
                            BlockPos at = new BlockPos(x + dx, groundY + trunk + dy, z + dz);
                            if (level.getBlockState(at).isAir()) level.setBlock(at, leaves, Block.UPDATE_CLIENTS);
                        }
                }
            }
    }
}
