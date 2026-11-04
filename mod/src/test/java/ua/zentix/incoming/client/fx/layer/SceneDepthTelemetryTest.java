package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneDepthTelemetryTest {
    @Test
    void ordinaryLodDescriptionNeedsNoGraphicsContext() throws ReflectiveOperationException {
        assertFalse(Boolean.getBoolean("incoming.nukeDiag"), "exercise the default timing probe");
        String[] names = {"last", "texture", "width", "height"};
        int[] values = {2, 1, 2, 2};
        Field[] fields = new Field[names.length];
        int[] previous = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            fields[i] = SceneDepth.class.getDeclaredField(names[i]);
            fields[i].setAccessible(true);
            previous[i] = fields[i].getInt(null);
        }
        try {
            // A valid-sized LOD frame used to make the ordinary timing label read its texture back from GL.
            // This JVM has no graphics context: attempting that readback must fail the regression.
            for (int i = 0; i < fields.length; i++) fields[i].setInt(null, values[i]);
            String label = assertDoesNotThrow(SceneDepth::describe);
            assertTrue(label.startsWith("world and LOD ("));
        } finally {
            for (int i = 0; i < fields.length; i++) fields[i].setInt(null, previous[i]);
        }
    }
}
