package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.ProgramManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;

import java.util.Arrays;
import java.util.Locale;

/**
 * Крупные клубы дыма — в кадре мельче полного. Цена слоя в налёте — заливка: клуб, что закрывает экран, стоит ~0,4 мс
 * на экран (2560×1600, RTX 4060), а внутри дыма десятков попаданий их 30–180 экранов на кадр (замер PF2 24.10). Но такой
 * клуб растянут: кадр запечённого клуба — 128 текселей поперёк, и на экране тексель занимает десятки пикселей. Свою
 * картинку клуб отдаёт и в кадре мельче: от 3 пикселей экрана на тексель — в 2 раза, от 7 — в 4, от 14 — в 8
 * ({@link #PER_TEXEL}; кадры {@code puff6} так, с билинейным увеличением назад, расходятся с полным кадром в среднем
 * не больше чем на 1/255 — {@code CoarsePassTest}). Так же — пелена ядерки ({@link PallPass}): кадр мельче и вывод с весом по
 * сходству расстояния до мира.
 * <p>
 * Порядок слоя (от дальних к ближним) неприкосновенен: смешивание с умноженной альфой ассоциативно, и подряд идущие
 * квадраты можно сложить в своём кадре (с нуля, тем же смешиванием) и положить на кадр одним выводом — итог тот же, что
 * у них по одному. Поэтому отсортированные квадраты делятся на отрезки ({@link #plan}): у отрезка один уровень — не
 * мельче, чем позволяет любой его квадрат; отрезок мельче полного — свой кадр и вывод ({@code fx_coarse}), у которого
 * своя цена ({@link #RUN}). Пока клубы дёшевы (меньше {@link #GATE} экранов), всё — в полном кадре, как прежде.
 * <p>
 * В мелком кадре аппаратной глубины нет: что за миром, отбрасывает шейдер слоя по расстоянию до мира (как у прохода
 * свечения), в решётке, через которую вывод потом и берёт пиксели (пиксель t кадра в f раз мельче смотрит в пиксель
 * floor((t + ½)·f) полного, {@code pallLattice}). Пиксель полного кадра, у которого ни один из четырёх соседей мелкого
 * кадра не то же место (прут, решётка, провод тоньше пикселя мелкого кадра), мелкий кадр не разрешает: вывод его не
 * трогает, маска ({@code fx_coarse}, {@code CoarseMask}) его отмечает, и квадраты отрезка досчитывают его в полном кадре
 * с аппаратной глубиной ({@code FxCoarseFill}: остальное шейдер слоя отбрасывает до света) — дым перед прутом ложится
 * на прут, за ним — нет, как без мелкого кадра.
 */
public final class CoarsePass {
    /** Уровни: 0 — полный кадр, k — в 2^k раз мельче. */
    static final int LEVELS = 4;
    /** Пикселей экрана на тексель картинки квадрата, с которых он может идти уровнем k (k ≥ 1). */
    static final float[] PER_TEXEL = {0, 3, 7, 14};
    /** Заливка квадратов, что могут идти мельче (экранов): меньше — всё в полном кадре. */
    static final float GATE = 4;
    /**
     * Цена отрезка мелкого кадра сверх его клубов, в экранах заливки клубом: чистка его кадра и вывод на полный (~4 выборки
     * кадра и 9 — расстояния до мира на пиксель, порядка одного клуба в рамке отрезка; оценка с запасом — во весь экран).
     */
    static final float RUN = 1;
    /** Отрезков не больше: иначе — дороже цена отрезка ({@link #plan}). */
    static final int MAX_RUNS = 12;

    @Nullable
    static ShaderInstance composite;
    /** Всё в полном кадре (песочница: {@code coarse off} — тот же кадр без мелких отрезков, для сравнения). */
    public static volatile boolean off;
    private static final int[] TEXTURES = {-1, -1, -1, -1}, FBOS = {-1, -1, -1, -1}, W = new int[LEVELS], H = new int[LEVELS];
    /**
     * Пересвет мелкого кадра уровня k (R32F, второй выход шейдера слоя): свет квадратов выше их непрозрачности, сложенный
     * смешиванием слоя. Восьмибитный кадр обрезает свет после каждого смешивания, RGBA32F — нет; где обрезка изменила бы
     * пиксель больше чем на 1/255 (по пересвету и кадру под отрезком, {@code fx_coarse}), пиксель — досчёту.
     */
    private static final int[] BRIGHT = {-1, -1, -1, -1};
    /** Маска полного кадра (R8): пиксели, которые мелкий кадр не разрешает, — их досчитывает полный кадр. */
    private static int mask = -1, maskFbo = -1;
    /**
     * Блок текстуры маски у шейдера слоя: за блоками 12–15 (шум пелены, окно воздуха, сетка дыма, шар; OpenGL 3.2 даёт
     * не меньше 48). Сам шейдер слоя её не держит: досчёт ставит её на время своего прохода.
     */
    static final int MASK_UNIT = 16;
    private static int fullW, fullH;
    private static boolean failed;
    /** Расстояние до мира кадра ({@link SceneDepth}); −1 — его нет, мелких кадров нет. */
    private static int scene = -1;
    private static final int[] VIEWPORT = new int[4];
    private static final float[] ZERO = {0, 0, 0, 0};
    /** Сводка за секунду: кадров с мелкими отрезками, отрезков, заливка клубов уровня k (экранов) — для строки сценария. */
    private static int frames, runs, lastFrames, lastRuns;
    private static final double[] FILL = new double[LEVELS], LAST_FILL = new double[LEVELS];

    private CoarsePass() {}

    /** Кадр слоя: расстояние до мира ({@link SceneDepth#update}; −1 — нет, всё в полном кадре). */
    static void frame(int sceneTexture) {
        scene = sceneTexture;
    }

    /** Мелкие кадры можно рисовать: есть шейдер вывода и расстояние до мира, кадры заводились. */
    static boolean ready() {
        return composite != null && scene >= 0 && !failed && !off;
    }

    /** Уровень квадрата по пикселям экрана на тексель его картинки: самый мелкий кадр, что её не теряет. */
    static int level(double pixelsPerTexel) {
        int k = 0;
        while (k + 1 < LEVELS && pixelsPerTexel >= PER_TEXEL[k + 1]) k++;
        return k;
    }

    /**
     * Отрезки отсортированных квадратов: {@code level[i]} — самый мелкий уровень квадрата i, {@code cover[i]} — доля кадра
     * под ним. Уровень квадрата — не мельче своего; цена — заливка квадрата ÷ 4^уровень, у отрезка мелкого кадра — ещё
     * {@code run}; наименьшая сумма — по Витерби. Квадраты, что могут идти мельче, вместе дешевле {@link #GATE} — один
     * отрезок полного кадра. В {@code from} — начало отрезка, в {@code at} — его уровень; вернёт число отрезков.
     */
    static int plan(int n, byte[] level, float[] cover, int[] from, byte[] at) {
        double coarse = 0;
        for (int i = 0; i < n; i++) if (level[i] > 0) coarse += cover[i];
        if (n == 0 || coarse < GATE) {
            from[0] = 0;
            at[0] = 0;
            return 1;
        }
        for (float run = RUN; ; run *= 2) {
            int count = viterbi(n, level, cover, run, from, at);
            if (count <= MAX_RUNS) return count;
        }
    }

    private static byte[] back = new byte[0];

    private static int viterbi(int n, byte[] level, float[] cover, float run, int[] from, byte[] at) {
        if (back.length < n * LEVELS) back = new byte[Math.max(n * LEVELS, back.length * 2)];
        double[] cost = new double[LEVELS], next = new double[LEVELS];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        // до первого квадрата — «отрезок» полного кадра без цены
        cost[0] = 0;
        for (int i = 0; i < n; i++) {
            for (int l = 0; l < LEVELS; l++) {
                next[l] = Double.POSITIVE_INFINITY;
                if (l > level[i]) continue;
                for (int a = 0; a < LEVELS; a++) {
                    double v = cost[a];
                    // кончился отрезок мелкого кадра — его вывод
                    if (a != l && a > 0) v += run;
                    // при равной цене — тот же уровень: квадрат без заливки (за глазом) не начинает отрезок
                    if (v < next[l] || v == next[l] && a == l) {
                        next[l] = v;
                        back[i * LEVELS + l] = (byte) a;
                    }
                }
                next[l] += cover[i] / (1 << 2 * l);
            }
            double[] t = cost;
            cost = next;
            next = t;
        }
        int l = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int a = 0; a < LEVELS; a++) {
            double v = cost[a] + (a > 0 ? run : 0);
            // при равной цене — уровень последнего отрезка, а не новый полный в конце (квадрат без заливки последним)
            if (v <= best) {
                best = v;
                l = a;
            }
        }
        // назад: уровни квадратов, отрезки — где уровень меняется
        int count = 0;
        for (int i = n - 1; i >= 0; i--) {
            int a = back[i * LEVELS + l];
            if (i == 0 || a != l) {
                from[count] = i;
                at[count] = (byte) l;
                count++;
            }
            l = a;
            if (count > MAX_RUNS) return count;
        }
        for (int i = 0, j = count - 1; i < j; i++, j--) {
            int f = from[i];
            from[i] = from[j];
            from[j] = f;
            byte b = at[i];
            at[i] = at[j];
            at[j] = b;
        }
        return count;
    }

    /**
     * Квадраты {@code [a, b)} кадра {@code quads} — в кадре уровня k и выводом на текущий: рамка {@code box} (NDC: x0, y0,
     * x1, y1) — где они на экране. Шейдер слоя s — уже со всеми текстурами и смешиванием слоя.
     */
    static void draw(FxQuads quads, ShaderInstance s, int a, int b, int k, float[] box) {
        ShaderInstance c = composite;
        int frame = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), under = color(frame);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int w = VIEWPORT[0] + VIEWPORT[2], h = VIEWPORT[1] + VIEWPORT[3];
        if (c == null || w <= 0 || h <= 0 || !target(w, h)) {
            quads.range(s, a, b);
            return;
        }
        int f = 1 << k;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, FBOS[k]);
        GlStateManager._viewport(0, 0, W[k], H[k]);
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO);
        GL30.glClearBufferfv(GL11.GL_COLOR, 1, ZERO);
        // y — во сколько раз пиксель крупнее; x = 0 — цвет, не свечение (fx.fsh: что за миром — отброшено). Кадр — с
        // округлением вверх, поэтому пиксель мелкого кадра — w / W полных по x и h / H по y (не ровно f, если кадр не
        // кратен f): той же долей шейдер слоя ищет расстояние до мира, а вывод — пиксели мелкого кадра
        float sx = (float) w / W[k], sy = (float) h / H[k];
        s.safeGetUniform("FxGlowPass").set(0f, (float) f);
        s.safeGetUniform("FxCoarseScale").set(sx, sy);
        quads.range(s, a, b);
        s.safeGetUniform("FxGlowPass").set(0f, 1f);
        s.safeGetUniform("FxCoarseScale").set(1f, 1f);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
        GlStateManager._viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        // вывод — в рамке отрезка (с запасом в пиксель мелкого кадра и соседей вывода)
        int margin = 2 * f + 2;
        int x0 = Math.max(0, (int) Math.floor((box[0] * 0.5 + 0.5) * w) - margin), y0 = Math.max(0, (int) Math.floor((box[1] * 0.5 + 0.5) * h) - margin);
        int x1 = Math.min(w, (int) Math.ceil((box[2] * 0.5 + 0.5) * w) + margin), y1 = Math.min(h, (int) Math.ceil((box[3] * 0.5 + 0.5) * h) + margin);
        if (x1 > x0 && y1 > y0) {
            RenderSystem.disableDepthTest();
            GlStateManager._enableScissorTest();
            GlStateManager._scissorBox(x0, y0, x1 - x0, y1 - y0);
            c.setSampler("Sampler0", TEXTURES[k]);
            c.setSampler("Sampler1", scene);
            c.setSampler("Sampler2", BRIGHT[k]);
            c.safeGetUniform("CoarseScale").set(sx, sy);
            // маска: пиксели, которых мелкий кадр не разрешает (их расстояние до мира — ни у одного из четырёх соседей, или
            // обрезка пересвета по кадру под отрезком): кадр ещё без отрезка, читать его можно — пишется маска. Кадра нет
            // текстурой — он считается белым (без него в Sampler3 — расстояние до мира, не маска: её пишет этот проход)
            c.setSampler("Sampler3", under >= 0 ? under : scene);
            c.safeGetUniform("CoarseFrame").set(under >= 0 ? 1f : 0f);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, maskFbo);
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO);
            c.safeGetUniform("CoarseMask").set(1f);
            c.apply();
            FxBloom.triangle();
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            c.clear();
            // вывод — в остальные, по маске
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, frame);
            c.setSampler("Sampler3", mask);
            c.safeGetUniform("CoarseMask").set(0f);
            c.apply();
            FxBloom.triangle();
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            c.clear();
            // свой массив вершин был привязан мимо BufferUploader: его привязку он помнит и не повторил бы
            GlStateManager._glBindVertexArray(0);
            BufferUploader.invalidate();
            RenderSystem.enableDepthTest();
            // досчёт: квадраты отрезка в полном кадре с аппаратной глубиной — только в пикселях маски (в рамке)
            // маска — на своём блоке, мимо ShaderInstance: ваниль ведёт блоки 0–11, а у шейдера слоя заняты все двенадцать
            int unit = GlStateManager._getActiveTexture();
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MASK_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, mask);
            GlStateManager._activeTexture(unit);
            int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            ProgramManager.glUseProgram(s.getId());
            Uniform.uploadInteger(Uniform.glGetUniformLocation(s.getId(), "FxCoarseMask"), MASK_UNIT);
            ProgramManager.glUseProgram(program);
            s.safeGetUniform("FxCoarseFill").set(1f);
            quads.range(s, a, b);
            s.safeGetUniform("FxCoarseFill").set(0f);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + MASK_UNIT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            GlStateManager._activeTexture(unit);
            GlStateManager._disableScissorTest();
        }
        runs++;
    }

    /** Текстура цвета кадра {@code frame} (куда он рисует), −1 — не текстура (кадр окна, рендербуфер). */
    private static int color(int frame) {
        if (frame == 0) return -1;
        int buffer = GL11.glGetInteger(GL20.GL_DRAW_BUFFER0);
        if (buffer < GL30.GL_COLOR_ATTACHMENT0 || buffer > GL30.GL_COLOR_ATTACHMENT15) return -1;
        if (GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, buffer, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) != GL11.GL_TEXTURE)
            return -1;
        return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, buffer, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    /** Кадр слоя с отрезками: заливка клубов, что шли уровнем k (экранов), — в сводку. */
    static void counted(double[] fill) {
        frames++;
        for (int k = 0; k < LEVELS; k++) FILL[k] += fill[k];
    }

    /** Сторона кадра уровня k у стороны полного w: с округлением вверх (пиксель мелкого — w / size полных, не 2^k). */
    static int size(int w, int k) {
        return Math.max(1, (w + (1 << k) - 1) >> k);
    }

    /**
     * Кадры уровней 1…3 для кадра w×h (RGBA32F: свет с умноженной альфой и закрытая доля); false — не завести. Полная
     * точность, не половинная: у RGBA16F бледные слои (сотни клубов с альфой меньше сотой) расходятся с законом
     * смешивания на единицы 1/255, как и в восьмибитном кадре (ревью #455), а цена слоя — свет клуба, не запись кадра.
     */
    private static boolean target(int w, int h) {
        if (TEXTURES[1] >= 0 && w == fullW && h == fullH) return true;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean ok = true;
        for (int k = 1; k < LEVELS; k++) {
            W[k] = size(w, k);
            H[k] = size(h, k);
            if (TEXTURES[k] < 0) {
                TEXTURES[k] = GlStateManager._genTexture();
                GlStateManager._bindTexture(TEXTURES[k]);
                GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
                GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
                GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
                GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            }
            GlStateManager._bindTexture(TEXTURES[k]);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, W[k], H[k], 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
            if (BRIGHT[k] < 0) {
                BRIGHT[k] = GlStateManager._genTexture();
                GlStateManager._bindTexture(BRIGHT[k]);
                GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
                GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
                GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
                GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
            }
            GlStateManager._bindTexture(BRIGHT[k]);
            GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_R32F, W[k], H[k], 0, GL11.GL_RED, GL11.GL_FLOAT, null);
            if (FBOS[k] < 0) FBOS[k] = GlStateManager.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, FBOS[k]);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, TEXTURES[k], 0);
            GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT1, GlConst.GL_TEXTURE_2D, BRIGHT[k], 0);
            GL20.glDrawBuffers(new int[]{GL30.GL_COLOR_ATTACHMENT0, GL30.GL_COLOR_ATTACHMENT1});
            ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        }
        if (mask < 0) {
            mask = GlStateManager._genTexture();
            GlStateManager._bindTexture(mask);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        }
        GlStateManager._bindTexture(mask);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_R8, w, h, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, null);
        if (maskFbo < 0) maskFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, maskFbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, mask, 0);
        ok &= GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        fullW = w;
        fullH = h;
        if (!ok) {
            Incoming.LOG.error("Effects layer: coarse smoke targets for {}×{} not created, all smoke at full resolution", w, h);
            failed = true;
        }
        return ok;
    }

    /** Раз в секунду (тик слоя). */
    static void second() {
        lastFrames = frames;
        lastRuns = runs;
        System.arraycopy(FILL, 0, LAST_FILL, 0, LEVELS);
        frames = runs = 0;
        Arrays.fill(FILL, 0);
    }

    /** Для строки сценария: кадров с мелкими отрезками за секунду, отрезков на кадр и заливка клубов по уровням. */
    static String describe() {
        int n = Math.max(lastFrames, 1);
        return String.format(Locale.ROOT, "coarse smoke: %d frames, %.1f coarse runs, puff fill per frame %.1f / %.1f / %.1f / %.1f screens at 1, 1/2, 1/4, 1/8",
                lastFrames, (double) lastRuns / n, LAST_FILL[0] / n, LAST_FILL[1] / n, LAST_FILL[2] / n, LAST_FILL[3] / n);
    }
}
