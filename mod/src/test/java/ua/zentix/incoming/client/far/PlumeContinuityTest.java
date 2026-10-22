package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.FireSmoke;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.FirePlume;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.util.Stability;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Шлейф пожара — один сплошной столб с любой дальности (жалоба 22.10: из 1–3 км дым лужи топлива шёл цепочкой отдельных
 * клубов). Шлейф раскладывает тот же код, что в игре ({@link FireSmoke#lay}), рисует тот же приёмник слоя эффектов
 * ({@link SmokePuffs#sink}: ленты и клубы, как у следа снаряда); квадраты пишет свой приёмник, и непрозрачность вдоль оси на
 * экране складывается из них: клуб — радиальный профиль непрозрачности запечённого клуба (самый узкий из кадров, что идут в
 * трубку), лента — её непрозрачность у оси. Разрыв — впадина: непрозрачность у оси меньше половины наименьшей из
 * наибольших по обе стороны в окне шириной в четыре σ сечения (не меньше 8 пикселей), и разница видна глазу (больше порога
 * контраста Кошмидера, {@link Sight#THRESHOLD}): так выглядит бусина за бусиной. Сплошной шлейф редеет вдоль оси плавно.
 */
class PlumeContinuityTest {
    /** Угол пикселя: 70° на 1080 строк. */
    private static final double PIXEL = Math.toRadians(70) / 1080;
    /** Профиль непрозрачности клуба по радиусу (доля полуразмера квадрата), шаг {@link #BIN}. */
    private static final double BIN = 0.02;
    private static double[] profile;

    /** Самый узкий радиальный профиль непрозрачности кадров клуба, что рисуют трубки (с {@link SmokePuffs#FIRST_FRAME}). */
    private static double[] profile() throws IOException {
        if (profile != null) return profile;
        int bins = (int) Math.round(1.5 / BIN);
        double[] min = new double[bins];
        java.util.Arrays.fill(min, 1);
        for (int f = SmokePuffs.FIRST_FRAME; f < FxAtlas.PUFF6_FRAMES; f++) {
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
            for (int k = 0; k < bins; k++) min[k] = Math.min(min[k], count[k] > 0 ? sum[k] / count[k] : 0);
        }
        return profile = min;
    }

    private static BufferedImage image(String path) throws IOException {
        try (InputStream in = PlumeContinuityTest.class.getResourceAsStream("/assets/incoming/" + path)) {
            assertNotNull(in, path);
            return ImageIO.read(in);
        }
    }

    /**
     * Запечённый клуб на экране — почти ровный диск: радиус, где непрозрачность падает до половины середины, у кадров,
     * что рисуют трубки, — от {@link SmokePuffs#PUFF_DISC_MIN} (самый узкий) до ~0,76, в среднем {@link SmokePuffs#PUFF_DISC}.
     * По ним — шаг мест трубки и доля непрозрачности клуба: новые кадры симуляции — новые числа.
     */
    @Test
    void puffDiscMatchesTheBakedFrames() throws IOException {
        double min = 1, sum = 0;
        int frames = 0;
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
            double half = 1.2;
            for (int k = 5; k < bins; k++) {
                if (c[k] > 0 && a[k] / c[k] < 0.5 * centre) {
                    half = k / 50.0;
                    break;
                }
            }
            min = Math.min(min, half);
            sum += half;
            frames++;
        }
        assertTrue(Math.abs(min - SmokePuffs.PUFF_DISC_MIN) <= 0.03, "самый узкий кадр: " + min);
        assertTrue(Math.abs(sum / frames - SmokePuffs.PUFF_DISC) <= 0.05, "в среднем: " + sum / frames);
    }

    /** Квадраты кадра: клубы (центр от глаза, полуразмеры, непрозрачность) и ленты (концы, полуширины, непрозрачности). */
    private static final class Record implements SmokePuffs.Out {
        final List<double[]> puffs = new ArrayList<>(), ribbons = new ArrayList<>();

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r,
                          float g, float b, float a) {
            puffs.add(new double[]{dx, dy, dz, halfA, halfB, Math.min(1, a)});
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, FxAtlas.Sprite sprite, float mix,
                              float shade, float r, float g, float b, float a) {
            puffs.add(new double[]{dx, dy, dz, halfA, halfB, Math.min(1, a)});
        }

        @Override
        public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a, int light) {}

        @Override
        public void veil(double dx, double dy, double dz, double half, float r, float g, float b, float a) {}

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                              float alphaB, int lightB, float r, float g, float b, float[] joint) {
            if (alphaA < 0.002f && alphaB < 0.002f) return false;
            ribbons.add(new double[]{ax, ay, az, halfA / FarSprites.RIBBON, alphaA, bx, by, bz, halfB / FarSprites.RIBBON, alphaB});
            joint[0] = 0;
            return true;
        }
    }

    /** Источник для приёмника: зерно и рост клуба; светящийся газ — дымом, без брызг. */
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
     * Шлейф очага fuel силы 1 (горит давно и ровно) в ветре u класса stability, взгляд сбоку (поперёк ветра) из range
     * метров с высоты 2 м над очагом: true — без разрывов вдоль оси в кадре.
     */
    private static boolean continuous(String name, FireSmoke.Fuel fuel, double u, double stability, double range) throws IOException {
        FirePlume plume = new FirePlume(fuel.watts(), Math.sqrt(fuel.area() / Math.PI), fuel.sootPerJoule(), u, stability);
        double far = plume.visible(FireSmoke.REACH);
        // ось шлейфа — концы кусков трубки, как их кладёт FireSmoke; очаг — (0, 0, 0), ветер — к +z
        List<double[]> axis = new ArrayList<>();
        SmokeGrid.Sink ends = new SmokeGrid.Sink() {
            @Override
            public void segment(double x, double y, double z, double ax, double ay, double az, double sh, double sv, double soot, double dust,
                                double water, float dustR, float dustG, float dustB, double kelvin, double rise) {}

            @Override
            public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double bx, double by, double bz, double shb,
                             double svb, double sootB, double kelvin, double rise, double sigma0) {
                axis.add(new double[]{ax, ay, az, Math.sqrt(sha * sva), bx, by, bz, Math.sqrt(shb * svb)});
            }
        };
        FireSmoke.lay(fuel, 0, 0, 0, 0, 1, u, stability, far, plume.size, x -> 1, x -> 1, ends);
        assertTrue(axis.size() > 3, name + ": шлейф из кусков");
        // глаз — сбоку от середины видимой части шлейфа в кадре (вблизи кадр берёт только часть шлейфа)
        double look = Math.min(0.5 * far, 0.5 * range);
        FarView view = view(new Vec3(-range, 2, look), at(axis, look));
        Record out = new Record();
        SmokeGrid.Sink sink = SmokePuffs.sink(view, out, SOURCE, 100);
        FireSmoke.lay(fuel, 0, 0, 0, 0, 1, u, stability, far, plume.size, x -> 1, x -> 1, sink);
        return along(name, axis, view, out);
    }

    /** Вид из eye на точку mid: 70° на 1080 строк, ясный воздух. */
    private static FarView view(Vec3 eye, double[] mid) {
        Vector3f fwd = new Vector3f((float) (mid[0] - eye.x), (float) (mid[1] - eye.y), (float) (mid[2] - eye.z)).normalize();
        Vector3f left = new Vector3f(0, 1, 0).cross(fwd, new Vector3f()).normalize(), up = fwd.cross(left, new Vector3f()).normalize();
        return new FarView(eye, fwd, left, up, 0, 1000, PIXEL, Math.toRadians(35), 1, 1, new Vector3f(1, 1, 1), 32000, 192, 192, 0,
                Sight.Air.at(0, 20000));
    }

    /**
     * Непрозрачность у оси трубки (куски axis: концы и σ в мире) по квадратам out, что нарисованы в виде view: true — без
     * разрывов в кадре.
     */
    private static boolean along(String name, List<double[]> axis, FarView view, Record out) throws IOException {
        Vec3 eye = view.camera();
        Vector3f fwd = view.forward(), left = view.left(), up = view.up();
        double edge = view.edge();
        double[] prof = profile();
        // непрозрачность вдоль оси на экране: шаг — четверть пикселя или σ/8
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
            double clear = 1;
            for (double[] q : out.puffs) {
                double qd = q[0] * fwd.x + q[1] * fwd.y + q[2] * fwd.z;
                if (qd <= 0) continue;
                double qu = (q[0] * left.x + q[1] * left.y + q[2] * left.z) / qd, qv = (q[0] * up.x + q[1] * up.y + q[2] * up.z) / qd;
                double r = Math.hypot((su - qu) / (q[3] / qd), (sv - qv) / (q[4] / qd));
                int k = (int) (r / BIN);
                if (k < prof.length) clear *= 1 - q[5] * prof[k];
            }
            for (double[] rb : out.ribbons) clear *= 1 - ribbonAt(rb, su, sv, fwd, left, up);
            samples.add(new double[]{path, 1 - clear, Math.max(4 * sigma, 8 * PIXEL * d)});
        }
        assertTrue(samples.size() > 20, name + ": шлейф в кадре");
        boolean ok = true;
        int gaps = 0;
        for (double[] s : samples) {
            // впадина: по обе стороны в окне непрозрачнее вдвое, и разница видна глазу (контраст больше порога Кошмидера);
            // начало шлейфа у очага и его тающий конец — не разрыв
            double before = 0, after = 0;
            for (double[] o : samples) {
                double dx = o[0] - s[0];
                if (dx < 0 && dx >= -0.5 * s[2]) before = Math.max(before, o[1]);
                if (dx > 0 && dx <= 0.5 * s[2]) after = Math.max(after, o[1]);
            }
            double top = Math.min(before, after);
            if (s[1] < 0.5 * top && top - s[1] > Sight.THRESHOLD) {
                if (gaps++ < 3) System.out.printf(Locale.ROOT, "%s: разрыв на %.0f м пути: %.3f против %.3f рядом%n", name, s[0], s[1], top);
                ok = false;
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d клубов, %d лент, %d точек оси, разрывов %d%n", name, out.puffs.size(), out.ribbons.size(),
                samples.size(), gaps);
        return ok;
    }

    /**
     * След снаряда тем же отрезком трубки ({@link FarFlightView#line}): прямой, под 40° к горизонту, 160 м (сгорание «Града»
     * на игровой скорости), σ от 0,5 до 10 м к хвосту, ослабление на метр e; взгляд сбоку из range метров.
     */
    private static boolean trail(String name, double e, double range) throws IOException {
        List<double[]> axis = new ArrayList<>();
        double step = 8, len = 160, cos = Math.cos(Math.toRadians(40)), sin = Math.sin(Math.toRadians(40));
        for (double s = 0; s < len; s += step) {
            axis.add(new double[]{0, s * sin, s * cos, 0.5 + 9.5 * (1 - s / len), 0, (s + step) * sin, (s + step) * cos, 0.5 + 9.5 * (1 - (s + step) / len)});
        }
        FarView view = view(new Vec3(-range, 2, 0.5 * len * cos), at(axis, 0.5 * len));
        Record out = new Record();
        Vec3 eye = view.camera();
        float[] joint = {Float.NaN, 0, 0};
        long seed = 77;
        for (double[] a : axis) {
            FarFlightView.Tube ta = end(view, a[0] - eye.x, a[1] - eye.y, a[2] - eye.z, a[3], e);
            FarFlightView.Tube tb = end(view, a[4] - eye.x, a[5] - eye.y, a[6] - eye.z, a[7], e);
            int drawn = FarFlightView.line(view, out, ta, tb, 0.5f, 0.5f, 0.5f, 0.6f, 1, 0.5, seed++, joint);
            if (drawn != FarFlightView.RIBBON) joint[0] = Float.NaN;
        }
        return along(name, axis, view, out);
    }

    /** Конец трубки сечения σ с ослаблением e на метр в (dx, dy, dz) от глаза — как у следа ({@code FarFlightView.end}). */
    private static FarFlightView.Tube end(FarView view, double dx, double dy, double dz, double sigma, double e) {
        double tau = e / (Math.sqrt(2 * Math.PI) * sigma), d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double width = ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma);
        double[] rib = new double[2];
        FarFlightView.ribbon(width, d, view.pixel(), rib);
        return new FarFlightView.Tube().set(dx, dy, dz, sigma, e, width, rib[0], (float) ((1 - Math.exp(-tau)) * rib[1] * view.air(d, dy)), 0);
    }

    /** Непрозрачность ленты в точке экрана (su, sv): у оси отрезка — её непрозрачность по месту, поперёк — до полуширины на половине. */
    private static double ribbonAt(double[] rb, double su, double sv, Vector3f fwd, Vector3f left, Vector3f up) {
        double ad = rb[0] * fwd.x + rb[1] * fwd.y + rb[2] * fwd.z, bd = rb[5] * fwd.x + rb[6] * fwd.y + rb[7] * fwd.z;
        if (ad <= 0 || bd <= 0) return 0;
        double au = (rb[0] * left.x + rb[1] * left.y + rb[2] * left.z) / ad, av = (rb[0] * up.x + rb[1] * up.y + rb[2] * up.z) / ad;
        double bu = (rb[5] * left.x + rb[6] * left.y + rb[7] * left.z) / bd, bv = (rb[5] * up.x + rb[6] * up.y + rb[7] * up.z) / bd;
        double tu = bu - au, tv = bv - av, len2 = tu * tu + tv * tv;
        double t = len2 > 0 ? Math.clamp(((su - au) * tu + (sv - av) * tv) / len2, 0, 1) : 0;
        double off = Math.hypot(su - au - t * tu, sv - av - t * tv);
        double half = (rb[3] / ad) * (1 - t) + (rb[8] / bd) * t, alpha = rb[4] * (1 - t) + rb[9] * t;
        // гаусс поперёк с половиной непрозрачности на полуширине (ширина ленты — на половине непрозрачности середины)
        return alpha * Math.exp(-Math.log(2) * sq(off / Math.max(half, 1e-9)));
    }

    /** Точка оси на пути path вдоль кусков: x, y, z и σ. */
    private static double[] at(List<double[]> axis, double path) {
        for (double[] s : axis) {
            double len = Math.sqrt(sq(s[4] - s[0]) + sq(s[5] - s[1]) + sq(s[6] - s[2]));
            if (path <= len || s == axis.get(axis.size() - 1)) {
                double t = len > 0 ? Math.min(1, path / len) : 0;
                return new double[]{s[0] + (s[4] - s[0]) * t, s[1] + (s[5] - s[1]) * t, s[2] + (s[6] - s[2]) * t, s[3] + (s[7] - s[3]) * t};
            }
            path -= len;
        }
        throw new IllegalStateException();
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Топливо в воронке шахеда: ~2,2 МВт бензина на 2 м радиуса (как {@code FireSmoke.pool}: сажа бензина 0,04 кг/кг, 43,4 МДж/кг). */
    private static final FireSmoke.Fuel POOL = new FireSmoke.Fuel(2.2e6, Math.PI * 4, 0.04 / 43.4e6);
    /** Горящий дом: 160 блоков огня (~28 МВт). */
    private static final FireSmoke.Fuel HOUSE = new FireSmoke.Fuel(160 * FirePlume.BLOCK_WATTS, 160, FirePlume.SOOT_PER_JOULE);

    @Test
    void fuelFireIsOneColumnFromAnyDistance() throws IOException {
        boolean ok = true;
        for (double range : new double[]{50, 300, 1000, 3000}) {
            ok &= continuous("лужа, ветер 5 м/с, D, " + range + " м", POOL, 5, Stability.D, range);
            ok &= continuous("лужа, штиль 1,5 м/с, E, " + range + " м", POOL, 1.5, Stability.E, range);
            ok &= continuous("лужа, вечер 1 м/с, F, " + range + " м", POOL, 1, Stability.F, range);
            ok &= continuous("масло подстанции, 3 м/с, D, " + range + " м", FireSmoke.OIL, 3, Stability.D, range);
            ok &= continuous("дом, 4,9 м/с, D, " + range + " м", HOUSE, 4.9, Stability.D, range);
        }
        assertTrue(ok, "шлейф с разрывами — подробности выше");
    }

    /** Тот же класс — след снаряда: трубка от тонкой (τ у оси ~0,05) до густой (~10) с любой дальности. */
    @Test
    void trailIsOneLineFromAnyDistance() throws IOException {
        boolean ok = true;
        for (double range : new double[]{50, 300, 1000, 1670, 3000}) {
            for (double e : new double[]{0.3, 3, 30}) ok &= trail("след, e " + e + ", " + range + " м", e, range);
        }
        assertTrue(ok, "след с разрывами — подробности выше");
    }
}
