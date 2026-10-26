package ua.zentix.incoming.client.far;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.client.fx.layer.FxQuads;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.client.fx.layer.FxAtlas;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.util.Air;
import ua.zentix.incoming.util.Stability;
import ua.zentix.incoming.util.Wind;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Long-range SAM package → track points → real squares SmokePuffs → alpha pixels with maps from jar. */
class SamTrailScreenTest {
    @Test
    void distantSustainerLeavesAPixelTrailAt15Through60Seconds() throws Exception {
        UUID id = UUID.randomUUID();
        Air before = FarFlightView.air;
        Air air = new Air(new Wind(-0.733, 10.6), Stability.C, 0.5);
        try {
            // In the game, this part is already behind the client entity. It is important to route the packet, not just TrailModel.look.
            Method receive = FlightTracks.class.getDeclaredMethod("receive", S2C.FarFlight.class, long.class);
            receive.setAccessible(true);
            for (int tick = 32; tick <= 120; tick += S2C.FarFlights.FAR_PERIOD) {
                Vec3 p = new Vec3(0, 150, -14 * tick);
                receive.invoke(null, new S2C.FarFlight(id, S2C.FarFlight.INTERCEPTOR, false, false, false, p,
                        new Vec3(0, 0, -14), 180, 0, 0, FlightPhase.CRUISE.ordinal(), tick - 30,
                        new Vec3(0, 150, -10000), Float.NaN, tick == 120), (long) tick);
            }
            FlightTrack track = FlightTracks.interceptor(id);
            assertNotNull(track);
            assertNull(track.weapon);
            FarFlightView.air = air;
            TrailPoints points = new TrailPoints();
            FarFlightView.sample(new FarFlightView.Far(track, 1), 120, points);
            assertTrue(points.end() - points.first() >= 40, "the marching section beyond the entity boundary is recorded");
            FarView view = view();
            for (double seconds : new double[]{15, 30, 60}) {
                Screen screen = new Screen(view);
                FarFlightView.collectTrails(view, screen.sprites, 120 + 20 * seconds, points, air);
                // billows trail locations waiting for frame plan: frame over
                SmokePuffs.flush();
                screen.raster();
                double peak = 0;
                int visible = 0;
                for (double a : screen.alpha) { peak = Math.max(peak, a); if (a >= 0.02) visible++; }
                System.out.printf("SAM pixels %.0f s: peak alpha %.3f, visible pixels %d, quads %d%n", seconds, peak, visible, screen.quads);
                assertTrue(peak > 0.04 && visible >= 30, "the marching trail is visible in many pixels on " + seconds + " s");
            }
        } finally {
            FlightTracks.reset();
            FarFlightView.reset();
            FarFlightView.air = before;
        }
    }

    private static FarView view() {
        Vec3 eye = new Vec3(-150, 100, 150), aim = new Vec3(300, 170, -700);
        Vector3f f = aim.subtract(eye).toVector3f().normalize(), left = new Vector3f(0, 1, 0).cross(f, new Vector3f()).normalize();
        Vector3f up = f.cross(left, new Vector3f()).normalize();
        return new FarView(eye, f, left, up, 0, 1000, 2 * Math.tan(Math.toRadians(35)) / Screen.H,
                Math.toRadians(55), 1, 1, new Vector3f(1, 1, 1), 8000, 192, 192, 0, Sight.Air.at(0, 20000));
    }

    /** Raster of real vertices FxQuads (including byte alpha), without GL and video card. */
    private static final class Screen {
        static final int W = 640, H = 360;
        final double[] alpha = new double[W * H];
        final FarView view;
        final FxQuads buffer = new FxQuads();
        final FarSprites sprites = new FarSprites();
        int quads;
        Screen(FarView view) {
            this.view = view;
            buffer.begin(view.left(), view.up());
            sprites.begin(view, buffer, null);
        }

        void raster() throws Exception {
            var field = FxQuads.class.getDeclaredField("stage");
            field.setAccessible(true);
            long data = field.getLong(buffer);
            quads = buffer.size();
            try {
                for (int q = 0; q < quads; q++) {
                    double[][] v = new double[4][6];
                    long start = data + q * 4L * 36;
                    boolean front = true;
                    for (int k = 0; k < 4; k++) {
                        long p = start + k * 36L;
                        double x = MemoryUtil.memGetFloat(p), y = MemoryUtil.memGetFloat(p + 4), z = MemoryUtil.memGetFloat(p + 8);
                        double d = x * view.forward().x + y * view.forward().y + z * view.forward().z;
                        if (d <= 0) { front = false; break; }
                        v[k][0] = W / 2.0 - (x * view.left().x + y * view.left().y + z * view.left().z) / (d * view.pixel());
                        v[k][1] = H / 2.0 - (x * view.up().x + y * view.up().y + z * view.up().z) / (d * view.pixel());
                        v[k][2] = MemoryUtil.memGetFloat(p + 12);
                        v[k][3] = MemoryUtil.memGetFloat(p + 16);
                        v[k][4] = (MemoryUtil.memGetByte(p + 23) & 255) / 255.0;
                        v[k][5] = 1 / d;
                    }
                    if (!front) continue;
                    int kind = MemoryUtil.memGetByte(start + 33);
                    double mix = MemoryUtil.memGetByte(start + 34) / 127.0;
                    double minX = W, minY = H, maxX = 0, maxY = 0, midU = 0, midV = 0;
                    for (double[] a : v) {
                        minX = Math.min(minX, a[0]); maxX = Math.max(maxX, a[0]);
                        minY = Math.min(minY, a[1]); maxY = Math.max(maxY, a[1]);
                        midU += a[2] / 4; midV += a[3] / 4;
                    }
                    int frame = -1;
                    if (kind < 0) for (int f = 0; f < FxAtlas.PUFF6_FRAMES; f++) {
                        FxAtlas.Sprite t = FxAtlas.puff6(f, false);
                        if (midU >= t.u0() && midU <= t.u1() && midV >= t.v0() && midV <= t.v1()) { frame = f; break; }
                    }
                    if (kind < 0) assertTrue(frame >= 0, "billows frame found on the sheet");
                    FxAtlas.Sprite tile = frame >= 0 ? FxAtlas.puff6(frame, false) : FxAtlas.glow();
                    for (int y = Math.max(0, (int) minY); y <= Math.min(H - 1, (int) maxY + 1); y++) {
                        for (int x = Math.max(0, (int) minX); x <= Math.min(W - 1, (int) maxX + 1); x++) {
                            double[] at = interpolate(v[0], v[1], v[2], x + .5, y + .5);
                            if (at == null) at = interpolate(v[0], v[2], v[3], x + .5, y + .5);
                            if (at == null) continue;
                            double u = (at[0] - tile.u0()) / (tile.u1() - tile.u0());
                            double vv = (at[1] - tile.v0()) / (tile.v1() - tile.v0());
                            double tex;
                            if (frame >= 0) tex = sample(texture(frame), u, vv) * (1 - mix) + sample(texture(Math.min(frame + 1, FxAtlas.PUFF6_FRAMES - 1)), u, vv) * mix;
                            else if (kind > 0) {
                                double r = Math.hypot((u * 2 - 1) * 64 / 63.0, (vv * 2 - 1) * 64 / 63.0);
                                double edge = Math.clamp((1 - r) / .25, 0, 1);
                                tex = Math.exp(-Math.pow(r / .42, 2)) * edge * edge * (3 - 2 * edge) / .997147;
                            } else tex = 1;
                            if (tex < .002) continue;
                            double a = at[2] * tex;
                            int i = y * W + x;
                            alpha[i] = 1 - (1 - alpha[i]) * (1 - a);
                        }
                    }
                }
            } finally {
                MemoryUtil.nmemFree(data);
                field.setLong(buffer, 0);
                var out = FxQuads.class.getDeclaredField("out");
                out.setAccessible(true);
                ((com.mojang.blaze3d.vertex.ByteBufferBuilder) out.get(buffer)).close();
            }
        }

        /** Perspective interpolation of UV and alpha of the real frame triangle. */
        private static double[] interpolate(double[] a, double[] b, double[] c, double x, double y) {
            double den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1]);
            if (Math.abs(den) < 1e-12) return null;
            double u = ((b[1] - c[1]) * (x - c[0]) + (c[0] - b[0]) * (y - c[1])) / den;
            double v = ((c[1] - a[1]) * (x - c[0]) + (a[0] - c[0]) * (y - c[1])) / den, w = 1 - u - v;
            if (Math.min(u, Math.min(v, w)) < -1e-8) return null;
            double d = u * a[5] + v * b[5] + w * c[5];
            return new double[]{(u * a[2] * a[5] + v * b[2] * b[5] + w * c[2] * c[5]) / d,
                    (u * a[3] * a[5] + v * b[3] * b[5] + w * c[3] * c[5]) / d,
                    (u * a[4] * a[5] + v * b[4] * b[5] + w * c[4] * c[5]) / d};
        }
    }

    private static final BufferedImage[] TEXTURES = new BufferedImage[FxAtlas.PUFF6_FRAMES];
    private static BufferedImage texture(int f) {
        if (TEXTURES[f] == null) {
            try (var in = SamTrailScreenTest.class.getResourceAsStream("/assets/incoming/textures/fx/particle/puff6a_%02d.png".formatted(f))) {
                assertNotNull(in); TEXTURES[f] = ImageIO.read(in);
            } catch (IOException e) { throw new AssertionError(e); }
        }
        return TEXTURES[f];
    }
    private static double sample(BufferedImage im, double u, double v) {
        if (u < 0 || u >= 1 || v < 0 || v >= 1) return 0;
        return (im.getRGB((int) (u * im.getWidth()), (int) (v * im.getHeight())) >>> 24) / 255.0;
    }
}
