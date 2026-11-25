package ua.zentix.incoming.api;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.target.Sides;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The side question: the first answer wins, keys are provider-namespaced, and invalidation reaches the cache. */
class SideResolveEventTest {
    @Test
    void firstAnswerWins() {
        SideResolveEvent e = new SideResolveEvent(UUID.randomUUID(), "p");
        assertFalse(e.isAnswered());
        assertTrue(e.answer("minecraft:team/red"));
        assertFalse(e.answer("ftbteams:" + UUID.randomUUID()), "a later answer took");
        assertEquals("minecraft:team/red", e.getSide());
    }

    @Test
    void keysAreNamespaced() {
        SideResolveEvent e = new SideResolveEvent(UUID.randomUUID(), null);
        for (String bad : new String[] {"red", ":red", "red:", "Bad Space:red", ""}) {
            assertThrows(IllegalArgumentException.class, () -> e.answer(bad), bad);
        }
        assertFalse(e.isAnswered(), "a rejected key answered");
        assertTrue(e.answer("kubejs:arena/blue"));
    }

    @Test
    void invalidateBumpsTheCache() {
        long before = Sides.epoch();
        SideResolveEvent.invalidate();
        assertEquals(before + 1, Sides.epoch());
    }
}
