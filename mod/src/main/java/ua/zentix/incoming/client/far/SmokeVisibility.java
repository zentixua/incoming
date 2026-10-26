package ua.zentix.incoming.client.far;

import org.joml.FrustumIntersection;
import org.joml.Matrix4fc;
import ua.zentix.incoming.client.fx.smoke.SmokeBounds;

/** Uses the effects layer's actual camera (including zoom and bobbing), before the cloud model runs. */
public final class SmokeVisibility {
    private static final FrustumIntersection FRUSTUM = new FrustumIntersection();
    // Far effects are folded towards the eye. Minecraft's near/far planes must not clip their real coordinates.
    private static final int SIDES = FrustumIntersection.PLANE_MASK_NX | FrustumIntersection.PLANE_MASK_PX
            | FrustumIntersection.PLANE_MASK_NY | FrustumIntersection.PLANE_MASK_PY;

    private SmokeVisibility() {}

    public static void begin(Matrix4fc matrix) { FRUSTUM.set(matrix); }

    static boolean visible(FarView view, SmokeBounds b) {
        double x = view.camera().x, y = view.camera().y, z = view.camera().z;
        if (b.empty()) return false;
        // Nearest distance and highest point give an upper bound on transmission through height-dependent haze.
        if (view.air(Math.sqrt(b.distanceSquared(x, y, z)), b.maxY - y) < Sight.THRESHOLD) return false;
        return FRUSTUM.intersectAab((float) (b.minX - x), (float) (b.minY - y), (float) (b.minZ - z),
                (float) (b.maxX - x), (float) (b.maxY - y), (float) (b.maxZ - z), SIDES) < 0;
    }
}
