package ua.zentix.incoming.nuclear.model;

/**
 * Пыль, которую поднимает ударная волна (DESIGN-nuke §6): один слой от фронта до пелены, а не стена и пелена порознь.
 * Законы — из обзора литературы «Blast-lofted dust» (дизайн «ядерка уровня кино», §8); метры, секунды модели, килограммы.
 * <p>
 * <b>Вынос.</b> Давление на земле, положительная фаза t₊ на месте и доля напора вдоль земли — по высоте подрыва
 * ({@link HobBlast}). За фронтом вдоль земли дует ветер Ренкина–Гюгонио ({@link BlastModel#windSpeed}) × √доли (у
 * правильного отражения под подрывом воздух идёт к земле под углом), спадающий за фазу как (1 − τ/t₊)·e^(−τ/t₊).
 * Динамическая скорость у земли — по шероховатой пластине Шлихтинга на x метров за фронтом (Gaj &amp; Small 1991,
 * ур. 7): u* = U/√2·B^(−1,25), B = 2,87 + 1,58·lg(x/K_s).
 * С u* ≥ 10 порогов срыва грунт уходит во взвесь — вынос F = 1,80·ρ·U^1,24 / (u*ₜₕ^0,24·B^2,8) (там же, ур. 8, по
 * Mirels 1984; ~1 кг/(м²·с) при 43 м/с над рыхлым песком); от порога до 10 порогов — сальтация, F ∝ u*³(1 − u*ₜₕ²/u*²)
 * (§3.1), с постоянной, при которой вынос непрерывен на 10 порогах. Вынос за фазу — не больше запаса грунта
 * ({@link Surface#supply}). Где рушатся дома — ещё пыль обломков (глина стен, штукатурка): её данных нет, она подобрана
 * по Хиросиме ({@link Surface#debris}, по разрушению {@link #destroyed}).
 * <p>
 * <b>Высота.</b> В фазе волны слой растёт, как турбулентный пыльный пограничный слой за фронтом (Kuhl и др. 1992,
 * ур. 17): h = c·R^(1/6)·(U_s·τ)^(5/6) — у переднего края ноль, назад толще; c = {@link #TOP_C} — видимый верх (у Kuhl
 * плотное ядро — 0,0256; GRABLE — 6–25 м у фронта в первые десятые секунды). U_s·τ — путь самого фронта по своей
 * нормали за точкой (у Kuhl фронт постоянной скорости, Mirels — так же), в возрасте пыли ({@link #run}, {@link #waveTop}):
 * фронт замедляется, и это не скорость в миг прихода × время — у эпицентра наземного та за фазу дала бы тысячи
 * километров и верх в 18 км; под воздушным подрывом — не путь следа фронта по земле, который бежит быстрее фронта. Потом
 * слой поднимает нагретая вспышкой земля (конвективный слой с вовлечением, Deardorff/Tennekes): к
 * h_b = √(2(1 + 2A)·f_h·E / (ρ c_p γ)) за {@link #RISE_S}; E — свет вспышки на ровную землю ({@link ThermalModel}).
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

    /**
     * Доля пыли смеси корзин, что ещё в воздухе через {@code seconds} у слоя высотой {@code height} м: каждая корзина
     * выпадает из перемешанного слоя e-кратно за высоту / скорость оседания ({@link #SETTLE}). Облака обычных взрывов
     * ({@code client/fx/smoke/CloudModel}).
     */
    public static double aloft(double seconds, double height) {
        double h = Math.max(height, 1), sum = 0;
        for (int i = 0; i < SHARE.length; i++) sum += SHARE[i] * Math.exp(-SETTLE[i] * Math.max(seconds, 0) / h);
        return sum;
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
    public static final double TOP_C = 0.1;
    /**
     * Слой над нагретой землёй: вовлечение A, доля света вспышки, что греет воздух f_h (обзор: 0,1–0,3), рост температуры
     * с высотой в устойчивом утреннем воздухе γ, К/м (3,5–9,8 К/км), ρ·c_p воздуха, Дж/(м³·К).
     */
    static final double ENTRAIN = 0.2, HEAT_SHARE = 0.2, LAPSE = 0.005, RHO_CP = 1206;
    /**
     * За сколько секунд слой дорастает до h_b (оценка: скорость конвекции w* — 1,5 м/с в 1,4 км и ~4 м/с у эпицентра у
     * 15 кт, обзор §5 — это 100–250 м за минуту).
     */
    public static final double RISE_S = 60;
    /**
     * Выше этого (× ∛(Y/15)) слой над нагретой землёй не встаёт, м: у эпицентра свет вспышки на землю — десятки МДж/м², и
     * закон дал бы километры; выше — уже ножка и шапка гриба. Оценка: у эпицентра верха обзор не даёт, в 1 км у него
     * 120–350 м — предел чуть выше верха этой оценки, у эпицентра слой не ниже, чем в километре. У 15 кт он действует
     * ближе ~530 м к воздушному подрыву (592 м) и ~420 м к наземному (в 1 км закон даёт 222 и 127 м); там пыль и так
     * уходит в ножку ({@link #STEM_M}).
     */
    static final double TOP_MAX = 400;
    /**
     * Мелкие уходят e-кратно за столько секунд — разбавление, вынос ветром, подсос к пожарам; подобрано по Хиросиме
     * (Hersey: Фудзии в 1,42 км — «около двадцати минут в потемневшем утре»), оседание мелких — часы.
     */
    static final double FINE_S = 800;
    /**
     * Подсос в ножку: в пределах {@link #STEM_M}·∛(Y/15) м от эпицентра (за половиной — слабее), с {@link #STEM_FROM_S}
     * ·∛(Y/15) с у воздушного подрыва (у шара на земле — сразу; между радиусом шара и двумя — плавно), e-кратно за
     * {@link #STEM_S} с. Оценка: Такакура в 300 м — «небо стало светлеть» через минуты. Эту пыль получает ножка
     * ({@link StemDust}).
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
         * Сухой суглинистый песок, запас — сантиметр грунта (15 кг/м²), город из дерева, глины и штукатурки — 0,1 кг/м²
         * пыли обломков (подобрано по давлению на земле {@link HobBlast} вместе с {@link DustModel#FINE_S}: в 1,4 км
         * у 15 кт на 592 м темно ~20 минут, в 3,2 км — сумерки, как в Хиросиме).
         */
        public static final Surface REFERENCE = new Surface(0.34, 15, 0.1);
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
     * Грунт, поднятый за положительную фазу (кг/м²) под давлением фронта {@code kpa}: вынос по ветру вдоль земли (доля
     * напора {@code along}), спадающему за фазу, на x = U_s·τ метров за фронтом (середины {@value #LIFT_STEPS} шагов); без
     * предела запаса. Оценка: ветер дует положительную фазу давления {@code positiveS} и спадает по её форме
     * (1 − x)·e^(−x), а обзор (§8.1) связывает вынос с фазой скоростного напора — она обычно несколько длиннее; своей
     * длины фазы напора по высоте подрыва у модели нет.
     */
    static double soil(double kpa, double along, double positiveS, double threshold) {
        double u0 = BlastModel.windSpeed(kpa) * Math.sqrt(Math.max(0, along)), front = BlastModel.frontSpeed(kpa), rho = shockedAir(kpa);
        // закон flux, но то, что от шага не зависит, — вне цикла: клиент строит слои сотнями в миг подрыва. Сальтация —
        // c·u*³(1 − u*ₜₕ²/u*²) с c из взвеси на 10 порогах: c·u*³ = salt·u*³/b^1,25 (b^1,24·1,25 / b^2,8 = 1/b^1,25)
        double edge = SUSPEND * threshold, low = Math.pow(threshold, 0.24);
        double salt = 1.80 * rho * Math.pow(edge * Math.sqrt(2), 1.24) / (low * edge * edge * edge * (1 - 1 / (SUSPEND * SUSPEND)));
        double m = 0, dt = positiveS / LIFT_STEPS;
        for (int i = 0; i < LIFT_STEPS; i++) {
            double t = (i + 0.5) * dt, x = t / positiveS, wind = u0 * (1 - x) * Math.exp(-x);
            double b = plate(front * t), bq = Math.pow(b, 1.25), us = wind / (Math.sqrt(2) * bq);
            // ветер за фазу спадает, а слой над землёй растёт: ниже порога — и до конца фазы
            if (us <= threshold) break;
            double f = us >= edge ? 1.80 * rho * Math.pow(wind, 1.24) / (low * Math.pow(b, 2.80))
                    : salt * us * us * us * (1 - threshold * threshold / (us * us)) / bq;
            m += f * dt;
        }
        return m;
    }

    /**
     * Доля поднятого к доле s положительной фазы 0..1: импульс скоростного напора ∫(1 − x)²e^(−2x)dx (напор — как квадрат
     * ветра), на весь импульс фазы. Сразу за фронтом — 0, к концу фазы — 1; у фронта растёт как 4,6·s. Оценка: это форма
     * импульса напора, а не закона выноса, которым {@link #soil} считает всю массу (сальтация ∝ u*³, взвесь ∝ U^1,24, и
     * ниже порога — ноль): тот к концу фазы спадает быстрее, и у края клина поднятого на деле больше. Форма — одна на все
     * дальности (голова — клин {@code BlastWall}), а по закону выноса она была бы своей у каждой.
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
        double hob = Math.max(0, hobM), ground = Math.max(0, groundM);
        double kpa = BlastModel.kpa(HobBlast.psi(ground, hob, yieldKt)), ys = Math.cbrt(yieldKt / 15);
        // ветер выноса — положительная фаза давления, а не напора (оценка, soil)
        double positive = HobBlast.positivePhaseSeconds(ground, hob, yieldKt), along = HobBlast.alongGround(ground, hob, yieldKt);
        double soil = Math.min(surface.supply(), soil(kpa, along, positive, surface.threshold()));
        double debris = surface.debris() * destroyed(BlastModel.psi(kpa));
        double first = waveTop(ground, hob, yieldKt, positive);
        // свет вспышки на ровную землю: от шара (не ближе его радиуса), с косинусом к вертикали
        double ball = FireballModel.maxRadius(yieldKt, surfaceBurst), high = Math.max(hob, ball);
        double lit = Math.max(Math.hypot(ground, high), ball);
        double heat = ThermalModel.fluenceCalPerCm2(lit, yieldKt, surfaceBurst, visibilityM) * CAL_PER_CM2 * high / lit;
        double buoyant = Math.min(TOP_MAX * ys, Math.sqrt(2 * (1 + 2 * ENTRAIN) * HEAT_SHARE * heat / (RHO_CP * LAPSE)));
        double stem = 1 - smooth(0.5 * STEM_M * ys, STEM_M * ys, ground);
        // нисходящий поток шара у оси глушит подсос первые секунды — у подрыва, чей шар не касается земли; шар на земле
        // (наземный и низкий) сам уносит пыль вверх с самого начала (StemDust)
        double from = STEM_FROM_S * ys * smooth(ball, 2 * ball, hob);
        return new Column(positive, soil, debris, first, buoyant, stem, from, ground, hob, yieldKt);
    }

    /**
     * Верх слоя в фазе волны на {@code groundM} м от эпицентра по земле в возрасте пыли {@code seconds} с (с прихода
     * фронта), м: пыльный пограничный слой Kuhl, h = c·R^(1/6)·x^(5/6), R — наклонная дальность (не меньше метра), x — путь
     * фронта по своей нормали за местом ({@link #run}). За свежим фронтом — ноль.
     */
    public static double waveTop(double groundM, double hobM, double yieldKt, double seconds) {
        double slant = Math.max(1, Math.hypot(Math.max(0, groundM), Math.max(0, hobM)));
        return TOP_C * Math.pow(slant, 1 / 6.0) * Math.pow(run(groundM, hobM, yieldKt, seconds), 5 / 6.0);
    }

    /**
     * Путь фронта по своей нормали за {@code seconds} с после того, как он прошёл {@code groundM} м от эпицентра по земле,
     * м: U_s·τ у Kuhl и Mirels (у них фронт постоянной скорости) — сколько прошёл над местом сам фронт; с этой длины растёт
     * пограничный слой. Фронт замедляется: у эпицентра наземного 15 кт за фазу — 440 м, а не скорость в миг прихода × фаза
     * (2·10⁶ м). Под воздушным подрывом при правильном отражении след фронта на земле ({@link #trace}) бежит быстрее самого
     * фронта (у эпицентра — бесконечно быстро), а воздух за фронтом идёт к земле, и вдоль неё — лишь доля cos α: путь
     * следа — не путь потока. Путь по нормали — ∫cos α по пути следа ({@link #normalPath}): у правильного отражения это
     * приращение наклонной дальности падающей волны (у 15 кт на 592 м за фазу под подрывом — 189 м против 509 м следа),
     * в области Маха — путь ножки по земле, между — переход {@link HobBlast#alongGround}; cos α — корень доли напора вдоль
     * земли, той же, что у ветра выноса ({@link #soil}).
     */
    static double run(double groundM, double hobM, double yieldKt, double seconds) {
        if (seconds <= 0) return 0;
        double from = Math.max(0, groundM);
        return normalPath(from, from + trace(from, hobM, yieldKt, seconds), hobM, yieldKt);
    }

    /**
     * ∫cos α dx по земле от {@code fromM} до {@code toM} м от эпицентра, м: cos α = √{@link HobBlast#alongGround} — на
     * сколько фронт продвигается по своей нормали за шаг следа по земле. У правильного отражения (ближе 0,8 начала
     * области Маха) cos α = x/√(x² + H²), и интеграл — приращение наклонной дальности; в области Маха cos α = 1; в
     * переходе — по Симпсону. У наземного — сам путь.
     */
    static double normalPath(double fromM, double toM, double hobM, double yieldKt) {
        double hob = Math.max(0, hobM);
        if (hob <= 0 || toM <= fromM) return Math.max(0, toM - fromM);
        double xm = HobBlast.machStem(hob, yieldKt), regular = 0.8 * xm, path = 0;
        if (fromM < regular) path += Math.hypot(Math.min(toM, regular), hob) - Math.hypot(fromM, hob);
        if (toM > xm) path += toM - Math.max(fromM, xm);
        double a = Math.max(fromM, regular), b = Math.min(toM, xm);
        if (b > a) {
            int n = 8;
            double h = (b - a) / n, sum = Math.sqrt(HobBlast.alongGround(a, hob, yieldKt)) + Math.sqrt(HobBlast.alongGround(b, hob, yieldKt));
            for (int k = 1; k < n; k++) sum += (k % 2 == 1 ? 4 : 2) * Math.sqrt(HobBlast.alongGround(a + k * h, hob, yieldKt));
            path += sum * h / 3;
        }
        return path;
    }

    /**
     * Путь следа фронта по земле за {@code seconds} с после того, как он прошёл {@code fromM} м от эпицентра, м (приход —
     * {@link HobBlast#arrivalSeconds}). Ложным положением (Иллинойс), до 0,1 %.
     */
    static double trace(double fromM, double hobM, double yieldKt, double seconds) {
        if (seconds <= 0) return 0;
        double from = Math.max(0, fromM), target = HobBlast.arrivalSeconds(from, hobM, yieldKt) + seconds;
        // вдали фронт — со скоростью звука: путь не меньше; ближе — быстрее, и конец ищется удвоением
        double lo = 0, flo = -seconds, hi = BlastModel.SOUND_SPEED * seconds;
        double fhi = HobBlast.arrivalSeconds(from + hi, hobM, yieldKt) - target;
        while (fhi < 0) {
            lo = hi;
            flo = fhi;
            hi *= 2;
            fhi = HobBlast.arrivalSeconds(from + hi, hobM, yieldKt) - target;
        }
        double x = hi;
        int kept = 0;
        for (int i = 0; i < 60 && hi - lo > 1e-3 * hi; i++) {
            x = hi - fhi * (hi - lo) / (fhi - flo);
            double fx = HobBlast.arrivalSeconds(from + x, hobM, yieldKt) - target;
            if (fx == 0) return x;
            // конец, что держится второй шаг подряд, — вполовину его значения (иначе ложное положение ползёт к корню с
            // одной стороны); kept: 1 — держится hi, −1 — lo
            if (fx > 0) {
                hi = x;
                fhi = fx;
                if (kept < 0) flo *= 0.5;
                kept = -1;
            } else {
                lo = x;
                flo = fx;
                if (kept > 0) fhi *= 0.5;
                kept = 1;
            }
        }
        return x;
    }

    /**
     * Докуда по земле волна поднимает пыль толщей {@code depth} и больше (оптическая, у слоя к концу фазы волны), м; дальше
     * пыли меньше. Подрыв и поверхность — как у {@link #column}. До метра: каждый шаг — слой целиком (интеграл выноса),
     * а клиент строит пелену в миг подрыва ({@code PallField}).
     */
    public static double reach(double hobM, double yieldKt, boolean surfaceBurst, double visibilityM, Surface surface, double depth) {
        double hob = Math.max(0, hobM), far = HobBlast.rangeFor(0.1, hob, yieldKt);
        return Solve.decreasingRoot(r -> column(r, hob, yieldKt, surfaceBurst, visibilityM, surface).lifted(), depth, 1, Math.max(far, 2), 1);
    }

    /**
     * Слой пыли в одном месте: положительная фаза t₊ на месте (с), поднятый грунт и пыль обломков (кг/м²), верх к концу
     * фазы волны (м, {@link #waveTop}), верх над нагретой землёй h_b (м), доля подсоса в ножку 0..1 и когда он начинается
     * (с); само место — по земле от эпицентра и высота подрыва (м), мощность (кт): по ним верх в фазе волны. Возраст —
     * секунды модели с прихода фронта.
     */
    public record Column(double positive, double soil, double debris, double first, double buoyant, double stem, double stemFrom,
                         double ground, double hob, double yieldKt) {
        /** Вся поднятая пыль, кг/м². */
        public double mass() {
            return soil + debris;
        }

        /** Верх, до которого слой дорастает, м. */
        public double ceiling() {
            double h = first();
            return Math.sqrt(h * h + buoyant * buoyant);
        }

        /**
         * Верх слоя в возрасте {@code age} с, м: в фазе волны — пыльный слой Kuhl по пути фронта за местом в этом возрасте
         * ({@link #waveTop}; к концу фазы — {@link #first}), потом — над нагретой землёй.
         */
        public double height(double age) {
            if (age <= 0) return 0;
            if (age <= positive) return waveTop(ground, hob, yieldKt, age);
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
