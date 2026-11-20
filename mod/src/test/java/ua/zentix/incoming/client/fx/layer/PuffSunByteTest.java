package ua.zentix.incoming.client.fx.layer;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The clouds' own shadow reaches the shader as the baked puff's direct-sun share in its {@code Emit} byte
 * ({@link FxQuads#sixWay(float, float, float)}), and {@code fx.vsh} applies it to the direct term only: in the vertex colour
 * it also took the sky's and the fireballs' light (review #689).
 */
class PuffSunByteTest {
    @Test
    void bakedPuffCarriesItsSunShareAndNoGlow() {
        FxQuads quads = new FxQuads();
        quads.begin(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
        quads.glowTexel(0.002);
        quads.sixWay(0.5f, 1, 0.25f);
        quads.facePosition(0, 0, -50, 50, 5, 5, FxAtlas.white(), 0.1f, 0.1f, 0.1f, 1, 1, 1, LightTexture.FULL_BRIGHT, 0);
        quads.sixWay(-1);
        // an ordinary glowing quad afterwards keeps its glow code
        quads.emission(1);
        quads.billboard(0, 0, -40, 40, 5, 0, FxAtlas.disc(), 1, 1, 1, 1, 0, 1, LightTexture.FULL_BRIGHT, 0);
        quads.emission(0);
        // and an unshadowed puff is 255
        quads.sixWay(0.5f, 1);
        quads.facePosition(0, 0, -30, 30, 5, 5, FxAtlas.white(), 0.1f, 0.1f, 0.1f, 1, 1, 1, LightTexture.FULL_BRIGHT, 0);
        quads.sixWay(-1);
        assertEquals(3, quads.size());
        int shaded = -1, glow = -1, open = -1;
        for (int i = 0; i < quads.size(); i++) {
            long q = quads.address(i);
            float z = MemoryUtil.memGetFloat(q + 8);
            int emit = MemoryUtil.memGetByte(q + 35) & 255;
            if (z < -45) shaded = emit;
            else if (z < -35) glow = emit;
            else open = emit;
        }
        assertEquals(Math.round(0.25f * 255), shaded);
        assertEquals(255, open);
        assertTrue(glow >= FxBloom.EMITTER && glow < FxBloom.SPOT, "glow code " + glow);
    }

    @Test
    void theVertexShaderReadsItForPuffsOnly() throws IOException {
        String vsh = Files.readString(Path.of("src/main/resources/assets/incoming/shaders/core/fx.vsh"));
        assertTrue(vsh.contains("float emit = six ? 0.0 : Emit;"));
        assertTrue(vsh.contains("sixKey = FxSun.w * Emit / 255.0;"));
        // every glow decision reads the gated value
        assertFalse(vsh.replace("float emit = six ? 0.0 : Emit;", "").replace("sixKey = FxSun.w * Emit / 255.0;", "")
                .matches("(?s).*[^a-zA-Z_]Emit\\s*[<>-].*"), "glow logic reads the raw Emit byte");
    }
}
