package ua.zentix.incoming.nuclear.model;

/**
 * Огненный шар топлива в баке изделия при ударе (CCPS 1994, «Guidelines for Evaluating the Characteristics of Vapor Cloud
 * Explosions, Flash Fires and BLEVEs»): диаметр D = 5,8·M^⅓ м, горит t = 0,45·M^⅓ с (M — кг топлива, до 30 т), с
 * поверхности излучает 200–350 кВт/м² — середина {@link #SURFACE_POWER}. Шаром горит не всё топливо: доля
 * {@link #POOL_SHARE} остаётся в воронке. Один закон у сервера (что шар поджигает) и у картинки и звука клиента
 * ({@code CloudModel}).
 */
public final class FuelFireball {
    /** Излучение поверхности шара, Вт/м²: середина 200–350 кВт/м² (CCPS). */
    public static final double SURFACE_POWER = 275e3;
    /** Джоулей в калории: световой импульс поджига — в кал/см², как у ядерки ({@link ThermalModel#IGNITE}). */
    private static final double J_PER_CAL_CM2 = 4.1868e4;
    /**
     * Доля топлива к удару, что остаётся в воронке и догорает там, а не разлетается брызгами в огненный шар: опыты Sandia с
     * крылом С-141 в масштабе ~1/42, бьющим в песок с глиной (Tieszen, Attaway, «Fuel dispersal in high-speed aircraft/soil
     * impact scenarios», SAND95-2529, 1996, OSTI 188568), — в воронке от почти нуля при ударе по касательной до 25–50 % при
     * крутом. Шахед бьёт под 60°, ракета под 45° — круто; взят нижний край: у опытов не было заряда, а взрыв БЧ рядом с баком
     * выбрасывает топливо из воронки (⚠ оценка внутри диапазона). Шар — остальное ({@link #ballKg}).
     */
    public static final double POOL_SHARE = 0.25;

    private FuelFireball() {}

    /** Сколько топлива из {@code fuelKg} кг к удару сгорает шаром, кг: всё, кроме доли воронки ({@link #POOL_SHARE}). */
    public static double ballKg(double fuelKg) {
        return Math.max(0, fuelKg) * (1 - POOL_SHARE);
    }

    /** Диаметр шара из {@code fuelKg} кг топлива в шаре ({@link #ballKg}), м. */
    public static double diameter(double fuelKg) {
        return fuelKg <= 0 ? 0 : 5.8 * Math.cbrt(fuelKg);
    }

    /** Сколько горит шар из {@code fuelKg} кг топлива в шаре, с. */
    public static double seconds(double fuelKg) {
        return fuelKg <= 0 ? 0 : 0.45 * Math.cbrt(fuelKg);
    }

    /**
     * Световой импульс на поверхность внутри шара из {@code fuelKg} кг топлива в шаре, кал/см²: излучение поверхности ×
     * время горения (поверхность в шаре видит пламя со всех сторон — оценка сверху для граней, повёрнутых от центра).
     * Место в мире, которое шар накрыл не всё горение, берёт меньше — {@link Sweep#exposure}.
     */
    public static double exposure(double fuelKg) {
        return SURFACE_POWER * seconds(fuelKg) / J_PER_CAL_CM2;
    }

    /**
     * Радиус шара диаметра {@code d}, м, что горит {@code s} с, в возрасте t, с (0 — погас или ещё нет): растёт к D/2 за
     * первую пятую горения (⚠ оценка формы).
     */
    public static double radius(double d, double s, double t) {
        return t >= 0 && t < s ? 0.5 * d * Math.min(1, Math.sqrt(t / (0.2 * s))) : 0;
    }

    /**
     * Середина шара радиуса r, растущего от точки взрыва на высоте {@code hob} м над поверхностью, над этой точкой, м: у
     * наземного — полусфера (центр видимой части — на 0,45 радиуса над землёй), у разрыва в воздухе шар свободный — центр в
     * точке взрыва; между — по доле радиуса, что уходит в землю (⚠ оценка формы: шар, срезанный землёй, к полусфере —
     * линейно по высоте подрыва). Тот же закон у шара продуктов детонации.
     */
    public static double onGround(double r, double hob) {
        return r <= 0 ? 0 : 0.45 * r * Math.clamp(1 - hob / r, 0, 1);
    }

    /**
     * Всплытие горящего шара диаметра {@code d}, что горит {@code s} с, к возрасту t, м: от земли к середине 0,75·D в
     * конец горения (CCPS) — ровно за горение (⚠ оценка формы: CCPS даёт только конечную высоту).
     */
    public static double lift(double d, double s, double t, double hob) {
        if (d <= 0) return 0;
        return (0.75 * d - onGround(0.5 * d, hob)) * Math.min(1, t / Math.max(s, 1e-6));
    }

    /** Высота середины шара над точкой взрыва в возрасте t, м (0 — шара нет): {@link #onGround} его радиуса и {@link #lift}. */
    public static double height(double d, double s, double t, double hob) {
        double r = radius(d, s, t);
        return r <= 0 ? 0 : onGround(r, hob) + lift(d, s, t, hob);
    }

    /** Шагов горения в {@link Sweep}. */
    static final int SWEEP_STEPS = 24;

    /**
     * Путь шара за горение — где он и какой по шагам ({@link #radius}, {@link #height}, снос ветром на высоте его середины:
     * u(z) = u₁₀·(z/10)^α, как у облака) — и что он даёт месту рядом. Один шар у картинки клиента ({@code CloudModel}: тот
     * же рост и подъём; снос там — термик облака, у которого шар — середина, с ним этот расходится меньше метра) и у
     * поджига сервера ({@code Craters.ignite}). Метры от точки взрыва.
     *
     * @param x  середина шара по шагам, по x
     * @param y  высота середины
     * @param z  по z
     * @param r  радиус (0 — шага нет)
     * @param dt шаг, с
     */
    public record Sweep(double[] x, double[] y, double[] z, double[] r, double dt) {
        /**
         * Шар из {@code ballKg} кг топлива ({@link #ballKg}) на высоте {@code hob} над поверхностью, ветер на 10 м
         * {@code windX}, {@code windZ} м/с, показатель профиля ветра {@code alpha}.
         */
        public static Sweep of(double ballKg, double hob, double windX, double windZ, double alpha) {
            double d = diameter(ballKg), s = seconds(ballKg), dt = s / SWEEP_STEPS;
            double[] x = new double[SWEEP_STEPS], y = new double[SWEEP_STEPS], z = new double[SWEEP_STEPS], r = new double[SWEEP_STEPS];
            double along = 0;
            for (int i = 0; i < SWEEP_STEPS && s > 0; i++) {
                double t = (i + 0.5) * dt, h = height(d, s, t, hob);
                // снос до середины шага: ветер на высоте середины шара (к началу шага — тот же закон)
                along += Math.pow(Math.max(height(d, s, i * dt, hob) + hob, 1) / 10, alpha) * 0.5 * dt;
                x[i] = windX * along;
                z[i] = windZ * along;
                y[i] = h;
                r[i] = radius(d, s, t);
                along += Math.pow(Math.max(h + hob, 1) / 10, alpha) * 0.5 * dt;
            }
            return new Sweep(x, y, z, r, dt);
        }

        /**
         * Световой импульс в месте (px, py, pz) м от точки взрыва, кал/см²: пока место в шаре — излучение поверхности
         * {@link #SURFACE_POWER} (как {@link FuelFireball#exposure}), вне шара — доля, под которой шар виден с места, (r/d)²
         * (сфера перед гранью, повёрнутой к ней; ⚠ оценка сверху, как и в шаре — наклон грани не берётся). Шар, что
         * поднялся над местом, светит на него и дальше, но всё слабее; место, где шара не было, импульса почти не берёт.
         */
        public double exposure(double px, double py, double pz) {
            double sum = 0;
            for (int i = 0; i < r.length; i++) {
                if (r[i] <= 0) continue;
                double dx = px - x[i], dy = py - y[i], dz = pz - z[i], d2 = dx * dx + dy * dy + dz * dz, r2 = r[i] * r[i];
                sum += d2 <= r2 ? 1 : r2 / d2;
            }
            return SURFACE_POWER * sum * dt / J_PER_CAL_CM2;
        }
    }
}
