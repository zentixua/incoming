package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.DustModel;
import ua.zentix.incoming.nuclear.model.FireballModel;
import ua.zentix.incoming.nuclear.model.HobBlast;
import ua.zentix.incoming.nuclear.model.MushroomModel;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastWallTest {
    /** 15 kt near the ground at a height of 64, without wind, on a scale 1 block = 4 m. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 0.25f, false, Detonation.OLD_WAVE);

    /**
     * The head is the dust immediately behind the front in the game (the front itself is not visible), the wedge is to the place where the positive phase in place
     *  has already ended (the arrival of the front in the game and the phase there is the moment t); head with transition to pall - not in a fireball on the ground
     *  (while the front is in the ball - there is no head, the ball has a shorter wedge, and the dust at its end is younger than the phase); thick up to ~1 psi, while the wind
     *  waves raises dust, and the fade disappears where there is nothing to raise (at 15 the kt is between 0,6 and 0,3 psi), - breaks away from the front.
     */
    @Test
    void headRidesTheFront() {
        BlastWall w = new BlastWall(GROUND, false);
        double scale = 20 * GROUND.scale(), firstEnd = GROUND.arrivalTicks(0) / scale + column(0).positive();
        int ends = 0, inBall = 0;
        for (double t = 0.05; t <= 20; t += 0.05) {
            assertEquals(w.shock(t), w.head(t), 1e-9, t + " s");
            double tail = w.tail(t), ball = w.underBall(t);
            double r = FireballModel.radius(t, GROUND.yieldKt(), true), y = FireballModel.centreHeight(t, 0, GROUND.yieldKt(), true);
            assertEquals(Math.sqrt(Math.max(0, r * r - y * y)), ball, 1e-9, t + " s: edge of the ball on the ground");
            double len = w.shock(t) - tail;
            if (ball > 0) len = Math.min(len, (w.shock(t) - ball) / BlastWall.HANDOFF_TO);
            assertEquals(Math.max(1e-3, len), w.length(t), 1e-9, t + " s: wedge - from the front to the end of the phase, transition to pall - to the edge of the ball");
            if (w.wall(t) != BlastWall.Wall.NONE) {
                // top - the layer where the wedge ends (at the edge of the ball - meters, and not the layer at the epicenter at 18 km)
                assertTrue(GROUND.metres(w.wall(t).height()) < 120, t + " s: top of wedge " + GROUND.metres(w.wall(t).height()) + " m");
                assertTrue(w.head(t) - BlastWall.HANDOFF_TO * w.length(t) >= ball - 1e-6, t + " s: transition of the head to pall - in the ball");
                if (ball > 0 && w.backAge(t) < 1) inBall++;
            }
            if (t < firstEnd) {
                assertEquals(0, tail, 1e-12, t + " s: the phase has not ended anywhere yet");
                continue;
            }
            double end = GROUND.arrivalTicks(GROUND.slantOf(GROUND.blocks(tail))) / scale + column(tail).positive();
            assertEquals(t, end, 0.01, t + " s: at the end of the wedge (" + tail + " m) the phase ended at " + end + " s");
            ends++;
        }
        assertTrue(ends > 300, "wedge end checks " + ends);
        assertTrue(inBall > 3, "the wedge is shorter than the phase at the edge of the ball - " + inBall + " checks");
        // while the front is in a fireball, there is no head; came out - dust right behind him
        assertEquals(BlastWall.Wall.NONE, w.wall(0.05), "head inside the ball");
        assertTrue(w.shock(0.05) < w.underBall(0.05));
        assertEquals(0, w.reach(0.05));
        assertTrue(w.wall(0.3).density() > 0, "there is no dust near the front itself");
        assertEquals(0, w.underBall(6), "the ball floated up but did not touch the ground");
        // dense (at least as opaque as the densest pall dust) up to ~1 psi: margin at 1.5 psi, thinner at 0.6;
        // fades out between 0.6 and 0.3 psi
        double[] s = new double[3];
        double t = 0.3;
        t = until(w, t, BlastModel.kpa(1.5));
        double dense = w.sigmas(t, s) / PallField.SIGMA_DENSE;
        assertTrue(dense > 1.5, "1,5 psi has only a head " + dense + " density pall");
        assertEquals(1, w.wall(t).density(), 1e-9);
        double thin = t;
        while (w.wall(thin).density() >= 1) thin += 0.01;
        double atThin = BlastModel.psi(GROUND.groundOverpressureKpa(GROUND.blocks(w.head(thin))));
        assertTrue(atThin < 1.5 && atThin > 0.6, "thick to " + atThin + " psi");
        t = until(w, thin, BlastModel.kpa(0.6));
        assertTrue(w.wall(t).density() > 0 && w.wall(t).density() < 1, "0,6 psi head " + w.wall(t).density());
        t = until(w, t, BlastModel.kpa(0.32));
        assertEquals(BlastWall.Wall.NONE, w.wall(t), "0,3 psi still has a head");
        assertEquals(0, w.reach(t));
    }

    /** The first moment with {@code from} (step 0,01 s), when the head reached the pressure of {@code kpa} on the ground. */
    private static double until(BlastWall w, double from, double kpa) {
        double range = GROUND.metres(GROUND.groundRange(kpa)), t = from;
        while (w.head(t) < range) t += 0.01;
        return t;
    }

    /**
     * The head top is the dusty boundary layer behind the fresh front (Kuhl et al. 1992, eq. 17; Mirels):
     * at the wedge end h = c·R^(1/6)·x^(5/6), where x is the model front path along its normal behind this point
     * at the local dust age, no older than its phase (Kuhl and Mirels have constant front speed, x = U_s·τ).
     * Under an airburst with regular reflection, this is the slant-range increment, not the front ground-trace
     * path (infinitely fast at the epicentre); in the Mach region it is the stem path. Behind a fresh front,
     * the layer starts at zero thickness and grows with path: metres at the epicentre, not kilometres.
     * Logarithmic time grid from 0,1 ms to 30 s, 1 kt, 15 kt and 1 Mt, ground, low and high airbursts: top follows
     * the law at in-game dust age and never exceeds the law with model arrival; no spikes, and while the
     * fireball is off the ground, the top does not sag (the wedge extends to the epicentre, and dust at its end
     * only ages; with a ground fireball it sags as the ball overtakes the head and touches the ground).
     */
    @Test
    void headTopIsTheBoundaryLayer() {
        // power, kt; detonation height, m; scale
        double[][] bursts = {{1, 0, 1}, {1, 28, 1}, {1, 240, 1}, {15, 0, 1}, {15, 0, 0.25}, {15, 83, 1}, {15, 592, 1}, {15, 592, 0.25},
                {1000, 0, 1}, {1000, 80, 1}, {1000, 333, 1}};
        for (double[] b : bursts) {
            double yield = b[0], hob = b[1];
            Detonation d = new Detonation(1, new Vec3(0, 64 + hob * b[2], 0), 64, yield, hob == 0, 0, 0, 0, 20_000, 7, (float) b[2], false, 1);
            BlastWall w = new BlastWall(d, false);
            String burst = yield + " kt on " + hob + " m, scale " + b[2] + ", ";
            double[] tops = new double[111];
            boolean[] ball = new boolean[tops.length];
            int heads = 0, ends = 0;
            for (int k = 0; k < tops.length; k++) {
                double t = 1e-4 * Math.pow(10, 0.05 * k);
                BlastWall.Wall wall = w.wall(t);
                tops[k] = Double.NaN;
                ball[k] = w.underBall(t) > 0;
                if (wall == BlastWall.Wall.NONE) continue;
                double top = d.metres(wall.height()), back = Math.max(0, w.head(t) - w.length(t));
                double positive = HobBlast.positivePhaseSeconds(back, hob, yield);
                // age of dust at the end of the wedge: from the arrival of the model front and from the arrival of the front in the game (by slant distance)
                double model = Math.min(t - HobBlast.arrivalSeconds(back, hob, yield), positive);
                double game = Math.min(t - d.arrivalTicks(d.slantOf(d.blocks(back))) / (20 * d.scale()), positive);
                double law = law(back, hob, yield, model), inGame = law(back, hob, yield, game);
                assertTrue(top <= 1.02 * law + 0.01, burst + t + " s: top " + top + " m above the boundary layer (" + law + " m)");
                assertEquals(inGame, top, 0.02 * inGame + 0.01, burst + t + " s: top is not the boundary layer at the dust age");
                if (w.backAge(t) >= 1) ends++;
                tops[k] = top;
                heads++;
            }
            assertTrue(heads > 20 && ends > 10, burst + "head on " + heads + " grid nodes, the wedge until the end of the phase is on " + ends);
            for (int k = 1; k < tops.length; k++) {
                double t = 1e-4 * Math.pow(10, 0.05 * k);
                // splash - the top is higher than both neighbors on the grid (×1,12 in time) by more than a quarter
                if (k + 1 < tops.length && !Double.isNaN(tops[k - 1] + tops[k] + tops[k + 1]))
                    assertTrue(tops[k] <= 1.25 * Math.max(tops[k - 1], tops[k + 1]) + 0.05,
                            burst + t + " s: splash top " + tops[k] + " m among " + tops[k - 1] + " and " + tops[k + 1]);
                // subsidence - the top is lower than at the previous node, more than 2% and 5 cm
                if (!Double.isNaN(tops[k - 1] + tops[k]) && !ball[k - 1] && !ball[k])
                    assertTrue(tops[k] >= 0.98 * tops[k - 1] - 0.05, burst + t + " s: the top sank from " + tops[k - 1] + " up to " + tops[k] + " m");
            }
        }
    }

    /** Dusty boundary layer at {@code groundM} m from the epicenter along the ground at dust age {@code seconds} s, m. */
    private static double law(double groundM, double hob, double yield, double seconds) {
        return DustModel.TOP_C * Math.pow(Math.max(1, Math.hypot(groundM, hob)), 1 / 6.0) * Math.pow(run(groundM, hob, yield, seconds), 5 / 6.0);
    }

    /**
     * The path of the front of the model along its normal for {@code seconds} s after it passed {@code groundM} m along the ground: how far
     *  during this time did its trace on the ground reach - dividing in half, the path along the normal - ∫√{@link HobBlast#alongGround} along the path
     *  of the trace with midpoints (as far as the front moves along its normal per meter of trace).
     */
    private static double run(double groundM, double hob, double yield, double seconds) {
        if (seconds <= 0) return 0;
        double target = HobBlast.arrivalSeconds(groundM, hob, yield) + seconds, lo = 0, hi = 1;
        while (HobBlast.arrivalSeconds(groundM + hi, hob, yield) < target) hi *= 2;
        for (int i = 0; i < 60; i++) {
            double m = 0.5 * (lo + hi);
            if (HobBlast.arrivalSeconds(groundM + m, hob, yield) < target) lo = m;
            else hi = m;
        }
        int n = 400;
        double step = 0.5 * (lo + hi) / n, path = 0;
        for (int i = 0; i < n; i++) path += Math.sqrt(HobBlast.alongGround(groundM + (i + 0.5) * step, hob, yield)) * step;
        return path;
    }

    /**
     * In the fireball there is no dust on the ground: the head with a transition to the pall and a stripe of splashes above the water is behind the edge of the ball
     * ({@link BlastWall#underBall}); the middle head in the ball is zero both above land and above water.
     */
    @Test
    void noHeadInTheFireball() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT];
        int checked = 0;
        for (double t = 0.1; t <= 6; t += 0.02) {
            double ball = GROUND.blocks(w.underBall(t));
            if (ball <= 0 || !w.slot(t, s) || s[3] <= 0) continue;
            assertTrue(s[0] - BlastWall.HANDOFF_TO * s[1] - s[5] >= ball - 0.01, t + " s: head or splash in the ball");
            // to the very edge - without it: there is the end of the transition to pall, and float of the slot gives 1e-10
            for (double r = 0; r < 0.999 * ball; r += 0.05 * ball) {
                for (double wet : new double[]{0, 0.5, 1}) {
                    for (double up : new double[]{-0.3, 0.05, 0.5})
                        assertEquals(0, BlastWall.sigma(s, r, 0, up * s[2], wet), t + " s: head inside the sphere at " + r + " blocks from the epicenter");
                }
            }
            checked++;
        }
        assertTrue(checked > 20, "ball checks " + checked);
    }

    /**
     * Wedge - laws of the dust layer behind the front: top u^(5/6) (Kuhl), raised - velocity impulse
     * ({@link DustModel#lifted}); the column above the ground - density near the ground × top - grows back as if raised, and the density at the ground
     *  is greatest at the edge (at u ≈ 0,1 - 2,6 times thicker than at the end of the wedge). Behind the wedge, the place for half a length goes to the pall.
     */
    @Test
    void wedgeLaws() {
        assertEquals(0, BlastWall.top(0));
        assertEquals(1, BlastWall.top(1), 1e-12);
        assertEquals(1, BlastWall.top(3), 1e-12);
        assertEquals(0, BlastWall.shape(0));
        assertEquals(1, BlastWall.shape(1), 1e-12);
        assertEquals(1, BlastWall.shape(2), 1e-12);
        double peak = 0, at = 0;
        for (double u = 0.001; u <= 1; u += 0.001) {
            assertEquals(DustModel.lifted(u), BlastWall.shape(u) * BlastWall.top(u), 1e-9, "pole not raised, u = " + u);
            if (BlastWall.shape(u) > peak) {
                peak = BlastWall.shape(u);
                at = u;
            }
        }
        assertEquals(2.6, peak, 0.15, "thickest at the edge");
        assertTrue(at > 0.05 && at < 0.2, "the thickest on " + at);
        assertEquals(1, BlastWall.handoff(1), 1e-12);
        assertEquals(0, BlastWall.handoff(BlastWall.HANDOFF_TO), 1e-12);
        assertEquals(0.5, BlastWall.handoff(0.5 * (BlastWall.HANDOFF_FROM + BlastWall.HANDOFF_TO)), 1e-12);
    }

    /** Layer {@link DustModel} of detonation {@link #GROUND} at {@code groundM} m from the epicenter. */
    private static DustModel.Column column(double groundM) {
        return DustModel.column(groundM, GROUND.hobMetres(), GROUND.yieldKt(), GROUND.surface(), GROUND.visibility(), DustModel.Surface.REFERENCE);
    }

    /**
     * The share of the path of dust with the wind behind the front ({@link BlastWall#carriedShare}) is the Friedlander particle velocity integral: from zero at the
     *  front, at the edge it grows at the speed of e (full wind behind the front), by the end of the phase the wind subsides (slope - zero), behind the phase
     *  the wind is reversed - the rarefaction phase returns dust to the epicenter.
     */
    @Test
    void dustIsCarriedOutByThePhaseAndBackByTheSuction() {
        assertEquals(0, BlastWall.carriedShare(0));
        assertEquals(0, BlastWall.carriedShare(-1));
        assertEquals(1, BlastWall.carriedShare(1), 1e-12);
        // rarefaction phase: the reverse wind returns the dust - to 2t₊ on the 0,74 path, to 5t₊ almost to its place (wave impulse - zero)
        assertEquals(2 / Math.E, BlastWall.carriedShare(2), 1e-12);
        assertEquals(5 * Math.exp(-4), BlastWall.carriedShare(5), 1e-12);
        for (double u = 1.05; u < 6; u += 0.05) assertTrue(BlastWall.carriedShare(u) < BlastWall.carriedShare(u - 0.05), "u = " + u);
        assertEquals(Math.E, BlastWall.carriedShare(1e-6) / 1e-6, 1e-4);
        assertEquals(0, (BlastWall.carriedShare(1) - BlastWall.carriedShare(1 - 1e-4)) / 1e-4, 1e-3);
        double prev = 0;
        for (double u = 0.01; u <= 1; u += 0.01) {
            double c = BlastWall.carriedShare(u);
            assertTrue(c > prev, "u = " + u);
            prev = c;
        }
    }

    /**
     * Complaint 18.10: The dust spread out from the epicenter, rather than falling from the ground behind the front. billows of the head - in the coordinates of the
     *  dust itself ({@link BlastWall#origin}, phase fraction - wedge fraction × {@code slot[8]}): at a place on the ground, while the head passes it
     *  (from the edge to the end of the transition to the pall), the billows place in the noise shifts less than half the path of the front during this time;
     *  across ring noise does not add up and is compressed no more than 1 + U/V times (U is the wind behind the front, V is the front in the game: at
     *  slow wave 0,2 - up to ~2,6); there is only one noise tile for the entire blast (growing headlong would move the billows along the ground).
     * The previous noise (in the tile at the front) traveled with the front - the shift would be along its entire path. Air 15 kt on 592 m on
     *  real and slow wave, ground 15 kt and 1 kt.
     */
    @Test
    void headCloudsStayWhereTheDustWasLifted() {
        Detonation[] bursts = {GROUND,
                new Detonation(1, new Vec3(0, 64, 0), 64, 1, true, 0, 0, 0, 20_000, 7, 1f, false, 1f),
                new Detonation(1, new Vec3(0, 64 + 592, 0), 64, 15, false, 0, 0, 0, 20_000, 7, 1f, false, 1f),
                new Detonation(1, new Vec3(0, 64 + 592, 0), 64, 15, false, 0, 0, 0, 20_000, 7, 1f, false, 0.2f)};
        for (Detonation d : bursts) {
            BlastWall w = new BlastWall(d, false);
            String burst = d.yieldKt() + " kt on " + d.hobMetres() + " m, wave " + d.wave() + ": ";
            float[] slot = new float[BlastWall.SLOT];
            double tile = -1, squeeze = 0;
            int places = 0;
            for (double place : new double[] {300, 800, 1400, 2500}) {
                double first = Double.NaN, last = Double.NaN, minO = Double.MAX_VALUE, maxO = -Double.MAX_VALUE;
                for (double t = 0.01; t < 120; t += 0.01) {
                    if (!w.slot(t, slot) || slot[3] <= 0) continue;
                    if (tile < 0) tile = slot[11];
                    assertEquals(tile, slot[11], 0, burst + t + " s: head noise tile changes");
                    double s = slot[0] - place;
                    if (s < 0 || s > BlastWall.HANDOFF_TO * slot[1]) continue;
                    if (Double.isNaN(first)) first = slot[0];
                    last = slot[0];
                    for (double z : new double[] {0, 0.5 * slot[2], slot[2]}) {
                        double o = BlastWall.origin(place, 0, z, s / slot[1] * slot[8], slot[2], slot[4])[0];
                        minO = Math.min(minO, o);
                        maxO = Math.max(maxO, o);
                        // the neighboring place is a meter closer to the epicenter: the noise does not add up, it is compressed to the extent of the wind against the front
                        double near = BlastWall.origin(place - 1, 0, z, (s + 1) / slot[1] * slot[8], slot[2], slot[4])[0];
                        assertTrue(o - near > 0, burst + place + " blocks, " + t + " s: noise collapsed");
                        squeeze = Math.max(squeeze, o - near);
                    }
                }
                if (Double.isNaN(first)) continue;
                places++;
                double moved = maxO - minO, front = last - first;
                assertTrue(moved < 0.5 * front, burst + place + " blocks: bills moved by " + moved + ", front - on " + front);
            }
            assertTrue(places >= 3, burst + "the head went almost nowhere");
            assertTrue(squeeze < (d.wave() < 0.5 ? 3 : 1.6), burst + "noise is compressed into " + squeeze + " times");
        }
    }

    /** The air path per phase at 15 kt at 592 m is tens of meters (wind 43 m/s behind the front at 1,4 km, phase 0,83 s - 13 m). */
    @Test
    void airRunIsTensOfMetres() {
        Detonation air = new Detonation(1, new Vec3(0, 64 + 592, 0), 64, 15, false, 0, 0, 0, 20_000, 7, 1f, false, 1f);
        BlastWall w = new BlastWall(air, false);
        float[] slot = new float[BlastWall.SLOT];
        for (double t = 1; t < 10; t += 1) {
            if (!w.slot(t, slot) || slot[3] <= 0) continue;
            assertTrue(slot[4] > 1 && slot[4] < 60, t + " s: air path " + slot[4]);
        }
    }

    /**
     * The slot numbers are those that the shader calculates: edge, length and top of the wedge, attenuation-scale and dust fractions at the edge and at the end of the wedge,
     *  tile; Wilson cloud - only in humid air.
     */
    @Test
    void slotCarriesTheHead() {
        BlastWall dry = new BlastWall(GROUND, false), humid = new BlastWall(GROUND, true);
        float[] s = new float[BlastWall.SLOT];
        assertTrue(dry.slot(3, s));
        BlastWall.Wall wall = dry.wall(3);
        assertEquals(wall.head(), s[0], 1e-3);
        assertEquals(wall.height(), s[2], 1e-3);
        assertEquals(GROUND.blocks(dry.length(3)), s[1], 1e-3);
        double[] sigmas = new double[3];
        double ref = dry.sigmas(3, sigmas);
        assertEquals(ref / GROUND.scale(), s[3], 1e-6 * s[3]);
        assertEquals(sigmas[0] / ref, s[14], 1e-6);
        assertEquals(sigmas[1] / ref, s[15], 1e-6);
        assertEquals(sigmas[2] / GROUND.scale(), s[7], 1e-6);
        assertEquals(1, Math.max(Math.max(s[14], s[15]), s[7] / s[3]), 1e-6, "scale - largest");
        // tile - at the largest top of the layer by the end of the phase: no smaller than at the top of the wedge now (constant -
        // headCloudsStayWhereTheDustWasLifted)
        assertTrue(s[11] >= BlastWall.TILE * s[2] - 1e-3, "tile " + s[11] + " smaller than a wedge " + s[2]);
        // the top of the wedge is tens of meters, like the dust layer at the end of the wave phase (Kuhl, GRABLE)
        assertTrue(GROUND.metres(s[2]) > 25 && GROUND.metres(s[2]) < 80, "top of the wedge " + GROUND.metres(s[2]) + " m");
        assertEquals(0, s[18], "wilson cloud in dry air");
        assertEquals(0, dry.wilsonReach(1));
        assertTrue(humid.slot(1, s));
        double front = GROUND.frontRadius(1 * 20 * GROUND.scale());
        assertEquals(BlastWall.WILSON_AT * front, s[16], 1e-3 * front);
        assertTrue(s[18] > 0);
        assertTrue(humid.wilsonReach(1) >= s[16] + s[17]);
        // wilson cloud melts in seconds
        humid.slot(5, s);
        assertEquals(0, s[18]);
    }

    /**
     * Middle head ({@link BlastWall#sigma}, copy of {@code pallWallAt} and {@code pallWallMean}): ahead of the edge of the dust and above the
     *  wedge - nothing; at the edge - a thin layer near the ground, thicker than at the end of the wedge; the pillar above the ground grows backwards; beyond one and a half
     *  wedge lengths - nothing (pall there); the ring is round.
     */
    @Test
    void meanHeadIsAWedge() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT];
        assertTrue(w.slot(4, s));
        double head = s[0], len = s[1], top = s[2], k = s[3];
        assertEquals(0, BlastWall.sigma(s, head + 2, 0, 0.1, 0));
        assertEquals(0, BlastWall.sigma(s, head - len, 0, 1.01 * top, 0));
        assertEquals(0, BlastWall.sigma(s, head - 0.1 * len, 0, 0.3 * top, 0), "at the edge above the wedge");
        assertTrue(BlastWall.sigma(s, head - 0.1 * len, 0, 0.02 * top, 0) > 1.5 * BlastWall.sigma(s, head - len, 0, 0.02 * top, 0), "it’s not thicker at the edge");
        assertEquals(0, BlastWall.sigma(s, head - 1.6 * len, 0, 0.1 * top, 0));
        double prev = 0;
        for (double back = 0.05; back <= 1; back += 0.05) {
            double tau = BlastWall.tau(s, head - back * len, 0, 0, Double.POSITIVE_INFINITY, 0);
            assertTrue(tau > prev, back + " length: the pillar does not grow back");
            prev = tau;
        }
        for (double up = -0.6; up <= 1.2; up += 0.05) {
            for (double back = -0.5; back <= 2; back += 0.02) {
                double v = BlastWall.sigma(s, head - back * len, 0, up * top, 0);
                assertTrue(v >= 0 && v <= 2.7 * k, up + ", " + back + ": " + v);
                assertEquals(v, BlastWall.sigma(s, 0, head - back * len, up * top, 0), 1e-9 * k, "the head is not round");
            }
        }
    }

    private static double luminance(float[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /**
     * The light of the frame, as {@code NukePall.light} considers it: at noon - the sky Minecraft (0,47, 0,65, 1 on the screen) and the sun
     *  overhead, at night - the sky light on the billows at night ({@link NukePall#nightSky}, on the screen 0,17), there is no sun.
     */
    private static NukePall.Sky sky(boolean day) {
        NukePall.Sky s = new NukePall.Sky();
        float ambient = day ? 1 : 0.17f;
        s.sy = day ? 1 : -1;
        s.day = day ? 1 : 0;
        float floor = (float) PallLight.linear(NukePall.nightSky(ambient, 1 - s.day));
        double[] colour = day ? new double[]{0.47, 0.65, 1} : new double[3];
        for (int c = 0; c < 3; c++) {
            s.sky[c] = NukePall.skyLight(colour[c], floor);
            s.sun[c] = NukePall.sunLight(1, s.sky[c], s.day);
        }
        s.base = ambient;
        s.exposure = (float) Sight.adapted(1, ambient);
        s.glowOnSmoke = (float) CloudPuffs.glowOnSmoke(ambient);
        return s;
    }

    /**
     * Dust near the camera - the color of the head there, as the frame shines on it ({@link NukePall#dustLight}). The head is a thick layer (visible at
     *  meters): light enters it thickly to the top of the wedge, to its front edge and from below. At night, fires have the bottom of the head of the earth not dark (it is illuminated by fires from below; according to the night light map, the dust would come out almost black) and the color of fire, but in the
     *  depth of the layer there is a shadow of its thickness. During the day, the top of the wedge is brown (red, followed by green, blue) in the light of the sun and sky, in depth there is a shadow; at night, too, as soon as the ball on the head stops blinding the eye accustomed to the dark (its light is about
     *  of a white wall; before, everything that it illuminates is overexposed).
     */
    @Test
    void nearDustTakesWallLight() {
        CloudPuffs puffs = new CloudPuffs(GROUND);
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT], glow = new float[8], c = new float[3], top = new float[3];
        double t = 0.5;
        while (GROUND.metres(w.wall(t).head()) < 1500) t += 0.05;
        slot(puffs, w, t, s, glow);
        double head = s[0], len = s[1], h = s[2];
        assertTrue(head < glow[3], "head at 1,5 km - not above fires");
        for (double u : new double[]{0.03, 0.1, 0.3}) {
            double here = h * BlastWall.top(u);
            for (double up : new double[]{0.005, 0.01}) {
                NukePall.dustLight(s, glow, sky(false), GROUND.windDir(), head - u * len, up * here, 0, up * here, c);
                assertTrue(luminance(c) > 0.2, u + " wedge, " + up + " top: at night the bottom of the head of fires is dark, " + luminance(c));
                assertTrue(c[0] > c[1] && c[1] > c[2], u + " wedge, " + up + " top: at night fires are not the color of fire, " + Arrays.toString(c));
            }
        }
        NukePall.dustLight(s, glow, sky(false), GROUND.windDir(), head - 0.3 * len, 0.2 * h * BlastWall.top(0.3), 0, 0.2 * h * BlastWall.top(0.3), c);
        assertTrue(luminance(c) < 0.01, "at night there is no shadow in the depths of the head above the fires: " + luminance(c));
        NukePall.dustLight(s, glow, sky(true), GROUND.windDir(), head - len, 0.9 * h, 0, 0.9 * h, top);
        assertTrue(top[0] > top[1] && top[1] > top[2], "during the day, the top of the head is not brown: " + Arrays.toString(top));
        assertTrue(luminance(top) > 0.5 && luminance(top) < 0.9, "head up during the day " + luminance(top));
        NukePall.dustLight(s, glow, sky(true), GROUND.windDir(), head - len, 0.1 * h, 0, 0.1 * h, c);
        assertTrue(luminance(top) > 3 * luminance(c), "during the day there is no shadow in the depths of my head: " + luminance(c) + " at the top " + luminance(top));
        // at night: the light of the ball on the head, the eyes accustomed to the darkness - already on the order of a white wall
        double night = sky(false).exposure;
        while (CloudPuffs.ball(0.2126 * glow[4] + 0.7152 * glow[5] + 0.0722 * glow[6], glow[0], Math.hypot(s[0], glow[7])) * night > 3) {
            t += 0.05;
            slot(puffs, w, t, s, glow);
        }
        assertTrue(t < 12, "the ball is blinding at night " + t + " s");
        head = s[0];
        len = s[1];
        h = s[2];
        NukePall.dustLight(s, glow, sky(false), GROUND.windDir(), head - len, 0.9 * h, 0, 0.9 * h, top);
        NukePall.dustLight(s, glow, sky(false), GROUND.windDir(), head - len, 0.1 * h, 0, 0.1 * h, c);
        assertTrue(luminance(top) > 2.5 * luminance(c), t + " s: no shadow deep inside the head at night: " + luminance(c) + " at the top " + luminance(top));
    }

    /** Wall and light of the ball and fires at 15 kt at the moment t: at {@code wall} and {@code glow}. */
    private static void slot(CloudPuffs puffs, BlastWall w, double t, float[] wall, float[] glow) {
        assertTrue(w.slot(t, wall));
        assertTrue(puffs.pallLight(MushroomModel.shape(t, 15, 0, true, GROUND.metres(w.wall(t).head())), glow));
    }

    /** The vertical thickness of the head ({@link BlastWall#tau}) is the integral of its weakening along the height. */
    @Test
    void verticalDepthIsTheIntegral() {
        BlastWall w = new BlastWall(GROUND, false);
        float[] s = new float[BlastWall.SLOT];
        assertTrue(w.slot(6, s) && s[3] > 0);
        double head = s[0], len = s[1], top = s[2];
        for (double back : new double[]{0.08, 0.3, 0.7, 1.2}) {
            double r = head - back * len, here = top * BlastWall.top(back);
            for (double eye : new double[]{-0.3 * top, 0, 0.4 * here}) {
                double up = 0, down = 0, ds = top / 20000;
                for (double z = eye + ds / 2; z < top; z += ds) up += BlastWall.sigma(s, r, 0, z, 0) * ds;
                for (double z = ds / 2 + Math.min(eye, 0); z < eye; z += ds) down += BlastWall.sigma(s, r, 0, z, 0) * ds;
                assertEquals(up, BlastWall.tau(s, r, 0, eye, Double.POSITIVE_INFINITY, 0), 0.02 * up + 1e-9, back + ", " + eye);
                if (eye > 0) assertEquals(down, BlastWall.tau(s, r, 0, 0, eye, 0), 0.02 * down + 1e-9, back + ", " + eye);
            }
        }
    }
}
