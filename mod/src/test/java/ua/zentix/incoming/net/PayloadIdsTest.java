package ua.zentix.incoming.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Every payload id is used once across both directions: NeoForge refuses a second registration and mod loading fails. */
class PayloadIdsTest {
    @Test
    void everyPayloadIdIsUnique() throws IllegalAccessException {
        Map<String, String> seen = new HashMap<>();
        for (Class<?> holder : new Class<?>[]{S2C.class, C2S.class}) {
            for (Class<?> c : holder.getDeclaredClasses()) {
                if (!CustomPacketPayload.class.isAssignableFrom(c)) continue;
                Field type;
                try {
                    type = c.getDeclaredField("TYPE");
                } catch (NoSuchFieldException e) {
                    continue;
                }
                if (!Modifier.isStatic(type.getModifiers())) continue;
                String id = ((CustomPacketPayload.Type<?>) type.get(null)).id().toString();
                String before = seen.put(id, c.getName());
                if (before != null) fail("payload id " + id + " used by " + before + " and " + c.getName());
            }
        }
        assertTrue(seen.size() > 20, "payloads found: " + seen.size());
    }
}
