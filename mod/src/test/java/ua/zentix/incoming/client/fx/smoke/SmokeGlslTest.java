package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The smoke grid for Java and shaders has one layout: cascade size and number, substance (albedo, asymmetry), air
 * ({@code smoke.glsl}, {@code fx_smoke_fill}); the place of the cell in the texture - using the same formula for the selection and filling.
 */
class SmokeGlslTest {
    private static String read(String path) {
        try (InputStream in = SmokeGlslTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Map<String, Double> constants(String glsl) {
        Map<String, Double> out = new HashMap<>();
        Matcher m = Pattern.compile("const\\s+(?:float|int)\\s+(\\w+)\\s*=\\s*([-0-9.eE]+)\\s*;").matcher(glsl);
        while (m.find()) out.put(m.group(1), Double.parseDouble(m.group(2)));
        return out;
    }

    @Test
    void layoutAndMatterMatchJava() {
        Map<String, Double> c = constants(read("include/smoke.glsl"));
        assertEquals(SmokeGrid.N, c.get("SMOKE_N"), 0);
        assertEquals(SmokeGrid.CASCADES, c.get("SMOKE_CASCADES"), 0);
        assertEquals(SmokeGrid.SOOT_W, c.get("SMOKE_SOOT_W"), 1e-6);
        assertEquals(SmokeGrid.SOOT_G, c.get("SMOKE_SOOT_G"), 1e-6);
        assertEquals(SmokeGrid.DUST_G, c.get("SMOKE_DUST_G"), 1e-6);
        assertEquals(SmokeGrid.DROP_W, c.get("SMOKE_DROP_W"), 1e-6);
        assertEquals(SmokeGrid.DROP_G, c.get("SMOKE_DROP_G"), 1e-6);
        assertEquals(SmokeGrid.AIR_K, c.get("SMOKE_AIR_K"), 1e-3);
        assertEquals(SmokeGrid.N, constants(read("core/fx_smoke_fill.fsh")).get("SMOKE_N"), 0);
        // billows by segment: in short - a point, like Java (SmokeGrid.line)
        assertEquals(SmokeGrid.LINE_POINT, constants(read("core/fx_smoke_fill.fsh")).get("SMOKE_LINE_POINT"), 1e-9);
        assertTrue(read("core/fx_smoke_fill.fsh").contains("1.061405429") && read("core/fx_smoke_fill.fsh").contains("0.3275911"), "erf 7.1.26");
        // rows of the billows table for filling - the same number as Java writes
        String fill = read("core/fx_smoke_fill.fsh");
        for (int row = 0; row < SmokeGrid.BLOB_ROWS; row++) assertTrue(fill.contains("ivec2(i, " + row + ")"), "string " + row);
    }

    /**
     * Cell location: filling writes layer (cascade N + height) with x - page N + cell by x, y - cell by z; sample
     * reads (page N + x) / 2N, z / N, (cascade N + y) / (cascades N) - the same axes.
     */
    @Test
    void fetchReadsWhereFillWrites() {
        String glsl = read("include/smoke.glsl").replaceAll("\\s+", " ");
        assertTrue(glsl.contains("vec3((page * n + l.x) / (2.0 * n), l.z / n, (float(c) * n + l.y) / (float(SMOKE_CASCADES) * n))"), "sampling axes");
        String fill = read("core/fx_smoke_fill.fsh").replaceAll("\\s+", " ");
        assertTrue(fill.contains("vec3(px - page * SMOKE_N, SmokeSlice.y, floor(gl_FragCoord.y))"), "filling axis");
    }
}
