package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Полёт без мотора по тикам: тяжесть, квадратичное сопротивление относительно воздуха с плотностью стандартной
 * атмосферы на высоте и ветер мира ({@code WeaponSpec.Ballistic}: «Град» после выгорания, падающая бомба) — шаги
 * {@link #SUBSTEPS} раз за тик ({@link #fly}). Парабола по тикам без сопротивления {@code p(n) = start + n·v0 − g·n(n−1)/2}
 * ({@link #at}) осталась проверке сектора пуска. Расчёт огня ({@link #aim}) ищет скорость и курс схода так, чтобы снаряд при известном ему
 * ветре пришёл в точку.
 */
public final class Ballistics {
    /** Ускорение свободного падения, блоков/тик²: 9.8 м/с² при 20 тиках в секунду. */
    public static final double GRAVITY = 9.8 / 400;
    /** Шагов интегрирования за тик: сопротивление меняется за тик мало, 4 шага — с запасом. */
    static final int SUBSTEPS = 4;
    /** Плотность на уровне моря, кг/м³. */
    private static final double RHO0 = StandardAtmosphere.density(0);

    private Ballistics() {}

    /** Ветер на высоте мира: блоков/тик по x и z. */
    public interface Wind {
        double x(double y);

        double z(double y);

        Wind CALM = uniform(0, 0);

        /** Одинаковый на любой высоте. */
        static Wind uniform(double x, double z) {
            return new Wind() {
                @Override
                public double x(double y) {
                    return x;
                }

                @Override
                public double z(double y) {
                    return z;
                }
            };
        }

        /** Ветер мира с профилем по высоте ({@link Air#speed}). */
        static Wind of(Air air) {
            return new Wind() {
                @Override
                public double x(double y) {
                    return air.windX(y) / 20;
                }

                @Override
                public double z(double y) {
                    return air.windZ(y) / 20;
                }
            };
        }

        /** Ветер у земли мира (10 м над морем) на любой высоте: что знает расчёт огня без зондирования. */
        static Wind ground(Air air) {
            double y = Air.SEA + 10;
            return uniform(air.windX(y) / 20, air.windZ(y) / 20);
        }
    }

    /** Путь по тикам: места на тиках 0..n−1 и скорость на последнем. */
    public static final class Path {
        private final double[] xs, ys, zs;
        private final int n;
        private final Vec3 last;

        Path(double[] xs, double[] ys, double[] zs, int n, Vec3 last) {
            this.xs = xs;
            this.ys = ys;
            this.zs = zs;
            this.n = n;
            this.last = last;
        }

        /** Сколько тиков посчитано. */
        public int ticks() {
            return n - 1;
        }

        /** Место на тике {@code k} (между тиками — по прямой; дальше посчитанного — с последней скоростью). */
        public Vec3 at(double k) {
            if (k <= 0) return new Vec3(xs[0], ys[0], zs[0]);
            if (k >= n - 1) {
                double d = k - (n - 1);
                return new Vec3(xs[n - 1] + last.x * d, ys[n - 1] + last.y * d, zs[n - 1] + last.z * d);
            }
            int i = (int) k;
            double f = k - i;
            return new Vec3(xs[i] + (xs[i + 1] - xs[i]) * f, ys[i] + (ys[i + 1] - ys[i]) * f, zs[i] + (zs[i + 1] - zs[i]) * f);
        }

        /** Тик (дробный), когда путь на спуске проходит высоту {@code y}; не проходит — {@code NaN}. */
        public double landing(double y) {
            for (int i = 1; i < n; i++) {
                if (ys[i] <= y && ys[i - 1] > y && ys[i] < ys[i - 1]) return i - 1 + (ys[i - 1] - y) / (ys[i - 1] - ys[i]);
            }
            return Double.NaN;
        }
    }

    /**
     * Полёт из {@code start} со скоростью {@code v0} (блоков/тик) с сопротивлением {@code drag} (1/блок на уровне моря,
     * {@code WeaponSpec.Ballistic#drag}) и ветром {@code wind}: до {@code maxTicks} тиков или пока путь не уйдёт ниже
     * {@code floor}.
     */
    public static Path fly(Vec3 start, Vec3 v0, double drag, Wind wind, double floor, int maxTicks) {
        int cap = Math.max(2, maxTicks + 1);
        double[] xs = new double[cap], ys = new double[cap], zs = new double[cap];
        double[] s = {start.x, start.y, start.z, v0.x, v0.y, v0.z};
        xs[0] = s[0];
        ys[0] = s[1];
        zs[0] = s[2];
        int n = 1;
        // ниже пола — только на спуске: старт ниже него (цель выше пусковой) путь не обрывает
        while (n < cap && (s[1] >= floor || s[4] > 0)) {
            step(s, drag, wind);
            xs[n] = s[0];
            ys[n] = s[1];
            zs[n] = s[2];
            n++;
        }
        return new Path(xs, ys, zs, n, new Vec3(s[3], s[4], s[5]));
    }

    /**
     * Один тик: {@code s} = {x, y, z, vx, vy, vz}. На каждом шаге ускорение — в начале шага, место — с его половиной
     * (p += v·dt + a·dt²/2): без сопротивления это точная парабола p = start + v0·t − g·t²/2.
     */
    static void step(double[] s, double drag, Wind wind) {
        double dt = 1.0 / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) {
            double rx = s[3] - wind.x(s[1]), ry = s[4], rz = s[5] - wind.z(s[1]);
            double k = drag * StandardAtmosphere.density(s[1] - Air.SEA) / RHO0 * Math.sqrt(rx * rx + ry * ry + rz * rz);
            double ax = -k * rx, ay = -k * ry - GRAVITY, az = -k * rz;
            s[0] += (s[3] + ax * dt / 2) * dt;
            s[1] += (s[4] + ay * dt / 2) * dt;
            s[2] += (s[5] + az * dt / 2) * dt;
            s[3] += ax * dt;
            s[4] += ay * dt;
            s[5] += az * dt;
        }
    }

    /**
     * Расчёт огня: скорость схода под углом возвышения {@code elevation} (°), с которой снаряд из {@code from} при ветре
     * {@code wind} на спуске пройдёт высоту {@code to} над самой точкой {@code to}. Скорость и курс — Ньютоном по
     * промаху в точке падения (производные — разностями); начальное приближение — парабола без сопротивления. Не сошлось
     * (точка выше досягаемого) — лучшее, что нашлось.
     */
    public static Vec3 aim(Vec3 from, Vec3 to, float elevation, double drag, Wind wind) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double d = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double el = Math.toRadians(elevation), az = Math.atan2(dz, dx);
        double denom = 2 * Math.cos(el) * Math.cos(el) * (d * Math.tan(el) - (to.y - from.y));
        double speed = denom > 0 ? Math.sqrt(GRAVITY * d * d / denom) : Math.sqrt(GRAVITY * d);
        int maxTicks = (int) Math.min(6000, 3 * d / Math.max(0.1, speed * Math.cos(el)) + 400);
        double[] miss = miss(from, to, speed, el, az, drag, wind, maxTicks);
        for (int it = 0; it < 12 && Math.hypot(miss[0], miss[1]) > 0.02; it++) {
            double ds = speed * 1e-3, da = 1e-4;
            double[] ms = miss(from, to, speed + ds, el, az, drag, wind, maxTicks), ma = miss(from, to, speed, el, az + da, drag, wind, maxTicks);
            double a = (ms[0] - miss[0]) / ds, b = (ma[0] - miss[0]) / da, c = (ms[1] - miss[1]) / ds, e = (ma[1] - miss[1]) / da;
            double det = a * e - b * c;
            if (Math.abs(det) < 1e-12) break;
            double stepS = -(e * miss[0] - b * miss[1]) / det, stepA = -(-c * miss[0] + a * miss[1]) / det;
            // не дальше половины скорости и 0,2 рад за шаг: вдали от решения производные врут
            stepS = Math.max(-0.5 * speed, Math.min(0.5 * speed, stepS));
            stepA = Math.max(-0.2, Math.min(0.2, stepA));
            speed += stepS;
            az += stepA;
            miss = miss(from, to, speed, el, az, drag, wind, maxTicks);
        }
        return velocity(speed, el, az);
    }

    private static Vec3 velocity(double speed, double el, double az) {
        double h = speed * Math.cos(el);
        return new Vec3(h * Math.cos(az), speed * Math.sin(el), h * Math.sin(az));
    }

    /**
     * Промах по горизонтали (x, z): где путь на спуске проходит высоту цели, минус сама цель. Не доходит до её высоты —
     * от вершины пути, с промахом по высоте в сторону цели (чтобы Ньютон тянул скорость вверх).
     */
    private static double[] miss(Vec3 from, Vec3 to, double speed, double el, double az, double drag, Wind wind, int maxTicks) {
        Path p = fly(from, velocity(speed, el, az), drag, wind, Math.min(to.y, from.y) - 1, maxTicks);
        double k = p.landing(to.y);
        if (Double.isNaN(k)) {
            int top = 0;
            for (int i = 1; i <= p.ticks(); i++) if (p.ys[i] > p.ys[top]) top = i;
            double short_ = to.y - p.ys[top] + 1;
            double dx = to.x - p.xs[top], dz = to.z - p.zs[top], l = Math.max(1e-6, Math.hypot(dx, dz));
            return new double[]{-(dx + dx / l * short_), -(dz + dz / l * short_)};
        }
        Vec3 at = p.at(k);
        return new double[]{at.x - to.x, at.z - to.z};
    }

    /**
     * Время полёта по параболе без сопротивления при угле возвышения {@code elevation}: N(N−1) = 2(d·tgθ − dy)/g. Если цель так
     * близко или так высоко, что выходит меньше {@code minTicks}, — угол круче (шагом 5°, до 80°), как у миномётной
     * траектории ({@link #elevationFor}).
     */
    public static int ticksFor(Vec3 from, Vec3 to, float elevation, int minTicks) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double d = Math.sqrt(dx * dx + dz * dz), dy = to.y - from.y;
        for (float theta = elevation; theta <= 80; theta += 5) {
            int ticks = vacuumTicks(d, dy, theta);
            if (ticks >= minTicks) return ticks;
        }
        return minTicks;
    }

    /** Угол возвышения, с которым по {@link #ticksFor} полёт не короче {@code minTicks}: пакет или круче, до 80°. */
    public static float elevationFor(Vec3 from, Vec3 to, float elevation, int minTicks) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double d = Math.sqrt(dx * dx + dz * dz), dy = to.y - from.y;
        for (float theta = elevation; theta <= 80; theta += 5) {
            if (vacuumTicks(d, dy, theta) >= minTicks) return theta;
        }
        return 80;
    }

    private static int vacuumTicks(double d, double dy, float theta) {
        double rhs = 2 * (d * Math.tan(Math.toRadians(theta)) - dy) / GRAVITY;
        return rhs <= 0 ? 0 : (int) Math.ceil((1 + Math.sqrt(1 + 4 * rhs)) / 2);
    }

    /** Начальная скорость, с которой снаряд без сопротивления из {@code from} будет в {@code to} ровно через {@code ticks}. */
    public static Vec3 launchVelocity(Vec3 from, Vec3 to, int ticks) {
        return to.subtract(from).scale(1.0 / ticks).add(0, GRAVITY * (ticks - 1) / 2, 0);
    }

    /** Точка параболы без сопротивления на тике {@code k}. */
    public static Vec3 at(Vec3 start, Vec3 v0, double k) {
        return start.add(v0.scale(k)).add(0, -GRAVITY * k * (k - 1) / 2, 0);
    }
}
