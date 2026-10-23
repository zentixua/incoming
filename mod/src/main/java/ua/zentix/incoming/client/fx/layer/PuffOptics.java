package ua.zentix.incoming.client.fx.layer;

/**
 * Толща кадров запечённого клуба ({@link FxAtlas#puff6}, {@link FxAtlas#spray6}) и как клубу с таким кадром нести гаусс
 * модели. Непрозрачность кадра — то, что Blender отдал за объём симуляции: α = 1 − e^(−τ) по лучу через газ, поэтому
 * τ кадра = −ln(1 − α) (байт 255 — {@link #TAU_MAX}). Клуб рисуется с толщей s·τ кадра ({@code fx.fsh}: 1 − (1 − α)^s), и
 * квадраты складываются, как толщи: e^(−Σ s·τ) — сколько бы клубов ни перекрывалось, Бугер–Ламберт держит сам слой.
 * <p>
 * Гаусс массы с толщей середины τ₀ (поперёк экрана σa, σb) несёт клуб полуразмером c·σ и толщей s·τ кадра, где c и s —
 * из двух сумм по экрану, которые у гаусса знает модель:
 * <ul>
 * <li>Σ τ — вещество (ослабление всей массы, κm = 2π·σa·σb·τ₀): s·M0·c² = 2π·τ₀ (M0 — Σ τ кадра по квадрату в долях
 * полуразмера);</li>
 * <li>Σ (1 − e^(−τ)) — сколько он закрывает одиночным: у гаусса 2π·Ein(τ₀) (Ein — целая показательная функция,
 * ∫₀^τ₀ (1 − e^(−t))/t dt), у клуба c²·Σ(1 − e^(−sτ)) кадра.</li>
 * </ul>
 * Отношение второго к первому — одно уравнение на s ({@link #solve}), c — из первого. У тонкого гаусса оба
 * сливаются, и уравнение сводится к третьей сумме Σ τ² (сколько вещество перекрывает само себя): размер клуба тогда — по
 * равной «площади участия» (Σ τ)²/Σ τ², у гаусса 4πσ². Плотность середины, край и форма клуба — кадра симуляции, а сколько
 * вещества и сколько оно закрывает — модели.
 */
public final class PuffOptics {
    /** Толща непрозрачного байта кадра (α = 255/255): пропускание 1/255 — меньшее 8 бит не несут. */
    public static final double TAU_MAX = Math.log(255);
    /** Сетка τ₀: от 10^{@value #LOG_LO} до 10^{@value #LOG_HI}, {@value #STEPS} шагов на декаду. */
    static final int LOG_LO = -4, LOG_HI = 4, STEPS = 10, POINTS = (LOG_HI - LOG_LO) * STEPS + 1;
    /**
     * Смесей кадра со следующим на кадр (0, ¼, ½, ¾): шейдер смешивает непрозрачность двух кадров и уже её переводит в
     * толщу, а −ln(1 − α) смеси — не смесь толщ (у тонкого дыма в середине между кадрами вещество выходило на 3 % меньше).
     * Между узлами — линейно.
     */
    static final int MIXES = 4;

    /** По узлам кадр·{@link #MIXES} + смесь: c и ln s на сетке τ₀; наименьший c (у тонкого гаусса). */
    private final float[][] c, lnS;
    private final float cMin;

    private PuffOptics(float[][] c, float[][] lnS, float cMin) {
        this.c = c;
        this.lnS = lnS;
        this.cMin = cMin;
    }

    /**
     * Оптика кадров по их непрозрачности: {@code alpha[f]} — байты альфы кадра f (0–255) по строкам, {@code n} × {@code n}
     * пикселей, квадрат клуба — весь кадр.
     */
    public static PuffOptics of(int[][] alpha, int n) {
        int frames = alpha.length, nodes = (frames - 1) * MIXES + 1, levels = 255 * MIXES + 1;
        float[][] c = new float[nodes][POINTS], lnS = new float[nodes][POINTS];
        float[] thin = new float[nodes];
        // уровень смеси L = (4 − k)·a + k·b байтов двух кадров (k — четверти): α = L/1020
        double[] tau = new double[levels];
        for (int l = 1; l < levels; l++) tau[l] = Math.min(TAU_MAX, -Math.log(1 - (double) l / (levels - 1)));
        double dA = 4.0 / ((double) n * n);
        for (int t = 0; t < nodes; t++) {
            int f = t / MIXES, k = t % MIXES, g = Math.min(f + 1, frames - 1);
            // гистограмма толщ: суммы — по уровням, а не по 16 тысячам пикселей
            double[] area = new double[levels];
            for (int i = 0; i < n * n; i++) area[(MIXES - k) * Math.clamp(alpha[f][i], 0, 255) + k * Math.clamp(alpha[g][i], 0, 255)] += dA;
            node(tau, area, c[t], lnS[t], thin, t);
        }
        float cMin = Float.MAX_VALUE;
        for (float v : thin) cMin = Math.min(cMin, v);
        return new PuffOptics(c, lnS, cMin);
    }

    /** Таблицы узла по гистограмме площадей {@code area} у толщ {@code tau}; в cThin — c тонкого гаусса по площади участия. */
    private static void node(double[] tau, double[] area, float[] c, float[] lnS, float[] cThin, int at) {
        // только занятые уровни: у кадра их сотни из тысячи
        int used = 0;
        for (int l = 1; l < area.length; l++) if (area[l] > 0) used++;
        double[] t = new double[used], a = new double[used];
        double m0 = 0, q = 0;
        for (int l = 1, j = 0; l < area.length; l++) {
            if (area[l] <= 0) continue;
            t[j] = tau[l];
            a[j++] = area[l];
            m0 += area[l] * tau[l];
            q += area[l] * tau[l] * tau[l];
        }
        if (!(m0 > 0)) {
            java.util.Arrays.fill(c, 1);
            java.util.Arrays.fill(lnS, 0);
            cThin[at] = 1;
            return;
        }
        cThin[at] = (float) Math.sqrt(4 * Math.PI * q / (m0 * m0));
        // s растёт с τ₀ не быстрее τ₀ (у тонкого — пропорционально): поиск — от прошлого узла сетки
        double last = Double.NaN;
        for (int i = 0; i < POINTS; i++) {
            double t0 = Math.pow(10, LOG_LO + (double) i / STEPS);
            double s = solve(t, a, m0, q, t0, last);
            last = s;
            c[i] = (float) Math.sqrt(2 * Math.PI * t0 / (s * m0));
            lnS[i] = (float) Math.log(s);
        }
    }

    /**
     * s, при котором у клуба доля «закрывает / вещество» та же, что у гаусса толщи t0: 1 − R(s) = 1 − Ein(t0)/t0, где
     * R(s) = Σ(1 − e^(−sτ))/(s·M0). Обе стороны растут от нуля монотонно — деление пополам по ln s (от прошлого узла
     * {@code from}, если он есть: следующий узел сетки — не дальше её шага по τ₀); у самого тонкого — предел
     * s = t0·M0/(2Σ τ²) (обе стороны — первые члены рядов: sΣτ²/(2M0) и t0/4).
     */
    static double solve(double[] tau, double[] area, double m0, double q, double t0, double from) {
        double want = 1 - ein(t0) / t0;
        if (want < 1e-7) return t0 * m0 / (2 * q);
        double lo = Math.log(1e-9), hi = Math.log(1e9);
        if (from > 0) {
            lo = Math.log(from);
            hi = lo + Math.log(10) / STEPS + 1e-6;
            while (lack(tau, area, m0, Math.exp(hi)) < want && hi < Math.log(1e9)) hi += Math.log(10) / STEPS;
        }
        while (hi - lo > 1e-6) {
            double mid = 0.5 * (lo + hi);
            if (lack(tau, area, m0, Math.exp(mid)) < want) lo = mid;
            else hi = mid;
        }
        return Math.exp(0.5 * (lo + hi));
    }

    /** 1 − R(s) = Σ(sτ − 1 + e^(−sτ))/(s·M0) без потери точности у малых sτ. */
    private static double lack(double[] tau, double[] area, double m0, double s) {
        double sum = 0;
        for (int b = 0; b < tau.length; b++) {
            double x = s * tau[b];
            sum += area[b] * (x < 1e-4 ? x * x / 2 - x * x * x / 6 : x + Math.expm1(-x));
        }
        return sum / (s * m0);
    }

    /**
     * Ein(x) = ∫₀^x (1 − e^(−t))/t dt: у малых — ряд Σ (−1)^(k+1) x^k/(k·k!), у больших — γ + ln x + E₁(x)
     * (Абрамовиц и Стиган 5.1.39), E₁ — цепной дробью (5.1.22) или рядом (5.1.11).
     */
    public static double ein(double x) {
        if (x < 4) {
            double term = x, sum = 0;
            for (int k = 1; k < 60; k++) {
                sum += term / k;
                term *= -x / (k + 1);
            }
            return sum;
        }
        return 0.5772156649015329 + Math.log(x) + e1(x);
    }

    /** E₁(x) при x ≥ 1: цепная дробь Ленца (Numerical Recipes 6.3). */
    private static double e1(double x) {
        double b = x + 1, c = 1e300, d = 1 / b, h = d;
        for (int i = 1; i < 200; i++) {
            double an = -(double) i * i;
            b += 2;
            d = 1 / (an * d + b);
            c = b + an / c;
            double del = c * d;
            h *= del;
            if (Math.abs(del - 1) < 1e-15) break;
        }
        return h * Math.exp(-x);
    }

    /**
     * Клуб, что несёт гаусс толщи середины tau0, в кадре frame (с дробью — смесь со следующим, как в шейдере): в
     * {@code out[0]} — полуразмер квадрата в σ гаусса, в {@code out[1]} — множитель толщи кадра s.
     */
    public void club(double frame, double tau0, double[] out) {
        double f = Math.clamp(frame * MIXES, 0, c.length - 1);
        int f0 = Math.min((int) f, c.length - 2);
        double m = f - f0;
        double x = Math.clamp((Math.log10(Math.max(tau0, 1e-30)) - LOG_LO) * STEPS, 0, POINTS - 1);
        int i = Math.min((int) x, POINTS - 2);
        double w = x - i;
        double c0 = c[f0][i] + (c[f0][i + 1] - c[f0][i]) * w, c1 = c[f0 + 1][i] + (c[f0 + 1][i + 1] - c[f0 + 1][i]) * w;
        double s0 = lnS[f0][i] + (lnS[f0][i + 1] - lnS[f0][i]) * w, s1 = lnS[f0 + 1][i] + (lnS[f0 + 1][i + 1] - lnS[f0 + 1][i]) * w;
        out[0] = c0 + (c1 - c0) * m;
        // за краем сетки: s пропорционально τ₀ (c тот же); выше — клуб и так непрозрачен
        double beyond = Math.log10(Math.max(tau0, 1e-30)) - LOG_LO - x / STEPS;
        out[1] = Math.exp(s0 + (s1 - s0) * m) * Math.pow(10, beyond);
    }

    /** Наименьший полуразмер клуба в σ (у тонкого гаусса, по площади участия) по всем кадрам: шаг мест трубки — от него. */
    public double cMin() {
        return cMin;
    }

    /**
     * Запасная оптика, пока лист не прочитан (и у проверок без картинок): кадр — гаусс толщи с серединой {@link #TAU_MAX}
     * и σ в треть полуразмера.
     */
    static PuffOptics fallback(int frames) {
        int n = 64;
        int[] a = new int[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double r = Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) / (n / 2.0);
                a[y * n + x] = (int) Math.round(255 * (1 - Math.exp(-TAU_MAX * Math.exp(-r * r / (2 / 9.0)))));
            }
        }
        PuffOptics one = of(new int[][]{a}, n);
        float[][] c = new float[(frames - 1) * MIXES + 1][], lnS = new float[c.length][];
        java.util.Arrays.fill(c, one.c[0]);
        java.util.Arrays.fill(lnS, one.lnS[0]);
        return new PuffOptics(c, lnS, one.cMin);
    }

    private static volatile PuffOptics puff = fallback(FxAtlas.PUFF6_FRAMES), spray = puff;

    /** Оптика кадров клуба дыма. */
    public static PuffOptics puff() {
        return puff;
    }

    /** Оптика кадров клуба брызг. */
    public static PuffOptics spray() {
        return spray;
    }

    /** Оптика прочитанного листа ({@link FxAtlas}; проверки — по картинкам кадров). */
    public static void set(PuffOptics puffs, PuffOptics sprays) {
        puff = puffs;
        spray = sprays;
    }
}
