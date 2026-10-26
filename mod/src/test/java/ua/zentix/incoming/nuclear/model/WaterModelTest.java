package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterModelTest {
    /** Ripples, splashes and foam - according to the Beaufort scale: none to the threshold, completely from the top, between - grow. */
    @Test
    void windThresholdsFollowBeaufort() {
        assertEquals(0, WaterModel.ripple(1.0));
        assertEquals(1, WaterModel.ripple(20), 1e-9);
        assertEquals(0, WaterModel.spray(10), "7 points (up to 14 m/s) does not tear off ridges");
        assertEquals(1, WaterModel.spray(50), 1e-9);
        assertEquals(0, WaterModel.foam(20), "foam stripes - with 10 points");
        assertEquals(1, WaterModel.foam(70), 1e-9);
        double prev = -1;
        for (double w = 0; w <= 80; w += 0.5) {
            double r = WaterModel.ripple(w), s = WaterModel.spray(w), f = WaterModel.foam(w);
            assertTrue(r >= prev, w + " m/s");
            assertTrue(r >= s && s >= f, w + " m/s: ripples before splashes, splashes before foam");
            prev = r;
        }
    }

    /** Spray wall: no, until the ridges are torn off, then higher with the wind, no higher than 45 m. */
    @Test
    void sprayWallGrowsWithWind() {
        assertEquals(0, WaterModel.sprayHeight(12));
        double prev = 0;
        for (double w = 15; w <= 80; w += 1) {
            double h = WaterModel.sprayHeight(w);
            assertTrue(h >= prev && h <= WaterModel.SPRAY_HIGH, w + " m/s: " + h);
            prev = h;
        }
        assertEquals(WaterModel.SPRAY_HIGH, WaterModel.sprayHeight(60), 1e-9);
    }

    /**
     * Dark ring: ahead of the front the water is smooth, near the very front it is darkest, behind it it becomes lighter - first quickly (stripe of
     *  ripples), then slowly; to calm water - in a few minutes.
     */
    @Test
    void darkRingIsSharpAtTheFrontAndFadesBehind() {
        double wind = 30, band = 25, calm = 600;
        assertEquals(0, WaterModel.darkness(wind, -1, band, calm));
        double front = WaterModel.darkness(wind, 0, band, calm);
        assertEquals(0.6, front, 1e-9);
        double prev = front;
        for (double since = 1; since <= 3000; since += 1) {
            double k = WaterModel.darkness(wind, since, band, calm);
            assertTrue(k < prev, since + " tick");
            prev = k;
        }
        assertTrue(WaterModel.darkness(wind, 4 * band, band, calm) < 0.5 * front, "the stripe at the front is noticeably darker");
        assertTrue(WaterModel.darkness(wind, 4 * band, band, calm) > 0.25 * front, "beyond the strip the water is still choppy");
        assertTrue(WaterModel.darkness(wind, 3000, band, calm) < 0.01, "in 2,5 minutes - calm");
        assertTrue(WaterModel.darkness(5, 0, band, calm) < front, "weak wind - weaker ripples");
    }

    /** The foam rises behind the front in a few ticks (not in front), holds on and launches the rail exit faster than the ripples. */
    @Test
    void whitecapsRiseBehindTheFront() {
        double wind = 80, calm = 600;
        assertEquals(0, WaterModel.whitecaps(wind, -2, calm));
        assertEquals(0, WaterModel.whitecaps(wind, 0, calm));
        assertTrue(WaterModel.whitecaps(wind, 8, calm) > 0.5);
        assertTrue(WaterModel.whitecaps(wind, 600, calm) < 0.05);
        assertEquals(0, WaterModel.whitecaps(20, 10, calm), "at 20 m/s there is no foam yet");
    }

    /** “Water” explosion: on water - all, above half of the largest ball - no; less with height. */
    @Test
    void wetBurstFadesWithHeight() {
        double rf = FireballModel.maxRadius(15, true);
        assertEquals(1, WaterModel.wetBurst(0, 15), 1e-9);
        assertEquals(1, WaterModel.wetBurst(0.1 * rf, 15), 1e-9);
        assertEquals(0, WaterModel.wetBurst(0.5 * rf, 15), 1e-9);
        assertEquals(0, WaterModel.wetBurst(600, 15), 1e-9);
        double prev = 1;
        for (double h = 0; h <= rf; h += 5) {
            double w = WaterModel.wetBurst(h, 15);
            assertTrue(w <= prev, h + " m");
            prev = w;
        }
    }

    /** “Baker” column (23 kt): radius 300 m, walls 90 m; gets up in seconds, falling - settles to the water; 1 has less kt. */
    @Test
    void columnMatchesBaker() {
        assertEquals(300, WaterModel.columnRadius(23), 1e-6);
        assertEquals(90, WaterModel.columnWall(23), 1e-6);
        double max = WaterModel.COLUMN_TOP * WaterModel.SURFACE_COLUMN;
        assertEquals(0, WaterModel.columnTop(0, 23));
        assertNull(WaterModel.column(0, 23));
        assertTrue(WaterModel.columnTop(2, 23) > 0.5 * max, "for 2 s - above half");
        assertTrue(WaterModel.columnTop(9, 23) > 0.95 * max, "to 9 s - almost all");
        assertEquals(0, WaterModel.columnFall(9, 23));
        assertEquals(1, WaterModel.columnFall(40, 23), 1e-9);
        assertEquals(0, WaterModel.columnTop(40, 23), "fallen - by the water");
        assertNull(WaterModel.column(40, 23), "fell - no pillar");
        assertTrue(WaterModel.columnRadius(1) < 0.4 * WaterModel.columnRadius(23));
    }

    /**
     * Shallow underwater explosion 100 kt (Glasstone, Dolan, §2.63): pillar above 5000 feet for 2 s; to 12 s - 3300 feet
     *  in diameter with walls 500 feet; to 20 s settles and narrows to 2000 feet. The height of the explosion on the surface is
     * , the share of the underwater ({@link WaterModel#SURFACE_COLUMN}).
     */
    @Test
    void columnMatchesGlasstoneHundredKilotons() {
        double ft = 0.3048, under = 1 / WaterModel.SURFACE_COLUMN;
        double two = WaterModel.columnTop(2, 100) * under;
        assertTrue(two > 5000 * ft && two < 6000 * ft, "for 2 s: " + two / ft + " feet");
        WaterModel.Column twelve = WaterModel.column(12, 100), twenty = WaterModel.column(20, 100);
        assertEquals(3300 * ft, 2 * (twelve.mid() + twelve.half()), 0.05 * 3300 * ft, "diameter to 12 s");
        assertEquals(500 * ft, 2 * twelve.half(), 0.05 * 500 * ft, "wall to 12 s");
        assertEquals(2000 * ft, 2 * (twenty.mid() + twenty.half()), 0.05 * 2000 * ft, "diameter to 20 s");
        assertEquals(2 * twelve.half(), 2 * twenty.half(), 1e-9, "wall of the same thickness");
        assertTrue(WaterModel.columnTop(20.5, 100) < WaterModel.columnTop(20, 100), "to 20 s settles");
        assertTrue(WaterModel.columnTop(12, 100) > 0.99 * WaterModel.columnTop(11, 100), "to 12 s still standing");
    }

    /**
     * The crown is a ring at the top of the pillar: its top is the top of the pillar, its cross-section is no thinner than the wall and no flatter than 2,5 : 1 (and at the low
     *  pillar: its middle is then under water); wider than the wall when the jets slowed down; The water of the pillar decreases as it falls.
     */
    @Test
    void crownSitsOnTheColumn() {
        double prevWater = 1;
        for (double t = 0.05; t < 40; t += 0.25) {
            WaterModel.Column c = WaterModel.column(t, 15);
            if (c == null) {
                assertTrue(t > 30, t + " s: the pole disappeared early");
                continue;
            }
            assertEquals(WaterModel.columnTop(t, 15), c.top(), 1e-9, t + " s");
            assertTrue(c.crownA() >= c.half() - 1e-9 && c.crownH() >= c.half() - 1e-9, t + " c: the crown is thinner than the wall");
            assertTrue(c.crownA() <= 2.5 * c.crownH(), t + " with: crown flat: " + c.crownA() + " on " + c.crownH());
            assertTrue(c.mid() - c.half() > 0, t + " with: hollow post");
            assertTrue(c.water() <= prevWater + 1e-12 && c.water() > 0, t + " with: water " + c.water());
            prevWater = c.water();
        }
        WaterModel.Column risen = WaterModel.column(6, 15);
        assertTrue(risen.crownR() > 1.3 * (risen.mid() + risen.half()), "the rising pillar is revealed by the crown");
        WaterModel.Column early = WaterModel.column(0.1, 15);
        assertTrue(early.crownR() < 1.1 * (early.mid() + early.half()), "the jets are still flying - there is no crown");
        // the crown falls with the outer curtain: settling column - without cap, top - edge of the wall
        WaterModel.Column falling = WaterModel.column(15, 15);
        assertTrue(WaterModel.columnTop(15, 15) < WaterModel.columnTop(9, 15), "to 15 s the pillar settling");
        assertEquals(falling.mid() + falling.half(), falling.crownR(), 1e-9, "subsidence pillar without crown");
        assertTrue(falling.crownH() <= falling.half() + 1e-9, "the top of the settling column is the edge of the wall");
    }

    /** The column appears when the ball cools down: for 15 kt for 0,5–2,5 s, for 1 MT - later (the ball shines longer). */
    @Test
    void columnShowsAsTheBallCools() {
        assertEquals(0, WaterModel.columnShow(0.4, 15), 1e-9, "while the ball is shining, its water is steam");
        assertEquals(1, WaterModel.columnShow(2.6, 15), 1e-9, "the ball has cooled down - the entire column is visible");
        assertTrue(WaterModel.columnShow(1.5, 15) > 0.3 && WaterModel.columnShow(1.5, 15) < 0.8);
        assertTrue(WaterModel.columnShow(2.6, 1000) < 0.5, "1 Mt ball shines longer");
    }

    /**
     * Base wave: at Baker it rises when the water column falls (~8 s), rolls at first ~30 m/s, by 4 minutes - about
     * 3 km in radius and ~550 m in height; for an explosion on the surface - the same, but smaller ({@link WaterModel#SURFACE_SURGE});
     *  thins out in minutes.
     */
    @Test
    void baseSurgeMatchesBaker() {
        double start = WaterModel.surgeStart(23), k = WaterModel.SURFACE_SURGE, wall = 1.2 * WaterModel.columnRadius(23);
        assertEquals(8, start, 1e-9);
        assertTrue(k > 0.5 && k < 1, "an explosion on the surface has a smaller wave than the underwater one, but of the same order");
        assertEquals(0, WaterModel.surgeRadius(start - 0.1, 23));
        assertEquals(0, WaterModel.surgeDensity(start, 23));
        double v = (WaterModel.surgeRadius(start + 2, 23) - WaterModel.surgeRadius(start + 1, 23)) / k;
        assertTrue(v > 25 && v < 32, "baker's speed at first " + v + " m/s");
        double r4 = (WaterModel.surgeRadius(240, 23) - wall) / k + wall;
        assertTrue(r4 > 2500 && r4 < 3200, "to 4 minutes at Baker " + r4 + " m");
        assertEquals(550 * k, WaterModel.surgeHeight(240, 23), 1e-6);
        double prev = 0;
        for (double t = start; t < 600; t += 1) {
            double r = WaterModel.surgeRadius(t, 23);
            assertTrue(r >= prev, t + " s");
            prev = r;
        }
        assertTrue(WaterModel.surgeDensity(start + 5, 23) > 0.9);
        assertTrue(WaterModel.surgeDensity(start + 240, 23) < 0.4, "after 4 minutes thins out");
    }
}
