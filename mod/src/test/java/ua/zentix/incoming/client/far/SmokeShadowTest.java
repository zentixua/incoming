package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud's own shadow ({@link SmokePuffs#sunThrough}): every puff of a black cloud was lit as if alone in the sky, and
 * the underside and core of the cloud came out as bright as its top (sandbox missile cloud with a side sun).
 */
class SmokeShadowTest {
    /** Puff record at (x, y, z) with σ and soot mass, as {@code Draw.hold} writes it. */
    private static void puff(double[] held, int i, double x, double y, double z, double sigma, double soot) {
        int o = i * SmokePuffs.FIELDS;
        held[o] = x;
        held[o + 1] = y;
        held[o + 2] = z;
        held[o + 3] = sigma;
        held[o + 4] = sigma;
        held[o + 5] = soot;
        held[o + 8] = held[o + 9] = held[o + 10] = 0.9;
        held[o + 13] = 1;
        held[o + 17] = 1;
    }

    private static double through(double[] held, int i) {
        return held[i * SmokePuffs.FIELDS + 17];
    }

    @Test
    void theUndersideIsInTheShadowOfTheTop() {
        double[] held = new double[3 * SmokePuffs.FIELDS];
        // a column of three soot puffs, σ 5 m, 10 m apart; each puff τ ≈ 3 across its middle
        double soot = 3 * 2 * Math.PI * 25 / SmokeGrid.SOOT_K;
        for (int i = 0; i < 3; i++) puff(held, i, 0, 10 * i, 0, 5, soot);
        SmokePuffs.sunThrough(held, 0, 3, new boolean[3], 0, 1, 0, 0.8f);
        // sun overhead: the top puff is open (only the upper tails of the ones below), the lower ones lie under the others
        assertTrue(through(held, 2) > 0.9, "top " + through(held, 2));
        assertTrue(through(held, 1) < 0.3, "middle " + through(held, 1));
        assertTrue(through(held, 0) < through(held, 1), "bottom " + through(held, 0));
        double overhead = through(held, 1);
        // the same column with a low sun from the side: only the neighbours' edges (2σ off the ray) are in front,
        // τ = 3·e^(−2)/2 from each
        for (int i = 0; i < 3; i++) puff(held, i, 0, 10 * i, 0, 5, soot);
        SmokePuffs.sunThrough(held, 0, 3, new boolean[3], 1, 0, 0, 0.8f);
        assertEquals(Math.exp(-2 * 1.5 * Math.exp(-2)), through(held, 1), 0.03);
        assertTrue(through(held, 1) > 2 * overhead, "side " + through(held, 1) + " overhead " + overhead);
    }

    @Test
    void mergedPuffsAndNoSunCastNoShadow() {
        double[] held = new double[2 * SmokePuffs.FIELDS];
        double soot = 3 * 2 * Math.PI * 25 / SmokeGrid.SOOT_K;
        puff(held, 0, 0, 0, 0, 5, soot);
        puff(held, 1, 0, 10, 0, 5, soot);
        SmokePuffs.sunThrough(held, 0, 2, new boolean[]{false, true}, 0, 1, 0, 0.8f);
        assertEquals(1, through(held, 0), 1e-9);
        puff(held, 1, 0, 10, 0, 5, soot);
        SmokePuffs.sunThrough(held, 0, 2, new boolean[2], 0, 1, 0, 0);
        assertEquals(1, through(held, 0), 1e-9);
    }

    @Test
    void theShadowTakesOnlyTheDirectLight() {
        assertEquals(1, SmokePuffs.sunLight(0.8, 1), 1e-12);
        // in the full shadow the sky's part stays
        assertEquals(0.2, SmokePuffs.sunLight(0.8, 0), 1e-12);
        assertEquals(1, SmokePuffs.sunLight(0, 0), 1e-12);
        assertEquals(0.6, SmokePuffs.sunLight(0.8, 0.5), 1e-12);
    }

    /**
     * A clump that is still the fire body (smoke share → 0) shades its neighbours by its share, as it shows as smoke: at
     * the handoff the shadow fades in, not at full thickness at once (review #689: τ = 10 at share 0.01 passed 0.0045 %).
     */
    @Test
    void theFireBodyFadesInAsAShadow() {
        double[] held = new double[2 * SmokePuffs.FIELDS];
        double soot = 10 * 2 * Math.PI * 25 / SmokeGrid.SOOT_K;
        double previous = 1;
        for (double share : new double[]{0, 0.01, 0.1, 0.5, 1}) {
            puff(held, 0, 0, 0, 0, 5, soot);
            puff(held, 1, 0, 10, 0, 5, soot);
            held[SmokePuffs.FIELDS + 13] = share;
            SmokePuffs.sunThrough(held, 0, 2, new boolean[2], 0, 1, 0, 0.8f);
            double t = through(held, 0);
            if (share == 0) assertEquals(1, t, 1e-12);
            if (share == 0.01) assertTrue(t > 0.9, "1 % share passes " + t);
            assertTrue(t <= previous, "share " + share);
            previous = t;
        }
    }

    /** The veil's light by direction is the substance's; its cloud's shadow dims every direction alike (review #689). */
    @Test
    void theVeilShadowKeepsTheSubstanceLaw() {
        org.joml.Vector3f forward = new org.joml.Vector3f(0, 0, 1);
        FarView view = SmokeFillTest.view(net.minecraft.world.phys.Vec3.ZERO, forward);
        float drops = SmokePuffs.thick(0, 0, 1, 0.9f);
        double sigma = 20, ext = 30 * 2 * Math.PI * sigma * sigma;
        SmokePuffs.Veil open = new SmokePuffs.Veil(), shaded = new SmokePuffs.Veil();
        open.fill(view, new double[]{0, 0, 0, sigma, sigma, ext, drops, drops, drops, 1, 0}, 1);
        shaded.fill(view, new double[]{0, 0, 0, sigma, sigma, ext, drops, drops, drops, 1, 0.8}, 1);
        int n = SmokePuffs.Veil.N, mid = n / 2, top = SmokePuffs.Veil.index(mid, n - 1), bottom = SmokePuffs.Veil.index(mid, 0);
        double ratioOpen = Math.pow(open.r[top] / open.r[bottom], 2.2), ratioShaded = Math.pow(shaded.r[top] / shaded.r[bottom], 2.2);
        // fog stays even under the shadow too; the shadow leaves 0.2 of the light everywhere
        assertEquals(ratioOpen, ratioShaded, 1e-3);
        assertTrue(ratioShaded < 1.3, "fog " + ratioShaded);
        assertEquals(0.2, Math.pow(shaded.r[top] / open.r[top], 2.2), 1e-3);
    }
}
