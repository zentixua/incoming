package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dust pass's atlas has its own sampler unit in the pall composite (6) and in the layer's front reads (10): no pall slot's
 * fire, terrain or water map is read through a unit the atlas is bound to, with any number of detonations in the slots (both
 * slots' terrain maps share one texture, read by slot through {@code pallGroundUv}).
 */
class DustAtlasSamplerTest {
    private static final Pattern SLOT_READ = Pattern.compile("(?:pallField|pallRelief|pallFloor|pallOpen|pallWater|pallSoil|pallGroundAt|flames)\\(([^;]*?)\\)");

    @Test
    void slotMapsNeverReadTheDustAtlasUnit() throws Exception {
        String pass = Files.readString(Path.of("src/main/java/ua/zentix/incoming/client/fx/layer/PallPass.java"));
        String composite = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/core/fx_pall_composite.fsh"));
        String front = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/include/pall_front.glsl"));
        assertEquals(1, count(pass, "DustWallPass.atlas() : table)"), "the composite binds the atlas on its own unit");
        assertTrue(pass.contains("setShaderTexture(6, f.dustWall.active ? DustWallPass.atlas() : table)"), "composite atlas unit");
        assertTrue(pass.contains("setShaderTexture(10, front.dustWall.active ? DustWallPass.atlas() : fallback)"), "front atlas unit");
        assertTrue(pass.contains("setShaderTexture(4, fire1)"), "slot 1's fire map keeps its unit");
        assertTrue(composite.contains("wallLayer(Sampler6,"), "the composite reads the atlas on unit 6");
        assertTrue(front.contains("wallLayer(Sampler10,"), "the front reads the atlas on unit 10");
        assertNoSlotRead(composite, "Sampler6", "fx_pall_composite");
        assertNoSlotRead(front, "Sampler10", "pall_front.glsl");
    }

    private static void assertNoSlotRead(String src, String unit, String file) {
        Matcher m = SLOT_READ.matcher(src);
        int reads = 0;
        while (m.find()) {
            reads++;
            assertFalse(m.group(1).matches(".*\\b" + unit + "\\b.*"), file + ": a slot map read through the atlas unit: " + m.group());
        }
        assertTrue(reads > 0, file + ": no slot reads found");
    }

    private static int count(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) n++;
        return n;
    }
}
