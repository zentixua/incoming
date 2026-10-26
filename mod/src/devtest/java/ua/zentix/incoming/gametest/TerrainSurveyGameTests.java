package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.Route;
import ua.zentix.incoming.guidance.Corridor;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.FlightTickets;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.util.TerrainSurvey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Terrain survey without loading chunks ({@link TerrainSurvey}, {@link Terrain.Source#DISK} and background
 * {@link Terrain.Source#GENERATOR}): an unloaded chunk gets its height from its file; a chunk absent on disk gets
 * generator terrain. A tick-time query waits for neither disk nor generator and loads nothing. A cruise missile
 * flying outside the world sees a hill before its target that is absent from the world, clears it and dives behind it.
 * <p>
 * Sites lie far beyond the platform (nothing loaded nearby), are separate for each test and at least a thousand
 * blocks from {@code StrikeGameTests.FarSite} sites.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerrainSurveyGameTests {
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private TerrainSurveyGameTests() {}

    private enum Site {
        DISK(-6400, 0),
        GENERATOR(0, -6400),
        NO_WAIT(-6400, -6400),
        HILL(-6400, 6400);

        private final int dx, dz;

        Site(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        BlockPos at(GameTestHelper h) {
            return h.absolutePos(CENTER).offset(dx, 0, dz);
        }
    }

    /** Chunk absent from memory (and the unload queue): its writes are already visible to disk reads. */
    private static boolean unloaded(ServerLevel level, List<ChunkPos> chunks) {
        for (ChunkPos c : chunks) if (Terrain.inMemory(level, c.toLong()) != null
                || level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) != null
                || level.getChunkSource().chunkMap.pendingUnloads.containsKey(c.toLong())) return false;
        return true;
    }

    /** Generator-node terrain as computed by the survey: first air, no lower than sea level. */
    private static int generatorNode(ServerLevel level, int x, int z) {
        ChunkGenerator g = level.getChunkSource().getGenerator();
        return Math.max(g.getSeaLevel(), g.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState()));
    }

    /**
     * A chunk is generated, a 40-block post is built on it, then it is unloaded (saved). On leaving memory it gave
     * the survey its heightmap: immediate answer without a gap. After forgetting it, the survey reads the file:
     * post-column height exactly matches the saved chunk heightmap; neighboring column has its own surface;
     * the first query has no answer within that tick. The query does not load the chunk.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "survey_disk", skyAccess = true)
    public static void diskHeightOfUnloadedChunk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(Site.DISK.at(h));
        level.getChunk(c.x, c.z);
        int x = c.getMiddleBlockX(), z = c.getMiddleBlockZ();
        int surface = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z);
        int beside = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x + 3, z);
        for (int y = surface; y < surface + 40; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
        int top = surface + 40;
        TerrainSurvey survey = TerrainSurvey.get(level);
        // Do not query the map before unload: MOTION_BLOCKING must be retained without any earlier flight/query.
        int[] stage = {0};
        h.onEachTick(() -> {
            if (stage[0] == 0) {
                if (!unloaded(level, List.of(c))) return;
                // unloaded; forget the map it gave the survey on unload, so the check tests file reading.
                // The first query requests a read and gives no immediate answer
                Terrain.Surface left = survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z);
                h.assertTrue(left != null && left.source() == Terrain.Source.DISK && left.y() == top, "unloaded chunk left no heightmap: " + left);
                assertReliefBand(h, x, z);
                survey.forget(c.toLong());
                h.assertTrue(survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z) == null, "survey answered in the query tick: it waited for disk");
                stage[0] = 1;
            }
            h.assertTrue(unloaded(level, List.of(c)), "query loaded the chunk");
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 1, "chunk did not unload");
            Terrain.Surface s = survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z);
            h.assertTrue(s != null, "no disk answer");
            h.assertTrue(s.source() == Terrain.Source.DISK && s.y() == top, "post: " + s + " instead of " + top + " from disk");
            Terrain.Surface next = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING, x + 3, z, Terrain.Allowed.RELIEF);
            h.assertTrue(next.source() == Terrain.Source.DISK && next.y() == beside, "neighboring column: " + next + " instead of " + beside);
            assertReliefBand(h, x, z);
            h.assertTrue(unloaded(level, List.of(c)), "chunk loaded");
        });
    }

    /**
     * Chunk absent on disk (never generated): background generator terrain is the maximum of the four nodes
     * of the {@link TerrainSurvey#GENERATOR_STEP} cell; the chunk never enters memory.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "survey_generator", skyAccess = true)
    public static void generatorWhereNothingOnDisk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(Site.GENERATOR.at(h));
        int x = c.getMinBlockX() + 5, z = c.getMinBlockZ() + 11;
        int step = TerrainSurvey.GENERATOR_STEP;
        int x0 = Math.floorDiv(x, step) * step, z0 = Math.floorDiv(z, step) * step;
        int expected = Math.max(Math.max(generatorNode(level, x0, z0), generatorNode(level, x0 + step, z0)),
                Math.max(generatorNode(level, x0, z0 + step), generatorNode(level, x0 + step, z0 + step)));
        TerrainSurvey survey = TerrainSurvey.get(level);
        h.assertTrue(survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z) == null, "survey answered in the query tick: generator ran within the tick");
        h.succeedWhen(() -> {
            Terrain.Surface s = survey.at(level, Heightmap.Types.MOTION_BLOCKING, x, z);
            h.assertTrue(s != null, "no generator answer");
            h.assertTrue(s.source() == Terrain.Source.GENERATOR && s.y() == expected, "generator: " + s + " instead of " + expected);
            assertReliefBand(h, x, z);
            h.assertTrue(unloaded(level, List.of(c)), "chunk appeared in memory");
        });
    }

    /** Beyond a gap in ready chunks, the Nether ceiling must not become a heightmap obstacle. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "survey_nether_gap", skyAccess = true)
    public static void netherCeilingBeyondUnreadyGapDoesNotBlockSightline(GameTestHelper h) {
        ServerLevel nether = h.getLevel().getServer().getLevel(Level.NETHER);
        h.assertTrue(nether != null, "no Nether");
        ChunkPos near = new ChunkPos(Site.GENERATOR.at(h));
        ChunkPos far = new ChunkPos(near.x + 32, near.z);
        int full = ChunkLevel.byStatus(FullChunkStatus.FULL);
        var tickets = nether.getChunkSource().chunkMap.getDistanceManager();
        for (ChunkPos c : List.of(near, far)) tickets.addTicket(AIM_AREA, c, full, c);
        StrikeGameTests.afterTest(h, () -> {
            for (ChunkPos c : List.of(near, far)) tickets.removeTicket(AIM_AREA, c, full, c);
        });
        nether.getChunk(near.x, near.z);
        nether.getChunk(far.x, far.z);
        // Only the sensor is exercised: no launch, flight tickets or entity ticks.
        class SensorMissile extends CruiseMissileEntity {
            SensorMissile() { super(ModEntities.CRUISE_MISSILE.get(), nether); }
            double sightline(Vec3 to) { return clearAlong(nether, to, 0); }
        }
        SensorMissile missile = new SensorMissile();
        Vec3 from = new Vec3(near.getMiddleBlockX() + 0.5, 80.5, near.getMiddleBlockZ() + 0.5);
        Vec3 to = from.add(32 * 16, 0, 0);
        missile.setPos(from);
        h.succeedWhen(() -> {
            h.assertTrue(Terrain.ready(nether, near.x, near.z) && Terrain.ready(nether, far.x, far.z), "ends not ready yet");
            h.assertFalse(Terrain.ready(nether, near.x + 16, near.z), "gap already ready");
            // Clear the near ready segment under the natural bedrock roof; touch no unready chunks.
            for (int x = near.getMiddleBlockX(); x <= near.getMaxBlockX(); x++)
                nether.getChunkSource().getChunkNow(near.x, near.z).setBlockState(new BlockPos(x, 80, near.getMiddleBlockZ()), Blocks.AIR.defaultBlockState(), false);
            Vec3 edge = Terrain.readyUntil(nether, from, to);
            h.assertTrue(edge != to && edge.x > from.x, "no ready segment before the gap");
            Terrain.Surface roof = Terrain.estimate(nether, Heightmap.Types.MOTION_BLOCKING,
                    far.getMiddleBlockX(), far.getMiddleBlockZ(), Terrain.Allowed.RELIEF);
            h.assertTrue(roof.source() == Terrain.Source.CEILING && roof.y() > to.y, "no ceiling beyond the gap: " + roof);
            for (Terrain.Allowed allowed : new Terrain.Allowed[]{Terrain.Allowed.RELIEF, Terrain.Allowed.VIRTUAL_RELIEF}) {
                double expected = Terrain.estimate(nether, Heightmap.Types.MOTION_BLOCKING,
                        far.getMiddleBlockX(), far.getMiddleBlockZ(), allowed).y();
                h.assertTrue(Terrain.relief(nether, allowed).at(far.getMiddleBlockX(), far.getMiddleBlockZ()) == expected,
                        "batched sensor changed Nether ceiling handling");
            }
            h.assertTrue(Double.isInfinite(missile.sightline(to)), "ceiling beyond the gap blocked the straight path");
            // A real block on the near ready segment must still block the in-world sensor.
            BlockPos obstacle = BlockPos.containing(from.add(2, 0, 0));
            var chunk = nether.getChunkSource().getChunkNow(near.x, near.z);
            chunk.setBlockState(obstacle, Blocks.STONE.defaultBlockState(), false);
            try {
                double hit = missile.sightline(to);
                h.assertTrue(hit > 0 && hit < edge.distanceTo(from), "block before the gap missed: " + hit);
            } finally {
                chunk.setBlockState(obstacle, Blocks.AIR.defaultBlockState(), false);
            }
            h.assertFalse(Terrain.ready(nether, near.x + 16, near.z), "sensor loaded the gap");
        });
    }

    /** A fresh measurement sees block changes even within the same tick; cached heights never outlive the band. */
    @GameTest(template = "range", timeoutTicks = 100, batch = "survey_reuse", skyAccess = true)
    public static void reliefBandSeesChangedChunk(GameTestHelper h) {
        BlockPos p = h.absolutePos(CENTER).above(50);
        ServerLevel level = h.getLevel();
        assertReliefBand(h, p.getX(), p.getZ());
        var before = Terrain.relief(level, Terrain.Allowed.RELIEF);
        double old = before.at(p.getX(), p.getZ());
        level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
        h.assertTrue(p.getY() + 1 > old, "pillar is not above the old heightmap");
        h.assertTrue(Terrain.relief(level, Terrain.Allowed.RELIEF).at(p.getX(), p.getZ()) == p.getY() + 1,
                "new measurement retained an old height");
        assertReliefBand(h, p.getX(), p.getZ());
        level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        assertReliefBand(h, p.getX(), p.getZ());
        h.succeed();
    }

    private static void assertReliefBand(GameTestHelper h, int x, int z) {
        double[] track = {x - 3.5, z - 2.5, x + 0.5, z + 0.5, x + 3.5, z - 2.5, x - 3.5, z - 2.5};
        Corridor.Climb climb = new Corridor.Climb(2, 0.3);
        for (Terrain.Allowed allowed : new Terrain.Allowed[]{Terrain.Allowed.RELIEF, Terrain.Allowed.VIRTUAL_RELIEF}) {
            double old = Corridor.highest(track, 1.5, climb,
                    (cx, cz) -> Terrain.estimate(h.getLevel(), Heightmap.Types.MOTION_BLOCKING, cx, cz, allowed).y());
            double batched = Corridor.highest(track, 1.5, climb, Terrain.relief(h.getLevel(), allowed));
            h.assertTrue(old == batched, "corridor maximum changed: " + old + " → " + batched);
        }
    }

    /** Chunk count in {@link #surveyNeverWaitsInTick}: 16×16 square. */
    private static final int NO_WAIT_SIDE = 16;
    /**
     * Maximum query time for {@code NO_WAIT_SIDE²} unknown chunks within a tick, ms, with margin for shared CI runners:
     * a request adds a few map entries and an I/O-thread task. Reading chunk files within the tick (~0.1–1 ms per chunk)
     * or running the generator (~3 ms per node, 9 nodes per overworld chunk) would cost hundreds of ms or seconds.
     */
    private static final long NO_WAIT_LIMIT_MS = 200;

    /**
     * Queries for 256 unknown chunks (half saved on disk, half absent) in one tick: no answer arrives within the query
     * tick (the survey waits for neither disk nor generator), queries take little time, no chunk loads then or later,
     * and all answers arrive in subsequent ticks.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "survey_no_wait", skyAccess = true)
    public static void surveyNeverWaitsInTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos base = new ChunkPos(Site.NO_WAIT.at(h));
        List<ChunkPos> all = new ArrayList<>();
        for (int dx = 0; dx < NO_WAIT_SIDE; dx++)
            for (int dz = 0; dz < NO_WAIT_SIDE; dz++) all.add(new ChunkPos(base.x + dx, base.z + dz));
        // half on disk (generated and unloaded), half absent
        List<ChunkPos> saved = all.subList(0, all.size() / 2);
        for (ChunkPos c : saved) level.getChunk(c.x, c.z);
        TerrainSurvey survey = TerrainSurvey.get(level);
        int[] stage = {0};
        long[] took = {0};
        h.onEachTick(() -> {
            if (stage[0] == 0) {
                if (!unloaded(level, all)) return;
                for (ChunkPos c : all) survey.forget(c.toLong());
                long t0 = System.nanoTime();
                int answered = 0;
                for (ChunkPos c : all) if (survey.at(level, Heightmap.Types.MOTION_BLOCKING, c.getMiddleBlockX(), c.getMiddleBlockZ()) != null) answered++;
                took[0] = System.nanoTime() - t0;
                Incoming.LOG.info(String.format(Locale.ROOT, "Terrain survey measurement: %d queries for unknown chunks in a tick — %.2f ms, answers in the tick %d",
                        all.size(), took[0] / 1e6, answered));
                h.assertTrue(answered == 0, "survey answered within the query tick " + answered + " times: waited for disk or generator");
                // requests in flight: tick-time reads would leave none (half the chunks also wait for the generator)
                h.assertTrue(survey.pendingCount() > 0, "no requests in flight: survey finished reading within the query tick");
                h.assertTrue(took[0] / 1_000_000 < NO_WAIT_LIMIT_MS, String.format(Locale.ROOT, "tick-time queries took %.1f ms", took[0] / 1e6));
                stage[0] = 1;
            }
            h.assertTrue(unloaded(level, all), "survey loaded a chunk");
        });
        h.succeedWhen(() -> {
            h.assertTrue(stage[0] == 1, "chunks did not unload");
            int disk = 0, generator = 0;
            for (ChunkPos c : all) {
                Terrain.Surface s = survey.at(level, Heightmap.Types.MOTION_BLOCKING, c.getMiddleBlockX(), c.getMiddleBlockZ());
                h.assertTrue(s != null, "no answer for chunk " + c);
                if (s.source() == Terrain.Source.DISK) disk++;
                else if (s.source() == Terrain.Source.GENERATOR) generator++;
            }
            h.assertTrue(disk == saved.size() && generator == all.size() - saved.size(),
                    "from disk " + disk + " (saved " + saved.size() + "), generator " + generator);
        });
    }

    /** Target area from tick one, as in {@code StrikeGameTests.returnsUnderOwnTerrain}: world reentry always occurs at the area edge. */
    private static final TicketType<ChunkPos> AIM_AREA = TicketType.create("incoming_test_survey_aim", Comparator.comparingLong(ChunkPos::toLong));
    /** Hill ridge this many blocks before the target against heading: beyond target-area edge (±72 blocks loaded). */
    private static final int HILL_CREST = 140;
    /** Hill height above ground: above target skimming flight (+12) and pop-up (+32). */
    private static final int HILL_HEIGHT = 45;
    /** Hill half-width across heading, blocks. */
    private static final int HILL_HALF_WIDTH = 16;
    /** Launch this many blocks before the target. */
    private static final int MISSILE_START = 600;
    /** Closer to the target horizontally means an explosion at the target. */
    private static final double BURST_NEAR_AIM = 5;

    /** Hill height above ground at {@code u} blocks from the ridge along heading: 1 : 1 slopes, 4-block ridge. */
    private static int hill(int u) {
        return Math.max(0, HILL_HEIGHT - Math.max(0, Math.abs(u) - 2));
    }

    /**
     * Cruise missile outside the world (nobody at or behind the target, no approach lane) heads toward a target on flat
     * ground; 140 blocks before it, a 45-block hill crosses the path. The hill is absent from the world, its chunks
     * saved on disk. Without the survey, an outside-world missile would know no terrain and fly 12 blocks above the
     * target, through the hill. Now it sees disk terrain, clears the ridge with margin and dives behind it: explosion
     * at the target. Flight runs at game speed: disk answers arrive between ticks, as on a server.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "survey_missile_hill", skyAccess = true)
    public static void missileDivesOverHillFromDisk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ChunkPos aimChunk = new ChunkPos(Site.HILL.at(h));
        level.getChunk(aimChunk.x, aimChunk.z);
        int ax = aimChunk.getMiddleBlockX(), az = aimChunk.getMiddleBlockZ();
        int ground = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, ax, az);
        Vec3 aim = new Vec3(ax + 0.5, ground, az + 0.5);
        // hill across heading (+x): load chunks immediately, place blocks in columns above ground
        int crest = ax - HILL_CREST;
        List<ChunkPos> hillChunks = new ArrayList<>();
        for (int x = crest - HILL_HEIGHT - 2; x <= crest + HILL_HEIGHT + 2; x++)
            for (int z = az - HILL_HALF_WIDTH; z <= az + HILL_HALF_WIDTH; z++) {
                ChunkPos c = new ChunkPos(new BlockPos(x, 0, z));
                if (!hillChunks.contains(c)) {
                    hillChunks.add(c);
                    level.getChunk(c.x, c.z);
                }
                int top = hill(x - crest);
                for (int y = ground; y < ground + top; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
            }
        Vec3[] burst = {null};
        Consumer<ExplosionEvent.Start> onBlast = e -> {
            Vec3 c = e.getExplosion().center();
            if (burst[0] == null && e.getLevel() == level && Math.hypot(c.x - aim.x, c.z - aim.z) < 16) burst[0] = c;
        };
        NeoForge.EVENT_BUS.addListener(onBlast);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(onBlast));
        UUID[] id = {null};
        Vec3[] last = {null};
        // minimum clearance above the hill and where it occurs; outside-world flight over the hill
        double[] worst = {Double.POSITIVE_INFINITY};
        String[] where = {""};
        long[] paced = {0};
        h.onEachTick(() -> {
            if (id[0] == null) {
                // hill saved on disk and unloaded: target area (its ticket also retains chunks out to 15 chunks around it in memory,
                // below full loading, so take it only now; hill chunks from there are partial disk copies,
                // neither ready nor full) and launch
                if (!unloaded(level, hillChunks)) return;
                // forget the map the hill gave the survey on unload: the missile reads its file
                TerrainSurvey survey = TerrainSurvey.get(level);
                for (ChunkPos c : hillChunks) survey.forget(c.toLong());
                for (int dx = -FlightTickets.DISTANCE; dx <= FlightTickets.DISTANCE; dx++)
                    for (int dz = -FlightTickets.DISTANCE; dz <= FlightTickets.DISTANCE; dz++) level.getChunk(aimChunk.x + dx, aimChunk.z + dz);
                level.getChunkSource().addRegionTicket(AIM_AREA, aimChunk, FlightTickets.DISTANCE, aimChunk);
                StrikeGameTests.afterTest(h, () -> level.getChunkSource().removeRegionTicket(AIM_AREA, aimChunk, FlightTickets.DISTANCE, aimChunk));
                CruiseMissileEntity m = ModEntities.CRUISE_MISSILE.get().create(level);
                // as with distant launch (StrikeService.fromAfar): 12 blocks above target
                m.launch(aim.add(-MISSILE_START, 12, 0), new Target.Point(aim), aim, null);
                m.setRoute(Route.direct());
                VirtualFlights.launch(level, m);
                id[0] = m.getUUID();
                paced[0] = System.nanoTime();
                return;
            }
            // game speed from launch: survey answers between ticks, as on a server
            long deadline = paced[0] + 50_000_000L, left;
            while ((left = deadline - System.nanoTime()) > 0) LockSupport.parkNanos(left);
            paced[0] = System.nanoTime();
            for (ChunkPos c : hillChunks) h.assertFalse(Terrain.ready(level, c.x, c.z), "hill chunk loaded: " + c);
            StrikeProjectile p = flight(level, id[0]);
            if (p == null) return;
            last[0] = p.position();
            int u = (int) Math.floor(p.getX()) - crest;
            if (Math.abs(u) <= HILL_HEIGHT + 2) {
                double clear = p.getY() - (ground + hill(u));
                if (clear < worst[0]) {
                    worst[0] = clear;
                    where[0] = String.format(Locale.ROOT, "%d blocks from ridge, y %.1f, outside world %s, phase %s", u, p.getY(), p.isVirtual(), p.flightPhase());
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(id[0] != null, "hill did not unload");
            h.assertFalse(flight(level, id[0]) != null, "missile still flying, distance to target " + (last[0] == null ? "?" : (int) last[0].distanceTo(aim)));
            Incoming.LOG.info("Missile over disk hill: minimum clearance {} ({})", String.format(Locale.ROOT, "%.1f", worst[0]), where[0]);
            h.assertTrue(worst[0] > 0, "missile flew through the hill: clearance " + String.format(Locale.ROOT, "%.1f", worst[0]) + " (" + where[0] + ")");
            h.assertTrue(burst[0] != null, "missile removed without an explosion at the target, last seen at " + (last[0] == null ? "?" : last[0].subtract(aim)));
            Vec3 off = burst[0].subtract(aim);
            h.assertTrue(Math.hypot(off.x, off.z) < BURST_NEAR_AIM, "explosion away from target: " + off + " from it");
        });
    }

    private static StrikeProjectile flight(ServerLevel level, UUID id) {
        return VirtualFlights.get(level).flights().stream().filter(v -> v.getUUID().equals(id)).findFirst()
                .orElseGet(() -> level.getEntity(id) instanceof StrikeProjectile e && !e.isRemoved() ? e : null);
    }
}
