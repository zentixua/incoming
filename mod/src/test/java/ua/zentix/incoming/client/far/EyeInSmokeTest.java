package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.plume.TrailModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.io.IOException;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eye in the Smoke (complaint 24.10, final pass 3.7.0, scene sam-burst: the wind carries the SAM trail through the camera). The frame - squares
 *  of billows and a veil of a layer ({@link SmokePuffs}, the same piece of tube as the trace, {@link FarFlightView#line}) - should
 *  repeat the substance itself: passing a beam from the eye through a Gaussian tube. Previously, billows tubes melted as soon as the eye
 *  entered their edge (~3,7σ), and the substance went into one veil with a thickness in the middle of the screen: the entire frame is a smooth white pall, and above the
 *  tube, where the beam does not meet it, too.
 */
class EyeInSmokeTest {
    /** SAM trace tube on ~9 s: σ 5 m, thickness across the middle 2,7 (opacity 0,93, {@code TrailModel.look}). */
    static final double SIGMA = 5, TAU = 2.7, E = TAU * Math.sqrt(2 * Math.PI) * SIGMA;
    /** Tube along z, from −LEN to LEN, in pieces along STEP m. */
    static final double LEN = 150, STEP = 4;
    /** Parts of the frame horizontally and vertically. */
    static final int COLS = 6, ROWS = 3, SAMPLES = 8;

    /**
     * The wind carries the tube through the eye: the axis is d from the eye horizontally across it, from 4σ in front to σ behind, step
     * 0,1σ; two glances - straight at the axis and 40° above (the tube goes to the bottom of the frame, the top looks past it into the sky). Frame - 18
     * parts (6 × 3), transmission of the part - average over 64 rays.
     * <ul>
     * <li>Eye in the tube (|d| ≤ σ: its billows have melted, the substance is a veil) - each part is no further than 0,15 from the substance (billows of
     * tubes walk on ~0,3σ - detail, not error).</li>
     * <li>Where the substance is lighter at the top of the frame than at the bottom (tube in the lower part) than on 0,1, the frame is also lighter at the top - not
     *  less than half of this difference without 0,03: the veil with one square gave one thing everywhere (before - smooth white pall and above the
     *  tube).</li>
     * <li>In a step, no part changes more than on 0,12: billows changes the veil without a jump.</li>
     * </ul>
     */
    @Test
    void tubeThroughTheEyeIsSeenRayByRay() throws IOException {
        double[][] prof = PlumeContinuityTest.profiles();
        for (double pitch : new double[]{0, 40}) {
            double inside = 0, jump = 0, flat = Double.POSITIVE_INFINITY;
            String insideAt = "", jumpAt = "", flatAt = "";
            double[] last = null;
            for (double d = 4 * SIGMA; d >= -SIGMA - 1e-9; d -= 0.1 * SIGMA) {
                Vector3f fwd = new Vector3f((float) Math.cos(Math.toRadians(pitch)), (float) Math.sin(Math.toRadians(pitch)), 0);
                FarView view = SmokeFillTest.view(new Vec3(0, 100, 0), fwd);
                SmokeFillTest.Screen screen = frame(view, d);
                double[] got = new double[COLS * ROWS], real = new double[COLS * ROWS];
                for (int i = 0; i < COLS; i++) {
                    for (int j = 0; j < ROWS; j++) {
                        for (int a = 0; a < SAMPLES; a++) {
                            for (int b = 0; b < SAMPLES; b++) {
                                double u = -SmokeFillTest.W + (i * SAMPLES + a + 0.5) * 2 * SmokeFillTest.W / (COLS * SAMPLES);
                                double v = -SmokeFillTest.H + (j * SAMPLES + b + 0.5) * 2 * SmokeFillTest.H / (ROWS * SAMPLES);
                                got[i * ROWS + j] += screen.transmittance(u, v, prof) / (SAMPLES * SAMPLES);
                                real[i * ROWS + j] += truth(view, d, u, v) / (SAMPLES * SAMPLES);
                            }
                        }
                    }
                }
                String at = String.format(Locale.ROOT, "axis in %.1f m: frame %s, substance %s", d, row(got), row(real));
                for (int k = 0; k < got.length; k++) {
                    if (Math.abs(d) <= SIGMA + 1e-9 && Math.abs(got[k] - real[k]) > inside) {
                        inside = Math.abs(got[k] - real[k]);
                        insideAt = at;
                    }
                    if (last != null && Math.abs(got[k] - last[k]) > jump) {
                        jump = Math.abs(got[k] - last[k]);
                        jumpAt = at;
                    }
                }
                for (int i = 0; i < COLS; i++) {
                    double want = real[i * ROWS + ROWS - 1] - real[i * ROWS], have = got[i * ROWS + ROWS - 1] - got[i * ROWS];
                    if (want > 0.1 && have - (0.5 * want - 0.03) < flat) {
                        flat = have - (0.5 * want - 0.03);
                        flatAt = at;
                    }
                }
                last = got;
            }
            String report = String.format(Locale.ROOT, "looking up at %.0f°: eye in the tube - part against the substance up to %.3f (%s); top versus bottom - stock %.3f (%s); step to %.3f (%s)",
                    pitch, inside, insideAt, flat, flatAt, jump, jumpAt);
            System.out.println("EYE " + report);
            assertTrue(inside <= 0.15, report);
            assertTrue(flat >= 0, report);
            assertTrue(jump <= 0.12, report);
        }
    }

    /** Frame parts in rows from top to bottom. */
    private static String row(double[] parts) {
        StringBuilder b = new StringBuilder();
        for (int j = ROWS - 1; j >= 0; j--) {
            b.append(j == ROWS - 1 ? "[" : " / ");
            for (int i = 0; i < COLS; i++) b.append(String.format(Locale.ROOT, i == 0 ? "%.2f" : " %.2f", parts[i * ROWS + j]));
        }
        return b.append(']').toString();
    }

    /** Frame: a tube with an axis along z in d m in front of the eye, horizontally across it, with the same section of the tube as the track. */
    private static SmokeFillTest.Screen frame(FarView view, double d) {
        SmokeFillTest.Screen screen = new SmokeFillTest.Screen(view);
        SmokePuffs.resetBudget();
        float[] joint = {Float.NaN, 0, 0};
        long seed = 91;
        for (double z = -LEN; z < LEN; z += STEP) {
            FarFlightView.Tube a = end(view, d, 0, z), b = end(view, d, 0, z + STEP);
            int drawn = FarFlightView.line(view, screen, a, b, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 0.9f, 1, 0.6, seed++, 1, joint);
            if (drawn != FarFlightView.RIBBON) joint[0] = Float.NaN;
        }
        SmokePuffs.flush();
        SmokePuffs.resetBudget();
        return screen;
    }

    /** The end of the tube at (dx, dy, dz) from the eye is like the mark ({@code FarFlightView.end}). */
    private static FarFlightView.Tube end(FarView view, double dx, double dy, double dz) {
        double tau = E / (Math.sqrt(2 * Math.PI) * SIGMA), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double width = TrailModel.width(tau, SIGMA);
        double[] rib = new double[2];
        FarFlightView.ribbon(width, d, view.pixel(), rib);
        return new FarFlightView.Tube().set(dx, dy, dz, SIGMA, E, width, rib[0], (float) ((1 - Math.exp(-tau)) * rib[1] * view.air(d, dy)), 0);
    }

    /**
     * Material transmittance along a ray from the eye to screen point (u, v): tube is an infinite Gaussian cylinder along z,
     * with axis at (d, 0) from the eye, attenuation per meter E:
     * τ = E/(2πσ²)·e^(−r²/2σ²)·(σ/w)·√(π/2)·(1 + erf(w·t₀/(√2σ))), where w is the ray component across the axis,
     * t₀ is the ray point closest to the axis, and r is the distance to the axis there.
     */
    static double truth(FarView view, double d, double u, double v) {
        Vector3f f = view.forward(), l = view.left(), up = view.up();
        double rx = f.x + u * l.x + v * up.x, ry = f.y + u * l.y + v * up.y, rz = f.z + u * l.z + v * up.z, n = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double a = rx / n, b = ry / n, w2 = a * a + b * b;
        if (w2 < 1e-12) return Math.abs(d) < SIGMA ? 0 : 1;
        double w = Math.sqrt(w2), t0 = d * a / w2, r2 = d * d * b * b / w2;
        double tau = E / (2 * Math.PI * SIGMA * SIGMA) * Math.exp(-r2 / (2 * SIGMA * SIGMA)) * SIGMA / w * Math.sqrt(Math.PI / 2)
                * (1 + SmokeGrid.erf(w * t0 / (Math.sqrt(2) * SIGMA)));
        return Math.exp(-tau);
    }
}
