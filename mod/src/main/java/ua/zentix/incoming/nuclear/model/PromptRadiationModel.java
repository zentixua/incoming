package ua.zentix.incoming.nuclear.model;

/**
 * Проникающая радиация первой минуты (гамма и нейтроны): D = K·Y·exp(−R/λ)/R², бэр (≈ сГр в воздухе: керма, которую
 * тело получает у поверхности). R — наклонная дальность, м.
 *
 * <p>K и λ подобраны по керме в свободном воздухе у Нагасаки (21 кт, высота 503 м; RERF: гамма 7,8 Гр на 1 км от
 * эпицентра, 0,89 на 1,5 км, 0,13 на 2 км, 0,021 на 2,5 км; нейтроны — ещё около 10 % на 1 км): модель даёт
 * 8,6 / 1,06 / 0,145 / 0,022 Гр. Хиросима (16 кт, 600 м; пушечная бомба в толстом корпусе выпускала гамма меньше) —
 * 3,9 Гр гаммы на 1 км против модельных 5,2.
 *
 * <p>Ослабление веществом — по массе на пути (кг/м²): слой половинного ослабления для гаммы первой минуты ≈ 320 кг/м² и
 * почти не зависит от вещества (Glasstone & Dolan 1977, табл. 8.41: сталь 1,5″, бетон 6″, земля 7,5″, вода 13″, дерево
 * 23″), у осадков (энергия ниже) — ≈ 130 кг/м² (там же: 0,7″, 2,2″, 3,3″, 4,8″, 8,8″).
 */
public final class PromptRadiationModel {
    /** Длина ослабления в воздухе, м. */
    public static final double LAMBDA = 330;
    /** Коэффициент, бэр·м²/кт (Нагасаки, гамма и нейтроны). */
    public static final double K = 1.52e9;
    /** Бэр в одном грее. */
    public static final double REM_PER_GY = 100;
    /** Слой половинного ослабления радиации первой минуты, кг/м² (бетон — 14 см, земля — 19 см). */
    public static final double HALF_PROMPT = 320;
    /** Слой половинного ослабления гаммы осадков, кг/м² (бетон — 5,6 см, земля — 8 см). */
    public static final double HALF_FALLOUT = 130;

    private PromptRadiationModel() {
    }

    public static double doseRem(double slantRangeM, double yieldKt) {
        double r = Math.max(slantRangeM, 1);
        return K * yieldKt * Math.exp(-r / LAMBDA) / (r * r);
    }

    /** Доля, проходящая сквозь {@code massKgM2} вещества со слоем половинного ослабления {@code half}. */
    public static double transmission(double massKgM2, double half) {
        return Math.pow(0.5, massKgM2 / half);
    }
}
