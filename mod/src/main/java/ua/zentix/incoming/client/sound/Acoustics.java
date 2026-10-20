package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.nuclear.model.BlastModel;

/**
 * Физика звука без Minecraft (проверяется юнит-тестами): задержка распространения и Доплер.
 * <p>
 * Слушатель в момент {@code now} слышит то, что источник излучил в момент {@code t}, где
 * |p(t) − L| = c·(now − t) — «запаздывающее время». Отсюда и честный Доплер (частота растёт, пока источник
 * приближается), и то, что дальний слушатель ещё слышит мотор, когда снаряд уже взорвался, — ровно до прихода фронта,
 * и то, что сверхзвуковой снаряд не слышно, пока до уха не дошёл его конус Маха.
 */
public final class Acoustics {
    /** Скорость звука: 343 м/с = 17.15 блока за тик. */
    public static final double SPEED = BlastModel.SOUND_SPEED / 20;

    private Acoustics() {}

    /** Траектория источника: положение в момент t (тики, дробные). */
    public interface Path {
        /** Положение в момент t; вне известного отрезка — ближайший край. */
        void at(double t, double[] out);

        /** Самый ранний известный момент. */
        double start();

        /** Скорость, быстрее которой источник на пути не двигался, блоков/тик (оценка сверху). */
        double maxSpeed();
    }

    /** Самый короткий шаг поиска корней, тиков: пары корней ближе этого (касание конуса Маха) — один миг. */
    private static final double MIN_STEP = 0.05;
    /** Шагов поиска не больше. */
    private static final int MAX_STEPS = 400;

    /**
     * Моменты излучения того, что слушатель в точке (lx, ly, lz) слышит сейчас, — корни
     * f(t) = |p(t) − L| − c·(now − t) на известном пути, от позднего к раннему (не больше {@code out.length}).
     * Дозвуковой источник даёт ровно один корень (f растёт: f′ = c − v<sub>r</sub> > 0), если его звук уже дошёл;
     * сверхзвуковой — ни одного вне конуса Маха и пару внутри: поздний (источник уходит, тон ниже) и ранний (звук,
     * изданный на подходе, приходит задом наперёд). Поиск — шагами назад от {@code now}: f меняется не быстрее
     * c + v<sub>max</sub> за тик, поэтому шаг |f| / (c + v<sub>max</sub>) корня не перешагнёт; смена знака —
     * делением пополам.
     *
     * @return сколько корней найдено (0 — звук ещё не дошёл или слушатель вне конуса)
     */
    public static int emissions(Path path, double now, double lx, double ly, double lz, double[] out) {
        double[] p = new double[3];
        double k = path.maxSpeed() + SPEED, start = path.start();
        double t = now, f = distance(path, t, lx, ly, lz, p);
        int n = 0;
        for (int i = 0; i < MAX_STEPS && n < out.length && t > start; i++) {
            double t1 = Math.max(start, t - Math.max(Math.abs(f) / k, MIN_STEP));
            double f1 = f(path, now, lx, ly, lz, t1, p);
            if (f1 == 0 || f1 > 0 != f > 0) out[n++] = refine(path, now, lx, ly, lz, t1, f1, t, p);
            t = t1;
            f = f1;
        }
        // шагов не хватило (скорость сверху завышена: скачок пути между тиками) — дальше ровно по тику, корни — так же
        while (n < out.length && t > start) {
            double t1 = Math.max(start, t - 1);
            double f1 = f(path, now, lx, ly, lz, t1, p);
            if (f1 == 0 || f1 > 0 != f > 0) out[n++] = refine(path, now, lx, ly, lz, t1, f1, t, p);
            t = t1;
            f = f1;
        }
        return n;
    }

    /**
     * Корень f между a (f(a) = fa) и b: делением пополам до тысячной тика, потом хордой (между тиками путь — прямая,
     * f почти линейна — хорда сходится сразу).
     */
    private static double refine(Path path, double now, double lx, double ly, double lz, double a, double fa, double b, double[] p) {
        double fb = f(path, now, lx, ly, lz, b, p);
        for (int i = 0; i < 40; i++) {
            if (fa == 0) return a;
            if (fb == 0) return b;
            double m = b - a > 1.0e-3 ? (a + b) / 2 : b - fb * (b - a) / (fb - fa);
            if (b - a <= 1.0e-9) return m;
            double fm = f(path, now, lx, ly, lz, m, p);
            if (Math.abs(fm) < 1.0e-9) return m;
            if (fm > 0 == fa > 0) {
                a = m;
                fa = fm;
            } else {
                b = m;
                fb = fm;
            }
        }
        return (a + b) / 2;
    }

    private static double f(Path path, double now, double lx, double ly, double lz, double t, double[] p) {
        return distance(path, t, lx, ly, lz, p) - SPEED * (now - t);
    }

    /**
     * Момент излучения того, что слушатель слышит сейчас, — поздний корень ({@link #emissions}): у дозвукового
     * источника он один, у сверхзвукового — звук уходящего источника (ранний, изданный на подходе, звучит задом
     * наперёд — так звук играть нельзя). {@code NaN} — звук ещё не дошёл: раньше прихода ничего не звучит.
     */
    public static double emissionTime(Path path, double now, double lx, double ly, double lz) {
        double[] roots = new double[1];
        return emissions(path, now, lx, ly, lz, roots) > 0 ? roots[0] : Double.NaN;
    }

    private static double distance(Path path, double t, double lx, double ly, double lz, double[] p) {
        path.at(t, p);
        return Math.sqrt(sq(p[0] - lx) + sq(p[1] - ly) + sq(p[2] - lz));
    }

    /**
     * Множитель частоты для движущегося источника и неподвижного слушателя: f' = f · c / (c − v·n),
     * где n — единичный вектор от источника к уху, v — скорость источника (блоков/тик). Без предела: у позднего корня
     * ({@link #emissionTime}) c − v·n > 0 всегда, и сверхзвуковой уходящий источник звучит ниже; отрицательный —
     * звук, изданный на подходе быстрее звука (задом наперёд). Ровно на конусе Маха (c = v·n) — бесконечность:
     * там звук приходит весь разом, N-волной ({@code SonicBoom}).
     */
    public static double doppler(double vx, double vy, double vz, double nx, double ny, double nz) {
        double radial = vx * nx + vy * ny + vz * nz;
        double gap = SPEED - radial;
        if (Math.abs(gap) < 1.0e-9) gap = Math.copySign(1.0e-9, gap);
        return SPEED / gap;
    }

    /** Плавная ступенька 0→1 между a и b. */
    public static double smoothstep(double a, double b, double x) {
        double t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static double sq(double x) {
        return x * x;
    }
}
