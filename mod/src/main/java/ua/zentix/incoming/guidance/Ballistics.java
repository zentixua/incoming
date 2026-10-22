package ua.zentix.incoming.guidance;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.strike.Article;
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

    /**
     * Сопротивление полёта без мотора: ускорение = coefficient · ρ/ρ₀ · f(M)<sup>mach</sup> · |v|·v (v — относительно
     * воздуха, блоков/тик), ρ — стандартной атмосферы на высоте, f(M) — кривая {@link Article#machFactor} по скорости
     * звука на высоте. Игровое ({@code WeaponSpec.Ballistic#drag}) — без кривой ({@code mach} 0); настоящее изделия —
     * ρ₀·CdA/2m с кривой целиком; шкала полёта ведёт оба числа ({@code WeaponSpec.Airframe#drag}).
     *
     * @param coefficient коэффициент на уровне моря, 1/блок
     * @param mach        показатель кривой f(M): 0 — без неё, 1 — вся
     */
    public record Drag(double coefficient, double mach) {
        /** Игровое: без кривой f(M). */
        public static Drag of(double coefficient) {
            return new Drag(coefficient, 0);
        }

        /** Коэффициент на высоте мира {@code y} при скорости {@code speed} блоков/тик относительно воздуха, 1/блок. */
        public double at(double y, double speed) {
            double h = y - Air.SEA;
            return coefficient * StandardAtmosphere.density(h) / RHO0 * machTerm(h, speed);
        }

        /** f(M)<sup>mach</sup>; без кривой — ровно 1 (игровая траектория не меняется ни в одном бите). */
        private double machTerm(double h, double speed) {
            if (mach == 0) return 1;
            double f = Article.machFactor(speed * 20 / StandardAtmosphere.soundSpeed(h));
            return f == 1 || mach == 1 ? f : Math.pow(f, mach);
        }
    }

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

        /**
         * Тик (дробный), когда путь по горизонтали доходит до дальности точки {@code to} от {@code from} (проекция на
         * направление на неё); не доходит — {@code NaN}.
         */
        public double reaching(Vec3 from, Vec3 to) {
            double ux = to.x - from.x, uz = to.z - from.z, d = Math.sqrt(ux * ux + uz * uz);
            if (d < 1e-9) return 0;
            ux /= d;
            uz /= d;
            double prev = (xs[0] - from.x) * ux + (zs[0] - from.z) * uz;
            for (int i = 1; i < n; i++) {
                double r = (xs[i] - from.x) * ux + (zs[i] - from.z) * uz;
                if (r >= d && prev < d) return i - 1 + (d - prev) / (r - prev);
                prev = r;
            }
            return Double.NaN;
        }

        /**
         * Тик (дробный), когда снаряд приходит к точке {@code to}: на спуске на её высоте, если там он к ней не дальше,
         * чем на дальности точки ({@link #reaching}); иначе — на её дальности (точку на холме или башне выше пути спуска
         * снаряд проходит ещё на подъёме). {@code NaN} — ни того, ни другого.
         */
        public double arrival(Vec3 from, Vec3 to) {
            double down = landing(to.y), across = reaching(from, to);
            if (Double.isNaN(down)) return across;
            if (Double.isNaN(across)) return down;
            return at(down).distanceTo(to) <= at(across).distanceTo(to) + 2 ? down : across;
        }
    }

    /**
     * Полёт из {@code start} со скоростью {@code v0} (блоков/тик) с сопротивлением {@code drag} ({@link Drag}) и ветром
     * {@code wind}: до {@code maxTicks} тиков или пока путь не уйдёт ниже {@code floor}.
     */
    public static Path fly(Vec3 start, Vec3 v0, Drag drag, Wind wind, double floor, int maxTicks) {
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
    static void step(double[] s, Drag drag, Wind wind) {
        double dt = 1.0 / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) {
            double rx = s[3] - wind.x(s[1]), ry = s[4], rz = s[5] - wind.z(s[1]);
            double v = Math.sqrt(rx * rx + ry * ry + rz * rz);
            double k = drag.at(s[1], v) * v;
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
     * {@code wind} проходит точку {@code to}: на её дальности — на её высоте и без отклонения вбок. Скорость и курс —
     * Ньютоном по этим двум промахам (производные — разностями; высота на дальности растёт со скоростью); начальное
     * приближение — парабола без сопротивления. Не сошлось (точка выше досягаемого) — лучшее, что нашлось.
     */
    public static Vec3 aim(Vec3 from, Vec3 to, float elevation, Drag drag, Wind wind) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double d = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double el = Math.toRadians(elevation), az = Math.atan2(dz, dx);
        double denom = 2 * Math.cos(el) * Math.cos(el) * (d * Math.tan(el) - (to.y - from.y));
        double speed = denom > 0 ? Math.sqrt(GRAVITY * d * d / denom) : Math.sqrt(GRAVITY * d);
        int maxTicks = (int) Math.min(6000, 3 * d / Math.max(0.1, speed * Math.cos(el)) + 400);
        double[] miss = miss(from, to, speed, el, az, drag, wind, maxTicks);
        double bestSpeed = speed, bestAz = az, best = Math.hypot(miss[0], miss[1]);
        for (int it = 0; it < 40 && best > 0.02; it++) {
            double ds = speed * 1e-3, da = 1e-4;
            double[] ms = miss(from, to, speed + ds, el, az, drag, wind, maxTicks), ma = miss(from, to, speed, el, az + da, drag, wind, maxTicks);
            double a = (ms[0] - miss[0]) / ds, b = (ma[0] - miss[0]) / da, c = (ms[1] - miss[1]) / ds, e = (ma[1] - miss[1]) / da;
            double det = a * e - b * c;
            if (Math.abs(det) < 1e-12) break;
            double stepS = -(e * miss[0] - b * miss[1]) / det, stepA = -(-c * miss[0] + a * miss[1]) / det;
            // не дальше половины скорости и 0,2 рад за шаг: вдали от решения производные врут
            stepS = Math.max(-0.5 * speed, Math.min(0.5 * speed, stepS));
            stepA = Math.max(-0.2, Math.min(0.2, stepA));
            // не быстрее 10 начальных: точки, которой нет решения, не разгонять без конца
            speed = Math.max(0.05, Math.min(10 * Math.sqrt(GRAVITY * d), speed + stepS));
            az += stepA;
            miss = miss(from, to, speed, el, az, drag, wind, maxTicks);
            double m = Math.hypot(miss[0], miss[1]);
            if (m < best) {
                best = m;
                bestSpeed = speed;
                bestAz = az;
            }
        }
        return velocity(bestSpeed, el, bestAz);
    }

    /** Снаряд из {@code from} со скоростью {@code v0} приходит к {@code to} ({@link Path#arrival}) ближе {@code tolerance}. */
    public static boolean hits(Vec3 from, Vec3 v0, Vec3 to, Drag drag, Wind wind, double tolerance) {
        double d = Math.sqrt(to.subtract(from).horizontalDistanceSqr());
        int maxTicks = (int) Math.min(6000, 3 * d / Math.max(0.05, v0.horizontalDistance()) + 400);
        Path p = fly(from, v0, drag, wind, Math.min(to.y, from.y) - 64, maxTicks);
        double k = p.arrival(from, to);
        return !Double.isNaN(k) && p.at(k).distanceTo(to) <= tolerance;
    }

    private static Vec3 velocity(double speed, double el, double az) {
        double h = speed * Math.cos(el);
        return new Vec3(h * Math.cos(az), speed * Math.sin(el), h * Math.sin(az));
    }

    /**
     * Промах на дальности точки: {высота пути там − высота точки, отклонение вбок (влево — плюс)}. Не долетает до её
     * дальности — высота самой низкой точки пути за вычетом недолёта (растёт со скоростью, как и высота на дальности).
     */
    private static double[] miss(Vec3 from, Vec3 to, double speed, double el, double az, Drag drag, Wind wind, int maxTicks) {
        double dx = to.x - from.x, dz = to.z - from.z, d = Math.max(1e-9, Math.sqrt(dx * dx + dz * dz));
        double ux = dx / d, uz = dz / d;
        Path p = fly(from, velocity(speed, el, az), drag, wind, Math.min(to.y, from.y) - d, maxTicks);
        double k = p.reaching(from, to);
        Vec3 at = p.at(Double.isNaN(k) ? p.ticks() : k);
        double along = (at.x - from.x) * ux + (at.z - from.z) * uz, side = -(at.x - from.x) * uz + (at.z - from.z) * ux;
        double high = at.y - to.y - (Double.isNaN(k) ? d - along : 0);
        return new double[]{high, side};
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
