package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.strike.WeaponType;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Regression cases from review R483 round 2; source cost uses a deterministic clock for CI. */
// One of the three slowest unit test classes (1–3 min): CI runs each in its own JVM (build.gradle, testGroup).
@Tag("slow")
class R483Round2Test {
    static final class Blast implements SmokeGrid.Source {
        final CloudModel model; final double born, x; final long[] clock;
        double lastSampled = Double.NaN; boolean drawn;
        Blast(WeaponType w, int kind, double born, double x, long[] clock) {
            this.born = born; this.x = x; this.clock = clock;
            model = model(w, kind);
        }
        @Override public double age(double s) { return s - born; }
        @Override public boolean bounds(double s, SmokeBounds out) {
            double t = s - born;
            model.bounds(t, -64, 320, out); model.condensationBounds(t, out); out.move(x, 0, 0); return true;
        }
        @Override public boolean budgeted() { return true; }
        @Override public void maintain(double s) { model.maintain(s - born); }
        @Override public boolean emit(double s, SmokeGrid.Sink out) {
            clock[0] += 150_000; // measured-order source cost, independent of runner speed
            double t = s - born;
            if (t >= model.life) return false;
            model.blobs(t, (bx, by, bz, sh, sv, soot, d, w, k, rise) -> out.blob(x + bx, by, bz, sh, sv, soot, d, w, 1, 1, 1, k, rise));
            return true;
        }
    }

    static CloudModel model(WeaponType w, int kind) {
        var ground = new GroundDust.Ground(GroundDust.Ground.filled(1), GroundDust.Ground.filled(0),
                GroundDust.Ground.filled(0), GroundDust.Ground.filled(0),
                (gx, gz) -> kind == 1 ? (Math.abs(gx) % 30 < 8 ? 0 : 20) : kind == 0 ? 0 : 20 * Math.sin(gx / 40) * Math.cos(gz / 40));
        var site = kind == 1 ? new ua.zentix.incoming.util.LocalWind(0, 15, 20, 10, 1, 2, .6, 30) : ua.zentix.incoming.util.LocalWind.OPEN;
        return new CloudModel(BlastSource.of(w, false), new CloudModel.Air(0.01, 5, -2, 0.2, 3, 0.6, site), false, 0, ground);
    }

    @Test void flickerAndTurn() {
        WeaponType[] ws = {WeaponType.MISSILE, WeaponType.DRONE, WeaponType.ROCKET, WeaponType.LOITER};
        for (int n : new int[]{100, 128}) for (double fps : new double[]{60, 30, 20}) {
            var prep = new SmokePreparation();
            long[] clock = {0};
            List<SmokeGrid.Source> sources = new ArrayList<>();
            var blasts = new ArrayList<Blast>();
            for (int i = 0; i < n; i++) {
                var b = new Blast(ws[i % 4], i % 2 + 1, i * 1.0, i % 2 == 0 ? 0 : 100_000, clock);
                blasts.add(b); sources.add(b);
            }
            double dt = 1.0 / fps, s = 0;
            boolean[] behind = {true};
            java.util.function.Predicate<SmokeBounds> visible = bb -> bb.minX < 50_000 || behind[0];
            SmokePreparation.Draw draw = (src, at) -> { ((Blast) src).lastSampled = at; ((Blast) src).drawn = true;
                return (x,y,z,ax,ay,az,sh,sv,so,d,w,r,g,b,k,rise) -> {}; };
            // raid, everything visible; measure blinking over the last 20 s
            long frames = 0, blinks = 0, blinkFrames = 0; double prepNs = 0, prepMax = 0;
            double raidEnd = Math.min(n, 128) + 30;
            for (; s < raidEnd; s += dt) {
                for (Blast b : blasts) b.drawn = false;
                long t0 = System.nanoTime();
                prep.draw(sources, Set.of(), s, 0, 0, 0, visible, draw, () -> clock[0]);
                double ns = System.nanoTime() - t0;
                if (s > raidEnd - 20) {
                    frames++; prepNs += ns; prepMax = Math.max(prepMax, ns);
                    int k = 0;
                    for (Blast b : blasts) if (sources.contains(b) && !b.drawn && !Double.isNaN(b.lastSampled)) k++;
                    blinks += k; if (k > 0) blinkFrames++;
                }
            }
            assertEquals(0, blinks, "continuously visible clouds must be drawn every frame: n=" + n + ", fps=" + fps);
            // hide the far half for 120 s, then turn back
            behind[0] = false;
            for (double end = s + 120; s < end; s += dt) prep.draw(sources, Set.of(), s, 0, 0, 0, visible, draw, () -> clock[0]);
            behind[0] = true;
            double turnedAt = s;
            int allBack = -1; double worstStale = 0; double turnMax = 0;
            for (int f = 0; f < (int) (5 * fps); f++, s += dt) {
                for (Blast b : blasts) b.drawn = false;
                long t0 = System.nanoTime();
                prep.draw(sources, Set.of(), s, 0, 0, 0, visible, draw, () -> clock[0]);
                turnMax = Math.max(turnMax, System.nanoTime() - t0);
                int missing = 0;
                for (Blast b : blasts) {
                    if (!sources.contains(b)) continue;
                    if (b.x == 0) assertTrue(b.drawn, "foreground cloud disappeared during a turn");
                    if (b.x > 0 && b.drawn) assertTrue(b.lastSampled >= turnedAt - SmokePreparation.MAX_SAMPLE_AGE,
                            "returned cloud replayed a pre-hide snapshot");
                    if (!b.drawn) missing++; else worstStale = Math.max(worstStale, s - b.lastSampled);
                }
                if (allBack < 0 && missing == 0) allBack = f;
            }
            assertTrue(allBack >= 0 && allBack < fps, "return must complete within one second, n=" + n + ", fps=" + fps);
            System.out.printf(java.util.Locale.ROOT,
                    "R483R2 n=%d fps=%.0f emit=%dus (deterministic clock): steady %d/%d frames with a blinking cloud, %.2f clouds/frame not drawn; prep mean %.2f max %.2f ms | turn: all drawn at frame %d, max drawn age %.3f s, max prep %.2f ms%n",
                    n, fps, 150, blinkFrames, frames, (double) blinks / frames, prepNs / frames / 1e6, prepMax / 1e6, allBack, worstStale, turnMax / 1e6);
        }
    }

    /** Same fine-path law after a hidden gap and during continuous viewing, including thirty seconds after return. */
    @Test void hiddenReturnMatchesContinuousPath() {
        for (WeaponType w : new WeaponType[]{WeaponType.MISSILE, WeaponType.DRONE, WeaponType.ROCKET, WeaponType.LOITER})
            for (int kind = 0; kind < 3; kind++) for (double[] gap : new double[][]{{5, 30}, {30, 120}, {60, 300}, {120, 600}}) {
                var cont = model(w, kind); var ret = model(w, kind);
                double t0 = gap[0], t1 = gap[1];
                for (double t = 1.0 / 30; t < t1; t += 1.0 / 30) {
                    cont.blobs(t, (x,y,z,sh,sv,s,d,wa,k,r) -> {});
                    if (t < t0) ret.blobs(t, (x,y,z,sh,sv,s,d,wa,k,r) -> {});
                }
                for (double extra : new double[]{0, 30}) {
                    double t = t1 + extra;
                    if (extra > 0) for (double u = t1; u < t; u += 1.0 / 30) {
                        cont.blobs(u, (x,y,z,sh,sv,s,d,wa,k,r) -> {}); ret.blobs(u, (x,y,z,sh,sv,s,d,wa,k,r) -> {});
                    }
                    var a = new ArrayList<double[]>(); var b = new ArrayList<double[]>();
                    cont.blobs(t, (x,y,z,sh,sv,s,d,wa,k,r) -> a.add(new double[]{x,y,z,sh,sv,d}));
                    ret.blobs(t, (x,y,z,sh,sv,s,d,wa,k,r) -> b.add(new double[]{x,y,z,sh,sv,d}));
                    assertEquals(a.size(), b.size(), "return changed the cloud component count");
                    double maxD = 0, maxRel = 0, wsum = 0, wd = 0, maxDy = 0;
                    for (int i = 0; i < a.size(); i++) {
                        double[] p = a.get(i), q = b.get(i);
                        double dd = Math.sqrt((p[0]-q[0])*(p[0]-q[0]) + (p[1]-q[1])*(p[1]-q[1]) + (p[2]-q[2])*(p[2]-q[2]));
                        maxD = Math.max(maxD, dd); maxDy = Math.max(maxDy, Math.abs(p[1]-q[1]));
                        maxRel = Math.max(maxRel, dd / Math.max(1e-6, Math.max(p[3], p[4])));
                        wsum += p[5]; wd += p[5] * dd;
                    }
                    String context = w + " terrain=" + kind + " hidden=" + t0 + ".." + t1 + " extra=" + extra;
                    assertTrue(wsum == 0 || wd / wsum <= 1, context + ": mass-weighted displacement exceeds one metre");
                    assertTrue(maxRel <= 1, context + ": displacement exceeds one sigma");
                    System.out.printf(java.util.Locale.ROOT, "R483SHIFT %s kind %d hidden %.0f-%.0f s, +%.0f s after return: max %.6f m (dy %.6f m, %.6f sigma), mass-weighted %.6f m, %d blobs%n",
                            w, kind, t0, t1, extra, maxD, maxDy, maxRel, wsum > 0 ? wd / wsum : 0, a.size());
                }
            }
    }
}
