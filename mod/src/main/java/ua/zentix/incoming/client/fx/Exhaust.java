package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.far.FarBlasts;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.plume.NozzleGlow;
import ua.zentix.incoming.client.fx.plume.TrailModel;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
import ua.zentix.incoming.client.fx.layer.FxLights;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.fx.smoke.Puffs;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.BomberEntity;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.entity.CruiseMissileEntity;
import ua.zentix.incoming.entity.DebrisEntity;
import ua.zentix.incoming.entity.DroneEntity;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.IcbmEntity;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.entity.RocketEntity;
import ua.zentix.incoming.entity.StrikeProjectile;
import ua.zentix.incoming.util.Local;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Двигатели в полёте: факел (его рисует {@link ua.zentix.incoming.client.render.StrikeProjectileRenderer} по
 * {@link #plume}), огонь и вспышки поджига и схода, искры. Дым двигателей частицами не рисуется: шлейф — лента по точкам
 * пути и объём сетки дыма у глаза ({@code client.far.FarFlightView}, закон — {@link TrailModel}), облако старта — объём
 * ({@code fx/smoke/LaunchCloud}: продукты, пока струя бьёт в землю, и поднятая ею пыль).
 */
public final class Exhaust {
    /**
     * Факел в системе снаряда: срез сопла в (0, {@code y}, {@code z}) (нос по +Z), факел на высоте снаряда и доля струи
     * {@code near} (с 0,8 прорисовки струю сменяет свет того же факела вдали). Привыкание глаза — у слоя эффектов,
     * который её рисует ({@code PlumeRenderer}): то же, что у света факела вдали.
     */
    public record Plume(float y, float z, Flame flame, float near) {
        /** Та же струя, тусклее в k раз (на краю прорисовки её сменяет свет факела вдали). */
        public Plume dimmed(float k) {
            return new Plume(y, z, flame, near * k);
        }
    }

    private static final class State {
        /** Камера игрока — на этом снаряде (видео с борта). */
        boolean onboard;
        Vec3 pad;
        /** Передний срез трубы РСЗО, из которой сошёл снаряд. */
        Vec3 muzzle;
    }

    private static final Map<StrikeProjectile, State> STATES = new WeakHashMap<>();

    private Exhaust() {}

    /** Раз в тик на клиенте для каждого летящего снаряда. */
    public static void tick(StrikeProjectile e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        State s = STATES.computeIfAbsent(e, k -> new State());
        s.onboard = Minecraft.getInstance().getCameraEntity() == e;
        switch (e) {
            case IcbmEntity icbm -> icbm(level, icbm, s);
            case CruiseMissileEntity m -> missile(level, m, s);
            case DroneEntity d -> drone(level, d, s);
            case RocketEntity rk -> rocket(level, rk, s);
            case BunkerBusterEntity b -> bomb(level, b, s);
            default -> {}
        }
    }

    /**
     * Факел двигателя сейчас или null — не горит: факел фазы из паспорта по законам ({@link FlameModel}: тот же, что свет
     * факела вдали) на высоте снаряда. Пульсаций нет: турбулентность струи — тысячи герц, за кадр она усредняется.
     */
    @Nullable
    public static Plume plume(StrikeProjectile e, float partial) {
        if (!e.isActive() || !(e.level() instanceof ClientLevel level)) return null;
        ClientWeaponSpec.ClientAirframe a = nozzles(e);
        // доля тяги ускорителя — по кривой (выход на режим, спад к отделению), с долей тика: факел гаснет без ступенек
        ClientWeaponSpec.Stage stage = a.far().stage(e.flightPhase(), e.phaseAge() + partial, e.airframe().thrust());
        Flame f = stage.flame(e.getY());
        if (f == null) return null;
        ClientWeaponSpec.At n = a.nozzle(stage);
        return new Plume((float) n.y(), (float) n.z(), f, 1);
    }

    /** Горячее сопло ускорителя на снаряде: вид в этот миг и срез в системе снаряда (нос по +Z). */
    public record Hot(NozzleGlow.Shine shine, ClientWeaponSpec.At at) {}

    /**
     * Горячее сопло стартового ускорителя, пока он на снаряде ({@link NozzleGlow}: время — настоящее, полка игрового
     * разгона сжата в настоящую); null — ускорителя нет, он отделился или сопло ещё не посчитано.
     */
    @Nullable
    public static Hot hot(StrikeProjectile e, float partial) {
        if (!e.isActive() || !e.flightPhase().boosterLit()) return null;
        ClientWeaponSpec.At at = nozzles(e).boosterNozzle();
        NozzleGlow g = at == null ? null : ClientWeaponSpec.nozzleGlow(e.weapon());
        if (g == null) return null;
        return new Hot(g.at(g.seconds(e.flightPhase(), e.phaseAge() + partial)), at);
    }

    /** Сопла сущности из клиентского паспорта (у B-2 и его бомбы — свои). */
    private static ClientWeaponSpec.ClientAirframe nozzles(StrikeProjectile e) {
        return ClientWeaponSpec.of(e.weapon()).airframe(!(e instanceof BomberEntity));
    }

    /** Точка в осях снаряда — в мир. */
    private static Vec3 at(StrikeProjectile e, ClientWeaponSpec.At p) {
        return Local.at(e.position(), e.getYRot(), e.getXRot(), p.x(), p.y(), p.z());
    }

    // ---------------------------------------------------------------- шахед

    /** Старт на ускорителе; дальше тонкий сизый выхлоп поршневого мотора — только лентой. */
    private static void drone(ClientLevel level, DroneEntity e, State s) {
        booster(level, e, s);
    }

    // ---------------------------------------------------------------- РСЗО

    /**
     * Реактивный снаряд «Града». Поджиг в трубе (3 тика): струя бьёт назад из заднего среза трубы — огонь и вспышка (дым
     * и пыль, что она бьёт в землю под пакетом, — облако старта). Сход: вспышка и искры у переднего среза. След —
     * лентой и объёмом ({@code FarFlightView}).
     */
    private static void rocket(ClientLevel level, RocketEntity e, State s) {
        FlightPhase ph = e.flightPhase();
        if (!e.isActive() || !ph.boosterLit()) return;
        RandomSource r = level.random;
        float yaw = e.getYRot(), pitch = e.getXRot();
        Vec3 back = Local.offset(yaw, pitch, 0, 0, -1);
        if (s.pad == null) {
            // первый тик поджига: снаряд ещё в трубе, его центр — середина трубы
            s.pad = e.position().add(back.scale(LauncherEntity.TUBE_LENGTH / 2));
            s.muzzle = e.position().subtract(back.scale(LauncherEntity.TUBE_LENGTH / 2));
        }
        if (ph == FlightPhase.IGNITION) {
            // струя из заднего среза трубы
            for (int i = 0; i < 4; i++) {
                Fx.fire().vel(back.scale(0.5 + r.nextDouble() * 0.7).add(r.nextGaussian() * 0.08, r.nextGaussian() * 0.08, r.nextGaussian() * 0.08))
                        .size(0.4f, 1.5f).life(4 + r.nextInt(4)).alpha(0.9f).drag(0.75f).spawn(level, s.pad);
            }
            Fx.flash().size(2.5f, 3.5f).life(2).alpha(0.7f).spawn(level, s.pad);
            return;
        }
        int age = e.phaseAge();
        if (age < 2) {
            // сход: огонь вырывается из переднего среза вслед за снарядом, искры
            Fx.flash().size(2, 3).life(2).alpha(0.6f).spawn(level, s.muzzle);
            for (int i = 0; i < 6; i++) {
                Fx.spark().vel(back.scale(-0.3 - r.nextDouble() * 0.5).add(r.nextGaussian() * 0.2, r.nextGaussian() * 0.2, r.nextGaussian() * 0.2))
                        .life(6 + r.nextInt(8)).size(0.05f, 0.02f).spawn(level, s.muzzle);
            }
        }
    }

    // ---------------------------------------------------------------- крылатая ракета

    /** ТРДД огня и дыма не даёт (его след — по закону, {@code TrailModel}); в пике — пар на корпусе (конденсация на околозвуке). */
    private static void missile(ClientLevel level, CruiseMissileEntity e, State s) {
        if (!e.isActive() || booster(level, e, s)) return;
        RandomSource r = level.random;
        // «воротник» пара вокруг корпуса виден только снаружи; с борта он у самого объектива
        if (e.flightPhase() == FlightPhase.TERMINAL && !s.onboard) {
            for (int i = 0; i < 3; i++) {
                double a = r.nextDouble() * Mth.TWO_PI;
                Vec3 p = Local.at(e.position(), e.getYRot(), e.getXRot(), Math.cos(a) * 0.35, Math.sin(a) * 0.35, 0.8 - r.nextDouble() * 1.6);
                Fx.smoke().size(0.3f, 1.2f).life(8).color(0xF4F6F8, 0xFFFFFF).alpha(0.45f).fadeIn(1).fadeFrom(0.2f).drag(0.8f)
                        .wind(0).spawn(level, p);
            }
        }
    }

    // ---------------------------------------------------------------- старт с пусковой

    /**
     * Шахед и ракета на пусковой и на ускорителе: на направляющей — ничего; поджиг — вспышки у сопла ускорителя (облако
     * у пусковой и белый след ускорителя — объёмом, {@code FarFlightView}). После отделения — обычный выхлоп (вернёт
     * false).
     */
    private static boolean booster(ClientLevel level, StrikeProjectile e, State s) {
        FlightPhase ph = e.flightPhase();
        if (ph == FlightPhase.READY) return true;
        if (!ph.boosterLit()) return false;
        Vec3 nozzle = at(e, nozzles(e).boosterNozzle());
        if (ph == FlightPhase.IGNITION && e.phaseAge() % 2 == 0) {
            float scale = nozzles(e).boosterSmoke();
            Fx.flash().size(3 * scale, 4 * scale).life(3).alpha(0.5f).spawn(level, nozzle);
        }
        return true;
    }

    // ---------------------------------------------------------------- МБР

    /**
     * Твердотопливная первая ступень: на старте из шахты — огонь у оголовка и вспышки (облако старта и белый шлейф —
     * объёмом, {@code FarFlightView}).
     */
    private static void icbm(ClientLevel level, IcbmEntity e, State s) {
        RandomSource r = level.random;
        if (s.pad == null) s.pad = e.position().add(0, -9, 0);
        int age = e.age();
        if (age < 50) {
            Vec3 pad = s.pad;
            for (int i = 0; i < 4; i++) {
                Fx.fire().vel(r.nextGaussian() * 0.15, 0.3 + r.nextDouble() * 0.5, r.nextGaussian() * 0.15).size(1.5f, 3.5f)
                        .life(10 + r.nextInt(8)).spawn(level, pad.x + r.nextGaussian(), pad.y + 1, pad.z + r.nextGaussian());
            }
            if (age % 2 == 0) Fx.flash().size(10, 12).life(3).alpha(0.5f).spawn(level, pad.add(0, 3, 0));
        }
    }

    // ---------------------------------------------------------------- бомба (инверсионные следы B-2 — лентой)

    /**
     * Бомба: срыв потока с хвоста (лентой); около скорости звука во влажном воздухе — «воротник» пара ({@link VapourCollar});
     * пыль входа в грунт — облако модели ({@link BlastEffects#bunkerImpact}).
     */
    private static void bomb(ClientLevel level, BunkerBusterEntity e, State s) {
        RandomSource r = level.random;
        // в грунте — ничего: пыль выброса из входа — облако модели (BlastEffects.bunkerImpact)
        if (e.isDrilling()) return;
        Vec3 at = e.position();
        // влажность — атмосфера мира (погода), как у следов и облаков
        double mach = VapourCollar.mach(e.speed() * 20, at.y - ua.zentix.incoming.util.Air.SEA);
        if (VapourCollar.forms(mach, ua.zentix.incoming.util.Air.of(level).humidity())) {
            for (int i = 0; i < 4; i++) {
                double a = r.nextDouble() * Mth.TWO_PI;
                Vec3 p = Local.at(e.position(), e.getYRot(), e.getXRot(), Math.cos(a) * 1.1, Math.sin(a) * 1.1, 2 - r.nextDouble());
                Fx.smoke().size(0.5f, 1.6f).life(7).color(0xF6F8FA, 0xFFFFFF).alpha(0.5f).fadeIn(1).fadeFrom(0.15f).drag(0.7f)
                        .wind(0).spawn(level, p);
            }
        }
    }

    // ---------------------------------------------------------------- зенитная ракета

    /**
     * Зенитная ракета: вспышка поджига у пакета и огонь у сопла. Дым — по закону, как у снарядов: облако старта, пока
     * струя бьёт в землю, и след лентой ({@code FarFlightView} по пути ракеты, {@code ClientWeaponSpec.INTERCEPTOR}).
     */
    public static void interceptor(InterceptorEntity e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        RandomSource r = level.random;
        Vec3 back = Local.offset(e.getYRot(), e.getXRot(), 0, 0, -1);
        Vec3 nozzle = e.position().add(back.scale(InterceptorSpec.SAM.noseLength()));
        if (e.age() <= 2 && e.tickCount <= 1) Fx.flash().size(2.5f, 3.5f).life(3).alpha(0.6f).spawn(level, nozzle);
        for (int i = 0; i < 2; i++) {
            Fx.fire().vel(back.scale(0.3 + r.nextDouble() * 0.3).add(r.nextGaussian() * 0.04, r.nextGaussian() * 0.04, r.nextGaussian() * 0.04))
                    .size(0.25f, 0.6f).life(2 + r.nextInt(2)).alpha(0.85f).drag(0.9f).spawn(level, nozzle);
        }
    }

    // ---------------------------------------------------------------- обломки

    private static final Map<DebrisEntity, Vec3> DEBRIS = new WeakHashMap<>();
    /** Дым горящих обломков — выбросами модели облака ({@link Puffs}), по обломку. */
    private static final Map<DebrisEntity, Burning> BURNING = new WeakHashMap<>();

    /**
     * Горящий обломок: дерево горит гранью ~1 м² ({@link FirePlume#BLOCK_WATTS}, как блок огня у дыма пожаров), за каждые
     * {@link #PUFF_S} с полёта — выброс тепла и сажи древесины ({@link FirePlume#SOOT_PER_JOULE}) термиком с места обломка.
     * Газ над пламенем — избыток на оси по Хескестаду у вершины пламени ({@link FirePlume#excess}).
     */
    private static final class Burning {
        final Puffs smoke;
        double fed;
        Vec3 at = Vec3.ZERO;

        Burning(Puffs smoke, double seconds) {
            this.smoke = smoke;
            this.fed = seconds;
        }
    }

    private static final double[] BB = new double[4];

    /**
     * Пламя горящих обломков светит на дым, частицы и ленты вокруг ({@link FxLights}): шар видимого пламени
     * ({@link FirePlume#flameLight}) радиусом в полвысоты пламени по Хескестаду, с привыканием глаза кадра.
     */
    public static void lights(FarView view, FxLights lights) {
        if (BURNING.isEmpty()) return;
        double now = SmokeGrid.seconds(view.partial()), r = 0.5 * DEBRIS_FLAME.flameHeight();
        FirePlume.flameLight(BB);
        double m = Math.max(BB[0], Math.max(BB[1], BB[2]));
        Vec3 cam = view.camera();
        for (Burning b : BURNING.values()) {
            if (now - b.fed > 2 * PUFF_S + 0.1) continue;
            lights.add(b.at.x - cam.x, b.at.y + r - cam.y, b.at.z - cam.z, r, BB[3] * view.exposure(), FireSmoke.FLAME_ON_SCREEN,
                    (float) (BB[0] / m), (float) (BB[1] / m), (float) (BB[2] / m));
        }
    }

    /** Пламя горящих обломков светит на мир кадра ({@code HeatPass}): тот же шар пламени, что у {@link #lights}. */
    public static void heat(Vec3 eye, double seconds, ua.zentix.incoming.client.fx.layer.HeatPass.Frame f, double air) {
        if (BURNING.isEmpty()) return;
        double r = 0.5 * DEBRIS_FLAME.flameHeight();
        FirePlume.flameLight(BB);
        for (Burning b : BURNING.values()) {
            if (seconds - b.fed > 2 * PUFF_S + 0.1) continue;
            f.light(b.at.x - eye.x, b.at.y + r - eye.y, b.at.z - eye.z, r, BB[0], BB[1], BB[2], air, Math.max(r, 1));
        }
    }

    /** Выброс дыма горящего обломка — раз в столько секунд пути (клуб у обломка — ~0,5 м, на скорости обломка ~10 м/с — через ~σ). */
    static final double PUFF_S = 0.1;
    /** Пламя обломка: грань дерева, очаг ~1 м² ({@link FirePlume}). */
    static final FirePlume DEBRIS_FLAME = new FirePlume(1, FirePlume.CALM, 3);

    /**
     * Горящий обломок: языки огня частицами (светящееся тело) и дым — выбросами модели облака ({@link Burning}), свет пламени —
     * на дым и частицы вокруг ({@link #lights}); остывший — пыльный след, пока летит.
     */
    public static void debris(DebrisEntity e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        Vec3 p = e.position().add(0, 0.5, 0);
        Vec3 from = DEBRIS.put(e, p);
        boolean moving = e.getDeltaMovement().lengthSqr() > 0.01;
        RandomSource r = level.random;
        if (e.isHot()) {
            Fx.fire().vel(r.nextGaussian() * 0.02, 0.04, r.nextGaussian() * 0.02).size(0.35f, 0.6f).life(8 + r.nextInt(6)).spawn(level, p);
            double now = SmokeGrid.seconds(0);
            Burning b = BURNING.get(e);
            if (b == null) {
                b = new Burning(new Puffs(FarBlasts.air(level, p), 0.2f, 0.19f, 0.18f, e.getUUID().getLeastSignificantBits()), now);
                BURNING.put(e, b);
                SmokeGrid.add(b.smoke);
            }
            b.at = p;
            // обломок мог уходить из отслеживания клиента: выброс — не больше двух шагов огня, а не всё пропущенное разом
            double dt = Math.min(now - b.fed, 2 * PUFF_S);
            if (dt >= PUFF_S) {
                double watts = FirePlume.BLOCK_WATTS * dt, top = DEBRIS_FLAME.flameHeight();
                b.smoke.add(p.x, p.y + top, p.z, now, FirePlume.CONVECTIVE * watts, CloudModel.T_AIR_K + DEBRIS_FLAME.excess(top), 0.5,
                        FirePlume.SOOT_PER_JOULE * watts, 0, 0, Double.NaN);
                b.fed = now;
            }
        } else if (moving) {
            segment(level, from, p, Fx.smoke().size(0.2f, 0.8f).life(40).color(0x8A8278, 0xA8A098).alpha(0.35f).fadeFrom(0.3f).budget(FxBudget.DEBRIS), 1.2, r);
        }
    }

    // ---------------------------------------------------------------- общее

    /** Клубы по отрезку от прошлого положения сопла до нынешнего через {@code step} блоков (со случайным сдвигом). */
    private static Vec3 segment(ClientLevel level, @Nullable Vec3 from, Vec3 to, Fx.Spec puff, double step, RandomSource r) {
        if (from == null || from.distanceToSqr(to) > 80 * 80) from = to;
        double len = from.distanceTo(to);
        // шлейф не прореживаем даже вдали: дырявый след заметнее, чем лишние клубы
        int n = Math.max(1, (int) Math.round(len / step));
        for (int i = 0; i < n; i++) {
            double k = (i + r.nextDouble()) / n;
            puff.spawn(level, Mth.lerp(k, from.x, to.x), Mth.lerp(k, from.y, to.y), Mth.lerp(k, from.z, to.z));
        }
        return to;
    }
}
