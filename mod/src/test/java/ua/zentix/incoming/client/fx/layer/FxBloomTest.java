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
 * Glow of the layer ({@link FxBloom}): the pyramid on the CPU — the same samples and weights as the shaders ({@link
 * #shadersSampleLikeTheReference}), shader laws — the same numbers as in Java, spots of small glowing emitters — on a
 * rasterization model of the emission pass (samples at pixel centres, halo — by the function from {@code fx.fsh}
 * itself).
 */
class FxBloomTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/incoming/shaders");

    private static String read(String path) throws IOException {
        return Files.readString(SHADERS.resolve(path)).replaceAll("//[^\n]*", "");
    }

    /**
     * Emission of the glow pass (level g of a w×h frame) with an energy point of 1 in frame pixels (a pixel of level g
     * — 4^g frame pixels) at (x, y) frame pixels; between centres — by shares of the neighbours, as a shift by a
     * fraction of a pixel.
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
     * Halo of a point in a 2560×1600 frame (the host's), both quality settings — the CIE eye scatter law: equal energy
     * per octave of angle — brightness ∝ 1/r² (r²·B = 1/(levels·2π·ln 2)) from 16 to 256 pixels within 25 % (the
     * B-spline gather puts the near wing of a level slightly narrower than the tent up-sampling), farther to the
     * pyramid edge — 35 %; round (diagonal the same, as along the axes: without level squares), falls off without steps
     * or bands, energy — the same as the source's.
     */
    @Test
    void haloFollowsTheEyeScatterLaw() {
        int w = 2560, h = 1600, cx = 1280, cy = 800;
        assertEquals(3, FxBloom.first(h), "levels as in the pack: one octave deeper than 1080");
        assertEquals(2, FxBloom.glowLevel(h, Bloom.FULL), "FULL: emission pass one octave finer than the first level");
        assertEquals(3, FxBloom.glowLevel(h, Bloom.FAST), "FAST: emission pass — the first level itself");
        int levels = FxBloom.last(w, h) - FxBloom.first(h) + 1;
        assertEquals(FxBloom.LEVELS, levels);
        double law = 1 / (levels * 2 * Math.PI * Math.log(2));
        for (Bloom q : new Bloom[]{Bloom.FULL, Bloom.FAST}) {
            int g = FxBloom.glowLevel(h, q);
            FxBloom.Image b = FxBloom.halo(point(w, h, g, cx, cy), w, h, g);
            assertEquals(1, b.sum(), 0.03, q + ": halo energy — source energy");
            double previous = Double.MAX_VALUE;
            for (int r = 1; r <= 700; r += 1) {
                double axis = (b.at(cx + r, cy) + b.at(cx - r, cy) + b.at(cx, cy + r) + b.at(cx, cy - r)) / 4;
                assertTrue(axis <= previous * 1.0005, q + ": falloff without steps, r " + r);
                previous = axis;
                int d = (int) Math.round(r / Math.sqrt(2));
                double diagonal = (b.at(cx + d, cy + d) + b.at(cx - d, cy - d) + b.at(cx + d, cy - d) + b.at(cx - d, cy + d)) / 4;
                double rd = d * Math.sqrt(2);
                if (r >= 16) {
                    assertEquals(1, diagonal * rd * rd / (axis * r * r), 0.1, q + ": round, r " + r);
                    assertEquals(1, axis * r * r / law, r <= 256 ? 0.25 : 0.35, q + ": 1/r², r " + r);
                } else {
                    assertEquals(1, diagonal / axis, 0.1, q + ": round at the source, r " + r);
                }
            }
        }
    }

    /**
     * The whole chain up to the curve with the numbers the passes set ({@link FxBloom#gatherUniforms}, {@link
     * FxBloom#outputUniforms}): halo light on the frame before the curve — a share {@link FxBloom#SHARE} of the source
     * energy (the gather divides by the number of levels, the output no longer divides: double normalisation gave
     * 0.12/7), at 64 pixels — the same share of the 1/r² law; on screen — the shader curve.
     */
    @Test
    void chainGivesTheShareOfTheEnergy() throws IOException {
        int w = 2560, h = 1600, cx = 1280, cy = 800;
        GlslFunction curve = GlslFunction.parse(read("include/bloom.glsl"), "bloomCurve");
        int levels = FxBloom.last(w, h) - FxBloom.first(h) + 1;
        double law = 1 / (levels * 2 * Math.PI * Math.log(2));
        for (Bloom q : new Bloom[]{Bloom.FULL, Bloom.FAST}) {
            int g = FxBloom.glowLevel(h, q);
            // fireball with a radius of 30 pixels at the white point
            double energy = Math.PI * 30 * 30 * FxBloom.WHITE;
            FxBloom.Image e = point(w, h, g, cx, cy);
            for (int i = 0; i < e.v().length; i++) e.v()[i] *= (float) energy;
            FxBloom.Image light = FxBloom.light(FxBloom.halo(e, w, h, g), w, h, g);
            assertEquals(FxBloom.SHARE * energy, light.sum(), 0.03 * FxBloom.SHARE * energy, q + ": halo light — share of energy");
            int r = 64;
            double axis = (light.at(cx + r, cy) + light.at(cx - r, cy) + light.at(cx, cy + r) + light.at(cx, cy - r)) / 4;
            assertEquals(1, axis * r * r / (FxBloom.SHARE * energy * law), 0.25, q + ": at 64 pixels — share of the law");
            double shown = Math.pow(curve.call(Map.of("x", axis)), 1 / 2.2);
            assertEquals(Math.pow(FxBloom.curve(axis), 1 / 2.2), shown, 1e-9, q + ": on screen — the shader curve");
            assertTrue(shown > 0.05 && shown < 0.9, q + ": halo at 64 pixels from a 30-pixel ball is visible and not white: " + shown);
        }
        String out = read("core/fx_bloom.fsh").replaceAll("\\s+", " ");
        assertTrue(out.contains("vec3 light = texture(Sampler0, uv).rgb * BloomStep.z * bloomThrough(covered);"), "output: share — one number of the pass");
        assertTrue(out.contains("pow(vec3(bloomCurve(light.r), bloomCurve(light.g), bloomCurve(light.b)), vec3(1.0 / 2.2))"), "output: curve and screen");
    }

    /**
     * Smoke in front of fire also dims its halo. A ball with a radius of 120 frame pixels, in front of its right part
     * and farther right — a band of smoke with opacity 0.8; the glow pass blends them “over” (as {@code ONE,
     * ONE_MINUS_SRC_ALPHA}), which covers the halo, and how much of it passes — shader functions ({@code bloomCovers},
     * {@code bloomThrough}); the share covered per frame — by a linear sample of the pass level, as in {@code
     * fx_bloom.fsh}. Halo over smoke is not brighter than the smoke transmission × the halo of the same ball without
     * smoke; around the open part of the ball the halo stays (at least half); on the ball itself and on the smoke in
     * front of it there is no halo. With dimming over the band, 0.09–0.14 of the halo without smoke comes out; without
     * it (a single share of the glow, as before) — about 0.5 at transmission 0.2.
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
                            // smoke over: no emission, alpha — its opacity
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
                    assertTrue(open > 0, q + ": halo without smoke exists");
                    assertTrue(behind <= (1 - smoke) * open * 1.001, q + ": halo over smoke not brighter than transmission × halo: " + behind / open + " at (" + x + ", " + y + ")");
                }
            }
            for (int x = cx - 250; x <= cx - 150; x += 10) {
                double open = shown(clear, covered[0], through, k, x, cy), left = shown(smoky, covered[1], through, k, x, cy);
                assertTrue(left >= 0.5 * open, q + ": halo at the open part of the ball stays: " + left / open + " at " + (x - cx));
            }
            assertEquals(0, shown(smoky, covered[1], through, k, cx + 80, cy), 1e-9, q + ": no halo on the smoke in front of the ball");
            assertEquals(0, shown(clear, covered[0], through, k, cx - 60, cy), 1e-9, q + ": no halo on the ball");
        }
        String out = read("core/fx_bloom.fsh").replaceAll("\\s+", " "), fsh = read("core/fx.fsh").replaceAll("\\s+", " ");
        assertTrue(out.contains("float covered = texture(Sampler1, at * BloomCover).r;"), "output: share covered — by a sample of the pass level");
        assertTrue(out.contains("* BloomStep.z * bloomThrough(covered);"), "output: halo — by the share not covered by the layer in front of it");
        assertTrue(fsh.contains("float covers = bloomCovers(fragColor.a, max(fragColor.r, max(fragColor.g, fragColor.b)), emits);"), "pass: covers each square");
        assertTrue(fsh.contains("fragCover = vec4(covers, 0.0, 0.0, fragColor.a);"), "pass: covered — by blending “over”");
    }

    /** Halo on the frame at pixel (x, y): light before the curve × the share that passes ({@code fx_bloom.fsh}). */
    private static double shown(FxBloom.Image light, FxBloom.Image covered, GlslFunction through, int k, int x, int y) {
        double c = covered.sample((x + 0.5) / k, (y + 0.5) / k);
        return light.at(x, y) * through.call(Map.of("covered", c));
    }

    /**
     * A source moving across the frame (shifts up to 64 pixels with fractions) does not jerk the halo: energy is the
     * same (no flicker at a spark), the halo at 40 and 300 pixels from it — the same within 6 %. Reduced 2×2 copies
     * ({@code glGenerateMipmap}) instead of a descent of 13 samples fail here (the far halo jumps several-fold): they
     * tie the deep levels to their own grid.
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
                assertEquals(ref.sum(), b.sum(), 0.002 * ref.sum(), q + ": energy, shift " + dx);
                assertEquals(near, b.at((int) Math.round(x + 40), (int) Math.round(y)), 0.06 * near, q + ": halo at 40 pixels, shift " + dx);
                assertEquals(far, b.at((int) Math.round(x + 300), (int) Math.round(y)), 0.06 * far, q + ": halo at 300 pixels, shift " + dx);
            }
        }
    }

    /**
     * Rasterization stage of the emission pass: a square glows where a pixel centre falls into it. A square finer than
     * a pixel (a spark, a distant torch) sometimes hits, sometimes not — its light in the pass jumps from zero to four
     * times more; a spot {@link FxBloom#spots} (halo {@code glowAlpha} from {@code fx.fsh}, half-size {@link
     * FxBloom#SPOT_HALF} pixel) at any shift carries the same energy — {@link FxBloom#SPOT_COVER} of its square's area
     * (within 1 %: halo σ ≈ 0.9 pixel, and the halo's edge is cut smoothly, but not as a Gaussian).
     */
    @Test
    void spotKeepsItsEnergyWhereverItFalls() throws IOException {
        GlslFunction glow = GlslFunction.parse(read("core/fx.fsh"), "glowAlpha");
        for (double r = 0; r < 1.1; r += 0.01) assertEquals(FxBloom.glowAlpha(r), glow.call(Map.of("r", r)), 1e-9, "glowAlpha in Java — as in the shader, r " + r);
        double expected = FxBloom.SPOT_COVER * Math.pow(2 * FxBloom.SPOT_HALF, 2);
        double tinyMin = Double.MAX_VALUE, tinyMax = 0;
        for (double ox = 0; ox < 1; ox += 0.0625) {
            for (double oy = 0; oy < 1; oy += 0.0625) {
                double cx = 50 + ox, cy = 30 + oy;
                // spot: pixel centres (i + ½) inside the square, value — halo by distance in half-size fractions ×
                // 64/63
                double sum = 0, half = FxBloom.SPOT_HALF;
                for (int j = (int) Math.floor(cy - half); j <= cy + half; j++) {
                    for (int i = (int) Math.floor(cx - half); i <= cx + half; i++) {
                        double dx = i + 0.5 - cx, dy = j + 0.5 - cy;
                        if (Math.abs(dx) > half || Math.abs(dy) > half) continue;
                        sum += glow.call(Map.of("r", Math.sqrt(dx * dx + dy * dy) / half * 64 / 63));
                    }
                }
                assertEquals(1, sum / expected, 0.01, "spot, shift " + ox + ", " + oy);
                // square of half a pixel: the same pixel centres
                double tiny = 0;
                for (int j = 25; j < 35; j++) {
                    for (int i = 45; i < 55; i++) if (Math.abs(i + 0.5 - cx) <= 0.25 && Math.abs(j + 0.5 - cy) <= 0.25) tiny += 1;
                }
                tinyMin = Math.min(tinyMin, tiny);
                tinyMax = Math.max(tinyMax, tiny);
            }
        }
        assertEquals(0, tinyMin, "a square finer than a pixel vanishes in the pass");
        assertEquals(1, tinyMax, "… and appears: light jumps from zero to full (its area is a quarter of a pixel)");
    }

    /**
     * Spots of a square ({@link FxBloom#spots}): a small one — one spot at its centre and its solid angle; a long thin
     * one (a torch far away) — spots along its length no further apart than half a spot's size; wide in both directions
     * — no spots (it is drawn itself).
     */
    @Test
    void spotsCoverSmallAndThinEmitters() {
        double texel = 0.004, d = 100;
        double[] out = new double[3 * FxBloom.SPOTS + 1];
        // square facing the camera at 0.3 pixels: angle 0.3·texel → side 0.3·texel·d
        float side = (float) (0.3 * texel * d), hs = side / 2;
        float[] tiny = {-hs, -hs, (float) -d, -hs, hs, (float) -d, hs, hs, (float) -d, hs, -hs, (float) -d};
        assertEquals(1, FxBloom.spots(tiny, texel, out));
        assertEquals(0, out[0], 1e-6);
        assertEquals(-d, out[2], 1e-4);
        assertEquals(side * side / (d * d), out[3], 1e-9, "solid angle of the square");
        // strip 40 pixels long, 0.5 wide
        float len = (float) (40 * texel * d / 2), wid = (float) (0.25 * texel * d);
        float[] strip = {-len, -wid, (float) -d, -len, wid, (float) -d, len, wid, (float) -d, len, -wid, (float) -d};
        int n = FxBloom.spots(strip, texel, out);
        assertEquals(FxBloom.SPOTS, n, "long — spots along its length, no more than " + FxBloom.SPOTS);
        assertEquals(4 * len * wid / (d * d), out[3 * n], 1e-9);
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            lo = Math.min(lo, out[3 * k]);
            hi = Math.max(hi, out[3 * k]);
            assertEquals(0, out[3 * k + 1], wid, "spots — on the strip's axis");
        }
        assertTrue(lo < -len * 0.8 && hi > len * 0.8, "spots — along its whole length");
        // 6 pixels in both directions — no spots
        float big = (float) (3 * texel * d);
        float[] wide = {-big, -big, (float) -d, -big, big, (float) -d, big, big, (float) -d, big, -big, (float) -d};
        assertEquals(0, FxBloom.spots(wide, texel, out));
    }

    /**
     * The passes — the same samples and weights as the CPU pyramid ({@link FxBloom#down}, {@link FxBloom#tent}, {@link
     * FxBloom#bspline}).
     */
    @Test
    void shadersSampleLikeTheReference() throws IOException {
        String down = read("core/fx_bloom_down.fsh").replaceAll("\\s+", " "), up = read("core/fx_bloom_up.fsh").replaceAll("\\s+", " ");
        assertTrue(down.contains("uv = gl_FragCoord.xy * 2.0 * d"), "down: a level's pixel — the point (2x + 1, 2y + 1) of the previous one");
        assertTrue(down.contains("e * 0.125 + corners * 0.03125 + sides * 0.0625 + inner * 0.125"), "down: weights");
        for (String tap : new String[]{"-2.0, 2.0", "2.0, 2.0", "-2.0, -2.0", "2.0, -2.0", "0.0, 2.0", "-2.0, 0.0", "2.0, 0.0", "0.0, -2.0", "-1.0, 1.0",
                "1.0, 1.0", "-1.0, -1.0", "1.0, -1.0"}) {
            assertTrue(down.contains("at(uv, d, " + tap + ")"), "down: sample " + tap);
        }
        assertTrue(up.contains("uv = gl_FragCoord.xy * 0.5 * d"), "tent: pixel — the point ((x + ½)/2, (y + ½)/2) of the finest");
        assertTrue(up.contains("(corners + 2.0 * sides + 4.0 * at(uv, d, 0.0, 0.0)) / 16.0"), "tent: weights");
        String gather = read("core/fx_bloom_gather.fsh").replaceAll("\\s+", " ");
        assertTrue(gather.contains("vec2 w0 = (-f3 + 3.0 * f2 - 3.0 * f + 1.0) / 6.0, w1 = (3.0 * f3 - 6.0 * f2 + 4.0) / 6.0;"), "gather: B-spline weights");
        assertTrue(gather.contains("vec2 w2 = (-3.0 * f3 + 3.0 * f2 + 3.0 * f + 1.0) / 6.0, w3 = f3 / 6.0;"), "gather: B-spline weights");
        assertTrue(gather.contains("vec2 p0 = (i - 0.5 + w1 / g0) / size, p1 = (i + 1.5 + w3 / g1) / size;"), "gather: two linear samples per axis");
        for (int i = 1; i < FxBloom.GATHER; i++) {
            assertTrue(gather.contains("bspline(Sampler" + i + ", at * " + (1.0 / (1 << i)) + ")"), "gather: level " + i + " — point (x + ½)/2^" + i);
        }
        assertTrue(gather.contains("fragColor = vec4(sum / BloomCount, 1.0);"), "gather: equal weights of levels");
        double[] wsum = FxBloom.basis(0.37);
        assertEquals(1, wsum[0] + wsum[1] + wsum[2] + wsum[3], 1e-12, "B-spline conserves energy");
        String out = read("core/fx_bloom.fsh").replaceAll("\\s+", " ");
        assertTrue(out.contains("vec2 uv = at * 0.5 * BloomStep.xy;"), "per frame: pixel — the point ((x + ½)/2, (y + ½)/2) of the half frame");
        assertTrue(out.contains("texture(Sampler0, uv).rgb * BloomStep.z * bloomThrough(covered)"), "share of the halo, around the glowing and behind smoke");
    }

    /**
     * The glow pass — the same shader as the colour ({@code fx.fsh}, {@code FxGlowPass}): emission takes the same
     * visible share (a soft edge at the world and LOD, at the camera, fog) and the pall in front of it; alpha — the
     * colour's alpha (smoke in front of fire dims its emission and the glowing share by the same share), and what is
     * behind the world is cut by distance to the world: the reduced pass has no hardware depth. A spot — only in the
     * glow pass; a square glowing as spots — only in the layer.
     */
    @Test
    void glowIsCutLikeTheColour() throws IOException {
        String fsh = read("core/fx.fsh").replaceAll("\\s+", " "), vsh = read("core/fx.vsh").replaceAll("\\s+", " ");
        assertTrue(fsh.contains("fragColor = color * seen * ColorModulator;"), "colour — with the visible share");
        assertTrue(fsh.contains("fragColor = vec4(glow * seen * ColorModulator.rgb, fragColor.a);"), "emission — with the same share and the colour's alpha");
        assertTrue(fsh.contains("fragCover = vec4(covers, 0.0, 0.0, fragColor.a);"), "covered in front of the halo — with the colour's alpha");
        String bloom = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/fx/layer/FxBloom.java"));
        assertTrue(!bloom.contains("enableScissor"), "glow pass — full frame: inside a scissor rectangle, smoke behind it did not dim the halo (a rectangular edge)");
        assertTrue(fsh.contains("glow *= front;"), "pall in front of fire dims the emission too");
        assertTrue(!fsh.contains("color *="), "colour is not dimmed beyond the visible share");
        assertTrue(fsh.contains("if (softness > 0.0) seen *= clamp((scene - viewDistance) / softness, 0.0, 1.0);"), "soft edge at the world — in the visible share");
        assertTrue(fsh.contains("if (FxGlowPass.x > 0.5 && scene < viewDistance * 0.999 - 0.05) discard;"), "what is behind the world does not glow");
        assertTrue(fsh.contains("float scene = pallScene(texture(Sampler1, frag / ScreenSize).r);"), "distance to the world — at the frame pixel, not the pass");
        assertTrue(fsh.contains("glow = color.rgb * emitGain;"), "spot: colour — linear light");
        assertTrue(vsh.contains("if (Emit > " + (FxBloom.SPOT - 0.5) + ")"), "spot — from " + FxBloom.SPOT);
        assertTrue(vsh.contains("vertexColor = vec4(Color.rgb * bloomMag(float(UV2.x)), bloomMag(float(UV2.y)));"), "spot: light and covered share — in UV2");
        assertTrue(vsh.contains("Normal.y < -0.5 || Emit > " + (FxBloom.SPOT - 0.5) + " ? vec4(1.0)"), "spot: UV2 — not a lightmap");
        assertTrue(vsh.contains("emitGain = bloomGain(Emit - " + (double) FxBloom.EMITTER + ");"), "over-exposure of the glowing");
        assertTrue(vsh.contains("if ((Emit > " + (FxBloom.SPOT - 0.5) + " && !glowPass) || (Emit > " + (FxBloom.SKIP - 0.5) + " && Emit < "
                + (FxBloom.SKIP + 0.5) + " && glowPass)) gl_Position = vec4(2.0, 2.0, 2.0, 1.0);"), "spot — only in the pass, its square — only in the layer");
    }

    /**
     * Shader laws ({@code bloom.glsl}) — the same as in Java: the over-exposure code, the white point, the halo curve.
     */
    @Test
    void shaderLawsAreJavaLaws() throws IOException {
        String glsl = read("include/bloom.glsl");
        assertEquals(FxBloom.WHITE, constant(glsl, "BLOOM_WHITE"), 1e-5);
        assertEquals(FxBloom.STEPS, constant(glsl, "BLOOM_STEPS"), 1e-9);
        GlslFunction gain = GlslFunction.parse(glsl, "bloomGain"), curve = GlslFunction.parse(glsl, "bloomCurve");
        for (int c = 0; c <= FxBloom.STEPS; c++) assertEquals(FxBloom.gain(c), gain.call(Map.of("code", (double) c)), 1e-5 * FxBloom.gain(c), "code " + c);
        for (double x = 0; x < 20; x += 0.1) assertEquals(FxBloom.curve(x), curve.call(Map.of("x", x)), 1e-12, "x " + x);
        assertEquals(FxBloom.MAG_ZERO, constant(glsl, "BLOOM_MAG_ZERO"), 0);
        assertEquals(FxBloom.MAG_STEP, constant(glsl, "BLOOM_MAG_STEP"), 0);
        assertTrue(FxBloom.EMITTER + FxBloom.STEPS < FxBloom.SPOT && FxBloom.SPOT + FxBloom.STEPS <= 255 && FxBloom.SKIP < FxBloom.EMITTER, "codes do not overlap");
    }

    /**
     * The white point — of the curve through which the fire is baked ({@code tools/bake_fx/pack.py film}): the light
     * that it shows as white — 7.24 white walls; a brighter frame shows no difference.
     */
    @Test
    void filmInverseUndoesTheBake() throws IOException {
        String pack = Files.readString(Path.of("../tools/bake_fx/pack.py"));
        assertTrue(pack.contains("x * (" + FxBloom.FA + " * x + " + FxBloom.FB + ") / (x * (" + FxBloom.FC + " * x + " + FxBloom.FD + ") + " + FxBloom.FE + ")"),
                "bake curve — the same numbers");
        for (double y = 0; y < 1; y += 0.001) assertEquals(y, FxBloom.film(FxBloom.filmInverse(y)), 1e-9, "y " + y);
        assertEquals(7.242, FxBloom.WHITE, 0.001);
        assertEquals(1, FxBloom.film(FxBloom.WHITE), 1e-9);
    }

    /**
     * Over-exposure code: 0 — light as shown; up to the white point — with a step under 1.6 %; brighter — the white
     * point itself.
     */
    @Test
    void gainCodeCoversTheWhitePoint() {
        assertEquals(0, FxBloom.code(0.3));
        assertEquals(0, FxBloom.code(1));
        assertEquals(FxBloom.STEPS, FxBloom.code(FxBloom.WHITE));
        assertEquals(FxBloom.STEPS, FxBloom.code(1e6));
        for (double g = 1; g <= FxBloom.WHITE; g *= 1.013) {
            int c = FxBloom.code(g);
            assertTrue(c >= 0 && c <= FxBloom.STEPS);
            assertEquals(1, FxBloom.gain(c) / g, 0.0081, "over-exposure " + g);
        }
    }

    private static double constant(String glsl, String name) {
        Matcher m = Pattern.compile("const float " + name + " = ([\\d.eE+-]+);").matcher(glsl);
        assertTrue(m.find(), name);
        return Double.parseDouble(m.group(1));
    }
}
