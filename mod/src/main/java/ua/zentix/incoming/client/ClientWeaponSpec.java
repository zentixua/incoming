package ua.zentix.incoming.client;

import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.client.fx.plume.NozzleGlow;
import ua.zentix.incoming.client.fx.plume.Smoke;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.client.render.WeaponModels;
import ua.zentix.incoming.client.sound.EngineSound;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.ThrustCurve;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.List;
import java.util.function.Supplier;

/**
 * Клиентская часть паспорта оружия ({@link ua.zentix.incoming.strike.WeaponSpec}): звуковые слои, голос ускорителя
 * и разовые звуки старта, сопла и факелы, сброшенный ускоритель, вид вдали ({@link FarLook}). Отдельно от общего
 * паспорта: сервер клиентские классы не грузит.
 * <p>
 * Сопла и срезы факелов — в осях снаряда (нос по +Z, центр — центр модели) и следуют за размерами моделей
 * ({@code tools/gen_models.py}): меняя модель, менять их здесь вместе с шарнирами в {@code WeaponModels} и
 * {@code noseLength} в общем паспорте.
 *
 * @param airframe   сущность, которой летит оружие
 * @param payload    вторая сущность (бомба B-2); null — её нет
 * @param booster    голос стартового ускорителя, пока он горит (слой {@code BOOSTER})
 * @param launch     разовый звук поджига у пусковой
 * @param separates  слышно отделение ускорителя (хлопок пиропатронов и лязг замков)
 * @param spent      сброшенный ускоритель; null — ускорителя нет
 */
public record ClientWeaponSpec(ClientAirframe airframe, @Nullable ClientAirframe payload, BoosterVoice booster,
                               LaunchCue launch, boolean separates, @Nullable SpentBooster spent) {

    /** Точка в осях снаряда: вправо-влево, вверх, вдоль (нос по +Z), блоков. */
    public record At(double x, double y, double z) {}

    /**
     * Модель, сопла и звук одной сущности полёта.
     *
     * @param model         модель ({@link WeaponModels}): вблизи её рисует рендерер сущности, вдали — {@code FarModels}
     * @param layers        слои звука мотора
     * @param engines       сопла маршевого двигателя (у B-2 — четыре инверсионных следа, у бомбы — срыв потока с хвоста)
     * @param boosterNozzle срез сопла ускорителя; null — ускорителя нет
     * @param boosterSmoke  размер вспышки поджига ускорителя (шахед меньше ракеты; облако старта — объём, {@code LaunchCloud})
     * @param far           огонь и дым по фазам полёта ({@link Flame} — факел вблизи и вдали) и вид вдали, где сущности у
     *                      клиента нет
     */
    public record ClientAirframe(WeaponModels.Look model, List<EngineSound.Layer> layers, List<At> engines, @Nullable At boosterNozzle, float boosterSmoke,
                                 FarLook far) {
        /** Сопло маршевого двигателя — только у оружия с соплами ({@link #engines} не пуст; у «Ланцета» их нет). */
        public At engine() {
            return engines.getFirst();
        }

        /** Откуда идут шлейфы: пока горит ускоритель — от его среза (если он есть), иначе от каждого сопла. */
        public List<At> trailNozzles(boolean booster) {
            return booster && boosterNozzle != null ? List.of(boosterNozzle) : engines;
        }

        /** Срез сопла, из которого бьёт факел фазы: ускоритель или первое маршевое. */
        public At nozzle(Stage stage) {
            return stage.booster() && boosterNozzle != null ? boosterNozzle : engine();
        }
    }

    /** Как звучит стартовый ускоритель (или двигатель), пока горит. */
    public enum BoosterVoice {
        /** Катапульта — без огня: только разовый удар и свист ({@link LaunchCue#CATAPULT}). */
        NONE,
        /** Ускоритель шахеда: поверх разового рёва старта у пусковой, уходит вместе со снарядом. */
        SOLID,
        /** Ускоритель крылатой ракеты: тот же, ниже тоном. */
        SOLID_LOW,
        /** Маленький двигатель реактивного снаряда: резкое шипение, выше тоном. */
        ROCKET,
        /** «Минитмен»: низкий рёв твердотопливной ступени, слышно за километр. */
        ICBM
    }

    /** Разовый звук поджига, когда его фронт дошёл до уха. */
    public enum LaunchCue {
        /** Нет (у МБР свой звук пуска, {@code NukeSounds}). */
        NONE,
        /** Рёв ускорителя шахеда у пусковой. */
        BOOSTER,
        /** Рёв ускорителя ракеты — ниже тоном. */
        BOOSTER_LOW,
        /** Сход реактивного снаряда с трубы: резкое «фш-ш» с треском. */
        ROCKET_TUBE,
        /** Катапульта барражирующего: удар поршня и свист направляющей. */
        CATAPULT
    }

    /**
     * Сброшенный ускоритель: сетка (поставщик: модели грузятся только в игре, паспорт читают и юнит-тесты звука) и её сдвиг.
     *
     * @param lift подъём над землёй (сетка лежит на боку)
     * @param dy   середина сетки в системе снаряда: вверх
     * @param dz   и вдоль
     */
    public record SpentBooster(Supplier<WeaponModels.Mesh> mesh, float lift, float dy, float dz) {}

    /**
     * Огонь и дым сущности полёта по фазам и её вид вдали, где её у клиента нет ({@code client.far.FarFlightView}):
     * корпус — та же модель, что вблизи ({@code client.render.FarModels}), мельче пары пикселей — мягкая тёмная точка.
     * Факел ({@link Flame}) — один вблизи и вдали: струя ({@code PlumeRenderer}) и свет. Шлейф ({@link Trail}) — лента
     * по точкам пути вблизи и вдали, одна от пуска до конца следа.
     *
     * @param size     наибольший размер корпуса (размах или длина модели), блоков: длина модели на экране (переход
     *                 точка → модель, упрощённые копии) и шар её плитки ({@code FarModels.TILE_RADIUS} × size — модель
     *                 в любой позе внутри него, {@code FarModelsTest}); крупнее пикселя точка не меньше его
     * @param area     средняя площадь силуэта, м² (по Коши — четверть поверхности: корпус πDL/4, крыло — половина
     *                 площади в плане): сколько неба корпус закрывает, когда он мельче пикселя
     * @param color    цвет корпуса на свету (вдали — смешан с дымкой)
     * @param ignition на поджиге ({@link FlightPhase#IGNITION}); null — как {@code boost}
     * @param boost    пока горит стартовый ускоритель ({@link FlightPhase#boosterLit}; у МБР и РСЗО — сам двигатель)
     * @param cruise   остальной полёт
     * @param terminal в пике ({@link FlightPhase#TERMINAL}); null — как {@code cruise}
     */
    public record FarLook(double size, double area, int color, @Nullable Stage ignition, Stage boost, Stage cruise, @Nullable Stage terminal) {
        /** Огонь и дым в фазе: на пусковой до поджига и под землёй (бомба бурит) — ничего. */
        public Stage stage(FlightPhase phase) {
            if (phase == FlightPhase.READY || phase == FlightPhase.DRILL) return Stage.NONE;
            if (phase == FlightPhase.IGNITION && ignition != null) return ignition;
            if (phase.boosterLit()) return boost;
            return phase == FlightPhase.TERMINAL && terminal != null ? terminal : cruise;
        }

        /**
         * То же на age тиков фазы (с долей тика) с тягой ускорителя по кривой thrust ({@code Airframe.thrust}: выход на
         * режим, полка, спад к отделению): пламя, свет и облако старта — по доле тяги; null — двигатель фазы горит ровно.
         */
        public Stage stage(FlightPhase phase, double age, @Nullable ThrustCurve thrust) {
            Stage s = stage(phase);
            return thrust == null || !phase.boosterLit() || s.motor == null ? s : s.at(thrust.share(phase, age));
        }
    }

    /**
     * Огонь и дым в одной фазе: какой двигатель горит и из какого сопла. Факел ({@link FlameModel}) и след
     * ({@code TrailModel}) — из данных двигателя по законам, одни вблизи и вдали.
     *
     * @param motor   двигатель; null — ничего не горит
     * @param booster горит стартовый ускоритель (сопло — {@link ClientAirframe#boosterNozzle}), иначе маршевые сопла
     * @param trail   след; null — его нет (поджиг РСЗО в трубе: след — с переднего среза)
     */
    public record Stage(@Nullable Motor motor, boolean booster, @Nullable Trail trail, double share) {
        public static final Stage NONE = new Stage(null, false, null);

        /** Двигатель горит полной тягой. */
        public Stage(@Nullable Motor motor, boolean booster, @Nullable Trail trail) {
            this(motor, booster, trail, 1);
        }

        /**
         * Та же фаза на доле share тяги полки ({@code ThrustCurve}): факел — {@link FlameModel#at(Motor, double, double)},
         * расход — {@link #flow()}; след тот же (вещество на метр — полки: ⚠ на спаде след не тоньше).
         */
        public Stage at(double share) {
            return share >= 1 && this.share >= 1 ? this : new Stage(motor, booster, trail, share);
        }

        /** Двигатель с расходом на доле тяги сейчас (облако старта); null — не горит. */
        @Nullable
        public Motor flow() {
            return motor == null || share >= 1 ? motor : share <= 0 ? null : motor.throttled(share);
        }

        /** Горит двигатель и оставляет след (точка ленты — раз в step тиков). */
        static Stage burning(Motor motor, boolean booster, int step) {
            return new Stage(motor, booster, Trail.of(motor, step));
        }

        /** Та же фаза, след которой ложится по настоящему пути выгорания изделия ({@link Trail#alongBurn}). */
        Stage alongBurn(Article article) {
            return trail == null || article.booster() == null ? this
                    : new Stage(motor, booster, trail.alongBurn(article.burnPath(), article.booster().burn()), share);
        }

        /** Факел на высоте мира y; null — не горит или не видно. */
        @Nullable
        public Flame flame(double y) {
            return motor == null ? null : FlameModel.at(motor, y - Air.SEA, share);
        }
    }

    // ---------------------------------------------------------------- двигатели
    //
    // Тяга, расход и скорость истечения — из паспорта изделия ({@link Article}: одно число на сервер и клиент, источник —
    // там); здесь — только то, что нужно картинке: температура, состав продуктов, дым. Числа — из открытых данных, где
    // они есть (источник рядом); чего нет — ⚠ оценка с диапазоном. Сопло, у которого
    // размер не опубликован, — расчётное: на срезе давление воздуха той высоты, на которую сопло рассчитано (у
    // ускорителей — земля, у первых ступеней — несколько километров). Тепло ракетного топлива в воздухе —
    // c_p·(T₀ − 288 K); КПД ракеты на старте (тяга × скорость против тепла) меньше 0,1 — взят 0.

    /**
     * Горючее в продуктах на срезе (догорает в воздухе), доли массы: смесевое с алюминием — CO ~0,22, H₂ ~0,02;
     * двухосновное — CO ~0,40, H₂ ~0,01 (⚠ типовые равновесные составы продуктов на срезе, по изделиям не опубликованы).
     */
    private static final double COMPOSITE_CO = 0.22, COMPOSITE_H2 = 0.02, DOUBLE_BASE_CO = 0.40, DOUBLE_BASE_H2 = 0.01;

    /** Доля Al₂O₃ в продуктах топлива с долей алюминия al: 2 Al → Al₂O₃ (102/54 = 1,89). */
    private static double alumina(double al) {
        return al * 102.0 / 54.0;
    }

    /** Твёрдотопливный двигатель паспорта: расход и истечение — из {@link Article.SolidMotor}. */
    private static Motor solid(String name, Article.SolidMotor m, double temperature, double gamma, double molarMass,
                               double design, Smoke smoke, double smokeIndex, double co, double h2, double water) {
        return solid(name, 1, m.flow(), m.exhaust(), temperature, gamma, molarMass, design, smoke, smokeIndex, co, h2, water);
    }

    /** Твёрдотопливный двигатель с соплом, рассчитанным на высоту design метров (давление стандартной атмосферы). */
    private static Motor solid(String name, int nozzles, double flow, double velocity, double temperature, double gamma, double molarMass,
                               double design, Smoke smoke, double smokeIndex, double co, double h2, double water) {
        double r = Motor.matchedRadius(nozzles, flow, velocity, temperature, gamma, molarMass, StandardAtmosphere.pressure(design));
        return solidWithExit(name, nozzles, flow, velocity, temperature, gamma, molarMass, r, smoke, smokeIndex, co, h2, water);
    }

    /** Твёрдотопливный двигатель с известным радиусом среза сопла exitRadius, м. */
    private static Motor solidWithExit(String name, int nozzles, double flow, double velocity, double temperature, double gamma, double molarMass,
                                       double exitRadius, Smoke smoke, double smokeIndex, double co, double h2, double water) {
        double cp = gamma * Motor.R / ((gamma - 1) * molarMass);
        return new Motor(name, nozzles, flow, flow, velocity, temperature, gamma, molarMass, exitRadius, smoke, smokeIndex, co, h2, water,
                cp * (temperature - StandardAtmosphere.T0), 0);
    }

    /** Воздушно-реактивный или поршневой: выхлоп дозвуковой, на срезе — давление воздуха у земли; молярная масса — воздуха. */
    private static Motor breathing(String name, double exhaust, double fuel, double velocity, double temperature, double gamma, double soot,
                                   double water, double heat, double efficiency) {
        double r = Motor.matchedRadius(1, exhaust, velocity, temperature, gamma, AIR_MOLAR, StandardAtmosphere.P0);
        return new Motor(name, 1, exhaust, fuel, velocity, temperature, gamma, AIR_MOLAR, r, Smoke.SOOT, soot, 0, 0, water, heat, efficiency);
    }

    /** Молярная масса воздуха, кг/моль. */
    private static final double AIR_MOLAR = 0.028_96;

    /**
     * Ускоритель шахеда ({@link WeaponSpec#DRONE_BOOSTER}): смесевое топливо с ~10 % алюминия (⚠), 3 000 K, γ 1,15,
     * 27 г/моль; сопло — на землю.
     */
    static final Motor DRONE_BOOSTER = solid("drone booster", WeaponSpec.DRONE_BOOSTER, 3_000, 1.15, 0.027, 0, Smoke.ALUMINA, alumina(0.10), COMPOSITE_CO, COMPOSITE_H2, 0.09);
    /**
     * Ускоритель «Томагавка» ({@link WeaponSpec#MISSILE_BOOSTER}): смесевое с ~16 % алюминия (⚠), 3 200 K, γ 1,14
     * (двухфазный, у топлив с алюминием 1,13–1,18), 28 г/моль; сопло — на землю.
     */
    static final Motor MISSILE_BOOSTER = solid("missile booster", WeaponSpec.MISSILE_BOOSTER, 3_200, 1.14, 0.028, 0, Smoke.ALUMINA, alumina(0.16), COMPOSITE_CO, COMPOSITE_H2, 0.09);
    /**
     * Маршевый ТРДД «Томагавка» ({@link WeaponSpec#MISSILE_TURBOFAN}: расход воздуха, струя, топливо): смешанный выхлоп
     * ~700 K (⚠); JP-10 (C₁₀H₁₆): 1,06 кг воды на кг и 42,1 МДж/кг; сажа ~0,03 г/кг (⚠); КПД 0,33 (тяга × 245 м/с
     * против тепла топлива).
     */
    static final Motor MISSILE_SUSTAINER = breathing("missile turbofan", WeaponSpec.MISSILE_TURBOFAN.airflow(),
            WeaponSpec.MISSILE_TURBOFAN.fuelFlow(), WeaponSpec.MISSILE_TURBOFAN.jet(), 700, 1.38, 3e-5, 1.06, 42.1e6, 0.33);
    /**
     * Двухтактный MD-550 шахеда (расход бензина — {@link WeaponSpec#DRONE_PISTON}): выхлоп ~70 г/с при ~1 000 K и
     * ~50 м/с (⚠); сажа и масло двухтакта ~1 г/кг (⚠); вода ~1,35 кг/кг бензина, 43,5 МДж/кг; КПД ~0,13 (0,7 винта ×
     * 37 кВт против тепла топлива).
     */
    static final Motor DRONE_ENGINE = breathing("drone piston", 0.07, WeaponSpec.DRONE_PISTON.fuelFlow(), 50, 1_000, 1.35, 1e-3, 1.35, 43.5e6, 0.13);
    /**
     * Каждый из четырёх F118-GE-100 B-2 ({@link WeaponSpec#BOMBER_TURBOFANS}: тяга на месте, расход топлива на крейсере):
     * на эшелоне ~60 кг/с выхлопа при ~450 м/с и ~700 K (⚠ оценки: воздух там вчетверо реже, чем у земли, — расход и
     * струя ниже паспортных на месте); керосин — 1,25 кг воды на кг и 43,2 МДж/кг (Schumann 1996), КПД 0,3 (там же, у
     * двигателей того поколения), сажа ~0,04 г/кг (⚠).
     */
    static final Motor BOMBER_ENGINE = breathing("bomber turbofan", 60, WeaponSpec.BOMBER_TURBOFANS.fuelFlow(), 450, 700, 1.38, 4e-5, 1.25, 43.2e6, 0.3);
    /**
     * Двигатель реактивного снаряда 9М22У (расход и истечение — {@link WeaponSpec#ROCKET_MOTOR}): двухосновное (баллиститное, ⚠ по советской практике), ~2 800 K, γ 1,22, 24 г/моль; дым — соли стабилизаторов ~2 %
     * (⚠); сопло — под калибр 122 мм, радиус среза 5,5 см (⚠): на давление у земли оно не помещается (нужно ~10 см),
     * струя недорасширена.
     */
    static final Motor ROCKET_MOTOR = solidWithExit("grad motor", 1, WeaponSpec.ROCKET_MOTOR.flow(), WeaponSpec.ROCKET_MOTOR.exhaust(), 2_800, 1.22, 0.024, 0.055, Smoke.SALTS, 0.02, DOUBLE_BASE_CO, DOUBLE_BASE_H2, 0.12);
    /**
     * Первая ступень «Минитмена III» (M55, смесевое: перхлорат аммония, алюминий, связка PBAA; тяга, топливо и время
     * работы — {@link WeaponSpec.Ascent} МБР): 340,7 кг/с при 2 652 м/с; четыре сопла, ⚠ рассчитаны на ~3 км (у земли
     * перерасширены); ~3 300 K, γ 1,14, 28 г/моль, ~16 % алюминия (⚠).
     */
    static final Motor ICBM_STAGE = solid("icbm stage", 4, WeaponSpec.NUKE.ascent().flow(), WeaponSpec.NUKE.ascent().exhaust(), 3_300, 1.14, 0.028, 3_000,
            Smoke.ALUMINA, alumina(0.16), COMPOSITE_CO, COMPOSITE_H2, 0.09);

    /**
     * РДТТ зенитной ракеты 9М96 (расход и истечение — {@link InterceptorSpec.Passport}; второй, маршевый режим — не по
     * физике, у 9М96 его в открытых данных нет): смесевое
     * с ~16 % алюминия (⚠), сопло рассчитано на землю; маршевый режим — тот же заряд и сопло, давление в камере ниже —
     * струя перерасширена.
     */
    public static final Motor SAM_BOOST = solid("sam boost", InterceptorSpec.SAM.passport().boost(), 3_200, 1.14, 0.028, 0, Smoke.ALUMINA, alumina(0.16), COMPOSITE_CO, COMPOSITE_H2, 0.09);
    public static final Motor SAM_SUSTAIN = solidWithExit("sam sustain", 1, InterceptorSpec.SAM.passport().sustain().flow(),
            InterceptorSpec.SAM.passport().sustain().exhaust(), 3_200, 1.14, 0.028, SAM_BOOST.exitRadius(), Smoke.ALUMINA,
            alumina(0.16), COMPOSITE_CO, COMPOSITE_H2, 0.09);

    /**
     * Ракета ЗРК 9М96: длина и калибр — паспорт, силуэт — πDL/4. Огонь и след — на разгоне двигатель разгона, потом
     * маршевый режим (точка ленты — каждый тик на разгоне: дуга схода крутая). За краем сущностей путь приходит
     * пакетами FarFlights: без них маршевый участок дыма вообще не рождался.
     */
    private static final FarLook SAM_FAR = new FarLook(InterceptorSpec.SAM.passport().length(),
            Math.PI * InterceptorSpec.SAM.passport().diameter() * InterceptorSpec.SAM.passport().length() / 4, 0xC8C8BE, null,
            Stage.burning(SAM_BOOST, false, 1), Stage.burning(SAM_SUSTAIN, false, 2), null);
    /** Ракета ЗРК: сопло — у хвоста модели; звук мотора — слой {@code EngineSound.Layer.SAM} ({@code InterceptorSound}). */
    public static final ClientAirframe INTERCEPTOR = new ClientAirframe(WeaponModels::interceptor, List.of(),
            List.of(new At(0, 0, -InterceptorSpec.SAM.noseLength())), null, 0, SAM_FAR);

    /** Шахед: 3,6 м, размах 2,6; дельта-крыло ~4,7 м² и корпус Ø 0,45. */
    private static final FarLook DRONE_FAR = new FarLook(3.6, 3.6, 0x3C3E42, null, Stage.burning(DRONE_BOOSTER, true, 2),
            Stage.burning(DRONE_ENGINE, false, 4), null);
    /** «Томагавк»: 5,9 м, с ускорителем 6,2, Ø 0,52, размах 2,7. */
    private static final FarLook MISSILE_FAR = new FarLook(6.2, 3.1, 0x6E7276, null, Stage.burning(MISSILE_BOOSTER, true, 2),
            Stage.burning(MISSILE_SUSTAINER, false, 4), null);
    /** B-2: размах 52 м, крыло 478 м² в плане; след каждого из четырёх двигателей — инверсия, когда она встаёт. */
    private static final FarLook BOMBER_FAR = new FarLook(52, 250, 0x2E3034, null, Stage.NONE, Stage.burning(BOMBER_ENGINE, false, 4), null);
    /** GBU-57: 6,2 м, Ø 0,8; двигателя нет — ни огня, ни следа. */
    private static final FarLook BOMB_FAR = new FarLook(6.2, 3.9, 0x55595E, null, Stage.NONE, Stage.NONE, null);
    /** «Минитмен III»: 18,3 м, Ø 1,7; ступень горит весь полёт. */
    private static final Stage ICBM_BURN = Stage.burning(ICBM_STAGE, false, 4);
    private static final FarLook ICBM_FAR = new FarLook(18.3, 24, 0xD8D6D0, null, ICBM_BURN, ICBM_BURN, null);
    /**
     * Снаряд «Града»: 2,9 м, Ø 0,12; поджиг — в трубе, след — с переднего среза; после выгорания — без огня. След — по
     * настоящему пути выгорания (~650 м, {@code Article.burnPath}) при любой скорости полёта: на игровой он ложится и после
     * факела — не по физике.
     */
    private static final FarLook ROCKET_FAR = new FarLook(2.9, 0.28, 0x4E5446, new Stage(ROCKET_MOTOR, false, null),
            Stage.burning(ROCKET_MOTOR, false, 2).alongBurn(WeaponType.ROCKET.spec().striker().article()), Stage.NONE, null);
    /** «Ланцет»: 1,65 м, Ø 0,2, два креста крыльев; катапульта и электромотор — без огня и дыма. */
    private static final FarLook LOITER_FAR = new FarLook(1.65, 0.62, 0x6A7066, null, Stage.NONE, Stage.NONE, null);

    public static final ClientWeaponSpec DRONE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::drone, List.of(EngineSound.Layer.DRONE_NEAR, EngineSound.Layer.DRONE_FAR, EngineSound.Layer.BOOSTER),
                    List.of(new At(0, 0.03, -1.95)), new At(0, -0.29, -1.95), 0.55f, DRONE_FAR),
            null, BoosterVoice.SOLID, LaunchCue.BOOSTER, true,
            new SpentBooster(() -> WeaponModels.Mesh.DRONE_BOOSTER, 0.1f, 0.29f, 1.22f));

    public static final ClientWeaponSpec MISSILE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::missile, List.of(EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR, EngineSound.Layer.MISSILE_DIVE,
                    EngineSound.Layer.MISSILE_FAR, EngineSound.Layer.MISSILE_WHISTLE, EngineSound.Layer.BOOSTER),
                    List.of(new At(0, 0, -3.2)), new At(0, 0, -3.45), 0.8f, MISSILE_FAR),
            null, BoosterVoice.SOLID_LOW, LaunchCue.BOOSTER_LOW, true,
            new SpentBooster(() -> WeaponModels.Mesh.MISSILE_BOOSTER, 0.24f, 0, 3.15f));

    public static final ClientWeaponSpec BUNKER = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::bomber, List.of(EngineSound.Layer.BOMBER_NEAR, EngineSound.Layer.BOMBER_FAR),
                    List.of(new At(3.2, 0.3, -9.5), new At(1.9, 0.3, -9.3), new At(-1.9, 0.3, -9.3), new At(-3.2, 0.3, -9.5)),
                    null, 0, BOMBER_FAR),
            new ClientAirframe(WeaponModels::bomb, List.of(EngineSound.Layer.BOMB_NEAR, EngineSound.Layer.BOMB_FAR, EngineSound.Layer.BOMB_DRILL),
                    List.of(new At(0, 0, -5.2)), null, 0, BOMB_FAR),
            BoosterVoice.SOLID, LaunchCue.BOOSTER, true, null);

    public static final ClientWeaponSpec NUKE = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::icbm, List.of(EngineSound.Layer.BOOSTER), List.of(new At(0, 0, -10)), null, 0, ICBM_FAR),
            null, BoosterVoice.ICBM, LaunchCue.NONE, false, null);

    /** РСЗО: рёв двигателя, пока горит; дальше снаряд летит по инерции и воет рассекаемым воздухом. */
    public static final ClientWeaponSpec ROCKET = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::rocket, List.of(EngineSound.Layer.BOOSTER, EngineSound.Layer.ROCKET_AIR), List.of(new At(0, 0, -1.5)),
                    null, 0, ROCKET_FAR),
            null, BoosterVoice.ROCKET, LaunchCue.ROCKET_TUBE, true, null);

    /** Барражирующий: тот же винт, но маленький электромотор — выше и тише (см. {@code EngineSound}). */
    public static final ClientWeaponSpec LOITER = new ClientWeaponSpec(
            new ClientAirframe(WeaponModels::loiter, List.of(EngineSound.Layer.LOITER_NEAR, EngineSound.Layer.LOITER_FAR, EngineSound.Layer.LOITER_DIVE,
                    EngineSound.Layer.BOOSTER), List.of(), null, 0, LOITER_FAR),
            null, BoosterVoice.NONE, LaunchCue.CATAPULT, false, null);

    /** Клиентский паспорт оружия. */
    public static ClientWeaponSpec of(WeaponType weapon) {
        return switch (weapon) {
            case DRONE -> DRONE;
            case MISSILE -> MISSILE;
            case BUNKER -> BUNKER;
            case NUKE -> NUKE;
            case ROCKET -> ROCKET;
            case LOITER -> LOITER;
        };
    }

    /**
     * Горячие сопла ускорителей по оружию: таблица остывания (десятки миллисекунд) считается один раз, в фоне, при первом
     * ускорителе — до готовности сопла нет (первые доли секунды поджига, стенки ещё холодные).
     */
    private static final java.util.Map<WeaponType, java.util.concurrent.CompletableFuture<java.util.Optional<NozzleGlow>>> NOZZLES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Горячее сопло ускорителя оружия weapon ({@link #buildNozzleGlow}), если уже посчитано; иначе null. */
    @Nullable
    public static NozzleGlow nozzleGlow(WeaponType weapon) {
        var f = NOZZLES.computeIfAbsent(weapon, w -> java.util.concurrent.CompletableFuture.supplyAsync(() -> buildNozzleGlow(w),
                net.minecraft.Util.backgroundExecutor()).whenComplete((g, ex) -> {
                    if (ex != null) ua.zentix.incoming.Incoming.LOG.error("Booster nozzle glow of {} failed: no hot nozzle", w, ex);
                }));
        return f.isDone() && !f.isCompletedExceptionally() ? f.join().orElse(null) : null;
    }

    /**
     * Горячее сопло сбрасываемого стартового ускорителя оружия weapon ({@link NozzleGlow}: двигатель ускорителя, кривая
     * тяги в игре и настоящее время работы); пусто — такого ускорителя нет.
     */
    public static java.util.Optional<NozzleGlow> buildNozzleGlow(WeaponType weapon) {
        Stage boost = of(weapon).airframe(false).far().boost();
        ThrustCurve curve = weapon.spec().airframe().thrust();
        Article.SolidMotor real = weapon.spec().airframe().article().booster();
        if (of(weapon).spent() == null || boost.motor() == null || !boost.booster() || curve == null || real == null) return java.util.Optional.empty();
        return java.util.Optional.of(new NozzleGlow(boost.motor(), curve, real.burn()));
    }

    /** Сущность полёта: носитель или вторая (бомба). */
    public ClientAirframe airframe(boolean payload) {
        return payload && this.payload != null ? this.payload : airframe;
    }
}
