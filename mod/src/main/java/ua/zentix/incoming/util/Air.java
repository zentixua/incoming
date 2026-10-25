package ua.zentix.incoming.util;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Воздух мира сейчас — один на сервер и клиент: ветер мира ({@link Wind}, тот же у ядерки), класс устойчивости
 * ({@link Stability}) по ветру у земли, солнцу и облачности и влажность. Его читают полёт снарядов (снос), дым, облака
 * взрывов и следы. Влажность Minecraft не знает: в дождь и грозу воздух насыщен (влажность над водой 1), в ясную погоду —
 * 0,5 (⚠ оценка: средняя влажность верхней тропосферы — десятки процентов).
 *
 * @param wind      ветер мира
 * @param stability класс устойчивости, 0 (A) .. 5 (F)
 * @param humidity  влажность над водой, 0..1
 */
public record Air(Wind wind, double stability, double humidity) {
    /**
     * Влажность у земли в ясную погоду, 0..1: средняя относительная влажность у поверхности суши — 70–75 %
     * (HadISDH, Willett et al. 2014, ⚠ сверить); 70 % — и опорные условия ISO 9613-1, при которых собраны дальние записи
     * звука ({@code sound.Air}).
     */
    public static final double SURFACE_CLEAR = 0.7;
    /** Уровень моря мира, блоков (как {@code WaterDecal.SEA_LEVEL}): высоты воздуха — над ним. */
    public static final int SEA = 63;
    /** Высота ветра у земли в таблице Паскуилла, м. */
    static final double U10 = 10;
    /** Штиль в нейтральном воздухе: измерение без неба, проверки. */
    public static final Air CALM = new Air(new Wind(0, 0), Stability.D, 0.5);

    /**
     * Воздух в тик gameTime при синусе высоты солнца sun, дожде и грозе 0..1. Ветер у земли зависит от класса
     * (профиль), класс — от ветра у земли: два шага от нейтрального профиля.
     */
    public static Air of(long gameTime, double sun, double rain, double thunder) {
        Wind w = Wind.at(gameTime);
        double overcast = Math.max(rain, thunder), c = Stability.D;
        for (int i = 0; i < 2; i++) c = Stability.of(w.at(U10, Stability.alpha(c)), sun, overcast);
        return new Air(w, c, 0.5 + 0.5 * Math.min(1, overcast));
    }

    /** Воздух мира {@code level} сейчас: время игры, солнце, дождь и гроза (на сервере и на клиенте одинаково). */
    public static Air of(Level level) {
        return of(level.getGameTime(), sun(level), level.getRainLevel(1), level.getThunderLevel(1));
    }

    /**
     * Синус высоты солнца в мире {@code level}: в измерении без неба или с застывшим временем солнца нет (−1, ночь).
     * Одна функция дня и ночи для воздуха, дыма и звука ({@code sound.Propagation.Weather}).
     */
    public static double sun(Level level) {
        var type = level.dimensionType();
        return type.hasSkyLight() && !type.hasFixedTime() ? Mth.cos(level.getTimeOfDay(1) * Mth.TWO_PI) : -1;
    }

    /**
     * Влажность у земли, 0..1 — для звука, который идёт у земли ({@code sound.Propagation}): в ясную погоду
     * {@link #SURFACE_CLEAR}, в дождь и грозу воздух насыщен, как и наверху (дождь — та же доля, что подняла
     * {@link #humidity}).
     */
    public double surfaceHumidity() {
        double overcast = Math.max(0, Math.min(1, 2 * humidity - 1));
        return SURFACE_CLEAR + (1 - SURFACE_CLEAR) * overcast;
    }

    /** Скорость ветра на высоте y мира, м/с (высота над землёй берётся над уровнем моря). */
    public double speed(double y) {
        return wind.at(y - SEA, Stability.alpha(stability));
    }

    /** Ветер на высоте y мира вдоль x, м/с. */
    public double windX(double y) {
        return speed(y) * wind.x();
    }

    /** Ветер на высоте y мира вдоль z, м/с. */
    public double windZ(double y) {
        return speed(y) * wind.z();
    }

    /** Частота Брента–Вяйсяля, 1/с. */
    public double brunt() {
        return Stability.brunt(stability);
    }
}
