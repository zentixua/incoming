package ua.zentix.incoming.client.nuclear;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import ua.zentix.incoming.Incoming;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;

/**
 * Nuclear fireball surface pattern ({@code textures/fx/nukeball.bin}, {@code tools/bake_fx}: Blender bake
 * and {@code pack.py}): each frame maps directions from the ball center (octahedral, y axis up); each texel
 * holds how many times the local surface-temperature variation exceeds the mean; mean p⁴ over the sphere is 1 (Stefan–Boltzmann:
 * the ball emits the model power). Brightness and color are a black body at the {@code MushroomModel} temperature; the pattern only sets where
 * the surface is hotter or colder. Frames follow the ball's rise distance in ball radii ({@code FireballModel.surfaceRise}).
 * Uploaded as a 3D R16 texture (u, v, frame): linear between frames. Drawn by the pall pass
 * ({@code fx_pall}: {@code PallBall}); if not loaded, the ball has no pattern, as before ({@link #ready}).
 */
public final class NukeBallSurface extends SimplePreparableReloadListener<NukeBallSurface.Data> {
    public static final NukeBallSurface INSTANCE = new NukeBallSurface();
    private static final ResourceLocation FILE = Incoming.id("textures/fx/nukeball.bin");
    /** File pattern is p / {@link #P_MAX} in 16 bits ({@code pack.py}: BALL_P). */
    public static final double P_MAX = 2.0;
    /** Texture unit: ShaderInstance does not bind 3D textures (all its textures are 2D); own unit, own binding. */
    static final int UNIT = 15;

    /** Loaded file: map size, frame rise distance in ball radii, 16-bit pattern (frame, v, u). */
    public record Data(int side, float[] rises, short[] values) {
        /** Pattern in texel (u, v) of frame k. */
        public double at(int k, int u, int v) {
            return (values[(k * side + v) * side + u] & 0xFFFF) / 65535.0 * P_MAX;
        }
    }

    private static volatile Data data;
    private static int texture = -1;

    private NukeBallSurface() {
    }

    /** File parsing: int32 size, int32 frame count, float32 frame distance, uint16 pattern — all little-endian. */
    public static Data parse(InputStream in) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
        int side = b.getInt(), frames = b.getInt();
        if (side < 2 || side > 1024 || frames < 2 || frames > 1024) throw new IOException("bad header " + side + "×" + frames);
        float[] rises = new float[frames];
        for (int i = 0; i < frames; i++) rises[i] = b.getFloat();
        short[] values = new short[side * side * frames];
        b.asShortBuffer().get(values);
        return new Data(side, rises, values);
    }

    @Override
    protected Data prepare(ResourceManager resources, ProfilerFiller profiler) {
        Optional<Resource> res = resources.getResource(FILE);
        if (res.isEmpty()) {
            Incoming.LOG.warn("{} not found: the nuclear fireball has no baked surface", FILE);
            return null;
        }
        try (InputStream in = res.get().open()) {
            return parse(in);
        } catch (IOException | RuntimeException e) {
            Incoming.LOG.warn("{} could not be read: the nuclear fireball has no baked surface", FILE, e);
            return null;
        }
    }

    /** Render thread: upload to video memory (reuse the texture on resource reload). */
    @Override
    protected void apply(Data d, ResourceManager resources, ProfilerFiller profiler) {
        if (d == null) {
            data = null;
            return;
        }
        if (texture < 0) texture = GlStateManager._genTexture();
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        ByteBuffer buf = MemoryUtil.memAlloc(d.values.length * 2);
        try {
            buf.asShortBuffer().put(d.values);
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R16, d.side, d.side, d.rises.length, 0, GL11.GL_RED,
                    GL11.GL_UNSIGNED_SHORT, buf);
        } finally {
            MemoryUtil.memFree(buf);
        }
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(unit);
        data = d;
    }

    /** Pattern loaded and in video memory: the ball uses it ({@code MushroomField}: pattern share), otherwise the previous billow tongues. */
    public static boolean ready() {
        return data != null;
    }

    /**
     * Frame position for rise distance {@code rise} (ball radii): index plus fraction along the frame distance (linear between frames,
     * clamped at the ends).
     */
    public static double frame(double rise) {
        Data d = data;
        return d == null ? 0 : frame(d.rises, rise);
    }

    public static double frame(float[] rises, double rise) {
        if (rise <= rises[0]) return 0;
        for (int i = 0; i < rises.length - 1; i++) {
            if (rise <= rises[i + 1]) return i + (rise - rises[i]) / Math.max(rises[i + 1] - rises[i], 1e-9);
        }
        return rises.length - 1;
    }

    /** Frame rise distance in ball radii; null — pattern not loaded. */
    public static float[] rises() {
        Data d = data;
        return d == null ? null : d.rises;
    }

    /** Frames in the texture (0 — not loaded). */
    public static int frames() {
        Data d = data;
        return d == null ? 0 : d.rises.length;
    }

    /** Bind to the pall ray pass after shader {@code apply()}: unit {@link #UNIT}, sampler {@code PallBall}. */
    public static void bind(ShaderInstance s) {
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, data != null ? texture : 0);
        GlStateManager._activeTexture(unit);
        com.mojang.blaze3d.shaders.Uniform.uploadInteger(com.mojang.blaze3d.shaders.Uniform.glGetUniformLocation(s.getId(), "PallBall"), UNIT);
    }

    /** Unbind after rendering. */
    public static void unbind() {
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(unit);
    }

    /**
     * Octahedral direction map (x, y, z; y up) → u 0..1 (as in {@code mushOctU} in {@code mushroom.glsl} and
     * {@code octa_decode} in {@code pack.py}): upper hemisphere — diamond in the center; lower — its corners.
     */
    public static double octU(double x, double y, double z) {
        double s = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (y < 0) return 0.5 * ((1 - Math.abs(z / s)) * Math.signum(x) + 1);
        return 0.5 * (x / s + 1);
    }

    /** Same for v ({@code mushOctV}). */
    public static double octV(double x, double y, double z) {
        double s = Math.abs(x) + Math.abs(y) + Math.abs(z);
        if (y < 0) return 0.5 * ((1 - Math.abs(x / s)) * Math.signum(z) + 1);
        return 0.5 * (z / s + 1);
    }
}
