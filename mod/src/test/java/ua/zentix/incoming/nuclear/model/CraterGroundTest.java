package ua.zentix.incoming.nuclear.model;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CraterGroundTest {
    /**
     * A city on a slope: most chunks are dominated by buildings 10–60 blocks tall, a few are streets and yards. The plane
     * follows the slope under the city; the buildings do not lift it (Zearth 08.11.2026: the bowl was cut from roof
     * gardens 57 blocks above the ground).
     */
    @Test
    void groundUnderACityKeepsTheSlope() {
        Random random = new Random(5);
        int n = 200;
        double[] x = new double[n], z = new double[n], h = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = random.nextDouble() * 300 - 150;
            z[i] = random.nextDouble() * 300 - 150;
            double ground = 64 + 0.1 * x[i] - 0.05 * z[i] + random.nextDouble();
            // two thirds of the chunks have no street at their low tenth: their sample is a roof
            h[i] = i % 3 == 0 ? ground : ground + 10 + random.nextDouble() * 50;
        }
        double[] p = CraterGround.fit(x, z, h, n);
        assertEquals(0.1, p[0], 0.01, "slope along x");
        assertEquals(-0.05, p[1], 0.01, "slope along z");
        assertEquals(64.5, p[2], 1, "height at the epicentre");
    }

    /**
     * Pits and ditches in the bowl (a sunken yard, a canal) may pull the plane down, never up past the ground: it is the
     * lowest the ground can be, where {@link CraterGround#base} starts looking for a building's foundation. A plane above
     * the ground would leave buildings standing below it unchecked.
     */
    @Test
    void ditchesKeepThePlaneUnderTheGround() {
        Random random = new Random(8);
        int n = 240;
        double[] x = new double[n], z = new double[n], h = new double[n], ground = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = random.nextDouble() * 300 - 150;
            z[i] = random.nextDouble() * 300 - 150;
            ground[i] = 64 - 0.08 * x[i] + 0.03 * z[i] + random.nextDouble();
            h[i] = switch (i % 6) {
                case 0, 1 -> ground[i] + 10 + random.nextDouble() * 50;
                case 2 -> ground[i] - 8;
                default -> ground[i];
            };
        }
        double[] p = CraterGround.fit(x, z, h, n);
        for (int i = 0; i < n; i++) {
            double plane = CraterGround.at(p, x[i], z[i]);
            assertTrue(plane <= ground[i] + 1, "plane above the ground at " + i + ": " + plane + " > " + ground[i]);
            assertTrue(plane >= ground[i] - 8 - CraterGround.BAND, "plane under the ditches at " + i + ": " + plane);
        }
    }

    @Test
    void fewSamplesGiveALevelPlane() {
        double[] p = CraterGround.fit(new double[] {0, 5}, new double[] {0, 5}, new double[] {70, 64}, 2);
        assertEquals(0, p[0], 0);
        assertEquals(0, p[1], 0);
        assertEquals(64, p[2], 0, "lowest median of two");
        p = CraterGround.fit(new double[] {-40, -20, 0, 20, 40}, new double[] {0, 0, 0, 0, 0}, new double[] {60, 62, 64, 66, 68}, 5);
        assertEquals(0, p[0], 0, "five chunks give a level plane");
        assertEquals(64, p[2], 0);
    }

    /** The chunk sample is one column at the low tenth: a ditch is not the ground, and the index points at that column. */
    @Test
    void lowColumnIsAColumnAtTheLowTenth() {
        int[] heights = {90, 63, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64, 64};
        int k = CraterGround.lowColumn(heights, heights.length);
        assertEquals(2, k);
        assertEquals(90, heights[0], "heights are not reordered");
    }

    private static CraterGround.Column column(String layers, int bottom) {
        // one letter per block from {@code bottom} up: g ground, a air, w liquid, b built; ground below, air above
        return y -> {
            int i = y - bottom;
            if (i < 0) return CraterGround.Layer.GROUND;
            if (i >= layers.length()) return CraterGround.Layer.AIR;
            return switch (layers.charAt(i)) {
                case 'g' -> CraterGround.Layer.GROUND;
                case 'w' -> CraterGround.Layer.LIQUID;
                case 'b' -> CraterGround.Layer.BUILT;
                default -> CraterGround.Layer.AIR;
            };
        };
    }

    /** A Zearth tower: terracotta floors over rooms every four blocks and a roof garden; its ground is the street's. */
    @Test
    void terracottaFloorsStandOnTheGroundBelow() {
        // ground to 63, rooms 64–66, floors at 67, 71, 75, roof garden 79–81
        assertEquals(64, CraterGround.base(column("aaagaaagaaagaaaggg", 64), 82, 64, -64));
    }

    /**
     * A Zearth office: storeys of slabs, stairs and glass over a ground-floor hall, under a roof garden of six blocks of
     * terracotta (thicker than {@link CraterGround#FLOOR}, thinner than the storeys under it).
     */
    @Test
    void storeysUnderAThickRoofGardenStandOnTheGround() {
        String storey = "bb" + "aaaaaa";
        assertEquals(64, CraterGround.base(column("aaaaaaaa" + storey.repeat(5) + "gggggg", 64), 118, 63.8, -64));
    }

    /** A Zearth podium: a smooth stone slab under solid terracotta; the slab is built, so the podium stands on 64. */
    @Test
    void solidPodiumOnASlabIsABuilding() {
        assertEquals(64, CraterGround.base(column("b" + "g".repeat(16), 64), 81, 64.3, -64));
    }

    /** A hill or a knoll of ground keeps its own surface, as before the survey; so does a column not above the plane. */
    @Test
    void naturalReliefKeepsItsSurface() {
        assertEquals(71, CraterGround.base(column("ggggggg", 64), 71, 64, -64), "hill");
        assertEquals(76, CraterGround.base(column("aaa" + "g".repeat(13), 60), 76, 64, -64), "thick roof over a cave");
        assertEquals(64, CraterGround.base(column("", 64), 64, 64, -64), "level ground");
        assertEquals(60, CraterGround.base(column("", 60), 60, 64, -64), "lake bed under the plane");
        assertEquals(71, CraterGround.base(column("aaaa" + "g".repeat(17), 50), 71, 64, -64), "a cave below the plane band is not looked at");
    }

    /**
     * Blocks inside a hill under thick ground are not a building: a stray block (a mod's ore not tagged, an infested or
     * dungeon block), a cave with dripstone, a mineshaft. The hill keeps its surface instead of a shaft down to them.
     */
    @Test
    void hillKeepsItsSurfaceOverWhatIsInsideIt() {
        assertEquals(85, CraterGround.base(column("g".repeat(6) + "b" + "g".repeat(14), 64), 85, 64, -64), "stray block");
        assertEquals(85, CraterGround.base(column("gggg" + "aba" + "g".repeat(14), 64), 85, 64, -64), "cave with dripstone");
        assertEquals(85, CraterGround.base(column("gggg" + "bbb" + "aaaa" + "g".repeat(10), 64), 85, 64, -64), "mineshaft");
    }

    /** A house on a hill stands on the hill, not on the plane under it. */
    @Test
    void buildingOnAHillStandsOnTheHill() {
        assertEquals(71, CraterGround.base(column("ggggggg" + "bbbbbbbbbb" + "gg", 64), 83, 64, -64));
        assertEquals(71, CraterGround.base(column("ggggggg" + "aaa" + "g", 64), 75, 64, -64), "a thin floor over a room on the hill");
    }

    /** The walk never goes below the plane band, even with built blocks deep under the column. */
    @Test
    void groundStaysWithinTheBand() {
        assertEquals(61, CraterGround.base(column("bbbbbbbbbbbbb" + "gggg", 55), 72, 64, -64));
    }

    /** The edge is irregular around the crater: no repeating lobes like the former {@code sin 7θ}, within ±12 % (the former was ±8 %). */
    @Test
    void edgeIsIrregular() {
        double phase = 11;
        int m = 3600;
        double sum = 0, sq = 0, max = 0, sevenfold = 0;
        for (int i = 0; i < m; i++) {
            double t = 2 * Math.PI * i / m, e = CraterGround.edge(t, phase) - 1;
            sum += e;
            sq += e * e;
            max = Math.max(max, Math.abs(e));
            sevenfold += Math.abs(e - (CraterGround.edge(t + 2 * Math.PI / 7, phase) - 1));
        }
        assertEquals(0, sum / m, 1e-3, "mean radius kept");
        assertEquals(CraterGround.EDGE_RMS, Math.sqrt(sq / m), 0.002);
        assertTrue(max < 0.12, "edge within 12 %: " + max);
        assertTrue(sevenfold / m > CraterGround.EDGE_RMS, "repeats every seventh of a turn");
        assertTrue(Math.abs(CraterGround.edge(1, 3) - CraterGround.edge(1, 4)) > 1e-4, "same edge for different craters");
        assertEquals(CraterGround.edge(1, 3), CraterGround.edge(1, 3), 0, "edge not deterministic");
    }
}
