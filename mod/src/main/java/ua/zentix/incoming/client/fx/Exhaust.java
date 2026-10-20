package ua.zentix.incoming.client.fx;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.plume.Air;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.FlameModel;
import ua.zentix.incoming.client.fx.plume.TrailModel;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.client.fx.particle.Fx;
import ua.zentix.incoming.client.fx.particle.FxBudget;
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
 * {@link #plume}), облако пуска, клубы объёма у густых шлейфов. Сам шлейф и инверсионные следы — лента по точкам пути
 * ({@code client.far.FarFlightView}, закон — {@link TrailModel}): мест частиц она не занимает, поэтому залп в сотни
 * ракет не рвёт следы на отдельные клубы.
 */
public final class Exhaust {
    /**
     * Факел в системе снаряда: срез сопла в (0, {@code y}, {@code z}) (нос по +Z), факел на высоте снаряда, привыкание
     * глаза {@code adapt} (яркость × adapt — во сколько раз он ярче белого экрана, {@code Sight.adapted}) и доля струи
     * {@code near} (с 0,8 прорисовки струю сменяет свет того же факела вдали).
     */
    public record Plume(float y, float z, Flame flame, double adapt, float near) {
        /** Та же струя, тусклее в k раз (на краю прорисовки её сменяет свет факела вдали). */
        public Plume dimmed(float k) {
            return new Plume(y, z, flame, adapt, near * k);
        }
    }

    private static final class State {
        Vec3 lastNozzle;
        /** Сопло на прошлом тике: с борта клубы кладутся с отставанием на тик (см. {@link #volume}). */
        Vec3 heldNozzle;
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
        ClientWeaponSpec.Stage stage = a.far().stage(e.flightPhase());
        Flame f = stage.flame(e.getY());
        if (f == null) return null;
        ClientWeaponSpec.At n = a.nozzle(stage);
        // привыкание глаза — то же, что у света этого факела вдали (FarRenderer: свет неба), иначе на 0,8 прорисовки
        // яркость скакнула бы
        return new Plume((float) n.y(), (float) n.z(), f, Sight.adapted(1, FarView.ambient(level, partial)), 1);
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
     * Реактивный снаряд «Града». Поджиг в трубе (3 тика): струя бьёт назад из заднего среза трубы — огонь, вспышка,
     * клубы, которые стелются по земле под пакетом. Сход: вспышка у переднего среза. Пока горит двигатель — плотный
     * серо-белый след, который висит дугой над позицией; очередь из 40 труб оставляет веер таких дуг и облако
     * у пусковой. После выгорания снаряд летит по инерции без следа.
     */
    /** Клубов объёма за тик у густого шлейфа (РСЗО, ускоритель, МБР): три полных пакета РСЗО помещаются в группу (ExhaustTest). */
    static final int VOLUME_PUFFS_PER_TICK = 1;
    /** Непрозрачность клуба объёма: под ним лента, плотнее — и клубы читались тёмными бусинами на ней. */
    private static final float VOLUME_ALPHA = 0.45f;

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
        Vec3 nozzle = at(e, nozzles(e).engine());
        if (ph == FlightPhase.IGNITION) {
            // струя из заднего среза трубы
            for (int i = 0; i < 4; i++) {
                Fx.fire().vel(back.scale(0.5 + r.nextDouble() * 0.7).add(r.nextGaussian() * 0.08, r.nextGaussian() * 0.08, r.nextGaussian() * 0.08))
                        .size(0.4f, 1.5f).life(4 + r.nextInt(4)).alpha(0.9f).drag(0.75f).spawn(level, s.pad);
            }
            Fx.flash().size(2.5f, 3.5f).life(2).alpha(0.7f).spawn(level, s.pad);
            backblast(level, s.pad, back, 5, r);
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
            Fx.smoke().vel(back.scale(-0.2)).size(0.6f, 2.2f).life(120 + r.nextInt(60)).color(0xE0DCD4, 0xA8A49E).alpha(0.6f)
                    .drag(0.88f).glow(0.7f, 4).rise(0.002f).fadeIn(1).fadeFrom(0.5f).spin(0.01f).budget(FxBudget.GROUND).spawn(level, s.muzzle);
        }
        if (age < 6) backblast(level, s.pad, back, 2, r);
        // след — лента; клубы объёма поверх неё — по тому же закону следа
        volume(level, s, nozzle, nozzles(e).far().stage(ph).trail(), r);
    }

    /** Выхлоп из заднего среза трубы: бьёт назад, в землю под пакетом, и растекается по ней. */
    private static void backblast(ClientLevel level, Vec3 rear, Vec3 back, int puffs, RandomSource r) {
        int n = Fx.count(puffs, rear);
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Mth.TWO_PI, v = 0.1 + r.nextDouble() * 0.3;
            Fx.smoke().vel(back.x * 0.6 + Math.cos(a) * v, back.y * 0.6 + r.nextDouble() * 0.06, back.z * 0.6 + Math.sin(a) * v)
                    .size(0.8f, 4.5f + r.nextFloat() * 2).life(260 + r.nextInt(160)).color(0xE4DFD6, 0xA9A399).alpha(0.75f)
                    .drag(0.9f).glow(0.6f, 6).rise(0.0015f).collide().growFast().fadeIn(2).fadeFrom(0.6f).spin(0.008f)
                    .budget(FxBudget.GROUND).spawn(level, rear.x + r.nextGaussian() * 0.2, rear.y + r.nextGaussian() * 0.2, rear.z + r.nextGaussian() * 0.2);
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
     * Шахед и ракета на пусковой и на ускорителе: на направляющей — ничего; поджиг — огонь и клубы дыма, которые
     * бьют в пусковую и растекаются вокруг неё; разгон — плотный белый шлейф твердотопливного ускорителя, который
     * висит в воздухе дугой старта. После отделения — обычный выхлоп (вернёт false). Срез сопла ускорителя и размер
     * дыма (шахед меньше ракеты) — из клиентского паспорта. Шлейф ускорителя — лента и клубы объёма.
     */
    private static boolean booster(ClientLevel level, StrikeProjectile e, State s) {
        FlightPhase ph = e.flightPhase();
        if (ph == FlightPhase.READY) return true;
        if (!ph.boosterLit()) return false;
        RandomSource r = level.random;
        ClientWeaponSpec.ClientAirframe nozzles = nozzles(e);
        float scale = nozzles.boosterSmoke();
        Vec3 nozzle = at(e, nozzles.boosterNozzle());
        Vec3 back = Local.offset(e.getYRot(), e.getXRot(), 0, 0, -1);
        if (s.pad == null) s.pad = nozzle;
        volume(level, s, nozzle, nozzles.far().stage(ph).trail(), r);
        if (ph == FlightPhase.IGNITION || e.phaseAge() < 8) {
            // струя бьёт в пусковую: дым отражается и стелется по земле вокруг
            int n = Fx.count(ph == FlightPhase.IGNITION ? 6 : 3, s.pad);
            for (int i = 0; i < n; i++) {
                double a = r.nextDouble() * Mth.TWO_PI, v = (0.15 + r.nextDouble() * 0.35) * scale;
                Fx.smoke().vel(back.x * 0.3 + Math.cos(a) * v, 0.02 + r.nextDouble() * 0.08, back.z * 0.3 + Math.sin(a) * v)
                        .size(1.0f * scale, (5 + r.nextFloat() * 3) * scale).life(240 + r.nextInt(160)).color(0xE9E4DC, 0xB4AEA6)
                        .alpha(0.8f).drag(0.93f).glow(0.6f, 8).rise(0.0015f).collide().growFast().fadeIn(3).fadeFrom(0.6f).spin(0.008f)
                        .budget(FxBudget.GROUND).spawn(level, s.pad.x + r.nextGaussian() * 0.5, s.pad.y - 0.5, s.pad.z + r.nextGaussian() * 0.5);
            }
            if (ph == FlightPhase.IGNITION && e.phaseAge() % 2 == 0) Fx.flash().size(3 * scale, 4 * scale).life(3).alpha(0.5f).spawn(level, nozzle);
        }
        return true;
    }

    // ---------------------------------------------------------------- МБР

    /**
     * Твердотопливная первая ступень: густой белый шлейф (лента и клубы объёма), который висит минутами и сносится
     * ветром; у сопла он подсвечен факелом. На старте из шахты — клубы, растекающиеся по земле во все стороны, и огонь
     * у оголовка.
     */
    private static void icbm(ClientLevel level, IcbmEntity e, State s) {
        RandomSource r = level.random;
        Vec3 nozzle = at(e, nozzles(e).engine());
        volume(level, s, nozzle, nozzles(e).far().stage(e.flightPhase()).trail(), r);
        if (s.pad == null) s.pad = e.position().add(0, -9, 0);
        int age = e.age();
        if (age < 140) launchCloud(level, s.pad, age, r);
    }

    /** Облако старта: из шахты бьёт огонь, дым выдавливает вверх и стелет по земле кольцом. */
    private static void launchCloud(ClientLevel level, Vec3 pad, int age, RandomSource r) {
        int n = Fx.count(age < 40 ? 10 : 4, pad);
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * Mth.TWO_PI, v = 0.5 + r.nextDouble() * 0.9;
            Fx.smoke().vel(Math.cos(a) * v, 0.05 + r.nextDouble() * 0.15, Math.sin(a) * v).size(2.5f, 11 + r.nextFloat() * 5)
                    .life(500 + r.nextInt(300)).color(0xE9E4DC, 0xB4AEA6).alpha(0.85f).drag(0.95f).glow(age < 40 ? 0.7f : 0.2f, 12)
                    .rise(0.0015f).collide().growFast().fadeIn(3).fadeFrom(0.6f).spin(0.008f)
                    .budget(FxBudget.GROUND).spawn(level, pad.x + Math.cos(a) * 2, pad.y + 1 + r.nextDouble() * 2, pad.z + Math.sin(a) * 2);
        }
        if (age < 50) {
            for (int i = 0; i < 4; i++) {
                Fx.fire().vel(r.nextGaussian() * 0.15, 0.3 + r.nextDouble() * 0.5, r.nextGaussian() * 0.15).size(1.5f, 3.5f)
                        .life(10 + r.nextInt(8)).spawn(level, pad.x + r.nextGaussian(), pad.y + 1, pad.z + r.nextGaussian());
            }
            if (age % 2 == 0) Fx.flash().size(10, 12).life(3).alpha(0.5f).spawn(level, pad.add(0, 3, 0));
        }
        // столб, который вытягивает за ракетой
        if (age < 90) {
            Fx.smoke().vel(r.nextGaussian() * 0.1, 0.5 + r.nextDouble() * 0.4, r.nextGaussian() * 0.1).size(3, 9).life(600 + r.nextInt(200))
                    .color(0xECE8E2, 0xBCB8B2).alpha(0.8f).drag(0.94f).glow(0.4f, 8).fadeIn(3).fadeFrom(0.6f).budget(FxBudget.GROUND)
                    .spawn(level, pad.add(0, 4, 0));
        }
    }

    // ---------------------------------------------------------------- бомба (инверсионные следы B-2 — лентой)

    /** Бомба: срыв потока с хвоста (лентой); у звукового барьера — «воротник» пара; при бурении — пыль у входа. */
    private static void bomb(ClientLevel level, BunkerBusterEntity e, State s) {
        RandomSource r = level.random;
        if (e.isDrilling()) {
            Vec3 in = e.entry();
            Fx.smoke().vel(r.nextGaussian() * 0.05, 0.08 + r.nextDouble() * 0.08, r.nextGaussian() * 0.05).size(0.6f, 3).life(120)
                    .color(0x7A7066, 0xA49A90).alpha(0.6f).rise(0.002f).budget(FxBudget.GROUND).spawn(level, in.x + r.nextGaussian() * 0.4, in.y + 0.4, in.z + r.nextGaussian() * 0.4);
            return;
        }
        if (e.speed() >= 8) {
            for (int i = 0; i < 4; i++) {
                double a = r.nextDouble() * Mth.TWO_PI;
                Vec3 p = Local.at(e.position(), e.getYRot(), e.getXRot(), Math.cos(a) * 1.1, Math.sin(a) * 1.1, 2 - r.nextDouble());
                Fx.smoke().size(0.5f, 1.6f).life(7).color(0xF6F8FA, 0xFFFFFF).alpha(0.5f).fadeIn(1).fadeFrom(0.15f).drag(0.7f)
                        .wind(0).spawn(level, p);
            }
        }
    }

    // ---------------------------------------------------------------- зенитная ракета

    private static final Map<InterceptorEntity, Vec3> INTERCEPTORS = new WeakHashMap<>();

    /**
     * Зенитная ракета: огонь у сопла и белый дымный след твердотопливного двигателя клубами по пути сопла за тик —
     * висит над позицией ЗРК дугой пуска и тает за полминуты. Сход с направляющей — вспышка и клубы у пусковой.
     */
    public static void interceptor(InterceptorEntity e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        RandomSource r = level.random;
        Vec3 back = Local.offset(e.getYRot(), e.getXRot(), 0, 0, -1);
        Vec3 nozzle = e.position().add(back.scale(InterceptorSpec.SAM.noseLength()));
        Vec3 from = INTERCEPTORS.put(e, nozzle);
        if (from == null && e.age() <= 2) {
            // сход: вспышка у пакета, дым растекается вокруг
            Fx.flash().size(2.5f, 3.5f).life(3).alpha(0.6f).spawn(level, nozzle);
            for (int i = 0; i < Fx.count(8, nozzle); i++) {
                double a = r.nextDouble() * Mth.TWO_PI, v = 0.15 + r.nextDouble() * 0.3;
                Fx.smoke().vel(Math.cos(a) * v, 0.02 + r.nextDouble() * 0.06, Math.sin(a) * v).size(0.8f, 4.5f).life(220 + r.nextInt(120))
                        .color(0xE9E4DC, 0xB4AEA6).alpha(0.75f).drag(0.92f).glow(0.6f, 6).rise(0.0015f).collide().growFast().fadeIn(2)
                        .fadeFrom(0.6f).spin(0.008f).budget(FxBudget.GROUND).spawn(level, nozzle.x, nozzle.y - 1, nozzle.z);
            }
        }
        Fx.Spec puff = Fx.smoke().size(0.35f, 2.2f).life(360 + r.nextInt(200)).color(0xF0EDE8, 0xBDB9B3).colorCurve(0.6f).alpha(0.55f)
                .drag(0.9f).glow(0.8f, 4).rise(0.0012f).fadeIn(1).fadeFrom(0.5f).spin(0.01f).budget(FxBudget.TRAIL);
        segment(level, from, nozzle, puff, 1.6, r);
        for (int i = 0; i < 2; i++) {
            Fx.fire().vel(back.scale(0.3 + r.nextDouble() * 0.3).add(r.nextGaussian() * 0.04, r.nextGaussian() * 0.04, r.nextGaussian() * 0.04))
                    .size(0.25f, 0.6f).life(2 + r.nextInt(2)).alpha(0.85f).drag(0.9f).spawn(level, nozzle);
        }
    }

    // ---------------------------------------------------------------- обломки

    private static final Map<DebrisEntity, Vec3> DEBRIS = new WeakHashMap<>();

    /** Горящий обломок: языки огня и жирный чёрный дым по всему пути; остывший — пыльный след, пока летит. */
    public static void debris(DebrisEntity e) {
        if (!(e.level() instanceof ClientLevel level)) return;
        Vec3 p = e.position().add(0, 0.5, 0);
        Vec3 from = DEBRIS.put(e, p);
        boolean moving = e.getDeltaMovement().lengthSqr() > 0.01;
        RandomSource r = level.random;
        if (e.isHot()) {
            Fx.fire().vel(r.nextGaussian() * 0.02, 0.04, r.nextGaussian() * 0.02).size(0.35f, 0.6f).life(8 + r.nextInt(6)).spawn(level, p);
            Fx.Spec smoke = Fx.smoke().vel(0, 0.03, 0).size(0.35f, 1.8f).life(90 + r.nextInt(50)).color(0x221E1C, 0x5E5854).alpha(0.7f)
                    .glow(0.5f, 4).rise(0.005f).fadeIn(2).fadeFrom(0.35f).budget(FxBudget.DEBRIS);
            if (moving) segment(level, from, p, smoke, 0.8, r);
            else if (r.nextInt(3) == 0) smoke.spawn(level, p);
        } else if (moving) {
            segment(level, from, p, Fx.smoke().size(0.2f, 0.8f).life(40).color(0x8A8278, 0xA8A098).alpha(0.35f).fadeFrom(0.3f).budget(FxBudget.DEBRIS), 1.2, r);
        }
    }

    // ---------------------------------------------------------------- общее

    /**
     * Клубы объёма поверх ленты шлейфа: {@link #VOLUME_PUFFS_PER_TICK} за тик в случайных местах пути сопла за тик,
     * в группе шлейфов (полная группа клубов не примет, а лента останется). Лента сплошная, но лицом к камере и
     * гладкая: клубы дают ей неровный край и плотность, когда смотришь вдоль следа; сами они тоже гладкие и без среза
     * бледного края ({@link Fx.Spec#smooth}), иначе ложатся на ленту белыми хлопьями, и видны, только когда на экране
     * перекрывают соседей ({@link Fx.Spec#chain}): сбоку, где лента и так во всю ширину, молодые клубы мельче пути за тик
     * читались бы цепочкой точек. С борта камера стоит между прошлым и нынешним положением снаряда, и клуб последнего
     * отрезка (на быстром снаряде — десяток блоков и больше) оказывается вплотную перед объективом (размытое пятно на весь
     * кадр). Поэтому, пока камера на снаряде, отрезок берётся тиком позже — уже за камерой. Снаряд на клиенте сдвигается
     * пакетом сервера, и в тик без пакета отрезка нет — нет и клуба: у клуба без пути не было бы шага цепочки, и он
     * оставался бы виден всегда (светлые точки и овалы на ленте); следующий отрезок длиннее, и шаг его клуба — весь
     * отрезок.
     */
    private static void volume(ClientLevel level, State s, Vec3 nozzle, @Nullable Trail trail, RandomSource r) {
        Vec3 held = s.heldNozzle;
        s.heldNozzle = nozzle;
        Vec3 to = s.onboard ? held : nozzle;
        if (to == null) return;
        Vec3 from = s.lastNozzle == null || s.lastNozzle.distanceToSqr(to) > 80 * 80 ? to : s.lastNozzle;
        s.lastNozzle = to;
        if (trail == null || from.distanceToSqr(to) < 1e-6) return;
        Fx.Spec puff = puff(trail, from.distanceTo(to) * 20, to.y);
        if (puff == null) return;
        puff.budget(FxBudget.TRAIL).smooth().chain(to.x - from.x, to.y - from.y, to.z - from.z, from.distanceTo(to) / VOLUME_PUFFS_PER_TICK);
        for (int i = 0; i < VOLUME_PUFFS_PER_TICK; i++) {
            double k = (i + r.nextDouble()) / VOLUME_PUFFS_PER_TICK;
            puff.spawn(level, Mth.lerp(k, from.x, to.x), Mth.lerp(k, from.y, to.y), Mth.lerp(k, from.z, to.z));
        }
    }

    private static final double[] BIRTH = new double[2], LOOK = new double[4];

    /**
     * Клубы объёма живут не дольше этого, с: долгий след несёт лента ({@code FarFlightView}), а клуб на минуты держал бы
     * место в группе шлейфов ({@code FxBudget.TRAIL}) — у залпов позже не рождались бы свои. Не закон дыма, а бюджет мест.
     */
    static final double PUFF_SECONDS = 30;

    /**
     * Клуб объёма следа, который снаряд со скоростью speed (м/с) кладёт на высоте y, — по закону следа ({@link TrailModel},
     * тот же, что у ленты): живёт, пока виден след (не дольше {@link #PUFF_SECONDS}), от ширины следа у сопла до его
     * наибольшей видимой ширины за это время (плотная часть клуба — около 0,7 его квадрата), цвета густого дыма,
     * всплывает вместе со следом; null — след не виден.
     */
    @Nullable
    private static Fx.Spec puff(Trail t, double speed, double y) {
        Air air = ClientAir.now();
        double u = air.speed(y), inv = TrailModel.inverseSpeed(speed, u);
        TrailModel.birth(t, inv, y, u, air, BIRTH);
        double life = Math.min(BIRTH[0], PUFF_SECONDS), ice = BIRTH[1];
        if (life <= 0) return null;
        TrailModel.look(t, 0.5, inv, ice, u, y, air, LOOK);
        double w0 = LOOK[0], tone = LOOK[3], w1 = w0;
        for (int k = 1; k <= 8; k++) {
            TrailModel.look(t, life * k / 8, inv, ice, u, y, air, LOOK);
            w1 = Math.max(w1, LOOK[0]);
        }
        int grey = (int) Math.round(255 * FlameModel.srgb(tone)), c = grey << 16 | grey << 8 | grey;
        double brunt = air.brunt();
        return Fx.smoke().size((float) (w0 / 1.4), (float) (w1 / 1.4)).life((int) Math.ceil(life * 20)).color(c, c).alpha(VOLUME_ALPHA)
                .drag(0.9f).fadeIn(2).fadeFrom(0.5f).spin(0.01f).thermal(ticks -> TrailModel.rise(t, ticks / 20, inv, brunt));
    }

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
