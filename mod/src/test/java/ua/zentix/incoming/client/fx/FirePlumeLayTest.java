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
 * Шлейф кусками трубки ({@link FireSmoke#lay}): сажа на метр оси сохраняет массу шлейфа (ṁ/u на метр пути по ветру —
 * и на крутом участке у очага), кусков всегда столько же (номер куска — зерно его клубов), а край дыма погасшего пожара
 * идёт по ветру ровно.
 */
class FirePlumeLayTest {
    /** Лужа топлива шахеда: ~2,2 МВт бензина на 2 м радиуса. */
    private static final FireSmoke.Fuel POOL = new FireSmoke.Fuel(2.2e6, Math.PI * 4, 0.04 / 43.4e6);

    /** Куски: концы (x, y, z), сажа на метр оси у концов. */
    private static List<double[]> lay(double u, double stability, double far, java.util.function.DoubleUnaryOperator blocks) {
        List<double[]> out = new ArrayList<>();
        FireSmoke.lay(POOL, 0, 0, 0, 0, 1, u, stability, far, 1, blocks, x -> 1, new SmokeGrid.Sink() {
            @Override
            public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                                double water, float dustR, float dustG, float dustB, double kelvin, double rise) {}

            @Override
            public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double bx, double by, double bz, double shb,
                             double svb, double sootB, double kelvin, double rise, double sigma0) {
                out.add(new double[]{ax, ay, az, bx, by, bz, sootA, sootB});
            }
        });
        return out;
    }

    @Test
    void sootAlongTheAxisKeepsTheMass() {
        // тихий вечер: у очага шлейф поднимается круто, ось куска в разы длиннее пути по ветру
        double u = 1, cls = Stability.F;
        FirePlume p = new FirePlume(POOL.watts(), 2, POOL.sootPerJoule(), u, cls);
        double far = 60, mass = 0;
        List<double[]> pieces = lay(u, cls, far, x -> 1);
        assertEquals(FireSmoke.SEGMENTS, pieces.size());
        for (double[] s : pieces) {
            double len = Math.sqrt(Math.pow(s[3] - s[0], 2) + Math.pow(s[4] - s[1], 2) + Math.pow(s[5] - s[2], 2));
            mass += 0.5 * (s[6] + s[7]) * len;
        }
        assertEquals(p.sootPerMetre() * far, mass, 0.02 * mass, "сажа шлейфа — ṁ/u на метр пути по ветру");
    }

    @Test
    void smokeOfAFireThatWentOutLeavesSmoothly() {
        double u = 3, cls = Stability.D, far = 600;
        double prev = -1;
        // погас 0…200 с назад: дым начинается в u·(время с конца) от очага; кусков — всегда столько же
        for (double ago = 0; ago < 200; ago += 0.5) {
            double gone = u * ago;
            List<double[]> pieces = lay(u, cls, far, x -> x < gone ? 0 : 1);
            assertEquals(FireSmoke.SEGMENTS, pieces.size(), "кусков всегда столько же");
            double start = Double.NaN;
            for (double[] s : pieces) {
                if (s[6] + s[7] > 0) {
                    start = s[6] > 0 ? s[2] : s[5];
                    break;
                }
            }
            if (Double.isNaN(start)) continue;
            assertEquals(gone, start, 0.01 * far, "край дыма — там, куда его унёс ветер, через " + ago + " с");
            if (prev >= 0) assertTrue(start >= prev - 1e-6, "край идёт только по ветру");
            prev = start;
        }
    }
}
