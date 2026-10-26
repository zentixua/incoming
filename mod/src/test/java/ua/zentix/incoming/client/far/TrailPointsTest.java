package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.fx.plume.TrailModel.Trail;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.LauncherEntity;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** General circular buffer of trail points: they melt away in their own time, the full buffer overwrites the old ones, the end clears the trail immediately. */
class TrailPointsTest {
    private static final Trail STYLE = ClientWeaponSpec.of(WeaponType.ROCKET).airframe(false).far().stage(FlightPhase.BOOST).trail();
    /** Lifespan of dots, tick: short and long trace. */
    private static final float SHORT = 40, LONG = 1000;

    @Test
    void pointsMeltByTheirOwnLifetime() {
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 100, STYLE, 0, 1, TrailPoints.NONE, 0, SHORT, 0, 0, 0, 0, 0, 0);
        long b = p.add(1, 0, 0, 100, STYLE, 0, 2, TrailPoints.NONE, 0, LONG, 0, 0, 0, 0, 0, 0);
        long c = p.add(2, 0, 0, 101, STYLE, 0, 1, a, 0, SHORT, 0, 0, 0, 0, 0, 0);
        p.expire(139);
        assertTrue(p.holds(a) && p.holds(c), "the short one is still alive");
        p.expire(140);
        assertFalse(p.holds(a), "melted off the tail");
        // after a long trail, the short one melted, but it cannot be removed from the tail - it will be weeded out by the frame by age
        assertTrue(p.holds(b) && p.holds(c));
        assertTrue(141 - p.birth[TrailPoints.index(c)] >= p.life[TrailPoints.index(c)]);
        p.expire(1100);
        assertTrue(p.isEmpty());
    }

    @Test
    void frozenWorldStopsAgeing() {
        // /tick freeze on 30 tick: the 100-th tick point melts to the 170-th, and not to the 140-th
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 100, STYLE, 0, 1, TrailPoints.NONE, 0, SHORT, 0, 0, 0, 0, 0, 0);
        for (int i = 0; i < 30; i++) p.hold();
        p.expire(169);
        assertTrue(p.holds(a), "doesn't age when frozen");
        p.expire(170);
        assertFalse(p.holds(a));
    }

    @Test
    void finishedRibbonEndsUntilThePathResumes() {
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 0, STYLE, 0, 1, TrailPoints.NONE, 0, LONG, 0, 0, 0, 0, 0, 0);
        long b = p.add(1, 0, 0, 4, STYLE, 0, 1, a, 0, LONG, 0, 0, 0, 0, 0, 0);
        p.finish(b);
        assertTrue(p.last[TrailPoints.index(b)], "ribbon ended: fades out there");
        assertFalse(p.last[TrailPoints.index(a)]);
        // there is a way again - the tape continues, the last end is no longer the end
        long c = p.add(2, 0, 0, 8, STYLE, 0, 1, b, 0, LONG, 0, 0, 0, 0, 0, 0);
        assertFalse(p.last[TrailPoints.index(b)]);
        assertFalse(p.last[TrailPoints.index(c)]);
        p.finish(TrailPoints.NONE);
    }

    @Test
    void fullBufferOverwritesOldestAndBreaksLinksToIt() {
        TrailPoints p = new TrailPoints();
        long prev = TrailPoints.NONE, firstLink = TrailPoints.NONE;
        for (int i = 0; i < TrailPoints.CAPACITY + 10; i++) {
            prev = p.add(i, 0, 0, i, STYLE, 0, 1, prev, 0, LONG, 0, 0, 0, 0, 0, 0);
            if (i == 10) firstLink = prev;
        }
        assertEquals(10, p.first(), "the ten oldest ones have been erased");
        assertFalse(p.holds(9));
        assertTrue(p.holds(10));
        // point 10 refers to the erased 9: there is no segment to it
        assertFalse(p.holds(p.prev[TrailPoints.index(firstLink)]));
        assertEquals(TrailPoints.CAPACITY + 9, p.prev[TrailPoints.index(prev)] + 1);
    }

    @Test
    void cancelledFlightTrailVanishesAtOnce() {
        TrailPoints p = new TrailPoints();
        long a = p.add(0, 0, 0, 0, STYLE, 0, 7, TrailPoints.NONE, 0, LONG, 0, 0, 0, 0, 0, 0);
        long b = p.add(0, 0, 0, 0, STYLE, 0, 8, TrailPoints.NONE, 0, LONG, 0, 0, 0, 0, 0, 0);
        p.kill(7);
        assertNull(p.style[TrailPoints.index(a)]);
        assertNotNull(p.style[TrailPoints.index(b)], "someone else's trail remains");
        p.expire(1);
        assertFalse(p.holds(a));
        assertTrue(p.holds(b));
    }

    @Test
    void wholeRocketPackFitsQuarterOfBuffer() {
        // "Grad" package - 40 shells: point once per step while the engine is burning, and burnout point
        Trail trail = ClientWeaponSpec.of(WeaponType.ROCKET).airframe(false).far().stage(FlightPhase.BOOST).trail();
        assertNotNull(trail);
        int pack = LauncherEntity.ROCKET_COLUMNS * LauncherEntity.ROCKET_ROWS;
        int perRocket = WeaponSpec.ROCKET.airframe().ballistic().rocket().burnTicks() / trail.step() + 2;
        assertTrue(pack * perRocket <= TrailPoints.CAPACITY / 4, pack * perRocket + " points");
    }
}
