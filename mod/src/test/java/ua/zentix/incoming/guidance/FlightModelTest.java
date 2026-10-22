package ua.zentix.incoming.guidance;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Своя игровая модель полёта: разворот не быстрее паспорта и с инерцией, обмен высоты на скорость с возвратом к
 * крейсерской, полёт без мотора с тяжестью, сопротивлением и ветром.
 */
class FlightModelTest {
    private static final List<WeaponSpec.Airframe> POWERED = List.of(WeaponSpec.DRONE.airframe(), WeaponSpec.MISSILE.airframe(),
            WeaponSpec.LOITER.airframe(), WeaponSpec.BUNKER.airframe());

    /** Разворот на 180°: угловая скорость не выше паспорта, набирается и гасится не быстрее его ускорения. */
    @Test
    void turnNeverFasterThanPassport() {
        for (WeaponSpec.Airframe air : POWERED) {
            for (WeaponSpec.Agility a : List.of(air.handling().climb(), air.handling().cruise(), air.handling().dive())) {
                FlightController f = new FlightController(0, 0);
                float yaw = f.yaw();
                double rate = 0;
                for (int t = 0; t < 400; t++) {
                    f.steerYaw(180, 0.15, a.turnRate(), a.turnAccel());
                    double r = Mth.wrapDegrees(f.yaw() - yaw);
                    assertTrue(Math.abs(r) <= a.turnRate() + 1e-4, air.entity() + ": разворот " + r + " °/тик");
                    assertTrue(Math.abs(r - rate) <= a.turnAccel() + 1e-4, "инерция: " + (r - rate));
                    rate = r;
                    yaw = f.yaw();
                }
                assertEquals(180, Math.abs(f.yaw()), 1, "довернул");
            }
        }
    }

    /** Крен растёт со скоростью разворота и не больше 70°. */
    @Test
    void bankFollowsTurnRate() {
        FlightController f = new FlightController(0, 0);
        assertEquals(0, f.bankAngle(2.1), 1e-6);
        for (int t = 0; t < 20; t++) f.steerYaw(90, 0.15, 3, 0.3);
        float bank = f.bankAngle(2.1);
        assertTrue(Math.abs(bank) > 40 && Math.abs(bank) <= 70, "крен " + bank);
    }

    /** В наборе скорость падает, в пике растёт (не выше предельной), потом сама возвращается к крейсерской. */
    @Test
    void altitudeTradesForSpeedAndReturnsToCruise() {
        for (WeaponSpec.Airframe air : POWERED) {
            double cruise = air.cruiseSpeed(), v = cruise, climb = Math.sin(Math.toRadians(air.handling().climbAngle()));
            for (int t = 0; t < 400; t++) v = air.airspeed(v, cruise, climb);
            assertTrue(v < cruise * 0.995, air.entity() + ": в наборе теряет скорость: " + v);
            for (int t = 0; t < 600; t++) v = air.airspeed(v, cruise, 0);
            assertEquals(cruise, v, 0.01, "вернулась к крейсерской");
            for (int t = 0; t < 40; t++) v = air.airspeed(v, cruise, -0.7);
            if (air.diveSpeed() > cruise) assertTrue(v > cruise, "в снижении разгоняется: " + v);
            else assertEquals(cruise, v, 1e-9, "предел — сама маршевая");
            assertTrue(v <= air.diveSpeed() + 1e-9, "не быстрее предельной");
            for (int t = 0; t < 600; t++) v = air.airspeed(v, cruise, 0);
            assertEquals(cruise, v, 0.01, "после снижения — снова крейсерская");
        }
        // шахед в наборе под 15° — заметно медленнее: игровой обмен
        WeaponSpec.Airframe drone = WeaponSpec.DRONE.airframe();
        double v = drone.cruiseSpeed();
        for (int t = 0; t < 400; t++) v = drone.airspeed(v, drone.cruiseSpeed(), Math.sin(Math.toRadians(15)));
        assertEquals(0.85, v / drone.cruiseSpeed(), 0.03);
    }

    /** Пике разгоняет быстрее одного мотора: тяжесть вдоль пути помогает. */
    @Test
    void diveGainsFasterThanEngineAlone() {
        WeaponSpec.Airframe drone = WeaponSpec.DRONE.airframe();
        double level = drone.cruiseSpeed(), dive = drone.cruiseSpeed();
        for (int t = 0; t < 10; t++) {
            level = drone.airspeed(level, drone.diveSpeed(), 0);
            dive = drone.airspeed(dive, drone.diveSpeed(), -Math.sin(Math.toRadians(60)));
        }
        assertTrue(dive > level + 0.2, "пике " + dive + " против " + level);
    }

    /** Без сопротивления и ветра шаги совпадают с параболой: интегратор верен. */
    @Test
    void noDragIsTheParabola() {
        Vec3 from = new Vec3(0, 80, 0), v0 = new Vec3(2, 3, 1);
        Ballistics.Path p = Ballistics.fly(from, v0, 0, Ballistics.Wind.CALM, -1000, 300);
        for (int k = 0; k <= 250; k += 25) {
            Vec3 exact = from.add(v0.scale(k)).add(0, -Ballistics.GRAVITY * k * k / 2, 0);
            assertEquals(0, p.at(k).distanceTo(exact), 1e-6, "тик " + k);
        }
    }

    /** Расчёт огня при известном ветре приводит «Град» в точку; с сопротивлением спуск круче подъёма. */
    @Test
    void gradFireSolutionLandsOnTarget() {
        double drag = WeaponSpec.ROCKET.airframe().ballistic().drag();
        Vec3 from = new Vec3(0, 70, 0);
        // и цель выше пусковой: на 250 блоках на 180 выше — снаряд проходит её ещё на подъёме
        for (Vec3 to : List.of(new Vec3(0, 64, 150), new Vec3(400, 90, 400), new Vec3(-1500, 64, 900), new Vec3(3000, 64, -200),
                new Vec3(250, 250, 0), new Vec3(100, 230, 30))) {
            Ballistics.Wind wind = Ballistics.Wind.uniform(0.3, -0.2);
            Vec3 v0 = Ballistics.aim(from, to, Ballistics.elevationFor(from, to, 50, 50), drag, wind);
            Ballistics.Path p = Ballistics.fly(from, v0, drag, wind, to.y - 10, 4000);
            double k = p.arrival(from, to);
            assertEquals(0, p.at(k).distanceTo(to), 0.5, "попал в " + to);
            // угол спуска у земли круче угла схода
            double down = p.landing(to.y);
            if (down == k && to.y <= from.y) {
                Vec3 v = p.at(k).subtract(p.at(k - 1));
                float el = Ballistics.elevationFor(from, to, 50, 50);
                assertTrue(Math.toDegrees(Math.atan2(-v.y, v.horizontalDistance())) > el, "спуск круче подъёма: " + to);
            }
        }
    }

    /** Сопротивление: снаряд нужно пустить быстрее, чем по параболе, — дальше одной тяжестью он бы не долетел. */
    @Test
    void dragNeedsFasterLaunch() {
        Vec3 from = Vec3.ZERO, to = new Vec3(0, 0, 2000);
        double drag = WeaponSpec.ROCKET.airframe().ballistic().drag();
        double withDrag = Ballistics.aim(from, to, 50, drag, Ballistics.Wind.CALM).length();
        double vacuum = Ballistics.aim(from, to, 50, 0, Ballistics.Wind.CALM).length();
        assertEquals(Math.sqrt(Ballistics.GRAVITY * 2000 / Math.sin(Math.toRadians(100))), vacuum, 0.05);
        assertTrue(withDrag > vacuum * 1.05, withDrag + " против " + vacuum);
    }

    /**
     * Ветер наверху сильнее, чем у земли, а расчёт огня знает только ветер у земли: залп сносит по ветру. Разброс схода из
     * паспорта даёт на 600 блоках СКО около 1 % дальности.
     */
    @Test
    void gradDriftsDownwindAndScatters() {
        double drag = WeaponSpec.ROCKET.airframe().ballistic().drag();
        Vec3 from = new Vec3(0, 64, 0), to = new Vec3(0, 64, 600);
        // по x дует ветер: у земли 0,25 блока/тик, на 200 блоках — 0,5
        Ballistics.Wind real = new Ballistics.Wind() {
            @Override
            public double x(double y) {
                return 0.25 + 0.25 * Math.min(1, Math.max(0, (y - 64) / 200));
            }

            @Override
            public double z(double y) {
                return 0;
            }
        };
        Vec3 v0 = Ballistics.aim(from, to, 50, drag, Ballistics.Wind.uniform(0.25, 0));
        Ballistics.Path p = Ballistics.fly(from, v0, drag, real, 0, 4000);
        Vec3 land = p.at(p.landing(to.y));
        assertTrue(land.x > 1 && land.x < 20, "снос по ветру: " + land.x);

        WeaponSpec.Ballistic b = WeaponSpec.ROCKET.airframe().ballistic();
        Vec3 aim = Ballistics.aim(from, to, 50, drag, Ballistics.Wind.CALM);
        RandomSource r = RandomSource.create(7);
        double sx = 0, sz = 0;
        int n = 400;
        for (int i = 0; i < n; i++) {
            double sa = Math.toRadians(b.spreadAngle());
            Vec3 dir = aim.normalize(), side = new Vec3(-dir.z, 0, dir.x).normalize(), up = side.cross(dir);
            Vec3 v = dir.add(side.scale(r.nextGaussian() * sa)).add(up.scale(r.nextGaussian() * sa)).normalize()
                    .scale(aim.length() * (1 + r.nextGaussian() * b.spreadSpeed()));
            Ballistics.Path q = Ballistics.fly(from, v, drag, Ballistics.Wind.CALM, 0, 4000);
            Vec3 l = q.at(q.landing(to.y));
            sx += l.x * l.x;
            sz += (l.z - to.z) * (l.z - to.z);
        }
        double sigmaX = Math.sqrt(sx / n), sigmaZ = Math.sqrt(sz / n);
        assertTrue(sigmaX > 2 && sigmaX < 10, "по направлению σ " + sigmaX);
        assertTrue(sigmaZ > 2 && sigmaZ < 12, "по дальности σ " + sigmaZ);
        // оценка паспорта — сверху, вместе со сносом ветром
        assertTrue(b.sigma(600) >= Math.max(sigmaX, sigmaZ) + land.x / 2, "оценка σ " + b.sigma(600));
    }

    /**
     * Бомба сходит со скоростью B-2 и к земле тормозит: сопротивление больше тяжести, скорость идёт к пределу
     * √(g/drag) сверху; медленная бомба разгоняется к нему снизу. Быстрее скорости сброса она не бывает.
     */
    @Test
    void bombFallsToTerminalSpeed() {
        WeaponSpec.Airframe bomb = WeaponSpec.BUNKER.payload(), bomber = WeaponSpec.BUNKER.airframe();
        double terminal = Math.sqrt(Ballistics.GRAVITY / bomb.ballistic().drag());
        assertEquals(bomber.cruiseSpeed(), bomb.cruiseSpeed(), 1e-9, "сходит со скоростью B-2");
        assertTrue(bomb.cruiseSpeed() > terminal, "B-2 быстрее предела падения");
        assertEquals(bomb.cruiseSpeed(), bomb.diveSpeed(), 1e-9);
        for (double start : new double[]{bomb.cruiseSpeed(), 2}) {
            // отвесно вниз у самой земли (плотность — как на уровне моря): место стоит, считается только скорость
            FlightController f = new FlightController(0, 89);
            Vec3 pos = new Vec3(0, 64, 0), aim = new Vec3(0, -1000, 0);
            double v = start, prev = v;
            for (int t = 0; t < 3000; t++) {
                v = BombDrop.steer(f, pos, v, aim);
                // нос на 89°, не 90: предел вдоль пути чуть ниже √(g/drag)
                assertTrue(Math.abs(v - terminal) <= Math.abs(prev - terminal) + 1e-4, "к пределу: " + prev + " → " + v);
                assertTrue(v <= bomb.diveSpeed() + 1e-9, "не быстрее сброса: " + v);
                prev = v;
            }
            assertEquals(terminal, prev, terminal * 0.05, "у земли — у предела (с " + start + ")");
        }
    }

    /** Ветер сносит бомбу медленно: тяжёлую и быструю — на доли блока за десятки тиков. */
    @Test
    void windBarelyMovesTheBomb() {
        Vec3 drift = Vec3.ZERO, wind = new Vec3(0.5, 0, 0);
        double path = 0;
        for (int t = 0; t < 30; t++) {
            drift = BombDrop.drift(drift, wind, 6.5);
            path += drift.x;
        }
        assertTrue(path > 0.05 && path < 2, "снос " + path);
    }
}
