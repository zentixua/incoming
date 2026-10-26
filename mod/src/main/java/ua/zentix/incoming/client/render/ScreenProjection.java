package ua.zentix.incoming.client.render;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Frame matrices (captured after sky): world point to screen for HUD and projectile camera markers, nuclear flash
 * afterimage. Anything sized in world units on screen (target and projectile frames) is computed here: frame matrix
 * uses FOV after zoom mods (Ok Zoomer changes {@code GameRenderer.getFov} return), whereas {@code options.fov} and
 * {@code ComputeFov} event FOV do not see zoom ({@code FovSourceTest}).
 */
public final class ScreenProjection {
    private static final Matrix4f VIEW_PROJ = new Matrix4f(), PROJ = new Matrix4f(), VIEW = new Matrix4f();
    private static Vec3 camera = Vec3.ZERO;
    private static float projScale = 1, projScaleX = 1;
    private static boolean valid;

    private ScreenProjection() {}

    public static void capture(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) return;
        PROJ.set(e.getProjectionMatrix());
        VIEW.set(e.getModelViewMatrix());
        VIEW_PROJ.set(PROJ).mul(VIEW);
        projScale = e.getProjectionMatrix().m11();
        projScaleX = e.getProjectionMatrix().m00();
        camera = e.getCamera().getPosition();
        valid = true;
    }

    /**
     * Screen point as fractions (0..1 left to right and top to bottom) and angular scale, or null if behind viewer.
     *
     * @return {x, y, w}: w is depth for size conversion (screen size ≈ radius / w)
     */
    @Nullable
    public static float[] project(Vec3 world) {
        if (!valid) return null;
        Vector4f v = new Vector4f((float) (world.x - camera.x), (float) (world.y - camera.y), (float) (world.z - camera.z), 1);
        VIEW_PROJ.transform(v);
        if (v.w <= 0.05f) return null;
        return new float[]{(v.x / v.w + 1) * 0.5f, (1 - v.y / v.w) * 0.5f, v.w};
    }

    /**
     * Screen rectangle (fractions 0..1, {x0, y0, x1, y1}) around world box {@code box} using frame matrices: angular
     * size in this frame (with zoom: frame matrix uses FOV after zoom mods); null if a box corner is behind viewer.
     */
    @Nullable
    public static float[] rect(AABB box) {
        return valid ? rect(VIEW_PROJ, camera, box) : null;
    }

    /** Same for projection-view matrix {@code viewProj} and eye {@code eye}. */
    @Nullable
    public static float[] rect(Matrix4f viewProj, Vec3 eye, AABB box) {
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        Vector4f v = new Vector4f();
        for (int i = 0; i < 8; i++) {
            v.set((float) (((i & 1) == 0 ? box.minX : box.maxX) - eye.x), (float) (((i & 2) == 0 ? box.minY : box.maxY) - eye.y),
                    (float) (((i & 4) == 0 ? box.minZ : box.maxZ) - eye.z), 1);
            viewProj.transform(v);
            if (v.w <= 0.05f) return null;
            float x = (v.x / v.w + 1) * 0.5f, y = (1 - v.y / v.w) * 0.5f;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }
        return new float[]{x0, y0, x1, y1};
    }

    /**
     * Screen rectangle (fractions 0..1, {x0, y0, x1, y1}) around a sphere of radius {@code radius} centred at
     * {@code centre} using frame matrices: angular size this frame (with zoom), null if centre behind viewer.
     * Body without its own box in the client world (distant projectile has only a track) uses a sphere of its largest dimension.
     */
    @Nullable
    public static float[] rect(Vec3 centre, double radius) {
        return valid ? rect(PROJ, VIEW, camera, centre, radius) : null;
    }

    /**
     * Same for projection {@code proj}, view {@code view} and eye {@code eye}: square of side 2·radius at centre
     * depth, facing eye; sphere's angular diameter 2·radius/depth (sphere much smaller than its distance).
     */
    @Nullable
    public static float[] rect(Matrix4f proj, Matrix4f view, Vec3 eye, Vec3 centre, double radius) {
        Vector4f c = new Vector4f((float) (centre.x - eye.x), (float) (centre.y - eye.y), (float) (centre.z - eye.z), 1);
        view.transform(c);
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        Vector4f v = new Vector4f();
        for (int i = 0; i < 4; i++) {
            v.set(c.x + ((i & 1) == 0 ? -radius : radius), c.y + ((i & 2) == 0 ? -radius : radius), c.z, 1);
            proj.transform(v);
            if (v.w <= 0.05f) return null;
            float x = (v.x / v.w + 1) * 0.5f, y = (1 - v.y / v.w) * 0.5f;
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
        }
        return new float[]{x0, y0, x1, y1};
    }

    /** Vertical projection scale: screen-height fraction per unit (radius / depth). */
    public static float verticalScale() {
        return projScale * 0.5f;
    }

    /** Horizontal projection scale: screen-width fraction per unit (sideways offset / depth). */
    public static float horizontalScale() {
        return projScaleX * 0.5f;
    }
}
