package ua.zentix.incoming.scenario.lod;

import ua.zentix.incoming.client.lod.TimedLodBuffer;

import org.junit.jupiter.api.Test;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class LodBufferTest {
    @Test void earlyDeliveryCannotMoveThePressureClock() {
        var b = new TimedLodBuffer<String>(2, 20);
        var t = b.reserve(1, 100, 10, 24);
        assertTrue(b.complete(t, "ruins"));
        assertNull(b.take(99));
        var r = b.take(107);
        assertEquals(100, r.ticket().due()); // seven ticks late, never reschedule the event
        assertEquals(10, b.bytes());
        b.finish(r);
        b.finish(r);
        assertEquals(0, b.bytes());
    }
    @Test void stopRefireRejectsOldReadsEvenAtTheSameCoordinate() {
        var b = new TimedLodBuffer<String>(4, 40);
        var old = b.reserve(7, 100, 10, 24);
        var ready = b.reserve(8, 100, 10, 24);
        b.complete(ready, "ready old");
        b.stop();
        assertEquals(10, b.bytes());
        var fresh = b.reserve(7, 200, 10, 24);
        assertFalse(b.complete(old, "late old"));
        assertFalse(b.complete(old, "duplicate"));
        assertTrue(b.complete(fresh, "new"));
        assertEquals("new", b.take(200).value());
    }
    @Test void neverCompletingReadsStayBoundedAcrossThousandsOfRefires() {
        var b = new TimedLodBuffer<String>(64, 64 * 1024);
        for (int round = 0; round < 10000; round++) {
            b.reserve(round, round, 1024, 24);
            b.stop();
            assertTrue(b.size() <= 64);
            assertTrue(b.bytes() <= 65536);
        }
        assertEquals(64, b.waiting());
        assertNull(b.reserve(10001, 0, 1, 1));
    }
    @Test void takenWorkStaysChargedAcrossStop() {
        var b = new TimedLodBuffer<String>(1, 10);
        var t = b.reserve(1, 0, 10, 24);
        b.complete(t, "x");
        var r = b.take(0);
        b.stop();
        assertFalse(b.current(t));
        assertNull(b.reserve(2, 0, 1, 1));
        b.finish(r);
        assertNotNull(b.reserve(2, 0, 1, 1));
    }
    @Test void bunchedOutOfOrderReadsDrainByDueTimeAndRespectBothLimits() {
        var b = new TimedLodBuffer<Integer>(4, 30);
        var a = b.reserve(1, 30, 10, 24);
        var c = b.reserve(3, 10, 10, 24);
        var d = b.reserve(2, 20, 10, 24);
        assertNull(b.reserve(4, 0, 1, 1));
        b.complete(a, 3); b.complete(d, 2); b.complete(c, 1);
        for (int i = 1; i <= 3; i++) { var r = b.take(30); assertEquals(i, r.value()); b.finish(r); }
        assertEquals(0, b.size());
    }
    @Test void panoramaYieldsWithoutWholeZoneEnumeration() {
        for (double radius : new double[]{1605, 3021, 6509}) {
            var p = new LodPanorama(radius);
            assertFalse(p.prepare(1));
            assertThrows(IllegalStateException.class, p::next);
            while (!p.prepare(8)) assertTrue(p.headers() < 256);
            long count = 0; double previous = 0;
            var unique = new HashSet<LodPanorama.Column>();
            for (var c = p.next(); c != null; c = p.next()) {
                assertTrue(c.radius() >= radius * .8 && c.radius() <= radius + 1e-8);
                assertTrue(c.z() >= Math.abs(c.x()));
                assertTrue(c.radius() >= previous);
                assertTrue(unique.add(c));
                previous = c.radius(); count++;
            }
            assertEquals(p.columns(), count);
            assertTrue(count > 1000);
        }
    }
}
