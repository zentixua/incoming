package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.DustWallPass;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.FireballModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The young dust wall's physical inputs ({@link PallField#young}, read by {@code dustwall.glsl} linearly between nodes): the
 * wall starts where the game front is, wherever between nodes where dust can lie (its rise e-fold is 0.12 s), and no dust
 * where the fireball touches the ground.
 */
class DustWallInputsTest {
    private static final int N = DustWallPass.NODES;

    private static Detonation detonation(double hob, double kt, float scale) {
        return new Detonation(1, new Vec3(0, 64 + hob, 0), 64, kt, hob == 0, 0, 0, 5, 20_000, 7, scale, false, 0.35f);
    }

    /** {@code dwNode} (same reading): component c of the table at ground radius r, blocks. */
    private static double node(float[] table, double reach, double r, int c) {
        double x = Math.min(r / reach, 1) * (N - 1);
        int i = (int) Math.min(Math.floor(x), N - 2);
        return table[4 * i + c] + (x - i) * (table[4 * (i + 1) + c] - table[4 * i + c]);
    }

    @Test
    void wallStartsWithTheFrontBetweenNodes() {
        for (Detonation d : new Detonation[] {detonation(0, 15, 1), detonation(500, 15, 1), detonation(0, 1000, 1), detonation(0, 15, 0.005f)}) {
            PallField field = new PallField(d);
            float[] table = new float[N * 4];
            field.young(30, 1, DustWallPass.SPAN, N, table);
            double reach = field.reach(), worst = 0, at = 0, ball = 0;
            // where dust can lie: beyond the widest ground footprint of the fireball (no dust under it, PallField.young)
            for (double t = 0.01; t < 20; t += 0.01)
                ball = Math.max(ball, FireballModel.groundRadius(t, Math.max(0, d.hobMetres()), d.yieldKt(), d.surface()));
            for (int i = 1; i <= 4000; i++) {
                double r = reach * i / 4000, slant = Math.hypot(d.metres(r), Math.max(0, d.hobMetres()));
                if (d.metres(r) <= ball) continue;
                double arrival = d.arrivalTicks(d.blocks(slant)) / (20 * d.scale());
                double e = Math.abs(node(table, reach, r, 0) - arrival);
                if (e > worst) {
                    worst = e;
                    at = r;
                }
            }
            assertTrue(worst < 0.03, "arrival off by " + worst + " s between nodes at " + at + " blocks of " + reach + ", " + d.yieldKt() + " kt, HOB " + d.hobMetres());
        }
    }

    @Test
    void noDustWhereTheFireballTouchesTheGround() {
        Detonation d = detonation(0, 15, 1);
        PallField field = new PallField(d);
        float[] table = new float[N * 4];
        double t = 0.45, ball = FireballModel.groundRadius(t, 0, 15, true);
        assertTrue(ball > 100, "the 15 kt ball touches the ground at 0.45 s: " + ball);
        field.young(t, 1, DustWallPass.SPAN, N, table);
        int under = 0;
        for (int j = 0; j < N; j++) {
            if (d.metres(j / (N - 1.0) * field.reach()) < ball) {
                assertEquals(0f, table[4 * j + 2], "dust under the ball at node " + j);
                under++;
            }
        }
        assertTrue(under > 0, "nodes under the ball");
    }
}
