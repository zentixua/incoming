package ua.zentix.incoming.nuclear.model;

/**
 * Что волна срывает с земли в миг прихода и как сильно бросает — без Minecraft (метры, секунды). Так фронт обычного
 * взрыва и виден на траве: кольцо сорванных травинок, дёрна и листьев бежит со скоростью фронта (на снимках —
 * пыль, трава и листья, сорванные в миг прихода).
 * <ul>
 * <li><b>Срыв</b> — закон срыва пыли {@link DustModel}: динамическая скорость u* пограничного слоя сразу за фронтом
 * (пластина Шлихтинга, Gaj &amp; Small 1991, ур. 7, {@link DustModel#friction} на ближайших 6 см, где закон пластины ещё
 * годен) выше порога поверхности: дёрн и всё на нём — {@link DustModel.Surface#COVERED} (3 м/с, Gaj &amp; Small §2.2).
 * Ветер — Ренкин–Гюгонио за фронтом × √доли напора вдоль земли. ⚠ Порог — грунта под травой, а не самих травинок и
 * листьев: своих чисел о том, когда волна срывает траву, нет; выходит около 1 psi.</li>
 * <li><b>Бросок</b> — лёгкую пластину (клок травы, лист) разгоняет квадратичное сопротивление, как у кусков
 * ({@code Fragments}): dv/dt = (g/v_t²)·|u − v|·(u − v), ветер за фазой по Фридлендеру u = U(1 − x)e^(−x). У обычного
 * взрыва фаза — миллисекунды, меньше тика игры, поэтому бросок считается здесь мелким шагом, а кусок вылетает уже с
 * этой скоростью в тихий воздух. Разрежения за фазой (обратного ветра) нет: Фридлендер за фазой у обычного заряда
 * его завышает (настоящее разрежение слабее и длиннее), а у клока, брошенного почти со скоростью ветра, обратный ветер
 * Фридлендера разворачивал бы его к воронке (⚠ приближение).</li>
 * </ul>
 */
public final class BlastLitter {
    /** Ближе к фронту закон пластины не годен, м ({@link DustModel#plate}). */
    static final double NEAREST_M = 0.06;
    /** Шагов интеграла броска на положительную фазу. */
    private static final int STEPS = 400;
    static final double G = 9.81;

    private BlastLitter() {}

    /** Динамическая скорость у земли сразу за фронтом давления {@code kpa}, м/с ({@code along} — доля напора вдоль земли). */
    public static double friction(double kpa, double along) {
        return DustModel.friction(BlastModel.windSpeed(kpa) * Math.sqrt(Math.max(0, along)), NEAREST_M);
    }

    /** Срывает ли фронт давления {@code kpa} то, что лежит и растёт на поверхности с порогом {@code threshold} м/с. */
    public static boolean tears(double kpa, double along, double threshold) {
        return friction(kpa, along) > threshold;
    }

    /** Срывает ли фронт траву, дёрн и листья ({@link DustModel.Surface#COVERED}). */
    public static boolean tearsCover(double kpa, double along) {
        return tears(kpa, along, DustModel.Surface.COVERED.threshold());
    }

    /**
     * Скорость, с которой ветер за фронтом бросает лёгкую пластину со скоростью падения {@code terminal} м/с: ветер сразу
     * за фронтом {@code windMs}, положительная фаза {@code tPlus} с. Пластина стартует в покое, м/с вдоль ветра.
     */
    public static double kick(double windMs, double tPlus, double terminal) {
        if (!(windMs > 0) || !(tPlus > 0) || !(terminal > 0)) return 0;
        double k = G / (terminal * terminal), dt = tPlus / STEPS, v = 0;
        for (int i = 0; i < STEPS; i++) {
            double x = (i + 0.5) / STEPS, u = windMs * (1 - x) * Math.exp(-x), rel = u - v;
            // полунеявный шаг: у тяжёлого ветра сопротивление за шаг сравнимо со скоростью
            v += k * Math.abs(rel) * rel * dt / (1 + 2 * k * Math.abs(rel) * dt);
        }
        return v;
    }
}
