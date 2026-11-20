package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongSupplier;

/**
 * Devtest: the worst case for a chunk snapshot ({@link ChunkShot}) — sections holding up to 4096 distinct states each
 * (global palette, a detailed modded city has over 256 a section). Written straight into the sections (no updates,
 * light, block entities or ticks) and put back by {@link #restore} in the same server-thread call, so nothing else sees
 * them.
 */
public final class DenseChunks {
    private final ServerLevel level;
    private final List<LevelChunk> chunks = new ArrayList<>();
    private final int fromSection;
    /** Sections written and their blocks before, in order. */
    private final List<Written> written = new ArrayList<>();

    private record Written(LevelChunkSection section, BlockState[] old) {}
    /** Sections written (global palette). */
    public final int sections;

    /**
     * Fill chunks {@code cx0..cx1 × cz0..cz1} (loaded) from section index {@code fromSection} (world bottom 0) to the top,
     * {@code distinct} different states a section (block-entity-free states of the registry). On a failure part-way, what
     * was written is put back before the exception leaves.
     */
    public DenseChunks(ServerLevel level, int cx0, int cz0, int cx1, int cz1, int fromSection, int distinct) {
        this.level = level;
        this.fromSection = fromSection;
        List<BlockState> pool = new ArrayList<>();
        for (BlockState st : Block.BLOCK_STATE_REGISTRY) {
            if (!st.isAir() && !st.hasBlockEntity() && st.getFluidState().isEmpty()) pool.add(st);
        }
        // the same states in every section: a section's distinct count is what costs; new states' properties are
        // computed once per server run whatever the chunk
        int n = 0;
        try {
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                    if (chunk == null) throw new IllegalStateException("chunk " + cx + " " + cz + " not loaded");
                    LevelChunkSection[] s = chunk.getSections();
                    for (int i = fromSection; i < s.length; i++) {
                        BlockState[] old = new BlockState[4096];
                        for (int p = 0; p < 4096; p++) old[p] = s[i].getBlockState(p & 15, p >> 8, p >> 4 & 15);
                        written.add(new Written(s[i], old));
                        for (int p = 0; p < 4096; p++) s[i].setBlockState(p & 15, p >> 8, p >> 4 & 15, pool.get(p % distinct % pool.size()), false);
                        if (s[i].getStates().data.palette() instanceof GlobalPalette) n++;
                    }
                    chunks.add(chunk);
                }
            }
        } catch (RuntimeException e) {
            restore();
            throw e;
        }
        this.sections = n;
    }

    /** Put the original blocks back (every section written so far). */
    public void restore() {
        for (Written w : written) {
            for (int p = 0; p < 4096; p++) w.section.setBlockState(p & 15, p >> 8, p >> 4 & 15, w.old[p], false);
        }
        written.clear();
    }

    /**
     * Wall-time probe (log only, never asserted: CI runners stall): per section the old publication (vanilla
     * {@code getAll}) against {@link ChunkShot#states}; per chunk the copy without publication and the whole old-style
     * take with publication; then a full collapse snapshot one unit a call (its slowest call).
     */
    public String probe(BlockPos removal) {
        long getAll = 0, distinct = 0, copy = 0, takeAll = 0, maxGetAll = 0;
        int secs = 0;
        // cold first: the states' properties are not in the table yet (computed on first sight)
        long cold = System.nanoTime();
        for (LevelChunk c : chunks) ChunkShot.take(c, null, null, false, true);
        cold = System.nanoTime() - cold;
        for (LevelChunk c : chunks) {
            LevelChunkSection[] s = c.getSections();
            for (int i = fromSection; i < s.length; i++) {
                PalettedContainer<BlockState> states = s[i].getStates();
                long t0 = System.nanoTime();
                states.getAll(ChunkShot::ensure);
                long t1 = System.nanoTime();
                ChunkShot.states(states, ChunkShot::ensure);
                long t2 = System.nanoTime();
                getAll += t1 - t0;
                maxGetAll = Math.max(maxGetAll, t1 - t0);
                distinct += t2 - t1;
                secs++;
            }
            long t0 = System.nanoTime();
            ChunkShot.take(c, null, null, false, false);
            long t1 = System.nanoTime();
            ChunkShot.take(c, null, null, false, true);
            long t2 = System.nanoTime();
            copy += t1 - t0;
            takeAll += t2 - t1;
        }
        Blast.clearProps();
        long[] slowest = {0};
        int calls = 0;
        it.unimi.dsi.fastutil.longs.LongArrayList at = new it.unimi.dsi.fastutil.longs.LongArrayList(new long[]{removal.asLong()});
        if (!StrikeSupport.tryReserve()) return "no solve slot";
        StrikeSupport support = new StrikeSupport(level, at, List.of(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()));
        try {
            LongSupplier clock = System::nanoTime;
            while (!support.started()) {
                long t0 = System.nanoTime();
                support.snapshot(clock, 0);
                slowest[0] = Math.max(slowest[0], System.nanoTime() - t0);
                calls++;
            }
        } finally {
            support.cancel();
        }
        int n = chunks.size();
        return String.format(Locale.ROOT,
                "%d chunks, %d dense sections: publication by vanilla getAll %.2f ms/section (max %.2f), by distinct scan %.3f ms/section; "
                        + "one chunk as one step: old (copy + getAll) %.1f ms, new take with publication %.2f ms (cold properties %.1f ms), "
                        + "copy without publication %.3f ms; collapse snapshot of the window from cold properties: %d units, slowest %.3f ms",
                n, secs, getAll / 1e6 / secs, maxGetAll / 1e6, distinct / 1e6 / secs, (copy + getAll) / 1e6 / n, takeAll / 1e6 / n,
                cold / 1e6 / n, copy / 1e6 / n, calls, slowest[0] / 1e6);
    }

    /** Chunk of a block position. */
    public static ChunkPos chunk(BlockPos p) {
        return new ChunkPos(p);
    }
}
