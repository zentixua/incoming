package ua.zentix.incoming.client.lod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LodRefreshHintsTest {
    @Test void aLongSessionAndAReplayBurstHaveFixedCoordinateBounds() {
        var hints = new LodRefreshHints(64, 12);
        Object world = new Object();
        hints.begin(world);
        for (long chunk = 0; chunk < 100_000; chunk++) {
            hints.remember(world, chunk);
            hints.overwritten(world, chunk);
            assertTrue(hints.recentSize() <= 64);
            assertTrue(hints.pendingSize() <= 12);
        }
        assertEquals(64, hints.recentSize());
        assertEquals(12, hints.pendingSize());
        assertArrayEquals(new long[]{99988, 99989, 99990}, hints.take(world, 3));
        assertEquals(9, hints.pendingSize());
    }

    @Test void lateCallbacksCannotRetargetTheNextWorldOrReconnect() {
        var hints = new LodRefreshHints(2, 2);
        Object old = new Object(), fresh = new Object();
        hints.begin(old);
        hints.remember(old, 7);
        hints.overwritten(old, 7);
        hints.reset();
        hints.remember(old, 7);
        hints.request(old, 7);
        assertEquals(0, hints.recentSize());
        assertEquals(0, hints.pendingSize());
        hints.begin(fresh);
        hints.remember(fresh, 8);
        hints.overwritten(fresh, 8);
        hints.remember(old, 7);
        hints.request(old, 7);
        assertArrayEquals(new long[0], hints.take(old, 2));
        assertArrayEquals(new long[]{8}, hints.take(fresh, 2));
    }

    @Test void aForeignDeliveryCrossingSubmissionKeepsItsRequestForCurrentServerState() {
        var hints = new LodRefreshHints(2, 2);
        Object world = new Object();
        hints.begin(world);
        hints.remember(world, 7);
        hints.overwritten(world, 7);
        hints.remember(world, 7);
        hints.overwritten(world, 7);
        assertArrayEquals(new long[]{7}, hints.take(world, 12));
        assertEquals(0, hints.pendingSize());
    }

    @Test void evictedHintsDoNotRetainTerrainAndRejectedColumnsCanStillRequestRefresh() {
        var hints = new LodRefreshHints(2, 2);
        Object world = new Object();
        hints.begin(world);
        hints.remember(world, 1);
        hints.remember(world, 2);
        hints.remember(world, 1);
        hints.remember(world, 3);
        hints.overwritten(world, 2);
        assertEquals(0, hints.pendingSize());
        hints.overwritten(world, 1);
        hints.request(world, 4);
        assertArrayEquals(new long[]{1, 4}, hints.take(world, 12));
    }
}
