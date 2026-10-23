package ua.zentix.incoming.strike;

import org.jetbrains.annotations.Nullable;

/**
 * Изделие по ТТХ: масса, размеры, сопротивление, двигатели, настоящие скорости и высоты полёта, условия удара и боевая
 * часть одной сущности полёта ({@link WeaponSpec.Airframe#article}). Одна запись на изделие, общая для сервера и
 * клиента: звук ({@code sound.Noise}) и факелы ({@code client.ClientWeaponSpec}) берут числа двигателей отсюда.
 * Единицы — СИ (кг, м, с, Н); источник каждого числа — в Javadoc паспорта изделия ({@link WeaponSpec}); «О» — оценка,
 * выведенная законом из других чисел, с диапазоном.
 * <p>
 * Игровые скорости и высоты — в {@link WeaponSpec.Airframe} (блоки/тик, блоки): они не по физике, медленнее и ниже
 * настоящих, чтобы подлёт было видно на сотнях блоков игры; настоящие — здесь ({@link #real}). Всё у цели — заряд,
 * корпус БЧ, скорость и угол удара — настоящее при любой настройке. Полёт эти числа пока не читает (кроме данных
 * двигателей у звука и картинки): их берут шаги физики полёта и взрыва, тесты сверяют их с законами
 * ({@code WeaponSpecTest}).
 *
 * @param dryMass    масса без топлива и без сбрасываемого ускорителя, кг
 * @param launchMass масса на старте без сбрасываемого ускорителя ({@link SolidMotor#jettison}), кг: с топливом (у
 *                   РСЗО — с топливом двигателя)
 * @param length     длина корпуса, м
 * @param diameter   диаметр корпуса (у B-2 — высота), м
 * @param span       размах крыла (у бескрылых — диаметр), м
 * @param cda        площадь сопротивления C<sub>d</sub>·S до звука, м²: D = ½·ρ·v²·CdA·{@link #machFactor}(M)
 * @param sustainer  маршевый двигатель; null — нет
 * @param booster    твердотопливный ускоритель (сбрасывается) или двигатель реактивного снаряда; null — нет
 * @param real       настоящие скорости и высота полёта
 * @param impact     настоящие условия удара (у МБР — входа боеголовки в атмосферу); null — сам не бьёт (B-2)
 * @param warhead    обычная боевая часть; null — её нет (B-2 — носитель, у МБР — ядерная)
 * @param fuelAtImpact топливо в баке к удару, кг: сгорает огненным шаром взрыва (картинка и звук клиента)
 * @param penetrator корпус, который пробивает грунт и бетон ({@code warhead.Penetration}); null — изделие не пробивает
 */
public record Article(double dryMass, double launchMass, double length, double diameter, double span, double cda,
                      @Nullable Propulsion sustainer, @Nullable SolidMotor booster, Envelope real, @Nullable Impact impact,
                      @Nullable Charge warhead, double fuelAtImpact, @Nullable Penetrator penetrator) {

    /** Ускорение свободного падения, м/с². */
    public static final double G = 9.80665;

    /** Маршевый двигатель. */
    public sealed interface Propulsion permits Propeller, Turbofan {
        /** Расход топлива на номинале (на крейсере), кг/с; 0 — электромотор. */
        double fuelFlow();
    }

    /**
     * Мотор с толкающим винтом: тяга T = η·P/v ({@code formulas.md} §2), на месте — не больше, чем даёт мощность.
     *
     * @param power      мощность на валу на номинале, Вт
     * @param rpm        номинальные обороты винта, об/мин
     * @param blades     лопастей винта
     * @param efficiency КПД винта на крейсере
     * @param fuelFlow   расход топлива на номинале, кг/с (0 — электромотор на батарее)
     */
    public record Propeller(double power, double rpm, int blades, double efficiency, double fuelFlow) implements Propulsion {
        /** Тяга на воздушной скорости v, Н. */
        public double thrust(double v) {
            return efficiency * power / v;
        }
    }

    /**
     * Турбовентиляторный двигатель (ТРДД).
     *
     * @param count       двигателей
     * @param thrust      тяга одного на месте у земли, Н
     * @param airflow     расход воздуха одного на месте, кг/с
     * @param jet         скорость смешанной струи на месте, м/с (тяга ≈ расход × скорость струи)
     * @param fuelFlow    расход топлива одного на крейсере, кг/с
     */
    public record Turbofan(int count, double thrust, double airflow, double jet, double fuelFlow) implements Propulsion {}

    /**
     * Твердотопливный двигатель: тяга постоянна всё время работы (у настоящих кривая тяги — по форме заряда; открытых
     * кривых нет, взята средняя), расход ṁ = T / v<sub>e</sub>, топливо — ṁ·t.
     *
     * @param thrust  средняя тяга, Н
     * @param exhaust эффективная скорость истечения, м/с (удельный импульс × g₀)
     * @param burn    время работы, с
     * @param jettison масса сбрасываемого ускорителя целиком (топливо и корпус), кг; 0 — двигатель в корпусе снаряда
     *                (РСЗО, ЗРК): топливо выгорает из массы снаряда
     */
    public record SolidMotor(double thrust, double exhaust, double burn, double jettison) {
        /** Расход топлива, кг/с. */
        public double flow() {
            return thrust / exhaust;
        }

        /** Масса топлива, кг. */
        public double propellant() {
            return flow() * burn;
        }

        /** Полный импульс, Н·с. */
        public double impulse() {
            return thrust * burn;
        }
    }

    /**
     * Настоящие скорости и высота полёта (игровые — в {@link WeaponSpec.Airframe}, не по физике).
     *
     * @param cruise   маршевая скорость, м/с (у РСЗО — к выгоранию двигателя, у МБР — к концу первой ступени)
     * @param dive     наибольшая скорость в пикировании или на конечном участке, м/с
     * @param altitude высота полёта над рельефом на маршруте, м (у B-2 — высота сброса; 0 — у баллистических — не задана)
     */
    public record Envelope(double cruise, double dive, double altitude) {}

    /**
     * Настоящие условия удара — сброс или пуск с настоящей высоты и дальности (сверка законов, {@code WeaponSpecTest}).
     * В игре полёт — своя модель с игровыми высотами и скоростями: пробитие берёт скорость и угол удара сущности в конце
     * её полёта в мире, а не эти.
     *
     * @param speed скорость, м/с
     * @param angle угол траектории к горизонту, ° (90 — отвесно)
     */
    public record Impact(double speed, double angle) {}

    /**
     * Обычная боевая часть.
     *
     * @param tnt       заряд в тротиловом эквиваленте, кг: волна, стёкла, воронка
     * @param explosive масса ВВ, кг (тротиловый эквивалент — она × относительная сила ВВ)
     * @param caseMass  масса корпуса БЧ, кг: осколки ({@code warhead.FragmentModel})
     * @param diameter  наружный диаметр корпуса БЧ, м: цилиндр ВВ в стальной оболочке — толщина стенки и длина выходят
     *                  из масс ВВ и корпуса ({@code FragmentModel})
     */
    public record Charge(double tnt, double explosive, double caseMass, double diameter) {}

    /**
     * Корпус-пробиватель: его масса, диаметр и длина — у изделия, здесь — нос.
     *
     * @param crh радиус дуги оживальной головной части в калибрах (CRH): коэффициент носа по Янгу —
     *            {@code warhead.Penetration#ogive}
     */
    public record Penetrator(double crh) {}

    /**
     * Множитель сопротивления по числу Маха f(M) для оперённых тонких тел без опубликованных продувок (О, типовая
     * форма кривой, {@code formulas.md} §2): 1 до М0,8; рост до 1,8 к М1,05; 1,8 до М1,2; дальше спадает на 0,45 на
     * единицу Маха, но не ниже 1,1. Закон приближённый: настоящая кривая у каждого корпуса своя.
     */
    /**
     * Путь, который изделие проходит, пока горит двигатель в его корпусе (РСЗО: {@link SolidMotor#jettison} 0, скорость
     * {@link Envelope#cruise} — к выгоранию), м: разгон от схода до неё за {@link SolidMotor#burn} — v·t/2 (⚠ тяга
     * постоянна, масса убывает — настоящий разгон к концу круче, путь короче на ~10 %; скорость схода из трубы, ~50 м/с у
     * РСЗО, не берётся). У «Града» — 683 · 1,9 / 2 ≈ 650 м. 0 — двигателя в корпусе нет или он сбрасывается.
     */
    public double burnPath() {
        return booster == null || booster.jettison() > 0 ? 0 : real.cruise() * booster.burn() / 2;
    }

    public static double machFactor(double mach) {
        if (mach < 0.8) return 1;
        if (mach < 1.05) return 1 + 0.8 * (mach - 0.8) / 0.25;
        if (mach < 1.2) return 1.8;
        return Math.max(1.1, 1.8 - 0.45 * (mach - 1.2));
    }
}

