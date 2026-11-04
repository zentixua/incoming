package ua.zentix.incoming.client.nuclear;

import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.nuclear.IcbmArrival;

/** Compact hot head and short emitted wake in the existing depth-tested, sorted effects layer. */
public final class IcbmArrivalRenderer {
    private static final int SEGMENTS = 2;
    /** presentation: apparent luminous region and relative brightness, not body size or a plasma temperature claim. */
    private static final double RADIUS = 1.5, LIGHT = 12;
    private static final double[] HEAD = new double[5];
    private static final Vector3f A = new Vector3f(), B = new Vector3f(), C = new Vector3f(), D = new Vector3f();
    private IcbmArrivalRenderer() {}

    public static void collect(FarView view, FarSprites out) {
        IcbmApproaches tracks = ClientNuclear.approaches();
        double server = tracks.time(view.partial()), local = tracks.local(view.partial());
        for (IcbmApproaches.Entry e : tracks.entries()) {
            IcbmArrival path = e.path;
            double now = e.time(server, local);
            if (now < path.start() || now >= path.end() + IcbmArrival.WAKE) continue;
            if (e.head() && now < path.end()) {
                Vec3 p = path.at(now).subtract(view.camera());
                double air = view.air(p.length(), p.y), light = LIGHT * path.light(now) * view.exposure() * air;
                Sight.light(RADIUS, light, p.length(), view.pixel(), HEAD);
                // Source-sized softness selects shared scene-depth occlusion, including folded distant terrain.
                // Compact source-space halo, with no screen-wide veil or bloom pass.
                out.glow(p.x, p.y, p.z, HEAD[0], RADIUS, 1, 0.94f, 0.82f, (float) Math.min(1, HEAD[2]));
                out.glow(p.x, p.y, p.z, HEAD[3], RADIUS, 1, 0.74f, 0.42f, (float) Math.min(0.3, light * 0.003));
            }
            for (int i = 0; i < SEGMENTS; i++) {
                double t0 = Math.min(path.end(), now - IcbmArrival.WAKE * i / SEGMENTS);
                double t1 = Math.max(path.start(), now - IcbmArrival.WAKE * (i + 1) / SEGMENTS);
                if (t1 >= t0 || t0 < path.start()) continue;
                Vec3 p = wake(path, t0, now).subtract(view.camera()), q = wake(path, t1, now).subtract(view.camera());
                // Short cooling wake advects in the same world wind as the other effects.
                // Fixed emission positions; no completed trail on onset, and no tail dragged through the burst.
                double a = glow(path, view, now, t0, p), b = glow(path, view, now, t1, q);
                Vec3 side = q.subtract(p).cross(p).normalize().scale(RADIUS * 0.45);
                if (side.lengthSqr() < 1e-8) {
                    out.glow(p.x, p.y, p.z, RADIUS * 0.45, RADIUS * 0.45, 1, 0.5f, 0.2f, (float) a);
                    continue;
                }
                set(A, p.add(side)); set(B, p.subtract(side)); set(C, q.subtract(side)); set(D, q.add(side));
                out.glowStrip(A, B, C, D, (float) a, (float) (a * 0.5), (float) (a * 0.2),
                        (float) b, (float) (b * 0.5), (float) (b * 0.2), (float) (RADIUS * 0.45), (float) ((p.length() + q.length()) * 0.5), 0, 0);
            }
        }
    }

    private static double glow(IcbmArrival p, FarView view, double now, double emission, Vec3 at) {
        double age = Math.clamp((now - emission) / IcbmArrival.WAKE, 0, 1);
        return Math.min(1, 0.15 * view.exposure() * view.air(at.length(), at.y)) * p.light(emission) * (1 - age) * (1 - age);
    }

    private static Vec3 wake(IcbmArrival path, double emitted, double now) {
        var wind = ua.zentix.incoming.util.Wind.at((long) emitted);
        double travel = wind.speed() * Math.max(0, now - emitted) / 20;
        return path.at(emitted).add(wind.x() * travel, 0, wind.z() * travel);
    }

    private static void set(Vector3f out, Vec3 p) { out.set((float) p.x, (float) p.y, (float) p.z); }
}
