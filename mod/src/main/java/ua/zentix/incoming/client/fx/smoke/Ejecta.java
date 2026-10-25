package ua.zentix.incoming.client.fx.smoke;

import net.minecraft.world.phys.Vec3;

/**
 * Баллистический выброс воронки рыхлого грунта — завеса струй, что поднимается из воронки и падает назад, без Minecraft.
 * Метры, секунды, килограммы. Один закон с комьями-частицами вблизи ({@code BlastEffects.ejecta}): скорость и место
 * вылета каждого элемента, угол, время полёта; масса — одна на оба.
 * <ul>
 * <li><b>Скорость</b> — Housen, Schmidt, Holsapple, «Crater ejecta scaling laws», J. Geophys. Res. 88(B3), 1983: элемент,
 * вылетевший с расстояния x от середины, — v = C₂·√(g·R)·(x/R)^(−1/μ); сухой пористый грунт μ ≈ {@value #MU} (тот же
 * класс мишеней — Housen, Holsapple, Icarus 211, 2011, physics.md [S07]); ⚠ C₂ = {@value #C2} — оценка по их графикам для
 * песка; ⚠ ближе {@value #FROM} R от середины выброса нет (быстрый хвост чувствителен к этой отсечке, его масса — проценты,
 * и закон сам делает его почти прозрачным). Масса — поровну по объёму полусферы радиуса x: доля u = (x/R)³.</li>
 * <li><b>Угол</b> — {@value #ANGLE_DEG}° ± {@value #SPREAD_DEG}° к горизонту, равномерно (⚠ оценка кода: угловой диаграммы
 * контактного заряда в источниках нет, physics.md), азимут — равномерно; вылет — разом в миг взрыва (раскопка длится
 * миллисекунды–десятки миллисекунд против секунд полёта).</li>
 * <li><b>Полёт</b> — без сопротивления, g = 9,81 (⚠ у зёрен мельче миллиметра на скорости в десятки м/с оно уже
 * заметно: завеса чуть выше и дальше настоящей, насколько — не оценено: закона сопротивления завесы зёрен в моде нет);
 * элемент, вернувшийся на высоту вылета, — на земле.</li>
 * <li><b>Свет</b> — завесу видно по зёрнам ({@link BlastTarget.Matter#extinction}): комья в десятки сантиметров ослабляют
 * свет в сотни раз слабее на килограмм, их доля ({@link BlastTarget.Matter#clods}) — частицами вблизи, а не завесой.</li>
 * </ul>
 * Завеса в кадре — {@value #JETS} струй по кругу (каждая — свой азимут и свой угол из ±{@value #SPREAD_DEG}°), у
 * каждой — {@value #BANDS} отрезка по скорости: вещество одного мига с разной скоростью лежит на кривой, отрезки — её
 * хорды (⚠ подача: настоящая завеса сплошная по азимуту, струи — её неровность, как на съёмках разрывов в грунте).
 * Сечение струи — разлёт её угла ±{@value #SPREAD_DEG}° на пройденном пути, не тоньше четверти радиуса воронки.
 */
public final class Ejecta {
    /** Закон Housen и др. 1983 для сухого грунта: μ и C₂ (оценка). */
    public static final double MU = 0.41, C2 = 0.55;
    /** Ближе этой доли радиуса воронки от середины выброса нет (⚠ оценка). */
    public static final double FROM = 0.2;
    /** Угол вылета к горизонту и его разброс в обе стороны, градусы (⚠ оценка). */
    public static final double ANGLE_DEG = 45, SPREAD_DEG = 10;
    static final double G = 9.80665;
    /** Струй завесы по кругу и отрезков у каждой. */
    public static final int JETS = 8, BANDS = 3;

    /** Радиус воронки, м; баллистический выброс, кг (без пыли, что ушла в воздух); из него — зёрна завесы, кг. */
    public final double radius, mass, grains;
    /** Скорость у края воронки и наибольшая, м/с. */
    public final double vRim, vMax;
    /** Доля объёма у отсечки: (FROM)³. */
    final double uMin;
    /** Когда на земле девять десятых выброса и весь, с. */
    public final double settled, life;

    /**
     * @param radius радиус воронки, м
     * @param mass   баллистический выброс, кг: масса воронки без пыли, что ушла в воздух
     * @param clods  доля выброса комьями (не завеса)
     */
    public Ejecta(double radius, double mass, double clods) {
        this.radius = Math.max(0, radius);
        this.mass = Math.max(0, mass);
        this.grains = this.mass * (1 - Math.clamp(clods, 0, 1));
        vRim = C2 * Math.sqrt(G * this.radius);
        uMin = FROM * FROM * FROM;
        vMax = speed(uMin);
        settled = flight(speed(uMin + 0.1 * (1 - uMin)), Math.toRadians(ANGLE_DEG));
        life = flight(vMax, Math.toRadians(ANGLE_DEG + SPREAD_DEG));
    }

    /** Приёмник кусков: место вылета (м, мир) и скорость, м/с. */
    public interface Pieces {
        void piece(Vec3 at, Vec3 velocity);
    }

    /**
     * {@code n} кусков этого выброса с грани в {@code origin}, наружу — {@code up} (у воронки — вверх, у лица пробитой плиты —
     * против оси дыры): тот же закон, что у завесы — доля объёма u равномерно, место вылета R·u^⅓ на грани, скорость
     * {@link #speed}, угол к грани {@value #ANGLE_DEG}° ± {@value #SPREAD_DEG}°, азимут равномерно; кусок вылетает в полуметре
     * над гранью (не в её блоке). Тяжесть — у частицы (вниз мира).
     */
    public void pieces(Vec3 origin, Vec3 up, int n, java.util.function.DoubleSupplier random, Pieces out) {
        Vec3 u0 = up.normalize();
        Vec3[] across = across(u0);
        for (int i = 0; i < n; i++) {
            double u = uMin + (1 - uMin) * random.getAsDouble();
            double v = speed(u);
            double el = Math.toRadians(ANGLE_DEG + (random.getAsDouble() * 2 - 1) * SPREAD_DEG), az = random.getAsDouble() * Math.PI * 2;
            Vec3 side = across[0].scale(Math.cos(az)).add(across[1].scale(Math.sin(az)));
            out.piece(origin.add(side.scale(from(u))).add(u0.scale(0.5)), side.scale(Math.cos(el) * v).add(u0.scale(Math.sin(el) * v)));
        }
    }

    /** Два единичных вектора поперёк единичного {@code n} и друг друга. */
    public static Vec3[] across(Vec3 n) {
        Vec3 ref = Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
        Vec3 a = n.cross(ref).normalize();
        return new Vec3[]{a, n.cross(a).normalize()};
    }

    /** Скорость элемента доли объёма u (от середины воронки), м/с: v = v_края·u^(−1/(3μ)). */
    public double speed(double u) {
        return vRim * Math.pow(Math.max(u, uMin), -1 / (3 * MU));
    }

    /** Откуда он вылетел, м от середины: x = R·u^⅓. */
    public double from(double u) {
        return Math.cbrt(Math.max(u, 0)) * radius;
    }

    /** Время полёта элемента со скоростью v под углом el (рад) к горизонту до высоты вылета, с. */
    static double flight(double v, double el) {
        return 2 * v * Math.sin(el) / G;
    }

    /** Доля объёма, медленнее которой элементы под углом el к возрасту t уже на земле (больше 1 — все в воздухе). */
    double landed(double t, double el) {
        if (t <= 0 || vRim <= 0) return Double.POSITIVE_INFINITY;
        double v = G * t / (2 * Math.sin(el));
        return Math.pow(v / vRim, -3 * MU);
    }

    /** Доля выброса под углом el, что в возрасте t ещё в воздухе (масса — поровну по u от {@link #uMin} до 1). */
    public double aloft(double t, double el) {
        double u = Math.min(1, landed(t, el));
        return u <= uMin ? 0 : (u - uMin) / (1 - uMin);
    }

    /** Приёмник струй: отрезок от середины − a до середины + a (от точки взрыва, м; y — вверх), круглое сечение σ, зёрен кг. */
    public interface Jets {
        void jet(double x, double y, double z, double ax, double ay, double az, double sigma, double kg);
    }

    /** Угол струи k (рад) по зерну взрыва: равномерно в ±{@link #SPREAD_DEG}° около {@link #ANGLE_DEG}°. */
    static double elevation(long seed, int k) {
        return Math.toRadians(ANGLE_DEG + SPREAD_DEG * (2 * unit(mix(seed + 0x2545F4914F6CDD1DL * (k + 1))) - 1));
    }

    /** Азимут струи k (рад): равномерно по кругу с поворотом по зерну. */
    static double azimuth(long seed, int k) {
        return 2 * Math.PI * (k + unit(mix(seed ^ 0x9E3779B97F4A7C15L))) / JETS;
    }

    /** Границы отрезков по доле объёма: равно по логарифму от {@link #uMin} до 1 (скорость — степень доли). */
    double bound(int b) {
        return Math.pow(uMin, 1 - (double) b / BANDS);
    }

    /**
     * Струи завесы в возрасте t: у каждой — отрезки по скорости от элементов, что ещё в воздухе; зёрна отрезка — его доля
     * объёма ({@link #grains} / {@link #JETS} на струю). Вернувшееся на землю в завесу не идёт. Сумма зёрен в воздухе —
     * {@link #grainsAloft}.
     */
    public void jets(double t, long seed, Jets out) {
        if (t <= 0 || grains <= 0 || radius <= 0) return;
        double drop = 0.5 * G * t * t, spread = Math.toRadians(SPREAD_DEG) / Math.sqrt(3);
        for (int k = 0; k < JETS; k++) {
            double el = elevation(seed, k), az = azimuth(seed, k), up = Math.sin(el), side = Math.cos(el);
            double cx = Math.cos(az), cz = Math.sin(az), last = Math.min(1, landed(t, el));
            for (int b = 0; b < BANDS; b++) {
                double ua = bound(b), ub = Math.min(bound(b + 1), last);
                if (ub <= ua) continue;
                // концы отрезка: элементы долей ua (быстрый) и ub (медленный): место вылета + путь по прямой − падение
                double va = speed(ua), vb = speed(ub);
                double ra = from(ua) + va * t * side, ya = va * t * up - drop, rb = from(ub) + vb * t * side, yb = vb * t * up - drop;
                double mx = 0.5 * (ra + rb), my = Math.max(0, 0.5 * (ya + yb)), hx = 0.5 * (ra - rb), hy = 0.5 * (ya - yb);
                double sigma = Math.max(spread * speed(Math.sqrt(ua * ub)) * t, 0.25 * radius);
                double kg = grains / JETS * (ub - ua) / (1 - uMin);
                out.jet(mx * cx, my, mx * cz, hx * cx, hy, hx * cz, sigma, kg);
            }
        }
    }

    /** Зёрна в воздухе в возрасте t, кг: то же, что кладут {@link #jets} (сумма по струям). */
    public double grainsAloft(double t, long seed) {
        if (t <= 0) return grains;
        double sum = 0;
        for (int k = 0; k < JETS; k++) sum += grains / JETS * aloft(t, elevation(seed, k));
        return sum;
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
