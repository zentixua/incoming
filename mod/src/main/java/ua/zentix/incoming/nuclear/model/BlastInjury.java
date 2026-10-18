package ua.zentix.incoming.nuclear.model;

/**
 * Ранения ударной волной (Glasstone & Dolan 1977, гл. 12): давление на лёгкие, бросок телом — удар о твёрдое или
 * кувырки по земле, осколки стёкол, завал рухнувшим домом. Вероятности — логнормальные кривые ({@link Fragility}) по
 * таблицам; сколько здоровья это стоит в игре — {@link #lostShare}.
 *
 * <p>Два исхода у каждого: гибель и тяжёлое ранение (или гибель). У каждого облучённого волной своя случайность
 * {@code fate} 0..1: погиб, если она меньше вероятности гибели, а иначе теряет долю здоровья — вероятность тяжёлого
 * ранения, делённая на {@code fate}, не больше {@link #CASUALTY_SHARE}: кто ближе к своему порогу, тот ранен тяжелее, а
 * тяжело раненый остаётся едва живым.
 */
public final class BlastInjury {
    /** Гибель от давления на лёгкие, psi эффективного давления: 1 % при 40, 50 % при 62, ~100 % при 92 (табл. 12.38). */
    public static final double LUNG_DEATH_PSI = 62, LUNG_DEATH_BETA = 0.19;
    /** Тяжёлое повреждение лёгких, psi: порог 12, тяжёлое — 25 (табл. 12.38). */
    public static final double LUNG_INJURY_PSI = 25, LUNG_INJURY_BETA = 0.32;
    /** Гибель от удара телом о твёрдое, м/с: порог 21 фут/с (6,4), 50 % — 54 (16,5), ~100 % — 138 (42) (табл. 12.49). */
    public static final double IMPACT_DEATH = 16.5, IMPACT_DEATH_BETA = 0.40;
    /** Перелом черепа при ударе, м/с: порог 13 фут/с (4,0), 50 % — 18 (5,5), ~100 % — 23 (7,0) (табл. 12.49). */
    public static final double IMPACT_INJURY = 5.5, IMPACT_INJURY_BETA = 0.12;
    /** Ранения от кувырков по открытой земле, м/с начальной скорости: 1 % при 30 фут/с (9,1), 50 % — 75 (22,9) (гл. 12, оценка). */
    public static final double TUMBLE_INJURY = 22.9, TUMBLE_INJURY_BETA = 0.40;
    /**
     * Гибель от кувырков, м/с: данных нет (гл. 12), оценка — во столько же раз выше удара о твёрдое, во сколько у ранений
     * (22,9 против 5,5): 69 м/с.
     */
    public static final double TUMBLE_DEATH = IMPACT_DEATH * TUMBLE_INJURY / IMPACT_INJURY;
    /**
     * Скорость осколков оконного стекла, м/с на √psi: 1,9 psi — 108 фут/с, 3,8 — 168 и 140, 5,0 — 170 (табл. 12.43,
     * в 2–4 м от окна); подгонка 80·√psi фут/с.
     */
    public static final double GLASS_SPEED = 24.4;
    /** Тяжёлое ранение осколком стекла около грамма (пробивает брюшную стенку), м/с: 1 % — 140 фут/с, 50 % — 245 (табл. 12.46). */
    public static final double GLASS_INJURY = 74.7, GLASS_INJURY_BETA = 0.24;
    /**
     * Завал рухнувшим домом из камня: 20 % погибают сразу, ещё 30 % — с ранениями, опасными для жизни (Coburn & Spence
     * 2002, M4 для кладки; оценка по землетрясениям — для ядерных ударов таких данных нет). Кто жив, остаётся под
     * завалом: задыхается, пока не выберется.
     */
    public static final double CRUSH_DEATH = 0.2, CRUSH_INJURY = 0.5;
    /** Тяжело раненый теряет столько здоровья. */
    public static final double CASUALTY_SHARE = 0.95;

    /**
     * Сопротивление тела напору: Cd·A/m человека — 0,6 м² на 70 кг. Сверка — G&D §12.44: манекен в 165 фунтов стоя,
     * спиной к взрыву, при 5,3 psi — наибольшая скорость 21 фут/с (6,4 м/с), всего 22 фута (6,7 м) с кувырками.
     * Мощность там не названа; модель на 20–40 кт у земли — 5,6–6,8 м/с и 6,2–9,1 м.
     */
    public static final double HUMAN_DRAG_PER_KG = 0.6 / 70;
    /** Ширина тела человека, м: тело шире — тяжелее на площадь, его бросает слабее. */
    public static final double HUMAN_WIDTH = 0.6;
    /** Торможение кувырками по земле после разгона, доля g. */
    private static final double TUMBLE_FRICTION = 0.5;
    private static final double G = 9.81, RHO0 = 1.225;
    private static final int STEPS_PER_PHASE = 400;

    private BlastInjury() {}

    /**
     * Эффективное давление на лёгкие, кПа: у стены за спиной — отражённое, лицом к взрыву — с напором торможения
     * (давление плюс скоростной напор), боком и спиной — падающее (табл. 12.38).
     *
     * @param qKpa   скоростной напор вдоль земли, кПа
     * @param facing насколько тело повёрнуто к взрыву: 1 — лицом, 0 — боком и спиной
     */
    public static double effectiveKpa(double kpa, double qKpa, boolean backedByWall, double facing) {
        if (backedByWall) return reflectedKpa(kpa);
        return kpa + qKpa * Math.max(0, Math.min(1, facing));
    }

    /** Отражённое давление при падении по нормали (воздух, γ = 1.4), кПа. */
    public static double reflectedKpa(double kpa) {
        double p0 = BlastModel.P0;
        return 2 * kpa * (7 * p0 + 4 * kpa) / (7 * p0 + kpa);
    }

    public static double lungDeath(double effectiveKpa) {
        return Fragility.probability(BlastModel.psi(effectiveKpa), LUNG_DEATH_PSI, LUNG_DEATH_BETA);
    }

    public static double lungInjury(double effectiveKpa) {
        return Fragility.probability(BlastModel.psi(effectiveKpa), LUNG_INJURY_PSI, LUNG_INJURY_BETA);
    }

    public static double impactDeath(double speed) {
        return Fragility.probability(speed, IMPACT_DEATH, IMPACT_DEATH_BETA);
    }

    public static double impactInjury(double speed) {
        return Fragility.probability(speed, IMPACT_INJURY, IMPACT_INJURY_BETA);
    }

    public static double tumbleDeath(double speed) {
        return Fragility.probability(speed, TUMBLE_DEATH, TUMBLE_INJURY_BETA);
    }

    public static double tumbleInjury(double speed) {
        return Fragility.probability(speed, TUMBLE_INJURY, TUMBLE_INJURY_BETA);
    }

    /** Скорость осколков окна, выбитого давлением {@code kpa}, м/с. */
    public static double glassSpeed(double kpa) {
        return GLASS_SPEED * Math.sqrt(Math.max(0, BlastModel.psi(kpa)));
    }

    public static double glassInjury(double kpa) {
        return Fragility.probability(glassSpeed(kpa), GLASS_INJURY, GLASS_INJURY_BETA);
    }

    /** Вероятность хоть одного из независимых исходов. */
    public static double any(double... p) {
        double none = 1;
        for (double x : p) none *= 1 - x;
        return 1 - none;
    }

    /**
     * Доля здоровья, которую теряет переживший волну: {@code injury / fate}, не больше {@link #CASUALTY_SHARE}
     * (1 — погиб: {@code fate < death}).
     */
    public static double lostShare(double death, double injury, double fate) {
        if (fate < death) return 1;
        return Math.min(CASUALTY_SHARE, Math.max(death, injury) / Math.max(fate, 1e-9));
    }

    /**
     * Бросок тела напором за фронтом: dv/dt = ρ(u − v)²·Cd·A/(2m), ветер u и плотность воздуха за фронтом — по
     * Ренкину–Гюгонио, со спадом (1 − t/T)·e^(−t/T) за положительную фазу T. Тело разгоняется, пока ветер быстрее
     * него, потом тормозит кувырками по земле ({@link #TUMBLE_FRICTION}·g).
     *
     * @param kpa       избыточное давление на фронте (плотность воздуха за ним)
     * @param u0        скорость ветра за фронтом, м/с
     * @param phase     положительная фаза, с
     * @param dragPerKg Cd·A/m тела, м²/кг ({@link #HUMAN_DRAG_PER_KG} у человека); 0 — не бросает
     */
    public static Throw thrown(double kpa, double u0, double phase, double dragPerKg) {
        if (u0 <= 0 || phase <= 0 || dragPerKg <= 0) return Throw.NONE;
        double rhoFront = RHO0 * (7 * BlastModel.P0 + 6 * kpa) / (7 * BlastModel.P0 + kpa);
        double dt = phase / STEPS_PER_PHASE;
        double[] path = new double[STEPS_PER_PHASE + 1], speed = new double[STEPS_PER_PHASE + 1];
        double v = 0, s = 0;
        int n = 1;
        for (int i = 0; i < STEPS_PER_PHASE; i++) {
            double t = (i + 0.5) * dt, f = (1 - t / phase) * Math.exp(-t / phase);
            double rel = u0 * f - v;
            if (rel <= 0) break;
            double a = 0.5 * (RHO0 + (rhoFront - RHO0) * f) * dragPerKg * rel * rel;
            s += (v + 0.5 * a * dt) * dt;
            v += a * dt;
            path[n] = s;
            speed[n] = v;
            n++;
        }
        return new Throw(v, s + v * v / (2 * TUMBLE_FRICTION * G), java.util.Arrays.copyOf(path, n), java.util.Arrays.copyOf(speed, n));
    }

    /**
     * Бросок: наибольшая скорость, весь путь до остановки (м) и разгон — скорость по пройденному пути.
     */
    public record Throw(double peak, double stop, double[] path, double[] speed) {
        public static final Throw NONE = new Throw(0, 0, new double[]{0}, new double[]{0});

        /** Скорость тела, пролетевшего {@code metres}, м/с (0 — дальше, чем его унесёт). */
        public double speedAfter(double metres) {
            if (metres <= 0) return 0;
            double accel = path[path.length - 1];
            if (metres >= accel) {
                double v2 = peak * peak - 2 * TUMBLE_FRICTION * G * (metres - accel);
                return v2 > 0 ? Math.sqrt(v2) : 0;
            }
            int i = java.util.Arrays.binarySearch(path, metres);
            if (i >= 0) return speed[i];
            i = -i - 1;
            double k = (metres - path[i - 1]) / (path[i] - path[i - 1]);
            return speed[i - 1] + k * (speed[i] - speed[i - 1]);
        }
    }
}
