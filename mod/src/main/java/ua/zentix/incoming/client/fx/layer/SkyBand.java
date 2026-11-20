package ua.zentix.incoming.client.fx.layer;

import java.util.Arrays;

/**
 * The sky a shader pack draws, by direction from the eye: the airlight in front of distant nuclear matter under a pack
 * ({@code pallAirLight} in {@code fx_pall}). Real airlight in front of a far object is the sky radiance along that line
 * (Koschmieder); under a shader pack that sky is the pack's, not vanilla's {@code getSkyColor}, which stays blue-grey at dusk
 * while the pack paints the sky gold (mission dusk-mushroom: the haze share, ≈0.42 of a 15 kt cap's lit side at 9 km, kept
 * the cap blue against an orange sky).
 * <p>
 * {@link #AZ} × {@link #EL} bins over the upper hemisphere (azimuth around the eye, elevation 0–90°). Each frame
 * {@code fx_sky_band} takes {@link #SAMPLES}² jittered directions per bin and averages the frame's pixels there that are sky
 * (world distance = sky, {@link SceneDepth}) and lie outside a cone of {@link #EXCLUDE_DEG} around the sun and the moon
 * ({@link #takes}): the sun disc and the pack's glare never enter, nor anything in front of the sky (terrain, LOD,
 * blocks; review #671 rejected screen taps that carried the sun and nearby blocks into the cap). Bins are smoothed over
 * {@link #TAU} seconds from captures where at least {@link #MIN_SHARE} of its samples were sky (a sliver of a bin is
 * noise, and refreshing on it kept an old sky alive: a pack switched by Iris's Apply held its colour for tens of seconds,
 * review #686 r4); a bin not seen for {@link #STALE} seconds is dropped (what is on screen refills at once). Empty bins take their row's nearest seen bins
 * (by azimuth), an empty row the nearest filled row; with no bin seen, the band is off and the airlight is vanilla's sky.
 * Without a shader pack the band is never filled: airlight stays vanilla's sky. Minecraft-free (unit tests); GL in
 * {@link SkyBandPass}.
 */
public final class SkyBand {
    /** Azimuth bins (22.5°) and elevation bins (15°): copies of {@code BAND_AZ}, {@code BAND_EL} in the shaders. */
    public static final int AZ = 16, EL = 6;
    /** Sample directions per bin side ({@code BAND_SAMPLES} in {@code fx_sky_band}). */
    public static final int SAMPLES = 8;
    /**
     * Cone left out around the sun and the moon, degrees: the pack's sun or moon disc with its glow (vanilla's sun quad
     * has a 16.7° half-width; the disc and glow of common packs lie within ~10°). presentation: the aureole inside it is
     * replaced by the sky beside it.
     */
    public static final double EXCLUDE_DEG = 10;
    /** Smoothing time constant, seconds (the shader's jittered samples, stars and drifting pack clouds average out). */
    public static final double TAU = 0.5;
    /** A bin not seen for this long is no longer the sky there (the sky changes through dusk), seconds. */
    public static final double STALE = 2;
    /** Least share of a bin's samples that must be sky for a capture to count there. */
    public static final double MIN_SHARE = 1.0 / 8;

    private final float[] color = new float[AZ * EL * 3];
    private final double[] seen = new double[AZ * EL];

    public SkyBand() {
        reset();
    }

    /** Forget every bin: the airlight is vanilla's sky until the next observation. */
    public void reset() {
        Arrays.fill(color, 0);
        Arrays.fill(seen, Double.NaN);
    }

    /** Cosine of the sun/moon cone ({@code BandSun.w}). */
    public static double cosExclude() {
        return Math.cos(Math.toRadians(EXCLUDE_DEG));
    }

    /**
     * A sky pixel along unit direction (x, y, z) counts for the band: outside the cone around the sun (sx, sy, sz) and the
     * moon opposite it. Copy of {@code skyBandTakes} in {@code fx_sky_band}.
     */
    public static boolean takes(double x, double y, double z, double sx, double sy, double sz, double cosOut) {
        return Math.abs(x * sx + y * sy + z * sz) < cosOut;
    }

    /**
     * The sun's direction as a shader pack draws it, into {@code out}: Iris turns the vanilla orbit (sx, sy, 0) about the
     * east–west axis by the pack's {@code sunPathRotation} ({@code CelestialUniforms}: Ry(−90°)·Rz(rotation)·Rx(sky angle)
     * applied to the zenith, vanilla's sky without Rz), so (sx, sy·cos r, −sy·sin r). The moon is opposite.
     */
    public static void packSun(double sx, double sy, double rotationDeg, double[] out) {
        double r = Math.toRadians(rotationDeg);
        out[0] = sx;
        out[1] = sy * Math.cos(r);
        out[2] = -sy * Math.sin(r);
    }

    /**
     * Sample direction in bin (a, e) at fraction (u, v) of the bin, into {@code out} (unit, y up). Copy of {@code bandDir} in
     * {@code fx_sky_band}.
     */
    public static void direction(int a, int e, double u, double v, double[] out) {
        double az = (a + u) / AZ * 2 * Math.PI, el = (e + v) / EL * 0.5 * Math.PI;
        out[0] = Math.cos(el) * Math.cos(az);
        out[1] = Math.sin(el);
        out[2] = Math.cos(el) * Math.sin(az);
    }

    /**
     * One pass's result: per bin (a + e·AZ) rgb mean of its sky pixels and the share of its samples that were sky
     * ({@code rgba}, length AZ·EL·4), captured at {@code now} (seconds; the capture's time, not its readback's).
     */
    public void observe(float[] rgba, double now) {
        for (int i = 0; i < AZ * EL; i++) {
            float share = rgba[i * 4 + 3];
            if (!(share >= MIN_SHARE) || !Float.isFinite(rgba[i * 4]) || !Float.isFinite(rgba[i * 4 + 1]) || !Float.isFinite(rgba[i * 4 + 2])) continue;
            boolean fresh = fresh(i, now);
            double dt = Double.isNaN(seen[i]) ? 0 : Math.max(now - seen[i], 0);
            double k = fresh ? 1 - Math.exp(-dt / TAU) : 1;
            for (int c = 0; c < 3; c++) color[i * 3 + c] += (float) ((rgba[i * 4 + c] - color[i * 3 + c]) * k);
            seen[i] = now;
        }
    }

    private boolean fresh(int i, double now) {
        return !Double.isNaN(seen[i]) && now - seen[i] <= STALE;
    }

    /**
     * The band to draw ({@code PallAirBand}, AZ·EL vec4: rgb, w unused) into {@code out}, holes filled; false — no bin seen
     * within {@link #STALE}: the airlight is vanilla's sky.
     */
    public boolean fill(float[] out, double now) {
        boolean[] row = new boolean[EL];
        boolean any = false;
        for (int e = 0; e < EL; e++) {
            for (int a = 0; a < AZ; a++) row[e] |= fresh(e * AZ + a, now);
            any |= row[e];
        }
        if (!any) return false;
        for (int e = 0; e < EL; e++) {
            if (!row[e]) continue;
            for (int a = 0; a < AZ; a++) {
                int i = e * AZ + a;
                if (fresh(i, now)) {
                    for (int c = 0; c < 3; c++) out[i * 4 + c] = color[i * 3 + c];
                    out[i * 4 + 3] = 1;
                    continue;
                }
                // nearest seen bins on either side in azimuth (wrapping), weighted by nearness
                int left = 1, right = 1;
                while (!fresh(e * AZ + Math.floorMod(a - left, AZ), now)) left++;
                while (!fresh(e * AZ + Math.floorMod(a + right, AZ), now)) right++;
                int l = e * AZ + Math.floorMod(a - left, AZ), r = e * AZ + Math.floorMod(a + right, AZ);
                double t = (double) left / (left + right);
                for (int c = 0; c < 3; c++) out[i * 4 + c] = (float) (color[l * 3 + c] + (color[r * 3 + c] - color[l * 3 + c]) * t);
                out[i * 4 + 3] = 0;
            }
        }
        for (int e = 0; e < EL; e++) {
            if (row[e]) continue;
            int from = -1;
            for (int d = 1; from < 0; d++) {
                if (e - d >= 0 && row[e - d]) from = e - d;
                else if (e + d < EL && row[e + d]) from = e + d;
            }
            for (int a = 0; a < AZ; a++) {
                System.arraycopy(out, (from * AZ + a) * 4, out, (e * AZ + a) * 4, 3);
                out[(e * AZ + a) * 4 + 3] = 0;
            }
        }
        return true;
    }

    /**
     * Airlight from a filled band ({@link #fill}) along direction (x, y, z), bilinear between bin centres, wrapping in
     * azimuth, elevation clamped to the horizon row below 0°. Copy of {@code pallAirBand} in {@code fx_pall}.
     */
    public static void read(float[] band, double x, double y, double z, double[] out) {
        double len = Math.sqrt(x * x + y * y + z * z);
        // straight up or down the azimuth is undefined (GLSL atan(0, 0)): take 0, as the shader does
        double az = Math.abs(x) + Math.abs(z) > 1e-9 * len ? Math.atan2(z, x) : 0;
        double u = az / (2 * Math.PI) * AZ - 0.5;
        double v = Math.asin(Math.min(Math.max(y / Math.max(len, 1e-9), 0), 1)) / (0.5 * Math.PI) * EL - 0.5;
        v = Math.min(Math.max(v, 0), EL - 1);
        double u0 = Math.floor(u), v0 = Math.floor(v), fu = u - u0, fv = v - v0;
        int a0 = Math.floorMod((int) u0, AZ), a1 = Math.floorMod((int) u0 + 1, AZ);
        int e0 = (int) v0, e1 = Math.min(e0 + 1, EL - 1);
        for (int c = 0; c < 3; c++) {
            double lo = band[(e0 * AZ + a0) * 4 + c] + (band[(e0 * AZ + a1) * 4 + c] - band[(e0 * AZ + a0) * 4 + c]) * fu;
            double hi = band[(e1 * AZ + a0) * 4 + c] + (band[(e1 * AZ + a1) * 4 + c] - band[(e1 * AZ + a0) * 4 + c]) * fu;
            out[c] = lo + (hi - lo) * fv;
        }
    }
}
