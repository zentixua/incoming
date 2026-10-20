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
 * Сетка дыма у Java и шейдеров — одна раскладка: размер каскада и их число, вещество (альбедо, асимметрия), воздух
 * ({@code smoke.glsl}, {@code fx_smoke_fill}); место клетки в текстуре — одной формулой у выборки и у заполнения.
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
        // клуб по отрезку: короче — точка, как у Java (SmokeGrid.line)
        assertEquals(SmokeGrid.LINE_POINT, constants(read("core/fx_smoke_fill.fsh")).get("SMOKE_LINE_POINT"), 1e-9);
        assertTrue(read("core/fx_smoke_fill.fsh").contains("1.061405429") && read("core/fx_smoke_fill.fsh").contains("0.3275911"), "erf 7.1.26");
        // строк таблицы клубов у заполнения — столько же, сколько пишет Java
        String fill = read("core/fx_smoke_fill.fsh");
        for (int row = 0; row < SmokeGrid.BLOB_ROWS; row++) assertTrue(fill.contains("ivec2(i, " + row + ")"), "строка " + row);
    }

    /**
     * Место клетки: заполнение пишет слой (каскад·N + высота) с x — страница·N + клетка по x, y — клетка по z; выборка
     * читает (страница·N + x) / 2N, z / N, (каскад·N + y) / (каскады·N) — те же оси.
     */
    @Test
    void fetchReadsWhereFillWrites() {
        String glsl = read("include/smoke.glsl").replaceAll("\\s+", " ");
        assertTrue(glsl.contains("vec3((page * n + l.x) / (2.0 * n), l.z / n, (float(c) * n + l.y) / (float(SMOKE_CASCADES) * n))"), "оси выборки");
        String fill = read("core/fx_smoke_fill.fsh").replaceAll("\\s+", " ");
        assertTrue(fill.contains("vec3(px - page * SMOKE_N, SmokeSlice.y, floor(gl_FragCoord.y))"), "оси заполнения");
    }
}
