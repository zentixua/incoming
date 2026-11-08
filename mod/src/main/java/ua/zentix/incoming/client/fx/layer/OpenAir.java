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
 * Outdoor air and block light near the camera: Minecraft skylight and block light in a 96×64×96-block window around the eye,
 * as a 3D texture (RG8: skylight / 15, block light / 15). Pall dust and haze, wall head — matter in outdoor air: it enters tunnels, caves and basements
 * only through openings; pall rays (and pall shadow on the world, and pall before layer quads) use field extinction ×
 * this fraction ({@code pall.glsl}: {@code pallOpen}). Without it the pall field depends on height above coarse terrain, and
 * a few-block ray to a wall in a mine below the pall passed through dense dust — underground haze.
 * <p>
 * Skylight measures connection to open sky, computed by Minecraft itself: 15 outdoors (14 under leaves),
 * 15 to the bottom of a shaft open to the sky, then decreasing by 1 per tunnel block from its base; 0 in a sealed working. It does
 * not know how much dust enters an opening: the skylight fraction is an estimate (indoor concentration by air exchange with outdoors
 * is not computed), rather than a law. Outside the window — outdoor air (1): distant pall stays as before.
 * <p>
 * Block light ({@code G}, from the same client light engine) is what torches, lamps and other light-emitting blocks put into
 * that air: the pall's dust scatters it (a glow around a lamp in the haze, {@code fx_pall}) and the pall's shadow on the world
 * leaves the share of a pixel's light that came from them ({@code fx_pall_composite}). Light carried by entities (a torch held
 * with LambDynamicLights) is not in the light engine; {@link MovingLight} overlays it near the eye, as the larger of the two,
 * the way that mod lights blocks.
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
    /** Window contents as uploaded from the light engine (RG per voxel, texture layout): the moving-light overlay restores from it. */
    private static byte[] mirror;
    /** Voxels with block light above 0 in each slot (debug line), and their sum. */
    private static final int[] LIT = new int[SX * SY * SX];
    private static int lit;
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
            MovingLight.reset();
            world = new WeakReference<>(level);
        }
        OpenAir.eye = eye;
        int ex = (int) Math.floor(eye.x), ey = (int) Math.floor(eye.y), ez = (int) Math.floor(eye.z);
        x0 = start(ex, SIZE_XZ);
        y0 = start(ey, SIZE_Y);
        z0 = start(ez, SIZE_XZ);
        int sx0 = x0 >> 4, sy0 = y0 >> 4, sz0 = z0 >> 4;
        LayerLightEventListener sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        LayerLightEventListener block = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
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
                    lit -= LIT[slot];
                    LIT[slot] = 0;
                    fill(level, sky, block, sx, sy, sz);
                    keep(Math.floorMod(sx, SX) * 16, Math.floorMod(sy, SY) * 16, Math.floorMod(sz, SX) * 16);
                    lit += LIT[slot] = litIn();
                    GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, Math.floorMod(sx, SX) * 16, Math.floorMod(sy, SY) * 16, Math.floorMod(sz, SX) * 16,
                            16, 16, 16, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, section);
                    STORED[slot] = key;
                }
            }
        }
        refreshAt = (refreshAt + REFRESH) % all;
        // light carried by entities (LambDynamicLights) over the light engine's, after the sections it overlays were refreshed
        MovingLight.overlay(level, ex, ey, ez);
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

    /**
     * Section skylight and block light into {@link #section} (two bytes per voxel; x fastest, then y, then z — texture order).
     */
    private static void fill(ClientLevel level, LayerLightEventListener sky, LayerLightEventListener block, int sx, int sy, int sz) {
        section.clear();
        if (level.getChunkSource().getChunkNow(sx, sz) == null || sy < level.getMinSection() || sy >= level.getMaxSection()) {
            // no chunk — unknown: outdoor air, no lamps; above world — sky, below — enclosed
            byte open = sy < level.getMinSection() ? 0 : (byte) 255;
            for (int k = 0; k < 4096; k++) {
                section.put(2 * k, open);
                section.put(2 * k + 1, (byte) 0);
            }
            return;
        }
        SectionPos at = SectionPos.of(sx, sy, sz);
        DataLayer data = sky.getDataLayerData(at);
        // block light: a section without data has none (BlockLightSectionStorage: no light there)
        DataLayer lamps = block.getDataLayerData(at);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // sections without their own data take skylight from the column above (SkyLightSectionStorage): same at every column height
                int column = data == null ? sky.getLightValue(pos.set(sx * 16 + x, sy * 16, sz * 16 + z)) : -1;
                for (int y = 0; y < 16; y++) {
                    int v = column >= 0 ? column : data.get(x, y, z), k = (z * 16 + y) * 16 + x;
                    section.put(2 * k, (byte) (v * 17));
                    section.put(2 * k + 1, (byte) (lamps == null ? 0 : lamps.get(x, y, z) * 17));
                }
            }
        }
    }

    /** Voxels with block light in {@link #section}. */
    private static int litIn() {
        int n = 0;
        for (int k = 0; k < 4096; k++) if (section.get(2 * k + 1) != 0) n++;
        return n;
    }

    /** {@link #section} into {@link #mirror} at texture voxel (tx, ty, tz), its lower corner. */
    private static void keep(int tx, int ty, int tz) {
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                int from = 2 * ((z * 16 + y) * 16), to = 2 * (((tz + z) * SIZE_Y + ty + y) * SIZE_XZ + tx);
                for (int b = 0; b < 32; b++) mirror[to + b] = section.get(from + b);
            }
        }
    }

    /** Uploaded light-engine voxel at texture voxel (tx, ty, tz): skylight (channel 0) or block light (1), 0..255. */
    static int stored(int tx, int ty, int tz, int channel) {
        return mirror[2 * ((tz * SIZE_Y + ty) * SIZE_XZ + tx) + channel] & 0xFF;
    }

    /** Texture voxel of world block coordinate {@code c} along an axis of {@code size} (the window wraps modulo the size). */
    static int slot(int c, int size) {
        return Math.floorMod(c, size);
    }

    /** Whether world block (x, y, z) is inside this frame's window. */
    static boolean inside(int x, int y, int z) {
        return x >= x0 && x < x0 + SIZE_XZ && y >= y0 && y < y0 + SIZE_Y && z >= z0 && z < z0 + SIZE_XZ;
    }

    /** Whether this frame's window holds block light: from the light engine or carried by entities ({@link MovingLight}). */
    static boolean lamps() {
        return ready && (lit > 0 || MovingLight.lit());
    }

    /**
     * Debug line part: the window's block light (voxels lit by the light engine, level at the eye block from it and as uploaded
     * with moving light), and {@link MovingLight}.
     */
    static String describe() {
        if (!ready) return "lamps: no window";
        int ex = (int) Math.floor(eye.x), ey = (int) Math.floor(eye.y), ez = (int) Math.floor(eye.z);
        return String.format(java.util.Locale.ROOT, "lamps: %s, lit voxels %d, block light at eye %d, sky %d; %s", lamps() ? "on" : "off", lit,
                blockAt(ex, ey, ez), inside(ex, ey, ez) ? stored(slot(ex, SIZE_XZ), slot(ey, SIZE_Y), slot(ez, SIZE_XZ), 0) / 17 : -1,
                MovingLight.describe());
    }

    /** Debug: voxels in the window lit by the light engine's block light. */
    static int litVoxels() {
        return lit;
    }

    /** Debug: block light / 15 the light engine put at eye block (x, y, z), −1 outside the window. */
    static int blockAt(int x, int y, int z) {
        if (!ready || !inside(x, y, z)) return -1;
        return stored(slot(x, SIZE_XZ), slot(y, SIZE_Y), slot(z, SIZE_XZ), 1) / 17;
    }

    static int texture() {
        return texture;
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
        section = MemoryUtil.memAlloc(2 * 4096);
        mirror = new byte[2 * SIZE_XZ * SIZE_Y * SIZE_XZ];
        Arrays.fill(STORED, Long.MIN_VALUE);
        Arrays.fill(LIT, 0);
        lit = 0;
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        // linear and repeating: block light for the dust is read between voxel centres (pallBlockLight), across the window's
        // wrap seam as across any other voxel boundary; skylight and the surface's block light are read texel by texel
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_RG8, SIZE_XZ, SIZE_Y, SIZE_XZ, 0, GL30.GL_RG, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return true;
    }
}
