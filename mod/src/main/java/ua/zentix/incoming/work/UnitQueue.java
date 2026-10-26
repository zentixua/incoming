package ua.zentix.incoming.work;

import net.minecraft.server.level.ServerLevel;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.nuclear.world.WorkClock;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Очередь работ одной полосы в одном мире ({@link WorkScheduler}): каждая работа — цепочка коротких единиц.
 * <p>
 * Две очереди. В первой — работы, которые ещё не начаты: их первая единица (у взрыва — лучи, урон и события: то, что
 * видно и чувствуется сразу) идёт по порядку постановки раньше всего остального. Работа, у которой после первой
 * единицы ещё есть работа (порции блоков взрыва, секции стёкол), переходит во вторую очередь. Продолжения чередуются
 * по одной единице: старый обход огня не держит за собой основной фронт нового удара. Место в круге переживает тик.
 * <p>
 * Работа, чей район ещё не готов ({@link Job#ready}), пропускается, пока не станет готов, а следующие за ней идут;
 * ждёт не дольше {@link #GIVE_UP_TICKS}. Работа, которая ждёт другую ({@link Job#blocked}: огненный шар и стёкла удара
 * — его главный взрыв целиком; блоки взрыва — лучи других взрывов того же тика), тоже пропускается; спрашивается перед
 * каждой единицей. Не сохраняется (как таймлайны взрывов); при остановке сервера
 * доделывается ({@link #finish(ServerLevel)}).
 */
public final class UnitQueue {
    /** Работа: единицы по одной, пока {@link #step} отвечает {@code true}. */
    public interface Job {
        /** Можно ли делать единицу сейчас (район готов). */
        default boolean ready(ServerLevel level) {
            return true;
        }

        /** Ждёт другую работу (та ещё не кончилась). */
        default boolean blocked() {
            return false;
        }

        /** Вид следующей единицы — у каждого вида своя оценка часов ({@link WorkClock#canStart(int)}). */
        default int unitKind() {
            return 0;
        }

        /** Одна единица работы; {@code false} — работа кончилась. */
        boolean step(ServerLevel level);

        /** Работа убрана — кончилась, отменена или упала: отпустить то, что она держит (тикеты). */
        default void end(ServerLevel level) {}

        /** Для строки в лог: что за работа. */
        String describe();
    }

    /** Сколько работа ждёт готовности района, тиков; дальше отменяется (мир не грузится). */
    public static final int GIVE_UP_TICKS = 6000;

    private static final class Entry {
        final Job job;
        final long added;

        Entry(Job job, long added) {
            this.job = job;
            this.added = added;
        }
    }

    private enum Outcome { SKIPPED, OUT_OF_TIME, MORE, DONE }

    /** Не начатые работы. */
    private final List<Entry> heads = new ArrayList<>();
    /** Начатые, с работой после первой единицы. */
    private final Deque<Entry> tails = new ArrayDeque<>();

    public void add(ServerLevel level, Job job) {
        add(level.getGameTime(), job);
    }

    /** То же со временем постановки (проверки без мира). */
    public void add(long now, Job job) {
        heads.add(new Entry(job, now));
    }

    public boolean isEmpty() {
        return heads.isEmpty() && tails.isEmpty();
    }

    public int size() {
        return heads.size() + tails.size();
    }

    /** Сколько тиков ждёт самая старая работа. */
    public long oldestWaitTicks(ServerLevel level) {
        long oldest = Long.MAX_VALUE;
        if (!heads.isEmpty()) oldest = heads.getFirst().added;
        for (Entry entry : tails) oldest = Math.min(oldest, entry.added);
        return oldest == Long.MAX_VALUE ? 0 : level.getGameTime() - oldest;
    }

    /** Единицы, пока часы разрешают: сначала первые единицы новых работ, потом продолжения начатых. */
    public void work(ServerLevel level, WorkClock clock) {
        work(level, level.getGameTime(), clock);
    }

    /**
     * То же со временем мира {@code now} ({@code level} только передаётся работам). Работа может поставить новую (взрыв
     * будит обработчики модов, те — свои взрывы): она встаёт в конец и идёт в этом же обходе, если хватит срока.
     */
    public void work(ServerLevel level, long now, WorkClock clock) {
        for (int i = 0; i < heads.size(); ) {
            Entry e = heads.get(i);
            Outcome o = run(level, now, e, clock);
            if (o == Outcome.OUT_OF_TIME) return;
            if (o == Outcome.SKIPPED) {
                i++;
                continue;
            }
            heads.remove(i);
            if (o == Outcome.MORE) tails.add(e);
            else finish(level, e);
        }
        int missed = 0;
        while (!tails.isEmpty() && missed < tails.size()) {
            if (clock.workedThisTick() && clock.overdue()) return;
            Entry e = tails.removeFirst();
            Outcome o = run(level, now, e, clock);
            if (o == Outcome.DONE) finish(level, e);
            else tails.addLast(e);
            // A full circle without progress: all wait, or none of their estimates fits the remaining time.
            if (o == Outcome.SKIPPED || o == Outcome.OUT_OF_TIME) missed++;
            else missed = 0;
        }
    }

    /** One ready unit, with dependencies rechecked at every turn. */
    private static Outcome run(ServerLevel level, long now, Entry e, WorkClock clock) {
        try {
            if (e.job.blocked()) return Outcome.SKIPPED;
            if (!e.job.ready(level)) {
                if (now - e.added < GIVE_UP_TICKS) return Outcome.SKIPPED;
                Incoming.LOG.warn("{} cancelled: area did not load within {} ticks", e.job.describe(), GIVE_UP_TICKS);
                return Outcome.DONE;
            }
            int kind = e.job.unitKind();
            if (!clock.canStart(kind)) return Outcome.OUT_OF_TIME;
            long c0 = clock.begin();
            try {
                return e.job.step(level) ? Outcome.MORE : Outcome.DONE;
            } finally {
                clock.end(c0, kind);
            }
        } catch (RuntimeException ex) {
            Incoming.LOG.error("{} failed with an exception and was removed", e.job.describe(), ex);
            return Outcome.DONE;
        }
    }

    private static void finish(ServerLevel level, Entry e) {
        try {
            e.job.end(level);
        } catch (RuntimeException ex) {
            Incoming.LOG.error("{}: release failed", e.job.describe(), ex);
        }
    }

    /**
     * Сервер останавливается: доделать сразу и без срока всё, что можно (начатый взрыв не остаётся снятым наполовину,
     * поставленный не пропадает), по тем же правилам — неготовые районы не читаются. Что так и не пошло (неготовый
     * район или ждёт такую работу), отпускается.
     */
    public void finish(ServerLevel level) {
        finish(level, level.getGameTime());
    }

    /** То же со временем мира {@code now} (проверки без мира). */
    public void finish(ServerLevel level, long now) {
        WorkClock unlimited = new WorkClock();
        for (int before = -1; !isEmpty() && size() != before; ) {
            before = size();
            unlimited.start(Long.MAX_VALUE / 4);
            work(level, now, unlimited);
        }
        clear(level);
    }

    /** Отпустить всё без работы (работа не сохраняется). */
    public void clear(ServerLevel level) {
        List<Entry> all = new ArrayList<>(heads);
        all.addAll(tails);
        heads.clear();
        tails.clear();
        for (Entry e : all) finish(level, e);
    }
}
