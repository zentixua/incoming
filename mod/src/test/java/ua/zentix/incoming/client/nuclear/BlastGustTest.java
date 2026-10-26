package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastGustTest {
    /** 15 kt near the ground at a height of 64, block - meter; wave like in life or in a game (0,2 sound). */
    private static Detonation ground(float wave) {
        return new Detonation(1, new Vec3(0, 64, 0), 64, 15, true, 0, 0, 0, 20_000, 7, 1f, false, wave);
    }

    private static final double[] RANGES = {150, 300, 600, 1000, 2000, 4000};

    /**
     * Wind - Friedlander in place: zero before the front, immediately behind it - the wind of the Rankine-Hugoniot model, zero towards the end of the phase and then
     * reverse; the path of the air is its integral (the same one that carries the dust of the head of the wall: the share of {@link BlastWall#carriedShare}), to the
     *  end of the phase U·t₊/e.
     */
    @Test
    void windIsFriedlanderAtThePlace() {
        Detonation d = ground(1);
        for (double r : RANGES) {
            Vec3 at = new Vec3(r, 65, 0);
            BlastGust g = BlastGust.of(d, at, () -> 0);
            assertEquals(0, g.speed(g.arrival - 1), 0, r + ": to the front");
            assertEquals(d.blastWind(at) / 20, g.speed(g.arrival + 1e-6), 1e-6 * g.u0 + 1e-9, r + ": just behind the front");
            assertEquals(0, g.speed(g.arrival + g.phase), 1e-12, r + ": end of phase");
            assertTrue(g.speed(g.arrival + 2 * g.phase) < 0, r + ": rarefaction phase");
            double end = g.u0 * g.phase * Math.exp(-1);
            assertEquals(end, g.path(g.arrival + g.phase), 1e-6 * end, r + ": path per phase");
            for (double x = 0.1; x <= 1; x += 0.1)
                assertEquals(BlastWall.carriedShare(x) * end, g.path(g.arrival + x * g.phase), 1e-6 * end, r + ": path like dust heads");
        }
    }

    /**
     * Nothing is ahead of the front of the wave: a piece of foliage and a board, torn off at rest near the place, fly with the wind (resistance according to its
     *  speed of fall, the wind is at the point of the piece, upward is the largest share) and does not go further than the front in the game - and with the game
     *  wave 0,2 of sound, where the real wind nearby is faster than the front.
     */
    @Test
    void piecesStayBehindTheFront() {
        for (float wave : new float[]{1f, 0.2f}) {
            Detonation d = ground(wave);
            for (double r : RANGES) {
                for (ua.zentix.incoming.client.fx.Fragments.Kind kind : new ua.zentix.incoming.client.fx.Fragments.Kind[]{
                        ua.zentix.incoming.client.fx.Fragments.Kind.LEAF, ua.zentix.incoming.client.fx.Fragments.Kind.BOARD}) {
                    double[] now = {0}, air = new double[3], v = new double[3], p = {r, 70, 0};
                    BlastGust g = BlastGust.of(d, new Vec3(r, 70, 0), () -> now[0]);
                    double vt = ua.zentix.incoming.client.fx.Fragments.terminal(kind, 600, 0.3) / 20, drag = ua.zentix.incoming.client.fx.Fragments.G / (vt * vt);
                    for (double t = Math.floor(g.arrival); t < g.arrival + 3 * g.phase; t++) {
                        now[0] = t;
                        g.at(p[0], p[1], p[2], air);
                        ua.zentix.incoming.client.fx.Fragments.step(v, air[0], ua.zentix.incoming.client.fx.Fragments.LIFT_EDGE * Math.max(0, air[1]), air[2], drag);
                        for (int i = 0; i < 3; i++) p[i] += v[i];
                        double slant = Math.sqrt(p[0] * p[0] + (p[1] - 64) * (p[1] - 64) + p[2] * p[2]);
                        assertTrue(slant <= d.frontRadius(t + 1) + 1e-6, wave + " " + r + " " + kind + " at " + t + ": " + slant + " > " + d.frontRadius(t + 1));
                    }
                }
            }
        }
    }

    /** The air path does not go further than the front in the game; the distance traveled does not decrease; Without a watch there is no wind blowing. */
    @Test
    void airPathBehindTheFront() {
        for (float wave : new float[]{1f, 0.2f}) {
            Detonation d = ground(wave);
            double[] into = new double[3];
            for (double r : RANGES) {
                Vec3 at = new Vec3(r, 65, 0);
                double slant = at.distanceTo(d.burst());
                double[] now = {0};
                BlastGust g = BlastGust.of(d, at, () -> now[0]);
                double travel = 0;
                for (double t = g.arrival; t < g.arrival + 4 * g.phase; t += g.phase / 50) {
                    assertTrue(slant + g.path(t) <= d.frontRadius(t) + 1e-6, wave + " " + r + " at " + t);
                    assertTrue(g.travel(t) >= travel - 1e-9, wave + " " + r + ": the path there and back does not decrease");
                    travel = g.travel(t);
                    now[0] = t;
                    double ahead = d.frontRadius(t) + 1;
                    g.at(ahead, 64, 0, into);
                    assertEquals(0, into[1], 0, wave + " " + r + ": there is no wind ahead of the front");
                }
            }
        }
        double[] into = new double[3];
        BlastGust.of(ground(1), new Vec3(1000, 65, 0), () -> Double.NaN).at(1000, 65, 0, into);
        assertEquals(0, into[1], 0, "the client has no explosion - no wind");
    }

    /**
     * A streak of dust breaks: the first dust is the farthest, on the path of the air from the arrival; the last one is at the break, while the phase is blowing;
     *  there is no dust before the front; the layer in which it dissolves is zero at the front and grows.
     */
    @Test
    void breachDustLiesAlongTheWind() {
        Detonation d = ground(1);
        Vec3 at = new Vec3(1000, 65, 0);
        BlastGust g = BlastGust.of(d, at, () -> 0);
        double[] head = new double[4], tail = new double[4];
        BlastDust.parcel(g, g.arrival, g.arrival - 5, head);
        assertEquals(0, head[0], 0, "to the front");
        for (double x = 0.2; x <= 1; x += 0.2) {
            double t = g.arrival + x * g.phase;
            BlastDust.parcel(g, g.arrival, t, head);
            BlastDust.parcel(g, t, t, tail);
            assertEquals(g.path(t), head[0], 1e-9, "the first dust is on the path of air");
            assertEquals(0, tail[0], 1e-9, "the last one is at the break");
            assertTrue(head[1] > 0 && head[3] > 0, "rises and blurs");
        }
        // the strip goes into the layer when the layer outgrows it: all of it - while there is no layer, half - when σ of the layer is equal to it, then
        //  to no longer; the share does not increase with the top of the layer
        assertEquals(1, BlastDust.apart(2, 0), 0);
        assertEquals(0.5, BlastDust.apart(2, 2 * Math.sqrt(12)), 1e-12);
        assertTrue(BlastDust.apart(2, 100) < 0.02);
        for (double top = 0; top < 200; top += 5) assertTrue(BlastDust.apart(2, top + 5) <= BlastDust.apart(2, top));
        double groundM = d.metres(1000);
        assertEquals(0, BlastDust.layerTop(d, groundM, 0), 1e-9);
        assertTrue(BlastDust.layerTop(d, groundM, 20) > 0 && BlastDust.layerTop(d, groundM, 100) > BlastDust.layerTop(d, groundM, 20));
    }
}
