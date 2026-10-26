package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.compat.DhUpdates;
import ua.zentix.incoming.grid.ChunkLights;
import ua.zentix.incoming.grid.GridLights;
import ua.zentix.incoming.grid.PowerGrid;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.NuclearStrikes;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.nuclear.world.FarLods;
import ua.zentix.incoming.nuclear.world.NuclearWorld;
import ua.zentix.incoming.nuclear.world.RuinPlan;
import ua.zentix.incoming.nuclear.world.RuinPlanner;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * World changes - in LOD Distant Horizons ({@link DhUpdates}, {@link FarLods}). DH itself is not included in the checks: the chunks go
 *  to the substituted receiver ({@link DhUpdates#testSink}).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DhGameTests {
    /** The middle of the site "range" at the grass level (floor - 11 layers). */
    private static final BlockPos CENTER = new BlockPos(32, 11, 32);

    private DhGameTests() {}

    /** The chunk goes to the receiver: tick → how many times per tick, and all sendings are in order. */
    private record Sent(Long2IntOpenHashMap perTick, LongArrayList chunks, List<Long> ticks) {
        Sent() {
            this(new Long2IntOpenHashMap(), new LongArrayList(), new ArrayList<>());
        }

        int count(ChunkPos c) {
            int n = 0;
            for (int i = 0; i < chunks.size(); i++) if (chunks.getLong(i) == c.toLong()) n++;
            return n;
        }

        long first(ChunkPos c) {
            for (int i = 0; i < chunks.size(); i++) if (chunks.getLong(i) == c.toLong()) return ticks.get(i);
            return -1;
        }
    }

    private static Sent sink(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Sent sent = new Sent();
        DhUpdates.testSink(level, (l, chunk) -> {
            long now = l.getGameTime();
            sent.perTick.addTo(now, 1);
            sent.chunks.add(chunk.getPos().toLong());
            sent.ticks.add(now);
        });
        StrikeGameTests.afterTest(h, () -> DhUpdates.testSink(level, null));
        return sent;
    }

    /**
     * Explosion of a warhead: the crater chunk goes to LOD when the explosion released the area - once immediately (portions of the explosion -
     *  with one call), the second - when the area stopped crumbling. Per tick - no more than the queue limit.
     */
    @GameTest(template = "range", timeoutTicks = 800, batch = "dh_blast", skyAccess = true)
    public static void blastReachesDhTwice(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Sent sent = sink(h);
        Vec3 at = Vec3.atCenterOf(h.absolutePos(CENTER.below(2)));
        ChunkPos crater = new ChunkPos(BlockPos.containing(at));
        long start = level.getGameTime();
        Warheads.Probe blast = Warheads.testBlast(level, at, 6, true, false, System::nanoTime, 5_000_000L);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "the explosion didn't end");
            h.assertTrue(sent.count(crater) >= 2, "chunk crater went to LOD " + sent.count(crater) + " times");
            long first = sent.first(crater);
            h.assertTrue(first > start, "the chunk left before the explosion");
            for (var e : sent.perTick.long2IntEntrySet()) h.assertTrue(e.getIntValue() <= 20, "per tick " + e.getLongKey() + " gone " + e.getIntValue());
            int sameTick = 0;
            for (int i = 0; i < sent.chunks.size(); i++) if (sent.chunks.getLong(i) == crater.toLong() && sent.ticks.get(i) == first) sameTick++;
            h.assertTrue(sameTick == 1, "chunk left several times in one tick");
        });
    }

    /**
     * Marking one chunk in a row: one call, repeat - no earlier than 40 tick after it (DH still holds the chunk in the
     *  queue and would skip the repeat silently).
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "dh_pace", skyAccess = true)
    public static void marksCoalesceAndResendLater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Sent sent = sink(h);
        ChunkPos c = new ChunkPos(h.absolutePos(CENTER));
        long now = level.getGameTime();
        DhUpdates.mark(level, c, now);
        DhUpdates.mark(level, c, now + 1);
        DhUpdates.mark(level, c, now + 2);
        h.runAfterDelay(5, () -> DhUpdates.mark(level, c, level.getGameTime()));
        h.succeedWhen(() -> {
            h.assertTrue(sent.count(c) == 2, "shipments " + sent.count(c));
            long a = sent.ticks.get(0), b = sent.ticks.get(1);
            h.assertTrue(b - a >= 40, "repeat in " + (b - a) + " tick");
        });
    }

    /**
     * A copy of a chunk from the disk with the ruins of the plane ({@link FarLods#testCopy}: save data, parsed as a chunk not in the world) -
     *  block to block is the same as the chunk after a real replacement, and the biomes are the same.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "dh_ruins", skyAccess = true)
    public static void farCopyMatchesRuins(GameTestHelper h) {
        RuinCostGameTests.city(h);
        ServerLevel level = h.getLevel();
        Detonation d = NuclearGameTests.atPsi(h, new BlockPos(-600, 0, 32), true, new BlockPos(32, 1, 32), 12);
        int compared = 0, cells = 0;
        for (int rx : new int[]{24, 40}) {
            for (int rz : new int[]{24, 40}) {
                ChunkPos c = new ChunkPos(h.absolutePos(new BlockPos(rx, 1, rz)));
                for (int dx = -RuinPlanner.REACH; dx <= RuinPlanner.REACH; dx++) {
                    for (int dz = -RuinPlanner.REACH; dz <= RuinPlanner.REACH; dz++) level.getChunk(c.x + dx, c.z + dz);
                }
                LevelChunk chunk = level.getChunk(c.x, c.z);
                RuinPlan plan = RuinPlanner.planFresh(level, d, chunk, false);
                ProtoChunk copy = FarLods.testCopy(level, chunk, plan, false);
                h.assertTrue(copy != null, "the plan did not match the copy of the chunk " + c);
                h.assertTrue(plan.apply(level, chunk, d, new ColumnScar.Budget(false)), "chunk plan " + c + " outdated");
                for (int y = level.getMinBuildHeight(); y < level.getMaxBuildHeight(); y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockPos p = c.getBlockAt(x, y, z);
                            BlockState real = chunk.getBlockState(p), far = copy.getBlockState(p);
                            h.assertTrue(real == far, "in a copy " + p + " — " + far + ", in the world - " + real);
                        }
                    }
                }
                BlockPos mid = c.getMiddleBlockPosition(CENTER.getY());
                h.assertTrue(copy.getNoiseBiome(mid.getX() >> 2, mid.getY() >> 2, mid.getZ() >> 2).value() == chunk.getNoiseBiome(mid.getX() >> 2, mid.getY() >> 2, mid.getZ() >> 2).value(),
                        "the biome of the copy is not the same");
                compared++;
                cells += plan.changedBlocks();
            }
        }
        h.assertTrue(cells > 0, "the city is not destroyed: there is nothing to compare");
        Incoming.LOG.info("LOD in the distance: {} copies coincided with the ruins, {} places", compared, cells);
        h.succeed();
    }

    /**
     * A trace of an explosion in the distance behind the heavy zone ({@link FarLods#scorch}): a chunk not in the world that the wave reached, goes to DH
     *  a copy from the disk with a trace on the ground - the grass is burned, the biome is scorched, below the ground everything is as it was - and not before the wave.
     *  Chunk - data on saving the grass field of the site, recorded on a disk in the distance, where there is no world; the epicenter is in 32 blocks
     *  (on a scale of ~1,76 km: ~9 cal/cm² with air transmission according to Glasstone). The server moves at the pace of the game: chunks read the
     *  I/O stream.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "dh_scorch", skyAccess = true)
    public static void farScorchReachesDh(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ScorchGameTests.fillBiome(h, 0, 63, net.minecraft.world.level.biome.Biomes.PLAINS);
        for (int x = 16; x < 48; x++) for (int z = 16; z < 48; z++) h.setBlock(new BlockPos(x, CENTER.getY() + 1, z), Blocks.SHORT_GRASS);
        LevelChunk sample = level.getChunkAt(h.absolutePos(CENTER));
        ChunkPos far = farTile(h, 9000, 9000);
        h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(far.toLong()) == null, "chunk " + far + " already in memory");
        net.minecraft.nbt.CompoundTag tag = net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, sample);
        tag.putInt("xPos", far.x);
        tag.putInt("zPos", far.z);
        level.getChunkSource().chunkMap.write(far, tag);
        long[] lods0 = FarLods.get(level).stats();
        ProtoChunk[] copy = {null};
        long[] at = {-1};
        DhUpdates.testSink(level, (l, chunk) -> {
            if (!chunk.getPos().equals(far) || !(chunk instanceof ProtoChunk p)) return;
            copy[0] = p;
            at[0] = l.getGameTime();
        });
        FarLods.testViewer(level, far);
        StrikeGameTests.afterTest(h, () -> {
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            FarLods.get(level).clear(level);
        });
        double scale = 0.0182;
        int ground = h.absolutePos(CENTER).getY() + 1;
        Vec3 burst = new Vec3(far.getMiddleBlockX() + 0.5 - 32, ground + ua.zentix.incoming.nuclear.model.Yield.optimalBurstHeight(15) * scale, far.getMiddleBlockZ() + 0.5);
        Detonation d = new Detonation(NuclearGameTests.IDS.incrementAndGet(), burst, ground, 15, false, level.getGameTime(), 0, 0, 20_000, 7, (float) scale, false,
                Detonation.OLD_WAVE);
        Vec3 mid = new Vec3(far.getMiddleBlockX() + 0.5, ground, far.getMiddleBlockZ() + 0.5);
        long due = d.gameTime() + (long) d.arrivalTicks(mid.distanceTo(burst));
        FarLods.scorch(level, d, false);
        h.succeedWhen(() -> {
            h.assertTrue(copy[0] != null, "the copy with the trace did not go to DH");
            h.assertTrue(at[0] >= due, "a copy went to " + at[0] + ", before the wave (" + due + ")");
            h.assertTrue(FarLods.get(level).stats()[9] - lods0[9] >= 1, "the copy is not counted as a trace");
            int grass = 0, burnt = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos top = far.getBlockAt(x, ground, z);
                    if (copy[0].getBlockState(top).is(Blocks.SHORT_GRASS)) grass++;
                    else burnt++;
                    // below the turf - as it was
                    for (int y = level.getMinBuildHeight(); y < ground - 1; y++) {
                        BlockState was = sample.getBlockState(sample.getPos().getBlockAt(x, y, z)), now = copy[0].getBlockState(far.getBlockAt(x, y, z));
                        h.assertTrue(was == now, "underground in " + far.getBlockAt(x, y, z) + " — " + now + ", it was " + was);
                    }
                }
            }
            h.assertTrue(burnt > grass, "the grass burned " + burnt + " columns from 256");
            BlockPos m = far.getMiddleBlockPosition(ground);
            var biome = copy[0].getNoiseBiome(m.getX() >> 2, m.getY() >> 2, m.getZ() >> 2);
            h.assertTrue(biome.unwrapKey().map(k -> k.location().getNamespace().equals(Incoming.MOD_ID)).orElse(false),
                    "the copy's biome is not scorched: " + biome.unwrapKey().map(k -> k.location().toString()).orElse("?"));
        });
    }

    /**
     * Chunk of a zone that is not on the disk (the edge of the explored world): a copy with ruins cannot be collected from the disk, so its square
     * 5×5 is taken into the world ({@code FarZone}) - vanilla generates it in the background, the ruins appear by loading (for a fresh chunk
     * no mark {@code chunk_scar}: the queue of ruins places it itself), the chunk with ruins goes to DH, the square is released.
     * The world of GameTest is flat - there is nothing to break, so glass is placed on a fresh chunk before the wave arrives: its wave hits.
     * The server runs at the pace of the game: generation is background, according to wall time.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "dh_far_zone", skyAccess = true)
    public static void farZoneLoadsMissingChunksForDh(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        // far beyond the site: nothing is generated there; chunk - the middle of its square 5×5 (square one)
        ChunkPos c = farTile(h, 6000, -6000);
        h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "chunk " + c + " already in memory");
        // farLods meters - for the whole world, from previous checks: growth compared
        long[] lods0 = FarLods.get(level).stats(), zone0 = FarLods.get(level).zoneStats();
        BlockPos[] glass = {null};
        // what went into DH: chunk and whether there were ruins in it at the time of sending
        int[] sent = {0, 0};
        DhUpdates.testSink(level, (l, chunk) -> {
            if (!chunk.getPos().equals(c) || !(chunk instanceof LevelChunk lc)) return;
            sent[0]++;
            if (glass[0] != null && lc.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) > 0 && lc.getBlockState(glass[0]).isAir()) sent[1]++;
        });
        FarLods.testViewer(level, c);
        StrikeGameTests.afterTest(h, () -> {
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        // explosion in the air above the chunk; the wave will reach its middle after WAVE tick - by then it will be generated
        int wave = 400;
        double ground = level.getMinBuildHeight() + 3, height = 40;
        Vec3 burst = new Vec3(c.getMiddleBlockX() + 0.5, ground + height, c.getMiddleBlockZ() + 0.5);
        Detonation probe = new Detonation(0, burst, ground, 15, false, 0, 0, 0, 20_000, 7, 0.05f, false, Detonation.OLD_WAVE);
        long now = level.getGameTime();
        Detonation d = new Detonation(NuclearGameTests.IDS.incrementAndGet(), burst, ground, 15, false, now + wave - (long) probe.arrivalTicks(height), 0, 0,
                20_000, 7, 0.05f, false, Detonation.OLD_WAVE);
        NuclearEvents.get(level).add(d);
        FarLods.request(level, c.toLong(), true);
        h.onEachTick(() -> {
            LevelChunk chunk = level.getChunkSource().getChunkNow(c.x, c.z);
            if (glass[0] == null && chunk != null) {
                h.assertTrue(level.getGameTime() < now + wave - 20, "the square took longer to load " + (wave - 20) + " tick");
                h.assertTrue(chunk.getExistingData(ModAttachments.CHUNK_SCAR).isEmpty(), "a fresh chunk already has a ruin mark");
                // the top block of the column (not the first air, like the height map)
                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, 8, 8);
                glass[0] = c.getBlockAt(8, top + 1, 8);
                for (int y = 0; y < 3; y++) level.setBlock(glass[0].above(y), Blocks.GLASS.defaultBlockState(), 2);
            }
        });
        h.succeedWhen(() -> {
            long[] zone = FarLods.get(level).zoneStats();
            long partial = FarLods.get(level).stats()[6] - lods0[6], taken = zone[2] - zone0[2], released = zone[3] - zone0[3], expired = zone[4] - zone0[4];
            h.assertTrue(glass[0] != null, "chunk didn't load");
            h.assertTrue(partial == 1, "not integers on disk: " + partial);
            h.assertTrue(sent[0] >= 1, "chunk with ruins did not go to DH");
            h.assertTrue(sent[1] >= 1, "in DH a chunk left without ruins (sending " + sent[0] + ")");
            h.assertTrue(taken == 1 && released == 1 && zone[1] == 0 && zone[0] == 0, "squares: waiting " + zone[0] + ", hold on " + zone[1] + ", taken " + taken
                    + ", released " + released);
            h.assertTrue(expired == 0 && zone[6] <= 2,"by deadline " + expired + ", in loading at once " + zone[6]);
        });
    }

    /**
     * Stopping the server while the square {@code FarZone} is loading: its ticket is released along with the rest of the work of the ruins
     *  ({@link NuclearWorld#onServerStopping}, before {@code util/StopDrain}) - no one holds the generation after that. Server
     *  runs at the pace of the game: the square is taken after the I/O thread responds.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "dh_far_zone_stop", skyAccess = true)
    public static void farZoneReleasedOnStop(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos c = farTile(h, -6000, 6000);
        h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "chunk " + c + " already in memory");
        DhUpdates.testSink(level, (l, chunk) -> {});
        FarLods.testViewer(level, c);
        StrikeGameTests.afterTest(h, () -> {
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        FarLods.request(level, c.toLong(), true);
        h.onEachTick(() -> {
            if (FarLods.get(level).zoneStats()[1] == 0) return;
            h.assertTrue(farZoneTickets(level, c) > 0, "the square is taken, but the ticket is not");
            boolean stopped = NuclearWorld.onServerStopping(level.getServer());
            long[] zone = FarLods.get(level).zoneStats();
            h.assertTrue(stopped, "background flows of ruins did not stop within the time limit");
            h.assertTrue(zone[0] == 0 && zone[1] == 0, "after stopping the squares waits " + zone[0] + ", holding on " + zone[1]);
            h.assertTrue(farZoneTickets(level, c) == 0, "after the stop there were ticket squares left: " + farZoneTickets(level, c));
            h.succeed();
        });
    }

    /**
     * The {@code nuclear.far_zone} setting is disabled: a chunk that is not on the disk remains ungenerated. Server
     *  runs at the pace of the game: the chunk header is read by the I/O stream, according to wall time.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "dh_far_zone_off", skyAccess = true)
    public static void farZoneOffGeneratesNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos c = farTile(h, 6000, 6000);
        h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "chunk " + c + " already in memory");
        // farLods meters - for the whole world, from previous checks: growth compared
        long partial0 = FarLods.get(level).stats()[6], taken0 = FarLods.get(level).zoneStats()[2];
        boolean was = IncomingConfig.SERVER.nukeFarZone.get();
        IncomingConfig.SERVER.nukeFarZone.set(false);
        DhUpdates.testSink(level, (l, chunk) -> {});
        FarLods.testViewer(level, c);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.nukeFarZone.set(was);
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        FarLods.request(level, c.toLong(), true);
        h.succeedWhen(() -> {
            h.assertTrue(FarLods.get(level).stats()[6] - partial0 == 1, "the title from the disk has not yet been read");
            h.assertTrue(FarLods.get(level).zoneStats()[2] == taken0, "the square is taken with the setting turned off");
            h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "chunk " + c + " loaded");
        });
    }

    /**
     * An under-generated chunk without blocks on the disk (at the edge of the explored world there are thousands of such: the beginnings of structures, biomes) -
     *  is the same as there is no chunk: its square is still generated and released. Chunk - the beginning of structures in memory, unloaded by
     *  vanilla onto the disk itself. The server moves at the pace of the game.
     */
    @GameTest(template = "range", timeoutTicks = 2400, batch = "dh_far_zone_proto", skyAccess = true)
    public static void farZoneTakesProtoWithoutBlocks(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos c = farTile(h, 12000, 0);
        h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "chunk " + c + " already in memory");
        ChunkAccess proto = level.getChunk(c.x, c.z, ChunkStatus.STRUCTURE_STARTS, true);
        h.assertTrue(proto.getPersistedStatus() == ChunkStatus.STRUCTURE_STARTS, "chunk already " + proto.getPersistedStatus());
        for (LevelChunkSection s : proto.getSections()) h.assertTrue(s.hasOnlyAir(), "the beginnings of structures have blocks");
        long[] lods0 = FarLods.get(level).stats(), zone0 = FarLods.get(level).zoneStats();
        DhUpdates.testSink(level, (l, chunk) -> {});
        FarLods.testViewer(level, c);
        StrikeGameTests.afterTest(h, () -> {
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        boolean[] requested = {false}, full = {false};
        h.onEachTick(() -> {
            if (!requested[0]) {
                // wait for vanilla to unload it (and write it to disk)
                if (level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) != null) return;
                FarLods.request(level, c.toLong(), true);
                requested[0] = true;
            }
            if (level.getChunkSource().getChunkNow(c.x, c.z) != null) full[0] = true;
        });
        h.succeedWhen(() -> {
            long[] zone = FarLods.get(level).zoneStats();
            long partial = FarLods.get(level).stats()[6] - lods0[6], taken = zone[2] - zone0[2], released = zone[3] - zone0[3], expired = zone[4] - zone0[4];
            h.assertTrue(requested[0], "chunk was not unloaded");
            h.assertTrue(partial == 1, "not integers on disk: " + partial);
            h.assertTrue(full[0], "chunk is not generated");
            h.assertTrue(taken == 1 && released == 1 && expired == 0 && zone[1] == 0 && zone[0] == 0, "squares: waiting " + zone[0] + ", hold on " + zone[1]
                    + ", taken " + taken + ", released " + released + ", by deadline " + expired);
        });
    }

    /**
     * A square that was not generated within the time limit (the chunk never became complete) is released with its ticket, and the zone goes
     *  further: three squares with two in the generation - the third is taken when the first ones are released. Deadline - 0 tick: for tick square
     *  from 25 chunks are not generated from scratch.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "dh_far_zone_slow", skyAccess = true)
    public static void farZoneStalledTileReleased(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos a = farTile(h, -12000, 0);
        ChunkPos[] tiles = {a, new ChunkPos(a.x + 5, a.z), new ChunkPos(a.x + 10, a.z)};
        for (ChunkPos t : tiles) h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(t.toLong()) == null, "chunk " + t + " already in memory");
        long[] zone0 = FarLods.get(level).zoneStats();
        DhUpdates.testSink(level, (l, chunk) -> {});
        FarLods.testViewer(level, tiles[1]);
        FarLods.testZoneLoadLimit(level, 0);
        StrikeGameTests.afterTest(h, () -> {
            FarLods.testZoneLoadLimit(level, -1);
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        for (ChunkPos t : tiles) FarLods.request(level, t.toLong(), true);
        h.succeedWhen(() -> {
            long[] zone = FarLods.get(level).zoneStats();
            long taken = zone[2] - zone0[2], released = zone[3] - zone0[3], expired = zone[4] - zone0[4];
            h.assertTrue(taken == 3 && expired == 3 && released == 0, "squares taken " + taken + ", by deadline " + expired + ", released " + released);
            h.assertTrue(zone[0] == 0 && zone[1] == 0, "waiting for squares " + zone[0] + ", holding on " + zone[1]);
            h.assertTrue(zone[6] <= 2, "in generation at once " + zone[6]);
            for (ChunkPos t : tiles) h.assertTrue(farZoneTickets(level, t) == 0, "at the square " + t + " ticket left");
        });
    }

    /**
     * There are many requests for LOD in the distance (in the heavy zone there are tens of thousands): no more than
     * {@link FarLods#VISITS} are viewed per tick, the rest are seen in a circle, and the entire queue goes through. blackout requests the same chunks before ruins (like
     *  in the game): the ruins request is not lost, and each chunk passes as a chunk with ruins - not whole on disk,
     *  and not “unread”. Chunks are not on disk; the {@code far_zone} setting is turned off - the world is not touched. The server runs at the
     *  pace of the game: the headers are read by the I/O stream.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "dh_far_visits", skyAccess = true)
    public static void farLodsVisitBoundedPerTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos at = farTile(h, 0, 12000);
        long[] lods0 = FarLods.get(level).stats();
        long taken0 = FarLods.get(level).zoneStats()[2];
        boolean was = IncomingConfig.SERVER.nukeFarZone.get();
        IncomingConfig.SERVER.nukeFarZone.set(false);
        DhUpdates.testSink(level, (l, chunk) -> {});
        FarLods.testViewer(level, at);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.nukeFarZone.set(was);
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        int n = 2000;
        for (int i = 0; i < n; i++) FarLods.request(level, ChunkPos.asLong(at.x + i % 50 - 25, at.z + i / 50 - 20), false);
        for (int i = 0; i < n; i++) FarLods.request(level, ChunkPos.asLong(at.x + i % 50 - 25, at.z + i / 50 - 20), true);
        h.assertTrue(FarLods.get(level).stats()[7] - lods0[7] == n, "in line " + (FarLods.get(level).stats()[7] - lods0[7]) + " from " + n);
        h.succeedWhen(() -> {
            long[] s = FarLods.get(level).stats();
            // queue longer than limit: limit and worked
            h.assertTrue(FarLods.get(level).visitsMax() == FarLods.VISITS, "for tick viewed until " + FarLods.get(level).visitsMax() + " requests");
            h.assertTrue(s[6] - lods0[6] == n, "not whole on disk " + (s[6] - lods0[6]) + " from " + n + ", not read " + (s[4] - lods0[4]));
            h.assertTrue(s[7] == 0, "in line " + s[7]);
            h.assertTrue(FarLods.get(level).zoneStats()[2] == taken0, "the square is taken with the setting turned off");
        });
    }

    /**
     * blackout places light requests for its entire radius at once (in the game - up to 159 thousand per 5 s), and requests for ruins
     *  come after the wave: they pass before the light, and do not wait for it all. There are no chunks on the disk: the request for ruins
     *  ends “not whole on disk” (the {@code far_zone} setting is turned off - the world is not touched), the request for light - “not
     *  read” ({@link FarLods#VISITS} views, 8 reads per tick). All requests for ruins end until the light has passed and
     * halves. The server runs at the pace of the game: the headers are read by the I/O stream.
     */
    @GameTest(template = "range", timeoutTicks = 1200, batch = "dh_far_order", skyAccess = true)
    public static void farRuinsGoAheadOfBlackout(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        StrikeGameTests.gameSpeed(h);
        ChunkPos at = farTile(h, 0, -12000);
        long[] lods0 = FarLods.get(level).stats();
        boolean was = IncomingConfig.SERVER.nukeFarZone.get();
        IncomingConfig.SERVER.nukeFarZone.set(false);
        DhUpdates.testSink(level, (l, chunk) -> {});
        FarLods.testViewer(level, at);
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.nukeFarZone.set(was);
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        int lights = 2000, ruins = 64;
        for (int i = 0; i < lights; i++) FarLods.request(level, ChunkPos.asLong(at.x + i % 50 - 25, at.z + i / 50 - 20), false);
        for (int i = 0; i < ruins; i++) FarLods.request(level, ChunkPos.asLong(at.x + i % 8 - 4, at.z + 30 + i / 8), true);
        h.succeedWhen(() -> {
            long[] s = FarLods.get(level).stats();
            long partial = s[6] - lods0[6], unread = s[4] - lods0[4];
            h.assertTrue(partial == ruins && unread < lights / 2, "the ruins have passed " + partial + " from " + ruins + ", light - " + unread + " from " + lights);
        });
    }

    /**
     * There are a lot of marks, and almost all of them have not arrived: no more than 256 is visible per tick, the rest are in a circle, and the
     *  mark that has arrived behind them goes to DH in a few ticks, and does not wait until those in front come.
     */
    @GameTest(template = "range", timeoutTicks = 100, batch = "dh_scan", skyAccess = true)
    public static void marksScanBoundedPerTick(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Sent sent = sink(h);
        ChunkPos c = new ChunkPos(h.absolutePos(CENTER));
        long now = level.getGameTime();
        int n = 2000;
        // far behind the site, not loaded - will not arrive soon
        for (int i = 0; i < n; i++) DhUpdates.mark(level, new ChunkPos(c.x + 1000 + i % 50, c.z + 1000 + i / 50), now + 10_000);
        DhUpdates.mark(level, c, now);
        h.succeedWhen(() -> {
            long[] s = DhUpdates.stats(level);
            h.assertTrue(s[4] <= 256, "per tick viewed " + s[4] + " marks");
            h.assertTrue(sent.count(c) == 1, "the reached mark for the queue did not go away");
            h.assertTrue(sent.first(c) - now <= n / 256 + 3, "left through " + (sent.first(c) - now) + " tick");
            h.assertTrue(s[0] >= n, "waiting " + s[0] + " from " + n);
        });
    }

    /**
     * Heavy-zone LOD with the front ({@code FrontLods}): a strike with flight toward a place not in memory (zone chunks
     * and their ruin windows are on disk). A copy of each zone chunk with a plan is built during flight and sent to DH at tick
     * {@code max(detonation, front arrival at DH section center − LEAD)}; one DH section (4×4 chunks) in one tick, once:
     * a {@code FarLods} request on wave arrival (and light from a quarter blacked out by detonation) does not repeat it.
     * Log output is delivery minus front arrival by chunk. The receiver immediately sends DH write confirmation (absent
     * without DH). Flight and wave run at game pace: disk reads, plans and copies follow wall-clock time.
     */
    @GameTest(template = "range", timeoutTicks = 6000, batch = "dh_front", skyAccess = true)
    public static void heavyZoneLodsGoWithTheFront(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // its place in the distance: zone 1 kt with a scale of 0,25 - 110 blocks, with the edge of the zone and windows of the ruins - 10 chunks from the middle
        ChunkPos t = farTile(h, -9000, 9000);
        int reach = 10 + RuinPlanner.REACH;
        List<ChunkPos> area = new ArrayList<>();
        for (int x = t.x - reach; x <= t.x + reach; x++) for (int z = t.z - reach; z <= t.z + reach; z++) area.add(new ChunkPos(x, z));
        for (ChunkPos c : area) h.assertTrue(level.getChunkSource().chunkMap.getVisibleChunkIfPresent(c.toLong()) == null, "chunk " + c + " already in memory");
        for (ChunkPos c : area) level.getChunk(c.x, c.z);
        Vec3 target = new Vec3(t.getMiddleBlockX() + 0.5, level.getHeight(Heightmap.Types.MOTION_BLOCKING, t.getMiddleBlockX(), t.getMiddleBlockZ()),
                t.getMiddleBlockZ() + 0.5);
        double scale = IncomingConfig.SERVER.nukeEffectsScale.get();
        // copies (ProtoChunk) by chunks: tick returns
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LongArrayList> copies = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        DhUpdates.testSink(level, (l, chunk) -> {
            if (chunk instanceof ProtoChunk) copies.computeIfAbsent(chunk.getPos().toLong(), k -> new LongArrayList()).add(l.getGameTime());
            DhUpdates.saved(l, chunk.getPos().x, chunk.getPos().z);
        });
        FarLods.testViewer(level, t);
        boolean[] prepared = {false};
        Runnable unwatch = NuclearGameTests.watchLog(line -> {
            if (line.startsWith("Ruins for strike #") && line.contains(" ready:")) prepared[0] = true;
        });
        StrikeGameTests.afterTest(h, () -> {
            unwatch.run();
            IncomingConfig.SERVER.nukeEffectsScale.set(scale);
            DhUpdates.testSink(level, null);
            FarLods.testViewer(level, null);
            NuclearStrikes.clear(level);
        });
        NuclearWorld w = NuclearWorld.get(level);
        NuclearEvents events = NuclearEvents.get(level);
        int[] stage = {0}, strike = {0};
        long[] since = {0}, tickAt = {System.nanoTime()};
        Detonation[] det = {null};
        // counting copies - while the preparation is alive (the zone behind the wave has ended - it no longer exists)
        long[][] stats = {null};
        h.onEachTick(() -> {
            long now = level.getGameTime();
            switch (stage[0]) {
                case 0 -> {
                    // generated was downloaded and written to disk
                    var map = level.getChunkSource().chunkMap;
                    for (ChunkPos c : area) if (map.getVisibleChunkIfPresent(c.toLong()) != null || map.pendingUnloads.containsKey(c.toLong())) return;
                    IncomingConfig.SERVER.nukeEffectsScale.set(0.25);
                    strike[0] = events.nextId();
                    events.schedule(new NuclearEvents.ScheduledStrike(strike[0], target, 1, false, now, now + 1200, target, java.util.Optional.empty(), true));
                    since[0] = now;
                    stage[0] = 1;
                }
                case 1 -> {
                    det[0] = events.detonations().stream().filter(x -> x.burst().distanceTo(target) < 20).findFirst().orElse(null);
                    long[] s = w.frontLodStats(strike[0]);
                    if (det[0] != null) {
                        h.assertTrue(prepared[0] && s != null && s[0] > 0 && s[12] == 0 && s[13] == 0, "copies are not ready to be detonated: "
                                + (s == null ? "no preparation" : "collected " + s[0] + ", read " + s[12] + ", waiting to be read " + s[13]));
                        stage[0] = 2;
                        return;
                    }
                    // plans and copies build background flows according to the wall clock: flight - at the pace of the game until the copies are ready
                    if (!prepared[0] || s == null || s[0] == 0 || s[12] > 0 || s[13] > 0) RuinBackgroundGameTests.gamePace(tickAt);
                    else tickAt[0] = System.nanoTime();
                }
                case 2 -> {
                    // wave - at the pace of the game (the path of FarLods upon the arrival of the wave reads from the disk according to the wall clock)
                    RuinBackgroundGameTests.gamePace(tickAt);
                    long[] s = w.frontLodStats(strike[0]);
                    if (s != null) stats[0] = s;
                    if (now < det[0].gameTime() + 200) return;
                    check(h, det[0], copies, stats[0]);
                    h.succeed();
                    stage[0] = 3;
                }
                default -> {
                }
            }
        });
    }

    /** Checking {@link #heavyZoneLodsGoWithTheFront}: zone copies - on time, by section, once; return minus the arrival of the front - in the log. */
    private static void check(GameTestHelper h, Detonation d, it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LongArrayList> copies, long[] s) {
        // zone, like preparation: chunks up to 5 psi near the ground and another 16 blocks
        double lo = 0, hi = d.radiusMax();
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            if (d.psi(new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z)) >= 5) lo = r;
            else hi = r;
        }
        double heavy = lo + 16;
        LongArrayList offsets = new LongArrayList();
        int zone = 0, sent = 0, sections = 0;
        java.util.Map<Long, Long> sectionTick = new java.util.HashMap<>();
        for (var e : copies.long2ObjectEntrySet()) {
            ChunkPos c = new ChunkPos(e.getLongKey());
            double x = Math.max(c.getMinBlockX(), Math.min(d.burst().x, c.getMaxBlockX() + 1)), z = Math.max(c.getMinBlockZ(), Math.min(d.burst().z, c.getMaxBlockZ() + 1));
            if (Math.hypot(x - d.burst().x, z - d.burst().z) > heavy) continue;
            zone++;
            LongArrayList ticks = e.getValue();
            h.assertTrue(ticks.size() == 1, "chunk copy " + c + " left " + ticks.size() + " times: " + ticks);
            long at = ticks.getLong(0);
            int sx = Math.floorDiv(c.x, 4), sz = Math.floorDiv(c.z, 4);
            double dx = sx * 64 + 32 - d.burst().x, dz = sz * 64 + 32 - d.burst().z, dy = d.burst().y - d.groundY();
            long sectionDue = d.gameTime() + (long) d.arrivalTicks(Math.sqrt(dx * dx + dy * dy + dz * dz));
            long expected = Math.max(d.gameTime(), sectionDue - DhUpdates.LEAD);
            h.assertTrue(at >= expected && at <= expected + 2, "chunk copy " + c + " went to " + at + ", term " + expected + " (detonation " + d.gameTime()
                    + ", front at the middle of the section " + sectionDue + ")");
            Long was = sectionTick.putIfAbsent(((long) sx << 32) ^ (sz & 0xFFFFFFFFL), at);
            if (was == null) sections++;
            else h.assertTrue(was == at, "section DH chunk " + c + " left with different ticks: " + was + " and " + at);
            offsets.add(at - ua.zentix.incoming.nuclear.world.ScarQueue.due(d, c));
            sent++;
        }
        long[] o = offsets.toLongArray();
        java.util.Arrays.sort(o);
        Incoming.LOG.info("DH front test: {} copies of the heavy zone in {} sections; send minus front arrival min {} median {} max {} ticks (lead {}); "
                        + "built {}, sent {}, nothing to show {}, loaded {}, over memory {}, front came first {}, not built {}", sent, sections,
                o.length == 0 ? 0 : o[0], o.length == 0 ? 0 : o[o.length / 2], o.length == 0 ? 0 : o[o.length - 1], DhUpdates.LEAD,
                s[0], s[1], s[9], s[5], s[6], s[7], s[8]);
        h.assertTrue(zone > 50, "zone copies gone " + zone);
        h.assertTrue(s[1] == zone, "went to account FrontLods " + s[1] + ", to the receiver - " + zone);
        h.assertTrue(s[8] == 0 && s[6] == 0, "didn't get together " + s[8] + ", supplanted " + s[6]);
    }

    /** The middle of the square 5×5 {@code FarZone} at the site chunk, shifted by {@code dx}, {@code dz} blocks (nothing generated there). */
    private static ChunkPos farTile(GameTestHelper h, int dx, int dz) {
        ChunkPos near = new ChunkPos(h.absolutePos(CENTER).offset(dx, 0, dz));
        return new ChunkPos(Math.floorDiv(near.x, 5) * 5 + 2, Math.floorDiv(near.z, 5) * 5 + 2);
    }

    /** ticket of the square {@code FarZone} with the middle {@code c} throughout the world: the key of its area is {@code UUID(0, c)}. */
    private static int farZoneTickets(ServerLevel level, ChunkPos c) {
        return TicketProbe.count(level, t -> true, new UUID(0L, c.toLong()));
    }

    /** An extinguished quarter in a copy: each lamp is its double without light, the rest is untouched. */
    @GameTest(template = "range", timeoutTicks = 100, batch = "dh_lamps", skyAccess = true)
    public static void farCopyDarkensLamps(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos lamp = h.absolutePos(CENTER.above(2)), sea = h.absolutePos(CENTER.above(3));
        level.setBlock(lamp, Blocks.GLOWSTONE.defaultBlockState(), 2);
        level.setBlock(sea, Blocks.SEA_LANTERN.defaultBlockState(), 2);
        LevelChunk chunk = level.getChunkAt(lamp);
        ProtoChunk copy = FarLods.testCopy(level, chunk, null, false);
        h.assertTrue(copy != null, "no copy");
        // batch GameTest are placed in one place: the quarter could extinguish the nuclear test earlier (blackout at 15 min), and then the
        //  lamps in the copy were already extinguished by the copy itself
        boolean dark = PowerGrid.get(level).dark(chunk.getPos().x, chunk.getPos().z, level.getGameTime());
        int n = ChunkLights.applyToCopy(copy.getSections(), true);
        h.assertTrue(dark ? n == 0 : n >= 2, "lamps turned off " + n + (dark ? " (the block is already dark)" : ""));
        h.assertTrue(copy.getBlockState(lamp) == GridLights.unlit(Blocks.GLOWSTONE.defaultBlockState()), "glowstone: " + copy.getBlockState(lamp));
        h.assertTrue(copy.getBlockState(sea) == GridLights.unlit(Blocks.SEA_LANTERN.defaultBlockState()), "sea lantern: " + copy.getBlockState(sea));
        h.assertTrue(copy.getBlockState(lamp.below()) == chunk.getBlockState(lamp.below()), "the block is touched, not the lamp");
        h.succeed();
    }
}
