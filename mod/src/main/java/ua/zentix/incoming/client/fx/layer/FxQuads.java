package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
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
    /** После {@link #draw}: начало прохода свечения в отсортированном порядке ({@link #glowStart}; n — нет светящихся). */
    private int glowFrom;
    /**
     * Глубина, ближе которой квадраты закрывают ореол светящихся ({@link #glowStart}): наибольшее у светящихся кадра
     * расстояние с полуразмером квадрата.
     */
    private float glowReach;
    /** Рамка вершин светящихся квадратов кадра (от камеры): x, y, z наименьшие, потом наибольшие. */
    private final float[] glowBox = new float[6];
    /** Угол пикселя прохода свечения, рад ({@link FxBloom#texel}); 0 — свечения нет. */
    private double glowTexel;
    /** Вершин записано у текущего квадрата; идёт запись пятен ({@link #spots}). */
    private int corner;
    private boolean spotting;
    private final FarFirst sort = new FarFirst();
    private final ByteBufferBuilder out = new ByteBufferBuilder(QUAD * 256);
    private Vector3f left = new Vector3f(1, 0, 0), up = new Vector3f(0, 1, 0);
    private byte nearest, opacity, emit;
    private short probe;
    /** В кадре есть квадраты со свечением ({@link #emission}). */
    private boolean glows;
    /**
     * Вид с проекцией (от камеры) и размер кадра в пикселях ({@link #screen}): по ним квадрату — его уровень мелкого кадра
     * ({@link CoarsePass}); без них кадр весь в полном разрешении.
     */
    @Nullable
    private Matrix4f screen;
    private int screenW, screenH;
    /**
     * Самый мелкий уровень квадрата ({@link CoarsePass#level}; 0 — только полный кадр), доля кадра под ним и рамка на
     * экране (NDC: x0, y0, x1, y1) — у запечённых клубов без свечения; у остального — 0.
     */
    private byte[] level = new byte[0];
    private float[] cover = new float[0], box = new float[0];
    /** План кадра ({@link CoarsePass#plan}) по отсортированным квадратам: их уровни и доли, начала и уровни отрезков. */
    private byte[] sortedLevel = new byte[0], runLevel = new byte[CoarsePass.MAX_RUNS + 1];
    private float[] sortedCover = new float[0];
    private final float[] runBox = new float[4];
    private int[] runFrom = new int[CoarsePass.MAX_RUNS + 1];
    private final double[] runFill = new double[CoarsePass.LEVELS];
    private final Vector4f clip = new Vector4f();

    /** Новый кадр: оси экрана (влево, вверх) для квадратов лицом к камере. */
    public void begin(Vector3f left, Vector3f up) {
        this.left = left;
        this.up = up;
        n = 0;
        nearest = opacity = emit = 0;
        probe = 0;
        glows = false;
        screen = null;
        glowReach = -Float.MAX_VALUE;
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
        emit = (byte) (hdr > 0 && glowTexel > 0 ? FxBloom.EMITTER + FxBloom.code(hdr) : 0);
    }

    /**
     * Угол пикселя прохода свечения кадра, рад ({@link FxBloom#texel}): светящийся квадрат уже {@link FxBloom#SMALL} таких
     * пикселей идёт в свечение пятнами с той же энергией ({@link #spots}); 0 — свечения нет (настройка, шейдеры).
     */
    public void glowTexel(double texel) {
        glowTexel = texel;
    }

    /**
     * Вид с проекцией от камеры ({@code FarDraw.viewProjection}) и размер кадра в пикселях: квадраты кадра (после этого вызова)
     * знают, сколько пикселей на тексель их картинки, и крупные клубы могут идти мелким кадром ({@link CoarsePass}).
     */
    public void screen(Matrix4f viewProjection, int width, int height) {
        screen = viewProjection;
        screenW = width;
        screenH = height;
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

    /** Место вершин квадрата i в памяти кадра (проверки: что записано — то и уйдёт в шейдер). */
    long address(int i) {
        return stage + (long) i * QUAD;
    }

    /** Начать квадрат на расстоянии {@code key} от камеры; дальше — ровно четыре {@link #vertex}. */
    public void quad(float key) {
        if (n == capacity) grow();
        keys[n] = key > 0 ? key : 0;
        emits[n] = false;
        level[n] = 0;
        cover[n] = 0;
        write = stage + (long) n * QUAD;
        n++;
        corner = 0;
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
        if (++corner == 4 && emits[n - 1]) {
            reach(n - 1);
            if (!spotting) spots(n - 1);
        }
        // запечённый клуб без свечения: может идти мелким кадром (сколько пикселей на тексель)
        if (corner == 4 && screen != null && !emits[n - 1] && nearest <= -13 && nearest >= -13 - SHADE_STEPS) measure(n - 1);
    }

    /**
     * Уровень мелкого кадра квадрата i ({@link CoarsePass#level}): по наименьшему растяжению картинки на экране —
     * пикселей на тексель в самом сжатом направлении, у каждого угла ({@link #stretch}); доля кадра и рамка на экране. Угол
     * у глаза или за ним (плоский квадрат, который глаз пересекает) — полный кадр во весь экран: растяжение у видимого края
     * такого квадрата бывает в доли пикселя на тексель (пыль под глазом, ревью #455), а меры без угла нет. Клуб лицом к
     * глазу так не бывает: его углы — на одном расстоянии с серединой.
     */
    private void measure(int i) {
        long q = stage + (long) i * QUAD;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        boolean behind = false;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            screen.transform(clip.set(MemoryUtil.memGetFloat(p), MemoryUtil.memGetFloat(p + 4), MemoryUtil.memGetFloat(p + 8), 1));
            if (clip.w < 1e-3f) {
                behind = true;
                break;
            }
            float sx = (clip.x / clip.w * 0.5f + 0.5f) * screenW, sy = (clip.y / clip.w * 0.5f + 0.5f) * screenH;
            corners[3 * k] = sx;
            corners[3 * k + 1] = sy;
            x0 = Math.min(x0, sx);
            y0 = Math.min(y0, sy);
            x1 = Math.max(x1, sx);
            y1 = Math.max(y1, sy);
        }
        int b = 4 * i;
        if (behind) {
            level[i] = 0;
            cover[i] = 1;
            box[b] = box[b + 1] = -1;
            box[b + 2] = box[b + 3] = 1;
            return;
        }
        double perTexel = Double.MAX_VALUE;
        for (int k = 0; k < 4; k++) perTexel = Math.min(perTexel, stretch(q, k));
        level[i] = (byte) (perTexel == Double.MAX_VALUE ? 0 : CoarsePass.level(perTexel));
        float cx0 = Math.max(0, x0), cy0 = Math.max(0, y0), cx1 = Math.min(screenW, x1), cy1 = Math.min(screenH, y1);
        cover[i] = cx1 > cx0 && cy1 > cy0 ? (cx1 - cx0) * (cy1 - cy0) / ((float) screenW * screenH) : 0;
        box[b] = cx0 / screenW * 2 - 1;
        box[b + 1] = cy0 / screenH * 2 - 1;
        box[b + 2] = cx1 / screenW * 2 - 1;
        box[b + 3] = cy1 / screenH * 2 - 1;
    }

    /** Доля ребра, на которую сдвигается точка у угла: производная проекции разностью, без заметной кривизны. */
    private static final double STEP = 0.01;

    /**
     * Наименьшее растяжение картинки у угла k квадрата q: пикселей экрана на тексель в самом сжатом направлении — меньшее
     * сингулярное число якобиана проекции по двум рёбрам угла (рёбра листа — по осям картинки), разностью на {@link #STEP}
     * ребра. Длина ребра на экране целиком тут не годится: у плоского квадрата вскользь (пыль у земли) ближний край
     * растянут, дальний сжат в доли пикселя на тексель (ревью #455). Перспектива сжимает сильнее всего у самого дальнего
     * места квадрата, а оно — в углу (1/w по плоскости линейна): наименьшее по углам — наименьшее по квадрату. Без
     * рёбер на листе — без меры.
     */
    private double stretch(long q, int k) {
        double ax = 0, ay = 0, bx = 0, by = 0;
        for (int e = 0; e < 2; e++) {
            int j = e == 0 ? (k + 1) & 3 : (k + 3) & 3;
            long p = q + (long) k * VERTEX, r = q + (long) j * VERTEX;
            double texels = Math.hypot((MemoryUtil.memGetFloat(r + 12) - MemoryUtil.memGetFloat(p + 12)) * FxAtlas.WIDTH,
                    (MemoryUtil.memGetFloat(r + 16) - MemoryUtil.memGetFloat(p + 16)) * FxAtlas.HEIGHT);
            if (texels <= 0.5) return Double.MAX_VALUE;
            float x = MemoryUtil.memGetFloat(p), y = MemoryUtil.memGetFloat(p + 4), z = MemoryUtil.memGetFloat(p + 8);
            float t = (float) STEP;
            // шаг внутрь квадрата: w между углами, у которых он больше нуля
            screen.transform(clip.set(x + (MemoryUtil.memGetFloat(r) - x) * t, y + (MemoryUtil.memGetFloat(r + 4) - y) * t,
                    z + (MemoryUtil.memGetFloat(r + 8) - z) * t, 1));
            double sx = (clip.x / clip.w * 0.5 + 0.5) * screenW, sy = (clip.y / clip.w * 0.5 + 0.5) * screenH;
            double per = STEP * texels, dx = (sx - corners[3 * k]) / per, dy = (sy - corners[3 * k + 1]) / per;
            if (e == 0) {
                ax = dx;
                ay = dy;
            } else {
                bx = dx;
                by = dy;
            }
        }
        double sum = ax * ax + ay * ay + bx * bx + by * by, det = ax * by - ay * bx;
        return Math.sqrt(Math.max(0, (sum - Math.sqrt(Math.max(0, sum * sum - 4 * det * det))) / 2));
    }

    /**
     * Светящийся квадрат i достаёт по глубине до своего расстояния с полуразмером (настоящим: вершины вдали перенесены
     * ближе): кадр шара стоит у передней половины шара, а ореол — свет воздуха вокруг всего огня, и рукава дыма сбоку на
     * глубине его середины стоят перед частью этого воздуха.
     */
    private void reach(int i) {
        long q = stage + (long) i * QUAD;
        float cx = 0, cy = 0, cz = 0;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            cx += MemoryUtil.memGetFloat(p) / 4;
            cy += MemoryUtil.memGetFloat(p + 4) / 4;
            cz += MemoryUtil.memGetFloat(p + 8) / 4;
        }
        float half = 0;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            float dx = MemoryUtil.memGetFloat(p) - cx, dy = MemoryUtil.memGetFloat(p + 4) - cy, dz = MemoryUtil.memGetFloat(p + 8) - cz;
            half = Math.max(half, (float) Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        float unfold = Math.max((MemoryUtil.memGetShort(q + 26) & 0xFFFF) / (float) Short.MAX_VALUE, 1e-4f);
        glowReach = Math.max(glowReach, keys[i] + half / unfold);
    }

    private final float[] corners = new float[12];
    private final double[] places = new double[3 * FxBloom.SPOTS + 1];
    private final float[] means = new float[FxAtlas.MEANS];

    /**
     * Светящийся квадрат i уже {@link FxBloom#SMALL} пикселей прохода свечения поперёк ({@link FxBloom#spots}): в проходе
     * свечения выборка в серединах его пикселей то попадает в него, то нет — свечение пропадает и мерцает. Вместо него в
     * проход идут пятна (ореол {@code far/glow} формулой, полуразмер {@link FxBloom#SPOT_HALF} пикселя) с его энергией:
     * средний свет его места на листе ({@link FxAtlas#means}, тем же законом, что {@code bloomLight} в {@code fx.fsh}) × цвет
     * вершин × пересвет × его телесный угол, поровну на пятна; доля закрытого — так же. Свет и доля закрытого — в
     * {@code UV2} ({@link FxBloom#magnitude}), оттенок — в цвете (наибольший канал — 1). Сам он в проход не идёт
     * ({@link FxBloom#SKIP}).
     */
    private void spots(int i) {
        long q = stage + (long) i * QUAD;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            corners[3 * k] = MemoryUtil.memGetFloat(p);
            corners[3 * k + 1] = MemoryUtil.memGetFloat(p + 4);
            corners[3 * k + 2] = MemoryUtil.memGetFloat(p + 8);
        }
        int count = FxBloom.spots(corners, glowTexel, places);
        if (count == 0) return;
        float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE, r = 0, g = 0, b = 0, a = 0, unfold = 0;
        double gain = 0;
        for (int k = 0; k < 4; k++) {
            long p = q + (long) k * VERTEX;
            float u = MemoryUtil.memGetFloat(p + 12), v = MemoryUtil.memGetFloat(p + 16);
            u0 = Math.min(u0, u);
            u1 = Math.max(u1, u);
            v0 = Math.min(v0, v);
            v1 = Math.max(v1, v);
            r += (MemoryUtil.memGetByte(p + 20) & 255) / 1020f;
            g += (MemoryUtil.memGetByte(p + 21) & 255) / 1020f;
            b += (MemoryUtil.memGetByte(p + 22) & 255) / 1020f;
            a += (MemoryUtil.memGetByte(p + 23) & 255) / 1020f;
            unfold += (MemoryUtil.memGetShort(p + 26) & 0xFFFF) / (4f * Short.MAX_VALUE);
            gain += FxBloom.gain((MemoryUtil.memGetByte(p + 35) & 255) - FxBloom.EMITTER) / 4;
        }
        if (!FxAtlas.means(u0, v0, u1, v1, means)) return;
        // как bloomLight: у частиц и кадров огня (умноженная альфа) — свет цвета на долю, у остального — свет поверх
        boolean cover = (MemoryUtil.memGetByte(q + 33) & 255) > 69 && a > 0.004f;
        double[] e = new double[3];
        double alpha;
        if (cover) {
            e[0] = Math.pow(Math.min(1, r / a), 2.2) * a * means[0];
            e[1] = Math.pow(Math.min(1, g / a), 2.2) * a * means[1];
            e[2] = Math.pow(Math.min(1, b / a), 2.2) * a * means[2];
            alpha = a * means[6];
        } else {
            e[0] = Math.pow(r, 2.2) * means[3];
            e[1] = Math.pow(g, 2.2) * means[4];
            e[2] = Math.pow(b, 2.2) * means[5];
            alpha = 0;
        }
        // на пятно: свет квадрата (средний × телесный угол) поровну, по площади ореола пятна
        double solid = places[3 * count], spot = FxBloom.SPOT_COVER * Math.pow(2 * FxBloom.SPOT_HALF * glowTexel, 2), share = solid / count / spot;
        double er = e[0] * gain * share, eg = e[1] * gain * share, eb = e[2] * gain * share, cover2 = Math.min(1, alpha * share);
        double top = Math.max(er, Math.max(eg, eb));
        if (top <= 0 && cover2 <= 0) return;
        // свет пятна — в 16 битах UV2 (FxBloom.magnitude), в цвете — только оттенок: в байте цвета слабое пятно обнулялось
        int light = FxBloom.magnitude(top) & 0xFFFF | FxBloom.magnitude(cover2) << 16;
        float hr = top > 0 ? (float) (er / top) : 0, hg = top > 0 ? (float) (eg / top) : 0, hb = top > 0 ? (float) (eb / top) : 0;
        float soft = Math.max(0, MemoryUtil.memGetShort(q + 24)) / 16f, fog = (MemoryUtil.memGetByte(q + 32) & 255) / 127f, key = keys[i];
        // сам квадрат — только в слое
        for (int k = 0; k < 4; k++) MemoryUtil.memPutByte(q + (long) k * VERTEX + 35, (byte) FxBloom.SKIP);
        byte n0 = nearest, o0 = opacity, e0 = emit;
        short p0 = probe;
        spotting = true;
        nearest = 64;
        opacity = 0;
        probe = 0;
        emit = (byte) FxBloom.SPOT;
        FxAtlas.Sprite glow = FxAtlas.glow();
        for (int k = 0; k < count; k++) {
            double x = places[3 * k], y = places[3 * k + 1], z = places[3 * k + 2], half = FxBloom.SPOT_HALF * glowTexel * Math.sqrt(x * x + y * y + z * z);
            billboard((float) x, (float) y, (float) z, key, (float) half, 0, glow, hr, hg, hb, 1, soft, unfold, light, fog);
        }
        nearest = n0;
        opacity = o0;
        emit = e0;
        probe = p0;
        spotting = false;
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

    /**
     * Отсортировать и нарисовать текущим шейдером; память кадра остаётся для следующего. Крупные клубы — отрезками мелкого
     * кадра ({@link #plan}, {@link CoarsePass}), если он есть.
     */
    void draw() {
        glowFrom = n;
        if (n == 0) return;
        int[] order = sort.sort(keys, n);
        glowFrom = glowStart(order);
        long dst = out.reserve(n * QUAD);
        for (int i = 0; i < n; i++) MemoryUtil.memCopy(stage + (long) order[i] * QUAD, dst + (long) i * QUAD, QUAD);
        ByteBufferBuilder.Result result = out.build();
        if (result == null) {
            glowFrom = n;
            return;
        }
        MeshData mesh = new MeshData(result, new MeshData.DrawState(FORMAT, n * 4, n * 6, VertexFormat.Mode.QUADS, VertexFormat.IndexType.least(n * 4)));
        int runs = runs(order, CoarsePass.ready());
        if (runs == 1 && runLevel[0] == 0) {
            BufferUploader.drawWithShader(mesh);
            return;
        }
        // один буфер на все отрезки (и проход свечения после них)
        BufferUploader.reset();
        VertexBuffer vb = FORMAT.getImmediateDrawVertexBuffer();
        vb.bind();
        vb.upload(mesh);
        ShaderInstance s = RenderSystem.getShader();
        if (s == null) return;
        for (int r = 0; r < runs; r++) {
            int a = runFrom[r], b = r + 1 < runs ? runFrom[r + 1] : n;
            if (runLevel[r] == 0) {
                range(s, a, b);
                continue;
            }
            box(a, b, order);
            CoarsePass.draw(this, s, a, b, runLevel[r], runBox);
        }
    }

    /**
     * План кадра ({@link CoarsePass#plan}) по порядку order; runLevel и runFrom — его отрезки. Вернёт их число; без плана —
     * один отрезок полного кадра.
     */
    int plan(int[] order) {
        if (sortedLevel.length < n) {
            sortedLevel = new byte[capacity];
            sortedCover = new float[capacity];
        }
        for (int i = 0; i < n; i++) {
            sortedLevel[i] = level[order[i]];
            sortedCover[i] = cover[order[i]];
        }
        int runs = CoarsePass.plan(n, sortedLevel, sortedCover, runFrom, runLevel);
        if (runs > 1 || runLevel[0] > 0) {
            Arrays.fill(runFill, 0);
            for (int r = 0; r < runs; r++) {
                int b = r + 1 < runs ? runFrom[r + 1] : n;
                for (int i = runFrom[r]; i < b; i++) runFill[runLevel[r]] += sortedCover[i];
            }
            CoarsePass.counted(runFill);
        }
        return runs;
    }

    /**
     * Отрезки кадра в порядке order: мелких кадров нет ({@code coarse} — нет) — один отрезок полного кадра, без плана
     * (план прошлого кадра здесь не годен: {@code coarse off} или потеря расстояния до мира рисовали бы его уровнем).
     */
    int runs(int[] order, boolean coarse) {
        if (coarse) return plan(order);
        runFrom[0] = 0;
        runLevel[0] = 0;
        return 1;
    }

    /** То же в порядке от дальних (проверки). */
    int runs(boolean coarse) {
        return runs(sort.sort(keys, n), coarse);
    }

    /** Самый мелкий уровень квадрата i в порядке записи (проверки). */
    byte level(int i) {
        return level[i];
    }

    /** План кадра в его порядке от дальних (проверки). */
    int plan() {
        return plan(sort.sort(keys, n));
    }

    /** Отрезки последнего {@link #plan}: начало и уровень отрезка r (проверки). */
    int runFrom(int r) {
        return runFrom[r];
    }

    byte runLevel(int r) {
        return runLevel[r];
    }

    /** Рамка квадратов отрезка [a, b) отсортированного порядка order на экране — в runBox. */
    private void box(int a, int b, int[] order) {
        runBox[0] = runBox[1] = Float.MAX_VALUE;
        runBox[2] = runBox[3] = -Float.MAX_VALUE;
        for (int i = a; i < b; i++) {
            int q = 4 * order[i];
            runBox[0] = Math.min(runBox[0], box[q]);
            runBox[1] = Math.min(runBox[1], box[q + 1]);
            runBox[2] = Math.max(runBox[2], box[q + 2]);
            runBox[3] = Math.max(runBox[3], box[q + 3]);
        }
    }

    /**
     * Квадраты [from, to) отсортированного порядка — шейдером s из буфера, что выгрузил {@link #draw}, без второй выгрузки.
     */
    void range(ShaderInstance s, int from, int to) {
        if (to <= from) return;
        VertexBuffer vb = FORMAT.getImmediateDrawVertexBuffer();
        vb.bind();
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), Minecraft.getInstance().getWindow());
        s.apply();
        VertexFormat.IndexType type = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS).type();
        org.lwjgl.opengl.GL11.glDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES, (to - from) * 6, type.asGLType, (long) from * 6 * type.bytes);
        s.clear();
    }

    /**
     * Начало прохода свечения в порядке order (от дальних): первый квадрат не дальше {@link #glowReach}. В проход идёт
     * то, что закрывает излучение и ореол светящихся: само светящееся и всё ближе глубины, до которой оно достаёт; что
     * дальше, их не закрывает (ореол над ним остаётся). n — светящихся нет.
     */
    private int glowStart(int[] order) {
        for (int i = 0; i < n; i++) {
            if (keys[order[i]] <= glowReach) return i;
        }
        return n;
    }

    /** {@link #glowStart} у квадратов кадра (проверки). */
    int glowStart() {
        return glowStart(sort.sort(keys, n));
    }

    /**
     * После {@link #draw}: ещё раз шейдером s — квадраты с начала прохода свечения ({@link #glowStart}) до ближнего
     * (проход свечения, {@link FxBloom}): что дальше, излучения и ореола светящихся не закрывает. Из того же буфера, что выгрузил
     * {@link #draw} (его отсортированные квадраты — с места {@code glowFrom}), без второй выгрузки. false — светящихся нет.
     */
    boolean drawGlow(ShaderInstance s) {
        if (glowFrom >= n) return false;
        range(s, glowFrom, n);
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
        level = Arrays.copyOf(level, cap);
        cover = Arrays.copyOf(cover, cap);
        box = Arrays.copyOf(box, 4 * cap);
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
