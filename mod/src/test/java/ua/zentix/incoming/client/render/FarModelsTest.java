package ua.zentix.incoming.client.render;

import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.strike.WeaponType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Models in the distance: an atlas tile in the corner of the model, the entire model in a tile ball, simplified copies of parts. */
class FarModelsTest {
    /** Parts on hinges on the side of the axle (wings and rocket air intake, flaps B-2): turning them away from the center. */
    private static final Set<WeaponModels.Mesh> HINGED = Set.of(WeaponModels.Mesh.MISSILE_WING_L, WeaponModels.Mesh.MISSILE_WING_R,
            WeaponModels.Mesh.MISSILE_INTAKE, WeaponModels.Mesh.BOMBER_DOOR_L_IN, WeaponModels.Mesh.BOMBER_DOOR_L_OUT,
            WeaponModels.Mesh.BOMBER_DOOR_R_IN, WeaponModels.Mesh.BOMBER_DOOR_R_OUT);

    private record Obj(List<double[]> vertices, int faces) {}

    private static Obj obj(String file) {
        try (InputStream in = FarModelsTest.class.getResourceAsStream("/assets/incoming/models/weapon/" + file + ".obj")) {
            if (in == null) return null;
            List<double[]> v = new ArrayList<>();
            int faces = 0;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                String[] s = line.trim().split("\\s+");
                if (s[0].equals("v")) v.add(new double[]{Double.parseDouble(s[1]), Double.parseDouble(s[2]), Double.parseDouble(s[3])});
                else if (s[0].equals("f")) faces++;
            }
            return new Obj(v, faces);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The size used to measure the distance view of the projectile ({@code FarLook.size}) is that of the projectile to which the part belongs. */
    private static double size(WeaponModels.Mesh m) {
        String n = m.name();
        ClientWeaponSpec.ClientAirframe a;
        if (n.startsWith("DRONE_")) a = ClientWeaponSpec.of(WeaponType.DRONE).airframe(false);
        else if (n.startsWith("MISSILE_")) a = ClientWeaponSpec.of(WeaponType.MISSILE).airframe(false);
        else if (n.startsWith("BOMBER_")) a = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(false);
        else if (n.startsWith("BOMB_")) a = ClientWeaponSpec.of(WeaponType.BUNKER).airframe(true);
        else if (n.startsWith("ICBM_")) a = ClientWeaponSpec.of(WeaponType.NUKE).airframe(false);
        else if (n.startsWith("ROCKET_")) a = ClientWeaponSpec.of(WeaponType.ROCKET).airframe(false);
        else a = ClientWeaponSpec.of(WeaponType.LOITER).airframe(false);
        return a.far().size();
    }

    private static double length(double x, double y, double z) {
        return Math.sqrt(x * x + y * y + z * z);
    }

    @Test
    void everyPoseFitsTheTileSphere() {
        for (WeaponModels.Mesh m : WeaponModels.Mesh.values()) {
            if (m == WeaponModels.Mesh.ROCKET_RACK) continue; // mLRS package - launcher, not a projectile in the distance
            if (m == WeaponModels.Mesh.INTERCEPTOR_BODY) continue; // an anti-aircraft missile is not drawn in the distance - only close, in entity
            if (m == WeaponModels.Mesh.SAM_PACK) continue; // sAM package - above its block, not drawn in the distance
            Obj o = obj(m.file);
            assertNotNull(o, m.file);
            double reach = 0;
            if (HINGED.contains(m)) {
                // hinge - on the edge of the part: the rotated point is no further from the center than the far corner of its box plus the diagonal
                double[] lo = {1e9, 1e9, 1e9}, hi = {-1e9, -1e9, -1e9};
                for (double[] v : o.vertices()) {
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], v[i]);
                        hi[i] = Math.max(hi[i], v[i]);
                    }
                }
                double corner = length(Math.max(-lo[0], hi[0]), Math.max(-lo[1], hi[1]), Math.max(-lo[2], hi[2]));
                reach = corner + length(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]);
            } else {
                // screws and disks rotate around the Z axis through the center, the “Lancet” wings only compress: they do not go beyond the grid points
                for (double[] v : o.vertices()) reach = Math.max(reach, length(v[0], v[1], v[2]));
            }
            double radius = FarModels.TILE_RADIUS * size(m);
            assertTrue(reach <= radius, m + ": " + reach + " behind the tile ball " + radius);
        }
    }

    @Test
    void coarseCopiesKeepPointsOfTheFullMeshAndAreCheaper() {
        int full = 0, coarse = 0;
        for (WeaponModels.Mesh m : WeaponModels.Mesh.values()) {
            Obj o = obj(m.file), c = obj(m.file + "_lod1");
            if (!m.lod) {
                assertNull(c, m.file + "_lod1 lies, but the part does not take it");
                continue;
            }
            assertNotNull(c, m.file + "_lod1");
            assertNotNull(FarModelsTest.class.getResource("/assets/incoming/models/weapon/" + m.file + "_lod1.json"), m.file + "_lod1.json");
            assertTrue(c.faces() <= o.faces(), m.file);
            // simplification removes the rows of the grid, rather than moving the points: the silhouette does not get fatter or thinner
            for (double[] p : c.vertices()) {
                boolean found = false;
                for (double[] q : o.vertices()) {
                    if (Math.abs(p[0] - q[0]) < 1e-4 && Math.abs(p[1] - q[1]) < 1e-4 && Math.abs(p[2] - q[2]) < 1e-4) {
                        found = true;
                        break;
                    }
                }
                assertTrue(found, m.file + "_lod1: points (" + p[0] + ", " + p[1] + ", " + p[2] + ") not in full grid");
            }
            full += o.faces();
            coarse += c.faces();
        }
        assertTrue(coarse * 3 < full, "simplified copies - " + coarse + " faces from " + full);
    }

    @Test
    void tileHasFourTexelsPerScreenPixel() {
        double pixel = Math.toRadians(70) / 720;
        // model at 10 screen pixels - tile at 40 texels
        assertEquals(40, FarModels.tileSize(5 * pixel, pixel));
        for (double px = 0.1; px < 400; px *= 1.07) {
            int side = FarModels.tileSize(px / 2 * pixel, pixel);
            assertEquals(0, side % 4, "side is a multiple of 4: mip level 2 does not divide texel between tiles");
            assertTrue(side >= FarModels.MIN_TILE && side <= FarModels.MAX_TILE);
            if (side > FarModels.MIN_TILE && side < FarModels.MAX_TILE) {
                assertTrue(side >= FarModels.SUPERSAMPLE * px && side < FarModels.SUPERSAMPLE * px + 5, px + " px: " + side);
            }
        }
        // border - entire mip-texels, the four largest tiles with borders are included in the atlas
        assertEquals(0, FarModels.PAD % 4);
        assertTrue(2 * (FarModels.MAX_TILE + 2 * FarModels.PAD) <= FarModels.ATLAS);
    }

    @Test
    void tileProjectionPutsTheModelSphereOnTheTile() {
        // a ball of radius h at a distance d is visible at an angle with tangent tan: rays with this tangent are the edges of the tile at any
        //  depth, the near and far points of the ball are the edges of the atlas depth
        float d = 500, h = 12, tan = (float) (h / Math.sqrt(d * d - h * h));
        int x = 100, y = 300, size = 64;
        FarModels.Tile t = new FarModels.Tile();
        t.place(x, y, size, d, h, tan);
        Vector4f p = new Vector4f();
        for (float z : new float[]{d - h, d, d + h}) {
            t.projection.transform(p.set(tan * z, -tan * z, z, 1));
            assertEquals(2.0 * (x + size / 2) / FarModels.ATLAS - 1, p.x / p.w, 1e-5);
            assertEquals(2.0 * (y - size / 2) / FarModels.ATLAS - 1, p.y / p.w, 1e-5);
            t.projection.transform(p.set(0, 0, z, 1));
            assertEquals(2.0 * x / FarModels.ATLAS - 1, p.x / p.w, 1e-5);
            assertEquals(2.0 * y / FarModels.ATLAS - 1, p.y / p.w, 1e-5);
        }
        t.projection.transform(p.set(0, 0, d - h, 1));
        assertEquals(-1, p.z / p.w, 1e-4);
        t.projection.transform(p.set(0, 0, d + h, 1));
        assertEquals(1, p.z / p.w, 1e-4);
    }
}
