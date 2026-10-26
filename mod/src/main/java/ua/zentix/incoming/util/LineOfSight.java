package ua.zentix.incoming.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Line of sight between two points over terrain: blocked by ground and buildings — columns whose tops exceed the line
 * where it crosses them. Only ready chunks known (heightmap {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES}): a column
 * in an unready chunk does not block — do not load chunks ({@code CLAUDE.md}). Leaves do not block: leafless heightmap.
 * An overhang (arch, canopy) counts as solid to its top.
 */
public final class LineOfSight {
    private LineOfSight() {}

    /** Column top unknown: chunk not ready. */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    /** First air above column (x, z), or {@link #UNKNOWN}. */
    @FunctionalInterface
    public interface Columns {
        int top(int x, int z);
    }

    /**
     * No known column blocks {@code from → to}: every intersected column (grid traversal, no clipped-corner gaps —
     * {@link GridWalk}) is below the line along the whole segment above it.
     */
    public static boolean clear(Vec3 from, Vec3 to, Columns columns) {
        double dy = to.y - from.y;
        return GridWalk.walk(from.x, from.z, to.x, to.z, 1, (GridWalk.SpanVisitor) (x, z, t0, t1) -> {
            int top = columns.top(x, z);
            // line over a column is a segment, lowest at one endpoint
            return top == UNKNOWN || Math.min(from.y + dy * t0, from.y + dy * t1) >= top;
        }) >= 1;
    }

    /** {@link #clear(Vec3, Vec3, Columns)} using ready world chunk heightmaps. */
    public static boolean clear(ServerLevel level, Vec3 from, Vec3 to) {
        return clear(from, to, new ReadyColumns(level));
    }

    /** Ready chunk heightmaps: line follows consecutive columns, chunk lookup once per 16 columns. */
    private static final class ReadyColumns implements Columns {
        private final ServerLevel level;
        private int cx = Integer.MIN_VALUE, cz;
        private LevelChunk chunk;

        ReadyColumns(ServerLevel level) {
            this.level = level;
        }

        @Override
        public int top(int x, int z) {
            if (x >> 4 != cx || z >> 4 != cz) {
                cx = x >> 4;
                cz = z >> 4;
                chunk = level.getChunkSource().getChunkNow(cx, cz);
            }
            return chunk == null ? UNKNOWN : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
        }
    }
}
