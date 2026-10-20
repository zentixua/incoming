package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.client.fx.plume.Stability;

/**
 * Дым над пожаром — непрерывный источник тепла и сажи, шлейф Бриггса, без Minecraft. Метры, секунды, ватты.
 * <ul>
 * <li><b>Источник.</b> Блок огня — горящая грань ~1 м²: ⚠ оценка {@value #BLOCK_WATTS} Вт (пик тепловыделения древесины
 * 100–250 кВт/м², Babrauskas, SFPE Handbook, 5-е изд., гл. 26 — середина). Конвекцией уходит {@value #CONVECTIVE}
 * (излучает 0,3 — доля излучения пламени древесины, там же; ⚠ по памяти). Сажа — выход 0,015 кг на кг сгоревшего при
 * эффективной теплоте 12,4 МДж/кг (красный дуб, таблица Тьюарсона в SFPE Handbook; ⚠ по памяти).</li>
 * <li><b>Подъём</b> — Бриггс (Briggs G. A., «Plume Rise Predictions», 1975; формулы — как в EPA ISC3, т. 2, §1.1.4;
 * ⚠ сверить): поток плавучести F = g·Q_c/(π·c_p·ρ·T); путь подъёма Δh(x) = 1,6·F^⅓·x^⅔/u до конечного: в нейтральном и
 * неустойчивом воздухе 21,425·F^¾/u (F &lt; 55) или 38,71·F^⅗/u, в устойчивом — 2,6·(F/(u·s))^⅓, s = N², а в штиль —
 * 5,0·F^¼·s^(−⅜) (MTT; меньшее из двух). Между классами D и E — плавно.</li>
 * <li><b>Расплывание</b> — Паскуилл–Гиффорд по пути ветра ({@link Stability}) и подъёмом (σ = Δh/3,5, Pasquill 1976) и
 * размер очага, в квадратуре.</li>
 * <li><b>Масса</b> — сажа на метр пути по ветру ṁ/u. Шлейф виден, пока толща через середину больше порога Кошмидера
 * (0,02).</li>
 * <li><b>Температура</b> у очага — избыток на оси по Хескестаду, ΔT = 9,1·(T/(g·c_p²·ρ²))^⅓·Q_c^⅔·z^(−5/3) (SFPE
 * Handbook, гл. 13; ⚠ по памяти), не выше пламени; свет пламени снизу — чёрное тело {@link #FLAME_K} K (поверхностная
 * излучаемая мощность малых пожаров 50–100 кВт/м², середина; ⚠ оценка).</li>
 * </ul>
 * Ветер слабее {@value #CALM} м/с формулы шлейфа не держат — берётся он (как у следа).
 */
public final class FirePlume {
    /** Тепловыделение одного блока огня, Вт (⚠ оценка). */
    public static final double BLOCK_WATTS = 175e3;
    /** Доля тепла, что уходит конвекцией. */
    public static final double CONVECTIVE = 0.7;
    /** Сажи на джоуль тепла: 0,015 кг/кг / 12,4 МДж/кг. */
    public static final double SOOT_PER_JOULE = 0.015 / 12.4e6;
    /** Пламя — чёрное тело с σT⁴ = 75 кВт/м², K. */
    public static final double FLAME_K = Math.pow(75e3 / 5.670374e-8, 0.25);
    /** Слабее этого ветра формулы шлейфа не держат, м/с. */
    public static final double CALM = 1;
    /** Порог видимости толщи. */
    static final double SEEN = CloudModel.SEEN;

    /** Тепло, Вт; поток плавучести, м⁴/с³; ветер, м/с; класс устойчивости; N², 1/с²; полуразмер очага, м. */
    public final double watts, flux, wind, stability, s, size;
    /** Конечный подъём, м, и путь по ветру до него, м. */
    public final double finalRise, finalX;

    /**
     * Шлейф над очагом из blocks блоков огня в ветре wind (м/с на высоте очага), классе устойчивости stability (0 — A … 5 —
     * F).
     */
    public FirePlume(double blocks, double wind, double stability) {
        watts = blocks * BLOCK_WATTS;
        flux = CloudModel.G * CONVECTIVE * watts / (Math.PI * CloudModel.CP * CloudModel.RHO * CloudModel.T_AIR);
        this.wind = Math.max(CALM, wind);
        this.stability = stability;
        double n = Stability.brunt(stability);
        s = n * n;
        // площадь очага ~ блоков м²: радиус √(N/π), σ — половина
        size = 0.5 * Math.sqrt(blocks / Math.PI);
        finalRise = finalRise(flux, this.wind, stability, s);
        finalX = Math.pow(finalRise * this.wind / (1.6 * Math.cbrt(flux)), 1.5);
    }

    /** Конечный подъём Бриггса, м. */
    static double finalRise(double f, double u, double stability, double s) {
        double neutral = f < 55 ? 21.425 * Math.pow(f, 0.75) / u : 38.71 * Math.pow(f, 0.6) / u;
        double stable = Math.min(2.6 * Math.cbrt(f / (u * s)), 5.0 * Math.pow(f, 0.25) * Math.pow(s, -0.375));
        double k = Math.max(0, Math.min(1, stability - Stability.D));
        return neutral + (stable - neutral) * k;
    }

    /** Подъём оси на пути x по ветру, м. */
    public double rise(double x) {
        if (x >= finalX) return finalRise;
        return 1.6 * Math.cbrt(flux) * Math.pow(Math.max(0, x), 2.0 / 3) / wind;
    }

    /** Скорость подъёма оси на пути x, м/с (u·dΔh/dx). */
    public double riseSpeed(double x) {
        if (x >= finalX || x <= 0) return 0;
        return (2.0 / 3) * 1.6 * Math.cbrt(flux) * Math.pow(x, -1.0 / 3);
    }

    /** σ поперёк ветра по горизонтали на пути x, м. */
    public double sigmaY(double x) {
        double b = rise(x) / 3.5, p = Stability.sigmaY(stability, x);
        return Math.sqrt(p * p + b * b + size * size);
    }

    /** σ по вертикали на пути x, м. */
    public double sigmaZ(double x) {
        double b = rise(x) / 3.5, p = Stability.sigmaZ(stability, x);
        return Math.sqrt(p * p + b * b + size * size);
    }

    /** Сажи на метр пути по ветру, кг/м. */
    public double sootPerMetre() {
        return watts * SOOT_PER_JOULE / wind;
    }

    /** Толща через середину шлейфа на пути x: σ_m·λ/(√(2π)·√(σ_y·σ_z)). */
    public double tau(double x) {
        return CloudModel.SOOT_K * sootPerMetre() / (Math.sqrt(2 * Math.PI) * Math.sqrt(sigmaY(x) * sigmaZ(x)));
    }

    /** Докуда по ветру шлейф виден, м (не дальше limit). */
    public double visible(double limit) {
        if (tau(limit) >= SEEN) return limit;
        double lo = 0, hi = limit;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            if (tau(mid) >= SEEN) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** Избыток температуры на оси на высоте z над очагом по Хескестаду, K (не выше пламени). */
    public double excess(double z) {
        double c = 9.1 * Math.cbrt(CloudModel.T_AIR / (CloudModel.G * CloudModel.CP * CloudModel.CP * CloudModel.RHO * CloudModel.RHO));
        double dt = c * Math.pow(CONVECTIVE * watts, 2.0 / 3) * Math.pow(Math.max(z, 0.1), -5.0 / 3);
        return Math.min(dt, FLAME_K - CloudModel.T_AIR);
    }

    /** Высота пламени по Хескестаду, м: L = 0,235·Q^⅖ − 1,02·D (Q — кВт, D — диаметр очага); не меньше полуметра. */
    public double flameHeight() {
        return Math.max(0.5, 0.235 * Math.pow(watts / 1e3, 0.4) - 1.02 * 4 * size);
    }
}
