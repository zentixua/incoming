package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.IncomingConfig.Client.Bloom;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свечение слоя ({@link FxBloom}): пирамида на процессоре — те же выборки и веса, что у шейдеров
 * ({@link #shadersSampleLikeTheReference}), законы шейдеров — те же числа, что в Java, пятна мелкого светящегося — на
 * модели растеризации прохода излучения (выборка в серединах пикселей, ореол — функцией из самого {@code fx.fsh}).
 */
class FxBloomTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/incoming/shaders");

    private static String read(String path) throws IOException {
        return Files.readString(SHADERS.resolve(path)).replaceAll("//[^\n]*", "");
    }

    /**
     * Излучение прохода свечения (уровень g кадра w×h) с точкой энергии 1 в пикселях кадра (пиксель уровня g — 4^g пикселей
     * кадра) в (x, y) пикселей кадра; между середин — долями соседей, как сдвиг на долю пикселя.
     */
    private static FxBloom.Image point(int w, int h, int g, double x, double y) {
        int gw = FxBloom.size(w, g), gh = FxBloom.size(h, g);
        double k = 1 << g, e0 = 1 / (k * k);
        FxBloom.Image e = FxBloom.Image.of(gw, gh);
        double px = x / k, py = y / k;
        int i = (int) Math.floor(px - 0.5), j = (int) Math.floor(py - 0.5);
        double fx = px - 0.5 - i, fy = py - 0.5 - j;
        e.v()[j * gw + i] += (float) (e0 * (1 - fx) * (1 - fy));
        e.v()[j * gw + i + 1] += (float) (e0 * fx * (1 - fy));
        e.v()[(j + 1) * gw + i] += (float) (e0 * (1 - fx) * fy);
        e.v()[(j + 1) * gw + i + 1] += (float) (e0 * fx * fy);
        return e;
    }

    /**
     * Ореол точки в кадре 2560×1600 (у хоста), обе настройки качества, — закон рассеяния в глазу CIE: равная энергия на
     * октаву угла — яркость ∝ 1/r² (r²·B = 1/(уровней·2π·ln 2)) от 16 до 256 пикселей в пределах 25 % (сбор B-сплайном
     * кладёт ближнее крыло уровня чуть уже подъёма шатрами), дальше до края пирамиды — 35 %; круглый (по диагонали — то же,
     * что по осям: без квадратов уровней), спадает без ступеней и полос, энергия — та же, что у источника.
     */
    @Test
    void haloFollowsTheEyeScatterLaw() {
        int w = 2560, h = 1600, cx = 1280, cy = 800;
        assertEquals(3, FxBloom.first(h), "уровни как у пака: на октаву глубже 1080");
        assertEquals(2, FxBloom.glowLevel(h, Bloom.FULL), "FULL: проход излучения на октаву мельче первого уровня");
        assertEquals(3, FxBloom.glowLevel(h, Bloom.FAST), "FAST: проход излучения — сам первый уровень");
        int levels = FxBloom.last(w, h) - FxBloom.first(h) + 1;
        assertEquals(FxBloom.LEVELS, levels);
        double law = 1 / (levels * 2 * Math.PI * Math.log(2));
        for (Bloom q : new Bloom[]{Bloom.FULL, Bloom.FAST}) {
            int g = FxBloom.glowLevel(h, q);
            FxBloom.Image b = FxBloom.halo(point(w, h, g, cx, cy), w, h, g);
            assertEquals(1, b.sum(), 0.03, q + ": энергия ореола — энергия источника");
            double previous = Double.MAX_VALUE;
            for (int r = 1; r <= 700; r += 1) {
                double axis = (b.at(cx + r, cy) + b.at(cx - r, cy) + b.at(cx, cy + r) + b.at(cx, cy - r)) / 4;
                assertTrue(axis <= previous * 1.0005, q + ": спад без ступеней, r " + r);
                previous = axis;
                int d = (int) Math.round(r / Math.sqrt(2));
                double diagonal = (b.at(cx + d, cy + d) + b.at(cx - d, cy - d) + b.at(cx + d, cy - d) + b.at(cx - d, cy + d)) / 4;
                double rd = d * Math.sqrt(2);
                if (r >= 16) {
                    assertEquals(1, diagonal * rd * rd / (axis * r * r), 0.1, q + ": круглый, r " + r);
                    assertEquals(1, axis * r * r / law, r <= 256 ? 0.25 : 0.35, q + ": 1/r², r " + r);
                } else {
                    assertEquals(1, diagonal / axis, 0.1, q + ": круглый у источника, r " + r);
                }
            }
        }
    }

    /**
     * Вся цепочка до кривой с теми числами, что ставят проходы ({@link FxBloom#gatherUniforms}, {@link FxBloom#outputUniforms}):
     * свет ореола на кадре до кривой — доля {@link FxBloom#SHARE} энергии источника (сбор делит на число уровней, вывод
     * больше не делит: двойная нормировка давала 0,12/7), в 64 пикселях — та же доля закона 1/r²; на экран — кривая шейдера.
     */
    @Test
    void chainGivesTheShareOfTheEnergy() throws IOException {
        int w = 2560, h = 1600, cx = 1280, cy = 800;
        GlslFunction curve = GlslFunction.parse(read("include/bloom.glsl"), "bloomCurve");
        int levels = FxBloom.last(w, h) - FxBloom.first(h) + 1;
        double law = 1 / (levels * 2 * Math.PI * Math.log(2));
        for (Bloom q : new Bloom[]{Bloom.FULL, Bloom.FAST}) {
            int g = FxBloom.glowLevel(h, q);
            // огненный шар радиусом 30 пикселей в белой точке
            double energy = Math.PI * 30 * 30 * FxBloom.WHITE;
            FxBloom.Image e = point(w, h, g, cx, cy);
            for (int i = 0; i < e.v().length; i++) e.v()[i] *= (float) energy;
            FxBloom.Image light = FxBloom.light(FxBloom.halo(e, w, h, g), w, h, g);
            assertEquals(FxBloom.SHARE * energy, light.sum(), 0.03 * FxBloom.SHARE * energy, q + ": свет ореола — доля энергии");
            int r = 64;
            double axis = (light.at(cx + r, cy) + light.at(cx - r, cy) + light.at(cx, cy + r) + light.at(cx, cy - r)) / 4;
            assertEquals(1, axis * r * r / (FxBloom.SHARE * energy * law), 0.25, q + ": в 64 пикселях — доля закона");
            double shown = Math.pow(curve.call(Map.of("x", axis)), 1 / 2.2);
            assertEquals(Math.pow(FxBloom.curve(axis), 1 / 2.2), shown, 1e-9, q + ": на экран — кривая шейдера");
            assertTrue(shown > 0.05 && shown < 0.9, q + ": ореол в 64 пикселях от шара в 30 пикселей виден и не белый: " + shown);
        }
        String out = read("core/fx_bloom.fsh").replaceAll("\\s+", " ");
        assertTrue(out.contains("vec3 light = texture(Sampler0, uv).rgb * BloomStep.z * bloomThrough(covered);"), "вывод: доля — одним числом прохода");
        assertTrue(out.contains("pow(vec3(bloomCurve(light.r), bloomCurve(light.g), bloomCurve(light.b)), vec3(1.0 / 2.2))"), "вывод: кривая и экран");
    }

    /**
     * Дым перед огнём гасит и его ореол. Шар радиусом 120 пикселей кадра, перед правой его частью и дальше вправо — полоса
     * дыма с непрозрачностью 0,8; проход свечения смешивает их «поверх» (как {@code ONE, ONE_MINUS_SRC_ALPHA}), что
     * закрывает ореол, и сколько его проходит, — функции шейдера ({@code bloomCovers}, {@code bloomThrough}); доля
     * закрытого на кадр — линейной выборкой уровня прохода, как в {@code fx_bloom.fsh}. Ореол поверх дыма не светлее, чем
     * пропускание дыма × ореол того же шара без дыма; вокруг открытой части шара ореол остаётся (не меньше половины); на
     * самом шаре и на дыме перед ним ореола нет. С гашением над полосой выходит 0,09–0,14 ореола без дыма, без него (одна
     * доля светящегося, как прежде) — около 0,5 при пропускании 0,2.
     */
    @Test
    void smokeInFrontAbsorbsTheHalo() throws IOException {
        String glsl = read("include/bloom.glsl");
        GlslFunction covers = GlslFunction.parse(glsl, "bloomCovers"), through = GlslFunction.parse(glsl, "bloomThrough");
        int w = 2560, h = 1600, cx = 1280, cy = 800, r = 120, band0 = cx + 40, band1 = cx + 200;
        double smoke = 0.8, fire = FxBloom.WHITE;
        for (Bloom q : new Bloom[]{Bloom.FULL, Bloom.FAST}) {
            int g = FxBloom.glowLevel(h, q), k = 1 << g, gw = FxBloom.size(w, g), gh = FxBloom.size(h, g);
            FxBloom.Image[] emission = {FxBloom.Image.of(gw, gh), FxBloom.Image.of(gw, gh)};
            FxBloom.Image[] covered = {FxBloom.Image.of(gw, gh), FxBloom.Image.of(gw, gh)};
            for (int j = 0; j < gh; j++) {
                for (int i = 0; i < gw; i++) {
                    double x = (i + 0.5) * k, y = (j + 0.5) * k;
                    boolean ball = Math.hypot(x - cx, y - cy) <= r, band = x >= band0 && x < band1;
                    for (int s = 0; s < 2; s++) {
                        double e = 0, c = 0;
                        if (ball) {
                            e = fire;
                            c = covers.call(Map.of("alpha", 1.0, "light", 1.0, "emits", 1.0));
                        }
                        if (s == 1 && band) {
                            // дым поверх: излучения нет, альфа — его непрозрачность
                            e *= 1 - smoke;
                            c = covers.call(Map.of("alpha", smoke, "light", 0.0, "emits", 0.0)) + (1 - smoke) * c;
                        }
                        emission[s].v()[j * gw + i] = (float) e;
                        covered[s].v()[j * gw + i] = (float) Math.min(1, c);
                    }
                }
            }
            FxBloom.Image clear = FxBloom.light(FxBloom.halo(emission[0], w, h, g), w, h, g);
            FxBloom.Image smoky = FxBloom.light(FxBloom.halo(emission[1], w, h, g), w, h, g);
            for (int x = cx + 150; x <= cx + 180; x += 6) {
                for (int y : new int[]{cy - 60, cy, cy + 60, cy - 220, cy + 220}) {
                    double open = shown(clear, covered[0], through, k, x, y), behind = shown(smoky, covered[1], through, k, x, y);
                    assertTrue(open > 0, q + ": ореол без дыма есть");
                    assertTrue(behind <= (1 - smoke) * open * 1.001, q + ": ореол поверх дыма не светлее пропускания × ореол: " + behind / open + " в (" + x + ", " + y + ")");
                }
            }
            for (int x = cx - 250; x <= cx - 150; x += 10) {
                double open = shown(clear, covered[0], through, k, x, cy), left = shown(smoky, covered[1], through, k, x, cy);
                assertTrue(left >= 0.5 * open, q + ": ореол у открытой части шара остаётся: " + left / open + " в " + (x - cx));
            }
            assertEquals(0, shown(smoky, covered[1], through, k, cx + 80, cy), 1e-9, q + ": на дыме перед шаром ореола нет");
            assertEquals(0, shown(clear, covered[0], through, k, cx - 60, cy), 1e-9, q + ": на шаре ореола нет");
        }
        String out = read("core/fx_bloom.fsh").replaceAll("\\s+", " "), fsh = read("core/fx.fsh").replaceAll("\\s+", " ");
        assertTrue(out.contains("float covered = texture(Sampler1, at * BloomCover).r;"), "вывод: доля закрытого — выборкой уровня прохода");
        assertTrue(out.contains("* BloomStep.z * bloomThrough(covered);"), "вывод: ореол — долей, не закрытой слоем перед ним");
        assertTrue(fsh.contains("float covers = bloomCovers(fragColor.a, max(fragColor.r, max(fragColor.g, fragColor.b)), emits);"), "проход: закрывает каждый квадрат");
        assertTrue(fsh.contains("fragCover = vec4(covers, 0.0, 0.0, fragColor.a);"), "проход: закрытое — смешиванием «поверх»");
    }

    /** Ореол на кадре в пикселе (x, y): свет до кривой × доля, что проходит ({@code fx_bloom.fsh}). */
    private static double shown(FxBloom.Image light, FxBloom.Image covered, GlslFunction through, int k, int x, int y) {
        double c = covered.sample((x + 0.5) / k, (y + 0.5) / k);
        return light.at(x, y) * through.call(Map.of("covered", c));
    }

    /**
     * Источник, идущий по кадру (сдвиги до 64 пикселей с долями), ореол не дёргает: энергия та же (без мерцания у искры),
     * ореол в 40 и в 300 пикселях от него — тот же в пределах 6 %. Уменьшенные копии 2×2 ({@code glGenerateMipmap}) вместо
     * спуска в 13 выборок тут проваливаются (дальний ореол скачет в разы): они привязывают глубокие уровни к своей сетке.
     */
    @Test
    void movingSourceKeepsItsHalo() {
        int w = 2560, h = 1600;
        for (Bloom q : new Bloom[]{Bloom.FULL, Bloom.FAST}) {
            int g = FxBloom.glowLevel(h, q);
            FxBloom.Image ref = FxBloom.halo(point(w, h, g, 1000, 700), w, h, g);
            double near = ref.at(1040, 700), far = ref.at(1300, 700);
            for (double dx = 0.5; dx < 64; dx += 3.7) {
                double x = 1000 + dx, y = 700 + 0.37 * dx;
                FxBloom.Image b = FxBloom.halo(point(w, h, g, x, y), w, h, g);
                assertEquals(ref.sum(), b.sum(), 0.002 * ref.sum(), q + ": энергия, сдвиг " + dx);
                assertEquals(near, b.at((int) Math.round(x + 40), (int) Math.round(y)), 0.06 * near, q + ": ореол в 40 пикселях, сдвиг " + dx);
                assertEquals(far, b.at((int) Math.round(x + 300), (int) Math.round(y)), 0.06 * far, q + ": ореол в 300 пикселях, сдвиг " + dx);
            }
        }
    }

    /**
     * Этап растеризации прохода излучения: квадрат светится там, где в него попадает середина пикселя. Квадрат мельче
     * пикселя (искра, дальний факел) то попадает, то нет — его свет в проходе прыгает от нуля до вчетверо больше; пятно
     * {@link FxBloom#spots} (ореол {@code glowAlpha} из {@code fx.fsh}, полуразмер {@link FxBloom#SPOT_HALF} пикселя) при
     * любом сдвиге несёт ту же энергию — {@link FxBloom#SPOT_COVER} площади своего квадрата (в пределах 1 %: σ ореола ≈ 0,9
     * пикселя, и край ореола срезан плавно, но не гауссом).
     */
    @Test
    void spotKeepsItsEnergyWhereverItFalls() throws IOException {
        GlslFunction glow = GlslFunction.parse(read("core/fx.fsh"), "glowAlpha");
        for (double r = 0; r < 1.1; r += 0.01) assertEquals(FxBloom.glowAlpha(r), glow.call(Map.of("r", r)), 1e-9, "glowAlpha в Java — как в шейдере, r " + r);
        double expected = FxBloom.SPOT_COVER * Math.pow(2 * FxBloom.SPOT_HALF, 2);
        double tinyMin = Double.MAX_VALUE, tinyMax = 0;
        for (double ox = 0; ox < 1; ox += 0.0625) {
            for (double oy = 0; oy < 1; oy += 0.0625) {
                double cx = 50 + ox, cy = 30 + oy;
                // пятно: середины пикселей (i + ½) внутри квадрата, значение — ореол по расстоянию в долях полуразмера × 64/63
                double sum = 0, half = FxBloom.SPOT_HALF;
                for (int j = (int) Math.floor(cy - half); j <= cy + half; j++) {
                    for (int i = (int) Math.floor(cx - half); i <= cx + half; i++) {
                        double dx = i + 0.5 - cx, dy = j + 0.5 - cy;
                        if (Math.abs(dx) > half || Math.abs(dy) > half) continue;
                        sum += glow.call(Map.of("r", Math.sqrt(dx * dx + dy * dy) / half * 64 / 63));
                    }
                }
                assertEquals(1, sum / expected, 0.01, "пятно, сдвиг " + ox + ", " + oy);
                // квадрат в полпикселя: те же середины пикселей
                double tiny = 0;
                for (int j = 25; j < 35; j++) {
                    for (int i = 45; i < 55; i++) if (Math.abs(i + 0.5 - cx) <= 0.25 && Math.abs(j + 0.5 - cy) <= 0.25) tiny += 1;
                }
                tinyMin = Math.min(tinyMin, tiny);
                tinyMax = Math.max(tinyMax, tiny);
            }
        }
        assertEquals(0, tinyMin, "квадрат мельче пикселя в проходе пропадает");
        assertEquals(1, tinyMax, "… и появляется: свет прыгает от нуля до полного (а его площадь — четверть пикселя)");
    }

    /**
     * Пятна квадрата ({@link FxBloom#spots}): мелкий — одно пятно в его середине и его телесный угол; длинный тонкий (факел
     * вдали) — пятна по длине не реже полуразмера пятна; широкий в обе стороны — без пятен (рисуется сам).
     */
    @Test
    void spotsCoverSmallAndThinEmitters() {
        double texel = 0.004, d = 100;
        double[] out = new double[3 * FxBloom.SPOTS + 1];
        // квадрат лицом к камере в 0,3 пикселя: угол 0,3·texel → сторона 0,3·texel·d
        float side = (float) (0.3 * texel * d), hs = side / 2;
        float[] tiny = {-hs, -hs, (float) -d, -hs, hs, (float) -d, hs, hs, (float) -d, hs, -hs, (float) -d};
        assertEquals(1, FxBloom.spots(tiny, texel, out));
        assertEquals(0, out[0], 1e-6);
        assertEquals(-d, out[2], 1e-4);
        assertEquals(side * side / (d * d), out[3], 1e-9, "телесный угол квадрата");
        // полоса 40 пикселей в длину, 0,5 в ширину
        float len = (float) (40 * texel * d / 2), wid = (float) (0.25 * texel * d);
        float[] strip = {-len, -wid, (float) -d, -len, wid, (float) -d, len, wid, (float) -d, len, -wid, (float) -d};
        int n = FxBloom.spots(strip, texel, out);
        assertEquals(FxBloom.SPOTS, n, "длинная — пятна по длине, не больше " + FxBloom.SPOTS);
        assertEquals(4 * len * wid / (d * d), out[3 * n], 1e-9);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            lo = Math.min(lo, out[3 * k]);
            hi = Math.max(hi, out[3 * k]);
            assertEquals(0, out[3 * k + 1], wid, "пятна — на оси полосы");
        }
        assertTrue(lo < -len * 0.8 && hi > len * 0.8, "пятна — по всей длине");
        // 6 пикселей в обе стороны — без пятен
        float big = (float) (3 * texel * d);
        float[] wide = {-big, -big, (float) -d, -big, big, (float) -d, big, big, (float) -d, big, -big, (float) -d};
        assertEquals(0, FxBloom.spots(wide, texel, out));
    }

    /** Проходы — те же выборки и веса, что пирамида на процессоре ({@link FxBloom#down}, {@link FxBloom#tent}, {@link FxBloom#bspline}). */
    @Test
    void shadersSampleLikeTheReference() throws IOException {
        String down = read("core/fx_bloom_down.fsh").replaceAll("\\s+", " "), up = read("core/fx_bloom_up.fsh").replaceAll("\\s+", " ");
        assertTrue(down.contains("uv = gl_FragCoord.xy * 2.0 * d"), "вниз: пиксель уровня — точка (2x + 1, 2y + 1) прошлого");
        assertTrue(down.contains("e * 0.125 + corners * 0.03125 + sides * 0.0625 + inner * 0.125"), "вниз: веса");
        for (String tap : new String[]{"-2.0, 2.0", "2.0, 2.0", "-2.0, -2.0", "2.0, -2.0", "0.0, 2.0", "-2.0, 0.0", "2.0, 0.0", "0.0, -2.0", "-1.0, 1.0",
                "1.0, 1.0", "-1.0, -1.0", "1.0, -1.0"}) {
            assertTrue(down.contains("at(uv, d, " + tap + ")"), "вниз: выборка " + tap);
        }
        assertTrue(up.contains("uv = gl_FragCoord.xy * 0.5 * d"), "шатёр: пиксель — точка ((x + ½)/2, (y + ½)/2) мельчайшего");
        assertTrue(up.contains("(corners + 2.0 * sides + 4.0 * at(uv, d, 0.0, 0.0)) / 16.0"), "шатёр: веса");
        String gather = read("core/fx_bloom_gather.fsh").replaceAll("\\s+", " ");
        assertTrue(gather.contains("vec2 w0 = (-f3 + 3.0 * f2 - 3.0 * f + 1.0) / 6.0, w1 = (3.0 * f3 - 6.0 * f2 + 4.0) / 6.0;"), "сбор: веса B-сплайна");
        assertTrue(gather.contains("vec2 w2 = (-3.0 * f3 + 3.0 * f2 + 3.0 * f + 1.0) / 6.0, w3 = f3 / 6.0;"), "сбор: веса B-сплайна");
        assertTrue(gather.contains("vec2 p0 = (i - 0.5 + w1 / g0) / size, p1 = (i + 1.5 + w3 / g1) / size;"), "сбор: две линейные выборки на ось");
        for (int i = 1; i < FxBloom.GATHER; i++) {
            assertTrue(gather.contains("bspline(Sampler" + i + ", at * " + (1.0 / (1 << i)) + ")"), "сбор: уровень " + i + " — точка (x + ½)/2^" + i);
        }
        assertTrue(gather.contains("fragColor = vec4(sum / BloomCount, 1.0);"), "сбор: равные веса уровней");
        double[] wsum = FxBloom.basis(0.37);
        assertEquals(1, wsum[0] + wsum[1] + wsum[2] + wsum[3], 1e-12, "B-сплайн сохраняет энергию");
        String out = read("core/fx_bloom.fsh").replaceAll("\\s+", " ");
        assertTrue(out.contains("vec2 uv = at * 0.5 * BloomStep.xy;"), "на кадр: пиксель — точка ((x + ½)/2, (y + ½)/2) половины кадра");
        assertTrue(out.contains("texture(Sampler0, uv).rgb * BloomStep.z * bloomThrough(covered)"), "доля ореола, вокруг светящегося и за дымом");
    }

    /**
     * Проход свечения — тот же шейдер, что у цвета ({@code fx.fsh}, {@code FxGlowPass}): излучение берёт ту же видимую долю
     * (мягкий край у мира и LOD, у камеры, туман) и пелену перед ним, альфа — альфа цвета (дым перед огнём гасит его
     * излучение и долю светящегося той же долей), а что за миром, отбрасывает по расстоянию до мира: в уменьшенном проходе
     * аппаратной глубины нет. Пятно — только в проходе свечения, светящийся пятнами квадрат — только в слое.
     */
    @Test
    void glowIsCutLikeTheColour() throws IOException {
        String fsh = read("core/fx.fsh").replaceAll("\\s+", " "), vsh = read("core/fx.vsh").replaceAll("\\s+", " ");
        assertTrue(fsh.contains("fragColor = color * seen * ColorModulator;"), "цвет — с видимой долей");
        assertTrue(fsh.contains("fragColor = vec4(glow * seen * ColorModulator.rgb, fragColor.a);"), "излучение — с той же долей и альфой цвета");
        assertTrue(fsh.contains("fragCover = vec4(covers, 0.0, 0.0, fragColor.a);"), "закрытое перед ореолом — с альфой цвета");
        String bloom = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/fx/layer/FxBloom.java"));
        assertTrue(!bloom.contains("enableScissor"), "проход свечения — во весь кадр: в ножницах рамки светящихся дым за ней ореол не гасил (прямоугольный край)");
        assertTrue(fsh.contains("glow *= front;"), "пелена перед огнём гасит и излучение");
        assertTrue(!fsh.contains("color *="), "цвет не гасится мимо видимой доли");
        assertTrue(fsh.contains("if (softness > 0.0) seen *= clamp((scene - viewDistance) / softness, 0.0, 1.0);"), "мягкий край у мира — в видимой доле");
        assertTrue(fsh.contains("if (FxGlowPass.x > 0.5 && scene < viewDistance * 0.999 - 0.05) discard;"), "что за миром, не светится");
        assertTrue(fsh.contains("float scene = pallScene(texture(Sampler1, frag / ScreenSize).r);"), "расстояние до мира — у пикселя кадра, не прохода");
        assertTrue(fsh.contains("glow = color.rgb * emitGain;"), "пятно: цвет — линейный свет");
        assertTrue(vsh.contains("if (Emit > " + (FxBloom.SPOT - 0.5) + ")"), "пятно — с " + FxBloom.SPOT);
        assertTrue(vsh.contains("vertexColor = vec4(Color.rgb * bloomMag(float(UV2.x)), bloomMag(float(UV2.y)));"), "пятно: свет и доля закрытого — в UV2");
        assertTrue(vsh.contains("Normal.y < -0.5 || Emit > " + (FxBloom.SPOT - 0.5) + " ? vec4(1.0)"), "пятно: UV2 — не карта освещения");
        assertTrue(vsh.contains("emitGain = bloomGain(Emit - " + (double) FxBloom.EMITTER + ");"), "пересвет светящегося");
        assertTrue(vsh.contains("if ((Emit > " + (FxBloom.SPOT - 0.5) + " && !glowPass) || (Emit > " + (FxBloom.SKIP - 0.5) + " && Emit < "
                + (FxBloom.SKIP + 0.5) + " && glowPass)) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);"), "пятно — только в проходе, его квадрат — только в слое");
    }

    /** Законы шейдеров ({@code bloom.glsl}) — те же, что в Java: код пересвета, белая точка, кривая ореола. */
    @Test
    void shaderLawsAreJavaLaws() throws IOException {
        String glsl = read("include/bloom.glsl");
        assertEquals(FxBloom.WHITE, constant(glsl, "BLOOM_WHITE"), 1e-5);
        assertEquals(FxBloom.STEPS, constant(glsl, "BLOOM_STEPS"), 1e-9);
        GlslFunction gain = GlslFunction.parse(glsl, "bloomGain"), curve = GlslFunction.parse(glsl, "bloomCurve");
        for (int c = 0; c <= FxBloom.STEPS; c++) assertEquals(FxBloom.gain(c), gain.call(Map.of("code", (double) c)), 1e-5 * FxBloom.gain(c), "код " + c);
        for (double x = 0; x < 20; x += 0.1) assertEquals(FxBloom.curve(x), curve.call(Map.of("x", x)), 1e-12, "x " + x);
        assertEquals(FxBloom.MAG_ZERO, constant(glsl, "BLOOM_MAG_ZERO"), 0);
        assertEquals(FxBloom.MAG_STEP, constant(glsl, "BLOOM_MAG_STEP"), 0);
        assertTrue(FxBloom.EMITTER + FxBloom.STEPS < FxBloom.SPOT && FxBloom.SPOT + FxBloom.STEPS <= 255 && FxBloom.SKIP < FxBloom.EMITTER, "коды не пересекаются");
    }

    /**
     * Белая точка — у той кривой, через которую запечён огонь ({@code tools/bake_fx/pack.py film}): свет, который она
     * показывает белым, — 7,24 белой стены; ярче кадр различий не показывает.
     */
    @Test
    void filmInverseUndoesTheBake() throws IOException {
        String pack = Files.readString(Path.of("../tools/bake_fx/pack.py"));
        assertTrue(pack.contains("x * (" + FxBloom.FA + " * x + " + FxBloom.FB + ") / (x * (" + FxBloom.FC + " * x + " + FxBloom.FD + ") + " + FxBloom.FE + ")"),
                "кривая запекания — те же числа");
        for (double y = 0; y < 1; y += 0.001) assertEquals(y, FxBloom.film(FxBloom.filmInverse(y)), 1e-9, "y " + y);
        assertEquals(7.242, FxBloom.WHITE, 0.001);
        assertEquals(1, FxBloom.film(FxBloom.WHITE), 1e-9);
    }

    /** Код пересвета: 0 — свет как показан, до белой точки — с шагом меньше 1,6 %, ярче — она. */
    @Test
    void gainCodeCoversTheWhitePoint() {
        assertEquals(0, FxBloom.code(0.3));
        assertEquals(0, FxBloom.code(1));
        assertEquals(FxBloom.STEPS, FxBloom.code(FxBloom.WHITE));
        assertEquals(FxBloom.STEPS, FxBloom.code(1e6));
        for (double g = 1; g <= FxBloom.WHITE; g *= 1.013) {
            int c = FxBloom.code(g);
            assertTrue(c >= 0 && c <= FxBloom.STEPS);
            assertEquals(1, FxBloom.gain(c) / g, 0.0081, "пересвет " + g);
        }
    }

    private static double constant(String glsl, String name) {
        Matcher m = Pattern.compile("const float " + name + " = ([\\d.eE+-]+);").matcher(glsl);
        assertTrue(m.find(), name);
        return Double.parseDouble(m.group(1));
    }
}
