package ua.zentix.incoming.guidance;

import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Силы вдоль пути управляемого аппарата ({@code ref/formulas.md} §2): m·dv/dt = T − D − m·g·sin γ. Автопилот просит
 * скорость, а получает то, что дают тяга маршевого двигателя (полный газ — предел, малый газ — ноль, тормозов нет),
 * сопротивление воздуха на высоте полёта и тяжесть на наборе и в пике. Без Minecraft: тот же закон у снаряда, у
 * проверки места пусковой и у модели в юнит-тестах.
 * <p>
 * Тяга: винт — T = η·P/v ({@link Article.Propeller#thrust}), на месте не больше, чем на скорости {@link #STATIC_SHARE}
 * от настоящей маршевой (на малой скорости η винта падает: О по импульсной теории для винтов этих моторов); ТРДД — тяга
 * у земли × ρ(h)/ρ₀ (падение тяги с высотой по плотности — приближение; спад по числу Маха не учтён, как и при выводе
 * CdA «Томагавка» в паспорте).
 */
public final class Propulsion {
    /** Тяга винта на месте — как на такой доле настоящей маршевой скорости. */
    static final double STATIC_SHARE = 0.6;
    /** Ускорение свободного падения, м/с². */
    private static final double G = Article.G;

    private Propulsion() {}

    /**
     * Наибольшая тяга маршевого двигателя изделия {@code a} на скорости {@code v} м/с на высоте {@code h} метров над
     * морем, Н; 0 — маршевого нет.
     */
    public static double thrust(Article a, double v, double h) {
        if (a.sustainer() instanceof Article.Propeller p) return p.thrust(Math.max(v, STATIC_SHARE * a.real().cruise()));
        if (a.sustainer() instanceof Article.Turbofan f)
            return f.count() * f.thrust() * StandardAtmosphere.density(h) / StandardAtmosphere.density(0);
        return 0;
    }

    /**
     * Пределы ускорения вдоль пути, блоков/тик²: [малый газ, полный газ] при скорости {@code speed} блоков/тик,
     * тангаже {@code pitch}° (Minecraft: &gt; 0 — нос вниз), на высоте мира {@code y} и массе {@code mass} кг.
     */
    public static double[] limits(Article a, double speed, double pitch, double y, double mass) {
        double v = speed * 20, h = y - Air.SEA;
        double drag = PointMass.drag(v, h, a.cda()), weight = mass * G * Math.sin(Math.toRadians(-pitch));
        double lo = (-drag - weight) / mass, hi = (thrust(a, v, h) - drag - weight) / mass;
        return new double[]{lo / 400, hi / 400};
    }

    /**
     * Скорость после тика: автопилот просит {@code target} блоков/тик и прибавку {@code gain}·(target − v) за тик
     * (не больше {@code maxStep} по модулю), силы дают предел ({@link #limits}). Выше {@code ceiling} — нет: у игровых
     * скоростей пике предел скорости — масштаб, не по физике ({@code strike.FlightScale}).
     */
    public static double next(Article a, double speed, double pitch, double y, double mass, double target, double gain, double maxStep,
                              double ceiling) {
        double[] lim = limits(a, speed, pitch, y, mass);
        double want = Math.max(-maxStep, Math.min(maxStep, (target - speed) * gain));
        double v = speed + Math.max(lim[0], Math.min(lim[1], want));
        return Math.max(0, Math.min(ceiling, v));
    }

    /**
     * Синус наибольшего угла установившегося набора на скорости {@code speed} блоков/тик: (T − D)/(m·g) — столько тяги
     * остаётся сверх сопротивления на подъём веса (0, если не остаётся).
     */
    public static double steadyClimb(Article a, double speed, double y, double mass) {
        double v = speed * 20, h = y - Air.SEA;
        return Math.max(0, Math.min(1, (thrust(a, v, h) - PointMass.drag(v, h, a.cda())) / (mass * G)));
    }
}
