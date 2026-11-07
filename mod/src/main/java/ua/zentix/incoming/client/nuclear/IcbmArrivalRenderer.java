package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.IcbmArrival;
import ua.zentix.incoming.nuclear.model.BlackBody;
import ua.zentix.incoming.nuclear.model.ReentryGlow;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.util.Air;

/**
 * Reentering warhead seen from the ground ({@link ReentryGlow}): a bright plasma head with glare and veil like any
 * other bright body ({@link FarSprites#light}), and its cooling wake as a glowing strip. Both in the existing
 * depth-tested, sorted effects layer.
 */
public final class IcbmArrivalRenderer {
    /** Wake strip pieces (two pieces left visible kinks at night). */
    private static final int SEGMENTS = 12;
    /**
     * The wake is drawn until its glow on screen falls to this share of white: overexposed at night, it stays visible for
     * many e-folds and fades through orange (a fixed three e-folds ended in a hard white cut).
     */
    private static final double FAINT = 0.01;
    /** Wake half-width as a share of the luminous head radius (est.): the hot core of the near wake. */
    private static final double WAKE_WIDTH = 0.6;
    /** Real entry speed, m/s (warhead {@code Article.Impact}). */
    private static final double ENTRY = WeaponSpec.NUKE.airframe().article().real().dive();
    private static final double[] HEAD = new double[5], RGB = new double[4];
    private static final float[] HEAD_RGB = rgb(ReentryGlow.HEAD_KELVIN), WAKE_RGB = rgb(ReentryGlow.WAKE_KELVIN);
    /** Head black-body light, linear rgb and luminance ({@link BlackBody#light}). */
    private static final double[] HEAD_LINEAR = new double[4];
    static { BlackBody.light(ReentryGlow.HEAD_KELVIN, HEAD_LINEAR); }
    private static final Vector3f A = new Vector3f(), B = new Vector3f(), C = new Vector3f(), D = new Vector3f();
    private IcbmArrivalRenderer() {}

    public static void collect(FarView view, FarSprites out) {
        IcbmApproaches tracks = ClientNuclear.approaches();
        double server = tracks.time(view.partial()), local = tracks.local(view.partial());
        for (IcbmApproaches.Entry e : tracks.entries()) {
            IcbmArrival path = e.path;
            double now = e.time(server, local);
            if (now < path.start() || now >= path.end() + IcbmArrival.WAKE) continue;
            double game = path.velocity().length(), sin = Math.abs(path.velocity().y) / Math.max(game, 1e-9);
            if (e.head() && now < path.end()) {
                Vec3 at = path.at(now), p = at.subtract(view.camera());
                double d = p.length(), t = view.air(d, p.y);
                double seen = ReentryGlow.luminance(ENTRY, at.y - Air.SEA, sin) * path.light(now) * view.exposure() * t;
                out.light(p.x, p.y, p.z, ReentryGlow.RADIUS, seen, t, view.pixel(), HEAD_RGB[0], HEAD_RGB[1], HEAD_RGB[2], 1, 1, HEAD);
            }
            wake(path, view, out, now, Math.min(now, path.end()), game, sin);
        }
    }

    /**
     * Head light on the frame's world and clouds ({@code HeatPass}): a light sphere of the head's radius and luminance,
     * like a fireball, so a night entry lights the ground under it (~4 lx from 2 km at the last seconds: many full moons).
     */
    public static void heat(Vec3 eye, float partial, ua.zentix.incoming.client.fx.layer.HeatPass.Frame f, double air) {
        IcbmApproaches tracks = ClientNuclear.approaches();
        if (tracks.entries().isEmpty()) return;
        double server = tracks.time(partial), local = tracks.local(partial);
        for (IcbmApproaches.Entry e : tracks.entries()) {
            IcbmArrival path = e.path;
            double now = e.time(server, local);
            if (!e.head() || now < path.start() || now >= path.end()) continue;
            double game = path.velocity().length(), sin = Math.abs(path.velocity().y) / Math.max(game, 1e-9);
            Vec3 at = path.at(now);
            double lum = ReentryGlow.luminance(ENTRY, at.y - Air.SEA, sin) * path.light(now);
            if (lum <= 0) continue;
            double k = lum / HEAD_LINEAR[3];
            f.light(at.x - eye.x, at.y - eye.y, at.z - eye.z, ReentryGlow.RADIUS, HEAD_LINEAR[0] * k, HEAD_LINEAR[1] * k,
                    HEAD_LINEAR[2] * k, air, Math.max(ReentryGlow.RADIUS, at.y - path.geometry().groundY()));
        }
    }

    /**
     * Glowing wake: a point emitted at tick e fades as exp(−age / τ) with τ the real quench time stretched to the game
     * speed (real wake length / game speed, {@link ReentryGlow#wakeLength}): the real wake length laid along the game path.
     */
    private static void wake(IcbmArrival path, FarView view, FarSprites out, double now, double newest, double game, double sin) {
        double tau = ReentryGlow.wakeLength(ENTRY, path.at(newest).y - Air.SEA, sin) / Math.max(game, 1e-9);
        double half = ReentryGlow.RADIUS * WAKE_WIDTH;
        Vec3 head = wakeAt(path, newest, now).subtract(view.camera());
        double first = glow(path, view, newest, newest, head, half, sin, game) * half / drawn(half, head.length(), view);
        if (first <= FAINT) return;
        double reach = Math.min(tau * Math.log(first / FAINT), IcbmArrival.WAKE);
        for (int i = 0; i < SEGMENTS; i++) {
            double e0 = newest - reach * i / SEGMENTS, e1 = newest - reach * (i + 1) / SEGMENTS;
            if (e0 <= path.start()) break;
            e1 = Math.max(e1, path.start());
            Vec3 p = wakeAt(path, e0, now).subtract(view.camera()), q = wakeAt(path, e1, now).subtract(view.camera());
            double a = glow(path, view, now, e0, p, half, sin, game), b = glow(path, view, now, e1, q, half, sin, game);
            if (a + b < 0.004) continue;
            double drawnA = drawn(half, p.length(), view), drawnB = drawn(half, q.length(), view);
            a *= half / drawnA;
            b *= half / drawnB;
            Vec3 axis = q.subtract(p), across = axis.cross(p.add(q).scale(0.5));
            if (across.lengthSqr() < 1e-12) continue;
            across = across.normalize();
            Vec3 sa = across.scale(drawnA), sb = across.scale(drawnB);
            set(A, p.add(sa));
            set(B, p.subtract(sa));
            set(C, q.subtract(sb));
            set(D, q.add(sb));
            float[] ca = Sight.onScreen(WAKE_RGB[0], WAKE_RGB[1], WAKE_RGB[2], a, 1), cb = Sight.onScreen(WAKE_RGB[0], WAKE_RGB[1], WAKE_RGB[2], b, 1);
            out.glowStrip(A, B, C, D, ca[0], ca[1], ca[2], cb[0], cb[1], cb[2], (float) Math.max(drawnA, drawnB),
                    (float) ((p.length() + q.length()) * 0.5), (float) a, (float) b);
        }
    }

    /** Drawn strip half-width: real, or a {@link Sight#MIN_PIXELS} line keeping the same light across. */
    private static double drawn(double half, double d, FarView view) {
        return Math.max(half, 0.5 * Sight.MIN_PIXELS * view.pixel() * d);
    }

    /** Wake brightness against screen white at the point emitted at {@code emission} (eye adaptation and air included). */
    private static double glow(IcbmArrival path, FarView view, double now, double emission, Vec3 at, double half, double sin, double game) {
        double h = path.at(emission).y - Air.SEA;
        double tau = ReentryGlow.wakeLength(ENTRY, h, sin) / Math.max(game, 1e-9);
        double age = Math.max(0, now - emission);
        return ReentryGlow.WAKE_SHARE * ReentryGlow.luminance(ENTRY, h, sin) * Math.exp(-age / Math.max(tau, 1e-6))
                * path.light(emission) * view.exposure() * view.air(at.length(), at.y);
    }

    /** Emitted wake point carried by the world wind since its emission. */
    private static Vec3 wakeAt(IcbmArrival path, double emitted, double now) {
        var wind = ua.zentix.incoming.util.Wind.at((long) emitted);
        double travel = wind.speed() * Math.max(0, now - emitted) / 20;
        return path.at(emitted).add(wind.x() * travel, 0, wind.z() * travel);
    }

    /** sRGB 0..1 color of black-body light at {@code kelvin}, largest channel 1. */
    private static float[] rgb(double kelvin) {
        BlackBody.light(kelvin, RGB);
        double max = Math.max(RGB[0], Math.max(RGB[1], RGB[2]));
        float[] c = new float[3];
        for (int i = 0; i < 3; i++) c[i] = (float) ua.zentix.incoming.client.fx.plume.FlameModel.srgb(RGB[i] / max);
        return c;
    }

    private static void set(Vector3f out, Vec3 p) { out.set((float) p.x, (float) p.y, (float) p.z); }
}
