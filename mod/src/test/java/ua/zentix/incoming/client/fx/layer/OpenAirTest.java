package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Окно наружного воздуха у глаза ({@link OpenAir}): по секциям, глаз — далеко от края при любом месте в секции. */
class OpenAirTest {
    /** Размер окна в шейдере ({@code pall.glsl}: {@code PALL_OPEN_SIZE}) — тот же, что у текстуры. */
    @Test
    void shaderKnowsTheWindowSize() throws java.io.IOException {
        try (var in = OpenAirTest.class.getResourceAsStream("/assets/incoming/shaders/include/pall.glsl")) {
            String glsl = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(glsl.contains(String.format(java.util.Locale.ROOT, "const vec3 PALL_OPEN_SIZE = vec3(%d.0, %d.0, %d.0);",
                    OpenAir.SIZE_XZ, OpenAir.SIZE_Y, OpenAir.SIZE_XZ)), "PALL_OPEN_SIZE ≠ OpenAir.SIZE_*");
        }
    }

    @Test
    void eyeStaysFarFromTheWindowEdge() {
        for (int size : new int[]{OpenAir.SIZE_XZ, OpenAir.SIZE_Y}) {
            for (int eye = -100; eye <= 100; eye++) {
                int start = OpenAir.start(eye, size);
                assertEquals(0, Math.floorMod(start, 16), "окно — по секциям");
                assertTrue(eye - start >= size / 2 - 8 && start + size - 1 - eye >= size / 2 - 8,
                        "глаз " + eye + " в окне " + start + "…" + (start + size - 1));
            }
        }
    }
}
