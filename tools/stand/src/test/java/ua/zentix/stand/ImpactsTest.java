package ua.zentix.stand;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Правило попадания (ServerWatch → Impacts): только взрыв того же снаряда, любой порядок, без срока, возврат в мир. */
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
        Impacts.Hit h = i.blast(a);
        assertNotNull(h);
        assertEquals("100", h.leave().salvo());
        assertEquals(111, h.leave().nanos());
        assertEquals("blast-after", h.how());
        assertEquals(0, i.waiting("100"));
    }

    @Test
    void blastBeforeLeaveCounts() {
        Impacts i = atC();
        assertNull(i.blast(a));
        Impacts.Hit h = i.leave(a, 1000, 65, 0, 5);
        assertNotNull(h);
        assertEquals("blast-before", h.how());
    }

    @Test
    void anotherProjectilesBlastNearbyConfirmsNothing() {
        // A ушёл в полёт вне мира у цели, рядом взорвался B: A — не попадание
        Impacts i = atC();
        assertNull(i.leave(a, 1000, 110, 0, 1));
        assertNull(i.blast(b));
        assertEquals(1, i.waiting("100"));
        // B уходит позже — его собственный взрыв уже был
        assertNotNull(i.leave(b, 1001, 65, 0, 2));
    }

    @Test
    void blastWithoutProjectileConfirmsNothing() {
        Impacts i = atC();
        i.leave(a, 1000, 65, 0, 1);
        assertNull(i.blast(null));
        assertEquals(1, i.waiting("100"));
    }

    @Test
    void returnToTheWorldCancelsTheWaitingLeaveAndTheRealHitCountsLater() {
        Impacts i = atC();
        assertNull(i.leave(a, 1000, 110, 0, 1));      // уход в полёт вне мира над целью
        assertTrue(i.join(a));                        // вернулся тем же UUID
        assertEquals(0, i.waiting("100"));
        assertFalse(i.join(a));
        assertNull(i.leave(a, 1005, 65, 3, 9));       // настоящее попадание
        Impacts.Hit h = i.blast(a);
        assertNotNull(h);
        assertEquals(9, h.leave().nanos());
    }

    @Test
    void secondaryBlastsOfTheSameProjectileCountOnce() {
        Impacts i = atC();
        i.leave(a, 1000, 65, 0, 1);
        assertNotNull(i.blast(a));
        assertNull(i.blast(a));
        assertNull(i.leave(a, 1000, 65, 0, 2));
    }

    @Test
    void theWaitDoesNotExpire() {
        // подрыв под нагрузкой — сколько угодно позже ухода (Incoming ждёт готовности района до 6000 тиков)
        Impacts i = atC();
        i.leave(a, 1000, 65, 0, 1);
        for (int t = 0; t < 10_000; t++) assertNull(i.blast(UUID.randomUUID()));
        assertNotNull(i.blast(a));
    }

    @Test
    void leaveFarFromEveryTargetIsForgotten() {
        Impacts i = atC();
        assertNull(i.leave(a, 1000, 65, 400, 1));     // у точки пуска
        assertEquals(0, i.waiting("100"));
        assertNull(i.blast(a));
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
