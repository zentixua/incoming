package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VeilDepthTest {
    @Test
    void distantMatterSortsBetweenNearAndFarClubs() {
        SmokePuffs.Veil veil = veil(80, 6, 2);
        int centre = SmokePuffs.Veil.N / 2;
        float depth = veil.depth(centre, centre);

        // The mesh stays near the eye for scene-depth sampling. Its colour must blend at the smoke,
        // behind a club at 16 m and in front of one at 315 m; a fixed key of 2 erased the near texture.
        assertEquals(SmokePuffs.VEIL_AT, veil.place(centre, centre, 2), 1e-6);
        assertTrue(depth > 60 && depth < 90, "sort depth = " + depth);
        assertEquals(depth, veil.copy().depth(centre, centre));
    }

    @Test
    void denseMatterAroundTheEyeKeepsTheNearOrder() {
        SmokePuffs.Veil veil = veil(0, 5, 20);
        int centre = SmokePuffs.Veil.N / 2;
        assertEquals(SmokePuffs.VEIL_AT, veil.depth(centre, centre), 1e-6);
    }

    @Test
    void samplerUsesTheMeshTriangleDiagonal() {
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        veil.extent = 1;
        int i = 3, j = 4;
        veil.tau[SmokePuffs.Veil.index(i + 1, j)] = Math.log(2);
        veil.tau[SmokePuffs.Veil.index(i, j + 1)] = Math.log(2);
        double u = 0.5 * (veil.at(i) + veil.at(i + 1)), v = 0.5 * (veil.at(j) + veil.at(j + 1));
        // Both ends of the a–c diagonal are clear; the other two corners cannot cover its midpoint.
        assertEquals(1, veil.through(u, v), 1e-6);
    }

    private static SmokePuffs.Veil veil(double z, double sigma, double tau) {
        FarView view = SmokeFillTest.view(Vec3.ZERO, new Vector3f(0, 0, 1));
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        double ext = tau * 2 * Math.PI * sigma * sigma;
        veil.fill(view, new double[]{0, 0, z, sigma, sigma, ext, 0.4, 0.3, 0.2, 1}, 1);
        return veil;
    }
}
