package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.WaterModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PallFieldTest {
    /** 15 kt near the ground at noon, wind 8 m/s, scale 1, wall of dust in the distance - 0,42 speed of sound. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    private static final Detonation SMALL = new Detonation(2, new Vec3(0, 64, 0), 64, 1, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    /** The same 15 kt, but the server did not set it on fire (water, high detonation): neither haze nor its ashes - only dust and splashes. */
    private static final Detonation NO_FIRES = new Detonation(4, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f,
            Detonation.NO_DAY_TIME, false);
    /** 15 kt near the ground without wind, the front in the game is like the model: head and pall in one place. */
    private static final Detonation CALM = new Detonation(6, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 1f, false, 1f);
    /** Hiroshima: 15 kt on 592 m (DS02: 16 kt on 600 m), without wind and fires - just dust. */
    private static final Detonation HIROSHIMA = new Detonation(7, new Vec3(0, 64 + 592, 0), 64, 15, false, 0, 0, 0, 20_000, 7, 1f, false, 1f,
            Detonation.NO_DAY_TIME, false);
    private static final long NOON = 6000;

    @Test
    void tableFitsTheLayer() {
        assertEquals(PallPass.WIDTH, PallField.BINS);
    }

    /**
     * Dust table - layer {@link DustModel} of the node: thickness above the ground (weakening at the ground × profile column × top) - thickness of the
     *  layer without the one still in the head of the wall, and without the one in the fireball on the ground; top — the top of the layer in blocks (not lower than
     * {@link PallField#MIN_TOP_BLOCKS}).
     */
    @Test
    void dustRowIsTheDustLayer() {
        Detonation quarter = new Detonation(5, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 0.25f, false, 0.42f);
        PallField p = new PallField(quarter, NOON);
        double[] v = new double[4];
        int inBall = 0;
        for (int i : new int[]{3, 20, 60}) {
            double arrival = arrival(p, i);
            // positive phase - different at each range
            DustModel.Column c = p.layer(i);
            double positive = c.positive();
            for (double age : new double[]{0.3, 1.2, 2, 30, 600}) {
                double ticks = arrival + 20 * quarter.scale() * age;
                p.dust(i, ticks, v);
                boolean ball = quarter.metres(p.radius(i)) < FireballModel.groundRadius(ticks / (20 * quarter.scale()), 0, 15, true);
                if (ball) inBall++;
                double depth = ball ? 0 : c.depth(age) * (1 - BlastWall.handoff(age / positive));
                assertEquals(c.depth(age) < PallField.GONE_DEPTH ? 0 : depth, v[0] * PallField.column(0) * v[1], 1e-6 * depth + 1e-12, i + ", " + age + " s");
                assertEquals(Math.max(PallField.MIN_TOP_BLOCKS, quarter.blocks(c.height(age))), v[1], 1e-9);
            }
            p.dust(i, arrival + 20 * quarter.scale() * 0.9 * positive, v);
            assertEquals(0, v[0], "dust younger than the wave phase - in the head of the wall");
        }
        assertTrue(inBall > 0, "nodes under the ball are not checked");
    }

    /**
     * In the fireball on the ground ({@link FireballModel#groundRadius}) there is no dust pall, no splashes - there is a ball; pall gets up
     *  from under him as he floats up.
     */
    @Test
    void noPallInTheFireball() {
        PallField p = new PallField(CALM, NOON);
        double[] v = new double[4];
        int inside = 0;
        for (double t = 0.3; t <= 8; t += 0.1) {
            double ball = FireballModel.groundRadius(t, 0, 15, true);
            for (int i = 0; i < PallField.BINS && CALM.metres(p.radius(i)) < ball; i++) {
                p.dust(i, 20 * t, v);
                assertEquals(0, v[0], t + " with: dust pall in a ball in " + CALM.metres(p.radius(i)) + " m");
                p.spray(i, 20 * t, v);
                assertEquals(0, v[0], t + " with: splashes in a ball in " + CALM.metres(p.radius(i)) + " m");
                inside++;
            }
        }
        assertTrue(inside > 100, "knots under the ball " + inside);
        assertEquals(0, FireballModel.groundRadius(8, 0, 15, true), "the ball did not float");
        p.dust(1, 20 * 8, v);
        assertTrue(v[0] > 0, "the ball floated up, but there was no pall at the epicenter");
        p.spray(1, 20 * 8, v);
        assertTrue(v[0] > 0, "the ball floated to the surface, but there was no splash at the epicenter");
    }

    /**
     * Complaint 18.10: from above, the pall looked like golden and dark wisps, rather than one blanket. Mixed layer
     * ({@link PallField#mixed}, {@link PallField#clumps}) with top mounds ({@link PallField#BUMPS}) at 15 kt on 592 m through
     * 6–7 minutes: where is the average the thickness of the layer above the ground is not less than 3 / (0,8 (1 − BUMPS)) ≈ 5,4, the thinnest column
     *  (the clearance of the billows in the hollow between the hillocks) is not thinner than 3: the ground through it is no more than 5% (e^(−3)), and the light of the thick layer from above almost does not depend on the thickness - the blanket is even. The young layer at the front is not yet mixed - patches with gaps (Kuhl §3.5).
     */
    @Test
    void oldPallIsOneBlanket() {
        PallField p = new PallField(HIROSHIMA, NOON);
        double[] v = new double[4];
        int thick = 0;
        for (int i = 0; i < PallField.BINS; i++) {
            p.dust(i, 8000, v);
            double tau = v[0] * PallField.column(0) * v[1];
            if (tau < 3 / (PallField.clumps(0, 1) * (1 - PallField.BUMPS))) continue;
            thick++;
            assertTrue(v[3] > 0.95, "node " + i + ": layer not mixed in 6 minutes: " + v[3]);
            double thinnest = tau * PallField.clumps(0, v[3]) * (1 - PallField.BUMPS);
            assertTrue(thinnest >= 3, "node " + i + ": thickness in the lumen " + thinnest + " at average " + tau);
        }
        assertTrue(thick > 10, "there is almost no thick pall: " + thick + " nodes");
        assertEquals(0, PallField.mixed(0));
        assertEquals(0, PallField.clumps(0, PallField.mixed(0)), 1e-12, "young layer - shreds with full gaps");
        for (double shape = 0; shape <= 3.5; shape += 0.5) {
            for (double after = 0; after <= 600; after += 30) {
                double m = PallField.mixed(after), c = PallField.clumps(shape, m);
                assertTrue(Math.abs(c - 1) <= Math.abs(shape - 1) + 1e-12, "fluctuations are growing");
                assertTrue(Math.abs(c - 1) >= PallField.MIXED * Math.abs(shape - 1) - 1e-12, "oscillations below the mixed layer");
            }
        }
    }

    /** A column above a point (shadow, sky light) is the integral of the profile along the height to the top, with an error less than 0,01. */
    @Test
    void columnIsIntegralOfProfile() {
        for (double x = -1.4; x <= 1.2; x += 0.05) {
            double sum = 0, step = 1e-4;
            for (double y = x + step / 2; y < 1.5; y += step) sum += PallField.profile(y) * step;
            assertEquals(sum, PallField.column(x), 0.01, "x = " + x);
        }
    }

    /** pall is thick near the ground, melts towards the top; in the valley below the epicenter - too, but in the cave deeper - not. */
    @Test
    void profileThinsUpward() {
        assertEquals(1, PallField.profile(0), 1e-9);
        assertEquals(0, PallField.profile(1), 1e-9);
        assertEquals(0, PallField.profile(-1), 1e-9);
        assertEquals(1, PallField.profile(-0.3), 1e-9);
        double prev = 1;
        for (double x = 0; x <= 1; x += 0.02) {
            assertTrue(PallField.profile(x) <= prev + 1e-12, "x = " + x);
            prev = PallField.profile(x);
        }
    }

    /**
     * The top of the pall is the top of the layer: seconds after the front - tens of meters, then the heated earth lifts it, and it does not descend; for 1 the kt is lower (Rosenblatt, review §5).
     */
    @Test
    void heightRisesOverHeatedGround() {
        PallField p = new PallField(HIROSHIMA, NOON);
        PallField small = new PallField(new Detonation(8, new Vec3(0, 64 + 592 * Math.cbrt(1 / 15.0), 0), 64, 1, false, 0, 0, 0, 20_000, 7, 1f,
                false, 1f, Detonation.NO_DAY_TIME, false), NOON);
        double[] v = new double[4];
        int i = (int) (1000 / p.reach() * PallField.BINS);
        double arrival = arrival(p, i), prev = 0;
        for (double age = 1.5; age <= 1800; age += 0.5) {
            p.dust(i, arrival + 20 * age, v);
            assertTrue(v[1] >= prev - 1e-9, age + " c: the top of the dust has dropped");
            prev = v[1];
        }
        p.dust(i, arrival + 20 * 5, v);
        assertTrue(v[1] > 60 && v[1] < 150, "to 1 km via 5 s top " + v[1]);
        p.dust(i, arrival + 20 * 600, v);
        assertTrue(v[1] > 120 && v[1] < 350, "in 1 km in 10 minutes top " + v[1]);
        double[] s = new double[4];
        int j = (int) (1000 * Math.cbrt(1 / 15.0) / small.reach() * PallField.BINS);
        small.dust(j, arrival(small, j) + 20 * 600, s);
        assertTrue(s[1] < 0.7 * v[1], "for 1 kt is not lower: " + s[1]);
    }

    /** Arrival of the front at node i, tick. */
    private static double arrival(PallField p, int i) {
        Detonation d = p.detonation();
        return d.arrivalTicks(d.blocks(Math.hypot(d.metres(p.radius(i)), Math.max(0, d.hobMetres()))));
    }

    /** Dust rises only where the wave front has passed: before it there is nothing. */
    @Test
    void pallRisesOnlyBehindTheFront() {
        PallField p = new PallField(GROUND, NOON);
        double[] v = new double[4];
        for (double ticks : new double[]{20, 100, 300, 600, 1200}) {
            double front = GROUND.frontRadius(ticks);
            for (int i = 0; i < PallField.BINS; i++) {
                double r = p.radius(i);
                p.dust(i, ticks, v);
                if (r > front * 1.01) assertEquals(0, v[0], "r = " + r + " ahead of the front " + front + " through " + ticks + " tick");
                p.spray(i, ticks, v);
                if (r > front * 1.01) assertEquals(0, v[0], "splashes ahead of the front, r = " + r);
            }
            int behind = (int) (Math.min(front * 0.5, p.reach() * 0.5) / p.reach() * PallField.BINS);
            p.dust(behind, ticks, v);
            if (ticks >= 100) assertTrue(v[0] > 0, "behind the front through " + ticks + " tick no dust");
        }
    }

    /**
     * The head of the wall and the pall are one layer: the head is dust younger than the positive phase, the pall is older, the place moves from
     *  of the head to the pall in half a phase ({@link BlastWall#handoff}). The thickness above the ground at the head and pall together is the thickness of the layer
     * ({@link DustModel.Column#depth}) everywhere behind the front: no gap, no double dust at the junction. Raised in a wedge - along a straight line
     *  between its edge and end (at 1 km the layer towards the epicenter grows faster than a straight line - up to +18%), at the junction - up to −11%.
     */
    @Test
    void headAndPallAreOneLayer() {
        PallField p = new PallField(CALM, NOON);
        BlastWall w = new BlastWall(CALM, false);
        float[] s = new float[BlastWall.SLOT];
        int checked = 0;
        for (double metres : new double[]{1000, 1500, 2500}) {
            int i = (int) (metres / p.reach() * PallField.BINS);
            double r = p.radius(i), arrival = arrival(p, i);
            for (double t = arrival / 20 + 0.02; t < arrival / 20 + 6 * p.layer(i).positive(); t += 0.02) {
                if (!w.slot(t, s)) continue;
                double u = (s[0] - r) / s[1], ticks = 20 * t;
                if (u < 0.05) continue;
                double head = BlastWall.tau(s, r, 0, 0, Double.POSITIVE_INFINITY, 0);
                double pall = p.tau(r, 0, 0, Double.POSITIVE_INFINITY, 0, ticks, PallField.Water.DRY);
                double layer = p.layer(i).depth(t - arrival / 20);
                assertEquals(layer, head + pall, 0.2 * layer, metres + " m, u = " + u + ": head " + head + " + pall " + pall);
                if (u > 0.9 && metres > 2000) assertEquals(layer, head + pall, 0.06 * layer, metres + " m, u = " + u + ": joint");
                if (u < 0.9) assertEquals(0, pall, 1e-12, "pall in the wedge of the head");
                if (u > 1.6) assertEquals(0, head, 1e-12, "head behind the wedge");
                checked++;
            }
        }
        assertTrue(checked > 100, "checks " + checked);
    }

    /**
     * Above the hill, the layer rises from its slope (the dust is picked up by the wind from it): a point in 40 blocks above the ground under the epicenter on the
     *  hill in 38 - as in 2 blocks above level ground; in the valley - as it was (dust flows there from the ground under the epicenter).
     */
    @Test
    void layerFollowsHillsNotValleys() {
        PallField p = new PallField(GROUND, NOON);
        double ticks = GROUND.arrivalTicks(1400) + 20 * 10;
        assertTrue(p.sigma(1400, 0, 2, 0, ticks, PallField.Water.DRY) > 0);
        assertEquals(p.sigma(1400, 0, 2, 0, ticks, PallField.Water.DRY), p.sigma(1400, 0, 40, 38, ticks, PallField.Water.DRY), 1e-12);
        assertEquals(p.sigma(1400, 0, -20, 0, ticks, PallField.Water.DRY), p.sigma(1400, 0, -20, -30, ticks, PallField.Water.DRY), 1e-12);
    }

    /** Vertical thickness ({@link PallField#tau}) - the integral of the attenuation along the height: above the eye and below it to the ground, and on the hill. */
    @Test
    void verticalDepthIsTheIntegral() {
        PallField p = new PallField(GROUND, NOON);
        for (double metres : new double[]{300, 1400, 2500}) {
            for (double age : new double[]{2, 20, 300}) {
                double ticks = GROUND.arrivalTicks(metres) + 20 * age;
                for (double floor : new double[]{-30, 0, 38}) {
                    for (double eye : new double[]{floor + 2, floor + 60}) {
                        double up = 0, down = 0, ds = 0.05;
                        for (double h = eye + ds / 2; h < eye + 2000; h += ds) up += p.sigma(metres, 0, h, floor, ticks, PallField.Water.DRY) * ds;
                        for (double h = floor + ds / 2; h < eye; h += ds) down += p.sigma(metres, 0, h, floor, ticks, PallField.Water.DRY) * ds;
                        String at = metres + " m, " + age + " s, floor " + floor + ", eye " + eye;
                        double tu = p.tau(metres, 0, eye, Double.POSITIVE_INFINITY, floor, ticks, PallField.Water.DRY);
                        double td = p.tau(metres, 0, floor, eye, floor, ticks, PallField.Water.DRY);
                        assertEquals(up, tu, 0.005 * up + 1e-6, at + ": above the eye");
                        assertEquals(down, td, 0.005 * down + 1e-6, at + ": under the eye");
                    }
                }
            }
        }
    }

    /**
     * Light on the ground under the pall against the open day (green channel, sun at 40°): direct sun through the thickness and light scattered by
     *  dust ({@link PallLight#diffuse}, albedo - dust color {@link NukePall#DUST}).
     */
    private static double groundLight(double tau) {
        double g = PallLight.truncated(PallLight.DUST_G), w = PallLight.albedo(PallLight.linear(NukePall.DUST[1]), g);
        double sky = PallLight.SKY * PallLight.linear(0.65), sun = 1 - sky, mu = Math.sin(Math.toRadians(40));
        return (mu * sun * Math.exp(-tau / mu) + PallLight.diffuse(w, g, tau, tau, PallLight.LAND, mu, sun, 1, 0, sky, 0, -2.0 / 3)) / (mu * sun + sky);
    }

    /** Light on the ground under the pall of Hiroshima at {@code metres} m from the epicenter through {@code age} from after the front. */
    private static double hiroshimaLight(PallField p, double metres, double age) {
        int i = (int) (metres / p.reach() * PallField.BINS);
        double ticks = arrival(p, i) + 20 * age;
        return groundLight(p.tau(p.radius(i), 0, 0, Double.POSITIVE_INFINITY, 0, ticks, PallField.Water.DRY));
    }

    /**
     * Hiroshima (15 kt on 592 m): near Fuji in 1,42 km - “about twenty minutes” of darkness (Hersey 1946): light on the ground under the veil
     * less than a thousandth of the day from the first seconds to 20-th minutes, to 30-th lighter; at Tanimoto in 3,2 km - twilight the first minutes
     *  (not night); near Takakura in 300 m - it brightens in minutes (the dust goes into the stem). An hour later, over 1,4 km it is almost clear.
     */
    @Test
    void darkForTwentyMinutesAtHiroshima() {
        PallField p = new PallField(HIROSHIMA, NOON);
        for (double age = 5; age <= 20 * 60; age += 10) {
            double l = hiroshimaLight(p, 1420, age);
            assertTrue(l < 1e-3, "1,42 km, " + age + " s: light " + l);
        }
        assertTrue(hiroshimaLight(p, 1420, 30 * 60) > 1e-3, "1,42 km to 30 minute it’s still night: " + hiroshimaLight(p, 1420, 30 * 60));
        assertTrue(hiroshimaLight(p, 1420, 60 * 60) > 0.2, "1,42 km in an hour " + hiroshimaLight(p, 1420, 60 * 60));
        for (double age = 5; age <= 180; age += 5) {
            double l = hiroshimaLight(p, 3200, age);
            assertTrue(l > 1e-3 && l < 0.05, "3,2 km, " + age + " s: light " + l);
        }
        assertTrue(hiroshimaLight(p, 300, 30) < 1e-3, "it’s not immediately dark at the epicenter");
        assertTrue(hiroshimaLight(p, 300, 600) > 3e-3, "at the epicenter after 10 minutes it is still night: " + hiroshimaLight(p, 300, 600));
        assertTrue(p.visible(20 * 3600), "within an hour the dust had settled everywhere");
    }

    /** haze lasts over the burning city until the morning (time of day 0 - 6:00), but not less than 5 minutes. */
    @Test
    void hazeUntilMorning() {
        assertEquals(18000, PallField.hazeEndTicks(6000));
        assertEquals(6000, PallField.hazeEndTicks(18000));
        assertEquals(25000, PallField.hazeEndTicks(23000));
        assertEquals(24000, PallField.hazeEndTicks(0));
        assertEquals(24000 + 18000, PallField.hazeEndTicks(24000 * 3 + 6000) + 24000);
        PallField p = new PallField(GROUND, 18000);
        assertTrue(p.visible(5000));
        double[] v = new double[4];
        p.haze(1, 4000, v);
        assertTrue(v[0] > 0 && v[2] > 0, "at midnight the city burns");
        p.haze(1, 6100, v);
        assertEquals(0, v[0], 1e-12);
        // the dust hangs for hours (fine), but it also settles
        assertTrue(p.visible(40000), "half an hour later the dust had already settled");
        assertFalse(p.visible(20 * 3600 * 7), "via 7 pall hours available");
    }

    /** The world clock goes exactly from tick {@code from} to {@code to}: time of day is {@code day0} plus tick. */
    private static void clock(PallField p, long day0, int from, int to) {
        for (int t = from; t <= to; t++) p.daylight(t, t, day0 + t);
    }

    /** haze of the node at the center at the moment {@code ticks}, 1/block. */
    private static double haze(PallField p, double ticks) {
        double[] v = new double[4];
        p.haze(1, ticks, v);
        return v[0];
    }

    /** The clock runs smoothly - haze goes out in the morning after the tick has passed, as if without a clock; the clock is standing too. */
    @Test
    void hazeEndsAtMorningWithSteadyOrStoppedClock() {
        PallField p = new PallField(GROUND, 18000);
        clock(p, 18000, 0, 6200);
        assertTrue(haze(p, 4000) > 0);
        assertEquals(0, haze(p, 6100), 1e-12);
        PallField stopped = new PallField(GROUND, 14000);
        for (int t = 0; t <= 9000; t++) stopped.daylight(t, t, 14000);
        assertTrue(haze(stopped, 8000) > 0, "the clock stands - haze until the morning according to the past tick");
        assertEquals(0, haze(stopped, 10100), 1e-12);
    }

    /** Dream: the clock jumps towards sunrise - haze goes out in a minute, even before 5 minutes (the explosion in 5 morning was waiting for the next morning). */
    @Test
    void sleepingThroughTheNightEndsTheHaze() {
        PallField evening = new PallField(GROUND, 14000);
        clock(evening, 14000, 0, 3000);
        assertTrue(haze(evening, 3000) > 0);
        evening.daylight(3001, 3001, 24000);
        assertTrue(haze(evening, 3100) > 0, "does not go out abruptly");
        assertEquals(0, haze(evening, 3001 + PallField.HAZE_FADE_TICKS + 1), 1e-12);

        PallField dawn = new PallField(GROUND, 23000);
        clock(dawn, 23000, 0, 300);
        dawn.daylight(301, 301, 24000);
        assertEquals(0, haze(dawn, 301 + PallField.HAZE_FADE_TICKS + 1), 1e-12, "haze would wait for the next morning");
        // without sleep - rule 5 minutes: sunrise in a minute does not extinguish it, it goes out by the next morning
        PallField natural = new PallField(GROUND, 23000);
        clock(natural, 23000, 0, 3000);
        assertTrue(haze(natural, 3000) > 0);
        assertTrue(haze(natural, 24000) > 0);
    }

    /** /time set day night - day: haze goes out; /time add - morning is closer; the server sets the clock back to twilight - no. */
    @Test
    void timeCommandsMoveTheMorning() {
        PallField set = new PallField(GROUND, 18000);
        clock(set, 18000, 0, 400);
        set.daylight(401, 401, 1000);
        assertEquals(0, haze(set, 401 + PallField.HAZE_FADE_TICKS + 1), 1e-12);

        PallField add = new PallField(GROUND, NOON);
        clock(add, NOON, 0, 100);
        add.daylight(101, 101, NOON + 100 + 6000);
        clock(add, NOON + 6000, 102, 11000);
        assertTrue(haze(add, 10000) > 0);
        assertEquals(0, haze(add, 12100), 1e-12, "morning on 6000 tick closer");

        PallField lag = new PallField(GROUND, NOON);
        clock(lag, NOON, 0, 6100);
        lag.daylight(6101, 6101, NOON + 5500);
        clock(lag, NOON - 600, 6102, 7000);
        assertTrue(haze(lag, 17000) > 0, "adjusting the clock at dusk - not morning");
    }

    /**
     * The world after the first day: the clock of the world is 85000 (the evening of the fourth day), {@code /time set 18000} sets them as is, at
     *  midnight. Morning - the nearest sunrise according to the time of day (via 6000 tick), and not the morning of the fourth day according to the number of
     *  hours; {@code /time set day} - it’s already morning.
     */
    @Test
    void timeSetInALaterDayFollowsTheTimeOfDay() {
        long evening = 3 * PallField.DAY + 13000;
        PallField set = new PallField(GROUND, evening);
        clock(set, evening, 0, 100);
        set.daylight(101, 101, 18000);
        clock(set, 18000 - 101, 102, 6300);
        assertTrue(haze(set, 4800) > 0, "haze lasts until sunrise");
        assertEquals(0, haze(set, 101 + 6000 + 1), 1e-12, "sunrise through 6000 tick after /time set — haze went out");
        assertTrue(haze(new PallField(GROUND, evening), 101 + 6000 + 1) > 0, "without clock jump - until morning via 11000");

        PallField day = new PallField(GROUND, evening);
        clock(day, evening, 0, 100);
        day.daylight(101, 101, 1000);
        assertEquals(0, haze(day, 101 + PallField.HAZE_FADE_TICKS + 1), 1e-12, "/time set day - it’s already morning");
    }

    /** {@code /time add} until dawn: haze lasts 5 minutes after detonation (not yet a day), and does not go out within a minute. */
    @Test
    void timeAddToDawnKeepsFiveMinutes() {
        PallField p = new PallField(GROUND, NOON);
        clock(p, NOON, 0, 100);
        p.daylight(101, 101, 23600);
        clock(p, 23600 - 101, 102, 7000);
        assertTrue(haze(p, PallField.HAZE_MIN_TICKS - PallField.HAZE_FADE_TICKS - 1) > 0, "in 4 minutes haze still available");
        assertTrue(haze(p, PallField.HAZE_MIN_TICKS - 300) > 0, "goes out at the last minute, not before");
        assertEquals(0, haze(p, PallField.HAZE_MIN_TICKS + 1), 1e-12, "after 5 minutes it went out");
        // the clock then passes sunrise on its own - nothing changes
        assertTrue(haze(p, 2000) > 0);
    }

    /**
     * Rewind the repeat: morning - again from the moment of the explosion, and the first measurement of the clock at the recording site is compared with it: before sleep the darkness
     *  is there again, after sleep it is gone immediately (as it was in the recording), and does not go out again for a minute.
     */
    @Test
    void rewindFollowsTheClockOfThePlace() {
        PallField p = new PallField(GROUND, 14000);
        clock(p, 14000, 0, 3000);
        p.daylight(3001, 3001, 24000);
        assertEquals(0, haze(p, 5000), 1e-12);
        p.rewound();
        p.daylight(2000, 2000, 16000);
        assertTrue(haze(p, 2000) > 0, "before bed");
        p.rewound();
        p.daylight(5000, 5000, 26000);
        assertEquals(0, haze(p, 5000), 1e-12, "after sleep");
    }

    /**
     * Entered later (entering the world, changing dimensions): the time of day of the explosion is from the server, and the dream that he did not see is the same jump: a night strike, the players slept until the morning - haze is not there, and does not wait for the next morning (the time of day of the explosion according to
     *  today's clock would be 5:30 - less than 5 minutes before sunrise).
     */
    @Test
    void lateJoinerAfterSleepSeesTheMorning() {
        PallField late = new PallField(GROUND, 14000);
        late.daylight(2000, 2000, 24000 + 1500);
        assertEquals(0, haze(late, 2000), 1e-12);
        PallField guessed = new PallField(GROUND, 24000 + 1500 - 2000);
        guessed.daylight(2000, 2000, 24000 + 1500);
        assertTrue(haze(guessed, 2000) > 0, "at current times - until next morning");

        // /time set night to evening: the same sunrise as those who saw the jump
        PallField seen = new PallField(GROUND, NOON);
        clock(seen, NOON, 0, 1000);
        seen.daylight(1001, 1001, 13000);
        clock(seen, 13000 - 1001, 1002, 2000);
        PallField joined = new PallField(GROUND, NOON);
        joined.daylight(2000, 2000, 13000 + 999);
        for (double t : new double[]{2000, 9000, 11000, 12000}) assertEquals(haze(seen, t), haze(joined, t), 1e-12, "tick " + t);
    }

    /** The clock is standing ({@code doDaylightCycle false}): the time of day lags behind the game time - this is not a jump for those who enter later. */
    @Test
    void lateJoinerWithStoppedClock() {
        PallField late = new PallField(GROUND, 23000);
        late.daylight(9000, 9000, 23000);
        assertTrue(haze(late, 9000) > 0, "morning by past tick - via 25000");
        assertEquals(haze(new PallField(GROUND, 23000), 9000), haze(late, 9000), 1e-12);
    }

    /**
     * The client's clock ran ahead of the lagging server (together the game time and the time of day) - not a jump: detonation 10 seconds
     *  before sunrise, the package arrived when the client's clock was 15 seconds ahead, already after sunrise - haze is waiting for the next morning,
     *  like everyone else; adjusting the clock back later is also not a jump.
     */
    @Test
    void clientClockLeadIsNotAJump() {
        PallField p = new PallField(GROUND, 23800);
        p.daylight(0, 300, 24100);
        p.daylight(1, 1, 23801);
        clock(p, 23800, 2, 3000);
        assertTrue(haze(p, 3000) > 0, "until next morning");
        assertTrue(p.visible(20000));
    }

    /**
     * No fires (high detonation: no ignition on the ground) no haze - pall ends with dust, not in the morning: when it is visible,
     *  detonation does not depend on the time of day.
     */
    @Test
    void withoutFiresNoHazeUntilMorning() {
        Detonation high = new Detonation(3, new Vec3(0, 64 + 3000, 0), 64, 15, false, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
        PallField dawn = new PallField(high, 23000), dusk = new PallField(high, 12000);
        assertTrue(dawn.reach() > 100, "there is dust");
        assertEquals(0, haze(dawn, 20 * 300), 1e-12);
        assertTrue(dawn.visible(200));
        boolean ended = false;
        for (int t = 200; t < 20 * 3600 * 7; t += 200) {
            assertEquals(dusk.visible(t), dawn.visible(t), "tick " + t);
            ended |= !dawn.visible(t);
        }
        assertTrue(ended, "dust doesn't settle");
        assertTrue(new PallField(GROUND, 23000).visible(24000));
    }

    /** The attenuation at a point is the same as what the table gives (the shader calculates from it), and the wind blows away the dust. */
    @Test
    void sigmaFollowsTheTable() {
        PallField p = new PallField(GROUND, NOON);
        double ticks = 20 * 120;
        float[] row = new float[4 * PallField.ROWS * PallField.BINS];
        p.row(ticks, true, false, row);
        double[] w = new double[2];
        p.drift(ticks, w);
        int i = 30;
        double r = p.radius(i), k = p.share(i, ticks);
        // downwind from the epicenter, near the ground: the dust of the i node, carried to its share (haze also reaches here)
        double ux = Math.cos(GROUND.windDir()), uz = Math.sin(GROUND.windDir());
        assertTrue(k > 0.9 && k < 1, "dust of node i - from the arrival of the front: " + k);
        w[0] *= k;
        w[1] *= k;
        double s = p.sigma(w[0] + ux * r, w[1] + uz * r, 0, 0, ticks, PallField.Water.DRY);
        assertTrue(s >= row[4 * i] * 0.98, "dust on the ground " + s + " < tables " + row[4 * i]);
        assertTrue(s <= row[4 * i] + row[4 * (2 * PallField.BINS + 2)] * 1.01 + 1e-9);
        // above pall - nothing
        assertEquals(0, p.sigma(w[0] + ux * r, w[1] + uz * r, row[4 * i + 1] * 2 + 400, 0, ticks, PallField.Water.DRY), 1e-12);
        assertTrue(w[0] > 0 && w[1] > 0, "wind at 0,5 rad - drift along +x and +z");
    }

    /**
     * The wind carries dust from the moment it was picked up: the edge of the dust is at the front in any direction from the epicenter (there is also sound,
     * shaking, debris and debris), and is not shifted along the entire path of the wind from the explosion - in the wind the dust would rise before the arrival of the wave,
     *  against the wind - later. At the front, the dust is the head of the wall (a ring around the epicenter), behind it is the pall.
     */
    @Test
    void dustEdgeStaysOnTheFrontInWind() {
        PallField p = new PallField(GROUND, NOON);
        int i = 110;
        double ticks = 0.5 * (arrival(p, i) + arrival(p, i + 1));
        double[] w = new double[2];
        p.drift(ticks, w);
        assertTrue(Math.hypot(w[0], w[1]) > 2.5 * (p.radius(1) - p.radius(0)), "demolition from explosion - more than two nodes: " + Math.hypot(w[0], w[1]));
        // pall - dust older than one and a half positive phases in place
        int behind = i;
        while (behind > 0 && ticks - arrival(p, behind) < 20 * GROUND.scale() * 1.6 * p.layer(behind).positive()) behind--;
        assertTrue(behind < i && behind > i - 20, "pall for " + (i - behind) + " nodes");
        double ux = Math.cos(GROUND.windDir()), uz = Math.sin(GROUND.windDir());
        for (int side : new int[]{1, -1}) {
            double back = side * p.radius(behind), ahead = side * p.radius(i + 2);
            assertTrue(p.sigma(ux * back, uz * back, 0, 0, ticks, PallField.Water.DRY) > 0, "behind the front there is dust, side " + side);
            assertEquals(0, p.sigma(ux * ahead, uz * ahead, 0, 0, ticks, PallField.Water.DRY), 1e-12, "there is no dust in front of the front, side " + side);
        }
        // head - at the very front, in a ring around the epicenter
        BlastWall wall = new BlastWall(GROUND, false);
        double head = wall.wall(ticks / (20 * GROUND.scale())).head();
        assertTrue(head > p.radius(i) - 1 && head < p.radius(i + 1) + 1, "head on " + head + " at the front at the node " + i);
    }

    /** Less blow means less pall. */
    @Test
    void smallerYieldSmallerPall() {
        assertTrue(new PallField(SMALL, NOON).reach() < 0.6 * new PallField(GROUND, NOON).reach());
        double reach = new PallField(GROUND, NOON).reach();
        assertTrue(reach > 4500 && reach < 7500, "pall 15 kt to " + reach + " blocks");
    }

    /** The ashes do not fall before the waves, near the center - in a minute there is, paper - only where the houses collapsed. */
    @Test
    void ashAfterTheWave() {
        PallField p = new PallField(GROUND, NOON);
        assertEquals(0, p.ashRate(800, 0, 10, PallField.Water.DRY));
        assertTrue(p.ashRate(800, 0, 20 * 90, PallField.Water.DRY) > 0.1);
        assertTrue(p.paperRate(600, 0, 20 * 30, PallField.Water.DRY) > 0.3);
        assertEquals(0, p.paperRate(4500, 0, 20 * 30, PallField.Water.DRY), 1e-9);
        assertTrue(p.ashRate(1000, 0, 20 * 60 * 8, PallField.Water.DRY) > 0, "haze sprinkles ashes over the fires");
    }
    /**
     * There is no dust above the water - the spray, torn off by the wind behind the front: rises behind a wall of spray, thins out in half a minute and after three
     *  minutes falls out completely (the dust is still hanging there); at the edge of the pall the wind behind the front is weak - the spray does not disrupt.
     */
    @Test
    void sprayOverWaterGoneInMinutes() {
        assertEquals(0, PallField.sprayEnvelope(WaterModel.SPRAY_LAG), 1e-12, "up to the splash wall");
        assertTrue(PallField.sprayEnvelope(WaterModel.SPRAY_LAG + 3) > 0.85);
        assertEquals(0, PallField.sprayEnvelope(200), 1e-12, "after three minutes the water is clear");
        PallField p = new PallField(GROUND, NOON);
        double[] v = new double[4];
        int i = 20;
        p.spray(i, 20 * 30, v);
        assertTrue(v[0] > 0.2 * PallField.SIGMA_SPRAY && v[0] <= PallField.SIGMA_SPRAY, "splashes after half a minute " + v[0]);
        p.spray(i, 20 * 240, v);
        assertEquals(0, v[0], 1e-12, "splashes fell out");
        p.dust(i, 20 * 240, v);
        assertTrue(v[0] > 0, "and the dust still hangs");
        // at the edge of the pall there is little wind behind the front for splashes
        p.spray(PallField.BINS - 1, arrival(p, PallField.BINS - 1) + 20 * 10, v);
        assertEquals(0, v[0], 1e-12, "there is no splash at the edge of the pall");
        // splashes are thinner than the thickest dust and are white (their color and scattering are in the shader)
        assertTrue(PallField.SIGMA_SPRAY < PallField.SIGMA_DENSE);
    }

    /**
     * The splashes have their own layer with their own height: they rise to the height of the ridges falling off ({@link WaterModel#sprayHeight}), large drops
     *  fall out - the top drops to {@link PallField#SPRAY_SINK} it; It is not associated with dust and its growth over the heated ground.
     */
    @Test
    void sprayHasItsOwnHeight() {
        assertEquals(10, PallField.sprayTop(10, 0), 1e-12);
        assertEquals(10 * PallField.SPRAY_SINK, PallField.sprayTop(10, 1e4), 1e-9);
        double prev = 10;
        for (double age = 1; age < 300; age += 1) {
            assertTrue(PallField.sprayTop(10, age) < prev);
            prev = PallField.sprayTop(10, age);
        }
        PallField p = new PallField(GROUND, NOON);
        double[] dust = new double[4], spray = new double[4];
        int i = 20;
        double arrival = arrival(p, i);
        double wind = GROUND.groundWind(p.radius(i));
        for (double age : new double[]{5, 30, 90}) {
            p.dust(i, arrival + 20 * age, dust);
            p.spray(i, arrival + 20 * age, spray);
            assertEquals(Math.max(1, PallField.sprayTop(Math.max(1, GROUND.blocks(WaterModel.sprayHeight(wind))), age)), spray[1], 1e-9);
            assertTrue(spray[1] < dust[1], age + " with: splashes " + spray[1] + " no less than dust " + dust[1]);
        }
    }

    /**
     * What is not under pall ({@link PallGround#hasLand}, {@link PallGround#hasWater}) is not in the table: an explosion in the middle of the
     *  sea - without dust and haze, over land - without splashes; the top of the layers remains.
     */
    @Test
    void tableHoldsOnlyWhatIsUnderThePall() {
        PallField p = new PallField(GROUND, NOON);
        float[] row = new float[4 * PallField.ROWS * PallField.BINS];
        int dustRow = PallField.DUST_ROW * PallField.BINS, sprayRow = PallField.SPRAY_ROW * PallField.BINS, hazeRow = PallField.HAZE_ROW * PallField.BINS;
        p.row(20 * 120, true, false, row);
        boolean dust = false, haze = false;
        for (int i = 0; i < PallField.BINS; i++) {
            assertEquals(0, row[4 * (sprayRow + i)], 0, "splashes over land, knot " + i);
            dust |= row[4 * (dustRow + i)] > 0;
            haze |= row[4 * (hazeRow + i)] > 0;
        }
        assertTrue(dust && haze);
        p.row(20 * 30, false, true, row);
        boolean spray = false;
        for (int i = 0; i < PallField.BINS; i++) {
            assertEquals(0, row[4 * (dustRow + i)], 0, "dust over the sea, knot " + i);
            assertEquals(0, row[4 * (hazeRow + i)], 0, "haze over the sea, knot " + i);
            assertTrue(row[4 * (dustRow + i) + 1] > 0 && row[4 * (sprayRow + i) + 1] > 0, "top of layer, node " + i);
            spray |= row[4 * (sprayRow + i)] > 0;
        }
        assertTrue(spray);
    }

    /**
     * The substance is determined by the place from which it was raised: dust raised above land and blown by the wind onto the water remains dust; splashes,
     *  torn from the water and carried ashore - splashes. The share of water is taken at the point minus the drift - in the same place as the table.
     */
    @Test
    void materialComesFromWhereItWasLifted() {
        PallField p = new PallField(NO_FIRES, NOON);
        double ticks = 20 * 60;
        double[] w = new double[2];
        p.drift(ticks, w);
        assertTrue(w[0] > 150, "demolition by +x " + w[0]);
        // sea to +x from x = 400: raised to (300, 0) above land, carried to water
        PallField.Water east = (dx, dz) -> dx > 400 ? 1 : 0;
        double qx = w[0] + 300, qz = w[1];
        assertTrue(qx > 400, "point above water");
        double dust = p.sigma(qx, qz, 0, 0, ticks, PallField.Water.DRY);
        assertTrue(dust > 0);
        assertEquals(dust, p.sigma(qx, qz, 0, 0, ticks, east), 1e-12, "dust blown onto the water - dust");
        assertEquals(0, p.mist(qx, qz, 0, 0, ticks, east), 1e-12);
        // raised in (600, 0) above the water - splashes, no dust
        double mx = w[0] + 600;
        assertTrue(p.mist(mx, w[1], 0, 0, ticks, east) > 0);
        assertEquals(p.mist(mx, w[1], 0, 0, ticks, east), p.sigma(mx, w[1], 0, 0, ticks, east), 1e-12, "there is no dust above the water");
        // sea to −x from x = 100: raised to (50, 0) above the water, washed ashore
        PallField.Water west = (dx, dz) -> dx < 100 ? 1 : 0;
        double bx = w[0] + 50;
        assertTrue(bx > 100, "point above land");
        double mist = p.mist(bx, w[1], 0, 0, ticks, west);
        assertTrue(mist > 0, "splashes washed ashore - splashes");
        assertEquals(mist, p.sigma(bx, w[1], 0, 0, ticks, west), 1e-12);
    }

    /**
     * The base wave is only for a “water” explosion: it rises in seconds, grows and melts in minutes according to the laws of its clubs
     *  ({@link WaterModel}, model watch), weakening is apparently in it; rolls over the shore (this is water raised at the
     *  epicenter), the pall frame covers it.
     */
    /** In a world of a smaller scale, a wave is by the clock and the standards of its billows: model seconds - tick / (20·scale), blocks - × scale. */
    @Test
    void surgeFollowsTheWorldScale() {
        Detonation quarter = new Detonation(5, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 0.25f, false, 0.42f);
        PallField p = new PallField(quarter, NOON);
        p.water(1, WaterModel.wetBurst(0, 15));
        double[] s = new double[3];
        assertTrue(p.surge(20 * 30 * 0.25, s));
        assertEquals(0.25 * WaterModel.surgeRadius(30, 15), s[0], 1e-6, "radius at 30th second of the model");
        assertEquals(PallField.SIGMA_SURGE / 0.25 * WaterModel.surgeDensity(30, 15), s[2], 1e-9);
    }

    @Test
    void surgeOnlyForAWetBurst() {
        PallField p = new PallField(NO_FIRES, NOON);
        double[] s = new double[3];
        assertFalse(p.surge(20 * 60, s), "there are no waves on land");
        double wet = WaterModel.wetBurst(0, 15);
        assertEquals(1, wet, 1e-9);
        p.water(1, wet);
        assertFalse(p.surge(20 * 5, s), "the wave rises after ~7 s");
        assertTrue(p.surge(20 * 30, s));
        double r30 = s[0], sigma30 = s[2];
        assertEquals(WaterModel.surgeRadius(30, 15), r30, 1e-6, "radius - like billows");
        assertEquals(PallField.SIGMA_SURGE * WaterModel.surgeDensity(30, 15), sigma30, 1e-9);
        assertTrue(p.surge(20 * 300, s));
        assertTrue(s[0] > r30 + 500 && s[2] < 0.5 * sigma30, "through 5 minutes wider and less frequent: " + s[0] + " " + s[2]);
        assertFalse(p.surge(20 * 60 * 30, s), "melted after half an hour");
        assertTrue(p.surge(20 * 120, s));
        double r = s[0];
        assertTrue(p.extent(20 * 120) >= 1.05 * r, "frame covers the wave");
        assertTrue(p.visible(20 * 120));
        // over land at the edge of the wave (water under the pall is not important) - fog; beyond the edge - no
        double[] w = new double[2];
        p.drift(20 * 120, w);
        assertTrue(p.mist(w[0] + 0.9 * r, w[1], 0, 0, 20 * 120, PallField.Water.DRY) > 0.5 * s[2]);
        assertEquals(0, p.mist(w[0] + 1.1 * r, w[1], 0, 0, 20 * 120, PallField.Water.DRY), 1e-12);
        // half “watery” - half as often
        p.water(1, 0.5);
        assertTrue(p.surge(20 * 120, s));
        assertEquals(0.5 * PallField.SIGMA_SURGE * WaterModel.surgeDensity(120, 15), s[2], 1e-9);
    }

    /** There is no ashes, no paper, no haze above the water (there are no fires on the water): pall over the sea - only splashes. */
    @Test
    void noAshPaperOrHazeOverWater() {
        PallField p = new PallField(GROUND, NOON);
        PallField.Water sea = (dx, dz) -> 1;
        assertEquals(0, p.ashRate(800, 0, 20 * 90, sea), 1e-12);
        assertEquals(0, p.paperRate(600, 0, 20 * 30, sea), 1e-12);
        assertEquals(0, p.ashRate(1000, 0, 20 * 60 * 8, sea), 1e-12);
        double late = 20 * 60 * 8;
        assertTrue(p.sigma(1000, 0, 0, 0, late, PallField.Water.DRY) > 0, "over land haze and dust");
        assertEquals(0, p.sigma(1000, 0, 0, 0, late, sea), 1e-12, "over the sea the spray fell long ago");
    }
}
