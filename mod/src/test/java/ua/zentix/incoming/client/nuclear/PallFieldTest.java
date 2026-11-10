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
        PallField p = new PallField(quarter);
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
        PallField p = new PallField(CALM);
        double[] v = new double[4];
        int inside = 0;
        for (double t = 0.3; t <= 8; t += 0.1) {
            double ball = FireballModel.groundRadius(t, 0, 15, true);
            for (int i = 0; i < PallField.BINS && CALM.metres(p.radius(i)) < ball; i++) {
                p.dust(i, 20 * t, v);
                assertEquals(0, v[0], t + " s: pall dust inside the sphere at " + CALM.metres(p.radius(i)) + " m");
                p.spray(i, 20 * t, v);
                assertEquals(0, v[0], t + " s: spray inside the sphere at " + CALM.metres(p.radius(i)) + " m");
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
        PallField p = new PallField(HIROSHIMA);
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
        PallField p = new PallField(HIROSHIMA);
        PallField small = new PallField(new Detonation(8, new Vec3(0, 64 + 592 * Math.cbrt(1 / 15.0), 0), 64, 1, false, 0, 0, 0, 20_000, 7, 1f,
                false, 1f, Detonation.NO_DAY_TIME, false));
        double[] v = new double[4];
        int i = (int) (1000 / p.reach() * PallField.BINS);
        double arrival = arrival(p, i), prev = 0;
        for (double age = 1.5; age <= 1800; age += 0.5) {
            p.dust(i, arrival + 20 * age, v);
            assertTrue(v[1] >= prev - 1e-9, age + " s: the top of the dust has dropped");
            prev = v[1];
        }
        p.dust(i, arrival + 20 * 5, v);
        assertTrue(v[1] > 60 && v[1] < 150, "to 1 km after 5 s top " + v[1]);
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
        PallField p = new PallField(GROUND);
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
     * ({@link DustModel.Column#depth}, as saltation develops behind the front — {@link BlastWall#develop}) everywhere behind the front:
     *  no gap, no double dust at the junction. Raised in a wedge - along a straight line
     *  between its edge and end (at 1 km the layer towards the epicenter grows faster than a straight line - up to +18%), at the junction - up to −11%.
     */
    @Test
    void headAndPallAreOneLayer() {
        PallField p = new PallField(CALM);
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
                // dust only: fire haze grows from the flash on its own clock (FireStorm.growth), not part of the wave layer
                double pall = p.dustTau(r, 0, ticks, PallField.Water.DRY);
                // the head rises with saltation development behind the front (BlastWall.develop): the layer as it develops
                double layer = p.layer(i).depth(t - arrival / 20) * BlastWall.develop(u, s[9], s[10]);
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
        PallField p = new PallField(GROUND);
        double ticks = GROUND.arrivalTicks(1400) + 20 * 10;
        assertTrue(p.sigma(1400, 0, 2, 0, ticks, PallField.Water.DRY) > 0);
        assertEquals(p.sigma(1400, 0, 2, 0, ticks, PallField.Water.DRY), p.sigma(1400, 0, 40, 38, ticks, PallField.Water.DRY), 1e-12);
        assertEquals(p.sigma(1400, 0, -20, 0, ticks, PallField.Water.DRY), p.sigma(1400, 0, -20, -30, ticks, PallField.Water.DRY), 1e-12);
    }

    /** Vertical thickness ({@link PallField#tau}) - the integral of the attenuation along the height: above the eye and below it to the ground, and on the hill. */
    @Test
    void verticalDepthIsTheIntegral() {
        PallField p = new PallField(GROUND);
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

    /**
     * Ground light under the Hiroshima pall at {@code metres} m from the epicenter, {@code age} s after the front.
     */
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
        PallField p = new PallField(HIROSHIMA);
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

    /** Haze of the node at the centre at {@code ticks} client ticks, 1/block. */
    private static double haze(PallField p, double ticks) {
        double[] v = new double[4];
        p.haze(1, ticks, v);
        return v[0];
    }

    /**
     * Fire haze lives the city's burning on the detonation clock ({@link PallField#clock}): hours on real time (a stopped day,
     * a day-length mod), gone once the fires are out ({@link FireStorm#OUT_S}); the dust settles on its own real time.
     */
    @Test
    void hazeBurnsItsHoursOnTheDetonationClock() {
        PallField p = new PallField(GROUND);
        assertTrue(haze(p, 20 * 600) > 0, "10 minutes: the city burns");
        assertTrue(haze(p, 20 * 6 * 3600) > 0, "6 hours: still smouldering");
        assertEquals(0, haze(p, 20 * (FireStorm.OUT_S + 1)), 1e-12, "burnt out");
        assertTrue(p.visible(20 * 6 * 3600));
        assertFalse(p.visible(20 * (FireStorm.OUT_S + 7200)), "fires out, dust settled");
    }

    /**
     * A vanilla day (the clock 72 times real time after the show) burns the same hours in minutes; a sleep moves the clock on
     * and the haze with it. The picture depends only on the clock, never on the time of day: one law for all.
     */
    @Test
    void hazeFollowsTheClockNotTheTimeOfDay() {
        PallField real = new PallField(GROUND), fast = new PallField(GROUND);
        fast.clock(t -> t <= 20 * 1200 ? t : 20 * 1200 + (t - 20 * 1200) * 72);
        for (double hours : new double[]{0.5, 2, 6, 11}) {
            double clockTicks = 20 * hours * 3600, realTicks = 20 * 1200 + (clockTicks - 20 * 1200) / 72;
            assertEquals(haze(real, clockTicks), haze(fast, realTicks), 1e-9 * haze(real, clockTicks) + 1e-15, hours + " h");
        }
        PallField slept = new PallField(GROUND);
        slept.clock(t -> t < 20 * 1800 ? t : t + 20 * 13 * 3600);
        assertTrue(haze(slept, 20 * 1700) > 0, "before the sleep");
        assertEquals(0, haze(slept, 20 * 1900), 1e-12, "slept through the night: the fires are out");
    }

    /** No fires (high detonation: no ignition on the ground): no haze, pall ends with the dust. */
    @Test
    void withoutFiresPallEndsWithTheDust() {
        Detonation high = new Detonation(3, new Vec3(0, 64 + 3000, 0), 64, 15, false, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
        PallField p = new PallField(high);
        assertTrue(p.reach() > 100, "there is dust");
        assertEquals(0, haze(p, 20 * 300), 1e-12);
        assertTrue(p.visible(200));
        boolean ended = false;
        for (int t = 200; t < 20 * 3600 * 7 && !ended; t += 200) ended = !p.visible(t);
        assertTrue(ended, "dust doesn't settle");
    }

    /** The attenuation at a point is the same as what the table gives (the shader calculates from it), and the wind blows away the dust. */
    @Test
    void sigmaFollowsTheTable() {
        PallField p = new PallField(GROUND);
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
        PallField p = new PallField(GROUND);
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
        assertTrue(new PallField(SMALL).reach() < 0.6 * new PallField(GROUND).reach());
        double reach = new PallField(GROUND).reach();
        assertTrue(reach > 4500 && reach < 7500, "pall 15 kt to " + reach + " blocks");
    }

    /** The ashes do not fall before the waves, near the center - in a minute there is, paper - only where the houses collapsed. */
    @Test
    void ashAfterTheWave() {
        PallField p = new PallField(GROUND);
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
        PallField p = new PallField(GROUND);
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
        PallField p = new PallField(GROUND);
        double[] dust = new double[4], spray = new double[4];
        int i = 20;
        double arrival = arrival(p, i);
        double wind = GROUND.groundWind(p.radius(i));
        for (double age : new double[]{5, 30, 90}) {
            p.dust(i, arrival + 20 * age, dust);
            p.spray(i, arrival + 20 * age, spray);
            assertEquals(Math.max(1, PallField.sprayTop(Math.max(1, GROUND.blocks(WaterModel.sprayHeight(wind))), age)), spray[1], 1e-9);
            assertTrue(spray[1] < dust[1], age + " s: splashes " + spray[1] + " no less than dust " + dust[1]);
        }
    }

    /**
     * What is not under pall ({@link PallGround#hasLand}, {@link PallGround#hasWater}) is not in the table: an explosion in the middle of the
     *  sea - without dust and haze, over land - without splashes; the top of the layers remains.
     */
    @Test
    void tableHoldsOnlyWhatIsUnderThePall() {
        PallField p = new PallField(GROUND);
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
        PallField p = new PallField(NO_FIRES);
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
     * The base wave is only for a “water” explosion: it rises in seconds, grows and melts in minutes according to the laws of its billows
     *  ({@link WaterModel}, model watch), weakening is apparently in it; rolls over the shore (this is water raised at the
     *  epicenter), the pall frame covers it.
     */
/** In a world of a smaller scale, a wave is by the clock and the standards of its billows: model seconds - tick / (20·scale), blocks - × scale. */
    @Test
    void surgeFollowsTheWorldScale() {
        Detonation quarter = new Detonation(5, new Vec3(0, 64, 0), 64, 15, true, 0, 0.5f, 8, 20_000, 7, 0.25f, false, 0.42f);
        PallField p = new PallField(quarter);
        p.water(1, WaterModel.wetBurst(0, 15));
        double[] s = new double[3];
        assertTrue(p.surge(20 * 30 * 0.25, s));
        assertEquals(0.25 * WaterModel.surgeRadius(30, 15), s[0], 1e-6, "radius at 30th second of the model");
        assertEquals(PallField.SIGMA_SURGE / 0.25 * WaterModel.surgeDensity(30, 15), s[2], 1e-9);
    }

    @Test
    void surgeOnlyForAWetBurst() {
        PallField p = new PallField(NO_FIRES);
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
        PallField p = new PallField(GROUND);
        PallField.Water sea = (dx, dz) -> 1;
        assertEquals(0, p.ashRate(800, 0, 20 * 90, sea), 1e-12);
        assertEquals(0, p.paperRate(600, 0, 20 * 30, sea), 1e-12);
        assertEquals(0, p.ashRate(1000, 0, 20 * 60 * 8, sea), 1e-12);
        double late = 20 * 60 * 8;
        assertTrue(p.sigma(1000, 0, 0, 0, late, PallField.Water.DRY) > 0, "over land haze and dust");
        assertEquals(0, p.sigma(1000, 0, 0, 0, late, sea), 1e-12, "over the sea the spray fell long ago");
    }

    /** The pall over hard ground where dust was lofted holds only debris dust ({@link PallField#soil}): thinner, never thicker. */
    @Test
    void pallThinsOverHardGround() {
        PallField p = new PallField(CALM);
        double ticks = CALM.arrivalTicks(CALM.blocks(800)) + 20 * 30;
        PallField.Water hard = new PallField.Water() {
            @Override
            public double at(double dx, double dz) {
                return 0;
            }

            @Override
            public double hard(double dx, double dz) {
                return 1;
            }
        };
        double soil = p.sigma(CALM.blocks(800), 0, 2, 0, ticks, PallField.Water.DRY), rock = p.sigma(CALM.blocks(800), 0, 2, 0, ticks, hard);
        assertTrue(soil > 0 && rock < 0.3 * soil, soil + " vs " + rock);
    }
}
