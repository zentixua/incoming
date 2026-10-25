package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.DustModel;

/**
 * Интегральный термик Мортона–Тейлора–Тёрнера (Proc. R. Soc. A 234, 1956, 1–23, DOI 10.1098/rspa.1956.0011),
 * без Minecraft. Подсос α|W| (α = {@value CloudModel#ALPHA}); тепло теряет плавучесть в устойчивом воздухе с частотой N.
 * Нагрузка частиц добавляет вес и инерцию; выход частиц из газа отличается от осаждения ({@link DustFallout}).
 * Перегруженный газ опускается, на опоре растекается. ⚠ Эллипсоид и его хорошо перемешанная взвесь — замыкание,
 * не измеренный профиль бетонной струи. После таблицы π/N геометрия носителя фиксирована; оседание и снос продолжаются.
 * Без частиц сохранён прежний подъём до первой вершины ({@link LaunchCloud}). Турбулентность воздуха — Бриггс ({@link #sigmaH}).
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
    private final double[] z, r, horizontal, vertical, w, gp, dx, dz, deposited;
    private final double dustKg, sootKg;
    private static final double SOLID_VOLUME = 3 / (4 * Math.PI * CloudModel.RHO);
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
        this(buoyancy, g0, rMin, air, 0, 0);
    }

    /**
     * Particle-laden thermal. The carrier gas keeps its heat buoyancy; suspended solids contribute the opposite force
     * g M/ρ. Brown et al., ARI-RR-1384 (2004), §§3.3.2, 3.5, and Carey et al., JGR 93 (1988), 15314–15328.
     * Particle inertia and the momentum carried away by fallout are included.
     * ⚠ Well-mixed closure, not a measured jet split. Gas/solid heat exchange is not resolved. Fallout flux is v_settle C through the lower gas surface.
     * Released particles remain in DustFallout
     * until deposition; loss from the gas is not loss from the atmosphere.
     * Terrain wakes and the directed gas jet through a slab are not resolved by this integral model.
     */
    public Thermal(double buoyancy, double g0, double rMin, CloudModel.Air air, double dustKg, double sootKg) {
        this.air = air;
        this.buoyancy = buoyancy;
        this.dustKg = Math.max(0, dustKg);
        this.sootKg = Math.max(0, sootKg);
        // без тепла (пыль, поднятая не жаром) — шар не всплывает: g' = 0
        double volume = buoyancy > 0 && g0 > 0 ? buoyancy / g0 : 0;
        if (buoyancy <= 0) g0 = 0;
        r0 = Math.max(rMin, Math.cbrt(3 * volume / (4 * Math.PI)));
        int n = (int) Math.ceil(Math.PI / air.n() / DT) + 1;
        z = new double[n];
        r = new double[n];
        horizontal = new double[n];
        vertical = new double[n];
        w = new double[n];
        gp = new double[n];
        dx = new double[n];
        dz = new double[n];
        deposited = new double[n];
        // MTT в переменных u = R³, m = (R³ + 3M/(4πρ))W, f = R³g'; центр — от половины радиуса над землёй (наземный шар — полусфера)
        double u = r0 * r0 * r0, m = 0, f = u * g0, h = 0.5 * r0, x = 0, y = 0, j = 0, a = r0;
        double[] k1 = new double[6], k2 = new double[6], k3 = new double[6], k4 = new double[6];
        for (int i = 0; i < n; i++) {
            double rr = Math.cbrt(u);
            z[i] = h;
            r[i] = rr;
            horizontal[i] = a;
            vertical[i] = u / (a * a);
            w[i] = m / (u + solidVolume(j));
            gp[i] = f / u;
            dx[i] = x;
            dz[i] = y;
            deposited[i] = j;
            // RK4 on the support constraint when the pressure reaction balances a downward force.
            boolean onSupport = h <= 0.5 * u / (a * a) + 1e-8 && m <= 0 && f < CloudModel.G * solidVolume(j);
            // Gas volume, momentum, heat buoyancy, height, cumulative fallout exposure and horizontal radius.
            rate(u, m, f, h, j, a, onSupport, k1);
            rate(u + 0.5 * DT * k1[0], m + 0.5 * DT * k1[1], f + 0.5 * DT * k1[2], h + 0.5 * DT * k1[3], j + 0.5 * DT * k1[4], a + 0.5 * DT * k1[5], onSupport, k2);
            rate(u + 0.5 * DT * k2[0], m + 0.5 * DT * k2[1], f + 0.5 * DT * k2[2], h + 0.5 * DT * k2[3], j + 0.5 * DT * k2[4], a + 0.5 * DT * k2[5], onSupport, k3);
            rate(u + DT * k3[0], m + DT * k3[1], f + DT * k3[2], h + DT * k3[3], j + DT * k3[4], a + DT * k3[5], onSupport, k4);
            u += DT / 6 * (k1[0] + 2 * k2[0] + 2 * k3[0] + k4[0]);
            m += DT / 6 * (k1[1] + 2 * k2[1] + 2 * k3[1] + k4[1]);
            f += DT / 6 * (k1[2] + 2 * k2[2] + 2 * k3[2] + k4[2]);
            h += DT / 6 * (k1[3] + 2 * k2[3] + 2 * k3[3] + k4[3]);
            j += DT / 6 * (k1[4] + 2 * k2[4] + 2 * k3[4] + k4[4]);
            a = Math.max(Math.cbrt(u), a + DT / 6 * (k1[5] + 2 * k2[5] + 2 * k3[5] + k4[5]));
            double support = 0.5 * u / (a * a);
            if (h <= support || (onSupport && m <= 0)) {
                h = support;
                m = Math.max(0, m); // support reaction; negative buoyancy drives the horizontal current in rate()
            }
            double along = air.wind(h) * DT;
            x += air.windX() * along;
            y += air.windZ() * along;
            if (dustKg == 0 && sootKg == 0 && m <= 0 && i > 0) {
                // вершина: дальше облако не поднимается
                for (int k = i + 1; k < n; k++) {
                    z[k] = z[i];
                    r[k] = r[i];
                    horizontal[k] = horizontal[i];
                    vertical[k] = vertical[i];
                    w[k] = 0;
                    gp[k] = gp[i];
                    dx[k] = dx[k - 1] + air.windX() * air.wind(z[i]) * DT;
                    dz[k] = dz[k - 1] + air.windZ() * air.wind(z[i]) * DT;
                    deposited[k] = deposited[k - 1];
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

    /** Газ и взвесь: импульс смеси, подсос, тепло, выход частиц через нижнюю поверхность; a — горизонтальный радиус. */
    private void rate(double u, double m, double f, double h, double j, double a, boolean onSupport, double[] out) {
        double rr = Math.cbrt(u), b = u / (a * a), solids = solidVolume(j);
        double wv = m / (u + solids), force = f - CloudModel.G * solids;
        boolean supported = onSupport || h <= 0.5 * b + 1e-8;
        if (onSupport) h = 0.5 * b;
        if (supported && wv < 0) wv = 0;
        out[0] = 3 * CloudModel.ALPHA * rr * rr * Math.abs(wv);
        out[4] = dustKg > 0 ? escapeDensity(b, h) : 0;
        double loss = dustKg > 0 ? SOLID_VOLUME * dustKg * DustModel.settlingFlux(j) * out[4] : 0;
        out[1] = supported && force < 0 && m <= 0 ? 0 : force - loss * wv;
        out[2] = -air.n() * air.n() * u * wv;
        out[5] = a <= rr + 1e-8 ? out[0] / (3 * rr * rr) : 0;
        if (supported && force < 0) {
            // An overloaded carrier spreads on its support instead of hovering. Constant-volume inertial current,
            // U_front = Fr sqrt(g_dense H), H = 2b, Huppert & Simpson (1980), same Fr as the existing wave model.
            // Oblate ellipsoid a²b = R³; entrainment by a supported gravity current is unresolved (⚠ closure).
            // The same mixture inertia as in vertical motion: dense acceleration cannot exceed g.
            out[5] = GroundDust.FR * Math.sqrt(-force / (u + solids) * 2 * b);
        }
        double db = out[0] / (a * a) - 2 * b * out[5] / a;
        out[3] = supported && wv <= 0 ? 0.5 * db : wv;
    }

    private double solidVolume(double exposure) {
        return SOLID_VOLUME * (sootKg + (dustKg > 0 ? dustKg * DustModel.airborne(exposure) : 0));
    }

    /** Projected exit area / occupied volume for the ellipsoid cut by its support plane, 1/m. */
    private static double escapeDensity(double b, double h) {
        double q = Math.max(0, Math.min(1, h / b));
        return 1 / (b * (2.0 / 3 + q - q * q * q / 3));
    }

    /** Cumulative ∫ A_exit/V dt, s/m. This is fallout from the gas, NOT disappearance on reaching the ground. */
    public double settling(double t) {
        double end = (z.length - 1) * DT, j = at(deposited, step(t));
        if (dustKg > 0 && t > end) j += (t - end) * escapeDensity(vertical[vertical.length - 1], z[z.length - 1]);
        return j;
    }

    /** Invert the monotone fallout exposure, using the same interpolation as settling(). */
    double timeForSettling(double value) {
        if (value <= 0) return 0;
        int last = deposited.length - 1;
        if (value >= deposited[last]) return last * DT + (value - deposited[last]) / escapeDensity(vertical[last], z[last]);
        int lo = 0, hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (deposited[mid] < value) lo = mid; else hi = mid;
        }
        return (lo + (value - deposited[lo]) / (deposited[hi] - deposited[lo])) * DT;
    }

    public double horizontalRadius(double t) { return at(horizontal, step(t)); }
    public double verticalRadius(double t) { return at(vertical, step(t)); }
    public double velocity(double t) { return t >= (w.length - 1) * DT ? 0 : at(w, step(t)); }

    /** Физическая масса свежей пыли, ещё удерживаемая газом, кг; свободные частицы учитывает DustFallout. */
    public double airborneDust(double t) {
        return dustKg * DustModel.airborne(settling(t));
    }

    public double opticalDust(double t) {
        return dustKg * DustModel.extinctionAloft(settling(t));
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

    /** Объёмно-эквивалентный радиус термика в возрасте t, м; за таблицей — последний. */
    public double radius(double t) {
        return at(r, step(t));
    }

    /** Скорость подъёма, м/с. */
    public double rise(double t) {
        return Math.max(0, at(w, step(t)));
    }

    /** Снос центра ветром за t, м (x и z). */
    public double driftX(double t) {
        return at(dx, step(t)) + air.windX() * air.wind(z[z.length - 1]) * beyond(t);
    }

    public double driftZ(double t) {
        return at(dz, step(t)) + air.windZ() * air.wind(z[z.length - 1]) * beyond(t);
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
        double s0 = horizontalRadius(t) / Math.sqrt(5);
        double s = CloudModel.spreadH(air.spread(), after(t));
        return Math.sqrt(s0 * s0 + s * s);
    }

    public double sigmaV(double t) {
        double s0 = verticalRadius(t) / Math.sqrt(5);
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
