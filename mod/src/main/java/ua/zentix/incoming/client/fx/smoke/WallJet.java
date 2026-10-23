package ua.zentix.incoming.client.fx.smoke;

/**
 * Стенная струя у земли от струи двигателя, ударившей в неё под углом: куда она уносит пыль. Без Minecraft.
 * <p>
 * <b>Доля по направлениям.</b> Струя, упавшая на плоскость под углом α к ней, растекается по ней во все стороны, но
 * неравно: вперёд (по горизонтальной доле струи) — больше. Доля потока на радиан азимута φ от направления «вперёд» —
 * w(φ) = sin³α / (2π·(1 − cos α·cos φ)²) (Hasson, Peck, «Thickness distribution in a sheet formed by impinging jets»,
 * AIChE J. 10(5), 1964: невязкий поворот, скорость в пелене та же, что в струе; у одной струи на плоскость — та же
 * половина картины двух встречных). Отвесно (α = 90°) — поровну во все стороны; полого — почти вся вперёд. Скорость у
 * всех долей одна, поэтому импульс по направлениям — тот же w(φ), а его сумма вдоль плоскости — M·cos α: ровно то,
 * что струя несла вдоль земли (земля без трения отнимает только отвесную долю). Это ⚠ закон для невязкого поворота;
 * у затопленной турбулентной струи так же лишь в области поворота (Beltaos 1976 мерил наклонные струи воздуха — по
 * описанию, картина вокруг точки удара близка к осесимметричной со сдвигом вперёд; данных у нас нет).
 * <p>
 * w(φ) — это закон средней аномалии Кеплера: dM/dν = (1 − e²)^{3/2}/(1 + e·cos ν)² при e = cos α, ν = φ − π. Поэтому
 * направления равной доли берутся точно: равные шаги M, обратное уравнение Кеплера ({@link #azimuths}).
 * <p>
 * <b>Как далеко.</b> Стенная струя — «затяжка»: импульс на радиан i(φ, t) = I(t)·w(φ) (I(t) — импульс, который струя
 * отдала земле к этому мигу, ṁ·u по подаче) несёт воздух, который она подхватила, — слой толщиной S·r у радиальной
 * стенной струи (S = {@value #SPREAD}: у плоской стенной 0,073 — Launder, Rodi, Annu. Rev. Fluid Mech. 15, 1983, ⚠ по
 * памяти; у радиальной рост в 1/0,83 раза больше — Lin, CSME 2008), т. е. массу ρ·S·r³/3 на радиан. Без трения о землю
 * (⚠ верхняя оценка) i(t) = (ρSr³/3)·dr/dt: r⁴ = r₀⁴ + 12·∫i dt/(ρS) (у короткого толчка ∫i dt = i·t; у ровной подачи
 * длиной T с итоговым импульсом i, пока она идёт, — i·t²/(2T)). Затяжка толкает пыль, пока её фронт быстрее ветра у земли
 * (dr/dt = 3i/(ρSr³)); дальше пыль несёт ветер — фронт стоит на (3i/(ρSU))^{1/3}. Пыль затяжки — по её массе (∝ r′² по
 * радиусу): середина на ¾ r, второй момент ⅗ r².
 */
public final class WallJet {
    /** Рост толщины радиальной стенной струи: 0,073 у плоской (Launder, Rodi 1983) / 0,83 (Lin 2008). */
    public static final double SPREAD = 0.073 / 0.83;
    /** Направлений равной доли. */
    public static final int DIRECTIONS = 32;
    /** cos α не ближе к 1, чем на это: у струи вдоль самой земли доля «вперёд» — сколь угодно узкий пик. */
    static final double GRAZING = 1e-3;

    private WallJet() {}

    /**
     * Доля потока стенной струи на радиан азимута φ от «вперёд» у струи под углом α к земле (sin α = sinA; положе
     * {@link #GRAZING} — как у этого предела, тот же, что у {@link #azimuths}).
     */
    public static double share(double sinA, double phi) {
        double c = Math.min(1 - GRAZING, Math.sqrt(Math.max(0, 1 - sinA * sinA)));
        double s2 = 1 - c * c, k = 1 - c * Math.cos(phi);
        return s2 * Math.sqrt(s2) / (2 * Math.PI * k * k);
    }

    /**
     * {@link #DIRECTIONS} азимутов от «вперёд», у каждого — равная доля потока (1/n) по {@link #share}: середины n
     * равных шагов средней аномалии, обратное уравнение Кеплера (e = cos α, ν = φ − π).
     */
    public static double[] azimuths(double sinA, int n) {
        double e = Math.min(1 - GRAZING, Math.sqrt(Math.max(0, 1 - sinA * sinA)));
        double[] out = new double[n];
        double ratio = Math.sqrt((1 + e) / (1 - e));
        for (int k = 0; k < n; k++) {
            double m = 2 * Math.PI * (k + 0.5) / n - Math.PI;
            // E − e·sin E = M, Ньютон от ±π: сходится при любом e < 1 (Charles, Tatum, Celest. Mech. 69, 1998)
            double big = Math.signum(m) * Math.PI;
            for (int it = 0; it < 50; it++) {
                double f = big - e * Math.sin(big) - m, d = 1 - e * Math.cos(big);
                double step = f / d;
                big -= step;
                if (Math.abs(step) < 1e-13) break;
            }
            double nu = 2 * Math.atan(ratio * Math.tan(big / 2));
            out[k] = nu + Math.PI;
        }
        return out;
    }

    /**
     * Фронт затяжки в направлении с импульсом i на радиан (Н·с/рад) и его интегралом по времени iTime (Н·с²/рад), начав с
     * радиуса r0; дальше места, где ветер у земли wind (м/с) её обгоняет, не идёт.
     */
    public static double reach(double i, double iTime, double r0, double wind) {
        if (i <= 0 || iTime <= 0) return r0;
        double r = Math.pow(Math.pow(r0, 4) + 12 * iTime / (CloudModel.RHO * SPREAD), 0.25);
        if (wind > 0) r = Math.min(r, Math.max(r0, Math.cbrt(3 * i / (CloudModel.RHO * SPREAD * wind))));
        return r;
    }
}
