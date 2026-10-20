package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Огонь, который клиент видит: блоки огня в своих чанках вокруг камеры, по чанку за чанком, сколько успеется за
 * {@link #BUDGET_NANOS} тика (весь круг прорисовки — за секунды; пожары меняются минутами). Секция без огня в палитре не
 * смотрится ({@code maybeHas}); с огнём — выборкой: каждый {@link #SAMPLE}-й блок, счёт ×{@link #SAMPLE}. Полный счёт
 * ({@code PalettedContainer.count}: все 4096 мест в новую карту) стоил бы миллисекунд за тик: в подробном городе палитра
 * секции глобальная ({@code maybeHas} всегда да), а малая помнит сгоревший огонь. По этому огню звук горящего города ядерки
 * ставит огонь, который рядом, на его место, а дальнюю модель ({@code FireField}) — в меру того, что горит на деле
 * (настройки пожаров сервера клиент не знает: пожары могут быть выключены или погашены); дым над пожарами
 * ({@link FireSmoke}) берёт окно выборок ({@link #forEachGroup}): у каждого чанка — последние {@link #SAMPLE} обходов,
 * по одному на каждый сдвиг выборки, вместе — все места чанка: счёт и середина огня точные, а не ×{@link #SAMPLE} одной
 * выборки (маленький очаг иначе мигал бы между 0 и 8 блоками).
 */
public final class FireWatch {
    /** Время на огонь за тик, нс (хотя бы один чанк). */
    static final long BUDGET_NANOS = 100_000;
    /** Чанков за тик не больше. */
    static final int MAX_PER_TICK = 8;
    /** Выборка в секции: каждый столько-й блок (по x — 2 места из 16, все y и z). */
    static final int SAMPLE = 8;
    /** Чанк → (число огня << 16) | высота огня над дном мира. */
    private static final Long2LongOpenHashMap SEEN = new Long2LongOpenHashMap();
    /**
     * Чанк → окно выборок: по сдвигу выборки — огня, сумма x, y, z мест (от угла чанка и дна мира), в конце — маска
     * сдвигов, что уже смотрелись.
     */
    private static final Long2ObjectOpenHashMap<int[]> WINDOW = new Long2ObjectOpenHashMap<>();
    /** Последняя выборка {@link #scan}: огня (без ×{@link #SAMPLE}), суммы мест. */
    private static int sampleN, sampleX, sampleY, sampleZ;
    @Nullable
    private static ClientLevel level;
    private static int cursor, total, phase;
    /** Цена с прошлой строки сценария: время за тики с огнём, сколько тиков, самый долгий тик (нс), чанков и секций. */
    private static long spent, ticks, slowest, chunks, scanned;

    private FireWatch() {}

    /** Ещё часть чанков в радиусе {@code radius} чанков от чанка камеры. */
    public static void tick(ClientLevel lvl, int cx, int cz, int radius) {
        long t0 = System.nanoTime();
        if (lvl != level) reset();
        level = lvl;
        int side = 2 * radius + 1, all = side * side;
        for (int k = 0; k < Math.min(MAX_PER_TICK, all); k++) {
            if (k > 0 && System.nanoTime() - t0 > BUDGET_NANOS) break;
            int i = cursor++ % all;
            if (cursor >= all) {
                cursor = 0;
                phase = (phase + 1) % SAMPLE;
                prune(lvl);
            }
            int x = cx - radius + i % side, z = cz - radius + i / side;
            LevelChunk chunk = lvl.getChunkSource().getChunk(x, z, false);
            long key = ChunkPos.asLong(x, z);
            long was = SEEN.remove(key);
            total -= (int) (was >>> 16);
            if (chunk == null) {
                WINDOW.remove(key);
                continue;
            }
            chunks++;
            long now = scan(chunk, phase);
            window(key, phase);
            if (now != 0) {
                SEEN.put(key, now);
                total += (int) (now >>> 16);
            }
        }
        long dt = System.nanoTime() - t0;
        spent += dt;
        ticks++;
        slowest = Math.max(slowest, dt);
    }

    /** Огонь чанка выборкой: (число << 16) | средняя высота; {@code offset} — сдвиг выборки (свой в каждом обходе). */
    private static long scan(LevelChunk chunk, int offset) {
        LevelChunkSection[] sections = chunk.getSections();
        sampleN = sampleX = sampleY = sampleZ = 0;
        long count = 0, ySum = 0;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !s.maybeHas(FireWatch::fire)) continue;
            scanned++;
            PalettedContainer<BlockState> states = s.getStates();
            int n = 0;
            // индекс секции — (y << 8) | (z << 4) | x
            for (int j = offset; j < 4096; j += SAMPLE) {
                if (fire(states.get(j & 15, j >> 8, (j >> 4) & 15))) {
                    n++;
                    sampleX += j & 15;
                    sampleY += i * 16 + (j >> 8);
                    sampleZ += (j >> 4) & 15;
                }
            }
            sampleN += n;
            n *= SAMPLE;
            count += n;
            ySum += (long) n * (i * 16 + 8);
        }
        if (count == 0) return 0;
        return Math.min(count, 0xFFFFFFL) << 16 | (ySum / count) & 0xFFFF;
    }

    /** Выборка сдвига phase — в окно чанка; окно без огня уходит. */
    private static void window(long key, int phase) {
        int[] w = WINDOW.get(key);
        if (w == null) {
            if (sampleN == 0) return;
            w = new int[4 * SAMPLE + 1];
            WINDOW.put(key, w);
        }
        w[4 * phase] = sampleN;
        w[4 * phase + 1] = sampleX;
        w[4 * phase + 2] = sampleY;
        w[4 * phase + 3] = sampleZ;
        w[4 * SAMPLE] |= 1 << phase;
        for (int p = 0; p < SAMPLE; p++) if (w[4 * p] != 0) return;
        WINDOW.remove(key);
    }

    /** Очаг огня: середина горящих мест в мире, блоков огня (по окну; пока не все сдвиги смотрелись — в их долю). */
    public interface Group {
        void fire(long chunk, double x, double y, double z, double blocks);
    }

    /** Огонь по чанкам — окно выборок ({@link #WINDOW}). */
    public static void forEachGroup(Group v) {
        if (level == null) return;
        int minY = level.getMinBuildHeight();
        for (Long2ObjectMap.Entry<int[]> e : WINDOW.long2ObjectEntrySet()) {
            int[] w = e.getValue();
            long n = 0, x = 0, y = 0, z = 0;
            for (int p = 0; p < SAMPLE; p++) {
                n += w[4 * p];
                x += w[4 * p + 1];
                y += w[4 * p + 2];
                z += w[4 * p + 3];
            }
            if (n == 0) continue;
            int seen = Integer.bitCount(w[4 * SAMPLE]);
            long key = e.getLongKey();
            v.fire(key, ChunkPos.getX(key) * 16 + x / (double) n + 0.5, minY + y / (double) n + 0.5, ChunkPos.getZ(key) * 16 + z / (double) n + 0.5,
                    n * (double) SAMPLE / Math.max(1, seen));
        }
    }

    private static boolean fire(BlockState state) {
        return state.getBlock() instanceof BaseFireBlock;
    }

    /** Чанки, которых у клиента больше нет. */
    private static void prune(ClientLevel lvl) {
        for (var it = SEEN.long2LongEntrySet().iterator(); it.hasNext(); ) {
            Long2LongMap.Entry e = it.next();
            if (lvl.getChunkSource().getChunk(ChunkPos.getX(e.getLongKey()), ChunkPos.getZ(e.getLongKey()), false) == null) {
                total -= (int) (e.getLongValue() >>> 16);
                it.remove();
            }
        }
        WINDOW.keySet().removeIf(k -> lvl.getChunkSource().getChunk(ChunkPos.getX(k), ChunkPos.getZ(k), false) == null);
    }

    /** Огонь по чанкам. */
    public interface Visitor {
        void fire(double x, double y, double z, int count);
    }

    public static void forEach(Visitor v) {
        if (level == null) return;
        int minY = level.getMinBuildHeight();
        for (Long2LongMap.Entry e : SEEN.long2LongEntrySet()) {
            long key = e.getLongKey(), val = e.getLongValue();
            v.fire(ChunkPos.getX(key) * 16 + 8, minY + (val & 0xFFFF), ChunkPos.getZ(key) * 16 + 8, (int) (val >>> 16));
        }
    }

    public static int total() {
        return total;
    }

    public static void reset() {
        SEEN.clear();
        WINDOW.clear();
        cursor = total = 0;
        level = null;
    }

    /**
     * Для строки {@code SCENARIO}: цена за тик с прошлой строки (средняя и самая долгая, мс), чанков и секций с огнём
     * в палитре за тик, огня всего; счёт — заново.
     */
    public static String describe() {
        String s = ticks == 0 ? "watch -" : String.format(Locale.ROOT, "watch %.3f/%.3f ms/tick chunks %.1f sections %.1f fire %d",
                spent / 1e6 / ticks, slowest / 1e6, chunks / (double) ticks, scanned / (double) ticks, total);
        spent = ticks = slowest = chunks = scanned = 0;
        return s;
    }
}
