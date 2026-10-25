package ua.zentix.incoming.client.fx.smoke;

/**
 * Тёплое облако одним термиком Мортона–Тейлора–Тёрнера (Proc. R. Soc. A 234, 1956; уравнения — в форме Bhamidipati &amp;
 * Woods, JFM 2023, ур. 2.1–2.3), без Minecraft: сфера радиуса R поднимается со скоростью W, подсасывая воздух через
 * поверхность со скоростью αW (α = {@value CloudModel#ALPHA}), ускоряется плавучестью g', а в устойчивом воздухе (частота
 * Брента–Вяйсяля N) теряет её: d(R³)/dt = 3α·R²W, d(R³W)/dt = R³g', d(R³g')/dt = −N²·R³W. После вершины облако стоит на ней,
 * уходя с ветром на своей высоте; турбулентность воздуха (Паскуилл–Гиффорд, формулы Бриггса для открытой местности, путь
 * ветра x = u·t) расплывает его с первой секунды ({@link #sigmaH}). Один закон у облака взрыва ({@link CloudModel}) и облака старта ({@link LaunchCloud}).
 * Метры, секунды; высоты — от точки начала (центр — от половины начального радиуса: на земле облако — полусфера).
 */
public final class Thermal {
    /** Шаг интегрирования подъёма, с. */
    static final double DT = 0.05;

    public final CloudModel.Air air;
    /** Плавучесть всего тепла B = g·Q/(ρ·c_p·T), м⁴/с². */
    public final double buoyancy;
    /** Начальный радиус, м. */
    public final double r0;
    /** Подъём по шагам {@link #DT}: высота центра, радиус, скорость, g'; снос по x и z. */
    private final double[] z, r, w, gp, dx, dz;
    /** Наибольшая скорость подъёма, м/с. */
    public final double maxRise;
    /** Наибольшая высота центра и время, когда она достигнута, с; радиус там. */
    public final double topZ, topSeconds, topR;

    /** Плавучесть тепла heat, Дж: B = g·Q/(ρ·c_p·T), м⁴/с². */
    public static double buoyancy(double heat) {
        return CloudModel.G * heat / (CloudModel.RHO * CloudModel.CP * CloudModel.T_AIR);
    }

    /**
     * Термик плавучести buoyancy (м⁴/с²), что начинается с g' = g0 (объём B/g0), но не мельче радиуса rMin, м: шар мельче блока
     * не различим, а R = 0 делил бы на ноль.
     */
    public Thermal(double buoyancy, double g0, double rMin, CloudModel.Air air) {
        this.air = air;
        this.buoyancy = buoyancy;
        // без тепла (пыль, поднятая не жаром) — шар не всплывает: g' = 0
        double volume = buoyancy > 0 && g0 > 0 ? buoyancy / g0 : 0;
        if (buoyancy <= 0) g0 = 0;
        r0 = Math.max(rMin, Math.cbrt(3 * volume / (4 * Math.PI)));
        int n = (int) Math.ceil(Math.PI / air.n() / DT) + 1;
        z = new double[n];
        r = new double[n];
        w = new double[n];
        gp = new double[n];
        dx = new double[n];
        dz = new double[n];
        // MTT в переменных u = R³, m = R³W, f = R³g'; центр — от половины радиуса над землёй (наземный шар — полусфера)
        double u = r0 * r0 * r0, m = 0, f = u * g0, h = 0.5 * r0, x = 0, y = 0;
        double[] k1 = new double[4], k2 = new double[4], k3 = new double[4], k4 = new double[4];
        for (int i = 0; i < n; i++) {
            double rr = Math.cbrt(u);
            z[i] = h;
            r[i] = rr;
            w[i] = m / u;
            gp[i] = f / u;
            dx[i] = x;
            dz[i] = y;
            // РК4 по (u, m, f, h)
            rate(u, m, f, k1);
            rate(u + 0.5 * DT * k1[0], m + 0.5 * DT * k1[1], f + 0.5 * DT * k1[2], k2);
            rate(u + 0.5 * DT * k2[0], m + 0.5 * DT * k2[1], f + 0.5 * DT * k2[2], k3);
            rate(u + DT * k3[0], m + DT * k3[1], f + DT * k3[2], k4);
            u += DT / 6 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]);
            m += DT / 6 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1]);
            f += DT / 6 * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2]);
            h += DT / 6 * (k1[3] + 2 * k2[3] + 2 * k3[3] + k4[3]);
            double along = air.wind(h) * DT;
            x += air.windX() * along;
            y += air.windZ() * along;
            if (m <= 0 && i > 0) {
                // вершина: дальше облако не поднимается
                for (int j = i + 1; j < n; j++) {
                    z[j] = z[i];
                    r[j] = r[i];
                    w[j] = 0;
                    gp[j] = gp[i];
                    dx[j] = dx[j - 1] + air.windX() * air.wind(z[i]) * DT;
                    dz[j] = dz[j - 1] + air.windZ() * air.wind(z[i]) * DT;
                }
                break;
            }
        }
        int top = 0;
        for (int i = 1; i < n; i++) if (z[i] > z[top]) top = i;
        double fastest = 0;
        for (double v : w) fastest = Math.max(fastest, v);
        maxRise = fastest;
        topZ = z[top];
        topR = r[top];
        topSeconds = top * DT;
    }

    /** Скорости d/dt (u, m, f, h) термика MTT при m = R³W, f = R³g' — в {@code out}. */
    private void rate(double u, double m, double f, double[] out) {
        double rr = Math.cbrt(u);
        double wv = m / u;
        out[0] = 3 * CloudModel.ALPHA * rr * rr * wv;
        out[1] = f;
        out[2] = -air.n() * air.n() * m;
        out[3] = wv;
    }

    /** Номер шага подъёма для возраста t, с (за таблицей — последний). */
    private double step(double t) {
        return Math.max(0, Math.min(z.length - 1.0, t / DT));
    }

    private static double at(double[] a, double s) {
        int i = (int) Math.min(a.length - 2, Math.max(0, Math.floor(s)));
        if (a.length == 1) return a[0];
        double f = Math.min(1, s - i);
        return a[i] + (a[i + 1] - a[i]) * f;
    }

    /** Высота центра в возрасте t, м. */
    public double height(double t) {
        return at(z, step(t));
    }

    /** Радиус термика (верх-хат MTT) в возрасте t, м; дальше вершины — тот же. */
    public double radius(double t) {
        return at(r, step(t));
    }

    /** Скорость подъёма, м/с. */
    public double rise(double t) {
        return Math.max(0, at(w, step(t)));
    }

    /** Снос центра ветром за t, м (x и z). */
    public double driftX(double t) {
        return at(dx, step(t)) + air.windX() * air.wind(topZ) * beyond(t);
    }

    public double driftZ(double t) {
        return at(dz, step(t)) + air.windZ() * air.wind(topZ) * beyond(t);
    }

    /** Сколько секунд возраст t за таблицей подъёма. */
    private double beyond(double t) {
        return Math.max(0, t - (z.length - 1) * DT);
    }

    /** Температура газа термика в возрасте t, K: T = T_a / (1 − g'/g). */
    public double kelvin(double t) {
        return CloudModel.T_AIR / (1 - Math.min(0.9, Math.max(0, at(gp, step(t))) / CloudModel.G));
    }

    /**
     * σ облака по горизонтали в возрасте t, м: термик (сфера R — σ = R/√5) и турбулентность воздуха — с первой секунды, в
     * квадратуре (вихри воздуха и подсос термика перемешивают независимо). Прежде воздух начинал работать только после
     * вершины (2–5 мин у ракеты), и облако всё это время было ровным растущим шаром (жалоба 23.10: «держит форму»).
     * ⚠ Бриггс — для шлейфа (с меандрами, облако одного выброса растёт медленнее в начале) — приближение.
     */
    public double sigmaH(double t) {
        double s0 = radius(t) / Math.sqrt(5);
        double s = CloudModel.spreadH(air.spread(), after(t));
        return Math.sqrt(s0 * s0 + s * s);
    }

    public double sigmaV(double t) {
        double s0 = radius(t) / Math.sqrt(5);
        double s = CloudModel.spreadV(air.spread(), after(t));
        return Math.sqrt(s0 * s0 + s * s);
    }

    /**
     * Путь ветра облака за его возраст t, м: ветер на нынешней высоте (не меньше 1 м/с: в штиль воздух тоже
     * перемешивается) за всё время с выброса.
     */
    private double after(double t) {
        double u = Math.max(1, Math.hypot(air.windX(), air.windZ()) * air.wind(height(t)));
        return u * Math.max(0, t);
    }
}
