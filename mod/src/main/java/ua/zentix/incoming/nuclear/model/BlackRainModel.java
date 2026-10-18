package ua.zentix.incoming.nuclear.model;

/**
 * Чёрный дождь от пожаров: огненный шторм поднимает влажный воздух с сажей в облако подрыва, и из него идёт дождь — у
 * любого подрыва над горящим городом. Хиросима (воздушный подрыв; Uda 1953 по RERF): дождь начался через ~20 минут, в
 * зоне сильного дождя (19 × 11 км к северо-западу от города, зона пожаров — около 2 км) лил 1–2 часа, слабый доходил до
 * 29 км. Здесь — полоса от зоны пожаров по ветру: над пожарами дождь идёт с {@link #START_S} по {@link #END_S} после
 * подрыва, облако уносит ветром, и оно выдождивается за {@link #DRIFT_S} пути; полоса шире зоны пожаров на
 * {@link #SPREAD} пройденного пути (у Хиросимы — 5,5 км полуширины в 17 км за зоной). В точке по ветру дождь
 * начинается позже — на путь облака до неё — и идёт столько же. x, z — метры от эпицентра; ветер дует в сторону
 * (cos, sin).
 *
 * <p>Часы — огненного шторма: настоящие секунды, сжатые в {@link #COMPRESSION} раз, как шторм у клиента (разгар
 * за 80 с, спад с 360 с против 20 минут и полутора часов в жизни; решение Артёма), — дождь идёт из того дыма, который
 * видно.
 *
 * <p>Радиоактивность: у воздушного подрыва продукты деления остаются в облаке, и дождь вымывает их часть на землю — в
 * сотни раз слабее следа наземного (DS86, гл. 6: Хиросима, Кои–Такасу — 1–3 Р за всё время, Нагасаки, Нисияма —
 * 20–40 Р). На земле под дождём мощность дозы в H+1 до {@link #RAIN_H1} Р/ч на 15 кт (оценка между двумя городами),
 * ∝ Y и доле воздушного подрыва; спад — закон Уэя–Вигнера, как у следа ({@link FalloutModel}). У наземного подрыва
 * то, что упало, — его след.
 *
 * @param fireRadiusM радиус зоны пожаров по земле, м; 0 — пожаров нет, нет и дождя
 * @param airShare    доля продуктов деления, оставшихся в облаке (1 − доля наземного подрыва)
 */
public record BlackRainModel(double yieldKt, double fireRadiusM, double windDirRad, double windSpeedMps, double airShare) {
    /** Во сколько раз часы шторма быстрее настоящих. */
    public static final double COMPRESSION = 15;
    /** Дождь над пожарами начинается и кончается, настоящие секунды после подрыва. */
    public static final double START_S = 20 * 60, END_S = START_S + 90 * 60;
    /** Сколько облако дождит, уходя по ветру, настоящие секунды. */
    public static final double DRIFT_S = 2 * 3600;
    /** На сколько полоса расширяется на метр пути по ветру. */
    public static final double SPREAD = 0.2;
    /** Мощность дозы на земле под дождём в H+1 у зоны пожаров, Р/ч, на 15 кт воздушного подрыва. */
    public static final double RAIN_H1 = 2;
    private static final double MIN_WIND = 0.5;

    /**
     * Когда в точке начинается дождь, секунды часов шторма после подрыва; бесконечность — точка вне полосы.
     * Идёт он {@link #durationSeconds} секунд.
     */
    public double startSeconds(double x, double z) {
        if (fireRadiusM <= 0) return Double.POSITIVE_INFINITY;
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        double cross = -x * Math.sin(windDirRad) + z * Math.cos(windDirRad);
        double past = Math.max(0, down - fireRadiusM);
        boolean inside = down < 0 ? down * down + cross * cross <= fireRadiusM * fireRadiusM
                : Math.abs(cross) <= fireRadiusM + SPREAD * down;
        if (!inside || past > wind() * DRIFT_S) return Double.POSITIVE_INFINITY;
        return (START_S + past / wind()) / COMPRESSION;
    }

    /** Сколько секунд часов шторма дождь идёт в точке. */
    public static double durationSeconds() {
        return (END_S - START_S) / COMPRESSION;
    }

    /** Идёт ли дождь в точке через {@code seconds} секунд часов шторма. */
    public boolean raining(double x, double z, double seconds) {
        double start = startSeconds(x, z);
        return seconds >= start && seconds < start + durationSeconds();
    }

    /** Когда дождь кончается в самой дальней точке полосы, секунды часов шторма. */
    public double endSeconds() {
        return fireRadiusM <= 0 ? 0 : (END_S + DRIFT_S) / COMPRESSION;
    }

    /**
     * Мощность дозы от вымытого дождём на землю в H+1, Р/ч ({@code seconds} — часы шторма): с начала дождя в точке; у
     * зоны пожаров полная, по ветру спадает e-кратно на трети полосы, поперёк — гаусс на половину полуширины.
     */
    public double depositH1(double x, double z, double seconds) {
        if (airShare <= 0 || seconds < startSeconds(x, z)) return 0;
        double down = x * Math.cos(windDirRad) + z * Math.sin(windDirRad);
        double cross = -x * Math.sin(windDirRad) + z * Math.cos(windDirRad);
        double past = Math.max(0, down - fireRadiusM);
        double sigma = 0.5 * (fireRadiusM + SPREAD * Math.max(0, down));
        return RAIN_H1 * yieldKt / 15 * airShare
                * Math.exp(-past / (wind() * DRIFT_S / 3)) * Math.exp(-cross * cross / (2 * sigma * sigma));
    }

    private double wind() {
        return Math.max(windSpeedMps, MIN_WIND);
    }
}
