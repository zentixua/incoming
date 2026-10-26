package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fetching a frame of baked billows and a fireball ({@code sixFrames}, {@code fireFrames} in {@code fx.fsh}) does not take the
 *  adjacent sheet frame on any copy: the offset from the edge is half a texel of the coarsest copy, which OpenGL reads at the same time step
 * sampling. With an indent of half a texel of the sheet, copies 3–4 (billows in the distance, flat billows of dust from the edge) took the edge of the adjacent frame—
 * thin features across the frame (WV).
 */
class PuffAtlasEdgeGlslTest {
    @Test
    void puffSamplesNeverReachTheNeighbouringFrame() throws IOException {
        try (var in = getClass().getResourceAsStream("/assets/incoming/shaders/core/fx.fsh")) {
            assertNotNull(in);
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
            Matcher levels = Pattern.compile("const float FRAME_LEVELS = ([\\d.]+);").matcher(source);
            assertTrue(levels.find(), "no FRAME_LEVELS");
            assertEquals(FxAtlas.MIPS, Double.parseDouble(levels.group(1)), "the sheet has as many copies as the shader knows");
            GlslFunction inset = GlslFunction.parse(source, "frameInset");
            for (double span = 0.1; span < 256; span *= 1.07) {
                // OpenGL reads a copy of the level λ = log2(step) and the next one after it: the roughest one is ⌈λ⌉, no further than the last one
                int level = (int) Math.min(FxAtlas.MIPS, Math.max(0, Math.ceil(Math.log(span) / Math.log(2) - 1e-9)));
                double got = inset.call(Map.of("span", span));
                assertTrue(got >= 0.5 * (1 << level) - 1e-9, "step " + span + ": indent " + got + " less than half a texel of copy " + level);
                assertTrue(got <= 0.5 * (1 << FxAtlas.MIPS) + 1e-9, "step " + span + ": indent " + got + " wider than half a texel of the last copy");
            }
            assertEquals(0.5, inset.call(Map.of("span", 1.0)), 1e-12, "close up - half a texel of a sheet, as before");
            // each sample of a sheet frame with an indent from the edge (billows, fire) is a copy, not half a texel of the sheet
            assertFalse(source.contains("0.5 / vec2(textureSize(Sampler0"), "the indentation of half a texel of the sheet remains");
            assertEquals(2, source.split("frameInset\\(sqrt").length - 1, "sixFrames and fireFrames take frameInset");
        }
    }
}
