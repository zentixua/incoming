package ua.zentix.incoming.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Distant projectiles use their actual models ({@link WeaponModels}, same code and animations as nearby entities)
 * where the client has no entity. Each model in the frame renders into its own atlas tile: perspective from the
 * eye, only that model at that viewing angle, four times screen detail, world light as for entities (sky lightmap,
 * two {@code Lighting} directional lights); then mipmap the atlas and place the tile in the frame as a camera-facing
 * quad ({@code client.far.FarSprites}), with atmospheric haze in opacity and sorted with smoke, back to front.
 * A model spanning a few pixels thus has an antialiased silhouette without flickering thin wings; distant tiles,
 * like other distant objects, move inside the far plane with the same angular size. Below {@link #COARSE_BELOW}
 * pixels use simplified part copies ({@link WeaponModels.Mesh#lod}).
 * <p>
 * Part meshes stay in video memory ({@link VertexBuffer}, like vanilla sky and chunks): built and uploaded once
 * (world entry, {@link #warmup}; again after resource reload), while each frame only sets part position (shader
 * matrices) and draws it. CPU vertices each frame would cost up to 1 ms per full B-2 on the laptop and grow with
 * model count. Atlas uses its own shader ({@code shaders/core/far_model}) in {@code AFTER_LEVEL}: Iris replaces other
 * shaders only during world rendering. Tiles, parts, meshes and atlas live across frames; per frame, only small,
 * short-lived model animation objects.
 */
public final class FarModels {
    /** Model in any rotation with any parts deployed fits a sphere of this radius per model size ({@code FarModelsTest}). */
    public static final double TILE_RADIUS = 0.6;
    /**
     * On-screen model length, pixels: below {@link #DOT_BELOW} a point ({@code Sight.body}: shape indistinguishable,
     * only sky occlusion visible), above {@link #MODEL_FROM} a model, transition between.
     */
    public static final double DOT_BELOW = 2, MODEL_FROM = 3;
    /**
     * Below {@link #COARSE_BELOW} pixels, simplified copies (surface deviation 0.5 % of size, less than a quarter
     * pixel); above {@link #FULL_ABOVE}, full meshes; between, keep previous choice (no boundary jitter).
     */
    public static final double COARSE_BELOW = 40, FULL_ABOVE = 48;
    /**
     * Atlas side, texels: ~9 MB video memory with mipmaps and depth; mipmap the whole atlas each frame with distant
     * models. Full atlas means remaining models in the frame use points.
     */
    static final int ATLAS = 1024;
    /**
     * Texels per screen pixel; tile from {@link #MIN_TILE} to {@link #MAX_TILE}: largest with border is exactly half
     * the atlas side, fitting four (tile wider than 126 screen px has fewer than 4 texels per pixel: large models
     * hardly need antialiasing).
     */
    static final int SUPERSAMPLE = 4, MIN_TILE = 8, MAX_TILE = 504;
    /** Mip levels beyond base (4 texels per pixel is level 2); empty tile border keeps mipmaps from sampling neighbours. */
    static final int MIP_LEVELS = 2, PAD = 1 << MIP_LEVELS;
    /** {@link #add} tile output: right and up (quad axes), half-size, u0, v0, u1, v1. */
    public static final int RIGHT = 0, UP = 3, HALF = 6, U0 = 7, V0 = 8, U1 = 9, V1 = 10, OUT = 11;
    private static final WeaponModels.Mesh[] MESHES = WeaponModels.Mesh.values();
    private static final Matrix4f IDENTITY = new Matrix4f();

    @Nullable
    private static ShaderInstance shader;

    @Nullable
    private TextureTarget atlas;
    /** Part meshes in video memory: two per part (full, simplified), plus the source mesh for each. */
    private final VertexBuffer[] meshes = new VertexBuffer[2 * MESHES.length];
    private final QuadMesh[] uploaded = new QuadMesh[2 * MESHES.length];
    private final List<Tile> tiles = new ArrayList<>();
    private int count;
    /** Shelf being filled: free space's left edge, shelf bottom and height. */
    private int shelfX, shelfY, shelfH;
    /** Model parts for this frame. */
    private final List<Part> parts = new ArrayList<>();
    private int partCount;
    /** Tile whose parts are currently being recorded. */
    @Nullable
    private Tile current;
    private final WeaponModels.Parts record = this::record;
    private final PoseStack poses = new PoseStack();
    private final Quaternionf rotation = new Quaternionf();
    private final int[] viewport = new int[4];
    private final ByteBuffer colorWrite = BufferUtils.createByteBuffer(4);
    private final Runnable clearAndDraw = () -> {
        clear(rowsUsed());
        draw();
    }, warm = () -> {
        clear(ATLAS);
        draw();
        clear(ATLAS);
    };

    /** One frame model and its tile. */
    static final class Tile {
        final ProjectilePose pose = new ProjectilePose();
        @Nullable
        WeaponModels.Look look;
        /** Model → viewing axes: right, up, toward model (camera at origin). */
        final Matrix4f view = new Matrix4f();
        /** Viewing axes → atlas tile: eye perspective, tile corners ±tan, depth spans model sphere. */
        final Matrix4f projection = new Matrix4f();
        /** Atlas tile centre, texels, and side length. */
        int x, y, size;

        /**
         * Tile centred at (x, y), side size, for a sphere of radius h at distance d (view angle tangent tan).
         * Perspective: x/z, y/z tangents into tile square; depth d − h to d + h spans atlas depth.
         */
        void place(int x, int y, int size, float d, float h, float tan) {
            this.x = x;
            this.y = y;
            this.size = size;
            float a = (float) size / ATLAS / tan, cx = 2f * x / ATLAS - 1, cy = 2f * y / ATLAS - 1, near = d - h, far = d + h;
            projection.set(a, 0, 0, 0, 0, a, 0, 0, cx, cy, (far + near) / (far - near), 1, 0, 0, -2 * far * near / (far - near), 0);
        }
    }

    /** Frame part: mesh, tile, position (view axes), normal rotation (world axes), opacity. */
    private static final class Part {
        @Nullable
        WeaponModels.Mesh mesh;
        @Nullable
        VertexBuffer buffer;
        @Nullable
        Tile tile;
        final Matrix4f pose = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
        float alpha;
    }

    /** Mod bus: atlas shader (again on resource reload). */
    public static void registerShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(new ShaderInstance(e.getResourceProvider(), Incoming.id("far_model"), DefaultVertexFormat.NEW_ENTITY),
                    s -> shader = s);
        } catch (IOException ex) {
            shader = null;
            Incoming.LOG.error("Far model shader failed to load: distant projectiles will be dots", ex);
        }
    }

    /** Begin frame: empty atlas. */
    public void begin() {
        count = 0;
        shelfX = shelfY = shelfH = 0;
    }

    /** Models in frame. */
    public int count() {
        return count;
    }

    /**
     * Add a model to the frame. Camera at origin, model centre (dx, dy, dz), size is its dimension
     * ({@code FarLook.size}), pixel is screen pixel angle, camUp is screen up. out ({@link #OUT} numbers) receives
     * the quad placing the tile in frame: axes, half-size (at centre distance), atlas location.
     *
     * @return false if no model: no shader, too close (wider than 60°), at view edge, or atlas full
     */
    public boolean add(WeaponModels.Look look, ProjectilePose pose, double dx, double dy, double dz, double size, double pixel,
                       Vector3f camUp, float[] out) {
        if (shader == null) return false;
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz), h = TILE_RADIUS * size;
        if (d < 2 * h) return false;
        double fx = dx / d, fy = dy / d, fz = dz / d;
        // Right crosses ray and screen up; up crosses ray and right: tile rotated like the screen.
        double rx = fy * camUp.z() - fz * camUp.y(), ry = fz * camUp.x() - fx * camUp.z(), rz = fx * camUp.y() - fy * camUp.x();
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rl < 1e-3) return false;
        rx /= rl;
        ry /= rl;
        rz /= rl;
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;
        // Sphere of radius h at distance d subtends asin(h/d): tile square uses that angle's tangents.
        double tan = h / Math.sqrt(d * d - h * h);
        int side = tileSize(tan, pixel);
        int slot = side + 2 * PAD;
        if (shelfX + slot > ATLAS) {
            shelfY += shelfH;
            shelfX = shelfH = 0;
        }
        if (shelfY + slot > ATLAS) return false;
        Tile t = tile();
        t.look = look;
        t.pose.copy(pose);
        t.place(shelfX + PAD + side / 2, shelfY + PAD + side / 2, side, (float) d, (float) h, (float) tan);
        shelfX += slot;
        shelfH = Math.max(shelfH, slot);
        t.view.set((float) rx, (float) ux, (float) fx, 0, (float) ry, (float) uy, (float) fy, 0, (float) rz, (float) uz, (float) fz, 0,
                0, 0, (float) d, 1);
        out[RIGHT] = (float) rx;
        out[RIGHT + 1] = (float) ry;
        out[RIGHT + 2] = (float) rz;
        out[UP] = (float) ux;
        out[UP + 1] = (float) uy;
        out[UP + 2] = (float) uz;
        out[HALF] = (float) (d * tan);
        out[U0] = (float) (t.x - side / 2) / ATLAS;
        out[V0] = (float) (t.y - side / 2) / ATLAS;
        out[U1] = (float) (t.x + side / 2) / ATLAS;
        out[V1] = (float) (t.y + side / 2) / ATLAS;
        return true;
    }

    private Tile tile() {
        if (count == tiles.size()) tiles.add(new Tile());
        return tiles.get(count++);
    }

    /**
     * Tile side, texels: {@link #SUPERSAMPLE} texels per pixel of tile angle (2·tan/pixel pixels), multiple of 4
     * (mip level 2 does not split a texel between tiles), from {@link #MIN_TILE} to {@link #MAX_TILE}.
     */
    static int tileSize(double tan, double pixel) {
        int texels = (int) Math.ceil(SUPERSAMPLE * 2 * tan / pixel);
        return Math.clamp((texels + 3) & ~3, MIN_TILE, MAX_TILE);
    }

    /** Atlas texture (0 if not yet created). */
    public int texture() {
        return atlas == null ? 0 : atlas.getColorTextureId();
    }

    /**
     * Prepare at world entry and after resource reload: atlas, all part meshes (both copies) in video memory,
     * and one draw of each so driver prepares program and vertex arrays here, rather than the first frame showing
     * a distant projectile (that frame would cost 5 ms CPU on the laptop).
     */
    public void warmup() {
        if (shader == null) return;
        begin();
        Tile t = tile();
        t.place(PAD + MIN_TILE / 2, PAD + MIN_TILE / 2, MIN_TILE, 10, 1, 0.1f);
        t.view.identity().setTranslation(0, 0, 10);
        current = t;
        poses.last().pose().set(t.view);
        poses.last().normal().identity();
        for (WeaponModels.Mesh m : MESHES) {
            record(m, poses, false, 1);
            if (m.lod) record(m, poses, true, 1);
        }
        current = null;
        count = 0;
        withAtlasBound(warm);
    }

    /**
     * Draw frame models into atlas and mipmap it. Called after frame collection and before quad drawing;
     * restore previous framebuffer and viewport afterward.
     */
    public void render() {
        if (count == 0 || shader == null) return;
        for (int i = 0; i < count; i++) {
            Tile t = tiles.get(i);
            current = t;
            PoseStack.Pose root = poses.last();
            root.pose().set(t.view);
            root.normal().identity();
            // Normals in world axes: Lighting directional lights use these, as for nearby entities.
            poses.pushPose();
            poses.mulPose(t.pose.rotation(rotation));
            t.look.render(t.pose, poses, record);
            poses.popPose();
            t.look = null;
        }
        current = null;
        withAtlasBound(clearAndDraw);
    }

    /** Model part in tile {@link #current} at current pose (upload mesh to video memory if absent). */
    private void record(WeaponModels.Mesh mesh, PoseStack pose, boolean coarse, float alpha) {
        VertexBuffer buffer = buffer(mesh, coarse);
        if (buffer == null) return;
        if (partCount == parts.size()) parts.add(new Part());
        Part p = parts.get(partCount++);
        p.mesh = mesh;
        p.buffer = buffer;
        p.tile = current;
        p.pose.set(pose.last().pose());
        p.normal.set(pose.last().normal());
        p.alpha = alpha;
    }

    /**
     * Part mesh in video memory: built on first draw and again when part mesh is rebuilt (resource reload);
     * sky light, or baked light if brighter ({@code glow}). {@code null} for empty mesh.
     */
    @Nullable
    private VertexBuffer buffer(WeaponModels.Mesh mesh, boolean coarse) {
        int i = 2 * mesh.ordinal() + (coarse && mesh.lod ? 1 : 0);
        QuadMesh source = mesh.mesh(coarse);
        if (uploaded[i] != source) {
            uploaded[i] = source;
            try (ByteBufferBuilder bytes = new ByteBufferBuilder(Math.max(256, source.bakedBytes()))) {
                MeshData data = source.bake(bytes, LightTexture.FULL_SKY);
                if (data == null) {
                    if (meshes[i] != null) meshes[i].close();
                    meshes[i] = null;
                } else {
                    if (meshes[i] == null) meshes[i] = new VertexBuffer(VertexBuffer.Usage.STATIC);
                    meshes[i].bind();
                    meshes[i].upload(data);
                    VertexBuffer.unbind();
                }
            }
        }
        return meshes[i];
    }

    private void withAtlasBound(Runnable work) {
        int drawFbo = GlStateManager.getBoundFramebuffer(), readFbo = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        // Write masks as left by previous renderer: atlas clearing enables both (clear).
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, colorWrite);
        try {
            if (atlas == null) create();
            atlas.bindWrite(false);
            GlStateManager._viewport(0, 0, ATLAS, ATLAS);
            work.run();
            RenderSystem.activeTexture(GL13.GL_TEXTURE0);
            RenderSystem.bindTexture(atlas.getColorTextureId());
            GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
        } finally {
            partCount = 0;
            RenderSystem.depthMask(depthWrite);
            RenderSystem.colorMask(colorWrite.get(0) != 0, colorWrite.get(1) != 0, colorWrite.get(2) != 0, colorWrite.get(3) != 0);
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    private void create() {
        TextureTarget t = new TextureTarget(ATLAS, ATLAS, true, Minecraft.ON_OSX);
        RenderSystem.bindTexture(t.getColorTextureId());
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, MIP_LEVELS);
        atlas = t;
    }

    /** Atlas rows occupied by frame tiles. */
    private int rowsUsed() {
        return Math.max(PAD, Math.min(ATLAS, shelfY + shelfH));
    }

    /**
     * Clear bottom rows atlas rows (transparent; depth farthest). Clear obeys write masks; Iris with a shader pack
     * leaves depth writes disabled at {@code AFTER_LEVEL} ({@code FinalPassRenderer.renderFinalPass}): without our
     * own mask, atlas depth would accumulate across frames and tear a moving model into pieces.
     */
    private static void clear(int rows) {
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableScissor(0, 0, ATLAS, rows);
        GlStateManager._clearColor(0, 0, 0, 0);
        GlStateManager._clearDepth(1);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
        RenderSystem.disableScissor();
    }

    /** Frame parts into atlas: opaque with depth first, then blurred propeller discs over them with premultiplied alpha. */
    private void draw() {
        ShaderInstance s = shader;
        if (s == null || partCount == 0) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != null && level.effects().constantAmbientLight()) Lighting.setupNetherLevel();
        else Lighting.setupLevel();
        mc.gameRenderer.lightTexture().turnOnLightLayer();
        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        // Like vanilla chunks: program and textures once, matrices and draw call per part.
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, IDENTITY, IDENTITY, mc.getWindow());
        s.apply();
        Uniform normal = s.getUniform("NormalMat");
        pass(s, normal, false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.depthMask(false);
        pass(s, normal, true);
        s.clear();
        VertexBuffer.unbind();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        mc.gameRenderer.lightTexture().turnOffLightLayer();
    }

    private void pass(ShaderInstance s, @Nullable Uniform normal, boolean translucent) {
        Tile tile = null;
        for (int i = 0; i < partCount; i++) {
            Part p = parts.get(i);
            if (p.mesh.translucent != translucent) continue;
            if (p.tile != tile) {
                tile = p.tile;
                uniform(s.PROJECTION_MATRIX, tile.projection);
            }
            uniform(s.MODEL_VIEW_MATRIX, p.pose);
            if (normal != null) {
                normal.set(p.normal);
                normal.upload();
            }
            if (s.COLOR_MODULATOR != null) {
                s.COLOR_MODULATOR.set(1, 1, 1, p.alpha);
                s.COLOR_MODULATOR.upload();
            }
            p.buffer.bind();
            p.buffer.draw();
        }
    }

    private static void uniform(@Nullable Uniform u, Matrix4f m) {
        if (u == null) return;
        u.set(m);
        u.upload();
    }
}
