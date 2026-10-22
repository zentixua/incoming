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
 * Верх колонок из чанков, которые прислал сервер LOD Server Support (см. {@link LssTerrain}): то же, что слой чанков
 * клиента ({@link LoadedChunksTerrain}) берёт из своего мира, — первый блок карты высот {@code MOTION_BLOCKING} (с кронами
 * и постройками), ниже — первый блок с цветом ванильной карты, глубина воды под ним. Без API LSS: считается и хранится
 * здесь, поэтому проверяется юнит-тестами.
 * <p>
 * Колонка упакована в {@code int}: высота + 2048 (12 бит), номер {@link MapColor} (6 бит), глубина воды 0–16 (5 бит),
 * бит «есть»; 0 — о колонке ничего не известно.
 */
final class LssColumns {
    /** Глубже этого вода на карте одинаково тёмная (как у {@link LoadedChunksTerrain}). */
    static final int MAX_WATER_DEPTH = 16;
    /** Сколько блоков ниже верха карты высот искать блок с цветом под прозрачными (стекло, свет). */
    static final int MAX_COLORLESS = 8;
    /** Колонок чанка подробно: каждая. */
    static final int FULL = 256;
    /** Колонок чанка грубо: середина каждой клетки 4×4 блока — те же колонки, что у плитки уровня 2. */
    static final int COARSE = 16;
    private static final int PRESENT = 1 << 23;
    private static final int HEIGHT_BIAS = 2048;
    /** Блоки карты высот {@code MOTION_BLOCKING}: преграждают движение или с жидкостью. */
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

    /** Секция чанка: блоки 16³ по локальным координатам. */
    interface Section {
        /** Только воздух: блоки можно не смотреть. */
        boolean empty();

        BlockState block(int x, int y, int z);
    }

    /**
     * Верх всех 256 колонок чанка (индекс {@code z·16 + x}). {@code sections[i]} — секция {@code minSection + i}
     * (номер секции = y >> 4); null — секции нет, в ней только воздух (LSS шлёт лишь непустые).
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

    /** Колонка (x, z) сверху вниз от {@code top} (индекс блока от низа первой секции). */
    private static int column(@Nullable Section[] sections, int top, int minY, int x, int z) {
        int y = top;
        // верх карты высот MOTION_BLOCKING: пустые секции проходятся целиком
        for (; y >= 0; y--) {
            Section s = sections[y >> 4];
            if (s == null || s.empty()) {
                y &= ~15;
                continue;
            }
            if (MOTION_BLOCKING.test(s.block(x, y & 15, z))) break;
        }
        if (y < 0) return 0;
        // ниже — первый блок с цветом карты, как у слоя чанков клиента
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

    /** Грубая копия подробного чанка: середина каждой клетки 4×4 (колонки 2, 6, 10, 14 по каждой оси). */
    static int[] coarse(int[] full) {
        int[] out = new int[COARSE];
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) out[j * 4 + i] = full[(4 * j + 2) * 16 + 4 * i + 2];
        }
        return out;
    }

    /** Колонка (x, z) мира из колонок её чанка (подробных или грубых). */
    static int at(int[] chunk, int x, int z) {
        return chunk.length == FULL ? chunk[(z & 15) * 16 + (x & 15)] : chunk[((z & 15) >> 2) * 4 + ((x & 15) >> 2)];
    }

    /**
     * Колонки одного мира: чанки по областям 32×32 (у чанка только заголовок массива, а не запись в карте). Массив
     * чанка не меняется, его заменяют целиком: пишет поток LSS ({@link #put}), понижает до грубого фоновый поток
     * ({@link #coarsen}), читают все. Подробных чанков не больше предела ({@link #coarsen}): дальние от игрока —
     * грубыми, по 64 байта.
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

        /** Колонки чанка пришли с сервера: заменяют прежние. */
        void put(int chunkX, int chunkZ, int[] columns) {
            int[] old = regions.computeIfAbsent(region(chunkX, chunkZ), r -> new AtomicReferenceArray<>(1024)).getAndSet(slot(chunkX, chunkZ), columns);
            count(old, columns);
        }

        /** Только если чанка ещё нет (чтение с диска не затирает пришедшее с сервера). */
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

        /** Колонка (x, z) упакованной ({@link LssColumns#pack}); 0 — не знаем. */
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
         * Подробных чанков больше {@code limit} — самые дальние от (x, z) блоков становятся грубыми, пока подробных не
         * останется {@code keep}. Чанк, который за это время пришёл снова, не трогается. Возвращает, сколько понижено.
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
         * Чанков больше {@code limit} — самые дальние от (x, z) блоков уходят, пока не останется {@code keep}. Возвращает,
         * сколько ушло.
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

        /** Всё на диск: чанки по одному, конец — {@code false}. */
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

        /** Чтение того, что записал {@link #write}: чанки, которых ещё нет; сколько прочитано. */
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
