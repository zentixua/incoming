package ua.zentix.incoming.client.fx.smoke;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Smoke grid: billows - in one cascade, layers and rectangles where it is, mass in cells - its mass. */
class SmokeGridTest {
    private static SmokeGrid.Frame frame(double eyeX, double eyeY, double eyeZ, SmokeGrid.Source... sources) {
        SmokeGrid.Frame f = new SmokeGrid.Frame();
        SmokeGrid.build(new ArrayList<>(List.of(sources)), f, eyeX, eyeY, eyeZ, 0);
        return f;
    }

    /** Grid scale source ({@link SmokeGrid.Source#volume}): one billows per point. */
    private static SmokeGrid.Source blob(double x, double y, double z, double sh, double sv) {
        return volume((t, out) -> {
            out.blob(x, y, z, sh, sv, 1, 1, 0, 0.8f, 0.7f, 0.6f, 400, 2);
            return true;
        });
    }

    /** The same source, but on a grid scale: this is the only one it takes. */
    private static SmokeGrid.Source volume(SmokeGrid.Source s) {
        return new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                return s.emit(seconds, out);
            }

            @Override
            public boolean volume() {
                return true;
            }
        };
    }

    /** Cascade billows table with center x from the eye and σ horizontally sh (table order - from near sources). */
    private static int cascadeAt(SmokeGrid.Frame f, double x, double sh) {
        for (int i = 0; i < f.count; i++) {
            float h = f.blobs[i * 4 + 3];
            if (Math.abs(f.blobs[i * 4] - x) < 1e-3 && h >= sh && h <= sh + SmokeGrid.CELL[SmokeGrid.CASCADES - 1]) return f.cascade[i];
        }
        return -1;
    }

    /**
     * Small billows at the eye - in a shallow cascade; large or distant - to large; for the last one - no. billows - not more than two
     *  cells of its cascade ({@link SmokeGrid#RESOLVE}): the grid no longer takes.
     */
    @Test
    void blobGoesToTheFinestCascadeThatHoldsIt() {
        SmokeGrid.Frame f = frame(1000.3, 70.7, -500.2, blob(1010, 75, -490, 5, 5), blob(1000, 70, -500, 70, 70), blob(1150, 120, -500, 20, 20),
                blob(1400, 120, -500, 70, 70), blob(5000, 70, 0, 70, 70));
        assertEquals(4, f.count, "behind the last cascade - no");
        assertEquals(0, cascadeAt(f, 1010 - 1000.3, 5), "small near the eye - in cascade 0");
        assertEquals(2, cascadeAt(f, 1000 - 1000.3, 70), "σ 70 is wider than four cells of the 1 cascade - into the 2 cascade");
        assertEquals(1, cascadeAt(f, 1150 - 1000.3, 20), "in 150 blocks - in cascade 1 (±256 m)");
        assertEquals(2, cascadeAt(f, 1400 - 1000.3, 70), "in 400 blocks - in cascade 2 (±1024 m)");
        // the angles of the cascades are multiples of the cage and hug the eye
        for (int c = 0; c < SmokeGrid.CASCADES; c++) {
            float cell = f.corner[4 * c + 3];
            assertEquals(SmokeGrid.CELL[c], cell);
            assertEquals(0, Math.floorMod(Math.round(1000.3 + f.corner[4 * c]), (int) cell));
            assertTrue(f.corner[4 * c] < 0 && f.corner[4 * c] + SmokeGrid.N * cell > 0);
        }
    }

    /**
     * Billows rectangles in layers: as many layers as the number of cells it occupies in height (three σ), on both pages; NDC within the
     *  layer (page 0 - left half).
     */
    @Test
    void layersCoverTheBlob() {
        SmokeGrid.Frame f = frame(0, 0, 0, blob(5, 3, -7, 5, 4));
        int layers = 0;
        for (int l = 0; l < SmokeGrid.CASCADES * SmokeGrid.N; l++) {
            int n = f.layerStart[l + 1] - f.layerStart[l];
            if (n == 0) continue;
            layers++;
            assertEquals(2 * 6, n, "two pages of two triangles");
            for (int v = f.layerStart[l]; v < f.layerStart[l + 1]; v++) {
                assertTrue(Math.abs(f.vertices[3 * v]) <= 1 && Math.abs(f.vertices[3 * v + 1]) <= 1);
                assertEquals(0, f.vertices[3 * v + 2]);
            }
        }
        double cell = SmokeGrid.CELL[0], sv = Math.sqrt(4 * 4 + 1);
        assertEquals(Math.floor((3 + 3 * sv - f.corner[1]) / cell) - Math.floor((3 - 3 * sv - f.corner[1]) / cell) + 1, layers);
    }

    /**
     * The attenuation in the cells (as calculated by the fill shader according to the billows table) in total by volume is κ'·m: billows does not lose or
     *  add substances in any cascade (smeared by half a cell, the mass is the same).
     */
    @Test
    void cellsHoldTheBlobMass() {
        for (double[] at : new double[][]{{5, 3, -7, 5, 4}, {150, 40, 100, 20, 16}, {600, 200, -300, 80, 70}}) {
            SmokeGrid.Frame f = frame(0, 0, 0, blob(at[0], at[1], at[2], at[3], at[4]));
            int c = f.cascade[0];
            float cell = f.corner[4 * c + 3];
            float[] b = f.blobs;
            int m = SmokeGrid.MAX_BLOBS;
            double h = b[3], v = b[m * 4], dust = b[m * 4 + 2], sum = 0;
            for (int x = 0; x < SmokeGrid.N; x++) {
                for (int y = 0; y < SmokeGrid.N; y++) {
                    for (int z = 0; z < SmokeGrid.N; z++) {
                        double px = f.corner[4 * c] + (x + 0.5) * cell - b[0], py = f.corner[4 * c + 1] + (y + 0.5) * cell - b[1],
                                pz = f.corner[4 * c + 2] + (z + 0.5) * cell - b[2];
                        sum += dust * Math.exp(-0.5 * ((px * px + pz * pz) / (h * h) + py * py / (v * v)));
                    }
                }
            }
            double k = SmokeGrid.dustK(0.8f, 0.7f, 0.6f);
            assertEquals(k, sum * cell * cell * cell, 0.02 * k, "billows in cascade " + c);
        }
    }

    /**
     * billows along a segment (trace, pillar above the fire): the cells have the same mass as billows (the fill shader counts
     * {@link SmokeGrid#line}), the layer rectangles cover the entire segment, and the zero segment is a point.
     */
    @Test
    void segmentHoldsItsMassAlongItsLength() {
        for (double[] at : new double[][]{{5, 3, -7, 20, 8, -6, 4.5, 4.5}, {100, 40, 60, 60, 30, 0, 16, 16}, {-20, 10, 20, 0, 25, 0, 4.5, 4.5}}) {
            double ax = at[3], ay = at[4], az = at[5];
            SmokeGrid.Frame f = frame(0, 0, 0, volume((t, out) -> {
                out.segment(at[0], at[1], at[2], ax, ay, az, at[6], at[7], 0, 1, 0, 0.8f, 0.7f, 0.6f, 288, 0);
                return true;
            }));
            assertEquals(1, f.count);
            int c = f.cascade[0];
            float cell = f.corner[4 * c + 3];
            float[] b = f.blobs;
            int m = SmokeGrid.MAX_BLOBS;
            double h = b[3], v = b[m * 4], dust = b[m * 4 + 2], sum = 0;
            assertEquals(ax, b[(2 * m) * 4 + 3], 1e-4);
            assertEquals(ay, b[(3 * m) * 4 + 2], 1e-4);
            assertEquals(az, b[(3 * m) * 4 + 3], 1e-4);
            double sa = Math.sqrt(ax * ax / (h * h) + ay * ay / (v * v) + az * az / (h * h));
            // cells that cover the layers
            boolean[] covered = new boolean[SmokeGrid.N * SmokeGrid.N * SmokeGrid.N];
            for (int l = c * SmokeGrid.N; l < (c + 1) * SmokeGrid.N; l++) {
                for (int k = f.layerStart[l]; k < f.layerStart[l + 1]; k += 6) {
                    float x0 = f.vertices[3 * k], z0 = f.vertices[3 * k + 1], x1 = f.vertices[3 * (k + 2)], z1 = f.vertices[3 * (k + 2) + 1];
                    if (x0 >= 0) continue;
                    int cx0 = Math.round((x0 + 1) * SmokeGrid.N), cx1 = Math.round((x1 + 1) * SmokeGrid.N);
                    int cz0 = Math.round((z0 + 1) / 2 * SmokeGrid.N), cz1 = Math.round((z1 + 1) / 2 * SmokeGrid.N);
                    for (int x = cx0; x < cx1; x++) for (int z = cz0; z < cz1; z++) covered[(x * SmokeGrid.N + (l - c * SmokeGrid.N)) * SmokeGrid.N + z] = true;
                }
            }
            double outside = 0;
            for (int x = 0; x < SmokeGrid.N; x++) {
                for (int y = 0; y < SmokeGrid.N; y++) {
                    for (int z = 0; z < SmokeGrid.N; z++) {
                        double px = f.corner[4 * c] + (x + 0.5) * cell - b[0], py = f.corner[4 * c + 1] + (y + 0.5) * cell - b[1],
                                pz = f.corner[4 * c + 2] + (z + 0.5) * cell - b[2];
                        double qx = px / h, qy = py / v, qz = pz / h, q2 = qx * qx + qy * qy + qz * qz;
                        double along = sa < SmokeGrid.LINE_POINT ? 0 : (qx * ax / h + qy * ay / v + qz * az / h) / sa;
                        double e = dust * Math.exp(-0.5 * Math.max(0, q2 - along * along)) * SmokeGrid.line(sa, sa < SmokeGrid.LINE_POINT ? Math.sqrt(q2) : along);
                        sum += e;
                        if (!covered[(x * SmokeGrid.N + y) * SmokeGrid.N + z]) outside += e;
                    }
                }
            }
            double k = SmokeGrid.dustK(0.8f, 0.7f, 0.6f);
            assertEquals(k, sum * cell * cell * cell, 0.02 * k, "segment in a cascade " + c);
            assertTrue(outside * cell * cell * cell < 0.01 * k, "outside the layer rectangles - " + outside * cell * cell * cell / k);
        }
        // segment to zero - point
        for (double q : new double[]{0, 0.7, 2}) {
            assertEquals(Math.exp(-0.5 * q * q), SmokeGrid.line(SmokeGrid.LINE_POINT * 1.001, q), 2e-3);
            assertEquals(Math.exp(-0.5 * q * q), SmokeGrid.line(0, q), 1e-12);
        }
        // erf - with the precision of Abramowitz and Stigan
        assertEquals(0.8427007929, SmokeGrid.erf(1), 2e-7);
        assertEquals(-0.5204998778, SmokeGrid.erf(-0.5), 2e-7);
    }

    /**
     * The fraction of the cross-section, which the cascade correctly shows: σ is not less than a cell - the whole thing, not more than half a cell - no; cell - at the location of the
     *  club (there is a small cascade near the eye, larger ones further on, behind the mesh - none).
     */
    @Test
    void resolvedShareFollowsTheCascadeCell() {
        assertEquals(1, SmokeGrid.resolved(2, 2), 1e-12);
        assertEquals(0, SmokeGrid.resolved(1, 2), 1e-12);
        assertEquals(0.5, SmokeGrid.resolved(1.5, 2), 1e-12);
        assertEquals(0, SmokeGrid.resolved(5, 0), 1e-12);
        double[] c = new double[3 * SmokeGrid.CASCADES];
        SmokeGrid.corners(0.5, 0.5, 0.5, c);
        assertEquals(2, SmokeGrid.cellFor(c, 10.5, 0.5, 0.5, 0, 0, 0, 1, 1), 0);
        assertEquals(8, SmokeGrid.cellFor(c, 100.5, 0.5, 0.5, 0, 0, 0, 1, 1), 0);
        assertEquals(8, SmokeGrid.cellFor(c, 10.5, 0.5, 0.5, 60, 0, 0, 1, 1), 0, "a long segment near the eye - into a cascade, where it is all");
        assertEquals(0, SmokeGrid.cellFor(c, 5000.5, 0.5, 0.5, 0, 0, 0, 1, 1), 0);
    }

    /** The drying up source goes away; the volume draws a cloud near the eye and does not draw at the edge of the mesh. */
    @Test
    void sourcesEndAndShareFadesAtTheEdge() {
        List<SmokeGrid.Source> list = new ArrayList<>(List.of(volume((t, out) -> false), blob(0, 0, 0, 5, 5)));
        SmokeGrid.build(list, new SmokeGrid.Frame(), 0, 0, 0, 0);
        assertEquals(1, list.size());
        double half = 0.5 * SmokeGrid.N * SmokeGrid.CELL[SmokeGrid.CASCADES - 1];
        assertEquals(1, SmokeGrid.volumeShare(100, 50, -200));
        assertEquals(0, SmokeGrid.volumeShare(0.8 * half, 0, 0), 1e-12);
        assertEquals(0, SmokeGrid.volumeShare(0, 0, -0.9 * half), 1e-12);
    }

    /** Source from n billows at point; remembers the result of the layout. */
    private static final class Cloud implements SmokeGrid.Source {
        final double x;
        final int n;
        Boolean placed;

        Cloud(double x, int n) {
            this.x = x;
            this.n = n;
        }

        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            // σ - no more than two cells of the cascade at this range (at the eye 2 m, up to 256 m - 8, further - 32)
            double sigma = x < 50 ? 5 : x < 200 ? 20 : 70;
            for (int i = 0; i < n; i++) out.blob(x, 0, 0, sigma, sigma, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
            return true;
        }

        @Override
        public void placed(boolean inGrid) {
            placed = inGrid;
        }

        @Override
        public boolean volume() {
            return true;
        }
    }

    /**
     * The billows table is full - the distant clouds leave entirely (they draw themselves), the nearby ones - all their billows; a cloud that
     *  does not fit in does not prevent the smaller one behind it from getting in.
     */
    @Test
    void overflowDropsWholeFarClouds() {
        Cloud far = new Cloud(300, 200), near = new Cloud(10, 300), mid = new Cloud(100, 250), small = new Cloud(400, 10);
        SmokeGrid.Frame f = frame(0, 0, 0, far, near, mid, small);
        assertEquals(Boolean.TRUE, near.placed);
        assertEquals(Boolean.TRUE, far.placed, "300 + 200 ≤ 512");
        assertEquals(Boolean.FALSE, mid.placed, "closer than the distant one, but 300 + 250 > 512 - it is completely absent");
        assertEquals(Boolean.TRUE, small.placed, "10 billows still fit");
        assertEquals(510, f.count);
    }

    /** The cloud, whose billows have gone beyond all the cascades (blown away by the wind), does not go into the grid at all - it draws itself, and not by cutting it off. */
    @Test
    void cloudPastTheLastCascadeLeavesWhole() {
        SmokeGrid.Source drifted = new SmokeGrid.Source() {
            Boolean placed;

            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(100, 50, 0, 20, 10, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                out.blob(3000, 50, 0, 20, 10, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }

            @Override
            public void placed(boolean inGrid) {
                placed = inGrid;
                assertEquals(false, inGrid);
            }

            @Override
            public boolean volume() {
                return true;
            }
        };
        Cloud near = new Cloud(10, 3);
        SmokeGrid.Frame f = frame(0, 0, 0, drifted, near);
        assertEquals(3, f.count, "only billows near clouds");
        assertEquals(Boolean.TRUE, near.placed);
    }

    /**
     * Cascade box - where its billows (three σ), inside the cascade; the cascade without billows is empty (the shader does not read it and passes
     *  without steps on the cell).
     */
    @Test
    void cascadeBoxesHoldTheirBlobs() {
        SmokeGrid.Frame f = frame(0.5, 0.5, 0.5, blob(10, 5, -8, 5, 4), blob(400, 50, 0, 70, 65));
        assertTrue(SmokeGrid.occupied(f, 0));
        assertTrue(!SmokeGrid.occupied(f, 1), "billows did not choose the middle cascade");
        assertTrue(SmokeGrid.occupied(f, 2));
        double h = Math.sqrt(5 * 5 + 1), v = Math.sqrt(4 * 4 + 1);
        assertEquals(10 - 3 * h - 0.5, f.boxLo[0], 1e-4);
        assertEquals(5 + 3 * v - 0.5, f.boxHi[1], 1e-4);
        // in the last cascade the box is within its boundaries
        for (int a = 0; a < 3; a++) {
            assertTrue(f.boxLo[6 + a] >= f.corner[8 + a] - 1e-3 && f.boxHi[6 + a] <= f.corner[8 + a] + SmokeGrid.N * SmokeGrid.CELL[2] + 1e-3);
        }
    }

    /** Dust weakening after delta Eddington: white (ω = 1) - K_MIX (1 − f), black - K_MIX. */
    @Test
    void dustExtinctionIsDeltaScaled() {
        double k = ua.zentix.incoming.nuclear.model.DustModel.K_MIX;
        assertEquals(k * (1 - SmokeGrid.DUST_F), SmokeGrid.dustK(1, 1, 1), 1e-9 * k);
        assertEquals(k, SmokeGrid.dustK(0, 0, 0), 1e-9 * k);
        // reversing ω' → ω: ω = 0,9 gives ω' = 0,64·0,9/(1 − 0,324)
        float wp = (float) (0.64 * 0.9 / (1 - 0.36 * 0.9));
        assertEquals(k * (1 - 0.9 * 0.36), SmokeGrid.dustK(wp, wp, wp), 1e-5 * k);
    }

    /**
     * Clouds of conventional weapons do not go into the grid (3.5.0: cell 8–32 m smeared a cloud of meters into cubes): the source is not
     *  of the grid scale ({@link SmokeGrid.Source#volume} is not) does not give it billows at all and receives “not in the grid” - it draw
     * layer squares; and billows the grid does not take even two cells of its cascade and the grid scale from the source is the entire source.
     */
    @Test
    void gridRefusesWhatItsCellDoesNotResolve() {
        boolean[] asked = {false};
        Boolean[] conventional = {null}, thin = {null}, wide = {null};
        SmokeGrid.Source shahed = new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                asked[0] = true;
                out.blob(5, 0, 5, 50, 50, 1, 1, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }

            @Override
            public void placed(boolean inGrid) {
                conventional[0] = inGrid;
            }
        };
        // billows σ 3 m at the eye - in cascade 0 (cell 2 m): already two cells - not allowed; with him at the source - and wide
        SmokeGrid.Source mixed = new Placed(thin) {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(0, 0, 0, 3, 3, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                out.blob(0, 0, 0, 200, 200, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }
        };
        // σ 5 m at the eye - cascade 0, two and a half cells: takes
        SmokeGrid.Source resolved = new Placed(wide) {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(0, 0, 0, 5, 5, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                return true;
            }
        };
        SmokeGrid.Frame f = frame(0, 0, 0, shahed, mixed, resolved);
        assertEquals(false, asked[0], "a regular cloud does not return to the billows grid");
        assertEquals(Boolean.FALSE, conventional[0], "regular cloud - squares");
        assertEquals(Boolean.FALSE, thin[0], "billows are already two cells - the whole source is squares");
        assertEquals(Boolean.TRUE, wide[0]);
        assertEquals(1, f.count);
        // billows σ cascade: σ is at least two of its cells - at any range where it falls
        double[] corner = new double[3 * SmokeGrid.CASCADES];
        SmokeGrid.corners(0, 0, 0, corner);
        for (double sigma : new double[]{1, 3, 5, 15, 20, 40, 63, 70, 200}) {
            for (double x : new double[]{0, 100, 300, 800}) {
                float cell = SmokeGrid.cellFor(corner, x, 0, 0, 0, 0, 0, sigma, sigma);
                Boolean[] got = {null};
                frame(0, 0, 0, new Placed(got) {
                    @Override
                    public boolean emit(double seconds, SmokeGrid.Sink out) {
                        out.blob(x, 0, 0, sigma, sigma, 1, 0, 0, 0.8f, 0.8f, 0.8f, 300, 0);
                        return true;
                    }
                });
                assertEquals(cell > 0 && sigma >= SmokeGrid.RESOLVE * cell, got[0], "σ " + sigma + " in " + x + " m, cage " + cell);
            }
        }
    }

    /** The grid scale source that remembers the layout result in box[0]. */
    private abstract static class Placed implements SmokeGrid.Source {
        private final Boolean[] box;

        Placed(Boolean[] box) {
            this.box = box;
        }

        @Override
        public void placed(boolean inGrid) {
            box[0] = inGrid;
        }

        @Override
        public boolean volume() {
            return true;
        }
    }

    /**
     * Clouds of conventional weapons do not declare themselves by grid scale: clouds of explosions ({@code FarBlasts}) and smoke of fires
     *  ({@code FireSmoke}) and launch clouds ({@code LaunchCloud}, and ICBM) do not override {@link SmokeGrid.Source#volume}.
     * New source of grid scale - deliberately here.
     */
    @Test
    void conventionalCloudsAreNotVolume() throws java.io.IOException {
        for (ua.zentix.incoming.strike.WeaponType w : ua.zentix.incoming.strike.WeaponType.values()) {
            var spec = ua.zentix.incoming.client.ClientWeaponSpec.of(w).airframe(false);
            if (spec == null) continue;
            ua.zentix.incoming.client.fx.plume.Motor m = spec.far().stage(ua.zentix.incoming.entity.FlightPhase.BOOST).motor();
            if (m == null) continue;
            LaunchCloud c = new LaunchCloud(CloudModel.Air.STANDARD, 64, 0.5, 0.5f, 0.5f, 0.5f, 0);
            for (int i = 0; i < 40; i++) c.feed(m, 0.05, 0, 66, 0, 0, -1, 0);
            if (!c.fed()) continue;
            assertEquals(false, c.volume(), w + ": launch cloud σ " + c.thermal().sigmaH(0));
        }
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/ua/zentix/incoming");
        List<String> volume = new ArrayList<>();
        try (var files = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : (Iterable<java.nio.file.Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String text = java.nio.file.Files.readString(p);
                if (text.contains("boolean volume()") && !p.endsWith("SmokeGrid.java")) {
                    volume.add(root.relativize(p).toString());
                }
            }
        }
        assertEquals(List.of(), volume, "grid scale sources");
    }
}
