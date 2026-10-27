package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.FireSmoke;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fire plume is one continuous column at any range (22.10 complaint: fuel-pool smoke from 1–3 km was a chain of separate
 * clumps). Same game code lays plume ({@link FireSmoke#lay}), same effects-layer sink renders it
 * ({@link SmokePuffs#sink}: ribbons and clumps, as projectile trail); custom sink writes quads, from which screen-axis
 * opacity is composed: clump uses its baked-frame radial opacity profile; ribbon uses
 * axial opacity. A gap is a valley: axial opacity below half the smaller of the
 * maxima on both sides within four cross-section σ (at least 8 pixels), with eye-visible difference (above Koschmieder
 * contrast threshold, {@link Sight#THRESHOLD}): bead after bead. Continuous plume thins smoothly along axis.
 */
class PlumeContinuityTest {
    /** Pixel angle: 70° over 1080 rows. */
    private static final double PIXEL = Math.toRadians(70) / 1080;
    /** Clump radial opacity-profile step (quad half-size share). */
    static final double BIN = 0.02;

    /** Clump-frame radial opacity profiles (quad half-size share, {@link #BIN} step). */
    static double[][] profiles() throws IOException {
        if (profiles != null) return profiles;
        int bins = (int) Math.round(1.5 / BIN);
        double[][] out = new double[FxAtlas.PUFF6_FRAMES][bins];
        for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
            BufferedImage img = image(String.format(Locale.ROOT, "textures/fx/particle/puff6a_%02d.png", f));
            int n = img.getWidth();
            double[] sum = new double[bins], count = new double[bins];
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double r = Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) / (n / 2.0);
                    int k = (int) (r / BIN);
                    if (k >= bins) continue;
                    sum[k] += (img.getRGB(x, y) >>> 24) / 255.0;
                    count[k]++;
                }
            }
            for (int k = 0; k < bins; k++) out[f][k] = count[k] > 0 ? sum[k] / count[k] : 0;
        }
        return profiles = out;
    }

    private static double[][] profiles;

    /** Clump frame by image (original and mirrored count as one frame). */
    static int frameOf(FxAtlas.Sprite sprite) {
        for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
            if (FxAtlas.puff6(f, false).equals(sprite) || FxAtlas.puff6(f, true).equals(sprite)) return f;
        }
        return FxAtlas.PUFF6_FRAMES - 1;
    }

    private static BufferedImage image(String path) throws IOException {
        try (InputStream in = PlumeContinuityTest.class.getResourceAsStream("/assets/incoming/" + path)) {
            assertNotNull(in, path);
            return ImageIO.read(in);
        }
    }

    /**
     * Cluster and tube clumps use different frames even for old clouds: source frame reaches last; forward-only scatter
     * would give every clump the same (mirrored) frame, a “stamp”. Spread is {@link SmokePuffs#FRAME_SPREAD} in both
     * directions, inward at sheet edges; zero spread uses source frame.
     */
    @Test
    void oldCloudsKeepVariedPuffs() {
        double last = FxAtlas.PUFF6_FRAMES - 1.001;
        for (double age : new double[]{0, 0.5, 3, 7.5, 14, last, 40}) {
            double lo = SmokePuffs.clubFrame(age, 0, true), hi = SmokePuffs.clubFrame(age, 1, true);
            assertEquals(2 * SmokePuffs.FRAME_SPREAD, hi - lo, 1e-9, "frame spread at source frame " + age);
            assertTrue(lo >= SmokePuffs.FIRST_FRAME && hi <= last, "frames on sheet: " + age);
            assertEquals(Math.clamp(age, SmokePuffs.FIRST_FRAME, last), SmokePuffs.clubFrame(age, 0.3, false), 1e-9);
        }
    }

    /**
     * Baked clump on screen is nearly uniform disc: uniform disc radius with same coverage along center chord
     * (∫ ring-mean opacity over radius up to 1.2 half-size / center opacity) for frames rendering
     * tubes is {@link SmokePuffs#PUFF_DISCS} per frame; narrowest is {@link SmokePuffs#PUFF_DISC_MIN}. These set tube-place
     * spacing and clump opacity share: new simulation frames require new numbers.
     */
    @Test
    void puffDiscMatchesTheBakedFrames() throws IOException {
        double min = 1;
        for (int f = SmokePuffs.FIRST_FRAME; f < FxAtlas.PUFF6_FRAMES; f++) {
            BufferedImage img = image(String.format(Locale.ROOT, "textures/fx/particle/puff6a_%02d.png", f));
            int n = img.getWidth(), bins = 60;
            double[] a = new double[bins], c = new double[bins];
            double centre = 0, cn = 0;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    double r = Math.hypot(x + 0.5 - n / 2.0, y + 0.5 - n / 2.0) / (n / 2.0), v = (img.getRGB(x, y) >>> 24) / 255.0;
                    if (r < 0.15) {
                        centre += v;
                        cn++;
                    }
                    int k = (int) (r * 50);
                    if (k < bins) {
                        a[k] += v;
                        c[k]++;
                    }
                }
            }
            centre /= cn;
            double half = 0;
            for (int k = 0; k < bins; k++) if (c[k] > 0) half += a[k] / c[k] / centre / 50;
            min = Math.min(min, half);
            assertEquals(SmokePuffs.PUFF_DISCS[f], half, 0.021, "frame " + f);
        }
        assertEquals(SmokePuffs.PUFF_DISC_MIN, min, 0.021, "narrowest frame");
    }

    /** Frame quads: clumps (eye-relative center, half-sizes, opacity) and ribbons (ends, half-widths, opacities). */
    private static final class Record implements SmokePuffs.Out {
        /** Camera-facing clumps, horizontal clumps (ground dust layer, {@code FxQuads.flat}), and ribbons. */
        final List<double[]> puffs = new ArrayList<>(), flats = new ArrayList<>(), ribbons = new ArrayList<>();
        /** Frame veil: large on-screen tube places (frame exceeds fill budget) enter it, same ray optical depth. */
        SmokePuffs.Veil veil;

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r,
                          float g, float b, float a) {
            puffs.add(new double[]{dx, dy, dz, halfA, halfB, Math.min(1, a), frameOf(sprite)});
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, double angle, FxAtlas.Sprite sprite, float mix,
                              float shade, float r, float g, float b, float a) {
            flats.add(new double[]{dx, dy, dz, halfA, halfB, Math.min(1, a), frameOf(sprite)});
        }

        @Override
        public void veil(SmokePuffs.Veil veil, int light) {
            this.veil = veil.copy();
        }

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                              float alphaB, int lightB, float r, float g, float b, float[] joint) {
            if (alphaA < 0.002f && alphaB < 0.002f) return false;
            ribbons.add(new double[]{ax, ay, az, halfA / FarSprites.RIBBON, alphaA, bx, by, bz, halfB / FarSprites.RIBBON, alphaB});
            joint[0] = 0;
            return true;
        }
    }

    /** Sink source: seed and clump growth; glowing gas as smoke, no spray. */
    private static final SmokeGrid.Source SOURCE = new SmokeGrid.Source() {
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            return true;
        }

        @Override
        public long seed() {
            return 12345;
        }
    };

    /**
     * Plume of strength-1 fuel source (long steady burn), wind u, stability class stability, side view (crosswind) from range
     * meters at 2 m above source: true means no screen-axis gaps.
     */
    private static boolean continuous(String name, FireSmoke.Fuel fuel, double u, double stability, double range) throws IOException {
        FirePlume plume = new FirePlume(fuel.watts(), Math.sqrt(fuel.area() / Math.PI), fuel.sootPerJoule(), u, stability);
        double far = plume.visible(FireSmoke.REACH);
        // plume axis: tube-piece ends as FireSmoke lays them; source at (0, 0, 0), wind toward +z
        List<double[]> axis = new ArrayList<>();
        SmokeGrid.Sink ends = new SmokeGrid.Sink() {
            @Override
            public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                                double water, float dustR, float dustG, float dustB, double kelvin, double rise) {}

            @Override
            public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                     double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin, double rise,
                     double sigma0) {
                double k = ua.zentix.incoming.client.fx.smoke.CloudModel.SOOT_K;
                axis.add(new double[]{ax, ay, az, Math.sqrt(sha * sva), bx, by, bz, Math.sqrt(shb * svb), k * sootA, k * sootB});
            }
        };
        FireSmoke.lay(fuel, 0, 0, 0, 0, 1, u, stability, far, plume.size, x -> 1, x -> 1, ends);
        assertTrue(axis.size() > 3, name + ": plume made of pieces");
        // eye beside center of visible plume portion (nearby frame contains only part of plume)
        double look = Math.min(0.5 * far, 0.5 * range);
        FarView view = view(new Vec3(-range, 2, look), at(axis, look));
        Record out = new Record();
        SmokeGrid.Sink sink = SmokePuffs.sink(view, out, SOURCE, 100);
        FireSmoke.lay(fuel, 0, 0, 0, 0, 1, u, stability, far, plume.size, x -> 1, x -> 1, sink);
        return along(name, axis, view, out);
    }

    /** View from eye toward mid: 70° over 1080 rows, clear air. */
    private static FarView view(Vec3 eye, double[] mid) {
        Vector3f fwd = new Vector3f((float) (mid[0] - eye.x), (float) (mid[1] - eye.y), (float) (mid[2] - eye.z)).normalize();
        Vector3f left = new Vector3f(0, 1, 0).cross(fwd, new Vector3f()).normalize(), up = fwd.cross(left, new Vector3f()).normalize();
        return new FarView(eye, fwd, left, up, 0, 1000, PIXEL, Math.toRadians(35), 1, 1, new Vector3f(1, 1, 1), 32000, 192, 192, 0,
                Sight.Air.at(0, 20000));
    }

    /**
     * Tube-axis opacity (axis pieces: ends and σ in world) from quads out rendered in view: true means no
     * frame gaps.
     */
    private static boolean along(String name, List<double[]> axis, FarView view, Record out) throws IOException {
        // tube-place clumps wait for frame plan: frame ended
        SmokePuffs.resetBudget();
        SmokePuffs.flush();
        Vec3 eye = view.camera();
        Vector3f fwd = view.forward(), left = view.left(), up = view.up();
        double edge = view.edge();
        double[][] prof = profiles();
        // screen-axis opacity: quarter-pixel or σ/8 step
        List<double[]> samples = new ArrayList<>();
        double total = 0;
        for (double[] s : axis) total += Math.sqrt(sq(s[4] - s[0]) + sq(s[5] - s[1]) + sq(s[6] - s[2]));
        for (double path = 0; path < total; ) {
            double[] p = at(axis, path);
            double px = p[0] - eye.x, py = p[1] - eye.y, pz = p[2] - eye.z, d = Math.sqrt(px * px + py * py + pz * pz);
            double depth = px * fwd.x + py * fwd.y + pz * fwd.z;
            double sigma = p[3];
            path += Math.max(0.05, Math.min(0.25 * PIXEL * d, sigma / 8));
            if (depth <= 0 || Math.acos(Math.min(1, depth / d)) > 0.8 * edge) continue;
            double su = (px * left.x + py * left.y + pz * left.z) / depth, sv = (px * up.x + py * up.y + pz * up.z) / depth;
            double clear = out.veil == null ? 1 : out.veil.through(su, sv);
            for (double[] q : out.puffs) {
                double qd = q[0] * fwd.x + q[1] * fwd.y + q[2] * fwd.z;
                if (qd <= 0) continue;
                double qu = (q[0] * left.x + q[1] * left.y + q[2] * left.z) / qd, qv = (q[0] * up.x + q[1] * up.y + q[2] * up.z) / qd;
                double r = Math.hypot((su - qu) / (q[3] / qd), (sv - qv) / (q[4] / qd));
                int k = (int) (r / BIN);
                if (k < prof[0].length) clear *= 1 - q[5] * prof[(int) q[6]][k];
            }
            // flat clump is horizontal quad: ray from eye toward axis point intersects its plane (if intersection precedes point)
            for (double[] q : out.flats) {
                if (Math.abs(py) < 1e-9) continue;
                double t = q[1] / py;
                if (t <= 0 || t >= 1) continue;
                double r = Math.hypot((px * t - q[0]) / q[3], (pz * t - q[2]) / q[4]);
                int k = (int) (r / BIN);
                if (k < prof[0].length) clear *= 1 - q[5] * prof[(int) q[6]][k];
            }
            for (double[] rb : out.ribbons) clear *= 1 - ribbonAt(rb, su, sv, fwd, left, up);
            // axis law is 1 − e^(−τ), τ = e/(√(2π)σ), with haze; only where clumps alone draw tube (wider than ribbon threshold)
            double tau = p[4] / (Math.sqrt(2 * Math.PI) * sigma);
            double law = ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma) >= FarFlightView.PUFFS_FULL * PIXEL * d
                    ? (1 - Math.exp(-tau)) * view.air(d, py) : Double.NaN;
            samples.add(new double[]{path, 1 - clear, Math.max(4 * sigma, 8 * PIXEL * d), law});
        }
        assertTrue(samples.size() > 20, name + ": plume in frame");
        boolean ok = true;
        int gaps = 0;
        for (double[] s : samples) {
            // valley: twice the opacity on both sides within window, eye-visible difference (contrast above Koschmieder threshold);
            // plume beginning at source and fading end are not gaps
            double before = 0, after = 0;
            for (double[] o : samples) {
                double dx = o[0] - s[0];
                if (dx < 0 && dx >= -0.5 * s[2]) before = Math.max(before, o[1]);
                if (dx > 0 && dx <= 0.5 * s[2]) after = Math.max(after, o[1]);
            }
            double top = Math.min(before, after);
            if (s[1] < 0.5 * top && top - s[1] > Sight.THRESHOLD) {
                if (gaps++ < 3) System.out.printf(Locale.ROOT, "%s: gap at %.0f m travel: %.3f versus %.3f nearby%n", name, s[0], s[1], top);
                ok = false;
            }
        }
        // mean axial opacity follows law (clump takes its tube share; ⚠ frame clump disc is 0.71–0.80 half-size,
        // ±25% by frame, closer averaged over axis)
        double drawn = 0, law = 0;
        for (double[] s : samples) {
            if (Double.isNaN(s[3])) continue;
            drawn += s[1];
            law += s[3];
        }
        double ratio = law > 0 ? drawn / law : 1;
        if (law > 0 && (ratio < 0.75 || ratio > 1.33)) {
            System.out.printf(Locale.ROOT, "%s: axis %.2f of law%n", name, ratio);
            ok = false;
        }
        System.out.printf(Locale.ROOT, "%s: %d clumps, %d ribbons, %d axis points, %d gaps, axis %.2f of law%n", name, out.puffs.size(),
                out.ribbons.size(), samples.size(), gaps, ratio);
        return ok;
    }

    /**
     * Projectile trail with same tube segment ({@link FarFlightView#line}): straight, 40° above horizon, 160 m (Grad burning
     * at game speed), σ from 0.5 to 10 m toward tail, extinction per meter e; side view from range meters.
     */
    private static boolean trail(String name, double e, double range) throws IOException {
        List<double[]> axis = new ArrayList<>();
        double step = 8, len = 160, cos = Math.cos(Math.toRadians(40)), sin = Math.sin(Math.toRadians(40));
        for (double s = 0; s < len; s += step) {
            axis.add(new double[]{0, s * sin, s * cos, 0.5 + 9.5 * (1 - s / len), 0, (s + step) * sin, (s + step) * cos, 0.5 + 9.5 * (1 - (s + step) / len),
                    e, e});
        }
        FarView view = view(new Vec3(-range, 2, 0.5 * len * cos), at(axis, 0.5 * len));
        Record out = new Record();
        Vec3 eye = view.camera();
        float[] joint = {Float.NaN, 0, 0};
        long seed = 77;
        for (double[] a : axis) {
            FarFlightView.Tube ta = end(view, a[0] - eye.x, a[1] - eye.y, a[2] - eye.z, a[3], e);
            FarFlightView.Tube tb = end(view, a[4] - eye.x, a[5] - eye.y, a[6] - eye.z, a[7], e);
            int drawn = FarFlightView.line(view, out, ta, tb, 0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0.6f, 1, 0.5, seed++, 1, joint);
            if (drawn != FarFlightView.RIBBON) joint[0] = Float.NaN;
        }
        return along(name, axis, view, out);
    }

    /** Tube end of cross-section σ with extinction e per meter at eye-relative (dx, dy, dz), as trail ({@code FarFlightView.end}). */
    private static FarFlightView.Tube end(FarView view, double dx, double dy, double dz, double sigma, double e) {
        double tau = e / (Math.sqrt(2 * Math.PI) * sigma), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double width = ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma);
        double[] rib = new double[2];
        FarFlightView.ribbon(width, d, view.pixel(), rib);
        return new FarFlightView.Tube().set(dx, dy, dz, sigma, e, width, rib[0], (float) ((1 - Math.exp(-tau)) * rib[1] * view.air(d, dy)), 0);
    }

    /** Ribbon opacity at screen point (su, sv): local axial opacity along segment, transverse half-opacity at half-width. */
    private static double ribbonAt(double[] rb, double su, double sv, Vector3f fwd, Vector3f left, Vector3f up) {
        double ad = rb[0] * fwd.x + rb[1] * fwd.y + rb[2] * fwd.z, bd = rb[5] * fwd.x + rb[6] * fwd.y + rb[7] * fwd.z;
        if (ad <= 0 || bd <= 0) return 0;
        double au = (rb[0] * left.x + rb[1] * left.y + rb[2] * left.z) / ad, av = (rb[0] * up.x + rb[1] * up.y + rb[2] * up.z) / ad;
        double bu = (rb[5] * left.x + rb[6] * left.y + rb[7] * left.z) / bd, bv = (rb[5] * up.x + rb[6] * up.y + rb[7] * up.z) / bd;
        double tu = bu - au, tv = bv - av, len2 = tu * tu + tv * tv;
        double t = len2 > 0 ? Math.clamp(((su - au) * tu + (sv - av) * tv) / len2, 0, 1) : 0;
        double off = Math.hypot(su - au - t * tu, sv - av - t * tv);
        double half = (rb[3] / ad) * (1 - t) + (rb[8] / bd) * t, alpha = rb[4] * (1 - t) + rb[9] * t;
        // transverse Gaussian with half opacity at half-width (ribbon width measured at half center opacity)
        return alpha * Math.exp(-Math.log(2) * sq(off / Math.max(half, 1e-9)));
    }

    /** Axis point at path distance along pieces: x, y, z, σ and extinction per axis meter. */
    private static double[] at(List<double[]> axis, double path) {
        for (double[] s : axis) {
            double len = Math.sqrt(sq(s[4] - s[0]) + sq(s[5] - s[1]) + sq(s[6] - s[2]));
            if (path <= len || s == axis.get(axis.size() - 1)) {
                double t = len > 0 ? Math.min(1, path / len) : 0;
                return new double[]{s[0] + (s[4] - s[0]) * t, s[1] + (s[5] - s[1]) * t, s[2] + (s[6] - s[2]) * t, s[3] + (s[7] - s[3]) * t,
                        s[8] + (s[9] - s[8]) * t};
            }
            path -= len;
        }
        throw new IllegalStateException();
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Shahed crater fuel: ~2.2 MW gasoline over 2 m radius (as {@code FireSmoke.pool}: gasoline soot 0.04 kg/kg, 43.4 MJ/kg). */
    private static final FireSmoke.Fuel POOL = new FireSmoke.Fuel(2.2e6, Math.PI * 4, 0.04 / 43.4e6);
    /** Burning house: 160 fire blocks (~28 MW). */
    private static final FireSmoke.Fuel HOUSE = new FireSmoke.Fuel(160 * FirePlume.BLOCK_WATTS, 160, FirePlume.SOOT_PER_JOULE);

    @Test
    void fuelFireIsOneColumnFromAnyDistance() throws IOException {
        boolean ok = true;
        for (double range : new double[]{50, 300, 1000, 3000}) {
            ok &= continuous("pool, wind 5 m/s, D, " + range + " m", POOL, 5, Stability.D, range);
            ok &= continuous("pool, calm 1.5 m/s, E, " + range + " m", POOL, 1.5, Stability.E, range);
            ok &= continuous("pool, evening 1 m/s, F, " + range + " m", POOL, 1, Stability.F, range);
            ok &= continuous("substation oil, 3 m/s, D, " + range + " m", FireSmoke.OIL, 3, Stability.D, range);
            ok &= continuous("house, 4.9 m/s, D, " + range + " m", HOUSE, 4.9, Stability.D, range);
        }
        assertTrue(ok, "plume has gaps; details above");
    }

    /**
     * Same class: explosion-cloud stem (dust from ground to cloud bottom, {@code CloudModel.blobs}): missile, neutral air
     * with 3 m/s wind, after 10, 20 and 40 s, from 300 m, 1 and 3 km.
     */
    @Test
    void blastStemIsOneColumnFromAnyDistance() throws IOException {
        CloudModel.Air air = new CloudModel.Air(Stability.brunt(Stability.D), 3, 0, Stability.alpha(Stability.D), (int) Stability.D, 0.5);
        CloudModel model = new CloudModel(BlastSource.of(WeaponType.MISSILE, false), air);
        float albedo = 0.8f;
        double k = SmokeGrid.dustK(albedo, albedo, albedo);
        boolean ok = true;
        for (double t : new double[]{10, 20, 40}) {
            List<double[]> axis = new ArrayList<>();
            model.blobs(t, new CloudModel.Blobs() {
                @Override
                public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin, double rise) {}

                @Override
                public void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb, double dustB,
                                 double kelvin, double rise) {
                    axis.add(new double[]{ax, ay, az, sa, bx, by, bz, sb, k * dustA, k * dustB});
                }
            });
            assertTrue(axis.size() > 1, "stem after " + t + " s");
            double[] top = axis.get(axis.size() - 1);
            for (double range : new double[]{300, 1000, 3000}) {
                FarView view = view(new Vec3(0, 2, -range), at(axis, 0.5 * pathLength(axis)));
                Record out = new Record();
                SmokeGrid.Sink sink = SmokePuffs.sink(view, out, GLOWING, 100);
                model.blobs(t, new CloudModel.Blobs() {
                    @Override
                    // cloud and ground dust layer have own clumps and matter: here only stem continuity
                    public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin,
                                     double rise) {}

                    @Override
                    public void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb,
                                     double dustB, double kelvin, double rise) {
                        sink.tube(ax, ay, az, sa, sa, 0, dustA, bx, by, bz, sb, sb, 0, dustB, albedo, albedo, albedo, kelvin, rise, sa);
                    }
                });
                ok &= along("stem after " + t + " s (top " + Math.round(top[5]) + " m), " + range + " m", axis, view, out);
            }
        }
        assertTrue(ok, "stem has gaps; details above");
    }

    /** Explosion cloud draws own fire body: hot matter appears as smoke by cooling share. */
    private static final SmokeGrid.Source GLOWING = new SmokeGrid.Source() {
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            return true;
        }

        @Override
        public boolean drawsGlow() {
            return true;
        }

        @Override
        public long seed() {
            return 4321;
        }
    };

    private static double pathLength(List<double[]> axis) {
        double total = 0;
        for (double[] s : axis) total += Math.sqrt(sq(s[4] - s[0]) + sq(s[5] - s[1]) + sq(s[6] - s[2]));
        return total;
    }

    /** Same class: projectile trail tube from thin (axial τ ~0.05) to thick (~10), at any range. */
    @Test
    void trailIsOneLineFromAnyDistance() throws IOException {
        boolean ok = true;
        for (double range : new double[]{50, 300, 1000, 1670, 3000}) {
            for (double e : new double[]{0.3, 3, 30}) ok &= trail("trail, e " + e + ", " + range + " m", e, range);
        }
        assertTrue(ok, "trail has gaps; details above");
    }
}
