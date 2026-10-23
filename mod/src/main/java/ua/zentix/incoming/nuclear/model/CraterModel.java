package ua.zentix.incoming.nuclear.model;

/**
 * Видимая воронка наземного подрыва: радиус 23·Y^0.3 м, глубина 7.7·Y^0.3 м (сухой грунт),
 * профиль — параболоид, вокруг — вал выброса.
 */
public final class CraterModel {
    /**
     * Грунт под эпицентром. Влажность — насыщение водой, а не вид грунта: сухой песок — эталонная среда кривых воронок
     * (TM 23-200: сухой песок 1,0, насыщенный грунт 1,5, гранит и песчаник 0,8); Glasstone и Dolan 1977, §6.09:
     * «в грунте, насыщенном водой, радиус заметно больше».
     */
    public enum Soil {
        /** Сухой грунт: почва, песок, гравий, щебень (рыхлое и сыпучее выше уровня воды). */
        DRY(1.0),
        /** Скала. */
        ROCK(0.7),
        /** Грунт, насыщенный водой (дно под водой). */
        WET(1.3);

        final double factor;

        Soil(double factor) {
            this.factor = factor;
        }
    }

    /** Диаметр воронки заряда на ∛W, м/кг^⅓, и её глубина — доля диаметра ({@link #chargeRadius}). */
    public static final double CHARGE_D = 0.65, CHARGE_DEPTH = 0.25;
    /** Высота вала на кромке — доля глубины. */
    private static final double RIM = 0.25;
    /** Внутренний склон вала начинается с этой доли радиуса. */
    private static final double RIM_INNER = 0.8;

    private CraterModel() {
    }

    public static double radius(double yieldKt, Soil soil) {
        return 23 * Math.pow(yieldKt, 0.3) * soil.factor;
    }

    public static double depth(double yieldKt, Soil soil) {
        return 7.7 * Math.pow(yieldKt, 0.3) * soil.factor;
    }

    /** Глубина чаши на горизонтальном расстоянии r от эпицентра, м (0 за радиусом). */
    public static double profileDepth(double r, double yieldKt, Soil soil) {
        return bowl(r, radius(yieldKt, soil), depth(yieldKt, soil));
    }

    /**
     * Высота вала над исходной поверхностью, м: растёт от 0.8 радиуса к кромке до 0.25 глубины,
     * снаружи квадратично сходит на нет к двум радиусам. Итоговая поверхность = вал − чаша.
     */
    public static double rimHeight(double r, double yieldKt, Soil soil) {
        return rim(r, radius(yieldKt, soil), depth(yieldKt, soil));
    }

    /** Чаша-параболоид радиуса {@code rc} и глубины {@code depth}: глубина на расстоянии r, м (0 за радиусом). */
    public static double bowl(double r, double rc, double depth) {
        if (r >= rc) return 0;
        double u = r / rc;
        return depth * (1 - u * u);
    }

    /** Вал вокруг чаши радиуса {@code rc} и глубины {@code depth}, м: тот же закон у ядерной и обычной воронки. */
    public static double rim(double r, double rc, double depth) {
        double peak = RIM * depth;
        if (r <= RIM_INNER * rc || r >= 2 * rc) return 0;
        double u = r < rc ? (r - RIM_INNER * rc) / ((1 - RIM_INNER) * rc) : (2 * rc - r) / rc;
        return peak * u * u;
    }

    /**
     * Видимая воронка заряда {@code tntKg} кг ТНТ на поверхности грунта (контактный взрыв): диаметр {@link #CHARGE_D}·∛W м,
     * глубина — четверть диаметра. Данные (Ambrosini, Luccioni, Danesi, «Craters produced by explosions on the soil
     * surface», Mecánica Computacional XXII, 2003): опыт EMRTC — 250 кг ТНТ на земле, Ø 3,8 м (0,60·∛W); расчёт с центром
     * заряда на уровне земли — 0,61·∛W, заряд, лежащий на земле, — 0,42·∛W; статистика ~200 наземных взрывов Кинни и
     * Грэхема — 0,8·∛W ± 30 %; «глубина обычно около четверти диаметра» (там же). Заряд с заглублением (бомба с
     * замедлением) роет больше — это {@code BuriedBlast}. Грунт — те же множители, что у ядерной ({@link Soil}). Удар
     * корпуса (у шахеда ~1 МДж против 200 МДж заряда) воронку не меняет.
     */
    public static double chargeRadius(double tntKg, Soil soil) {
        return tntKg <= 0 ? 0 : 0.5 * CHARGE_D * Math.cbrt(tntKg) * soil.factor;
    }

    /** Глубина воронки заряда, м: {@link #CHARGE_DEPTH} диаметра. */
    public static double chargeDepth(double tntKg, Soil soil) {
        return 2 * CHARGE_DEPTH * chargeRadius(tntKg, soil);
    }

    /** Объём чаши воронки заряда (параболоид π/2·r²·h), м³: столько грунта выброшено. */
    public static double chargeVolume(double tntKg, Soil soil) {
        double r = chargeRadius(tntKg, soil);
        return Math.PI / 2 * r * r * chargeDepth(tntKg, soil);
    }

    /** Будет ли воронка: подрыв не выше 0.1 максимального радиуса шара наземного взрыва. */
    public static boolean formsCrater(double hobM, double yieldKt) {
        return hobM <= 0.1 * FireballModel.maxRadius(yieldKt, true);
    }
}
