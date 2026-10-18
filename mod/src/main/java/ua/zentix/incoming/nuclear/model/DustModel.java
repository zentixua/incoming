package ua.zentix.incoming.nuclear.model;

/**
 * Пыль, которую поднимает ударная волна (DESIGN-nuke §6): один слой от фронта до пелены, а не стена и пелена порознь.
 * Законы — из обзора литературы «Blast-lofted dust» (дизайн «ядерка уровня кино», §8); метры, секунды модели, килограммы.
 * <p>
 * <b>Вынос.</b> За фронтом дует ветер Ренкина–Гюгонио ({@link BlastModel#windSpeed}), спадающий за положительную фазу
 * ({@link BlastModel#positivePhaseSeconds}) как (1 − τ/t₊)·e^(−τ/t₊). Динамическая скорость у земли — по шероховатой
 * пластине Шлихтинга на x метров за фронтом (Gaj &amp; Small 1991, ур. 7): u* = U/√2·B^(−1,25), B = 2,87 + 1,58·lg(x/K_s).
 * С u* ≥ 10 порогов срыва грунт уходит во взвесь — вынос F = 1,80·ρ·U^1,24 / (u*ₜₕ^0,24·B^2,8) (там же, ур. 8, по
 * Mirels 1984; ~1 кг/(м²·с) при 43 м/с над рыхлым песком); от порога до 10 порогов — сальтация, F ∝ u*³(1 − u*ₜₕ²/u*²)
 * (§3.1), с постоянной, при которой вынос непрерывен на 10 порогах. Вынос за фазу — не больше запаса грунта
 * ({@link Surface#supply}). Где рушатся дома — ещё пыль обломков (глина стен, штукатурка): её данных нет, она подобрана
 * по Хиросиме ({@link Surface#debris}, по разрушению {@link #destroyed}).
 * <p>
 * <b>Высота.</b> В фазе волны слой растёт, как турбулентный пыльный пограничный слой за фронтом (Kuhl и др. 1992,
 * ур. 17): h = c·R^(1/6)·(U_s·τ)^(5/6) — у переднего края ноль, назад толще; c = {@link #TOP_C} — видимый верх (у Kuhl
 * плотное ядро — 0,0256; GRABLE — 6–25 м у фронта в первые десятые секунды). Потом слой поднимает нагретая вспышкой
 * земля (конвективный слой с вовлечением, Deardorff/Tennekes): к h_b = √(2(1 + 2A)·f_h·E / (ρ c_p γ)) за
 * {@link #RISE_S}; E — свет вспышки на ровную землю ({@link ThermalModel}).
 * <p>
 * <b>Оседание и уход.</b> Пять корзин по размеру (по MET и MISERS BLUFF II): каждая оседает из перемешанного слоя
 * dm/dt = −v·m/h; мелкие уходят разбавлением и выносом за {@link #FINE_S} (подобрано: Хиросима, 1,42 км — темно ~20
 * минут, светлее к 30), у эпицентра пыль ещё тянет в ножку ({@link #STEM_S}). Оптика — геометрическая: k = 3Q/(2ρD),
 * толща над землёй τ = Σ k·m.
 */
public final class DustModel {
    /** Размер зёрен корзин, мкм (MET: MMD 15 мкм, MISERS BLUFF II: 25–30 мкм и 15 % массы крупнее 47 мкм). */
    static final double[] SIZE_UM = {3, 15, 40, 120, 400};
    /** Доля массы корзин. */
    static final double[] SHARE = {0.2, 0.3, 0.3, 0.15, 0.05};
    /**
     * Скорость оседания корзин, м/с: кварц 2,65 г/см³ в воздухе (Стокс с поправкой Каннингема, выше Re ≈ 0,1 — сопротивление
     * Клифта–Говена) — таблица обзора (§4), между её узлами по логарифму.
     */
    static final double[] SETTLE = {7.3e-4, 0.018, 0.118, 0.745, 3.03};
    /** Фактор ослабления крупного зерна (геометрическая оптика) и плотность зерна, кг/м³. */
    static final double EXTINCTION_Q = 2, GRAIN_DENSITY = 2650;
    /** Ослабление на массу корзин, м²/кг: k = 3Q / (2ρD). */
    static final double[] K = new double[SIZE_UM.length];
    /** Ослабление смеси, м²/кг: Σ k·доля (≈ 108; у распределения MET в обзоре — 130). */
    public static final double K_MIX;

    static {
        double sum = 0;
        for (int i = 0; i < K.length; i++) {
            K[i] = 3 * EXTINCTION_Q / (2 * GRAIN_DENSITY * SIZE_UM[i] * 1e-6);
            sum += K[i] * SHARE[i];
        }
        K_MIX = sum;
    }

    /** Шероховатость K_s пластины Шлихтинга, м (Gaj &amp; Small, ур. 7). */
    static final double ROUGHNESS = 0.05;
    /** Грунт уходит во взвесь с u* в столько порогов срыва (Owen 1964, через Gaj &amp; Small §3.2). */
    static final double SUSPEND = 10;
    /** Плотность воздуха до фронта, кг/м³. */
    static final double AIR_DENSITY = 1.2;
    /** Узлов интеграла выноса по положительной фазе. */
    private static final int LIFT_STEPS = 200;
    /** Пыль обломков: от этого давления на земле, psi, и полная — с этого (рушатся дома). */
    static final double DEBRIS_FROM_PSI = 0.6, DEBRIS_FULL_PSI = 6;
    /** Видимый верх слоя в фазе волны, доля закона Kuhl (у него плотное ядро — 0,0256). */
    static final double TOP_C = 0.1;
    /**
     * Слой над нагретой землёй: вовлечение A, доля света вспышки, что греет воздух f_h (обзор: 0,1–0,3), рост температуры
     * с высотой в устойчивом утреннем воздухе γ, К/м (3,5–9,8 К/км), ρ·c_p воздуха, Дж/(м³·К).
     */
    static final double ENTRAIN = 0.2, HEAT_SHARE = 0.2, LAPSE = 0.005, RHO_CP = 1206;
    /**
     * За сколько секунд слой дорастает до h_b (оценка: скорость конвекции w* — 1,5 м/с в 1,4 км и ~4 м/с у эпицентра у
     * 15 кт, обзор §5 — это 100–250 м за минуту).
     */
    static final double RISE_S = 60;
    /**
     * Выше этого (× ∛(Y/15)) слой над нагретой землёй не встаёт, м: у эпицентра свет вспышки на землю — десятки МДж/м², и
     * закон дал бы километры; выше — уже ножка и шапка гриба. Оценка: верх оценки обзора в 1 км (350 м).
     */
    static final double TOP_MAX = 400;
    /**
     * Мелкие уходят e-кратно за столько секунд — разбавление, вынос ветром, подсос к пожарам; подобрано по Хиросиме
     * (Hersey: Фудзии в 1,42 км — «около двадцати минут в потемневшем утре»), оседание мелких — часы.
     */
    static final double FINE_S = 1000;
    /**
     * Подсос в ножку: в пределах {@link #STEM_M}·∛(Y/15) м от эпицентра (за половиной — слабее), с {@link #STEM_FROM_S}
     * ·∛(Y/15) с, e-кратно за {@link #STEM_S} с. Оценка: Такакура в 300 м — «небо стало светлеть» через минуты.
     */
    static final double STEM_M = 1000, STEM_FROM_S = 10, STEM_S = 90;
    /** cal/см² → Дж/м². */
    private static final double CAL_PER_CM2 = 4.184e4;

    private DustModel() {
    }

    /**
     * Что лежит на земле: порог срыва u*ₜₕ (м/с, сухой грунт — Gillette 1988 в Gaj &amp; Small, табл. 1: рыхлый песок 0,28,
     * суглинистый песок 0,34, глина 0,54, суглинок 0,78; трава и посевы — от 3, §2.2), запас грунта на вынос (кг/м²) и
     * пыль обломков там, где рушится всё (кг/м²).
     */
    public record Surface(double threshold, double supply, double debris) {
        /**
         * Сухой суглинистый песок, запас — сантиметр грунта (15 кг/м²), город из дерева, глины и штукатурки — 0,5 кг/м²
         * пыли обломков (подобрано: в 1,4 км у 15 кт на 592 м темно ~20 минут, в 3,2 км — сумерки, как в Хиросиме).
         */
        public static final Surface REFERENCE = new Surface(0.34, 15, 0.5);
    }

    /** Гладкая ступень 0..1 от e0 до e1. */
    static double smooth(double e0, double e1, double x) {
        double f = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return f * f * (3 - 2 * f);
    }

    /** Доля разрушенного 0..1 по давлению на земле, psi: гладко по логарифму от 0,6 psi до 6 (рушатся дома). */
    public static double destroyed(double psi) {
        if (psi <= DEBRIS_FROM_PSI) return 0;
        return smooth(0, 1, Math.log(psi / DEBRIS_FROM_PSI) / Math.log(DEBRIS_FULL_PSI / DEBRIS_FROM_PSI));
    }

    /** B = 2,87 + 1,58·lg(x/K_s) на x м за фронтом (не ближе 6 см: ближе закон пластины не годен). */
    static double plate(double behindM) {
        return 2.87 + 1.58 * Math.log10(Math.max(behindM, 0.06) / ROUGHNESS);
    }

    /** Динамическая скорость у земли под ветром U, м/с, на x м за фронтом. */
    static double friction(double windMs, double behindM) {
        return windMs / Math.sqrt(2) * Math.pow(plate(behindM), -1.25);
    }

    /** Вынос во взвесь (u* ≥ 10 порогов), кг/(м²·с): Gaj &amp; Small, ур. 8. */
    private static double suspended(double rho, double windMs, double b, double threshold) {
        return 1.80 * rho * Math.pow(windMs, 1.24) / (Math.pow(threshold, 0.24) * Math.pow(b, 2.80));
    }

    /**
     * Вынос с земли, кг/(м²·с): ветер U (м/с) на x м за фронтом, плотность воздуха rho (кг/м³), порог срыва u*ₜₕ (м/с).
     * Ниже порога — ноль, до 10 порогов — сальтация ∝ u*³(1 − u*ₜₕ²/u*²), непрерывная со взвесью на 10 порогах.
     */
    public static double flux(double rho, double windMs, double behindM, double threshold) {
        double b = plate(behindM), us = friction(windMs, behindM);
        if (us <= threshold) return 0;
        if (us >= SUSPEND * threshold) return suspended(rho, windMs, b, threshold);
        // ветер, при котором здесь u* — ровно 10 порогов: там сальтация переходит во взвесь
        double edge = SUSPEND * threshold, windAt = edge * Math.sqrt(2) * Math.pow(b, 1.25);
        double c = suspended(rho, windAt, b, threshold) / (edge * edge * edge * (1 - 1 / (SUSPEND * SUSPEND)));
        return c * us * us * us * (1 - threshold * threshold / (us * us));
    }

    /** Плотность воздуха за фронтом давления {@code kpa}, кг/м³ (Ренкин–Гюгонио, γ = 1,4). */
    static double shockedAir(double kpa) {
        double p = 1 + Math.max(0, kpa) / BlastModel.P0;
        return AIR_DENSITY * (6 * p + 1) / (p + 6);
    }

    /**
     * Грунт, поднятый за положительную фазу (кг/м²) под давлением фронта {@code kpa}: вынос по ветру, спадающему за фазу,
     * на x = U_s·τ метров за фронтом (середины {@value #LIFT_STEPS} шагов); без предела запаса.
     */
    static double soil(double kpa, double positiveS, double threshold) {
        double u0 = BlastModel.windSpeed(kpa), front = BlastModel.frontSpeed(kpa), rho = shockedAir(kpa);
        double m = 0, dt = positiveS / LIFT_STEPS;
        for (int i = 0; i < LIFT_STEPS; i++) {
            double t = (i + 0.5) * dt, x = t / positiveS;
            m += flux(rho, u0 * (1 - x) * Math.exp(-x), front * t, threshold) * dt;
        }
        return m;
    }

    /**
     * Доля поднятого к доле s положительной фазы 0..1: импульс скоростного напора ∫(1 − x)²e^(−2x)dx (напор — как квадрат
     * ветра), на весь импульс фазы. Сразу за фронтом — 0, к концу фазы — 1; у фронта растёт как 4,6·s.
     */
    public static double lifted(double s) {
        double x = Math.max(0, Math.min(1, s)), y = 1 - x;
        return (0.25 - Math.exp(-2 * x) * (y * y / 2 - y / 2 + 0.25)) / ((1 - Math.exp(-2)) / 4);
    }

    /**
     * Слой пыли над землёй на {@code groundM} м от эпицентра: подрыв мощностью {@code yieldKt} на высоте {@code hobM}
     * (наземный — {@code surfaceBurst}), видимость воздуха {@code visibilityM} (свет вспышки), поверхность {@code surface}.
     */
    public static Column column(double groundM, double hobM, double yieldKt, boolean surfaceBurst, double visibilityM, Surface surface) {
        double hob = Math.max(0, hobM), ground = Math.max(0, groundM), slant = Math.max(1, Math.hypot(ground, hob));
        double kpa = BlastModel.overpressureKpa(slant, yieldKt), ys = Math.cbrt(yieldKt / 15);
        double positive = BlastModel.positivePhaseSeconds(yieldKt);
        double soil = Math.min(surface.supply(), soil(kpa, positive, surface.threshold()));
        double debris = surface.debris() * destroyed(BlastModel.psi(kpa));
        double wave = TOP_C * Math.pow(slant, 1 / 6.0) * Math.pow(BlastModel.frontSpeed(kpa), 5 / 6.0);
        // свет вспышки на ровную землю: от шара (не ближе его радиуса), с косинусом к вертикали
        double ball = FireballModel.maxRadius(yieldKt, surfaceBurst), high = Math.max(hob, ball);
        double lit = Math.max(Math.hypot(ground, high), ball);
        double heat = ThermalModel.fluenceCalPerCm2(lit, yieldKt, surfaceBurst, visibilityM) * CAL_PER_CM2 * high / lit;
        double buoyant = Math.min(TOP_MAX * ys, Math.sqrt(2 * (1 + 2 * ENTRAIN) * HEAT_SHARE * heat / (RHO_CP * LAPSE)));
        double stem = 1 - smooth(0.5 * STEM_M * ys, STEM_M * ys, ground);
        return new Column(positive, soil, debris, wave, buoyant, stem, STEM_FROM_S * ys);
    }

    /**
     * Докуда по земле волна поднимает пыль толщей {@code depth} и больше (оптическая, у слоя к концу фазы волны), м; дальше
     * пыли меньше. Подрыв и поверхность — как у {@link #column}.
     */
    public static double reach(double hobM, double yieldKt, boolean surfaceBurst, double visibilityM, Surface surface, double depth) {
        double hob = Math.max(0, hobM), far = BlastModel.rangeForOverpressure(BlastModel.kpa(0.1), yieldKt);
        double farGround = Math.sqrt(Math.max(0, far * far - hob * hob));
        return Solve.decreasingRoot(r -> column(r, hob, yieldKt, surfaceBurst, visibilityM, surface).lifted(), depth, 1, Math.max(farGround, 2));
    }

    /**
     * Слой пыли в одном месте: положительная фаза t₊ (с), поднятый грунт и пыль обломков (кг/м²), закон верха в фазе волны
     * (h = wave·τ^(5/6), м), верх над нагретой землёй h_b (м), доля подсоса в ножку 0..1 и когда он начинается (с).
     * Возраст — секунды модели с прихода фронта.
     */
    public record Column(double positive, double soil, double debris, double wave, double buoyant, double stem, double stemFrom) {
        /** Вся поднятая пыль, кг/м². */
        public double mass() {
            return soil + debris;
        }

        /** Верх к концу фазы волны, м. */
        public double first() {
            return wave * Math.pow(positive, 5 / 6.0);
        }

        /** Верх, до которого слой дорастает, м. */
        public double ceiling() {
            double h = first();
            return Math.sqrt(h * h + buoyant * buoyant);
        }

        /** Верх слоя в возрасте {@code age} с, м: в фазе волны — как пыльный слой Kuhl, потом — над нагретой землёй. */
        public double height(double age) {
            if (age <= 0) return 0;
            if (age <= positive) return wave * Math.pow(age, 5 / 6.0);
            double h = first(), x = Math.min((age - positive) / RISE_S, 1);
            return Math.sqrt(h * h + buoyant * buoyant * x);
        }

        /**
         * ∫dt/h от конца фазы волны до возраста {@code age} (с/м): из него оседание корзины — e^(−v·∫dt/h). Пока слой растёт,
         * h² = h₁² + b·x, и интеграл — 2(√(h₁² + b·x) − h₁)/b; потом — по постоянной высоте.
         */
        double settled(double age) {
            if (age <= positive) return 0;
            double h = first(), x = age - positive, b = buoyant * buoyant / RISE_S;
            if (b < 1e-9) return x / h;
            if (x <= RISE_S) return 2 * (Math.sqrt(h * h + b * x) - h) / b;
            double top = ceiling();
            return 2 * (top - h) / b + (x - RISE_S) / top;
        }

        /** Доля, что ещё не ушла разбавлением и в ножку, в возрасте {@code age}. */
        double left(double age) {
            return Math.exp(-Math.max(age - positive, 0) / FINE_S) * Math.exp(-stem * Math.max(age - stemFrom, 0) / STEM_S);
        }

        /** Масса корзины i над землёй в возрасте {@code age}, кг/м². */
        double bin(int i, double age, double settled, double left) {
            return SHARE[i] * mass() * DustModel.lifted(age / positive) * Math.exp(-SETTLE[i] * settled) * left;
        }

        /** Толща слоя над землёй (оптическая, по вертикали) в возрасте {@code age}: Σ k·m. */
        public double depth(double age) {
            if (age <= 0) return 0;
            double j = settled(age), left = left(age), tau = 0;
            for (int i = 0; i < K.length; i++) tau += K[i] * bin(i, age, j, left);
            return tau;
        }

        /** Толща всего, что подняла волна (без оседания и ухода) — у слоя к концу фазы волны: K·M. */
        public double lifted() {
            return K_MIX * mass();
        }

        /** Масса над землёй в возрасте {@code age}, кг/м². */
        public double massAt(double age) {
            if (age <= 0) return 0;
            double j = settled(age), left = left(age), m = 0;
            for (int i = 0; i < K.length; i++) m += bin(i, age, j, left);
            return m;
        }

        /** Сколько оседает на землю в возрасте {@code age}, кг/(м²·с): Σ v·m/h. */
        public double fallout(double age) {
            double h = height(age);
            if (h <= 0) return 0;
            double j = settled(age), left = left(age), f = 0;
            for (int i = 0; i < K.length; i++) f += SETTLE[i] * bin(i, age, j, left);
            return f / h;
        }

        /** Доля корзины i, что ещё в воздухе в возрасте {@code age} (без ухода мелких): оседание. */
        double airborne(int i, double age) {
            return Math.exp(-SETTLE[i] * settled(age));
        }
    }
}
