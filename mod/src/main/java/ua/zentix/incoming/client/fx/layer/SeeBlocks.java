package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * See-through blocks around the eye ({@link SeeThrough}): which blocks of a 96×64×96 window are drawn translucent (stained and
 * tinted glass, ice, slime, honey, a portal, water — their chunk render type is translucent) and which of them are a liquid.
 * {@code fx_depth} looks a translucent surface up here:
 * <ul>
 *   <li>with an Iris shader pack the pack also draws entities and the hand after the opaque-depth copy (Iris batches entity
 *       rendering until the translucent pass): only a surface standing in a see-through block is looked through; anything
 *       else nearer than the copy is opaque, as before;</li>
 *   <li>a liquid's top seen from above is the world (our effects are not under water), glass seen from above — a skylight —
 *       is looked through.</li>
 * </ul>
 * Outside the window the shader falls back to the surface's orientation. Sections are filled as the eye enters them and
 * {@link #REFRESH} per frame in rotation (broken windows); a section whose palette has no see-through block is filled with
 * zeros without reading its blocks. Kept only while the layer looks through surfaces; primary view only.
 */
final class SeeBlocks {
    /** Window, blocks: horizontal and vertical (multiples of 16). */
    static final int SIZE_XZ = 96, SIZE_Y = 64;
    private static final int SX = SIZE_XZ / 16, SY = SIZE_Y / 16;
    static final int REFRESH = 4;
    /** Own texture unit ({@code fx_depth}: {@code SeeBlocks}). */
    static final int UNIT = 18;
    /** Voxel bits: a see-through block, a see-through liquid. */
    static final byte SEE = 1, LIQUID = 2;

    private static int texture = -1;
    private static ByteBuffer section;
    private static final long[] STORED = new long[SX * SY * SX];
    private static int refreshAt;
    private static int x0, y0, z0;
    private static boolean ready;
    private static WeakReference<ClientLevel> world = new WeakReference<>(null);
    /** Bits per block state: the render type comes from the state's model and does not change while models stay loaded. */
    private static final Map<BlockState, Byte> KIND = new IdentityHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create(0);

    private SeeBlocks() {}

    /** The window around the frame eye; false — none this frame (the shader uses the surface orientation everywhere). */
    static boolean update(ClientLevel level, Vec3 eye) {
        if (!ensure()) return ready = false;
        if (level != world.get()) {
            Arrays.fill(STORED, Long.MIN_VALUE);
            world = new WeakReference<>(level);
        }
        x0 = OpenAir.start((int) Math.floor(eye.x), SIZE_XZ);
        y0 = OpenAir.start((int) Math.floor(eye.y), SIZE_Y);
        z0 = OpenAir.start((int) Math.floor(eye.z), SIZE_XZ);
        int sx0 = x0 >> 4, sy0 = y0 >> 4, sz0 = z0 >> 4;
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        int i = 0, all = SX * SY * SX;
        for (int sx = sx0; sx < sx0 + SX; sx++) {
            for (int sy = sy0; sy < sy0 + SY; sy++) {
                for (int sz = sz0; sz < sz0 + SX; sz++) {
                    int slot = (Math.floorMod(sx, SX) * SY + Math.floorMod(sy, SY)) * SX + Math.floorMod(sz, SX);
                    long key = SectionPos.asLong(sx, sy, sz);
                    boolean due = Math.floorMod(i++ - refreshAt, all) < REFRESH;
                    if (STORED[slot] == key && !due) continue;
                    fill(level, sx, sy, sz);
                    GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, Math.floorMod(sx, SX) * 16, Math.floorMod(sy, SY) * 16, Math.floorMod(sz, SX) * 16,
                            16, 16, 16, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, section);
                    STORED[slot] = key;
                }
            }
        }
        refreshAt = (refreshAt + REFRESH) % all;
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(unit);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        return ready = true;
    }

    /** One section's bits into {@link #section} (x fastest, then y, then z — texture order); no chunk — nothing see-through. */
    private static void fill(ClientLevel level, int sx, int sy, int sz) {
        MemoryUtil.memSet(section, 0);
        int index = sy - level.getMinSection();
        LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null || index < 0 || index >= chunk.getSections().length) return;
        LevelChunkSection s = chunk.getSections()[index];
        if (s.hasOnlyAir() || !s.getStates().maybeHas(state -> kind(state) != 0)) return;
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) section.put((z * 16 + y) * 16 + x, kind(s.getBlockState(x, y, z)));
            }
        }
    }

    /** {@link #SEE} if the state or its fluid is drawn in the translucent chunk layer, plus {@link #LIQUID} for such a fluid. */
    static byte kind(BlockState state) {
        Byte known = KIND.get(state);
        if (known != null) return known;
        byte bits = 0;
        if (Minecraft.getInstance().getBlockRenderer().getBlockModel(state).getRenderTypes(state, RANDOM, ModelData.EMPTY)
                .contains(RenderType.translucent())) bits |= SEE;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && ItemBlockRenderTypes.getRenderLayer(fluid) == RenderType.translucent()) bits |= SEE | LIQUID;
        KIND.put(state, bits);
        return bits;
    }

    /** Models reloaded (resource packs): render types may differ. */
    static void forget() {
        KIND.clear();
        Arrays.fill(STORED, Long.MIN_VALUE);
    }

    private static boolean ensure() {
        if (texture >= 0) return true;
        section = MemoryUtil.memAlloc(4096);
        Arrays.fill(STORED, Long.MIN_VALUE);
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL11.GL_REPEAT);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, org.lwjgl.opengl.GL30.GL_R8, SIZE_XZ, SIZE_Y, SIZE_XZ, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(unit);
        return true;
    }

    /** This frame's window: texture (−1 none) and its lower world corner. */
    static int texture() {
        return ready ? texture : -1;
    }

    static int x0() {
        return x0;
    }

    static int y0() {
        return y0;
    }

    static int z0() {
        return z0;
    }

    /** No window this frame (secondary view, no see-through). */
    static void off() {
        ready = false;
    }
}
