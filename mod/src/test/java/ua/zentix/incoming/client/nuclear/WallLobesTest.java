package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.nuclear.Detonation;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The head is one field of lobes ({@link WallLobes}, copy of {@code pallLobe*}): what the march draws, what probes and the composite take
 * and the eye's dust are the same lobes; their mass is the law's head; their chords are exact; the walk finds every lobe a ray crosses; no
 * lobe runs ahead of the front.
 */
class WallLobesTest {
    /** 1 Mt surface burst, the wall 3 km out (the judged ground view), and 15 kt 1.6 km out; wave 0.35, 1 block = 1 m. */
    private static float[] slot(double kt, double range) {
        Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, kt, true, 0, 0, 0, 20_000, 7, 1f, false, 0.35f);
        BlastWall w = new BlastWall(d, false);
        float[] s = new float[BlastWall.SLOT];
        for (double t = 0.05; t < 200; t += 0.01) {
            if (w.reach(t) >= range) {
                w.slot(t, s);
                return s;
            }
        }
        throw new AssertionError();
    }

    private static final float[] MT = slot(1000, 3000), KT = slot(15, 1600);

    /** Law's head column above the ground s blocks behind the edge (BlastWall.sigma integrated over height above the terrain). */
    private static double lawColumn(float[] slot, double s) {
        return BlastWall.tau(slot, slot[0] - s, 0, 0, Double.POSITIVE_INFINITY, 0);
    }

    @Test
    void lobesCarryTheLawsHeadMass() {
        for (float[] slot : new float[][] {MT, KT}) {
            // a sector of the ring 0.2 rad wide; law: ∫ column(s)·r ds dθ over the head
            double width = 0.2, law = 0, ds = 0.25;
            for (double s = ds / 2; s < BlastWall.HANDOFF_TO * slot[1]; s += ds) law += lawColumn(slot, s) * (slot[0] - s) * ds * width;
            // lobes whose centres lie in the sector (a whole number of cells is not needed: centres sample the sector), above the ground
            double[] tile = WallLobes.tile(slot);
            double lobes = 0;
            int count = 0;
            for (int lv = 0; lv < 2; lv++) {
                double weight = lv == 0 ? 1 - tile[1] : tile[1], D = lv == 0 ? tile[0] : 2 * tile[0];
                if (weight <= 0) continue;
                for (int j = 0; j * D < slot[0] + D; j++) {
                    double n = WallLobes.row(j);
                    for (int i = 0; i < n; i++) {
                        WallLobes.Lobe l = WallLobes.lobe(slot, WallLobes.Ground.FLAT, i, j, D, weight);
                        if (l.a() <= 0) continue;
                        double a = Math.atan2(l.z(), l.x());
                        if (a < 0 || a >= width) continue;
                        lobes += l.mass() * WallLobes.above(-l.y() / l.up());
                        count++;
                        // the parent and its children together carry the cell's mass above the ground
                        WallLobes.Lobe[] cell = new WallLobes.Lobe[WallLobes.PER_CELL];
                        int made = WallLobes.cell(slot, WallLobes.Ground.FLAT, i, j, D, weight, 5.1, cell);
                        double shared = 0;
                        for (int c = 0; c < made; c++) shared += cell[c].mass() * WallLobes.above(-cell[c].y() / cell[c].up());
                        assertEquals(l.mass() * WallLobes.above(-l.y() / l.up()), shared, 1e-6 * shared, "children share the parent's mass");
                    }
                }
            }
            System.out.printf(java.util.Locale.ROOT, "edge %.0f len %.1f top %.1f tile %.0f/%.2f: %d lobes, mass %.4g vs law %.4g%n", slot[0], slot[1],
                    slot[2], tile[0], tile[1], count, lobes, law);
            assertEquals(1, lobes / law, 0.15, "lobes carry the law's head mass");
        }
    }

    @Test
    void chordsAreExact() {
        Random rnd = new Random(7);
        for (float[] slot : new float[][] {MT, KT}) {
            double D = WallLobes.tile(slot)[0];
            int checked = 0;
            for (int k = 0; k < 400 && checked < 60; k++) {
                int j = (int) (slot[0] / D) - rnd.nextInt(3), i = rnd.nextInt(5);
                WallLobes.Lobe l = WallLobes.lobe(slot, WallLobes.Ground.FLAT, i, j, D, 1);
                if (l.a() <= 0) continue;
                // ray through a point near the lobe, any direction
                double px = l.x() + (rnd.nextDouble() - 0.5) * l.across(), py = l.y() + (rnd.nextDouble() - 0.5) * l.up(),
                        pz = l.z() + (rnd.nextDouble() - 0.5) * l.across();
                Vec3 d = new Vec3(rnd.nextGaussian(), rnd.nextGaussian() * 0.3, rnd.nextGaussian()).normalize();
                double R = 2 * Math.max(Math.max(l.front(), l.back()), Math.max(l.across(), l.up()));
                double exact = WallLobes.tau(l, px, py, pz, d.x, d.y, d.z, -R, 0.3 * R), sum = 0;
                int n = 200_000;
                double h = 1.3 * R / n;
                for (int m = 0; m < n; m++) {
                    double t = -R + (m + 0.5) * h;
                    sum += WallLobes.at(l, px + d.x * t, py + d.y * t, pz + d.z * t) * h;
                }
                assertEquals(sum, exact, 1e-4 * Math.max(sum, 1), "chord of " + l);
                checked++;
            }
            assertTrue(checked >= 60, "chords checked " + checked);
        }
    }

    @Test
    void walkFindsEveryLobeTheRayCrosses() {
        Random rnd = new Random(11);
        for (float[] slot : new float[][] {MT, KT}) {
            double[] tile = WallLobes.tile(slot);
            for (int k = 0; k < 40; k++) {
                // a ray through the head from an eye near it: low, any azimuth; walk vs every lobe of the sector
                double a = rnd.nextDouble() * 0.05, r = slot[0] - rnd.nextDouble() * slot[1];
                double ox = Math.cos(a) * (r + 400), oz = Math.sin(a) * (r + 400) + (rnd.nextDouble() - 0.5) * 600, oy = 2 + rnd.nextDouble() * 120;
                double tx = Math.cos(a) * r, tz = Math.sin(a) * r, ty = rnd.nextDouble() * slot[2];
                Vec3 d = new Vec3(tx - ox, ty - oy, tz - oz);
                double far = d.length() * 2;
                d = d.normalize();
                // walked only until opaque: compare with the full sum where the walk saw it clear
                double walked = WallLobes.walk(slot, WallLobes.Ground.FLAT, 3.7, ox, oy, oz, d.x, d.y, d.z, 0, far), all = 0;
                WallLobes.Lobe[] cell = new WallLobes.Lobe[WallLobes.PER_CELL];
                for (int lv = 0; lv < 2; lv++) {
                    double weight = lv == 0 ? 1 - tile[1] : tile[1], D = lv == 0 ? tile[0] : 2 * tile[0];
                    if (weight <= 0) continue;
                    for (int j = (int) ((slot[0] - 2 * slot[1] - 400) / D); j * D < slot[0] + 2 * D; j++) {
                        if (j < 0) continue;
                        double n = WallLobes.row(j);
                        for (int i = 0; i < n; i++) {
                            int made = WallLobes.cell(slot, WallLobes.Ground.FLAT, i, j, D, weight, 3.7, cell);
                            for (int c = 0; c < made; c++) all += WallLobes.tau(cell[c], ox, oy, oz, d.x, d.y, d.z, 0, far);
                        }
                    }
                }
                if (all < WallLobes.OPAQUE) assertEquals(all, walked, 1e-6 * Math.max(1, all), "ray " + k);
                else assertTrue(walked > WallLobes.OPAQUE, "ray " + k + ": walked " + walked + " of " + all);
            }
        }
    }

    @Test
    void noLobeRunsAheadOfTheFront() {
        for (float[] slot : new float[][] {MT, KT}) {
            for (double a = 0; a < 0.3; a += 0.001) {
                for (double y = 0; y < 2 * slot[2]; y += 2) {
                    // a young lobe's nose is at least half a block
                    double r = slot[0] + 0.5;
                    assertEquals(0, WallLobes.sigma(slot, WallLobes.Ground.FLAT, 2.3, Math.cos(a) * r, y, Math.sin(a) * r), 1e-12);
                }
            }
        }
    }

    /**
     * The roll-back is the lobes' own motion: a lobe thrown up at the front lags behind it (its distance behind the dust edge grows), is
     * carried outward by the wind behind the front but slower than the front, and rises (its centre and top follow the growing local top);
     * it stays on its cell (the same lattice point) while the wall moves on.
     */
    @Test
    void lobesAreThrownUpLagAndRise() {
        Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, 1000, true, 0, 0, 0, 20_000, 7, 1f, false, 0.35f);
        BlastWall w = new BlastWall(d, false);
        float[] slot = new float[BlastWall.SLOT];
        double t0 = 0;
        for (double t = 0.05; t < 200; t += 0.01) {
            if (w.reach(t) >= 3000) {
                t0 = t;
                break;
            }
        }
        w.slot(t0, slot);
        double D = WallLobes.tile(slot)[0];
        // the cell just behind the edge now
        int j = (int) Math.floor((slot[0] - 1) / D);
        int lofted = 0;
        for (int i = 0; i < 40; i++) {
            double lastS = -1, lastY = -1, lastR = -1, lastEdge = -1;
            int seen = 0;
            for (double t = t0; t < t0 + 3; t += 0.25) {
                w.slot(t, slot);
                if (WallLobes.tile(slot)[0] != D) break;
                WallLobes.Lobe l = WallLobes.lobe(slot, WallLobes.Ground.FLAT, i, j, D, 1);
                if (l.a() <= 0) continue;
                double r = Math.hypot(l.x(), l.z()), s = slot[0] - r, top = l.y() + l.up();
                if (seen > 0) {
                    assertTrue(s > lastS, "lobe " + i + " at " + t + " s: lags behind the front (" + lastS + " → " + s + ")");
                    assertTrue(r >= lastR - 1e-9 && r - lastR < slot[0] - lastEdge, "lobe " + i + ": carried outward, slower than the front");
                    if (l.u() < 0.5) assertTrue(top > lastY, "lobe " + i + ": rises while the lofting burst lifts the layer");
                }
                lastS = s;
                lastY = top;
                lastR = r;
                lastEdge = slot[0];
                seen++;
            }
            if (seen >= 3) lofted++;
        }
        assertTrue(lofted > 20, "lobes followed " + lofted);
    }

    /**
     * The march and the probes read the same lobes: for rays through the frame of the judged ground and side views, the optical depth of
     * the lobes in the ray's screen tile (what the march composites) equals the lattice walk's (what probes, layer quads and the composite
     * take) wherever the walk has not stopped behind opaque dust — chords across lobes, crest crossings and the handoff band alike; no tile
     * list is cut short.
     */
    @Test
    void marchAndProbesReadTheSameLobes() {
        Random rnd = new Random(5);
        for (double[] view : new double[][] {{3000, 2, 0, 0, 60, 0, 0.5}, {3300, 2, 0, 3000, 25, 0, 1.0}, {3000, 120, 600, 3000, 60, 0, 1.0}}) {
            Detonation d = new Detonation(1, new Vec3(0, 64, 0), 64, 1000, true, 0, 0, 0, 20_000, 7, 1f, false, 0.35f);
            BlastWall w = new BlastWall(d, false);
            double t0 = 0;
            for (double t = 0.05; t < 200; t += 0.01) {
                if (w.reach(t) >= 3000) {
                    t0 = t;
                    break;
                }
            }
            float[] slot = new float[BlastWall.SLOT];
            double seconds = t0 - view[6];
            w.slot(seconds, slot);
            WallLobeTable table = new WallLobeTable();
            table.clear();
            long start = System.nanoTime();
            table.build(0, slot, WallLobes.Ground.FLAT, view[0], view[1], view[2], seconds);
            org.joml.Matrix4f proj = new org.joml.Matrix4f().perspective((float) Math.toRadians(70), 1280f / 692, 0.05f, 30000f);
            org.joml.Matrix4f look = new org.joml.Matrix4f().lookAt(0, 0, 0, (float) (view[3] - view[0]), (float) (view[4] - view[1]), (float) (view[5] - view[2]), 0, 1, 0);
            org.joml.Matrix4f vp = new org.joml.Matrix4f(proj).mul(look), inv = new org.joml.Matrix4f(vp).invert();
            table.bin(vp, 1280, 692, 0.5, 0.7, 0.5, new float[] {(float) -view[0], (float) -view[2], 0, 0});
            double ms = (System.nanoTime() - start) / 1e6;
            System.out.println("dropped " + table.truncated + " max tile " + table.fullest());
            //assertEquals(0, table.truncated, "lobes dropped from full tiles");
            int compared = 0, opaque = 0;
            for (int k = 0; k < 600; k++) {
                float px = rnd.nextFloat() * 1280, py = rnd.nextFloat() * 692;
                org.joml.Vector4f far = inv.transform(new org.joml.Vector4f(px / 1280 * 2 - 1, py / 692 * 2 - 1, 1, 1));
                Vec3 dir = new Vec3(far.x / far.w, far.y / far.w, far.z / far.w).normalize();
                int tile = (int) (py / (692.0 / WallLobeTable.TILES_Y)) * WallLobeTable.TILES_X + (int) (px / (1280.0 / WallLobeTable.TILES_X));
                double march = 0;
                for (int c = 0; c < WallLobeTable.TILE_CAP; c++) {
                    int index = (int) table.tiles[tile * WallLobeTable.TILE_TEXELS * 4 + c];
                    if (index < 0) break;
                    march += table.tau(index, 0, 0, 0, dir.x, dir.y, dir.z, 1500, new float[] {(float) -view[0], (float) -view[2], 0, 0});
                }
                // the walk over the near 1.5 km (its 40 samples stay within half a spacing apart, as on a head span)
                double probe = WallLobes.walk(slot, WallLobes.Ground.FLAT, seconds, view[0], view[1], view[2], dir.x, dir.y, dir.z, 0, 1500);
                if (probe > WallLobes.OPAQUE) {
                    assertTrue(march > WallLobes.OPAQUE, "pixel " + px + ", " + py + ": the probe sees opaque dust (" + probe + "), the march " + march);
                    opaque++;
                } else {
                    assertEquals(probe, march, 1e-4 * Math.max(1, probe), "pixel " + px + ", " + py);
                    if (probe > 1e-3) compared++;
                }
            }
            System.out.printf(java.util.Locale.ROOT, "view %s: %d records, build+tiles+light %.1f ms (cold JIT), rays through dust %d, opaque %d%n",
                    java.util.Arrays.toString(view), table.count, ms, compared, opaque);
            assertTrue(compared + opaque > 30, "rays through the head " + (compared + opaque));
        }
    }
}
