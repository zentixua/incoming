package ua.zentix.incoming.strike;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.guidance.BombDrop;
import ua.zentix.incoming.guidance.FlightController;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World flight scale ({@link FlightScale}): game numbers at 0, real numbers at 1, between - by logarithm. */
class FlightScaleTest {
    private static final double[] XS = {0, 0.5, 1};

    /** All craft spec by name: flight entities and bomb B-2. */
    private static Map<String, WeaponSpec.Airframe> airframes() {
        Map<String, WeaponSpec.Airframe> all = new LinkedHashMap<>();
        for (WeaponType w : WeaponType.values()) {
            all.put(w.name(), w.spec().airframe());
            if (w.spec().payload() != null) all.put(w.name() + " payload", w.spec().payload());
        }
        return all;
    }

    @Test
    void blendIsGameAtZeroRealAtOneGeometricBetween() {
        assertEquals(2, FlightScale.blend(2, 8, 0));
        assertEquals(8, FlightScale.blend(2, 8, 1), 1e-12);
        assertEquals(4, FlightScale.blend(2, 8, 0.5), 1e-12);
        assertEquals(170, FlightScale.blend(170, 0, 1), "no real number - game number");
        assertEquals(0, FlightScale.blend(0, 7200, 1), "no gaming - gaming");
    }

    /** Cruise, peak and height of each product: with 0 - exactly game, with 1 - real, with 0,5 - geometric average. */
    @Test
    void everyArticleAtZeroHalfOne() {
        airframes().forEach((name, a) -> {
            Article.Envelope real = a.article().real();
            check(a.gameCruise(), real.cruise() / 20, a::cruiseSpeed, name + " cruise");
            check(a.gameDive(), real.dive() / 20, a::diveSpeed, name + " dive");
            if (real.altitude() > 0 && a.gameHeight() > 0) check(a.gameHeight(), real.altitude(), a::cruiseHeight, name + " height");
            else for (double x : XS) assertEquals(a.gameHeight(), a.cruiseHeight(x), name + ": without real height - game");
        });
    }

    private static void check(double game, double real, java.util.function.DoubleUnaryOperator at, String what) {
        assertEquals(game, at.applyAsDouble(0), 0, what + " at 0");
        assertEquals(Math.sqrt(game * real), at.applyAsDouble(0.5), 1e-9 * real, what + " at 0,5");
        assertEquals(real, at.applyAsDouble(1), 1e-9 * real, what + " at 1");
    }

    /**
     * The order of speeds of products: where the game and life agree (one is not slower than the other in both cases), it is the same for any
     *  scale. Shahed, "Lancet", rocket and B-2 agree: "Lancet" is slower than Shahed, Shahed is a rocket, the rocket is not faster than B-2.
     *  "Grad" in the game is slower than B-2, but in life launch rail exit from the package three times faster than him - on the scale he overtakes him, as in life.
     */
    @Test
    void orderOfSpeedsKept() {
        Map<String, WeaponSpec.Airframe> all = airframes();
        for (double x = 0; x <= 1.0001; x += 0.125) {
            for (var a : all.entrySet()) {
                for (var b : all.entrySet()) {
                    WeaponSpec.Airframe p = a.getValue(), q = b.getValue();
                    if (p.gameCruise() <= q.gameCruise() && p.cruiseSpeed(1) <= q.cruiseSpeed(1)) {
                        assertTrue(p.cruiseSpeed(x) <= q.cruiseSpeed(x) * (1 + 1e-12), a.getKey() + " faster " + b.getKey() + " at " + x);
                    }
                }
            }
            double lancet = WeaponSpec.LOITER.airframe().cruiseSpeed(x), shahed = WeaponSpec.DRONE.airframe().cruiseSpeed(x),
                    missile = WeaponSpec.MISSILE.airframe().cruiseSpeed(x), bomber = WeaponSpec.BUNKER.airframe().cruiseSpeed(x);
            assertTrue(lancet < shahed && shahed < missile && missile <= bomber * (1 + 1e-12), "at " + x);
        }
    }

    /** Interceptor missile: top speed 14 → 50 blocks/tick (1000 m/s for 9M96), acceleration over the same ticks. */
    @Test
    void interceptorSpeed() {
        InterceptorSpec s = InterceptorSpec.SAM;
        check(s.gameMaxSpeed(), s.passport().maxSpeed() / 20, s::maxSpeed, "SAM");
        assertEquals(s.gameBoostAccel(), s.boostAccel(0), 0);
        double reach = (s.gameMaxSpeed() - s.launchSpeed()) / s.gameBoostAccel();
        for (double x : XS) assertEquals(s.maxSpeed(x), s.launchSpeed() + s.boostAccel(x) * reach, 1e-9, "acceleration in the same tick at " + x);
    }

    /** Resistance without a motor: game without the Mach curve at 0, real ρ₀·CdA/2m with it at 1. */
    @Test
    void dragFromGameToArticle() {
        for (WeaponSpec.Airframe a : List.of(WeaponSpec.ROCKET.airframe(), WeaponSpec.BUNKER.payload())) {
            Ballistics.Drag zero = a.drag(0), one = a.drag(1);
            assertEquals(a.ballistic().drag(), zero.coefficient(), 0);
            assertEquals(0, zero.mach());
            assertEquals(StandardAtmosphere.density(0) * a.article().cda() / (2 * a.article().dryMass()), one.coefficient(), 1e-15);
            assertEquals(1, one.mach());
        }
        assertEquals(null, WeaponSpec.DRONE.airframe().drag(1), "on a motor - without flight resistance without a motor");
    }

    /**
     * The lengths derived from the speed increase with the cruising speed: spec connections ({@code WeaponSpecTest}) are maintained throughout the entire scale -
     * , the last section contains two turning radii, the route point is taken near the radius, the attack is not canceled closer to the
     *  circle, the hill is outside the circle at the peak speed and no further than the section and approach strip, the alarm is before the section.
     */
    @Test
    void lengthsFollowSpeed() {
        for (double x : XS) {
            for (WeaponType t : List.of(WeaponType.DRONE, WeaponType.MISSILE)) {
                WeaponSpec w = t.spec();
                WeaponSpec.Airframe a = w.airframe();
                double r = a.turnRadius(a.cruiseSpeed(x));
                assertTrue(w.finalLeg(x) >= 2 * r, t + " plot at " + x);
                assertTrue(w.capture(x) >= 0.95 * r && w.capture(x) <= 2 * r, t + " capture at " + x);
                assertTrue(a.attack(x).reattackMin() < r * WeaponSpec.TURN_MARGIN, t + " re-entry at " + x);
                assertTrue(w.sirenLead() * a.cruiseSpeed(x) >= w.finalLeg(x), t + " anxiety when " + x);
            }
            WeaponSpec.Airframe m = WeaponSpec.MISSILE.airframe();
            WeaponSpec.Attack at = m.attack(x);
            assertTrue(at.terminalRange() > m.turnRadius(m.diveSpeed(x)) * WeaponSpec.TURN_MARGIN, "slide outside the circle " + x);
            assertTrue(at.terminalRange() <= WeaponSpec.MISSILE.finalLeg(x) && at.terminalRange() <= m.visibleLeg(x), "slide at " + x);
            assertTrue(at.popUpMinRange() > at.terminalRange());
            double lancet = WeaponSpec.LOITER.airframe().turnRadius(WeaponSpec.LOITER.airframe().cruiseSpeed(x));
            assertTrue(WeaponSpec.LOITER.capture(x) >= 0.95 * lancet, "\"Lancet\": capture at " + x);
        }
    }

    /**
     * Grad at a distance: the rack is set to the trajectory range for its angle at the scale's launch rail exit speed.
     * At 0, exactly 600 blocks as before; at 1, the firing solution for this range gives launch rail exit at the real
     * 683 m/s (±2%), range ~20 km, as for 9M22U at maximum range (20.4 km at 53°; here without the powered-flight segment,
     * approximately 15–25 km).
     */
    @Test
    void gradFromAfarFiresAtTheBlendedSpeed() {
        WeaponSpec g = WeaponSpec.ROCKET;
        assertEquals(600, g.standoff(0), 0);
        for (double x : new double[]{0.5, 1}) {
            double d = g.standoff(x);
            Vec3 from = new Vec3(0, Air.SEA, 0), to = new Vec3(d, Air.SEA, 0);
            double v = Ballistics.aim(from, to, g.rack().elevation(), g.airframe().drag(x), Ballistics.Wind.CALM).length();
            assertEquals(g.airframe().cruiseSpeed(x), v, 0.02 * g.airframe().cruiseSpeed(x), "launch rail exit at " + x + " s " + d + " blocks");
        }
        assertTrue(g.standoff(1) > 15_000 && g.standoff(1) < 25_000, "offset at 1: " + g.standoff(1));
    }

    /**
     * Bomb at 1: B-2 at the real echelon 12 km and 250 m/s drops it at the drop line (the same angle to the point as on
     *  in-game); with real resistance and the Mach curve, it falls ~50 s and hits with the impact speed spec GBU-57
     *  (511 m/s; with us it goes with the rudders directly to the point, and not according to ballistics - ±10%). Penetration at the target - at this speed.
     */
    @Test
    void bombAtRealScaleHitsAtPassportSpeed() {
        WeaponSpec.Airframe b2 = WeaponSpec.BUNKER.airframe(), bomb = WeaponSpec.BUNKER.payload();
        double h = b2.cruiseHeight(1), line = h * WeaponSpec.BUNKER.route().gameFinalLeg() / b2.gameHeight();
        Ballistics.Drag drag = bomb.drag(1);
        Vec3 aim = new Vec3(0, Air.SEA, 0), velocity = new Vec3(0, 0, b2.cruiseSpeed(1));
        Vec3 release = aim.add(0, h, -line);
        assertEquals(0, BombDrop.miss(release, velocity, aim, drag), "hits the reset line");
        FlightController f = new FlightController(0, 0);
        Vec3 pos = release.add(0, -BombDrop.DROP_BELOW, 0);
        double v = velocity.length();
        int t = 0;
        while (pos.distanceTo(aim) > v + BombDrop.REACH_PAD && t < 5000) {
            v = BombDrop.steer(f, pos, v, aim, drag);
            pos = pos.add(f.forward().scale(v));
            t++;
        }
        double impact = bomb.article().impact().speed();
        assertEquals(impact, v * 20, 0.1 * impact, "impact speed, m/s");
        assertTrue(t / 20.0 > 35 && t / 20.0 < 65, "fall " + t / 20.0 + " s");
    }
}
