package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review #716 probe (Codex): the veil's matter split moves continuously and keeps its slopes. */
final class VeilTransitionTest {
    private static final int CENTER = SmokePuffs.Veil.index(SmokePuffs.Veil.N / 2, SmokePuffs.Veil.N / 2);

    @Test
    void walkingOneCentimeterDoesNotJumpTheContrastOfADistantWall() {
        // Eye-local haze plus a budget-culled clump, with a wall 20 m away between them.
        FarView view = SmokeFillTest.view(new Vec3(0, 100, 0), new Vector3f(0, 0, 1));
        double[] a = atWall(view, -1.47, 20.00);
        double[] b = atWall(view, -1.46, 20.01);
        String result = "wall T " + a[0] + " -> " + b[0] + ", integrated T " + a[1] + " -> " + b[1]
                + ", fitted center " + a[2] + " -> " + b[2];
        System.out.println(result);
        assertTrue(Math.abs(a[0] - b[0]) < 0.05, result);
    }

    private static double[] atWall(FarView view, double offset, double wall) {
        double localExt = 0.4 * 2 * Math.PI * 25;
        double remoteExt = 3 * 2 * Math.PI * 16;
        double[] packed = {0, 0, offset, 5, 5, localExt, 0.5, 0.5, 0.5, 1,
                           0, 0, 40 + offset, 4, 4, remoteExt, 0.5, 0.5, 0.5, 1};
        SmokePuffs.Veil veil = new SmokePuffs.Veil();
        veil.fill(view, packed, 2);
        double live = 1 - veil.before(CENTER, wall) * SmokePuffs.Veil.byteAlpha(veil.alpha(CENTER));
        double opticalDepth = 0;
        for (int i = 0; i < packed.length; i += SmokePuffs.VEIL_FIELDS) {
            double m = packed[i + 2], w = packed[i + 3], ext = packed[i + 5];
            for (double x = 0.0025; x < wall; x += 0.005) {
                opticalDepth += ext / (Math.pow(2 * Math.PI, 1.5) * w * w * w)
                        * Math.exp(-0.5 * Math.pow((x - m) / w, 2)) * 0.005;
            }
        }
        return new double[]{live, Math.exp(-opticalDepth), veil.middle[CENTER]};
    }
}
