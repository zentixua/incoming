package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Clump quad is visible Gaussian edge; thick soot layer color is dark gray, not a black hole. */
class SmokePuffsTest {
    /** Edge where optical depth drops to 0.05: ~3.7σ for dense clump; thin (τ₀ below threshold) gets minimum edge, not NaN. */
    @Test
    void edgeOfThinAndThickClouds() {
        assertEquals(Math.sqrt(2 * Math.log(42 / SmokePuffs.EDGE_TAU)), SmokePuffs.edge(42), 1e-9);
        for (double tau : new double[]{0, 0.004, 0.01, 0.049, 0.05}) {
            double e = SmokePuffs.edge(tau);
            assertTrue(Double.isFinite(e) && e == 1.2, "τ₀ " + tau + ": edge " + e);
        }
    }

    /** Thick soot layer (ω' ≈ 0.2) reflects ~4%, ~0.24 on screen, not 0: Eddington gave negative reflectance. */
    @Test
    void sootIsDarkGreyNotBlack() {
        float soot = SmokePuffs.thick(1, 0, 0, 0.9f);
        assertTrue(soot > 0.15 && soot < 0.35, "soot " + soot);
    }

    /**
     * Clump-map shadow raised to mixture light-attenuation exponent relative to baked matter: white aluminum-oxide smoke and drops have
     * almost no shadow (otherwise white SAM launch cloud became dark gray sphere); soot darker than bake; mixture matching
     * bake unchanged; exponent fits vertex byte ({@code FxQuads.SHADE_MAX}).
     */
    @Test
    void mapShadeFollowsAlbedo() {
        double white = SmokePuffs.shade(0, 0, 1, 0.9f) / SmokePuffs.BAKED_K, soot = SmokePuffs.shade(1, 0, 0, 0.9f) / SmokePuffs.BAKED_K;
        assertTrue(white < 0.05, "drops " + white);
        assertTrue(soot > 1 && soot < ua.zentix.incoming.client.fx.layer.FxQuads.SHADE_MAX, "soot " + soot);
        assertEquals(1, SmokePuffs.twoStream(0.46, 0.355) / SmokePuffs.BAKED_K, 0.01, "baked matter");
        // spray baked with albedo 0.99, cloud drops whiter (0.9999): weaker spray-map shadow for them, stronger for soot
        assertTrue(SmokePuffs.shade(0, 0, 1, 0.9f) / SmokePuffs.SPRAY_K < 1, "drops on spray maps");
        assertTrue(SmokePuffs.shade(1, 0, 0, 0.9f) / SmokePuffs.SPRAY_K > 1, "soot on spray maps");
    }

    /** Baked albedo and asymmetry match baking script. */
    @Test
    void bakedAlbedoMatchesBakeScript() throws java.io.IOException {
        String bake = java.nio.file.Files.readString(java.nio.file.Path.of("..", "tools", "bake_fx", "bake.py"));
        assertTrue(bake.contains("PUFF_ALBEDO, PUFF_G = " + SmokePuffs.BAKED_W + ", " + SmokePuffs.BAKED_G),
                "tools/bake_fx/bake.py: PUFF_ALBEDO, PUFF_G not " + SmokePuffs.BAKED_W + ", " + SmokePuffs.BAKED_G);
        assertTrue(bake.contains("SPRAY_ALBEDO, SPRAY_G = " + SmokePuffs.SPRAY_W + ", " + SmokePuffs.SPRAY_G),
                "tools/bake_fx/bake.py: SPRAY_ALBEDO, SPRAY_G not " + SmokePuffs.SPRAY_W + ", " + SmokePuffs.SPRAY_G);
    }

    /**
     * Flat clump (ground dust layer) projects by view angle: from above, circle of width σh; from side, strip of
     * height σv; between, √(σv²cos²e + σh²sin²e); round clump always σ. Camera-facing quad of height σv viewed from above
     * would be a line across frame (F2a frames: dark strips above ground around explosion).
     */
    @Test
    void flatClubProjectsByViewAngle() {
        double sh = 20, sv = 0.5;
        org.junit.jupiter.api.Assertions.assertEquals(sh, SmokePuffs.projected(sh, sv, 0, -50, 0), 1e-9, "from above");
        org.junit.jupiter.api.Assertions.assertEquals(sv, SmokePuffs.projected(sh, sv, 50, 0, 0), 1e-9, "from side");
        double e = Math.toRadians(20), d = 50;
        double mid = SmokePuffs.projected(sh, sv, d * Math.cos(e), -d * Math.sin(e), 0);
        org.junit.jupiter.api.Assertions.assertEquals(Math.sqrt(sv * sv * Math.pow(Math.cos(e), 2) + sh * sh * Math.pow(Math.sin(e), 2)), mid, 1e-9);
        org.junit.jupiter.api.Assertions.assertTrue(mid > 10 * sv, "at 20°, no longer a line: " + mid);
        org.junit.jupiter.api.Assertions.assertEquals(3, SmokePuffs.projected(3, 3, 10, -7, 2), 1e-9, "round");
    }

    /**
     * Optical depth along eye ray through Gaussian: sphere uses old veil formula (τ₀·e^(−b²/2σ²)·½(1 + erf(a/√2σ))); flat
     * layer (σh 40, σv 1) seen from 18 m above has thin top-down optical depth, not depth along layer (final F2a frames:
     * dust-layer veil covered entire frame at substation and bomb at 150 m).
     */
    @Test
    void sightDepthThroughAFlatLayer() {
        double ext = 1000, s = 5;
        double tau0 = ext / (2 * Math.PI * s * s);
        // eye at sphere center, looking sideways: half transverse optical depth
        org.junit.jupiter.api.Assertions.assertEquals(0.5 * tau0, SmokePuffs.sightDepth(0, 0, 0, s, s, ext, 1, 0, 0), 1e-9);
        // sphere 10 ahead (a = 10), 3 sideways
        double a = 10, b = 3, old = tau0 * Math.exp(-0.5 * b * b / (s * s)) * 0.5 * (1 + ua.zentix.incoming.client.fx.smoke.SmokeGrid.erf(a / (Math.sqrt(2) * s)));
        org.junit.jupiter.api.Assertions.assertEquals(old, SmokePuffs.sightDepth(a, b, 0, s, s, ext, 1, 0, 0), 1e-9);
        // flat layer below eye: downward optical depth ext/(2πσh²) (whole layer below eye); along layer enormous
        double down = SmokePuffs.sightDepth(0, -18, 0, 40, 1, ext, 0, -1, 0);
        org.junit.jupiter.api.Assertions.assertEquals(ext / (2 * Math.PI * 40 * 40), down, 1e-6);
        double level = SmokePuffs.sightDepth(0, -18, 0, 40, 1, ext, 1, 0, 0);
        org.junit.jupiter.api.Assertions.assertTrue(level < 1e-6, "looking along, 18 σ above layer: " + level);
    }

    /**
     * Ground dust layer (σh 15, σv 0.5) viewed from 18 m above and 50 m away lies horizontally (camera-facing quad
     * would stand above ground as a “slab” at 0.1 s, F2a frames); same layer from eye level and round clump face camera.
     */
    @Test
    void flatClubLiesWhenSeenFromAbove() {
        assertEquals(1, SmokePuffs.lyingShare(15, 0.5, -18, 53));
        assertEquals(0, SmokePuffs.lyingShare(15, 0.5, -1, 53), "a strip at eye level");
        assertEquals(0, SmokePuffs.lyingShare(5, 5, -18, 53), "round");
    }

    /**
     * Trail tube as clumps ({@link SmokePuffs#tube}): entire segment matter (place shares sum to 1 at every level),
     * clumps spaced no farther than {@link SmokePuffs#SEGMENT_STEP}·σ until maximum level; matter at each place changes
     * without jumps as σ grows and level changes (clumps do not jump). Transverse center optical depth of Gaussian tube
     * equals trail-law tube depth, e/(√(2π)·σ).
     */
    @Test
    void tubeKeepsItsMatterAndSpacing() {
        double len = 28;
        double[] last = null;
        for (double sigma = 0.3; sigma < 40; sigma *= 1.01) {
            double level = SmokePuffs.tubeLevel(len, sigma), total = SmokePuffs.tubeTotal(level);
            int n = 1 << SmokePuffs.tubeDepth(level);
            double sum = 0;
            for (int j = 0; j < n; j++) sum += SmokePuffs.tubeWeight(j, level);
            assertEquals(total, sum, 1e-9, "whole matter at σ " + sigma);
            if (level < SmokePuffs.TUBE_LEVELS) assertTrue(len / n <= SmokePuffs.SEGMENT_STEP * sigma * 1.0001, "step at σ " + sigma);
            // place matter at finest level
            int fine = 1 << SmokePuffs.TUBE_LEVELS;
            double[] mass = new double[fine];
            for (int j = 0; j < n; j++) mass[j * (fine / n)] = SmokePuffs.tubeWeight(j, level) / total;
            if (last != null) {
                for (int j = 0; j < fine; j++) assertTrue(Math.abs(mass[j] - last[j]) < 0.05, "place " + j + " at σ " + sigma);
            }
            last = mass;
        }
        // transverse center optical depth: Gaussians spaced along axis
        double sigma = 0.5, e = 0.4, level = SmokePuffs.tubeLevel(len, sigma), total = SmokePuffs.tubeTotal(level);
        int n = 1 << SmokePuffs.tubeDepth(level);
        double tau = 0, at = len / 2;
        for (int j = 0; j < n; j++) {
            double x = len * j / n - at, k = e * len * SmokePuffs.tubeWeight(j, level) / total;
            tau += k / (2 * Math.PI * sigma * sigma) * Math.exp(-x * x / (2 * sigma * sigma));
        }
        assertEquals(e / (Math.sqrt(2 * Math.PI) * sigma), tau, 0.05 * tau, "transverse tube optical depth");
    }

    /**
     * Veil softness to world ({@link SmokePuffs.Veil#soft}): vertex fades linearly toward world ({@code fx.fsh}: (world − 2 blocks) /
     * softness); its opacity share before world at S is (1 − e^(−τ(S)))/(1 − e^(−τ)), τ(S) ray optical depth to S (here
     * numerical from Gaussian density). Over 3…120 m, ramp differs from share by at most 0.4, closer than old veil without softness
     * (fully covered everything beyond 2 blocks): eye along dense SAM trail (σ 5 m, transverse depth 2.7: half
     * opacity already at 3 m), distant dense clump at 60 m (house before it at 20 m gets less than 0.2 veil), and thin
     * haze. ⚠ Linear ramp from 2 blocks cannot be more accurate than ~0.35 for eye-local matter or narrow distant clump.
     */
    @Test
    void veilFadesToTheWorldWhereItsMatterIs() {
        net.minecraft.world.phys.Vec3 eye = new net.minecraft.world.phys.Vec3(0, 100, 0);
        FarView view = SmokeFillTest.view(eye, new org.joml.Vector3f(0, 0, 1));
        double sigma = 5, tube = 2.7 * Math.sqrt(2 * Math.PI) * sigma;
        java.util.List<double[]> cases = new java.util.ArrayList<>();
        // tube along z through eye, Gaussians every 2 m
        java.util.List<double[]> trail = new java.util.ArrayList<>();
        for (double z = -150; z <= 150; z += 2) trail.add(new double[]{0, 0, z, sigma, sigma, tube * 2});
        // distant dense clump: center optical depth ~3
        java.util.List<double[]> far = java.util.List.of(new double[]{0, 0, 60, 8, 8, 3 * 2 * Math.PI * 64});
        // thin haze: broad Gaussian around eye, center optical depth ~0.2
        java.util.List<double[]> thin = java.util.List.of(new double[]{0, 0, 30, 40, 20, 0.2 * 2 * Math.PI * 40 * 20});
        for (java.util.List<double[]> gs : java.util.List.of(trail, far, thin)) {
            double[] packed = new double[gs.size() * SmokePuffs.VEIL_FIELDS];
            for (int c = 0; c < gs.size(); c++) {
                double[] g = gs.get(c);
                int o = c * SmokePuffs.VEIL_FIELDS;
                System.arraycopy(g, 0, packed, o, 6);
                packed[o + 6] = packed[o + 7] = packed[o + 8] = 0.8;
                packed[o + 9] = 1;
            }
            SmokePuffs.Veil veil = new SmokePuffs.Veil();
            veil.fill(view, packed, gs.size());
            int k = SmokePuffs.Veil.index(SmokePuffs.Veil.N / 2, SmokePuffs.Veil.N / 2);
            double soft = veil.soft[k], total = veil.tau[k], worst = 0, at = 0, old = 0, house = 0;
            // forward ray (z) optical depth from Gaussian density in 0.05 m steps; check every meter from 3 to 120
            double tau = 0;
            for (int step = 0; step < 2400; step++) {
                double z = (step + 0.5) * 0.05;
                for (double[] g : gs) {
                    double dx = -g[0], dy = -g[1], dz = z - g[2];
                    tau += 0.05 * g[5] / (Math.pow(2 * Math.PI, 1.5) * g[3] * g[3] * g[4])
                            * Math.exp(-0.5 * ((dx * dx + dz * dz) / (g[3] * g[3]) + dy * dy / (g[4] * g[4])));
                }
                double s = (step + 1) * 0.05;
                if (s < 3 || (step + 1) % 20 != 0) continue;
                double share = -Math.expm1(-tau) / -Math.expm1(-total), ramp = Math.clamp((s - SmokePuffs.VEIL_AT) / soft, 0, 1);
                old = Math.max(old, 1 - share);
                if (Math.abs(s - 20) < 1e-6) house = ramp;
                if (Math.abs(share - ramp) > worst) {
                    worst = Math.abs(share - ramp);
                    at = s;
                }
            }
            String got = String.format(java.util.Locale.ROOT, "optical depth %.2f, softness %.1f: ramp differs from share before world by %.2f at %.0f m (without softness %.2f), at 20 m %.2f",
                    total, soft, worst, at, old, house);
            System.out.println("VEIL " + got);
            assertTrue(worst <= 0.4 && worst < old, got);
            if (gs == far) assertTrue(house < 0.2, got);
        }
    }

    /**
     * Review #460: places of one tube never merge at any level. Group stored only holder's tube; groups A1+B1
     * (holds A) and A2+B2 (holds B) merged at next level, two places per tube in one clump. Group membership
     * persists across levels ({@link SmokePuffs.TubeGroups}).
     */
    @Test
    void tubeGroupsKeepEveryTubeAcrossLevels() {
        SmokePuffs.TubeGroups g = new SmokePuffs.TubeGroups();
        g.reset();
        long a = 1, b = 2, c = 3;
        g.add(0, a);
        g.add(1, b);
        g.add(2, a);
        g.add(3, b);
        g.add(4, c);
        assertTrue(g.disjoint(0, 1) && !g.disjoint(0, 2), "level 0: A1 with B1 yes, A1 with A2 no");
        g.join(0, 1);
        g.join(3, 2);
        assertTrue(!g.disjoint(0, 3) && !g.disjoint(3, 0), "level 1: A1+B1 and B2+A2 share tubes");
        assertTrue(g.disjoint(0, 4), "level 1: A1+B1 and C have different tubes");
        g.join(0, 4);
        assertTrue(!g.disjoint(3, 0), "level 2: group A1+B1+C and B2+A2");
    }
}
