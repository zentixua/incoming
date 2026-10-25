package ua.zentix.incoming.client.far;

import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** SMV: непрозрачность кадра слоя по квадратам {@link SmokeFillTest.Screen} — картинка и мера подробности (только проверки). */
final class SmvRaster {
    static final int W = 640, H = 360;
    private static float[][] frames;
    private static int frameSize;

    private static void load() throws IOException {
        if (frames != null) return;
        frames = new float[ua.zentix.incoming.client.fx.layer.FxAtlas.PUFF6_FRAMES][];
        for (int f = 0; f < frames.length; f++) {
            String path = String.format(Locale.ROOT, "/assets/incoming/textures/fx/particle/puff6a_%02d.png", f);
            try (InputStream in = SmvRaster.class.getResourceAsStream(path)) {
                BufferedImage img = ImageIO.read(in);
                int n = img.getWidth();
                frameSize = n;
                float[] a = new float[n * n];
                for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) a[y * n + x] = (img.getRGB(x, y) >>> 24) / 255f;
                frames[f] = a;
            }
        }
    }

    /** Непрозрачность по пикселям W×H (строки сверху вниз). */
    static float[] opacity(SmokeFillTest.Screen s) throws IOException {
        load();
        double tw = SmokeFillTest.W, th = SmokeFillTest.H;
        float[] clear = new float[W * H];
        java.util.Arrays.fill(clear, 1f);
        int n = frameSize;
        for (double[] q : s.quads) {
            double cu = q[0], cv = q[1], hu = q[2], hv = q[3], a = q[4];
            int f = (int) q[5];
            if (a <= 0) continue;
            int x0 = (int) Math.floor(((-(cu + hu)) / tw + 1) * 0.5 * W), x1 = (int) Math.ceil(((-(cu - hu)) / tw + 1) * 0.5 * W);
            int y0 = (int) Math.floor((1 - (cv + hv) / th) * 0.5 * H), y1 = (int) Math.ceil((1 - (cv - hv) / th) * 0.5 * H);
            x0 = Math.max(0, x0);
            y0 = Math.max(0, y0);
            x1 = Math.min(W - 1, x1);
            y1 = Math.min(H - 1, y1);
            float[] img = frames[Math.clamp(f, 0, frames.length - 1)];
            for (int y = y0; y <= y1; y++) {
                double v = (1 - 2 * (y + 0.5) / H) * th, t = (v - cv) / hv;
                if (t < -1 || t > 1) continue;
                int iy = Math.min(n - 1, (int) ((1 - t) * 0.5 * n));
                for (int x = x0; x <= x1; x++) {
                    double u = -(2 * (x + 0.5) / W - 1) * tw, sx = (u - cu) / hu;
                    if (sx < -1 || sx > 1) continue;
                    int ix = Math.min(n - 1, (int) ((sx + 1) * 0.5 * n));
                    clear[y * W + x] *= (float) (1 - a * img[iy * n + ix]);
                }
            }
        }
        Vector3f f = s.view.forward(), l = s.view.left(), up = s.view.up();
        for (double[] p : s.polygons) {
            if (p[12] <= 0 || SmokeFillTest.isFlat(p)) continue;
            double[] pu = new double[4], pv = new double[4];
            boolean ok = true;
            for (int i = 0; i < 4; i++) {
                double x = p[3 * i], y = p[3 * i + 1], z = p[3 * i + 2], depth = x * f.x + y * f.y + z * f.z;
                if (depth <= 0.05) ok = false;
                pu[i] = (x * l.x + y * l.y + z * l.z) / depth;
                pv[i] = (x * up.x + y * up.y + z * up.z) / depth;
            }
            if (!ok) continue;
            double minU = Math.min(Math.min(pu[0], pu[1]), Math.min(pu[2], pu[3])), maxU = Math.max(Math.max(pu[0], pu[1]), Math.max(pu[2], pu[3]));
            double minV = Math.min(Math.min(pv[0], pv[1]), Math.min(pv[2], pv[3])), maxV = Math.max(Math.max(pv[0], pv[1]), Math.max(pv[2], pv[3]));
            int x0 = Math.max(0, (int) Math.floor((-maxU / tw + 1) * 0.5 * W)), x1 = Math.min(W - 1, (int) Math.ceil((-minU / tw + 1) * 0.5 * W));
            int y0 = Math.max(0, (int) Math.floor((1 - maxV / th) * 0.5 * H)), y1 = Math.min(H - 1, (int) Math.ceil((1 - minV / th) * 0.5 * H));
            for (int y = y0; y <= y1; y++) {
                double v = (1 - 2 * (y + 0.5) / H) * th;
                for (int x = x0; x <= x1; x++) {
                    double u = -(2 * (x + 0.5) / W - 1) * tw;
                    if (!inside(pu, pv, u, v)) continue;
                    // поперёк ленты — профиль ореола (fx.fsh glowAlpha): доля пути от края 0–3 к краю 1–2
                    double d0 = edgeDist(pu[0], pv[0], pu[3], pv[3], u, v), d1 = edgeDist(pu[1], pv[1], pu[2], pv[2], u, v);
                    double across = 2 * d0 / Math.max(d0 + d1, 1e-12) - 1;
                    clear[y * W + x] *= (float) (1 - p[12] * glow(Math.abs(across)));
                }
            }
        }
        for (int y = 0; y < H; y++) {
            double v = (1 - 2 * (y + 0.5) / H) * th;
            for (int x = 0; x < W; x++) {
                double u = -(2 * (x + 0.5) / W - 1) * tw;
                if (s.veil != null) clear[y * W + x] *= (float) s.veil.through(u, v);
                for (double[] q : s.faces) {
                    double r = Math.hypot((u - q[0]) / q[2], (v - q[1]) / q[3]);
                    if (r <= 1) clear[y * W + x] *= (float) (1 - q[4] * glow(r));
                }
            }
        }
        float[] out = new float[W * H];
        for (int i = 0; i < out.length; i++) out[i] = 1 - clear[i];
        return out;
    }

    static double glow(double r) {
        double t = Math.clamp((1 - r) / 0.25, 0, 1);
        return Math.exp(-(r / 0.42) * (r / 0.42)) * t * t * (3 - 2 * t) / 0.997147;
    }

    private static double edgeDist(double ax, double ay, double bx, double by, double u, double v) {
        double dx = bx - ax, dy = by - ay, n = Math.hypot(dx, dy);
        return n < 1e-12 ? Math.hypot(u - ax, v - ay) : Math.abs(dx * (v - ay) - dy * (u - ax)) / n;
    }

    private static boolean inside(double[] pu, double[] pv, double u, double v) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double c = (pu[j] - pu[i]) * (v - pv[i]) - (pv[j] - pv[i]) * (u - pu[i]);
            if (c > 0) pos = true;
            if (c < 0) neg = true;
        }
        return !(pos && neg);
    }

    static void save(float[] img, File file) throws IOException {
        BufferedImage b = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int g = (int) Math.round(255 * Math.clamp(img[y * W + x], 0, 1));
                b.setRGB(x, y, g << 16 | g << 8 | g);
            }
        file.getParentFile().mkdirs();
        ImageIO.write(b, "png", file);
    }

    /** Подробность: средняя |разность гауссов σ1 и σ2 пикселей| там, где дым (непрозрачность > 0,05). */
    static double detail(float[] img, double s1, double s2) {
        float[] a = blur(img, s1), b = blur(img, s2);
        double sum = 0;
        int n = 0;
        for (int i = 0; i < img.length; i++) {
            if (b[i] < 0.05) continue;
            sum += Math.abs(a[i] - b[i]);
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    /** SSIM двух картинок (окно — гаусс σ 1,5 px), среднее там, где в эталоне a дым (непрозрачность > 0,05). */
    static double ssim(float[] a, float[] b) {
        float[] ma = blur(a, 1.5), mb = blur(b, 1.5);
        float[] aa = new float[a.length], bb = new float[a.length], ab = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            aa[i] = a[i] * a[i];
            bb[i] = b[i] * b[i];
            ab[i] = a[i] * b[i];
        }
        float[] saa = blur(aa, 1.5), sbb = blur(bb, 1.5), sab = blur(ab, 1.5);
        double c1 = 0.0001, c2 = 0.0009, sum = 0;
        int n = 0;
        for (int i = 0; i < a.length; i++) {
            if (ma[i] < 0.05) continue;
            double va = saa[i] - ma[i] * ma[i], vb = sbb[i] - mb[i] * mb[i], cov = sab[i] - ma[i] * mb[i];
            sum += (2 * ma[i] * mb[i] + c1) * (2 * cov + c2) / ((ma[i] * ma[i] + mb[i] * mb[i] + c1) * (va + vb + c2));
            n++;
        }
        return n == 0 ? 1 : sum / n;
    }

    static float[] blur(float[] img, double sigma) {
        int r = (int) Math.ceil(3 * sigma);
        double[] k = new double[2 * r + 1];
        double ks = 0;
        for (int i = -r; i <= r; i++) ks += k[i + r] = Math.exp(-0.5 * i * i / (sigma * sigma));
        float[] t = new float[img.length], o = new float[img.length];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double s = 0;
                for (int i = -r; i <= r; i++) s += k[i + r] * img[y * W + Math.clamp(x + i, 0, W - 1)];
                t[y * W + x] = (float) (s / ks);
            }
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                double s = 0;
                for (int i = -r; i <= r; i++) s += k[i + r] * t[Math.clamp(y + i, 0, H - 1) * W + x];
                o[y * W + x] = (float) (s / ks);
            }
        return o;
    }
}
