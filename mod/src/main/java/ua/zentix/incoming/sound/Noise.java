package ua.zentix.incoming.sound;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.ThrustCurve;

/**
 * Чем снаряд шумит — паспорт звука его аппарата ({@code WeaponSpec.Airframe#noise}): маршевый двигатель, стартовый
 * ускоритель (или двигатель реактивного снаряда), обтекание корпуса, бурение и разовые удары старта. Громкость каждого
 * — мощность звука L<sub>W</sub> (дБ отн. 1 пВт) по закону своего класса из чисел изделия и настоящей скорости сущности
 * ({@code StrikeProjectile#velocity}), спектр — октавы записи, которая его играет ({@link Spectra}). Слышимость на
 * сервере и громкость на клиенте считаются по одному этому паспорту ({@link Propagation}).
 *
 * @param engine     маршевый двигатель; {@code null} — нет
 * @param motor      твердотопливный ускоритель или двигатель реактивного снаряда — горит, пока {@link FlightPhase#boosterLit};
 *                   {@code null} — нет
 * @param body       корпус: шум обтекания и (сверх звука) N-волна; {@code null} — не в счёт
 * @param drill      бомба бурит грунт, A-уровень мощности, дБА; {@code NaN} — не бурит
 * @param catapult   толчок катапульты, A-уровень мощности, дБА; {@code NaN} — нет
 * @param separation отстрел ускорителя (пиропатроны, лязг замков), A-уровень мощности, дБА; {@code NaN} — нет
 */
public record Noise(@Nullable Engine engine, @Nullable Rocket motor, @Nullable Body body, double drill, double catapult, double separation) {
    /** Скорость звука, м/с. */
    public static final double SOUND = ua.zentix.incoming.nuclear.model.BlastModel.SOUND_SPEED;
    /** Мощность нуля дБ, Вт. */
    static final double PW = 1e-12;
    /** Тишина — уровень, которого нет. */
    public static final double SILENT = Double.NEGATIVE_INFINITY;

    /** Что шумит: у каждой части свой закон и своя запись. */
    public enum Part { ENGINE, MOTOR, BODY, DRILL }

    /** Маршевый двигатель. */
    public sealed interface Engine permits Propeller, Jet {
        /** Мощность звука сейчас, дБ (без взвешивания). */
        double power(FlightPhase ph, double age, double speed);

        /** Обороты против номинала (тон записи — на номинале). */
        double revs(FlightPhase ph, double age, double speed);

        Spectrum spectrum();
    }

    /**
     * Поршневой мотор или электромотор с винтом фиксированного шага. Обороты — по воздушной скорости при неизменной
     * поступи винта (J = V/nD): n = n<sub>ном</sub>·V/V<sub>ном</sub>, не меньше оборотов на месте и не больше предела;
     * мощность на валу ∝ n³ (C<sub>P</sub>·ρ·n³·D⁵ при той же поступи), звук — постоянная доля мощности: L<sub>WA</sub> =
     * L<sub>WA,ref</sub> + 10·lg(P/P<sub>ref</sub>) по записи аппарата того же класса с известным расстоянием. Тона —
     * лопасти B·n/60 и вспышки в цилиндрах: в петле на номинальных оборотах ({@code tools/build_sounds.py}).
     *
     * @param power  мощность на номинале, Вт
     * @param rpm    номинальные обороты, об/мин
     * @param blades лопастей винта
     * @param speed  воздушная скорость на номинале, м/с
     * @param idle   обороты на месте (на пусковой, полный газ) против номинала
     * @param limit  предел оборотов (раскрутка в пике) против номинала
     * @param refA   A-уровень мощности звука аппарата того же класса, дБА, при мощности {@code refPower}, Вт
     */
    public record Propeller(double power, double rpm, int blades, double speed, double idle, double limit, double refA, double refPower,
                            Spectrum spectrum) implements Engine {
        @Override
        public double revs(FlightPhase ph, double age, double v) {
            if (ph.onLauncher()) return idle;
            return Math.max(idle, Math.min(limit, v * 20 / speed));
        }

        @Override
        public double power(FlightPhase ph, double age, double v) {
            double n = revs(ph, age, v);
            return refA + 10 * Math.log10(power * n * n * n / refPower) - spectrum.aWeighting();
        }

        /** Частота лопастей на номинале, Гц. */
        public double bladePass() {
            return blades * rpm / 60;
        }
    }

    /**
     * Турбореактивный двигатель: шум струи по закону Лайтхилла — доля механической мощности струи η = K·M⁵
     * (M — число Маха струи относительно воздуха), K = 10⁻⁴ (M. J. Lighthill, «On sound generated aerodynamically», 1952–54;
     * ⚠ сверить по первоисточнику), скорость струи относительно воздуха — за вычетом скорости полёта (простейший
     * учёт полёта, (V<sub>j</sub> − V)⁷: измеренный полёт снижает шум слабее — здесь шум занижен, у B-2 на ~5 дБ), а тяга —
     * на месте (на эшелоне меньше — здесь завышен). Расход — тяга на месте / скорость струи.
     * Запускается после отделения ускорителя, обороты ротора от малого газа к полным — за {@code spool} тиков;
     * скорость струи — пропорционально оборотам.
     *
     * @param thrust тяга одного двигателя на месте, Н
     * @param jet    скорость струи на месте, м/с
     * @param count  двигателей
     * @param idle   обороты малого газа против полных
     * @param spool  тиков раскрутки
     */
    public record Jet(double thrust, double jet, int count, double idle, double spool, Spectrum spectrum) implements Engine {
        /** Коэффициент Лайтхилла. */
        static final double LIGHTHILL = 1e-4;

        @Override
        public double revs(FlightPhase ph, double age, double v) {
            if (ph.launching()) return 0;
            return ph == FlightPhase.CLIMB ? idle + (1 - idle) * Propagation.smoothstep(0, spool, age) : 1;
        }

        @Override
        public double power(FlightPhase ph, double age, double v) {
            double n = revs(ph, age, v);
            double rel = jet * n - v * 20;
            if (n <= 0 || rel <= 0) return SILENT;
            double mass = thrust / jet;
            double w = count * LIGHTHILL * Math.pow(rel / SOUND, 5) * 0.5 * mass * rel * rel;
            return 10 * Math.log10(w / PW);
        }
    }

    /**
     * Твердотопливный двигатель: звук — доля механической мощности струи η·½·F·v<sub>e</sub>, η = 0,005 ± 0,0022
     * (NASA SP-8072, «Acoustic loads generated by the propulsion system», 1971; значение — по Oliynik, Batutina, 2024).
     * Тяга во времени — кривая ускорителя ({@link ThrustCurve}: выход на режим, полка, спад к отделению), скорость
     * истечения при спаде та же, поэтому мощность струи ∝ доле тяги; без кривой — только выход на режим на поджиге
     * ({@link ThrustCurve#ignition}), дальше полка.
     *
     * @param thrust  тяга, Н
     * @param exhaust скорость истечения, м/с
     * @param curve   тяга стартового ускорителя во времени; null — двигатель горит всю фазу разгона полной тягой
     */
    public record Rocket(double thrust, double exhaust, Spectrum spectrum, @Nullable ThrustCurve curve) {
        static final double EFFICIENCY = 0.005;

        public Rocket(double thrust, double exhaust, Spectrum spectrum) {
            this(thrust, exhaust, spectrum, null);
        }

        public double power(FlightPhase ph, double age) {
            if (!ph.boosterLit()) return SILENT;
            double share = curve != null ? curve.share(ph, age) : ph == FlightPhase.IGNITION ? ThrustCurve.ignition(age) : 1;
            return share <= 0 ? SILENT : 10 * Math.log10(EFFICIENCY * 0.5 * thrust * share * exhaust / PW);
        }
    }

    /**
     * Корпус в потоке: шум обтекания — диполь Кёрла (N. Curle, 1955), W = K·ρ·U⁶·A/c³ (A — миделево сечение), где
     * K = C′²·St²·π²/48 из силы, которую срыв вихрей с частотой St·U/D даёт корпусу: C′ = 0,1, St = 0,2 — оценка,
     * одна на все корпуса (≈ 8·10⁻⁵). Тон — по числу Струхаля: ∝ U, запись звучит на {@code reference}.
     *
     * @param diameter  калибр (диаметр корпуса), м
     * @param length    длина, м
     * @param reference скорость, на которой звучит запись, м/с (оценка по записи)
     */
    public record Body(double diameter, double length, double reference, Spectrum spectrum) {
        static final double CURLE = 0.1 * 0.1 * 0.2 * 0.2 * Math.PI * Math.PI / 48;

        public double area() {
            return Math.PI * diameter * diameter / 4;
        }

        public double power(double v) {
            double u = v * 20;
            if (u <= 0) return SILENT;
            double w = CURLE * Air.DENSITY * Math.pow(u, 6) * area() / (SOUND * SOUND * SOUND);
            return 10 * Math.log10(w / PW);
        }

        /** Тон обтекания против записи. */
        public double pitch(double v) {
            return v * 20 / reference;
        }
    }

    /**
     * A-уровень мощности звука источника со спектром {@code shape}, дБА, у которого на пути {@code path} закон
     * распространения мода ({@link Propagation#level}) даёт замеренный A-уровень {@code measured}. Обратный ход того же
     * закона, а не формула разлёта со своей поправкой на землю: иначе источник, выведенный из замера, на месте самого
     * замера звучал бы не так, как замерено (шахед — на 4,9 дБ тише своего опорного сверхлёгкого над лугом, F4).
     */
    public static double fromMeasured(double measured, Spectrum shape, Propagation.Path path) {
        return measured - Propagation.level(0, shape, path, null) + shape.aWeighting();
    }

    /** Мощность звука части сейчас, дБ (без взвешивания); не шумит — {@link #SILENT}. */
    public double power(Part part, FlightPhase ph, double age, double speed, boolean drilling) {
        return switch (part) {
            case ENGINE -> engine == null || drilling ? SILENT : engine.power(ph, age, speed);
            case MOTOR -> motor == null || drilling ? SILENT : motor.power(ph, age);
            case BODY -> body == null || drilling ? SILENT : body.power(speed);
            case DRILL -> drilling && !Double.isNaN(drill) ? drill - Spectra.BOMB_DRILL.aWeighting() : SILENT;
        };
    }

    /** Спектр части. */
    public Spectrum spectrum(Part part) {
        return switch (part) {
            case ENGINE -> engine == null ? Spectra.DRONE_ENGINE : engine.spectrum();
            case MOTOR -> motor == null ? Spectra.BOOSTER_ENGINE : motor.spectrum();
            case BODY -> body == null ? Spectra.BOMB_FALL : body.spectrum();
            case DRILL -> Spectra.BOMB_DRILL;
        };
    }

    /**
     * Что от всего, что шумит сейчас, доходит до уха по пути {@code path} (без преград): октавы (A) в {@code bands}.
     *
     * @param speed скорость, блоков/тик
     * @return A-уровень всего, дБА ({@link #SILENT} — не шумит)
     */
    public double hear(FlightPhase ph, double age, double speed, boolean drilling, Propagation.Path path, double[] bands) {
        double[] sum = new double[Propagation.BANDS], part = new double[Propagation.BANDS];
        boolean any = false;
        for (Part p : Part.values()) {
            double lw = power(p, ph, age, speed, drilling);
            if (lw == SILENT) continue;
            any = true;
            Propagation.level(lw, spectrum(p), path, part);
            for (int b = 0; b < sum.length; b++) sum[b] += Math.pow(10, part[b] / 10);
        }
        double total = 0;
        for (int b = 0; b < sum.length; b++) {
            bands[b] = sum[b] > 0 ? 10 * Math.log10(sum[b]) : SILENT;
            total += sum[b];
        }
        return any ? 10 * Math.log10(total) : SILENT;
    }

    /** Насколько всё, что шумит сейчас, громче фона у уха в самой выгодной октаве, дБ (меньше нуля — не слышно). */
    public double margin(FlightPhase ph, double age, double speed, boolean drilling, Propagation.Path path) {
        // сверху: вся мощность в одной октаве, без воздуха, с наибольшей прибавкой земли и A — не слышно и так
        double sum = 0;
        for (Part p : Part.values()) {
            double lw = power(p, ph, age, speed, drilling);
            if (lw != SILENT) sum += Math.pow(10, lw / 10);
        }
        if (sum <= 0) return Double.NEGATIVE_INFINITY;
        double bound = 10 * Math.log10(sum) - Propagation.divergence(path.d()) + Propagation.MAX_GAIN - Propagation.quietest(path.weather());
        if (bound <= 0) return bound;
        double[] bands = new double[Propagation.BANDS];
        return hear(ph, age, speed, drilling, path, bands) == SILENT ? Double.NEGATIVE_INFINITY : Propagation.margin(bands, path.weather());
    }
}
