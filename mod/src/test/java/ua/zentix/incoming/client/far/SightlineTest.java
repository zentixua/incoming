package ua.zentix.incoming.client.far;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Beam on the height map: line of sight, the hill between - the path difference and the height from which the source is visible. */
class SightlineTest {
    @Test
    void flatGroundIsOpen() {
        Sightline.Result r = Sightline.trace((x, z) -> 64, 0, 65, 0, 2000, 66, 0);
        assertEquals(0, r.pathDifference(), 1e-9);
        assertEquals(0, r.hidden(), 1e-9);
    }

    @Test
    void unknownColumnsAreNoObstacle() {
        assertEquals(Sightline.Result.OPEN, Sightline.trace((x, z) -> Double.NaN, 0, 65, 0, 2000, 66, 0));
    }

    @Test
    void hillInTheMiddleHidesTheBaseButNotTheColumn() {
        // hill 40 blocks above the plain in the middle of the path at 2 km: source near the ground, eye at the height of 2 block
        Sightline.Heights hill = (x, z) -> x >= 960 && x < 1040 ? 104 : 64;
        Sightline.Result r = Sightline.trace(hill, 0, 65, 0, 2000, 66, 0);
        double a = Math.hypot(1000, 104 - 65), b = Math.hypot(1000, 66 - 104), d = Math.hypot(2000, 1);
        assertEquals(a + b - d, r.pathDifference(), 0.5, "hill edge - travel difference");
        // the straight line from the eye through the vertex rises halfway above the source by 2 (104 − 66) + 66 − 65
        assertEquals(2 * (104 - 66) + 1, r.hidden(), 3);
    }

    @Test
    void raySamplesAreBounded() {
        int[] calls = {0};
        Sightline.trace((x, z) -> {
            calls[0]++;
            return 64;
        }, 0, 65, 0, 8000, 66, 0);
        assertTrue(calls[0] < Sightline.MAX_SAMPLES, "points " + calls[0]);
    }

    @Test
    void groundFindsTheFarSlopeTheCrosshairRestsOn() {
        // eye at 100 over a plain at 64, looking down 1:250: the ray meets the plain 9 000 blocks out
        double dy = -1.0 / 250, n = Math.sqrt(1 + dy * dy);
        double d = Sightline.ground((x, z) -> 64, 0, 100, 0, 1 / n, dy / n, 0, 0, 20_000);
        assertEquals(9_000, d / n, 2, "binoculars reach a far plain at block accuracy");
    }

    @Test
    void groundStopsAtTheFirstRidgeAndSkipsUnknownColumns() {
        // unknown terrain up to 3 km is not ground; a ridge above eye level at 5 km takes the ray before the plain behind it
        Sightline.Heights h = (x, z) -> x < 3_000 ? Double.NaN : x >= 5_000 && x < 5_100 ? 140 : 64;
        double d = Sightline.ground(h, 0, 120, 0, 1, 0, 0, 0, 20_000);
        assertEquals(5_000, d, 2);
    }

    @Test
    void groundMissesBeyondVisibility() {
        // the plain is met at 9 km, but rain limits sight to 5 km
        double dy = -1.0 / 250, n = Math.sqrt(1 + dy * dy);
        assertTrue(Double.isNaN(Sightline.ground((x, z) -> 64, 0, 100, 0, 1 / n, dy / n, 0, 0, 5_000)));
    }

    @Test
    void groundSamplesAreBounded() {
        int[] calls = {0};
        Sightline.ground((x, z) -> {
            calls[0]++;
            return Double.NaN;
        }, 0, 100, 0, 1, 0, 0, 0, 20_000);
        assertTrue(calls[0] < 2_100, "points " + calls[0]);
    }

    @Test
    void groundIgnoresTheColumnsTheEyeStartsUnder() {
        // eye at 65 under a roof (column top 70) for the first 4 blocks, then a plain at 64 and a hill at 2 km
        Sightline.Heights h = (x, z) -> x < 4 ? 70 : x >= 2_000 ? 90 : 64;
        assertEquals(2_000, Sightline.ground(h, 0.5, 65, 0.5, 1, 0, 0, 0, 20_000), 2, "not the roof over the player's head");
    }
}
