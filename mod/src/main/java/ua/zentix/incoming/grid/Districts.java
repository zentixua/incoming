package ua.zentix.incoming.grid;

/**
 * Grid districts: irregular areas of about 80 blocks, made of whole chunks. Centers use a grid of cells of {@link #CELL}
 * chunks, each shifted within its cell by a random amount (from cell coordinates, always the same); a chunk belongs to
 * the district with the nearest center (Voronoi cells). Distant district boundaries therefore do not form a square grid.
 * Everything is derived from coordinates: nothing stored, server and checks get the same result.
 */
public final class Districts {
    /** Center grid spacing, chunks (5 chunks = 80 blocks). */
    public static final int CELL = 5;
    /** Maximum center offset from cell center as a fraction of the cell: districts vary in size without degenerating. */
    private static final double JITTER = 0.8;

    private Districts() {}

    /** A chunk's district: key of its center's cell (as {@code ChunkPos.asLong} for cells). */
    public static long of(int chunkX, int chunkZ) {
        double px = chunkX * 16 + 8, pz = chunkZ * 16 + 8;
        int cx = Math.floorDiv(chunkX, CELL), cz = Math.floorDiv(chunkZ, CELL);
        long best = 0;
        double bestD = Double.MAX_VALUE;
        // own center may be farther than 1.27 cells (cell corner, shifted center), and a center two cells away closer: scan 5×5
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int x = cx + dx, z = cz + dz;
                double sx = seedX(x, z) - px, sz = seedZ(x, z) - pz;
                double d = sx * sx + sz * sz;
                if (d < bestD) {
                    bestD = d;
                    best = key(x, z);
                }
            }
        }
        return best;
    }

    public static long key(int cellX, int cellZ) {
        return (long) cellX & 0xFFFFFFFFL | ((long) cellZ & 0xFFFFFFFFL) << 32;
    }

    public static int cellX(long district) {
        return (int) district;
    }

    public static int cellZ(long district) {
        return (int) (district >>> 32);
    }

    /** District center, blocks. */
    public static double seedX(long district) {
        return seedX(cellX(district), cellZ(district));
    }

    public static double seedZ(long district) {
        return seedZ(cellX(district), cellZ(district));
    }

    private static double seedX(int cellX, int cellZ) {
        return (cellX + 0.5 + JITTER * (unit(cellX, cellZ, 1) - 0.5)) * CELL * 16;
    }

    private static double seedZ(int cellX, int cellZ) {
        return (cellZ + 0.5 + JITTER * (unit(cellX, cellZ, 2) - 0.5)) * CELL * 16;
    }

    /** District random number in [0, 1): identical for the same cell and {@code salt}. */
    public static double unit(long district, int salt) {
        return unit(cellX(district), cellZ(district), salt);
    }

    private static double unit(int x, int z, int salt) {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL ^ salt * 0x165667B19E3779F9L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 11) * 0x1.0p-53;
    }
}
