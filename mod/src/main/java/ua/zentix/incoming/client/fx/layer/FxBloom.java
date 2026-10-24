package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
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
 * <li>Излучение. Слой пишет вторую цель ({@code fx.fsh}, {@code fragGlow}, R11F_G11F_B10F во весь кадр) тем же
 * смешиванием, что и цвет ({@code ONE, ONE_MINUS_SRC_ALPHA}): дым перед огнём гасит его излучение той же долей, а что
 * за стеной и рельефом, отсекает та же глубина (один фрагмент — два выхода) и тот же мягкий край у мира. Свет —
 * линейный, белый экран — 1: у кадров огня — обратная плёночная кривая запекания ({@link #filmInverse}, кадр
 * показывает огонь через неё), у ядер, вспышек и факела — показанный свет, умноженный на срезанный экраном пересвет
 * ({@link FxQuads#emission}), не ярче белой точки кривой {@link #WHITE}: дальше неё кадр различий не показывает.</li>
 * <li>Пирамида — двойной фильтр Jimenez 2014 (Call of Duty: Advanced Warfare; так же — Unity URP, Blender EEVEE):
 * вниз — 13 выборок, вверх — шатёр 3×3 с прибавлением уровня. Обе свёртки сохраняют энергию, и каждый уровень несёт
 * всю энергию излучения: равные веса уровней — равная энергия на октаву угла, то есть спад 1/θ², как у функции
 * рассеяния глаза CIE (Vos 1984, CIE 135/1999: L/E = 10/θ², θ в градусах). Уровни — как у пака: 2–8 при высоте
 * кадра 1080 (2^k пикселей — ~0,2°…17° при поле зрения 70°), выше — на октаву глубже ({@link #first}).</li>
 * <li>Доля {@link #SHARE} без порога — как у пака по умолчанию; у глаза по CIE на углах 0,1°–50° выходит 0,12
 * (0,0191·ln(θ₂/θ₁)). Источник свою долю не теряет (кадр огня уже показан своей кривой; у пака она уходит в ореол —
 * у середины огня это тени на проценты), ореол прибавляется: кривая 1 − e^(−x) и смешивание «экраном»
 * ({@code ONE, ONE_MINUS_SRC_COLOR}) — ровно сложение света до этой кривой.</li>
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
    /** Белая точка плёночной кривой: свет, который она показывает белым (≈ 7,24). */
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
     * Ореол излучения e во весь кадр — свет до кривой (без доли {@link #SHARE}): уровни {@link #first} … {@link #last}
     * с равными весами, как в проходах; сумма по кадру — сумма излучения (энергия сохраняется).
     */
    static Image halo(Image e) {
        int first = first(e.h), last = last(e.w, e.h);
        Image[] level = new Image[last + 1];
        level[0] = e;
        for (int k = 1; k <= last; k++) level[k] = down(level[k - 1]);
        Image up = level[last];
        for (int k = last - 1; k >= first; k--) {
            Image t = tent(up, level[k].w, level[k].h);
            for (int i = 0; i < t.v.length; i++) t.v[i] += level[k].v[i];
            up = t;
        }
        for (int k = first - 1; k >= 1; k--) up = tent(up, level[k].w, level[k].h);
        Image out = Image.of(e.w, e.h);
        double scale = 1.0 / (last - first + 1);
        for (int y = 0; y < e.h; y++) {
            for (int x = 0; x < e.w; x++) out.v[y * e.w + x] = (float) (up.sample((x + 0.5) / 2, (y + 0.5) / 2) * scale);
        }
        return out;
    }

    // ---------------------------------------------------------------- проходы

    @Nullable
    static ShaderInstance down, up, out;
    /** Своя рамка кадра: цвет и глубина — привязанного кадра, вторая цель — излучение. */
    private static int fbo = -1, glow = -1, glowW, glowH;
    /** Чем рамка собрана: тип и номер цвета и глубины привязанного кадра (рамку пересобирать, если сменились). */
    private static final int[] BUILT = new int[5];
    private static boolean complete, failed;
    private static final int MAX = 12;
    private static final int[] LEVEL = new int[MAX + 1], LEVEL_FBO = new int[MAX + 1], LEVEL_W = new int[MAX + 1], LEVEL_H = new int[MAX + 1];
    private static final int[] VIEWPORT = new int[4];
    private static final float[] ZERO = new float[4];
    /** Кадр, в который рисует слой (до своей рамки), и рисует ли слой сейчас в свою. */
    private static int frame;
    private static boolean on;
    /** Время проходов свечения на видеокарте (без самого слоя) и размер кадра — для строки сценария. */
    private static final GpuTimer GPU = new GpuTimer();
    private static int lastFirst, lastLast;

    static {
        java.util.Arrays.fill(LEVEL, -1);
        java.util.Arrays.fill(LEVEL_FBO, -1);
    }

    /**
     * Перед квадратами слоя: кадр со свечением рисуется в свою рамку — цвет и глубина привязанного кадра и цель
     * излучения (очищенная). false — свечения нет (в кадре ничего не светится, шейдеры не загрузились, рамку не собрать,
     * привязан кадр окна): слой рисует как раньше.
     */
    static boolean begin(boolean glows) {
        on = false;
        if (!glows || failed || down == null || up == null || out == null) return false;
        frame = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (frame == 0) return false;
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        if (w <= 0 || h <= 0) return false;
        // цвет, в который рисует кадр (его первый буфер вывода), и его глубина
        int colour = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
        if (colour < GL30.GL_COLOR_ATTACHMENT0 || colour > GL30.GL_COLOR_ATTACHMENT0 + 15) return false;
        int colourType = attachment(colour, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE), colourName = attachment(colour, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int depthType = attachment(GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int depthName = depthType == GL11.GL_NONE ? 0 : attachment(GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (colourType != GL11.GL_TEXTURE && colourType != GL30.GL_RENDERBUFFER) return false;
        if (!target(w, h)) return false;
        if (fbo < 0 || BUILT[0] != colourType || BUILT[1] != colourName || BUILT[2] != depthType || BUILT[3] != depthName || BUILT[4] != glow) {
            if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            attach(GL30.GL_COLOR_ATTACHMENT0, colourType, colourName);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GlConst.GL_TEXTURE_2D, glow, 0);
            attach(GL30.GL_DEPTH_ATTACHMENT, depthType, depthName);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
            complete = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
            BUILT[0] = colourType;
            BUILT[1] = colourName;
            BUILT[2] = depthType;
            BUILT[3] = depthName;
            BUILT[4] = glow;
            if (!complete) Incoming.LOG.warn("Effects bloom: frame {}×{} with the glow target is not complete, no bloom in this frame", w, h);
        } else {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        }
        if (!complete) {
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
            return false;
        }
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, ZERO);
        on = true;
        return true;
    }

    /**
     * После квадратов слоя: кадр — снова прежний, пирамида излучения и ореол на кадр. Состояние смешивания и глубины —
     * как у слоя после квадратов.
     */
    static void end() {
        if (!on) return;
        on = false;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        int timed = GPU.begin();
        int w = glowW, h = glowH, first = first(h), last = last(w, h);
        lastFirst = first;
        lastLast = last;
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        // вниз: уровень k из k − 1 (0 — само излучение)
        for (int k = 1; k <= last; k++) pass(down, k, k == 1 ? glow : LEVEL[k - 1], k == 1 ? w : LEVEL_W[k - 1], k == 1 ? h : LEVEL_H[k - 1]);
        // вверх с прибавлением: уровень k — свой спуск плюс шатёр из k + 1
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        for (int k = last - 1; k >= first; k--) pass(up, k, LEVEL[k + 1], LEVEL_W[k + 1], LEVEL_H[k + 1]);
        // мельче первого уровня — только шатром, без своего спуска (у пака lod 0–1 в ореол не входят)
        RenderSystem.disableBlend();
        for (int k = first - 1; k >= 1; k--) pass(up, k, LEVEL[k + 1], LEVEL_W[k + 1], LEVEL_H[k + 1]);
        // на кадр: кривая и «экран»
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR, GlStateManager.SourceFactor.ZERO,
                GlStateManager.DestFactor.ONE);
        ShaderInstance s = out;
        s.safeGetUniform("BloomStep").set(1f / LEVEL_W[1], 1f / LEVEL_H[1], (float) (SHARE / (last - first + 1)), 0f);
        s.safeGetUniform("BloomFrom").set((float) VIEWPORT[0], (float) VIEWPORT[1]);
        RenderSystem.setShaderTexture(0, LEVEL[1]);
        quad(s);
        // как у слоя после квадратов
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableDepthTest();
        GPU.end(timed);
    }

    /** Проход в уровень k шейдером s из текстуры src размера sw×sh. */
    private static void pass(ShaderInstance s, int k, int src, int sw, int sh) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, LEVEL_FBO[k]);
        GlStateManager._viewport(0, 0, LEVEL_W[k], LEVEL_H[k]);
        s.safeGetUniform("BloomStep").set(1f / sw, 1f / sh, 0f, 0f);
        RenderSystem.setShaderTexture(0, src);
        quad(s);
    }

    /** Четырёхугольник во весь привязанный кадр шейдером s. */
    private static void quad(ShaderInstance s) {
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh == null) return;
        s.setDefaultUniforms(VertexFormat.Mode.QUADS, RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(),
                Minecraft.getInstance().getWindow());
        s.apply();
        BufferUploader.draw(mesh);
        s.clear();
    }

    /** Свойство pname вложения att привязанного кадра. */
    private static int attachment(int att, int pname) {
        return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, att, pname);
    }

    private static void attach(int att, int type, int name) {
        if (type == GL11.GL_TEXTURE) {
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, att, GlConst.GL_TEXTURE_2D, name, 0);
        } else if (type == GL30.GL_RENDERBUFFER) {
            GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, att, GL30.GL_RENDERBUFFER, name);
        } else {
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, att, GlConst.GL_TEXTURE_2D, 0, 0);
        }
    }

    /** Цель излучения w×h и уровни пирамиды (R11F_G11F_B10F, линейная выборка, край — «по краю»); false — не завести. */
    private static boolean target(int w, int h) {
        if (glow >= 0 && glowW == w && glowH == h) return true;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        glow = texture(glow, w, h);
        glowW = w;
        glowH = h;
        boolean ok = true;
        for (int k = 1; k <= MAX; k++) {
            LEVEL_W[k] = size(w, k);
            LEVEL_H[k] = size(h, k);
            LEVEL[k] = texture(LEVEL[k], LEVEL_W[k], LEVEL_H[k]);
            if (LEVEL_FBO[k] < 0) LEVEL_FBO[k] = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, LEVEL_FBO[k]);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, LEVEL[k], 0);
            ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        }
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        if (!ok) {
            Incoming.LOG.error("Effects bloom: pyramid for {}×{} not created, no bloom", w, h);
            failed = true;
        }
        return ok;
    }

    private static int texture(int id, int w, int h) {
        if (id < 0) {
            id = GlStateManager._genTexture();
            GlStateManager._bindTexture(id);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        }
        GlStateManager._bindTexture(id);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_R11F_G11F_B10F, w, h, 0, GL11.GL_RGB, GL11.GL_FLOAT, null);
        return id;
    }

    /** Раз в секунду (тик слоя). */
    static void second() {
        GPU.second();
    }

    /** Для строки сценария: время проходов свечения и уровни. */
    static String describe() {
        return String.format(Locale.ROOT, "bloom %.3f ms (longest %.3f, %d frames; %dx%d, levels %d-%d)", GPU.lastMean, GPU.lastMax, GPU.lastFrames,
                glowW, glowH, lastFirst, lastLast);
    }
}
