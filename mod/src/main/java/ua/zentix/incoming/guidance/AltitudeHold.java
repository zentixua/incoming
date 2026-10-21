package ua.zentix.incoming.guidance;

import ua.zentix.incoming.strike.WeaponSpec;

/**
 * Удержание высоты: плавный фильтр заданной высоты (1/5 разницы за тик) и тангаж, пропорциональный ошибке (1.2° на
 * блок, вверх — до угла набора паспорта, вниз — до угла снижения, {@link WeaponSpec.Handling}), с ограничением угловой
 * скорости и ускорения ({@link FlightController}).
 */
public final class AltitudeHold {
    /** Сглаженная заданная высота. */
    private double filter;

    /** Сглаженная заданная высота (сохраняется со снарядом). */
    public double filter() {
        return filter;
    }

    /** Начать с высоты {@code y} (старт, возврат в мир, загрузка сохранения). */
    public void reset(double y) {
        filter = y;
    }

    /** Держать высоту {@code desired}, находясь на {@code y}: повороты по тангажу — участка полёта {@code a}. */
    public void hold(FlightController flight, double y, double desired, double gain, WeaponSpec.Handling h, WeaponSpec.Agility a) {
        filter += (desired - filter) / 5;
        double climb = Math.max(-h.descentAngle(), Math.min(h.climbAngle(), (filter - y) * 1.2));
        flight.holdPitch(-climb, gain, a.pitchRate(), a.pitchAccel());
    }
}
