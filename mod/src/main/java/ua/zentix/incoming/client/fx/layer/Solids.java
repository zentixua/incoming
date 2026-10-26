package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Light occluders near the camera: full opaque blocks ({@link BlockState#isSolidRender}) in a {@value #SIZE_XZ}×{@value #SIZE_Y}×{@value #SIZE_XZ}-block window around the eye —
 * an R8 3D texture (255 — occluder). The fire-light pass shadow ray ({@code fx_heat}: {@code heatBlocked})
 * traverses its cells: Minecraft is blocks, so light-path occluders are known exactly, on-screen or not. Frame depth alone
 * cannot decide: behind a visible face there may be a solid house or empty space behind a post (review #399: a ray crossed a solid
 * house oblique to the view in 84 % of cases). Leaves, glass, water, grass do not occlude (⚠ leaves transmit partly);
 * neither do partial blocks (slabs, stairs, snow layers, paths, farmland, carpets): a full-cell occluder would shadow ground beneath
 * snow with a neighbouring snow cell (review #399: completely for light at 10–30°); the window stores no subcell height (⚠ slabs
 * and stairs transmit light).
 * <p>
 * Window uses 16³ sections, wrapped modulo size (like {@link OpenAir}): new sections first zeroed (unknown — no
 * occluder), then filled with blocks from eye to edge within {@link #FILL_NANOS} per frame (performance, not physics); each
 * frame refreshes {@value #REFRESH} sections in rotation (whole window in ~1.8 s at 60 fps) and sections within frame
 * light-source radii ({@link #near}: blasts break blocks next to the fire) — at most half the budget,
 * in rotation; the rest goes to the window, at least one section per frame (otherwise fire sections alone would consume the whole budget). A section
 * with no full blocks or only full blocks (air, solid stone — palette knows, {@code maybeHas}) needs no block scan.
 * A section unchanged since last read (palette and index-storage fingerprint — hundreds of {@code long}s) is neither read nor
 * uploaded: uploading to a texture still read by the previous frame waits for the GPU (look gate LFX4: up to ~40 uploads per
 * frame, nighttime blast layer time 1.6 → 11.7 ms at unchanged CPU time). Updates only while the pass has light; resets on world change. No client
 * chunk — no occluder.
 */
public final class Solids {
    /** Window, blocks (multiples of 16). */
    static final int SIZE_XZ = 192, SIZE_Y = 96;
    private static final int SX = SIZE_XZ / 16, SY = SIZE_Y / 16, ALL = SX * SY * SX;
    /** Sections refreshed per frame in rotation (whole window in {@value #ALL}/{@value #REFRESH} frames). */
    static final int REFRESH = 8;
    /** Maximum block-reading time per frame, ns. */
    static final long FILL_NANOS = 1_000_000;
    /** Custom texture unit (12 — pall noise, 13 — {@link OpenAir}, 14 — {@code SmokePass}). */
    static final int UNIT = 15;

    private static int texture = -1;
    private static ByteBuffer section, zeros, full;
    /** World section whose blocks occupy each slot, and section for which it was zeroed ({@link SectionPos#asLong}). */
    private static final long[] STORED = new long[ALL], ZEROED = new long[ALL];
    /** Section block fingerprint when read ({@link #print}). */
    private static final long[] PRINT = new long[ALL];
    /** “Slot zeroed at texture creation” in {@link #ZEROED}: no need to zero it again. */
    private static final long FRESH = Long.MIN_VALUE + 1;
    private static final int[] PENDING = new int[ALL];
    private static final long[] PENDING_KEY = new long[ALL];
    /** Read order: squared eye distance (blocks², integer) in high bits, index in low 11 bits. */
    private static final long[] ORDER = new long[ALL];
    private static final int[] NEAR_PENDING = new int[ALL];
    private static int refreshAt, nearTurn;
    /** Full opaque block is an occluder (shape without position: full-block shape is position-independent). */
    private static final java.util.function.Predicate<BlockState> SOLID = st -> !st.isAir()
            && st.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    private static int x0, y0, z0;
    private static boolean ready;
    private static Vec3 eye = Vec3.ZERO;
    private static WeakReference<ClientLevel> world = new WeakReference<>(null);
    /** Sections near this frame's light sources (refreshed every frame). */
    private static final long[] NEAR = new long[32];
    private static int nears;

    private Solids() {}

    /**
     * Frame light source at world (x, y, z), radius r: touched sections bypass the window queue (at most
     * half the budget, in their own rotation), nearest first (slots end at {@link #NEAR}).
     */
    static void near(double x, double y, double z, double r) {
        int x0 = SectionPos.blockToSectionCoord(x - r), x1 = SectionPos.blockToSectionCoord(x + r);
        int y0 = SectionPos.blockToSectionCoord(y - r), y1 = SectionPos.blockToSectionCoord(y + r);
        int z0 = SectionPos.blockToSectionCoord(z - r), z1 = SectionPos.blockToSectionCoord(z + r);
        int n = 0;
        long[] keys = new long[(x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)];
        for (int sx = x0; sx <= x1; sx++) {
            for (int sy = y0; sy <= y1; sy++) {
                for (int sz = z0; sz <= z1; sz++) {
                    double dx = sx * 16 + 8 - x, dy = sy * 16 + 8 - y, dz = sz * 16 + 8 - z;
                    keys[n] = (long) (dx * dx + dy * dy + dz * dz) << 20 | n;
                    n++;
                }
            }
        }
        Arrays.sort(keys, 0, n);
        int ny = y1 - y0 + 1, nz = z1 - z0 + 1;
        for (int k = 0; k < n; k++) {
            int i = (int) (keys[k] & 0xFFFFF);
            add(SectionPos.asLong(x0 + i / (ny * nz), y0 + i / nz % ny, z0 + i % nz));
        }
    }

    private static void add(long key) {
        if (nears >= NEAR.length) return;
        for (int i = 0; i < nears; i++) if (NEAR[i] == key) return;
        NEAR[nears++] = key;
    }

    /** Window around the eye for this frame; false — no window (no shadows). */
    static boolean update(ClientLevel level, Vec3 eye) {
        try {
            return ready = fill(level, eye);
        } finally {
            nears = 0;
        }
    }

    private static boolean fill(ClientLevel level, Vec3 eye) {
        // tightly packed unpacking before any upload (like OpenAir): vanilla leaves row length and skips from its
        // images, and the driver would read beyond a 4 KB section
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        ensure();
        if (level != world.get()) {
            // previous-world sections in the window are not ours: zero their slots again (slots still zero since creation stay as is)
            for (int k = 0; k < ALL; k++) {
                if (STORED[k] != Long.MIN_VALUE || ZEROED[k] != FRESH) ZEROED[k] = Long.MIN_VALUE;
                STORED[k] = Long.MIN_VALUE;
            }
            world = new WeakReference<>(level);
        }
        Solids.eye = eye;
        int ex = (int) Math.floor(eye.x), ey = (int) Math.floor(eye.y), ez = (int) Math.floor(eye.z);
        x0 = OpenAir.start(ex, SIZE_XZ);
        y0 = OpenAir.start(ey, SIZE_Y);
        z0 = OpenAir.start(ez, SIZE_XZ);
        int sx0 = x0 >> 4, sy0 = y0 >> 4, sz0 = z0 >> 4;
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        int pending = 0, nearPending = 0, i = 0;
        for (int sx = sx0; sx < sx0 + SX; sx++) {
            for (int sy = sy0; sy < sy0 + SY; sy++) {
                for (int sz = sz0; sz < sz0 + SX; sz++) {
                    int slot = slot(sx, sy, sz);
                    long key = SectionPos.asLong(sx, sy, sz);
                    boolean close = isNear(key);
                    boolean due = Math.floorMod(i++ - refreshAt, ALL) < REFRESH || close;
                    if (STORED[slot] == key && (!due || PRINT[slot] == print(level, sx, sy, sz))) continue;
                    if (STORED[slot] != key && ZEROED[slot] != key) {
                        // another section in this slot — zero immediately: its blocks are not our occluders here
                        if (ZEROED[slot] != FRESH) upload(sx, sy, sz, zeros);
                        ZEROED[slot] = key;
                        // slot now contains zeroes, not previous section blocks: reread that section if it returns
                        STORED[slot] = Long.MIN_VALUE;
                    }
                    PENDING[pending] = slot;
                    PENDING_KEY[pending] = key;
                    if (close) {
                        NEAR_PENDING[nearPending++] = pending;
                    } else {
                        double dx = sx * 16 + 8 - eye.x, dy = sy * 16 + 8 - eye.y, dz = sz * 16 + 8 - eye.z;
                        ORDER[pending - nearPending] = (long) (dx * dx + dy * dy + dz * dz) << 11 | pending;
                    }
                    pending++;
                }
            }
        }
        refreshAt = (refreshAt + REFRESH) % ALL;
        long start = System.nanoTime();
        // fire sections get at most half the budget, rotating between frames
        for (int k = 0; k < nearPending; k++) {
            if (k > 0 && System.nanoTime() - start > FILL_NANOS / 2) break;
            read(level, NEAR_PENDING[Math.floorMod(k + nearTurn, nearPending)]);
        }
        nearTurn = Math.floorMod(nearTurn + 1, ALL);
        // remaining window from eye to edge, at least one section
        int far = pending - nearPending;
        Arrays.sort(ORDER, 0, far);
        for (int k = 0; k < far; k++) {
            if (k > 0 && System.nanoTime() - start > FILL_NANOS) break;
            read(level, (int) (ORDER[k] & 0x7FF));
        }
        flush();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        return true;
    }

    private static boolean isNear(long key) {
        for (int i = 0; i < nears; i++) if (NEAR[i] == key) return true;
        return false;
    }

    private static int slot(int sx, int sy, int sz) {
        return (Math.floorMod(sx, SX) * SY + Math.floorMod(sy, SY)) * SX + Math.floorMod(sz, SX);
    }

    /** Sections per upload batch (more batches in the same frame if needed). */
    static final int BATCH = 128;
    private static int pbo = -1, batched;
    private static ByteBuffer batch;
    private static final int[] BATCH_AT = new int[BATCH * 3];

    /**
     * Add section to a frame upload batch ({@link #flush}): direct memory upload to a texture still read by the previous
     * frame waited for the GPU (look gate LFX9: +6.7 ms layer time in the first second after a nighttime blast, same
     * CPU time), whereas a pixel-unpack-buffer copy queues on the GPU.
     */
    private static void upload(int sx, int sy, int sz, ByteBuffer data) {
        if (batched == BATCH) flush();
        batch.put(batched * 4096, data, 0, 4096);
        BATCH_AT[3 * batched] = Math.floorMod(sx, SX) * 16;
        BATCH_AT[3 * batched + 1] = Math.floorMod(sy, SY) * 16;
        BATCH_AT[3 * batched + 2] = Math.floorMod(sz, SX) * 16;
        batched++;
    }

    /**
     * Upload batch to the bound texture: create a fresh unpack buffer per batch (driver releases the old buffer still read by the
     * GPU itself — no wait); sections use offsets within it; unbind buffer afterwards (otherwise other
     * texture uploads would read from it).
     */
    private static void flush() {
        if (batched == 0) return;
        if (pbo < 0) pbo = GL15.glGenBuffers();
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
        batch.limit(batched * 4096).position(0);
        GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, batch, GL15.GL_STREAM_DRAW);
        batch.clear();
        for (int k = 0; k < batched; k++) {
            GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, BATCH_AT[3 * k], BATCH_AT[3 * k + 1], BATCH_AT[3 * k + 2], 16, 16, 16, GL11.GL_RED,
                    GL11.GL_UNSIGNED_BYTE, (long) k * 4096);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        batched = 0;
    }

    /** Pending section o: upload occluders, remember the section in its slot. */
    private static void read(ClientLevel level, int o) {
        long key = PENDING_KEY[o];
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        PRINT[PENDING[o]] = print(level, sx, sy, sz);
        upload(sx, sy, sz, blocks(level, sx, sy, sz));
        STORED[PENDING[o]] = key;
    }

    /**
     * Section block fingerprint: indexed palette states and index storage (as in nuclear ruin snapshots), without scanning
     * positions; no section (no chunk, outside world) or only air — 0.
     */
    private static long print(ClientLevel level, int sx, int sy, int sz) {
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) return 0;
        LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) return 0;
        LevelChunkSection s = chunk.getSection(level.getSectionIndexFromSectionY(sy));
        if (s.hasOnlyAir()) return 0;
        var data = s.getStates().data;
        net.minecraft.world.level.chunk.Palette<BlockState> palette = data.palette();
        int[] states = new int[palette instanceof net.minecraft.world.level.chunk.GlobalPalette ? 0 : palette.getSize()];
        for (int k = 0; k < states.length; k++) states[k] = System.identityHashCode(palette.valueFor(k));
        long h = fingerprint(data.storage().getBits(), states, data.storage().getRaw());
        return h == 0 ? 1 : h;
    }

    /**
     * Fingerprint: index width, palette states, storage words — fmix64 (murmurHash3) mixing after each:
     * multiplication propagates differences only upward; {@code HashCommon.mix} gives the top bit a fixed pattern, so changes in
     * neighbouring words would cancel (review #399); fmix64 avalanches into every bit.
     */
    static long fingerprint(int bits, int[] states, long[] raw) {
        long h = it.unimi.dsi.fastutil.HashCommon.murmurHash3(0x9E3779B97F4A7C15L ^ bits);
        for (int st : states) h = it.unimi.dsi.fastutil.HashCommon.murmurHash3(h ^ st);
        for (long v : raw) h = it.unimi.dsi.fastutil.HashCommon.murmurHash3(h ^ v);
        return h;
    }

    /** Section occluders (x fastest, then y, then z — texture order): {@link #zeros}, {@link #full} or {@link #section}. */
    private static ByteBuffer blocks(ClientLevel level, int sx, int sy, int sz) {
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) return zeros;
        LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) return zeros;
        LevelChunkSection s = chunk.getSection(level.getSectionIndexFromSectionY(sy));
        // palette: no occluders or all occluders (maybeHas means “possibly”: false means definitely none)
        if (s.hasOnlyAir() || !s.maybeHas(SOLID)) return zeros;
        if (!s.maybeHas(SOLID.negate())) return full;
        section.clear();
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) section.put((z * 16 + y) * 16 + x, SOLID.test(s.getBlockState(x, y, z)) ? (byte) 255 : 0);
            }
        }
        return section;
    }

    /**
     * This frame's window to shader {@code s}: texture on unit {@link #UNIT} ({@code HeatSolids}), {@code HeatSolidsAt} —
     * eye-relative lower corner (xyz), window present (w), {@code HeatSolidsWrap} — that corner's texture position. Like
     * {@link OpenAir#bind}: unit index directly into the program, on every bind.
     */
    static void bind(ShaderInstance s) {
        boolean on = ready && texture >= 0;
        s.safeGetUniform("HeatSolidsAt").set((float) (x0 - eye.x), (float) (y0 - eye.y), (float) (z0 - eye.z), on ? 1f : 0f);
        s.safeGetUniform("HeatSolidsWrap").set((float) Math.floorMod(x0, SIZE_XZ), (float) Math.floorMod(y0, SIZE_Y), (float) Math.floorMod(z0, SIZE_XZ));
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, on ? texture : 0);
        GlStateManager._activeTexture(unit);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        ProgramManager.glUseProgram(s.getId());
        Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "HeatSolids"), UNIT);
        ProgramManager.glUseProgram(program);
    }

    /** Window no longer bound here (after the pass): 3D texture unbound from its unit to avoid interfering with other programs. */
    static void unbind() {
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
        GlStateManager._activeTexture(unit);
    }

    private static void ensure() {
        if (texture >= 0) return;
        section = MemoryUtil.memAlloc(4096);
        zeros = MemoryUtil.memCalloc(4096);
        batch = MemoryUtil.memAlloc(BATCH * 4096);
        full = MemoryUtil.memAlloc(4096);
        for (int k = 0; k < 4096; k++) full.put(k, (byte) 255);
        Arrays.fill(STORED, Long.MIN_VALUE);
        Arrays.fill(ZEROED, FRESH);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // zero entire window in one call (uninitialized contents are undefined); slots marked “zeroed at creation”
        ByteBuffer blank = MemoryUtil.memCalloc(SIZE_XZ * SIZE_Y * SIZE_XZ);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SIZE_XZ, SIZE_Y, SIZE_XZ, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, blank);
        MemoryUtil.memFree(blank);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }
}
