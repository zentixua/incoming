package ua.zentix.incoming.grid;

import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutageTest {
    /** Substation in 0 0, area 512 blocks, cascade 3 block per tick, light through 1000 tick per 200. */
    private static final Outage O = new Outage(1, 0, 0, 512, 100, 3, 1100, 200, 7);

    @Test
    void cascadeGoesOutwardWithSpread() {
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                long d = Districts.of(x, z);
                if (!O.covers(d)) continue;
                double r = Math.hypot(Districts.seedX(d), Districts.seedZ(d));
                long at = O.darkAt(d);
                // ±25 % of cascade arrival
                assertTrue(at >= 100 + Math.floor(r / 3 * 0.75) && at <= 100 + Math.ceil(r / 3 * 1.25), "quarter in " + r + ": " + at);
                assertTrue(at <= O.lastDark());
                assertFalse(O.dark(d, at - 1));
                assertTrue(O.dark(d, at) || O.lightAt(d) <= at);
            }
        }
    }

    @Test
    void lightReturnsWithinSpreadAndOutageEnds() {
        for (int x = -30; x <= 30; x++) {
            long d = Districts.of(x, 0);
            if (!O.covers(d)) continue;
            long light = O.lightAt(d);
            assertTrue(light >= 1100 && light <= 1300);
            assertFalse(O.dark(d, light));
        }
        assertFalse(O.over(1299));
        assertTrue(O.over(1300));
    }

    @Test
    void districtOutsideRadiusStaysLit() {
        long far = Districts.of(100, 0);
        assertFalse(O.covers(far));
        assertFalse(O.dark(far, 500));
    }

    @Test
    void neverRestoredStaysDark() {
        Outage o = new Outage(2, 0, 0, 512, 0, 3, Outage.NEVER, 200, -1);
        long d = Districts.of(0, 0);
        assertEquals(Outage.NEVER, o.lightAt(d));
        assertTrue(o.dark(d, Long.MAX_VALUE - 1));
        assertFalse(o.over(Long.MAX_VALUE - 1));
    }

    /** Return on command before the cascade has reached: the block never goes out. */
    @Test
    void earlyRestoreSkipsDistrictsNotYetDark() {
        Outage o = new Outage(3, 0, 0, 512, 0, 0.5, Outage.NEVER, 0, -1).restoring(10, 0);
        long edge = Districts.of(25, 0);
        assertTrue(o.covers(edge));
        for (long t = 0; t < 2000; t++) assertFalse(o.dark(edge, t));
    }

    @Test
    void codecRoundTrip() {
        var json = Outage.CODEC.encodeStart(JsonOps.INSTANCE, O).getOrThrow();
        assertEquals(O, Outage.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        json.getAsJsonObject().remove("node");
        assertEquals(-1, Outage.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().node());
    }

    /** Nuclear outage by damage: destroyed within 2500 blocks, damaged belt to 6000, area 8000 blocks. */
    private static final Outage NUKE = new Outage(4, 0, 0, 8000, 0, 50, Outage.NEVER, 1800, -1, 2500, 6000);
    private static final double HOUR = Outage.TICKS_PER_HOUR;

    /**
     * Repair follows the damage: never where the grid was destroyed, days in the damaged belt (2–14, ±25 %), hours outside it
     * (2–24, ±25 %); the whole tripped ring relights before any district of the damaged belt, the far edge before its inner
     * side, and no district lights before the restoration cascade starts.
     */
    @Test
    void nuclearRepairFollowsDamage() {
        long trippedLast = Long.MIN_VALUE, damagedFirst = Long.MAX_VALUE, first = Long.MAX_VALUE;
        long innerTripped = 0, outerTripped = 0;
        int dead = 0, damaged = 0, tripped = 0;
        for (int x = -480; x <= 480; x += 3) {
            for (int z = -480; z <= 480; z += 37) {
                long d = Districts.of(x, z);
                if (!NUKE.covers(d)) continue;
                double r = Math.hypot(Districts.seedX(d), Districts.seedZ(d));
                long light = NUKE.lightAt(d);
                assertTrue(NUKE.darkAt(d) < light, "lit before it went dark at " + r);
                if (r <= 2500) {
                    assertEquals(Outage.NEVER, light, "destroyed grid relit at " + r);
                    assertTrue(NUKE.dark(d, 100_000_000L));
                    dead++;
                    continue;
                }
                first = Math.min(first, light);
                double hours = light / HOUR;
                if (r <= 6000) {
                    assertTrue(hours >= 48 * 0.75 - 0.01 && hours <= 336 * 1.25 + 0.01, "damaged belt at " + r + ": " + hours + " h");
                    damagedFirst = Math.min(damagedFirst, light);
                    damaged++;
                } else {
                    assertTrue(hours >= 2 * 0.75 - 0.01 && hours <= 24 * 1.25 + 0.01, "tripped ring at " + r + ": " + hours + " h");
                    trippedLast = Math.max(trippedLast, light);
                    if (r < 6300) innerTripped = Math.max(innerTripped, light);
                    if (r > 7700) outerTripped = Math.max(outerTripped, light);
                    tripped++;
                }
            }
        }
        assertTrue(dead > 0 && damaged > 0 && tripped > 0, dead + " " + damaged + " " + tripped);
        assertTrue(trippedLast < damagedFirst, "outskirts after the damaged belt");
        assertTrue(outerTripped < innerTripped, "far edge after the inner side of the ring");
        assertTrue(NUKE.restoreFrom() <= first && NUKE.restoreFrom() > 0);
        assertTrue(NUKE.byDamage());
        assertFalse(NUKE.over(Long.MAX_VALUE - 1), "a destroyed zone ended by itself");
    }

    /** A command restores light everywhere, the destroyed zone included, and ends the outage. */
    @Test
    void commandOverridesDamage() {
        Outage o = NUKE.restoring(5000, 200);
        long centre = Districts.of(0, 0);
        assertTrue(o.lightAt(centre) <= 5200);
        assertFalse(o.dark(centre, 5200));
        assertTrue(o.over(5200));
        // a district whose repair was due before the command keeps that sooner time
        for (int x = 400; x <= 480; x++) {
            long d = Districts.of(x, 0);
            if (o.covers(d)) assertEquals(Math.min(NUKE.lightAt(d), 5000 + Math.round(200 * Districts.unit(d, 4))), o.lightAt(d));
        }
    }

    /** Without a destroyed zone (high burst) the outage ends once the longest repair is done. */
    @Test
    void damageWithoutDestroyedZoneEnds() {
        Outage o = new Outage(5, 0, 0, 4000, 0, 50, Outage.NEVER, 0, -1, 0, 3000);
        assertFalse(o.over(o.rerouteAt()));
        assertTrue(o.over(o.rerouteAt() + 1));
        for (int x = -250; x <= 250; x += 5) {
            long d = Districts.of(x, 0);
            if (o.covers(d)) assertTrue(o.lightAt(d) <= o.rerouteAt(), "district at " + x + " beyond the longest repair");
        }
    }

    @Test
    void damageCodecRoundTrip() {
        var json = Outage.CODEC.encodeStart(JsonOps.INSTANCE, NUKE).getOrThrow();
        assertEquals(NUKE, Outage.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow());
        // outages saved before the damage law load as uniform ones
        json.getAsJsonObject().remove("dead");
        json.getAsJsonObject().remove("damaged");
        assertFalse(Outage.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().byDamage());
        assertFalse(O.byDamage());
    }
}
