package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.layer.PallPass;
import ua.zentix.incoming.nuclear.Detonation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Relief under the pall ({@link PallGround}): where its ray ends, behind which there is no world. */
class PallGroundTest {
    /** 15 CT near the ground at a height of 109, scale 1. */
    private static final Detonation GROUND = new Detonation(1, new Vec3(0, 109, 0), 109, 15, true, 0, 0.5f, 8, 20_000, 7, 1f, false, 0.42f);
    private static final Sightline.Heights UNKNOWN = (x, z) -> Double.NaN;
    /**
     * Half the side of the map of filling checks is below, the blocks: where the coast and the valley will lie are decided by the cells, and the pall frame (and with it the
     *  map) is moved by the laws of dust - the filling does not depend on them.
     */
    private static final double FILL_HALF = 6550;

    private static PallGround ground() {
        return new PallGround(GROUND, new PallField(GROUND, 6000));
    }

    /** The entire circle of measurements according to {@code heights} and filling. */
    private static void surveyAll(PallGround g, Sightline.Heights heights) {
        surveyAll(g, heights, null);
    }

    /** The entire circle of measurements for {@code heights} and {@code water} and filling. */
    private static void surveyAll(PallGround g, Sightline.Heights heights, FarTerrain.Water water) {
        for (int i = 0; i < PallGround.SIDE * PallGround.SIDE / PallGround.PER_TICK; i++) g.survey(0, 0, 0, heights, water);
        g.fill();
    }

    @Test
    void knowingNothingKeepsTheGroundUnderTheBurst() {
        PallGround g = ground();
        surveyAll(g, UNKNOWN);
        for (double x = -g.half(); x <= g.half(); x += g.half() / 8) {
            for (double z = -g.half(); z <= g.half(); z += g.half() / 8) assertEquals(0, g.at(x, z), 1e-6, x + " " + z);
        }
        PallPass.Ground out = new PallPass.Ground();
        g.frame(out);
        assertEquals(0, out.params.y(), 1e-6);
        assertEquals(0, out.params.z(), 1e-6);
        assertTrue(out.params.x() >= PallGround.MIN_CELL * PallGround.SIDE / 2);
        // and water: explosion on land - land everywhere
        assertFalse(g.hasWater());
        assertTrue(g.hasLand());
        assertEquals(0, g.water(1500, -800), 1e-6);
    }

    /**
     * We don’t know anything about the water under the pall - it’s all like under the epicenter ({@code NukeWater.under}): an explosion in the sea - above the
     *  sea, there is no land (dust and haze will not be in the table). The water under the epicenter has decided - the map is filled immediately, without waiting for the
     *  deadline: the column and the wave have already risen, the pall should not remain dust for a second.
     */
    @Test
    void unknownWaterIsWhatIsUnderTheBurst() {
        PallField pall = new PallField(GROUND, 6000);
        PallGround g = new PallGround(GROUND, pall);
        surveyAll(g, UNKNOWN);
        int version = g.version();
        pall.water(1, 1);
        g.survey(0, 0, 0, UNKNOWN, null);
        assertEquals(version + 1, g.version(), "water below the epicenter - immediately");
        assertTrue(g.hasWater());
        assertFalse(g.hasLand());
        for (double x = -g.half(); x <= g.half(); x += g.half() / 4) {
            for (double z = -g.half(); z <= g.half(); z += g.half() / 4) assertEquals(1, g.water(x, z), 1e-6, x + " " + z);
        }
        assertEquals(1, g.waterShare(), 1e-6);
    }

    /**
     * Shot {@code nuke-water}: an explosion at sea, the viewer above the water near the shore knows only the chunks around him - the shore across,
     *  the land behind him. The unknown is like the nearest known: the sea between the viewer and the explosion and behind the explosion is the sea (in the middle of everything seen, it would be half-dry, and there would be dust hanging over it), the shore continues in both directions (the farther, the wider the strip where it can be), the land behind is land.
     */
    @Test
    void coastContinuesAndOpenSeaStaysSea() {
        PallField pall = new PallField(GROUND, 6000);
        pall.water(1, 1);
        PallGround g = new PallGround(GROUND, pall, FILL_HALF);
        assertEquals(FILL_HALF, g.half(), 1e-9);
        // viewer in (−1500, 0), shore - x = −1540, sea to +x
        FarTerrain.Water shore = (x, z) -> Math.hypot(x + 1500, z) < 250 ? (x > -1540 ? 1 : 0) : Double.NaN;
        surveyAll(g, UNKNOWN, shore);
        assertTrue(g.hasWater());
        assertTrue(g.hasLand());
        assertEquals(1, g.water(-1300, 0), 0.02, "famous sea");
        assertEquals(0, g.water(-1700, 0), 0.02, "famous land");
        for (double[] p : new double[][]{{-800, 0}, {0, 0}, {1500, 300}, {-900, 1600}, {-900, -1600}, {2000, -2500}}) {
            assertEquals(1, g.water(p[0], p[1]), 0.02, "sea " + p[0] + " " + p[1]);
        }
        for (double[] p : new double[][]{{-3000, 0}, {-2500, 800}, {-2500, -800}, {-5000, 300}}) {
            assertEquals(0, g.water(p[0], p[1]), 0.02, "land " + p[0] + " " + p[1]);
        }
        // along the coast in the distance, where it is unknown, the land passes into the sea once (the share of water does not decrease), and not in spots; where exactly - according to the closest known, from how the shore lay on the cells
        for (double z : new double[]{1600, -1600}) {
            double prev = g.water(-3000, z);
            assertEquals(0, prev, 0.02, "land in the distance " + z);
            for (double x = -3000; x <= -900; x += 10) {
                double w = g.water(x, z);
                assertTrue(w >= prev - 1e-6, "shore in the distance " + x + " " + z + ": " + w + " after " + prev);
                prev = w;
            }
            assertEquals(1, prev, 0.02, "sea in the distance " + z);
        }
    }

    /**
     * Shot {@code nuke-lake}: explosion on land, a lake in front of the viewer. The unknown land around remains land: in the average
     *  of what was seen, the water of the lake would spread across the entire map, and above the land instead of dust there would be pale splashes.
     */
    @Test
    void aLakeByTheViewerDoesNotFloodTheLand() {
        PallField pall = new PallField(GROUND, 6000);
        PallGround g = new PallGround(GROUND, pall);
        // viewer in (0, −1500), lake 300 × 260 blocks in front of him (to detonation)
        FarTerrain.Water lake = (x, z) -> Math.hypot(x, z + 1500) >= 400 ? Double.NaN
                : Math.abs(x) < 150 && z > -1480 && z < -1220 ? 1 : 0;
        surveyAll(g, UNKNOWN, lake);
        assertTrue(g.hasWater());
        assertTrue(g.hasLand());
        assertTrue(g.water(0, -1350) > 0.9, "lake " + g.water(0, -1350));
        for (double[] p : new double[][]{{0, -700}, {0, 0}, {2000, 0}, {0, 2500}, {-2500, -1500}, {0, -2500}}) {
            assertEquals(0, g.water(p[0], p[1]), 0.02, "land " + p[0] + " " + p[1]);
        }
        assertTrue(g.waterShare() < 0.01, "water on the map " + g.waterShare());
    }

    /** Under the epicenter, the map knows the water itself (chunk at the explosion): what is known there - as it is, around - by it. */
    @Test
    void knownCellsKeepTheirWater() {
        PallField pall = new PallField(GROUND, 6000);
        pall.water(1, 1);
        PallGround g = new PallGround(GROUND, pall);
        // explosion near the coast: land to −x, sea to +x, known in 600 blocks
        FarTerrain.Water coast = (x, z) -> Math.hypot(x, z) < 600 ? (x > 0 ? 1 : 0) : Double.NaN;
        surveyAll(g, UNKNOWN, coast);
        assertEquals(0, g.water(-400, 0), 0.02);
        assertEquals(1, g.water(400, 0), 0.02);
        assertEquals(0, g.water(-3000, 200), 0.02, "the coast continues");
        assertEquals(1, g.water(3000, 200), 0.02);
        double share = g.waterShare();
        assertTrue(share > 0.4 && share < 0.6, "half of the map is sea: " + share);
    }

    /**
     * Camera in the valley in 400 blocks from the epicenter on the pillar (frame pall-low): the client knows only the chunks around him (the ground
     *  on 46 is lower than under the epicenter). The unknown is filled with the known around - in the distance the beam ends on the ground of the valley,
     *  and not on the plane under the epicenter; at the epicenter (the server knows its land), the map rises to it smoothly.
     */
    @Test
    void unknownTakesTheKnownAround() {
        PallGround g = new PallGround(GROUND, new PallField(GROUND, 6000), FILL_HALF);
        assertEquals(FILL_HALF, g.half(), 1e-9);
        Sightline.Heights valley = (x, z) -> Math.hypot(x + 400, z) < 192 ? 63 : Double.NaN;
        surveyAll(g, valley);
        assertEquals(-46, g.at(-400, 0), 1);
        for (double[] p : new double[][]{{-2000, 0}, {-1200, 1500}, {-400, -2500}}) assertEquals(-46, g.at(p[0], p[1]), 8, p[0] + " " + p[1]);
        assertTrue(g.at(0, 0) > -40, "at the epicenter - to its land: " + g.at(0, 0));
        // no steps: adjacent cells are nearby
        double cell = 2 * g.half() / PallGround.SIDE, worst = 0;
        for (double x = -g.half(); x < g.half() - cell; x += cell) worst = Math.max(worst, Math.abs(g.at(x + cell, 0) - g.at(x, 0)));
        assertTrue(worst < 15, "step " + worst);
    }

    /** The player leaves - the chunks are unloaded, the client no longer knows: the card remains as it was seen. */
    @Test
    void seenCellsStaySeen() {
        PallGround g = ground();
        Sightline.Heights hills = (x, z) -> 80 + 30 * Math.sin(x / 700.0) * Math.cos(z / 900.0);
        surveyAll(g, hills);
        double a = g.at(500, -300), b = g.at(-1500, 2000);
        surveyAll(g, UNKNOWN);
        assertEquals(a, g.at(500, -300), 1e-6);
        assertEquals(b, g.at(-1500, 2000), 1e-6);
    }

    /** The card is filled out again only when something new is learned, and no more than once every {@link PallGround#FILL_TICKS} tick. */
    @Test
    void refillsOnlyOnNewsAtMostOnceASecond() {
        PallGround g = ground();
        Sightline.Heights hills = (x, z) -> 80 + 30 * Math.sin(x / 700.0) * Math.cos(z / 900.0);
        surveyAll(g, hills);
        int version = g.version();
        for (int i = 0; i < 3 * PallGround.FILL_TICKS; i++) g.survey(0, 0, 0, hills, null);
        assertEquals(version, g.version(), "the same thing - without filling");
        g.fill();
        version = g.version();
        Sightline.Heights raised = (x, z) -> hills.at(x, z) + 10;
        for (int i = 1; i < PallGround.FILL_TICKS; i++) g.survey(0, 0, 0, raised, null);
        assertEquals(version, g.version(), "new ahead of schedule - waiting");
        g.survey(0, 0, 0, raised, null);
        assertEquals(version + 1, g.version(), "on time - completed");
    }

    /**
     * The map is on the pall at the source (table, trail haze) and the camera, and not for the entire demolition: after half an hour the dust was carried away behind the map,
     *  and the camera at the epicenter - the map is only for the grown trail; the camera went with the wind - the map is larger and covers it with a margin of
     *  (not wider than the entire pall), what is known remains in it.
     */
    @Test
    void growingKeepsWhatIsKnown() {
        PallGround g = new PallGround(GROUND, new PallField(GROUND, 6000));
        Sightline.Heights hills = (x, z) -> 80 + 30 * Math.sin(x / 700.0) * Math.cos(z / 900.0);
        surveyAll(g, hills);
        double half = g.half(), a = g.at(600, 400);
        PallField pall = new PallField(GROUND, 6000);
        double late = 20 * 1800;
        g.survey(late, 0, 0, UNKNOWN, null);
        double source = pall.sourceExtent(late);
        assertTrue(source > half, "trail haze has not grown beyond the map in half an hour");
        assertEquals(PallGround.GROW * source, g.half(), 1e-9, "camera at the epicenter: map - not on pall at the source");
        assertTrue(pall.extent(late) > 1.25 * g.half(), "after half an hour the dust had not blown off the map");
        double eye = 0.9 * pall.extent(late);
        g.survey(late, 0, eye, UNKNOWN, null);
        assertTrue(g.half() >= Math.min(pall.extent(late), eye + GROUND.blocks(PallGround.AROUND_EYE_M)), "wind camera - not on the map");
        assertTrue(g.half() <= PallGround.GROW * pall.extent(late) + 1e-9, "map wider than pall");
        assertEquals(a, g.at(600, 400), 6, "what is known remains (on larger cells)");
        assertEquals(80 + 30 * Math.sin(600 / 700.0) * Math.cos(400 / 900.0) - 109, g.at(600, 400), 6);
    }
}
