package ua.zentix.incoming.client.fx.plume;

import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.strike.ThrustCurve;
import ua.zentix.incoming.util.StandardAtmosphere;

/**
 * Горячее сопло стартового РДТТ: свечение стенок сопла по их температуре — на ускорителе в полёте (виден на спаде тяги,
 * когда факел гаснет) и на отделившемся ({@code SpentBoosterEntity}), пока он не остынет. Корпус не светится: стальной
 * корпус РДТТ изнутри закрыт теплозащитой, и снаружи он за время работы нагревается на десятки градусов (Sutton, Biblarz,
 * «Rocket Propulsion Elements», гл. 15), свечение начинается с ~800 K (точка Дрейпера).
 * <p>
 * Стенки — типовое сопло тактического РДТТ (Sutton, Biblarz, гл. 15; ⚠ чертежей ускорителей «Томагавка» Mk 106 и шахеда
 * нет): горло — вкладыш из графита ({@link Material#GRAPHITE}), раструб — углепластик на фенольной смоле
 * ({@link Material#CARBON_PHENOLIC}, после работы — кокс). Каждая — плоская стенка (⚠ кривизна не учитывается) с
 * закрытой тыльной стороной (за ней — теплозащита и сталь: за десятки секунд тепла туда почти не уходит), теплопроводность
 * по толщине — сеткой ({@link #surface}). Нагрев в работе — конвекцией газа по Бартцу
 * ({@link #bartz}: h ∝ p_к^0,8 — на спаде тяги падает с давлением, {@link ThrustCurve}) к температуре восстановления у
 * стенки ({@link #recovery}); ⚠ без уноса массы, оседания окиси алюминия и поправки на горячую стенку (σ = 1). После
 * работы стенка теряет тепло излучением наружу через срез (доля {@link #VIEW_OUT}) и конвекцией воздуха
 * ({@link #AIR_H}), изнутри к поверхности его подводит теплопроводность. Видно — свет поверхности: ε × чёрное тело её
 * температуры ({@link BlackBody}).
 * <p>
 * Время — настоящее: разгон в игре короче настоящей работы (не по физике, {@code WeaponSpec.LaunchProfile}), поэтому
 * полка игровой фазы разгона сжимается в настоящую полку ({@link #seconds}), спад — в настоящую длительность (как у
 * {@link ThrustCurve}). К отделению стенки нагреты, как после настоящих 12 с у «Томагавка» и 2,5 с у шахеда.
 * Что видно: в конце спада у среза — раскалённый раструб (~2 500 K, жёлто-белый), он гаснет за 10–30 с через оранжевый
 * в тёмно-красный; графитовое горло массивнее и держит красный свет внутри сопла минутами (⚠ без замеров остывания
 * сопел после работы — порядок по закону).
 */
public final class NozzleGlow {
    /** Постоянная Стефана — Больцмана, Вт/(м²·K⁴). */
    static final double SIGMA = 5.670374e-8;
    /**
     * Доля излучения стенки, уходящая наружу через срез: остальное она обменивает с другими стенками сопла и пустым
     * корпусом примерно той же температуры (⚠ оценка конфигурационного множителя полости).
     */
    static final double VIEW_OUT = 0.5;
    /** Конвекция воздуха в сопле падающего ускорителя, Вт/(м²·K) (⚠ оценка: донная область, 10–100). */
    static final double AIR_H = 25;
    /** Полуугол конического раструба: стандартный 15° (Sutton, Biblarz, гл. 3). */
    static final double CONE_HALF_ANGLE = Math.toRadians(15);
    /** Толщина графитового вкладыша — радиусов горла (⚠ типовые вкладыши: наружный диаметр ~2 диаметра горла). */
    static final double INSERT = 1.0;
    /** Толщина углепластика раструба — радиусов горла (⚠ оценка: у сопел этого размера 1–2 см). */
    static final double LINER = 0.4;
    /** Шаг таблицы температур, с. */
    static final double SAMPLE = 0.05;
    /** Сколько секунд остывания после конца работы в таблице: ускоритель живёт в мире меньше ({@code SpentBoosterEntity}). */
    static final double AFTER = 60;
    /**
     * Узлов сетки по толщине: у углепластика — {@value #CELLS_LINER} (глубина прогрева за спад тяги √(α·t) — 0,25–0,55 мм,
     * узел не толще её половины; с 20 узлами раструб на отделении выходил на 90–125 K холоднее закона), у графита —
     * {@value #CELLS_INSERT} (он проводит в 40 раз лучше; ошибка не больше ~15 K, счёт десятки мс).
     */
    static final int CELLS_LINER = 80, CELLS_INSERT = 40;

    /**
     * Материал стенки: теплопроводность и теплоёмкость по температуре (таблицы, между точками — по прямой), плотность,
     * степень черноты.
     */
    public enum Material {
        /**
         * Мелкозернистый графит вкладышей горла (класс ATJ): ρ 1 760 кг/м³; теплоёмкость — JANAF (графит, C(ref)); теплопроводность
         * поперёк зерна ~125 Вт/(м·K) при 300 K, падает до ~33 при 2 500 K (⚠ по справочным кривым ATJ, ±20 %);
         * ε 0,85 (0,8–0,9).
         */
        GRAPHITE(1760, 0.85, new double[]{298, 400, 500, 600, 700, 800, 900, 1000, 1500, 2000, 2500, 3000},
                new double[]{709, 993, 1218, 1402, 1543, 1651, 1734, 1799, 1974, 2054, 2100, 2134},
                new double[]{300, 1000, 1500, 2000, 2500, 3000}, new double[]{125, 60, 45, 38, 33, 30}),
        /**
         * Кокс углепластика на фенольной смоле (раструбы РДТТ, класс MX-4926): ρ 1 300 кг/м³, теплоёмкость ~1 500 Дж/(кг·K),
         * теплопроводность ~1 Вт/(м·K) (⚠ у кокса 0,6–1,7 по температуре), ε 0,85.
         */
        CARBON_PHENOLIC(1300, 0.85, new double[]{298, 3000}, new double[]{1500, 1500}, new double[]{298, 3000}, new double[]{1.0, 1.0});

        final double density, emissivity;
        private final double[] heatT, heat, conductT, conduct;

        Material(double density, double emissivity, double[] heatT, double[] heat, double[] conductT, double[] conduct) {
            this.density = density;
            this.emissivity = emissivity;
            this.heatT = heatT;
            this.heat = heat;
            this.conductT = conductT;
            this.conduct = conduct;
        }

        /** Удельная теплоёмкость при температуре t, Дж/(кг·K). */
        double heat(double t) {
            return table(heatT, heat, t);
        }

        /** Теплопроводность при температуре t, Вт/(м·K). */
        double conductivity(double t) {
            return table(conductT, conduct, t);
        }

        /** Наибольшая температуропроводность k/(ρc) на всём диапазоне таблиц, м²/с: шаг явной сетки. */
        double maxDiffusivity() {
            double a = 0;
            for (double t = 250; t <= 3500; t += 25) a = Math.max(a, conductivity(t) / (density * heat(t)));
            return a;
        }
    }

    /**
     * Стенка: материал, толщина, м, коэффициент теплоотдачи газа на полке, Вт/(м²·K), температура восстановления, K,
     * степень черноты газа в сопле на полке (сквозь него стенка излучает наружу).
     */
    record Wall(Material material, double thickness, double gas, double recovery, double gasEmissivity) {
        /** Узлов сетки по толщине. */
        int cells() {
            return material == Material.GRAPHITE ? CELLS_INSERT : CELLS_LINER;
        }
    }

    /** Вид сопла в миг: радиусы среза и горла, глубина горла за срезом, м; температуры раструба и горла, K. */
    public record Shine(double exitRadius, double throatRadius, double depth, double coneKelvin, double throatKelvin) {
        /** Яркость раструба в единицах кадра (белая стена в полдень — 1). */
        public double coneLuminance() {
            return Material.CARBON_PHENOLIC.emissivity * NozzleGlow.luminance(coneKelvin);
        }

        /** Яркость горла. */
        public double throatLuminance() {
            return Material.GRAPHITE.emissivity * NozzleGlow.luminance(throatKelvin);
        }

        /**
         * Доля горла, видная через срез у взгляда под углом с косинусом cos к оси сопла (1 — строго сзади): круг горла на
         * глубине {@link #depth} за срезом сдвинут по срезу на depth·tg θ, видна его часть внутри круга среза (линза двух
         * кругов); спереди среза — 0.
         */
        public double throatSeen(double cos) {
            if (cos <= 0) return 0;
            double tan = Math.sqrt(Math.max(0, 1 - cos * cos)) / cos;
            return lens(throatRadius, exitRadius, depth * tan) / (Math.PI * throatRadius * throatRadius);
        }
    }

    /** Площадь пересечения кругов радиусов r1, r2 с центрами на расстоянии d. */
    static double lens(double r1, double r2, double d) {
        if (d >= r1 + r2) return 0;
        double small = Math.min(r1, r2);
        if (d <= Math.abs(r2 - r1)) return Math.PI * small * small;
        double a1 = Math.acos(Math.max(-1, Math.min(1, (d * d + r1 * r1 - r2 * r2) / (2 * d * r1))));
        double a2 = Math.acos(Math.max(-1, Math.min(1, (d * d + r2 * r2 - r1 * r1) / (2 * d * r2))));
        double k = (-d + r1 + r2) * (d + r1 - r2) * (d - r1 + r2) * (d + r1 + r2);
        return r1 * r1 * a1 + r2 * r2 * a2 - 0.5 * Math.sqrt(Math.max(0, k));
    }

    private final Motor motor;
    private final ThrustCurve curve;
    /** Настоящее время работы, с. */
    private final double burn;
    private final double exitRadius, throatRadius, depth;
    /** Температура поверхности раструба и горла через {@link #SAMPLE} с от поджига, K. */
    private final double[] cone, throat;
    /** Стенки горла и раструба; спад тяги, настоящие секунды. */
    final Wall throatWall, coneWall;
    final double tail;

    /**
     * Сопло ускорителя motor (данные двигателя — {@code ClientWeaponSpec}) с кривой тяги curve в игре
     * ({@code Airframe.thrust}) и настоящим временем работы burn, с ({@code Article.SolidMotor.burn}).
     */
    public NozzleGlow(Motor motor, ThrustCurve curve, double burn) {
        this.motor = motor;
        this.curve = curve;
        this.burn = burn;
        exitRadius = motor.exitRadius();
        throatRadius = throatRadius(motor);
        depth = Math.max(0, exitRadius - throatRadius) / Math.tan(CONE_HALF_ANGLE);
        double ratio = Math.max(1, exitRadius * exitRadius / (throatRadius * throatRadius));
        // раструб — одной стенкой в середине по площади (⚠): теплоотдача и восстановление там
        double mid = (1 + ratio) / 2;
        // чернота газа в сопле — ядра струи поперёк у среза (⚠ в сопле газ плотнее: оценка снизу)
        Flame plateau = FlameModel.at(motor, 0);
        double gas = plateau == null ? 0 : plateau.emissivity();
        throatWall = new Wall(Material.GRAPHITE, INSERT * throatRadius, bartz(motor, 1), recovery(motor, 1), gas);
        coneWall = new Wall(Material.CARBON_PHENOLIC, LINER * throatRadius, bartz(motor, mid), recovery(motor, mach(motor.gamma(), mid)), gas);
        tail = curve.tail() / 20;
        throat = surface(throatWall, burn, tail, AFTER, 1, 1);
        cone = surface(coneWall, burn, tail, AFTER, 1, 1);
    }

    public Motor motor() {
        return motor;
    }

    /** Настоящее время работы, с: отделение ускорителя в игре — в этот миг таблицы. */
    public double burn() {
        return burn;
    }

    /**
     * Настоящие секунды от поджига на age тиков фазы ph ускорителя в полёте: поджиг — как есть (выход на режим,
     * {@link ThrustCurve#RISE}), полка игровой фазы разгона — в настоящую полку, спад — 1:1 (он настоящей длительности).
     */
    public double seconds(FlightPhase ph, double age) {
        double rise = ThrustCurve.RISE / 20;
        if (ph == FlightPhase.IGNITION) return Math.min(Math.max(age, 0), ThrustCurve.RISE) / 20;
        if (ph != FlightPhase.BOOST) return 0;
        double plateauGame = curve.burn() - curve.tail(), plateauReal = Math.max(0, burn - curve.tail() / 20 - rise);
        double a = Math.max(0, age);
        if (a < plateauGame) return rise + a / plateauGame * plateauReal;
        return rise + plateauReal + Math.min(a - plateauGame, curve.tail()) / 20;
    }

    /** Сопло через seconds настоящих секунд от поджига (после конца работы — {@link #burn} + секунды остывания). */
    public Shine at(double seconds) {
        return new Shine(exitRadius, throatRadius, depth, sample(cone, seconds), sample(throat, seconds));
    }

    /** Отделившийся ускоритель на age тиков жизни: стенки остывают с конца работы. */
    public Shine spent(double age) {
        return at(burn + Math.max(0, age) / 20);
    }

    /** Таблица xs → ys (xs по возрастанию): между точками — по прямой, за краями — крайние значения. */
    static double table(double[] xs, double[] ys, double x) {
        if (x <= xs[0]) return ys[0];
        for (int i = 1; i < xs.length; i++) {
            if (x <= xs[i]) return ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / (xs[i] - xs[i - 1]);
        }
        return ys[ys.length - 1];
    }

    private static double sample(double[] table, double seconds) {
        double i = Math.max(0, seconds) / SAMPLE;
        int k = (int) Math.floor(i);
        if (k >= table.length - 1) return table[table.length - 1];
        double f = i - k;
        return table[k] * (1 - f) + table[k + 1] * f;
    }

    // ------------------------------------------------------------------ законы

    /**
     * Радиус горла, м: расход ṁ = p_к·A_кр/c*, c* = √(RT/M)/(√γ·(2/(γ+1))^((γ+1)/(2(γ−1)))) (Sutton, Biblarz, гл. 3);
     * давление в камере — из среза двигателя ({@link Motor#chamberPressure}).
     */
    static double throatRadius(Motor m) {
        double area = m.exhaust() * cStar(m) / m.chamberPressure();
        return Math.sqrt(area / (m.nozzles() * Math.PI));
    }

    /** Характеристическая скорость c*, м/с. */
    static double cStar(Motor m) {
        double g = m.gamma();
        double gammaFn = Math.sqrt(g) * Math.pow(2 / (g + 1), (g + 1) / (2 * (g - 1)));
        return Math.sqrt(Motor.R * m.temperature() / m.molarMass()) / gammaFn;
    }

    /** Вязкость продуктов, Па·с: μ = 46,6·10⁻¹⁰·M^0,5·T^0,6 (фунт/(дюйм·с), T в °R; Sutton, Biblarz, ур. 8-21) в СИ. */
    static double viscosity(Motor m) {
        return 46.6e-10 * Math.sqrt(m.molarMass() * 1000) * Math.pow(m.temperature() * 1.8, 0.6) * 0.45359237 / 0.0254;
    }

    /** Число Прандтля продуктов: 4γ/(9γ − 5) (формула Эйкена, там же). */
    static double prandtl(Motor m) {
        return 4 * m.gamma() / (9 * m.gamma() - 5);
    }

    /**
     * Теплоотдача газа к стенке в месте с отношением площадей ratio = A/A_кр (1 — горло), Вт/(м²·K): Бартц (1957),
     * h = 0,026/D_кр^0,2 · μ^0,2·c_p/Pr^0,6 · (p_к/c*)^0,8 · (A_кр/A)^0,9 (радиус скругления горла ~ D_кр, σ = 1 — ⚠).
     */
    static double bartz(Motor m, double ratio) {
        double d = 2 * throatRadius(m);
        return 0.026 / Math.pow(d, 0.2) * Math.pow(viscosity(m), 0.2) * m.cp() / Math.pow(prandtl(m), 0.6)
                * Math.pow(m.chamberPressure() / cStar(m), 0.8) * Math.pow(1 / ratio, 0.9);
    }

    /** Температура восстановления у стенки при числе Маха mach, K: T_к·(1 + r·(γ−1)/2·M²)/(1 + (γ−1)/2·M²), r = Pr^⅓. */
    static double recovery(Motor m, double mach) {
        double k = (m.gamma() - 1) / 2 * mach * mach;
        return m.temperature() * (1 + Math.cbrt(prandtl(m)) * k) / (1 + k);
    }

    /** Сверхзвуковое число Маха при отношении площадей ratio (уравнение площадей, деление пополам). */
    static double mach(double gamma, double ratio) {
        double lo = 1, hi = 30;
        for (int i = 0; i < 80; i++) {
            double mid = (lo + hi) / 2;
            if (areaRatio(gamma, mid) < ratio) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    static double areaRatio(double gamma, double mach) {
        return Math.pow(2 / (gamma + 1) * (1 + (gamma - 1) / 2 * mach * mach), (gamma + 1) / (2 * (gamma - 1))) / mach;
    }

    /**
     * Температура поверхности стенки через {@link #SAMPLE} с от поджига до burn + after: теплопроводность по толщине —
     * явная сетка {@link Wall#cells} × cellScale узлов от поверхности до тыла (крайние — полуячейки; тыл закрыт), узел на
     * самой поверхности получает поток {@link #flux} при доле тяги настоящей кривой ({@link #share}: выход на режим, полка,
     * спад tail с) — схема второго порядка: с узлом в полуячейке под поверхностью и балансом через него прогрев графита
     * под факелом сходился медленно (~25 K на 40 узлах). Шаг — по устойчивости, но не крупнее четверти шага таблицы и десятой
     * доли спада (у углепластика устойчивость позволяет 0,1–0,3 с — спад прошёл бы за один-два шага); fine — во сколько
     * раз мельче шаг по времени, cellScale — во сколько раз больше узлов (эталон в тестах).
     */
    static double[] surface(Wall w, double burn, double tail, double after, double fine, int cellScale) {
        Material mat = w.material();
        int n = w.cells() * cellScale + 1;
        double dx = w.thickness() / (n - 1), air = StandardAtmosphere.T0;
        double[] t = new double[n], next = new double[n];
        java.util.Arrays.fill(t, air);
        // полуячейка у поверхности: ρc·dx/2 против 2k/dx к соседу и потока газа — шаг вдвое меньше внутреннего
        double dt = Math.min(0.2 * dx * dx / mat.maxDiffusivity(), Math.min(SAMPLE / 4, tail / 10)) / fine;
        ThrustCurve real = new ThrustCurve(ThrustCurve.RISE, burn * 20 - ThrustCurve.RISE, tail * 20);
        int samples = (int) Math.ceil((burn + after) / SAMPLE) + 1;
        double[] out = new double[samples];
        double time = 0;
        int k = 0;
        out[k++] = air;
        while (k < samples) {
            double q = flux(w, t[0], share(real, burn, time), air);
            for (int i = 0; i < n; i++) {
                double in = i == 0 ? q : mat.conductivity((t[i - 1] + t[i]) / 2) * (t[i - 1] - t[i]) / dx;
                double outFlow = i == n - 1 ? 0 : mat.conductivity((t[i] + t[i + 1]) / 2) * (t[i] - t[i + 1]) / dx;
                double cell = i == 0 || i == n - 1 ? dx / 2 : dx;
                next[i] = t[i] + dt * (in - outFlow) / (mat.density * mat.heat(t[i]) * cell);
            }
            double nowTime = time + dt;
            // записи между шагами — по прямой
            while (k < samples && k * SAMPLE <= nowTime) {
                double f = (k * SAMPLE - time) / dt;
                out[k++] = t[0] + (next[0] - t[0]) * f;
            }
            double[] swap = t;
            t = next;
            next = swap;
            time = nowTime;
        }
        return out;
    }

    /** Доля тяги на time настоящих секунд от поджига: кривая real ({@link ThrustCurve}), после burn — 0. */
    static double share(ThrustCurve real, double burn, double time) {
        if (time >= burn) return 0;
        return time < ThrustCurve.RISE / 20 ? ThrustCurve.ignition(time * 20) : real.share(FlightPhase.BOOST, time * 20 - ThrustCurve.RISE);
    }

    /**
     * Поток тепла в поверхность стенки температуры ts при доле тяги share, Вт/м² (в стенку — больше нуля) — один баланс на
     * всё время: подвод газа h·s^0,8·(T_восст − T); излучение наружу ε·σ·{@link #VIEW_OUT}·(T⁴ − T_в⁴) — сквозь газ в сопле,
     * доля (1 − ε_г(s)), ε_г(s) = 1 − (1 − ε_г)^s (толща газа ∝ расходу, как у {@code Flame.dimmed}); воздух {@link #AIR_H}
     * — долей (1 − s): пока газ идёт, воздуха у стенки нет. Все члены непрерывны по s — конец работы без ступеньки (при
     * выключателе «газ — излучение» поверхность раструба прыгала на ~280 K в один миг).
     */
    static double flux(Wall w, double ts, double share, double air) {
        Material mat = w.material();
        double h = share > 0 ? w.gas() * Math.pow(share, 0.8) : 0;
        double rad = mat.emissivity * SIGMA * VIEW_OUT * Math.pow(1 - w.gasEmissivity(), share), conv = AIR_H * (1 - share);
        return h * (w.recovery() - ts) - rad * (Math.pow(ts, 4) - Math.pow(air, 4)) - conv * (ts - air);
    }

    // ------------------------------------------------------------------ свет

    /** Таблица яркости чёрного тела от {@link #FROM_K} шагом {@link #STEP_K}: {@link BlackBody} — 200 шагов на число. */
    private static final double FROM_K = 500, STEP_K = 5;
    private static final int TO = 600;
    private static final double[] LUM = new double[TO + 1];
    private static final int[] COLOR = new int[TO + 1];

    static {
        double[] l = new double[4];
        for (int i = 0; i <= TO; i++) {
            BlackBody.light(FROM_K + i * STEP_K, l);
            LUM[i] = l[3];
            COLOR[i] = FlameModel.pack(l);
        }
    }

    /** Яркость чёрного тела температуры kelvin в единицах кадра (белая стена в полдень — 1); ниже 500 K — 0. */
    public static double luminance(double kelvin) {
        if (kelvin <= FROM_K) return 0;
        double i = Math.min((kelvin - FROM_K) / STEP_K, TO);
        int k = Math.min((int) i, TO - 1);
        double f = i - k;
        // между узлами — по логарифму: яркость у красного каления растёт на порядки на сотню градусов
        return Math.exp(Math.log(LUM[k] + 1e-300) * (1 - f) + Math.log(LUM[k + 1] + 1e-300) * f);
    }

    /** Цветность чёрного тела температуры kelvin (ближайший узел таблицы): sRGB, наибольший канал — 255. */
    public static int color(double kelvin) {
        int k = (int) Math.round(Math.max(0, Math.min(TO, (kelvin - FROM_K) / STEP_K)));
        return COLOR[k];
    }
}
