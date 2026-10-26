package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Постоянство клубов у границы кучки 1↔2 и ориентация лежащих клубов в мире, без видеокарты. */
class SmokeTargetedProbe {
    static SmokeGrid.Source blob(double x, double y, double z, double sh, double sv, double tau, long seed) {
        double soot = tau * 2 * Math.PI * sh * sv / SmokeGrid.SOOT_K;
        return new SmokeGrid.Source() {
            @Override
            public boolean emit(double now, SmokeGrid.Sink out) {
                out.blob(x, y, z, sh, sv, soot, 0, 0, 0.6f, 0.55f, 0.45f, 300, 0);
                return true;
            }
            @Override
            public long seed() { return seed; }
        };
    }

    static SmokeFillTest.Screen frame(SmokeGrid.Source source, FarView view, double budget) {
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        try {
            SmokePuffs.resetBudget();
            FarFlightView.reset();
            SmokeFillTest.Screen s = new SmokeFillTest.Screen(view);
            for (int i = 0; i < 3; i++) {
                s.clear();
                FarFlightView.budget();
                source.emit(0, SmokePuffs.sink(view, s, source, 0));
                SmokePuffs.flush();
            }
            return s;
        } finally {
            SmokePuffs.budget = was;
        }
    }

    @Test
    void clusterBoundary() throws IOException {
        FarView v = SmokeFillTest.view(new Vec3(0, 10, 0), new Vector3f(0, 0, 1));
        double sigma = 2, tau = 1, radius = sigma * SmokePuffs.edge(tau), d = radius / (40 * v.pixel());
        double[][] prof = PlumeContinuityTest.profiles();
        for (double budget : new double[]{Double.POSITIVE_INFINITY, 16}) {
            double max = 0, sum = 0, min = 1;
            int n = 0;
            for (long seed = 1; seed <= 16; seed++) {
                SmokeFillTest.Screen a = frame(blob(0, 10, d * (1 + 1e-6), sigma, sigma, tau, seed), v, budget);
                SmokeFillTest.Screen b = frame(blob(0, 10, d * (1 - 1e-6), sigma, sigma, tau, seed), v, budget);
                double peak = 0, mean = 0;
                for (int i = 0; i < 81; i++) for (int j = 0; j < 81; j++) {
                    double u = (2 * (i + 0.5) / 81 - 1) * 1.3 * radius / d, w = (2 * (j + 0.5) / 81 - 1) * 1.3 * radius / d;
                    double delta = Math.abs(a.transmittance(u, w, prof) - b.transmittance(u, w, prof));
                    peak = Math.max(peak, delta);
                    mean += delta / (81 * 81);
                }
                assertTrue(peak < 4.0 / 255, "Cluster 1↔2 changed transmittance by " + peak + " at seed " + seed);
                System.out.printf(Locale.ROOT, "THRESHOLD budget=%s seed=%d pixels=39.99996/40.00004 quads=%d/%d mean=%.9f max=%.9f%n",
                        Double.isInfinite(budget) ? "unlimited" : "16", seed, a.quads.size(), b.quads.size(), mean, peak);
                max = Math.max(max, peak); min = Math.min(min, peak); sum += mean; n++;
            }
            System.out.printf(Locale.ROOT, "THRESHOLD_SUMMARY budget=%s seeds=%d mean=%.9f peak_min=%.9f peak_max=%.9f%n",
                    Double.isInfinite(budget) ? "unlimited" : "16", n, sum / n, min, max);
        }
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    static Map<String, double[]> flats(SmokeFillTest.Screen s, Vec3 eye) {
        Map<String, double[]> out = new HashMap<>();
        for (double[] p : s.polygons) {
            if (!SmokeFillTest.isFlat(p)) continue;
            double cx = 0.5 * (p[0] + p[6]) + eye.x, cy = p[1] + eye.y, cz = 0.5 * (p[2] + p[8]) + eye.z;
            String key = Math.round(cx * 1e5) + ":" + Math.round(cy * 1e5) + ":" + Math.round(cz * 1e5);
            double[] world = p.clone();
            for (int i = 0; i < 4; i++) { world[3 * i] += eye.x; world[3 * i + 1] += eye.y; world[3 * i + 2] += eye.z; }
            out.put(key, world);
        }
        return out;
    }

    @Test
    void flatOrientation() throws IOException {
        Vec3 eye = new Vec3(0, 5, 0);
        Vector3f f = new Vector3f(0, -5, 40).normalize();
        SmokeGrid.Source source = blob(0, 0, 40, 10, 0.1, 0.7, 17);
        double angle = Math.toRadians(0.5);
        Vector3f yaw = new Vector3f((float) (-f.z * Math.sin(angle)), f.y, (float) (f.z * Math.cos(angle)));
        for (double budget : new double[]{Double.POSITIVE_INFINITY, 16}) {
            SmokeFillTest.Screen a = frame(source, SmokeFillTest.view(eye, f), budget);
            SmokeFillTest.Screen b = frame(source, SmokeFillTest.view(eye, yaw), budget);
            Map<String, double[]> aa = flats(a, eye), bb = flats(b, eye);
            double worstAngle = 0, worstCorner = 0;
            int matched = 0;
            for (Map.Entry<String, double[]> e : aa.entrySet()) {
                double[] p = e.getValue(), q = bb.get(e.getKey());
                if (q == null) continue;
                matched++;
                double pAngle = Math.atan2(p[11] - p[2], p[9] - p[0]), qAngle = Math.atan2(q[11] - q[2], q[9] - q[0]);
                worstAngle = Math.max(worstAngle, Math.abs(Math.IEEEremainder(qAngle - pAngle, Math.PI)));
                for (int i = 0; i < 4; i++) {
                    double dx = p[3 * i] - q[3 * i], dy = p[3 * i + 1] - q[3 * i + 1], dz = p[3 * i + 2] - q[3 * i + 2];
                    worstCorner = Math.max(worstCorner, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }
            assertTrue(matched > 0 && worstAngle < 1e-8 && worstCorner < 1e-8, "Flat puffs turned or moved with camera yaw");
            System.out.printf(Locale.ROOT, "FLAT_ORIENTATION budget=%s polygons=%d/%d matched=%d yaw=0.5deg axis_max_deg=%.9f corner_shift_max_m=%.9f%n",
                    Double.isInfinite(budget) ? "unlimited" : "16", aa.size(), bb.size(), matched, Math.toDegrees(worstAngle), worstCorner);
        }
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    /** Старое условие lies: sh·|dy| = sv·distance. Меняется высота глаза всего на два микрометра. */
    @Test
    void flatBoundary() throws IOException {
        double sh = 10, sv = 1, z = 100, height = z * sv / Math.sqrt(sh * sh - sv * sv);
        Vec3 midEye = new Vec3(0, height, 0), lowEye = new Vec3(0, height - 1e-6, 0), highEye = new Vec3(0, height + 1e-6, 0);
        FarView mid = SmokeFillTest.view(midEye, new Vector3f(0, (float) -height, (float) z));
        double extent = 1.3 * sh * SmokePuffs.edge(0.7) / z;
        double[][] profiles = PlumeContinuityTest.profiles();
        for (double budget : new double[]{Double.POSITIVE_INFINITY, 16}) {
            double max = 0, sum = 0;
            for (long seed = 1; seed <= 8; seed++) {
                SmokeGrid.Source source = blob(0, 0, z, sh, sv, 0.7, seed);
                SmokeFillTest.Screen a = frame(source, SmokeFillTest.view(lowEye, new Vector3f(0, (float) -lowEye.y, (float) z)), budget);
                SmokeFillTest.Screen b = frame(source, SmokeFillTest.view(highEye, new Vector3f(0, (float) -highEye.y, (float) z)), budget);
                Vector3f f = mid.forward(), l = mid.left(), up = mid.up();
                double peak = 0, mean = 0;
                for (int i = 0; i < 81; i++) for (int j = 0; j < 81; j++) {
                    double u = (2 * (i + 0.5) / 81 - 1) * extent, v = (2 * (j + 0.5) / 81 - 1) * extent;
                    double rx = f.x + u * l.x + v * up.x, ry = f.y + u * l.y + v * up.y, rz = f.z + u * l.z + v * up.z;
                    double delta = Math.abs(a.transmittanceRay(rx, ry, rz, profiles) - b.transmittanceRay(rx, ry, rz, profiles));
                    peak = Math.max(peak, delta);
                    mean += delta / (81 * 81);
                }
                assertTrue(peak < 4.0 / 255, "Flat/billboard boundary changed transmittance by " + peak + " at seed " + seed);
                max = Math.max(max, peak);
                sum += mean;
            }
            System.out.printf(Locale.ROOT, "FLAT_BOUNDARY budget=%s height=%.9f delta_eye_m=0.000002 seeds=8 mean=%.9f max=%.9f%n",
                    Double.isInfinite(budget) ? "unlimited" : "16", height, sum / 8, max);
        }
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    /** Дальний гаусс меньше порога кучки: единственный клуб хранит размер, но рисунок и отражение — по своему зерну. */
    @Test
    void distantSinglePuffsKeepSeededVariation() {
        FarView view = SmokeFillTest.view(new Vec3(0, 10, 0), new Vector3f(0, 0, 1));
        double expectedHalf = 2 * SmokePuffs.edge(1);
        for (double budget : new double[]{Double.POSITIVE_INFINITY, 16}) {
            java.util.Set<Integer> frames = new java.util.HashSet<>();
            java.util.Set<Boolean> mirrors = new java.util.HashSet<>();
            Map<String, Integer> pairs = new java.util.TreeMap<>();
            int most = 0;
            for (long seed = 1; seed <= 32; seed++) {
                SmokeFillTest.Screen screen = frame(blob(0, 10, 1000, 2, 2, 1, seed), view, budget);
                assertTrue(screen.puffs.size() == 1, "The distant fixture must draw exactly one puff");
                SmokeFillTest.Puff puff = screen.puffs.getFirst();
                int frame = puff.frame;
                boolean mirror = puff.sprite.equals(ua.zentix.incoming.client.fx.layer.FxAtlas.puff6(frame, true));
                frames.add(frame);
                mirrors.add(mirror);
                most = Math.max(most, pairs.merge(frame + ":" + mirror, 1, Integer::sum));
                double[] p = puff.vertices;
                double ax = p[9] - p[0], ay = p[10] - p[1], az = p[11] - p[2];
                double half = 0.5 * Math.sqrt(ax * ax + ay * ay + az * az);
                assertTrue(Math.abs(half - expectedHalf) < 1e-9, "Seed changed the size of a single puff");
            }
            assertTrue(frames.size() >= 2 && mirrors.size() == 2 && most < 32,
                    "All distant single puffs use one frame or mirror: " + pairs);
            System.out.printf(Locale.ROOT, "DISTANT_SINGLE budget=%s seeds=32 frames=%s mirrors=%s most_common_pair=%d/32 pairs=%s%n",
                    Double.isInfinite(budget) ? "unlimited" : "16", frames, mirrors, most, pairs);
        }
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }
}
