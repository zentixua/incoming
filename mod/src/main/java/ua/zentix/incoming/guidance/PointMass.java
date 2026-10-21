package ua.zentix.incoming.guidance;

import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Точка с массой в стандартной атмосфере ({@code ref/formulas.md} §2): m·dv/dt = T − D − m·g, сопротивление
 * D = ½·ρ(h)·v²·CdA·f(M) против скорости относительно воздуха (ветер мира несёт воздух), f(M) — {@link Article#machFactor},
 * тяга — вдоль скорости относительно воздуха (оперённое тело встаёт по потоку), масса убывает расходом топлива.
 * Без Minecraft: тот же закон у снаряда в полёте, у таблицы стрельбы и у проверок паспорта ({@code WeaponSpecTest}).
 * <p>
 * Единицы — СИ: место в метрах мира (блок = метр; высота над морем — над {@link Air#SEA}), скорость — м/с, время — с.
 * Шаг — Рунге–Кутта 4-го порядка; тик (0,05 с) делится на подшаги {@link #SUBSTEPS}: у разгона «Града» (~300 м/с²)
 * ошибка скорости за работу двигателя тогда меньше 0,1 %.
 */
public final class PointMass {
    /** Ускорение свободного падения, м/с². */
    public static final double G = Article.G;
    /** Тик Minecraft, с. */
    public static final double TICK = 0.05;
    /** Подшагов на тик. */
    public static final int SUBSTEPS = 4;

    /** Ветер на высоте y мира, м/с (по x и z). */
    public interface Wind {
        Wind CALM = new Wind() {
            @Override
            public double x(double y) {
                return 0;
            }

            @Override
            public double z(double y) {
                return 0;
            }
        };

        double x(double y);

        double z(double y);

        /** Ветер воздуха мира {@code air} ({@link Air#windX}, {@link Air#windZ}). */
        static Wind of(Air air) {
            return new Wind() {
                @Override
                public double x(double y) {
                    return air.windX(y);
                }

                @Override
                public double z(double y) {
                    return air.windZ(y);
                }
            };
        }
    }

    /** Место, м. */
    public double x, y, z;
    /** Скорость относительно земли, м/с. */
    public double vx, vy, vz;
    /** Масса, кг. */
    public double mass;
    /** Площадь сопротивления до звука C<sub>d</sub>·S, м². */
    public final double cda;

    public PointMass(double cda, double mass) {
        this.cda = cda;
        this.mass = mass;
    }

    public PointMass copy() {
        PointMass p = new PointMass(cda, mass);
        p.x = x;
        p.y = y;
        p.z = z;
        p.vx = vx;
        p.vy = vy;
        p.vz = vz;
        return p;
    }

    /** Скорость относительно земли, м/с. */
    public double speed() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    /**
     * Сопротивление на скорости {@code v} м/с относительно воздуха на высоте {@code h} метров над морем, Н:
     * ½·ρ(h)·v²·CdA·f(M), M = v / a(h).
     */
    public static double drag(double v, double h, double cda) {
        return 0.5 * StandardAtmosphere.density(h) * v * v * cda * Article.machFactor(v / StandardAtmosphere.soundSpeed(h));
    }

    /**
     * Тик полёта ({@link #TICK}) подшагами: тяга {@code thrust} Н вдоль скорости относительно воздуха, расход
     * {@code flow} кг/с, ветер {@code wind}.
     */
    public void tick(double thrust, double flow, Wind wind) {
        double dt = TICK / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) step(dt, thrust, flow, wind);
    }

    /** Шаг {@code dt} секунд (Рунге–Кутта 4-го порядка; масса за шаг убывает на flow·dt). */
    public void step(double dt, double thrust, double flow, Wind wind) {
        double m0 = mass, m1 = Math.max(1e-6, mass - flow * dt / 2), m2 = Math.max(1e-6, mass - flow * dt);
        double[] k1 = accel(x, y, z, vx, vy, vz, m0, thrust, wind);
        double[] k2 = accel(x + vx * dt / 2, y + vy * dt / 2, z + vz * dt / 2,
                vx + k1[0] * dt / 2, vy + k1[1] * dt / 2, vz + k1[2] * dt / 2, m1, thrust, wind);
        double[] k3 = accel(x + (vx + k1[0] * dt / 2) * dt / 2, y + (vy + k1[1] * dt / 2) * dt / 2, z + (vz + k1[2] * dt / 2) * dt / 2,
                vx + k2[0] * dt / 2, vy + k2[1] * dt / 2, vz + k2[2] * dt / 2, m1, thrust, wind);
        double[] k4 = accel(x + (vx + k2[0] * dt / 2) * dt, y + (vy + k2[1] * dt / 2) * dt, z + (vz + k2[2] * dt / 2) * dt,
                vx + k3[0] * dt, vy + k3[1] * dt, vz + k3[2] * dt, m2, thrust, wind);
        // место: x(t+dt) = x + dt·(v1 + 2v2 + 2v3 + v4)/6, где v_i — скорости в точках метода
        double v2x = vx + k1[0] * dt / 2, v2y = vy + k1[1] * dt / 2, v2z = vz + k1[2] * dt / 2;
        double v3x = vx + k2[0] * dt / 2, v3y = vy + k2[1] * dt / 2, v3z = vz + k2[2] * dt / 2;
        double v4x = vx + k3[0] * dt, v4y = vy + k3[1] * dt, v4z = vz + k3[2] * dt;
        x += dt * (vx + 2 * v2x + 2 * v3x + v4x) / 6;
        y += dt * (vy + 2 * v2y + 2 * v3y + v4y) / 6;
        z += dt * (vz + 2 * v2z + 2 * v3z + v4z) / 6;
        vx += dt * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]) / 6;
        vy += dt * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1]) / 6;
        vz += dt * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2]) / 6;
        mass = m2;
    }

    /** Ускорение, м/с²: тяга и сопротивление вдоль скорости относительно воздуха, тяжесть вниз. */
    private double[] accel(double px, double py, double pz, double ux, double uy, double uz, double m, double thrust, Wind wind) {
        double ax = ux - wind.x(py), ay = uy, az = uz - wind.z(py);
        double v = Math.sqrt(ax * ax + ay * ay + az * az);
        if (v < 1e-9) return new double[]{0, -G, 0};
        double f = (thrust - drag(v, py - Air.SEA, cda)) / (m * v);
        return new double[]{ax * f, ay * f - G, az * f};
    }
}
