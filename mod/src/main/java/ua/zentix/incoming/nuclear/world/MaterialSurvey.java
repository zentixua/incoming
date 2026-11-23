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

import java.util.ArrayList;
import ua.zentix.incoming.nuclear.material.LoadedSurfaces;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Per scheduled source. Only the level thread owns the live votes. Closing transfers them to an immutable revision. */
final class MaterialSurvey {
    record Sample(long chunk, int votes) {}
    private final java.util.UUID revision = java.util.UUID.randomUUID();
    private final Detonation geometry;
    int sparseReads, storedAnswers;
    private final Long2IntOpenHashMap votes = new Long2IntOpenHashMap();
    private final RuinInbox<Sample> reads;
    private final CompletableFuture<long[]> sparse;
    private DiskShots.Format format;
    private int next;
    private boolean closed;

    MaterialSurvey(ServerLevel level, Detonation d, AtomicLong reservations) {
        geometry = d;
        reads = new RuinInbox<>(reservations::addAndGet, NuclearPrep.READ_BYTES);
        var regions = ua.zentix.incoming.util.RegionFiles.folder(level);
        sparse = CompletableFuture.supplyAsync(() -> {
            long[] cells = sparse(d);
            return java.util.Arrays.stream(cells).filter(key -> ua.zentix.incoming.util.RegionFiles.present(regions,
                    ChunkPos.getX(key), ChunkPos.getZ(key))).toArray();
        }, Util.backgroundExecutor());
    }

    /** One representative chunk per world-aligned outer cell, nearest first. No chunk generation. */
    static long[] sparse(Detonation d) {
        DustGrid.Bounds b = DustGrid.Bounds.of(d);
        var positions = new ArrayList<ChunkPos>();
        int step = DustGrid.COARSE;
        double inner = NuclearPrep.heavyRadius(d) + RuinPlanner.REACH * 16;
        for (int z = Math.floorDiv(b.z() - b.reach(), step); z <= Math.floorDiv(b.z() + b.reach(), step); z++) {
            for (int x = Math.floorDiv(b.x() - b.reach(), step); x <= Math.floorDiv(b.x() + b.reach(), step); x++) {
                var pos = new ChunkPos((x * step + step / 2) >> 4, (z * step + step / 2) >> 4);
                double distance = Math.hypot(pos.getMiddleBlockX() - b.x(), pos.getMiddleBlockZ() - b.z());
                if (distance > inner && distance <= b.reach() + step * Math.sqrt(0.5)) positions.add(pos);
            }
        }
        positions.sort(Comparator.comparingDouble(p -> Math.hypot(p.getMiddleBlockX() - b.x(), p.getMiddleBlockZ() - b.z())));
        return positions.stream().mapToLong(ChunkPos::toLong).toArray();
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
        if (!closed && value != 0) votes.putIfAbsent(chunk, value);
    }

    void work(ServerLevel level, long deadline, int slots) {
        Sample s;
        while (System.nanoTime() < deadline && (s = reads.poll()) != null) collect(s);
        long[] positions = sparse.getNow(null);
        if (positions == null) return;
        while (next < positions.length && slots > 0 && reads.pending() < 4 && System.nanoTime() < deadline) {
            if (!RuinWorkers.admit(RuinWorkers.Priority.FAR) || NuclearPrep.memory(level) + NuclearPrep.READ_BYTES > NuclearPrep.PLANS_CAP) return;
            long key = positions[next++];
            if (votes.containsKey(key) || level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) != null) continue;
            if (format == null) format = DiskShots.Format.of(level);
            var receive = reads.expect();
            slots--;
            sparseReads++;
            DiskShots.readSurface(format, new ChunkPos(key)).thenAccept(value -> receive.accept(new Sample(key, value)));
        }
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
        return c.getFluidState(at).is(net.minecraft.tags.FluidTags.WATER) ? DustMaterial.WATER.ordinal() : DustMaterial.SOIL.ordinal();
    }
    MaterialBirth seal(ServerLevel level, long tick, LoadedSurfaces live) {
        Sample s;
        while ((s = reads.poll()) != null) collect(s);
        int x = (int) Math.floor(geometry.burst().x), z = (int) Math.floor(geometry.burst().z);
        var c = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (c != null) live.put(c.getPos().toLong(), liveVotes(c));
        close();
        return MaterialBirth.seal(revision, geometry, tick, origin(level, geometry), votes, live.snapshot());
    }
    void close() {
        closed = true;
        reads.invalidate();
    }
}
