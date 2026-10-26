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
 * Its own game flight model: turning no faster than spec and with inertia, exchanging altitude for speed with returning to
 *  cruising, flying without a motor with gravity, resistance and wind.
 */
class FlightModelTest {
    private static final List<WeaponSpec.Airframe> POWERED = List.of(WeaponSpec.DRONE.airframe(), WeaponSpec.MISSILE.airframe(),
            WeaponSpec.LOITER.airframe(), WeaponSpec.BUNKER.airframe());

    /** Turn to 180°: angular velocity is not higher than spec, it increases and decreases no faster than its acceleration. */
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
                    assertTrue(Math.abs(r) <= a.turnRate() + 1e-4, air.entity() + ": reversal " + r + " °/tick");
                    assertTrue(Math.abs(r - rate) <= a.turnAccel() + 1e-4, "inertia: " + (r - rate));
                    rate = r;
                    yaw = f.yaw();
                }
                assertEquals(180, Math.abs(f.yaw()), 1, "entrusted");
            }
        }
    }

    /** The roll increases with the turning speed and does not exceed 70°. */
    @Test
    void bankFollowsTurnRate() {
        FlightController f = new FlightController(0, 0);
        assertEquals(0, f.bankAngle(2.1), 1e-6);
        for (int t = 0; t < 20; t++) f.steerYaw(90, 0.15, 3, 0.3);
        float bank = f.bankAngle(2.1);
        assertTrue(Math.abs(bank) > 40 && Math.abs(bank) <= 70, "roll " + bank);
    }

    /** During the climb, the speed drops, at the peak it increases (not higher than the maximum), then it returns to cruising speed. */
    @Test
    void altitudeTradesForSpeedAndReturnsToCruise() {
        for (WeaponSpec.Airframe air : POWERED) {
            double cruise = air.cruiseSpeed(), v = cruise, climb = Math.sin(Math.toRadians(air.handling().climbAngle()));
            for (int t = 0; t < 400; t++) v = air.airspeed(v, cruise, climb);
            assertTrue(v < cruise * 0.995, air.entity() + ": while dialing loses speed: " + v);
            for (int t = 0; t < 600; t++) v = air.airspeed(v, cruise, 0);
            assertEquals(cruise, v, 0.01, "returned to cruising");
            for (int t = 0; t < 40; t++) v = air.airspeed(v, cruise, -0.7);
            if (air.diveSpeed() > cruise) assertTrue(v > cruise, "in decline it accelerates: " + v);
            else assertEquals(cruise, v, 1e-9, "the limit is the march itself");
            assertTrue(v <= air.diveSpeed() + 1e-9, "no faster than the limit");
            for (int t = 0; t < 600; t++) v = air.airspeed(v, cruise, 0);
            assertEquals(cruise, v, 0.01, "after descent - cruising again");
        }
        // shahed in a set under 15° - noticeably slower: game exchange
        WeaponSpec.Airframe drone = WeaponSpec.DRONE.airframe();
        double v = drone.cruiseSpeed();
        for (int t = 0; t < 400; t++) v = drone.airspeed(v, drone.cruiseSpeed(), Math.sin(Math.toRadians(15)));
        assertEquals(0.85, v / drone.cruiseSpeed(), 0.03);
    }

    /** Piquet accelerates faster than one engine: the weight along the path helps. */
    @Test
    void diveGainsFasterThanEngineAlone() {
        WeaponSpec.Airframe drone = WeaponSpec.DRONE.airframe();
        double level = drone.cruiseSpeed(), dive = drone.cruiseSpeed();
        for (int t = 0; t < 10; t++) {
            level = drone.airspeed(level, drone.diveSpeed(), 0);
            dive = drone.airspeed(dive, drone.diveSpeed(), -Math.sin(Math.toRadians(60)));
        }
        assertTrue(dive > level + 0.2, "dive " + dive + " against " + level);
    }

    /** Without resistance and wind, the steps coincide with the parabola: the integrator is correct. */
    @Test
    void noDragIsTheParabola() {
        Vec3 from = new Vec3(0, 80, 0), v0 = new Vec3(2, 3, 1);
        Ballistics.Path p = Ballistics.fly(from, v0, Ballistics.Drag.of(0), Ballistics.Wind.CALM, -1000, 300);
        for (int k = 0; k <= 250; k += 25) {
            Vec3 exact = from.add(v0.scale(k)).add(0, -Ballistics.GRAVITY * k * k / 2, 0);
            assertEquals(0, p.at(k).distanceTo(exact), 1e-6, "tick " + k);
        }
    }

    /** Calculating the fire with a known wind brings “Grad” to the point; with resistance the descent is steeper than the ascent. */
    @Test
    void gradFireSolutionLandsOnTarget() {
        Ballistics.Drag drag = WeaponSpec.ROCKET.airframe().drag(0);
        Vec3 from = new Vec3(0, 70, 0);
        // and the target is higher than the launcher: on 250 blocks, 180 is higher - the projectile passes it while still on the rise
        for (Vec3 to : List.of(new Vec3(0, 64, 150), new Vec3(400, 90, 400), new Vec3(-1500, 64, 900), new Vec3(3000, 64, -200),
                new Vec3(250, 250, 0), new Vec3(100, 230, 30))) {
            Ballistics.Wind wind = Ballistics.Wind.uniform(0.3, -0.2);
            Vec3 v0 = Ballistics.aim(from, to, Ballistics.elevationFor(from, to, 50, 50), drag, wind);
            Ballistics.Path p = Ballistics.fly(from, v0, drag, wind, to.y - 10, 4000);
            double k = p.arrival(from, to);
            assertEquals(0, p.at(k).distanceTo(to), 0.5, "got into " + to);
            // the descent angle at the ground is steeper than the launch rail exit angle
            double down = p.landing(to.y);
            if (down == k && to.y <= from.y) {
                Vec3 v = p.at(k).subtract(p.at(k - 1));
                float el = Ballistics.elevationFor(from, to, 50, 50);
                assertTrue(Math.toDegrees(Math.atan2(-v.y, v.horizontalDistance())) > el, "the descent is steeper than the ascent: " + to);
            }
        }
    }

    /** Resistance: the projectile must be launched faster than in a parabola - it would not have reached further with its weight alone. */
    @Test
    void dragNeedsFasterLaunch() {
        Vec3 from = Vec3.ZERO, to = new Vec3(0, 0, 2000);
        Ballistics.Drag drag = WeaponSpec.ROCKET.airframe().drag(0);
        double withDrag = Ballistics.aim(from, to, 50, drag, Ballistics.Wind.CALM).length();
        double vacuum = Ballistics.aim(from, to, 50, Ballistics.Drag.of(0), Ballistics.Wind.CALM).length();
        assertEquals(Math.sqrt(Ballistics.GRAVITY * 2000 / Math.sin(Math.toRadians(100))), vacuum, 0.05);
        assertTrue(withDrag > vacuum * 1.05, withDrag + " against " + vacuum);
    }

    /**
     * The wind above is stronger than at the ground, and only the wind at the ground knows the timing of the fire: the volley blows downwind. The spread of the launch rail exit from the
     *  spec gives on 600 blocks the standard deviation of about 1 % of the range.
     */
    @Test
    void gradDriftsDownwindAndScatters() {
        Ballistics.Drag drag = WeaponSpec.ROCKET.airframe().drag(0);
        Vec3 from = new Vec3(0, 64, 0), to = new Vec3(0, 64, 600);
        // the wind blows along x: near the ground 0,25 block/tick, on 200 blocks - 0,5
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
        assertTrue(land.x > 1 && land.x < 20, "wind drift: " + land.x);

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
        assertTrue(sigmaX > 2 && sigmaX < 10, "in the direction σ " + sigmaX);
        assertTrue(sigmaZ > 2 && sigmaZ < 12, "by range σ " + sigmaZ);
        // spec rating - from above, along with wind drift
        assertTrue(b.sigma(600) >= Math.max(sigmaX, sigmaZ) + land.x / 2, "estimate σ " + b.sigma(600));
    }

    /**
     * The bomb launches rail exit with a speed of B-2 and slows down towards the ground: the resistance is greater than gravity, the speed goes to the limit
     * √(g/drag) from above; a slow bomb accelerates towards it from below. It cannot be faster than the release speed.
     */
    @Test
    void bombFallsToTerminalSpeed() {
        WeaponSpec.Airframe bomb = WeaponSpec.BUNKER.payload(), bomber = WeaponSpec.BUNKER.airframe();
        double terminal = Math.sqrt(Ballistics.GRAVITY / bomb.ballistic().drag());
        assertEquals(bomber.cruiseSpeed(), bomb.cruiseSpeed(), 1e-9, "releases at B-2 speed");
        assertTrue(bomb.cruiseSpeed() > terminal, "B-2 faster than the falling limit");
        assertEquals(bomb.cruiseSpeed(), bomb.diveSpeed(), 1e-9);
        for (double start : new double[]{bomb.cruiseSpeed(), 2}) {
            // vertically down near the ground (density is the same as at sea level): the place is standing, only the speed is considered
            FlightController f = new FlightController(0, 89);
            Vec3 pos = new Vec3(0, 64, 0), aim = new Vec3(0, -1000, 0);
            double v = start, prev = v;
            for (int t = 0; t < 3000; t++) {
                v = BombDrop.steer(f, pos, v, aim);
                // nose at 89°, not 90: the limit along the path is just below √(g/drag)
                assertTrue(Math.abs(v - terminal) <= Math.abs(prev - terminal) + 1e-4, "to the limit: " + prev + " → " + v);
                assertTrue(v <= bomb.diveSpeed() + 1e-9, "no faster than reset: " + v);
                prev = v;
            }
            assertEquals(terminal, prev, terminal * 0.05, "near the ground - at the limit (with " + start + ")");
        }
    }

    /** The wind blows the bomb slowly: heavy and fast - a fraction of a block in tens of ticks. */
    @Test
    void windBarelyMovesTheBomb() {
        Vec3 drift = Vec3.ZERO, wind = new Vec3(0.5, 0, 0);
        double path = 0;
        for (int t = 0; t < 30; t++) {
            drift = BombDrop.drift(drift, wind, 6.5);
            path += drift.x;
        }
        assertTrue(path > 0.05 && path < 2, "demolition " + path);
    }
}
