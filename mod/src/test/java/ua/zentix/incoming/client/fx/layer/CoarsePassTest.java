package ua.zentix.incoming.client.fx.layer;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
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
        assertTrue(out.contains("bool unresolved = total <= 0.0;"), "условие");
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
     * Допуск «то же место» у вывода ({@code coarseTolerance}) — абсолютный, в блоках: десятая блока плюс 16 шагов
     * 24-битной глубины на этом расстоянии. Шагов наклон ровной земли (глубина кадра с ближней плоскостью 0,05, вскользь
     * и под углом, отступ образца до 12 пикселей) ошибается не больше 16 — ни один пиксель земли в досчёт не уходит.
     */
    @Test
    void sameSurfaceIsTheSamePlaceAtTheDepthBuffersOwnStep() throws IOException {
        String glsl = shader("include/pall.glsl") + shader("core/fx_coarse.fsh");
        GlslFunction tolerance = GlslFunction.parse(glsl, "coarseTolerance"), slope = GlslFunction.parse(glsl, "pallSlopeAxis"),
                alike = GlslFunction.parse(glsl, "coarseAlike");
        assertEquals(16 / (0.05 * (1 << 24)), GlslFunction.parse("float k() { return COARSE_STEP; }" + glsl, "k").call(Map.of()), 1e-11);
        assertEquals(0.29, tolerance.call(Map.of("d", 100.0)), 0.01);
        assertEquals(0.86, tolerance.call(Map.of("d", 200.0)), 0.01);
        double n = 0.05, f = 1024, h = 1.6, focal = H / 2.0 / Math.tan(Math.toRadians(35));
        int checked = 0;
        for (double pitch : new double[]{0, 5, 20, 60}) {
            double[] z = new double[H];
            for (int row = 0; row < H; row++) {
                // ряд экрана сверху вниз, ось взгляда опущена на pitch: расстояние до ровной земли на h ниже глаза по оси
                // взгляда — через 24-битную глубину, как у кадра Minecraft
                double a = Math.atan((row - H / 2 + 0.5) / focal), down = a + Math.toRadians(pitch);
                double along = down > 1e-6 ? h / Math.sin(down) * Math.cos(a) : 1e9;
                if (along > f) {
                    z[row] = 1e9;
                    continue;
                }
                double ndc = (f + n) / (f - n) - 2 * f * n / ((f - n) * along), d01 = Math.round((ndc + 1) / 2 * ((1 << 24) - 1)) / (double) ((1 << 24) - 1);
                z[row] = 2 * f * n / ((f + n) - (d01 * 2 - 1) * (f - n));
            }
            for (int row = 1; row < H - 1; row++) {
                if (z[row - 1] >= 1e8 || z[row] >= 1e8 || z[row + 1] >= 1e8) continue;
                double w = 1 / z[row], tol = tolerance.call(Map.of("d", z[row])) / z[row];
                double s = slope.call(Map.of("l", w - 1 / z[row - 1], "r", 1 / z[row + 1] - w, "w", w, "tol", tol));
                for (int o = -12; o <= 12; o++) {
                    if (row + o < 0 || row + o >= H || z[row + o] >= 1e8) continue;
                    double ahead = 1 / Math.max(w + s * o, 1e-9), a = alike.call(Map.of("d", z[row + o], "e", ahead));
                    assertTrue(a > 0.5, String.format(Locale.ROOT, "земля %.0f° ряд %d (%.1f блока) в %d: %.3f", pitch, row, z[row], o, a));
                    checked++;
                }
            }
        }
        assertTrue(checked > 10000, "проверено " + checked);
    }

    /**
     * Цепочка вывода на краях, по самому шейдеру ({@link GlslFunction}: наклон {@code pallSlopeAxis}, сходство
     * {@code coarseAlike}, вес {@code coarseWeight}; место на той же поверхности {@code pallAhead} — здесь по одной оси).
     * Прут в пиксель перед стеной, дым между ними (ревью #455: 100 и 104 блока, 200 и 201, 100 и 100,5): наклона у прута
     * нет, ни один сосед мелкого кадра у стены — не то же место, пиксель уходит в досчёт; с допуском наклона пелены (5 %)
     * наклон вёл бы к стене. Край переднего предмета с билинейкой 1/16 у него и 15/16 у фона: фон в цвет не идёт вовсе.
     * Ровная земля вскользь (1/расстояние по экрану линейно, у соседа на 5 % дальше): наклон держится, соседи — то же место.
     */
    @Test
    void edgesOfTheWorldKeepCoarseSmokeOnItsOwnSurface() throws IOException {
        String glsl = shader("include/pall.glsl") + shader("core/fx_coarse.fsh");
        GlslFunction slope = GlslFunction.parse(glsl, "pallSlopeAxis"), alike = GlslFunction.parse(glsl, "coarseAlike"),
                weight = GlslFunction.parse(glsl, "coarseWeight"), tolerance = GlslFunction.parse(glsl, "coarseTolerance");
        String flat = glsl.replaceAll("\\s+", " ");
        assertTrue(flat.contains("return 1.0 / max(1.0 / depth + dot(slope, offset), 1e-9);"), "pallAhead — как здесь");
        assertTrue(flat.contains("slope = pallSlopeWithin(Sampler1, full, fullSize, depth, coarseTolerance(depth) / depth);"), "наклон — с допуском мелкого кадра");
        assertTrue(flat.contains("float a = coarseAlike(sampleDepth(t, fullSize), pallAhead(depth, slope, vec2(rayPixel(t, fullSize) - full)));"), "сходство");
        assertTrue(flat.contains("float w = coarseWeight(mix(1.0 - f.x, f.x, float(i)) * mix(1.0 - f.y, f.y, float(j)), a);"), "вес — coarseWeight");
        assertTrue(flat.contains("return vec2(pallSlopeAxis(l, r, w, tol), pallSlopeAxis(d, u, w, tol));"), "наклон по осям");
        assertTrue(flat.contains("return pallSlopeWithin(scene, at, size, depth, 0.05);"), "пелена — со своими 5 %");
        assertTrue(!flat.contains("pallAlike(sampleDepth"), "вывод — не сходством пелены");

        // прут в пиксель у rod, стена wall у соседей и у образцов мелкого кадра в −3 и +5 пикселях (1/8)
        for (double[] pair : new double[][]{{100, 104}, {200, 201}, {100, 100.5}, {30, 30.2}}) {
            double rod = pair[0], wall = pair[1], w = 1 / rod, l = w - 1 / wall, r = 1 / wall - w;
            double s = slope.call(Map.of("l", l, "r", r, "w", w, "tol", tolerance.call(Map.of("d", rod)) / rod));
            assertEquals(0, s, "у прута наклона нет: " + rod + "/" + wall);
            double total = 0;
            for (int off : new int[]{-3, 5}) {
                double ahead = 1 / Math.max(w + s * off, 1e-9), a = alike.call(Map.of("d", wall, "e", ahead));
                total += weight.call(Map.of("b", 0.5, "a", a));
            }
            assertEquals(0, total, "прут — в досчёт: " + rod + "/" + wall);
        }
        double w = 1 / 100.0, l = w - 1 / 104.0, r = 1 / 104.0 - w, loose = slope.call(Map.of("l", l, "r", r, "w", w, "tol", 0.05));
        assertTrue(alike.call(Map.of("d", 104.0, "e", 1 / (w + loose))) > 0.5, "допуск пелены вёл бы наклон к стене");

        // край: передний образец 1/16, фон 15/16
        double front = weight.call(Map.of("b", 1 / 16.0, "a", alike.call(Map.of("d", 100.0, "e", 100.0)))),
                back = weight.call(Map.of("b", 15 / 16.0, "a", alike.call(Map.of("d", 104.0, "e", 100.0))));
        assertTrue(front > 0);
        assertEquals(0, back, "фон в цвет не идёт");

        // ровная земля вскользь: 1/расстояние меняется на 5 % за пиксель
        double g = 1 / 40.0, step = -0.05 * g, gs = slope.call(Map.of("l", step, "r", step, "w", g, "tol", tolerance.call(Map.of("d", 40.0)) * g));
        assertEquals(step, gs, 1e-15);
        for (int off : new int[]{-3, 5}) {
            double there = 1 / (g + step * off);
            assertTrue(alike.call(Map.of("d", there, "e", 1 / Math.max(g + gs * off, 1e-9))) > 0.99, "земля в " + off);
        }

        // наклон пелены — тот же, что был: меньшая разность, гребень дальше 5 % — без наклона
        Random rnd = new Random(7);
        for (int k = 0; k < 2000; k++) {
            double ww = 0.001 + rnd.nextDouble() * 0.1, ll = (rnd.nextDouble() - 0.5) * 0.3 * ww, rr = (rnd.nextDouble() - 0.5) * 0.3 * ww;
            double old = ll * rr < 0 && Math.min(Math.abs(ll), Math.abs(rr)) > 0.05 * ww ? 0 : Math.abs(ll) < Math.abs(rr) ? ll : rr;
            assertEquals(old, slope.call(Map.of("l", ll, "r", rr, "w", ww, "tol", 0.05)), 1e-15, ll + " " + rr + " " + ww);
        }
    }

    /**
     * Плоский квадрат пыли вскользь (ревью #455): 180 блоков поперёк, середина в 100 блоках впереди, на блок ниже глаза.
     * Ближний край растянут во много пикселей на тексель, дальний — в доли пикселя: квадрат — полным кадром. Ближе
     * (ближний край у глаза или за ним) — тоже полным кадром, без скачка уровня, когда край проходит глаз.
     */
    @Test
    void flatQuadSeenEdgeOnStaysFullResolution() {
        for (double d : new double[]{100, 91, 90, 80, 30}) {
            FxQuads quads = frame();
            quads.sixWay(0.5f);
            quads.flat(0, -1, (float) -d, (float) d, 90, 90, FxAtlas.puff6(3, false), 0.5f, 0.5f, 0.5f, 0.5f, 2, 1, LightTexture.FULL_BRIGHT, 0);
            quads.sixWay(-1);
            assertEquals(0, quads.level(0), "в " + d);
        }
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
