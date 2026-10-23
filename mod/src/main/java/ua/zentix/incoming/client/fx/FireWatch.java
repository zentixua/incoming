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
 * <p>
 * У свежего удара ({@link #hot}) огонь загорается в тот же тик (шар топлива, горящее топливо в воронке, обломки), а
 * растёт и гаснет за секунды: обход всех чанков прорисовки (12 чанков — 625 чанков, ~4 с) с выборкой 1/{@link #SAMPLE}
 * видел бы его на проход позже, а окно из {@link #SAMPLE} проходов держало бы счёт полминуты назад — дом, что
 * разгорается от 5 до 30 блоков, дымил бы силой первых. Чанки у удара поэтому смотрятся целиком (все сдвиги выборки
 * за раз, {@link #hotScan}) вне очереди, от середины наружу, пока огонь там может расти
 * ({@link #HOT_SECONDS}).
 */
public final class FireWatch {
    /** Время на огонь за тик, нс (хотя бы один чанк). */
    static final long BUDGET_NANOS = 100_000;
    /** Чанков за тик не больше. */
    static final int MAX_PER_TICK = 8;
    /** Выборка в секции: каждый столько-й блок (по x — 2 места из 16, все y и z). */
    static final int SAMPLE = 8;
    /**
     * Чанки у удара смотрятся целиком: столько чанков от чанка удара (шар топлива ракеты — 17 м в радиусе, огонь
     * от горящего топлива и обломков — рядом с ним; 2 чанка — 32–48 м).
     */
    static final int HOT_CHUNKS = 2;
    /** Столько секунд после удара (пожар у воронки разгорается и гаснет минутами: лужа шахеда — 2,5 мин). */
    static final int HOT_SECONDS = 180;
    /** Чанки у ударов по порядку осмотра (от середины наружу) и тик, до которого каждый смотрится. */
    private static final it.unimi.dsi.fastutil.longs.LongArrayList HOT = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private static final Long2LongOpenHashMap HOT_UNTIL = new Long2LongOpenHashMap();
    private static int hotCursor;
    private static long clock;
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
        clock++;
        hotScan(lvl, t0);
        int side = 2 * radius + 1, all = side * side;
        for (int k = 0; k < Math.min(MAX_PER_TICK, all); k++) {
            if (k > 0 && System.nanoTime() - t0 > BUDGET_NANOS) break;
            int i = cursor++ % all;
            if (cursor >= all) {
                cursor = 0;
                phase = (phase + 1) % SAMPLE;
                prune(lvl);
                // окно — только в круге обхода: огонь за ним не пересматривается и не гас бы
                WINDOW.keySet().removeIf(c -> Math.abs(ChunkPos.getX(c) - cx) > radius || Math.abs(ChunkPos.getZ(c) - cz) > radius);
            }
            int x = cx - radius + i % side, z = cz - radius + i / side;
            long key = ChunkPos.asLong(x, z);
            // чанк у удара смотрится целиком своей очередью ({@link #hotScan}): выборка одного сдвига вернула бы в окно прошлые
            if (HOT_UNTIL.containsKey(key)) continue;
            LevelChunk chunk = lvl.getChunkSource().getChunk(x, z, false);
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

    /**
     * Удар в (x, z): чанки вокруг ({@link #HOT_CHUNKS}) смотрятся целиком вне очереди {@link #HOT_SECONDS} с, от
     * середины наружу; повторный удар рядом продлевает срок.
     */
    public static void hot(ClientLevel lvl, double x, double z) {
        if (lvl != level) {
            reset();
            level = lvl;
        }
        int cx = (int) Math.floor(x) >> 4, cz = (int) Math.floor(z) >> 4;
        long until = clock + 20L * HOT_SECONDS;
        // новые — сразу у курсора, по кольцам: середина нового удара не ждёт круга по чанкам прежних
        int at = Math.min(hotCursor, HOT.size());
        for (int ring = 0; ring <= HOT_CHUNKS; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    long key = ChunkPos.asLong(cx + dx, cz + dz);
                    if (HOT_UNTIL.put(key, until) == 0) HOT.add(at++, key);
                }
            }
        }
    }

    /**
     * Чанк у удара, что смотрится сейчас (null — никакой), следующая его секция и огонь, найденный в прошлых секциях:
     * по 4 числа на сдвиг выборки и маска, как в окне.
     */
    private static long hotKey;
    @Nullable
    private static LevelChunk hotChunk;
    private static int hotSection;
    private static int[] hotWindow = new int[4 * SAMPLE + 1];
    private static long hotCount, hotY;

    /**
     * Чанки у удара — целиком, секция за секцией ({@link #phases}), пока у тика есть половина {@link #BUDGET_NANOS}
     * (хотя бы одна секция): в подробном городе палитра секции глобальная, все 4096 мест каждой секции — десятки
     * микросекунд, и чанк целиком за один тик съел бы бюджет всего обхода. Чанк досмотрен — его окно и счёт — нынешний
     * огонь; срок вышел — чанк снова в общей очереди.
     */
    private static void hotScan(ClientLevel lvl, long t0) {
        boolean first = true;
        while (first || System.nanoTime() - t0 < BUDGET_NANOS / 2) {
            first = false;
            if (hotChunk == null && !nextHot(lvl)) return;
            LevelChunk chunk = hotChunk;
            LevelChunkSection[] sections = chunk.getSections();
            while (hotSection < sections.length) {
                int i = hotSection++;
                LevelChunkSection s = sections[i];
                if (s.hasOnlyAir() || !s.maybeHas(FireWatch::fire)) continue;
                scanned++;
                PalettedContainer<BlockState> states = s.getStates();
                int n = phases(j -> fire(states.get(j & 15, j >> 8, (j >> 4) & 15)), i * 16, hotWindow);
                hotCount += n;
                hotY += (long) n * (i * 16 + 8);
                break;
            }
            if (hotSection < sections.length) continue;
            // чанк досмотрен: окно — весь его огонь сейчас
            long key = hotKey, was = SEEN.remove(key);
            total -= (int) (was >>> 16);
            if (hotCount == 0) {
                WINDOW.remove(key);
            } else {
                hotWindow[4 * SAMPLE] = (1 << SAMPLE) - 1;
                WINDOW.put(key, hotWindow);
                SEEN.put(key, Math.min(hotCount, 0xFFFFFFL) << 16 | (hotY / hotCount) & 0xFFFF);
                total += (int) Math.min(hotCount, 0xFFFFFFL);
                hotWindow = new int[4 * SAMPLE + 1];
            }
            hotChunk = null;
        }
    }

    /** Следующий чанк у удара, что есть у клиента: в {@link #hotChunk}; сроки вышедшие — из очереди. Нашёлся ли. */
    private static boolean nextHot(ClientLevel lvl) {
        for (int tries = HOT.size(); tries > 0 && !HOT.isEmpty(); tries--) {
            if (hotCursor >= HOT.size()) hotCursor = 0;
            long key = HOT.getLong(hotCursor);
            if (HOT_UNTIL.get(key) < clock) {
                HOT.removeLong(hotCursor);
                HOT_UNTIL.remove(key);
                continue;
            }
            hotCursor++;
            LevelChunk chunk = lvl.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            if (chunk == null) {
                long was = SEEN.remove(key);
                total -= (int) (was >>> 16);
                WINDOW.remove(key);
                continue;
            }
            chunks++;
            hotKey = key;
            hotChunk = chunk;
            hotSection = 0;
            hotCount = hotY = 0;
            java.util.Arrays.fill(hotWindow, 0);
            return true;
        }
        return false;
    }

    /**
     * Огонь одной секции во все сдвиги выборки: {@code fire} — огонь ли на месте j = (y << 8) | (z << 4) | x, {@code base}
     * — высота секции над дном мира; в {@code w} (по 4 числа на сдвиг j mod {@link #SAMPLE}: огня, сумма x, y, z мест)
     * прибавляется её огонь. Сколько огня в секции.
     */
    static int phases(java.util.function.IntPredicate fire, int base, int[] w) {
        int n = 0;
        for (int j = 0; j < 4096; j++) {
            if (!fire.test(j)) continue;
            int p = 4 * (j % SAMPLE);
            w[p]++;
            w[p + 1] += j & 15;
            w[p + 2] += base + (j >> 8);
            w[p + 3] += (j >> 4) & 15;
            n++;
        }
        return n;
    }

    /** Блоков огня по окну чанка ({@link #WINDOW}): выборки сдвигов, что смотрелись, на их долю. */
    static double blocks(int[] w) {
        long n = 0;
        for (int p = 0; p < SAMPLE; p++) n += w[4 * p];
        return n * (double) SAMPLE / Math.max(1, Integer.bitCount(w[4 * SAMPLE]));
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
            long key = e.getLongKey();
            v.fire(key, ChunkPos.getX(key) * 16 + x / (double) n + 0.5, minY + y / (double) n + 0.5, ChunkPos.getZ(key) * 16 + z / (double) n + 0.5,
                    blocks(w));
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
        HOT.clear();
        HOT_UNTIL.clear();
        hotChunk = null;
        cursor = total = hotCursor = 0;
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
