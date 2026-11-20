package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cloud's own shadow ({@link SmokePuffs#sunThrough}): without it every puff of a black cloud is lit as if alone in the
 * sky, and the underside and core of the cloud are as bright as its top.
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
        // the same column with a low sun from the side: from the puff's sunlit skin (1σ toward the sun) only the
        // neighbours' edges (2σ off the ray) are in front, less than half of their τ = 3·e^(−2)/2 each
        for (int i = 0; i < 3; i++) puff(held, i, 0, 10 * i, 0, 5, soot);
        SmokePuffs.sunThrough(held, 0, 3, new boolean[3], 1, 0, 0, 0.8f);
        assertTrue(through(held, 1) > Math.exp(-2 * 1.5 * Math.exp(-2)) && through(held, 1) < 1, "side " + through(held, 1));
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
     * the handoff the shadow fades in, not at full thickness at once (at full thickness τ = 10 would pass 0.0045 % at a
     * share of 0.01).
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

    /**
     * Through pale dust the sunlight goes on as diffuse light (two streams, e^(−kτ), k ≈ 0.5): a dust column's lower puff
     * stays far brighter than a soot column's of the same thickness (the beam, e^(−τ)), and the dust cloud stays pale in
     * its own shadow, as in footage of explosion dust.
     */
    @Test
    void dustLetsTheLightThroughSootDoesNot() {
        double[] soot = new double[2 * SmokePuffs.FIELDS], dust = new double[2 * SmokePuffs.FIELDS];
        double tau = 3 * 2 * Math.PI * 25;
        puff(soot, 0, 0, 0, 0, 5, tau / SmokeGrid.SOOT_K);
        puff(soot, 1, 0, 10, 0, 5, tau / SmokeGrid.SOOT_K);
        float pale = SmokeGrid.dustAlbedo(0.6f);
        double kd = SmokeGrid.dustK(pale, pale, pale);
        puff(dust, 0, 0, 0, 0, 5, 0);
        puff(dust, 1, 0, 10, 0, 5, 0);
        for (int i = 0; i < 2; i++) {
            int o = i * SmokePuffs.FIELDS;
            dust[o + 6] = tau / kd;
            dust[o + 8] = dust[o + 9] = dust[o + 10] = pale;
        }
        SmokePuffs.sunThrough(soot, 0, 2, new boolean[2], 0, 1, 0, 0.8f);
        SmokePuffs.sunThrough(dust, 0, 2, new boolean[2], 0, 1, 0, 0.8f);
        double k = SmokePuffs.shade(0, kd, 0, pale);
        assertTrue(k > 0.3 && k < 0.8, "dust k " + k);
        // the same blocker thickness: dust passes its power k of what soot passes
        assertEquals(Math.pow(through(soot, 0), k), through(dust, 0), 1e-6);
        assertTrue(through(dust, 0) > 2 * through(soot, 0), "dust " + through(dust, 0) + " soot " + through(soot, 0));
    }

    /**
     * Within the pair budget a puff's beam is summed through the source's most attenuating puffs: the dense one above
     * casts the shadow, a thin one beside the beam is what a capped sum leaves out.
     */
    @Test
    void cappedSumKeepsTheDensestBlockers() {
        double[] held = new double[3 * SmokePuffs.FIELDS];
        double dense = 3 * 2 * Math.PI * 25 / SmokeGrid.SOOT_K;
        puff(held, 0, 0, 0, 0, 5, dense);
        puff(held, 1, 0, 10, 0, 5, dense);
        puff(held, 2, 0, 20, 0, 5, dense / 30);
        SmokePuffs.sunThrough(held, 0, 3, new boolean[3], 0, 1, 0, 0.8f);
        double all = through(held, 0);
        for (int i = 0; i < 3; i++) held[i * SmokePuffs.FIELDS + 17] = 1;
        SmokePuffs.sunThrough(held, 0, 3, new boolean[3], 0, 1, 0, 0.8f, 1);
        double one = through(held, 0);
        // one blocker: the dense puff right above (the receiver itself is skipped), not the thin one
        assertTrue(one < 0.3, "one blocker " + one);
        assertTrue(one >= all && one - all < 0.1, one + " vs " + all);
    }

    /** The veil's light by direction is the substance's; its cloud's shadow dims every direction alike. */
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
