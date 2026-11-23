package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.material.DustGrid;
import ua.zentix.incoming.nuclear.material.DustMaterial;
import ua.zentix.incoming.nuclear.material.MaterialBirth;

import ua.zentix.incoming.nuclear.material.LoadedSurfaces;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Per scheduled source. Only the level thread owns the live votes. Closing transfers them to an immutable revision. */
final class MaterialSurvey {
    record Sample(long chunk, int votes) {}
    private final MaterialBirth prepared;
    private final Detonation geometry;
    int sparseReads, storedAnswers;
    private Long2IntOpenHashMap votes = new Long2IntOpenHashMap();
    private final RuinInbox<Sample> reads;
    private final CompletableFuture<ReadPlan> sparse;
    private DiskShots.Format format;
    private int next;
    private boolean closed;
    MaterialBirth birth;
    private MaterialBirth.Encoded lastPublished;
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet sampled = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();


    MaterialSurvey(ServerLevel level, Detonation d, AtomicLong reservations) {
        geometry = d;
        prepared = MaterialBirth.prepare(java.util.UUID.randomUUID(), d);
        reads = new RuinInbox<>(reservations::addAndGet, NuclearPrep.READ_BYTES);
        var regions = ua.zentix.incoming.util.RegionFiles.folder(level);
        sparse = CompletableFuture.supplyAsync(() -> plan(d, regions), Util.backgroundExecutor()).exceptionally(error -> {
            ua.zentix.incoming.Incoming.LOG.warn("Cannot survey stored surfaces for source {}", prepared.id, error);
            return new ReadPlan(null, new long[0], new int[0]);
        });
    }

    private record ReadPlan(DustGrid grid, long[] positions, int[] deadline) {
        int firstEligible(int next, long age) {
            int hi = deadline.length;
            while (next < hi) {
                int mid = (next + hi) >>> 1;
                if (deadline[mid] < age) next = mid + 1;
                else hi = mid;
            }
            return next;
        }
    }

    static long[] sparse(Detonation d) { return plan(d, null).positions; }

    /** One representative stored chunk per addressable cell outside preparation, in first-contact order. */
    private static ReadPlan plan(Detonation d, java.nio.file.Path regions) {
        var grid = new DustGrid(DustGrid.Bounds.of(d));
        var b = grid.bounds;
        double inner = NuclearPrep.heavyRadius(d) + RuinPlanner.REACH * 16;
        var exists = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
        var chunks = new it.unimi.dsi.fastutil.longs.LongArrayList();
        var order = new it.unimi.dsi.fastutil.longs.LongArrayList();
        var front = d.front();
        double height = d.burst().y - d.groundY();
        grid.visit((i, x, z, size) -> {
            if (Math.hypot(x - b.x(), z - b.z()) <= inner) return;
            int cx = x >> 4, cz = z >> 4;
            if (grid.index(cx * 16 + 8, cz * 16 + 8) != i) {
                // A fine cell can mask the middle of an otherwise addressable coarse cell.
                int loX = (x - size / 2) >> 4, loZ = (z - size / 2) >> 4;
                boolean found = false;
                for (int iz = loZ; !found && iz < loZ + size / 16; iz++) {
                    for (int ix = loX; ix < loX + size / 16; ix++) {
                        if (grid.index(ix * 16 + 8, iz * 16 + 8) == i) { cx = ix; cz = iz; found = true; break; }
                    }
                }
                if (!found) return;
            }
            if (regions != null) {
                long region = ChunkPos.asLong(cx >> 5, cz >> 5);
                if (!exists.containsKey(region)) exists.put(region, ua.zentix.incoming.util.RegionFiles.present(regions, cx, cz));
                if (!exists.get(region)) return;
            }
            order.add((long) DustGrid.deadline(front, height, grid.nearSquared(x, z, size)) << 24 | chunks.size());
            chunks.add(ChunkPos.asLong(cx, cz));
        });
        long[] sorted = order.toLongArray();
        java.util.Arrays.sort(sorted);
        int[] due = new int[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            due[i] = (int) (sorted[i] >> 24);
            sorted[i] = chunks.getLong((int) (sorted[i] & 0xffffff));
        }
        return new ReadPlan(grid, sorted, due);
    }

    /** Four equal-area subcolumns. Stored palettes are detached from the world and only scanned by the parser. */
    static int sample(PalettedContainer<BlockState>[] sections) {
        int votes = 0;
        for (int z = 4; z < 16; z += 8) for (int x = 4; x < 16; x += 8) {
            column: for (int sec = sections.length - 1; sec >= 0; sec--) {
                if (sections[sec] == null) continue;
                for (int y = 15; y >= 0; y--) {
                    BlockState state = sections[sec].get(x, y, z);
                    if (!surface(state)) continue;
                    votes += 1 << (4 * DustMaterial.from(state).ordinal());
                    break column;
                }
            }
        }
        return votes;
    }
    /** The top non-air block supplies the material. Permeable canopy and plants expose their support, as in FarTerrain.dustY. */
    private static boolean surface(BlockState state) {
        return !state.isAir() && (state.getBlock() == Blocks.SNOW || Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(state));
    }

    private static int top(LevelChunk chunk, int x, int z) {
        // A ready FULL chunk normally has WORLD_SURFACE. An editor chunk without it must never trigger vanilla priming.
        int top = chunk.getMaxBuildHeight() - 1;
        if (chunk.hasPrimedHeightmap(Heightmap.Types.WORLD_SURFACE)) top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        if (top < chunk.getMinBuildHeight()) return -1;
        var sections = chunk.getSections();
        int first = chunk.getSectionIndex(top);
        for (int i = first; i >= 0; i--) {
            if (sections[i].hasOnlyAir()) continue;
            for (int y = i == first ? top & 15 : 15; y >= 0; y--) {
                BlockState state = sections[i].getBlockState(x & 15, y, z & 15);
                if (surface(state)) return DustMaterial.from(state).ordinal();
            }
        }
        return -1;
    }
    static int liveVotes(LevelChunk chunk) {
        ChunkPos p = chunk.getPos();
        int value = 0;
        for (int z = 4; z < 16; z += 8) for (int x = 4; x < 16; x += 8) {
            int material = top(chunk, p.getMinBlockX() + x, p.getMinBlockZ() + z);
            if (material >= 0) value += 1 << (4 * material);
        }
        return value;
    }
    void disk(long chunk, int value) {
        var plan = sparse.getNow(null);
        if (closed || value == 0 || plan != null && plan.grid != null
                && plan.grid.index(ChunkPos.getX(chunk) * 16 + 8, ChunkPos.getZ(chunk) * 16 + 8) < 0 || !sampled.add(chunk)) return;
        votes.putIfAbsent(chunk, value);
        if (birth == null) {
            long dx = ChunkPos.getX(chunk) * 16L + 8 - (int) Math.floor(geometry.burst().x);
            long dz = ChunkPos.getZ(chunk) * 16L + 8 - (int) Math.floor(geometry.burst().z);
            long distance = dx * dx + dz * dz;
            if (distance < nearestDistance || distance == nearestDistance && chunk < nearestKey) {
                nearestDistance = distance;
                nearestKey = chunk;
                nearestSeed = DustMaterial.ofVotes(value).ordinal();
            }
        }
    }
    void live(long chunk, int value) {
        if (birth != null && value != 0) votes.put(chunk, value | (1 << 16));
    }

    void work(ServerLevel level, long deadline, int slots) {
        Sample s;
        while (System.nanoTime() < deadline && (s = reads.poll()) != null) collect(s);
        var plan = sparse.getNow(null);
        if (plan == null || closed) return;
        long[] positions = plan.positions;
        if (birth != null) {
            if (!birth.surveying()) return;
            next = plan.firstEligible(next, level.getGameTime() - birth.burstTick());
        }
        if (next == positions.length || slots <= 0) return;
        long headroom = NuclearPrep.PLANS_CAP - NuclearPrep.memory(level);
        slots = (int) Math.min(slots, headroom / NuclearPrep.READ_BYTES);
        while (next < positions.length && slots > 0 && System.nanoTime() < deadline) {
            long key = positions[next++];
            if (sampled.contains(key) || level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null) continue;
            if (format == null) format = DiskShots.Format.of(level);
            slots--;
            var receive = request(key);
            DiskShots.readSurface(format, new ChunkPos(key)).thenAccept(receive);
        }
    }

    /** Reservation and completion for one real palette read. Birth never cancels an outer cell's request. */
    java.util.function.Consumer<Integer> request(long key) {
        sparseReads++;
        var receive = reads.expect();
        return value -> receive.accept(new Sample(key, value));
    }

    private void collect(Sample s) {
        if (s.votes != 0) storedAnswers++;
        disk(s.chunk, s.votes);
    }

    static int origin(ServerLevel level, Detonation d) {
        int x = (int) Math.floor(d.burst().x), z = (int) Math.floor(d.burst().z);
        var c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (level.dimensionType().hasCeiling()) return DustMaterial.HARD.ordinal();
        if (c == null) return -1;
        BlockPos at = new BlockPos(x, (int) Math.ceil(d.groundY()) - 1, z);
        return DustMaterial.from(c.getBlockState(at)).ordinal();
    }
    private int nearestSeed = DustMaterial.SOIL.ordinal();
    private long nearestKey = Long.MAX_VALUE, nearestDistance = Long.MAX_VALUE;

    MaterialBirth begin(ServerLevel level, long burst, long now, LoadedSurfaces live) {
        collectReady();
        int seed = origin(level, geometry);
        prepared.begin(geometry, burst, now, seed < 0 ? nearestSeed : seed, votes, live.snapshot());
        birth = prepared;
        votes = new Long2IntOpenHashMap();
        return birth;
    }
    private void collectReady() {
        Sample s;
        while ((s = reads.poll()) != null) collect(s);
    }

    /** Fence the inbox before closing this tick. A worker completing after this fence belongs to the next tick. */
    boolean advance(long tick) {
        collectReady();
        if (birth == null) return false;
        birth.advance(tick, votes);
        boolean dirty = !votes.isEmpty();
        if (dirty) votes = new Long2IntOpenHashMap();
        return dirty;
    }
    boolean publish() {
        if (birth == null) return false;
        var data = birth.encoded();
        boolean changed = lastPublished != data;
        lastPublished = data;
        return changed;
    }

    boolean wantsReads() {
        var plan = sparse.getNow(null);
        return !closed && (reads.pending() > 0 || plan == null || next < plan.positions.length);
    }

    boolean publishedFinal() { return birth != null && !birth.surveying() && lastPublished == birth.encoded(); }

    void close() {
        closed = true;
        reads.invalidate();
    }
}
