package ua.zentix.incoming.client.map;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.util.Terrain;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;
import java.util.function.LongToIntFunction;
import java.util.function.Predicate;

/**
 * Column tops from Voxy database sections (see {@link VoxyTerrain}): same data that the client chunk layer
 * ({@link LoadedChunksTerrain}) and LSS ({@link LssColumns}) obtain from blocks: first {@code MOTION_BLOCKING}
 * heightmap block from above, then first vanilla map-coloured block below, water depth beneath. No Voxy classes:
 * computed here, so covered by unit tests.
 * <p>
 * Level {@code L} Voxy section has 32³ voxels of {@code 2^L} blocks (index {@code y·1024 + z·32 + x}); above level zero,
 * Voxy combines eight smaller voxels using the most opaque (leaves count as fully opaque), on a tie the +x child first,
 * then +y, then +z, and air only if all are air
 * ({@code Mipper}). Thus the top non-air voxel in a column is the highest block of the {@code 2^L × 2^L} cell,
 * but its exact height within the voxel is unknown: take voxel midpoint ({@link #height}), error up to
 * ±{@code 2^L/2} blocks. Level 0 is exact, like chunks.
 * <p>
 * Column packed as in {@link LssColumns#pack}; 0 if unknown (no sections: Voxy has not seen the location).
 */
final class VoxyColumns {
    /** Voxy section side, voxels. */
    static final int SIDE = 32;
    /** Columns per section. */
    static final int AREA = SIDE * SIDE;
    /** Voxels per section ({@code WorldSection.SECTION_VOLUME}). */
    static final int VOLUME = AREA * SIDE;
    /** Block information ({@link #info}): whether known at all (0 unknown, treated as air). */
    static final int KNOWN = 1 << 8;
    /** {@code MOTION_BLOCKING} heightmap block: blocks movement or contains fluid. */
    static final int MOTION = 1 << 9;
    /** Water (including waterlogged block). */
    static final int WATER = 1 << 10;
    /** Leaves ({@code minecraft:leaves}): a canopy over the ground where dust lies ({@code Terrain.dustTop}). */
    static final int LEAVES = 1 << 11;
    /** {@code MOTION_BLOCKING_NO_LEAVES} block: the ground under a canopy. */
    static final int GROUND = 1 << 12;
    private static final Predicate<BlockState> MOTION_BLOCKING = Heightmap.Types.MOTION_BLOCKING.isOpaque();
    private static final Predicate<BlockState> NO_LEAVES = Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque();

    private static final byte TOP = 0, COLOR = 1, DEPTH = 2, DONE = 3;
    private static final int SEARCHING = Integer.MIN_VALUE, UNKNOWN = Integer.MIN_VALUE + 1, NONE = Integer.MAX_VALUE;

    private VoxyColumns() {}

    /**
     * Block information for decoding: map colour ID (bits 0–5), {@link #KNOWN}, {@link #MOTION}, {@link #WATER}, {@link #LEAVES},
     * {@link #GROUND}.
     */
    static int info(BlockState state) {
        MapColor color = state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        FluidState fluid = state.getFluidState();
        boolean water = fluid.is(FluidTags.WATER) || fluid.getType().isSame(Fluids.WATER);
        return KNOWN | color.id & 0x3F | (MOTION_BLOCKING.test(state) ? MOTION : 0) | (water ? WATER : 0)
                | (Terrain.canopy(state) ? LEAVES : 0) | (NO_LEAVES.test(state) ? GROUND : 0);
    }

    /**
     * Column height (first air above ground) from a level {@code level} voxel with bottom {@code bottom}: block top
     * lies somewhere between {@code bottom + 1} and {@code bottom + 2^L}; take the midpoint.
     */
    static int height(int level, int bottom) {
        return bottom + ((1 << level) + 1) / 2;
    }

    /**
     * Summarize one column of level {@code level} sections (32×32 columns), sections top-down ({@link #section}) until
     * {@link #done}; result in {@link #finish}.
     */
    static final class Summary {
        final int level;
        private final int scale, colorless;
        private final int[] out = new int[AREA];
        private final byte[] state = new byte[AREA];
        /** Voxels left to search for colour; water depth in voxels; found colour and height. */
        private final int[] budget = new int[AREA], depth = new int[AREA], color = new int[AREA], top = new int[AREA];
        /**
         * Under leaves on top the scan goes on down to the ground ({@link #GROUND}) after colour and depth: its height,
         * {@link #SEARCHING} while searching, {@link #NONE} without a canopy, {@link #UNKNOWN} where no ground was proven. A
         * column ends when both end. Above level 0 a voxel holding leaves may hold the forest floor too (Voxy mips leaves as
         * fully opaque, like grass and stone: a level-4 forest read 10-21 m under its floor); only an air voxel between the leaves
         * and the ground proves the gap, else the canopy is unknown. The leaves voxel's bottom, to stop the scan
         * {@link LssColumns#MAX_CANOPY} blocks under it.
         */
        private final int[] ground = new int[AREA], crown = new int[AREA];
        private final boolean[] gap = new boolean[AREA];
        private int open = AREA;
        /** Last supplied section: next one is one below. */
        private int lastY = Integer.MAX_VALUE;

        Summary(int level) {
            this.level = level;
            Arrays.fill(ground, NONE);
            this.scale = 1 << level;
            // Search 8 blocks below transparent blocks (glass, light) for colour, as in the chunk layer.
            this.colorless = Math.max(1, LssColumns.MAX_COLORLESS >> level);
        }

        boolean done() {
            return open == 0;
        }

        /**
         * Section {@code sy} (height index at this level), next below previous; {@code data} contains its voxels, null
         * if absent from database (air or unseen by Voxy). {@code blockId} is voxel block ID; {@code info} gives block
         * information by ID (0 unknown).
         */
        void section(int sy, @Nullable long[] data, LongToIntFunction blockId, IntUnaryOperator info) {
            if (sy >= lastY) throw new IllegalArgumentException("sections go top down: " + sy + " after " + lastY);
            lastY = sy;
            for (int i = 0; i < AREA && open > 0; i++) {
                if (finished(i)) continue;
                for (int ly = SIDE - 1; ly >= 0 && !finished(i); ly--) {
                    int bottom = ((sy << 5) + ly) << level;
                    int id = data == null ? 0 : blockId.applyAsInt(data[ly << 10 | i]);
                    int b = id == 0 ? 0 : info.applyAsInt(id);
                    step(i, b, bottom);
                }
            }
        }

        private boolean finished(int i) {
            return state[i] == DONE && ground[i] != SEARCHING;
        }

        /** One voxel in column {@code i}, top-down: information for its block {@code b} (0 air or unknown). */
        private void step(int i, int b, int bottom) {
            if (ground[i] == SEARCHING) {
                // the voxel right above the ground must be air (or what the floor would out-mip: no MOTION), not lower leaves
                if (b == 0 || (b & MOTION) == 0) gap[i] = true;
                else if ((b & LEAVES) != 0) gap[i] = false;
                if ((b & GROUND) != 0 || bottom < crown[i] - LssColumns.MAX_CANOPY) {
                    // the ground voxel's top: the block top lies in it, the gap above it is air
                    ground[i] = (b & GROUND) != 0 && (level == 0 || gap[i]) ? bottom + scale : UNKNOWN;
                    if (state[i] == DONE) open--;
                }
            }
            switch (state[i]) {
                case TOP -> {
                    if ((b & MOTION) == 0) return;
                    if ((b & LEAVES) != 0 && (b & GROUND) == 0) {
                        ground[i] = SEARCHING;
                        crown[i] = bottom;
                    }
                    state[i] = COLOR;
                    budget[i] = colorless;
                    colour(i, b, bottom);
                }
                case COLOR -> colour(i, b, bottom);
                case DEPTH -> {
                    if ((b & WATER) == 0) {
                        close(i);
                        return;
                    }
                    // Water deeper than 16 blocks has the same map darkness: stop searching.
                    if (++depth[i] * scale >= LssColumns.MAX_WATER_DEPTH) close(i);
                }
                default -> {}
            }
        }

        private void colour(int i, int b, int bottom) {
            int c = b & 0x3F;
            if ((b & KNOWN) != 0 && c != MapColor.NONE.id) {
                color[i] = c;
                top[i] = height(level, bottom);
                if ((b & WATER) != 0) {
                    depth[i] = 1;
                    state[i] = DEPTH;
                    if (scale >= LssColumns.MAX_WATER_DEPTH) close(i);
                    return;
                }
                close(i);
                return;
            }
            if (--budget[i] > 0) return;
            // No colour below transparent blocks either: unknown column, as in the chunk layer.
            // (its canopy is of no use without a column)
            color[i] = -1;
            ground[i] = UNKNOWN;
            state[i] = DONE;
            open--;
        }

        private void close(int i) {
            state[i] = DONE;
            if (ground[i] != SEARCHING) open--;
        }

        /**
         * Columns (index {@code z·32 + x}): water with bottom below the last section keeps the accumulated depth; a canopy
         * whose ground lies below the last section is unknown.
         */
        int[] finish() {
            for (int i = 0; i < AREA; i++) {
                if (state[i] == DEPTH) state[i] = DONE;
                if (state[i] != DONE || color[i] < 0) continue;
                int g = ground[i];
                int canopy = g == NONE ? 0 : g == SEARCHING || g == UNKNOWN ? -1 : Math.max(0, top[i] - g);
                out[i] = LssColumns.pack(top[i], color[i], depth[i] * scale, canopy);
            }
            return out;
        }
    }
}
