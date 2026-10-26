package ua.zentix.incoming.client.render;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthDirection;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import org.joml.Matrix4f;
import ua.zentix.incoming.Incoming;

/**
 * Link between {@link DhDepth} and Distant Horizons API; class loads only with DH installed. Projection and clip
 * planes from this frame's {@code DhApiBeforeRenderEvent} (last perspective event: under Iris, DH also renders
 * shadows with its own non-perspective projection); same event reports another program rendering LODs into its own
 * framebuffer ({@code IDhApiFramebuffer} replacement set by Iris before every pass rendered by the shader pack).
 * Texture, depth range and direction from {@code renderProxy} (API 7.2).
 */
final class DhDepthApi {
    /** API major version used for the build; 7.2 adds depth range and direction. */
    private static final int API_MAJOR = 7, API_MINOR = 2;
    private static final float[] ROWS = new float[16];
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static float near, far;
    private static boolean captured, foreign;

    private DhDepthApi() {}

    static boolean supported() {
        int major = DhApi.getApiMajorVersion(), minor = DhApi.getApiMinorVersion();
        if (major == API_MAJOR && minor >= API_MINOR) return true;
        Incoming.LOG.warn("Distant Horizons {}: API {}.{}, the mod is built for {}.{}+ — DH terrain does not hide distant smoke and blasts",
                DhApi.getModVersion(), major, minor, API_MAJOR, API_MINOR);
        return false;
    }

    static void subscribe() {
        DhApiEventRegister.on(DhApiBeforeRenderEvent.class, new DhApiBeforeRenderEvent() {
            @Override
            public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
                DhApiMat4f m = event.value.dhProjectionMatrix;
                // Perspective row 3 is (0, 0, −1, 0); shadow projection lacks it.
                if (Math.abs(m.m32 + 1) > 1e-3f) return;
                m.putValuesInArray(ROWS);
                // DH provides rows, JOML reads columns.
                PROJECTION.set(ROWS).transpose();
                near = event.value.nearClipPlane;
                far = event.value.farClipPlane;
                foreign = DhApi.overrides.get(IDhApiFramebuffer.class) != null;
                captured = true;
            }
        });
    }

    static boolean current(DhDepth.Frame out) {
        if (!captured) return false;
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        if (proxy == null) return false;
        try {
            DhApiResult<Integer> texture = proxy.getDhDepthTextureGlId();
            if (!texture.success || texture.payload == null || texture.payload <= 0) return false;
            out.texture = texture.payload;
            out.negativeOneToOne = proxy.getDepthRange() == EDhApiDepthRange.NEG_ONE_TO_POS_ONE;
            EDhApiDepthDirection direction = proxy.getDepthDirection();
            out.forward = direction == EDhApiDepthDirection.FORWARD_Z;
            out.empty = direction.farDepth;
        } catch (IllegalStateException e) {
            // DH renderer not yet created.
            return false;
        }
        out.projection.set(PROJECTION);
        out.near = near;
        out.far = far;
        out.foreign = foreign;
        return true;
    }

    static void endFrame() {
        captured = false;
    }
}
