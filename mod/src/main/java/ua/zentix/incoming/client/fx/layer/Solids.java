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
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Что заслоняет свет у камеры: полные непрозрачные блоки ({@link BlockState#isSolidRender}) в окне {@value #SIZE_XZ}×{@value #SIZE_Y}×{@value #SIZE_XZ} блоков вокруг глаза —
 * трёхмерной текстурой R8 (255 — заслон). По ней луч тени прохода света огня ({@code fx_heat}: {@code heatBlocked})
 * идёт по клеткам: мир Minecraft — блоки, и заслон на пути света известен точно, в кадре он или нет. По одной глубине
 * кадра это не решить: за видимой гранью может быть и толща дома, и пустота за столбом (ревью #399: луч сквозь цельный
 * дом под углом к взгляду проходил в 84 % случаев). Листва, стекло, вода, трава — не заслон (⚠ листва пропускает часть);
 * неполные блоки (плиты, ступени, слой снега, тропинка, пашня, ковёр) — тоже: заслон в целую клетку дал бы земле под
 * снегом тень от соседней клетки снега (ревью #399: у света под 10–30° — вся), а высоты в клетке окно не хранит (⚠ плита
 * и ступень свет пропускают).
 * <p>
 * Окно — по секциям 16³, по модулю размера (как {@link OpenAir}): новые секции окна — сперва нулями (не знаем — не
 * заслон), потом блоками от глаза к краю, не дольше {@link #FILL_NANOS} за кадр (производительность, не физика); каждый
 * кадр перечитываются {@value #REFRESH} секций по кругу (всё окно — за ~1,8 с при 60 кадрах) и секции у источников
 * света кадра в пределах их радиуса ({@link #near}: взрыв ломает блоки у самого огня) — им не больше половины бюджета,
 * по очереди, остальное — окну, хоть одна секция за кадр (иначе секции у огней одни съедали бы весь бюджет). Секция
 * без полных блоков или из одних них (воздух, толща камня — палитра это знает, {@code maybeHas}) — без обхода блоков.
 * Секция, чьи блоки с прошлого чтения те же (отпечаток палитры и хранилища номеров — сотни {@code long}), не читается и
 * не грузится: загрузка в текстуру, которую ещё читает прошлый кадр, ждёт видеокарту (шлагбаум LFX4: до ~40 загрузок за
 * кадр, время слоя у ночного взрыва 1,6 → 11,7 мс при том же времени процессора). Обновляется, только пока у прохода есть свет; сбрасывается при смене мира. Чанка у клиента
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
    private static ByteBuffer section, zeros, full, cubeSection, cubeZeros, cubeFull;
    /** Секция мира с блоками в каждом месте окна и секция, под которую место обнулено ({@link SectionPos#asLong}). */
    private static final long[] STORED = new long[ALL], ZEROED = new long[ALL];
    /** Отпечаток блоков секции при её чтении ({@link #print}). */
    private static final long[] PRINT = new long[ALL];
    /** «Место обнулено при создании текстуры» в {@link #ZEROED}: обнулять его снова не нужно. */
    private static final long FRESH = Long.MIN_VALUE + 1;
    private static final int[] PENDING = new int[ALL];
    private static final long[] PENDING_KEY = new long[ALL];
    /** Порядок чтения: расстояние² от глаза (блоки², целое) в старших битах, номер в младших 11. */
    private static final long[] ORDER = new long[ALL];
    private static final int[] NEAR_PENDING = new int[ALL];
    private static int refreshAt, nearTurn;
    /** Полный непрозрачный блок — заслон (форма блока — без места: у полных блоков она от места не зависит). */
    private static final java.util.function.Predicate<BlockState> SOLID = st -> !st.isAir()
            && st.isSolidRender(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    private static int x0, y0, z0;
    private static boolean ready;
    private static Vec3 eye = Vec3.ZERO;
    private static WeakReference<ClientLevel> world = new WeakReference<>(null);
    /** Секции у источников света этого кадра (перечитываются каждый кадр). */
    private static final long[] NEAR = new long[32];
    private static int nears;

    private Solids() {}

    /**
     * Источник света кадра в (x, y, z) мира радиусом r: секции, которые он задевает, — вне очереди окна (не больше
     * половины бюджета, по очереди между собой), ближние к нему первыми (место на {@link #NEAR} кончается).
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

    /** Окно вокруг глаза к этому кадру; false — окна нет (теней нет). */
    static boolean update(ClientLevel level, Vec3 eye) {
        try {
            return ready = fill(level, eye);
        } finally {
            nears = 0;
        }
    }

    private static boolean fill(ClientLevel level, Vec3 eye) {
        // распаковка — плотная, до любой загрузки (как у OpenAir): ваниль оставляет длину строки и пропуски от своих
        // картинок, и драйвер читал бы за 4 КБ секции
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        ensure();
        if (level != world.get()) {
            // секции прежнего мира в окне — не наши: их места заново нулями (места, ещё нулевые с создания, — как есть)
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
                        // чужая секция в этом месте — сразу нулями: блоки той секции здесь — не наши заслоны
                        if (ZEROED[slot] != FRESH) upload(sx, sy, sz, zeros);
                        ZEROED[slot] = key;
                        // в месте теперь нули, не блоки прежней секции: та, вернувшись, читается заново
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
        // секции у огней — не больше половины бюджета, по очереди с кадра на кадр
        for (int k = 0; k < nearPending; k++) {
            if (k > 0 && System.nanoTime() - start > FILL_NANOS / 2) break;
            read(level, NEAR_PENDING[Math.floorMod(k + nearTurn, nearPending)]);
        }
        nearTurn = Math.floorMod(nearTurn + 1, ALL);
        // остальное окно — от глаза к краю, хоть одна секция
        int far = pending - nearPending;
        Arrays.sort(ORDER, 0, far);
        for (int k = 0; k < far; k++) {
            if (k > 0 && System.nanoTime() - start > FILL_NANOS) break;
            read(level, (int) (ORDER[k] & 0x7FF));
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

    /**
     * Заслоны секции в текстуру: блоки — на её место окна, а кубы 4³ («есть ли в кубе заслон») — в слой грубой сетки
     * за окном по z (с {@link #SIZE_XZ}): по нему луч тени ({@code heatBlocked}) идёт открытым воздухом шагом в куб.
     */
    private static void upload(int sx, int sy, int sz, ByteBuffer data) {
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, Math.floorMod(sx, SX) * 16, Math.floorMod(sy, SY) * 16, Math.floorMod(sz, SX) * 16,
                16, 16, 16, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data);
        ByteBuffer c = data == zeros ? cubeZeros : data == full ? cubeFull : cubes(data);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, Math.floorMod(sx, SX) * 4, Math.floorMod(sy, SY) * 4, SIZE_XZ + Math.floorMod(sz, SX) * 4,
                4, 4, 4, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, c);
    }

    /** Кубы 4³ секции data: 255 — в кубе есть заслон (x — быстрее всех, потом y, потом z). */
    private static ByteBuffer cubes(ByteBuffer data) {
        for (int k = 0; k < 64; k++) cubeSection.put(k, (byte) 0);
        for (int z = 0; z < 16; z++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if (data.get((z * 16 + y) * 16 + x) != 0) cubeSection.put(((z >> 2) * 4 + (y >> 2)) * 4 + (x >> 2), (byte) 255);
                }
            }
        }
        return cubeSection;
    }

    /** Секция pending-номера o: заслоны — в текстуру, место помнит её. */
    private static void read(ClientLevel level, int o) {
        long key = PENDING_KEY[o];
        int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
        PRINT[PENDING[o]] = print(level, sx, sy, sz);
        upload(sx, sy, sz, blocks(level, sx, sy, sz));
        STORED[PENDING[o]] = key;
    }

    /**
     * Отпечаток блоков секции: состояния палитры по номерам и хранилище номеров (как у снимков руин ядерки), без обхода
     * мест; секции нет (чанка нет, вне мира) или в ней один воздух — 0.
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
     * Отпечаток: ширина номера, состояния палитры, слова хранилища — перемешиванием fmix64 (murmurHash3) после каждого:
     * у умножения разница идёт только вверх, у {@code HashCommon.mix} старший бит выходит постоянным узором, и правки в
     * соседних словах гасили бы друг друга (ревью #399); у fmix64 — лавина во все биты.
     */
    static long fingerprint(int bits, int[] states, long[] raw) {
        long h = it.unimi.dsi.fastutil.HashCommon.murmurHash3(0x9E3779B97F4A7C15L ^ bits);
        for (int st : states) h = it.unimi.dsi.fastutil.HashCommon.murmurHash3(h ^ st);
        for (long v : raw) h = it.unimi.dsi.fastutil.HashCommon.murmurHash3(h ^ v);
        return h;
    }

    /** Заслоны секции (x — быстрее всех, потом y, потом z — как у текстуры): {@link #zeros}, {@link #full} или {@link #section}. */
    private static ByteBuffer blocks(ClientLevel level, int sx, int sy, int sz) {
        if (sy < level.getMinSection() || sy >= level.getMaxSection()) return zeros;
        LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) return zeros;
        LevelChunkSection s = chunk.getSection(level.getSectionIndexFromSectionY(sy));
        // палитра: заслонов нет или все — заслоны (maybeHas — «может быть»: нет — значит точно нет)
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
        full = MemoryUtil.memAlloc(4096);
        for (int k = 0; k < 4096; k++) full.put(k, (byte) 255);
        cubeSection = MemoryUtil.memAlloc(64);
        cubeZeros = MemoryUtil.memCalloc(64);
        cubeFull = MemoryUtil.memAlloc(64);
        for (int k = 0; k < 64; k++) cubeFull.put(k, (byte) 255);
        Arrays.fill(STORED, Long.MIN_VALUE);
        Arrays.fill(ZEROED, FRESH);
        texture = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, texture);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        // всё окно — нулями одним вызовом (содержимое без данных не определено), места — «обнулены при создании»
        // по z за окном — слой грубой сетки кубов 4³ (SIZE_XZ / 4)
        ByteBuffer blank = MemoryUtil.memCalloc(SIZE_XZ * SIZE_Y * (SIZE_XZ + SIZE_XZ / 4));
        GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, SIZE_XZ, SIZE_Y, SIZE_XZ + SIZE_XZ / 4, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, blank);
        MemoryUtil.memFree(blank);
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, 0);
    }
}
