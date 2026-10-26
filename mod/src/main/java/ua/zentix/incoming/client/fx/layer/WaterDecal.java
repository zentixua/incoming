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
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.material.FogType;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.client.far.FarView;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Effects layer water decal: dark nuclear wave front ring — ripples under post-front wind, with foam near the epicentre
 * (model profile — {@code client/nuclear/NukeWater}). One full-frame pass before layer quads: position behind each
 * pixel from world distance ({@link SceneDepth}: Minecraft and Distant Horizons depth) and frame matrices;
 * water identified by a level surface (surface behind pixel horizontal, neighbours at the same height: walls, trunks,
 * grass, mobs and object edges against the sky at the same within-block height are not water) at still-water top — 8/9 block
 * (within render distance; water writes depth in vanilla and Iris; tolerance grows with distant depth error and, under a shader pack,
 * its waves and frame jitter, {@link #PACK_DROP}, {@link #PACK_STEP}, {@link #PACK_JITTER}); beyond render distance (DH LOD) — sea surface
 * or a point below it, then ray/sea-surface intersection (LOD without water depth shows the bottom). Each ring has its own ripple and foam
 * noise from its centre. Minecraft or the shader pack draws water; the decal only darkens it and adds white
 * foam, with the same air haze and Minecraft fog as particles.
 * <p>
 * Ring profile — a row in texture {@link #SAMPLES}×{@link #RINGS}: darkness and foam behind the front at {@link #sampleAt}
 * blocks (samples denser near the sharp front).
 */
public final class WaterDecal {
    /** Maximum frame rings; profile samples. */
    public static final int RINGS = 4, SAMPLES = 64;
    /** Still-water top above block bottom ({@code LiquidBlockRenderer} — 8/9). */
    public static final double SURFACE = 8.0 / 9.0;
    /**
     * Client sea level: no client generator; {@code Level.getSeaLevel} in 1.21.1 is deprecated and always 63 —
     * Overworld sea (water through 62, first air above — 63).
     */
    public static final int SEA_LEVEL = 63;
    /**
     * Under a shader pack, waves lower the surface below 8/9, blocks: Complementary Reimagined (host's pack) by default lowers
     * water-top vertices ({@code WAVING_WATER_VERTEX}: −0.05 ± 0.025 under open sky, −0.05 under a roof), with margin.
     */
    static final float PACK_DROP = 0.08f;
    /** Shader-pack surface is not flat: neighbouring vertex height difference, blocks (Complementary — up to 0.05). */
    static final float PACK_STEP = 0.05f;
    /**
     * Shader-pack frame jitter for TAA, pixels: depth was written by a ray shifted by a pixel fraction, while the decal reads
     * it along the pixel-centre ray — position height error is shift × distance. Complementary default
     * ({@code TAA_JITTER} 1) — up to 0.055 pixel.
     */
    static final float PACK_JITTER = 0.06f;
    @Nullable
    static ShaderInstance shader;
    private static final double[][] RING = new double[RINGS][4];
    private static final String[] RING_UNIFORMS = {"Ring0", "Ring1", "Ring2", "Ring3"};
    private static final ByteBuffer PIXELS = BufferUtils.createByteBuffer(SAMPLES * RINGS * 4);
    private static final Matrix4f INV_PROJ = new Matrix4f(), INV_VIEW = new Matrix4f();
    private static final Vector4f HAZE = new Vector4f();
    private static int count, texture = -1, lastCount;
    private static boolean wanted, packChecked;
    /** {@code IrisApi.isShaderPackInUse} (reflection: mod does not depend on Iris); null — no Iris. */
    @Nullable
    private static MethodHandle packInUse;

    private WaterDecal() {}

    /** Distance behind the front for profile sample {@code i} of {@code length}, blocks: denser near front. */
    public static double sampleAt(int i, double length) {
        double f = (double) i / (SAMPLES - 1);
        return length * f * f;
    }

    /** Frame start: no rings yet; whether needed (shader exists, dimension has sky, camera outside water). */
    static void begin(@Nullable ClientLevel level, Camera camera) {
        lastCount = count;
        count = 0;
        wanted = shader != null && level != null && level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling()
                && camera.getFluidInCamera() == FogType.NONE;
    }

    /** Rings needed this frame (otherwise skip profile computation). */
    public static boolean wanted() {
        return wanted && count < RINGS;
    }

    /**
     * Add a frame ring: centre (world, blocks), water front radius, profile length behind it and profile — water darkness and foam
     * 0..1 at {@link #sampleAt} blocks behind the front. False — no slot.
     */
    public static boolean ring(double x, double z, double radius, double length, float[] dark, float[] foam) {
        return ring(x, z, radius, length, dark, foam, false);
    }

    /** Conventional water blast: patchy — lace-like foam with capped opacity; nuclear whitecaps unchanged. */
    public static boolean ring(double x, double z, double radius, double length, float[] dark, float[] foam, boolean patchy) {
        if (!wanted()) return false;
        int row = count++;
        RING[row][0] = x;
        RING[row][1] = z;
        RING[row][2] = radius;
        RING[row][3] = Math.max(1, length);
        for (int i = 0; i < SAMPLES; i++) {
            int at = (row * SAMPLES + i) * 4;
            PIXELS.put(at, (byte) Math.round(Math.max(0, Math.min(1, dark[i])) * 255));
            PIXELS.put(at + 1, (byte) Math.round(Math.max(0, Math.min(1, foam[i])) * 255));
            PIXELS.put(at + 2, (byte) (patchy ? 255 : 0));
            PIXELS.put(at + 3, (byte) 255);
        }
        return true;
    }

    /** There is something to draw. */
    static boolean active() {
        return count > 0 && shader != null;
    }

    /**
     * Decal pass: before layer quads, after {@code FarDraw.begin} (eye-relative view; custom full-frame matrices here).
     * {@code scene} — world-distance texture ({@link SceneDepth#update}).
     */
    static void draw(RenderLevelStageEvent e, FarView view, int scene, FxFrame.Fog fog) {
        ShaderInstance s = shader;
        if (s == null || count == 0 || scene < 0) return;
        upload();
        Camera camera = e.getCamera();
        var cam = camera.getPosition();
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, scene);
        RenderSystem.setShaderTexture(1, texture);
        s.safeGetUniform("InvProj").set(INV_PROJ.set(e.getProjectionMatrix()).invert());
        s.safeGetUniform("InvView").set(INV_VIEW.set(e.getModelViewMatrix()).invert());
        s.safeGetUniform("CamY").set((float) cam.y);
        double sea = SEA_LEVEL - 1 + SURFACE;
        s.safeGetUniform("WaterParams").set((float) (0.8 * view.world()), (float) (sea - cam.y), (float) count, view.ambient());
        Vector4f haze = fog.haze(view.range(), HAZE);
        s.safeGetUniform("Haze").set(haze.x, haze.y, haze.z, haze.w);
        s.safeGetUniform("Pixel").set((float) view.pixel());
        boolean pack = shaderPack();
        s.safeGetUniform("Pack").set(pack ? PACK_DROP : 0f, pack ? PACK_STEP : 0f, pack ? PACK_JITTER : 0f);
        for (int i = 0; i < RINGS; i++) {
            double[] r = RING[i];
            s.safeGetUniform(RING_UNIFORMS[i]).set((float) (r[0] - cam.x), (float) (r[1] - cam.z), (float) r[2], (float) r[3]);
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.enableDepthTest();
    }

    /** Iris shader pack currently renders the world. */
    private static boolean shaderPack() {
        if (!packChecked) {
            packChecked = true;
            packInUse = irisPackInUse();
        }
        if (packInUse == null) return false;
        try {
            return (boolean) packInUse.invokeExact();
        } catch (Throwable t) {
            packInUse = null;
            Incoming.LOG.warn("Iris: shader pack check failed, the water decal assumes no shader pack", t);
            return false;
        }
    }

    @Nullable
    private static MethodHandle irisPackInUse() {
        if (!ModList.get().isLoaded("iris")) return null;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object iris = api.getMethod("getInstance").invoke(null);
            return MethodHandles.publicLookup().findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(iris);
        } catch (ReflectiveOperationException | LinkageError e) {
            Incoming.LOG.warn("Iris: no shader pack API, the water decal assumes no shader pack", e);
            return null;
        }
    }

    /** Frame profiles to texture (custom 64×4 RGBA8: darkness, foam; allocate once, then update pixels only). */
    private static void upload() {
        boolean fresh = texture < 0;
        if (fresh) {
            texture = GlStateManager._genTexture();
            GlStateManager._bindTexture(texture);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(texture);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        PIXELS.position(0);
        if (fresh) {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SAMPLES, RINGS, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, PIXELS);
        } else {
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, SAMPLES, RINGS, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, PIXELS);
        }
    }

    /** For the scenario line: water rings in the previous frame. */
    public static String describe() {
        return String.format(Locale.ROOT, "%d ring(s)%s", lastCount, shader == null ? " (no shader)" : "");
    }
}
