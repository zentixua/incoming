package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * Квадраты кадра слоя эффектов: копятся в своей памяти (вне кучи Java, между кадрами не освобождается), потом
 * сортируются от дальних к ближним по настоящему расстоянию до камеры и рисуются в общем порядке. Прозрачное
 * смешивается верно только в таком порядке: ближний клуб поверх дальнего, а не «кто родился позже».
 * <p>
 * Вершина — 36 байт ({@link #FORMAT}): место от камеры, место на листе, свет с умноженной альфой, мягкость края
 * у рельефа или проба источника ({@link #seenBy}) и множитель переноса ближе ({@code UV1}), свет мира ({@code UV2}),
 * туман Minecraft ({@code Normal.x}),
 * чем брать текстуру ({@code Normal.y}: 1 — частица ближайшим пикселем, {@link #nearestTexels}; −1 — плитка атласа
 * дальних моделей, {@link #modelTiles}; ½ — ореол по формуле, {@link #glowShape}; −¼ — запечённый клуб с шестисторонним
 * светом, {@link #sixWay}; ⅝ — кадры огня со смесью, {@link #fireballMix}; 0 — лист слоя гладко) и непрозрачность частицы без текстуры или смесь кадров клуба
 * ({@code Normal.z}, {@link #opacity}).
 */
public final class FxQuads {
    public static final VertexFormat FORMAT = VertexFormat.builder()
            .add("Position", VertexFormatElement.POSITION)
            .add("UV0", VertexFormatElement.UV0)
            .add("Color", VertexFormatElement.COLOR)
            .add("UV1", VertexFormatElement.UV1)
            .add("UV2", VertexFormatElement.UV2)
            .add("Normal", VertexFormatElement.NORMAL)
            .padding(1)
            .build();
    private static final int VERTEX = 36, QUAD = 4 * VERTEX;
    /** Мягкость края клуба у земли и стен — доля его полуразмера. */
    public static final float SOFT = 0.5f;

    private long stage;
    private int capacity, n;
    private long write;
    private float[] keys = new float[0];
    private boolean[] reducible = new boolean[0];
    private int[] resolution = new int[0];
    private boolean[] thin = new boolean[0];
    private float[] thinBounds = new float[0];
    private float[] bounds = new float[0];
    private float[] frameBounds;
    private final Vector4f clip = new Vector4f();
    private VertexBuffer gpu;
    private final FarFirst sort = new FarFirst();
    private final ByteBufferBuilder out = new ByteBufferBuilder(QUAD * 256);
    private Vector3f left = new Vector3f(1, 0, 0), up = new Vector3f(0, 1, 0);
    private byte nearest, opacity;
    private short probe;

    /** Новый кадр: оси экрана (влево, вверх) для квадратов лицом к камере. */
    public void begin(Vector3f left, Vector3f up) {
        this.left = left;
        this.up = up;
        // Enhanced class redefinition initializes added fields to null in an already running sandbox.
        if (resolution == null) resolution = new int[capacity];
        if (thin == null) thin = new boolean[capacity];
        if (thinBounds == null) thinBounds = new float[4 * capacity];
        n = 0;
        nearest = opacity = 0;
        probe = 0;
        six = false;
    }

    /**
     * Следующие квадраты — частицы: вблизи ближайшим пикселем картинки, как из атласа Minecraft (сглаженный клуб
     * мыльный), и без бледного края ({@link #opacity}); мельче вдвое — гладко. Дальнее — всегда гладко (ореол в сотню
     * пикселей из картинки 64×64 иначе лестницей).
     */
    public void nearestTexels(boolean on) {
        nearest = (byte) (on ? 127 : 0);
        six = false;
        opacity = 0;
    }

    /**
     * Следующие квадраты — плитки атласа дальних моделей ({@code FarModels}): текстура — его, свет в нём уже есть
     * (карта освещения не умножается).
     */
    public void modelTiles(boolean on) {
        nearest = (byte) (on ? -127 : 0);
        six = false;
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
        sixWay(mix, 1, 1);
    }

    /**
     * То же с тенью карт в степени shade (0…{@link #SHADE_MAX}, {@code SmokePuffs.shade}): она — в {@code Normal.y} внутри
     * полосы запечённых клубов (−0,4…−0,1), {@link #SHADE_STEPS} ступеней; толща клуба — толща его кадра × tau
     * ({@code PuffOptics}: непрозрачность 1 − (1 − α)^tau) — в {@code UV2.x} как {@link #TAU_STEPS}·log₂ tau: свет мира у клуба
     * всегда полный (под открытым небом; его свет — по картам кадра в шейдере), и карта освещения берётся без UV2.
     */
    public void sixWay(float mix, float shade, double tau) {
        sixWay(mix, shade, tau, false);
    }

    /** Broad optically thin smoke needs density sampling; its sub-threshold self-shadow needs no six-way maps. */
    public void sixWay(float mix, float shade, double tau, boolean broad) {
        nearest = (byte) (mix < 0 ? 0 : -13 - Math.round(Mth.clamp(shade / SHADE_MAX, 0, 1) * SHADE_STEPS));
        if (broad && mix >= 0) nearest = -60;
        opacity = mix < 0 ? 0 : (byte) Math.round(Mth.clamp(mix, 0, 1) * 127);
        sixTau = mix < 0 ? 0 : tauCode(tau);
        six = mix >= 0;
    }

    /** Множитель толщи клуба в {@code UV2.x}: {@link #TAU_STEPS}·log₂ tau ({@code fx.vsh}: exp2(UV2.x / SIX_TAU_STEPS)). */
    public static short tauCode(double tau) {
        return (short) Mth.clamp(Math.round(Math.log(Math.max(tau, 1e-30)) / Math.log(2) * TAU_STEPS), Short.MIN_VALUE + 1, Short.MAX_VALUE);
    }

    /** Ступеней множителя толщи клуба на октаву ({@code fx.vsh}: {@code SIX_TAU_STEPS}): ±32 октавы, шаг 0,07 %. */
    public static final int TAU_STEPS = 1024;
    private short sixTau;
    private boolean six;

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
        six = false;
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

    public int size() {
        return n;
    }

    /** Начать квадрат на расстоянии {@code key} от камеры; дальше — ровно четыре {@link #vertex}. */
    public void quad(float key) {
        if (n == capacity) grow();
        keys[n] = key > 0 ? key : 0;
        thin[n] = six && nearest == -60;
        reducible[n] = nearest > -100; // Sprite particles, ribbons and light; model tiles retain native resolution.
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
        MemoryUtil.memPutShort(p + 28, six ? sixTau : (short) (light & 0xFFFF));
        MemoryUtil.memPutShort(p + 30, six ? 0 : (short) (light >>> 16 & 0xFFFF));
        MemoryUtil.memPutByte(p + 32, (byte) Math.round(Mth.clamp(fog, 0, 1) * 127));
        MemoryUtil.memPutByte(p + 33, nearest);
        MemoryUtil.memPutByte(p + 34, opacity);
        MemoryUtil.memPutByte(p + 35, (byte) 0);
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
    void draw(Matrix4f projectionView, ShaderInstance shader, int scene) {
        if (n == 0) {
            PuffTarget.reset();
            return;
        }
        int[] order = sort.sort(keys, n);
        if (frameBounds == null || frameBounds.length < 4 * n) frameBounds = new float[4 * capacity];
        // Консервативные рамки настоящих квадратов в кадре: бюджет — по числу пикселей цели, включая прозрачные углы.
        for (int i = 0; i < n; i++) {
            int o = 4 * i;
            Arrays.fill(bounds, o, o + 4, 0);
            Arrays.fill(thinBounds, o, o + 4, 0);
            float x0 = 1, y0 = 1, x1 = 0, y1 = 0;
            int behind = 0;
            for (int j = 0; j < 4; j++) {
                long p = stage + (long) i * QUAD + (long) j * VERTEX;
                projectionView.transform(clip.set(MemoryUtil.memGetFloat(p), MemoryUtil.memGetFloat(p + 4), MemoryUtil.memGetFloat(p + 8), 1));
                if (!(clip.w > 0)) {
                    behind++;
                    continue;
                }
                float x = Math.clamp(clip.x / clip.w * 0.5f + 0.5f, 0, 1), y = Math.clamp(clip.y / clip.w * 0.5f + 0.5f, 0, 1);
                x0 = Math.min(x0, x);
                x1 = Math.max(x1, x);
                y0 = Math.min(y0, y);
                y1 = Math.max(y1, y);
            }
            if (behind == 4) { x0 = y0 = x1 = y1 = 0; }
            else if (behind > 0) { x0 = y0 = 0; x1 = y1 = 1; }
            frameBounds[o] = x0;
            frameBounds[o + 1] = y0;
            frameBounds[o + 2] = x1;
            frameBounds[o + 3] = y1;
            if (reducible[i]) System.arraycopy(frameBounds, o, thin[i] ? thinBounds : bounds, o, 4);
        }
        int visible = visibleOrder(order, frameBounds, n);
        if (!PuffTarget.begin(shader, scene, bounds, thinBounds, n)) {
            if (visible > 0) draw(order, 0, visible);
            return;
        }
        for (int i = 0; i < n; i++) resolution[i] = reducible[i] ? PuffTarget.resolution(thin[i] ? thinBounds : bounds, i, thin[i]) : 1;
        // One upload for all ranges. Rebuilding an immediate MeshData and its uniforms per group costs CPU at a raid.
        if (gpu == null) gpu = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        gpu.bind();
        MeshData mesh = mesh(order, 0, visible);
        if (mesh == null) {
            PuffTarget.end();
            VertexBuffer.unbind();
            return;
        }
        gpu.upload(mesh);
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        try {
            // Только последовательные частицы общего порядка. Модели и субпиксельные детали остаются в полном разрешении.
            for (int from = 0; from < visible;) {
                int sampleStep = resolution[order[from]];
                int to = groupEnd(order, resolution, from, visible);
                if (sampleStep > 1) {
                    float x0 = 1, y0 = 1, x1 = 0, y1 = 0;
                    for (int i = from; i < to; i++) {
                        int o = 4 * order[i];
                        x0 = Math.min(x0, frameBounds[o]);
                        y0 = Math.min(y0, frameBounds[o + 1]);
                        x1 = Math.max(x1, frameBounds[o + 2]);
                        y1 = Math.max(y1, frameBounds[o + 3]);
                    }
                    if (PuffTarget.low(x0, y0, x1, y1, sampleStep)) {
                        range(shader, from, to);
                        PuffTarget.composite(x0, y0, x1, y1);
                    }
                } else range(shader, from, to);
                from = to;
            }
        } finally {
            PuffTarget.end();
            shader.clear();
            VertexBuffer.unbind();
        }
    }

    /** Invisible quads neither draw nor split adjacent particle runs. Depth order of the survivors is unchanged. */
    static int visibleOrder(int[] order, float[] rects, int count) {
        int visible = 0;
        for (int i = 0; i < count; i++) {
            int o = 4 * order[i];
            if (rects[o + 2] > rects[o] && rects[o + 3] > rects[o + 1]) order[visible++] = order[i];
        }
        return visible;
    }

    /** Граница последовательной группы одного разрешения в общем порядке глубины. */
    static int groupEnd(int[] order, int[] resolution, int from, int count) {
        int to = from + 1;
        while (to < count && resolution[order[to]] == resolution[order[from]]) to++;
        return to;
    }

    private void draw(int[] order, int from, int to) {
        MeshData mesh = mesh(order, from, to);
        if (mesh != null) BufferUploader.drawWithShader(mesh);
    }

    private MeshData mesh(int[] order, int from, int to) {
        int count = to - from;
        long dst = out.reserve(count * QUAD);
        for (int i = from; i < to; i++) MemoryUtil.memCopy(stage + (long) order[i] * QUAD, dst + (long) (i - from) * QUAD, QUAD);
        ByteBufferBuilder.Result result = out.build();
        if (result == null) return null;
        return new MeshData(result, new MeshData.DrawState(FORMAT, count * 4, count * 6, VertexFormat.Mode.QUADS,
                VertexFormat.IndexType.least(count * 4)));
    }

    private void range(ShaderInstance shader, int from, int to) {
        gpu.bind(); // The compositor uses the vanilla POSITION buffer between these ranges.
        shader.apply();
        VertexFormat.IndexType type = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();
        GlStateManager._drawElements(GL11.GL_TRIANGLES, (to - from) * 6, type.asGLType, (long) from * 6 * type.bytes);
        // Keep atlas/light samplers bound; only the two compositor samplers change before the next range.
    }

    private void grow() {
        int cap = Math.max(1024, capacity * 2);
        long p = stage == 0 ? MemoryUtil.nmemAlloc((long) cap * QUAD) : MemoryUtil.nmemRealloc(stage, (long) cap * QUAD);
        if (p == 0) throw new OutOfMemoryError("Effects layer: out of memory for " + cap + " quads");
        stage = p;
        capacity = cap;
        keys = Arrays.copyOf(keys, cap);
        reducible = Arrays.copyOf(reducible, cap);
        resolution = Arrays.copyOf(resolution, cap);
        thin = Arrays.copyOf(thin, cap);
        thinBounds = Arrays.copyOf(thinBounds, 4 * cap);
        bounds = Arrays.copyOf(bounds, 4 * cap);
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
