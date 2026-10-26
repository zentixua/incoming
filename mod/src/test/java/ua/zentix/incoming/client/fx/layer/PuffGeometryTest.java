package ua.zentix.incoming.client.fx.layer;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks actual GPU-buffer vertices: turning the view does not rotate puffs around a stationary eye. */
class PuffGeometryTest {
    @Test
    void worldVerticesIgnoreCameraYawPitchAndRoll() {
        FxQuads out = new FxQuads();
        FxAtlas.Sprite sprite = FxAtlas.puff6(7, true);
        for (float[] center : new float[][]{{13, -7, 42}, {-61, 28, -83}, {0, 30, 0}, {0, -30, 0}}) {
            double[] expectedFace = null, expectedFlat = null;
            for (int k = 0; k < 12; k++) {
                Quaternionf turn = new Quaternionf().rotationYXZ(k * 0.43f, k * -0.17f, k * 0.29f);
                out.begin(new Vector3f(1, 0, 0).rotate(turn), new Vector3f(0, 1, 0).rotate(turn));
                out.sixWay(0.37f, 1);
                out.facePosition(center[0], center[1], center[2], 100, 8, 13, sprite, 1, 1, 1, 0.8f, 4, 1, LightTexture.FULL_BRIGHT, 0);
                out.flat(center[0], center[1], center[2], 100, 8, 13, 0.73, sprite, 1, 1, 1, 0.8f, 4, 1, LightTexture.FULL_BRIGHT, 0);
                double[] face = vertices(out, 0), flat = vertices(out, 1);
                if (expectedFace == null) {
                    expectedFace = face;
                    expectedFlat = flat;
                } else {
                    assertArrayEquals(expectedFace, face, 0, "upright puff, rotation " + k);
                    assertArrayEquals(expectedFlat, flat, 0, "horizontal puff, rotation " + k);
                }
                // The upright puff plane is perpendicular to its center ray, with up derived from world vertical.
                for (int i = 0; i < 4; i++) {
                    double dot = 0;
                    for (int j = 0; j < 3; j++) dot += (face[3 * i + j] - center[j]) * center[j];
                    assertEquals(0, dot, 1e-3);
                    assertEquals(center[1], flat[3 * i + 1], 0);
                }
                assertTrue(face[4] >= face[1], "puff up points upward in world space");
            }
        }
    }

    /**
     * The lighting basis in two shorts matches actual vertex and UV axes, including poles and mirroring. Packing
     * changes six-way lighting by less than 0.0003, under one tenth of a color step. Decode matches puffAxes/puffDirection in fx.vsh.
     */
    @Test
    void packedLightBasisMatchesTheWorldVertices() {
        FxQuads out = new FxQuads();
        Random random = new Random(622);
        Vec3[] lights = {new Vec3(0.3, 0.8, -0.2).normalize(), new Vec3(-0.7, -0.2, 0.6).normalize(),
                new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1)};
        float[][] poles = {{0, 30, 0}, {0, -30, 0}, {0.0001f, 200, -0.0002f}, {-0.0001f, -200, 0.0002f},
                {1e-10f, 30, -1e-10f}, {1e-7f, -200, -1e-7f}};
        for (int sample = 0; sample < 100; sample++) {
            float x = sample < poles.length ? poles[sample][0] : (float) (800 * random.nextDouble() - 400);
            float y = sample < poles.length ? poles[sample][1] : (float) (600 * random.nextDouble() - 300);
            float z = sample < poles.length ? poles[sample][2] : (float) (800 * random.nextDouble() - 400);
            double angle = sample < poles.length ? sample * Math.PI / 2 : random.nextDouble() * 8 * Math.PI - 4 * Math.PI;
            Vec3 center = new Vec3(x, y, z);
            for (boolean mirror : new boolean[]{false, true}) {
                out.begin(new Vector3f(1, 0, 0), new Vector3f(0, 1, 0));
                out.sixWay(0.37f, 1);
                FxAtlas.Sprite sprite = FxAtlas.puff6(7, mirror);
                out.facePosition(x, y, z, 100, 11, 17, sprite, 1, 1, 1, 0.8f, 4, 1, LightTexture.FULL_BRIGHT, 0);
                out.flat(x, y, z, 100, 11, 17, angle, sprite, 1, 1, 1, 0.8f, 4, 1, LightTexture.FULL_BRIGHT, 0);
                for (int quad = 0; quad < 2; quad++) {
                    double[] vertices = vertices(out, quad);
                    Vec3 right = edge(vertices, 0, 3).scale(mirror ? 1 : -1), up = edge(vertices, 0, 1);
                    Vec3 front = right.cross(up).normalize();
                    if (front.dot(center) > 0) front = front.scale(-1);
                    Vec3[] expected = {right, up, front};
                    int code = code(out, quad, 0);
                    LightFrame frame = decode(code, y);
                    Vec3[] got = frame.axes();
                    for (int corner = 1; corner < 4; corner++) assertEquals(code, code(out, quad, corner), "one basis for all four vertices");
                    for (int axis = 0; axis < 3; axis++) assertTrue(expected[axis].distanceTo(got[axis]) < 1.3e-4, "axis precision " + axis);
                    for (Vec3 light : lights) assertEquals(light(expected, light), light(frame.project(light)), 0.0003, "six-way lighting");
                }
            }
        }
    }

    private static int code(FxQuads quads, int at, int corner) {
        long address = quads.address(at) + 36L * corner;
        return (MemoryUtil.memGetShort(address + 28) & 0xffff) | (MemoryUtil.memGetShort(address + 30) & 0xffff) << 16;
    }

    /** The same decoding as puffAxes: two L1 circles, mirroring, elevation or a horizontal plane. */
    private static LightFrame decode(int code, double height) {
        int yawCode = (short) code, pitchCode = (short) (code >>> 16);
        double s = ((yawCode & 16383) - (yawCode & 16384)) / 16383.0, c = 1 - Math.abs(s);
        if (yawCode < 0) c = -c;
        double scale = 1 / Math.hypot(c, s);
        c *= scale;
        s *= scale;
        int pitch = (pitchCode & 16383) - (pitchCode & 16384);
        if (pitch == -16384) return new LightFrame(c, s, -1, height > 0 ? -1 : 1, pitchCode < 0);
        double sp = pitch / 16383.0, cp = 1 - Math.abs(sp);
        scale = 1 / Math.hypot(cp, sp);
        return new LightFrame(c, s, cp * scale, sp * scale, pitchCode < 0);
    }

    /** Four parameters and the scalar puffDirection projection, without a matrix or trigonometry. */
    private record LightFrame(double c, double s, double cp, double sp, boolean mirror) {
        Vec3 project(Vec3 light) {
            double right = (-c * light.x - s * light.z) * (mirror ? -1 : 1), h = -s * light.x + c * light.z;
            if (cp < 0) return new Vec3(right, h, sp * light.y);
            return new Vec3(right, cp * light.y - sp * h, -cp * h - sp * light.y);
        }

        Vec3[] axes() {
            Vec3 x = project(new Vec3(1, 0, 0)), y = project(new Vec3(0, 1, 0)), z = project(new Vec3(0, 0, 1));
            return new Vec3[]{new Vec3(x.x, y.x, z.x), new Vec3(x.y, y.y, z.y), new Vec3(x.z, y.z, z.z)};
        }
    }

    private static Vec3 edge(double[] vertices, int from, int to) {
        return new Vec3(vertices[3 * to] - vertices[3 * from], vertices[3 * to + 1] - vertices[3 * from + 1],
                vertices[3 * to + 2] - vertices[3 * from + 2]).normalize();
    }

    private static double light(Vec3[] basis, Vec3 toward) {
        return light(new Vec3(basis[0].dot(toward), basis[1].dot(toward), basis[2].dot(toward)));
    }

    private static double light(Vec3 l) {
        return (l.x > 0 ? 0.1 : 0.7) * l.x * l.x + (l.y > 0 ? 0.3 : 0.5) * l.y * l.y + (l.z < 0 ? 0.9 : 0.2) * l.z * l.z;
    }

    private static double[] vertices(FxQuads quads, int at) {
        double[] out = new double[12];
        for (int i = 0; i < 4; i++) {
            long address = quads.address(at) + 36L * i;
            for (int j = 0; j < 3; j++) out[3 * i + j] = MemoryUtil.memGetFloat(address + 4L * j);
        }
        return out;
    }
}
