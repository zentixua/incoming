package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.client.fx.layer.PuffGeometry;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.fx.smoke.GroundDust;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.util.Stability;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Цена дыма в кадре (жалоба 23.10: в дыму налёта шахедов 8 к/с, кадр 62 мс, пики 279 мс, видеокарта 91 %, без шейдеров).
 * Налёт — 14 шахедов и 2 ракеты в квадрате 240 м вокруг глаза, возраст облаков 5–150 с, ветер 4 м/с; облака раскладывает
 * тот же приёмник слоя, что в игре ({@link SmokePuffs#sink}), квадраты пишет свой приёмник. Что видно сквозь дым —
 * пропускание по лучу взгляда, e^(−Στ), а не число слоёв: с бюджетом заливки ({@link SmokePuffs#FILL_BUDGET}) кадр
 * закрывает не больше бюджета экранов, а пропускание по экрану то же, что без бюджета.
 */
class SmokeFillTest {
    /**
     * Кадр 16:9, 70° по вертикали на 1080 строк: пиксель и полудиагональ — как у кадра игры ({@code FarRenderer.view}:
     * тангенс на пиксель, край — полудиагональ).
     */
    static final double H = Math.tan(Math.toRadians(35)), W = H * 16 / 9, PIXEL = 2 * H / 1080,
            EDGE = Math.atan(0.5 * PIXEL * Math.hypot(1920, 1080));

    /** Облако модели — источник, как событие {@code FarBlasts.Event}. */
    record Blast(CloudModel model, double x, double y, double z, double born, long seed) implements SmokeGrid.Source {
        @Override
        public boolean emit(double seconds, SmokeGrid.Sink out) {
            double t = seconds - born;
            if (t < 0 || t >= model.life) return false;
            double growth = puffGrowth(seconds);
            model.blobs(t, new CloudModel.Blobs() {
                @Override
                public void blob(double bx, double by, double bz, double sh, double sv, double soot, double dust, double water, double kelvin, double rise) {
                    out.blob(x + bx, y + by, z + bz, sh, sv, soot, dust, water, 0.6f, 0.55f, 0.45f, kelvin, rise);
                }

                @Override
                public void tube(double ax, double ay, double az, double sa, double massA, double bx, double by, double bz, double sb, double massB,
                                 double kelvin, double rise) {
                    out.tube(x + ax, y + ay, z + az, sa, sa, 0, massA, x + bx, y + by, z + bz, sb, sb, 0, massB, 0.6f, 0.55f, 0.45f, kelvin, rise,
                            sa / Math.max(growth, 1));
                }
            });
            return true;
        }

        @Override
        public double puffGrowth(double seconds) {
            double t = Math.max(0, seconds - born);
            return model.sigmaH(t) / Math.max(model.sigmaH(0), 1e-6);
        }

        @Override
        public long seed() {
            return seed;
        }

        @Override
        public boolean drawsGlow() {
            return true;
        }
    }

    /**
     * World-space puffs: fill comes from four vertices; opacity comes from ray intersection with the actual plane.
     * quads retains screen metadata for count and size checks; it does not define the measured frame.
     */
    static final class Screen implements SmokePuffs.Out {
        final FarView view;
        final List<double[]> quads = new ArrayList<>();
        final List<Puff> puffs = new ArrayList<>();
        /** Многоугольники кадра: 4 вершины от глаза (12 чисел) и непрозрачность — лежащие клубы и полосы лент. */
        final List<double[]> polygons = new ArrayList<>();
        /** Квадраты лент лицом к камере (круг торца, вуаль «глаз в трубке»): середина, полуразмеры в тангенсах, непрозрачность. */
        final List<double[]> faces = new ArrayList<>();
        private final FarSprites.RibbonShape shape = new FarSprites.RibbonShape();
        private final double[] hit = new double[2];
        /** Вуаль кадра (сетка по экрану); null — её нет. */
        SmokePuffs.Veil veil;

        Screen(FarView view) {
            this.view = view;
        }

        @Override
        public void puff6(double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float shade, float r, float g,
                          float b, float a) {
            if (a < 0.002f) return;
            double[] p = new double[13];
            PuffGeometry.face(dx, dy, dz, halfA, halfB, p);
            puff(p, dx, dy, dz, halfA, halfB, sprite, mix, a, false);
        }

        @Override
        public void puff6Flat(double dx, double dy, double dz, double halfA, double halfB, double soft, double angle, FxAtlas.Sprite sprite, float mix, float shade,
                              float r, float g, float b, float a) {
            if (a < 0.002f) return;
            double[] p = new double[13];
            PuffGeometry.flat(dx, dy, dz, halfA, halfB, angle, p);
            puff(p, dx, dy, dz, halfA, halfB, sprite, mix, a, true);
            polygons.add(p);
        }

        private void puff(double[] p, double dx, double dy, double dz, double halfA, double halfB, FxAtlas.Sprite sprite, float mix, float a,
                          boolean flat) {
            p[12] = Math.min(1, a);
            puffs.add(new Puff(p, sprite, mix));
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            double depth = dx * f.x + dy * f.y + dz * f.z, safeDepth = Math.max(SmokePuffs.NEAR_FROM, depth);
            double near = Math.clamp((depth - SmokePuffs.NEAR_FROM) / SmokePuffs.NEAR_FADE, 0, 1);
            quads.add(new double[]{(dx * l.x + dy * l.y + dz * l.z) / safeDepth, (dx * up.x + dy * up.y + dz * up.z) / safeDepth,
                    halfA / safeDepth, halfB / safeDepth, Math.min(1, a) * near, PlumeContinuityTest.frameOf(sprite), flat ? 1 : 0});
        }

        @Override
        public void veil(SmokePuffs.Veil veil, int light) {
            this.veil = veil.copy();
        }

        @Override
        public boolean ribbon(double ax, double ay, double az, double halfA, float alphaA, int lightA, double bx, double by, double bz, double halfB,
                              float alphaB, int lightB, float r, float g, float b, float[] joint) {
            // лента как у FarSprites.ribbon: вуаль «глаз в трубке», круг торца, полоса по своим четырём вершинам
            FarSprites.RibbonShape q = shape;
            Vector3f f = view.forward();
            boolean strip = q.set(view.far(), f.x, f.y, f.z, ax, ay, az, halfA, alphaA, bx, by, bz, halfB, alphaB, joint);
            if (q.veil > 0.004f) face(f.x * SmokePuffs.VEIL_AT, f.y * SmokePuffs.VEIL_AT, f.z * SmokePuffs.VEIL_AT, SmokePuffs.VEIL_AT * 4, q.veil);
            if (q.capAlpha > 0) face(q.mx, q.my, q.mz, q.halfW * FarSprites.GLOW, q.capAlpha);
            if (strip) {
                // [13], [14] — непрозрачность у a и у b (полоса — линейно между ними)
                polygons.add(new double[]{q.x0 - q.px * q.ha, q.y0 - q.py * q.ha, q.z0 - q.pz * q.ha, q.x0 + q.px * q.ha, q.y0 + q.py * q.ha,
                        q.z0 + q.pz * q.ha, q.x1 + q.nx * q.hb, q.y1 + q.ny * q.hb, q.z1 + q.nz * q.hb, q.x1 - q.nx * q.hb, q.y1 - q.ny * q.hb,
                        q.z1 - q.nz * q.hb, 0.5 * (Math.min(1, q.alphaA) + Math.min(1, q.alphaB)), Math.min(1, q.alphaA), Math.min(1, q.alphaB)});
                joint[0] = q.nx;
                joint[1] = q.ny;
                joint[2] = q.nz;
            } else if (q.cap) joint[0] = Float.NaN;
            return strip;
        }

        /** Внутри ли проекции многоугольника p (4 вершины перед глазом) точка экрана (u, v). */
        boolean insideProjected(double[] p, double u, double v) {
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            double[] pu = new double[4], pv = new double[4];
            for (int i = 0; i < 4; i++) {
                double x = p[3 * i], y = p[3 * i + 1], z = p[3 * i + 2], depth = x * f.x + y * f.y + z * f.z;
                if (depth <= 0.05) return false;
                pu[i] = (x * l.x + y * l.y + z * l.z) / depth;
                pv[i] = (x * up.x + y * up.y + z * up.z) / depth;
            }
            int sign = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                double c = (pu[j] - pu[i]) * (v - pv[i]) - (pv[j] - pv[i]) * (u - pu[i]);
                int sg = c > 0 ? 1 : c < 0 ? -1 : 0;
                if (sg == 0) continue;
                if (sign == 0) sign = sg;
                else if (sg != sign) return false;
            }
            return true;
        }

        /** Новый кадр: квадраты, многоугольники и вуаль — заново. */
        void clear() {
            quads.clear();
            puffs.clear();
            polygons.clear();
            faces.clear();
            veil = null;
        }

        /** Квадрат лицом к камере с серединой (x, y, z) от глаза и полуразмером half — в кадр. */
        private void face(double x, double y, double z, double half, double a) {
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            double depth = x * f.x + y * f.y + z * f.z;
            if (depth <= 0) return;
            faces.add(new double[]{(x * l.x + y * l.y + z * l.z) / depth, (x * up.x + y * up.y + z * up.z) / depth, half / depth, half / depth, Math.min(1, a)});
        }

        /** Screen area covered by geometry, including overlap: count each puff and ribbon exactly once. */
        double fill() {
            double sum = 0;
            for (Puff p : puffs) sum += SmokePuffs.polygonFill(view, java.util.Arrays.copyOf(p.vertices, 12));
            for (double[] q : faces) {
                double x0 = Math.max(-W, q[0] - q[2]), x1 = Math.min(W, q[0] + q[2]), y0 = Math.max(-H, q[1] - q[3]), y1 = Math.min(H, q[1] + q[3]);
                if (x1 > x0 && y1 > y0) sum += (x1 - x0) * (y1 - y0) / (4 * W * H);
            }
            // Horizontal puffs also remain in polygons for existing ribbon checks, but were already counted above.
            for (double[] p : polygons) if (p.length >= 15) sum += SmokePuffs.polygonFill(view, java.util.Arrays.copyOf(p, 12));
            return sum;
        }

        /** Пропускание в точке экрана (u, v): квадраты — радиальный профиль непрозрачности кадра клуба, вуали. */
        double transmittance(double u, double v, double[][] prof) {
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            return transmittanceRay(f.x + u * l.x + v * up.x, f.y + u * l.y + v * up.y, f.z + u * l.z + v * up.z, prof);
        }

        /** The same world ray under different camera rotations: the puff plane, not the screen, defines the hit and UV. */
        double transmittanceRay(double rx, double ry, double rz, double[][] prof) {
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            double depth = rx * f.x + ry * f.y + rz * f.z;
            double u = (rx * l.x + ry * l.y + rz * l.z) / depth, v = (rx * up.x + ry * up.y + rz * up.z) / depth;
            double clear = surroundings(u, v);
            for (Puff p : puffs) {
                double alpha = p.hit(rx, ry, rz, f, hit);
                if (alpha <= 0) continue;
                double radius = Math.hypot(hit[0], hit[1]);
                double a0 = profile(prof[p.frame], radius), a1 = profile(prof[Math.min(p.frame + 1, prof.length - 1)], radius);
                clear *= 1 - alpha * (a0 + (a1 - a0) * p.mix);
            }
            return clear;
        }

        /** Additional measurement using actual 2D alpha and motion vectors, with bilinear sampling and no mipmaps. */
        double texturedTransmittanceRay(double rx, double ry, double rz) throws IOException {
            Vector3f f = view.forward(), l = view.left(), up = view.up();
            double depth = rx * f.x + ry * f.y + rz * f.z;
            double u = (rx * l.x + ry * l.y + rz * l.z) / depth, v = (rx * up.x + ry * up.y + rz * up.z) / depth;
            double clear = surroundings(u, v);
            for (Puff p : puffs) {
                double alpha = p.hit(rx, ry, rz, f, hit);
                if (alpha > 0) clear *= 1 - alpha * Baked.get(p.sprite).alpha(hit[0], hit[1], p.mix);
            }
            return clear;
        }

        private double surroundings(double u, double v) {
            double clear = veil == null ? 1 : veil.through(u, v);
            for (double[] q : faces) if (Math.abs(u - q[0]) <= q[2] && Math.abs(v - q[1]) <= q[3]) clear *= 1 - q[4];
            for (double[] p : polygons) {
                // Ribbon strips have uniform interior opacity; puffs are handled below by intersection with their plane.
                if (p[12] <= 0 || p.length < 15) continue;
                if (insideProjected(p, u, v)) clear *= 1 - p[12];
            }
            return clear;
        }

        /** The profile stores ring means: interpolate between ring centers, without a jump at each BIN. */
        private static double profile(double[] profile, double radius) {
            double at = Math.max(0, radius / PlumeContinuityTest.BIN - 0.5);
            int bin = (int) at;
            if (bin >= profile.length) return 0;
            double next = bin + 1 < profile.length ? profile[bin + 1] : 0;
            return profile[bin] + (next - profile[bin]) * (at - bin);
        }
    }

    /** Source puff maps: the same 2D alpha, mirroring and frame displacement as shader sixPlaces/sixAlpha. */
    private record Baked(BufferedImage a, BufferedImage next, BufferedImage flow, boolean mirror) {
        private static final Map<FxAtlas.Sprite, Baked> CACHE = new HashMap<>();

        static Baked get(FxAtlas.Sprite sprite) throws IOException {
            Baked found = CACHE.get(sprite);
            if (found != null) return found;
            for (int frame = 0; frame < FxAtlas.PUFF6_FRAMES; frame++) {
                for (boolean mirror : new boolean[]{false, true}) {
                    String prefix = null;
                    for (SmokeGrid.Look look : SmokeGrid.Look.values()) {
                        if (!FxAtlas.club(look, frame, mirror).equals(sprite)) continue;
                        prefix = switch (look) {
                            case SMOKE -> "puff";
                            case EJECTA -> "eject";
                            case CRUSH -> "crush";
                            case AIRBURST -> "air";
                        };
                    }
                    if (FxAtlas.spray6(frame, mirror).equals(sprite)) prefix = "spray";
                    if (prefix == null) continue;
                    Baked baked = new Baked(image(prefix, 'a', frame), image(prefix, 'a', Math.min(frame + 1, FxAtlas.PUFF6_FRAMES - 1)),
                            image(prefix, 'v', frame), mirror);
                    CACHE.put(sprite, baked);
                    return baked;
                }
            }
            throw new IllegalArgumentException("not a baked puff: " + sprite);
        }

        private static BufferedImage image(String prefix, char map, int frame) throws IOException {
            String path = String.format(Locale.ROOT, "/assets/incoming/textures/fx/particle/%s6%c_%02d.png", prefix, map, frame);
            try (InputStream in = SmokeFillTest.class.getResourceAsStream(path)) {
                if (in == null) throw new IOException("missing puff map " + path);
                return ImageIO.read(in);
            }
        }

        double alpha(double x, double y, double mix) {
            double u = (1 + (mirror ? x : -x)) * 0.5, v = (1 - y) * 0.5;
            double fa = channel(flow, u, v, 24), scale = 2 * FxAtlas.PUFF6_FLOW / flow.getWidth();
            double fu = (channel(flow, u, v, 16) / Math.max(fa, 1e-3) - 0.5) * scale;
            double fv = (channel(flow, u, v, 8) / Math.max(fa, 1e-3) - 0.5) * scale;
            double a0 = channel(a, u - fu * mix, v - fv * mix, 24);
            double a1 = channel(next, u + fu * (1 - mix), v + fv * (1 - mix), 24);
            return a0 + (a1 - a0) * mix;
        }

        /** Bilinear sampling with a half-texel inset; color is premultiplied, as in the FxAtlas atlas. */
        private static double channel(BufferedImage image, double u, double v, int shift) {
            double x = Math.clamp(u * image.getWidth() - 0.5, 0, image.getWidth() - 1);
            double y = Math.clamp(v * image.getHeight() - 0.5, 0, image.getHeight() - 1);
            int x0 = (int) x, y0 = (int) y, x1 = Math.min(x0 + 1, image.getWidth() - 1), y1 = Math.min(y0 + 1, image.getHeight() - 1);
            double a = pixel(image, x0, y0, shift), b = pixel(image, x1, y0, shift);
            double c = pixel(image, x0, y1, shift), d = pixel(image, x1, y1, shift);
            return (a + (b - a) * (x - x0)) * (1 - y + y0) + (c + (d - c) * (x - x0)) * (y - y0);
        }

        private static double pixel(BufferedImage image, int x, int y, int shift) {
            int pixel = image.getRGB(x, y);
            double alpha = (pixel >>> 24) / 255.0;
            return shift == 24 ? alpha : ((pixel >>> shift) & 255) / 255.0 * alpha;
        }
    }

    /** Puff plane and hit-to-UV transform; a test-only model of the layer sink. */
    static final class Puff {
        final double[] vertices;
        final FxAtlas.Sprite sprite;
        final int frame;
        final double mix;
        private final double nx, ny, nz, plane, ux, uy, uz, vx, vy, vz, cu, cv;

        Puff(double[] p, FxAtlas.Sprite sprite, double mix) {
            this.vertices = p;
            this.sprite = sprite;
            this.frame = PlumeContinuityTest.frameOf(sprite);
            this.mix = mix;
            double cx = 0.5 * (p[0] + p[6]), cy = 0.5 * (p[1] + p[7]), cz = 0.5 * (p[2] + p[8]);
            double ax = 0.5 * (p[9] - p[0]), ay = 0.5 * (p[10] - p[1]), az = 0.5 * (p[11] - p[2]);
            double bx = 0.5 * (p[3] - p[0]), by = 0.5 * (p[4] - p[1]), bz = 0.5 * (p[5] - p[2]);
            nx = ay * bz - az * by;
            ny = az * bx - ax * bz;
            nz = ax * by - ay * bx;
            plane = cx * nx + cy * ny + cz * nz;
            double aa = ax * ax + ay * ay + az * az, bb = bx * bx + by * by + bz * bz;
            ux = ax / aa;
            uy = ay / aa;
            uz = az / aa;
            vx = bx / bb;
            vy = by / bb;
            vz = bz / bb;
            cu = cx * ux + cy * uy + cz * uz;
            cv = cx * vx + cy * vy + cz * vz;
        }

        /** Vertex opacity with the shader near fade; out receives local hit coordinates (−1…1). */
        double hit(double rx, double ry, double rz, Vector3f forward, double[] out) {
            double facing = rx * nx + ry * ny + rz * nz;
            if (Math.abs(facing) < 1e-12) return 0;
            double t = plane / facing;
            if (t <= 0) return 0;
            double u = t * (rx * ux + ry * uy + rz * uz) - cu, v = t * (rx * vx + ry * vy + rz * vz) - cv;
            if (Math.abs(u) > 1 || Math.abs(v) > 1) return 0;
            out[0] = u;
            out[1] = v;
            double depth = t * (rx * forward.x + ry * forward.y + rz * forward.z);
            return vertices[12] * Math.clamp((depth - SmokePuffs.NEAR_FROM) / SmokePuffs.NEAR_FADE, 0, 1);
        }
    }

    /** A fixed world ray hits the same puff UV while the view rotates, including camera roll. */
    @Test
    void fixedPuffKeepsWorldRayTransmittanceWhenCameraTurns() throws IOException {
        double[][] profile = PlumeContinuityTest.profiles();
        for (boolean flat : new boolean[]{false, true}) {
            Screen first = fixedPuff(view(new Vec3(0, 80, 0), new Vector3f(0, 0, 1)), flat);
            double[] vertices = first.puffs.getFirst().vertices;
            double[] center = {12, -8, 90};
            double least = 1, most = 0;
            for (int turn = -3; turn <= 3; turn++) {
                Vector3f f = new Vector3f(0, 0, 1).rotateY(turn * 0.23f).rotateX(turn * 0.09f);
                FarView v = view(new Vec3(0, 80, 0), f);
                v.left().rotateAxis(turn * 0.19f, f.x, f.y, f.z);
                v.up().rotateAxis(turn * 0.19f, f.x, f.y, f.z);
                Screen moved = fixedPuff(v, flat);
                assertArrayEquals(vertices, moved.puffs.getFirst().vertices, 0, "world-space puff vertices");
                assertEquals(SmokePuffs.polygonFill(v, java.util.Arrays.copyOf(vertices, 12)), moved.fill(), 1e-12,
                        "puff area is counted once, from its vertices");
                for (int y = -4; y <= 4; y++) {
                    for (int x = -4; x <= 4; x++) {
                        double[] ray = center.clone();
                        for (int k = 0; k < 3; k++) {
                            ray[k] += 0.1 * x * (vertices[9 + k] - vertices[k]) + 0.1 * y * (vertices[3 + k] - vertices[k]);
                        }
                        double radial = first.transmittanceRay(ray[0], ray[1], ray[2], profile);
                        double textured = first.texturedTransmittanceRay(ray[0], ray[1], ray[2]);
                        least = Math.min(least, textured);
                        most = Math.max(most, textured);
                        assertEquals(radial, moved.transmittanceRay(ray[0], ray[1], ray[2], profile), 1e-12, "radial profile, rotation " + turn);
                        assertEquals(textured, moved.texturedTransmittanceRay(ray[0], ray[1], ray[2]), 1e-12, "2D alpha, rotation " + turn);
                    }
                }
            }
            assertTrue(most - least > 0.2, "both the transparent edge and dense center are covered");
        }
    }

    private static Screen fixedPuff(FarView view, boolean flat) {
        Screen out = new Screen(view);
        FxAtlas.Sprite sprite = FxAtlas.puff6(7, true);
        if (flat) out.puff6Flat(12, -8, 90, 34, 26, 5, 0.73, sprite, 0.37f, 0, 1, 1, 1, 0.8f);
        else out.puff6(12, -8, 90, 24, 17, sprite, 0.37f, 1, 1, 1, 1, 0.8f);
        return out;
    }

    /** Лежит ли многоугольник (все вершины на одной высоте — лежащий клуб, а не полоса ленты). */
    static boolean isFlat(double[] p) {
        return p[1] == p[4] && p[4] == p[7] && p[7] == p[10];
    }

    static FarView view(Vec3 eye, Vector3f fwd) {
        fwd.normalize();
        Vector3f left = new Vector3f(0, 1, 0).cross(fwd, new Vector3f()).normalize(), up = fwd.cross(left, new Vector3f()).normalize();
        return new FarView(eye, fwd, left, up, 0, 1000, PIXEL, EDGE, 1, 1, new Vector3f(1, 1, 1), 32000, 192, 192, 0,
                Sight.Air.at(0, 20000));
    }

    /** Налёт: 14 шахедов и 2 ракеты в квадрате 240 м, 40 % — в крыши на 10–40 м, возраст 5–150 с, ветер 4 м/с (класс D). */
    static List<Blast> raid(double now) {
        Random rnd = new Random(7);
        CloudModel.Air air = new CloudModel.Air(Stability.STANDARD_N, 4, 1, 0.15, 3, 0.5);
        List<Blast> out = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            WeaponType w = i < 2 ? WeaponType.MISSILE : WeaponType.DRONE;
            double hob = rnd.nextDouble() < 0.4 ? 10 + 30 * rnd.nextDouble() : 0;
            CloudModel m = new CloudModel(BlastSource.of(w, false), air, false, hob, GroundDust.Ground.BARE);
            double x = (rnd.nextDouble() - 0.5) * 240, z = (rnd.nextDouble() - 0.5) * 240;
            out.add(new Blast(m, x, hob, z, now - 5 - 145 * rnd.nextDouble(), rnd.nextLong()));
        }
        return out;
    }

    /**
     * Пропускание самого вещества налёта в точке экрана (u, v) из глаза кадра {@code view}: e^(−Στ), τ — толща по лучу
     * через каждый гаусс облаков модели ({@link SmokePuffs#sightDepth}; ножка — гауссами трубки), без квадратов. С ним
     * сверяются оба кадра — полный и в бюджете.
     */
    static double truth(List<Blast> raid, double now, FarView view, double u, double v) {
        Vector3f f = view.forward(), l = view.left(), up = view.up();
        double rx = f.x + u * l.x + v * up.x, ry = f.y + u * l.y + v * up.y, rz = f.z + u * l.z + v * up.z, n = Math.sqrt(rx * rx + ry * ry + rz * rz);
        double fx = rx / n, fy = ry / n, fz = rz / n, kd = SmokeGrid.dustK(0.6f, 0.55f, 0.45f);
        double[] tau = new double[1];
        Vec3 eye = view.camera();
        for (Blast b : raid) {
            double t = now - b.born();
            if (t < 0 || t >= b.model().life) continue;
            b.model().blobs(t, (x, y, z, sh, sv, soot, dust, water, kelvin, rise) -> {
                // светящийся газ — тело огня, не дым (как у приёмника слоя): доля дыма — у непрозрачности клуба, не у массы
                double smoke = SmokePuffs.smokeShare(true, kelvin, view.skyLuminance());
                double ext = CloudModel.SOOT_K * soot + kd * dust;
                if (ext > 0 && smoke > 0) {
                    double along = SmokePuffs.sightDepth(b.x() + x - eye.x, b.y() + y - eye.y, b.z() + z - eye.z, sh, sv, ext, fx, fy, fz);
                    tau[0] -= Math.log(1 - Math.min(smoke * (1 - Math.exp(-along)), 1 - 1e-12));
                }
            });
        }
        return Math.exp(-tau[0]);
    }

    /** Кадр налёта из глаза eye в сторону fwd с бюджетом заливки budget. */
    static Screen frame(List<Blast> raid, double now, Vec3 eye, Vector3f fwd, double budget) {
        FarView v = view(eye, new Vector3f(fwd));
        Screen s = new Screen(v);
        double was = SmokePuffs.budget;
        SmokePuffs.budget = budget;
        try {
            // два кадра: доля кучек трубок — из прошлого кадра
            SmokePuffs.resetBudget();
            for (int frame = 0; frame < 2; frame++) {
                s.clear();
                for (Blast b : raid) b.emit(now, SmokePuffs.sink(v, s, b, now));
                SmokePuffs.flush();
            }
        } finally {
            SmokePuffs.budget = was;
            SmokePuffs.resetBudget();
        }
        return s;
    }

    /**
     * Доля дыма у тела огня — у непрозрачности, а не у массы (live 24.10): у клуба шара сотни оптических толщ, и масса × доля
     * давала сплошную сажу уже при доле 1 % — скачок за ~0,1 с, тёмный ореол перед шаром ракеты днём. Густой клуб (τ ≈ 200
     * поперёк) с долей дыма 10 % закрывает середину не меньше доли и не больше 0,35, а не целиком (прежде — ~1). Сверх
     * доли — кучка: доли клубов считаются по перекрытию площадей (много × размер²), а середину накрывают все клубы кучки.
     */
    @Test
    void glowShareScalesOpacityNotMass() throws IOException {
        double lo = 798, hi = 3000;
        for (int it = 0; it < 60; it++) {
            double mid = 0.5 * (lo + hi);
            if (CloudModel.glowShare(mid, 1) < 0.9) lo = mid;
            else hi = mid;
        }
        double kelvin = 0.5 * (lo + hi), share = SmokePuffs.smokeShare(true, kelvin, 1);
        assertTrue(Math.abs(share - 0.1) < 1e-3, "доля дыма " + share);
        double sigma = 5, soot = 200 * 2 * Math.PI * sigma * sigma / CloudModel.SOOT_K;
        SmokeGrid.Source ball = new SmokeGrid.Source() {
            @Override
            public boolean emit(double seconds, SmokeGrid.Sink out) {
                out.blob(60, 40, 0, sigma, sigma, soot, 0, 0, 0.6f, 0.55f, 0.45f, kelvin, 0);
                return true;
            }

            @Override
            public double puffGrowth(double seconds) {
                return 1;
            }

            @Override
            public long seed() {
                return 3;
            }

            @Override
            public boolean drawsGlow() {
                return true;
            }
        };
        FarView v = view(new Vec3(0, 40, 0), new Vector3f(1, 0, 0));
        Screen s = new Screen(v);
        SmokePuffs.resetBudget();
        ball.emit(0, SmokePuffs.sink(v, s, ball, 0));
        SmokePuffs.flush();
        SmokePuffs.resetBudget();
        double covered = 1 - s.transmittance(0, 0, PlumeContinuityTest.profiles());
        assertTrue(covered >= share - 0.01 && covered <= 0.35, "середина клуба с долей дыма " + share + " закрыта на " + covered);
    }

    /**
     * Глаз на крыше в дыму налёта и рядом с облаками, взгляд вдоль дыма. При настоящем бюджете налёт из 16 ударов в него
     * ложится как есть — без слияния и вуали (вид прежний). Сам предел — на вчетверо меньшем бюджете: без него кадр
     * закрывает больше двух таких бюджетов, с ним — не больше бюджета (с запасом на оценку площади кучки, 15 %; пол — по
     * одному клубу на гаусс — не выше бюджета) и меньше вдвое; пропускание по экрану в среднем и в каждой из 18 частей кадра
     * от пропускания самого вещества модели ({@link #truth}) — не дальше, чем у полного кадра, больше чем на 0,04 и 0,05
     * (мельче части — подробность клубов: её бюджет и отдаёт). Налёт из 100 — {@code SmokeRaidTest}.
     */
    @Test
    void raidSmokeStaysInTheFillBudgetAtTheSameDensity() throws IOException {
        double now = 1000, half = SmokePuffs.FILL_BUDGET / 4;
        List<Blast> raid = raid(now);
        double[][] prof = PlumeContinuityTest.profiles();
        Vector3f along = new Vector3f(1, 0, 0);
        for (Vec3 eye : new Vec3[]{new Vec3(0, 40, 0), new Vec3(raid.get(3).x(), 25, raid.get(3).z() + 20), new Vec3(raid.get(5).x() + 5, 60, raid.get(5).z())}) {
            frame(raid, now, eye, along, SmokePuffs.FILL_BUDGET);
            double raw = SmokePuffs.lastRaw(), whole = SmokePuffs.lastFloor();
            Screen free = frame(raid, now, eye, along, Double.POSITIVE_INFINITY), budget = frame(raid, now, eye, along, half);
            double meanFree = 0, meanBudget = 0;
            int n = 0;
            for (int i = 0; i < 48; i++) {
                for (int j = 0; j < 27; j++) {
                    double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                    meanFree += free.transmittance(u, v, prof);
                    meanBudget += budget.transmittance(u, v, prof);
                    n++;
                }
            }
            meanFree /= n;
            meanBudget /= n;
            // подробность клубов мельче части кадра — её бюджет и отдаёт, плотность — по частям; оба кадра — против вещества
            // модели (без квадратов): бюджет не дальше от него, чем полный кадр, больше чем на шум подробности
            double floor = SmokePuffs.lastFloor();
            double[] real = new double[18];
            FarView v0 = view(eye, new Vector3f(along));
            for (int i = 0; i < 48; i++) {
                for (int j = 0; j < 27; j++) {
                    double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                    real[i / 8 * 3 + j / 9] += truth(raid, now, v0, u, v) / 72;
                }
            }
            double worstFree = worstBlock(free, real, prof), worst = worstBlock(budget, real, prof), meanReal = 0;
            for (double r : real) meanReal += r / real.length;
            String got = String.format(Locale.ROOT, "глаз %s: при бюджете %.0f пол %.1f (до слияния %.1f); без бюджета %d квадратов, %.1f экранов, пропускание %.3f; с бюджетом %.0f — %d, %.1f (пол %.1f), %.3f; худшая часть против вещества: полный %.3f, в бюджете %.3f",
                    eye, SmokePuffs.FILL_BUDGET, whole, raw, free.quads.size(), free.fill(), meanFree, half, budget.quads.size(), budget.fill(), floor, meanBudget,
                    worstFree, worst);
            System.out.println("FILL " + got);
            assertTrue(raw <= SmokePuffs.FILL_BUDGET && whole == raw, got);
            assertTrue(free.fill() > 2 * half, got);
            assertTrue(budget.fill() <= 1.15 * half && floor <= half + 1e-9, got);
            assertTrue(budget.fill() < free.fill() / 2, got);
            assertTrue(Math.abs(meanBudget - meanReal) <= Math.abs(meanFree - meanReal) + 0.04, got + "; вещество в среднем " + meanReal);
            assertTrue(worst <= worstFree + 0.05, got);
        }
    }

    /**
     * Залп в грунт через полсекунды (струи завесы выброса в воздухе у каждого из 16 ударов, кадры удара в пыль): кадр — в том
     * же бюджете заливки, что облака налёта (не больше бюджета или его пола, с тем же запасом 15 %); новых квадратов сверх
     * бюджета завеса не даёт.
     */
    @Test
    void ejectaCurtainsStayInTheFillBudget() {
        double now = 1000;
        Random rnd = new Random(11);
        List<SmokeGrid.Source> salvo = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            double x = (rnd.nextDouble() - 0.5) * 80, z = 40 + rnd.nextDouble() * 80;
            long seed = rnd.nextLong();
            ua.zentix.incoming.client.fx.smoke.BlastTarget t = ua.zentix.incoming.client.fx.smoke.BlastTarget.SOIL;
            ua.zentix.incoming.client.fx.smoke.Ejecta c = new ua.zentix.incoming.client.fx.smoke.Ejecta(
                    ua.zentix.incoming.nuclear.model.CraterModel.chargeRadius(50, ua.zentix.incoming.nuclear.model.CraterModel.Soil.DRY),
                    t.mass(50) * (1 - CloudModel.DUST_LOFTED), t.matter().clods);
            double k = ua.zentix.incoming.client.fx.smoke.BlastTarget.Matter.DIRT.extinction() / SmokeGrid.dustK(0.2f, 0.12f, 0.06f);
            salvo.add(new SmokeGrid.Source() {
                @Override
                public boolean emit(double seconds, SmokeGrid.Sink out) {
                    FarBlasts.curtainClubs(c, 0.5, seed, x, 0, z, k, 0.2f, 0.12f, 0.06f, out);
                    return true;
                }

                @Override
                public long seed() {
                    return seed;
                }
            });
        }
        FarView v = view(new Vec3(0, 2, 0), new Vector3f(0, 0, 1));
        for (double budget : new double[]{Double.POSITIVE_INFINITY, SmokePuffs.FILL_BUDGET}) {
            Screen s = new Screen(v);
            double was = SmokePuffs.budget;
            SmokePuffs.budget = budget;
            try {
                SmokePuffs.resetBudget();
                for (SmokeGrid.Source src : salvo) src.emit(now, SmokePuffs.sink(v, s, src, now));
                SmokePuffs.flush();
            } finally {
                SmokePuffs.budget = was;
                SmokePuffs.resetBudget();
            }
            assertTrue(!s.quads.isEmpty(), "завеса видна");
            if (budget < Double.POSITIVE_INFINITY) {
                double floor = SmokePuffs.lastFloor();
                assertTrue(s.fill() <= 1.15 * Math.max(SmokePuffs.FILL_BUDGET, floor), "заливка " + s.fill() + ", пол " + floor);
            }
        }
    }

    /** Клубы разного вида (дым и завеса выброса) — разные кадры: в один гаусс не сливаются, даже лёжа друг на друге. */
    @Test
    void differentLooksDoNotMerge() {
        int f = SmokePuffs.FIELDS;
        double[] held = new double[2 * f];
        for (int i = 0; i < 2; i++) {
            held[i * f + 1] = 10;
            held[i * f + 3] = held[i * f + 4] = 4;
            held[i * f + 6] = 2;
            held[i * f + 8] = 0.6;
            held[i * f + 9] = 0.55;
            held[i * f + 10] = 0.45;
        }
        held[f + 15] = SmokeGrid.Look.EJECTA.ordinal();
        boolean[] gone = new boolean[2];
        SmokePuffs.merge(held, 0, 2, gone, CloudModel.SOOT_K, SmokeGrid.DROP_K);
        assertTrue(!gone[0] && !gone[1], "дым и завеса не слились");
        held[f + 15] = SmokeGrid.Look.SMOKE.ordinal();
        SmokePuffs.merge(held, 0, 2, gone, CloudModel.SOOT_K, SmokeGrid.DROP_K);
        assertTrue(gone[1], "одного вида — слились");
    }

    /**
     * Слияние близких гауссов держит массу и моменты: два равных клуба на расстоянии σ — один с суммой масс, серединой
     * посередине и σ² = σ² + (σ/2)² по оси разноса (по горизонтали — половина: σh общий у двух осей); дальше 2σ — не
     * сливаются никогда, дальше σ — только когда кадр не укладывается в бюджет.
     */
    /**
     * Подробность кучек — случайная (зерно облака): смена зерна меняет пропускание по частям кадра не больше чем на 0,08 —
     * от этого шума и мерится, что бюджет плотность не трогает ({@link #raidSmokeStaysInTheFillBudgetAtTheSameDensity}: +0,05).
     * Клуб — шапка облака взрыва сверху (tools/bake_fx, puff-vdb): густой диск (покрытие по хорде 0,71–0,80 полуразмера) с
     * резким клубящимся краем, а не прежний пушистый шар (0,48–0,82), поэтому место клуба меняет пропускание части сильнее,
     * чем прежние 0,05; против вещества модели кадр при этом ближе (худшая часть 0,20–0,23 против 0,38–0,43).
     */
    @Test
    void clusterDetailNoise() throws IOException {
        double now = 1000;
        List<Blast> raid = raid(now), other = new ArrayList<>();
        for (Blast b : raid) other.add(new Blast(b.model(), b.x(), b.y(), b.z(), b.born(), b.seed() * 31 + 7));
        double[][] prof = PlumeContinuityTest.profiles();
        Screen a = frame(raid, now, new Vec3(0, 40, 0), new Vector3f(1, 0, 0), Double.POSITIVE_INFINITY);
        Screen b = frame(other, now, new Vec3(0, 40, 0), new Vector3f(1, 0, 0), Double.POSITIVE_INFINITY);
        double worst = worstBlock(a, b, prof);
        assertTrue(worst <= 0.08, "зерно: худшая часть кадра " + worst);
    }

    /** Наибольшая разница среднего пропускания двух кадров по 18 частям (8 × 9 узлов сетки 48×27). */
    static double worstBlock(Screen a, Screen b, double[][] prof) {
        return worstBlock(a, blocks(b, prof), prof);
    }

    /** То же против средних по частям {@code ref}. */
    static double worstBlock(Screen a, double[] ref, double[][] prof) {
        double[] ba = blocks(a, prof);
        double worst = 0;
        for (int k = 0; k < 18; k++) worst = Math.max(worst, Math.abs(ba[k] - ref[k]));
        return worst;
    }

    /** Среднее пропускание кадра по 18 частям. */
    static double[] blocks(Screen a, double[][] prof) {
        double[] ba = new double[18];
        for (int i = 0; i < 48; i++) {
            for (int j = 0; j < 27; j++) {
                double u = -W + (i + 0.5) * 2 * W / 48, v = -H + (j + 0.5) * 2 * H / 27;
                ba[i / 8 * 3 + j / 9] += a.transmittance(u, v, prof) / 72;
            }
        }
        return ba;
    }

    @Test
    void mergeKeepsMassAndMoments() {
        int f = SmokePuffs.FIELDS;
        double[] held = new double[3 * f];
        double[][] blobs = {{0, 10, 0, 4, 4, 1, 2, 0}, {4, 10, 0, 4, 4, 1, 2, 0}, {40, 10, 0, 4, 4, 1, 2, 0}};
        for (int i = 0; i < 3; i++) {
            System.arraycopy(blobs[i], 0, held, i * f, 8);
            held[i * f + 8] = 0.6;
            held[i * f + 9] = 0.55;
            held[i * f + 10] = 0.45;
            held[i * f + 13] = 1;
        }
        boolean[] gone = new boolean[3];
        SmokePuffs.merge(held, 0, 3, gone, CloudModel.SOOT_K, SmokeGrid.DROP_K);
        assertTrue(!gone[0] && gone[1] && !gone[2], "слит только близкий");
        assertTrue(Math.abs(held[0] - 2) < 1e-9 && Math.abs(held[5] - 2) < 1e-9 && Math.abs(held[6] - 4) < 1e-9, "середина и массы");
        assertTrue(Math.abs(held[3] - Math.sqrt(16 + 0.5 * 4)) < 1e-9, "σh² = σh² + половина разноса²: " + held[3]);
        assertTrue(Math.abs(held[4] - 4) < 1e-9, "σv та же");
    }
}
