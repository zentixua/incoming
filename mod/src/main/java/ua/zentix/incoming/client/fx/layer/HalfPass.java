package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;

import java.nio.FloatBuffer;
import java.util.Arrays;

/**
 * Soft smoke at half resolution (off-screen particles, GPU Gems 3 ch. 23; low-resolution translucency of Unreal and
 * Unity): the layer's clubs, eye veil and glare in the eye (a smooth halo of pure light) at least {@link #WIDE} pixels
 * across go into a half-resolution target, then onto
 * the frame with depth-aware upsampling ({@code fx_half_composite}). Their cost is the number of fragments: 0.73–0.80 ms
 * per screen of fill at 2560×1600 (RTX 4060 laptop, sandbox raid of 40), of which raster and blending of the quad area
 * ~0.2 ms per screen even with a constant shader (the overdraw is local: tens of layers in the cloud's middle), the six-way
 * light maps and their powers ~0.2, frame places and alpha the rest; a club-only program saved 7 %. Clubs are mostly drawn
 * smaller than their 128-pixel frames, so the frame's finest octave was already filtered away; narrower quads (a lying
 * club edge-on, a few pixels thin) stay at full resolution.
 * <p>
 * Order stays the layer's far-to-near order on every tile ({@link Plan}): behind a tile's last full-resolution quad (fire,
 * glare, a thin club) smoke draws at full resolution in order, in front of it into the target, laid onto the frame once
 * after every full-resolution quad ({@link #flush}) — fire inside smoke stays behind the smoke in front of it and in front
 * of the smoke behind it. Hardware depth is absent in the target: smoke behind the world fades by the world distance
 * ({@code fx.fsh}, from a checkerboard min/max half-resolution copy, {@code fx_half_depth}), exactly as its soft edge does.
 */
final class HalfPass {
    /** Frame pixels across a quad's narrower edge for half resolution: thinner ones (a lying club edge-on) stay full. */
    static final double WIDE = 24;
    /** Frame pixels per tile side (the plan's unit). Even: a tile holds whole half-resolution pixels. */
    static final int TILE = 64;

    @Nullable
    static ShaderInstance depthShader, composite;
    private static int accum = -1, accumFbo = -1, depth = -1, depthFbo = -1, width, height, ring;
    /** Tile cut textures (a ring: the one written this frame is not one a previous frame may still read). */
    private static final int[] cuts = {-1, -1, -1}, cutW = new int[3], cutH = new int[3];
    private static FloatBuffer cutData = MemoryUtil.memAllocFloat(1);
    /** Clip rectangles of quads split by tile, NDC (texel k % 1024, k / 1024), a ring as the cuts. */
    private static final int[] rects = {-1, -1, -1}, rectRows = new int[3];
    private static FloatBuffer rectData = MemoryUtil.memAllocFloat(4);
    static final int RECT_ROW = 1024;
    private static boolean failed;
    private static final Plan PLAN = new Plan();
    /** Per sorted quad: may draw at half resolution, and its tiles x0, y0, x1, y1 (inclusive; x0 > x1 — off screen). */
    private static boolean[] soft = new boolean[0];
    private static int[] tiles = new int[0];
    private static final int[] VIEWPORT = new int[4];
    /** Last frame: quads by kind and lays, for the scenario line. */
    private static int lastLow, lastFull, lastFlushes, lastScene = -1;
    /** This frame drew through the plan (the drain frame's parts follow it). */
    private static boolean planned;

    private HalfPass() {}

    /**
     * Where each quad draws, per tile; pure, checked by {@code HalfPassTest}. A tile's cut is the sorted place of its last
     * full-resolution quad (fire, glare, particles, a thin club; −1 — none). On a tile, a soft quad behind (or at) the cut
     * draws in the frame in order, one in front of it into the target, and the target is laid onto the frame once, after
     * every full-resolution quad: each tile gets its full-resolution quads in order, then its half-resolution smoke in order
     * on top — the layer's order exactly. A quad over tiles with different cuts draws in both, each fragment by its tile.
     * One lay a frame: a lay waits for everything drawn into the target, and laying before each fire inside smoke (35–56
     * lays in a 40-strike raid) cost more than it saved; deciding whole quads instead of tiles chained nearly every club
     * behind some fire to full resolution.
     */
    static final class Plan {
        /**
         * Where a quad draws: frame whole, frame behind the cut, target whole, target in front of the cut, frame after the
         * lay (nearer than every soft quad: glare in the eye, particles at the camera).
         */
        static final int FRAME = 1, FRAME_BEHIND = 2, TARGET = 4, TARGET_FRONT = 8, LATE = 16;
        int tilesX, tilesY;
        /**
         * First sorted place after the last soft quad: the target is laid there, and the quads from it on draw over it
         * in order. Night glare in the eye is the nearest quad of the frame and covers most of it: with the lay at the
         * end, every club behind it stayed at full resolution (370 of 6100 halved in the night raid of 40).
         */
        int late;
        /** Each tile's cut. */
        float[] cut = new float[0];
        /** Tiles holding half-resolution smoke (laid at the end). */
        long[] pending = new long[0];
        boolean any;
        int[] where = new int[0];
        /** Per split quad: bounding rectangles (x0, y0, x1, y1 inclusive) of its tiles behind and in front of their cuts. */
        int[] behind = new int[0], ahead = new int[0];

        void begin(int tilesX, int tilesY) {
            this.tilesX = tilesX;
            this.tilesY = tilesY;
            int count = tilesX * tilesY, words = (count + 63) / 64;
            if (cut.length < count) cut = new float[count];
            if (pending.length < words) pending = new long[words];
            Arrays.fill(cut, 0, count, -1);
            Arrays.fill(pending, 0);
            any = false;
        }

        /**
         * Quads 0..n−1 in the layer's order (far to near), tile rectangles in t (x0, y0, x1, y1 inclusive; x0 > x1 off
         * screen), {@code soft[k]} — may draw at half resolution: fills {@link #where}.
         */
        void classify(boolean[] soft, int[] t, int n) {
            if (where.length < n) {
                where = new int[Math.max(n, 2 * where.length)];
                behind = new int[4 * where.length];
                ahead = new int[4 * where.length];
            }
            late = 0;
            for (int k = 0; k < n; k++) if (soft[k]) late = k + 1;
            for (int k = late; k < n; k++) where[k] = LATE;
            for (int k = 0; k < late; k++) {
                if (soft[k]) continue;
                for (int y = t[4 * k + 1]; y <= t[4 * k + 3]; y++) {
                    for (int x = t[4 * k]; x <= t[4 * k + 2]; x++) cut[y * tilesX + x] = k;
                }
            }
            for (int k = 0; k < late; k++) {
                if (!soft[k] || t[4 * k] > t[4 * k + 2]) {
                    where[k] = FRAME;
                    continue;
                }
                boolean back = false, front = false;
                int bx0 = Integer.MAX_VALUE, by0 = Integer.MAX_VALUE, bx1 = -1, by1 = -1;
                int fx0 = Integer.MAX_VALUE, fy0 = Integer.MAX_VALUE, fx1 = -1, fy1 = -1;
                for (int y = t[4 * k + 1]; y <= t[4 * k + 3]; y++) {
                    for (int x = t[4 * k]; x <= t[4 * k + 2]; x++) {
                        int i = y * tilesX + x;
                        if (k <= cut[i]) {
                            back = true;
                            bx0 = Math.min(bx0, x);
                            by0 = Math.min(by0, y);
                            bx1 = Math.max(bx1, x);
                            by1 = Math.max(by1, y);
                        } else {
                            front = true;
                            fx0 = Math.min(fx0, x);
                            fy0 = Math.min(fy0, y);
                            fx1 = Math.max(fx1, x);
                            fy1 = Math.max(fy1, y);
                            pending[i >>> 6] |= 1L << (i & 63);
                        }
                    }
                }
                any |= front;
                where[k] = !front ? FRAME : !back ? TARGET : FRAME_BEHIND | TARGET_FRONT;
                behind[4 * k] = bx0;
                behind[4 * k + 1] = by0;
                behind[4 * k + 2] = bx1;
                behind[4 * k + 3] = by1;
                ahead[4 * k] = fx0;
                ahead[4 * k + 1] = fy0;
                ahead[4 * k + 2] = fx1;
                ahead[4 * k + 3] = fy1;
            }
        }

        boolean laid(int i) {
            return (pending[i >>> 6] & 1L << (i & 63)) != 0;
        }
    }

    /** The half pass can run this frame (shaders loaded, targets not failed, world distance present). */
    private static boolean usable(int scene, int w, int h) {
        return !failed && depthShader != null && composite != null && scene >= 0 && w >= 4 && h >= 4;
    }

    /** Targets for a w×h frame and the half-resolution world distance from {@code scene}; false — no half pass this frame. */
    private static boolean begin(int scene, int w, int h) {
        if (!usable(scene, w, h)) return false;
        int hw = (w + 1) / 2, hh = (h + 1) / 2;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (hw != width || hh != height || accum < 0) {
            if (accum < 0) {
                accum = GlStateManager._genTexture();
                depth = GlStateManager._genTexture();
                accumFbo = GlStateManager.glGenFramebuffers();
                depthFbo = GlStateManager.glGenFramebuffers();
                for (int i = 0; i < cuts.length; i++) cuts[i] = GlStateManager._genTexture();
                for (int i = 0; i < rects.length; i++) rects[i] = GlStateManager._genTexture();
            }
            texture(accum, GL30.GL_RGBA16F, GL11.GL_RGBA, hw, hh);
            texture(depth, GL30.GL_R32F, GL11.GL_RED, hw, hh);
            boolean ok = attach(accumFbo, accum) && attach(depthFbo, depth);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            if (!ok) {
                Incoming.LOG.error("Effects layer: half-resolution smoke target {}×{} not created, smoke at full resolution", hw, hh);
                failed = true;
                return false;
            }
            width = hw;
            height = hh;
        }
        // world distance at half resolution, then an empty target
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFbo);
        GlStateManager._viewport(0, 0, width, height);
        RenderSystem.disableBlend();
        int was = RenderSystem.getShaderTexture(1);
        RenderSystem.setShaderTexture(1, scene);
        fullScreen(depthShader);
        RenderSystem.setShaderTexture(1, was);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, accumFbo);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._clearColor(0, 0, 0, 1);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        return true;
    }

    private static void texture(int id, int internal, int format, int w, int h) {
        GlStateManager._bindTexture(id);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, internal, w, h, 0, format, GL11.GL_FLOAT, null);
    }

    private static boolean attach(int fbo, int texture) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        return GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
    }

    private static void fullScreen(ShaderInstance s) {
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh == null) return;
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        s.apply();
        BufferUploader.draw(mesh);
        s.clear();
    }

    /**
     * Draw the layer's quads (sorted and uploaded, {@link FxQuads#prepare}) with layer shader s onto the bound w×h frame:
     * full-resolution quads and smoke behind its tile's cut onto the frame, smoke in front of it into the target, then the
     * target onto the frame. {@code scene} is the world distance texture; {@code viewProjection} maps the quads'
     * eye-relative vertices to clip space. The layer's state is restored afterwards.
     */
    static void draw(FxQuads q, ShaderInstance s, int scene, Matrix4fc viewProjection, int w, int h) {
        int n = q.size();
        lastLow = lastFull = lastFlushes = 0;
        planned = false;
        if (n == 0) return;
        Plan plan = PLAN;
        if (usable(scene, w, h)) {
            if (soft.length < n) {
                soft = new boolean[Math.max(n, 2 * soft.length)];
                tiles = new int[4 * soft.length];
            }
            plan.begin((w + TILE - 1) / TILE, (h + TILE - 1) / TILE);
            q.screen(viewProjection, w, h, TILE, WIDE, soft, tiles);
            plan.classify(soft, tiles, n);
        }
        // nothing for the target (or no target): the layer as it always drew, without the half pass's fixed cost
        if (!usable(scene, w, h) || !plan.any || !begin(scene, w, h)) {
            q.drawRange(s, 0, n, -1, 0, -1, TILE);
            lastFull = n;
            return;
        }
        int main = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        uploadCut(plan);
        uploadRects(plan, n, w, h);
        planned = true;
        lastScene = scene;
        try {
            // the frame's quads in order, then the target's: neither reads the other until the lay. One call each: every
            // quad clips to its rectangle for the pass (empty — dropped before raster), each fragment keeps its tile's side
            int rows = rectRows(n);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            layerBlend();
            int late = plan.late;
            q.drawRange(s, 0, late, -1, 1, cuts[ring], TILE, rects[ring], 0);
            if (plan.any) {
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, accumFbo);
                GlStateManager._viewport(0, 0, width, height);
                // light adds over what is behind, transmittance multiplies
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                        GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                q.drawRange(s, 0, late, depth, 2, cuts[ring], TILE, rects[ring], rows);
                flush(main, scene);
                lastFlushes = 1;
            }
            // nearer than all smoke: over it, in order
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            layerBlend();
            q.drawRange(s, late, n, -1, 0, -1, TILE);
            for (int k = 0; k < n; k++) {
                if ((plan.where[k] & (Plan.FRAME | Plan.FRAME_BEHIND | Plan.LATE)) != 0) lastFull++;
                if ((plan.where[k] & (Plan.TARGET | Plan.TARGET_FRONT)) != 0) lastLow++;
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            layerBlend();
        }
    }

    private static void layerBlend() {
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
    }

    /** This frame's tile cuts into the next texture of the ring (an upload never waits for a frame still drawing). */
    private static void uploadCut(Plan plan) {
        ring = (ring + 1) % cuts.length;
        int count = plan.tilesX * plan.tilesY;
        if (cutData.capacity() < count) {
            MemoryUtil.memFree(cutData);
            cutData = MemoryUtil.memAllocFloat(count);
        }
        FloatBuffer b = cutData;
        b.clear();
        b.put(plan.cut, 0, count).flip();
        if (cutW[ring] != plan.tilesX || cutH[ring] != plan.tilesY) {
            texture(cuts[ring], GL30.GL_R32F, GL11.GL_RED, plan.tilesX, plan.tilesY);
            cutW[ring] = plan.tilesX;
            cutH[ring] = plan.tilesY;
        }
        GlStateManager._bindTexture(cuts[ring]);
        GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, plan.tilesX, plan.tilesY, GL11.GL_RED, GL11.GL_FLOAT, b);
    }

    private static int rectRows(int n) {
        return (n + RECT_ROW - 1) / RECT_ROW;
    }

    /**
     * Clip rectangles of this frame's quads for both passes into the ring's texture (frame pass in rows from 0, target
     * pass after them): whole, a split quad's tiles on that side, or empty (the quad draws only in the other pass).
     */
    private static void uploadRects(Plan plan, int n, int w, int h) {
        int rows = rectRows(n), floats = 4 * 2 * rows * RECT_ROW;
        if (rectData.capacity() < floats) {
            MemoryUtil.memFree(rectData);
            rectData = MemoryUtil.memAllocFloat(floats);
        }
        FloatBuffer b = rectData;
        b.clear();
        for (int pass = 0; pass < 2; pass++) {
            int whole = pass == 0 ? Plan.FRAME : Plan.TARGET;
            int[] side = pass == 0 ? plan.behind : plan.ahead;
            for (int k = 0; k < n; k++) {
                int where = plan.where[k];
                if ((where & whole) != 0) {
                    b.put(-2).put(-2).put(2).put(2);
                } else if (where == (Plan.FRAME_BEHIND | Plan.TARGET_FRONT)) {
                    b.put(2f * side[4 * k] * TILE / w - 1).put(2f * side[4 * k + 1] * TILE / h - 1)
                            .put(2f * Math.min((side[4 * k + 2] + 1) * TILE, w) / w - 1).put(2f * Math.min((side[4 * k + 3] + 1) * TILE, h) / h - 1);
                } else {
                    b.put(2).put(2).put(-2).put(-2);
                }
            }
            while (b.position() < 4 * (pass + 1) * rows * RECT_ROW) b.put(0);
        }
        b.flip();
        if (rectRows[ring] < 2 * rows) {
            int size = Math.max(2 * rows, 2 * rectRows[ring]);
            GlStateManager._bindTexture(rects[ring]);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, RECT_ROW, size, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
            rectRows[ring] = size;
        }
        GlStateManager._bindTexture(rects[ring]);
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, RECT_ROW, 2 * rows, GL11.GL_RGBA, GL11.GL_FLOAT, b);
    }

    /** Lay the target onto the frame over the tiles holding its smoke (rows of adjacent tiles as one rectangle). */
    private static void flush(int main, int scene) {
        ShaderInstance c = composite;
        Plan plan = PLAN;
        int fw = VIEWPORT[2], fh = VIEWPORT[3];
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableBlend();
        // frame × transmittance + smoke light; the frame's alpha stays
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        for (int y = 0; y < plan.tilesY; y++) {
            for (int x = 0; x < plan.tilesX; ) {
                if (!plan.laid(y * plan.tilesX + x)) {
                    x++;
                    continue;
                }
                int from = x;
                while (x < plan.tilesX && plan.laid(y * plan.tilesX + x)) x++;
                float x0 = 2f * from * TILE / fw - 1, x1 = 2f * Math.min(x * TILE, fw) / fw - 1;
                float y0 = 2f * y * TILE / fh - 1, y1 = 2f * Math.min((y + 1) * TILE, fh) / fh - 1;
                b.addVertex(x0, y0, 0);
                b.addVertex(x1, y0, 0);
                b.addVertex(x1, y1, 0);
                b.addVertex(x0, y1, 0);
            }
        }
        MeshData mesh = b.build();
        if (mesh == null) return;
        if (c == null) {
            mesh.close();
            return;
        }
        int t0 = RenderSystem.getShaderTexture(0), t1 = RenderSystem.getShaderTexture(1), t2 = RenderSystem.getShaderTexture(2),
                t3 = RenderSystem.getShaderTexture(3);
        RenderSystem.setShaderTexture(0, accum);
        RenderSystem.setShaderTexture(1, depth);
        RenderSystem.setShaderTexture(2, scene);
        RenderSystem.setShaderTexture(3, cuts[ring]);
        c.safeGetUniform("HalfTile").set((float) TILE);
        c.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        // like every full-frame pass of the layer: the frame's depth is not the composite's business
        RenderSystem.disableDepthTest();
        c.apply();
        BufferUploader.draw(mesh);
        c.clear();
        RenderSystem.enableDepthTest();
        RenderSystem.setShaderTexture(0, t0);
        RenderSystem.setShaderTexture(1, t1);
        RenderSystem.setShaderTexture(2, t2);
        RenderSystem.setShaderTexture(3, t3);
    }

    /**
     * Drain frame ({@link LayerTimes}): each part of the layer alone as this frame drew it — its quads in the frame behind
     * their tiles' cuts, the rest into the target, laid onto the frame; samples of the target count four frame pixels.
     * false — this frame drew no half-resolution smoke (the parts draw as usual).
     */
    static boolean drawParts(FxQuads q, ShaderInstance s, LayerTimes.PartClock clock) {
        if (!planned) return false;
        int n = q.size(), rows = rectRows(n);
        int main = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int[][] runs = q.partRuns();
        clock.start();
        try {
            for (int p = 0; p < runs.length; p++) {
                int[] r = runs[p];
                int quads = 0;
                boolean target = false;
                for (int i = 0; i < r.length; i += 2) {
                    quads += r[i + 1];
                    for (int k = r[i]; k < r[i] + r[i + 1] && !target; k++) target = (PLAN.where[k] & (Plan.TARGET | Plan.TARGET_FRONT)) != 0;
                }
                int[] before = clip(r, 0, PLAN.late), after = clip(r, PLAN.late, n);
                clock.begin(p);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
                GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
                layerBlend();
                q.passBegin(s, -1, 1, cuts[ring], TILE, rects[ring], 0);
                FxQuads.multiDraw(before);
                q.passEnd(s, rects[ring], TILE);
                q.passBegin(s, -1, 0, -1, TILE, -1, 0);
                FxQuads.multiDraw(after);
                q.passEnd(s, -1, TILE);
                clock.half(p);
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, accumFbo);
                GlStateManager._viewport(0, 0, width, height);
                GlStateManager._clearColor(0, 0, 0, 1);
                GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
                RenderSystem.enableBlend();
                RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                        GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                q.passBegin(s, depth, 2, cuts[ring], TILE, rects[ring], rows);
                FxQuads.multiDraw(before);
                q.passEnd(s, rects[ring], TILE);
                clock.halfDone(p);
                if (target) flush(main, lastScene);
                clock.end(p, quads, r.length / 2);
            }
        } finally {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
            layerBlend();
        }
        return true;
    }

    /** Runs r (pairs start, length) cut to sorted places [from, to). */
    private static int[] clip(int[] r, int from, int to) {
        int[] out = new int[r.length];
        int m = 0;
        for (int i = 0; i < r.length; i += 2) {
            int a = Math.max(r[i], from), b = Math.min(r[i] + r[i + 1], to);
            if (b <= a) continue;
            out[m++] = a;
            out[m++] = b - a;
        }
        return Arrays.copyOf(out, m);
    }

    /** For the scenario line. */
    static String describe() {
        return String.format(java.util.Locale.ROOT, "half-resolution smoke %d quads, full %d (a quad over both kinds of tiles counts in each), lays %d",
                lastLow, lastFull, lastFlushes);
    }
}
