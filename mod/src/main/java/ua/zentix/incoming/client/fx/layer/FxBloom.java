package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;

import java.util.Locale;

/**
 * Свечение (bloom) огня, огненного шара, вспышки и факела — своё, после шейдерпака: слой эффектов идёт после его
 * композита ({@code AFTER_LEVEL}), и законного пути в буфер пака на 1.21.1 нет (Iris без API, «unknown shaders»
 * выключены по умолчанию). Тем же способом, что у Complementary Reimagined/Unbound r5.9.3 ({@code composite4/5}:
 * HDR кадра → пирамида размытий {@code lod} 2–8 → {@code mix(цвет, пирамида, 0,12)} без порога → кривая), только
 * HDR — не весь кадр, а излучение нашего слоя.
 * <ul>
 * <li>Излучение. После квадратов слоя — ещё один проход тем же шейдером ({@code fx.fsh}, {@code FxGlowPass}) на октаву
 * мельче первого уровня ореола ({@link #glowLevel}: у 1080 — половина кадра, у 1600 — четверть; дальше — спуск в 13
 * выборок, и ореол тот же, что из излучения во весь кадр) и только в рамке светящихся на экране (вне её излучения нет, и
 * дым там ничего не гасит): квадраты от самого дальнего светящегося до ближнего (что дальше всех огней, их излучения не
 * закрывает) — из того же буфера вершин — пишут излучение (R11F_G11F_B10F) и долю пикселя, которую занимает само
 * светящееся (R8), тем же смешиванием, что и цвет ({@code ONE, ONE_MINUS_SRC_ALPHA}): дым перед огнём гасит его излучение
 * той же долей, мягкий край у мира тот же, а что за стеной и рельефом, отсекает расстояние до мира ({@link SceneDepth};
 * аппаратной глубины в проходе нет). Дым там — одна непрозрачность, без света. Вторая цель в самом слое (MRT) вышла
 * дороже: каждый фрагмент дыма во весь кадр смешивал ещё две цели (слой +0,6–0,85 мс у ракеты ночью на 2560×1600, так —
 * +0,4–0,6 мс вместе с пирамидой).
 * Свет — линейный, белый экран — 1: показанный свет, умноженный на пересвет, который экран срезал
 * ({@link FxQuads#emission}: у ядер, вспышек и факела — яркость против белого до {@code Sight.onScreen}, у кадра огня —
 * яркость шара модели против его кадра на экране), не ярче белой точки {@link #WHITE} плёночной кривой огня: ярче неё
 * кадр различий не показывает.</li>
 * <li>Пирамида — двойной фильтр Jimenez 2014 (Call of Duty: Advanced Warfare; так же — Unity URP, Blender EEVEE):
 * вниз — 13 выборок, вверх — шатёр 3×3 с прибавлением уровня. Обе свёртки сохраняют энергию, и каждый уровень несёт
 * всю энергию излучения: равные веса уровней — равная энергия на октаву угла, то есть спад 1/θ², как у функции
 * рассеяния глаза CIE (Vos 1984, CIE 135/1999: L/E = 10/θ², θ в градусах). Уровни — как у пака: 2–8 при высоте
 * кадра 1080 (2^k пикселей — ~0,2°…17° при поле зрения 70°), выше — на октаву глубже ({@link #first}).</li>
 * <li>Доля {@link #SHARE} без порога — как у пака по умолчанию; у глаза по CIE на углах 0,1°–50° выходит 0,12
 * (0,0191·ln(θ₂/θ₁)). Ореол прибавляется вокруг светящегося: кривая 1 − e^(−x) и смешивание «экраном»
 * ({@code ONE, ONE_MINUS_SRC_COLOR}) — ровно сложение света до этой кривой. На самом светящемся его нет (по доле
 * светящегося): кадр огня показан своей экспозицией, в десятки раз ниже пересвета модели, и ореол, посчитанный с
 * пересветом, высветлил бы его складки и сажу в «мыло». У пака источник отдаёт ореолу свою долю; здесь он её не
 * теряет — его кадр уже показан своей кривой.</li>
 * </ul>
 * Подача, не физика: яркость и энергия огня в модели не меняются, свет на мир и дым — свой ({@code FxLights},
 * {@code HeatPass}). Блик и вуаль Стайлза–Холладея у глаза ({@code FarSprites.glare}) остаются: они видят источник
 * и за кадром, и вне его. Работает с любым паком и без шейдеров; кадр, в который рисует слой, — привязанный (у
 * Flashback при выводе видео — его размера). Ядерный шар (объём пелены) сюда не пишет.
 */
public final class FxBloom {
    /** Доля излучения в ореоле: {@code BLOOM_STRENGTH} Complementary по умолчанию; у глаза по CIE на 0,1°–50° — 0,12. */
    public static final double SHARE = 0.12;
    /** Уровней пирамиды в ореоле (у пака — lod 2–8). */
    public static final int LEVELS = 7;
    /** Первый уровень при высоте кадра {@link #REFERENCE} (у пака — lod 2). */
    static final int FIRST = 2;
    /** Высота кадра, к которой привязаны уровни (у пака — 1080: размер ореола на экране не зависит от разрешения). */
    static final int REFERENCE = 1080;
    /** Плёночная кривая запекания огня ({@code tools/bake_fx/pack.py film}: ACES, приближение Narkowicz 2016). */
    static final double FA = 2.51, FB = 0.03, FC = 2.43, FD = 0.59, FE = 0.14;
    /** Белая точка плёночной кривой огня: свет, который она показывает белым (≈ 7,24); свечение берёт свет не ярче. */
    public static final double WHITE = filmInverse(1);
    /** Ступеней кода пересвета в байте вершины (1…255; 0 — не светится). */
    static final int STEPS = 254;

    private FxBloom() {}

    // ---------------------------------------------------------------- законы (общие с шейдерами: FxBloomTest)

    /** Плёночная кривая запекания: показанный линейный свет 0…1 по свету x. */
    public static double film(double x) {
        x = Math.max(0, x);
        return Math.min(1, x * (FA * x + FB) / (x * (FC * x + FD) + FE));
    }

    /** Обратная плёночная кривая: свет, который она показывает как y (0…1); y = 1 — {@link #WHITE}. */
    public static double filmInverse(double y) {
        double t = Math.max(0, Math.min(1, y));
        double a = FA - FC * t, b = FB - FD * t, c = -FE * t;
        return (-b + Math.sqrt(b * b - 4 * a * c)) / (2 * a);
    }

    /** Код пересвета в байте вершины: 1 — свет как показан, 255 — {@link #WHITE} и ярче. */
    public static int code(double hdr) {
        double g = Math.max(1, Math.min(WHITE, hdr));
        return 1 + (int) Math.round(STEPS * Math.log(g) / Math.log(WHITE));
    }

    /** Пересвет по коду (код 0 — не светится, но здесь 1). */
    public static double gain(int code) {
        return Math.pow(WHITE, (Math.max(1, code) - 1) / (double) STEPS);
    }

    /** Кривая ореола: свет x (белый экран — 1) → доля до белого; смешивание «экраном» — сложение света до неё. */
    public static double curve(double x) {
        return -Math.expm1(-Math.max(0, x));
    }

    /**
     * Первый уровень пирамиды у кадра высотой {@code height}: как у пака — {@link #FIRST} при {@link #REFERENCE}, выше —
     * на столько октав глубже (у 1600 — третий), ниже — не мельче.
     */
    static int first(int height) {
        return FIRST + (int) Math.max(0, Math.round(Math.log(height / (double) REFERENCE) / Math.log(2)));
    }

    /**
     * Уровень прохода излучения у кадра высотой {@code height}: на октаву мельче {@link #first} — дальше он идёт в пирамиду
     * через спуск в 13 выборок, и ореол выходит тем же, что из излучения во весь кадр; а фрагментов у прохода вчетверо
     * меньше на каждую октаву (у 1600 — четверть кадра).
     */
    static int glowLevel(int height) {
        return first(height) - 1;
    }

    /** Последний уровень: {@link #LEVELS} от первого, но не мельче двух пикселей по меньшей стороне. */
    static int last(int width, int height) {
        int f = first(height), k = f;
        while (k < f + LEVELS - 1 && size(Math.min(width, height), k + 1) >= 2) k += 1;
        return k;
    }

    /** Размер уровня k по стороне n: вдвое мельче на уровень, с округлением вверх. */
    static int size(int n, int k) {
        for (int i = 0; i < k; i++) n = (n + 1) / 2;
        return Math.max(1, n);
    }

    // ---------------------------------------------------------------- пирамида на процессоре (те же выборки, что шейдеры)

    /** Пиксели уровня: свет одного канала по строкам, ширина и высота. */
    record Image(float[] v, int w, int h) {
        static Image of(int w, int h) {
            return new Image(new float[w * h], w, h);
        }

        float at(int x, int y) {
            return v[Math.max(0, Math.min(h - 1, y)) * w + Math.max(0, Math.min(w - 1, x))];
        }

        /** Выборка с линейным фильтром и краем «по краю» в точке (x, y) в пикселях (середина пикселя i — i + ½). */
        float sample(double x, double y) {
            double u = x - 0.5, t = y - 0.5;
            int i = (int) Math.floor(u), j = (int) Math.floor(t);
            double fu = u - i, ft = t - j;
            return (float) ((at(i, j) * (1 - fu) + at(i + 1, j) * fu) * (1 - ft) + (at(i, j + 1) * (1 - fu) + at(i + 1, j + 1) * fu) * ft);
        }

        double sum() {
            double s = 0;
            for (float f : v) s += f;
            return s;
        }
    }

    /** Вниз ({@code fx_bloom_down}): пиксель (x, y) уровня — точка (2x + 1, 2y + 1) прошлого, 13 выборок. */
    static Image down(Image s) {
        Image o = Image.of((s.w + 1) / 2, (s.h + 1) / 2);
        for (int y = 0; y < o.h; y++) {
            for (int x = 0; x < o.w; x++) {
                double cx = 2 * x + 1, cy = 2 * y + 1;
                double e = s.sample(cx, cy);
                double corners = s.sample(cx - 2, cy + 2) + s.sample(cx + 2, cy + 2) + s.sample(cx - 2, cy - 2) + s.sample(cx + 2, cy - 2);
                double sides = s.sample(cx, cy + 2) + s.sample(cx - 2, cy) + s.sample(cx + 2, cy) + s.sample(cx, cy - 2);
                double inner = s.sample(cx - 1, cy + 1) + s.sample(cx + 1, cy + 1) + s.sample(cx - 1, cy - 1) + s.sample(cx + 1, cy - 1);
                o.v[y * o.w + x] = (float) (e * 0.125 + corners * 0.03125 + sides * 0.0625 + inner * 0.125);
            }
        }
        return o;
    }

    /** Вверх ({@code fx_bloom_up}): шатёр 3×3 по пикселям мельчайшего уровня s в уровень размера w×h. */
    static Image tent(Image s, int w, int h) {
        Image o = Image.of(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double cx = (x + 0.5) / 2, cy = (y + 0.5) / 2;
                double corners = s.sample(cx - 1, cy - 1) + s.sample(cx + 1, cy - 1) + s.sample(cx - 1, cy + 1) + s.sample(cx + 1, cy + 1);
                double sides = s.sample(cx, cy - 1) + s.sample(cx - 1, cy) + s.sample(cx + 1, cy) + s.sample(cx, cy + 1);
                o.v[y * w + x] = (float) ((corners + 2 * sides + 4 * s.sample(cx, cy)) / 16);
            }
        }
        return o;
    }

    /**
     * Ореол во весь кадр w×h по излучению прохода свечения e (уровень {@link #glowLevel}) — свет до кривой (без доли
     * {@link #SHARE}): уровни {@link #first} … {@link #last} с равными весами, как в проходах; сумма по кадру — сумма
     * излучения в пикселях кадра (энергия сохраняется).
     */
    static Image halo(Image e, int w, int h) {
        int g = glowLevel(h), first = first(h), last = last(w, h);
        Image[] level = new Image[last + 1];
        level[g] = e;
        for (int k = g + 1; k <= last; k++) level[k] = down(level[k - 1]);
        Image up = level[last];
        for (int k = last - 1; k >= first; k--) {
            Image t = tent(up, level[k].w, level[k].h);
            for (int i = 0; i < t.v.length; i++) t.v[i] += level[k].v[i];
            up = t;
        }
        // мельче первого уровня — шатром до половины кадра: растянутый вчетверо линейно уровень у источника некруглый (~10 %)
        for (int k = first - 1; k >= 1; k--) up = tent(up, size(w, k), size(h, k));
        Image out = Image.of(w, h);
        double scale = 1.0 / (last - first + 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) out.v[y * w + x] = (float) (up.sample((x + 0.5) / 2, (y + 0.5) / 2) * scale);
        }
        return out;
    }

    // ---------------------------------------------------------------- проходы

    @Nullable
    static ShaderInstance down, up, out;
    /** Рамка прохода свечения: излучение (уровень {@link #glowLevel} пирамиды) и доля светящегося того же размера. */
    private static int fbo = -1, cover = -1, frameW, frameH;
    private static boolean failed;
    private static final int MAX = 12;
    private static final int[] LEVEL = new int[MAX + 1], LEVEL_FBO = new int[MAX + 1], LEVEL_W = new int[MAX + 1], LEVEL_H = new int[MAX + 1];
    private static final int[] VIEWPORT = new int[4];
    private static final float[] ZERO = new float[4];
    /** Время проходов свечения на видеокарте (с проходом излучения) и уровни — для строки сценария. */
    private static final GpuTimer GPU = new GpuTimer();
    /** Части: проход излучения, пирамида, вывод на кадр. */
    private static final GpuTimer PASS = new GpuTimer(), PYRAMID = new GpuTimer(), OUT = new GpuTimer();
    private static int lastFirst, lastLast;

    static {
        java.util.Arrays.fill(LEVEL, -1);
        java.util.Arrays.fill(LEVEL_FBO, -1);
    }

    /**
     * После квадратов слоя (шейдер слоя {@code fx} — текущий, состояние смешивания — его): квадраты от самого дальнего
     * светящегося — ещё раз, в половину кадра, проходом свечения ({@code FxGlowPass}: излучение и доля светящегося), потом
     * пирамида и ореол на кадр. Ничего не светится, шейдеры не загрузились, рамку не завести — ничего.
     */
    static void draw(FxQuads quads, ShaderInstance fx, Matrix4f viewProjection) {
        if (!quads.glows() || failed || down == null || up == null || out == null) return;
        int frame = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        if (w <= 0 || h <= 0 || !target(w, h)) return;
        int g = glowLevel(h);
        // излучение и доля светящегося — только в рамке светящихся на экране: вне её дым ничего не гасит (там нуль)
        if (!scissor(quads.glowBox(), viewProjection, LEVEL_W[g], LEVEL_H[g], RECT)) return;
        int timed = GPU.begin(), pass = PASS.begin();
        // излучение и доля светящегося — тем же шейдером и смешиванием, на уровне g, без аппаратной глубины (что за
        // миром — по расстоянию до мира в шейдере)
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._viewport(0, 0, LEVEL_W[g], LEVEL_H[g]);
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO);
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, ZERO);
        fx.safeGetUniform("FxGlowPass").set(1f, (float) w / LEVEL_W[g]);
        RenderSystem.enableScissor(RECT[0], RECT[1], RECT[2], RECT[3]);
        boolean any = quads.drawGlow(fx);
        RenderSystem.disableScissor();
        fx.safeGetUniform("FxGlowPass").set(0f, 1f);
        PASS.end(pass);
        if (any) halo(frame, w, h);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        // как у слоя после квадратов
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        GPU.end(timed);
    }

    private static final int[] RECT = new int[4];
    private static final Vector4f CORNER = new Vector4f();

    /**
     * Прямоугольник в пикселях цели w×h, куда ложится рамка box (от камеры) в матрице viewProjection, с запасом в два
     * пикселя: x, y, ширина, высота — в rect. Угол рамки у глаза или за ним — весь кадр. false — рамка вне кадра.
     */
    static boolean scissor(float[] box, Matrix4f viewProjection, int w, int h, int[] rect) {
        double x0 = 1, y0 = 1, x1 = -1, y1 = -1;
        for (int i = 0; i < 8; i++) {
            viewProjection.transform(CORNER.set(box[(i & 1) == 0 ? 0 : 3], box[(i & 2) == 0 ? 1 : 4], box[(i & 4) == 0 ? 2 : 5], 1));
            if (CORNER.w < 1e-3f) {
                x0 = y0 = -1;
                x1 = y1 = 1;
                break;
            }
            x0 = Math.min(x0, CORNER.x / CORNER.w);
            y0 = Math.min(y0, CORNER.y / CORNER.w);
            x1 = Math.max(x1, CORNER.x / CORNER.w);
            y1 = Math.max(y1, CORNER.y / CORNER.w);
        }
        int px0 = (int) Math.max(0, Math.floor((x0 * 0.5 + 0.5) * w) - 2), py0 = (int) Math.max(0, Math.floor((y0 * 0.5 + 0.5) * h) - 2);
        int px1 = (int) Math.min(w, Math.ceil((x1 * 0.5 + 0.5) * w) + 2), py1 = (int) Math.min(h, Math.ceil((y1 * 0.5 + 0.5) * h) + 2);
        rect[0] = px0;
        rect[1] = py0;
        rect[2] = px1 - px0;
        rect[3] = py1 - py0;
        return rect[2] > 0 && rect[3] > 0;
    }

    /**
     * Пирамида из излучения (уровень {@link #glowLevel}) и ореол на кадр frame размера w×h. Проходов шестнадцать, зависимых
     * друг от друга, и цена у них — не пиксели, а сама цепочка: шейдер применяется раз на серию, между проходами меняются
     * только цель, текстура и шаг, четырёхугольник — постоянный треугольник во весь кадр (у 2560×1600 с выводом — 0,36–0,41 мс
     * против 0,40–0,51 с подготовкой на каждый проход, замер с glFinish).
     */
    private static void halo(int frame, int w, int h) {
        int g = glowLevel(h), first = first(h), last = last(w, h);
        lastFirst = first;
        lastLast = last;
        int timed = PYRAMID.begin();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        triangle();
        // вниз: уровень k из k − 1
        begin(down, LEVEL[g]);
        for (int k = g + 1; k <= last; k++) pass(down, k, LEVEL[k - 1], LEVEL_W[k - 1], LEVEL_H[k - 1]);
        // вверх с прибавлением: уровень k — свой спуск плюс шатёр из k + 1
        begin(up, LEVEL[last]);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        for (int k = last - 1; k >= first; k--) pass(up, k, LEVEL[k + 1], LEVEL_W[k + 1], LEVEL_H[k + 1]);
        // мельче первого уровня — только шатром, без своего спуска (у пака lod 0–1 в ореол не входят), до половины кадра:
        // растянутый вчетверо линейно уровень у источника некруглый (~10 %)
        RenderSystem.disableBlend();
        for (int k = first - 1; k >= 1; k--) pass(up, k, LEVEL[k + 1], LEVEL_W[k + 1], LEVEL_H[k + 1]);
        PYRAMID.end(timed);
        // на кадр: вокруг светящегося, кривая и «экран»
        int shown = OUT.begin();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR, GlStateManager.SourceFactor.ZERO,
                GlStateManager.DestFactor.ONE);
        ShaderInstance s = out;
        s.setSampler("Sampler1", cover);
        s.safeGetUniform("BloomStep").set(1f / LEVEL_W[1], 1f / LEVEL_H[1], (float) (SHARE / (last - first + 1)), 0f);
        s.safeGetUniform("BloomFrom").set((float) VIEWPORT[0], (float) VIEWPORT[1]);
        s.safeGetUniform("BloomCover").set(1f / ((1 << g) * LEVEL_W[g]), 1f / ((1 << g) * LEVEL_H[g]));
        begin(s, LEVEL[1]);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        s.clear();
        // свой массив вершин был привязан мимо BufferUploader: его привязку он помнит и не повторил бы
        GlStateManager._glBindVertexArray(0);
        BufferUploader.invalidate();
        OUT.end(shown);
    }

    /** Применить шейдер s с текстурой src в Sampler0 (серия проходов дальше меняет только цель, текстуру и шаг). */
    private static void begin(ShaderInstance s, int src) {
        s.setSampler("Sampler0", src);
        s.apply();
    }

    /** Проход в уровень k применённым шейдером s из текстуры src размера sw×sh. */
    private static void pass(ShaderInstance s, int k, int src, int sw, int sh) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, LEVEL_FBO[k]);
        GlStateManager._viewport(0, 0, LEVEL_W[k], LEVEL_H[k]);
        com.mojang.blaze3d.shaders.Uniform step = s.getUniform("BloomStep");
        if (step != null) {
            step.set(1f / sw, 1f / sh, 0f, 0f);
            step.upload();
        }
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(src);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    /** Свой массив вершин: треугольник во весь кадр (−1, −1), (3, −1), (−1, 3) — без шва по диагонали. */
    private static int triangle = -1;

    private static void triangle() {
        if (triangle >= 0) {
            GlStateManager._glBindVertexArray(triangle);
            return;
        }
        triangle = GlStateManager._glGenVertexArrays();
        GlStateManager._glBindVertexArray(triangle);
        int buffer = GlStateManager._glGenBuffers();
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, new float[]{-1, -1, 0, 3, -1, 0, -1, 3, 0}, GL15.GL_STATIC_DRAW);
        // место Position — первое: шейдеры проходов собраны с форматом POSITION
        GlStateManager._enableVertexAttribArray(0);
        GlStateManager._vertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 12, 0);
    }

    /**
     * Уровни пирамиды кадра w×h (R11F_G11F_B10F, линейная выборка, край — «по краю»; уровень {@link #glowLevel} —
     * излучение прохода свечения) и доля светящегося (R8, того же размера); false — не завести.
     */
    private static boolean target(int w, int h) {
        if (fbo >= 0 && frameW == w && frameH == h) return true;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean ok = true;
        for (int k = 1; k <= MAX; k++) {
            LEVEL_W[k] = size(w, k);
            LEVEL_H[k] = size(h, k);
            LEVEL[k] = texture(LEVEL[k], LEVEL_W[k], LEVEL_H[k], GL30.GL_R11F_G11F_B10F);
            if (LEVEL_FBO[k] < 0) LEVEL_FBO[k] = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, LEVEL_FBO[k]);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, LEVEL[k], 0);
            ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        }
        int g = glowLevel(h);
        cover = texture(cover, LEVEL_W[g], LEVEL_H[g], GL30.GL_R8);
        if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, LEVEL[g], 0);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GlConst.GL_TEXTURE_2D, cover, 0);
        GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
        ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        frameW = w;
        frameH = h;
        if (!ok) {
            Incoming.LOG.error("Effects bloom: targets for {}×{} not created, no bloom", w, h);
            failed = true;
        }
        return ok;
    }

    private static int texture(int id, int w, int h, int format) {
        if (id < 0) {
            id = GlStateManager._genTexture();
            GlStateManager._bindTexture(id);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(id);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, format, w, h, 0, GL11.GL_RGB, GL11.GL_FLOAT, null);
        return id;
    }

    /** Раз в секунду (тик слоя). */
    static void second() {
        GPU.second();
        PASS.second();
        PYRAMID.second();
        OUT.second();
    }

    /** Для строки сценария: время проходов свечения и уровни. */
    static String describe() {
        return String.format(Locale.ROOT, "bloom %.3f ms (longest %.3f, %d frames; glow pass %.3f, pyramid %.3f, out %.3f; %dx%d, levels %d-%d)",
                GPU.lastMean, GPU.lastMax, GPU.lastFrames, PASS.lastMean, PYRAMID.lastMean, OUT.lastMean, frameW, frameH, lastFirst, lastLast);
    }
}
