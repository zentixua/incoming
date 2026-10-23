package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.fx.Fragments;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.function.DoubleSupplier;

/**
 * Ветер за фронтом ударной волны в одном месте — тот же закон, что несёт пыль головы стены ({@link BlastWall#carried}):
 * вдоль земли от эпицентра, сразу за фронтом {@link Detonation#blastWind} (Ренкин–Гюгонио × √доли напора вдоль земли),
 * дальше по Фридлендеру u(t) = U(1 − t/t₊)e^(−t/t₊) (Glasstone и Dolan 1977, §3.57), t₊ — положительная фаза на месте
 * ({@link Detonation#positivePhaseTicks}); в фазе разрежения — обратный и слабее (наибольший — e^(−2) от U на 2t₊).
 * Путь воздуха с прихода — ∫u dt = U·t₊·x·e^(−x), x = t/t₊: к концу фазы U·t₊/e ({@link BlastWall#CARRY}), потом обратно.
 * Им летят куски снесённого ({@link Fragments}) и пыль проломов ({@link BlastDust}) у камеры. Ветер берётся в месте, откуда
 * их сорвало: куски и пыль уходят на десятки блоков, а давление на таком пути почти не меняется (⚠ приближение).
 * <p>
 * Перед фронтом ветра нет: воздух за скачком всегда медленнее самого скачка, но фронт в игре вдали идёт со скоростью
 * {@code nuclear.wave_speed} (по умолчанию 0,2 звука, {@link Detonation#wave}), а ветер и фаза — настоящие, и у сильной
 * волны воздух обогнал бы фронт. Поэтому ветер не быстрее фронта в игре, путь не уходит дальше фронта, и тому, что уже
 * впереди него, ветер не дует (⚠ не по физике — следствие игровой скорости волны; при волне как в жизни ничего не меняет).
 * <p>
 * Блоки и тики игры: путь — блоки, скорость — блоки за тик (метр модели — {@code scale} блоков, секунда модели —
 * {@code 20·scale} тиков, поэтому скорость в блоках за тик — U/20 при любом масштабе); часы — тики после подрыва.
 */
public final class BlastGust implements Fragments.Wind {
    private final Detonation d;
    /** Приход фронта в игре — свой у подрыва ({@link Detonation#front}): таблица, а не поиск в кэше на каждый вызов. */
    private final ua.zentix.incoming.nuclear.model.FrontProfile front;
    /** Наклонная дальность места от подрыва, блоки. */
    private final double slant;
    /** Приход фронта и положительная фаза, тики после подрыва; ветер сразу за фронтом, блоков за тик; направление от эпицентра. */
    final double arrival, phase, u0, ax, az;
    private final DoubleSupplier clock;
    /** Самое дальнее место воздуха ({@link #travel}): миг, тики после подрыва, и путь, блоки; считается при первом спросе. */
    private double peakTicks = Double.NaN, peakPath;

    private BlastGust(Detonation d, Vec3 at, DoubleSupplier clock) {
        this.d = d;
        this.front = d.front();
        this.slant = at.distanceTo(d.burst());
        double dx = at.x - d.burst().x, dz = at.z - d.burst().z, len = Math.sqrt(dx * dx + dz * dz);
        this.ax = len < 1e-3 ? 1 : dx / len;
        this.az = len < 1e-3 ? 0 : dz / len;
        this.arrival = d.arrivalTicks(slant);
        this.phase = Math.max(d.positivePhaseTicks(slant), 1e-3);
        this.u0 = d.blastWind(at) / 20;
        this.clock = clock;
    }

    /**
     * Ветер волны подрыва {@code a} в месте {@code at} — по часам подрыва у клиента, найденного по номеру: вход в мир,
     * возрождение и перемотка повтора заменяют его запись ({@code ClientNuclear.sync}), а куски в полёте остаются, и со
     * старыми часами ветер у них застыл бы на десяток секунд. Подрыва нет — ветра нет.
     */
    static BlastGust at(ClientNuclear.Active a, Vec3 at) {
        int id = a.d.id();
        return of(a.d, at, () -> ClientNuclear.ticksOf(id));
    }

    /** Ветер волны подрыва {@code d} в месте {@code at}; {@code clock} — тики после подрыва. */
    public static BlastGust of(Detonation d, Vec3 at, DoubleSupplier clock) {
        return new BlastGust(d, at, clock);
    }

    /** Доля положительной фазы с прихода фронта в миг {@code ticks} (до прихода — 0). */
    double x(double ticks) {
        return Math.max(0, ticks - arrival) / phase;
    }

    /**
     * Скорость воздуха от эпицентра вдоль земли, блоков за тик (в фазе разрежения — меньше нуля; до фронта — 0), не быстрее
     * фронта в игре.
     */
    double speed(double ticks) {
        double x = x(ticks);
        if (x <= 0) return 0;
        double u = u0 * (1 - x) * Math.exp(-x);
        return Math.min(u, front.radiusAt(ticks + 0.5) - front.radiusAt(ticks - 0.5));
    }

    /**
     * Путь воздуха от эпицентра с прихода фронта, блоки: U·t₊·x·e^(−x) (в фазе разрежения идёт назад), не дальше фронта
     * в игре.
     */
    double path(double ticks) {
        double x = x(ticks);
        if (x <= 0) return 0;
        return Math.min(u0 * phase * x * Math.exp(-x), Math.max(0, front.radiusAt(ticks) - slant));
    }

    /**
     * Пройденное воздухом расстояние туда и обратно, блоки (не убывает): до самого дальнего места — путь, потом ещё и весь
     * обратный ход. Путь один раз растёт и один раз убывает (ветер Фридлендера меняет знак один раз, фронт только уходит),
     * поэтому самое дальнее место — одно ({@link #peak}).
     */
    double travel(double ticks) {
        if (Double.isNaN(peakTicks)) peak();
        return ticks <= peakTicks ? path(ticks) : 2 * peakPath - path(ticks);
    }

    /** Миг и путь самого дальнего места воздуха: делением отрезка на три (путь одногорбый) от прихода до трёх фаз. */
    private void peak() {
        double lo = arrival, hi = arrival + 3 * phase;
        for (int i = 0; i < 60; i++) {
            double m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
            if (path(m1) < path(m2)) lo = m1;
            else hi = m2;
        }
        peakTicks = 0.5 * (lo + hi);
        peakPath = path(peakTicks);
    }

    @Override
    public void at(double x, double y, double z, double[] into) {
        double now = clock.getAsDouble(), u = Double.isNaN(now) ? 0 : speed(now);
        // впереди фронта воздух стоит
        double bx = x - d.burst().x, by = y - d.burst().y, bz = z - d.burst().z, r = u == 0 ? 0 : front.radiusAt(now);
        if (bx * bx + by * by + bz * bz > r * r) u = 0;
        into[0] = ax * u;
        into[1] = u;
        into[2] = az * u;
    }
}
