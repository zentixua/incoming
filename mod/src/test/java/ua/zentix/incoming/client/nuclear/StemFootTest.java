package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.MushroomModel;
import ua.zentix.incoming.nuclear.model.StemDust;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The carpet of dust and stem of the fungus at its base is one field: medium weakening of the pall ({@link PallField#sigma}) and stem with
 *  suction ({@code mushMean}: stem, and where the suction is thicker - it is, {@link MushroomField#footDensity}) where the carpet goes into the
 *  leg. Previously, the stem stood on the flat top of the layer: at the wall above the top of the layer, the density dropped several times to half the height of the layer, and
 *  the surface of the dust rose vertically from the top of the carpet.
 */
class StemFootTest {
    private static final String GLSL = read("include/mushroom.glsl");
    private static final GlslFunction EDGE = GlslFunction.parse(GLSL, "mushEdge");

    private static String read(String path) {
        try (InputStream in = StemFootTest.class.getResourceAsStream("/assets/incoming/shaders/" + path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("//[^\n]*", "");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Detonation without wind (the field around the stem is a circle), scale 1: block - meter. */
    private static Detonation burst(double yieldKt, double hob) {
        return new Detonation(1, new Vec3(0, 64 + hob, 0), 64, yieldKt, hob <= 0, 0, 0, 0, 20_000, 7, 1, false, 1);
    }

    /** A moment of detonation: mushroom, pall and suction at the base. */
    private record Case(String name, Detonation d, PallField pall, MushroomModel.Shape s, double ticks, double[] foot) {
        static Case of(double yieldKt, double hob, double seconds) {
            Detonation d = burst(yieldKt, hob);
            PallField pall = new PallField(d, 6000);
            double ticks = 20 * seconds, front = d.metres(d.frontRadius(ticks));
            MushroomModel.Shape s = MushroomModel.shape(seconds, yieldKt, hob, d.surface(), Math.sqrt(Math.max(0, front * front - hob * hob)),
                    StemDust.of(yieldKt, hob, d.surface(), d.visibility()));
            double[] foot = new double[5];
            MushroomField.foot(d, pall, PallField.Water.DRY, s, ticks, foot);
            return new Case(String.format(Locale.ROOT, "%.0f kt on %.0f m, %.0f s", yieldKt, hob, seconds), d, pall, s, ticks, foot);
        }

        double top() {
            return foot[0];
        }

        double wall(double h) {
            return MushroomModel.stemRadius(h / s.capBottom(), s.stemR(), MushroomModel.dustFlare(s));
        }

        /** Weakening of the stem at the height of h without a fraction of the edge ({@code mushStemSigma} at the base: it is far from the top of the stem). */
        double stemSigma(double h) {
            return s.stemTau() / (2 * wall(h));
        }

        /** stem without suction - average according to billows ({@code mushMean}). */
        double stem(double rho, double h) {
            double r = wall(h);
            return stemSigma(h) * EDGE.call(Map.of("d", rho - r, "e", 0.5 * MushroomField.LOBES_STEM * r));
        }

        /** Suction at height h without edge share. */
        double footSigma(double h) {
            return MushroomField.footDensity(h, foot[0], foot[1], foot[2], Math.min(foot[3], s.stemTop()));
        }

        /** The suction is thicker than the stem itself - from the top of the layer to this part of it (in steps of a hundredth); 1 - there is no layer at the top. */
        double dense() {
            double u = 1;
            while (u < 20 && footSigma((u + 0.01) * top()) >= stemSigma((u + 0.01) * top())) u += 0.01;
            return u;
        }

        /** The middle of the suction strip behind the stem wall is at a height of h, until it tapers towards the stem - a flow line from the top of the carpet to the stem. */
        double sheet(double h) {
            return wall(h) + 0.5 * foot[2] * MushroomField.footWidth(Math.max(h, 0), foot[0]);
        }

        /** The strip still spans the entire width of the flow line (no more than three quarters of it) - from the thick part of the carpet to this height, blocks. */
        double full() {
            double h = 0.3 * top();
            while (h < 20 * top() && band(h + 0.01 * top()) >= 0.75 * foot[2] * MushroomField.footWidth(h + 0.01 * top(), foot[0])) h += 0.01 * top();
            return h;
        }

        /** Suction - average according to billows ({@code mushMean}: edge - at stem). */
        double band(double h) {
            return MushroomField.footBand(h, foot[0], foot[2], foot[4]);
        }

        double foot(double rho, double h) {
            double r = wall(h), at = r + band(h);
            return footSigma(h) * MushroomField.footWall(rho, r) * EDGE.call(Map.of("d", rho - at, "e", 0.5 * MushroomField.LOBES_STEM * r));
        }

        /** Dust at the base: carpet and stem, and where the suction is thicker than the stem - it. */
        double dust(double rho, double h, boolean withFoot) {
            return dust(rho, rho, h, withFoot);
        }

        /** The same with the carpet on {@code carpet} blocks from the axis. */
        double dust(double rho, double carpet, double h, boolean withFoot) {
            double stem = stem(rho, h);
            if (withFoot) stem = Math.max(stem, foot(rho, h));
            return pall.sigma(carpet, 0, h, 0, ticks, PallField.Water.DRY) + stem;
        }
    }

    private static final Case[] CASES = {Case.of(15, 0, 45), Case.of(15, 0, 60), Case.of(15, 0, 120), Case.of(15, 0, 240),
            Case.of(15, 592, 60), Case.of(15, 592, 120), Case.of(1, 0, 30), Case.of(1, 0, 90)};

    /** The light of the influx must be lost in its own dust above the top of the former carpet. */
    @Test
    void denseFootIncludesItsOwnOverburden() {
        for (Case c : CASES) {
            FootColumn column = new FootColumn(c.top(), c.foot[1], c.foot[2], Math.min(c.foot[3], c.s.stemTop()), c.foot[4]);
            double tau = column.above(c.top());
            assertTrue(tau >= 0);
            if (c.foot[4] > 1.5 * c.top() && c.foot[3] > 2 * c.top()) {
                assertTrue(tau > 0.3, c.name + ": raised dust above the top of the carpet must shade itself: " + tau);
            }
        }
    }

    /** Every moment has a suction: otherwise the checks below do not check anything. */
    @Test
    void everyCaseHasInflow() {
        for (Case c : CASES) {
            assertTrue(c.foot[2] > 0.3 && c.foot[1] > 0 && c.top() > 20, c.name + ": share " + c.foot[2] + ", top " + c.top());
        }
    }

    /**
     * Along the suction flow line (the middle of its strip behind the stem wall) from the thick part of the carpet (0,3 its top) upward until
     *  the suction is thicker than the stem: stem density with suction without jumps (per hundredth of the top of the layer - no more than 2 % of the carpet at the wall or at the
     *  beginning of the path; the carpet itself - the field is pall, it has no jumps), and all the dust is at least half the carpet at the beginning of the path
     *  or the stem itself at this height - whichever is less. Without suction, there is empty space above the top of the carpet (for the ground 15 kt in 45–60 s): the carpet and the stem are two
     *  bodies with a void between them. The path is not higher than the top of the layer under the dust front, which the suction carries upward with the air stem (at
     *  ground 15 kt in 45 s - 0,9 km): the front is the edge, and not the junction of the carpet and the stem. The carpet is in one place where the stripe is on
     *  at the top of the layer: the client’s pall table ({@link PallField#sigma}) is based on radius nodes, and the path, shifting toward the axis by
     *  dozens of blocks, would catch the steps of nodes that are not in the shader (the table there is an interpolated texture).
     */
    @Test
    void noDensityStepFromCarpetIntoStem() {
        int hollow = 0;
        for (Case c : CASES) {
            double top = c.top(), step = top / 100, from = 0.3 * top;
            double end = Math.min(Math.min(c.dense() * top, c.full()), Math.min(c.foot[3], c.s.stemTop()) - top);
            double carpet = c.sheet(top);
            double start = c.dust(c.sheet(from), carpet, from, true), prev = start, thinnestOld = Double.POSITIVE_INFINITY;
            for (double h = from + step; h <= end; h += step) {
                double now = c.dust(c.sheet(h), carpet, h, true), floor = 0.5 * Math.min(start, c.stemSigma(h));
                double carpetNow = c.dust(c.sheet(h), carpet, h, false) - c.stem(c.sheet(h), h), carpetPrev = c.dust(c.sheet(h - step), carpet, h - step, false) - c.stem(c.sheet(h - step), h - step);
                assertTrue(Math.abs((now - carpetNow) - (prev - carpetPrev)) <= 0.02 * Math.max(start, c.foot[1]),
                        c.name + String.format(Locale.ROOT, ": stem jump and suction on %.0f m (%.2f top): %.4f → %.4f", h, h / top, prev - carpetPrev, now - carpetNow));
                assertTrue(now >= floor, c.name + String.format(Locale.ROOT, ": on %.0f m (%.2f top) dust %.4f, less than %.4f", h, h / top, now, floor));
                thinnestOld = Math.min(thinnestOld, c.dust(c.sheet(h), carpet, h, false) / floor);
                prev = now;
            }
            System.out.printf(Locale.ROOT, "%s: top %.0f, σ %.3f, share %.2f, rise %.0f, merge %.2f top; way to %.2f top, without suction - %.3f threshold %n",
                    c.name, top, c.foot[1], c.foot[2], c.foot[3], c.foot[4] / top, end / top, thinnestOld);
            // in the late, shallow and early air carpets, the carpet is against the wall and touches the stem without suction - there was no void there
            if (thinnestOld < 0.1) hollow++;
        }
        // there was emptiness in the ground 15 kt in 45 and 60 s (the carpet against the wall in 4–7 times thicker than the stem - host frames) and in the air in 120 s
        assertTrue(hollow >= 3, "without suction there would be a void between the carpet and the stem " + hollow + " moments");
    }

    /**
     * The surface of the dust (where the average weakening is a fifth of the stem at this height) from the top of the carpet to the place where the suction is no longer thicker than the stem is behind the surface of the stem itself and is getting closer to it: the carpet rises to the stem with a smooth bell. At the
     *  top of the layer the bell is no less than a quarter of the top of the layer × the suction share, to the four tops it launches a rail exit into the surface of the leg
     *  without a ledge (at 0,05 of the top of the layer - no more than 0,1 it). Without suction, the surfaces coincided. In a ground-based
     *  detonation, the suction at the top of the layer is thicker than the stem (the carpet near the wall is thicker than it), while in the case of an air blast, it may not be: there the stem itself is
     *  thick, like a carpet.
     */
    @Test
    void carpetClimbsIntoTheStem() {
        for (Case c : CASES) {
            double top = c.top(), prev = Double.POSITIVE_INFINITY;
            for (double u = 1; u <= 4.001; u += 0.05) {
                double h = u * top, seen = 0.2 * c.stem(0, h);
                double excess = surface(c, h, seen, true) - surface(c, h, seen, false);
                // bell above the carpet - where the stem merges with the suction above one and a half tops of the layer (in the air and in the
                //  late weak carpet the merger is lower: there the carpet already touches the stem)
                if (u < 1.001 && c.foot[4] >= 1.5 * top) {
                    assertTrue(excess > 0.25 * c.foot[2] * top, c.name + String.format(Locale.ROOT, ": at the top of the layer there is a bell %.0f m", excess));
                }
                assertTrue(excess <= prev + 1e-6 * top, c.name + String.format(Locale.ROOT, ": on the %.2f top the bell is wider than below", u));
                // transitions into the stem instead of ending at a step: at 0.05 of layer height, no more than 0.1 of it (slope up to 2)
                assertTrue(prev == Double.POSITIVE_INFINITY || prev - excess <= 0.1 * top,
                        c.name + String.format(Locale.ROOT, ": at the top %.2f the bell breaks off: %.0f → %.0f m", u, prev, excess));
                prev = excess;
            }
            assertTrue(prev < 0.02 * top, c.name + ": on the four tops of the layer there is still a bell " + prev + " m");
        }
    }

    /**
     * stem away from the base - as without suction: above the height of fusion (with a margin of a tenth: on it the suction and stem are equal) and inside the stem (closer to 0,6 its radius to the axis) suction
     *  does not add anything; the original stem pattern holds ShaderSourceTest. Otherwise, the suction in its entirety stood inside the thickness of the carpet to the front of its dust - a ring across the
     *  leg - and the turn, changing with height, inclined its jets in a “herringbone” pattern (review of frames of the first option).
     */
    @Test
    void stemAwayFromTheFootIsAsBefore() {
        for (Case c : CASES) {
            double top = c.top(), merge = c.foot[4];
            for (double h = 0; h <= 6 * top; h += 0.05 * top) {
                double r = c.wall(h);
                for (double rho = 0; rho <= r + 2 * top; rho += 0.05 * r) {
                    boolean away = h >= 1.1 * merge || rho <= 0.6 * r;
                    if (away) {
                        assertTrue(Math.abs(c.dust(rho, h, true) - c.dust(rho, h, false)) <= 1e-12,
                                c.name + String.format(Locale.ROOT, ": suction on %.0f m, %.0f from the axis", h, rho));
                    }
                }
            }
        }
    }

    /** The different color and light of the two environments do not form a stage: at the entrance, the pure influx has the light of the carpet itself. */
    @Test
    void inflowRadianceJoinsTheCarpetBeforeItBecomesTheStem() {
        GlslFunction light = GlslFunction.parse(GLSL, "mushFootLightShare");
        for (Case c : CASES) {
            double top = c.top(), merge = c.foot[4], before = 1;
            for (double h = 0; h <= 5 * top; h += 0.002 * top) {
                double share = light.call(Map.of("h", h, "top", top, "merge", merge, "carried", 0.4, "total", 0.4));
                assertTrue(share >= 0 && share <= before + 1e-9, c.name + ": jump in carpet light share");
                if (h <= Math.min(top, 0.5 * merge)) {
                    // brown carpet and gray stem in different light: all the channels at the entrance are exactly carpet.
                    for (double[] colors : new double[][]{{0.42, 0.12}, {0.26, 0.20}, {0.10, 0.35}}) {
                        double radiance = colors[1] + (colors[0] - colors[1]) * share;
                        assertTrue(Math.abs(radiance - colors[0]) < 1e-12, c.name + ": different color/light at the entrance");
                    }
                }
                if (h >= merge) assertTrue(share == 0, c.name + ": carpet light above merge");
                before = share;
            }
        }
    }

    /** The movement map does not go inside the original stem, above the base and to the distant haze. */
    @Test
    void movingCarpetIsConfinedToTheInflow() {
        GlslFunction flow = GlslFunction.parse(GLSL, "mushFootInfluence");
        for (Case c : CASES) {
            double top = c.top(), merge = c.foot[4];
            for (double h = 0; h < 5 * top; h += 0.05 * top) {
                double r = c.wall(h);
                for (double rho = 0; rho < r + 3 * top; rho += 0.05 * r) {
                    double share = flow.call(Map.of("h", h, "rho", rho, "r", r, "top", top, "share", c.foot[2], "merge", merge));
                    assertTrue(Double.isFinite(share) && share >= 0 && share <= c.foot[2] + 1e-9);
                    if (rho <= 0.6 * r || h >= merge || rho >= r + 2 * top) assertTrue(share == 0, c.name + ": movement outside the tributary");
                }
            }
            double h = 0.2 * Math.min(top, merge), r = c.wall(h);
            assertTrue(flow.call(Map.of("h", h, "rho", r + 0.5 * top, "r", r, "top", top, "share", c.foot[2], "merge", merge)) > 0);
        }
    }

    /** The blocks are furthest from the axis, where the dust at the base at a height of h is not thinner than seen. */
    private static double surface(Case c, double h, double seen, boolean withFoot) {
        double far = c.wall(h) + 4 * c.top();
        for (double rho = far; rho > 0; rho -= 0.002 * c.top()) {
            if (c.dust(rho, h, withFoot) >= seen) return rho;
        }
        return 0;
    }
}
