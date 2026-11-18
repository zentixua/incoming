package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ViewRaysTest {
    @Test
    void hundredSourcesCannotExceedTheAllowanceOrReplenishItWithinOneTick() {
        ViewRays budget = new ViewRays(16, 8);
        assertTrue(budget.begin(100));
        int rays = 0, clears = 0;
        for (int i = 0; i < 100; i++) {
            if (budget.trace(100, 0, 20)) rays++;
            if (budget.clear()) clears++;
        }
        assertEquals(16, rays);
        assertEquals(8, clears);
        assertFalse(budget.begin(100));
        assertFalse(budget.trace(100, 0, 20));
        assertFalse(budget.clear());
        assertTrue(budget.begin(1000));
        for (int i = 0; i < 16; i++) assertTrue(budget.trace(1000, 0, 20));
        assertFalse(budget.trace(1000, 0, 20), "a slow feed must not spend accumulated ticks in one frame");
    }

    @Test
    void elapsedTicksWorkAtTenHertzWithoutStarvingOddOwnerPhases() {
        ViewRays budget = new ViewRays(16, 0);
        long previous = -4;
        int samples = 0;
        for (int now = 0; now < 20; now += 2) {
            assertTrue(budget.begin(now));
            if (budget.trace(now, previous, 4)) {
                samples++;
                previous = now;
            }
        }
        assertEquals(5, samples);
    }
}
