package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.guidance.Ballistics;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.strike.Hearing;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Layers of sound along the entire path of the projectile: volume - from the sound power and path, tone - from revolutions and Doppler. */
class EngineSoundTest {
    private static final UUID ID = UUID.randomUUID();
    private static final Vec3 EAR = Vec3.ZERO;
    private static final Propagation.Weather NIGHT = Propagation.Weather.NIGHT;
    /** Marching speed of the rocket, blocks/tick. */
    private static final double V = WeaponSpec.MISSILE.airframe().cruiseSpeed();
    private static final EngineSound.Layer[] MISSILE = {EngineSound.Layer.MISSILE_FRONT, EngineSound.Layer.MISSILE_REAR,
            EngineSound.Layer.MISSILE_DIVE, EngineSound.Layer.MISSILE_FAR, EngineSound.Layer.MISSILE_WHISTLE};

    /** Projectile weapon {@code w} with {@code from} blocks directly at the listener at a height of {@code y}, {@code ticks} tick, packets once in {@code every}. */
    private static FlightTrack straight(WeaponType w, double from, double y, double v, FlightPhase ph, Vec3 aim, int ticks, int every) {
        FlightTrack t = new FlightTrack(ID, w, false);
        for (int k = 0; k <= ticks; k += every) {
            t.record(k, new S2C.FarFlight(ID, w.id(), false, false, true, new Vec3(from - v * k, y, 0), new Vec3(-v, 0, 0), 90, 0, 0,
                    ph.ordinal(), 200 + k, aim));
        }
        return t;
    }

    private static FlightTrack missile(double from, Vec3 aim, int ticks) {
        return straight(WeaponType.MISSILE, from, 12, V, FlightPhase.CRUISE, aim, ticks, 1);
    }

    private static Emission heard(FlightTrack t, double now, Vec3 ear) {
        double te = Acoustics.emissionTime(t, now, ear.x, ear.y, ear.z);
        return Double.isNaN(te) || !t.covers(te) ? null : Emission.at(t, te, ear, false, NIGHT);
    }

    private static double gain(EngineSound.Layer layer, FlightTrack t, double now, Vec3 ear) {
        Emission e = heard(t, now, ear);
        return e == null ? 0 : layer.tone(t, e).gain();
    }

    /** The volume of the rocket motor - all its records together (by power). */
    private static double engine(FlightTrack t, double now, Vec3 ear) {
        double g = 0;
        for (EngineSound.Layer l : MISSILE) g += Math.pow(gain(l, t, now, ear), 2);
        return Math.sqrt(g);
    }

    @Test
    void missileWhistleWarnsFromWhereItBecomesAudible() {
        // path through packets (once per 2 tick), like a rocket outside the world, from the edge of audibility at night to the listener at the target: whistle
        //  can be heard before the rocket arrives at d (1/v − 1/c), and path history does not lose the audible point
        Noise noise = WeaponSpec.MISSILE.airframe().noise();
        double from = Math.min(Hearing.range(noise, FlightPhase.CRUISE, 200, V, false, 12, NIGHT), 5000);
        int arrival = (int) (from / V);
        FlightTrack t = straight(WeaponType.MISSILE, from, 12, V, FlightPhase.CRUISE, EAR, arrival, 2);
        int first = -1;
        double firstDistance = 0;
        for (int k = 0; k <= arrival && first < 0; k++) {
            Emission e = heard(t, k, EAR);
            if (e != null && EngineSound.Layer.MISSILE_WHISTLE.tone(t, e).gain() >= VoiceBudget.AUDIBLE) {
                first = k;
                firstDistance = e.distance();
            }
        }
        assertTrue(first >= 0, "there was no whistle");
        assertTrue(firstDistance > 0.6 * from, "whistle can be heard from " + Math.round(firstDistance) + " blocks, audibility - up to " + Math.round(from));
        double lead = firstDistance * (1 / V - 1 / Acoustics.SPEED);
        assertTrue(arrival - first >= 0.9 * lead, "whistle for " + (arrival - first) + " tick until the rocket arrives, waiting ~" + Math.round(lead));
        // closer - louder (closer to the howl of a fan - the recording is closer to the front)
        assertTrue(engine(t, arrival - 30, EAR) > engine(t, first + 30, EAR));
    }

    @Test
    void missileEngineDoesNotDipOnPassOrLayerHandoff() {
        // the rocket passes in 12 blocks overhead and goes further: the power of the motor (all its layers are different records,
        //  add up in terms of power) on approach only grows, then only falls - without falling overhead and replacing
        //  near records with distant ones, in front with rear ones
        FlightTrack t = missile(1200, new Vec3(-3000, 0, 0), 600);
        double prev = -1;
        boolean prevApproaching = true;
        int passed = 0;
        for (double now = 5; now <= 600; now += 0.5) {
            Emission e = heard(t, now, EAR);
            if (e == null) continue;
            double power = 0;
            for (EngineSound.Layer l : MISSILE) power += Math.pow(l.tone(t, e).gain(), 2);
            if (prev >= 0 && e.approaching() == prevApproaching) {
                String at = "tick " + now + ", " + Math.round(e.distance()) + " blocks, " + (e.approaching() ? "approach" : "following");
                if (e.approaching()) assertTrue(power >= prev * (1 - 1e-9), at + ": quieter than tick before: " + prev + " → " + power);
                else assertTrue(power <= prev * (1 + 1e-9), at + ": louder than tick before: " + prev + " → " + power);
            }
            if (!e.approaching()) passed++;
            prev = power;
            prevApproaching = e.approaching();
        }
        assertTrue(passed > 40, "the rocket went behind the listener: sample following " + passed);
    }

    @Test
    void layersOfOneEngineShareItsPower() {
        // shahed's near and far recordings together sound like one motor: the sum of the powers equals the volume level at the ear
        FlightTrack t = straight(WeaponType.DRONE, 1500, 45, 2.1, FlightPhase.CRUISE, new Vec3(-2000, 0, 0), 700, 1);
        for (int now = 100; now <= 700; now += 10) {
            Emission e = heard(t, now, EAR);
            if (e == null) continue;
            double near = EngineSound.Layer.DRONE_NEAR.tone(t, e).gain(), far = EngineSound.Layer.DRONE_FAR.tone(t, e).gain();
            Emission.Heard h = e.heard(EngineSound.noise(t), Noise.Part.ENGINE, false);
            assertEquals(Loudness.gain(h.level(), h.margin()), Math.sqrt(near * near + far * far), 1e-9, "tick " + now);
        }
    }

    @Test
    void shareKeepsPowerOfTwoLayers() {
        for (double w = 0; w <= 1; w += 0.05) {
            double a = EngineSound.share(w), b = EngineSound.share(1 - w);
            assertEquals(1, a * a + b * b, 1e-12);
        }
    }

    @Test
    void propellerToneFollowsAirspeed() {
        // fixed pitch propeller: speed - according to speed (step is the same), not lower than the speed in place and not higher than the limit
        Noise.Propeller p = (Noise.Propeller) WeaponSpec.DRONE.airframe().noise().engine();
        assertEquals(p.idle(), p.revs(FlightPhase.READY, 0, 0), 1e-12);
        double cruise = WeaponSpec.DRONE.airframe().cruiseSpeed();
        assertEquals(cruise * 20 / p.speed(), p.revs(FlightPhase.CRUISE, 0, cruise), 1e-12);
        assertEquals(p.limit(), p.revs(FlightPhase.TERMINAL, 0, 100), 1e-12);
        // power ∝ n³: 10% more rpm - 1,24 dB louder
        assertEquals(30 * Math.log10(1.1), p.power(FlightPhase.CRUISE, 0, 1.1 * p.speed() / 20) - p.power(FlightPhase.CRUISE, 0, p.speed() / 20), 1e-9);
    }

    @Test
    void rocketHowlsWhereverItIsAboveTheBackground() {
        // mLRS projectile on 1200 blocks (top of the arc ~360 blocks): after burnout, the howl is heard everywhere where the flow around the background is louder than the
        //  background with a margin, and beyond the edge of audibility there is silence
        double range = 1200;
        int ticks = Ballistics.ticksFor(Vec3.ZERO, new Vec3(range, 0, 0), 50, 50);
        Vec3 v0 = Ballistics.launchVelocity(Vec3.ZERO, new Vec3(range, 0, 0), ticks);
        FlightTrack t = new FlightTrack(ID, WeaponType.ROCKET, false);
        for (int k = 0; k <= ticks; k++) {
            Vec3 p = Ballistics.at(Vec3.ZERO, v0, k), v = Ballistics.at(Vec3.ZERO, v0, k + 1).subtract(p);
            FlightPhase ph = k < 40 ? FlightPhase.BOOST : v.y < 0 ? FlightPhase.TERMINAL : FlightPhase.CRUISE;
            t.record(k, new S2C.FarFlight(ID, WeaponType.ROCKET.id(), false, false, true, p, v, 90, 0, 0, ph.ordinal(), k, new Vec3(range, 0, 0)));
        }
        int heard = 0, silent = 0;
        for (double x : new double[] {-10, range / 4, range / 2, range + 5}) {
            Vec3 ear = new Vec3(x, 2, 0);
            for (int now = 0; now <= ticks + 100; now += 5) {
                Emission e = heard(t, now, ear);
                if (e == null || e.time() < 42 || e.time() > ticks) continue;
                Emission.Heard h = e.heard(EngineSound.noise(t), Noise.Part.BODY, false);
                double gain = EngineSound.Layer.ROCKET_AIR.tone(t, e).gain();
                // flow direction - up to −5,2 dB following
                if (h.margin() > Propagation.FADE + 5.3) {
                    assertTrue(gain > 0, "listener " + x + ", tick " + now + ": in " + Math.round(e.distance()) + " blocks howl " + gain);
                    heard++;
                } else if (h.margin() <= 0) {
                    assertEquals(0, gain, 0, "out of earshot");
                    silent++;
                }
            }
        }
        assertTrue(heard > 5 && silent > 5, "audible sample: " + heard + ", beyond the edge - " + silent);
    }

    @Test
    void whistleFadesThroughThePassInsteadOfCuttingOff() {
        // the rocket passes in 60 blocks from the side: the howl of the fan, which carries forward, subsides over the flight, and does not break off
        Vec3 ear = new Vec3(0, 12, 60);
        FlightTrack t = missile(600, new Vec3(-700, 0, 0), 300);
        double prev = -1, peak = 0, worst = 0;
        for (double now = 40; now <= 220; now += 1) {
            double g = 0;
            Emission e = heard(t, now, ear);
            if (e != null) for (EngineSound.Layer l : MISSILE) g += Math.pow(l.tone(t, e).gain(), 2);
            g = Math.sqrt(g);
            if (prev >= 0) worst = Math.max(worst, Math.abs(g - prev));
            peak = Math.max(peak, g);
            prev = g;
        }
        assertTrue(peak > 0.05, "rocket in flight " + peak);
        assertTrue(worst < 0.15 * peak, "volume jump per tick " + worst + " at peak " + peak);
    }

    @Test
    void supersonicRoundCracksBeforeItIsHeard() {
        // a projectile at 1,4 M (synthetic speed: there is no supersonic in the current flight) passes in 100 blocks above the
        //  listener: until the Mach cone reaches, it is not audible at all; at tick, when he reaches it, there is a N wave, and only with it
        //  the howl begins
        double v = 1.4 * Acoustics.SPEED;
        FlightTrack t = straight(WeaponType.ROCKET, 3000, 100, v, FlightPhase.TERMINAL, new Vec3(-5000, 0, 0), 300, 1);
        double[] roots = new double[1];
        boolean reached = false;
        int boomAt = -1;
        for (int now = 0; now <= 300; now++) {
            boolean r = Acoustics.emissions(t, now, 0, 0, 0, roots) > 0;
            if (!r) assertEquals(0, gain(EngineSound.Layer.ROCKET_AIR, t, now, EAR), 0, "tick " + now + ": howl before the cone arrives");
            if (r && !reached) {
                Booms.Crack c = Booms.crack(t, roots[0], EAR, NIGHT);
                assertNotNull(c, "the cone arrived without the N wave");
                assertTrue(c.wave().pressure() > 50 && c.volume() > 0, "wave " + c.wave() + ", volume " + c.volume());
                boomAt = now;
            }
            reached |= r;
        }
        // beyond the last entry the path will only reach the speed - there is no cotton there
        assertNull(Booms.crack(t, t.lastTick() + 1, EAR, NIGHT));
        // the cone catches up with the ear when the projectile passes h·√(M² − 1) behind it
        double overhead = 3000 / v, expected = overhead + 100 * Math.sqrt(1.4 * 1.4 - 1) / v;
        assertEquals(expected, boomAt, 1.5, "N-wave on tick");
        // subsonic projectile does not pop
        FlightTrack slow = straight(WeaponType.ROCKET, 600, 100, 4, FlightPhase.TERMINAL, new Vec3(-5000, 0, 0), 300, 1);
        for (int now = 0; now <= 300; now++) {
            if (Acoustics.emissions(slow, now, 0, 0, 0, roots) > 0) {
                assertNull(Booms.crack(slow, roots[0], EAR, NIGHT));
                break;
            }
        }
    }
}
