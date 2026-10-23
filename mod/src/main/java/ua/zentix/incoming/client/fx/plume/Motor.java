package ua.zentix.incoming.client.fx.plume;

import org.jetbrains.annotations.Nullable;

/**
 * Двигатель как источник огня и дыма — данные паспорта ({@code ClientWeaponSpec}), из которых {@link FlameModel}
 * выводит факел, а {@link TrailModel} — след. Скорость полёта здесь не хранится: её дают сущность или путь снаряда.
 * Единицы СИ. Сопла одинаковые, у многосопловой ступени струи сливаются в одну (их рисуют одной струёй той же площади).
 *
 * @param name        имя для строк сценария и тестов
 * @param nozzles     сопел
 * @param exhaust     расход через все сопла, кг/с (у ракеты — топливо, у воздушно-реактивного и поршня — воздух с топливом)
 * @param fuel        сжигаемое топливо, кг/с: из него дым, вода и тепло
 * @param velocity    скорость истечения на срезе, м/с (у ракеты — эффективная: удельный импульс × g₀)
 * @param temperature полная температура газа (у ракеты — в камере), K
 * @param gamma       показатель адиабаты продуктов (у РДТТ с алюминием — эффективный двухфазный)
 * @param molarMass   молярная масса газа, кг/моль
 * @param exitRadius  радиус среза одного сопла, м
 * @param smoke       конденсированные продукты; null — их нет
 * @param smokeIndex  их масса на килограмм топлива
 * @param monoxide    доля CO в продуктах на срезе (догорает в воздухе)
 * @param hydrogen    доля H₂ в продуктах на срезе (догорает в воздухе)
 * @param water       водяной пар на килограмм топлива (для инверсионного следа)
 * @param heat        тепло сгорания килограмма топлива, Дж
 * @param efficiency  доля тепла, что уходит в работу тяги, а не в след (полный КПД η у Шмидта–Эпплмана)
 */
public record Motor(String name, int nozzles, double exhaust, double fuel, double velocity, double temperature, double gamma, double molarMass,
                    double exitRadius, @Nullable Smoke smoke, double smokeIndex, double monoxide, double hydrogen, double water, double heat,
                    double efficiency) {
    /** Универсальная газовая постоянная, Дж/(моль·K). */
    public static final double R = 8.31446;
    /** g₀, м/с². */
    public static final double G0 = 9.80665;

    /** Удельная теплоёмкость газа при постоянном давлении, Дж/(кг·K). */
    public double cp() {
        return gamma * R / ((gamma - 1) * molarMass);
    }

    /** Температура на срезе: полная минус кинетическая, K. */
    public double exitTemperature() {
        return Math.max(1, temperature - velocity * velocity / (2 * cp()));
    }

    /** Площадь среза всех сопел, м². */
    public double exitArea() {
        return nozzles * Math.PI * exitRadius * exitRadius;
    }

    /** Плотность газа на срезе, кг/м³. */
    public double exitDensity() {
        return exhaust / (velocity * exitArea());
    }

    /** Давление на срезе, Па. */
    public double exitPressure() {
        return exitDensity() * R / molarMass * exitTemperature();
    }

    /** Давление в камере (изоэнтропа от среза к полной температуре), Па: проверка данных на правдоподобие. */
    public double chamberPressure() {
        return exitPressure() * Math.pow(temperature / exitTemperature(), gamma / (gamma - 1));
    }

    /** Тяга при расчётном давлении на срезе, Н. */
    public double thrust() {
        return exhaust * velocity;
    }

    /** Масса конденсированных продуктов в секунду, кг/с. */
    public double smokeFlow() {
        return smoke == null ? 0 : fuel * smokeIndex;
    }

    /** Водяной пар в секунду, кг/с. */
    public double waterFlow() {
        return fuel * water;
    }

    /**
     * Тот же двигатель на доле share расхода полки — РДТТ на спаде тяги ({@code strike.ThrustCurve}): сопло в критике,
     * расход ∝ давлению в камере; температура в камере и скорость истечения те же (⚠ при падении давления сопло
     * перерасширено, и истечение немного падает), срез тот же — давление на нём и в камере падает с расходом.
     */
    public Motor throttled(double share) {
        return new Motor(name, nozzles, exhaust * share, fuel * share, velocity, temperature, gamma, molarMass, exitRadius, smoke, smokeIndex,
                monoxide, hydrogen, water, heat, efficiency);
    }

    /** Тепло, которое двигатель оставляет в воздухе за секунду, Вт. */
    public double heatFlow() {
        return fuel * heat * (1 - efficiency);
    }

    /**
     * Радиус среза сопла, на котором струя при этих расходе, скорости и температурах выходит с давлением p
     * (расчётный режим — сопло рассчитано на высоту с этим давлением).
     */
    public static double matchedRadius(int nozzles, double exhaust, double velocity, double temperature, double gamma, double molarMass, double p) {
        double cp = gamma * R / ((gamma - 1) * molarMass), te = temperature - velocity * velocity / (2 * cp);
        double rho = p * molarMass / (R * te);
        return Math.sqrt(exhaust / (nozzles * rho * velocity * Math.PI));
    }
}
