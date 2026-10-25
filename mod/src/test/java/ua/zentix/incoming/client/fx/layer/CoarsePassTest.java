package ua.zentix.incoming.client.fx.layer;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Крупные клубы — мелким кадром ({@link CoarsePass}): картинка клуба от этого не теряется, порядок слоя не меняется, а
 * дешёвый кадр остаётся полным.
 */
class CoarsePassTest {
    private static final Path PARTICLES = Path.of("src/main/resources/assets/incoming/textures/fx/particle");
    private static final int W = 2560, H = 1600;
    private static final Matrix4f PROJECTION = new Matrix4f().perspective((float) Math.toRadians(70), (float) W / H, 0.05f, 1024f);

    /**
     * Кадры запечённого клуба ({@code puff6a_*}, с умноженной альфой), растянутые на p пикселей экрана на тексель: в кадре
     * в 2^k раз мельче (выборка в серединах его пикселей) и билинейно назад — против полного кадра в среднем не дальше
     * 1/255 на канал, на всех p от порога уровня ({@link CoarsePass#PER_TEXEL}) до следующего и при любом сдвиге сетки.
     */
    @Test
    void puffPictureSurvivesItsCoarseLevel() throws IOException {
        for (int frame = 0; frame < FxAtlas.PUFF6_FRAMES; frame++) {
            float[][] img = premultiplied(ImageIO.read(PARTICLES.resolve(String.format("puff6a_%02d.png", frame)).toFile()));
            for (int k = 1; k < CoarsePass.LEVELS; k++) {
                double top = k + 1 < CoarsePass.LEVELS ? CoarsePass.PER_TEXEL[k + 1] : 2 * CoarsePass.PER_TEXEL[k];
                for (double p = CoarsePass.PER_TEXEL[k]; p < top; p *= 1.13) {
                    for (double shift : new double[]{0, 0.37, 0.71}) {
                        double rms = rms(img, p, 1 << k, shift);
                        assertTrue(rms <= 1.0, String.format("кадр %d, уровень %d, %.2f пикселей на тексель, сдвиг %.2f: %.2f/255", frame, k, p, shift, rms));
                    }
                }
            }
        }
    }

    /** Отклонение мелкого кадра от полного (в 1/255, среднеквадратичное по каналам с умноженной альфой). */
    static double rms(float[][] img, double p, int f, double shift) {
        int size = (int) Math.ceil(128 * p), low = (size + f - 1) / f;
        float[][] coarse = new float[low * low][];
        for (int j = 0; j < low; j++) {
            for (int i = 0; i < low; i++) coarse[j * low + i] = bilinear(img, 128, ((i + 0.5) * f - shift) / p, ((j + 0.5) * f - shift) / p);
        }
        double sum = 0;
        int count = 0;
        for (int y = 0; y < size; y += 3) {
            for (int x = 0; x < size; x += 3) {
                float[] full = bilinear(img, 128, (x + 0.5 - shift) / p, (y + 0.5 - shift) / p);
                float[] up = bilinear(coarse, low, (x + 0.5) / f, (y + 0.5) / f);
                for (int c = 0; c < 4; c++) {
                    double d = (up[c] - full[c]) * 255;
                    sum += d * d;
                    count++;
                }
            }
        }
        return Math.sqrt(sum / count);
    }

    /** Билинейная выборка квадратной картинки side×side в месте (x, y) её текселей (середины — +½), край — по краю. */
    private static float[] bilinear(float[][] img, int side, double x, double y) {
        double fx = Math.clamp(x - 0.5, 0, side - 1), fy = Math.clamp(y - 0.5, 0, side - 1);
        int x0 = (int) fx, y0 = (int) fy, x1 = Math.min(x0 + 1, side - 1), y1 = Math.min(y0 + 1, side - 1);
        double ax = fx - x0, ay = fy - y0;
        float[] out = new float[4];
        for (int c = 0; c < 4; c++) {
            double top = img[y0 * side + x0][c] * (1 - ax) + img[y0 * side + x1][c] * ax;
            double bottom = img[y1 * side + x0][c] * (1 - ax) + img[y1 * side + x1][c] * ax;
            out[c] = (float) (top * (1 - ay) + bottom * ay);
        }
        return out;
    }

    static float[][] premultiplied(BufferedImage image) {
        assertEquals(128, image.getWidth());
        float[][] out = new float[128 * 128][];
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                int argb = image.getRGB(x, y);
                float a = (argb >>> 24) / 255f;
                out[y * 128 + x] = new float[]{(argb >> 16 & 255) / 255f * a, (argb >> 8 & 255) / 255f * a, (argb & 255) / 255f * a, a};
            }
        }
        return out;
    }

    /**
     * Уровень клуба — по его пикселям на тексель: клуб полуразмером h на расстоянии d занимает 2h/d·f пикселей поперёк
     * (f — фокус кадра в пикселях), кадр клуба — 128 текселей.
     */
    @Test
    void puffLevelFollowsItsPixelsPerTexel() {
        double focal = H / 2.0 / Math.tan(Math.toRadians(35));
        for (double perTexel : new double[]{0.5, 2.9, 3.1, 6.9, 7.1, 13.9, 14.1, 40}) {
            FxQuads quads = frame();
            double d = 60, half = perTexel * 128 / focal * d / 2;
            puff(quads, 0, 0, d, half);
            int expected = perTexel >= 14 ? 3 : perTexel >= 7 ? 2 : perTexel >= 3 ? 1 : 0;
            assertEquals(expected, quads.level(0), perTexel + " пикселей на тексель");
        }
        // шесть клубов шире экрана (25 пикселей на тексель) — одним отрезком в 8 раз мельче
        FxQuads quads = frame();
        double d = 20, half = 25.0 * 128 / focal * d / 2;
        for (int i = 0; i < 6; i++) puff(quads, 0, 0, d + i * 0.5, half);
        int runs = quads.plan();
        assertEquals(1, runs);
        assertEquals(3, quads.runLevel(0));
    }

    /** Клубов меньше {@link CoarsePass#GATE} экранов — кадр как прежде: один отрезок полного кадра. */
    @Test
    void cheapSmokeStaysAtFullResolution() {
        double focal = H / 2.0 / Math.tan(Math.toRadians(35));
        FxQuads quads = frame();
        // клуб в пол-экрана поперёк при 20 пикселях на тексель — растянут, но заливки мало
        double d = 100, half = 0.25 * W / focal * d;
        for (int i = 0; i < 3; i++) puff(quads, 0, 0, d + 10 * i, half);
        assertEquals(1, quads.plan());
        assertEquals(0, quads.runLevel(0));
    }

    /**
     * Мелкое между крупными (искра, дальний клуб) рвёт отрезок: порядок от дальних к ближним остаётся, у каждого отрезка
     * уровень не мельче, чем у любого его квадрата, отрезки покрывают все квадраты подряд.
     */
    @Test
    void runsKeepTheOrderAndEveryQuadsLevel() {
        Random rnd = new Random(5);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + rnd.nextInt(300);
            byte[] level = new byte[n];
            float[] cover = new float[n];
            for (int i = 0; i < n; i++) {
                level[i] = (byte) rnd.nextInt(CoarsePass.LEVELS);
                cover[i] = (float) (rnd.nextDouble() < 0.2 ? rnd.nextDouble() : 0.01 * rnd.nextDouble());
            }
            int[] from = new int[CoarsePass.MAX_RUNS + 1];
            byte[] at = new byte[CoarsePass.MAX_RUNS + 1];
            int runs = CoarsePass.plan(n, level, cover, from, at);
            assertTrue(runs >= 1 && runs <= CoarsePass.MAX_RUNS, "отрезков " + runs);
            assertEquals(0, from[0]);
            double cost = 0, full = 0;
            for (int r = 0; r < runs; r++) {
                int b = r + 1 < runs ? from[r + 1] : n;
                assertTrue(b > from[r], "отрезок " + r + " пуст");
                if (r > 0) assertTrue(at[r] != at[r - 1], "соседние отрезки одного уровня");
                for (int i = from[r]; i < b; i++) {
                    assertTrue(at[r] <= level[i], "квадрат " + i + " уровня " + level[i] + " в отрезке уровня " + at[r]);
                    cost += cover[i] / (1 << 2 * at[r]);
                    full += cover[i];
                }
                if (at[r] > 0) cost += CoarsePass.RUN;
            }
            assertTrue(cost <= full + 1e-6, "план дороже полного кадра: " + cost + " против " + full);
        }
    }

    /** Искра (не клуб) между крупными клубами — в полном кадре, клубы до и после — мелким. */
    @Test
    void sparkBetweenBigPuffsSplitsTheRun() {
        double focal = H / 2.0 / Math.tan(Math.toRadians(35));
        FxQuads quads = frame();
        // клуб в 1800 пикселей поперёк на 45 блоках: 14 пикселей на тексель, 0,7 экрана
        double half = 1800 / focal * 45 / 2;
        for (int i = 0; i < 5; i++) puff(quads, 0, 0, 50 - i, half);
        quads.sixWay(-1);
        quads.billboard(0, 0, -44, 44, 0.5f, 0, FxAtlas.disc(), 1, 1, 1, 1, 0, 1, LightTexture.FULL_BRIGHT, 0);
        for (int i = 0; i < 5; i++) puff(quads, 0, 0, 40 - i, half);
        int runs = quads.plan();
        assertEquals(3, runs);
        assertEquals(0, quads.runFrom(0));
        assertEquals(5, quads.runFrom(1));
        assertEquals(6, quads.runFrom(2));
        assertTrue(quads.runLevel(0) > 0 && quads.runLevel(2) > 0);
        assertEquals(0, quads.runLevel(1));
    }

    /**
     * Без мелких кадров ({@code coarse off}, нет расстояния до мира) — один отрезок полного кадра, даже если прошлый кадр
     * шёл мелким отрезком (ревью #455: план прошлого кадра рисовал весь слой мелким).
     */
    @Test
    void withoutCoarseTargetsTheLayerIsFullResolution() {
        double focal = H / 2.0 / Math.tan(Math.toRadians(35));
        FxQuads quads = frame();
        double d = 20, half = 25.0 * 128 / focal * d / 2;
        for (int i = 0; i < 6; i++) puff(quads, 0, 0, d + i * 0.5, half);
        assertEquals(1, quads.runs(true));
        assertEquals(3, quads.runLevel(0));
        assertEquals(1, quads.runs(false));
        assertEquals(0, quads.runLevel(0));
        assertEquals(0, quads.runFrom(0));
    }

    /**
     * Кадр, не кратный 2^k (1921×1081): пиксель t мелкого кадра растр кладёт на (t + ½)·w / W полных, а не на (t + ½)·2^k
     * (ревью #455: до 7 пикселей мимо у 1/8) — та же доля у шейдера слоя ({@code FxCoarseScale}) и у вывода
     * ({@code CoarseScale}).
     */
    @Test
    void coarsePixelMapsToTheFullPixelTheRasterGivesIt() throws IOException {
        for (int w : new int[]{1921, 1081, 1572, 2560, 1}) {
            for (int k = 1; k < CoarsePass.LEVELS; k++) {
                int small = CoarsePass.size(w, k);
                assertTrue(small * (1 << k) >= w && (small - 1) * (1 << k) < w, w + " у " + k);
                double scale = (double) w / small;
                for (int t = 0; t < small; t++) {
                    // NDC середины пикселя t мелкого кадра — и где та же NDC в полном
                    double ndc = (t + 0.5) / small * 2 - 1, full = (ndc + 1) / 2 * w;
                    assertEquals(full, (t + 0.5) * scale, 1e-9);
                }
            }
        }
        String fsh = shader("core/fx.fsh"), out = shader("core/fx_coarse.fsh");
        assertTrue(fsh.contains("vec2 frag = gl_FragCoord.xy * mix(vec2(FxGlowPass.y), FxCoarseScale, float(coarse));"), "шейдер слоя — долей кадра");
        assertTrue(out.contains("ivec2((vec2(t) + 0.5) * CoarseScale)") && out.contains("(vec2(full) + 0.5) / CoarseScale - 0.5"), "вывод — той же долей");
    }

    /**
     * Прут или решётка тоньше пикселя мелкого кадра (ревью #455): ни один из четырёх соседей мелкого кадра не то же место —
     * вывод пиксель не трогает, маска его отмечает, и квадраты отрезка досчитывают его в полном кадре с аппаратной
     * глубиной: дым перед прутом ложится на прут, дым за ним — нет, как без мелкого кадра. Картинку держат кадры решётки
     * за дымом и перед ним (PR); здесь — что вывод, маска и досчёт делят пиксели без щелей и без двойного вклада.
     */
    @Test
    void unresolvedPixelsAreDrawnAtFullResolution() throws IOException {
        String fsh = shader("core/fx.fsh"), out = shader("core/fx_coarse.fsh").replaceAll("\\s+", " ");
        // одно условие «не разрешён» у маски и у вывода: маска — только там, вывод — только вне
        assertTrue(out.contains("bool unresolved = same <= 0.5;"), "условие");
        assertTrue(out.contains("if (CoarseMask > 0.5) { if (!unresolved) discard; fragColor = vec4(1.0); return; } if (unresolved) discard;"),
                "маска и вывод делят пиксели");
        // досчёт: шейдер слоя отбрасывает всё вне маски до света, в маске — как обычный квадрат (с глубиной кадра)
        assertTrue(fsh.contains("if (FxCoarseFill > 0.5 && texelFetch(FxCoarseMask, ivec2(gl_FragCoord.xy), 0).r < 0.5) discard;"), "досчёт по маске");
        String code = java.nio.file.Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/fx/layer/CoarsePass.java"));
        int maskPass = code.indexOf("c.safeGetUniform(\"CoarseMask\").set(1f);"), composite = code.indexOf("c.safeGetUniform(\"CoarseMask\").set(0f);"),
                fill = code.indexOf("s.safeGetUniform(\"FxCoarseFill\").set(1f);"), redraw = code.indexOf("quads.range(s, a, b);", fill);
        assertTrue(maskPass > 0 && composite > maskPass && fill > composite && redraw > fill, "маска, вывод, досчёт — по порядку, в том же отрезке");
    }

    /**
     * Разрыв глубины в 4 % — прут на 100 блоках перед стеной на 104 (ревью #455): соседи мелкого кадра у прута — не то же
     * место, пиксель уходит в досчёт; та же поверхность (104 против 104,3: наклон в пределах пикселя мелкого кадра) —
     * то же. Шейдер вывода — с тем же допуском, что здесь.
     */
    @Test
    void depthStepOfAFewPercentIsNotTheSamePlace() throws IOException {
        assertTrue(CoarsePass.alike(100, 104) < 0.5, "прут и стена");
        assertTrue(CoarsePass.alike(100, 102) < 0.5, "прут и дым за ним");
        assertTrue(CoarsePass.alike(104, 104.3) > 0.5, "одна стена");
        assertTrue(CoarsePass.alike(0.5, 0.501) > 0.5, "вплотную");
        String out = shader("core/fx_coarse.fsh").replaceAll("\\s+", " ");
        assertTrue(out.contains("const float COARSE_SAME = " + (float) CoarsePass.SAME + ";"), "допуск шейдера");
        assertTrue(out.contains("float rel = abs(d - e) / max(min(d, e), 0.1) / COARSE_SAME; return 1.0 / (1.0 + rel * rel);"), "та же формула");
        assertTrue(out.contains("coarseAlike(") && !out.contains("pallAlike("), "вывод — строгим сходством");
    }

    /**
     * Плоский квадрат пыли вскользь (ревью #455): 180 блоков поперёк, середина в 100 блоках впереди, на блок ниже глаза.
     * Ближний край растянут во много пикселей на тексель, дальний — в доли пикселя: квадрат — полным кадром.
     */
    @Test
    void flatQuadSeenEdgeOnStaysFullResolution() {
        FxQuads quads = frame();
        quads.sixWay(0.5f);
        quads.flat(0, -1, -100, 100, 90, 90, FxAtlas.puff6(3, false), 0.5f, 0.5f, 0.5f, 0.5f, 2, 1, LightTexture.FULL_BRIGHT, 0);
        quads.sixWay(-1);
        assertEquals(0, quads.level(0));
        // тот же квадрат лицом к глазу на тех же 100 блоках — крупный клуб, мелким кадром
        double focal = H / 2.0 / Math.tan(Math.toRadians(35));
        FxQuads facing = frame();
        puff(facing, 0, 0, 100, 90);
        assertTrue(90 * 2 / 100.0 * focal / 128 > CoarsePass.PER_TEXEL[CoarsePass.LEVELS - 1]);
        assertEquals(CoarsePass.LEVELS - 1, facing.level(0));
    }

    private static String shader(String path) throws IOException {
        return java.nio.file.Files.readString(Path.of("src/main/resources/assets/incoming/shaders").resolve(path)).replaceAll("//[^\n]*", "");
    }

    private static FxQuads frame() {
        FxQuads quads = new FxQuads();
        quads.begin(new Vector3f(-1, 0, 0), new Vector3f(0, 1, 0));
        quads.screen(PROJECTION, W, H);
        return quads;
    }

    private static void puff(FxQuads quads, double x, double y, double d, double half) {
        quads.sixWay(0.5f);
        quads.billboard((float) x, (float) y, (float) -d, (float) d, (float) half, 0, FxAtlas.puff6(3, false), 0.5f, 0.5f, 0.5f, 0.5f, 2, 1,
                LightTexture.FULL_BRIGHT, 0);
        quads.sixWay(-1);
    }
}
