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

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Где у камеры наружный воздух: свет неба Minecraft в окне 96×64×96 блоков вокруг глаза — трёхмерной текстурой (R8,
 * свет неба / 15). Пыль и мгла пелены, голова стены — вещество наружного воздуха: в штольню, пещеру, подвал его заносит
 * только через проёмы, и луч пелены (и тень пелены на мире, и пелена перед квадратами слоя) берёт ослабление поля ×
 * эту долю ({@code pall.glsl}: {@code pallOpen}). Без неё поле пелены — функция высоты над рельефом по грубой карте, и
 * в шахте под пеленой луч в несколько блоков до стены шёл сквозь густую пыль — мгла под землёй.
 * <p>
 * Свет неба — мера связи с открытым небом, которую считает сам Minecraft: под открытым небом 15 (под листвой 14), в
 * шахте к небу — 15 до дна, дальше в штольню от её дна — на 1 меньше с каждым блоком, в глухой выработке — 0. Сколько
 * пыли заносит в проём, он не знает: доля по свету неба — оценка (концентрация внутри по обмену воздуха с улицей
 * не считается), а не закон. Вне окна — наружный воздух (1): у пелены вдали всё как прежде.
 * <p>
 * Окно — по секциям 16³, в текстуре по модулю размера (секция мира (x, y, z) всегда в одном месте текстуры): глаз
 * перешёл в соседнюю секцию — заполняются только вошедшие в окно секции, и каждый кадр перечитывается
 * {@link #REFRESH} секций по кругу (свет меняется — ударная волна ломает стены и кровлю): всё окно — за 36 кадров.
 * Чанка у клиента нет — наружный воздух.
 */
public final class OpenAir {
    /** Окно, блоки: по горизонтали и по вертикали (кратно 16). */
    static final int SIZE_XZ = 96, SIZE_Y = 64;
    private static final int SX = SIZE_XZ / 16, SY = SIZE_Y / 16;
    /** Секций, перечитываемых за кадр. */
    static final int REFRESH = 4;
    /** Свой блок текстуры (как у шума пелены, {@code PallPass.NOISE_UNIT}: трёхмерные ShaderInstance не привязывает). */
    static final int UNIT = 13;

    private static int texture = -1;
    private static ByteBuffer section;
    /** Какая секция мира лежит в каждом месте окна ({@link SectionPos#asLong}); {@link Long#MIN_VALUE} — никакой. */
    private static final long[] STORED = new long[SX * SY * SX];
    private static int refreshAt;
    /** Нижний угол окна в мире (блоки) и есть ли окно в этом кадре. */
    private static int x0, y0, z0;
    private static boolean ready;
    /** Глаз кадра, от которого шейдеры считают места. */
    private static Vec3 eye = Vec3.ZERO;

    private OpenAir() {}

    /**
     * Окно вокруг глаза {@code eye} — к этому кадру (новые секции и {@link #REFRESH} по кругу). Зовёт проход пелены до
     * своих лучей; false — окна нет (шейдер считает весь воздух наружным).
     */
    static boolean update(ClientLevel level, Vec3 eye) {
        if (!ensure()) return ready = false;
        OpenAir.eye = eye;
        int ex = (int) Math.floor(eye.x), ey = (int) Math.floor(eye.y), ez = (int) Math.floor(eye.z);
        x0 = start(ex, SIZE_XZ);
        y0 = start(ey, SIZE_Y);
        z0 = start(ez, SIZE_XZ);
        int sx0 = x0 >> 4, sy0 = y0 >> 4, sz0 = z0 >> 4;
        LayerLightEventListener sky = level.getLightEngine().getLayerListener(LightLayer.SKY);
        // строки секции — по 16 байт: выравнивание распаковки (4) их не трогает
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
     * Нижний край окна размера {@code size} по оси, где глаз в блоке {@code eye}: по границе секции, глаз — не ближе
     * size/2 − 8 блоков к краю (40 по горизонтали, 24 по вертикали).
     */
    static int start(int eye, int size) {
        return Math.floorDiv(eye - size / 2 + 8, 16) * 16;
    }

    /** Свет неба секции в {@link #section} (x — быстрее всех, потом y, потом z — как у текстуры). */
    private static void fill(ClientLevel level, LayerLightEventListener sky, int sx, int sy, int sz) {
        section.clear();
        if (level.getChunkSource().getChunkNow(sx, sz) == null || sy < level.getMinSection() || sy >= level.getMaxSection()) {
            // чанка нет — не знаем, что там: наружный воздух; выше и ниже мира — тоже воздух (выше — небо)
            byte open = sy < level.getMinSection() ? 0 : (byte) 255;
            for (int k = 0; k < 4096; k++) section.put(k, open);
            return;
        }
        DataLayer data = sky.getDataLayerData(SectionPos.of(sx, sy, sz));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                // секции без своих данных свет неба берут по столбу сверху (SkyLightSectionStorage): у всех высот столба он один
                int column = data == null ? sky.getLightValue(pos.set(sx * 16 + x, sy * 16, sz * 16 + z)) : -1;
                for (int y = 0; y < 16; y++) {
                    int v = column >= 0 ? column : data.get(x, y, z);
                    section.put((z * 16 + y) * 16 + x, (byte) (v * 17));
                }
            }
        }
    }

    /**
     * Окно этого кадра — шейдеру {@code s} ({@code on} — пелена в кадре нарисована, окно годно): текстура на блоке
     * {@link #UNIT} ({@code PallOpen}), {@code PallOpenAt} — нижний угол окна от глаза кадра (xyz) и есть ли окно (w),
     * {@code PallOpenWrap} — где этот угол в текстуре. Звать до отрисовки шейдером: числа он передаст при {@code apply},
     * номер блока сэмплера ставится в программу сразу. Трёхмерная текстура на своём блоке другим
     * программам не мешает: сэмплеры разных типов нельзя только у одной программы на одном блоке.
     */
    static void bind(ShaderInstance s, boolean on) {
        on &= ready && texture >= 0;
        s.safeGetUniform("PallOpenAt").set((float) (x0 - eye.x), (float) (y0 - eye.y), (float) (z0 - eye.z), on ? 1f : 0f);
        s.safeGetUniform("PallOpenWrap").set((float) Math.floorMod(x0, SIZE_XZ), (float) Math.floorMod(y0, SIZE_Y), (float) Math.floorMod(z0, SIZE_XZ));
        int unit = GlStateManager._getActiveTexture();
        GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNIT);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, on ? texture : 0);
        GlStateManager._activeTexture(unit);
        // номер блока — в саму программу; текущую вернуть (ShaderInstance помнит, какая текущая, и не переключает)
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        ProgramManager.glUseProgram(s.getId());
        Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "PallOpen"), UNIT);
        ProgramManager.glUseProgram(program);
    }

    /** Смена мира: прежнее окно — не этого мира. */
    static void reset() {
        Arrays.fill(STORED, Long.MIN_VALUE);
        ready = false;
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
