package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;

import java.util.Arrays;

/**
 * Visible fraction of a bright source (fireball, fire, plume) in the frame — for its glare and veil. These are light
 * scattered in the eye itself: their quad sits at the eye ({@code FarSprites#eye}); neither depth nor world distance behind
 * their pixels clips them — only matter blocking the source itself can hide them. Terrain rays beyond render distance
 * ({@code Sightline}: steps from 16 blocks, map heights) miss high-rises and cannot see Distant Horizons LOD at all:
 * a distant fire behind the city, itself hidden by buildings, would shine through them as glare and veil.
 * <p>
 * Visibility therefore uses what is on screen: each frame source gets a probe — a pixel in a custom texture; shader
 * {@code fx_probe} reads world distance ({@link SceneDepth}: Minecraft and DH LOD depth) at the source's screen
 * centre and over the upper half of a surrounding circle (like block rays within render distance, {@code FarBlasts#clear}: the lower
 * disc near ground is hidden by ground below the source itself), then compares with distance to its front half, using the
 * same softness as its layer body. Glare quad reads its probe in the layer vertex shader ({@code fx.vsh}) by index
 * ({@link FxQuads#seenBy}). An off-screen source is invisible to the screen probe — caller rays decide visibility there. World depth cannot see nuclear pall,
 * dust wall and mushroom volumes (fireball behind dust, another cap or stem) — the probe also
 * takes their transmission (pall to source front, {@code pall_front.glsl}, same code and parameters as layer
 * quads; mushrooms to its centre), in and out of frame; the source's own cap does not hide it. The layer does not clip glare and veil by pall or mushrooms again: eye
 * light comes from the source, rather than quad corners.
 */
public final class SourceProbes {
    /** Probes per texture row. */
    static final int WIDTH = 256;
    /** Maximum frame probes: index stored in a 16-bit layer vertex field ({@link FxQuads#vertex}). */
    static final int MAX = Short.MAX_VALUE;
    /** Probe vertex: eye-relative source centre, radius and index, quad corner on its pixel. */
    static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .build();
    @Nullable
    static ShaderInstance shader;
    private static final int[] VIEWPORT = new int[4];
    private int fbo = -1, texture = -1, rows;
    /** Frame sources: eye-relative centre (xyz) and radius, blocks. */
    private float[] sources = new float[4 * 64];
    private int n;

    /** New frame: no sources. */
    void begin() {
        n = 0;
    }

    /**
     * Source at eye-relative (x, y, z), radius r, blocks — its probe index (from 1); 0 — frame already has
     * {@link #MAX} probes: such a source uses only caller rays for visibility.
     */
    public int add(double x, double y, double z, double r) {
        if (n >= MAX) return 0;
        if (4 * (n + 1) > sources.length) sources = Arrays.copyOf(sources, sources.length * 2);
        int i = 4 * n;
        sources[i] = (float) x;
        sources[i + 1] = (float) y;
        sources[i + 2] = (float) z;
        sources[i + 3] = (float) r;
        return ++n;
    }

    /**
     * Compute frame probes from world-distance texture {@code scene} ({@link SceneDepth#update}) and frame pall
     * {@code pall} with its mushrooms ({@link PallPass#bindFront}; {@code pallDrawn} — drawn): eye-relative view, frame
     * projection, pixel angle, rad. Visibility texture (fraction by probe index − 1: x — remainder by {@link #WIDTH}, y — quotient);
     * −1 — no probes or failure.
     */
    int update(int scene, PallPass.Frame pall, boolean pallDrawn, Matrix4f view, Matrix4f projection, double pixel) {
        ShaderInstance s = shader;
        if (n == 0 || s == null || scene < 0 || !ensure((n + WIDTH - 1) / WIDTH)) return -1;
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, WIDTH, rows);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, scene);
        PallPass.bindFront(s, pall, pallDrawn, scene);
        s.safeGetUniform("SourceView").set(view);
        s.safeGetUniform("SourceProj").set(projection);
        s.safeGetUniform("ProbeSize").set((float) WIDTH, (float) rows);
        s.safeGetUniform("Pixel").set((float) pixel);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, FORMAT);
        for (int i = 0; i < n; i++) {
            float x = sources[4 * i], y = sources[4 * i + 1], z = sources[4 * i + 2], r = sources[4 * i + 3];
            // quad exactly covers its pixel, counter-clockwise
            b.addVertex(x, y, z).setUv(r, i).setNormal(-1, -1, 0);
            b.addVertex(x, y, z).setUv(r, i).setNormal(1, -1, 0);
            b.addVertex(x, y, z).setUv(r, i).setNormal(1, 1, 0);
            b.addVertex(x, y, z).setUv(r, i).setNormal(-1, 1, 0);
        }
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableDepthTest();
        return texture;
    }

    /** Visibility texture and framebuffer with at least {@code need} rows (grows, never shrinks). */
    private boolean ensure(int need) {
        if (fbo >= 0 && need <= rows) return true;
        if (texture < 0) texture = GlStateManager._genTexture();
        if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
        rows = Math.max(need, 2 * rows);
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_R8, WIDTH, rows, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, null);
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GlConst.GL_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        if (!ok) {
            Incoming.LOG.error("Effects layer: source probe texture {}×{} not created, glare of hidden fire is not dimmed", WIDTH, rows);
            rows = 0;
            return false;
        }
        return true;
    }
}
