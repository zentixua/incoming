package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.util.PoiRecords;

/**
 * Shared nuclear destruction helpers: block replacement through the world (block entities, crater), detonation fire
 * counter, thermal fluence with shadows. {@link RuinPlanner} handles columns, {@link RuinPlan} applies chunks.
 */
public final class ColumnScar {
    /** No neighbor-update cascade or item drops; clients receive section-batched changes. */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    private ColumnScar() {}

    /** Per-detonation counters: fires capped at configured count. */
    public static final class Budget {
        /** Whether the detonation ignites fires (fresh — yes, forgotten — scorches only). */
        final boolean ignites;
        int fires;
        /**
         * Counted flash-layer fire, by chunks not yet ruined ({@code RuinPlan.write}): ruins over a layer without its plan
         * ({@link FlashScorch#marked}: detonation ruins forgotten after idle time) do not know which chunk fire is counted —
         * remove all of it from the count, add back fire that keeps burning. Counter after restart is new, counted none of
         * the chunk's layer fire.
         */
        final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap layer = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

        public Budget(boolean ignites) {
            this.ignites = ignites;
        }

        /** For checks: counted fires. */
        public int firesForTest() {
            return fires;
        }

        /** For checks: counted flash-layer fire in the chunk ({@link #layer}). */
        public int layerForTest(net.minecraft.world.level.ChunkPos chunk) {
            return layer.get(chunk.toLong());
        }
    }

    /**
     * Replace a block without drops or orphaned block entity data. All scorch and crater block replacements go here.
     * <p>
     * First fetch the block entity via {@link ServerLevel#getBlockEntity}: a never-ticked chunk (fresh generation at render
     * edge) holds deferred data — after generation, a “DUMMY” placeholder for each village bed, bell and chest
     * ({@code WorldGenRegion.setBlock}); after loading, saved data with {@code keepPacked}. The old block's {@code onRemove}
     * removes only a live block entity; deferred data remains in air or water, and on first tick or save vanilla logs
     * “Tried to load a DUMMY block entity … found air”. Fetching promotes it to a live block entity, removed by replacement.
     * For structure or beehive block entities, fresh generation leaves both live entity and placeholder at the same position
     * ({@code ProtoChunk.setBlockEntity} does not remove the placeholder; vanilla clears them on first chunk tick or save,
     * but the post-wave zone loads without ticking): fetch returns the live one, and remove the placeholder alongside it —
     * otherwise chunk save would promote it above air (21 positions 600–840 blocks from a 15 kt burst).
     * <p>
     * {@code UPDATE_SUPPRESS_DROPS} does not suppress container contents: chest, barrel and furnace spill them in
     * {@code onRemove} — thousands of ground items in a village, then ticking. As {@code /setblock} and {@code /fill} do,
     * {@link Clearable#tryClear} empties the block entity before replacement.
     * <p>
     * An unopened generated chest holds a loot table, not items: expanded at first content read — and {@code onRemove}
     * reads it to spill it. An explorer map in loot searches for a structure and renders biomes on the server thread
     * (bench — 567 ms for one chest). The blast vaporizes the chest without loot: remove the table before replacement.
     */
    public static void replace(ServerLevel level, BlockPos pos, BlockState old, BlockState with) {
        replace(level, pos, old, with, FLAGS);
    }

    /** The same with {@code flags} ({@code setBlock}): neighbor updates make loose material above the position fall. */
    public static void replace(ServerLevel level, BlockPos pos, BlockState old, BlockState with, int flags) {
        if (old.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof RandomizableContainer loot) loot.setLootTable(null);
            Clearable.tryClear(be);
            level.getChunkAt(pos).pendingBlockEntities.remove(pos);
        }
        if (!old.is(with.getBlock())) PoiRecords.recordIfMissing(level, pos, old);
        level.setBlock(pos, with, flags);
    }

    /** Thermal fluence at a point with terrain and building shadows, cal/cm². */
    static double lit(Level level, Detonation d, BlockPos pos) {
        Vec3 p = Vec3.atCenterOf(pos).add(0, 0.5, 0);
        return ThermalShadow.visible(level, d.burst(), p) ? d.fluence(p) : 0;
    }
}
