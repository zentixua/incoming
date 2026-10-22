package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.guidance.Bearing;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.registry.ModEntities;
import ua.zentix.incoming.strike.FlightScale;
import ua.zentix.incoming.strike.VirtualFlights;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Terrain;

import java.util.UUID;

/**
 * B-2 на эшелоне +170 над местом, куда упадёт бомба (на шкале полёта — до 12 км, {@link WeaponSpec.Airframe#cruiseHeight()};
 * цель перенацелили выше или ниже, у цели крыша или башня —
 * эшелон меняется полого, не круче 6°): заходит издалека (большая часть пути — вне загруженного мира), идёт по прямой
 * 12 блоков/тик (240 м/с), сбрасывает бетонобойную бомбу на эшелоне за ~145 блоков до цели по горизонтали (на любом
 * эшелоне — под тем же углом на точку, {@link #releaseLine}; бомба сходит с его скоростью,
 * рули опускают ей нос, и она входит почти отвесно) и уходит с разворотом и набором высоты. Не на эшелоне (вернулся в мир над
 * холмом, точку перенацелили выше или ниже) — сбрасывает с той высоты, где он есть, дальше или ближе: B-2 проигрывает
 * падение бомбы ({@link BombDrop#releaseNow}); промах отовсюду (точка слишком близко впереди, сзади, выше него) —
 * заходит снова.
 */
public class BomberEntity extends StrikeProjectile {
    /** Паспорт B-2: скорость, разворот (радиус ~690 блоков), эшелон над точкой сброса. */
    private static final WeaponSpec.Airframe AIR = WeaponSpec.BUNKER.airframe();
    /**
     * Дальность сброса на игровом эшелоне ({@link #GAME_ALTITUDE}): угол на точку при сбросе один на любой высоте —
     * бомба опускает нос и идёт на точку по той же прямой ({@link #releaseLine}).
     */
    private static final double GAME_RELEASE = WeaponSpec.BUNKER.route().gameFinalLeg();
    /** Игровой эшелон над точкой сброса. */
    private static final double GAME_ALTITUDE = AIR.gameHeight();
    /** Дальность сброса к высоте над точкой. */
    private static final double RELEASE_RATIO = GAME_RELEASE / GAME_ALTITUDE;
    /** Падение бомбы с игрового эшелона до удара, тиков (оценка: ~20). */
    private static final int FALL_TICKS = 20;
    /** Смена эшелона: тангаж не круче этого, ° (набор и снижение B-2 — пологие). */
    private static final double LEVEL_CHANGE_PITCH = 6;
    /** Тангаж на блок ошибки эшелона, °: у эшелона — плавный выход в горизонт. */
    private static final double PITCH_PER_BLOCK = 0.15;
    /** После сброса улетает и исчезает через столько тиков (или раньше — на краю загруженного мира). */
    private static final int EGRESS_TICKS = 400;

    /** Бомба сброшена или удар отозван до сброса ({@link #recall}): B-2 уходит. */
    private boolean released;
    /** Точка под землёй, к которой бомба пробивается (цель в пещере); null — бурит вниз. */
    @Nullable
    private BlockPos goal;
    /** В какую сторону уходить после сброса: +1 — влево, -1 — вправо. */
    private float breakSide = 1;

    public BomberEntity(EntityType<? extends BomberEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.BUNKER;
    }

    /**
     * Чанки держит, как все, и после сброса, пока уходит: без своего тикета он входит в чанк, который тикает по тикету
     * встречного B-2 залпа, тот уходит — и B-2 замирает в чанке без тика, а в лог дважды в одной точке идёт «в полёте
     * выгружен вместе с чанком» (залпы B-2 ×30). Вне мира после сброса не летает: уход никто не увидит,
     * на краю тикающих чанков он исчезает.
     */
    @Override
    protected boolean fliesVirtually() {
        return !released;
    }

    @Override
    protected boolean acceptsRetarget() {
        return !released;
    }

    /** Бомбардировщик проходит цель и уходит дальше: его путь у неё не кончается. */
    @Override
    @Nullable
    protected Vec3 pathEnd() {
        return null;
    }

    /**
     * Запас над рельефом у возврата в мир — только чтобы не возникнуть в доме или склоне впереди: B-2 и так идёт
     * на эшелоне +170 над местом падения бомбы. С запасом 120 его поднимало бы над каждым домом или холмом выше 50 блоков
     * на курсе выше эшелона, и с возврата у цели он не успевал бы снизиться и сбрасывал бы выше эшелона — дальше
     * от точки.
     */

    public boolean hasReleased() {
        return released;
    }

    /** До удара бомбы: дойти до точки сброса и падение ({@link #fallTicks}; без второго захода — его заранее не знает никто). */
    @Override
    public int etaTicks() {
        if (tracker == null) return 0;
        Vec3 aim = tracker.point();
        Bearing b = bearingTo(aim);
        return (int) Math.ceil(Math.max(0, b.horizontal() - releaseLine(getY() - aim.y)) / AIR.cruiseSpeed()) + fallTicks();
    }

    /** Скорость B-2 в горизонтальном полёте курсом {@code yaw}, блоков/тик: с ней же сходит бомба. */
    public static Vec3 levelVelocity(float yaw) {
        return Vec3.directionFromRotation(0, yaw).scale(AIR.cruiseSpeed());
    }

    /**
     * Дальность сброса с высоты {@code height} над точкой: угол на точку тот же, что на игровом эшелоне.
     * Сброс бывает и раньше — на краю окна, откуда бомба ещё попадает ({@link BombDrop#releaseNow}).
     */
    public static double releaseLine(double height) {
        return height * GAME_RELEASE / GAME_ALTITUDE;
    }

    /** Эшелон над точкой, куда упадёт бомба, на шкале полёта мира: игровой +170, настоящий — 12 км. */
    private static double altitude() {
        return AIR.cruiseHeight();
    }

    /** Дальность сброса на эшелоне, по горизонтали до точки. */
    private static double releaseDistance() {
        return releaseLine(altitude());
    }

    /**
     * Падение бомбы с эшелона до удара, тиков (оценка): путь — в столько раз длиннее игрового, во сколько выше эшелон,
     * скорость — во сколько быстрее B-2, с которой она сходит; при игровой шкале — {@link #FALL_TICKS}.
     */
    private static int fallTicks() {
        double x = FlightScale.x();
        return (int) Math.round(FALL_TICKS * AIR.cruiseHeight(x) / GAME_ALTITUDE / AIR.stretch(x));
    }

    /**
     * @param surface точка на поверхности над целью (туда падает бомба)
     * @param goal    цель под землёй или null
     */
    public void launch(Vec3 pos, Vec3 surface, @Nullable BlockPos goal, @Nullable UUID owner) {
        Vec3 start = new Vec3(pos.x, surface.y + altitude(), pos.z);
        super.launch(start, new Target.Point(surface), surface, owner);
        this.goal = goal;
        this.speed = AIR.cruiseSpeed();
        this.breakSide = random.nextBoolean() ? 1 : -1;
        setPhase(FlightPhase.CRUISE);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        Vec3 aim = tracker.point();
        Bearing b = bearingTo(aim);
        // эшелон — над тем, куда упадёт бомба: у пуска он от первой оценки поверхности, а перенацеливание на точку ниже
        // или выше, крыша или башня, которой не было в оценке генератора, меняют её
        double echelon = 0;
        if (!released) {
            Vec3 drop = surfaceUnder(level, aim);
            echelon = drop.y + altitude();
            // сброс — когда бомба, сброшенная сейчас, придёт в точку, и лучше уже не будет (BombDrop.releaseNow): со 170
            // за 100 блоков бомба со скоростью B-2 перелетает точку на ~75, на точку в 40 блоках впереди — на ~150; сзади
            // (бомба падает по курсу и назад не рулит) — не сбрасывать вовсе
            if (ahead(drop) && BombDrop.releaseNow(position(), flight.forward().scale(speed), drop, RELEASE_RATIO)) {
                release(level, drop);
            }
        }
        if (exhausted() && !released) {
            Incoming.LOG.warn("B-2 {} ran out of endurance without dropping its bomb, removed at {} (aim point {}, off-world {})", getUUID(),
                    blockPosition(), BlockPos.containing(aim), isVirtual());
        }
        // вне мира после сброса лететь незачем: уход никто не увидит
        if (exhausted() || released && (phaseAge() >= EGRESS_TICKS || isVirtual())) {
            discard();
            return;
        }
        WeaponSpec.Agility egress = AIR.handling().climb(), cruise = AIR.handling().cruise();
        if (released) {
            // уход: вираж на 70° от курса и набор высоты
            flight.holdPitch(-6, 0.05, egress.pitchRate(), egress.pitchAccel());
            if (phaseAge() < 60) flight.steerYaw(flight.yaw() + 20 * breakSide, 0.08, egress.turnRate(), egress.turnAccel());
            else flight.settleYaw(egress.turnAccel());
        } else {
            flight.holdPitch(Mth.clamp((getY() - echelon) * PITCH_PER_BLOCK, -LEVEL_CHANGE_PITCH, LEVEL_CHANGE_PITCH), 0.1,
                    cruise.pitchRate(), cruise.pitchAccel());
        }
        if (!released && b.horizontal() > releaseDistance() + 40) {
            // точка сброса внутри круга разворота (перенацелили сбоку, проскочил её): на пределе поворота он
            // кружил бы вокруг неё без конца — прямо, пока она не выйдет из круга, и новый заход
            if (insideTurn(aim, AIR.turnRate())) flight.settleYaw(cruise.turnAccel());
            else flight.steerYaw(b.yaw(), 0.1, AIR.turnRate(), cruise.turnAccel());
        }
        // в наборе на уходе теряет скорость, на эшелоне держит маршевую
        throttle(AIR.cruiseSpeed());
        Vec3 dir = flight.forward();
        Vec3 next = position().add(dir.scale(speed));
        if (leavesTickingChunks(level, next)) return;
        moveAlong(level, next, dir);
    }

    /** Свой отбой до сброса: бомбы не будет — B-2 отворачивает и уходит, как после сброса. После сброса — нечего отзывать. */
    @Override
    public boolean recall(ServerLevel level) {
        if (released) return false;
        released = true;
        setPhase(FlightPhase.EGRESS);
        Incoming.LOG.info("B-2 {} recalled at {}: leaving without a drop (off-world {})", getUUID(), blockPosition(), isVirtual());
        return true;
    }

    /** @param drop точка на поверхности, куда падает бомба */
    private void release(ServerLevel level, Vec3 drop) {
        released = true;
        setPhase(FlightPhase.EGRESS);
        BunkerBusterEntity bomb = ModEntities.BUNKER_BUSTER.get().create(level);
        if (bomb == null) return;
        bomb.drop(position().add(0, -BombDrop.DROP_BELOW, 0), flight.forward().scale(speed), drop, goal, ownerId());
        bomb.setNuclear(nuclear);
        shareAir(bomb);
        // по UUID бомбы из предупреждений снаряда находится её B-2
        Incoming.LOG.info("B-2 {} dropped bomb {} at {}, {} blocks from the aim point, {} above it (off-world {})", getUUID(), bomb.getUUID(),
                blockPosition(), Math.round(position().subtract(drop).horizontalDistance()), Math.round(getY() - drop.y), isVirtual());
        if (isVirtual()) VirtualFlights.launch(level, bomb);
        else level.addFreshEntity(bomb);
    }

    /**
     * Точка сброса впереди или почти под брюхом — там, куда бомба может упасть: позади она уже «пройдена»
     * ({@link BombDrop#passed}: падает круто вниз по курсу, не рулит).
     */
    private boolean ahead(Vec3 drop) {
        return !BombDrop.passed(position(), drop, flight.yaw());
    }

    /**
     * Куда падать: у пуска поверхность под целью бывает оценкой генератора (чанк не был готов) — к сбросу чанк
     * у цели обычно готов, и высота берётся из него.
     */
    private static Vec3 surfaceUnder(ServerLevel level, Vec3 aim) {
        Terrain.Surface ground = Terrain.estimate(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(aim.x), Mth.floor(aim.z), Terrain.Allowed.CHUNK);
        return ground.known() ? new Vec3(aim.x, ground.y() - 0.5, aim.z) : aim;
    }

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        discard();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        released = tag.getBoolean("released");
        goal = NbtUtils.readBlockPos(tag, "goal").orElse(null);
        breakSide = tag.contains("break_side") ? tag.getFloat("break_side") : 1;
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("released", released);
        if (goal != null) tag.put("goal", NbtUtils.writeBlockPos(goal));
        tag.putFloat("break_side", breakSide);
    }
}
