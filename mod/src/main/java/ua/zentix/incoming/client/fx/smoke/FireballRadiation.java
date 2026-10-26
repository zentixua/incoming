package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.nuclear.model.FuelFireball;

import java.util.function.DoubleUnaryOperator;

/**
 * Энергия света обычного огненного шара, отдельно от его геометрии и температуры газа. Серое тело с постоянной
 * эффективной степенью черноты ε = бюджет / ∫4πR²M(T)dt; расчёт один раз при создании облака, не на кадр.
 * У ВВ бюджет видимый (400–700 нм), у топлива — всего спектра. Это разные величины.
 */
record FireballRadiation(double he, double fuel, double cooling) {
    /**
     * Видимая энергия / энергия ВВ: Христофоров, ФГВ 50(1), 2014, с.109–110, рис.2 (видимая кривая ×10),
     * DOI 10.1134/S0010508214010122: ТГ 50/50, 0,95 кг, 400–700 нм, 0,24% к t/m⅓ = 0,2 с/кг⅓.
     * https://www.sibran.ru/upload/iblock/f6e/f6e9410409c31429c6382e40eeb701df.pdf
     * ⚠ Оценка: перенос одного малого опыта на составы и заряды 10–500 кг, не их измеренная световая отдача.
     * Форма R(t), T(t) остаётся оценочной; этот закон ограничивает её интеграл, не назначает дальность света.
     */
    static final double VISIBLE_SHARE = 0.0024;
    /** Определение тротилового эквивалента, Дж/кг (NIST SP 811, ton of TNT = 4,184·10⁹ J). */
    static final double TNT_J_PER_KG = 4.184e6;
    /** Стефан–Больцман и hc/k в СИ (из определяющих h, c, k, BIPM SI Brochure). */
    static final double SIGMA = 5.670374419e-8, C2 = 1.4387768775039338e-2;
    /** Квадратура по текущей геометрии; независимый мелкий интеграл в FireballRadiationTest. */
    private static final int STEPS = 128;

    static FireballRadiation of(CloudModel m) {
        double heEnergy = integrate(0, m.heBallSeconds, t -> area(m.heBallRadius(t)) * visibleExitance(m.heBallKelvin(t)));
        double fuelEnergy = integrate(0, m.fuelBallSeconds, t -> area(m.fuelBallRadius(t)) * totalExitance(CloudModel.FUEL_BALL_K));
        boolean fuelLast = m.source.fuelKg() > 0 && m.fuelBallSeconds >= m.heBallSeconds;
        // Общий остывающий газ уже не разделён на два шара: ⚠ хвост относится к последнему горевшему шару, как его
        // радиус и начальная T в CloudModel. Он входит в тот же бюджет, а не излучает заново полным чёрным телом.
        double tail = integrate(m.burnSeconds, m.fireSeconds, t -> area(m.ballRadius(t))
                * (fuelLast ? totalExitance(m.coolKelvin(t)) : visibleExitance(m.coolKelvin(t))));
        if (fuelLast) fuelEnergy += tail;
        else heEnergy += tail;
        double he = emissivity(VISIBLE_SHARE * TNT_J_PER_KG * m.source.chargeKg(), heEnergy);
        // χr = 0,3 — та же оценка, что у тепла термика. TNO Yellow Book (2005), 6.91–6.93, 6.119:
        // Fs = 0,00325 Psv^0,32, при 1,6 МПа ≈0,314. ⚠ Бак при ударе — не измеренная BLEVE этого давления.
        double fuel = emissivity(CloudModel.RADIANT * FuelFireball.ballKg(m.source.fuelKg()) * m.source.fuel().heatJPerKg, fuelEnergy);
        return new FireballRadiation(he, fuel, fuelLast ? fuel : he);
    }

    private static double emissivity(double budget, double energy) {
        return energy > 0 ? Math.clamp(budget / energy, 0, 1) : 0;
    }

    private static double area(double r) {
        return 4 * Math.PI * r * r;
    }

    private static double integrate(double from, double to, DoubleUnaryOperator power) {
        double dt = (to - from) / STEPS, energy = 0;
        if (dt <= 0) return 0;
        for (int i = 0; i < STEPS; i++) energy += power.applyAsDouble(from + (i + 0.5) * dt) * dt;
        return energy;
    }

    static double totalExitance(double kelvin) {
        return SIGMA * Math.pow(Math.max(0, kelvin), 4);
    }

    /**
     * Планк, Вт/м² в 400–700 нм, без фотопического веса CIE. После x = hc/(λkT):
     * σT⁴·15/π⁴·∫x³/(exp(x)−1)dx. Ряд 1/(exp(x)−1) = Σexp(−nx), каждый член интегрируется точно;
     * шести членов достаточно с запасом при T ≤ 3000 K (x ≥ 6,85). Без спектрального цикла на каждом узле времени.
     */
    static double visibleExitance(double kelvin) {
        if (kelvin <= 0) return 0;
        double a = C2 / (700e-9 * kelvin), b = C2 / (400e-9 * kelvin), sum = 0;
        for (int n = 1; n <= 6; n++) sum += tail(a, n) - tail(b, n);
        return totalExitance(kelvin) * (15 / Math.pow(Math.PI, 4)) * sum;
    }

    private static double tail(double x, int n) {
        double inv = 1.0 / n;
        return Math.exp(-n * x) * inv * (x * x * x + inv * (3 * x * x + inv * (6 * x + 6 * inv)));
    }
}
