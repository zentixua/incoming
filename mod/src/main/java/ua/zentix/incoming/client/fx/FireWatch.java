package ua.zentix.incoming.client.fx;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.nuclear.ClientNuclear;

import java.util.List;
import java.util.Locale;

/**
 * Огонь горящего города ядерного подрыва, который клиент видит: блоки огня в своих чанках у подрывов (круг руин и
 * {@link #NUKE_MARGIN} блоков за ним — докуда {@code NukeAfter} берёт огонь в гул пожаров и в ветер в огонь), по чанку за
 * чанком, сколько успеется за {@link #BUDGET_NANOS} тика (весь круг прорисовки — за секунды; пожары меняются минутами).
 * Огонь вне подрывов мод не смотрит: огонь мира, и тот, что поджёг обычный удар, горит и дымит как любой огонь игры
 * (ванилью или модом, который ведёт горение). Подрыва нет — обхода нет.
 * <p>
 * Секция без огня в палитре не смотрится ({@code maybeHas}); с огнём — выборкой: каждый {@link #SAMPLE}-й блок, счёт
 * ×{@link #SAMPLE}. Полный счёт ({@code PalettedContainer.count}: все 4096 мест в новую карту) стоил бы миллисекунд за
 * тик: в подробном городе палитра секции глобальная ({@code maybeHas} всегда да), а малая помнит сгоревший огонь. По
 * этому огню звук горящего города ставит огонь, который рядом, на его место, а дальнюю модель ({@code FireField}) — в
 * меру того, что горит на деле (настройки пожаров сервера клиент не знает: пожары могут быть выключены или погашены).
 * <p>
 * Огонь на клетках горящего топлива воронок — не огонь дерева ({@link FuelCells}): его тепло и сажу несёт очаг
 * воронки, и в счёт он не идёт.
 */
public final class FireWatch {
    /** Время на огонь за тик, нс (хотя бы один чанк). */
    static final long BUDGET_NANOS = 100_000;
    /** Чанков за тик не больше. */
    static final int MAX_PER_TICK = 8;
    /** Выборка в секции: каждый столько-й блок (по x — 2 места из 16, все y и z). */
    static final int SAMPLE = 8;
    /** За кругом руин подрыва — столько блоков: так далеко {@code NukeAfter} берёт огонь подрыва в счёт. */
    public static final double NUKE_MARGIN = 64;
    /** Чанк → (число огня << 16) | высота огня над дном мира. */
    private static final Long2LongOpenHashMap SEEN = new Long2LongOpenHashMap();
    @Nullable
    private static ClientLevel level;
    private static int cursor, total, phase;
    /** Цена с прошлой строки сценария: время за тики с огнём, сколько тиков, самый долгий тик (нс), чанков и секций. */
    private static long spent, ticks, slowest, chunks, scanned;

    private FireWatch() {}

    /** Ещё часть чанков у ядерных подрывов в радиусе {@code radius} чанков от чанка камеры. */
    public static void tick(ClientLevel lvl, int cx, int cz, int radius) {
        long t0 = System.nanoTime();
        if (lvl != level) reset();
        level = lvl;
        List<ClientNuclear.Active> nukes = ClientNuclear.detonations();
        if (nukes.isEmpty()) {
            if (!SEEN.isEmpty()) {
                SEEN.clear();
                total = 0;
            }
            return;
        }
        int side = 2 * radius + 1, all = side * side;
        // чанки вне подрывов — мимо, без счёта (одна проверка расстояния), но не больше круга за тик
        for (int k = 0, step = 0; k < MAX_PER_TICK && step < all; step++) {
            if (k > 0 && System.nanoTime() - t0 > BUDGET_NANOS) break;
            int i = cursor++ % all;
            if (cursor >= all) {
                cursor = 0;
                phase = (phase + 1) % SAMPLE;
                prune(lvl, cx, cz, radius);
            }
            int x = cx - radius + i % side, z = cz - radius + i / side;
            long key = ChunkPos.asLong(x, z);
            long was = SEEN.remove(key);
            total -= (int) (was >>> 16);
            if (!nuclear(nukes, x, z)) continue;
            k++;
            LevelChunk chunk = lvl.getChunkSource().getChunk(x, z, false);
            if (chunk == null) continue;
            chunks++;
            long now = scan(chunk, phase);
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

    /** Чанк (x, z) задевает круг подрыва: середина не дальше радиуса и полудиагонали чанка. */
    static boolean nuclear(List<ClientNuclear.Active> nukes, int x, int z) {
        double mx = x * 16 + 8, mz = z * 16 + 8;
        for (ClientNuclear.Active a : nukes) {
            double r = a.d.ruinRadius() + NUKE_MARGIN + 12, dx = mx - a.d.burst().x, dz = mz - a.d.burst().z;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    /**
     * Огонь чанка выборкой: (число << 16) | средняя высота; {@code offset} — сдвиг выборки (свой в каждом обходе). Огонь на
     * клетках горящего топлива воронок — не в счёте: его дым — очаг воронки ({@link FuelCells}).
     */
    private static long scan(LevelChunk chunk, int offset) {
        LevelChunkSection[] sections = chunk.getSections();
        long count = 0, ySum = 0;
        boolean fuel = FireSmoke.FUEL.inChunk(chunk.getPos().toLong());
        int bx = chunk.getPos().getMinBlockX(), bz = chunk.getPos().getMinBlockZ(), minY = chunk.getMinBuildHeight();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !s.maybeHas(FireWatch::fire)) continue;
            scanned++;
            PalettedContainer<BlockState> states = s.getStates();
            int n = 0;
            // индекс секции — (y << 8) | (z << 4) | x
            for (int j = offset; j < 4096; j += SAMPLE) {
                if (fire(states.get(j & 15, j >> 8, (j >> 4) & 15))
                        && !(fuel && FireSmoke.FUEL.covers(bx + (j & 15), minY + i * 16 + (j >> 8), bz + ((j >> 4) & 15)))) n++;
            }
            n *= SAMPLE;
            count += n;
            ySum += (long) n * (i * 16 + 8);
        }
        if (count == 0) return 0;
        return Math.min(count, 0xFFFFFFL) << 16 | (ySum / count) & 0xFFFF;
    }

    private static boolean fire(BlockState state) {
        return state.getBlock() instanceof BaseFireBlock;
    }

    /** Чанки, которых у клиента больше нет, и чанки за кругом обхода: их огонь не пересматривается и не гас бы. */
    private static void prune(ClientLevel lvl, int cx, int cz, int radius) {
        for (var it = SEEN.long2LongEntrySet().iterator(); it.hasNext(); ) {
            Long2LongMap.Entry e = it.next();
            int x = ChunkPos.getX(e.getLongKey()), z = ChunkPos.getZ(e.getLongKey());
            if (Math.abs(x - cx) > radius || Math.abs(z - cz) > radius || lvl.getChunkSource().getChunk(x, z, false) == null) {
                total -= (int) (e.getLongValue() >>> 16);
                it.remove();
            }
        }
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
