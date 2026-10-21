package ua.zentix.incoming.strike;

import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LauncherRack;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Spectra;

import java.util.function.Supplier;

/**
 * Паспорт оружия: всё, чем один вид оружия отличается от другого, — в одном описании на вид ({@link WeaponType#spec()}).
 * Пуск, взрыв, сирена, слышимость, пусковая и сами снаряды читают числа отсюда, а не держат свои копии и
 * {@code switch} по виду оружия. Клиентская часть (звуковые слои, модели) — {@code client.ClientWeaponSpec}: сервер
 * клиентские классы не грузит.
 * <p>
 * Законы наведения ({@code guidance/}) и логика фаз полёта в сущностях — не здесь: паспорт даёт им числа (скорости,
 * пределы поворота, высоты), а как лететь, решают они.
 *
 * @param siren      какая тревога у цели
 * @param sirenSeconds за сколько секунд до удара цель «видит» снаряд и включается тревога (у МБР — свой отсчёт, не этот)
 * @param salvo      залп: паузы между пусками и что ставит пульт, когда выбирают это оружие
 * @param warhead    ядерная боевая часть: всегда, по выбору или никогда
 * @param power      сила взрыва боевой части — настройка мира
 * @param launch     как пускается ({@link StrikeService#launch})
 * @param route      путь до цели: время полёта, последний прямой участок, вынос пусковой
 * @param tracking   идёт ли за движущейся целью, которую заметил игрок ({@code target.Target.Sighted})
 * @param rack       пакет пусковой у стреляющего; null — оружие с пусковой у игрока не стартует
 * @param blast      картинка и вторичные подрывы наземного взрыва ({@code Warheads})
 * @param penetrates бьёт на глубине точки (бомба ищет пещеру под целью), а не по поверхности
 * @param confirmPitch тон щелчка пульта, когда пуск принят
 * @param airframe   сущность, которой летит оружие (у B-2 — сам бомбардировщик)
 * @param payload    вторая сущность полёта: бомба, которую сбрасывает носитель; null — её нет
 * @param munition   боеприпас: предмет, который пуск снимает из инвентаря стреляющего ({@link Munitions})
 * @param ascent     разгон ракетной ступени по тяге и массе (МБР); null — у оружия его нет
 */
public record WeaponSpec(WeaponType.SirenKind siren, int sirenSeconds, Salvo salvo, Warhead warhead,
                         Supplier<ModConfigSpec.IntValue> power, Launch launch, Route route, Tracking tracking, @Nullable LauncherRack rack,
                         Blast blast, boolean penetrates, float confirmPitch, Airframe airframe, @Nullable Airframe payload,
                         Munition munition, @Nullable Ascent ascent) {

    /** Как оружие пускается. */
    public enum Launch {
        /** Шахед и крылатая ракета: с пусковой рядом со стреляющим по маршруту в обход, иначе издалека. */
        GUIDED,
        /** Реактивный снаряд РСЗО из трубы пакета, по баллистике. */
        ROCKET,
        /** Барражирующий боеприпас с катапульты: прямо к цели и круг над ней. */
        LOITER,
        /** B-2 заходит издалека и сбрасывает бомбу. */
        BOMBER,
        /** МБР: запланированный ядерный удар ({@code NuclearStrikes}). */
        ICBM
    }

    /**
     * Как оружие бьёт по движущейся цели (сущность, аппарат), которую игрок выбрал, видя её ({@code target.Target.Sighted}).
     * Приказ без правил (консоль, командный блок, ведущий, команда оператора) этого не касается: там снаряд идёт за целью.
     */
    public enum Tracking {
        /**
         * Идёт за целью, пока оператор держит её в кадре камеры этого снаряда ({@link CameraLink}); не держит — летит туда,
         * где её видели в последний раз. Камера у оператора одна, поэтому ведомый снаряд у него тоже один.
         */
        CAMERA,
        /** Бьёт туда, где цель была при приказе: за ней не идёт. */
        POINT
    }

    /**
     * Залп.
     *
     * @param gapMin пауза между пусками, тиков: от
     * @param gapMax до
     * @param count  сколько в залпе ставит пульт, когда выбирают это оружие вместо одиночного (1 — оставить как было)
     * @param spread и разброс не меньше, блоков
     */
    public record Salvo(int gapMin, int gapMax, int count, int spread) {
        static Salvo gaps(int gapMin, int gapMax) {
            return new Salvo(gapMin, gapMax, 1, 0);
        }
    }

    /**
     * Ядерная боевая часть.
     *
     * @param always     всегда ядерная (МБР: её боеприпас уже ядерный)
     * @param optional   может нести ядерную вместо обычной ({@code Loadout.Nuke#onCarrier}: ракета, бомба B-2) — тогда пуск
     *                   снимает и ядерную боевую часть ({@link Munitions#warhead()}, одна на всех носителей)
     * @param chooseBurst подрыв выбирается: воздушный или наземный (бомба рвётся под землёй — выбора нет)
     */
    public record Warhead(boolean always, boolean optional, boolean chooseBurst) {
        static final Warhead CONVENTIONAL = new Warhead(false, false, false);
    }

    /**
     * Боеприпас: что пуск снимает из инвентаря стреляющего ({@link Munitions}) — по предмету на снаряд или пакет снарядов.
     *
     * @param item   предмет (поставщик: паспорт не трогает реестр, пока его не спросят)
     * @param rounds сколько снарядов в одном предмете: больше одного — пакет, его начинают и тратят по снаряду
     */
    public record Munition(Supplier<? extends Item> item, int rounds) {
        static Munition single(Supplier<? extends Item> item) {
            return new Munition(item, 1);
        }
    }

    /** Наземный взрыв боевой части: картинка у клиентов ({@code S2C.Blast}) и то, что взрыв делает сверх главного подрыва. */
    public enum Blast {
        /** Взрыв шахеда: главный подрыв и огненный шар. */
        DRONE,
        /** Взрыв крылатой ракеты: сильнее огненный шар, кольцо огня, вторичные подрывы, свои обломки. */
        MISSILE,
        /** Взрыв реактивного снаряда РСЗО. */
        ROCKET,
        /** Наземного взрыва боевой части нет: у бомбы — подземный ({@code BunkerBlast}), у МБР — ядерный подрыв. */
        NONE
    }

    /**
     * Путь до цели.
     *
     * @param flightSeconds время полёта от пуска до цели, секунд (настройка мира): путь маршрута = скорость × время;
     *                      null — время полёта следует из дальности, а не задаётся
     * @param finalLeg      последний прямой участок перед целью — в радиусах разворота на маршевой скорости (так он
     *                      вмещает манёвр при любом масштабе {@link FlightScale}); у носителя — линия сброса в высотах
     *                      эшелона ({@link WeaponSpec#finalLeg()})
     * @param standoff      без стреляющего рядом пусковая стоит за столько блоков от цели (по направлению захода)
     * @param dispersion    рассеивание неуправляемого снаряда: СКО точки падения по каждой оси — такая доля дальности
     *                      стрельбы, не меньше блока; 0 — снаряд управляемый, точка — сама цель
     * @param error         ошибка наведения: СКО промаха по каждой оси, блоков, — снаряд целится в цель со сдвигом,
     *                      случайным для каждого пуска (спутниковая навигация, сброс с эшелона); 0 — точно в цель
     * @param capture       точка маршрута взята, когда до неё по горизонтали ближе стольких радиусов разворота на
     *                      маршевой (или она уже позади, {@code guidance.Route#update}): около одного — ближе снаряд
     *                      кружил бы вокруг точки ({@link WeaponSpec#capture()})
     * @param reach         дальность по маршруту оператора ({@link Waypoints}): путь от него через его точки до цели не
     *                      длиннее стольких блоков; 0 — по точкам оператора не летает
     */
    public record Route(@Nullable Supplier<ModConfigSpec.IntValue> flightSeconds, double finalLeg, double standoff, double dispersion,
                        double error, double capture, double reach) {
        /** Время полёта из настроек, секунд. */
        public int seconds() {
            return flightSeconds == null ? 0 : flightSeconds.get().get();
        }

        /** Летит по точкам оператора ({@link Waypoints}). */
        public boolean waypoints() {
            return reach > 0;
        }

        /** СКО точки падения по каждой оси при стрельбе на {@code range} блоков (0 — без рассеивания). */
        public double sigma(double range) {
            return dispersion > 0 ? Math.max(1, range * dispersion) : 0;
        }

        /** Промах этого пуска ({@link #error}): сдвиг по горизонтали. */
        public Vec3 miss(RandomSource random) {
            return error > 0 ? new Vec3(random.nextGaussian() * error, 0, random.nextGaussian() * error) : Vec3.ZERO;
        }
    }

    /**
     * Стартовый участок с пусковой: сколько гореть на направляющей, как разгоняет и куда к концу разгона опускает нос.
     * У изделия с ускорителем ({@link Article#booster}) разгон — по его тяге ({@link #boost}); у катапульты («Ланцет»:
     * ускорителя нет) — толчок {@code boostAccel} за тик {@code boostTicks} тиков: это пуск, он игровой.
     *
     * @param ignitionTicks ускоритель горит, снаряд ещё стоит
     * @param boostTicks    катапульта: толчок столько тиков после схода (у ускорителя не читается — работа по его времени)
     * @param boostAccel    катапульта: прирост скорости за тик, блоков/тик² (у ускорителя не читается)
     * @param railTicks     первые тики разгона нос держит угол направляющей
     * @param boostEndPitch тангаж к концу разгона (° , < 0 — нос вверх)
     */
    public record LaunchProfile(int ignitionTicks, int boostTicks, double boostAccel, int railTicks, float boostEndPitch) {
        /** Тяга ускорителя нарастает за столько тиков (воспламенение заряда, ~0,3 с — О). */
        static final double RISE_TICKS = 6;

        /**
         * Тик разгона {@code tick} (с 1): сойдя с направляющей, нос уходит к тангажу конца разгона. Ускоритель: тяга
         * паспорта (и винта, если он уже тянет) минус сопротивление и вес вдоль пути, масса — с ускорителем, убывает
         * расходом ({@code ref/formulas.md} §2); тяга нарастает за {@link #RISE_TICKS}. Катапульта — толчок. Один
         * закон у снаряда в полёте и у проверки сектора пуска ({@code LaunchSite.clearAhead}).
         *
         * @param y высота мира, блоков (плотность воздуха)
         * @return скорость после тика
         */
        public double boost(FlightController flight, double speed, int tick, Airframe air, double y) {
            if (tick > railTicks) flight.holdPitch(boostEndPitch, 0.06, 1.2, 0.12);
            double rise = Math.min(1, (tick + 1) / RISE_TICKS);
            Article a = air.article();
            Article.SolidMotor b = a.booster();
            if (b == null) return speed + boostAccel * rise;
            double t = (tick - 1) / 20.0, v = speed * 20, h = y - ua.zentix.incoming.util.Air.SEA;
            double mass = a.launchMass() + b.jettison() - b.flow() * Math.min(t, b.burn());
            double push = b.thrust() * rise + (a.sustainer() instanceof Article.Propeller ? ua.zentix.incoming.guidance.Propulsion.thrust(a, v, h) : 0);
            double accel = (push - ua.zentix.incoming.guidance.PointMass.drag(v, h, a.cda())) / mass
                    - Article.G * Math.sin(Math.toRadians(-flight.pitch()));
            return Math.max(0, speed + accel / 400);
        }

        /** Сколько тиков самое большее идёт разгон: время работы ускорителя или толчок катапульты. */
        public int burnTicks(Airframe air) {
            Article.SolidMotor b = air.article().booster();
            return b == null ? boostTicks : (int) Math.round(b.burn() * 20);
        }

        /**
         * Разгон ещё идёт после тика {@code tick} на скорости {@code speed}: не кончилось время работы, и ускоритель не
         * разогнал до маршевой скорости масштаба (у игровых скоростей — раньше, чем выгорит: игровая маршевая ниже того,
         * что даёт настоящий ускоритель, — не по физике, {@link FlightScale}).
         */
        public boolean boosting(int tick, double speed, Airframe air) {
            return tick < burnTicks(air) && (air.article().booster() == null || speed < air.cruiseSpeed());
        }
    }

    /**
     * Разгон твёрдотопливной ступени вертикального старта: тяга постоянна, масса падает по мере выгорания топлива,
     * поэтому ускорение растёт — a(t) = T / (m₀ − ṁ·t) − g, ṁ = топливо / время работы. Сопротивление воздуха и рост
     * тяги с высотой не учтены; g вычитается целиком, как при отвесном подъёме: при наклоне до {@code 90 − climbPitch}
     * от вертикали это меньше 2 % ускорения. Тангаж упрощён — открытой программы тангажа нет: до {@code pitchOverTick}
     * ракета идёт строго по оси (хвост выходит из шахты), потом плавно наклоняется к курсу цели до {@code climbPitch}.
     *
     * @param thrust        тяга, Н
     * @param launchMass    стартовая масса ракеты, кг
     * @param propellant    топливо ступени, кг
     * @param burnSeconds   время работы ступени, с
     * @param pitchOverTick с этого тика нос наклоняется к курсу цели
     * @param climbPitch    угол подъёма над горизонтом после наклона, °
     * @param ceiling       выше потолка мира на столько блоков ракета больше не нужна (дальше летит удар)
     */
    public record Ascent(double thrust, double launchMass, double propellant, double burnSeconds, int pitchOverTick, float climbPitch,
                         int ceiling) {
        /** Ускорение свободного падения, м/с². */
        static final double G = 9.81;

        /** Расход топлива, кг/с. */
        public double flow() {
            return propellant / burnSeconds;
        }

        /** Скорость истечения T / ṁ, м/с. */
        public double exhaust() {
            return thrust / flow();
        }

        /** Чистое ускорение вдоль корпуса через {@code seconds} с после старта, м/с² (после выгорания — как в конце). */
        public double accel(double seconds) {
            double t = Math.min(Math.max(seconds, 0), burnSeconds);
            return thrust / (launchMass - propellant / burnSeconds * t) - G;
        }

        /**
         * Скорость после тика разгона {@code tick} (с 1: первый тик после старта), блоков/тик: прирост — ускорение
         * начала тика, м/с² × 0,05² (блок = метр, тик = 0,05 с).
         */
        public double step(double speed, int tick) {
            return speed + accel((tick - 1) / 20.0) / 400;
        }
    }

    /**
     * Летательный аппарат: числа, которыми сущность снаряда отличается от других.
     *
     * Скорости и высоты здесь — игровые, не по физике: медленнее и ниже настоящих, чтобы подлёт было видно на сотнях
     * блоков игры; настоящие — в {@link Article#real} изделия, между ними — масштаб {@link FlightScale}: полёт берёт
     * {@link #cruiseSpeed()}, {@link #diveSpeed()}, {@link #cruiseHeight()}. Повороты — по перегрузке изделия
     * ({@link Article#loadFactor}): радиус разворота растёт как квадрат скорости.
     *
     * @param entity        тип сущности (поставщик: паспорт не трогает реестр, пока его не спросят)
     * @param gameCruise    игровая маршевая скорость, блоков/тик (не по физике)
     * @param gameDive      игровая предельная скорость в пикировании (у кого пике нет — маршевая), блоков/тик (не по физике)
     * @param climbTurnShare на наборе высоты после старта автопилот поворачивает мягче: такая доля предела разворота
     *                      (логика автопилота, не закон); 0 — не управляется
     * @param gameHeight    игровая высота крейсера, блоков (не по физике): над стартом и целью у шахеда, над целью у
     *                      «Ланцета» — круг, над рельефом у ракеты — бреющий полёт, над точкой сброса у B-2
     * @param clearance     наименьший запас высоты над рельефом, с которым снаряд возвращается в мир из полёта вне мира
     * @param reliefLookahead на сколько блоков пути вперёд автопилот смотрит рельеф полосой ({@code guidance.Autopilot.reliefAhead}
     *                      у шахеда и ракеты — с наклоном набора, у «Ланцета» — прямо по курсу без наклона); 0 — не смотрит
     * @param noseLength    полудлина корпуса: от центра до носа, блоков
     * @param health        сколько урона выдержит, прежде чем его собьют; 0 — сбить нельзя
     * @param range         запас хода, блоков, если его не задаёт план полёта ({@code guidance.Mission})
     * @param reachPad      запас дальности неконтактного взрывателя сверх шага за тик, блоков
     * @param launchProfile старт с пусковой; null — с пусковой не стартует (или у него свой старт, как у РСЗО)
     * @param minVisibleLeg последние столько блоков до цели (не меньше, {@link #visibleLeg()}) снаряд летит в мире,
     *                      когда у цели есть игрок ({@link FlightTickets#approach}); 0 — только район цели
     * @param attack        геометрия атаки управляемого снаряда
     * @param noise         чем шумит: двигатели, корпус, старт ({@link Noise}); докуда его слышно — {@link Hearing}
     * @param radar         как его видит и бьёт ЗРК ({@code defense.Radar})
     * @param article       изделие по ТТХ: масса, размеры, двигатели, настоящие скорости, перегрузка, условия удара, БЧ
     */
    public record Airframe(Supplier<EntityType<? extends StrikeProjectile>> entity, double gameCruise, double gameDive,
                           double climbTurnShare, double gameHeight, double clearance, double reliefLookahead, double noseLength,
                           float health, double range, double reachPad, @Nullable LaunchProfile launchProfile, double minVisibleLeg,
                           Attack attack, Noise noise, Signature radar, Article article) {
        /** Маршевая скорость по масштабу {@link FlightScale}, блоков/тик: по ней считается время подлёта. */
        public double cruiseSpeed() {
            return FlightScale.blend(gameCruise, article.real().cruise() / 20);
        }

        /** Предельная скорость в пикировании по масштабу, блоков/тик. */
        public double diveSpeed() {
            return FlightScale.blend(gameDive, article.real().dive() / 20);
        }

        /** Высота крейсера по масштабу, блоков. */
        public double cruiseHeight() {
            return FlightScale.blend(gameHeight, article.real().altitude());
        }

        /**
         * Наибольшее боковое ускорение в вираже без потери высоты, блоков/тик²: g·√(n² − 1) — подъёмная сила n·g держит
         * вес и гнёт путь ({@code guidance.FlightController}); 0 — не поворачивает.
         */
        public double lateral() {
            double n = article.loadFactor();
            return n > 1 ? FlightController.G * Math.sqrt(n * n - 1) : 0;
        }

        /** Предел разворота на скорости {@code speed} блоков/тик, °/тик (0 — не управляется). */
        public double turnRate(double speed) {
            return speed > 0 ? Math.toDegrees(lateral() / speed) : 0;
        }

        /** То же на наборе высоты после старта: доля {@link #climbTurnShare}. */
        public double climbTurnRate(double speed) {
            return turnRate(speed) * climbTurnShare;
        }

        /** Радиус разворота на скорости {@code speed}, блоков: v²/(g·√(n² − 1)). */
        public double turnRadius(double speed) {
            double a = lateral();
            return a > 0 ? speed * speed / a : Double.POSITIVE_INFINITY;
        }

        /** Ближе этого, блоков, атака из-за круга разворота не отменяется ({@link Attack#reattackMin}). */
        public double reattackMin() {
            return attack.reattackMin() > 0 ? attack.reattackMin() * turnRadius(cruiseSpeed()) : 0;
        }

        /** Горка начинается за столько блоков от цели ({@link Attack#terminalRange}); 0 — горки нет. */
        public double terminalRange() {
            return attack.terminalRange() > 0 ? attack.terminalRange() * turnRadius(diveSpeed()) : 0;
        }

        /** Горка — при заходе не ближе стольких блоков ({@link Attack#popUpMinRange}). */
        public double popUpMinRange() {
            return attack.popUpMinRange() > 0 ? attack.popUpMinRange() * turnRadius(diveSpeed()) : 0;
        }

        /**
         * Последние столько блоков до цели снаряд летит в мире, когда у цели есть игрок: не меньше паспортной
         * {@link #minVisibleLeg} и вся горка с запасом {@link #VISIBLE_POP_UP} — её видно у цели при любом масштабе.
         */
        public double visibleLeg() {
            return minVisibleLeg > 0 ? Math.max(minVisibleLeg, VISIBLE_POP_UP * terminalRange()) : 0;
        }

        /** Полоса подлёта длиннее начала горки во столько раз. */
        public static final double VISIBLE_POP_UP = 1.2;
    }

    /**
     * Снаряд на экране радара ЗРК ({@code defense.Radar}): с какой дальности его видно и тратит ли ЗРК на него ракету.
     *
     * @param visibility доля дальности радара, с которой снаряд виден: 1 — со всей, 0.15 — малозаметный B-2 только
     *                   вблизи; крылатую ракету на бреющем и мелкий «Ланцет» радар берёт ближе шахеда
     * @param intercept  ЗРК тратит на него ракету-перехватчик: шахед, ракета, «Ланцет», B-2. МБР — не цель ЗРК: ПВО мода
     *                   против ударов по месту, а перехват МБР — другая система; неуправляемый снаряд «Града» не стоит
     *                   ракеты, падающая бомба — поздно
     */
    public record Signature(double visibility, boolean intercept) {}

    /**
     * Геометрия атаки управляемого снаряда — в радиусах разворота ({@link Airframe#turnRadius}): манёвры (горка, выход
     * из пике, повторный заход) — дуги радиуса v²/(g·√(n² − 1)), поэтому при любом масштабе скорости их форма та же.
     * Сами доли подобраны по картинке и сценариям полёта и держат запас на то, чего формула не видит (набор угловой
     * скорости, разгон в пике, рельеф); их связь с выводимыми числами проверяет {@code WeaponSpecTest}.
     *
     * @param reattackMin   ближе этого (по горизонтали, радиусов на маршевой) атака из-за круга разворота не отменяется:
     *                      малый промах добирает неконтактный взрыватель, а пролетев, снаряд зайдёт снова. Меньше
     *                      радиуса разворота
     * @param terminalRange горка перед пикированием начинается за столько радиусов на скорости пике от цели (0 — горки
     *                      нет): не ближе геометрии горки (подъём с бреющего полёта до вершины и пике с неё под углом
     *                      входа) с запасом на переход по тангажу
     * @param popUpMinRange горка только при заходе хотя бы с такого расстояния (радиусов на скорости пике): ближе ракете
     *                      не хватит места набрать высоту; больше {@code terminalRange} на путь, за который ракета
     *                      выходит на курс атаки
     */
    public record Attack(double reattackMin, double terminalRange, double popUpMinRange) {
        static final Attack NONE = new Attack(0, 0, 0);
    }

    /** Запас радиуса разворота, внутри которого точку не достать ({@code guidance.Autopilot.insideTurn}): угловая скорость набирается не сразу. */
    public static final double TURN_MARGIN = 1.2;

    /** Радиус разворота: скорость v блоков/тик при угловой скорости ω °/тик — r = v / ω (ω в радианах). */
    public static double turnRadius(double speed, double rateDeg) {
        return speed / Math.toRadians(rateDeg);
    }

    /** За сколько тиков до удара включается тревога у цели. */
    public int sirenLead() {
        return sirenSeconds * 20;
    }

    /** Сила взрыва боевой части (настройка мира). */
    public float blastPower() {
        return power.get().get();
    }

    /**
     * Последний прямой участок перед целью, блоков ({@link Route#finalLeg}): у управляемых — столько радиусов разворота
     * на маршевой скорости, у носителя (B-2, сбрасывает {@link #payload}) — линия сброса в высотах эшелона.
     */
    public double finalLeg() {
        if (payload != null) return route.finalLeg() * airframe.cruiseHeight();
        double r = airframe.turnRadius(airframe.cruiseSpeed());
        return Double.isFinite(r) ? route.finalLeg() * r : 0;
    }

    /** Точка маршрута взята ближе стольких блоков по горизонтали ({@link Route#capture}). */
    public double capture() {
        double r = airframe.turnRadius(airframe.cruiseSpeed());
        return Double.isFinite(r) ? route.capture() * r : 0;
    }

    /** Сущность полёта, которая бьёт по цели: бомба у B-2, иначе сам аппарат. */
    public Airframe striker() {
        return payload != null ? payload : airframe;
    }

    /**
     * Заряд наземного взрыва в тротиловом эквиваленте, кг, — боевая часть изделия, которое бьёт ({@link Article.Charge#tnt}):
     * по давлению его волны наземный взрыв выбивает стёкла ({@code Warheads}), по нему же картинка и звук взрыва у
     * клиента; 0 — наземного взрыва нет ({@link Blast#NONE}: бомба рвётся под землёй, у МБР — ядерный подрыв).
     */
    public double charge() {
        Article.Charge w = striker().article().warhead();
        return blast == Blast.NONE || w == null ? 0 : w.tnt();
    }

    /**
     * Топливо в баке к удару, кг ({@link Article#fuelAtImpact}): сгорает огненным шаром взрыва (картинка и звук клиента).
     * Данные, а не логика полёта: полёт его не читает.
     */
    public double fuelAtImpact() {
        return striker().article().fuelAtImpact();
    }

    /** Пауза между пусками в залпе, тиков. */
    public int salvoGap(RandomSource random) {
        return salvo.gapMin() + random.nextInt(salvo.gapMax() - salvo.gapMin() + 1);
    }

    // ---- двигатели изделий ({@link Article}): одно число на сервер, звук ({@link Noise}) и факелы клиента
    //      ({@code client.ClientWeaponSpec}); «О» — оценка, выведена законом из других чисел, с диапазоном

    /**
     * MD-550 шахеда — копия Limbach L550E: двухтактный оппозитный четырёхцилиндровый 548 см³, 37 кВт (50 л. с.) на
     * 7500 об/мин (Wikipedia «Limbach L550E»), толкающий двухлопастной винт прямо на валу; КПД винта на крейсере 0,7
     * (О, 0,6–0,75); расход — удельный 0,45 кг/(кВт·ч) двухтакта этого класса (О, 0,4–0,5: у изделия не опубликован) —
     * 4,6 г/с бензина.
     */
    public static final Article.Propeller DRONE_PISTON = new Article.Propeller(37_000, 7500, 2, 0.7, 37_000 * 0.45 / 3.6e6);

    /**
     * Ускоритель шахеда (РДТТ под хвостом, сбрасывается): горит ~2–3 с (по видео пусков, Militarnyi), тяга не
     * опубликована — О: 4,2 кН (3–6 кН) — ~215 кг с ускорителем до ~35 м/с за 2,5 с под 15°; истечение 2100 м/с
     * (удельный импульс ~215 с малого РДТТ); ускоритель целиком ~15 кг (О).
     */
    public static final Article.SolidMotor DRONE_BOOSTER = new Article.SolidMotor(4200, 2100, 2.5, 15);

    /**
     * Маршевый ТРДД «Томагавка» Williams F107-WR-402: 3,1 кН (700 фунтов), удельный расход 0,683 фунт/(фунт·ч)
     * (Wikipedia «Williams F107») — 0,060 кг/с; расход воздуха не опубликован — О: 6,1 кг/с при струе ~510 м/с
     * (двухконтурность ~1, тяга ≈ расход × струя).
     */
    public static final Article.Turbofan MISSILE_TURBOFAN = new Article.Turbofan(1, 3100, 6.1, 510, 3100 / Article.G * 0.683 / 3600);

    /**
     * Ускоритель «Томагавка» Atlantic Research Mk 106: 26,7 кН 12 с (designation-systems.net, «Raytheon BGM-109
     * Tomahawk»); истечение ~2350 м/с (О: удельный импульс смесевого топлива ~240 с) — 136 кг топлива; ускоритель целиком
     * ~270 кг (О: 1,6 т с ним против 1,3 т без, Wikipedia «Tomahawk (missile)»).
     */
    public static final Article.SolidMotor MISSILE_BOOSTER = new Article.SolidMotor(26_700, 2350, 12, 270);

    /**
     * B-2: четыре ТРДД F118-GE-100 по 77 кН (17 300 фунтов, Wikipedia «Northrop B-2 Spirit»); на месте ~135 кг/с воздуха
     * и струя ~570 м/с (О: двухконтурность 0,76, тяга ≈ расход × струя); на крейсере ~0,25 кг/с керосина каждый (О).
     */
    public static final Article.Turbofan BOMBER_TURBOFANS = new Article.Turbofan(4, 77_000, 135, 570, 0.25);

    /**
     * Двигатель 9М22У «Града»: время работы, импульс и топливо не опубликованы — О: 20 кг топлива (66,6 → 46,6 кг; у
     * современной замены EDePro G-2000 — 27 кг смесевого, 62,5 кН·с за 2,7 с), истечение 2000 м/с (690 м/с к выгоранию
     * по Циолковскому требуют не меньше 1930; у двухосновных 200–230 с), работа 1,9 с (1,5–2,5) — 21 кН.
     */
    public static final Article.SolidMotor ROCKET_MOTOR = new Article.SolidMotor(20 / 1.9 * 2000, 2000, 1.9, 0);

    /**
     * «Ланцет-3»: электромотор с толкающим двухлопастным винтом; мощность не опубликована — О: на сопротивление при
     * 110 км/ч ~0,5 кВт, на валу ~0,8 кВт при КПД винта ~0,65; ~9000 об/мин (О: винт ~0,36 м с концами на 0,5 М). Батарея
     * — расхода топлива нет.
     */
    public static final Article.Propeller LOITER_MOTOR = new Article.Propeller(800, 9000, 2, 0.65, 0);

    /** Разгон первой ступени «Минитмена III» ({@link Ascent}, числа и источники — в {@link #NUKE}). */
    static final Ascent ICBM_ASCENT = new Ascent(903_700, 36_030, 20_780, 61, 60, 85, 320);

    // ---- изделия по ТТХ ({@link Article}); игровые скорости и высоты в {@link Airframe} — не по физике

    /**
     * Shahed-136: 200 кг (у «Герани-2» до 240), длина 3,5 м, размах 2,5 м, до ~185 км/ч = 51 м/с — наибольшая, на полной
     * мощности (Wikipedia «HESA Shahed 136»); корпус Ø ~0,5 м (О по фото). Топливо на старте — О: ~50 кг (30–70; бак 71 л
     * назван только у варианта с БЧ 90 кг). Открытые данные здесь расходятся: на полной мощности 50 кг хватает на
     * ~550 км (4,6 г/с), заявленные 2000–2500 км требуют втрое больше топлива или полёта на малом газу — дальность по
     * расходу считает шаг полёта (S2), здесь она не задаётся. CdA 0,3 м² (О, 0,27–0,34: 37 кВт × 0,7 винта при 51 м/с у земли). Высота — с 2025 года крейсер 1,5–2,8 км
     * и пике с 2–2,5 км; заявлено 60–4000 м (ГУР МО Украины, разборы Euromaidan Press 06.2025, Gwara Media) — взято
     * 2000 м. Пике — О: отвесно ~105 м/с, удар под 60° ~99 м/с (баланс тяги винта, веса и сопротивления,
     * {@code WeaponSpecTest}). БЧ — 50 кг (оценки 30–50; у поздних «Гераней» 90 — Wikipedia, ISIS): решение Артёма —
     * исходный шахед с 50 кг ТНТ; доля ВВ не опубликована — О: ~40 кг ВВ × 1,25 и ~10 кг корпуса. Топливо к удару —
     * О: 30 кг бензина (10–50, по пройденному пути).
     * Перегрузка — О: 3,8, предел нормальной категории лёгких самолётов (14 CFR 23.337(a)(1): 2,1 + 24 000/(W + 10 000)
     * при весе W фунтов, но не больше 3,8; у ~440 фунтов шахеда — 4,4 → 3,8): данных изделия нет.
     */
    static final Article DRONE_ARTICLE = new Article(150, 200, 3.5, 0.5, 2.5, 0.3, DRONE_PISTON, DRONE_BOOSTER,
            new Article.Envelope(51.4, 105, 2000), new Article.Impact(99, 60), new Article.Charge(50, 40, 10), 30, 3.8);

    /**
     * BGM-109 «Томагавк» Block IV: 1,3 т без ускорителя, 5,56 м, Ø 0,52 м, размах 2,67 м, М0,74 ≈ 250 м/с (880 км/ч),
     * бреющий полёт 30–50 м над рельефом (Wikipedia «Tomahawk (missile)», designation-systems.net). Топливо (TH-dimer,
     * как JP-10) не опубликовано — О: ~385 кг (1600 км × 0,060 кг/с ÷ 250 м/с). CdA 0,078 м² (О: тяга 3,1 кН равна
     * сопротивлению на 250 м/с у земли). БЧ WDU-36/B — 310 кг (designation-systems: 340), из них 120 кг PBXN-107
     * (Wikipedia по PEO U&W): решение Артёма 18.10 — ~150 кг ТНТ (О: относительная сила PBXN-107 ~1,25). Конечный
     * участок — горка и пике или прямо; угол открыто не назван — О: 45° на крейсерской скорости. Топливо к удару — О:
     * 275 кг (200–350, сотни кг и размер огненного шара).
     * Перегрузка — О: 3 (2–4) — подъёмная сила на 250 м/с у земли (скоростной напор 38 кПа): крыло ~0,61 м² (размах
     * 2,67 м без корпуса 0,62 м при хорде ~0,3 м — оценка патента US 11187505) с C<sub>L max</sub> ~1 и поперечное
     * обтекание корпуса под 15° (~0,25 м²) — ~33 кН на ~11 кН веса; данных изделия нет.
     */
    static final Article MISSILE_ARTICLE = new Article(925, 1310, 5.56, 0.52, 2.67, 0.078, MISSILE_TURBOFAN, MISSILE_BOOSTER,
            new Article.Envelope(250, 250, 40), new Article.Impact(250, 45), new Article.Charge(150, 120, 190), 275, 3);

    /**
     * B-2 Spirit: пустой 71,7 т, взлётная до 170,6 т, длина 21 м, высота 5,2 м, размах 52,4 м, крейсер М0,85 — на
     * 12 км ~250 м/с, потолок 15,2 км (Wikipedia «Northrop B-2 Spirit»); высота сброса — О: 12 км (9–12 км у высотного
     * бомбардировщика). CdA — О: ~4 м² (C<sub>d0</sub> ~0,008 летающего крыла на 478 м²). Сам не бьёт: удар — у бомбы.
     * Перегрузка — О: 2,5, предел манёвра транспортной категории (14 CFR 25.337(b)): данных изделия нет.
     */
    static final Article BOMBER_ARTICLE = new Article(71_700, 170_600, 21, 5.2, 52.4, 4, BOMBER_TURBOFANS, null,
            new Article.Envelope(250, 250, 12_000), null, null, 0, 2.5);

    /**
     * GBU-57A/B MOP: класс 30 000 фунтов (13,6 т; корпус BLU-127 с ВВ — 12 304 кг), 6,2 × 0,8 м, ВВ 2423 кг: 2082 кг
     * AFX-757 и 341 кг PBXN-114 (Wikipedia «GBU-57A/B MOP» по DTRA). Тротиловый эквивалент — О: равен массе ВВ
     * (относительная сила AFX-757 открыто не названа), корпус ~9,9 т стали. Сброс и удар открыто не названы — О:
     * с 12 км на 250 м/с при C<sub>d</sub> 0,3 (0,2–0,35, CdA 0,15 м²) бомба падает ~50 с и бьёт на 511 м/с (506–522)
     * под 64° (точка с массой в стандартной атмосфере, {@code WeaponSpecTest}). Топлива нет.
     * Перегрузка — О: 0,3 — корпус без крыла на ~400 м/с в середине падения даёт 0,1–0,4 (поперечное обтекание);
     * падение по ней считает шаг бомбы (S5).
     */
    static final Article BOMB_ARTICLE = new Article(13_600, 13_600, 6.2, 0.8, 0.8, 0.15, null, null,
            new Article.Envelope(250, 511, 12_000), new Article.Impact(511, 64), new Article.Charge(2423, 2423, 9_900), 0, 0.3);

    /**
     * LGM-30G «Минитмен III»: 36 030 кг, 18,2 × 1,67 м (USAF Fact Sheet); мод ведёт только первую ступень
     * ({@link #ICBM_ASCENT}), масса без её топлива — 15 250 кг. CdA — О: 0,66 м² (C<sub>d</sub> ~0,3 на 2,19 м²).
     * К выгоранию первой ступени — О: ~1680 м/с (Циолковский минус тяжесть, без сопротивления). Вход боеголовки — О:
     * ~7,2 км/с под 22,5° к горизонту (наименьшая энергия на ~10 000 км: γ = (180° − 90°)/4, v по закону Кеплера);
     * Wikipedia «LGM-30 Minuteman» называет 7,83 км/с на конечном участке. Боевая часть ядерная — обычной нет.
     * Перегрузки манёвра нет (0): на разгоне ракету ведут рули двигателя.
     */
    static final Article ICBM_ARTICLE = new Article(ICBM_ASCENT.launchMass() - ICBM_ASCENT.propellant(), ICBM_ASCENT.launchMass(), 18.2, 1.67, 1.67, 0.66, null, null,
            new Article.Envelope(1680, 7200, 0), new Article.Impact(7200, 22.5), null, 0, 0);

    /**
     * 9М22У «Града»: 66,6 кг, 2,87 м, 122 мм, дальность 5–20,38 км, ~690 м/с (Wikipedia «BM-21 Grad»); двигатель —
     * {@link #ROCKET_MOTOR}. CdA до звука — О: 0,0034 м² (C<sub>d</sub> 0,29): с ним и кривой {@link Article#machFactor}
     * наибольшая дальность — 20,4 км при угле 53°, к выгоранию 683 м/с, полёт 77 с, апогей 7,2 км, удар 337 м/с под 62°
     * ({@code WeaponSpecTest}). БЧ — 18,4 кг, из них 6,4 кг ВВ (Wikipedia, ru: «Град (РСЗО)»); ТНТ — по массе ВВ
     * (как было), корпус 12 кг.
     * Не управляется (перегрузка 0): после схода — только баллистика.
     */
    static final Article ROCKET_ARTICLE = new Article(46.6, 66.6, 2.87, 0.122, 0.122, 0.0034, null, ROCKET_MOTOR,
            new Article.Envelope(683, 683, 0), new Article.Impact(337, 62), new Article.Charge(6.4, 6.4, 12), 0, 0);

    /**
     * «Ланцет-3»: 12 кг, БЧ 3 кг, 80–110 км/ч, в пике до ~300 км/ч с 600–800 м (Wikipedia «ZALA Lancet», Army
     * Recognition); длина 1,65 м (С); Ø ~0,2 и размах ~1,2 м (О по модели). CdA — О: 0,028 м² (отвесное пике 300 км/ч
     * при 12 кг). Удар — О: под 70° на ~82 м/с (баланс веса, тяги и сопротивления, {@code WeaponSpecTest}). Доля ВВ в БЧ
     * не опубликована — О: ~2,3 кг ВВ × 1,3 = 3 кг ТНТ, корпус 0,7 кг. Топлива нет (батарея).
     * Перегрузка — О: 3,8, тот же предел лёгкого летательного аппарата, что у шахеда (14 CFR 23.337): данных изделия нет.
     */
    static final Article LOITER_ARTICLE = new Article(12, 12, 1.65, 0.2, 1.2, 0.028, LOITER_MOTOR, null,
            new Article.Envelope(30.6, 83, 700), new Article.Impact(82, 70), new Article.Charge(3, 2.3, 0.7), 0, 3.8);

    // ---- паспорта звука ({@link Noise}): законы источников из чисел двигателей выше; «О» — оценка по классу

    /**
     * Шахед ({@link #DRONE_PISTON}): лопасти 250 Гц, вал 125 Гц; номинал звука — крейсер 185 км/ч = 51 м/с (Wikipedia
     * «HESA Shahed 136»). Громкость — по классу: сверхлёгкие самолёты 15–30 л. с. на полном
     * газу дают ~70 дБА в 150 м под собой (F. W. J. van Deventer, «Noise aspects of ultralights and model airplanes»,
     * Proc. IOA): L<sub>WA</sub> = 70 + 20·lg 150 + 11 − 3 (отражение от земли у микрофона) ≈ 121,5 дБА на ~16,4 кВт
     * (22 л. с.). На месте полный газ — 0,8 номинала оборотов, в пике раскрутка не выше 1,1 (О). Корпус — из {@link #DRONE_ARTICLE}; в записи
     * обтекания — его крейсер. Отстрел ускорителя ~125 дБА (О).
     */
    static final Noise DRONE_NOISE = new Noise(
            new Noise.Propeller(DRONE_PISTON.power(), DRONE_PISTON.rpm(), DRONE_PISTON.blades(), DRONE_ARTICLE.real().cruise(), 0.8, 1.1, 121.5, 16_400, Spectra.DRONE_ENGINE),
            new Noise.Rocket(DRONE_BOOSTER.thrust(), DRONE_BOOSTER.exhaust(), Spectra.BOOSTER_ENGINE),
            new Noise.Body(DRONE_ARTICLE.diameter(), DRONE_ARTICLE.length(), DRONE_ARTICLE.real().cruise(), Spectra.BOMB_FALL), Double.NaN, Double.NaN, 125);

    /**
     * «Томагавк» ({@link #MISSILE_TURBOFAN}, {@link #MISSILE_BOOSTER}): раскрутка от малого газа 0,55 за 2,5 с (О). Корпус —
     * {@link #MISSILE_ARTICLE}.
     */
    static final Noise MISSILE_NOISE = new Noise(
            new Noise.Jet(MISSILE_TURBOFAN.thrust(), MISSILE_TURBOFAN.jet(), MISSILE_TURBOFAN.count(), 0.55, 50, Spectra.MISSILE_ENGINE_REAR),
            new Noise.Rocket(MISSILE_BOOSTER.thrust(), MISSILE_BOOSTER.exhaust(), Spectra.BOOSTER_ENGINE),
            new Noise.Body(MISSILE_ARTICLE.diameter(), MISSILE_ARTICLE.length(), MISSILE_ARTICLE.real().cruise(), Spectra.BOMB_FALL), Double.NaN, Double.NaN, 125);

    /** B-2 ({@link #BOMBER_TURBOFANS}): тяга — на месте, верхняя оценка (на эшелоне двигатели прикрыты). */
    static final Noise BOMBER_NOISE = new Noise(new Noise.Jet(BOMBER_TURBOFANS.thrust(), BOMBER_TURBOFANS.jet(), BOMBER_TURBOFANS.count(), 0.55, 50,
            Spectra.BOMBER_ENGINE), null, null, Double.NaN, Double.NaN, Double.NaN);

    /**
     * GBU-57 MOP: 6,2 × 0,8 м (Wikipedia); запись падения — ветер на KC-135 (~230 м/с, О). Бурение породы ~125 дБА
     * (О: сильнее тяжёлого отбойника).
     */
    static final Noise BOMB_NOISE = new Noise(null, null, new Noise.Body(BOMB_ARTICLE.diameter(), BOMB_ARTICLE.length(), 230, Spectra.BOMB_FALL), 125, Double.NaN, Double.NaN);

    /** МБР: первая ступень ({@link #ICBM_ASCENT}): истечение 903 700 · 61 с / 20 780 кг = 2653 м/с; корпус 1,67 × 18,2 м (USAF). */
    static final Noise ICBM_NOISE = new Noise(null, new Noise.Rocket(ICBM_ASCENT.thrust(), ICBM_ASCENT.exhaust(), Spectra.BOOSTER_ENGINE),
            new Noise.Body(ICBM_ARTICLE.diameter(), ICBM_ARTICLE.length(), 500, Spectra.BOMB_FALL), Double.NaN, Double.NaN, Double.NaN);

    /** «Град» ({@link #ROCKET_MOTOR}): 122 мм × 2,87 м (Wikipedia «BM-21 Grad»); запись воя — мины на подлёте (~150 м/с, О). */
    static final Noise ROCKET_NOISE = new Noise(null, new Noise.Rocket(ROCKET_MOTOR.thrust(), ROCKET_MOTOR.exhaust(), Spectra.BOOSTER_ENGINE),
            new Noise.Body(ROCKET_ARTICLE.diameter(), ROCKET_ARTICLE.length(), 150, Spectra.ROCKET_INCOMING), Double.NaN, Double.NaN, Double.NaN);

    /**
     * «Ланцет-3» ({@link #LOITER_MOTOR}): 110 км/ч = 30,6 м/с (Wikipedia «ZALA Lancet»); лопасти ~300 Гц. Громкость — по
     * классу: мультикоптеры от любительских до тяжёлых — 72,8–97,5 дБ мощности звука в висении (измерения восьми типов,
     * Корея), ~0,8 кВт — у верхнего края: 95 дБА (О). На катапульте — 0,85 номинала, в пике винт раскручивается до
     * 1,15 (О). Корпус ~0,2 × 1,65 м (О); запись пике — пролёт р/у самолёта ~40 м/с (О). Катапульта ~120 дБА (О).
     */
    static final Noise LOITER_NOISE = new Noise(new Noise.Propeller(LOITER_MOTOR.power(), LOITER_MOTOR.rpm(), LOITER_MOTOR.blades(), LOITER_ARTICLE.real().cruise(), 0.85, 1.15,
            95, 800, Spectra.LOITER_ENGINE), null, new Noise.Body(LOITER_ARTICLE.diameter(), LOITER_ARTICLE.length(), 40, Spectra.LOITER_DIVE), Double.NaN, 120, Double.NaN);

    /**
     * Дрон-камикадзе Shahed-136 ({@link #DRONE_ARTICLE}). Игровое (не по физике): ≈150 км/ч, крейсер в 45 блоках над
     * рельефом, пикирование с разгоном до 3 блоков/тик. Стартовый ускоритель горит ~2 с (настоящий — ~2,5 с) и сбрасывается. Боевая часть —
     * 50 кг ТНТ: стёкла выбивает до ~70 блоков. По точкам оператора — до 30 км пути (~12 минут полёта): втрое дальше
     * карты пульта по умолчанию. Навигация спутниковая и инерциальная: промах — СКО 3 блока по каждой оси (в среднем
     * ~3,5 блока от цели).
     */
    public static final WeaponSpec DRONE = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 25, Salvo.gaps(20, 40), Warhead.CONVENTIONAL,
            () -> IncomingConfig.SERVER.dronePower, Launch.GUIDED,
            new Route(() -> IncomingConfig.SERVER.droneFlightTime, 7.5, 0, 0, 3, 1.0, 30_000), Tracking.CAMERA, LauncherRack.DRONE, Blast.DRONE, false, 0.6f,
            new Airframe(() -> ModEntities.DRONE.get(), 2.1, 3.0, 1.6 / 3.0, 45, 20, 256, 1.83, 12, 900 * 2.1, 4.3,
                    new LaunchProfile(8, 0, 0, 8, -9), 0, new Attack(0.4, 0, 0),
                    DRONE_NOISE, new Signature(1.0, true), DRONE_ARTICLE),
            null, Munition.single(() -> ModItems.SHAHED.get()), null);

    /**
     * Крылатая ракета «Томагавк» ({@link #MISSILE_ARTICLE}). Игровое (не по физике): 80 м/с (4 блока/тик, ≈290 км/ч) —
     * медленнее настоящей, чтобы подлёт было видно; бреющий полёт в 12 блоках над рельефом, горка и пикирование до 5
     * блоков/тик. Ускоритель выносит её до ~3.4 блока/тик — ниже маршевой: дальше разгоняет турбина, без рывка вниз.
     * Последние 256 блоков — в мире: больше дальности прорисовки 12 чанков, чтобы подлёт с её края был виден и при
     * меньшей дистанции симуляции. Боевая часть — ~150 кг ТНТ (WDU-36/B): стёкла выбивает до ~105 блоков. По точкам
     * оператора — до 30 км пути (~6 минут полёта). Точнее шахеда (сверка с рельефом и камерой на подлёте): промах — СКО
     * 1,5 блока по оси.
     */
    public static final WeaponSpec MISSILE = new WeaponSpec(WeaponType.SirenKind.MISSILE, 15, Salvo.gaps(15, 30), new Warhead(false, true, true),
            () -> IncomingConfig.SERVER.missilePower, Launch.GUIDED,
            new Route(() -> IncomingConfig.SERVER.missileFlightTime, 4, 0, 0, 1.5, 1.57, 30_000), Tracking.POINT, LauncherRack.MISSILE, Blast.MISSILE, false, 0.5f,
            new Airframe(() -> ModEntities.CRUISE_MISSILE.get(), 4.0, 5.0, 2.0 / 3.0, 12, 12, 256, 2.96, 8, 700 * 4.0, 6.5,
                    new LaunchProfile(6, 0, 0, 8, -14), 256, new Attack(0.84, 1.68, 1.94),
                    MISSILE_NOISE, new Signature(0.7, true), MISSILE_ARTICLE),
            null, Munition.single(() -> ModItems.CRUISE_MISSILE.get()), null);

    /**
     * B-2 ({@link #BOMBER_ARTICLE}) и GBU-57 ({@link #BOMB_ARTICLE}). Игровое (не по физике): эшелон +170 над местом
     * падения бомбы (настоящий — ~12 км), 12 блоков/тик, разворот 1°/тик (радиус ~690 блоков), сброс за ~85 блоков до
     * цели; бетонобойная бомба пробивает грунт и взрывается под землёй. Бомба со спутниковым наведением: промах — СКО
     * 1,5 блока по оси. Топлива у бомбы нет.
     */
    public static final WeaponSpec BUNKER = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 20, Salvo.gaps(60, 80), new Warhead(false, true, false),
            () -> IncomingConfig.SERVER.bunkerPower, Launch.BOMBER,
            new Route(() -> IncomingConfig.SERVER.bomberFlightTime, 0.5, 0, 0, 1.5, 0, 0), Tracking.POINT, null, Blast.NONE, true, 0.5f,
            new Airframe(() -> ModEntities.BOMBER.get(), 12, 12, 1.0, 170, 30, 0, 8.5, 0, 120 * 12, 0, null, 0, Attack.NONE,
                    BOMBER_NOISE, new Signature(0.15, true), BOMBER_ARTICLE),
            new Airframe(() -> ModEntities.BUNKER_BUSTER.get(), BombDrop.MAX_SPEED, BombDrop.MAX_SPEED, 0, 0, 12, 0, BombDrop.NOSE, 0, 300 * BombDrop.MAX_SPEED,
                    BombDrop.REACH_PAD, null, 0, Attack.NONE,
                    BOMB_NOISE, new Signature(0.1, false), BOMB_ARTICLE),
            Munition.single(() -> ModItems.BUNKER_BUSTER.get()), null);

    /**
     * МБР с ядерной боеголовкой ({@link #ICBM_ARTICLE}): только участок разгона (предел — 25 блоков/тик, не по физике);
     * удар — таймер {@code NuclearStrikes}. Разгон — первая ступень LGM-30G Minuteman III ({@link #ICBM_ASCENT}):
     * стартовая масса 36 030 кг (79 432 фунта) и тяга первой ступени
     * 203 158 фунтов (903,7 кН) — USAF Fact Sheet «LGM-30G Minuteman III»
     * (https://www.af.mil/About-Us/Fact-Sheets/Display/Article/104466/lgm-30g-minuteman-iii/); топливо первой ступени —
     * 20 780 кг за 61 с (S. Fetter, «A Ballistic Missile Primer», табл. первой ступени Minuteman: у Minuteman III она та же,
     * https://gotech.spp.umd.edu/sites/default/files/2019-08/1990-MissilePrimer.pdf). На отрыве 1,56 g, к 10 с — 1,82 g:
     * 165 м/с и ~810 м по оси. Строго отвесно 3 с (хвост выходит из шахты 25 блоков к ~1,8 с), потом наклон к курсу
     * цели до 85° над горизонтом; за потолок мира + 320 уходит через ~8,5 с на y 64, отойдя на ~30 блоков вбок: дальше
     * вбок она уходила бы из готовых чанков у места пуска. Сила
     * взрыва — {@code bunkerPower}: своей настройки у МБР нет, её подрыв ядерный (мощность — килотонны {@code Loadout.Nuke}),
     * а поле паспорта обязательно. Промаха нет: у настоящей МБР он в сотни метров, но при ядерном заряде ничего не меняет,
     * а подрыв точно в отметке совпадает с её сиреной и предупреждением. Топлива к удару нет (боеголовка).
     */
    public static final WeaponSpec NUKE = new WeaponSpec(WeaponType.SirenKind.NUCLEAR, 0, Salvo.gaps(200, 300), new Warhead(true, false, true),
            () -> IncomingConfig.SERVER.bunkerPower, Launch.ICBM,
            new Route(null, 0, 0, 0, 0, 0, 0), Tracking.POINT, null, Blast.NONE, false, 0.5f,
            new Airframe(() -> ModEntities.ICBM.get(), 25, 25, 0, 0, 12, 0, 9, 0, 600 * 25, 0, null, 0, Attack.NONE,
                    ICBM_NOISE, new Signature(1.0, false), ICBM_ARTICLE),
            null, Munition.single(() -> ModItems.ICBM.get()), ICBM_ASCENT);

    /**
     * РСЗО БМ-21 «Град», снаряд 9М22У ({@link #ROCKET_ARTICLE}): неуправляемые реактивные снаряды по баллистике с пакета
     * из 40 труб, залп очередью по полсекунды; одиночным не стреляет — пульт ставит очередь из 12 по площади 15 блоков;
     * без стреляющего рядом пакет стоит в 600 блоках от цели (игровое, не по физике: настоящая дальность — 5–20,4 км).
     * Рассеивание — игровое, не по физике: СКО 1 % дальности по каждой оси; у 9М22У на наибольшей дальности — 1/130 по
     * дальности и 1/200 по направлению (Wikipedia, ru: «Град (РСЗО)»), на малых открытых таблиц нет. Заряд снаряда —
     * 6,4 кг ВВ: стёкла выбивает до ~37 блоков. Боеприпас — пакет на все трубы пусковой (40 снарядов, как перезарядка
     * БМ-21): залп тратит из него по снаряду. Топлива к удару нет: двигатель выгорает за секунду-другую после схода.
     */
    public static final WeaponSpec ROCKET = new WeaponSpec(WeaponType.SirenKind.MISSILE, 8, new Salvo(8, 12, 12, 15), Warhead.CONVENTIONAL,
            () -> IncomingConfig.SERVER.rocketPower, Launch.ROCKET,
            new Route(null, 0, 600, 0.01, 0, 0, 0), Tracking.POINT, LauncherRack.ROCKET, Blast.ROCKET, false, 0.5f,
            new Airframe(() -> ModEntities.ROCKET.get(), 4, 4, 0, 0, 4, 0, 1.45, 2, 2400 * 4, 1.5, null, 0, Attack.NONE,
                    ROCKET_NOISE, new Signature(1.0, false), ROCKET_ARTICLE),
            null, new Munition(() -> ModItems.GRAD_ROCKETS.get(), LauncherRack.ROCKET.slots()), null);

    /**
     * Барражирующий боеприпас «Ланцет-3» ({@link #LOITER_ARTICLE}). Игровое (не по физике): ≈115 км/ч, с катапульты
     * (толчок за полсекунды, без огня) к цели, круг на 45 блоков выше неё (настоящий — 600–800 м), пикирование с
     * разгоном до 4 блоков/тик; без стреляющего рядом — издалека, с 500 блоков. Боевая часть — 3 кг: стёкла выбивает
     * до ~29 блоков. По точкам оператора — до 15 км пути (~8 минут полёта): ближнее оружие, вдвое короче шахеда.
     * Наводится камерой (оператора или своей): промаха нет. Топлива нет: мотор электрический (батарея).
     */
    public static final WeaponSpec LOITER = new WeaponSpec(WeaponType.SirenKind.AIR_RAID, 20, Salvo.gaps(40, 60), Warhead.CONVENTIONAL,
            () -> IncomingConfig.SERVER.loiterPower, Launch.LOITER,
            new Route(null, 0, 500, 0, 0, 1.05, 15_000), Tracking.CAMERA, LauncherRack.LOITER, Blast.DRONE, false, 0.5f,
            new Airframe(() -> ModEntities.LOITER.get(), 1.6, 4.0, 2.0 / 3.0, 45, 25, 35, 0.87, 4, 2400 * 1.6, 3.0,
                    new LaunchProfile(4, 10, 0.16, 4, -8), 0, Attack.NONE,
                    LOITER_NOISE, new Signature(0.5, true), LOITER_ARTICLE),
            null, Munition.single(() -> ModItems.LANCET.get()), null);
}
