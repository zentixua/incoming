package ua.zentix.incoming.client.render;

import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import ua.zentix.incoming.Incoming;

import java.util.Locale;

/**
 * {@link LodDepth} source: this frame's Distant Horizons terrain depth for rendering after the world; hardware depth
 * testing cannot see it (DH renders LODs to its own depth texture, transferring only colour to the frame; same
 * texture under Iris shaders, pack's {@code dhDepthTex}). DH texture and projection from public API
 * ({@link DhDepthApi}); without DH or with another API version, no DH depth, and only Minecraft terrain is visible
 * after the world.
 * <p>
 * Projection that wrote depth: DH renders LODs itself with its {@code dhProjectionMatrix}. A DH-supporting shader
 * pack renders them using its program into its own framebuffer (Iris replaces {@code IDhApiFramebuffer}); Iris
 * builds perspective with {@code DhApiRenderParam} clip planes ({@code nearClipPlane}, {@code farClipPlane}).
 * These DH planes differ from its matrix: matrix near plane at most 7.5 blocks ({@code RenderUtil.setDhProjectionMatrix}),
 * parameters use a fifth of render distance by frame angle, ~23 blocks for Artem. Pack depth read with DH's matrix
 * is three times nearer than reality: city LODs at 3–5 km appear at 1–1.5 km, entirely hiding blast columns at 1.5 km
 * (Complementary).
 */
public final class DhDepth implements LodDepth.Source {
    private static final DhDepth SOURCE = new DhDepth();
    /** Last logged DH depth mode (direction, range, renderer); −1 if none yet. */
    private static int logged = -1;
    private static String described = "";
    /** For logs: invalid DH frame projection. */
    private static final int UNUSABLE = 8;
    private static final Vector4f VIEW = new Vector4f();
    private static final Frame FRAME = new Frame();
    private static final Matrix4f PROJECTION = new Matrix4f();

    private DhDepth() {}

    /** Frame DH depth as provided by API: texture, projection, range and direction, renderer. */
    static final class Frame {
        int texture;
        boolean negativeOneToOne;
        float empty;
        /** Farther means larger (forward depth), otherwise reversed. */
        boolean forward;
        /** Another program (Iris shader pack) renders LODs into its own framebuffer. */
        boolean foreign;
        /** DH projection ({@code dhProjectionMatrix}) and its frame parameter clip planes, blocks. */
        final Matrix4f projection = new Matrix4f();
        float near, far;
    }

    /** At client startup: DH API can provide depth, subscribe to its frames; null if unavailable. */
    static LodDepth.Source init() {
        if (!ModList.get().isLoaded("distanthorizons")) return null;
        try {
            if (!DhDepthApi.supported()) return null;
            DhDepthApi.subscribe();
            return SOURCE;
        } catch (LinkageError e) {
            // DH API lacks required classes or methods: game loads, distant smoke is not hidden by LODs.
            Incoming.LOG.warn("Distant Horizons: API differs from the one the mod was built for — DH terrain does not hide distant smoke and blasts", e);
            return null;
        }
    }

    @Override
    public boolean current(LodDepth.View out) {
        Frame f = FRAME;
        if (!DhDepthApi.current(f)) return false;
        Matrix4f projection = PROJECTION.set(f.projection);
        if (irisDrawn(f.foreign, f.forward, f.negativeOneToOne)) irisPlanes(projection, f.near, f.far);
        projection.invert(out.inverseProjection);
        out.texture = f.texture;
        out.negativeOneToOne = f.negativeOneToOne;
        out.empty = f.empty;
        out.source = "Distant Horizons";
        int how = (f.forward ? 1 : 0) | (f.negativeOneToOne ? 2 : 0) | (f.foreign ? 4 : 0);
        boolean usable = out.inverseProjection.isFinite();
        int state = usable ? how : UNUSABLE;
        // Log only depth mode changes (log and scenario line), not every frame.
        if (state != logged) {
            logged = state;
            described = LodDepth.describe(f.forward, f.negativeOneToOne, f.empty, along(out.inverseProjection, f.forward ? 0 : 1, f.negativeOneToOne),
                    String.format(Locale.ROOT, " (DH planes %.1f to %.0f), LOD drawn by %s", f.near, f.far, f.foreign ? "the shader pack" : "DH"));
            // Invalid frame projection (DH planes coincide, matrix unset): layer treats this frame's LODs as without DH.
            if (usable) Incoming.LOG.info("Distant Horizons depth for the effects layer: {}", described);
            else Incoming.LOG.warn("Distant Horizons depth for the effects layer: DH projection is not invertible ({}), DH terrain ignored", described);
        }
        out.how = described;
        if (!usable) return false;
        return true;
    }

    /**
     * Shader pack renders LODs with Iris perspective (forward depth, NDC −1 to 1): read via {@link #irisPlanes}.
     * Without a shader pack, including Iris installed without one, DH renders itself with its own matrix and reversed
     * depth ({@code REVERSE_Z}, clear 0, matrix NDC 0 to 1); no framebuffer replacement, DH matrix is correct as is.
     */
    static boolean irisDrawn(boolean foreign, boolean forward, boolean negativeOneToOne) {
        return foreign && forward && negativeOneToOne;
    }

    /** View-axis distance (blocks) from a DH depth texture value, as {@code along} in {@code fx_depth}. */
    static float along(Matrix4f inverseProjection, float depth, boolean negativeOneToOne) {
        inverseProjection.transform(VIEW.set(0, 0, negativeOneToOne ? depth * 2 - 1 : depth, 1));
        return -VIEW.z / VIEW.w;
    }

    /**
     * Projection Iris uses to render LODs with the pack's program ({@code LodRendererEvents}: {@code setPerspective}
     * with Minecraft FOV/aspect and {@code DhApiRenderParam} planes), derived from DH projection: identical except
     * depth row, forward depth, NDC −1 to 1.
     */
    static Matrix4f irisPlanes(Matrix4f projection, float near, float far) {
        return projection.m22((far + near) / (near - far)).m32(2 * far * near / (near - far));
    }

    @Override
    public void endFrame() {
        DhDepthApi.endFrame();
    }
}
