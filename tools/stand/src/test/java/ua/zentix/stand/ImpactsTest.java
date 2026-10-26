package ua.zentix.stand;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hit rule (ServerWatch → Impacts): same-projectile explosion only, either order, no deadline, rejoin. */
class ImpactsTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    private Impacts atC() {
        Impacts i = new Impacts();
        i.target("100", 1000, 0, 160);
        return i;
    }

    @Test
    void leaveThenOwnBlastIsAHitAtTheLeaveMoment() {
        Impacts i = atC();
        assertNull(i.leave(a, 1010, 65, 5, 111));
        assertEquals(1, i.waiting("100"));
        Impacts.Hit h = i.blast(a, true);
        assertNotNull(h);
        assertEquals("100", h.leave().salvo());
        assertEquals(111, h.leave().nanos());
        assertEquals("blast-after", h.how());
        assertEquals(0, i.waiting("100"));
    }

    @Test
    void blastBeforeLeaveCounts() {
        Impacts i = atC();
        assertNull(i.blast(a, true));
        Impacts.Hit h = i.leave(a, 1000, 65, 0, 5);
        assertNotNull(h);
        assertEquals("blast-before", h.how());
    }

    @Test
    void anotherProjectilesBlastNearbyConfirmsNothing() {
        // A leaves for off-world flight near target; B explodes nearby: A is not a hit
        Impacts i = atC();
        assertNull(i.leave(a, 1000, 110, 0, 1));
        assertNull(i.blast(b, true));
        assertEquals(1, i.waiting("100"));
        // B leaves later: its own explosion already occurred
        assertNotNull(i.leave(b, 1001, 65, 0, 2));
    }

    @Test
    void blastWithoutProjectileConfirmsNothing() {
        Impacts i = atC();
        i.leave(a, 1000, 65, 0, 1);
        assertNull(i.blast(null, true));
        assertEquals(1, i.waiting("100"));
    }

    @Test
    void returnToTheWorldCancelsTheWaitingLeaveAndTheRealHitCountsLater() {
        Impacts i = atC();
        assertNull(i.leave(a, 1000, 110, 0, 1));      // Leaves for off-world flight over target
        assertTrue(i.join(a));                        // Rejoins with same UUID
        assertEquals(0, i.waiting("100"));
        assertFalse(i.join(a));
        assertNull(i.leave(a, 1005, 65, 3, 9));       // Real hit
        Impacts.Hit h = i.blast(a, true);
        assertNotNull(h);
        assertEquals(9, h.leave().nanos());
    }

    @Test
    void secondaryBlastsOfTheSameProjectileCountOnce() {
        Impacts i = atC();
        i.leave(a, 1000, 65, 0, 1);
        assertNotNull(i.blast(a, true));
        assertNull(i.blast(a, true));
        assertNull(i.leave(a, 1000, 65, 0, 2));
    }

    @Test
    void theWaitDoesNotExpire() {
        // Detonation under load can be arbitrarily later than removal (Incoming waits up to 6000 ticks for area readiness)
        Impacts i = atC();
        i.leave(a, 1000, 65, 0, 1);
        for (int t = 0; t < 10_000; t++) assertNull(i.blast(UUID.randomUUID(), true));
        assertNotNull(i.blast(a, true));
    }

    @Test
    void aCrashNearTheTargetIsNotAHit() {
        // Pre-arming crash near target: own vanilla explosion without warhead; bench must not measure crashes
        Impacts i = atC();
        assertNull(i.leave(a, 1000, 65, 0, 1));
        assertNull(i.blast(a, false));
        assertEquals(1, i.waiting("100"));
        assertNull(i.blast(b, false));
        assertNull(i.leave(b, 1000, 65, 0, 2));     // Crash before removal also does not mean "explosion occurred"
        assertEquals(2, i.waiting("100"));
    }

    @Test
    void leaveFarFromEveryTargetIsForgotten() {
        Impacts i = atC();
        assertNull(i.leave(a, 1000, 65, 400, 1));     // At launch point
        assertEquals(0, i.waiting("100"));
        assertNull(i.blast(a, true));
    }

    @Test
    void laterTargetAtTheSamePlaceTakesTheLeave() {
        Impacts i = atC();
        i.target("acc", 1000, 0, 120);
        i.leave(a, 1000, 65, 0, 1);
        assertEquals(1, i.waiting("acc"));
        assertEquals(0, i.waiting("100"));
    }
}
