package ua.zentix.incoming.client.fx.layer;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaterDecalFailureTest {
    @Test
    void fatalShaderPackQueryPropagatesWithoutDisablingTheAdapter() throws Exception {
        var checked = WaterDecal.class.getDeclaredField("packChecked");
        var inUse = WaterDecal.class.getDeclaredField("packInUse");
        var query = WaterDecal.class.getDeclaredMethod("shaderPack");
        checked.setAccessible(true);
        inUse.setAccessible(true);
        query.setAccessible(true);
        boolean wasChecked = checked.getBoolean(null);
        Object wasInUse = inUse.get(null);
        try {
            checked.setBoolean(null, true);
            OutOfMemoryError failure = new OutOfMemoryError("injected shader API failure");
            MethodHandle throwing = MethodHandles.throwException(boolean.class, OutOfMemoryError.class).bindTo(failure);
            inUse.set(null, throwing);
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () -> query.invoke(null));
            assertSame(failure, thrown.getCause());
            assertSame(throwing, inUse.get(null), "fatal failures are not an unavailable shader API");
        } finally {
            checked.setBoolean(null, wasChecked);
            inUse.set(null, wasInUse);
        }
    }
}
