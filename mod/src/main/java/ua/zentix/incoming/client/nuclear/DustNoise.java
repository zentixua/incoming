package ua.zentix.incoming.client.nuclear;

import java.util.Random;

/**
 * The dust wave's value-noise lattice and the haze level's top fields, the Java copy of {@code dustwall.glsl}'s
 * {@code dwNoise3}, {@code dwSwellField}, {@code dwPlume} and {@code dwEnvelope} (presentation, the shader's numbers): the
 * camera's inside sight reads the haze level's swollen top at the eye ({@link PallField#sight}). The lattice is the one the
 * pass uploads ({@code DustWallPass}); {@code DustNoiseTest} and {@code tools/dust_wall_gl.py} hold the copies to the shader.
 */
public final class DustNoise {
    /** Lattice side ({@code DW_LATTICE}). */
    public static final int SIDE = 128;
    /** {@code DW_BULGE}, {@code DW_PLUME}, {@code DW_SHEAR_S}, {@code DW_EDDY_S}. */
    static final double BULGE = 0.35, PLUME = 2.5, SHEAR_S = 300, EDDY_S = 300;
    /** {@code DW_RISE}, {@code DW_HOLD}, {@code DW_FALL}. */
    static final double RISE = 0.12, HOLD = 0.25, FALL = 0.3;

    private static byte[] values;

    private DustNoise() {}

    /** The 128³ lattice of random bytes (x fastest, then y, then z; read as unsigned / 255); fixed seed: the same for everyone. */
    public static synchronized byte[] values() {
        if (values == null) {
            byte[] v = new byte[SIDE * SIDE * SIDE];
            new Random(0xD057L).nextBytes(v);
            values = v;
        }
        return values;
    }

    private static double at(byte[] v, int x, int y, int z) {
        return (v[(x & (SIDE - 1)) + SIDE * ((y & (SIDE - 1)) + SIDE * (z & (SIDE - 1)))] & 0xFF) / 255.0;
    }

    /** {@code dwNoise3}: the lattice trilinearly at the smoothstep-warped coordinate (the texture's linear filter, wrapped). */
    static double noise(double x, double y, double z) {
        byte[] v = values();
        double ix = Math.floor(x), iy = Math.floor(y), iz = Math.floor(z);
        double fx = warp(x - ix), fy = warp(y - iy), fz = warp(z - iz);
        int i = (int) ix, j = (int) iy, k = (int) iz;
        double c00 = at(v, i, j, k) + fx * (at(v, i + 1, j, k) - at(v, i, j, k));
        double c10 = at(v, i, j + 1, k) + fx * (at(v, i + 1, j + 1, k) - at(v, i, j + 1, k));
        double c01 = at(v, i, j, k + 1) + fx * (at(v, i + 1, j, k + 1) - at(v, i, j, k + 1));
        double c11 = at(v, i, j + 1, k + 1) + fx * (at(v, i + 1, j + 1, k + 1) - at(v, i, j + 1, k + 1));
        double c0 = c00 + fy * (c10 - c00), c1 = c01 + fy * (c11 - c01);
        return c0 + fz * (c1 - c0);
    }

    private static double warp(double f) {
        return f * f * (3 - 2 * f);
    }

    /** {@code dwEddyPhase}. */
    static double eddyPhase(double a) {
        double x = Math.max(a, 0) / EDDY_S;
        return x < 0.01 ? Math.max(a, 0) * (1 - 0.3 * x + 0.14 * x * x) : EDDY_S / 0.4 * (Math.pow(1 + x, 0.4) - 1);
    }

    /** {@code dwRamp}. */
    static double ramp(double a, double c, double w) {
        return 1 / (1 + Math.exp(-(a - c) / w));
    }

    /** {@code dwSoft}. */
    static double soft(double x) {
        return x / Math.sqrt(1 + x * x);
    }

    /** {@code dwSwellField} at air-mass point (x, z), age a: the swell (share of the layer); its large-scale value into l[0]. */
    static double swell(double x, double z, double a, double[] l) {
        double px = x / 220.0, pz = z / 220.0;
        double w = noise(px * 0.4, pz * 0.4, 4.4) - 0.5;
        px += 0.8 * w;
        pz -= 0.8 * w;
        double e = eddyPhase(a);
        l[0] = 0.6 * noise(px, pz, 7.1 + 0.08 * e) + 0.4 * noise(x / 90.0, z / 90.0, 0.37 + 0.12 * e);
        double hollow = smoothstep(0.62, 0.8, noise(x / 160.0, z / 160.0, 2.2 + 0.03 * e)) * ramp(a, 6.5, 1.2);
        return BULGE * soft((l[0] - 0.5) * 4.0) * (0.2 + 0.8 * ramp(a, 2.75, 0.45)) - 0.4 * hollow;
    }

    /** {@code dwPlume}. */
    static double plume(double l, double a, double hot) {
        double tuft = smoothstep(0.62, 0.84, l);
        return PLUME * hot * tuft * tuft * (1 - Math.exp(-a / 40.0)) * Math.exp(-a / 1500.0);
    }

    /** {@code dwEnvelope}. */
    static double envelope(double a, double h, double k) {
        if (a <= 0) return 0;
        double x = Math.max(a - HOLD, 0) / FALL;
        return h * (1 - Math.exp(-a / RISE)) * (1 + (k - 1) * Math.exp(-x * x));
    }

    private static double smoothstep(double a, double b, double x) {
        double v = Math.clamp((x - a) / (b - a), 0, 1);
        return v * v * (3 - 2 * v);
    }
}
