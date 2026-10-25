package ua.zentix.stand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Счёт попаданий без Minecraft (его события переводит {@link ServerWatch}; тест — {@code ImpactsTest}).
 *
 * <p>Попадание — снаряд ушёл из мира убитым или убранным в радиусе цели залпа ({@link #leave}) и начался взрыв, чей
 * снаряд — он же, и это подрыв боевой части ({@link #blast}: UUID источника взрыва, тип урона боевой части). Свой
 * взрыв аварии (снаряд разбился до взведения — малый ванильный взрыв без боевой части) попаданием не бывает: стенд
 * мерил бы не удары. Порядок любой: работа попаданий Incoming идёт под бюджетом
 * тика, и взрыв начинается через тики после ухода (или до него). Сопоставление — только по UUID: взрыв другого снаряда
 * рядом чужой уход не подтверждает, вторичные подрывы того же снаряда второй раз не считаются, взрыв без снаряда
 * (нет источника) не подтверждает ничего. Уход без взрыва — переход в полёт вне мира ({@code VirtualFlights.park}
 * тоже убирает сущность): он ждёт, пока снаряд не вернётся в мир тем же UUID ({@link #join} снимает ожидание) или не
 * взорвётся; срока у ожидания нет — подрыв под нагрузкой идёт сколько угодно позже, конец залпа решает его срок.
 * Всё — в потоке сервера, кроме {@link #waiting}.
 */
final class Impacts {
    /** Цель залпа: уход в радиусе {@code r} от (x, z) по горизонтали — попадание этого залпа. */
    record Target(String salvo, double x, double z, double r) {}

    /** Уход у цели: снаряд, залп, где, когда (nanoTime) — миг попадания. */
    record Leave(UUID uuid, String salvo, double x, double y, double z, long nanos) {}

    /** Подтверждённое попадание: уход и чем подтверждён ({@code blast-after} — взрыв после ухода, {@code blast-before} — до). */
    record Hit(Leave leave, String how) {}

    private final List<Target> targets = new ArrayList<>();
    private final Map<UUID, Leave> pending = new LinkedHashMap<>();
    /** Снаряды, чей взрыв уже начался, а ухода у цели ещё не было. */
    private final Set<UUID> blasted = new HashSet<>();
    private final Set<UUID> counted = new HashSet<>();
    /** Читает и поток клиента ({@link #waiting}). */
    private final Map<String, Integer> waiting = new ConcurrentHashMap<>();

    /** Залп ждёт попаданий у (x, z) в радиусе r; позже заявленная цель берёт уход раньше прежних (места одни). */
    void target(String salvo, double x, double z, double r) {
        targets.addFirst(new Target(salvo, x, z, r));
    }

    /** Снаряд в мире (появился или вернулся из полёта вне мира): ждущий уход — не попадание; назад — был ли он. */
    boolean join(UUID uuid) {
        Leave l = pending.remove(uuid);
        if (l == null) return false;
        waiting.merge(l.salvo, -1, Integer::sum);
        return true;
    }

    /**
     * Снаряд ушёл из мира убитым или убранным в (x, y, z). Назад — попадание (взрыв уже был), иначе null; уход у цели
     * без взрыва ждёт ({@link #waiting}), вдали от целей — забыт.
     */
    Hit leave(UUID uuid, double x, double y, double z, long nanos) {
        if (counted.contains(uuid)) return null;
        for (Target t : targets) {
            if (Math.hypot(x - t.x, z - t.z) > t.r) continue;
            Leave l = new Leave(uuid, t.salvo, x, y, z, nanos);
            if (blasted.remove(uuid)) return count(l, "blast-before");
            Leave old = pending.put(uuid, l);
            if (old != null) waiting.merge(old.salvo, -1, Integer::sum);
            waiting.merge(l.salvo, 1, Integer::sum);
            return null;
        }
        return null;
    }

    /**
     * Начался взрыв снаряда {@code uuid} (null — у взрыва нет снаряда); {@code warhead} — это подрыв боевой части, а не
     * авария. Не в счёт — без снаряда или не боевая часть. Назад — попадание или null.
     */
    Hit blast(UUID uuid, boolean warhead) {
        if (uuid == null || !warhead || counted.contains(uuid)) return null;
        Leave l = pending.remove(uuid);
        if (l == null) {
            blasted.add(uuid);
            return null;
        }
        waiting.merge(l.salvo, -1, Integer::sum);
        return count(l, "blast-after");
    }

    private Hit count(Leave l, String how) {
        counted.add(l.uuid);
        return new Hit(l, how);
    }

    /** Уходов залпа у цели, ждущих взрыва или возврата в мир. */
    int waiting(String salvo) {
        return waiting.getOrDefault(salvo, 0);
    }
}
