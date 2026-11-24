package ua.zentix.incoming.info;

import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Script-facing values of {@link Automation}: refusal codes and the {@code incoming_alert} payload. */
class AutomationTest {
    @Test
    void reasonCodeIsTheKeyWithoutPrefix() {
        assertEquals("fixed_launcher.busy", Automation.code(Component.translatable("incoming.fixed_launcher.busy")));
        assertEquals("munitions.short", Automation.code(Component.translatable("incoming.munitions.short", "x", 3, 2)));
        assertEquals("failed", Automation.code(Component.literal("something")));
    }

    @Test
    void privateRefusalsReadPrivate() {
        assertEquals("fixed_launcher.busy", Automation.reason(Component.translatable("incoming.fixed_launcher.busy")));
        assertEquals("fixed_launcher.sector", Automation.reason(Component.translatable("incoming.fixed_launcher.sector")));
        // these name the mission's count, the owner's rights or the target's range
        assertEquals(Automation.PRIVATE, Automation.reason(Component.translatable("incoming.munitions.short", "x", 3, 2)));
        assertEquals(Automation.PRIVATE, Automation.reason(Component.translatable("incoming.fixed_launcher.no_rights")));
        assertEquals(Automation.PRIVATE, Automation.reason(Component.translatable("incoming.fixed_launcher.out_of_range", 10000)));
    }

    @Test
    void alertPayloadIsCountAndWholeBlocks() {
        assertArrayEquals(new Object[]{2, 413}, Automation.alert(2, 412.6));
        assertArrayEquals(new Object[]{1, -1}, Automation.alert(1, Double.POSITIVE_INFINITY));
    }
}
