package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Outdoor air near the camera: Minecraft skylight in a 96×64×96-block window around the eye, as a 3D texture (R8,
 * skylight / 15). Pall dust and haze, wall head — matter in outdoor air: it enters tunnels, caves and basements
 * only through openings; pall rays (and pall shadow on the world, and pall before layer quads) use field extinction ×
 * this fraction ({@code pall.glsl}: {@code pallOpen}). Without it the pall field depends on height above coarse terrain, and
 * a few-block ray to a wall in a mine below the pall passed through dense dust — underground haze.
 * <p>
 * Skylight measures connection to open sky, computed by Minecraft itself: 15 outdoors (14 under leaves),
 * 15 to the bottom of a shaft open to the sky, then decreasing by 1 per tunnel block from its base; 0 in a sealed working. It does
 * not know how much dust enters an opening: the skylight fraction is an estimate (indoor concentration by air exchange with outdoors
 * is not computed), rather than a law. Outside the window — outdoor air (1): distant pall stays as before.
 * <p>
 * Window uses 16³ sections, wrapped modulo texture size (world section (x, y, z) always uses the same texture slot): eye
 * enters a neighbouring section — fill only newly included sections; reread {@link #REFRESH} sections per frame
 * in rotation (light changes as the shock wave breaks walls and roofs): entire window in 36 frames.
 * While the pall is out of view, the window is neither updated nor reset (returning does not refill it all);
 * reset on world change. No client chunk — outdoor air.
 */
public final class OpenAir {
    /** Window, blocks: horizontal and vertical (multiples of 16). */
    static final int SIZE_XZ = 96, SIZE_Y = 64;
    private static final int SX = SIZE_XZ / 16, SY = SIZE_Y / 16;
    /** Sections refreshed per frame. */
    static final int REFRESH = 4;
    /** Custom texture unit (like pall noise, {@code PallPass.NOISE_UNIT}: ShaderInstance does not bind 3D textures). */
    static final int UNIT = 13;

    private static int texture = -1;
    private static ByteBuffer section;
    /** World section in each window slot ({@link SectionPos#asLong}); {@link Long#MIN_VALUE} — none. */
    private static final long[] STORED = new long[SX * SY * SX];
    private static int refreshAt;
    /** Lower world window corner (blocks), and whether this frame has a window. */
    private static int x0, y0, z0;
    private static boolean ready;
    /** Frame eye used by shaders to compute positions. */
    private static Vec3 eye = Vec3.ZERO;
    /**
     * World owning window sections (different world — rebuild window: sections at the same positions are not its own); weak reference avoids retaining
     * a departed world.
     */
    private static WeakReference<ClientLevel> world = new WeakReference<>(null);

    private OpenAir() {}

    /**
     * Window around {@code eye} for this frame (new sections and {@link #REFRESH} in rotation). Called by the pall pass before
     * its rays; false — no window (shader treats all air as outdoors).
     */
    static boolean update(ClientLevel level, Vec3 eye) {
        // a skyless dimension (End) has zero skylight everywhere: no window there, all air is outdoors
        if (!level.dimensionType().hasSkyLight() || !ensure()) return ready = false;
        if (level != world.get()) {
            Arrays.fill(STORED, Long.MIN_VALUE);
            world = new WeakReference<>(level);
        }
        OpenAir.eye = eye;
        int ex = (int) Math.floor(eye.x), ey = (int) Math.floor(eye.y), ez = (int) Math.floor(eye.z);
        x0 = start(ex, SIZE_XZ);
        y0 = start(ey, SIZE_Y);
        z0 = start(ez, SIZE_XZ);
        int sx0 = x0 >> 4, sy0 = y0 >> 4, sz0 = z0 >> 4;
        LayerLightEventListener sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        // tightly packed unpacking: vanilla leaves row length and skips from its images (maps, font glyphs), and
        // the driver would read beyond a 4 KB section; section rows are 16 bytes, unaffected by alignment (4)
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        int i = 0, all = SX * SY * SX;
        for (int sx = sx0; sx < sx0 + SX; sx++) {
            for (int sy = sy0; sy < sy0 + SY; sy++) {
                for (int sz = sz0; sz < sz0 + SX; sz++) {
                    int slot = (Math.floorMod(sx, SX) * SY + Math.floorMod(sy, SY)) * SX + Math.floorMod(sz, SX);
                    long key = SectionPos.asLong(sx, sy, sz);
                    boolean due = Math.floorMod(i++ - refreshAt, all) < REFRESH;
                    if (STORED[slot] == key && !due) continue;
                    fill(level, sky, sx, sy, sz);
                    GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, Math.floorMod(sx, SX) * 16, Math.floorMod(sy, SY) * 16, Math.floorMod(sz, SX) * 16,
                            16, 16, 16, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, section);
                    STORED[slot] = key;
                }
            }
        }
        refreshAt = (refreshAt + REFRESH) % all;
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return ready = true;
    }

    /**
     * Lower window bound along an axis of {@code size} with eye in block {@code eye}: section-aligned, eye at least
     * size/2 − 8 blocks from the edge (40 horizontally, 24 vertically).
     */
    static int start(int eye, int size) {
        return Math.floorDiv(eye - size / 2 + 8, 16) * 16;
    }

    /** Section skylight into {@link #section} (x fastest, then y, then z — texture order). */
    private static void fill(ClientLevel level, LayerLightEventListener sky, int sx, int sy, int sz) {
        section.clear();
        if (level.getChunkSource().getChunkNow(sx, sz) == null || sy < level.getMinSection() || sy >= level.getMaxSection()) {
            // no chunk — unknown: outdoor air; above world — sky, below — enclosed
            byte open = sy < level.getMinSection() ? 0 : (byte) 255;
            for (int k = 0; k < 4096; k++) section.put(k, open);
            return;
        }
        DataLayer data = sky.getDataLayerData(SectionPos.of(sx, sy, sz));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // sections without their own data take skylight from the column above (SkyLightSectionStorage): same at every column height
                int column = data == null ? sky.getLightValue(pos.set(sx * 16 + x, sy * 16, sz * 16 + z)) : -1;
                for (int y = 0; y < 16; y++) {
                    int v = column >= 0 ? column : data.get(x, y, z);
                    section.put((z * 16 + y) * 16 + x, (byte) (v * 17));
                }
            }
        }
    }

    /**
     * This frame's window to shader {@code s} ({@code on} — pall drawn this frame, window valid): texture at unit
     * {@link #UNIT} ({@code PallOpen}), {@code PallOpenAt} — lower corner relative to frame eye (xyz), window present (w),
     * {@code PallOpenWrap} — texture position of that corner. Call before shader drawing: numbers are sent by {@code apply},
     * sampler unit set in the program immediately. A 3D texture on its own unit does not interfere with other
     * programs: different sampler types are forbidden only on the same unit within one program.
     */
    static void bind(ShaderInstance s, boolean on) {
        on &= ready && texture >= 0;
        s.safeGetUniform("PallOpenAt").set((float) (x0 - eye.x), (float) (y0 - eye.y), (float) (z0 - eye.z), on ? 1f : 0f);
        s.safeGetUniform("PallOpenWrap").set((float) Math.floorMod(x0, SIZE_XZ), (float) Math.floorMod(y0, SIZE_Y), (float) Math.floorMod(z0, SIZE_XZ));
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, on ? texture : 0);
        GlStateManager._activeTexture(unit);
        // unit index into the program itself on every bind: Veil recompiles shaders, resetting program
        // uniforms (a one-time unit assignment would vanish — window sampler on unit 0); restore current program
        // (ShaderInstance remembers the current program and does not switch)
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        ProgramManager.glUseProgram(s.getId());
        Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "PallOpen"), UNIT);
        ProgramManager.glUseProgram(program);
    }

    private static boolean ensure() {
        if (texture >= 0) return true;
        section = MemoryUtil.memAlloc(4096);
        Arrays.fill(STORED, Long.MIN_VALUE);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SIZE_XZ, SIZE_Y, SIZE_XZ, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return true;
    }
}
