package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.LocalWind;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    /** Frozen samples from the eager implementation at 341c2ea6f, not a second copy of the solver. */
    @Test
    void shelteredShearRetainsRiseDriftAndTableBoundaries() {
        var thermal = new Thermal(Thermal.buoyancy(1e9), 2, 1, shelteredAir());
        assertEquals(314.15, thermal.topSeconds, 1e-10);
        // age, height, radius, x drift, z drift; apex 314.15 s, final table node 314.20 s.
        double[][] samples = {
                {.025, 7.445091167831677, 14.888038739581193, .02329824867102042, -.017473686503265314},
                {.05, 7.446341078083389, 14.888394964002455, .04659649734204084, -.03494737300653063},
                {314.125, 343.8504878852337, 110.76357680279563, 2340.2557306059734, -1755.191797954474},
                {314.15, 343.85049204544754, 110.76357798845657, 2340.4586268144817, -1755.3439701108553},
                {314.175, 343.85049204544754, 110.76357798845657, 2340.6615230229904, -1755.4961422672368},
                {314.2, 343.85049204544754, 110.76357798845657, 2340.864419231499, -1755.6483144236183},
                {314.225, 343.85049204544754, 110.76357798845657, 2341.067315440008, -1755.8004865799996}
        };
        for (int i : new int[]{0, 1, 2, 3, 4, 5, 6, 0, 4, 2}) {
            double[] row = samples[i];
            double t = row[0];
            assertArrayEquals(row, new double[]{t, thermal.height(t), thermal.radius(t),
                    thermal.driftX(t), thermal.driftZ(t)}, 1e-8, "rise/drift at " + t);
        }
    }

    @Test
    void cloudShearRetainsEagerMidpointIntegralAndLastNode() {
        var cloud = new CloudModel(BlastSource.of(WeaponType.DRONE, false), shelteredAir());
        // Eager 0.5 s midpoint integral at 341c2ea6f; includes interpolation and saturation beyond its last node.
        double[][] samples = {{.25, .07731091024233902}, {.5, .15462182048467804},
                {.75, .2610780657746321}, {30, 13.329494828586146},
                {3600.25, 5109.520422393245}, {3600.5, 5110.18916115787}, {3600.75, 5110.18916115787}};
        for (int i : new int[]{0, 1, 2, 3, 4, 5, 6, 0, 3, 2}) {
            assertEquals(samples[i][1], cloud.shear(samples[i][0]), 1e-8, "shear at " + samples[i][0]);
        }
    }

    @Test
    void streetEmissionRetainsReferencePositionsAcrossBackwardAgeRequest() {
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(0), GroundDust.Ground.filled(0), (x, z) -> Math.abs(x) % 30 < 8 ? 0 : 20);
        var dust = GroundDust.skirt(150, 0, 1000, ground, shelteredAir());
        // Baseline 341c2ea6f: count, then index-weighted x/y/z, horizontal/vertical width and extinction mass.
        // Ordered moments exercise all 24 clumps without storing an entire reference implementation in CI.
        double[] early = {24, 93.96697120363388, 1020.254212495768, -804.5176478102983,
                1342.5059173567042, 1275.31776561971, 18118.72192713457};
        double[] late = {24, 486.075663173261, 1104.6254604219735, -3229.9704615442633,
                3713.2121720790315, 1380.7818255274667, 16855.654055814593};
        assertArrayEquals(early, streetMoments(dust, 1), 1e-7);
        assertArrayEquals(late, streetMoments(dust, 30), 1e-7);
        assertArrayEquals(early, streetMoments(dust, 1), 1e-7, "rewind after terrain paths have advanced");
        assertArrayEquals(late, streetMoments(dust, 30), 1e-7, "advance again after rewind");
    }

    private static CloudModel.Air shelteredAir() {
        return new CloudModel.Air(.01, 4, -3, .2, 3, .5, new LocalWind(0, 0, 20, 10, 1, 2, .1, 80));
    }

    private static double[] streetMoments(GroundDust dust, double t) {
        double[] sum = new double[7];
        dust.blobs(t, 1, (x, y, z, sh, sv, mass) -> {
            double index = ++sum[0];
            sum[1] += index * x; sum[2] += index * y; sum[3] += index * z;
            sum[4] += index * sh; sum[5] += index * sv; sum[6] += index * mass;
        });
        return sum;
    }

}
