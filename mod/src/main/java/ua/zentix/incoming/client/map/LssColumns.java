package ua.zentix.incoming.client.map;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.Predicate;

/**
 * Column tops from chunks sent by the LOD Server Support server (see {@link LssTerrain}): the same data the client
 * chunk layer ({@link LoadedChunksTerrain}) takes from its world: first {@code MOTION_BLOCKING} heightmap block
 * (with canopies and buildings), then first vanilla map-coloured block below, and water depth beneath it. No LSS API:
 * computed and stored here, so covered by unit tests.
 * <p>
 * Column packed in an {@code int}: height + 2048 (12 bits), {@link MapColor} ID (6 bits), water depth 0–16 (5 bits),
 * presence bit; 0 means nothing known about the column.
 */
final class LssColumns {
    /** Water deeper than this has the same map darkness (as in {@link LoadedChunksTerrain}). */
    static final int MAX_WATER_DEPTH = 16;
    /** Blocks to search below the heightmap top for a coloured block under transparent ones (glass, light). */
    static final int MAX_COLORLESS = 8;
    /** Detailed chunk columns: every column. */
    static final int FULL = 256;
    /** Coarse chunk columns: centre of each 4×4 block cell, the same columns as a level 2 tile. */
    static final int COARSE = 16;
    private static final int PRESENT = 1 << 23;
    private static final int HEIGHT_BIAS = 2048;
    /** {@code MOTION_BLOCKING} heightmap blocks: block movement or contain fluid. */
    private static final Predicate<BlockState> MOTION_BLOCKING = Heightmap.Types.MOTION_BLOCKING.isOpaque();

    private LssColumns() {}

    static int pack(int height, int color, int waterDepth) {
        int h = Math.max(0, Math.min(4095, height + HEIGHT_BIAS));
        return PRESENT | h | (color & 0x3F) << 12 | Math.min(MAX_WATER_DEPTH, Math.max(0, waterDepth)) << 18;
    }

    static boolean present(int packed) {
        return (packed & PRESENT) != 0;
    }

    static int height(int packed) {
        return (packed & 0xFFF) - HEIGHT_BIAS;
    }

    static int color(int packed) {
        return packed >>> 12 & 0x3F;
    }

    static int waterDepth(int packed) {
        return packed >>> 18 & 0x1F;
    }

    @Nullable
    static TerrainSource.Column column(int packed) {
        return present(packed) ? new TerrainSource.Column(height(packed), MapColor.byId(color(packed)), waterDepth(packed)) : null;
    }

    /** Chunk section: 16³ blocks at local coordinates. */
    interface Section {
        /** Air only: no need to inspect blocks. */
        boolean empty();

        BlockState block(int x, int y, int z);
    }

    /**
     * Tops of all 256 chunk columns (index {@code z·16 + x}). {@code sections[i]} is section {@code minSection + i}
     * (section number = y >> 4); null means absent, all air (LSS sends only nonempty sections).
     */
    static int[] summarize(int minSection, @Nullable Section[] sections) {
        int[] out = new int[FULL];
        int top = sections.length * 16 - 1;
        while (top >= 0 && (sections[top >> 4] == null || sections[top >> 4].empty())) top = (top & ~15) - 1;
        if (top < 0) return out;
        int minY = minSection * 16;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) out[z * 16 + x] = column(sections, top, minY, x, z);
        }
        return out;
    }

    /** Column (x, z) top-down from {@code top} (block index from the first section's bottom). */
    private static int column(@Nullable Section[] sections, int top, int minY, int x, int z) {
        int y = top;
        // MOTION_BLOCKING heightmap top: skip empty sections as a whole.
        for (; y >= 0; y--) {
            Section s = sections[y >> 4];
            if (s == null || s.empty()) {
                y &= ~15;
                continue;
            }
            if (MOTION_BLOCKING.test(s.block(x, y & 15, z))) break;
        }
        if (y < 0) return 0;
        // First map-coloured block below, as in the client chunk layer.
        for (int bottom = Math.max(0, y + 1 - MAX_COLORLESS); y >= bottom; y--) {
            BlockState state = block(sections, x, y, z);
            MapColor color = state == null ? MapColor.NONE : state.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (color == MapColor.NONE) continue;
            return pack(minY + y + 1, color.id, water(state.getFluidState()) ? waterDepth(sections, x, y, z) : 0);
        }
        return 0;
    }

    @Nullable
    private static BlockState block(@Nullable Section[] sections, int x, int y, int z) {
        Section s = sections[y >> 4];
        return s == null || s.empty() ? null : s.block(x, y & 15, z);
    }

    private static boolean water(FluidState fluid) {
        return fluid.is(FluidTags.WATER) || fluid.getType().isSame(Fluids.WATER);
    }

    private static int waterDepth(@Nullable Section[] sections, int x, int surface, int z) {
        int depth = 1;
        while (depth < MAX_WATER_DEPTH && surface - depth >= 0) {
            BlockState below = block(sections, x, surface - depth, z);
            if (below == null || !water(below.getFluidState())) break;
            depth++;
        }
        return depth;
    }

    /** Coarse copy of a detailed chunk: centre of each 4×4 cell (columns 2, 6, 10, 14 on each axis). */
    static int[] coarse(int[] full) {
        int[] out = new int[COARSE];
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) out[j * 4 + i] = full[(4 * j + 2) * 16 + 4 * i + 2];
        }
        return out;
    }

    /** World column (x, z) from its chunk columns (detailed or coarse). */
    static int at(int[] chunk, int x, int z) {
        return chunk.length == FULL ? chunk[(z & 15) * 16 + (x & 15)] : chunk[((z & 15) >> 2) * 4 + ((x & 15) >> 2)];
    }

    /**
     * Columns of one world: chunks in 32×32 regions (only array header per chunk, rather than a map entry). Chunk array
     * is immutable and replaced as a whole: LSS thread writes ({@link #put}), background thread coarsens
     * ({@link #coarsen}), all threads read. Detailed chunks stay within the limit ({@link #coarsen});
     * far from the player, coarse at 64 bytes each.
     */
    static final class Store {
        private static final int FORMAT = 1;
        private static final int MAGIC = 0x494C5353; // ILSS
        private final Map<Long, AtomicReferenceArray<int[]>> regions = new ConcurrentHashMap<>();
        private final AtomicInteger chunks = new AtomicInteger(), full = new AtomicInteger();

        private static long region(int chunkX, int chunkZ) {
            return ChunkPos.asLong(chunkX >> 5, chunkZ >> 5);
        }

        private static int slot(int chunkX, int chunkZ) {
            return (chunkZ & 31) << 5 | chunkX & 31;
        }

        /** Chunk columns received from the server replace the previous ones. */
        void put(int chunkX, int chunkZ, int[] columns) {
            int[] old = regions.computeIfAbsent(region(chunkX, chunkZ), r -> new AtomicReferenceArray<>(1024)).getAndSet(slot(chunkX, chunkZ), columns);
            count(old, columns);
        }

        /** Only if the chunk is absent (disk read does not overwrite data received from the server). */
        boolean putIfAbsent(int chunkX, int chunkZ, int[] columns) {
            boolean set = regions.computeIfAbsent(region(chunkX, chunkZ), r -> new AtomicReferenceArray<>(1024)).compareAndSet(slot(chunkX, chunkZ), null, columns);
            if (set) count(null, columns);
            return set;
        }

        private void count(@Nullable int[] old, int[] now) {
            if (old == null) chunks.incrementAndGet();
            full.addAndGet((now.length == FULL ? 1 : 0) - (old != null && old.length == FULL ? 1 : 0));
        }

        @Nullable
        int[] chunk(int chunkX, int chunkZ) {
            AtomicReferenceArray<int[]> r = regions.get(region(chunkX, chunkZ));
            return r == null ? null : r.get(slot(chunkX, chunkZ));
        }

        /** Column (x, z) packed ({@link LssColumns#pack}); 0 if unknown. */
        int get(int x, int z) {
            int[] c = chunk(x >> 4, z >> 4);
            return c == null ? 0 : at(c, x, z);
        }

        int chunks() {
            return chunks.get();
        }

        int fullChunks() {
            return full.get();
        }

        /**
         * More than {@code limit} detailed chunks: coarsen farthest from block (x, z) until {@code keep} remain detailed.
         * Leave chunks received again in the meantime untouched. Returns number coarsened.
         */
        int coarsen(double x, double z, int limit, int keep) {
            if (full.get() <= limit) return 0;
            List<long[]> found = new ArrayList<>();
            for (Map.Entry<Long, AtomicReferenceArray<int[]>> e : regions.entrySet()) {
                int rx = ChunkPos.getX(e.getKey()), rz = ChunkPos.getZ(e.getKey());
                AtomicReferenceArray<int[]> r = e.getValue();
                for (int i = 0; i < 1024; i++) {
                    int[] c = r.get(i);
                    if (c == null || c.length != FULL) continue;
                    int cx = rx << 5 | i & 31, cz = rz << 5 | i >> 5;
                    double dx = cx * 16 + 8 - x, dz = cz * 16 + 8 - z;
                    found.add(new long[] {ChunkPos.asLong(cx, cz), (long) (dx * dx + dz * dz)});
                }
            }
            found.sort(Comparator.comparingLong((long[] a) -> a[1]).reversed());
            int done = 0;
            for (long[] f : found) {
                if (full.get() <= keep) break;
                int cx = ChunkPos.getX(f[0]), cz = ChunkPos.getZ(f[0]);
                AtomicReferenceArray<int[]> r = regions.get(region(cx, cz));
                int[] c = r.get(slot(cx, cz));
                if (c == null || c.length != FULL) continue;
                int[] small = LssColumns.coarse(c);
                if (r.compareAndSet(slot(cx, cz), c, small)) {
                    count(c, small);
                    done++;
                }
            }
            return done;
        }

        /**
         * More than {@code limit} chunks: remove farthest from block (x, z) until {@code keep} remain.
         * Returns number removed.
         */
        int trim(double x, double z, int limit, int keep) {
            if (chunks.get() <= limit) return 0;
            List<long[]> found = new ArrayList<>();
            for (Map.Entry<Long, AtomicReferenceArray<int[]>> e : regions.entrySet()) {
                int rx = ChunkPos.getX(e.getKey()), rz = ChunkPos.getZ(e.getKey());
                AtomicReferenceArray<int[]> r = e.getValue();
                for (int i = 0; i < 1024; i++) {
                    if (r.get(i) == null) continue;
                    int cx = rx << 5 | i & 31, cz = rz << 5 | i >> 5;
                    double dx = cx * 16 + 8 - x, dz = cz * 16 + 8 - z;
                    found.add(new long[] {ChunkPos.asLong(cx, cz), (long) (dx * dx + dz * dz)});
                }
            }
            found.sort(Comparator.comparingLong((long[] a) -> a[1]).reversed());
            int done = 0;
            for (long[] f : found) {
                if (chunks.get() <= keep) break;
                int cx = ChunkPos.getX(f[0]), cz = ChunkPos.getZ(f[0]);
                AtomicReferenceArray<int[]> r = regions.get(region(cx, cz));
                int[] c = r.get(slot(cx, cz));
                if (c != null && r.compareAndSet(slot(cx, cz), c, null)) {
                    chunks.decrementAndGet();
                    if (c.length == FULL) full.decrementAndGet();
                    done++;
                }
            }
            return done;
        }

        /** Everything to disk: chunks one at a time, terminated by {@code false}. */
        void write(DataOutputStream out) throws IOException {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT);
            for (Map.Entry<Long, AtomicReferenceArray<int[]>> e : regions.entrySet()) {
                int rx = ChunkPos.getX(e.getKey()), rz = ChunkPos.getZ(e.getKey());
                AtomicReferenceArray<int[]> r = e.getValue();
                for (int i = 0; i < 1024; i++) {
                    int[] c = r.get(i);
                    if (c == null) continue;
                    out.writeBoolean(true);
                    out.writeInt(rx << 5 | i & 31);
                    out.writeInt(rz << 5 | i >> 5);
                    out.writeBoolean(c.length == FULL);
                    for (int v : c) out.writeInt(v);
                }
            }
            out.writeBoolean(false);
        }

        /** Read what {@link #write} wrote: only absent chunks; number read. */
        int read(DataInputStream in) throws IOException {
            if (in.readInt() != MAGIC) throw new IOException("not an Incoming LSS terrain file");
            int format = in.readInt();
            if (format != FORMAT) throw new IOException("format " + format + ", expected " + FORMAT);
            int n = 0;
            while (in.readBoolean()) {
                int cx = in.readInt(), cz = in.readInt();
                int[] c = new int[in.readBoolean() ? FULL : COARSE];
                for (int i = 0; i < c.length; i++) c[i] = in.readInt();
                putIfAbsent(cx, cz, c);
                n++;
            }
            return n;
        }
    }
}
