package ua.zentix.incoming.strike;

import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.LauncherRack;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.util.StandardAtmosphere;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spec numbers are consistent with each other: the output (turning radius, siren lead) - as in the weapon description,
 *  and the selected ones (attack geometry, last section, launcher package) do not contradict the output. By changing the speed or
 * turn in spec, this test will tell you which selected number to revise.
 */
class WeaponSpecTest {
    private static final List<WeaponSpec> GUIDED = List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE);

    @Test
    void turnRadiusIsSpeedOverTurnRate() {
        // shahed ~40 blocks, rocket ~76, B-2 ~690 (description BomberEntity)
        assertEquals(40.1, WeaponSpec.DRONE.airframe().turnRadius(WeaponSpec.DRONE.airframe().cruiseSpeed()), 0.1);
        assertEquals(76.4, WeaponSpec.MISSILE.airframe().turnRadius(WeaponSpec.MISSILE.airframe().cruiseSpeed()), 0.1);
        assertEquals(687.5, WeaponSpec.BUNKER.airframe().turnRadius(WeaponSpec.BUNKER.airframe().cruiseSpeed()), 0.1);
    }

    /**
     * How the projectile sees and hits the SAM: B-2 and “Lancet” are barely noticeable, the bomb under B-2 is almost invisible; ICBM and MLRS SAM sees
     *  but does not intercept (ICBM is not its target, “Grad” is cheaper than a missile); Shahed, rocket, Lancet and B-2 - yes.
     */
    @Test
    void radarSignature() {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec.Signature r = w.spec().airframe().radar();
            assertTrue(r.visibility() > 0 && r.visibility() <= 1, w + ": visibility " + r.visibility());
            assertEquals(w == WeaponType.DRONE || w == WeaponType.MISSILE || w == WeaponType.LOITER || w == WeaponType.BUNKER, r.intercept(),
                    w + ": interception");
        }
        assertTrue(WeaponSpec.BUNKER.airframe().radar().visibility() <= 0.2, "B-2 visible from afar");
        assertTrue(WeaponSpec.LOITER.airframe().radar().visibility() < WeaponSpec.DRONE.airframe().radar().visibility());
        assertTrue(WeaponSpec.MISSILE.airframe().radar().visibility() < WeaponSpec.DRONE.airframe().radar().visibility());
        WeaponSpec.Airframe bomb = WeaponSpec.BUNKER.payload();
        assertTrue(bomb != null && !bomb.radar().intercept() && bomb.radar().visibility() <= WeaponSpec.BUNKER.airframe().radar().visibility());
    }

    @Test
    void sirenLeadIsSecondsInTicks() {
        assertEquals(500, WeaponSpec.DRONE.sirenLead());
        assertEquals(300, WeaponSpec.MISSILE.sirenLead());
        assertEquals(160, WeaponSpec.ROCKET.sirenLead());
        assertEquals(400, WeaponSpec.LOITER.sirenLead());
        assertEquals(400, WeaponSpec.BUNKER.sirenLead());
    }

    /**
     * The warhead charge is the one who has a ground explosion, and it knocks out the glass (the fragile threshold) to the range of
     *  from the weapon description.
     */
    @Test
    void chargeBreaksGlassAsDescribed() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.ROCKET, WeaponSpec.LOITER, WeaponSpec.BUNKER, WeaponSpec.NUKE)) {
            assertEquals(w.blast() == WeaponSpec.Blast.NONE, w.charge() == 0, () -> w.blast() + ": charge " + w.charge());
        }
        assertEquals(70, glassRange(WeaponSpec.DRONE), 7);
        assertEquals(105, glassRange(WeaponSpec.MISSILE), 10);
        assertEquals(37, glassRange(WeaponSpec.ROCKET), 4);
        assertEquals(29, glassRange(WeaponSpec.LOITER), 3);
    }

    private static double glassRange(WeaponSpec w) {
        return BlastModel.rangeForSurfaceOverpressure(BlastModel.kpa(BlockResponse.FRAGILE_PSI), w.charge());
    }

    /**
     * Miss: Missile and bomb have less than Shahed; “Lancet” (camera), ICBM and MLRS (it has scattering) - without a miss.
     *  Shahed miss sample - with its standard deviation along each axis, horizontally.
     */
    @Test
    void missIsPerWeapon() {
        assertTrue(WeaponSpec.MISSILE.route().error() < WeaponSpec.DRONE.route().error());
        assertTrue(WeaponSpec.BUNKER.route().error() < WeaponSpec.DRONE.route().error());
        RandomSource random = RandomSource.create(1);
        for (WeaponSpec w : List.of(WeaponSpec.LOITER, WeaponSpec.NUKE, WeaponSpec.ROCKET)) assertEquals(Vec3.ZERO, w.route().miss(random));
        int n = 20_000;
        double sx = 0, sz = 0;
        for (int i = 0; i < n; i++) {
            Vec3 m = WeaponSpec.DRONE.route().miss(random);
            assertEquals(0, m.y);
            sx += m.x * m.x;
            sz += m.z * m.z;
        }
        assertEquals(WeaponSpec.DRONE.route().error(), Math.sqrt(sx / n), 0.05);
        assertEquals(WeaponSpec.DRONE.route().error(), Math.sqrt(sz / n), 0.05);
    }

    /** The alarm sounds before the projectile reaches the last straight section: at the target they have time to hear the approach. */
    @Test
    void sirenBeforeFinalLeg() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.BUNKER)) {
            double warned = w.sirenLead() * w.airframe().cruiseSpeed();
            assertTrue(warned >= w.finalLeg(), "anxiety for " + warned + " blocks, last section " + w.finalLeg());
        }
    }

    /**
     * A miss closer to {@code reattackMin} does not cancel the attack: it is less than the turning circle with a margin, otherwise the projectile at the very target
     *  would go for a new approach where the fuse is still reaching.
     */
    @Test
    void reattackInsideTurnCircle() {
        for (WeaponSpec w : GUIDED) {
            WeaponSpec.Airframe a = w.airframe();
            double circle = a.turnRadius(a.cruiseSpeed()) * WeaponSpec.TURN_MARGIN;
            assertTrue(a.attack().reattackMin() > 0 && a.attack().reattackMin() < circle,
                    "reattackMin " + a.attack().reattackMin() + ", turning circle " + circle);
        }
    }

    /**
     * The slide begins outside the turning circle at dive speed (otherwise the attack target is immediately “inside the circle” and the attack
     *  is cancelled), no further than the last straight section and in the stripe that is visible at the target; approaching the slide - from afar,
     *  than its beginning.
     */
    @Test
    void popUpGeometryFitsTurnAndLegs() {
        WeaponSpec.Airframe a = WeaponSpec.MISSILE.airframe();
        WeaponSpec.Attack attack = a.attack();
        assertTrue(attack.terminalRange() > a.turnRadius(a.diveSpeed()) * WeaponSpec.TURN_MARGIN);
        assertTrue(attack.terminalRange() <= WeaponSpec.MISSILE.finalLeg());
        assertTrue(attack.terminalRange() <= a.visibleLeg());
        assertTrue(attack.popUpMinRange() > attack.terminalRange());
    }

    /** The last straight section accommodates two turning radii: the projectile exits the detour onto the target course before the attack. */
    @Test
    void finalLegFitsTurn() {
        for (WeaponSpec w : GUIDED) {
            WeaponSpec.Airframe a = w.airframe();
            assertTrue(w.finalLeg() >= 2 * a.turnRadius(a.cruiseSpeed()), "last section " + w.finalLeg());
        }
    }

    /**
     * A waypoint is captured near the turn radius: any closer and the projectile would circle it;
     * much farther and it would cut the turn. Operator-route range covers a target at the default map edge
     * (10 000 blocks) with a detour one and a half times the straight distance.
     */
    @Test
    void waypointCaptureAndReach() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.LOITER)) {
            WeaponSpec.Airframe a = w.airframe();
            double r = a.turnRadius(a.cruiseSpeed());
            assertTrue(w.capture() >= 0.95 * r && w.capture() <= 2 * r, "capture point " + w.capture() + ", turning radius " + r);
            assertTrue(w.route().reach() >= 1.5 * 10_000, "route distance " + w.route().reach());
        }
        for (WeaponSpec w : List.of(WeaponSpec.BUNKER, WeaponSpec.NUKE, WeaponSpec.ROCKET)) assertTrue(!w.route().waypoints());
    }

    /** The launcher cell is occupied while the projectile is burning on the rail and launch rail exit from it; launch no more often than the cell is freed. */
    @Test
    void rackHoldsSlotThroughLaunch() {
        for (WeaponSpec w : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.LOITER)) {
            WeaponSpec.LaunchProfile lp = w.airframe().launchProfile();
            LauncherRack rack = w.rack();
            assertTrue(rack != null && lp != null);
            assertTrue(rack.busyTicks() >= lp.ignitionTicks() + lp.railTicks(), w.launch() + ": cell " + rack.busyTicks());
            assertTrue(rack.spacing() <= rack.busyTicks());
        }
    }

    /** Each type of weapon has its own spec, and the order of types (numbers in the network and saves) has not changed. */
    @Test
    void everyWeaponHasSpec() {
        assertEquals(List.of(WeaponType.DRONE, WeaponType.MISSILE, WeaponType.BUNKER, WeaponType.NUKE, WeaponType.ROCKET, WeaponType.LOITER),
                List.of(WeaponType.values()));
        for (WeaponType w : WeaponType.values()) {
            assertTrue(w.spec() != null, w.getSerializedName());
            assertEquals(w.ordinal(), w.id());
        }
        assertTrue(WeaponType.BUNKER.spec().payload() != null);
    }

    /**
     * Ammunition for each weapon: one item per projectile, and the “Grad” package for all launcher pipes (40, like reloading
     * BM-21): a salvo by default from the remote control is placed in it.
     */
    @Test
    void munitionPerShotAndGradPack() {
        for (WeaponType w : WeaponType.values()) {
            if (w == WeaponType.ROCKET) continue;
            assertEquals(1, w.spec().munition().rounds(), w.getSerializedName());
        }
        assertEquals(LauncherRack.ROCKET.slots(), WeaponSpec.ROCKET.munition().rounds());
        assertEquals(40, WeaponSpec.ROCKET.munition().rounds());
        assertTrue(WeaponSpec.ROCKET.salvo().count() <= WeaponSpec.ROCKET.munition().rounds());
    }

    /**
     * Explosion on the ground - with a charge; fuel to strike - for those who fly on it (Shahed - gasoline, rocket - JP-10), for
     *  "Lancet" (battery), "Grad" (engine burnt out), bombs and warheads - zero. Numbers and their source are in Javadoc spec.
     */
    @Test
    void surfaceBlastsHaveChargeAndFuelAtImpact() {
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec s = w.spec();
            if (s.blast() != WeaponSpec.Blast.NONE) assertTrue(s.charge() > 0, w + ": charge " + s.charge());
            assertTrue(s.fuelAtImpact() >= 0 && Double.isFinite(s.fuelAtImpact()), w + ": fuel " + s.fuelAtImpact());
        }
        assertEquals(30, WeaponSpec.DRONE.fuelAtImpact());
        assertEquals(275, WeaponSpec.MISSILE.fuelAtImpact());
        for (WeaponSpec s : List.of(WeaponSpec.LOITER, WeaponSpec.ROCKET, WeaponSpec.BUNKER, WeaponSpec.NUKE)) assertEquals(0, s.fuelAtImpact());
    }

    // ---- products by performance characteristics: numbers derived by law versus open data

    private static final double RHO0 = StandardAtmosphere.density(0);

    /** All flight entities of all weapons (B-2 has a bomb). */
    private static List<WeaponSpec.Airframe> airframes() {
        List<WeaponSpec.Airframe> out = new ArrayList<>();
        for (WeaponType w : WeaponType.values()) {
            out.add(w.spec().airframe());
            if (w.spec().payload() != null) out.add(w.spec().payload());
        }
        return out;
    }

    /**
     * For each product, all the fields are real numbers: masses, dimensions, resistance, speeds; from whom it hits - conditions
     *  of the strike; Warhead - TNT is not less than zero, the body and explosives are not more than the entire mass; the fuel to impact is no more than the fuel on board.
     */
    @Test
    void everyAirframeHasArticle() {
        for (WeaponSpec.Airframe a : airframes()) {
            Article r = a.article();
            String n = a.noise() + "";
            for (double v : new double[]{r.dryMass(), r.launchMass(), r.length(), r.diameter(), r.span(), r.cda(), r.real().cruise(), r.real().dive()})
                assertTrue(v > 0 && Double.isFinite(v), n);
            assertTrue(r.launchMass() >= r.dryMass(), n);
            assertTrue(r.real().dive() >= r.real().cruise(), n);
            assertTrue(r.fuelAtImpact() >= 0 && r.fuelAtImpact() <= r.launchMass() - r.dryMass(), n);
            if (r.warhead() != null) {
                assertTrue(r.warhead().tnt() > 0 && r.warhead().explosive() > 0 && r.warhead().caseMass() >= 0, n);
                assertTrue(r.warhead().explosive() + r.warhead().caseMass() <= r.dryMass(), n);
            }
            if (r.booster() != null) assertTrue(r.booster().propellant() > 0 && r.booster().jettison() >= 0, n);
        }
        for (WeaponType w : WeaponType.values()) {
            WeaponSpec s = w.spec();
            assertTrue(s.striker().article().impact() != null, w + ": impact conditions");
            if (s.blast() != WeaponSpec.Blast.NONE) assertTrue(s.striker().article().warhead() != null, w + ": warhead");
        }
        assertTrue(WeaponSpec.BUNKER.airframe().article().impact() == null, "B-2 does not hit itself");
    }

    /** Artem solutions: missile - "Tomahawk" ~150 kg TNT, Shahed - original with 50 kg; MOP - 2,4 t explosives. */
    @Test
    void chargesByDecision() {
        assertEquals(150, WeaponSpec.MISSILE.charge());
        assertEquals(50, WeaponSpec.DRONE.charge());
        assertEquals(6.4, WeaponSpec.ROCKET.charge());
        assertEquals(3, WeaponSpec.LOITER.charge());
        assertEquals(2423, WeaponSpec.BUNKER.striker().article().warhead().explosive());
        assertEquals(0, WeaponSpec.BUNKER.charge(), "the bomb explodes underground: there is no explosion on the ground");
        assertEquals(0, WeaponSpec.NUKE.charge());
    }

    /** Game marching speeds are no more than real ones (not physical, so that the approach is visible). */
    @Test
    void gameSpeedsBelowReal() {
        for (WeaponSpec s : List.of(WeaponSpec.DRONE, WeaponSpec.MISSILE, WeaponSpec.BUNKER)) {
            WeaponSpec.Airframe a = s.airframe();
            assertTrue(a.cruiseSpeed() * 20 <= a.article().real().cruise(), s.blast() + "");
        }
    }

    /** Shahed: cruise from propeller power and resistance ηP = ½ρv³·CdA - 185 km/h ±10 % (Wikipedia). */
    @Test
    void shahedCruiseFromPowerAndDrag() {
        Article a = WeaponSpec.DRONE.airframe().article();
        Article.Propeller p = (Article.Propeller) a.sustainer();
        double v = Math.cbrt(2 * p.efficiency() * p.power() / (RHO0 * a.cda()));
        assertEquals(185 / 3.6, v, 185 / 3.6 * 0.1);
        assertEquals(a.real().cruise(), v, a.real().cruise() * 0.1);
        assertEquals(a.real().dive(), dive(a, 90), a.real().dive() * 0.05, "vertical");
        assertEquals(a.impact().speed(), dive(a, a.impact().angle()), a.impact().speed() * 0.05, "dive kick");
    }

    /** Tomahawk: cruise speed from turbofan thrust and drag T = ½ρv²·CdA is ~250 m/s ±5% (M0.74). */
    @Test
    void tomahawkCruiseFromThrust() {
        Article a = WeaponSpec.MISSILE.airframe().article();
        double v = Math.sqrt(2 * ((Article.Turbofan) a.sustainer()).thrust() / (RHO0 * a.cda()));
        assertEquals(250, v, 250 * 0.05);
        // accelerator Mk 106 accelerates 1,6 t from standstill to ~210 m/s - then the turbine
        Article.SolidMotor b = a.booster();
        double m0 = a.launchMass() + b.jettison();
        double dv = b.exhaust() * Math.log(m0 / (m0 - b.propellant()));
        assertTrue(dv > 150 && dv < a.real().cruise(), "Δv accelerator " + dv);
    }

    /** "Lancet": vertical dive 300 km/h ±10 % of weight and CdA; impact - the same balance at the angle of impact. */
    @Test
    void lancetDiveFromWeight() {
        Article a = WeaponSpec.LOITER.airframe().article();
        assertEquals(300 / 3.6, dive(a, 90), 300 / 3.6 * 0.1);
        assertEquals(a.impact().speed(), dive(a, a.impact().angle()), a.impact().speed() * 0.05);
    }

    /**
     * “Grad”: point with mass, engine thrust spec, resistance CdA·f(M) in standard atmosphere - maximum
     * range 20,38 km ±3 % (Wikipedia), to burnout ~690 m/s ±3 %, impact - as in spec.
     */
    @Test
    void gradRangeWithMachDrag() {
        Article a = WeaponSpec.ROCKET.airframe().article();
        assertEquals(a.launchMass() - a.dryMass(), a.booster().propellant(), 0.01, "fuel - mass difference");
        double[] best = null;
        for (int deg = 40; deg <= 65; deg++) {
            double[] r = fly(a, a.booster(), Math.toRadians(deg), 0, 0);
            if (best == null || r[0] > best[0]) best = r;
        }
        assertEquals(20_380, best[0], 20_380 * 0.03, "range");
        assertEquals(690, best[1], 690 * 0.03, "to burnout");
        assertEquals(a.real().cruise(), best[1], 5);
        assertEquals(a.impact().speed(), best[2], 5, "impact speed");
        assertEquals(a.impact().angle(), best[3], 1, "impact angle");
    }

    /** GBU-57: fall from drop height at cruising speed B-2 - impact as in spec (±2%, ±1°). */
    @Test
    void mopDropImpact() {
        Article bomb = WeaponSpec.BUNKER.payload().article(), b2 = WeaponSpec.BUNKER.airframe().article();
        double[] r = fly(bomb, null, 0, b2.real().cruise(), b2.real().altitude());
        assertEquals(bomb.impact().speed(), r[2], bomb.impact().speed() * 0.02);
        assertEquals(bomb.impact().angle(), r[3], 1);
        assertTrue(r[4] > 45 && r[4] < 55, "drop ~50 s: " + r[4]);
    }

    /** 9M96: engine Δv by Tsiolkovsky is no less than top speed; warhead 24 kg (explosive + casing). */
    @Test
    void samPassportBy9M96() {
        InterceptorSpec.Passport p = InterceptorSpec.SAM.passport();
        double m0 = p.launchMass();
        assertTrue(p.boost().exhaust() * Math.log(m0 / (m0 - p.boost().propellant())) >= p.maxSpeed());
        assertEquals(24, p.warhead().explosive() + p.warhead().caseMass(), 1e-9);
        assertTrue(p.rangeMin() < p.rangeMax() && p.altitudeMin() < p.altitudeMax() && p.fuse() > 0);
        assertTrue(InterceptorSpec.SAM.maxSpeed() * 20 < p.maxSpeed(), "the game one is slower than the real one");
    }

    /** The sound and torches take the engine from the spec: one thrust and one outflow per product. */
    @Test
    void noiseUsesArticleMotors() {
        assertEquals(WeaponSpec.DRONE_BOOSTER.thrust(), WeaponSpec.DRONE.airframe().noise().motor().thrust());
        assertEquals(WeaponSpec.MISSILE_BOOSTER.exhaust(), WeaponSpec.MISSILE.airframe().noise().motor().exhaust());
        assertEquals(WeaponSpec.ROCKET_MOTOR.thrust(), WeaponSpec.ROCKET.airframe().noise().motor().thrust());
        assertEquals(WeaponSpec.NUKE.ascent().thrust(), WeaponSpec.NUKE.airframe().noise().motor().thrust());
    }

    /** Steady speed at peak at an angle θ to the horizontal: propeller thrust + mg·sin θ = ½ρv²·CdA. */
    private static double dive(Article a, double angle) {
        double m = (a.dryMass() + a.launchMass()) / 2, lo = 1, hi = 500;
        for (int i = 0; i < 100; i++) {
            double v = (lo + hi) / 2;
            double thrust = a.sustainer() instanceof Article.Propeller p ? p.thrust(v) : 0;
            if (thrust + m * Article.G * Math.sin(Math.toRadians(angle)) > 0.5 * RHO0 * v * v * a.cda()) lo = v;
            else hi = v;
        }
        return lo;
    }

    /**
     * Point with mass above flat earth: engine thrust (if any) along the speed, resistance ½ρv²·CdA·f(M) according to
     * standard atmosphere, step 0,01 s.
     *
     * @return range, m; speed to burnout, m/s; impact speed, m/s; impact angle, °; flight time, s
     */
    private static double[] fly(Article a, Article.SolidMotor motor, double elevation, double v0, double h0) {
        double dt = 0.01, x = 0, h = h0, t = 0, burnout = Double.NaN;
        double v = Math.max(v0, 1e-3), vx = v * Math.cos(elevation), vy = v * Math.sin(elevation);
        double burn = motor == null ? 0 : motor.burn();
        while (true) {
            v = Math.hypot(vx, vy);
            double rho = StandardAtmosphere.density(Math.max(h, 0));
            double sound = Math.sqrt(1.4 * StandardAtmosphere.R_AIR * StandardAtmosphere.temperature(Math.max(h, 0)));
            double m = a.launchMass() - (motor == null ? 0 : motor.flow() * Math.min(t, burn));
            double thrust = t < burn ? motor.thrust() : 0;
            double drag = 0.5 * rho * v * v * a.cda() * Article.machFactor(v / sound);
            vx += (thrust - drag) * vx / v / m * dt;
            vy += ((thrust - drag) * vy / v / m - Article.G) * dt;
            x += vx * dt;
            h += vy * dt;
            t += dt;
            if (Double.isNaN(burnout) && t >= burn) burnout = Math.hypot(vx, vy);
            if (h < 0 && t > 1) return new double[]{x, burnout, Math.hypot(vx, vy), Math.toDegrees(Math.atan2(-vy, vx)), t};
        }
    }
}
