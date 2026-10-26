package ua.zentix.incoming.client.fx.layer;

import net.minecraft.client.renderer.LightTexture;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Проверяет настоящие вершины буфера GPU: поворот взгляда не поворачивает клубы вокруг неподвижного глаза. */
class PuffGeometryTest {
    @Test
    void worldVerticesIgnoreCameraYawPitchAndRoll() {
        FxQuads out = new FxQuads();
        FxAtlas.Sprite sprite = FxAtlas.puff6(7, true);
        for (float[] center : new float[][]{{13, -7, 42}, {-61, 28, -83}, {0, 30, 0}, {0, -30, 0}}) {
            double[] expectedFace = null, expectedFlat = null;
            for (int k = 0; k < 12; k++) {
                Quaternionf turn = new Quaternionf().rotationYXZ(k * 0.43f, k * -0.17f, k * 0.29f);
                out.begin(new Vector3f(1, 0, 0).rotate(turn), new Vector3f(0, 1, 0).rotate(turn));
                out.facePosition(center[0], center[1], center[2], 100, 8, 13, sprite, 1, 1, 1, 0.8f, 4, 1, LightTexture.FULL_BRIGHT, 0);
                out.flat(center[0], center[1], center[2], 100, 8, 13, 0.73, sprite, 1, 1, 1, 0.8f, 4, 1, LightTexture.FULL_BRIGHT, 0);
                double[] face = vertices(out, 0), flat = vertices(out, 1);
                if (expectedFace == null) {
                    expectedFace = face;
                    expectedFlat = flat;
                } else {
                    assertArrayEquals(expectedFace, face, 0, "стоячий клуб, поворот " + k);
                    assertArrayEquals(expectedFlat, flat, 0, "лежащий клуб, поворот " + k);
                }
                // Плоскость стоячего клуба перпендикулярна лучу к его середине, верх — от вертикали мира.
                for (int i = 0; i < 4; i++) {
                    double dot = 0;
                    for (int j = 0; j < 3; j++) dot += (face[3 * i + j] - center[j]) * center[j];
                    assertEquals(0, dot, 1e-3);
                    assertEquals(center[1], flat[3 * i + 1], 0);
                }
                assertTrue(face[4] >= face[1], "верх клуба — вверх в мире");
            }
        }
    }

    private static double[] vertices(FxQuads quads, int at) {
        double[] out = new double[12];
        for (int i = 0; i < 4; i++) {
            long address = quads.address(at) + 36L * i;
            for (int j = 0; j < 3; j++) out[3 * i + j] = MemoryUtil.memGetFloat(address + 4L * j);
        }
        return out;
    }
}
