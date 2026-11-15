package ua.zentix.incoming.client.fx.layer;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.nuclear.NukeLight;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/** Actual renderer histories survive primary -> secondary -> nested secondary -> primary. */
class ViewHistoryTest {
    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Test
    void nestedViewsDoNotChangePrimaryDepthAndPallHistory() throws ReflectiveOperationException {
        Field previous = field(PallPass.class, "PREVIOUS"), current = field(SceneDepth.class, "current");
        Field valid = field(SceneDepth.class, "previousValid"), history = field(PallPass.class, "history");
        Matrix4f primary = (Matrix4f) previous.get(null);
        Matrix4f saved = new Matrix4f(primary);
        int originalCurrent = current.getInt(null);
        boolean originalValid = valid.getBoolean(null), originalHistory = history.getBoolean(null);
        var a = new PallPass.ViewState();
        var da = new SceneDepth.ViewState();
        var b = new PallPass.ViewState();
        var db = new SceneDepth.ViewState();
        try {
            primary.rotationY(0.7f).translate(3, 4, 5);
            float[] expected = primary.get(new float[16]);
            current.setInt(null, 1);
            valid.setBoolean(null, true);
            history.setBoolean(null, true);
            a.swap(); da.swap();
            assertFalse(history.getBoolean(null));
            Matrix4f secondary = (Matrix4f) previous.get(null);
            assertNotSame(primary, secondary);
            secondary.rotationX(1.3f);
            current.setInt(null, 7);
            b.swap(); db.swap();
            ((Matrix4f) previous.get(null)).translation(100, 200, 300);
            current.setInt(null, 9);
            b.swap(); db.swap();
            assertSame(secondary, previous.get(null));
            assertEquals(7, current.getInt(null));
            a.swap(); da.swap();
            assertSame(primary, previous.get(null));
            assertArrayEquals(expected, primary.get(new float[16]));
            assertEquals(1, current.getInt(null));
            assertTrue(valid.getBoolean(null));
            assertTrue(history.getBoolean(null));
        } finally {
            previous.set(null, primary);
            primary.set(saved);
            current.setInt(null, originalCurrent);
            valid.setBoolean(null, originalValid);
            history.setBoolean(null, originalHistory);
        }
    }

    @Test
    void secondaryPreparationDoesNotConsumePrimaryExposure() throws ReflectiveOperationException {
        Field prepared = field(NukeLight.class, "prepared"), exposure = field(NukeLight.class, "exposure");
        boolean was = prepared.getBoolean(null);
        double old = exposure.getDouble(null);
        var secondary = new NukeLight.ViewState();
        try {
            prepared.setBoolean(null, true);
            exposure.setDouble(null, 0.123456789);
            secondary.swap();
            NukeLight.newView();
            assertFalse(prepared.getBoolean(null));
            exposure.setDouble(null, 0.9);
            prepared.setBoolean(null, true);
            secondary.swap();
            assertTrue(prepared.getBoolean(null));
            assertEquals(0.123456789, exposure.getDouble(null));
            secondary.swap();
            assertEquals(0.9, exposure.getDouble(null));
            secondary.swap();
        } finally {
            prepared.setBoolean(null, was);
            exposure.setDouble(null, old);
        }
    }
}
