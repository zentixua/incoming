package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Construction must not walk future streets or integrate future wind before an impact can be shown. */
class CloudBuildTest {
    @Test
    void lifetimeNeverWalksFutureTerrain() {
        int[] reads = {0};
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), (x, z) -> {
                    reads[0]++;
                    return Math.abs(x) % 30 < 8 ? 0 : 20;
                });
        var air = new CloudModel.Air(.01, 4, -3, .2, 3, .5);
        var dust = GroundDust.skirt(150, 0, 1000, ground, air);
        // The initial floor and channel footprint is finite. The old lifetime search also traced
        // streets all the way to the one-hour horizon before returning from this constructor.
        assertTrue(reads[0] < 15_000, "initial footprint only; terrain reads: " + reads[0]);
        assertTrue(dust.life > 1);
        int initial = reads[0];
        for (double t : new double[]{.01, 1, 10, 300, 1800, 3600}) dust.tau(t);
        assertEquals(initial, reads[0], "extinction needs mass and widths, never terrain or positions");
    }

    @Test
    void sharedRiseKeepsEachWindAndSupportsSeeking() {
        var east = new CloudModel.Air(.01, 3, 0, 0, 3, .5);
        var north = new CloudModel.Air(.01, 0, -7, 0, 3, .5);
        double buoyancy = Thermal.buoyancy(1e9);
        var a = new Thermal(buoyancy, 2, 1, east);
        var b = new Thermal(buoyancy, 2, 1, north);
        // Same rise table, independent wind histories; old ages stay usable after a replay seek.
        for (double t : new double[]{0, .02, 1, 40, 200, 3600, .05, 25, 600}) {
            assertEquals(a.height(t), b.height(t), 0);
            assertEquals(3 * t, a.driftX(t), 1e-8);
            assertEquals(0, a.driftZ(t), 0);
            assertEquals(0, b.driftX(t), 0);
            assertEquals(-7 * t, b.driftZ(t), 1e-8);
        }
    }

    @Test
    void supportDoesNotDependOnRequestOrder() {
        var air = new CloudModel.Air(.01, 4, -3, .2, 3, .5);
        var sequential = new GroundDust(150, 0, 1, air);
        var seek = new GroundDust(150, 0, 1, air);
        seek.bounds(3600, -64, 320, new SmokeBounds());
        for (double t : new double[]{.001, .1, 1, 3, 10, 50, 300, 1800}) {
            var a = new SmokeBounds();
            var b = new SmokeBounds();
            sequential.bounds(t, -64, 320, a);
            seek.bounds(t, -64, 320, b);
            assertEquals(b.minX, a.minX, 0);
            assertEquals(b.maxX, a.maxX, 0);
            assertEquals(b.minY, a.minY, 0);
            assertEquals(b.maxY, a.maxY, 0);
            assertEquals(b.minZ, a.minZ, 0);
            assertEquals(b.maxZ, a.maxZ, 0);
        }
    }
}
