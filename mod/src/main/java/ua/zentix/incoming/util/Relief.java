package ua.zentix.incoming.util;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Arrays;
import java.util.function.IntBinaryOperator;

/** Surface height at a world column; analytic flight models may also return fractional heights. */
@FunctionalInterface
public interface Relief {
    double at(int x, int z);

    /** One chunk's integer height view, queried with world column coordinates. */
    @FunctionalInterface
    interface Chunks {
        IntBinaryOperator at(int chunkX, int chunkZ);
    }

    /**
     * One synchronous measurement: resolve each chunk once and read each column once. Never retain this cache
     * between measurements, even within a tick: blocks and chunk readiness can change. Cache raw heights only;
     * revisiting a column along another segment still requires that segment's own climb correction.
     */
    final class Survey implements Relief {
        /** Outside Minecraft's build-height range; unknown terrain itself still returns the world's bottom. */
        private static final int UNREAD = Integer.MIN_VALUE;
        private final Chunks source;
        private final Long2ObjectOpenHashMap<Columns> chunks = new Long2ObjectOpenHashMap<>();

        public Survey(Chunks source) {
            this.source = source;
        }

        @Override
        public double at(int x, int z) {
            int cx = x >> 4, cz = z >> 4;
            long key = (cx & 0xffffffffL) | ((long) cz << 32);
            Columns columns = chunks.get(key);
            if (columns == null) {
                columns = new Columns(source.at(cx, cz));
                chunks.put(key, columns);
            }
            int i = (x & 15) + (z & 15) * 16;
            if (columns.y[i] == UNREAD) columns.y[i] = columns.source.applyAsInt(x, z);
            return columns.y[i];
        }

        private static final class Columns {
            final IntBinaryOperator source;
            final int[] y = new int[256];

            Columns(IntBinaryOperator source) {
                this.source = source;
                Arrays.fill(y, UNREAD);
            }
        }
    }
}
