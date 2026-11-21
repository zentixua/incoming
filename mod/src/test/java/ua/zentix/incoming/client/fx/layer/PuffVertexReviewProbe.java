package ua.zentix.incoming.client.fx.layer;

import java.io.IOException;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probe (Codex, round 1): a baked puff's sun share in the {@code Emit} byte never makes it a glow emitter or spot,
 * and the veil and real emitters keep their bytes.
 */
class PuffVertexReviewProbe {
    @Test
    void lightBytesDoNotTurnPuffsIntoGlowSpotsOrLeakIntoTheVeil() throws IOException {
        FxQuadsGlowTest.sheet();
        FxQuads quads = new FxQuads();
        quads.begin(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
        quads.glowTexel(0.002);
        quads.part(FxQuads.Part.CLUBS);
        quads.sixWay(0.5f, 1, 2);
        quads.facePosition(0, 0, -53, 53, 2, 2, FxAtlas.puff6(0, false), 1, 1, 1, 1,
                0, 1, LightTexture.FULL_BRIGHT, 0);
        quads.sixWay(0.5f, 0, 0.01f);
        quads.flat(0, -1, -45, 45, 2, 2, 0.0, FxAtlas.puff6(0, true), 1, 1, 1, 1,
                0, 1, LightTexture.FULL_BRIGHT, 0);
        quads.sixWay(-1);
        assertEquals(2, quads.size());
        assertFalse(quads.glows());
        assertEquals(2, quads.glowStart());
        assertBytes(quads, 0, 0, 200);
        assertBytes(quads, 1, 0, FxBloom.SKIP);

        quads.part(FxQuads.Part.VEIL);
        quads.veil(0.5f);
        disc(quads, 1, 1, 0.75f);
        quads.endVeil();
        assertBytes(quads, 2, 95, 0);
        assertEquals(FxQuads.VEIL, MemoryUtil.memGetByte(quads.address(2) + 33));

        quads.part(FxQuads.Part.FIRE);
        quads.emission(1);
        disc(quads, 50, 0.02f, 0.2f);
        quads.emission(0);
        assertTrue(quads.glows());
        assertEquals(5, quads.size());
        assertEquals(1, quads.glowStart(), "The far puff sets no glow bound; the near puff still covers the halo");
        assertBytes(quads, 3, 25, FxBloom.SKIP);
        assertBytes(quads, 4, 25, FxBloom.SPOT);
    }

    private static void disc(FxQuads quads, float distance, float half, float fog) {
        quads.billboard(0, 0, -distance, distance, half, 0, FxAtlas.disc(), 1, 1, 1, 1, 0, 1, LightTexture.FULL_BRIGHT, fog);
    }

    private static void assertBytes(FxQuads quads, int index, int fog, int emissionOrSun) {
        for (int corner = 0; corner < 4; corner++) {
            long address = quads.address(index) + corner * 36L;
            assertEquals(fog, MemoryUtil.memGetByte(address + 32) & 255);
            assertEquals(emissionOrSun, MemoryUtil.memGetByte(address + 35) & 255);
        }
    }
}
