package ua.zentix.incoming.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.nuclear.NuclearWarhead;
import ua.zentix.incoming.nuclear.world.ColumnScar;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.registry.ModDataMaps.Penetrability;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.target.Target;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Nbt;
import ua.zentix.incoming.util.Terrain;
import ua.zentix.incoming.warhead.Penetration;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Бетонобойная бомба GBU-57. Падает ({@link BombDrop}) и входит в грунт с той скоростью и под тем углом, с какими
 * кончилось падение; дальше идёт прямо, по закону Янга ({@link Penetration}): каждый блок пути — среда из карты данных
 * {@code incoming:penetration} ({@link Penetrability}), соседние блоки одной среды — один слой. Взрыватель: пустота после
 * {@value #SENSOR_SOLID} м породы — подрыв, когда бомба вошла в неё целиком ({@link #VOID_DEPTH}, датчик пустоты); иначе —
 * там, где бомба встала. Неготовый чанк впереди или низ мира — подрыв на месте. Канал — блоки по оси пути (калибр 0,8 м —
 * один блок).
 */
public class BunkerBusterEntity extends StrikeProjectile {
    private static final EntityDataAccessor<Vector3f> DATA_ENTRY = SynchedEntityData.defineId(BunkerBusterEntity.class, EntityDataSerializers.VECTOR3);
    /** Датчик пустоты взводится, когда за бомбой столько метров твёрдой среды (как было: 4 блока). */
    public static final double SENSOR_SOLID = 4;
    /** Среда мягче этого (S не меньше) датчик пустоты не взводит ({@link #arms}). */
    static final double SENSOR_SOFT = 20;
    /** Самый длинный путь в грунте, м: дальше — подрыв (в мягком грунте путь — десятки метров, в воде — сотни). */
    static final double MAX_PATH = 400;

    /** Цель под землёй (пещера, бункер): бомба целит в неё, а не в поверхность над ней. */
    @Nullable
    private BlockPos goal;
    /** Путь в грунте от входа ({@link #plan}); пуст — не начат или прочитан из сохранения (тогда — заново отсюда). */
    private Path path = Path.EMPTY;
    /** Откуда путь {@link #path}. */
    private Vec3 origin = Vec3.ZERO;
    /** Сколько времени бомба уже в грунте, с, и сколько блоков канала уже пройдено. */
    private double drillSeconds;
    private int carved;
    /** Сколько твёрдого, что взводит датчик пустоты, бомба уже прошла, м (сохраняется: путь после загрузки — заново). */
    private double armed;

    /** Снос ветром за тик, блоков (сохраняется: уходя в полёт вне мира, бомба становится новой сущностью из NBT). */
    private Vec3 windDrift = Vec3.ZERO;

    public BunkerBusterEntity(EntityType<? extends BunkerBusterEntity> type, Level level) {
        super(type, level);
    }

    @Override
    public WeaponType weapon() {
        return WeaponType.BUNKER;
    }

    /** Сброшенная бомба падает: отбой её не останавливает — B-2 отзывают до сброса. */
    @Override
    public boolean recallable() {
        return false;
    }

    /** Бомба — вторая сущность полёта оружия: её паспорт — {@link WeaponSpec#payload}. */
    @Override
    public WeaponSpec.Airframe airframe() {
        return WeaponSpec.BUNKER.payload();
    }

    @Override
    protected boolean climbs() {
        return false;
    }

    @Override
    protected boolean acceptsRetarget() {
        return false;
    }

    /** Точка входа в грунт (для дыма из скважины и выброса газов). */
    public Vec3 entry() {
        Vector3f v = entityData.get(DATA_ENTRY);
        return new Vec3(v.x, v.y, v.z);
    }

    public boolean isDrilling() {
        return flightPhase() == FlightPhase.DRILL;
    }

    /**
     * Сброс с бомбардировщика: бомба сходит со скоростью носителя {@code velocity} (блоков/тик) — величина, курс и тангаж
     * его ({@link BombDrop}).
     */
    public void drop(Vec3 pos, Vec3 velocity, Vec3 surface, @Nullable BlockPos goal, @Nullable UUID owner) {
        super.launch(pos, new Target.Point(surface), surface, owner);
        float[] angles = FlightController.anglesTo(Vec3.ZERO, velocity);
        flight.set(angles[0], angles[1]);
        moveTo(pos.x, pos.y, pos.z, angles[0], angles[1]);
        this.goal = goal;
        this.speed = velocity.length();
        setPhase(FlightPhase.TERMINAL);
    }

    @Override
    protected void serverTick(ServerLevel level) {
        if (flightPhase() == FlightPhase.DRILL) {
            drillTick(level);
            return;
        }
        Vec3 aim = tracker.point();
        // цель под землёй — бомба со спутниковым наведением целит в неё саму: в грунт она входит на прямой к ней
        speed = BombDrop.steer(flight, position(), speed, goal != null ? Vec3.atCenterOf(goal) : aim);
        Air air = air();
        double y = getY();
        windDrift = BombDrop.drift(windDrift, new Vec3(air.windX(y) / 20, 0, air.windZ(y) / 20), speed);
        advance(level, aim, airframe().reachPad());
    }

    /** Снос ветром ({@link BombDrop#drift}); в грунте — нет. */
    @Override
    protected Vec3 drift() {
        return flightPhase() == FlightPhase.DRILL ? Vec3.ZERO : windDrift;
    }

    /**
     * В грунте путь бомбы кончился: район цели остаётся там, где он был при входе (у цели или у места, где путь вне мира
     * встретил поверхность), — за целью, которая движется, или назад к цели после попадания на поверхности вне мира он не
     * идёт: бомба там уже не появится. Свой чанк бомба держит сама ({@link #moveAlong}).
     */
    @Override
    protected void holdTargetArea(ServerLevel level) {
        if (flightPhase() != FlightPhase.DRILL) super.holdTargetArea(level);
    }

    // ---------------------------------------------------------------- вход в грунт

    @Override
    protected void impact(ServerLevel level, Vec3 point, @Nullable Entity hitEntity) {
        if (flightPhase() == FlightPhase.DRILL) return;
        if (nuclear != null) {
            // ядерная проникающая БЧ (как B61-11): уходит в грунт на несколько метров — наземный подрыв с воронкой
            discard();
            NuclearWarhead.detonate(level, point, nuclear.yieldKt(), false, ownerId());
            return;
        }
        // удар — скоростью сущности в конце падения: вдоль носа и снос ветром
        Vec3 velocity = flight.forward().scale(speed).add(windDrift);
        double v = velocity.length() * 20;
        float[] angles = FlightController.anglesTo(Vec3.ZERO, velocity);
        flight.set(angles[0], angles[1]);
        speed = velocity.length();
        setPhase(FlightPhase.DRILL);
        // устье хода — на блок выше точки удара: оттуда идут дым и газы
        entityData.set(DATA_ENTRY, new Vector3f((float) point.x, (float) point.y + 1, (float) point.z));
        moveAlong(level, point, flight.forward());
        path = plan(level, point, flight.forward(), v, 0);
        ua.zentix.incoming.Incoming.LOG.info("Bunker buster entered the ground at {} {} {}: {} m/s, {}° below the horizon, path {} m ({})",
                Mth.floor(point.x), Mth.floor(point.y), Mth.floor(point.z), Math.round(v), Math.round(flight.pitch()),
                String.format(java.util.Locale.ROOT, "%.1f", path.length()), path.end().name().toLowerCase(java.util.Locale.ROOT));
        origin = point;
        drillSeconds = 0;
        carved = 0;
        Warheads.bunkerEntry(level, point, v, this, ownerId());
    }

    // ---------------------------------------------------------------- путь в грунте

    /** Тело бомбы для закона пробития — из паспорта. */
    public static Penetration.Body body() {
        return Penetration.body(WeaponSpec.BUNKER.payload().article());
    }

    /** Как бомба внутри: в пустоте датчик срабатывает, когда она вошла туда целиком, м. */
    public static double voidDepth() {
        return WeaponSpec.BUNKER.payload().article().length();
    }

    /**
     * Путь в грунте от {@code from} по {@code dir} со скоростью {@code v} м/с: блоки по оси пути, где бомба будет к
     * какому времени, и где встанет; {@code solid0} — сколько твёрдого уже за ней (путь заново после загрузки). Читаются
     * только готовые чанки: неготовый — конец пути (подрыв там). В блоке — столько среды, сколько оси пути внутри его
     * формы столкновений (ковёр, плита, ступени — не целый метр); остальное в нём — пустота или жидкость, что в нём стоит.
     * Блок с записью в карте данных и без формы (рыхлый снег) — среда весь.
     */
    public static Path plan(ServerLevel level, Vec3 from, Vec3 dir, double v, double solid0) {
        Penetration.Run run = new Penetration.Run(body(), v);
        List<Step> steps = new ArrayList<>();
        double s = 0, t = 0, solid = solid0, hollow = 0, depth = voidDepth();
        Path.End end = Path.End.LIMIT;
        while (s < MAX_PATH) {
            Vec3 at = from.add(dir.scale(s));
            BlockPos b = BlockPos.containing(at.add(dir.scale(1e-4)));
            if (!level.isInWorldBounds(b) || !Terrain.ready(level, b)) {
                end = Path.End.EDGE;
                break;
            }
            double len = Math.max(1e-4, exit(at, dir, b));
            BlockState state = level.getBlockState(b);
            FluidState fluid = state.getFluidState();
            Penetrability entry = Penetrability.find(state);
            VoxelShape shape = state.getCollisionShape(level, b);
            // среда блока: запись карты (или прочее твёрдое) по длине оси в форме; жидкость — по остатку
            double matter = entry != null && shape.isEmpty() ? len : inside(shape, b, at, dir, len);
            Penetrability medium = matter > 0 ? (entry != null ? entry : Penetrability.OTHER) : null;
            // жидкость без записи в карте (мода) — жидкость плотности воды, а не прочее твёрдое
            Penetrability liquid = fluid.isEmpty() ? null : Penetrability.find(fluid.createLegacyBlock());
            if (liquid == null && !fluid.isEmpty()) liquid = Penetrability.WATER;
            double va = run.speed();
            if (va <= 0) {
                // скорость кончилась (путь заново после загрузки на последнем тике): встала здесь
                end = Path.End.REST;
                break;
            }
            if (medium != null) {
                hollow = 0;
                double went = run.through(medium.kind(), medium.s(), matter);
                double vb = run.speed();
                s += went;
                t += went > 0 ? 2 * went / (va + vb) : 0;
                if (arms(medium)) solid += went;
                va = vb;
                if (run.stopped()) {
                    steps.add(new Step(b, s, t, carves(medium), solid));
                    end = Path.End.REST;
                    break;
                }
            }
            double rest = len - matter;
            if (rest > 0 && liquid != null) {
                hollow = 0;
                double went = run.through(liquid.kind(), liquid.s(), rest);
                double vb = run.speed();
                s += went;
                t += went > 0 ? 2 * went / (va + vb) : 0;
                if (run.stopped()) {
                    steps.add(new Step(b, s, t, medium != null && carves(medium), solid));
                    end = Path.End.REST;
                    break;
                }
            } else if (rest > 0) {
                run.gap();
                if (solid >= SENSOR_SOLID && hollow + rest >= depth) {
                    double went = depth - hollow;
                    s += went;
                    t += went / va;
                    steps.add(new Step(b, s, t, medium != null && carves(medium), solid));
                    end = Path.End.VOID;
                    break;
                }
                hollow += rest;
                s += rest;
                t += rest / va;
            }
            steps.add(new Step(b, s, t, medium != null && carves(medium), solid));
        }
        return new Path(List.copyOf(steps), s, t, end);
    }

    /** Выбивается ли блок среды в канал: твёрдое — да; жидкость (вода, лава) и непробиваемое — нет. */
    static boolean carves(Penetrability m) {
        return m.kind() != Penetration.Kind.FLUID && m.kind() != Penetration.Kind.STOP;
    }

    /**
     * Взводит ли среда датчик пустоты: твёрдая и не мягче S {@value #SENSOR_SOFT} — в листве, шерсти, рыхлом снеге
     * замедление в десятки раз слабее, чем в грунте, и датчик (по замедлению) их за преграду не считает (оценка).
     */
    static boolean arms(Penetrability m) {
        return m.kind() != Penetration.Kind.FLUID && m.kind() != Penetration.Kind.STOP && m.s() < SENSOR_SOFT;
    }

    /** Сколько оси пути от {@code at} длиной {@code len} по {@code dir} внутри формы {@code shape} блока {@code b}, м. */
    private static double inside(VoxelShape shape, BlockPos b, Vec3 at, Vec3 dir, double len) {
        if (shape.isEmpty()) return 0;
        double sum = 0;
        for (AABB box : shape.toAabbs()) {
            AABB w = box.move(b);
            // отрезок оси внутри коробки: пересечение интервалов по трём осям
            double lo = 0, hi = len;
            double[] p = {at.x, at.y, at.z}, d = {dir.x, dir.y, dir.z}, mn = {w.minX, w.minY, w.minZ}, mx = {w.maxX, w.maxY, w.maxZ};
            for (int k = 0; k < 3 && lo < hi; k++) {
                if (Math.abs(d[k]) < 1e-12) {
                    if (p[k] < mn[k] || p[k] > mx[k]) hi = lo;
                    continue;
                }
                double a = (mn[k] - p[k]) / d[k], c = (mx[k] - p[k]) / d[k];
                lo = Math.max(lo, Math.min(a, c));
                hi = Math.min(hi, Math.max(a, c));
            }
            if (hi > lo) sum += hi - lo;
        }
        return Math.min(len, sum);
    }

    /** Сколько пути от {@code at} по {@code dir} до выхода из блока {@code b}, м. */
    private static double exit(Vec3 at, Vec3 dir, BlockPos b) {
        double best = Double.POSITIVE_INFINITY;
        best = Math.min(best, axis(at.x, dir.x, b.getX()));
        best = Math.min(best, axis(at.y, dir.y, b.getY()));
        best = Math.min(best, axis(at.z, dir.z, b.getZ()));
        return best;
    }

    private static double axis(double p, double d, int lo) {
        if (Math.abs(d) < 1e-9) return Double.POSITIVE_INFINITY;
        return ((d > 0 ? lo + 1 : lo) - p) / d;
    }

    /**
     * Тик в грунте: бомба проходит путь, пройденный за 0,05 с, — канал по оси и удар корпуса по тем, кто на пути; путь
     * кончился — подрыв. Путь из сохранения не хранится: после загрузки — заново отсюда с той же скоростью.
     */
    private void drillTick(ServerLevel level) {
        if (path == Path.EMPTY) {
            path = plan(level, position(), flight.forward(), speed * 20, armed);
            origin = position();
            drillSeconds = 0;
            carved = 0;
        }
        Vec3 from = position();
        drillSeconds += 0.05;
        double s = Math.min(path.length(), along(drillSeconds));
        while (carved < path.steps().size() && path.steps().get(carved).s() <= s + 1e-6) {
            Step step = path.steps().get(carved++);
            armed = step.armed();
            if (step.solid()) carve(level, step.pos());
        }
        Vec3 to = origin.add(flight.forward().scale(s));
        strike(level, from, to);
        speed = Math.max(0, speedAt(drillSeconds) / 20);
        moveAlong(level, to, flight.forward());
        if (drillSeconds >= path.seconds()) detonate(level);
    }

    /** Сколько пути пройдено к {@code seconds} в грунте, м (между блоками — по прямой во времени). */
    private double along(double seconds) {
        double s0 = 0, t0 = 0;
        for (Step step : path.steps()) {
            if (step.t() >= seconds) return s0 + (step.s() - s0) * (seconds - t0) / Math.max(1e-9, step.t() - t0);
            s0 = step.s();
            t0 = step.t();
        }
        return path.length();
    }

    /** Скорость в грунте к {@code seconds}, м/с (по шагу пути). */
    private double speedAt(double seconds) {
        double s0 = 0, t0 = 0;
        for (Step step : path.steps()) {
            if (step.t() >= seconds) return (step.s() - s0) / Math.max(1e-9, step.t() - t0);
            s0 = step.s();
            t0 = step.t();
        }
        return 0;
    }

    /**
     * Канал: блок на оси пути выбит (калибр 0,8 м — один блок), сыпучее над ним падает в ход. Только где готовы и соседние
     * чанки: соседей читают обновления и Sable.
     */
    private static void carve(ServerLevel level, BlockPos p) {
        if (!IncomingConfig.SERVER.blockDamage.get() || !Terrain.neighbourhoodReady(level, p.getX() >> 4, p.getZ() >> 4)) return;
        BlockState s = level.getBlockState(p);
        if (s.isAir()) return;
        ColumnScar.replace(level, p, s, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    /** Корпус в 13,6 т на сотнях метров в секунду: кто на пути бомбы за этот тик — погибает (удар корпуса). */
    private void strike(ServerLevel level, Vec3 from, Vec3 to) {
        double r = body().diameter() / 2;
        AABB box = new AABB(from, to).inflate(r + 0.5);
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
            if (e.getBoundingBox().inflate(r).clip(from, to).isPresent() || e.getBoundingBox().inflate(r).contains(from)) {
                e.hurt(ModDamageTypes.source(level, ModDamageTypes.KINETIC, this, ownerPlayer()), Float.MAX_VALUE);
            }
        }
    }

    private void detonate(ServerLevel level) {
        Vec3 at = position();
        Vec3 entry = entry();
        discard();
        Warheads.bunker(level, at, entry, this, ownerId());
    }

    /**
     * Блок пути: где он, сколько пути и времени от входа к его концу, твёрдый ли (выбивается в канал).
     *
     * @param s     путь от входа к концу блока, м
     * @param t     время от входа к концу блока, с
     * @param solid твёрдая среда (жидкость и пустота каналом не выбиваются)
     * @param armed сколько твёрдого, что взводит датчик пустоты, пройдено к концу блока, м
     */
    public record Step(BlockPos pos, double s, double t, boolean solid, double armed) {}

    /**
     * Путь в грунте.
     *
     * @param length  весь путь, м
     * @param seconds время до подрыва, с
     * @param end     чем кончился
     */
    public record Path(List<Step> steps, double length, double seconds, End end) {
        static final Path EMPTY = new Path(List.of(), 0, 0, End.LIMIT);

        public enum End {
            /** Встала. */
            REST,
            /** Датчик пустоты. */
            VOID,
            /** Неготовый чанк или край мира. */
            EDGE,
            /** Самый длинный путь. */
            LIMIT
        }
    }

    // ---------------------------------------------------------------- данные

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ENTRY, new Vector3f());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        Vec3 d = Nbt.getVec(tag, "wind_drift");
        windDrift = d != null ? d : Vec3.ZERO;
        goal = NbtUtils.readBlockPos(tag, "goal").orElse(null);
        armed = tag.getDouble("armed");
        entityData.set(DATA_ENTRY, new Vector3f(tag.getFloat("entry_x"), tag.getFloat("entry_y"), tag.getFloat("entry_z")));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        Nbt.putVec(tag, "wind_drift", windDrift);
        if (goal != null) tag.put("goal", NbtUtils.writeBlockPos(goal));
        tag.putDouble("armed", armed);
        Vec3 e = entry();
        tag.putFloat("entry_x", (float) e.x);
        tag.putFloat("entry_y", (float) e.y);
        tag.putFloat("entry_z", (float) e.z);
    }
}
