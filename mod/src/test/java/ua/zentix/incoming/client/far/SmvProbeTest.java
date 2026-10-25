package ua.zentix.incoming.client.far;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.util.List;
import java.util.Locale;

/** Временная проба SMV: что в кадре налёта «100» — места, порог лент, ленты по ширине, слияние, вуаль. */
class SmvProbeTest {
    static double px(FarView v, double x, double y, double z, double[] uv) {
        Vector3f f = v.forward(), l = v.left(), u = v.up();
        double d = x * f.x + y * f.y + z * f.z;
        uv[0] = (x * l.x + y * l.y + z * l.z) / d / SmokeFillTest.PIXEL;
        uv[1] = (x * u.x + y * u.y + z * u.z) / d / SmokeFillTest.PIXEL;
        return d;
    }

    static double lastMs;

    static double[] run(List<SmokeGrid.Source> raid, double now, FarView v, double budget, boolean pinScale) throws Exception {
        var scale = FarFlightView.class.getDeclaredField("puffScale");
        scale.setAccessible(true);
        var clubs = FarFlightView.class.getDeclaredField("clubs");
        clubs.setAccessible(true);
        SmokeFillTest.Screen s = new SmokeFillTest.Screen(v);
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        SmokePuffs.resetBudget();
        FarFlightView.reset();
        int want = 0;
        long nanos = 0;
        for (int frame = 0; frame < 40; frame++) {
            s.clear();
            long t0 = System.nanoTime();
            if (pinScale) {
                want = clubs.getInt(null);
                scale.setDouble(null, 1);
                clubs.setInt(null, 0);
            } else FarFlightView.budget();
            for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(v, s, b, now));
            SmokePuffs.flush();
            if (frame >= 20) nanos += System.nanoTime() - t0;
        }
        SmokePuffs.budget = was;
        lastMs = nanos / 20e6;
        return new double[]{SmokePuffs.lastRaw(), SmokePuffs.lastFloor(), SmokePuffs.lastLevel(), SmokePuffs.lastItems(), SmokePuffs.lastVeils(),
                SmokePuffs.lastPlaces(), clubs.getInt(null), scale.getDouble(null), s.quads.size(), s.polygons.size(), want};
    }

    static String bands(double[][] plan, boolean alive) {
        double[] edges = {25, 50, 100, 200, 400, 1e9};
        String[] kinds = {"blob", "place", "ribbon"};
        double[][] f = new double[3][edges.length];
        int[][] n = new int[3][edges.length];
        double lowSeen = 0, total = 0;
        for (double[] e : plan) {
            if (alive && (e[11] > 0 || e[10] >= 1)) continue;
            double d = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
            int b = 0;
            while (d > edges[b]) b++;
            int k = e[13] == Integer.MIN_VALUE ? 2 : e[13] < 0 ? 1 : 0;
            f[k][b] += e[6];
            n[k][b]++;
            total += e[6];
            if (e[8] * e[9] < 0.05) lowSeen += e[6];
        }
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "total %.1f (seen<0.05: %.1f)", total, lowSeen));
        for (int k = 0; k < 3; k++) {
            sb.append(" | ").append(kinds[k]);
            for (int b = 0; b < edges.length; b++) sb.append(String.format(Locale.ROOT, " %.1f/%d", f[k][b], n[k][b]));
        }
        return sb.toString();
    }

    static final java.io.File RASTER = new java.io.File("/mnt/data/projects/incoming/mod/run/smv/raster");

    /** Флаги опыта: mod/run/smv/flags.txt, «имя=значение» через пробел. */
    static String flag(String name, String def) {
        try {
            for (String w : java.nio.file.Files.readString(java.nio.file.Path.of("/mnt/data/projects/incoming/mod/run/smv/flags.txt")).trim().split("\\s+")) {
                int e = w.indexOf('=');
                if (e > 0 && w.substring(0, e).equals(name)) return w.substring(e + 1);
            }
        } catch (java.io.IOException ignored) {
        }
        return def;
    }

    static String tag = flag("tag", "main");

    @Test
    void raster() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        String[] cams = {"out", "edge", "in"};
        for (int c = 0; c < 3; c++) {
            FarView v = SmokeRaidTest.view(c);
            for (double b : new double[]{Double.POSITIVE_INFINITY, 16}) {
                SmokeFillTest.Screen s = SmokeRaidTest.frame(raid, now, v, b);
                float[] img = SmvRaster.opacity(s);
                String name = cams[c] + "_" + (b > 100 ? "free" : tag);
                SmvRaster.save(img, new java.io.File(RASTER, name + ".png"));
                double mean = 0;
                for (float x : img) mean += x / img.length;
                System.out.printf(Locale.ROOT, "SMVR %s: mean %.3f detail 2-8 %.4f 8-32 %.4f | fill %.1f floor %.1f level %d veils %d items %d%n", name, mean,
                        SmvRaster.detail(img, 2, 8), SmvRaster.detail(img, 8, 32), s.fill(), SmokePuffs.lastFloor(), SmokePuffs.lastLevel(),
                        SmokePuffs.lastVeils(), SmokePuffs.lastItems());
            }
        }
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    /** Эталон: без бюджета, мест без предела, порог лент не растёт (все клубы, как 3.7.0 без предела). */
    static SmokeFillTest.Screen reference(List<SmokeGrid.Source> raid, double now, FarView v) throws Exception {
        boolean a = SmokePuffs.ADAPT, d = SmokePuffs.FIT_DENSITY, m = SmokePuffs.NO_MERGE, rb = SmokePuffs.RIBBON_FIX;
        SmokePuffs.ADAPT = SmokePuffs.FIT_DENSITY = SmokePuffs.NO_MERGE = SmokePuffs.RIBBON_FIX = false;
        try {
            return reference0(raid, now, v);
        } finally {
            SmokePuffs.ADAPT = a;
            SmokePuffs.FIT_DENSITY = d;
            SmokePuffs.NO_MERGE = m;
            SmokePuffs.RIBBON_FIX = rb;
        }
    }

    static SmokeFillTest.Screen reference0(List<SmokeGrid.Source> raid, double now, FarView v) throws Exception {
        int was = SmokePuffs.PLACES;
        SmokePuffs.PLACES = 1_000_000;
        try {
            return screen(raid, now, v, Double.POSITIVE_INFINITY, true);
        } finally {
            SmokePuffs.PLACES = was;
        }
    }

    /** Кадр (40 кадров подряд) — его Screen. */
    static SmokeFillTest.Screen screen(List<SmokeGrid.Source> raid, double now, FarView v, double budget, boolean pinScale) throws Exception {
        var scale = FarFlightView.class.getDeclaredField("puffScale");
        scale.setAccessible(true);
        var clubs = FarFlightView.class.getDeclaredField("clubs");
        clubs.setAccessible(true);
        SmokeFillTest.Screen s = new SmokeFillTest.Screen(v);
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        SmokePuffs.resetBudget();
        FarFlightView.reset();
        try {
            long t0 = 0, sum = 0;
            for (int frame = 0; frame < 40; frame++) {
                s.clear();
                t0 = System.nanoTime();
                if (pinScale) {
                    scale.setDouble(null, 1);
                    clubs.setInt(null, 0);
                } else FarFlightView.budget();
                for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(v, s, b, now));
                SmokePuffs.flush();
                if (frame >= 20) sum += System.nanoTime() - t0;
            }
            lastMs = sum / 20e6;
        } finally {
            SmokePuffs.budget = was;
        }
        return s;
    }

    static final String[] CAMS = {"out", "edge", "in"};

    @Test
    void ssim() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        boolean pin = Boolean.parseBoolean(flag("pin", "false"));
        SmokePuffs.ADAPT = Boolean.parseBoolean(flag("adapt", "false"));
        SmokePuffs.FIT_DENSITY = Boolean.parseBoolean(flag("density", "false"));
        SmokePuffs.NO_MERGE = Boolean.parseBoolean(flag("nomerge", "false"));
        SmokePuffs.RIBBON_FIX = Boolean.parseBoolean(flag("ribbon", "false"));
        int placesWas = SmokePuffs.PLACES;
        SmokePuffs.PLACES = Integer.parseInt(flag("places", "3000"));
        int clubWas = FarFlightView.CLUB_BUDGET;
        FarFlightView.CLUB_BUDGET = Integer.parseInt(flag("clubs", "3000"));
        for (int c = 0; c < 3; c++) {
            FarView v = SmokeRaidTest.view(c);
            SmokeFillTest.Screen ref = reference(raid, now, v);
            float[] r = SmvRaster.opacity(ref);
            double refFill = ref.fill();
            SmvRaster.save(r, new java.io.File(RASTER, CAMS[c] + "_ref.png"));
            SmokeFillTest.Screen s = screen(raid, now, v, 16, pin);
            float[] b = SmvRaster.opacity(s);
            SmvRaster.save(b, new java.io.File(RASTER, CAMS[c] + "_" + tag + ".png"));
            double mr = 0, mb = 0;
            for (int i = 0; i < r.length; i++) {
                mr += r[i] / r.length;
                mb += b[i] / b.length;
            }
            System.out.printf(Locale.ROOT, "SMVQ %s %s: ssim %.3f | mean ref %.3f %s %.3f | detail 2-8 ref %.4f %.4f, 8-32 ref %.4f %.4f | fill ref %.1f %s %.1f, level %d veils %d items %d%n",
                    CAMS[c], tag, SmvRaster.ssim(r, b), mr, tag, mb, SmvRaster.detail(r, 2, 8), SmvRaster.detail(b, 2, 8), SmvRaster.detail(r, 8, 32),
                    SmvRaster.detail(b, 8, 32), refFill, tag, s.fill(), SmokePuffs.lastLevel(), SmokePuffs.lastVeils(), SmokePuffs.lastItems());
            System.out.printf(Locale.ROOT, "SMVQ   cpu %.2f ms places %d%n", lastMs, SmokePuffs.lastPlaces());
        }
        SmokePuffs.ADAPT = SmokePuffs.FIT_DENSITY = SmokePuffs.NO_MERGE = SmokePuffs.RIBBON_FIX = false;
        SmokePuffs.PLACES = placesWas;
        FarFlightView.CLUB_BUDGET = clubWas;
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    @Test
    void segments() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        for (int c = 0; c < 3; c++) {
            List<double[]> segs = new java.util.ArrayList<>();
            FarView v = SmokeRaidTest.view(c);
            run(raid, now, v, 16, true);
            FarFlightView.SEG = segs::add;
            run1(raid, now, v);
            FarFlightView.SEG = null;
            double[] wEdges = {4.8, 10, 20, 40, 80, 160, 320, 1e9};
            int[] nSeg = new int[wEdges.length];
            double[] places = new double[wEdges.length], ideal = new double[wEdges.length], fillQuad = new double[wEdges.length];
            for (double[] g : segs) {
                double tx = g[3] - g[0], ty = g[4] - g[1], tz = g[5] - g[2], len = Math.sqrt(tx * tx + ty * ty + tz * tz);
                double sa = g[6], sb = g[7], ea = g[11], eb = g[12];
                double px = Math.max(g[8], g[9]) / Math.max(g[10] * g[13], 1e-9);
                double step = Math.min(SmokePuffs.tubeStep(sa, ea / (Math.sqrt(2 * Math.PI) * sa)), SmokePuffs.tubeStep(sb, eb / (Math.sqrt(2 * Math.PI) * sb)));
                double level = SmokePuffs.tubeLevel(len, step / SmokePuffs.SEGMENT_STEP);
                int n = 1 << SmokePuffs.tubeDepth(level);
                // места через шаг по своей σ: ∫dx/шаг(σ(x)), шаг ∝ σ
                double ratio = step / Math.min(sa, sb), id = Math.abs(sb - sa) < 1e-6 ? len / (ratio * sa) : len / (ratio * (sb - sa)) * Math.log(Math.max(sa, sb) / Math.min(sa, sb)) * Math.signum(sb - sa) * Math.signum(sb - sa);
                if (FarFlightView.puffShare(Math.max(g[8], g[9]), g[10], g[13]) <= 0) continue;
                int b = 0;
                while (px > wEdges[b]) b++;
                nSeg[b]++;
                places[b] += n;
                ideal[b] += Math.max(1, id);
            }
            StringBuilder sb = new StringBuilder();
            double cum = 0;
            for (int b = wEdges.length - 1; b >= 0; b--) {
                cum += places[b];
                sb.append(String.format(Locale.ROOT, " [>%s px: %d seg, %.0f places (ideal %.0f), cum %.0f]", b == 0 ? "2.4" : String.valueOf(wEdges[b - 1]), nSeg[b], places[b], ideal[b], cum));
            }
            System.out.println("SMVS " + SmokeRaidTest.NAMES[c] + " segments " + segs.size() + ":" + sb);
        }
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    /** Ещё один кадр с прибитым порогом (после run). */
    static void run1(List<SmokeGrid.Source> raid, double now, FarView v) throws Exception {
        var scale = FarFlightView.class.getDeclaredField("puffScale");
        scale.setAccessible(true);
        scale.setDouble(null, 1);
        SmokeFillTest.Screen s = new SmokeFillTest.Screen(v);
        double was = SmokePuffs.budget;
        SmokePuffs.budget = 16;
        for (SmokeGrid.Source b : raid) b.emit(now, SmokePuffs.sink(v, s, b, now));
        SmokePuffs.flush();
        SmokePuffs.budget = was;
    }

    /** По полосам дальности: живые записи и их заливка на стадии (dead/veil учитываются). */
    static String stage(double[][] plan) {
        double[] edges = {25, 50, 100, 200, 400, 1e9};
        StringBuilder sb = new StringBuilder();
        for (int b = 0; b < edges.length; b++) {
            int alive = 0, dead = 0, veiled = 0;
            double fa = 0, fv = 0;
            for (double[] e : plan) {
                double d = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
                int k = 0;
                while (d > edges[k]) k++;
                if (k != b) continue;
                if (e[11] > 0) dead++;
                else if (e[10] >= 1) {
                    veiled++;
                    fv += e[6];
                } else {
                    alive++;
                    fa += e[6];
                }
            }
            sb.append(String.format(Locale.ROOT, " [%s: live %d %.1f scr, veil %d (%.1f scr), dead %d]", b == 5 ? "far" : "<" + (int) edges[b], alive, fa, veiled, fv, dead));
        }
        return sb.toString();
    }

    @Test
    void stages() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        double[][][] got = new double[4][][];
        SmokePuffs.PROBE = (d, st) -> got[st] = SmokePuffs.probePlan(d);
        SmokePuffs.ADAPT = Boolean.parseBoolean(flag("adapt", "false"));
        SmokePuffs.RIBBON_FIX = Boolean.parseBoolean(flag("ribbon", "false"));
        SmokePuffs.FIT_DENSITY = Boolean.parseBoolean(flag("density", "false"));
        SmokePuffs.PLACES = FarFlightView.CLUB_BUDGET = Integer.parseInt(flag("places", "3000"));
        System.out.println("SMVT flags " + flag("tag", "?"));
        try {
            for (int c = 0; c < 3; c++) {
                run(raid, now, SmokeRaidTest.view(c), 16, false);
                System.out.printf(Locale.ROOT, "SMVT %s%n  plan  %s%n  hide  %s%n  merge %s%n  fit   %s%n", SmokeRaidTest.NAMES[c], stage(got[0]), stage(got[2]),
                        stage(got[3]), stage(got[1]));
            }
        } finally {
            SmokePuffs.PROBE = null;
            SmokePuffs.PLACES = FarFlightView.CLUB_BUDGET = 3000;
            SmokePuffs.resetBudget();
            FarFlightView.reset();
        }
    }

    @Test
    void where() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        double[][][] got = new double[2][][];
        SmokePuffs.PROBE = (d, stage) -> got[stage] = SmokePuffs.probePlan(d);
        try {
            for (int c = 0; c < 3; c++)
                for (boolean pin : new boolean[]{false, true}) {
                    run(raid, now, SmokeRaidTest.view(c), 16, pin);
                    System.out.printf(Locale.ROOT, "SMVW %s pin=%s bands <25/<50/<100/<200/<400/far%n  raw   %s%n  after %s%n", SmokeRaidTest.NAMES[c], pin,
                            bands(got[0], false), bands(got[1], true));
                }
        } finally {
            SmokePuffs.PROBE = null;
            SmokePuffs.resetBudget();
            FarFlightView.reset();
        }
    }

    @Test
    void adapt() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        for (boolean a : new boolean[]{false, true}) {
            SmokePuffs.ADAPT = a;
            int was = SmokePuffs.PLACES;
            SmokePuffs.PLACES = 1_000_000;
            for (int c = 0; c < 3; c++) {
                double[] r = run(raid, now, SmokeRaidTest.view(c), Double.POSITIVE_INFINITY, true);
                System.out.printf(Locale.ROOT, "SMVA adapt=%s %s: places %.0f raw %.1f quads %.0f strips %.0f%n", a, SmokeRaidTest.NAMES[c], r[5], r[0], r[8], r[9]);
            }
            SmokePuffs.PLACES = was;
        }
        SmokePuffs.ADAPT = false;
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    @Test
    void cpu() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        for (int rep = 0; rep < 2; rep++)
            for (String mode : new String[]{"main", "adapt3000", "adaptAll"}) {
                SmokePuffs.ADAPT = !mode.equals("main");
                int was = SmokePuffs.PLACES;
                if (mode.equals("adaptAll")) SmokePuffs.PLACES = 1_000_000;
                StringBuilder sb = new StringBuilder();
                for (int c = 0; c < 3; c++) {
                    double[] r = run(raid, now, SmokeRaidTest.view(c), 16, !mode.equals("main"));
                    sb.append(String.format(Locale.ROOT, " %s %.2f ms (places %.0f)", SmokeRaidTest.NAMES[c], lastMs, r[5]));
                }
                SmokePuffs.PLACES = was;
                if (rep == 1) System.out.println("SMVC " + mode + ":" + sb);
            }
        SmokePuffs.ADAPT = false;
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    @Test
    void parts() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        List<SmokeGrid.Source> strikes = raid.stream().filter(x -> x instanceof SmokeRaidTest.Strike).toList();
        List<SmokeGrid.Source> fires = raid.stream().filter(x -> x instanceof SmokeRaidTest.Fire).toList();
        for (int c = 0; c < 3; c++) {
            FarView v = SmokeRaidTest.view(c);
            for (var e : java.util.Map.of("all", raid, "strikes", strikes, "fires", fires).entrySet())
                for (boolean pin : new boolean[]{false, true}) for (double b : new double[]{Double.POSITIVE_INFINITY, 16}) {
                    if (!e.getKey().equals("all") && b < 100) continue;
                    double[] r = run(e.getValue(), now, v, b, pin);
                    System.out.printf(Locale.ROOT, "SMVP %s %s pin=%s budget=%s: %.2f ms raw %.1f floor %.1f level %.0f items %.0f veils %.0f places %.0f clubs %.0f scale %.1f quads %.0f strips %.0f wanted %.0f%n",
                            SmokeRaidTest.NAMES[c], e.getKey(), pin, b, lastMs, r[0], r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8], r[9], r[10]);
                }
        }
        SmokePuffs.resetBudget();
        FarFlightView.reset();
    }

    @Test
    void probe() throws Exception {
        double now = 1000;
        List<SmokeGrid.Source> raid = SmokeRaidTest.raid(now);
        var scale = FarFlightView.class.getDeclaredField("puffScale");
        scale.setAccessible(true);
        var clubs = FarFlightView.class.getDeclaredField("clubs");
        clubs.setAccessible(true);
        for (int c = 0; c < 3; c++) {
            FarView v = SmokeRaidTest.view(c);
            for (double budget : new double[]{Double.POSITIVE_INFINITY, SmokePuffs.FILL_BUDGET}) {
                SmokeFillTest.Screen s = SmokeRaidTest.frame(raid, now, v, budget);
                int strips = 0, wide = 0, flat = 0;
                double stripFill = 0, wideFill = 0, maxW = 0;
                double[] a = new double[2], b = new double[2];
                for (double[] p : s.polygons) {
                    if (SmokeFillTest.isFlat(p)) {
                        flat++;
                        continue;
                    }
                    strips++;
                    px(v, p[0], p[1], p[2], a);
                    px(v, p[3], p[4], p[5], b);
                    double w = Math.hypot(a[0] - b[0], a[1] - b[1]);
                    double fill = SmokePuffs.polygonFill(v, java.util.Arrays.copyOf(p, 12));
                    stripFill += fill;
                    maxW = Math.max(maxW, w);
                    if (w > 2 * FarFlightView.PUFFS_FULL) {
                        wide++;
                        wideFill += fill;
                    }
                }
                int stretched = 0;
                double quadFill = 0;
                for (double[] q : s.quads) {
                    double ar = Math.max(q[2], q[3]) / Math.max(1e-9, Math.min(q[2], q[3]));
                    if (ar > 3) stretched++;
                    quadFill += 4 * q[2] * q[3] / (4 * SmokeFillTest.W * SmokeFillTest.H);
                }
                System.out.printf(Locale.ROOT,
                        "SMV %s budget %s: puffScale %.2f clubs %d places %d blobs %d | raw %.1f floor %.1f level %d share %.2f items %d veils %d | quads %d (stretched>3: %d, fill~%.1f) flat %d | strips %d fill %.2f, wider than %.1f px: %d fill %.2f, max %.0f px%n",
                        SmokeRaidTest.NAMES[c], budget == SmokePuffs.FILL_BUDGET ? "16" : "inf", scale.getDouble(null), clubs.getInt(null),
                        SmokePuffs.lastPlaces(), SmokePuffs.lastBlobs(), SmokePuffs.lastRaw(), SmokePuffs.lastFloor(), SmokePuffs.lastLevel(),
                        SmokePuffs.lastShare(), SmokePuffs.lastItems(), SmokePuffs.lastVeils(), s.quads.size(), stretched, quadFill, flat, strips,
                        stripFill, 2 * FarFlightView.PUFFS_FULL, wide, wideFill, maxW);
            }
        }
        SmokePuffs.resetBudget();
    }
}
