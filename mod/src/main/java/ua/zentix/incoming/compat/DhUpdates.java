package ua.zentix.incoming.compat;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.nuclear.world.FarLods;
import ua.zentix.incoming.util.Terrain;
import java.util.concurrent.CompletableFuture;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Mod world changes delivered to DH's terrain database and Voxy players through their shared queue.
 * Loaded marks coalesce and resend no sooner than {@link #RESEND}; built distant copies use {@link #offer}, and
 * front-held copies use {@link #sendHeld}. When a marked chunk unloads while delivery is paused, read its saved
 * sections asynchronously instead of dropping the fresh update. Never load a world chunk for LOD delivery.
 */
public final class DhUpdates {
    /** Вызовов DH за тик сервера (400 чанков в секунду). */
    static final int PER_TICK = 20;
    /** Раньше этого после прошлой отправки чанк снова не отправляется: DH ещё держит его в очереди (250 мс и пакет ~1 с). */
    static final int RESEND = 40;
    /** Сколько тиков после изменения ждать следующих порций того же взрыва. */
    public static final int SETTLE = 10;
    /** Собранных чанков не в мире ждут отправки, не больше: за ними — память (секции целиком). */
    static final int OFFER_LIMIT = 512;
    /**
     * Отметок за тик просматривается, не больше: не наступившие встают в конец (по кругу) — очередь (руины ядерки —
     * тысячи чанков) не обходится вся каждый тик, а наступившая за ними ждёт не дольше нескольких тиков.
     */
    static final int SCAN = 256;
    /**
     * Своя задержка DH от вызова до записи LOD в базу, тиков, — наименьшая ({@link DhQueue#LEAD}): держанная копия
     * ({@link #sendHeld}) уходит за столько до прихода фронта — LOD меняется не раньше него.
     */
    public static final int LEAD = DhQueue.LEAD;
    /**
     * Сколько DH держит отданную копию чанка не в мире, нс, не меньше: до записи в базу (без подтверждения — до срока
     * {@link DhQueue#TIMEOUT_NANOS}) и ещё {@link DhQueue#CACHE_NANOS} в своём кэше: память копии, отданной к фронту, —
     * столько после отдачи.
     */
    public static final long HOLD_NANOS = DhQueue.TIMEOUT_NANOS + DhQueue.CACHE_NANOS;

    /** Чанк → тик, не раньше которого его отправить; по порядку отметок. */
    private final Long2LongLinkedOpenHashMap due = new Long2LongLinkedOpenHashMap();
    /** Чанк → более поздняя отметка, чем ближайшая в {@link #due}: после отправки чанк ждёт её снова. */
    private final Long2LongOpenHashMap later = new Long2LongOpenHashMap();
    /** Чанк → тик последней отправки (старше {@link #RESEND} — забываются). */
    private final Long2LongOpenHashMap sent = new Long2LongOpenHashMap();
    /** At most four asynchronous reads; marks remain pending until a fresh copy is delivered. */
    static final class SavedRead {
        final CompletableFuture<ChunkAccess> future;
        boolean obsolete;

        SavedRead(CompletableFuture<ChunkAccess> future) { this.future = future; }
    }

    private final Long2ObjectOpenHashMap<SavedRead> unloaded = new Long2ObjectOpenHashMap<>();
    private final ArrayDeque<ChunkAccess> offered = new ArrayDeque<>();
    /** Чанки в {@link #offered} (сколько копий каждого ждёт отправки). */
    private final Long2IntOpenHashMap offeredCount = new Long2IntOpenHashMap();
    /** Держанные копии, отданные и не подтверждённые ({@link #sendHeld}). */
    private final DhQueue held = new DhQueue();
    /** Подтверждения из потока DH ({@link #saved}): чанк и когда. */
    private final ConcurrentLinkedQueue<long[]> acks = new ConcurrentLinkedQueue<>();
    private long sentTotal, offeredTotal;
    /** Для проверок: больше всего отметок, просмотренных за один тик. */
    private int scannedMax;
    /** Куда уходят чанки: в игре — {@link DhChunks} и {@link VoxyLods}; проверки подставляют свой приёмник ({@link #testSink}). */
    private Sink sink = DhUpdates::deliver;
    private boolean test;
    private boolean listening;
    /** Non-owning callback fast path; queues and world references remain in level attachments. */
    private static volatile int waitingLevels;

    private void setListening(boolean value) {
        if (listening == value) return;
        listening = value;
        waitingLevels += value ? 1 : -1;
    }

    /** Server lifecycle boundary: the previous server's level attachments no longer receive acknowledgements. */
    static void resetWaiting() {
        waitingLevels = 0;
    }

    /** Приёмник чанков. */
    public interface Sink {
        void overwrite(ServerLevel level, ChunkAccess chunk);
    }

    /** Для {@link ModAttachments#DH_UPDATES}: своё у каждого мира, не сохраняется. */
    public DhUpdates() {}

    /** Очередь мира, если есть куда отдавать (DH стоит, в мире игрок с Voxy или проверка подставила приёмник), иначе null. */
    private static DhUpdates active(ServerLevel level) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        return u.test || DhChunks.available() || VoxyLods.wanted(level) ? u : null;
    }

    /** Чанк — в DH (если стоит) и игрокам с Voxy (если есть). Поток сервера. */
    private static void deliver(ServerLevel level, ChunkAccess chunk) {
        DhChunks.overwrite(level, chunk);
        VoxyLods.send(level, chunk);
    }

    /** Темп держит очередь DH (подтверждения записи): DH стоит или проверка подставила приёмник. */
    private boolean paced() {
        return test || DhChunks.available();
    }

    /** Проверки: чанки мира {@code level} уходят в {@code sink} (и без DH); null — снова в DH, очередь пуста. */
    public static void testSink(ServerLevel level, @Nullable Sink sink) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        u.sink = sink != null ? sink : DhUpdates::deliver;
        u.test = sink != null;
        if (sink == null) {
            u.due.clear();
            u.unloaded.clear();
            u.later.clear();
            u.offered.clear();
            u.offeredCount.clear();
            u.held.clear();
            u.setListening(false);
            u.acks.clear();
        }
    }

    /** Мод изменил блоки загруженного чанка: в DH не раньше тика {@code at}. Поток сервера. */
    public static void mark(ServerLevel level, ChunkPos pos, long at) {
        DhUpdates u = active(level);
        if (u == null) return;
        SavedRead read = u.unloaded.get(pos.toLong());
        if (read != null) read.obsolete = true; // A later change must not reuse the previous save's result.
        Long2LongLinkedOpenHashMap due = u.due;
        long key = pos.toLong();
        // повторная отметка не откладывает раннюю, а встаёт за ней: после отправки чанк ждёт и её
        if (!due.containsKey(key)) {
            due.put(key, at);
            return;
        }
        long was = due.get(key);
        if (at < was) due.put(key, at);
        long last = Math.max(was, at);
        if (last > due.get(key) && last > u.later.get(key)) u.later.put(key, last);
    }

    /** {@link #mark} всех чанков квадрата вокруг {@code centre} радиусом {@code reach} блоков. */
    public static void markArea(ServerLevel level, Vec3 centre, double reach, long at) {
        if (active(level) == null) return;
        int x0 = Mth.floor(centre.x - reach) >> 4, x1 = Mth.floor(centre.x + reach) >> 4;
        int z0 = Mth.floor(centre.z - reach) >> 4, z1 = Mth.floor(centre.z + reach) >> 4;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) mark(level, new ChunkPos(x, z), at);
    }

    /** Отметка загруженного чанка ещё ждёт отправки в DH ({@link #mark}). Поток сервера. */
    public static boolean pending(ServerLevel level, long chunk) {
        DhUpdates u = active(level);
        return u != null && u.due.containsKey(chunk);
    }

    /** Есть куда отдавать: DH стоит (или проверка подставила приёмник). */
    public static boolean enabled(ServerLevel level) {
        return active(level) != null;
    }

    /**
     * Есть ли куда отдавать, и сколько ещё собранных чанков не в мире можно отдать ({@link #offer}) прямо сейчас: и не
     * больше, чем осталось места в очередях игроков с Voxy ({@link VoxyLods#room}).
     */
    public static int room(ServerLevel level) {
        DhUpdates u = active(level);
        return u == null ? 0 : Math.max(0, Math.min(OFFER_LIMIT - u.offered.size(), VoxyLods.room(level) - u.offered.size()));
    }

    /**
     * Чанк не из мира (его копия с изменениями, {@code ProtoChunk}) — в LOD как есть: после этого его не менять.
     * Поток сервера.
     */
    public static void offer(ServerLevel level, ChunkAccess chunk) {
        DhUpdates u = active(level);
        if (u == null) return;
        u.offered.add(chunk);
        u.offeredCount.addTo(chunk.getPos().toLong(), 1);
        u.offeredTotal++;
    }

    /**
     * Сколько ещё держанных копий можно отдать сейчас ({@link #sendHeld}): окно — половина наименьшей очереди DH на мир
     * по числу его потоков ({@link DhQueue#window}), минус отданные и не подтверждённые; без DH — 0.
     */
    public static int heldRoom(ServerLevel level) {
        DhUpdates u = active(level);
        if (u == null) return 0;
        int dh = u.paced() ? DhQueue.window(DhChunks.threads()) - u.held.waiting() : Integer.MAX_VALUE;
        return Math.min(dh, VoxyLods.room(level));
    }

    /**
     * Держанная копия чанка не в мире (собрана заранее, {@code ProtoChunk}) — в DH сейчас, мимо {@link #PER_TICK}: её
     * срок — приход фронта, а темп держит окно ({@link #heldRoom}); после этого копию не менять. Поток сервера.
     *
     * @return false — не сейчас: тот же чанк отдан DH меньше {@link #RESEND} тиков назад или ждёт отправки
     * ({@link #offer}) — DH пропустил бы копию или накрыл бы её той
     */
    public static boolean sendHeld(ServerLevel level, ChunkAccess copy) {
        DhUpdates u = active(level);
        if (u == null) return true;
        long key = copy.getPos().toLong(), now = level.getGameTime();
        if (u.offeredCount.containsKey(key) || u.sent.containsKey(key) && now - u.sent.get(key) < RESEND) return false;
        // подтверждения записи — только от DH: без него темп держит место в очередях Voxy (heldRoom)
        boolean paced = u.paced();
        if (paced && u.held.waiting() == 0) {
            u.setListening(true);
            DhChunks.listen();
        }
        if (u.test) {
            u.sink.overwrite(level, copy);
        } else {
            DhChunks.overwrite(level, copy);
            // DH ставит LOD через LEAD тиков после вызова, Voxy — сразу: копия игрокам с Voxy — к самому фронту
            VoxyLods.send(level, copy, now + LEAD);
        }
        if (paced) u.held.sent(key, System.nanoTime());
        u.sent.put(key, now);
        return true;
    }

    /**
     * DH записал LOD чанка в свою базу ({@code DhApiChunkModifiedEvent}; поток DH, проверки — любой): подтверждение
     * держанной копии ({@link #sendHeld}). Мир — тот, что DH обёртывает ({@code getWrappedMcObject}).
     */
    public static void saved(Object level, int x, int z) {
        if (waitingLevels == 0 || !(level instanceof ServerLevel world)) return;
        var server = world.getServer();
        if (!server.isRunning()) return;
        long at = System.nanoTime();
        // Attachment access belongs to the server thread; DH callbacks can arrive after world shutdown.
        server.execute(() -> {
            if (!server.isRunning() || server.getLevel(world.dimension()) != world || !world.hasData(ModAttachments.DH_UPDATES)) return;
            DhUpdates u = world.getData(ModAttachments.DH_UPDATES);
            if (u.listening) u.acks.add(new long[] {ChunkPos.asLong(x, z), at});
        });
    }

    /**
     * Держанные копии мира с прошлого вызова (счёт начинается заново): отдано, подтверждено, без подтверждения за срок,
     * задержка подтверждённых — средняя и наибольшая (мс), ждут подтверждения сейчас.
     */
    public static long[] takeHeldStats(ServerLevel level) {
        return level.getData(ModAttachments.DH_UPDATES).held.take();
    }

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.hasData(ModAttachments.DH_UPDATES)) level.getData(ModAttachments.DH_UPDATES).tick(level);
    }

    private void tick(ServerLevel level) {
        long now = level.getGameTime();
        unloaded.long2ObjectEntrySet().removeIf(e -> e.getValue().future.isDone() && !due.containsKey(e.getLongKey()));
        if (held.waiting() > 0) {
            for (long[] a; (a = acks.poll()) != null; ) held.ack(a[0], a[1]);
            held.expire(System.nanoTime());
            if (held.waiting() == 0) setListening(false);
        }
        if (due.isEmpty() && offered.isEmpty()) return;
        // игроки с Voxy (и с DH): темп — и место в их очередях, иначе лишнее они бы не получили
        int budget = Math.min(PER_TICK, VoxyLods.room(level));
        // собранные чанки — первыми: отметки чанков в мире подождут тик, а собранные держат память; чанк, отданный DH
        // меньше RESEND тиков назад (держанной копией или прошлой собранной), — в конец: DH пропустил бы его молча
        for (int i = 0, n = offered.size(); budget > 0 && i < n; i++) {
            ChunkAccess c = offered.poll();
            long key = c.getPos().toLong();
            if (sent.containsKey(key) && now - sent.get(key) < RESEND) {
                offered.add(c);
                continue;
            }
            if (offeredCount.addTo(key, -1) <= 1) offeredCount.remove(key);
            sink.overwrite(level, c);
            sent.put(key, now);
            budget--;
        }
        // отправленных за RESEND тиков — не больше (PER_TICK + окно держанных копий) × RESEND
        sent.long2LongEntrySet().removeIf(e -> now - e.getLongValue() >= RESEND);
        Pending pending = new Pending();
        LongArrayList notYet = new LongArrayList();
        ObjectIterator<Long2LongMap.Entry> it = due.long2LongEntrySet().fastIterator();
        int scanned = 0;
        while (budget > 0 && scanned < SCAN && it.hasNext()) {
            Long2LongMap.Entry e = it.next();
            scanned++;
            long key = e.getLongKey();
            long at = Math.max(e.getLongValue(), sent.containsKey(key) ? sent.get(key) + RESEND : Long.MIN_VALUE);
            if (at > now) {
                e.setValue(at);
                notYet.add(key);
                continue;
            }
            ChunkAccess chunk = Terrain.inMemory(level, key);
            if (chunk == null) {
                SavedRead read = unloaded.get(key);
                if (read == null) {
                    if (unloaded.size() < 4) unloaded.put(key, new SavedRead(FarLods.savedCopy(level, key)));
                } else if (read.future.isDone()) {
                    unloaded.remove(key);
                    if (read.obsolete) {
                        // Retain/count the old read until completion, then retry the newest mark in a later tick.
                        notYet.add(key);
                        continue;
                    }
                    if (read.future.isCompletedExceptionally()) {
                        e.setValue(now + 20); // Retry I/O failure; do not lose the update or block the tick.
                    } else {
                        chunk = read.future.getNow(null);
                        if (chunk == null) {
                            // No final saved blocks exist here (markArea also covers untouched world-edge chunks).
                            it.remove();
                            later.remove(key);
                            continue;
                        }
                    }
                }
                if (chunk == null) {
                    notYet.add(key);
                    continue;
                }
            }
            it.remove();
            if (later.containsKey(key)) {
                long next = later.remove(key);
                if (next > now) pending.add(key, next);
            }
            sink.overwrite(level, chunk);
            sent.put(key, now);
            sentTotal++;
            budget--;
        }
        scannedMax = Math.max(scannedMax, scanned);
        // обошли не всё: не наступившие — в конец, следующий тик смотрит дальше
        if (it.hasNext()) for (int i = 0; i < notYet.size(); i++) due.getAndMoveToLast(notYet.getLong(i));
        for (int i = 0; i < pending.keys.size(); i++) due.put(pending.keys.getLong(i), pending.ats.getLong(i));
    }

    /** Отметки, которые встают снова после отправки (в карту — после обхода). */
    private static final class Pending {
        final LongArrayList keys = new LongArrayList(), ats = new LongArrayList();

        void add(long key, long at) {
            keys.add(key);
            ats.add(at);
        }
    }

    /**
     * Для проверок: ждут отправки (чанки мира, собранные), отправлено с запуска мира (чанки мира, собранные), больше
     * всего отметок, просмотренных за тик.
     */
    public static long[] stats(ServerLevel level) {
        DhUpdates u = level.getData(ModAttachments.DH_UPDATES);
        return new long[] {u.due.size(), u.offered.size(), u.sentTotal, u.offeredTotal - u.offered.size(), u.scannedMax};
    }
}
