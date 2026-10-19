package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Окно наружного воздуха у глаза ({@link OpenAir}): по секциям, глаз — далеко от края при любом месте в секции. */
class OpenAirTest {
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
