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
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
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
 * when its fence has passed (no wait: a frame or two later; one readback in flight), smoothed on the CPU, and uploaded to the
 * pall programs as {@code PallAirBand} (an array: {@code ShaderInstance} cannot set it, so it goes in by hand, like the 3D
 * textures) with {@code PallAirBandOn}. Without a pack nothing runs and the band stays off: the airlight is vanilla's sky.
 * The pass is 96 pixels × 64 samples: two texel fetches each.
 */
public final class SkyBandPass {
    @Nullable
    static ShaderInstance shader;
    private static final SkyBand BAND = new SkyBand();
    private static final float[] READ = new float[SkyBand.AZ * SkyBand.EL * 4], DRAWN = new float[SkyBand.AZ * SkyBand.EL * 4];
    private static final int[] VIEWPORT = new int[4];
    private static int fbo = -1, texture = -1, pbo = -1;
    @Nullable
    private static FloatBuffer readBuffer, upload;
    private static long fence;
    private static boolean on;
    private static long frame;
    @Nullable
    private static ClientLevel level;

    private SkyBandPass() {}

    /**
     * This frame's band: read a finished pass, start the next. {@code scene} — world distances ({@link SceneDepth}),
     * {@code viewProjection} — eye-relative view-projection, (sx, sy, sz) — the sun's direction.
     */
    static void update(int scene, Matrix4f viewProjection, float sx, float sy, float sz) {
        Minecraft mc = Minecraft.getInstance();
        double now = System.nanoTime() * 1e-9;
        if (mc.level != level) {
            level = mc.level;
            BAND.reset();
        }
        ShaderInstance s = shader;
        if (s == null || scene < 0 || !ShaderPack.inUse()) {
            if (on) BAND.reset();
            on = false;
            return;
        }
        // second views (FxViews) draw with the band of the primary view
        if (FxViews.secondary()) return;
        if (!ensure()) {
            on = false;
            return;
        }
        collect(now);
        on = BAND.fill(DRAWN, now);
        if (fence != 0) return;
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, SkyBand.AZ, SkyBand.EL);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, mc.getMainRenderTarget().getColorTextureId());
        RenderSystem.setShaderTexture(1, scene);
        s.safeGetUniform("BandViewProj").set(viewProjection);
        float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1e-6f) len = 1;
        s.safeGetUniform("BandSun").set(sx / len, sy / len, sz / len, (float) SkyBand.cosExclude());
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
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        GL11.glReadPixels(0, 0, SkyBand.AZ, SkyBand.EL, GL11.GL_RGBA, GL11.GL_FLOAT, 0L);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableDepthTest();
    }

    /** The finished readback, if its fence has passed (never waits). */
    private static void collect(double now) {
        if (fence == 0) return;
        int state = GL32.glClientWaitSync(fence, 0, 0);
        if (state == GL32.GL_TIMEOUT_EXPIRED) return;
        GL32.glDeleteSync(fence);
        fence = 0;
        if (state == GL32.GL_WAIT_FAILED) return;
        FloatBuffer r = readBuffer;
        if (r == null) return;
        r.clear();
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
        GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0, r);
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        r.get(0, READ);
        BAND.observe(READ, now);
    }

    /**
     * Set this frame's band in program {@code s} ({@code fx_pall} and the programs sharing its include): {@code PallAirBandOn}
     * through the program's uniform, the array by hand on every bind (Veil recompiles shaders in place, resetting program
     * uniforms; the current program is restored).
     */
    static void bind(ShaderInstance s) {
        s.safeGetUniform("PallAirBandOn").set(on ? 1f : 0f);
        if (!on) return;
        FloatBuffer u = upload;
        if (u == null) u = upload = MemoryUtil.memAllocFloat(DRAWN.length);
        u.clear();
        u.put(DRAWN).flip();
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        ProgramManager.glUseProgram(s.getId());
        int at = GL20.glGetUniformLocation(s.getId(), "PallAirBand");
        if (at >= 0) GL20.glUniform4fv(at, u);
        ProgramManager.glUseProgram(program);
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
        texture = GlStateManager._genTexture();
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, SkyBand.AZ, SkyBand.EL, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        int status = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, 0);
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
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        readBuffer = MemoryUtil.memAllocFloat(READ.length);
        return true;
    }
}
