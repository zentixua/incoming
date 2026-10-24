package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.fx.smoke.WaterBlast;
import ua.zentix.incoming.strike.WeaponType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Залп «Града» по воде у камеры (финальный проход 3.7.0, сцена {@code grad-day}: на 6 с — полоса брызг с прямыми
 * вертикальными краями, на 12 с — ровно-серый экран, на 20 с — прямоугольные «простыни»). Двенадцать разрывов в воду в
 * круге 15 м через секунду, глаз — в 30 м и 14 м над водой, взгляд — на 6 м над серединой; ветер 6 м/с. Брызги
 * раскладывает тот же приёмник слоя, что в игре ({@link SmokePuffs#sink}), квадраты пишет свой приёмник.
 * <ul>
 * <li>Клуб брызг не вытянут: стоящий квадрат кадра брызг — не длиннее {@value #ROUND} раза поперёк (растянутый кадр клал
 * струю плоской полосой с прямыми боками).</li>
 * <li>Глаз — вне столбов: вуали нет (столб шире закона накрывал камеру, и вуаль заливала экран).</li>
 * <li>Через 9 с после последнего разрыва (вода упала, водяная пыль — тонким слоем у воды) брызги закрывают меньше
 * {@value #FOG_COVER} кадра хотя бы наполовину.</li>
 * </ul>
 */
class WaterSalvoTest {
    /** Стоящий клуб брызг — не длиннее стольких раз поперёк. */
    static final double ROUND = 1.25;
    /** Доля кадра, закрытая брызгами больше чем наполовину, через 9 с после последнего разрыва — не больше. */
    static final double FOG_COVER = 0.1;

    /** Брызги разрыва на воде — источник, как событие {@code FarBlasts.Event} (без облака самого взрыва). */
    record Spray(WaterBlast water, double x, double y, double z, double born, long seed) implements SmokeGrid.Source {
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            double t = seconds - born;
            if (t < 0) return true;
            return water.blobs(t, out, x, y, z);
        }

        @Override
        public double puffGrowth(double seconds) {
            return 1;
        }

        @Override
        public long seed() {
            return seed;
        }

        @Override
        public boolean drawsGlow() {
            return true;
        }

        @Override
        public boolean sprayClubs() {
            return true;
        }

        @Override
        public boolean sprayClubs(double seconds) {
            return water.aloft(seconds - born) > 0;
        }
    }

    /** Квадраты кадра на экране (тангенсы): середина, полуразмеры, непрозрачность, кадр, брызги ли, лежит ли; вуаль. */
    static final class Screen implements SmokePuffs.Out {
        final FarView view;
        final List<double[]> quads = new ArrayList<>();
        /** Вуаль у глаза кадра (копия сетки), null — её нет. */
        SmokePuffs.Veil veil;

        Screen(FarView view) {
            this.view = view;
        }

        void add(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float a, boolean flat) {
            if (a < 0.002f) return;
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            double depth = dx * f.x + dy * f.y + dz * f.z;
            if (depth <= 0.3) return;
            int frame = -1, spray = 0, mirror = 0;
            for (int i = 0; i < FxAtlas.PUFF6_FRAMES; i++) {
                for (int m = 0; m < 2; m++) {
                    if (FxAtlas.puff6(i, m == 1).equals(sprite)) {
                        frame = i;
                        spray = 0;
                        mirror = m;
                    }
                    if (FxAtlas.spray6(i, m == 1).equals(sprite)) {
                        frame = i;
                        spray = 1;
                        mirror = m;
                    }
                }
            }
            quads.add(new double[]{-(dx * l.x + dy * l.y + dz * l.z) / depth, (dx * up.x + dy * up.y + dz * up.z) / depth, halfA / depth, halfB / depth,
                    Math.min(1, a), frame, spray, mirror, flat ? 1 : 0, halfA, halfB});
        }

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                          float b, float a) {
            add(dx, dy, dz, halfA, halfB, sprite, a, false);
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, FxAtlas.Sprite sprite, float mix, float shade,
                              float r, float g, float b, float a) {
            // лежащий квадрат: по высоте экрана — проекция диска
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            add(dx, dy, dz, halfA, halfB * Math.abs(dy) / d, sprite, a, true);
        }

        @Override
        public void veil(SmokePuffs.Veil veil, int light) {
            this.veil = veil.copy();
        }

        /** Пропускание вуали в точке экрана (u — вправо, v — вверх, тангенсы); вуали нет — 1. */
        double through(double u, double v) {
            return veil == null ? 1 : veil.through(-u, v);
        }

        /** Наибольшая непрозрачность вуали по кадру. */
        double veiled() {
            double most = 0;
            for (int j = 0; j < 27; j++) {
                for (int i = 0; i < 48; i++) {
                    double u = -SmokeFillTest.W + (i + 0.5) * 2 * SmokeFillTest.W / 48, v = -SmokeFillTest.H + (j + 0.5) * 2 * SmokeFillTest.H / 27;
                    most = Math.max(most, 1 - through(u, v));
                }
            }
            return most;
        }

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                              float alphaB, int lightB, float r, float g, float b, float[] joint) {
            return false;
        }

        /** Доля кадра (сетка 160 × 90), где квадраты вместе закрывают больше половины: альфа кадров атласа, как на листе. */
        double covered(float[][][] alpha) {
            int w = 160, h = 90, n = 0;
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    double u = -SmokeFillTest.W + (i + 0.5) * 2 * SmokeFillTest.W / w, v = SmokeFillTest.H - (j + 0.5) * 2 * SmokeFillTest.H / h, clear = 1;
                    for (double[] q : quads) {
                        if (q[5] < 0) continue;
                        double s = (u - q[0]) / q[2], t = (v - q[1]) / q[3];
                        if (Math.abs(s) >= 1 || Math.abs(t) >= 1) continue;
                        if (q[7] > 0.5) s = -s;
                        int tx = (int) Math.min(127, (s + 1) / 2 * 128), ty = (int) Math.min(127, (1 - t) / 2 * 128);
                        clear *= 1 - q[4] * alpha[(int) q[6]][(int) q[5]][ty * 128 + tx];
                    }
                    if (through(u, v) * clear < 0.5) n++;
                }
            }
            return (double) n / (w * h);
        }
    }

    /** Альфа кадров клуба дыма и брызг: [брызги ли][кадр][пиксель 128 × 128]. */
    static float[][][] alpha() throws IOException {
        float[][][] out = new float[2][FxAtlas.PUFF6_FRAMES][];
        for (int k = 0; k < 2; k++) {
            for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
                String p = String.format(Locale.ROOT, "/assets/incoming/textures/fx/particle/%s_%02d.png", k == 1 ? "spray6a" : "puff6a", f);
                try (InputStream in = WaterSalvoTest.class.getResourceAsStream(p)) {
                    assertNotNull(in, p);
                    BufferedImage im = ImageIO.read(in);
                    float[] a = new float[128 * 128];
                    for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) a[y * 128 + x] = ((im.getRGB(x, y) >>> 24) & 255) / 255f;
                    out[k][f] = a;
                }
            }
        }
        return out;
    }

    /** Залп: двенадцать разрывов «Града» в воду глубиной 4 м в круге 15 м вокруг (0, 0, 0) через секунду. */
    static List<Spray> salvo() {
        CloudModel.Air air = new CloudModel.Air(0.012, 0, -6, 0.14, 2, 1);
        Random rnd = new Random(7);
        List<Spray> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            double a = 2 * Math.PI * rnd.nextDouble(), r = 15 * Math.sqrt(rnd.nextDouble());
            out.add(new Spray(new WaterBlast(WeaponType.ROCKET.spec().charge(), 0.5, 4, air), r * Math.cos(a), 0, r * Math.sin(a), i, i + 1));
        }
        return out;
    }

    static Screen frame(List<Spray> salvo, double now) {
        FarView v = SmokeFillTest.view(new Vec3(0, 14, 30), new Vector3f(0, -8, -30));
        Screen s = new Screen(v);
        // два кадра: доля кучек трубок — из прошлого кадра
        SmokePuffs.resetBudget();
        for (int k = 0; k < 2; k++) {
            s.quads.clear();
            s.veil = null;
            for (Spray b : salvo) b.emit(now, SmokePuffs.sink(v, s, b, now));
            SmokePuffs.flush();
        }
        SmokePuffs.resetBudget();
        return s;
    }

    /**
     * Клубы отрезка ждут слияния и бюджета, как клубы-точки, но друг с другом не сливаются: шаг отрезка — по меньшей σ, и у
     * плоского ряда пыли (σh 6 м, σv 1 м, 40 м вдоль x — пыль у старта, {@code LaunchCloud}) соседи ближе σh — слитые, они
     * собрались бы в один круглый в плане гаусс. Те же клубы точками (не отрезка) сливаются.
     */
    @Test
    void segmentClubsDoNotMergeWithEachOther() {
        for (boolean segment : new boolean[]{true, false}) {
            int n = SmokePuffs.SEGMENT_CLUBS;
            double[] held = new double[n * SmokePuffs.FIELDS];
            for (int k = 0; k < n; k++) {
                int o = k * SmokePuffs.FIELDS;
                held[o] = -20 + 40.0 * (k + 0.5) / n;
                held[o + 1] = 1;
                held[o + 3] = 6;
                held[o + 4] = 1;
                held[o + 6] = 5.0 / n;
                held[o + 8] = 0.6;
                held[o + 9] = 0.55;
                held[o + 10] = 0.45;
                // номер: у клубов отрезка — один отрезок (номер / SEGMENT_CLUBS), у точек — каждый свой
                held[o + 11] = segment ? 3 * n + k : (3 + k) * n;
                held[o + 13] = 1;
                held[o + 14] = segment ? 1 : 0;
            }
            boolean[] gone = new boolean[n];
            SmokePuffs.merge(held, 0, n, gone, CloudModel.SOOT_K, SmokeGrid.DROP_K);
            int left = 0;
            for (boolean g : gone) if (!g) left++;
            if (segment) assertTrue(left == n, "клубы отрезка слились: осталось " + left + " из " + n);
            else assertTrue(left < n / 2, "точки рядом не слились: осталось " + left);
        }
    }

    @Test
    void salvoIntoWaterIsNotAWall() throws IOException {
        List<Spray> salvo = salvo();
        float[][][] alpha = alpha();
        for (double now : new double[]{3, 6, 12, 20}) {
            Screen s = frame(salvo, now);
            double worst = 0;
            for (double[] q : s.quads) {
                if (q[6] < 0.5 || q[8] > 0.5) continue;
                worst = Math.max(worst, Math.max(q[9] / q[10], q[10] / q[9]));
            }
            double cover = s.covered(alpha);
            double veil = s.veiled();
            String got = String.format(Locale.ROOT, "%.0f с: квадратов %d, самый вытянутый клуб брызг %.2f, вуаль до %.3f, закрыто больше половины %.2f кадра",
                    now, s.quads.size(), worst, veil, cover);
            System.out.println("SALVO " + got);
            assertTrue(worst <= ROUND, got);
            assertTrue(veil < 0.02, got);
            if (now >= 20) assertTrue(cover <= FOG_COVER, got);
        }
    }
}
