package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL45;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarRenderer;
import ua.zentix.incoming.client.far.SmokePuffs;
import ua.zentix.incoming.client.fx.smoke.SmokeFrame;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;
import ua.zentix.incoming.client.nuclear.NukeLight;
import ua.zentix.incoming.client.render.PlumeRenderer;

/** Secondary render scopes. The primary renderer retains its existing storage and draw order. */
public final class FxViews {
    private static Camera primaryCamera;
    private static RenderTarget primaryTarget;
    private static SecondaryViews<Target, Saved> views;
    private static Scope scope;
    private static ClientLevel level;
    private static boolean warned;

    private FxViews() {}

    /** World entry, outside any nested render; Minecraft's getters may be replaced inside a camera render. */
    public static void anchor() {
        Minecraft mc = Minecraft.getInstance();
        primaryCamera = mc.gameRenderer.getMainCamera();
        primaryTarget = mc.getMainRenderTarget();
    }

    /**
     * Stable camera/target identity, before any FBO queries. Iris uses its own FBO for the primary world's early stages;
     * an FBO mismatch there is not a secondary camera. The ordinary path performs no GL query or cache operation.
     */
    public static boolean secondary(Camera camera) {
        return primaryCamera != null && (camera != primaryCamera || Minecraft.getInstance().getMainRenderTarget() != primaryTarget);
    }

    public static boolean secondary() { return scope != null; }

    public static boolean skipped() { return scope != null && scope.entry == null; }

    /** Before nuclear fog/light sampling or world entity submissions. */
    public static void begin(Camera camera) {
        if (!secondary(camera)) {
            resumePrimary();
            return;
        }
        ClientLevel currentLevel = Minecraft.getInstance().level;
        if (views != null && level != currentLevel) clear();
        int draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        // Fog and entity stages may bind different pipeline FBOs. They are still one camera invocation;
        // AFTER_LEVEL validates its target and skips it if the early target no longer matches.
        if (scope != null && scope.camera == camera) return;
        Target target = target(draw);
        if (views == null && target != null) {
            views = new SecondaryViews<>(Saved::close);
            level = currentLevel;
        }
        if (target != null) views.invalidate(key -> key.framebuffer == draw && !key.equals(target));
        var entry = target == null ? null : views.acquire(target, System.nanoTime(), Saved::new);
        Scope next = new Scope(scope, camera, draw, entry);
        if (entry != null) {
            entry.value.swap();
            NukeLight.newView();
        }
        scope = next;
        if (entry == null) warn("unsupported depth target or full or recursive view cache");
    }

    /** World-stage matrices are copied, since camera mods can restore the outer matrix before AFTER_LEVEL. */
    static void capture(RenderLevelStageEvent event) {
        begin(event.getCamera());
        if (scope == null || scope.entry == null) return;
        scope.modelView.set(event.getModelViewMatrix());
        scope.projection.set(event.getProjectionMatrix());
        scope.captured = true;
    }

    static RenderLevelStageEvent event(RenderLevelStageEvent event) {
        if (scope == null || scope.entry == null || !scope.captured || scope.camera != event.getCamera()
                || !scope.entry.key.equals(target(GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)))) {
            warn("secondary AFTER_LEVEL without a matching capture/target");
            return null;
        }
        return new RenderLevelStageEvent(event.getStage(), event.getLevelRenderer(), event.getPoseStack(),
                scope.modelView, scope.projection, event.getRenderTick(), event.getPartialTick(), event.getCamera(), event.getFrustum());
    }

    /** Preserve separate read/draw bindings even when a pass internally binds GL_FRAMEBUFFER. */
    static void saveTarget() {
        if (scope == null) return;
        scope.draw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        scope.read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, scope.viewport);
    }

    static void restoreTarget() {
        if (scope == null || scope.draw < 0) return;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scope.draw);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, scope.read);
        int[] v = scope.viewport;
        GlStateManager._viewport(v[0], v[1], v[2], v[3]);
    }

    static void end() {
        Scope done = scope;
        if (done == null) return;
        if (done.entry != null) {
            done.entry.value.swap();
            views.release(done.entry, System.nanoTime());
        }
        scope = done.parent;
    }

    /** An aborted secondary render must not lend its storage to the next primary stage or client tick. */
    public static void resumePrimary() {
        if (scope != null) while (scope != null) end();
    }

    /** A failed world render may never dispatch AFTER_LEVEL. Unwind before the next outer frame. */
    public static void outerFrame() {
        resumePrimary();
        if (views != null) {
            if (level != Minecraft.getInstance().level) {
                clear();
                return;
            }
            views.expire(System.nanoTime());
            if (views.size() == 0) {
                views = null;
                level = null;
            }
        }
    }

    public static void clear() {
        while (scope != null) end();
        if (views != null) {
            views.clear();
            views = null;
        }
        warned = false;
        level = null;
    }

    public static int width(int primary) { return scope == null || scope.entry == null ? primary : scope.entry.key.width; }
    public static int height(int primary) { return scope == null || scope.entry == null ? primary : scope.entry.key.height; }
    public static double world(double primary) {
        return scope == null ? primary : Minecraft.getInstance().gameRenderer.getDepthFar() / 4.0;
    }

    static int depth(int primary) { return scope == null || scope.entry == null ? primary : scope.entry.key.depth; }

    /** Full-view coordinates, including while drawing into the half-resolution smoke or bloom target. */
    public static void screenSize(ShaderInstance shader) {
        if (scope != null && scope.entry != null) {
            Target target = scope.entry.key;
            shader.safeGetUniform("ScreenSize").set((float) target.width, (float) target.height);
        }
    }

    static void textures(int... ids) {
        for (int id : ids) if (id > 0) GlStateManager._deleteTexture(id);
    }

    static void framebuffers(int... ids) {
        for (int id : ids) if (id > 0) GlStateManager._glDeleteFramebuffers(id);
    }

    private static void warn(String reason) {
        if (!warned) {
            warned = true;
            Incoming.LOG.info("Skipping Incoming effects in a secondary view: {}", reason);
        }
    }

    private record Target(int framebuffer, int depth, int width, int height) {}

    /** Only a single-sample 2D depth texture covering this view; no copy/resolve fallback. */
    private static Target target(int draw) {
        if (draw == 0 || GL11.glGetInteger(GL13.GL_SAMPLES) != 0) return null;
        int kind = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (kind != GL11.GL_TEXTURE) return null;
        int depth = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (depth <= 0 || GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL) != 0
                || GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                GL32.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) != 0) return null;
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int w = viewport[2], h = viewport[3];
        if (w <= 0 || h <= 0 || viewport[0] != 0 || viewport[1] != 0) return null;
        RenderTarget current = Minecraft.getInstance().getMainRenderTarget();
        if (current.frameBufferId == draw && current.getDepthTextureId() == depth) {
            return current.width == w && current.height == h ? new Target(draw, depth, w, h) : null;
        }
        // OpenGL 4.5 DSA identifies a foreign texture without rebinding it to an assumed target.
        if (!GL.getCapabilities().OpenGL45 && !GL.getCapabilities().GL_ARB_direct_state_access) return null;
        if (GL45.glGetTextureParameteri(depth, GL45.GL_TEXTURE_TARGET) != GL11.GL_TEXTURE_2D
                || GL45.glGetTextureLevelParameteri(depth, 0, GL11.GL_TEXTURE_WIDTH) != w
                || GL45.glGetTextureLevelParameteri(depth, 0, GL11.GL_TEXTURE_HEIGHT) != h) return null;
        return new Target(draw, depth, w, h);
    }

    private static final class Scope {
        final Scope parent;
        final Camera camera;
        final int framebuffer;
        final SecondaryViews.Entry<Target, Saved> entry;
        final Matrix4f modelView = new Matrix4f(), projection = new Matrix4f();
        boolean captured;
        int draw = -1, read;
        final int[] viewport = new int[4];

        Scope(Scope parent, Camera camera, int framebuffer, SecondaryViews.Entry<Target, Saved> entry) {
            this.parent = parent;
            this.camera = camera;
            this.framebuffer = framebuffer;
            this.entry = entry;
        }
    }

    private static final class Saved {
        final LayerTimes.ViewState timing = new LayerTimes.ViewState();
        final ua.zentix.incoming.client.far.FarFlightView.ViewState flight = new ua.zentix.incoming.client.far.FarFlightView.ViewState();
        final ua.zentix.incoming.client.far.FarBlasts.ViewState blasts = new ua.zentix.incoming.client.far.FarBlasts.ViewState();
        final SceneDepth.ViewState depth = new SceneDepth.ViewState();
        final PallPass.ViewState pall = new PallPass.ViewState();
        final HalfPass.ViewState half = new HalfPass.ViewState();
        final FxBloom.ViewState bloom = new FxBloom.ViewState();
        final HeatPass.ViewState heat = new HeatPass.ViewState();
        final OpenAir.ViewState air = new OpenAir.ViewState();
        final Solids.ViewState solids = new Solids.ViewState();
        final SmokePass.ViewState smoke = new SmokePass.ViewState();
        final FxLayer.ViewState layer = new FxLayer.ViewState();
        final WaterDecal.ViewState water = new WaterDecal.ViewState();
        final SmokeGrid.ViewState grid = new SmokeGrid.ViewState();
        final SmokeFrame.ViewState smokeFrame = new SmokeFrame.ViewState();
        final FarRenderer.ViewState far = new FarRenderer.ViewState();
        final SmokePuffs.ViewState puffs = new SmokePuffs.ViewState();
        final PlumeRenderer.ViewState plumes = new PlumeRenderer.ViewState();
        final NukeLight.ViewState light = new NukeLight.ViewState();
        final SeeThrough.ViewState see = new SeeThrough.ViewState();

        void swap() {
            timing.swap(); flight.swap(); blasts.swap();
            depth.swap(); pall.swap(); half.swap(); bloom.swap(); heat.swap(); air.swap(); solids.swap(); smoke.swap();
            layer.swap(); water.swap(); grid.swap(); smokeFrame.swap(); far.swap(); puffs.swap(); plumes.swap(); light.swap(); see.swap();
        }

        void close() {
            timing.close();
            depth.close(); pall.close(); half.close(); bloom.close(); heat.close(); air.close(); solids.close(); smoke.close();
            layer.close(); water.close(); see.close();
        }
    }
}
