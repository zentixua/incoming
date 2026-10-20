package ua.zentix.incoming.client.fx.plume;

import ua.zentix.incoming.util.Wind;

/**
 * Воздух для дыма сейчас: ветер мира ({@link Wind}, тот же у ядерки), класс устойчивости ({@link Stability}) по
 * ветру у земли, солнцу и облачности и влажность. Влажность Minecraft не знает: в дождь и грозу воздух насыщен
 * (влажность над водой 1), в ясную погоду — 0,5 (⚠ оценка: средняя влажность верхней тропосферы — десятки процентов).
 *
 * @param wind      ветер мира
 * @param stability класс устойчивости, 0 (A) .. 5 (F)
 * @param humidity  влажность над водой, 0..1
 */
public record Air(Wind wind, double stability, double humidity) {
    /** Уровень моря мира, блоков (как {@code WaterDecal.SEA_LEVEL}): высоты дыма — над ним. */
    public static final int SEA = 63;
    /** Высота ветра у земли в таблице Паскуилла, м. */
    static final double U10 = 10;

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

    /** Скорость ветра на высоте y мира, м/с. */
    public double speed(double y) {
        return wind.at(y - SEA, Stability.alpha(stability));
    }

    /** Частота Брента–Вяйсяля, 1/с. */
    public double brunt() {
        return Stability.brunt(stability);
    }
}
