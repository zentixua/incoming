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
 * Шлейф пожара — один сплошной столб с любой дальности (жалоба 22.10: из 1–3 км дым лужи топлива шёл цепочкой отдельных
 * клубов). Шлейф раскладывает тот же код, что в игре ({@link FireSmoke#lay}), рисует тот же приёмник слоя эффектов
 * ({@link SmokePuffs#sink}: ленты и клубы, как у следа снаряда); квадраты пишет свой приёмник, и непрозрачность вдоль оси на
 * экране складывается из них: клуб — радиальный профиль непрозрачности его кадра запечённого клуба, лента — её
 * непрозрачность у оси. Разрыв — впадина: непрозрачность у оси меньше половины наименьшей из
 * наибольших по обе стороны в окне шириной в четыре σ сечения (не меньше 8 пикселей), и разница видна глазу (больше порога
 * контраста Кошмидера, {@link Sight#THRESHOLD}): так выглядит бусина за бусиной. Сплошной шлейф редеет вдоль оси плавно.
 */
class PlumeContinuityTest {
    /** Угол пикселя: 70° на 1080 строк. */
    private static final double PIXEL = Math.toRadians(70) / 1080;
    private static BufferedImage image(String path) throws IOException {
        try (InputStream in = PlumeContinuityTest.class.getResourceAsStream("/assets/incoming/" + path)) {
            assertNotNull(in, path);
            return ImageIO.read(in);
        }
    }

    /**
     * Запечённый клуб на экране — почти ровный диск: радиус, где непрозрачность падает до половины середины, у кадров,
     * что рисуют трубки, — самый узкий — {@link SmokePuffs#PUFF_DISC_MIN}. По нему — шаг мест трубки: новые кадры
     * симуляции — новое число.
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
            double half = 1.2;
            for (int k = 5; k < bins; k++) {
                if (c[k] > 0 && a[k] / c[k] < 0.5 * centre) {
                    half = k / 50.0;
                    break;
                }
            }
            min = Math.min(min, half);
        }
        assertEquals(SmokePuffs.PUFF_DISC_MIN, min, 0.021, "самый узкий кадр");
    }

    /** Клуб в записи: центр от глаза, полуразмеры, видимая доля, кадр, смесь, отражён ли, множитель толщи. */
    static double[] club(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float a, double tau) {
        return new double[]{dx, dy, dz, halfA, halfB, Math.min(1, a), ClubFrames.frame(sprite), mix, ClubFrames.mirrored(sprite) ? 1 : 0, tau};
    }

    /** Непрозрачность записанного клуба q в точке (x, y) его квадрата (доли полуразмера). */
    static double clubAt(double[] q, double x, double y) throws IOException {
        return ClubFrames.at((int) q[6], q[7], q[8] > 0.5, x, y, q[5], q[9]);
    }

    @org.junit.jupiter.api.BeforeAll
    static void optics() throws IOException {
        ClubFrames.install();
    }

    /** Квадраты кадра: клубы (центр от глаза, полуразмеры, непрозрачность) и ленты (концы, полуширины, непрозрачности). */
    private static final class Record implements SmokePuffs.Out {
        private SmokeFillTest.Screen rayPuffs;

        @Override
        public boolean gaussian(FarView view,double x,double y,double z,double sh,double sv,double ext,double across,
                                double card,FxAtlas.Sprite sprite,float mix,float shade,
                                float r,float g,float b,float coverage) {
            if(rayPuffs==null) rayPuffs=new SmokeFillTest.Screen(view);
            return rayPuffs.gaussian(view,x,y,z,sh,sv,ext,across,card,sprite,mix,shade,r,g,b,coverage);
        }

        /** Клубы лицом к камере, лежащие на горизонтали (слой пыли у земли, {@code FxQuads.flat}) и ленты. */
        final List<double[]> puffs = new ArrayList<>(), flats = new ArrayList<>(), ribbons = new ArrayList<>();

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r,
                          float g, float b, float a, double tau) {
            puffs.add(club(dx, dy, dz, halfA, halfB, sprite, mix, a, tau));
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, FxAtlas.Sprite sprite, float mix,
                              float shade, float r, float g, float b, float a, double tau) {
            flats.add(club(dx, dy, dz, halfA, halfB, sprite, mix, a, tau));
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
            public void tube(double ax, double ay, double az, double sha, double sva, double sootA, double dustA, double bx, double by, double bz,
                     double shb, double svb, double sootB, double dustB, float albedoR, float albedoG, float albedoB, double kelvin, double rise,
                     double sigma0) {
                double k = ua.zentix.incoming.client.fx.smoke.CloudModel.SOOT_K;
                axis.add(new double[]{ax, ay, az, Math.sqrt(sha * sva), bx, by, bz, Math.sqrt(shb * svb), k * sootA, k * sootB});
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
        SmokePuffs.flush(); // World-source segments join the frame's merge/planning pass before drawing.
        Vec3 eye = view.camera();
        Vector3f fwd = view.forward(), left = view.left(), up = view.up();
        double edge = view.edge();
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
            double clear = out.rayPuffs==null?1:out.rayPuffs.transmittance(su,sv);
            for (double[] q : out.puffs) {
                double qd = q[0] * fwd.x + q[1] * fwd.y + q[2] * fwd.z;
                if (qd <= 0) continue;
                double qu = (q[0] * left.x + q[1] * left.y + q[2] * left.z) / qd, qv = (q[0] * up.x + q[1] * up.y + q[2] * up.z) / qd;
                clear *= 1 - clubAt(q, (su - qu) / (q[3] / qd), (sv - qv) / (q[4] / qd));
            }
            // лежащий клуб — квадрат на горизонтали: луч из глаза в точку оси пересекает его плоскость (если пересекает до неё)
            for (double[] q : out.flats) {
                if (Math.abs(py) < 1e-9) continue;
                double t = q[1] / py;
                if (t <= 0 || t >= 1) continue;
                clear *= 1 - clubAt(q, (px * t - q[0]) / q[3], (pz * t - q[2]) / q[4]);
            }
            for (double[] rb : out.ribbons) clear *= 1 - ribbonAt(rb, su, sv, fwd, left, up);
            // закон у оси — 1 − e^(−τ), τ = e/(√(2π)σ), с дымкой; только где трубку рисуют одни клубы (шире порога лент)
            double tau = p[4] / (Math.sqrt(2 * Math.PI) * sigma);
            double law = ua.zentix.incoming.client.fx.plume.TrailModel.width(tau, sigma) >= FarFlightView.PUFFS_FULL * PIXEL * d
                    ? (1 - Math.exp(-tau)) * view.air(d, py) : Double.NaN;
            samples.add(new double[]{path, 1 - clear, Math.max(4 * sigma, 8 * PIXEL * d), law});
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
        // непрозрачность у оси в среднем — закона: клубы несут вещество трубки точно (PuffOptics, SmokeFillTest), а толща
        // у оси — ещё и форма кадра поперёк цепочки (⚠ запечённый клуб не круглый: газ всплывает, и цепочка вдоль наклонной
        // на экране оси сечёт его по длинной стороне — тонкий след с 50 м до 1,4 закона; на 3.6.1 там же 1,2)
        double drawn = 0, law = 0;
        for (double[] s : samples) {
            if (Double.isNaN(s[3])) continue;
            drawn += s[1];
            law += s[3];
        }
        double ratio = law > 0 ? drawn / law : 1;
        if (law > 0 && (ratio < 0.7 || ratio > 1.45)) {
            System.out.printf(Locale.ROOT, "%s: у оси %.2f закона%n", name, ratio);
            ok = false;
        }
        System.out.printf(Locale.ROOT, "%s: %d клубов, %d лент, %d точек оси, разрывов %d, у оси %.2f закона%n", name, out.puffs.size(),
                out.ribbons.size(), samples.size(), gaps, ratio);
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
            int drawn = FarFlightView.line(view, out, ta, tb, 0.5f, 0.5f, 0.5f, 0.6f, 0.6f, 0.6f, 1, 0.5, seed++, joint);
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

    /** Точка оси на пути path вдоль кусков: x, y, z, σ и ослабление на метр оси. */
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

    /**
     * Тот же класс — ножка облака взрыва (пыль от земли до низа облака, {@code CloudModel.blobs}): ракета, нейтральный воздух
     * с ветром 3 м/с, через 10, 20 и 40 с, из 300 м, 1 и 3 км.
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
            assertTrue(axis.size() > 1, "ножка через " + t + " с");
            double[] top = axis.get(axis.size() - 1);
            for (double range : new double[]{300, 1000, 3000}) {
                FarView view = view(new Vec3(0, 2, -range), at(axis, 0.5 * pathLength(axis)));
                Record out = new Record();
                SmokeGrid.Sink sink = SmokePuffs.sink(view, out, GLOWING, 100);
                model.blobs(t, new CloudModel.Blobs() {
                    @Override
                    // облако и слой пыли у земли — свои клубы, у них своё вещество: здесь — только сплошность самой ножки
                    public void blob(double x, double y, double z, double sh, double sv, double soot, double dust, double water, double kelvin,
                                     double rise) {}

                    @Override
                    public void tube(double ax, double ay, double az, double sa, double dustA, double bx, double by, double bz, double sb,
                                     double dustB, double kelvin, double rise) {
                        sink.tube(ax, ay, az, sa, sa, 0, dustA, bx, by, bz, sb, sb, 0, dustB, albedo, albedo, albedo, kelvin, rise, sa);
                    }
                });
                ok &= along("ножка через " + t + " с (верх " + Math.round(top[5]) + " м), " + range + " м", axis, view, out);
            }
        }
        assertTrue(ok, "ножка с разрывами — подробности выше");
    }

    /** Облако взрыва рисует свой огонь телом: горячее проступает дымом долей остывания. */
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
