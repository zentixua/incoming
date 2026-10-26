package ua.zentix.incoming.client.fx;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.util.Stability;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * trail with pieces of pipe ({@link FireSmoke#lay}): soot per meter of the axle preserves the mass of the trail (ṁ/u per meter of path downwind -
 *  and on a steep section near the fireplace), there are always the same number of pieces (the number of the piece is the seed of its billows), and the edge of the smoke of an extinguished fire
 *  goes straight downwind.
 */
class FirePlumeLayTest {
    /** Fuel puddle Shahed: ~2,2 MW of gasoline at 2 m radius. */
    private static final FireSmoke.Fuel POOL = new FireSmoke.Fuel(2.2e6, Math.PI * 4, 0.04 / 43.4e6);

    /** Pieces: ends (x, y, z), soot per meter of axle at the ends. */
    private static List<double[]> lay(double u, double stability, double far, java.util.function.DoubleUnaryOperator blocks) {
        List<double[]> out = new ArrayList<>();
        FireSmoke.lay(POOL, 0, 0, 0, 0, 1, u, stability, far, 1, blocks, x -> 1, new SmokeGrid.Sink() {
            @Override
            public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                                double water, float dustR, float dustG, float dustB, double kelvin, double rise) {}

            @Override
            public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                     double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin, double rise,
                     double sigma0) {
                out.add(new double[]{ax, ay, az, bx, by, bz, sootA, sootB});
            }
        });
        return out;
    }

    @Test
    void sootAlongTheAxisKeepsTheMass() {
        // quiet evening: near the fireplace the trail rises steeply, the axis of the piece is several times longer than the path downwind
        double u = 1, cls = Stability.F;
        FirePlume p = new FirePlume(POOL.watts(), 2, POOL.sootPerJoule(), u, cls);
        double far = 60, mass = 0;
        List<double[]> pieces = lay(u, cls, far, x -> 1);
        assertEquals(FireSmoke.SEGMENTS, pieces.size());
        for (double[] s : pieces) {
            double len = Math.sqrt(Math.pow(s[3] - s[0], 2) + Math.pow(s[4] - s[1], 2) + Math.pow(s[5] - s[2], 2));
            mass += 0.5 * (s[6] + s[7]) * len;
        }
        assertEquals(p.sootPerMetre() * far, mass, 0.02 * mass, "soot trail - ṁ/u per meter of path downwind");
    }

    @Test
    void smokeOfAFireThatWentOutLeavesSmoothly() {
        double u = 3, cls = Stability.D, far = 600;
        double prev = -1;
        // went out 0...200 s back: smoke starts at u (time from the end) from the hearth; pieces - always the same
        for (double ago = 0; ago < 200; ago += 0.5) {
            double gone = u * ago;
            List<double[]> pieces = lay(u, cls, far, x -> x < gone ? 0 : 1);
            assertEquals(FireSmoke.SEGMENTS, pieces.size(), "always the same number of pieces");
            double start = Double.NaN;
            for (double[] s : pieces) {
                if (s[6] + s[7] > 0) {
                    start = s[6] > 0 ? s[2] : s[5];
                    break;
                }
            }
            if (Double.isNaN(start)) continue;
            assertEquals(gone, start, 0.01 * far, "the edge of the smoke - where the wind carried it, through " + ago + " s");
            if (prev >= 0) assertTrue(start >= prev - 1e-6, "the edge goes only with the wind");
            prev = start;
        }
    }
}
