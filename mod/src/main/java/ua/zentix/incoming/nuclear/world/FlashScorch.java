package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.compat.SableTerrain;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.model.ThermalModel;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.util.Palettes;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

/**
 * Свет по земле в миг вспышки (план «ядерка уровня кино», шаг 3): всё, что световой импульс делает с миром, встаёт за
 * время самого импульса, а не с волной, — выгоревший дёрн и гарь, сгоревшие растения, растаявший снег, опалённые
 * биомы, гарь на гранях и пожары ({@link RuinPlanner#flash}: слой вспышки — часть плана руин без напора). Волна потом
 * ставит руины как прежде (по миру до вспышки) и меняет сверх слоя только то, что сделала она: содранный дёрн, завал,
 * проломы, сбитая часть пожаров ({@link RuinPlan#apply}).
 * <p>
 * Одна работа на подрыв (поток сервера, полоса ядерки общего бюджета, после руин, до которых
 * дошла волна, — из того, что они оставили: руины у фронта важнее слоя вдали, {@link NuclearWorld#tick}): в первой единице —
 * чанки в памяти, до которых дойдут руины ({@link ScarQueue#inRange}) и свет оставляет след ({@link #reach}), сперва те,
 * что видят игроки (ближние к игроку первыми), потом остальные от эпицентра; дальше по единице на чанк — снимки (из кэша
 * руин подрыва: те же снимки берут руины) и план слоя в фоне ({@link RuinWorkers}: видимые — ступенью видимых), и по
 * единице на готовый слой — запись в чанк, как руины ({@link RuinPlan#applyFlash}: прямо в секции, игрокам — пакетами
 * секций в том же тике, LOD Distant Horizons). Чанк встаёт не раньше, чем свет у его ближней к шару точки набрал порог
 * поджига ({@link Detonation#flashTicks}): у эпицентра — сразу, у края — к концу импульса (у 15 кт — 1,24 с, у 1 Мт —
 * 10 с). Огонь слоя — без тиков до руин чанка: он не растёт и не гаснет, пока их нет ({@link RuinPlan#applyFlash}).
 * <p>
 * Слоя не получают: чанк, до которого волна дошла раньше слоя (у самого эпицентра; его руины — всё сразу, как прежде);
 * чанк у аппаратов Sable, чьи соседи не в памяти (Sable на каждое место читает соседей — запись загрузила бы их; руины
 * дождутся соседей сами); новые чанки, пока снимки и слои подрыва держат больше {@link NuclearPrep#PLANS_CAP} или куче
 * тесно ({@link HeapWatch}). Чанки, загруженные после подрыва, получают всё с руинами. Сама не сохраняется: импульс
 * длится секунды; что слой зажёг огонь, помнит сам чанк ({@link #mark}).
 */
public final class FlashScorch {
    final Detonation d;
    /** Чанки, которые задала проверка (null — чанки в памяти в радиусе следа). */
    @Nullable
    private final long[] only;
    @Nullable
    private final ColumnScar.Budget fixedBudget;
    /** Чанки по порядку (видимые игроку — первыми) и когда каждому вставать (игровое время); null — ещё не собраны. */
    @Nullable
    private long[] order;
    private long[] due;
    private boolean[] seen;
    private int next, seenCount;
    /** Отданные в фон слои: номер чанка в {@link #order} → задача. По порядку отдачи. */
    private final Map<Integer, Build> building = new LinkedHashMap<>();
    private int applied, stale, late, gone, waiting, skipped, failures, blocks, fires;
    private long nanos, lastApplied = -1;
    private final HeapWatch.Tracker heap = new HeapWatch.Tracker();

    /** Фоновый слой: задача и когда она пошла в работу (игровое время; −1 — ещё ждёт в очереди пула). */
    private static final class Build {
        final Future<RuinPlan> task;
        long since = -1;

        Build(Future<RuinPlan> task) {
            this.task = task;
        }
    }

    FlashScorch(Detonation d) {
        this(d, null, null);
    }

    private FlashScorch(Detonation d, @Nullable long[] only, @Nullable ColumnScar.Budget budget) {
        this.d = d;
        this.only = only;
        this.fixedBudget = budget;
    }

    /** Для проверок: слой вспышки только этих чанков (они в памяти целиком), со своим счётчиком пожаров. */
    public static FlashScorch forTest(Detonation d, long[] chunks, ColumnScar.Budget budget) {
        return new FlashScorch(d, chunks.clone(), budget);
    }

    public Detonation detonation() {
        return d;
    }

    /** Чанков, где слой встал (проверки). */
    public int appliedChunks() {
        return applied;
    }

    /** Чанков без слоя: у аппаратов Sable, чьи соседи не в памяти (проверки). */
    public int waitingChunks() {
        return waiting;
    }

    // ---------------------------------------------------------------- отметка на чанке

    /** Слой вспышки подрыва {@code id} зажёг огонь в чанке (поток сервера; сохраняется с чанком). */
    static void mark(LevelChunk chunk, int id) {
        chunk.setData(ModAttachments.FLASH_LAYER, id);
        chunk.setUnsaved(true);
    }

    /** В чанке стоит слой вспышки подрыва {@code id} с огнём, а его руин ещё нет. */
    public static boolean marked(LevelChunk chunk, int id) {
        return marks(chunk.getExistingData(ModAttachments.FLASH_LAYER).orElse(0), id);
    }

    /** Отметка {@code marker} (из чанка или его данных на диске) — слой подрыва {@code id}. */
    static boolean marks(int marker, int id) {
        return id > 0 && marker == id;
    }

    /** Руины подрыва {@code id} встали: отметка его слоя (и прежних) снимается. */
    static void unmark(LevelChunk chunk, int id) {
        int was = chunk.getExistingData(ModAttachments.FLASH_LAYER).orElse(0);
        if (was == 0 || was > id) return;
        chunk.removeData(ModAttachments.FLASH_LAYER);
        chunk.setUnsaved(true);
    }

    /**
     * Загружен чанк с отметкой слоя: руин не будет — подрыв забыт ({@link NuclearEvents}, ни среди идущих, ни среди
     * прошлых), — true: огню слоя пора дать тики ({@link #burnOut}, в тике мира: при загрузке тики ещё не ставятся).
     * Руины уже стоят — отметка снимается здесь же.
     */
    static boolean forgotten(LevelChunk chunk, NuclearEvents events) {
        int id = chunk.getExistingData(ModAttachments.FLASH_LAYER).orElse(0);
        if (id == 0) return false;
        if (chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= id) {
            unmark(chunk, id);
            return false;
        }
        return events.detonations().stream().noneMatch(e -> e.id() == id) && events.past().stream().noneMatch(e -> e.id() == id);
    }

    /**
     * Руин подрыва, чей слой стоит в чанке, не будет: огню чанка — тики, как поставленному ({@code FireBlock.onPlace}:
     * 30–39 тиков), отметка снимается (поток сервера, чанк в памяти целиком).
     */
    static void burnOut(ServerLevel level, LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        ChunkPos pos = chunk.getPos();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            if (s.hasOnlyAir() || !Palettes.contains(s.getStates(), st -> st.getBlock() instanceof BaseFireBlock)) continue;
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int k = 0; k < 4096; k++) {
                BlockState st = s.getBlockState(k & 15, k >> 8, (k >> 4) & 15);
                if (!(st.getBlock() instanceof BaseFireBlock)) continue;
                m.set(pos.getMinBlockX() + (k & 15), y0 + (k >> 8), pos.getMinBlockZ() + ((k >> 4) & 15));
                level.scheduleTick(m.immutable(), st.getBlock(), 30 + (int) (RuinPlanner.hash(m.getX(), m.getY(), m.getZ(), 31) * 10));
            }
        }
        chunk.removeData(ModAttachments.FLASH_LAYER);
        chunk.setUnsaved(true);
    }

    /**
     * Для проверок: чанк с отметкой слоя загружен заново ({@link #forgotten}) — подрыв забыт: огню — тики сразу
     * ({@link #burnOut}). false — подрыв ещё известен или отметки нет.
     */
    public static boolean burnOutForTest(ServerLevel level, LevelChunk chunk) {
        if (!forgotten(chunk, NuclearEvents.get(level))) return false;
        burnOut(level, chunk);
        return true;
    }

    // ---------------------------------------------------------------- работа

    /**
     * Докуда свет оставляет след, блоки по горизонтали от эпицентра: след на земле ({@link RuinPlanner#scorchReach}), пожары
     * (поджиг на склоне к взрыву) и гарь на гранях (свет на высоте шара); не дальше радиуса руин — дальше их нет и у волны.
     */
    static double reach(Detonation d) {
        double lo = 0, hi = d.ruinRadius();
        for (int i = 0; i < 40; i++) {
            double r = (lo + hi) / 2;
            Vec3 ground = new Vec3(d.burst().x + r, d.groundY() + 1, d.burst().z), high = new Vec3(d.burst().x + r, d.burst().y, d.burst().z);
            double q = d.fluence(ground) * 2.5;
            if (q >= ThermalModel.IGNITE || FlashChar.possible(d.fluence(high), FlashChar.CHARS) || FlashChar.possible(d.fluence(high), FlashChar.SOOTS)) lo = r;
            else hi = r;
        }
        return Math.max(lo, RuinPlanner.scorchReach(d));
    }

    /**
     * Сколько успеем за бюджет (поток сервера). Порядок — собрать чанки, записать готовые слои (их время пришло), отдать
     * в фон следующие. Ошибка одного чанка — в лог, остальные идут дальше.
     *
     * @return true — слой встал во всех чанках (или им он уже не нужен)
     */
    public boolean work(ServerLevel level, long now, WorkClock clock) {
        RuinContext ctx = NuclearWorld.get(level).ruins(level, d, now);
        if (order == null) {
            if (!clock.canStart()) return false;
            long t0 = clock.begin();
            list(level);
            nanos += clock.end(t0);
        }
        ScarQueue scars = NuclearWorld.get(level).scars();
        ColumnScar.Budget budget = fixedBudget != null ? fixedBudget : scars.budget(level, d);
        for (Iterator<Map.Entry<Integer, Build>> it = building.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Build> e = it.next();
            int k = e.getKey();
            Build b = e.getValue();
            if (!b.task.isDone()) {
                if (abandoned(b, k, now)) it.remove();
                continue;
            }
            if (due[k] > now) continue;
            if (!(seen[k] ? clock.canStartVisible(ScarQueue.SEEN_BUSY_NANOS) : clock.canStart())) return false;
            it.remove();
            long t0 = clock.begin();
            try {
                place(level, ctx, scars, k, b.task, budget, now);
            } catch (RuntimeException ex) {
                failed(k, ex);
            } finally {
                nanos += clock.end(t0);
            }
        }
        while (next < order.length) {
            int k = next;
            RuinWorkers.Priority priority = seen[k] ? RuinWorkers.Priority.SEEN : RuinWorkers.Priority.NEAR;
            if (!RuinWorkers.admit(priority) || !(seen[k] ? clock.canStartVisible(ScarQueue.SEEN_BUSY_NANOS) : clock.canStart())) return false;
            next++;
            long key = order[k];
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                gone++;
                continue;
            }
            if (ruined(ctx, chunk)) {
                late++;
                continue;
            }
            // у аппарата Sable запись читает соседей: без них — руины, когда соседи будут (их ждёт очередь)
            if (SableTerrain.watched(level, chunk) && !NuclearTickets.neighbourhoodLoaded(level, chunk.getPos(), 1)) {
                waiting++;
                continue;
            }
            // снимки и планы слоёв держат память до волны: сверх предела или в тесной куче новые не берутся
            if (ctx.shotBytes() >= NuclearPrep.PLANS_CAP || heap.tight()) {
                skipped++;
                continue;
            }
            long t0 = clock.begin();
            try {
                RuinContext.Grid g = ctx.flashGrid(level, chunk.getPos(), true);
                building.put(k, new Build(RuinWorkers.submit(() -> {
                    RuinWorkers.checkStop();
                    return RuinPlanner.flash(g);
                }, priority)));
            } catch (RuntimeException ex) {
                failed(k, ex);
            } finally {
                nanos += clock.end(t0);
            }
        }
        if (!building.isEmpty()) return false;
        Incoming.LOG.info("Nuclear detonation #{}: flash scorch — {} of {} chunks ({} seen by players) in {} ticks (pulse {} ticks), {} ms on the server thread; "
                        + "{} blocks, {} fires lit; {} stale, {} after the wave, {} unloaded, {} at aircraft without neighbours, {} over the memory limit{}",
                d.id(), applied, order.length, seenCount, lastApplied < 0 ? 0 : lastApplied - d.gameTime(),
                String.format(java.util.Locale.ROOT, "%.1f", d.pulseTicks()), nanos / 1_000_000, blocks, fires, stale, late, gone, waiting, skipped,
                failures > 0 ? ", " + failures + " failed" : "");
        return true;
    }

    /** Отбой, остановка: фоновые слои больше не нужны. */
    void cancel() {
        for (Build b : building.values()) b.task.cancel(false);
        building.clear();
    }

    /**
     * Фоновый слой дольше {@link RuinContext#TASK_AGE_LIMIT} тиков работы (от начала работы, не от постановки в очередь)
     * — брошен: зависшая задача иначе держала бы работу (и {@link NuclearWorld#busy}) без конца. Чанк получит всё с руинами.
     */
    private boolean abandoned(Build b, int k, long now) {
        if (!RuinWorkers.started(b.task)) {
            b.since = -1;
            return false;
        }
        if (b.since < 0) b.since = now;
        if (now - b.since < RuinContext.TASK_AGE_LIMIT) return false;
        b.task.cancel(true);
        RuinWorkers.expired();
        if (failures++ < 3) {
            Incoming.LOG.warn("Nuclear detonation #{}: flash scorch of chunk {} not ready in {} ticks — abandoned (the wave brings it with the ruins)",
                    d.id(), new ChunkPos(order[k]), RuinContext.TASK_AGE_LIMIT);
        }
        return true;
    }

    /** Чанк упал с ошибкой: в лог (первые), остальные чанки идут дальше; этот получит всё с руинами. */
    private void failed(int k, Throwable e) {
        if (failures++ < 3) Incoming.LOG.error("Nuclear detonation #{}: flash scorch of chunk {} failed", d.id(), new ChunkPos(order[k]), e);
    }

    /** Руины чанка уже стоят (волна дошла раньше слоя): слой ему не нужен. */
    private boolean ruined(RuinContext ctx, LevelChunk chunk) {
        return ctx.applied(chunk.getPos().toLong()) != null || chunk.getExistingData(ModAttachments.CHUNK_SCAR).orElse(0) >= d.id();
    }

    /** Готовый слой — в чанк. */
    private void place(ServerLevel level, RuinContext ctx, ScarQueue scars, int k, Future<RuinPlan> task, ColumnScar.Budget budget, long now) {
        long key = order[k];
        RuinPlan plan;
        try {
            plan = task.get();
        } catch (java.util.concurrent.ExecutionException e) {
            failed(k, e.getCause());
            return;
        } catch (InterruptedException | java.util.concurrent.CancellationException e) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
        if (chunk == null) {
            gone++;
            return;
        }
        if (ruined(ctx, chunk)) {
            late++;
            return;
        }
        // соседи нужны записи (аппарат Sable рядом, блок-сущность у края), а их нет: руины дождутся их сами
        if (plan.waitsNeighbours(level, chunk) > 0) {
            waiting++;
            return;
        }
        int lit = plan.applyFlash(level, chunk, d, budget);
        if (lit < 0) {
            stale++;
            return;
        }
        applied++;
        blocks += plan.changedBlocks();
        fires += lit;
        lastApplied = now;
        // руинам чанка снимок уже не нужен (план готов или строится): до волны его держали бы только соседи — возьмут заново
        if (scars.hasPlan(ctx, d, key)) ctx.dropShot(key);
    }

    /**
     * Чанки следа (в памяти целиком, в радиусе руин), по порядку: видимые игроком — по расстоянию до ближнего игрока,
     * остальные — от эпицентра; когда каждому вставать.
     */
    private void list(ServerLevel level) {
        LongArrayList in = new LongArrayList();
        if (only != null) {
            in.addElements(0, only);
        } else {
            double reach = reach(d);
            for (ChunkHolder holder : level.getChunkSource().chunkMap.getChunks()) {
                ChunkPos p = holder.getPos();
                // руины придут только в радиус очереди руин (по земле, как и след): огонь слоя без них не получил бы тиков
                if (NuclearPrep.horizontal(p, d) <= reach && ScarQueue.inRange(p, d) && level.getChunkSource().getChunkNow(p.x, p.z) != null) in.add(p.toLong());
            }
        }
        List<ServerPlayer> players = level.players();
        int n = in.size();
        double[] rank = new double[n];
        boolean[] watched = new boolean[n];
        for (int i = 0; i < n; i++) {
            ChunkPos p = new ChunkPos(in.getLong(i));
            double best = Double.MAX_VALUE;
            for (ServerPlayer player : players) {
                if (!player.getChunkTrackingView().contains(p)) continue;
                best = Math.min(best, Math.hypot(p.getMiddleBlockX() + 0.5 - player.getX(), p.getMiddleBlockZ() + 0.5 - player.getZ()));
            }
            watched[i] = best < Double.MAX_VALUE || ScarQueue.seenInTest(p.toLong());
            // видимые — раньше всех невидимых (сдвиг больше любого расстояния в мире)
            rank[i] = watched[i] ? (best < Double.MAX_VALUE ? best : 0) : 1e9 + NuclearPrep.horizontal(p, d);
        }
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(rank[a], rank[b]));
        order = new long[n];
        due = new long[n];
        seen = new boolean[n];
        for (int j = 0; j < n; j++) {
            int i = idx[j];
            ChunkPos p = new ChunkPos(in.getLong(i));
            order[j] = p.toLong();
            seen[j] = watched[i];
            if (seen[j]) seenCount++;
            // свет у ближней к шару точки чанка на земле: там порог поджига набирается раньше всего чанка
            double x = Mth.clamp(d.burst().x, p.getMinBlockX(), p.getMaxBlockX() + 1), z = Mth.clamp(d.burst().z, p.getMinBlockZ(), p.getMaxBlockZ() + 1);
            due[j] = d.gameTime() + (long) Math.ceil(d.flashTicks(d.fluence(new Vec3(x, d.groundY() + 1, z)), ThermalModel.IGNITE));
        }
    }
}
