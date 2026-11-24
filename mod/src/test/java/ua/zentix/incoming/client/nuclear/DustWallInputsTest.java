package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.DustWallPass;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.FireballModel;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dust wave's table row 0 ({@link PallField#lifetime}, read by {@code dustwall.glsl} linearly between nodes): each ground point's
 * dust is born when the game front reaches it, wherever between nodes where dust can lie (its rise e-fold is 0.12 s).
 */
class DustWallInputsTest {
    private static final int N = DustWallPass.NODES;

    private static Detonation detonation(double hob, double kt, float scale) {
        return new Detonation(1, new Vec3(0, 64 + hob, 0), 64, kt, hob == 0, 0, 0, 5, 20_000, 7, scale, false, 0.35f);
    }

    /** {@code dwRow0} (same reading): component c of row 0 at ground radius r, blocks. */
    private static double node(float[] table, double reach, double r, int c) {
        double x = Math.min(r / reach, 1) * (N - 1);
        int i = (int) Math.min(Math.floor(x), N - 2);
        return table[4 * i + c] + (x - i) * (table[4 * (i + 1) + c] - table[4 * i + c]);
    }

    @Test
    void dustIsBornWithTheFrontBetweenNodes() {
        for (Detonation d : new Detonation[] {detonation(0, 15, 1), detonation(500, 15, 1), detonation(0, 1000, 1), detonation(0, 15, 0.005f)}) {
            PallField field = new PallField(d);
            float[] table = field.waveTableNow();
            double reach = field.reach(), worst = 0, at = 0, ball = 0;
            // where dust can lie: beyond the widest ground footprint of the fireball
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
}
