package ua.zentix.incoming.entity;

/**
 * Очистка пустого ускорителя — не по физике: 20 с после отделения или 5 с после падения, что раньше;
 * под дном мира — 3 с с тем же плавным исчезновением.
 * Модель и её свечение исчезают вместе за 2 с; последняя секунда невидима, чтобы пакет удаления и небольшое
 * расхождение часов сервера и клиента не обрывали свет. Температура стенок от этой подачи не меняется.
 */
public final class BoosterLifetime {
    public static final int MAX_AGE = 400;
    public static final int LIE_TICKS = 100;
    public static final int FADE_TICKS = 40;
    public static final int HIDDEN_TICKS = 20;

    private BoosterLifetime() {}

    /** Остаток жизни по часам сервера → общая видимость сетки и горячего сопла. */
    public static float visibility(double remainingTicks) {
        double x = Math.clamp((remainingTicks - HIDDEN_TICKS) / FADE_TICKS, 0, 1);
        return (float) (x * x * (3 - 2 * x));
    }
}
