package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.BlastTarget;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.GroundDust;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Target for the explosion package ({@link FarBlasts#target}): a slab above the void - only according to the support sent by the server breach
 *  ({@code BreachGameTests}: the support of the package coincides with what the rays demolish), and not according to the client’s own rule; there is no support -
 *  according to the substance of the material.
 */
class BlastTargetSupportTest {
    private static final double SHAHED = 50;

    private static S2C.Blast blast(GroundMaterial m, Optional<S2C.Blast.Support> support) {
        return new S2C.Blast(S2C.Blast.DRONE, Vec3.ZERO, m.ordinal(), 0, 1, Optional.empty(), 0, S2C.Blast.DOWN, true, support);
    }

    @Test
    void slabComesFromTheServerBreach() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        BlastTarget t = FarBlasts.target(blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 1, (float) r))),
                GroundMaterial.WOOD, SHAHED, 0);
        assertEquals(BlastTarget.Family.SLAB, t.family());
        assertEquals(1, t.slab(), 1e-6);
        assertEquals(Math.sqrt(r * r - 1), t.hole(), 1e-5);
    }

    @Test
    void unbreachedSupportFollowsWhatWasHit() {
        // boards on the ground (support, not broken) - solid; soil - loose, although the material of the bag is not soil
        assertEquals(BlastTarget.Family.HARD, FarBlasts.target(blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 0, 0))),
                GroundMaterial.WOOD, SHAHED, 0).family());
        assertEquals(BlastTarget.Family.SOIL, FarBlasts.target(blast(GroundMaterial.DIRT, Optional.of(new S2C.Blast.Support(true, 0, 0))),
                GroundMaterial.DIRT, SHAHED, 0).family());
    }

    /** Without support in the package (repeat up to this field, explosion without server) - as before: according to the substance of the material, the support is solid. */
    @Test
    void noSupportFallsBackToTheMaterial() {
        assertEquals(BlastTarget.of(GroundMaterial.STONE, SHAHED, 0), FarBlasts.target(blast(GroundMaterial.STONE, Optional.empty()),
                GroundMaterial.STONE, SHAHED, 0));
        assertEquals(BlastTarget.Family.SOIL, FarBlasts.target(blast(GroundMaterial.DIRT, Optional.empty()), GroundMaterial.DIRT, SHAHED, 0).family());
    }

    /**
     * The support of the package is the contact found by the server: the family, mass and dust of the target do not depend on the height above the relief, which the
     *  client knows roughly in the distance (review of the 2 front F: Shahed by a meter of boards, tile {@code FarTerrain} without a house - land on 5 m
     *  lower, and the gap became a gap in the air: 0 kg instead of 3,6 t and 72 kg of dust). The real gap without support is in the air.
     */
    @Test
    void serverContactOutranksCoarseHeight() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        S2C.Blast roof = blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 1, (float) r, S2C.Blast.DOWN, 0)));
        BlastTarget exact = FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, 0);
        assertEquals(BlastTarget.Family.SLAB, exact.family());
        assertTrue(exact.mass(SHAHED) > 3000, "break of a meter of boards at Shahed - tons: " + exact.mass(SHAHED));
        BlastSource src = BlastSource.of(WeaponType.DRONE, false);
        double hot = new CloudModel(src, CloudModel.Air.STANDARD, exact, 0, GroundDust.Ground.BARE).dust;
        for (double hob : new double[]{5, 30, Double.NaN}) {
            BlastTarget far = FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, hob);
            assertEquals(exact, far, "height " + hob + " m: same goal");
            if (!Double.isNaN(hob)) assertEquals(hot, new CloudModel(src, CloudModel.Air.STANDARD, far, hob, GroundDust.Ground.BARE).dust,
                    1e-9, "height " + hob + " m: the hot dust of the break is the same (the cold dust would have been removed by contact in height)");
        }
        assertEquals(BlastTarget.Family.AIR, FarBlasts.target(blast(GroundMaterial.WOOD, Optional.empty()), GroundMaterial.WOOD, SHAHED, 30).family(),
                "without support high - gap in the air");
    }

    /** Average height of the billows layer at age t (by dust mass), m from the point of explosion; mass is in the second element. */
    private static double[] meanHeight(GroundDust d, double t) {
        double[] sum = new double[2];
        d.blobs(t, 1, (x, y, z, sh, sv, dust) -> {
            sum[0] += y * dust;
            sum[1] += dust;
        });
        return new double[]{sum[1] > 0 ? sum[0] / sum[1] : Double.NaN, sum[1]};
    }

    /**
     * The dust skirt of the breach is born at the edge that the server found - at the point of the explosion, and not at the rough height of the ground in the distance
     *  (review of 3 front F: at a height of 30 m, its billows in a second were 30 m below the roof); the dust of the wave keeps the relief as it is.
     */
    @Test
    void crushedDustStartsAtTheFace() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        S2C.Blast roof = blast(GroundMaterial.WOOD, Optional.of(new S2C.Blast.Support(false, 1, (float) r, S2C.Blast.DOWN, 0)));
        BlastSource src = BlastSource.of(WeaponType.DRONE, false);
        CloudModel exact = new CloudModel(src, CloudModel.Air.STANDARD, FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, 0), 0,
                GroundDust.Ground.BARE);
        for (double t : new double[]{0.3, 1}) {
            double[] at0 = meanHeight(exact.crushed, t);
            assertTrue(at0[1] > 0, t + " s: no cold breach dust");
            for (double hob : new double[]{5, 30}) {
                CloudModel far = new CloudModel(src, CloudModel.Air.STANDARD, FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, hob), hob,
                        GroundDust.Ground.BARE);
                double[] at = meanHeight(far.crushed, t);
                assertEquals(at0[0], at[0], 0.5, "height " + hob + " m, " + t + " s: dust from the breach - near the roof");
            }
        }
        // dust waves - from the surface around: when the ground is 5 m below, it is there, and not at the roof
        double[] wave0 = meanHeight(exact.ground, 1);
        double[] wave5 = meanHeight(new CloudModel(src, CloudModel.Air.STANDARD, FarBlasts.target(roof, GroundMaterial.WOOD, SHAHED, 5), 5,
                GroundDust.Ground.BARE).ground, 1);
        assertTrue(wave0[1] > 0 && wave5[1] > 0, "dust waves are there");
        assertTrue(wave5[0] < wave0[0] - 3, "the dust of the waves holds the relief: " + wave0[0] + " and " + wave5[0] + " m");
    }

    @Test
    void spallLeavesThroughTheServerHole() {
        double r = BreachModel.Material.POOR.radius(SHAHED, BreachModel.UNTAMPED);
        Object[][] cases = {
                // wall a meter high, side impact in the middle of the block: edge - x = 0, charge on it, normal +x
                {new Vec3(0.5, 66.5, 0.5), new Vec3(1, -0.5, 0.2), new Vec3(1, 0, 0), 0.5f},
                // roof, impact under 37° from vertical with nose on top: normal down
                {new Vec3(0.5, 70, 0.5), new Vec3(0.6, -0.8, 0), new Vec3(0, -1, 0), 0f},
        };
        java.util.Random random = new java.util.Random(7);
        for (Object[] c : cases) {
            Vec3 pos = (Vec3) c[0], axis = (Vec3) c[1], normal = (Vec3) c[2];
            S2C.Blast p = new S2C.Blast(S2C.Blast.DRONE, pos, GroundMaterial.WOOD.ordinal(), 0, 1, Optional.empty(), 0, axis, true,
                    Optional.of(new S2C.Blast.Support(false, 1, (float) r, normal, (float) c[3])));
            BlastTarget t = FarBlasts.target(p, GroundMaterial.WOOD, SHAHED, 0);
            assertEquals(BlastTarget.Family.SLAB, t.family());
            Vec3 face = t.face();
            assertEquals(0, pos.subtract(normal.scale((float) c[3])).distanceTo(face), 1e-6, "charge is on the verge");
            double cone = Math.cos(Math.toRadians(BlastTarget.SPALL_CONE_DEG)) - 1e-9;
            t.spall(SHAHED, 200, random::nextDouble, (at, v) -> {
                Vec3 off = at.subtract(face);
                double along = off.dot(normal);
                assertTrue(along >= t.slab() - 1e-9, "the piece is not behind the stove: " + along + " with thickness " + t.slab());
                assertTrue(off.subtract(normal.scale(along)).length() <= t.hole() + 1e-9, "piece outside the hole");
                assertTrue(v.normalize().dot(normal) >= cone, "piece outside the spall cone: " + v);
            });
            new ua.zentix.incoming.client.fx.smoke.Ejecta(t.hole(), 0, 0).pieces(face, normal.scale(-1), 200, random::nextDouble,
                    (at, v) -> assertTrue(v.dot(normal) < 0 && at.subtract(face).dot(normal) < 0, "face crumb - outward from the edge"));
        }
    }
}
