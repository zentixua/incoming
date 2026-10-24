package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;
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
 * ({@link #shadersSampleLikeTheReference}), законы шейдеров — те же числа, что в Java.
 */
class FxBloomTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/incoming/shaders");

    private static String read(String path) throws IOException {
        return Files.readString(SHADERS.resolve(path)).replaceAll("//[^\n]*", "");
    }

    /** Точка энергии 1 в (x, y) пикселей кадра (между середин — долями соседей, как сдвиг на долю пикселя). */
    private static FxBloom.Image point(int w, int h, double x, double y) {
        FxBloom.Image e = FxBloom.Image.of(w, h);
        int i = (int) Math.floor(x - 0.5), j = (int) Math.floor(y - 0.5);
        double fx = x - 0.5 - i, fy = y - 0.5 - j;
        e.v()[j * w + i] += (float) ((1 - fx) * (1 - fy));
        e.v()[j * w + i + 1] += (float) (fx * (1 - fy));
        e.v()[(j + 1) * w + i] += (float) ((1 - fx) * fy);
        e.v()[(j + 1) * w + i + 1] += (float) (fx * fy);
        return e;
    }

    /**
     * Ореол точки в кадре 2560×1600 (у хоста) — закон рассеяния в глазу CIE: равная энергия на октаву угла — яркость
     * ∝ 1/r² (r²·B = 1/(уровней·2π·ln 2)) от 16 пикселей до краёв пирамиды; круглый (по диагонали — то же, что по осям:
     * без квадратов уровней), спадает без ступеней и полос, энергия — та же, что у источника (без доли ореола).
     */
    @Test
    void haloFollowsTheEyeScatterLaw() {
        int w = 2560, h = 1600, cx = 1280, cy = 800;
        assertEquals(3, FxBloom.first(h), "уровни как у пака: на октаву глубже 1080");
        int levels = FxBloom.last(w, h) - FxBloom.first(h) + 1;
        assertEquals(FxBloom.LEVELS, levels);
        FxBloom.Image b = FxBloom.halo(point(w, h, cx, cy));
        assertEquals(1, b.sum(), 0.03, "энергия ореола — энергия источника");
        double law = 1 / (levels * 2 * Math.PI * Math.log(2)), previous = Double.MAX_VALUE;
        for (int r = 1; r <= 700; r += 1) {
            double axis = (b.at(cx + r, cy) + b.at(cx - r, cy) + b.at(cx, cy + r) + b.at(cx, cy - r)) / 4;
            assertTrue(axis <= previous * 1.0005, "спад без ступеней, r " + r);
            previous = axis;
            int d = (int) Math.round(r / Math.sqrt(2));
            double diagonal = (b.at(cx + d, cy + d) + b.at(cx - d, cy - d) + b.at(cx + d, cy - d) + b.at(cx - d, cy + d)) / 4;
            double rd = d * Math.sqrt(2);
            // та же яркость на том же расстоянии (диагональ — на своём rd; там, где спад ∝ 1/r², пересчёт по нему)
            if (r >= 16) {
                assertEquals(1, diagonal * rd * rd / (axis * r * r), 0.1, "круглый, r " + r);
                assertEquals(1, axis * r * r / law, r <= 256 ? 0.1 : 0.2, "1/r², r " + r);
            } else {
                assertEquals(1, diagonal / axis, 0.1, "круглый у источника, r " + r);
            }
        }
    }

    /**
     * Источник, сдвинутый на долю пикселя, ореол не дёргает: энергия та же (без мерцания у искры, идущей по кадру), пятно
     * у самого источника — то же.
     */
    @Test
    void movingSourceKeepsItsHalo() {
        int w = 2560, h = 1600;
        FxBloom.Image ref = FxBloom.halo(point(w, h, 1000, 700));
        double near = ref.at(1000 + 40, 700);
        for (double dx = 0.125; dx < 1; dx += 0.125) {
            FxBloom.Image b = FxBloom.halo(point(w, h, 1000 + dx, 700 + 0.5 * dx));
            assertEquals(ref.sum(), b.sum(), 0.002 * ref.sum(), "энергия, сдвиг " + dx);
            assertEquals(near, b.at(1000 + 40, 700), 0.05 * near, "ореол в 40 пикселях, сдвиг " + dx);
        }
    }

    /** Проходы — те же выборки и веса, что пирамида на процессоре ({@link FxBloom#down}, {@link FxBloom#tent}). */
    @Test
    void shadersSampleLikeTheReference() throws IOException {
        String down = read("core/fx_bloom_down.fsh").replaceAll("\\s+", " "), up = read("core/fx_bloom_up.fsh").replaceAll("\\s+", " ");
        assertTrue(down.contains("uv = gl_FragCoord.xy * 2.0 * d"), "вниз: пиксель уровня — точка (2x + 1, 2y + 1) прошлого");
        assertTrue(down.contains("e * 0.125 + corners * 0.03125 + sides * 0.0625 + inner * 0.125"), "вниз: веса");
        for (String tap : new String[]{"-2.0, 2.0", "2.0, 2.0", "-2.0, -2.0", "2.0, -2.0", "0.0, 2.0", "-2.0, 0.0", "2.0, 0.0", "0.0, -2.0", "-1.0, 1.0",
                "1.0, 1.0", "-1.0, -1.0", "1.0, -1.0"}) {
            assertTrue(down.contains("at(uv, d, " + tap + ")"), "вниз: выборка " + tap);
        }
        assertTrue(up.contains("uv = gl_FragCoord.xy * 0.5 * d"), "вверх: пиксель — точка ((x + ½)/2, (y + ½)/2) мельчайшего");
        assertTrue(up.contains("(corners + 2.0 * sides + 4.0 * at(uv, d, 0.0, 0.0)) / 16.0"), "вверх: шатёр");
        String out = read("core/fx_bloom.fsh").replaceAll("\\s+", " ");
        assertTrue(out.contains("texture(Sampler0, at * 0.5 * BloomStep.xy).rgb * BloomStep.z"), "на кадр: первый уровень, доля на число уровней");
    }

    /**
     * Излучение пишет тот же фрагмент, что и цвет ({@code fx.fsh}), — значит, его режет та же глубина (стены, рельеф): что
     * за ними, не светится и не даёт ореола; и та же видимая доля (мягкий край у мира и LOD, у камеры, туман) и пелена
     * перед ним. Альфа излучения — альфа цвета: дым перед огнём гасит его излучение той же долей.
     */
    @Test
    void glowIsCutLikeTheColour() throws IOException {
        String fsh = read("core/fx.fsh").replaceAll("\\s+", " ");
        assertTrue(fsh.contains("fragColor = color * seen * ColorModulator;"), "цвет — с видимой долей");
        assertTrue(fsh.contains("fragGlow = vec4(glow * seen * ColorModulator.rgb, fragColor.a);"), "излучение — с той же долей и альфой цвета");
        assertTrue(fsh.contains("glow *= front;"), "пелена перед огнём гасит и излучение");
        assertTrue(!fsh.contains("color *="), "цвет не гасится мимо видимой доли");
        assertTrue(fsh.contains("seen *= clamp((scene - viewDistance) / softness, 0.0, 1.0);"), "мягкий край у мира — в видимой доле");
    }

    /** Законы шейдеров ({@code bloom.glsl}) — те же, что в Java: код пересвета, белая точка, кривая ореола. */
    @Test
    void shaderLawsAreJavaLaws() throws IOException {
        String glsl = read("include/bloom.glsl");
        assertEquals(FxBloom.WHITE, constant(glsl, "BLOOM_WHITE"), 1e-5);
        assertEquals(FxBloom.STEPS, constant(glsl, "BLOOM_STEPS"), 1e-9);
        GlslFunction gain = GlslFunction.parse(glsl, "bloomGain"), curve = GlslFunction.parse(glsl, "bloomCurve");
        for (int c = 1; c <= 255; c++) assertEquals(FxBloom.gain(c), gain.call(Map.of("code", (double) c)), 1e-5 * FxBloom.gain(c), "код " + c);
        for (double x = 0; x < 20; x += 0.1) assertEquals(FxBloom.curve(x), curve.call(Map.of("x", x)), 1e-12, "x " + x);
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

    /** Код пересвета: 1 — свет как показан, до белой точки — с шагом меньше процента, ярче — она. */
    @Test
    void gainCodeCoversTheWhitePoint() {
        assertEquals(1, FxBloom.code(0.3));
        assertEquals(1, FxBloom.code(1));
        assertEquals(255, FxBloom.code(FxBloom.WHITE));
        assertEquals(255, FxBloom.code(1e6));
        for (double g = 1; g <= FxBloom.WHITE; g *= 1.013) {
            int c = FxBloom.code(g);
            assertTrue(c >= 1 && c <= 255);
            assertEquals(1, FxBloom.gain(c) / g, 0.005, "пересвет " + g);
        }
    }

    private static double constant(String glsl, String name) {
        Matcher m = Pattern.compile("const float " + name + " = ([\\d.eE+-]+);").matcher(glsl);
        assertTrue(m.find(), name);
        return Double.parseDouble(m.group(1));
    }
}
