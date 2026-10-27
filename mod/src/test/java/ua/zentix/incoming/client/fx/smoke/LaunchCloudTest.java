package ua.zentix.incoming.client.fx.smoke;

import ua.zentix.incoming.util.Stability;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.plume.Motor;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Start cloud: when the products are a cloud (Morton length), how many there are, and the rise is a thermal MTT. */
class LaunchCloudTest {
    private static Motor motor(WeaponType w) {
        return ClientWeaponSpec.of(w).airframe(false).far().stage(FlightPhase.BOOST).motor();
    }

    /**
     * Morton length M^¾/F^½ from engine spec: for “Grad” (10,5 kg/s, 2 000 m/s, ~50 MW in air) - ~40 m, at the
     *  Shahed accelerator - ~24 m, at the rocket accelerator - ~41 m, at the first stage of the ICBM - ~100 m.
     */
    @Test
    void mortonLengthFromThePassport() {
        Motor grad = motor(WeaponType.ROCKET);
        double m = grad.exhaust() * grad.velocity() / 1.225, f = 9.80665 * grad.heatFlow() / (1.225 * 1005 * 288.15);
        assertEquals(Math.pow(m, 0.75) / Math.sqrt(f), LaunchCloud.mortonLength(grad), 1e-9);
        assertEquals(40, LaunchCloud.mortonLength(grad), 3);
        assertEquals(24, LaunchCloud.mortonLength(motor(WeaponType.DRONE)), 3);
        assertEquals(41, LaunchCloud.mortonLength(motor(WeaponType.MISSILE)), 4);
        assertEquals(104, LaunchCloud.mortonLength(motor(WeaponType.NUKE)), 10);
    }

    /**
     * The jet touches the ground half-width: vertically downward - at the height of the nozzle, sideways - at the height / 0,094, upwards - never; at the nozzle
     *  at 2,5 m above the ground under 30° “Grad” gives its products to the cloud until it flies ~35 m.
     */
    @Test
    void jetHitsTheGroundWithinItsMortonLength() {
        assertEquals(10, LaunchCloud.reach(10, -1), 1e-9);
        assertEquals(10 / LaunchCloud.SPREAD, LaunchCloud.reach(10, 0), 1e-6);
        assertEquals(Double.POSITIVE_INFINITY, LaunchCloud.reach(10, 0.5));
        Motor grad = motor(WeaponType.ROCKET);
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30));
        double fed = -1;
        for (double s = 0; s < 200; s += 0.5) {
            if (!LaunchCloud.impinges(grad, 2.5 + s * sin, -sin)) {
                fed = s;
                break;
            }
        }
        double l0 = 2.5 / (sin + LaunchCloud.SPREAD * cos), k = sin + LaunchCloud.SPREAD * cos;
        // ℓ = (2,5 + s·sin)/k ≤ L_M
        assertEquals((LaunchCloud.mortonLength(grad) * k - 2.5) / sin, fed, 0.6);
        assertTrue(fed > 20 && fed < 50, "presentation path " + fed + " (ℓ at the pipe " + l0 + ")");
        // shahed from the rail at 2 m under 15° - half the accelerator path
        assertTrue(LaunchCloud.impinges(motor(WeaponType.DRONE), 2, -Math.sin(Math.toRadians(15))));
        assertFalse(LaunchCloud.impinges(motor(WeaponType.DRONE), 20, -Math.sin(Math.toRadians(15))));
    }

    /**
     * While the jet hits the ground, its products are a cloud near the ground, but between the nozzle and the ground they are carried by the jet itself: under the rocket a
     *  column is visible to the cloud (launch frame from behind, F4: in 3.5.x on 1,5 s under the rocket it was empty - all points of the trail before ~38 m heights
     *  were given over entirely to the cloud). Column - substance per meter ṁ/u(x), u(x) = u₀·B·d′/x: does not depend on how quickly
     * the rocket was moving when it was at this point, and behind the flame, up to Morton’s length, it can be seen.
     */
    @Test
    void jetColumnStandsBetweenNozzleAndGround() {
        var stage = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false).far().stage(FlightPhase.BOOST);
        Motor m = stage.motor();
        var trail = stage.trail();
        double flame = stage.flame(ua.zentix.incoming.util.Air.SEA).flame(), morton = LaunchCloud.mortonLength(m);
        double[] look = new double[ua.zentix.incoming.client.fx.plume.TrailModel.LOOK];
        for (double v : new double[]{3, 10, 30}) {
            double inv = ua.zentix.incoming.client.fx.plume.TrailModel.inverseSpeed(v, 0);
            for (double x = 1.5 * flame; x <= morton; x += 1) {
                double share = LaunchCloud.column(m, x, inv);
                assertTrue(share > 0 && share <= 1, "share " + share);
                // substance per meter of column - from the jet, and not from the speed of the rocket, as long as the column is less than the required trace
                if (share < 1) assertEquals(m.velocity() * LaunchCloud.JET_DECAY * LaunchCloud.diameter(m) / x, 1 / (share * inv), 1e-6 * x + 1e-6);
                ua.zentix.incoming.client.fx.plume.TrailModel.look(trail, 0.5, inv, 0, 0, 0, 0, 0.01, Stability.D, share, look);
                assertTrue(look[1] > 0.3, "pillar in " + x + " m behind the nozzle (rocket " + v + " m/s) visible: opacity " + look[1]);
            }
        }
        // at the nozzle the jet is faster than any projectile: the column there is less frequent than the trace
        assertTrue(LaunchCloud.column(m, 1, ua.zentix.incoming.client.fx.plume.TrailModel.inverseSpeed(30, 0)) < 0.05);
    }

    /**
     * presentation: heat - engine heat in the air × time, soot and drops - the same thickness as food, dust - looseness;
     *  the jet does not blow past the ground.
     */
    @Test
    void feedTakesTheProductsOfTheBurn() {
        Motor grad = motor(WeaponType.ROCKET);
        LaunchCloud c = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, 0);
        double sin = Math.sin(Math.toRadians(30)), cos = Math.cos(Math.toRadians(30));
        assertTrue(c.feed(grad, 0.05, 0, 66.5, 0, -cos, -sin, 0));
        assertTrue(c.feed(grad, 0.05, 1, 67, 0, -cos, -sin, 0));
        assertFalse(c.feed(grad, 0.05, 0, 66.5, 0, 0, 1, 0), "jet up");
        assertEquals(grad.heatFlow() * 0.1, c.heat(), 1e-6 * c.heat());
        double ext = grad.smoke().extinction * grad.smokeFlow() * 0.1;
        assertEquals(ext, CloudModel.SOOT_K * c.soot() + CloudModel.DROP_K * c.water(), 1e-9 * ext);
        assertEquals(LaunchCloud.DUST_PER_KG * grad.exhaust() * 0.1, c.dust(), 1e-9);
        LaunchCloud stone = new LaunchCloud(CloudModel.Air.STANDARD, 64, 0, 0.5f, 0.5f, 0.5f, 0);
        stone.feed(grad, 0.05, 0, 66.5, 0, -cos, -sin, 0);
        assertEquals(0, stone.dust(), 0, "the stone does not generate dust");
        assertTrue(c.life() > 10, "the cloud of the salvo is visible " + c.life() + " s");
    }

    /**
     * Cloud thermal - MTT: at a point source the top of the center (8F/(α³N²))^¼ with F = 3B/(4π, for ~π/N; from the small initial
     *  radius (the ball where the heat is held 600 K: 6 m versus 150 m rise) - within 15 % (the initial thermal without impulse
     *  does not reach self-similar rise immediately); at night (N is larger) - lower as N^−½.
     */
    @Test
    void cloudRisesAsAnMttThermal() {
        double heat = 2e8, b = Thermal.buoyancy(heat), f = 3 * b / (4 * Math.PI), a = CloudModel.ALPHA;
        for (double n : new double[]{CloudModel.N_STANDARD, Stability.brunt(Stability.F)}) {
            CloudModel.Air air = new CloudModel.Air(n, 0, 0, 0.14, 3, 0.5);
            Thermal t = new Thermal(b, CloudModel.G * (1 - CloudModel.T_AIR / 600), 0.5, air);
            double top = Math.pow(8 * f / (a * a * a * n * n), 0.25);
            assertEquals(top, t.topZ, 0.15 * top, "N = " + n);
            assertEquals(a * top, t.topR, 0.25 * a * top);
            assertEquals(Math.PI / n, t.topSeconds, 0.35 * Math.PI / n);
        }
    }
    /**
     * The rebuild after the feed ends integrates the whole rise (~1 ms of one thread) and cannot yield: it happens in the
     * client tick ({@link LaunchCloud#settle}), never in {@link LaunchCloud#emit}, which runs within the shared 2 ms
     * preparation budget (review of #509: eight clouds whose feed had just ended took 2.6–2.8 ms there). The feed ends
     * below the 1.25 rebuild step, so the last fed heat still waits for that rebuild.
     */
    @Test
    void emitNeverRebuildsTheThermalTheTickDoes() {
        Motor grad = motor(WeaponType.ROCKET);
        double s0 = SmokeGrid.seconds(0);
        LaunchCloud c = new LaunchCloud(CloudModel.Air.STANDARD, 64, 1, 0.4f, 0.3f, 0.2f, s0);
        assertTrue(c.feed(grad, 0.10, s0, 0, 66.5, 0, 0, -1, 0));
        assertTrue(c.feed(grad, 0.01, s0 + 0.05, 0, 66.5, 0, 0, -1, 0));
        Thermal fed = c.thermal();
        c.settle(s0 + 0.05);
        assertSame(fed, c.thermal(), "the feed has not ended yet");
        assertTrue(c.emit(s0 + 0.3, (x, y, z, ax, ay, az, sh, sv, soot, d, w, r, g, b, k, rise) -> {}));
        assertSame(fed, c.thermal(), "emit rebuilt the thermal inside the preparation budget");
        c.settle(s0 + 0.3);
        assertNotSame(fed, c.thermal(), "the tick takes all heat once the feed has ended");
        Thermal all = c.thermal();
        c.settle(s0 + 0.4);
        assertSame(all, c.thermal(), "one rebuild for the heat fed");
    }
}
