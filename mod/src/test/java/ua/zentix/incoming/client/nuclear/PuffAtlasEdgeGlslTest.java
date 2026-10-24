package ua.zentix.incoming.client.nuclear;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.layer.FxAtlas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Выборка кадра запечённого клуба ({@code sixFrames} в {@code fx.fsh}) не берёт соседний кадр листа ни на одной копии:
 * отступ от края — полтекселя самой грубой копии, что читает OpenGL при этом шаге выборки. С отступом в полтекселя листа
 * копии 3–4 (клуб вдали, плоский клуб пыли с ребра) брали край соседнего кадра — тонкие черты поперёк кадра (WV).
 */
class PuffAtlasEdgeGlslTest {
    @Test
    void puffSamplesNeverReachTheNeighbouringFrame() throws IOException {
        try (var in = getClass().getResourceAsStream("/assets/incoming/shaders/core/fx.fsh")) {
            assertNotNull(in);
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
            Matcher levels = Pattern.compile("const float SIX_LEVELS = ([\\d.]+);").matcher(source);
            assertTrue(levels.find(), "нет SIX_LEVELS");
            assertEquals(FxAtlas.MIPS, Double.parseDouble(levels.group(1)), "копий у листа — столько же, сколько знает шейдер");
            GlslFunction inset = GlslFunction.parse(source, "sixInset");
            for (double span = 0.1; span < 256; span *= 1.07) {
                // OpenGL читает копию уровня λ = log2(шаг) и следующую за ней: самая грубая — ⌈λ⌉, не дальше последней
                int level = (int) Math.min(FxAtlas.MIPS, Math.max(0, Math.ceil(Math.log(span) / Math.log(2) - 1e-9)));
                double got = inset.call(Map.of("span", span));
                assertTrue(got >= 0.5 * (1 << level) - 1e-9, "шаг " + span + ": отступ " + got + " меньше полтекселя копии " + level);
                assertTrue(got <= 0.5 * (1 << FxAtlas.MIPS) + 1e-9, "шаг " + span + ": отступ " + got + " шире полтекселя последней копии");
            }
            assertEquals(0.5, inset.call(Map.of("span", 1.0)), 1e-12, "вблизи — полтекселя листа, как прежде");
        }
    }
}
