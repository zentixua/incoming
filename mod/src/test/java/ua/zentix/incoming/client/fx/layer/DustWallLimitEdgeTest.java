package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pall's rays read their world distance from the dust limit's texture at the block pixel clamped into the frame
 * ({@code fx_pall.glsl}: {@code clamp(full, 0, size - 1)}); the limit pass ({@code fx_dustwall_limit.fsh}) writes that same
 * clamped pixel. With an odd width or height the last block's centre lies past the edge: a pass writing only unclamped
 * centres leaves the edge ray a texel nobody wrote (haze, mushroom and firestorm gone in a 1-2 px strip).
 */
class DustWallLimitEdgeTest {
    private static final Path SHADERS = Path.of("src/main/resources/assets/incoming/shaders");

    @Test
    void everyTexelThePallReadsIsWritten() throws IOException {
        String limit = Files.readString(SHADERS.resolve("core/fx_dustwall_limit.fsh"));
        String pall = Files.readString(SHADERS.resolve("include/fx_pall.glsl"));
        assertTrue(limit.contains("min(ivec2((low + 0.5) * PallLimit.x), textureSize(Sampler1, 0) - 1) != p"),
                "the limit pass writes the clamped block pixel (the rule this test evaluates)");
        assertTrue(pall.contains("texelFetch(Sampler0, clamp(full, ivec2(0), size - 1), 0)"), "the pall reads the clamped pixel");
        for (int w : new int[] {854, 855, 1017, 1365, 1366, 1599, 1920}) {
            int factor = 2, low = (w + factor - 1) / factor;
            for (int c = 0; c < low; c++) {
                int read = Math.min((int) ((c + 0.5) * factor), w - 1);
                // the limit pass at pixel p: block floor(p / f), written where its clamped centre is p
                int block = read / factor;
                int written = Math.min((int) ((block + 0.5) * factor), w - 1);
                assertTrue(block == c && written == read, "width " + w + ": the ray of block " + c + " reads pixel " + read + ", never written");
            }
        }
    }
}
