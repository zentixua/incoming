package ua.zentix.incoming.client.fx.layer;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.client.nuclear.GlslFunction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Свечение мелкого светящегося через настоящий {@link FxQuads}: квадрат пишется, как его пишет слой; вершины кадра
 * читаются, как их читает {@code fx.vsh} (байт {@code Emit}, цвет, {@code UV2} пятна), и рисуются моделью прохода
 * свечения — выборкой в серединах его пикселей (ореол пятна — {@code glowAlpha} из {@code fx.fsh}). Энергия в проходе —
 * та же, что у источника, при любом его размере и сдвиге; без пятен ({@code FxQuads.spots}) тест падает: точечная выборка
 * то теряет источник, то даёт ему целый пиксель.
 */
class FxQuadsGlowTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/incoming/shaders");
    private static GlslFunction glow, mag;

    @BeforeAll
    static void sheet() throws IOException {
        // лист: место far/disc — белое непрозрачное (средние для свечения — по нему), остальное пусто
        int[] base = new int[FxAtlas.WIDTH * FxAtlas.HEIGHT];
        FxAtlas.Sprite disc = FxAtlas.disc();
        for (int y = Math.round(disc.v0() * FxAtlas.HEIGHT); y < Math.round(disc.v1() * FxAtlas.HEIGHT); y++) {
            Arrays.fill(base, y * FxAtlas.WIDTH + Math.round(disc.u0() * FxAtlas.WIDTH), y * FxAtlas.WIDTH + Math.round(disc.u1() * FxAtlas.WIDTH), 0xFFFFFFFF);
        }
        FxAtlas.emissionFrom(base);
        glow = GlslFunction.parse(Files.readString(SHADERS.resolve("core/fx.fsh")).replaceAll("//[^\n]*", ""), "glowAlpha");
        mag = GlslFunction.parse(Files.readString(SHADERS.resolve("include/bloom.glsl")).replaceAll("//[^\n]*", ""), "bloomMag");
    }

    /** Код величины пятна — тот же в Java и в шейдере, туда и обратно — с шагом 0,07 %. */
    @Test
    void spotMagnitudeRoundTrips() {
        assertEquals(0, FxBloom.magnitude(0));
        assertEquals(0, mag.call(Map.of("code", 0.0)), 0);
        for (double v = 1e-4; v < 1e4; v *= 1.37) {
            int c = FxBloom.magnitude(v);
            assertEquals(1, FxBloom.magnitudeOf(c) / v, 0.00035, "величина " + v);
            assertEquals(FxBloom.magnitudeOf(c), mag.call(Map.of("code", (double) c)), 1e-9 * FxBloom.magnitudeOf(c), "шейдер, код " + c);
        }
    }

    /**
     * Белый круг-ядро (как {@code FarSprites.core}) от 0,05 до 2,8 пикселя прохода свечения и в разных местах относительно его
     * пикселей: свет в проходе — его телесный угол в пределах 2 % (пятна; без них — от нуля до многих раз).
     */
    @Test
    void smallEmitterKeepsItsEnergyInTheGlowPass() {
        double texel = 0.002, d = 50;
        FxQuads quads = new FxQuads();
        for (double size = 0.05; size < 2.9; size *= 1.21) {
            for (double shift = 0; shift < 1; shift += 0.137) {
                quads.begin(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
                quads.glowTexel(texel);
                quads.emission(1);
                double half = size / 2 * texel * d, cx = (shift + 0.31) * texel * d, cy = (0.7 * shift + 0.12) * texel * d;
                quads.billboard((float) cx, (float) cy, (float) -d, (float) d, (float) half, 0, FxAtlas.disc(), 1, 1, 1, 1, 0, 1,
                        LightTexture.FULL_BRIGHT, 0);
                quads.emission(0);
                double expected = Math.pow(size, 2), got = glowPass(quads, texel);
                assertEquals(1, got / expected, 0.02, "ядро " + size + " пикселя прохода, сдвиг " + shift);
            }
        }
    }

    /**
     * Свет квадратов кадра в проходе свечения (в пикселях прохода²): вершины — как их читает {@code fx.vsh}; квадрат,
     * светящийся пятнами, в проход не идёт; светящийся — светом цвета в серединах пикселей, что в нём; пятно — ореолом.
     */
    private static double glowPass(FxQuads quads, double texel) {
        double sum = 0;
        for (int i = 0; i < quads.size(); i++) {
            long q = quads.address(i);
            int emit = MemoryUtil.memGetByte(q + 35) & 255;
            if (emit < FxBloom.EMITTER) continue;
            // экран: x/−z, y/−z в пикселях прохода
            double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, y0 = Double.MAX_VALUE, y1 = -Double.MAX_VALUE;
            for (int k = 0; k < 4; k++) {
                long p = q + k * 36L;
                double z = -MemoryUtil.memGetFloat(p + 8), x = MemoryUtil.memGetFloat(p) / z / texel, y = MemoryUtil.memGetFloat(p + 4) / z / texel;
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
            double r = (MemoryUtil.memGetByte(q + 20) & 255) / 255.0;
            boolean spot = emit >= FxBloom.SPOT;
            double light, cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, half = (x1 - x0) / 2;
            if (spot) {
                light = r * mag.call(Map.of("code", (double) (MemoryUtil.memGetShort(q + 28) & 0xFFFF)));
            } else {
                light = Math.pow(r, 2.2) * FxBloom.gain(emit - FxBloom.EMITTER);
            }
            for (int j = (int) Math.floor(y0); j <= y1; j++) {
                for (int k = (int) Math.floor(x0); k <= x1; k++) {
                    double px = k + 0.5, py = j + 0.5;
                    if (px < x0 || px > x1 || py < y0 || py > y1) continue;
                    double rr = Math.hypot(px - cx, py - cy) / half * 64 / 63;
                    sum += spot ? light * glow.call(Map.of("r", rr)) : light;
                }
            }
        }
        return sum;
    }

    /** Пятно и его квадрат: квадрат — только в слое, пятно — только в проходе, с оттенком в цвете и светом в {@code UV2}. */
    @Test
    void spotCarriesItsLightInUv2() {
        double texel = 0.002, d = 50;
        FxQuads quads = new FxQuads();
        quads.begin(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
        quads.glowTexel(texel);
        quads.emission(1);
        double half = 0.2 * texel * d;
        quads.billboard(0, 0, (float) -d, (float) d, (float) half, 0, FxAtlas.disc(), 1, 0.5f, 0.25f, 1, 0, 1, LightTexture.FULL_BRIGHT, 0);
        assertEquals(2, quads.size(), "квадрат и его пятно");
        assertEquals(FxBloom.SKIP, MemoryUtil.memGetByte(quads.address(0) + 35) & 255, "квадрат — только в слое");
        long spot = quads.address(1);
        assertEquals(FxBloom.SPOT, MemoryUtil.memGetByte(spot + 35) & 255, "пятно");
        assertEquals(255, MemoryUtil.memGetByte(spot + 20) & 255, "наибольший канал оттенка — целый байт");
        assertEquals(Math.pow(0.5, 2.2), (MemoryUtil.memGetByte(spot + 21) & 255) / 255.0, 0.003, "оттенок — отношение линейного света каналов");
        assertTrue((MemoryUtil.memGetShort(spot + 28) & 0xFFFF) > 0, "свет пятна — в UV2");
    }
}
