package ua.zentix.incoming.client.render;

import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import ua.zentix.incoming.Incoming;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * {@link LodDepth} source: Voxy terrain depth (NeoForge 1.21.1 only via Roxy, official Voxy 0.2.16 jar for 1.21.11).
 * Voxy has no API: read its classes by reflection only, no mixins or writes (same way Roxy obtains Voxy depth,
 * MIT: {@code compat/RoxyLodEntityOcclusion}):
 * <ul>
 *   <li>{@code IGetVoxyRenderSystem.getNullable()}: this world's Voxy renderer or null;</li>
 *   <li>its {@code pipeline} field (own pipeline without shader pack, {@code IrisVoxyRenderPipeline} with one) →
 *       {@code fb} → {@code getDepthTex().id}: depth texture where Voxy draws opaque LODs;</li>
 *   <li>{@code pipeline.properties}: depth range ({@code isZero2One}), direction ({@code isReverseZ}),
 *       clear value ({@code clearDepth}), near-plane value ({@code inverseClearDepth});</li>
 *   <li>{@code getViewport()} → {@code projection}: projection that wrote depth; {@code frameId}: whether Voxy rendered
 *       since last frame.</li>
 * </ul>
 * Texture contents (Voxy 0.2.16, {@code AbstractRenderPipeline.initDepthStencil}): before LODs, Voxy sets near-plane
 * depth where Minecraft drew (preventing LODs over the world): {@link LodDepth.View#world}, rather than LOD 8–16 blocks
 * away; with a shader pack, restores clear depth there afterward. Sky is clear depth. Rest is LODs with Voxy projection:
 * shader pack has its own colour program, but Voxy positions vertices with the same projection (pack's {@code vxProj}),
 * so no mismatched planes as in DH under Iris.
 * <p>
 * Without a shader pack (or with a pack requesting LODs in Minecraft depth, {@code renderToVanillaDepth}), Voxy also
 * transfers LODs to Minecraft depth, pinning those beyond its far plane to that plane
 * ({@code blit_texture_depth_cutout.frag}): a distant blast would hide behind any LOD ground near it, hence
 * {@link LodDepth.View#pinsWorldDepth}. Voxy classes or fields missing in another version means no source (log line),
 * game continues as without Voxy; runtime read failure logs one warning and disables source.
 */
final class VoxyDepth implements LodDepth.Source {
    private static final String CORE = "me.cortex.voxy.client.core.";

    private final Method renderSystem, viewport, depthTex, zeroToOne, reverseZ, clearDepth, nearDepth;
    private final Field pipeline, fb, properties, textureId, projection, frameId;
    /** Voxy frame {@code frameId} whose depth the layer already took; −1 if none. */
    private int taken = -1, last = -1;
    /** Last logged depth decoding mode; −1 if none. */
    private int logged = -1;
    private String described = "";
    private boolean broken;
    private final Matrix4f projectionCopy = new Matrix4f();
    private final Vector4f point = new Vector4f();

    VoxyDepth(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> getter = Class.forName(CORE + "IGetVoxyRenderSystem", false, loader);
        Class<?> system = Class.forName(CORE + "VoxyRenderSystem", false, loader);
        Class<?> abstractPipeline = Class.forName(CORE + "AbstractRenderPipeline", false, loader);
        Class<?> depthBuffer = Class.forName(CORE + "rendering.util.DepthFramebuffer", false, loader);
        Class<?> texture = Class.forName(CORE + "gl.GlTexture", false, loader);
        Class<?> props = Class.forName(CORE + "RenderProperties", false, loader);
        Class<?> view = Class.forName(CORE + "rendering.Viewport", false, loader);
        renderSystem = getter.getMethod("getNullable");
        viewport = system.getMethod("getViewport");
        pipeline = system.getDeclaredField("pipeline");
        pipeline.setAccessible(true);
        fb = abstractPipeline.getField("fb");
        properties = abstractPipeline.getField("properties");
        depthTex = depthBuffer.getMethod("getDepthTex");
        textureId = texture.getField("id");
        zeroToOne = props.getMethod("isZero2One");
        reverseZ = props.getMethod("isReverseZ");
        clearDepth = props.getMethod("clearDepth");
        nearDepth = props.getMethod("inverseClearDepth");
        projection = view.getField("projection");
        frameId = view.getField("frameId");
    }

    /** At client startup: Voxy installed and all classes and fields found; null if unavailable. */
    @Nullable
    static LodDepth.Source init() {
        if (!ModList.get().isLoaded("voxy")) return null;
        // Same frame with and without Voxy depth, for frame comparisons.
        if (!Boolean.parseBoolean(System.getProperty("incoming.lodDepth.voxy", "true"))) {
            Incoming.LOG.info("Voxy depth for the effects layer: off (-Dincoming.lodDepth.voxy=false)");
            return null;
        }
        try {
            VoxyDepth d = new VoxyDepth(VoxyDepth.class.getClassLoader());
            Incoming.LOG.info("Voxy depth for the effects layer: classes found, reading its depth texture (read-only)");
            return d;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Incoming.LOG.warn("Voxy: its classes differ from Voxy 0.2.16 the mod reads ({}) — Voxy terrain does not hide distant smoke and blasts", e.toString());
            return null;
        }
    }

    @Override
    public boolean current(LodDepth.View out) {
        if (broken) return false;
        try {
            Object system = renderSystem.invoke(null);
            if (system == null) return false;
            // Null during shader pack shadow pass: depth is for another view.
            Object view = viewport.invoke(system);
            Object pipe = pipeline.get(system);
            if (view == null || pipe == null) return false;
            int frame = frameId.getInt(view);
            // Voxy did not render this frame (pause, menu, LODs disabled): its texture holds previous-frame depth.
            if (frame == taken) return false;
            last = frame;
            Object buffer = fb.get(pipe);
            Object tex = buffer == null ? null : depthTex.invoke(buffer);
            int id = tex == null ? 0 : textureId.getInt(tex);
            if (id <= 0) return false;
            Object p = properties.get(pipe);
            boolean zero2one = (boolean) zeroToOne.invoke(p), reverse = (boolean) reverseZ.invoke(p);
            out.texture = id;
            out.negativeOneToOne = !zero2one;
            out.empty = (float) clearDepth.invoke(p);
            out.world = (float) nearDepth.invoke(p);
            out.pinsWorldDepth = true;
            projectionCopy.set((Matrix4fc) projection.get(view)).invert(out.inverseProjection);
            if (!out.inverseProjection.isFinite()) return false;
            int how = (reverse ? 1 : 0) | (zero2one ? 2 : 0);
            if (how != logged) {
                logged = how;
                described = LodDepth.describe(!reverse, out.negativeOneToOne, out.empty, nearest(out, reverse),
                        String.format(java.util.Locale.ROOT, ", Minecraft pixels marked %.1f", out.world));
                Incoming.LOG.info("Voxy depth for the effects layer: {}", described);
            }
            out.source = "Voxy";
            out.how = described;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Voxy changed: fields exist but values differ; layer proceeds without Voxy LODs, game continues.
            broken = true;
            Incoming.LOG.warn("Voxy depth for the effects layer: reading Voxy failed ({}), Voxy terrain no longer hides distant smoke and blasts", e.toString());
            return false;
        }
    }

    /**
     * End of frame: current Voxy frame is now old, even if layer did not use its depth (no effects this frame).
     * Next frame takes depth only if Voxy rendered anew.
     */
    @Override
    public void endFrame() {
        if (broken) return;
        try {
            Object system = renderSystem.invoke(null);
            Object view = system == null ? null : viewport.invoke(system);
            taken = view == null ? last : frameId.getInt(view);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            taken = last;
        }
    }

    /** Voxy projection near-plane distance, blocks, for the log line. */
    private float nearest(LodDepth.View v, boolean reverse) {
        float d = reverse ? 1 : 0;
        v.inverseProjection.transform(point.set(0, 0, v.negativeOneToOne ? d * 2 - 1 : d, 1));
        return -point.z / point.w;
    }
}
