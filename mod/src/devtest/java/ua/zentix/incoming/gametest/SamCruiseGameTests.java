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
 * past it, the SAM stands on open ground with the chunks a player's view would hold around it (the rest of the
 * path is out of the world), at game and at real flight scale. Every interceptor's launch range, height, sampled
 * closest approach and end are printed ({@code grep SAMCRUISE}); the missile must be shot down.
 * <p>
 * Not here: a SAM among trees or hills (measured 20.11.2026 with model forests and hills). The radar sees the
 * missile only 95–320 blocks out or never, and whether the interceptor is in time then depends on the sweep phase
 * ({@link SamBlockEntity#SWEEP} ticks, up to 40 blocks of missile flight): forest overhead seen at 120, tall forest
 * 95, hills 101 were hit locally, hills overhead missed on CI when the shot came 9 ticks after first sight; with the
 * aim 300 blocks off the SAM a forest hid it until 317 blocks and it dived onto its aim first, hills hid it entirely.
 * Works as designed (the guide advises open high ground).
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

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void flatOverhead(GameTestHelper h) {
        engage(h, 0, 0);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "sam_cruise", skyAccess = true)
    public static void flatCrossing(GameTestHelper h) {
        engage(h, 1, 300);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "flight_scale_sam_cruise_overhead", skyAccess = true)
    public static void realFlatOverhead(GameTestHelper h) {
        StrikeGameTests.flightScale(h, 1);
        engage(h, 2, 0);
    }

    @GameTest(template = "range", timeoutTicks = 2400, batch = "flight_scale_sam_cruise_crossing", skyAccess = true)
    public static void realFlatCrossing(GameTestHelper h) {
        StrikeGameTests.flightScale(h, 1);
        engage(h, 3, 300);
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
     * SAM at site {@code cell} on flat ground; an unowned cruise missile from {@code START} blocks out (from +X)
     * at a point {@code aside} blocks from the SAM across its course.
     */
    private static void engage(GameTestHelper h, int cell, double aside) {
        ServerLevel level = h.getLevel();
        String name = h.testInfo.getTestName();
        ChunkPos centre = new ChunkPos((ORIGIN - cell * SPACING) >> 4, ORIGIN >> 4);
        level.getChunkSource().addRegionTicket(SITE, centre, VIEW, centre);
        StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(SITE, centre, VIEW, centre));
        for (int dx = -VIEW; dx <= VIEW; dx++)
            for (int dz = -VIEW; dz <= VIEW; dz++) level.getChunk(centre.x + dx, centre.z + dz);
        BlockPos base = new BlockPos(centre.getMiddleBlockX(), 0, centre.getMiddleBlockZ());
        int groundY = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, base.getX(), base.getZ());
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
        // the aim may lie beyond the held chunks: generate its chunk so the aim is on the surface (the missile's own
        // target-area tickets load it in flight)
        level.getChunk(Mth.floor(aimX) >> 4, Mth.floor(aimZ) >> 4);
        Vec3 aim = new Vec3(aimX, Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, Mth.floor(aimX), Mth.floor(aimZ)) + 0.5, aimZ);
        h.assertTrue(aim.y > level.getMinBuildHeight() + 1, "aim not on the surface: " + aim);

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
                Incoming.LOG.info("SAMCRUISE {} scale {}: missile launched at {} toward {}, cruise {} blocks/tick at {} blocks", name,
                        FlightScale.x(), BlockPos.containing(p.position()), BlockPos.containing(aim), String.format(Locale.ROOT, "%.2f", p.airframe().cruiseSpeed()),
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
                if (s.pos == null) continue;
                Vec3 next = f.position();
                // sampled at tick ends while both fly (a burst ends the flight before its last segment): approximate
                if (!f.done() && m != null && mPrev != null) {
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
                lines.add(String.format(Locale.ROOT, "SAMCRUISE %s shot at tick %d, range %.0f: %s at %s, %.0f blocks above the SAM ground, top %.0f, closest sampled %.1f",
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
}
