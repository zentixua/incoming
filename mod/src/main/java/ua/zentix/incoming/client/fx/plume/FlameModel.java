package ua.zentix.incoming.client.fx.plume;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Факел двигателя из данных паспорта ({@link Motor}) — один закон для всех двигателей.
 * <ol>
 * <li><b>Струя</b>: газ из камеры (полная температура T₀, давление p₀ — по изоэнтропе от среза) расширяется до давления
 * воздуха на высоте (стандартная атмосфера): число Маха M_j, температура T_j и радиус r_j полностью расширенной струи
 * (изоэнтропа, без потерь). Недорасширенная струя за срезом шире сопла, перерасширенная — уже.</li>
 * <li><b>Ядро</b> — сверхзвуковая середина, где газ ещё не смешался с воздухом: L_c = 3,45·d_j·(1 + 0,38·M_j)²
 * (Eldred, NASA SP-8072, 1971, по полностью расширенной струе; формула и её разбор — James и др., Trans. JSASS 14,
 * 2016, Po_2_17: у M = 3 это 15,8 d; Varnier, AIAA J. 39(10), 2001, по замерам вдвое короче — ⚠ точность в 2 раза).</li>
 * <li><b>Догорание</b>: продукты твёрдого топлива богаты горючим (CO, H₂ — {@link Motor#monoxide}, {@link Motor#hydrogen}) и
 * догорают в подмешанном воздухе. За ядром доля продуктов на оси спадает как у круглой струи, ∝ 1/x (оценка: так спадают
 * скорость и примесь в дальней части струи); пламя кончается, где смесь на оси стехиометрическая: x_f = L_c/f_st
 * (Burke–Schumann: пламя струи — поверхность стехиометрической смеси), f_st = 1/(1 + воздух на кг продуктов: CO —
 * 2,46 кг, H₂ — 34,5 кг). Температура пламени — адиабатическая при f_st: смесь продуктов и воздуха плюс теплота
 * сгорания (CO 10,1 МДж/кг, H₂ 120 МДж/кг) на теплоёмкость {@link #CP_FLAME} (⚠ без диссоциации и излучения — верхняя
 * оценка, настоящая на 10–20 % ниже).</li>
 * <li><b>Хвост</b>: за пламенем газ остывает тем же законом ∝ 1/x и виден, пока горячее точки Дрейпера (798 K — с неё
 * раскалённое тело видно в темноте).</li>
 * <li><b>Свет</b>: газ в видимом почти не светит — светят конденсированные частицы (Al₂O₃, сажа): степень черноты
 * ε = 1 − e^(−τ), τ = σ_m·(поглощение горячих)·c·2r_j, c — их плотность в струе. Яркость — ε × чёрное тело
 * ({@link BlackBody}) при температуре газа, цвет — цветность чёрного тела; у пламени и хвоста — в их середине. За ядром
 * частицы разбавляются: τ ∝ 1/x (масса в сечении та же, путь растёт как x, плотность падает как x²).</li>
 * <li><b>Алмазы</b> — скачки уплотнения, когда давление на срезе не равно давлению воздуха (и при недорасширении, и при
 * перерасширении); заметны при расхождении больше {@link #DIAMONDS} (⚠ оценка: слабые скачки не разогревают газ до
 * свечения).</li>
 * </ol>
 * Нет факела ({@code null}), если газ на выходе холоднее точки Дрейпера или его свет ночью не виден глазу (поршень,
 * ТРДД: частиц почти нет, горючего в выхлопе нет). Ширина — слой смешения растёт от кромки сопла внутрь до оси к концу
 * ядра и столько же наружу (⚠ оценка: настоящий слой растёт наружу сильнее).
 */
public final class FlameModel {
    /** Точка Дрейпера, K. */
    public static final double DRAPER = 798;
    /** Отношение давлений среза и воздуха (или обратное), с которого видны алмазы. */
    public static final double DIAMONDS = 1.3;
    /** Воздух на килограмм CO и H₂ при полном сгорании, кг (кислород 0,571 и 8 кг на кг, в воздухе его 23,2 % по массе). */
    static final double AIR_CO = 2.46, AIR_H2 = 34.5;
    /** Теплота сгорания CO и H₂ (низшая), Дж/кг. */
    static final double HEAT_CO = 10.1e6, HEAT_H2 = 120e6;
    /** Теплоёмкость горячей смеси продуктов и воздуха, Дж/(кг·K) (⚠ оценка: 1,2–1,4 кДж/(кг·K) при 1 500–2 500 K). */
    static final double CP_FLAME = 1_300;
    /** Коэффициент длины ядра Элдреда. */
    static final double ELDRED = 3.45, ELDRED_MACH = 0.38;
    /**
     * Свет, который глаз видит на самом тёмном экране: порог Кошмидера при привыкании ночью в грозу (свет неба 0,12,
     * {@code Sight.adapted}).
     */
    static final double DIMMEST = Sight.THRESHOLD * Math.pow(0.12, Sight.ADAPT);
    /** Высоты в кэше — ступенями, м: давление на 50 м меняется на 0,6 %. */
    static final double STEP = 50;

    private static final Map<Long, Flame> CACHE = new ConcurrentHashMap<>();
    private static final Map<Motor, Integer> IDS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT = new AtomicInteger();
    private static final Flame NONE = new Flame(0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    private FlameModel() {}

    /** Факел двигателя на высоте altitude метров над уровнем моря (ступенями {@link #STEP}); null — не видно. */
    @Nullable
    public static Flame at(Motor motor, double altitude) {
        long step = Math.round(altitude / STEP);
        long key = (long) IDS.computeIfAbsent(motor, m -> NEXT.getAndIncrement()) << 32 | (step & 0xFFFFFFFFL);
        Flame f = CACHE.computeIfAbsent(key, k -> {
            Flame made = of(motor, step * STEP);
            return made == null ? NONE : made;
        });
        return f == NONE ? null : f;
    }

    /** Факел без кэша (юнит-тесты). */
    @Nullable
    public static Flame of(Motor m, double altitude) {
        double ta = StandardAtmosphere.temperature(altitude), pa = StandardAtmosphere.pressure(altitude);
        double g = m.gamma(), p0 = m.chamberPressure(), t0 = m.temperature();
        double ratio = Math.pow(p0 / pa, (g - 1) / g);
        if (ratio <= 1) return null;
        double mach = Math.sqrt(2 / (g - 1) * (ratio - 1)), tj = t0 / (1 + (g - 1) / 2 * mach * mach);
        if (tj <= DRAPER || tj <= ta) return null;
        double vj = mach * Math.sqrt(g * Motor.R * tj / m.molarMass()), rhoj = pa * m.molarMass() / (Motor.R * tj);
        double perNozzle = m.exhaust() / m.nozzles(), rj = Math.sqrt(perNozzle / (rhoj * vj * Math.PI));
        double core = ELDRED * 2 * rj * Math.pow(1 + ELDRED_MACH * mach, 2);
        // догорание: пламя до стехиометрической смеси на оси, при своей адиабатической температуре
        double air = m.monoxide() * AIR_CO + m.hydrogen() * AIR_H2, flame = core, tf = tj;
        if (air > 0) {
            double fst = 1 / (1 + air);
            flame = core / fst;
            tf = Math.max(tj, fst * tj + (1 - fst) * ta + fst * (m.monoxide() * HEAT_CO + m.hydrogen() * HEAT_H2) / CP_FLAME);
        }
        double length = flame * (tf - ta) / (DRAPER - ta);
        double tau = 0;
        Smoke s = m.smoke();
        if (s != null) tau = s.extinction * s.hotAbsorption * (m.smokeFlow() / m.nozzles()) / (vj * Math.PI * rj * rj) * 2 * rj;
        double eps = 1 - Math.exp(-tau);
        double coreL = eps * BlackBody.luminance(tj);
        if (coreL < DIMMEST) return null;
        double xf = (core + flame) / 2, epsF = 1 - Math.exp(-tau * core / xf);
        double xt = (flame + length) / 2, tt = ta + (tf - ta) * flame / xt, epsT = 1 - Math.exp(-tau * core / xt);
        double width = rj * Math.sqrt(m.nozzles());
        double pe = m.exitPressure();
        boolean diamonds = pe > pa * DIAMONDS || pe * DIAMONDS < pa;
        return new Flame(core, flame, length, width, diamonds, coreL, epsF * BlackBody.luminance(tf), epsT * BlackBody.luminance(tt),
                color(tj), color(tf), color(tt), tj, tf, eps);
    }

    /** Цветность чёрного тела температуры kelvin: sRGB, наибольший канал — 255. */
    public static int color(double kelvin) {
        double[] l = new double[4];
        BlackBody.light(kelvin, l);
        return pack(l);
    }

    /** Линейный rgb (первые три числа) → sRGB, наибольший канал — 255. */
    static int pack(double[] rgb) {
        double max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
        if (max <= 0) return 0;
        int out = 0;
        for (int i = 0; i < 3; i++) out = out << 8 | (int) Math.round(255 * srgb(rgb[i] / max));
        return out;
    }

    /** Кодирование sRGB (IEC 61966-2-1). */
    public static double srgb(double c) {
        c = Math.max(0, Math.min(1, c));
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    /** Байт sRGB → линейная доля. */
    public static double linear(int srgb) {
        double c = srgb / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
