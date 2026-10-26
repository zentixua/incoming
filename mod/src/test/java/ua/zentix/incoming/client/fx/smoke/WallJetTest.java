package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wall jet oblique jet near the ground: share in directions (Hasson–Peck), puff, where the dust from the launcher goes. */
class WallJetTest {
    /**
     * The azimuth fraction of the flow is the entire jet (∫w dφ = 1), and its momentum along the ground is exactly the horizontal fraction of the impulse
     *  of the jet (∫w cos φ dφ = cos α): the ground without friction takes only vertical. Directions of equal shares
     * ({@link WallJet#azimuths}) give the same amounts.
     */
    @Test
    void shareKeepsTheFlowAndTheMomentumAlongTheGround() {
        for (double deg : new double[]{90, 60, 30, 15, 5}) {
            double sinA = Math.sin(Math.toRadians(deg)), cosA = Math.cos(Math.toRadians(deg));
            int steps = 400_000;
            double sum = 0, along = 0;
            for (int i = 0; i < steps; i++) {
                double phi = 2 * Math.PI * (i + 0.5) / steps, w = WallJet.share(sinA, phi) * 2 * Math.PI / steps;
                sum += w;
                along += w * Math.cos(phi);
            }
            assertEquals(1, sum, 1e-4, "the whole stream " + deg + "°");
            assertEquals(cosA, along, 1e-4, "impulse along the ground, " + deg + "°");
            double[] az = WallJet.azimuths(sinA, 4096);
            double c = 0, s = 0;
            for (double a : az) {
                c += Math.cos(a);
                s += Math.sin(a);
            }
            assertEquals(cosA, c / az.length, 2e-3, "equal shares, " + deg + "°");
            assertEquals(0, s / az.length, 1e-9, "sideways - equally");
        }
        // vertically - equally in all directions
        assertEquals(1 / (2 * Math.PI), WallJet.share(1, 0), 1e-12);
        assertEquals(1 / (2 * Math.PI), WallJet.share(1, 2), 1e-12);
    }

    /**
     * Tightening: r⁴ = r₀⁴ + 12·∫i dt/(ρS) - at the push 16 times longer twice as far; the front stands where the wind
     *  overtakes it (dr/dt = 3i/(ρSr³) = U), and does not run back.
     */
    @Test
    void puffSlowsAsItSweepsUpAirAndStopsWhereTheWindIsFaster() {
        double i = 1e4, r0 = 0.5;
        double a = WallJet.reach(i, i * 1, r0, 0), b = WallJet.reach(i, i * 16, r0, 0);
        assertEquals(2 * Math.pow(Math.pow(a, 4) - Math.pow(r0, 4), 0.25), Math.pow(Math.pow(b, 4) - Math.pow(r0, 4), 0.25), 1e-9);
        double wind = 2, stop = Math.cbrt(3 * i / (CloudModel.RHO * WallJet.SPREAD * wind));
        assertEquals(stop, WallJet.reach(i, i * 1e6, r0, wind), 1e-9);
        double prev = 0;
        for (double t = 0; t < 600; t += 0.5) {
            double r = WallJet.reach(i, i * t, r0, wind);
            assertTrue(r >= prev - 1e-12);
            prev = r;
        }
        assertEquals(r0, WallJet.reach(0, 0, r0, wind), 0);
    }

    /**
     * Impulse - according to the history of the presentation (review #400: everything presented as a push at the beginning overestimated the takeaway of the long presentation ICBM):
     *  flat presentation with a length of T towards its end - ∫I dt = I·T/2, half the push of the same impulse; a cloud supplied exactly,
     *  goes no further than a cloud of the same impulse with a push.
     */
    @Test
    void longFeedPushesAsItGoes() {
        Motor m = motor(WeaponType.NUKE);
        double s0 = SmokeGrid.seconds(0);
        LaunchCloud puff = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, s0);
        puff.feed(m, 2, 0, 67, 0, 0, -1, 0);
        double[] once = puff.fanMoments(2, 1, 0);
        // the same presentation in exactly 2 s - to tick SmokeGrid
        LaunchCloud even = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, s0);
        double before = SmokeGrid.seconds(0);
        for (int k = 0; k < 40; k++) {
            even.feed(m, 0.05, 0, 67, 0, 0, -1, 0);
            SmokeGrid.tick();
        }
        double t = SmokeGrid.seconds(0) - s0;
        assertEquals(2, SmokeGrid.seconds(0) - before, 1e-9);
        double[] spread = even.fanMoments(t, 1, 0);
        assertTrue(spread[4] < once[4], "smooth presentation - closer to the push: " + spread[4] + " against " + once[4]);
        // ∫I dt to the end of the flat presentation - I·T/2 (in increments of tick - up to half a tick): radius ∝ ∫^¼
        double expect = Math.pow((Math.pow(once[4] / 0.75, 4) - 1) * 0.5 * (1 + 0.05 / 2), 0.25) * 0.75;
        assertEquals(expect, spread[4], 0.05 * expect);
    }

    private static Motor motor(WeaponType w) {
        return ClientWeaponSpec.of(w).airframe(false).far().stage(FlightPhase.BOOST).motor();
    }

    /** billows of dust near the ground of the cloud of start: middle (x, z) from the point of impact, half-segment (ax, az), σ horizontally. */
    private record Dust(double x, double z, double ax, double az, double sh) {}

    private static Dust dust(LaunchCloud c, double t, double seconds0) {
        seconds0 += SmokeGrid.seconds(0);
        List<Dust> out = new ArrayList<>();
        c.emit(seconds0 + t, (x, y, z, ax, ay, az, sh, sv, soot, d, water, r, g, b, kelvin, rise) -> {
            if (soot == 0 && water == 0 && d > 0 && kelvin == CloudModel.T_AIR) out.add(new Dust(x, z, ax, az, sh));
        });
        assertEquals(1, out.size(), "one billows of dust near the ground");
        return out.getFirst();
    }

    /**
     * Complaint regarding analysis of AMW: dust from the oblique jet lay all around at the site of the impact. Now for “Grad” at 30° its middle goes
     *  forward along the horizontal part of the jet (here - +x or −z, where the jet faces), billows are extended along it; for a steep
     *  jet - at the point of impact and round. There is no wind (otherwise it will be demolished).
     */
    @Test
    void obliqueJetBlowsTheDustAheadAlongItsDirection() {
        Motor grad = motor(WeaponType.ROCKET);
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30));
        for (double[] dir : new double[][]{{1, 0}, {0, -1}}) {
            LaunchCloud c = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, SmokeGrid.seconds(0));
            // jet from the nozzle at 2,5 m under 30° downwards, at the ground - at ~4 m forward
            for (int k = 0; k < 6; k++) c.feed(grad, 0.05, 0, 66.5, 0, dir[0] * cos, -sin, dir[1] * cos);
            double hitX = dir[0] * cos * LaunchCloud.reach(2.5, -sin), hitZ = dir[1] * cos * LaunchCloud.reach(2.5, -sin);
            Dust d = dust(c, 3, 0.4);
            double ahead = (d.x - hitX) * dir[0] + (d.z - hitZ) * dir[1], side = (d.x - hitX) * dir[1] - (d.z - hitZ) * dir[0];
            assertTrue(ahead > 3, "forward along the stream: " + ahead);
            assertEquals(0, side, 1e-6, "not sideways");
            double len = Math.hypot(d.ax, d.az);
            assertTrue(len > 1, "stretched along the stream: " + len);
            assertEquals(1, Math.abs(d.ax * dir[0] + d.az * dir[1]) / len, 1e-9, "segment - along the stream");
        }
        LaunchCloud down = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, SmokeGrid.seconds(0));
        for (int k = 0; k < 6; k++) down.feed(grad, 0.05, 0, 66.5, 0, 0, -1, 0);
        Dust d = dust(down, 3, 0.4);
        assertEquals(0, Math.hypot(d.x, d.z), 1e-6, "vertically - at the point of impact");
        assertEquals(0, Math.hypot(d.ax, d.az), 1e-6, "and all around");
    }

    /**
     * Presentation instant - according to the clock of the journey, like the beginning of the cloud (review #400): path cloud by packets, started and served with a lag of
     *  on 4 tick, at the same time after presentation - the same as for the cloud without a lag.
     */
    @Test
    void feedTimeIsThePathClock() {
        Motor grad = motor(WeaponType.ROCKET);
        double s0 = SmokeGrid.seconds(0), lag = 0.2, sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30));
        LaunchCloud now = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, s0);
        LaunchCloud late = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, s0 - lag);
        for (int k = 0; k < 6; k++) {
            now.feed(grad, 0.05, s0 + 0.05 * k, 0, 66.5, 0, cos, -sin, 0);
            late.feed(grad, 0.05, s0 - lag + 0.05 * k, 0, 66.5, 0, cos, -sin, 0);
        }
        double[] a = now.fanMoments(0.8, 0.5, 0), b = late.fanMoments(0.8, 0.5, 0);
        for (int i = 0; i < a.length; i++) assertEquals(a[i], b[i], 1e-9 * Math.max(1, Math.abs(a[i])));
    }

    /** Dust near the ground is in one billow and no more than its share of cloud dust: the wall stream transfers dust, but does not create it. */
    @Test
    void groundDustIsNeverMoreThanItsShare() {
        Motor grad = motor(WeaponType.ROCKET);
        double s0 = SmokeGrid.seconds(0);
        LaunchCloud c = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, s0);
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30));
        c.feed(grad, 0.1, 0, 66.5, 0, cos, -sin, 0);
        double[] mass = new double[1];
        c.emit(s0 + 0.5, (x, y, z, ax, ay, az, sh, sv, soot, d, water, r, g, b, kelvin, rise) -> {
            if (soot == 0 && water == 0 && kelvin == CloudModel.T_AIR) mass[0] += d;
        });
        assertTrue(mass[0] > 0 && mass[0] <= (1 - CloudModel.CLOUD_SHARE) * c.dust() + 1e-12, "dust on the ground: " + mass[0]);
    }
}
