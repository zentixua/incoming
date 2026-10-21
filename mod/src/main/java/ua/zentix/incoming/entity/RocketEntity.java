package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.guidance.Mission;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Nbt;
import ua.zentix.incoming.util.Terrain;

import java.util.UUID;

/**
 * Неуправляемый реактивный снаряд РСЗО (122 мм, как у «Града»). Стоит в трубе пакета, поджиг, сход — и дальше
 * только баллистика ({@link Ballistics}): тяжесть, сопротивление воздуха и ветер мира ({@link WeaponSpec.Ballistic},
 * игровой коэффициент). Скорость и курс схода при угле возвышения пакета даёт расчёт огня ({@link Ballistics#aim}),
 * который знает только ветер у земли; разброс схода — из паспорта. Так что на 500 блоков снаряд летит ~10 с,
 * поднимается на сотню блоков, спускается круче, чем поднимался, и залп ложится с разбросом и сносом по ветру.
 * Двигатель горит первые {@link #BURN_TICKS} (факел и плотный дым; скорость схода — сразу, не по физике: разгон в трубе
 * и на первых метрах не считается), дальше полёт по инерции; на нисходящей ветви — фаза атаки.
 * <p>
 * Путь считается при сходе на весь полёт ({@link Ballistics#fly}) и проходится по тикам. Если снаряд сдвинули (возврат
 * из полёта вне мира поднимает над рельефом), расчёт огня повторяется от текущего места с тем же наклоном пути. Цель
 * не отслеживается: куда навели при пуске, туда и упадёт (у места с карты высота уточняется один раз —
 * {@link #settleImpact}).
 * <p>
 * Район точки падения грузится с постановки в трубу, снаряд сходит сразу. Если он вне мира подходит к цели, а район
 * ещё не готов, конец траектории растягивается во времени ({@link #stretch}), а не замирает в воздухе у цели.
 */
public class RocketEntity extends StrikeProjectile {
    /** Двигатель горит столько тиков после схода. */
    public static final int BURN_TICKS = 40;
    /** Поджиг в трубе до схода. */
    public static final int IGNITION_TICKS = 3;
    /** Ближе этого времени полёта не стреляет: круче задираем трубы (миномётная траектория). */
    public static final int MIN_FLIGHT = 50;
    /** Вне мира, пока район цели не готов, замедляются последние столько тиков траектории (5 с, дольше загрузки района). */
    public static final double STRETCH_TICKS = 100;
    /** Темп полёта вне мира меняется не быстрее этого за тик. */
    private static final double RATE_SLEW = 0.05;
    /**
     * Ближе этого к неготовому району цели растянутый полёт не подходит: там снаряд вне мира ждал бы района стоя
     * (StrikeProjectile.advanceVirtual ждёт в 48 блоках плюс шаг).
     */
    private static final double HOLD_OFF = 64;
    /** Запас хода сверх дуги траектории, тиков на маршевой скорости: снаряд, не попавший в точку, летит дальше вниз. */
    private static final int OVERSHOOT_TICKS = 200;

    /** Полёт без мотора из паспорта: сопротивление и разброс схода. */
    private static final WeaponSpec.Ballistic FLIGHT = WeaponSpec.ROCKET.airframe().ballistic();
    /** Ниже точки падения путь считается ещё на столько блоков: промах в овраг, полёт дальше вниз. */
    private static final double BELOW_IMPACT = 64;

    /** Точка падения — куда навели при пуске. */
    @Nullable
    private Vec3 impactAt;
    /** Путь текущей траектории по тикам (не сохраняется: считается заново от начала и скорости схода). */
    @Nullable
    private Ballistics.Path path;
    /** Где путь на спуске проходит высоту точки падения: туда снаряд и падает (ветер и разброс уводят от точки). */
    @Nullable
    private Vec3 landing;
    /** Начало текущей траектории, скорость в нём и сколько тиков лететь от него. */
    @Nullable
    private Vec3 start;
    private Vec3 v0 = Vec3.ZERO;
    private int flightTicks;
    /** Время от начала текущей траектории, тиков (дробное: полёт вне мира растягивается). */
    private double t;
    /** Темп времени траектории: 1 — как у мира; меньше — полёт вне мира растянут до загрузки района цели. */
    private double rate = 1;
    /** Сколько времени мира набежало сверх траектории (растяжение); целые тики уходят в ожидание района. */
    private double stretched;
    /** Время траектории, после которого снаряд ближе {@link #HOLD_OFF} к цели: растяжение к нему только подходит. */
    private double holdAt;
    /** Угол возвышения пакета, с которым снаряд стоял на пусковой. */
    private float elevation = LauncherEntity.elevation(WeaponType.ROCKET);

    public RocketEntity(EntityType<? extends RocketEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.ROCKET;
    }

    /** Неуправляемый реактивный снаряд: самоликвидатора нет, отбой его не останавливает (полёт — секунды). */
    @Override
    public boolean recallable() {
        return false;
    }

    /** Средняя скорость по траектории — для оценок; время подлёта считается по самой траектории. */

    @Override
    protected boolean climbs() {
        return false;
    }

    @Override
    protected boolean acceptsRetarget() {
        return false;
    }

    /** Поставить в трубу: как у шахеда, плюс угол пакета — по нему строится траектория. */
    public void placeInTube(Vec3 rail, float yaw, float elevation, int readyTicks, int hiddenTicks,
                            Target target, Vec3 impact, @Nullable UUID owner) {
        placeOnLauncher(rail, yaw, elevation, readyTicks, hiddenTicks, target, impact, owner);
        this.elevation = elevation;
        this.impactAt = impact;
    }

    /**
     * Пуск без пусковой (консоль, нет места): снаряд уже на траектории, стартовавшей в {@code from}.
     * Взрыватель взведён — такой снаряд приходит издалека.
     */
    public void launchFrom(Vec3 from, Target target, Vec3 impact, @Nullable UUID owner) {
        launch(from, target, impact, owner);
        this.impactAt = impact;
        this.elevation = LauncherEntity.elevation(WeaponType.ROCKET);
        solve(from, impact);
        setPhase(FlightPhase.CRUISE);
        face(v0);
    }

    @Override
    protected int launchTicksLeft() {
        return switch (flightPhase()) {
            case READY -> Math.max(0, readyTicks() - phaseAge()) + IGNITION_TICKS;
            case IGNITION -> Math.max(0, IGNITION_TICKS - phaseAge());
            default -> 0;
        };
    }

    @Override
    public int etaTicks() {
        if (start == null) {
            // ещё в трубе: время полёта от неё
            Vec3 aim = impactAt != null ? impactAt : aimPoint();
            return launchTicksLeft() + Ballistics.ticksFor(position(), aim, elevation, MIN_FLIGHT);
        }
        return (int) Math.ceil(Math.max(0, flightTicks - t));
    }

    @Override
    protected void serverTick(ServerLevel level) {
        if (impactAt == null) impactAt = tracker.point();
        settleImpact(level);
        FlightPhase ph = flightPhase();
        if (ph == FlightPhase.READY) {
            speed = 0;
            if (phaseAge() >= readyTicks()) setPhase(FlightPhase.IGNITION);
            return;
        }
        if (ph == FlightPhase.IGNITION) {
            speed = 0;
            if (phaseAge() >= IGNITION_TICKS) {
                solve(position(), impactAt);
                setPhase(FlightPhase.BOOST);
            }
            return;
        }
        // вне мира путь кончился на поверхности: ни траектории, ни растяжения — ждать там загрузки и попасть
        if (isGrounded()) {
            advance(level, landing(), airframe().reachPad());
            return;
        }
        if (start == null) solve(position(), impactAt);

        // сдвинули с траектории (вернулся в мир выше рельефа) — расчёт огня от текущего места
        if (position().distanceToSqr(at(t)) > 0.25) {
            resolve(position(), impactAt);
        }

        if (ph == FlightPhase.BOOST && phaseAge() >= BURN_TICKS) setPhase(FlightPhase.CRUISE);
        // в мире снаряд виден — время только как у мира
        double step = isVirtual() ? stretch(level) : (rate = 1);
        if (isRemoved()) return;
        Vec3 v = at(t + step).subtract(at(t));
        if (flightPhase() == FlightPhase.CRUISE && v.y < 0) setPhase(FlightPhase.TERMINAL);

        // вне мира растянутый почти до остановки у цели: стоять, пока темп не вернётся
        if (v.lengthSqr() < 1.0e-12) return;
        face(v);
        speed = v.length();
        Vec3 before = position();
        if (!advance(level, landing(), airframe().reachPad())) return;
        // вне мира у самой цели снаряд ждёт загрузки района — тогда он не сдвинулся и время траектории стоит
        if (position().distanceToSqr(before) > 1.0e-6) t += step;
    }

    /**
     * Место с карты ({@link Target.Ground}): высота точки падения у пуска — оценка, пока чанк там не готов (оценка
     * генератора у города из сохранения — улица под крышей). Район точки падения грузится с постановки в трубу; как
     * только её чанк готов, точка встаёт на его поверхность (разброс остаётся свой) — один раз, как высота цели у расчёта
     * огня: цель становится этой точкой и дальше, как у всей РСЗО, не отслеживается. Снаряд уже летит — траектория
     * пересчитывается от текущего места на оставшееся время.
     */
    private void settleImpact(ServerLevel level) {
        if (!(tracker.target() instanceof Target.Ground) || !Terrain.ready(level, BlockPos.containing(impactAt))) return;
        Vec3 surface = new Target.Ground(impactAt).surface(level);
        boolean moved = !surface.equals(impactAt);
        impactAt = surface;
        // цель — сама точка падения с разбросом, как у пуска: у неё метка, сирена, камера и район цели
        settleAim(impactAt);
        if (moved && start != null) resolve(position(), impactAt);
    }

    /** Темп времени траектории: 1 — как у мира, меньше — полёт вне мира растянут (для стенда). */
    public double timeRate() {
        return rate;
    }

    /**
     * Темп полёта вне мира. Район точки падения не готов — последние {@link #STRETCH_TICKS} тиков траектории до
     * {@link #HOLD_OFF} блоков от цели замедляются плавно (темп = оставшееся до них время / STRETCH_TICKS, меняется
     * не быстрее {@link #RATE_SLEW} за тик): снаряд подходит к этой черте всё медленнее и не замирает; район готов —
     * темп так же плавно возвращается к 1. Вне мира
     * снаряда никто не видит, только слышит путь издалека. Растянутое время — ожидание района: снаряд идёт медленнее и
     * хода на него не тратит, предел ожидания общий с ожиданием у цели.
     */
    private double stretch(ServerLevel level) {
        double want = aimAreaReady(level, impactAt) ? 1 : Math.min(1, Math.max(0, holdAt - t) / STRETCH_TICKS);
        rate += Math.max(-RATE_SLEW, Math.min(RATE_SLEW, want - rate));
        stretched += 1 - rate;
        while (stretched >= 1 && !isRemoved()) {
            stretched -= 1;
            waitForAimArea(impactAt);
        }
        return rate;
    }

    /**
     * Район цели грузится с постановки в трубу: полёт по дуге короткий (500 блоков — ~10 с), и район, взятый на
     * подлёте, не успевал бы загрузиться. Снаряд сходит сразу: полёт длиннее загрузки района (замер: 0,7–4,6 с на
     * свежий район), а короткий полёт вне мира растягивается ({@link #stretch}).
     */
    @Override
    protected double preloadDistance() {
        return Double.MAX_VALUE;
    }

    /**
     * Путь кончается там, где траектория на спуске проходит высоту точки падения ({@link #landing}): ветер и разброс схода
     * уводят его от точки цели на блоки.
     */
    @Override
    @Nullable
    protected Vec3 pathEnd() {
        return landing != null ? landing : impactAt != null ? impactAt : super.pathEnd();
    }

    /** Куда снаряд падает: точка на спуске траектории; траектории ещё нет — точка цели. */
    private Vec3 landing() {
        return landing != null ? landing : impactAt;
    }

    /** Нос по скорости. */
    private void face(Vec3 v) {
        float[] a = FlightController.anglesTo(Vec3.ZERO, v);
        flight.set(a[0], a[1]);
    }

    private Vec3 at(double k) {
        return path().at(k);
    }

    /** Путь текущей траектории (после загрузки — заново от начала и скорости схода, с ветром мира сейчас). */
    private Ballistics.Path path() {
        if (path == null) fly(impactAt != null ? impactAt : start);
        return path;
    }

    /** Последний тик траектории, на котором снаряд ещё дальше {@link #HOLD_OFF} от точки падения (0 — уже ближе). */
    private double holdPoint(Vec3 impact) {
        for (int k = flightTicks; k > 0; k--) {
            if (at(k).distanceTo(impact) > HOLD_OFF) return k;
        }
        return 0;
    }

    /**
     * Сход из {@code from} на {@code to}: угол возвышения пакета (положе {@link #MIN_FLIGHT} не выходит — круче, до 80°),
     * скорость и курс — расчёт огня при ветре у земли, потом разброс схода из паспорта.
     */
    private void solve(Vec3 from, Vec3 to) {
        float el = Ballistics.elevationFor(from, to, elevation, MIN_FLIGHT);
        restart(from, to, scatter(Ballistics.aim(from, to, el, FLIGHT.drag(), Ballistics.Wind.ground(Air.of(level())))));
    }

    /**
     * Расчёт огня заново от {@code from} (сдвинули с траектории, уточнилась высота точки): с тем же наклоном пути, что
     * сейчас, без нового разброса.
     */
    private void resolve(Vec3 from, Vec3 to) {
        Vec3 v = at(t + 1).subtract(at(t));
        float el = (float) Math.toDegrees(Math.atan2(v.y, Math.max(1e-6, v.horizontalDistance())));
        restart(from, to, Ballistics.aim(from, to, Math.max(-80, Math.min(80, el)), FLIGHT.drag(), Ballistics.Wind.ground(Air.of(level()))));
    }

    /** Разброс схода: направление — СКО {@link WeaponSpec.Ballistic#spreadAngle} по двум осям, скорость — {@link WeaponSpec.Ballistic#spreadSpeed}. */
    private Vec3 scatter(Vec3 v) {
        double speed = v.length();
        if (speed < 1e-9) return v;
        Vec3 dir = v.scale(1 / speed);
        Vec3 side = new Vec3(-dir.z, 0, dir.x);
        side = side.lengthSqr() < 1e-9 ? new Vec3(1, 0, 0) : side.normalize();
        Vec3 up = side.cross(dir);
        double sa = Math.toRadians(FLIGHT.spreadAngle());
        Vec3 d = dir.add(side.scale(random.nextGaussian() * sa)).add(up.scale(random.nextGaussian() * sa)).normalize();
        return d.scale(speed * (1 + random.nextGaussian() * FLIGHT.spreadSpeed()));
    }

    /**
     * Длина дуги текущей траектории от времени {@code from} до точки падения, блоков: столько снаряд пролетит по тикам
     * (дробное начало — полёт вне мира растягивается).
     */
    private double arcFrom(double from) {
        int first = (int) Math.ceil(from);
        double arc = first < flightTicks ? at(first).subtract(at(from)).length() : 0;
        for (int k = first; k < flightTicks; k++) arc += at(k + 1).subtract(at(k)).length();
        return arc;
    }

    /** Запас хода на остаток траектории от {@link #t}: дуга до точки падения и перелёт {@link #OVERSHOOT_TICKS}. */
    private Mission missionLeft() {
        return Mission.of(arcFrom(t) + OVERSHOOT_TICKS * cruiseSpeed());
    }

    /** Новая траектория из {@code from} со скоростью {@code v} на точку {@code to}. */
    private void restart(Vec3 from, Vec3 to, Vec3 v) {
        start = from;
        v0 = v;
        t = 0;
        fly(to);
        setMission(missionLeft());
        holdAt = holdPoint(landing);
    }

    /**
     * Путь от {@link #start} со скоростью {@link #v0} при ветре мира сейчас — до высоты {@code to} на спуске и ещё
     * {@link #OVERSHOOT_TICKS} или {@link #BELOW_IMPACT} ниже; время полёта и место падения — по нему.
     */
    private void fly(Vec3 to) {
        double h = Math.max(0.05, v0.horizontalDistance()), d = Math.sqrt(to.subtract(start).horizontalDistanceSqr());
        int ticks = (int) Math.min(6000, 3 * d / h + 400) + OVERSHOOT_TICKS;
        path = Ballistics.fly(start, v0, FLIGHT.drag(), Ballistics.Wind.of(Air.of(level())), to.y - BELOW_IMPACT, ticks);
        double k = path.landing(to.y);
        flightTicks = Double.isNaN(k) ? Math.max(1, path.ticks()) : Math.max(1, (int) Math.ceil(k));
        landing = Double.isNaN(k) ? to : path.at(k);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        elevation = tag.contains("elevation") ? tag.getFloat("elevation") : LauncherEntity.elevation(WeaponType.ROCKET);
        Vec3 impact = Nbt.getVec(tag, "impact");
        if (impact != null) impactAt = impact;
        if (tag.contains("start_x")) {
            start = Nbt.getVec(tag, "start");
            v0 = Nbt.getVec(tag, "v0");
            flightTicks = tag.getInt("flight_ticks");
            t = tag.contains("t") ? tag.getDouble("t") : tag.getInt("n");
            // путь — заново от начала и скорости схода (сохранения до сопротивления воздуха пойдут по новому закону;
            // разойдутся с ним — расчёт огня от текущего места)
            path = null;
            landing = null;
            if (impactAt != null) {
                fly(impactAt);
                holdAt = holdPoint(landing);
            }
            // 2.3.x (срок жизни, ключа mission нет): на дальней дуге снаряд быстрее маршевой, и остаток срока × маршевая
            // кончался бы на полпути (5 км: ≈ 3600 блоков хода на ≈ 5700 блоков дуги) — запас по самой дуге
            if (!tag.contains("mission")) setMission(missionLeft());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putFloat("elevation", elevation);
        if (impactAt != null) Nbt.putVec(tag, "impact", impactAt);
        if (start != null) {
            Nbt.putVec(tag, "start", start);
            Nbt.putVec(tag, "v0", v0);
            tag.putInt("flight_ticks", flightTicks);
            tag.putDouble("t", t);
        }
    }
}
