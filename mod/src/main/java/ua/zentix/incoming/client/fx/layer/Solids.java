package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
 * Что заслоняет свет у камеры: блоки, которые закрывают свет ({@link BlockState#canOcclude}, как у теней ядерной
 * вспышки — {@code FlashChar}), в окне {@value #SIZE_XZ}×{@value #SIZE_Y}×{@value #SIZE_XZ} блоков вокруг глаза —
 * трёхмерной текстурой R8 (255 — заслон). По ней луч тени прохода света огня ({@code fx_heat}: {@code heatBlocked})
 * идёт по клеткам: мир Minecraft — блоки, и заслон на пути света известен точно, в кадре он или нет. По одной глубине
 * кадра это не решить: за видимой гранью может быть и толща дома, и пустота за столбом (ревью #399: луч сквозь цельный
 * дом под углом к взгляду проходил в 84 % случаев). Листва, стекло, вода, трава — не заслон (⚠ листва пропускает часть).
 * <p>
 * Окно — по секциям 16³, по модулю размера (как {@link OpenAir}): новые секции окна — сперва нулями (не знаем — не
 * заслон), потом блоками от глаза к краю, не дольше {@link #FILL_NANOS} за кадр (производительность, не физика); каждый
 * кадр перечитываются {@value #REFRESH} секций по кругу и секции у источников света кадра ({@link #near}: взрыв ломает
 * блоки у самого огня). Обновляется, только пока у прохода есть свет; сбрасывается при смене мира. Чанка у клиента
 * нет — не заслон.
 */
public final class Solids {
    /** Окно, блоки (кратно 16). */
    static final int SIZE_XZ = 192, SIZE_Y = 96;
    private static final int SX = SIZE_XZ / 16, SY = SIZE_Y / 16, ALL = SX * SY * SX;
    /** Секций, перечитываемых за кадр по кругу (всё окно — за {@value #ALL}/{@value #REFRESH} кадров). */
    static final int REFRESH = 8;
    /** Чтение блоков за кадр — не дольше, нс. */
    static final long FILL_NANOS = 1_000_000;
    /** Свой блок текстуры (12 — шум пелены, 13 — {@link OpenAir}, 14 — {@code SmokePass}). */
    static final int UNIT = 15;

    private static int texture = -1;
    private static ByteBuffer section, zeros;
    /** Секция мира с блоками в каждом месте окна и секция, под которую место обнулено ({@link SectionPos#asLong}). */
    private static final long[] STORED = new long[ALL], ZEROED = new long[ALL];
    private static final int[] PENDING = new int[ALL];
    private static final long[] PENDING_KEY = new long[ALL];
    private static final double[] PENDING_D = new double[ALL];
    private static final Integer[] ORDER = new Integer[ALL];
    private static int refreshAt;
    private static int x0, y0, z0;
    private static boolean ready;
    private static Vec3 eye = Vec3.ZERO;
    private static WeakReference<ClientLevel> world = new WeakReference<>(null);
    /** Секции у источников света этого кадра (перечитываются каждый кадр). */
    private static final long[] NEAR = new long[16];
    private static int nears;

    private Solids() {}

    /** Источник света кадра в (x, y, z) мира: его секция перечитывается в этом кадре. */
    static void near(double x, double y, double z) {
        if (nears >= NEAR.length) return;
        long key = SectionPos.asLong(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(y), SectionPos.blockToSectionCoord(z));
        for (int i = 0; i < nears; i++) if (NEAR[i] == key) return;
        NEAR[nears++] = key;
    }

    /** Окно вокруг глаза к этому кадру; false — окна нет (теней нет). */
    static boolean update(ClientLevel level, Vec3 eye) {
        try {
            return ready = fill(level, eye);
        } finally {
            nears = 0;
        }
    }

    private static boolean fill(ClientLevel level, Vec3 eye) {
        ensure();
        if (level != world.get()) {
            Arrays.fill(STORED, Long.MIN_VALUE);
            Arrays.fill(ZEROED, Long.MIN_VALUE);
            world = new WeakReference<>(level);
        }
        Solids.eye = eye;
        int ex = (int) Math.floor(eye.x), ey = (int) Math.floor(eye.y), ez = (int) Math.floor(eye.z);
        x0 = OpenAir.start(ex, SIZE_XZ);
        y0 = OpenAir.start(ey, SIZE_Y);
        z0 = OpenAir.start(ez, SIZE_XZ);
        int sx0 = x0 >> 4, sy0 = y0 >> 4, sz0 = z0 >> 4;
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        int pending = 0, i = 0;
        for (int sx = sx0; sx < sx0 + SX; sx++) {
            for (int sy = sy0; sy < sy0 + SY; sy++) {
                for (int sz = sz0; sz < sz0 + SX; sz++) {
                    int slot = slot(sx, sy, sz);
                    long key = SectionPos.asLong(sx, sy, sz);
                    boolean due = Math.floorMod(i++ - refreshAt, ALL) < REFRESH || isNear(key);
                    if (STORED[slot] == key && !due) continue;
                    if (STORED[slot] != key && ZEROED[slot] != key) {
                        // чужая секция в этом месте — сразу нулями: блоки той секции здесь — не наши заслоны
                        upload(sx, sy, sz, zeros);
                        ZEROED[slot] = key;
                    }
                    double dx = sx * 16 + 8 - eye.x, dy = sy * 16 + 8 - eye.y, dz = sz * 16 + 8 - eye.z;
                    PENDING[pending] = slot;
                    PENDING_KEY[pending] = key;
                    PENDING_D[pending] = isNear(key) ? -1 : dx * dx + dy * dy + dz * dz;
                    pending++;
                }
            }
        }
        refreshAt = (refreshAt + REFRESH) % ALL;
        for (int k = 0; k < pending; k++) ORDER[k] = k;
        Arrays.sort(ORDER, 0, pending, (a, b) -> Double.compare(PENDING_D[a], PENDING_D[b]));
        long until = System.nanoTime() + FILL_NANOS;
        for (int k = 0; k < pending; k++) {
            if (k > 0 && System.nanoTime() > until) break;
            int o = ORDER[k];
            long key = PENDING_KEY[o];
            int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
            read(level, sx, sy, sz);
            upload(sx, sy, sz, section);
            STORED[PENDING[o]] = key;
        }
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

    private static void upload(int sx, int sy, int sz, ByteBuffer data) {
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, Math.floorMod(sx, SX) * 16, Math.floorMod(sy, SY) * 16, Math.floorMod(sz, SX) * 16,
                16, 16, 16, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data);
    }

    /** Заслоны секции в {@link #section} (x — быстрее всех, потом y, потом z — как у текстуры). */
    private static void read(ClientLevel level, int sx, int sy, int sz) {
        section.clear();
        for (int k = 0; k < 4096; k++) section.put(k, (byte) 0);
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) return;
        LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) return;
        LevelChunkSection s = chunk.getSection(level.getSectionIndexFromSectionY(sy));
        if (s.hasOnlyAir()) return;
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (s.getBlockState(x, y, z).canOcclude()) section.put((z * 16 + y) * 16 + x, (byte) 255);
                }
            }
        }
    }

    /**
     * Окно этого кадра — шейдеру {@code s}: текстура на блоке {@link #UNIT} ({@code HeatSolids}), {@code HeatSolidsAt} —
     * нижний угол окна от глаза кадра (xyz) и есть ли окно (w), {@code HeatSolidsWrap} — где этот угол в текстуре. Как
     * {@link OpenAir#bind}: номер блока — в саму программу, при каждой привязке.
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

    /** Окно больше не на этом месте (после прохода): трёхмерная текстура снята с блока, чтобы не мешать чужим программам. */
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
        Arrays.fill(STORED, Long.MIN_VALUE);
        Arrays.fill(ZEROED, Long.MIN_VALUE);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SIZE_XZ, SIZE_Y, SIZE_XZ, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        // всё окно — нулями до первого чтения (у текстуры без данных содержимое не определено)
        for (int sx = 0; sx < SX; sx++) for (int sy = 0; sy < SY; sy++) for (int sz = 0; sz < SX; sz++) upload(sx, sy, sz, zeros);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }
}
