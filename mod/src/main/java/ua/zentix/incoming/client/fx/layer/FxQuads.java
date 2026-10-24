package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Квадраты кадра слоя эффектов: копятся в своей памяти (вне кучи Java, между кадрами не освобождается), потом
 * сортируются от дальних к ближним по настоящему расстоянию до камеры и уходят одним вызовом отрисовки. Прозрачное
 * смешивается верно только в таком порядке: ближний клуб поверх дальнего, а не «кто родился позже».
 * <p>
 * Вершина — 36 байт ({@link #FORMAT}): место от камеры, место на листе, свет с умноженной альфой, мягкость края
 * у рельефа или проба источника ({@link #seenBy}) и множитель переноса ближе ({@code UV1}), свет мира ({@code UV2}),
 * туман Minecraft ({@code Normal.x}),
 * чем брать текстуру ({@code Normal.y}: 1 — частица ближайшим пикселем, {@link #nearestTexels}; −1 — плитка атласа
 * дальних моделей, {@link #modelTiles}; ½ — ореол по формуле, {@link #glowShape}; −¼ — запечённый клуб с шестисторонним
 * светом, {@link #sixWay}; ⅝ — кадры огня со смесью, {@link #fireballMix}; 0 — лист слоя гладко) и непрозрачность частицы без текстуры или смесь кадров клуба
 * ({@code Normal.z}, {@link #opacity}), свечение ({@code Emit}, {@link #emission}).
 */
public final class FxQuads {
    /**
     * Свечение вершины ({@link #emission}): байт без нормировки (в шейдере — число 0…255). Свой элемент формата — под
     * свободным номером ({@code findNextId}), как свои элементы заводит Iris.
     */
    public static final VertexFormatElement EMIT = VertexFormatElement.register(VertexFormatElement.findNextId(), 0, VertexFormatElement.Type.UBYTE,
            VertexFormatElement.Usage.GENERIC, 1);
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .add("Normal", VertexFormatElement.NORMAL)
            .add("Emit", EMIT)
            .build();
    private static final int VERTEX = 36, QUAD = 4 * VERTEX;
    /** Мягкость края клуба у земли и стен — доля его полуразмера. */
    public static final float SOFT = 0.5f;

    private long stage;
    private int capacity, n;
    private long write;
    private float[] keys = new float[0];
    /** Квадрат светится (хоть одна вершина с {@link #emission}). */
    private boolean[] emits = new boolean[0];
    /** После {@link #draw}: место самого дальнего светящегося в отсортированном порядке (n — нет светящихся). */
    private int glowFrom;
    /** Рамка вершин светящихся квадратов кадра (от камеры): x, y, z наименьшие, потом наибольшие. */
    private final float[] glowBox = new float[6];
    private final FarFirst sort = new FarFirst();
    private final ByteBufferBuilder out = new ByteBufferBuilder(QUAD * 256);
    private Vector3f left = new Vector3f(1, 0, 0), up = new Vector3f(0, 1, 0);
    private byte nearest, opacity, emit;
    private short probe;
    /** В кадре есть квадраты со свечением ({@link #emission}). */
    private boolean glows;

    /** Новый кадр: оси экрана (влево, вверх) для квадратов лицом к камере. */
    public void begin(Vector3f left, Vector3f up) {
        this.left = left;
        this.up = up;
        n = 0;
        nearest = opacity = emit = 0;
        probe = 0;
        glows = false;
        Arrays.fill(glowBox, 0, 3, Float.MAX_VALUE);
        Arrays.fill(glowBox, 3, 6, -Float.MAX_VALUE);
    }

    /**
     * Следующие квадраты — частицы: вблизи ближайшим пикселем картинки, как из атласа Minecraft (сглаженный клуб
     * мыльный), и без бледного края ({@link #opacity}); мельче вдвое — гладко. Дальнее — всегда гладко (ореол в сотню
     * пикселей из картинки 64×64 иначе лестницей).
     */
    public void nearestTexels(boolean on) {
        nearest = (byte) (on ? 127 : 0);
        opacity = 0;
    }

    /**
     * Следующие квадраты — плитки атласа дальних моделей ({@code FarModels}): текстура — его, свет в нём уже есть
     * (карта освещения не умножается).
     */
    public void modelTiles(boolean on) {
        nearest = (byte) (on ? -127 : 0);
        opacity = 0;
    }

    /**
     * Следующие квадраты — ореол {@code far/glow} ({@link FxAtlas#glow}; блик, вуаль, зарево, ленты шлейфов): крупнее
     * четверти картинки на экране — по той же формуле в шейдере, а не из картинки 64×64. Ореол у глаза бывает в экран
     * поперёк, и билинейно из картинки он выходил бы ступенями квадратами по пикселю картинки (по 25 пикселей экрана:
     * излом выборки на каждом и 8 бит в бледном хвосте). Мельче — из картинки и её уменьшенных копий (гладко).
     */
    /**
     * Следующие квадраты — запечённый клуб с шестисторонним светом ({@link FxAtlas#puff6}, {@code Normal.y} = −¼): свет
     * солнца или луны, неба и огненных шаров — по его картам в шейдере, между кадром и следующим — смесь mix по векторам
     * движения ({@code Normal.z}). Меньше нуля — снова как обычно.
     */
    public void sixWay(float mix) {
        sixWay(mix, 1);
    }

    /**
     * То же с тенью карт в степени shade (0…{@link #SHADE_MAX}, {@code SmokePuffs.shade}): она — в {@code Normal.y} внутри
     * полосы запечённых клубов (−0,4…−0,1), {@link #SHADE_STEPS} ступеней.
     */
    public void sixWay(float mix, float shade) {
        nearest = (byte) (mix < 0 ? 0 : -13 - Math.round(Mth.clamp(shade / SHADE_MAX, 0, 1) * SHADE_STEPS));
        opacity = mix < 0 ? 0 : (byte) Math.round(Mth.clamp(mix, 0, 1) * 127);
    }

    /** Наибольшая степень тени карт клуба (сажа — ~1,3) и ступеней в полосе байта −13…−50 ({@code fx.fsh}). */
    public static final float SHADE_MAX = 1.5f;
    public static final int SHADE_STEPS = 37;

    /** Кадры огня — смесь в шейдере с умноженной альфой, а не два квадрата с провалом яркости между кадрами. */
    public void fireballMix(float mix) {
        nearest = (byte) (mix < 0 ? 0 : 80);
        opacity = mix < 0 ? 0 : (byte) Math.round(Mth.clamp(mix, 0, 1) * 127);
    }

    public void glowShape(boolean on) {
        nearest = (byte) (on ? 64 : 0);
        opacity = 0;
    }

    /**
     * Непрозрачность следующих вершин частицы без текстуры (у искр и вспышек тоже, хоть они только светят): где вместе
     * с текстурой она меньше 0,1, частица вблизи не рисуется — как у ванильного шейдера частиц, иначе бледный ореол
     * каждого клуба в плотном облаке складывался бы в мутную дымку вокруг него. Вдали среза нет.
     */
    public void opacity(float a) {
        opacity = (byte) Math.round(Mth.clamp(a, 0, 1) * 127);
    }

    /**
     * Следующие квадраты — свет в глазу (блик, вуаль) от источника с пробой {@code probe} ({@link SourceProbes#add}):
     * их видно, сколько в кадре видно самого источника, а что за самим квадратом — не важно (мягкость края у рельефа
     * не берётся). 0 — снова как обычно.
     */
    public void seenBy(int probe) {
        this.probe = (short) Mth.clamp(probe, 0, SourceProbes.MAX);
    }

    /**
     * Следующие вершины светятся ({@link FxBloom}): огонь, огненный шар, вспышка, факел. {@code hdr} — во сколько раз их
     * свет ярче показанного (пересвет, который экран срезал: яркость против белого экрана больше 1, у кадра огня — шар
     * модели против кадра); 1 — свет как показан; 0 и меньше — не светятся (дым, ореолы в глазу, модели).
     */
    public void emission(float hdr) {
        emit = (byte) (hdr > 0 ? FxBloom.code(hdr) : 0);
    }

    /** В кадре есть свечение: проход свечения нужен. */
    public boolean glows() {
        return glows;
    }

    /** Рамка вершин светящихся квадратов кадра (от камеры): x, y, z наименьшие, потом наибольшие; без них — пустая. */
    float[] glowBox() {
        return glowBox;
    }

    public int size() {
        return n;
    }

    /** Начать квадрат на расстоянии {@code key} от камеры; дальше — ровно четыре {@link #vertex}. */
    public void quad(float key) {
        if (n == capacity) grow();
        keys[n] = key > 0 ? key : 0;
        emits[n] = false;
        write = stage + (long) n * QUAD;
        n++;
    }

    /**
     * Вершина: место от камеры, место на листе, свет (r, g, b), уже умноженный на непрозрачность, сколько закрыто
     * за ней (a), мягкость края у рельефа (блоки, 0 — без мягкости; у света от источника, {@link #seenBy}, — не
     * берётся), во сколько раз точка перенесена ближе (1 — нет), свет мира (упакованный), туман Minecraft 0..1.
     */
    public void vertex(float x, float y, float z, float u, float v, float r, float g, float b, float a, float soft, float unfold, int light, float fog) {
        long p = write;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4, y);
        MemoryUtil.memPutFloat(p + 8, z);
        MemoryUtil.memPutFloat(p + 12, u);
        MemoryUtil.memPutFloat(p + 16, v);
        MemoryUtil.memPutByte(p + 20, unorm(r));
        MemoryUtil.memPutByte(p + 21, unorm(g));
        MemoryUtil.memPutByte(p + 22, unorm(b));
        MemoryUtil.memPutByte(p + 23, unorm(a));
        // мягкость — не меньше нуля, проба источника — минус её номер
        MemoryUtil.memPutShort(p + 24, probe > 0 ? (short) -probe : (short) Mth.clamp(Math.round(soft * 16), 0, Short.MAX_VALUE));
        MemoryUtil.memPutShort(p + 26, (short) Mth.clamp(Math.round(unfold * Short.MAX_VALUE), 1, Short.MAX_VALUE));
        MemoryUtil.memPutShort(p + 28, (short) (light & 0xFFFF));
        MemoryUtil.memPutShort(p + 30, (short) (light >>> 16 & 0xFFFF));
        MemoryUtil.memPutByte(p + 32, (byte) Math.round(Mth.clamp(fog, 0, 1) * 127));
        MemoryUtil.memPutByte(p + 33, nearest);
        MemoryUtil.memPutByte(p + 34, opacity);
        MemoryUtil.memPutByte(p + 35, emit);
        if (emit != 0) {
            glows = true;
            emits[n - 1] = true;
            glowBox[0] = Math.min(glowBox[0], x);
            glowBox[1] = Math.min(glowBox[1], y);
            glowBox[2] = Math.min(glowBox[2], z);
            glowBox[3] = Math.max(glowBox[3], x);
            glowBox[4] = Math.max(glowBox[4], y);
            glowBox[5] = Math.max(glowBox[5], z);
        }
        write = p + VERTEX;
    }

    /**
     * Квадрат лицом к камере с центром (x, y, z) от камеры, полуразмером half, повёрнутый на rot, на расстоянии key
     * (для сортировки), текстура — место на листе.
     */
    public void billboard(float x, float y, float z, float key, float half, float rot, FxAtlas.Sprite s, float r, float g, float b, float a,
                          float soft, float unfold, int light, float fog) {
        billboard(x, y, z, key, half, half, rot, s, r, g, b, a, soft, unfold, light, fog);
    }

    /**
     * То же с разными полуразмерами по осям квадрата (до поворота): {@code halfA} — вдоль «влево» экрана, {@code halfB} —
     * вдоль «вверх». Хлопья пепла кувыркаются — сжатая ось.
     */
    public void billboard(float x, float y, float z, float key, float halfA, float halfB, float rot, FxAtlas.Sprite s, float r, float g, float b,
                          float a, float soft, float unfold, int light, float fog) {
        float c = Mth.cos(rot), sn = Mth.sin(rot);
        // оси квадрата в плоскости экрана, повёрнутые на rot
        float ax = (left.x * c + up.x * sn) * halfA, ay = (left.y * c + up.y * sn) * halfA, az = (left.z * c + up.z * sn) * halfA;
        float bx = (-left.x * sn + up.x * c) * halfB, by = (-left.y * sn + up.y * c) * halfB, bz = (-left.z * sn + up.z * c) * halfB;
        quad(key);
        vertex(x - ax - bx, y - ay - by, z - az - bz, s.u1(), s.v1(), r, g, b, a, soft, unfold, light, fog);
        vertex(x - ax + bx, y - ay + by, z - az + bz, s.u1(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax + bx, y + ay + by, z + az + bz, s.u0(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax - bx, y + ay - by, z + az - bz, s.u0(), s.v1(), r, g, b, a, soft, unfold, light, fog);
    }

    /**
     * Квадрат, лежащий в горизонтальной плоскости, с центром (x, y, z) от камеры: оси — «влево» экрана, положенное на
     * горизонталь, и горизонталь поперёк неё (от камеры), полуразмеры {@code halfA} и {@code halfB}. Плоский клуб (слой
     * пыли у земли) — такой квадрат: у квадрата лицом к камере нижняя половина уходила в землю, а верхняя стояла стеной
     * над ней; у лежащего каждая точка — на своей глубине, и мягкий край у рельефа считается по ней.
     */
    public void flat(float x, float y, float z, float key, float halfA, float halfB, FxAtlas.Sprite s, float r, float g, float b, float a,
                     float soft, float unfold, int light, float fog) {
        float lx = left.x, lz = left.z, len = Mth.sqrt(lx * lx + lz * lz);
        if (len < 1e-6f) {
            lx = 1;
            lz = 0;
        } else {
            lx /= len;
            lz /= len;
        }
        float ax = lx * halfA, az = lz * halfA, bx = -lz * halfB, bz = lx * halfB;
        quad(key);
        vertex(x - ax - bx, y, z - az - bz, s.u1(), s.v1(), r, g, b, a, soft, unfold, light, fog);
        vertex(x - ax + bx, y, z - az + bz, s.u1(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax + bx, y, z + az + bz, s.u0(), s.v0(), r, g, b, a, soft, unfold, light, fog);
        vertex(x + ax - bx, y, z + az - bz, s.u0(), s.v1(), r, g, b, a, soft, unfold, light, fog);
    }

    /** Отсортировать и нарисовать текущим шейдером; память кадра остаётся для следующего. */
    void draw() {
        glowFrom = n;
        if (n == 0) return;
        int[] order = sort.sort(keys, n);
        for (int i = 0; i < n; i++) {
            if (emits[order[i]]) {
                glowFrom = i;
                break;
            }
        }
        long dst = out.reserve(n * QUAD);
        for (int i = 0; i < n; i++) MemoryUtil.memCopy(stage + (long) order[i] * QUAD, dst + (long) i * QUAD, QUAD);
        ByteBufferBuilder.Result result = out.build();
        if (result == null) {
            glowFrom = n;
            return;
        }
        BufferUploader.drawWithShader(new MeshData(result, new MeshData.DrawState(FORMAT, n * 4, n * 6, VertexFormat.Mode.QUADS,
                VertexFormat.IndexType.least(n * 4))));
    }

    /**
     * После {@link #draw}: ещё раз шейдером s — квадраты от самого дальнего светящегося до ближнего (проход свечения,
     * {@link FxBloom}): что дальше всех светящихся, их излучения не закрывает. Из того же буфера, что выгрузил
     * {@link #draw} (его отсортированные квадраты — с места {@code glowFrom}), без второй выгрузки. false — светящихся нет.
     */
    boolean drawGlow(net.minecraft.client.renderer.ShaderInstance s) {
        if (glowFrom >= n) return false;
        com.mojang.blaze3d.vertex.VertexBuffer vb = FORMAT.getImmediateDrawVertexBuffer();
        vb.bind();
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, com.mojang.blaze3d.systems.RenderSystem.getModelViewMatrix(),
                com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix(), net.minecraft.client.Minecraft.getInstance().getWindow());
        s.apply();
        VertexFormat.IndexType type = com.mojang.blaze3d.systems.RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();
        org.lwjgl.opengl.GL11.glDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES, (n - glowFrom) * 6, type.asGLType, (long) glowFrom * 6 * type.bytes);
        s.clear();
        return true;
    }

    private void grow() {
        int cap = Math.max(1024, capacity * 2);
        long p = stage == 0 ? MemoryUtil.nmemAlloc((long) cap * QUAD) : MemoryUtil.nmemRealloc(stage, (long) cap * QUAD);
        if (p == 0) throw new OutOfMemoryError("Effects layer: out of memory for " + cap + " quads");
        stage = p;
        capacity = cap;
        keys = Arrays.copyOf(keys, cap);
        emits = Arrays.copyOf(emits, cap);
    }

    private static byte unorm(float f) {
        return (byte) (int) (Mth.clamp(f, 0, 1) * 255 + 0.5f);
    }

    /**
     * Порядок от дальних к ближним: поразрядная сортировка (три прохода по 11, 11 и 10 бит) по битам расстояния —
     * у неотрицательных чисел порядок битов и есть порядок значений, инверсия даёт убывание; равные — в порядке записи.
     * Массивы живут между кадрами.
     */
    static final class FarFirst {
        private static final int RADIX = 11, BUCKETS = 1 << RADIX;
        private final int[] counts = new int[BUCKETS];
        private int[] order = new int[0], spare = new int[0], bits = new int[0], spareBits = new int[0];

        /** Номера первых n ключей (неотрицательных) по убыванию; массив — до следующего вызова. */
        int[] sort(float[] keys, int n) {
            if (order.length < n) {
                int cap = Math.max(n, order.length * 2);
                order = new int[cap];
                spare = new int[cap];
                bits = new int[cap];
                spareBits = new int[cap];
            }
            int[] idx = order, idx2 = spare, k = bits, k2 = spareBits;
            for (int i = 0; i < n; i++) {
                idx[i] = i;
                k[i] = ~Float.floatToRawIntBits(keys[i]);
            }
            for (int shift = 0; shift < 32; shift += RADIX) {
                Arrays.fill(counts, 0);
                for (int i = 0; i < n; i++) counts[k[i] >>> shift & BUCKETS - 1]++;
                for (int i = 0, sum = 0; i < BUCKETS; i++) {
                    int c = counts[i];
                    counts[i] = sum;
                    sum += c;
                }
                for (int i = 0; i < n; i++) {
                    int to = counts[k[i] >>> shift & BUCKETS - 1]++;
                    idx2[to] = idx[i];
                    k2[to] = k[i];
                }
                int[] t = idx;
                idx = idx2;
                idx2 = t;
                t = k;
                k = k2;
                k2 = t;
            }
            order = idx;
            spare = idx2;
            bits = k;
            spareBits = k2;
            return idx;
        }
    }
}
