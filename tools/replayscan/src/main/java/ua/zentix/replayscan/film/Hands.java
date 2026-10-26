package ua.zentix.replayscan.film;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Random;

/**
 * Camera FPV “as a person filmed” - trailer layer ({@code Operator}, style FPV, 13.10: Artem’s comments on the draft -
 *  cameras are too smooth, they don’t shake after an impact) on top of the pilot’s path ({@link Pilot}). The path is recalculated in advance for the entire
 *  window, the same for evaluation and output:
 * <ul>
 * <li> place and view - Gaussian smoothing without lag (window is known entirely);</li>
 * <li>hands - drone trembling: sum of sines 0,4–4 Hz (the candidate has his own seed), in degrees - by frame angle;</li>
 * <li>explosions - a push when a wave reaches the camera (17,15 block per tick, like the sound of a mod): range over distance and
 *  fireball, 4–6 Hz with attenuation, the gaze throws up, the place is pushed by the explosion. The shaking of the mod itself (5° with
 *  frequency ~10 Hz) before shooting is turned off in its client settings ({@code camera_shake = 0}, shooting task):
 * replayscan it is not touches.</li>
 * </ul>
 */
final class Hands {
    private Hands() {}

    /** Smoothing place and view, tick (σ). */
    static final double POS_SIGMA = 1.5, LOOK_SIGMA = 1.5;
    /** Jitter at frame angle 60°: heading, pitch, roll (°), place (blocks); frequency band, Hz. */
    static final double YAW = 0.35, PITCH = 0.35, ROLL = 0.9, POS = 0.05, F_LO = 0.4, F_HI = 4;
    /** Impact from the explosion: gaze span - {@code K}·fireball / (distance + 8), no more than {@code MAX}, °; share of FPV. */
    static final double K = 6, MAX = 3.5, SHAKE = 1.1;
    /** Explosion wave speed, blocks per tick (like the sound Incoming). */
    static final double FRONT = 17.15;

    /** Explosion: tick, location, diameter of the fireball. */
    record Blast(int t, Vec3 pos, double size) {}

    static Cameras.Path humanize(Cameras.Path in, long seed, List<Blast> blasts, int from, int to) {
        int n = to - from + 1;
        double[] x = new double[n], y = new double[n], z = new double[n], yaw = new double[n], pitch = new double[n],
                roll = new double[n];
        double fov = in.at(from).fov();
        for (int i = 0; i < n; i++) {
            Cameras.Pose p = in.at(from + i);
            x[i] = p.pos().x;
            y[i] = p.pos().y;
            z[i] = p.pos().z;
            // course without jump through ±180°
            yaw[i] = i == 0 ? p.yaw() : yaw[i - 1] + Mth.wrapDegrees(p.yaw() - (float) yaw[i - 1]);
            pitch[i] = p.pitch();
            roll[i] = in.roll(from + i);
        }
        x = gauss(x, POS_SIGMA);
        y = gauss(y, POS_SIGMA);
        z = gauss(z, POS_SIGMA);
        yaw = gauss(yaw, LOOK_SIGMA);
        pitch = gauss(pitch, LOOK_SIGMA);
        Random r = new Random(seed);
        double[] ny = noise(r, n, YAW, F_LO, F_HI), np = noise(r, n, PITCH, F_LO, F_HI), nr = noise(r, n, ROLL, F_LO, F_HI);
        double[] nx = noise(r, n, POS, F_LO * 0.5, F_HI * 0.5), nyy = noise(r, n, POS, F_LO * 0.5, F_HI * 0.5),
                nz = noise(r, n, POS, F_LO * 0.5, F_HI * 0.5);
        // in degrees the shaking is smaller at the long focus: on the screen - the same
        double q = Mth.clamp(fov / 60, 0.25, 1.3);
        for (int i = 0; i < n; i++) {
            yaw[i] += ny[i] * q;
            pitch[i] += np[i] * q;
            roll[i] += nr[i];
            x[i] += nx[i];
            y[i] += nyy[i];
            z[i] += nz[i];
        }
        shake(r, blasts, from, x, y, z, yaw, pitch, roll);
        double[] fx = x, fy = y, fz = z, fyaw = yaw, fpitch = pitch, froll = roll;
        return new Cameras.Path() {
            @Override
            public Cameras.Pose at(double t) {
                double u = Mth.clamp(t - from, 0, n - 1);
                int i = (int) Math.floor(u), j = Math.min(n - 1, i + 1);
                double k = u - i;
                return new Cameras.Pose(new Vec3(Mth.lerp(k, fx[i], fx[j]), Mth.lerp(k, fy[i], fy[j]), Mth.lerp(k, fz[i], fz[j])),
                        (float) Mth.lerp(k, fyaw[i], fyaw[j]), (float) Mth.clamp(Mth.lerp(k, fpitch[i], fpitch[j]), -90, 90), fov);
            }

            @Override
            public double roll(double t) {
                double u = Mth.clamp(t - from, 0, n - 1);
                int i = (int) Math.floor(u), j = Math.min(n - 1, i + 1);
                return Mth.lerp(u - i, froll[i], froll[j]);
            }
        };
    }

    /**
     * Shocks from explosions: the wave reaches the camera through the distance / {@link #FRONT} tick after the impact; scope - according to the fireball
     *  and distance, fades over 5–15 tick; the gaze looks up and returns, the place is pushed by an explosion.
     */
    static void shake(Random r, List<Blast> blasts, int from, double[] x, double[] y, double[] z, double[] yaw, double[] pitch,
                      double[] roll) {
        int n = x.length;
        double[] sy = new double[n], sp = new double[n], sr = new double[n];
        for (Blast f : blasts) {
            if (f.t() > from + n || f.t() < from - 80) continue;
            int at = Mth.clamp(f.t() - from, 0, n - 1);
            Vec3 cam = new Vec3(x[at], y[at], z[at]);
            double dist = cam.distanceTo(f.pos());
            double amp = Math.min(MAX, K * f.size() / (dist + 8)) * SHAKE;
            if (amp < 0.08) continue;
            double arrive = f.t() + dist / FRONT - from;
            double tau = 5 + amp * 2.5;
            double p1 = r.nextDouble() * 6.3, p2 = r.nextDouble() * 6.3, p3 = r.nextDouble() * 6.3, p4 = r.nextDouble() * 6.3,
                    p5 = r.nextDouble() * 6.3;
            Vec3 away = cam.subtract(f.pos());
            away = away.lengthSqr() < 1e-6 ? Vec3.ZERO : away.normalize();
            double jolt = Math.min(0.35, amp * 0.1);
            for (int i = Math.max(0, (int) Math.ceil(arrive)); i < n; i++) {
                double u = i - arrive;
                if (u > tau * 5) break;
                double rise = 1 - Math.exp(-u / 0.8), env = rise * Math.exp(-u / tau);
                double w = 2 * Math.PI / 20;
                sy[i] += amp * env * (0.7 * Math.sin(w * 5.3 * u + p1) + 0.3 * Math.sin(w * 2.9 * u + p2));
                sp[i] += amp * env * (0.7 * Math.sin(w * 4.7 * u + p3) + 0.3 * Math.sin(w * 3.3 * u + p4))
                        // look up and return (pitch Minecraft: less - higher)
                        - amp * 0.6 * rise * Math.exp(-u / (tau * 1.6));
                sr[i] += amp * 0.8 * env * Math.sin(w * 4.1 * u + p5);
                double push = jolt * (1 - Math.exp(-u / 0.8)) * Math.exp(-u / 3);
                x[i] += away.x * push;
                y[i] += away.y * push;
                z[i] += away.z * push;
            }
        }
        for (int i = 0; i < n; i++) {
            yaw[i] += Mth.clamp(sy[i], -5, 5);
            pitch[i] += Mth.clamp(sp[i], -5, 5);
            roll[i] += Mth.clamp(sr[i], -5, 5);
        }
    }

    /** Gaussian smoothing without lag; beyond the edges are extreme values. σ ≤ 0 - as is. */
    static double[] gauss(double[] v, double sigma) {
        if (sigma <= 0) return v;
        int h = (int) Math.ceil(sigma * 3), n = v.length;
        double[] k = new double[2 * h + 1];
        double sum = 0;
        for (int i = -h; i <= h; i++) sum += k[i + h] = Math.exp(-i * i / (2 * sigma * sigma));
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = -h; j <= h; j++) s += k[j + h] * v[Mth.clamp(i + j, 0, n - 1)];
            out[i] = s / sum;
        }
        return out;
    }

    /** Smooth noise: five sines in the band {@code fLo}–{@code fHi} Hz, range ~1/f, mean square – {@code rms}. */
    static double[] noise(Random r, int n, double rms, double fLo, double fHi) {
        double[] out = new double[n];
        if (rms <= 0) return out;
        int m = 5;
        double[] f = new double[m], ph = new double[m], amp = new double[m];
        double power = 0;
        for (int j = 0; j < m; j++) {
            f[j] = fLo * Math.pow(fHi / fLo, (j + r.nextDouble()) / m);
            ph[j] = r.nextDouble() * Math.PI * 2;
            amp[j] = 1 / f[j];
            power += amp[j] * amp[j] / 2;
        }
        double scale = rms / Math.sqrt(power);
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int j = 0; j < m; j++) s += amp[j] * Math.sin(2 * Math.PI * f[j] * i / 20 + ph[j]);
            out[i] = s * scale;
        }
        return out;
    }
}
