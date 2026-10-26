package ua.zentix.incoming.client.flight;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.sound.Acoustics;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponType;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Path of the projectile through the server packets: between packets - in a straight line, gap - again, without data - silent. */
class FlightTrackTest {
    private static final UUID ID = UUID.randomUUID();

    private static S2C.FarFlight at(double x, int phaseAge) {
        return at(x, 11.5, phaseAge);
    }

    private static S2C.FarFlight at(double x, double vx, int phaseAge) {
        return new S2C.FarFlight(ID, WeaponType.MISSILE.id(), false, false, true, new Vec3(x, 80, 0), new Vec3(vx, 0, 0), 0, 0, 0,
                FlightPhase.CRUISE.ordinal(), phaseAge, new Vec3(500, 80, 0));
    }

    private static double x(FlightTrack t, double time) {
        double[] p = new double[3];
        t.at(time, p);
        return p[0];
    }

    @Test
    void ticksBetweenPacketsFollowStraightLine() {
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.record(14, at(46, 104));
        assertEquals(11.5, x(t, 11), 1e-9);
        assertEquals(34.5, x(t, 13), 1e-9);
        assertEquals(11.5, t.velocity(12.5).x, 1e-9, "smooth speed - Doppler without steps");
        assertEquals(103, t.phaseAge(13), 1e-9);
        assertEquals(10, t.start(), 1e-9);
    }

    @Test
    void velocityIsWhatServerReportedNotUnevenArrival() {
        // server tick packet 11 was late for the client tick: the position stands, then jumps two steps - the speed is even
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(11, at(0, 100));
        t.record(12, at(23, 102));
        for (double time = 10; time <= 12; time += 0.25) assertEquals(11.5, t.velocity(time).x, 1e-9);
    }

    @Test
    void velocityBetweenTicksIsInterpolated() {
        FlightTrack t = new FlightTrack(ID, WeaponType.ROCKET, false);
        t.record(10, at(0, 4, 100));
        t.record(12, at(9, 5, 102));
        assertEquals(4.5, t.velocity(11).x, 1e-9);
        assertEquals(4.75, t.velocity(11.5).x, 1e-9);
        assertEquals(5, t.velocity(20).x, 1e-9, "beyond the edge of history - the last known");
    }

    @Test
    void laterPacketOfTheSameTickWins() {
        // the network sent two packets in one tick: the last one is correct
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.record(12, at(25, 104));
        assertEquals(25, x(t, 12), 1e-9);
        assertEquals(12.5, x(t, 11), 1e-9);
    }

    @Test
    void longGapStartsHistoryOver() {
        // the projectile left hearing and returned: between these points it did not fly in a straight line
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(40, at(300, 130));
        assertEquals(40, t.start(), 1e-9);
        assertEquals(300, x(t, 30), 1e-9);
    }

    @Test
    void silentWhenNothingIsKnownForThatMoment() {
        // by package - we wait for the next one (period and tick for uneven delivery), there is nothing further to listen to
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        assertTrue(t.covers(12 + S2C.FarFlights.PERIOD + 2));
        assertFalse(t.covers(12 + S2C.FarFlights.PERIOD + 2.5));
    }

    @Test
    void emissionTimeFollowsPacketPath() {
        // rocket 11.5 block/tick by packets per listener at the origin: |p(te)| = c·(now − te)
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        for (int k = 0; k <= 40; k += S2C.FarFlights.PERIOD) t.record(k, at(600 - 11.5 * k, k));
        double now = 40;
        double te = Acoustics.emissionTime(t, now, 0, 80, 0);
        assertEquals(Acoustics.SPEED * (now - te), Math.abs(x(t, te)), 1e-4);
    }

    @Test
    void predictionRunsAheadByLastVelocityButNotPastTheAim() {
        // the next packet is still on the way: the projectile in the distance travels further in speed, but does not fly through the target (x = 500)
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        double[] p = new double[3];
        assertTrue(t.predict(13.5, p));
        assertEquals(23 + 11.5 * 1.5, p[0], 1e-9);
        t.record(14, at(490, 104));
        assertTrue(t.predict(16, p));
        assertEquals(500, p[0], 1e-9);
        assertFalse(t.predict(14 + S2C.FarFlights.PERIOD + 3, p), "there is no data package beyond the expiration date");
    }

    @Test
    void blastEndsTheFlightWhenItHappened() {
        // the last packet about a long-range missile reached 6 tick before the explosion packet (longer waiting for the next packet): the sound
        //  lasts until the explosion itself in speed, and does not subside earlier - otherwise there is a pause of silence before the explosion
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.record(14, at(46, 104));
        assertFalse(t.covers(19), "without explosion package - no data");
        t.impact(20);
        assertTrue(t.covers(19.5));
        assertTrue(t.covers(20));
        assertFalse(t.covers(20.5), "after the explosion there is no engine");
        assertEquals(46 + 11.5 * 6, x(t, 20), 1e-9, "before the explosion - according to the speed of the last recording");
    }

    @Test
    void lateBlastDoesNotStretchAnOldPath() {
        // there are no packets for a long time (left hearing, the server stopped): the explosion does not carry the sound beyond the break, after which the path is restarted
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        t.impact(60);
        assertTrue(t.covers(20));
        assertFalse(t.covers(20.5));
        // end of the flight in silence (there was no explosion) - immediately after the last recording
        FlightTrack s = new FlightTrack(ID, WeaponType.MISSILE, false);
        s.record(10, at(0, 100));
        s.die(60);
        assertTrue(s.covers(11));
        assertFalse(s.covers(11.5));
    }

    @Test
    void trailIsDrawnOnlyOverServerSamples() {
        // points by packages - for trail in the distance; The client's entity draws its trail with particles
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        assertTrue(t.fromServer(11));
        assertTrue(t.fromServer(12));
        assertFalse(t.fromServer(13));
        assertFalse(t.fromServer(9));
    }

    private static S2C.FarFlight turned(float yaw, float pitch, float roll) {
        return new S2C.FarFlight(ID, WeaponType.MISSILE.id(), false, false, true, new Vec3(0, 80, 0), new Vec3(4, 0, 0), yaw, pitch, roll,
                FlightPhase.CRUISE.ordinal(), 100, new Vec3(500, 80, 0));
    }

    @Test
    void anglesTurnTheShortWayBetweenTicks() {
        // the model in the distance turns and rolls between the packages, rather than jumping; via ±180° - short way
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, turned(170, -4, 20));
        t.record(11, turned(-170, 4, -20));
        float[] a = new float[3];
        t.angles(10.5, a);
        assertEquals(180, a[0], 1e-4);
        assertEquals(0, a[1], 1e-4);
        assertEquals(0, a[2], 1e-4);
        t.angles(15, a);
        assertEquals(-170, a[0], 1e-4, "behind the last package are the last corners");
        assertEquals(-20, a[2], 1e-4);
        // missed tick between packets - mid-turn and roll
        t.record(13, turned(-150, 4, 20));
        t.angles(12, a);
        assertEquals(-160, a[0], 1e-4);
        assertEquals(0, a[2], 1e-4);
    }

    @Test
    void ageCountsFromTheFirstPacketOfTheHistory() {
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        t.record(10, at(0, 100));
        t.record(12, at(23, 102));
        assertEquals(4.5, t.age(14.5), 1e-9);
        assertEquals(0, t.age(5), 1e-9);
    }

    /** The ground of the launch site from the packages remains on the path even after the launch: the marching package does not erase it. */
    @Test
    void launchGroundKeptFromPackets() {
        FlightTrack t = new FlightTrack(ID, WeaponType.MISSILE, false);
        assertTrue(Double.isNaN(t.launchGround()), "without packages there is no land");
        t.record(10, new S2C.FarFlight(ID, WeaponType.MISSILE.id(), false, false, true, new Vec3(0, 75, 0), Vec3.ZERO, 0, -30, 0,
                FlightPhase.IGNITION.ordinal(), 3, new Vec3(500, 80, 0), 72));
        t.record(12, at(10, 2));
        assertEquals(72, t.launchGround());
    }
}
