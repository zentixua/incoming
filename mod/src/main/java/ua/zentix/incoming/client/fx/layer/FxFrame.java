package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.FrustumIntersection;
import org.joml.Vector4f;
import ua.zentix.incoming.client.far.FarView;
import ua.zentix.incoming.client.far.Sight;

/**
 * Per-frame particle inputs: camera, frustum (camera-relative coordinates), air haze, render boundary and fog
 * Minecraft.
 */
public final class FxFrame {
    public final Vec3 cam;
    public final FrustumIntersection frustum;
    private final FarView view;
    private final float fogStart, fogEnd;
    private final boolean cylinder;

    FxFrame(FarView view, FrustumIntersection frustum, Fog fog) {
        this.cam = view.camera();
        this.frustum = frustum;
        this.view = view;
        this.fogStart = fog.start;
        this.fogEnd = fog.end;
        this.cylinder = fog.shape == FogShape.CYLINDER;
    }

    /**
     * Fraction of particle light at d blocks, dy above the eye, transmitted through air ({@link FarView#air}): the same haze as
     * distant visuals.
     */
    public float haze(double d, double dy) {
        return (float) view.air(d, dy);
    }

    /**
     * Particle share at distance d: beyond 0.8 render distance distant visuals replace it ({@link FarView#farShare}); no particle at
     * the boundary. Independent of Minecraft fog: Distant Horizons removes it, and blast particles at 400 blocks would be drawn together
     * with distant visuals of the same blast — particle smoke would hide the fireball.
     */
    public float near(double d) {
        return (float) (1 - view.farShare(d));
    }

    /** Minecraft fog at a camera-relative point, 0..1 — like vanilla shaders (water, lava, Nether, blindness, nuclear ash). */
    public float fog(double x, double y, double z) {
        if (fogEnd <= fogStart) return 0;
        double d = cylinder ? Math.max(Math.sqrt(x * x + z * z), Math.abs(y)) : Math.sqrt(x * x + y * y + z * z);
        if (d <= fogStart) return 0;
        if (d >= fogEnd) return 1;
        float t = (float) ((d - fogStart) / (fogEnd - fogStart));
        return Mth.clamp(t * t * (3 - 2 * t), 0, 1);
    }

    /** World fog captured before Minecraft resets it at frame end. */
    static final class Fog {
        float start, end;
        FogShape shape = FogShape.SPHERE;
        final float[] color = new float[4];

        /**
         * Air haze and this fog for effects drawn onto the world (water decal, pall and mushroom shadow; shader {@code Haze}):
         * x — air extinction from visibility {@code range}, 1/block ({@link Sight}); y, z — fog start
         * and end (no fog — beyond everything); w — 1 for cylindrical fog. Into {@code out}.
         */
        Vector4f haze(double range, Vector4f out) {
            boolean on = end > start;
            return out.set((float) (Sight.KOSCHMIEDER / range), on ? start : 1e9f, on ? end : 2e9f, shape == FogShape.CYLINDER ? 1 : 0);
        }
    }
}
