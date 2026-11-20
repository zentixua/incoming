package ua.zentix.incoming.nuclear.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** {@link GlassPath}: each window the pulse ray crosses passes {@link GlassPath#GLASS} once. */
class GlassPathTest {
    @Test
    void obliqueCrossingOfOneWallIsOnePane() {
        GlassPath p = new GlassPath();
        p.enter(0.6, false);
        p.enter(1.1, true);
        p.enter(1.4, true);
        p.enter(1.9, true);
        p.enter(2.3, false);
        assertEquals(GlassPath.GLASS, p.pass, 1e-12);
    }

    @Test
    void cellTouchedAtATieDoesNotSplitAWindow() {
        // diagonal ray: leaves glazing (1, 1) and enters glazing (2, 2) at the same parameter; the walk visits air (2, 1) between
        GlassPath p = new GlassPath();
        p.enter(0.7, true);
        p.enter(2.1, false);
        p.enter(2.1, true);
        p.enter(3.5, false);
        assertEquals(GlassPath.GLASS, p.pass, 1e-12);
    }

    @Test
    void twoWindowsWithAirBetweenPassTwice() {
        GlassPath p = new GlassPath();
        p.enter(0.5, true);
        p.enter(1.5, false);
        p.enter(2.5, true);
        p.enter(3.5, false);
        assertEquals(GlassPath.GLASS * GlassPath.GLASS, p.pass, 1e-12);
    }
}
