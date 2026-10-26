package ua.zentix.incoming.client.render;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import ua.zentix.incoming.client.far.FarSprites;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;
import ua.zentix.incoming.client.fx.Exhaust;
import ua.zentix.incoming.client.fx.plume.Flame;
import ua.zentix.incoming.client.fx.plume.NozzleGlow;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Nearby engine plume ({@link Flame}, from {@code FlameModel}): nozzle-axis ribbons facing the camera (jet from
 * every side, rather than a flat image), sheath (afterburning flame and cooling tail) in pieces from exit to Draper
 * point, each position's light and colour from the model law ({@link Flame#sheath}: white flame, yellow and orange
 * tail fading toward the end), plus core to the supersonic section's end. Width is local jet width
 * ({@link Flame#widthAt}). Jet is a cylinder: length visible from the side, cross-section end-on. End-on, an axial
 * ribbon becomes a transverse segment (directly from behind, a stick), so add a camera-facing jet cross-section at
 * the nozzle exit: flame circle (half-width at flame end) and core circle (half-width at exit), {@code far/disc}
 * with the same flux as the cross-section ({@link #HALO}). Geometry: {@link #layout} (end-on and side areas checked
 * by {@code PlumeRendererTest}).
 * <p>
 * Effects layer draws jet after the world ({@code FxLayer}, via {@link FarSprites}), rather than the world entity:
 * under Iris, everything drawn before world end passes through the shader pack (Complementary: eyes light ×1.5
 * raised to 1.5, clouds and sky haze over anything not writing depth), leaving a daytime jet 75 times brighter than
 * a white wall as a pale stub at the nozzle (RV2 look gate frames: 53 warm pixels versus 494 in 3.4.1), though visible
 * at night. Entity render only records this frame's jet ({@link #render}); brightness uses effects-layer frame eye
 * adaptation ({@link FarView#exposure}), the same as its distant light: brighter than screen white draws at full
 * strength and whitens ({@code Sight.WHITE}). Glare and veiling light belong to plume light ({@code FarFlightView})
 * at any distance. Shines additively over what is behind it, and overlays its own trail smoke at the same position.
 */
public final class PlumeRenderer {
    /**
     * Cross-section quad half-size per radius: soft-edged {@code far/disc} circle has the same flux as a circle of
     * radius r ({@link FarSprites#DISC}). Not a halo: its three-radius tail is surrounding glow, not the jet, which
     * made the end-on plume look like a sun (surrounding light in the eye: glare and veil in {@code FarFlightView}).
     */
    static final float HALO = (float) FarSprites.DISC;
    /** {@link #layout} layers: sheath pieces, core, flame cross-section, core cross-section. */
    static final int SHEATH = 0, CORE = 1, FLAME_DISC = 2, CORE_DISC = 3;

    /** {@link #layout} quad output: layer, sheath piece (0 for others), four corners (projectile axes). */
    interface Quads {
        /** Ribbon a, b edge nearer nozzle, c, d farther; cross-sections use square corners. */
        void quad(int layer, int piece, Vector3f a, Vector3f b, Vector3f c, Vector3f d);
    }

    /** Frame jet: plume, projectile-axis rotation, projectile world position. */
    private record Jet(Exhaust.Plume plume, Quaternionf rotation, Vec3 origin) {}

    /**
     * This frame's jets by entity: Iris also renders entities into the shadow map, which would duplicate a jet.
     * Render thread only.
     */
    private static final Map<net.minecraft.world.entity.Entity, Jet> FRAME = new IdentityHashMap<>();

    /** Frame hot nozzle: nozzle view, booster-axis rotation (nozzle along −Z), world nozzle exit, near share. */
    private record Hot(NozzleGlow.Shine shine, Quaternionf rotation, Vec3 nozzle, float near) {}

    /** This frame's hot nozzles by entity, like jets ({@link #FRAME}). */
    private static final Map<net.minecraft.world.entity.Entity, Hot> HOT = new IdentityHashMap<>();

    private PlumeRenderer() {}

    /**
     * Entity key renders this frame with hot nozzle s ({@link NozzleGlow}): exit at nozzle (world), nozzle axis −Z
     * of rotation; near is the near share, as for the jet.
     */
    public static void glow(NozzleGlow.Shine s, net.minecraft.world.entity.Entity key, Quaternionf rotation, Vec3 nozzle, float near) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getCameraEntity() == key && mc.options.getCameraType().isFirstPerson()) return;
        HOT.put(key, new Hot(s, new Quaternionf(rotation), nozzle, near));
    }

    /**
     * Entity key renders this frame with jet p: effects layer will draw it ({@link #collect}).
     *
     * @param rotation projectile-axis rotation (nose along +Z)
     * @param origin   projectile world position this frame
     */
    public static void render(Exhaust.Plume p, net.minecraft.world.entity.Entity key, Quaternionf rotation, Vec3 origin) {
        // Onboard view cannot see its own engine jet (same as distant plume light, FarFlightView.onboard): Iris draws the camera
        // entity into the shadow map; without this the layer would draw the jet right at the eye.
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getCameraEntity() == key && mc.options.getCameraType().isFirstPerson()) return;
        FRAME.put(key, new Jet(p, new Quaternionf(rotation), origin));
    }

    /** Frame jets into effects-layer quads (relative to frame eye {@code view.camera()}). */
    public static void collect(FarView view, FarSprites out) {
        if (FRAME.isEmpty() && HOT.isEmpty()) return;
        Vec3 eye = view.camera();
        double exposure = view.exposure();
        for (Jet jet : FRAME.values()) draw(jet, eye, exposure, view, out);
        for (Hot hot : HOT.values()) draw(hot, eye, exposure, view, out);
    }

    /**
     * End of frame ({@code FxLayer}, after-world stage, including when the layer drew nothing): forget frame jets;
     * without collection (layer unready, resource reload), they would retain entities and later draw at old positions.
     */
    public static void endFrame() {
        FRAME.clear();
        HOT.clear();
    }

    /** World exit. */
    public static void reset() {
        FRAME.clear();
        HOT.clear();
    }

    private static void draw(Jet jet, Vec3 eye, double exposure, FarView view, FarSprites out) {
        Exhaust.Plume p = jet.plume();
        Flame f = p.flame();
        Quaternionf rot = jet.rotation(), inv = new Quaternionf(rot).conjugate();
        Vec3 o = jet.origin();
        // Nozzle exit to eye in projectile axes (nozzle at (0, y, z)); screen axes copied into the same space (view vectors are live).
        Vector3f cam = new Vector3f((float) (eye.x - o.x), (float) (eye.y - o.y), (float) (eye.z - o.z)).rotate(inv).sub(0, p.y(), 0);
        Vector3f left = new Vector3f(view.left()).rotate(inv), up = new Vector3f(view.up()).rotate(inv);
        float ox = (float) (o.x - eye.x), oy = (float) (o.y - eye.y), oz = (float) (o.z - eye.z);
        Flame.Sheath sheath = f.sheath();
        // Air haze to the jet attenuates its light like this plume's distant light (FarFlightView: brightness × transmittance,
        // then to screen): no jump at 0.8 render distance.
        double dist = Math.sqrt(ox * ox + oy * oy + oz * oz);
        float near = p.near();
        // Jet light over its own exhaust smoke: trail and column at the jet are the same matter; emission and scattered light
        // add (⚠ self-absorption ignored); sorting individual quads cut the jet into steps between billows
        // (F6 frames from behind). Margin is view-axis jet depth and trail billows near the axis (up to ~3.7σ₀, σ₀ half jet width
        // at its end, TrailModel.Trail.of): all pieces share one key, nearest jet point minus axis billows; smoke nearer than that,
        // both own and other sources, occludes it.
        Vector3f nozzle = new Vector3f(0, p.y(), p.z()).rotate(rot).add(ox, oy, oz);
        Vector3f tail = new Vector3f(0, p.y(), p.z() - (float) f.length()).rotate(rot).add(ox, oy, oz);
        float key = (float) Math.max(1e-3, Math.min(nozzle.length(), tail.length()) - 2 * f.spread());
        double lit = exposure * view.air(dist, oy);
        layout(f, p.z(), cam, left, up, (layer, piece, a, b, c, d) -> {
            for (Vector3f v : new Vector3f[]{a, b, c, d}) v.add(0, p.y(), 0).rotate(rot).add(ox, oy, oz);
            switch (layer) {
                case SHEATH -> {
                    double l0 = sheath.luminance()[piece] * lit, l1 = sheath.luminance()[piece + 1] * lit;
                    float[] c0 = Sight.onScreen(sheath.color()[piece], l0, near);
                    float[] c1 = Sight.onScreen(sheath.color()[piece + 1], l1, near);
                    out.glowStrip(a, b, c, d, c0[0], c0[1], c0[2], c1[0], c1[1], c1[2], (float) f.widthAt(sheath.x()[piece]), key, (float) l0, (float) l1);
                }
                case CORE -> {
                    double l0 = f.coreLuminance() * lit;
                    float[] c0 = Sight.onScreen(f.coreColor(), l0, near);
                    out.glowStrip(a, b, c, d, c0[0], c0[1], c0[2], c0[0], c0[1], c0[2], (float) f.width(), key, (float) l0, (float) l0);
                }
                default -> {
                    boolean core = layer == CORE_DISC;
                    double l0 = (core ? f.coreLuminance() : f.flameLuminance()) * lit;
                    float[] c0 = Sight.onScreen(core ? f.coreColor() : f.flameColor(), l0, near);
                    double r = new Vector3f(c).sub(a).length() / 2 / Math.sqrt(2) / HALO;
                    out.glowDisc((a.x + c.x) / 2, (a.y + c.y) / 2, (a.z + c.z) / 2, r, c0[0], c0[1], c0[2], (float) r, (float) l0);
                }
            }
        });
    }

    /**
     * Hot nozzle: exit circle in its plane shows bell light (bell walls visible through the exit); throat circle at
     * its depth adds beyond the bell, by the fraction visible through the exit ({@link NozzleGlow.Shine#throatSeen}).
     * No nozzle visible from in front of the exit. Below {@link Sight#MIN_PIXELS} spot size, use a circular spot dimmed
     * by the same factor (same light, as {@link Sight#point}). Haze and eye adaptation as for the jet.
     */
    private static void draw(Hot hot, Vec3 eye, double exposure, FarView view, FarSprites out) {
        NozzleGlow.Shine s = hot.shine();
        Quaternionf rot = hot.rotation();
        Vector3f axis = new Vector3f(0, 0, -1).rotate(rot), ax = new Vector3f(1, 0, 0).rotate(rot), ay = new Vector3f(0, 1, 0).rotate(rot);
        float nx = (float) (hot.nozzle().x - eye.x), ny = (float) (hot.nozzle().y - eye.y), nz = (float) (hot.nozzle().z - eye.z);
        double dist = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (dist < 1e-3) return;
        // Cosine between outward nozzle axis and eye direction.
        double cos = -(axis.x * nx + axis.y * ny + axis.z * nz) / dist;
        if (cos <= 0) return;
        double lit = exposure * view.air(dist, ny);
        // Light through exit: throat circle at its visible fraction, bell on remaining exit area; the layer adds circles
        // over circles, so use uniform exit light for the bell minus throat share (exact flux,
        // ⚠ throat colder than bell does not darken the centre).
        double throatShare = s.throatSeen(cos) * (s.throatRadius() * s.throatRadius()) / (s.exitRadius() * s.exitRadius());
        // Exit slightly behind its plane, avoiding the same depth as nozzle mesh edge.
        plate(out, view, new Vector3f(axis).mul(0.01f).add(nx, ny, nz), ax, ay, s.exitRadius(), cos, dist, NozzleGlow.color(s.coneKelvin()),
                s.coneLuminance() * (1 - throatShare) * lit, hot.near());
        plate(out, view, new Vector3f(axis).mul((float) -s.depth()).add(nx, ny, nz), ax, ay, s.throatRadius(), cos, dist,
                NozzleGlow.color(s.throatKelvin()), s.throatLuminance() * s.throatSeen(cos) * lit, hot.near());
    }

    /** Circle radius r, centre c (eye-relative), in ax, ay plane, seen with cosine cos from distance dist. */
    private static void plate(FarSprites out, FarView view, Vector3f c, Vector3f ax, Vector3f ay, double r, double cos, double dist, int color,
                              double lum, float near) {
        if (lum <= 0) return;
        // Ellipse narrower than a one-and-a-half-pixel spot (minor axis r·cos): camera-facing circular spot with the same light
        // (ellipse area π·r²·cos); otherwise edge-on it is a subpixel needle, flickering dots.
        double least = 0.5 * Sight.MIN_PIXELS * view.pixel() * dist;
        if (r * cos < least) {
            double spot = Math.max(least, r * Math.sqrt(cos));
            double seen = lum * r * r * cos / (spot * spot);
            float[] rgb = Sight.onScreen(color, seen, near);
            out.glowDisc(c.x, c.y, c.z, spot, rgb[0], rgb[1], rgb[2], 0, (float) seen);
            return;
        }
        float[] rgb = Sight.onScreen(color, lum, near);
        out.glowPlate(c, new Vector3f(ax).mul((float) r), new Vector3f(ay).mul((float) r), rgb[0], rgb[1], rgb[2], c.length(), (float) lum);
    }

    /**
     * Quads of plume f with nozzle exit at z0 (projectile axes, nose +Z), for camera cam and screen axes left, up in
     * the same space: sheath pieces at {@link Flame#sheath} positions and core as camera-facing axial ribbons;
     * flame and core cross-sections at the exit as camera-facing circles.
     */
    static void layout(Flame f, float z0, Vector3f cam, Vector3f left, Vector3f up, Quads out) {
        double[] xs = f.sheath().x();
        Vector3f prev = null;
        for (int i = 0; i < xs.length; i++) {
            Vector3f side = side(cam, z0 - (float) xs[i]).mul((float) f.widthAt(xs[i]));
            if (prev != null) {
                float za = z0 - (float) xs[i - 1], zb = z0 - (float) xs[i];
                out.quad(SHEATH, i - 1, new Vector3f(-prev.x, -prev.y, za), new Vector3f(prev.x, prev.y, za), new Vector3f(side.x, side.y, zb),
                        new Vector3f(-side.x, -side.y, zb));
            }
            prev = side;
        }
        Vector3f s0 = side(cam, z0 - (float) f.core() / 2).mul((float) f.width());
        float zc = z0 - (float) f.core();
        out.quad(CORE, 0, new Vector3f(-s0.x, -s0.y, z0), new Vector3f(s0.x, s0.y, z0), new Vector3f(s0.x, s0.y, zc), new Vector3f(-s0.x, -s0.y, zc));
        disc(out, FLAME_DISC, new Vector3f(0, 0, z0 - (float) f.width()), left, up, HALO * (float) f.widthAt(f.flame()));
        disc(out, CORE_DISC, new Vector3f(0, 0, z0 - 0.5f * (float) f.width()), left, up, HALO * (float) f.width());
    }

    /** Across Z axis toward camera at axial point z: unit vector perpendicular to axis and camera ray. */
    private static Vector3f side(Vector3f cam, float z) {
        Vector3f toCam = new Vector3f(cam).sub(0, 0, z);
        Vector3f side = new Vector3f(0, 0, -1).cross(toCam);
        if (side.lengthSquared() < 1e-6f) side.set(1, 0, 0);
        return side.normalize();
    }

    /** Camera-facing quad of half-size s centred at at (screen axes left, up). */
    private static void disc(Quads out, int layer, Vector3f at, Vector3f left, Vector3f up, float s) {
        Vector3f l = new Vector3f(left).mul(s), u = new Vector3f(up).mul(s);
        out.quad(layer, 0, new Vector3f(at).sub(l).sub(u), new Vector3f(at).sub(l).add(u), new Vector3f(at).add(l).add(u), new Vector3f(at).add(l).sub(u));
    }
}
