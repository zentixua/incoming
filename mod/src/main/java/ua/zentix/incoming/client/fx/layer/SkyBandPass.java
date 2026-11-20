package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;

import java.nio.FloatBuffer;
import java.util.Locale;

/**
 * GL side of {@link SkyBand}: under a shader pack, once per primary frame before the pall, {@code fx_sky_band} averages the
 * frame's sky pixels into {@link SkyBand#AZ} × {@link SkyBand#EL} bins (RGBA16F target), read back through a pixel-pack buffer
 * when its fence has passed (never waits: a frame or two later; one readback in flight), smoothed on the CPU, and uploaded
 * to the pall programs as {@code PallAirBand} with {@code PallAirBandOn}. Without a pack nothing runs and the band stays off:
 * the airlight is vanilla's sky. The pass is 96 pixels × 64 samples, up to 31 texel fetches each (the sky test around the sample).
 * <p>
 * The array is not in the program's JSON ({@code ShaderInstance} sets no arrays): it is uploaded after {@code apply()},
 * right before the draw ({@link #upload}). Veil (inside Sable) finds uniforms the JSON omits and uploads zeroed copies of
 * them at the end of every {@code apply()}, and may swap the program there; an upload before {@code apply()} loses to both.
 * <p>
 * Only sky counts: the world distance must say sky at the sample and {@code BAND_EDGE} pixels around it; with a LOD mod
 * installed, only frames whose distance includes its LODs ({@link SceneDepth#withLod}) — otherwise a LOD silhouette
 * passes as sky, and no capture is taken. The sun and moon cones follow the pack's sun path ({@link ShaderPack#sunPathRotation}).
 * The band is dropped with a pending capture on a world change, a pack switched off or a shader reload; a capture's time
 * is its submission.
 */
public final class SkyBandPass {
    @Nullable
    static ShaderInstance shader;
    private static final SkyBand BAND = new SkyBand();
    private static final float[] READ = new float[SkyBand.AZ * SkyBand.EL * 4], DRAWN = new float[SkyBand.AZ * SkyBand.EL * 4];
    private static final int[] VIEWPORT = new int[4];
    private static final double[] SUN = new double[3];
    /** Caller's 2D texture on units 0 and 1 (the pass's samplers). */
    private static final int[] UNITS = new int[2];
    private static int fbo = -1, texture = -1, pbo = -1;
    @Nullable
    private static FloatBuffer readBuffer, upload;
    private static long fence;
    /** Submission time of the pending capture, seconds. */
    private static double captured;
    private static boolean on;
    private static long frame;
    /** What the band was taken from: a change drops it. */
    @Nullable
    private static ClientLevel level;
    @Nullable
    private static ShaderInstance source;
    private static boolean lodMod, lodChecked;

    private SkyBandPass() {}

    /**
     * This frame's band: read a finished capture, start the next. {@code scene} — world distances ({@link SceneDepth}),
     * {@code viewProjection} — eye-relative view-projection, (sx, sy) — vanilla's sun direction (east–west plane).
     */
    static void update(int scene, Matrix4f viewProjection, float sx, float sy) {
        Minecraft mc = Minecraft.getInstance();
        double now = System.nanoTime() * 1e-9;
        ShaderInstance s = shader;
        boolean pack = s != null && ShaderPack.inUse();
        if (mc.level != level || s != source || !pack) {
            level = mc.level;
            source = s;
            drop();
        }
        if (!pack || scene < 0) {
            on = false;
            return;
        }
        // second views (FxViews) draw with the primary view's band, still expiring
        if (FxViews.secondary()) {
            on = BAND.fill(DRAWN, now);
            return;
        }
        // all GL state this pass touches, restored below (framebuffers apart: the caller's read and draw ones)
        int drawFb = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), readFb = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), packBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int rowLength = GL11.glGetInteger(GL12.GL_PACK_ROW_LENGTH), skipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS),
                skipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS), alignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT),
                swap = GL11.glGetInteger(GL11.GL_PACK_SWAP_BYTES);
        boolean depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), blend = GL11.glIsEnabled(GL11.GL_BLEND),
                depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int texture0 = RenderSystem.getShaderTexture(0), texture1 = RenderSystem.getShaderTexture(1);
        ShaderInstance previousShader = RenderSystem.getShader();
        // the draw binds and then unbinds textures on units 0 and 1 (ShaderInstance.apply/clear) and binds its own vertex array
        int activeUnit = GlStateManager._getActiveTexture();
        for (int u = 0; u < UNITS.length; u++) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + u);
            UNITS[u] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        GlStateManager._activeTexture(activeUnit);
        int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        try {
            if (!ensure()) {
                on = false;
                return;
            }
            collect();
            on = BAND.fill(DRAWN, now);
            // no sun direction (the empty warm-up frame before the first pall): no cone to leave out, no capture
            if (fence != 0 || sx * sx + sy * sy < 1e-6f || !captureable(mc)) return;
            capture(s, mc, scene, viewProjection, sx, sy);
            captured = now;
        } finally {
            GlStateManager._pixelStore(GL12.GL_PACK_ROW_LENGTH, rowLength);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, skipRows);
            GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, skipPixels);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, swap);
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, packBuffer);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFb);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFb);
            RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            if (depthTest) RenderSystem.enableDepthTest();
            else RenderSystem.disableDepthTest();
            if (blend) RenderSystem.enableBlend();
            else RenderSystem.disableBlend();
            RenderSystem.depthMask(depthMask);
            RenderSystem.setShaderTexture(0, texture0);
            RenderSystem.setShaderTexture(1, texture1);
            RenderSystem.setShader(() -> previousShader);
            ProgramManager.glUseProgram(program);
            for (int u = 0; u < UNITS.length; u++) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + u);
                GlStateManager._bindTexture(UNITS[u]);
            }
            GlStateManager._activeTexture(activeUnit);
            // the caller's vertex array; BufferUploader forgets which of its buffers it left bound
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            BufferUploader.invalidate();
        }
    }

    /** Positive sky classification: with a LOD mod installed, this frame's world distance must hold its LODs. */
    private static boolean captureable(Minecraft mc) {
        if (!lodChecked) {
            lodChecked = true;
            lodMod = ModList.get().isLoaded("voxy") || ModList.get().isLoaded("distanthorizons");
        }
        return !lodMod || SceneDepth.withLod();
    }

    private static void capture(ShaderInstance s, Minecraft mc, int scene, Matrix4f viewProjection, float sx, float sy) {
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, SkyBand.AZ, SkyBand.EL);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, mc.getMainRenderTarget().getColorTextureId());
        RenderSystem.setShaderTexture(1, scene);
        s.safeGetUniform("BandViewProj").set(viewProjection);
        SkyBand.packSun(sx, sy, ShaderPack.sunPathRotation(), SUN);
        double len = Math.sqrt(SUN[0] * SUN[0] + SUN[1] * SUN[1] + SUN[2] * SUN[2]);
        s.safeGetUniform("BandSun").set((float) (SUN[0] / len), (float) (SUN[1] / len), (float) (SUN[2] / len), (float) SkyBand.cosExclude());
        // R2 sequence: the samples cover each bin evenly over frames
        frame++;
        s.safeGetUniform("BandJitter").set((float) ((frame * 0.7548776662466927) % 1.0), (float) ((frame * 0.5698402909980532) % 1.0));
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        // read into the pack buffer: returns at once, the copy finishes on the GPU
        GlStateManager._pixelStore(GL12.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
        GL11.glPixelStorei(GL11.GL_PACK_SWAP_BYTES, GL11.GL_FALSE);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        GL11.glReadPixels(0, 0, SkyBand.AZ, SkyBand.EL, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
        fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    }

    /** The finished readback, if its fence has passed (never waits); dated by its capture. */
    private static void collect() {
        if (fence == 0) return;
        int state = GL32.glClientWaitSync(fence, 0, 0);
        if (state == GL32.GL_TIMEOUT_EXPIRED) return;
        GL32.glDeleteSync(fence);
        fence = 0;
        FloatBuffer r = readBuffer;
        if (state == GL32.GL_WAIT_FAILED || r == null) return;
        r.clear();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0, r);
        r.get(0, READ);
        BAND.observe(READ, captured);
    }

    /** Forget the band and any capture in flight: its source (world, pack, shader) has changed. */
    private static void drop() {
        if (fence != 0) {
            GL32.glDeleteSync(fence);
            fence = 0;
        }
        BAND.reset();
        on = false;
    }

    /** {@code PallAirBandOn} for program {@code s}, before its {@code apply()} ({@code fx_pall} and the programs sharing its include). */
    static void bind(ShaderInstance s) {
        s.safeGetUniform("PallAirBandOn").set(on ? 1f : 0f);
    }

    /**
     * The band array into the program {@code apply()} has just made current, right before the draw: after Veil's own
     * uploads and any program swap of {@code apply()}. No-op while the band is off or for programs without the array.
     */
    static void upload() {
        if (!on) return;
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (program == 0) return;
        int at = GL20.glGetUniformLocation(program, "PallAirBand");
        if (at < 0) return;
        FloatBuffer u = upload;
        if (u == null) u = upload = MemoryUtil.memAllocFloat(DRAWN.length);
        u.clear();
        u.put(DRAWN).flip();
        GL20.glUniform4fv(at, u);
    }

    /** Scenario line: the band's state and the airlight it gives along (x, y, z). */
    public static String describe(double x, double y, double z) {
        if (!on) return "band off (vanilla sky)";
        double[] c = new double[3];
        SkyBand.read(DRAWN, x, y, z, c);
        return String.format(Locale.ROOT, "band %.3f %.3f %.3f", c[0], c[1], c[2]);
    }

    private static boolean ensure() {
        if (fbo >= 0) return true;
        if (shader == null) return false;
        texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, SkyBand.AZ, SkyBand.EL, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        // the read buffer is this framebuffer's own state
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        int status = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER);
        if (status != GlConst.GL_FRAMEBUFFER_COMPLETE) {
            Incoming.LOG.warn("Sky band framebuffer incomplete ({}): distant nuclear haze takes vanilla's sky colour", status);
            GlStateManager._glDeleteFramebuffers(fbo);
            GlStateManager._deleteTexture(texture);
            fbo = texture = -1;
            shader = null;
            return false;
        }
        pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (long) READ.length * Float.BYTES, GL15.GL_STREAM_READ);
        readBuffer = MemoryUtil.memAllocFloat(READ.length);
        return true;
    }
}
