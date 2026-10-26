package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.fx.smoke.SmokeGrid;

import java.nio.FloatBuffer;

/**
 * Smoke grid ({@link SmokeGrid}) in VRAM: RGBA16F 3D texture (2N × N × N·cascades, 12 MB), billow table
 * (RGBA32F, {@link SmokeGrid#MAX_BLOBS} × {@link SmokeGrid#BLOB_ROWS}) and filling via {@code fx_smoke_fill} shader: texture
 * layer by layer, billows as cell rectangles with additive blending; clear the whole texture to zero first. Shaders reading the grid
 * ({@code smoke.glsl}: pall rays, frame composite, layer quads, probes) receive it at its own texture unit {@link #UNIT}
 * ({@link #bind}) and frame parameters.
 */
public final class SmokePass {
    /** Grid texture unit: after pall noise (12) and outdoor-air window (13). */
    static final int UNIT = 14;
    @Nullable
    static ShaderInstance fill;
    private static int grid = -1, blobs = -1, clearFbo = -1, sliceFbo = -1, vao = -1, vbo = -1;
    private static boolean failed;
    @Nullable
    private static FloatBuffer blobBuffer, vertexBuffer;
    /** This frame's grid is filled (its parameters are valid for shaders). */
    private static boolean filled;
    private static final int[] VIEWPORT = new int[4];

    private SmokePass() {}

    /** Grid is available: fill shader compiled and textures created. */
    public static boolean available() {
        return fill != null && !failed;
    }

    /** Whether this frame has a grid (filled by {@link #fill}). */
    public static boolean filled() {
        return filled;
    }

    /** New frame: grid not yet filled (shaders read an empty grid). */
    static void begin() {
        filled = false;
    }

    /**
     * Fill frame {@code f} grid; false — failed (no shader or textures) or no smoke. Restore bound framebuffer, viewport
     * and blending afterwards (called by {@code PallPass} within its passes).
     */
    static boolean fill(SmokeGrid.Frame f) {
        filled = false;
        ShaderInstance s = fill;
        if (s == null || failed || f.empty() || !ensure()) return false;
        int n = SmokeGrid.N, layers = SmokeGrid.CASCADES * n;
        // billow table
        blobBuffer.clear();
        blobBuffer.put(f.blobs, 0, SmokeGrid.BLOB_ROWS * SmokeGrid.MAX_BLOBS * 4).flip();
        GlStateManager._bindTexture(blobs);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SmokeGrid.MAX_BLOBS, SmokeGrid.BLOB_ROWS, GL11.GL_RGBA, GL11.GL_FLOAT, blobBuffer);
        // rectangle vertices for all layers
        int vertices = f.layerStart[layers];
        if (vertexBuffer == null || vertexBuffer.capacity() < vertices * 3) {
            if (vertexBuffer != null) MemoryUtil.memFree(vertexBuffer);
            vertexBuffer = MemoryUtil.memAllocFloat(Math.max(vertices * 3, 3 * 4096));
        }
        vertexBuffer.clear();
        vertexBuffer.put(f.vertices, 0, vertices * 3).flip();

        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        // zero the entire grid (layered attachment: glClear clears every layer)
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, clearFbo);
        RenderSystem.viewport(0, 0, 2 * n, n);
        RenderSystem.colorMask(true, true, true, true);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        s.setSampler("Sampler0", blobs);
        s.safeGetUniform("SmokeCorner").set(cornerMatrix(f));
        s.apply();
        // additive blending after apply: a program without custom blending in JSON would disable it
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE);
        RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
        int slice = Uniform.glGetUniformLocation(s.getId(), "SmokeSlice");
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, sliceFbo);
        BufferUploader.invalidate();
        GL30.glBindVertexArray(vao);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertexBuffer, GL15.GL_STREAM_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0);
        GL20.glEnableVertexAttribArray(0);
        for (int l = 0; l < layers; l++) {
            int from = f.layerStart[l], count = f.layerStart[l + 1] - from;
            if (count == 0) continue;
            GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, grid, 0, l);
            GL20.glUniform4f(slice, l / n, l % n, 0, 0);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, from, count);
        }
        GL30.glBindVertexArray(0);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        BufferUploader.invalidate();
        s.clear();
        RenderSystem.disableBlend();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        filled = true;
        return true;
    }

    private static final org.joml.Matrix4f CORNER = new org.joml.Matrix4f();
    /** Cascade billow boxes ({@code smoke.glsl}: {@code SmokeLo}, {@code SmokeHi}): column c; lower w is 1 for a nonempty cascade. */
    private static final org.joml.Matrix4f BOX_LO = new org.joml.Matrix4f(), BOX_HI = new org.joml.Matrix4f();

    /** Frame cascades ({@code smoke.glsl}): column c — eye-relative corner and cell size; column 3: x — 1 if the grid exists. */
    static org.joml.Matrix4f cornerMatrix(SmokeGrid.Frame f) {
        CORNER.zero();
        for (int c = 0; c < SmokeGrid.CASCADES; c++) {
            CORNER.setColumn(c, new org.joml.Vector4f(f.corner[4 * c], f.corner[4 * c + 1], f.corner[4 * c + 2], f.corner[4 * c + 3]));
        }
        CORNER.m30(filled && !f.empty() ? 1 : 0);
        return CORNER;
    }

    /**
     * Frame {@code f} grid to shader {@code s}: texture at unit {@link #UNIT} ({@code SmokeGrid}), cascades
     * ({@code SmokeCascade}) and smoke bounds ({@code SmokeMin}/{@code SmokeMax}). No grid — zero in cascade
     * {@code SmokeCascade[3].x}: shader skips it. Call before shader drawing (numbers are sent by {@code apply});
     * sampler unit index directly into the program immediately (like {@code OpenAir.bind}).
     */
    static void bind(ShaderInstance s, @Nullable SmokeGrid.Frame f) {
        boolean on = filled && f != null && !f.empty();
        s.safeGetUniform("SmokeCascade").set(on ? cornerMatrix(f) : CORNER.zero());
        BOX_LO.zero();
        BOX_HI.zero();
        if (on) {
            s.safeGetUniform("SmokeMin").set(f.min[0], f.min[1], f.min[2]);
            s.safeGetUniform("SmokeMax").set(f.max[0], f.max[1], f.max[2]);
            for (int c = 0; c < SmokeGrid.CASCADES; c++) {
                if (!SmokeGrid.occupied(f, c)) continue;
                BOX_LO.setColumn(c, new org.joml.Vector4f(f.boxLo[3 * c], f.boxLo[3 * c + 1], f.boxLo[3 * c + 2], 1));
                BOX_HI.setColumn(c, new org.joml.Vector4f(f.boxHi[3 * c], f.boxHi[3 * c + 1], f.boxHi[3 * c + 2], 0));
            }
        } else {
            s.safeGetUniform("SmokeMin").set(0f, 0f, 0f);
            s.safeGetUniform("SmokeMax").set(0f, 0f, 0f);
        }
        s.safeGetUniform("SmokeLo").set(BOX_LO);
        s.safeGetUniform("SmokeHi").set(BOX_HI);
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, on ? grid : 0);
        GlStateManager._activeTexture(unit);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        ProgramManager.glUseProgram(s.getId());
        Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "SmokeGrid"), UNIT);
        ProgramManager.glUseProgram(program);
    }

    /** Unbind the grid from its unit (after the pass): other programs may expect a different type there. */
    static void unbind() {
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(unit);
    }

    /** Textures, framebuffers and vertex array created once; failure disables the grid until restart. */
    private static boolean ensure() {
        if (grid >= 0) return true;
        if (failed) return false;
        int n = SmokeGrid.N;
        int previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        grid = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, grid);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RGBA16F, 2 * n, n, SmokeGrid.CASCADES * n, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);

        blobs = GlStateManager._genTexture();
        GlStateManager._bindTexture(blobs);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, SmokeGrid.MAX_BLOBS, SmokeGrid.BLOB_ROWS, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
        blobBuffer = MemoryUtil.memAllocFloat(SmokeGrid.BLOB_ROWS * SmokeGrid.MAX_BLOBS * 4);

        clearFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, clearFbo);
        GL32.glFramebufferTexture(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, grid, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        sliceFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, sliceFbo);
        GL30.glFramebufferTextureLayer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, grid, 0, 0);
        ok &= GlStateManager.glCheckFramebufferStatus(GlConst.GL_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GlConst.GL_FRAMEBUFFER, previous);
        vao = GL30.glGenVertexArrays();
        vbo = GL15.glGenBuffers();
        if (!ok) {
            failed = true;
            Incoming.LOG.error("Blast smoke grid: render targets not created, blast smoke has no volume");
            return false;
        }
        Incoming.LOG.info("Blast smoke grid: {} cascades of {}³ cells ({} m), {} MB", SmokeGrid.CASCADES, n,
                java.util.Arrays.toString(SmokeGrid.CELL), 2 * n * n * n * SmokeGrid.CASCADES * 8 / (1024 * 1024));
        return true;
    }
}
