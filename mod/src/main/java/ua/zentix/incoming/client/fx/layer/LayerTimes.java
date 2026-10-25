package ua.zentix.incoming.client.fx.layer;

import com.mojang.blaze3d.platform.GlConst;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import ua.zentix.incoming.Incoming;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Проба: время слоя эффектов на видеокарте по частям — чтобы причину цены кадра называло число, а не модель.
 * <ul>
 * <li>Проходы слоя ({@link Pass}: атлас моделей, глубина сцены, наклейка на воду, пелена с дымом объёмом, свет и дрожание
 * огня, пробы источников, квадраты, свечение) — метки времени (glQueryCounter) на их границах в каждом кадре, по кольцу
 * из {@link #RING} кадров: читаются через 2–3 кадра, когда готовы, без ожидания (как {@link GpuTimer}). Метка между
 * соседними проходами «протекает»: видеокарта ставит её, не дождавшись хвоста прошлой работы, и он попадает в следующий
 * проход (замер HB2, #442). Среднее таких кадров — для слежки; сумма верна, доли — примерно.</li>
 * <li>Поэтому раз в {@link #DRAIN_EVERY_NS} кадр слива: перед каждой меткой glFinish — работа до неё вся кончилась, и
 * проходы на нём точны. Квадраты — один отсортированный вызов, и части внутри него перемешаны по глубине: такой кадр
 * после общего вызова рисует каждую часть ({@link FxQuads.Part}) ещё раз отдельно, тоже со сливом до и после (число
 * пикселей — запросом GL_SAMPLES_PASSED), а кадр перед этим копируется и потом возвращается: на экране — тот же кадр.
 * Кадр слива стоит ещё одни квадраты, две копии кадра и простои видеокарты между проходами (на стенде при 17 к/с это ~3 %
 * кадров: p50 и p95 не сдвигает); в среднее проходов он не идёт.</li>
 * </ul>
 * Проба идёт, только пока её читают: открыт F3, строку {@link #describe} спрашивали за последние {@link #READ_NS} (стенд,
 * сценарии) или её держат включённой ({@link #always}). Иначе меток нет вовсе (и у {@link GpuTimer} тоже), копии кадра нет.
 */
public final class LayerTimes {
    /**
     * Проходы слоя в порядке кадра; время прохода — от прошлой метки до его. {@link #PROBE} — работа самой пробы на кадре
     * слива (копия кадра, части, кадр назад); в других кадрах его метка — сразу за квадратами, и протёкший в неё хвост
     * квадратов идёт им.
     */
    enum Pass {
        MODELS, DEPTH, WATER, PALL, HEAT, PROBES, QUADS, PROBE, BLOOM;

        final String label = name().toLowerCase(Locale.ROOT);
    }

    private static final int RING = 4, MARKS = Pass.values().length + 1, PASSES = Pass.values().length, PARTS = FxQuads.Part.values().length;
    /** Кадр слива — не чаще. */
    static final long DRAIN_EVERY_NS = 2_000_000_000L;
    /** Проба идёт столько после последнего чтения строки. */
    static final long READ_NS = 5_000_000_000L;

    private static boolean always, on;
    /** Когда строку читали и когда был кадр слива (0 — не было). */
    private static long lastRead, lastDrain;
    /** Метки времени: 0 — не проверяли, 1 — есть, −1 — нет. */
    private static int timer;
    private static final int[][] MARK = new int[RING][MARKS];
    private static final boolean[] ISSUED = new boolean[RING], DRAINED = new boolean[RING];
    /** У кадра слива — место меток его частей в {@link PartClock} (−1 — частей нет). */
    private static final int[] PART_SLOT = new int[RING];
    private static int ring, slot = -1;
    private static final double[] SUM = new double[PASSES], MEAN = new double[PASSES], EXACT = new double[PASSES];
    private static int frames, lastFrames;
    /** Последний прочитанный кадр слива: когда; почему без частей (null — с частями). */
    private static long exactAt;
    private static String partsMissing = "no drained frame yet";

    private static final PartClock CLOCK = new PartClock();
    /** Копия кадра для кадра слива: текстура и её буфер кадра, размер. */
    private static int copy = -1, copyFbo = -1, copyW, copyH;

    private LayerTimes() {}

    /** Пробу держит включённой сам клиент (сценарии и песочница: их строки читают время слоя каждый раз). */
    public static void always() {
        always = true;
    }

    /** Идёт ли проба в этом кадре (её решает {@link #begin}). */
    static boolean on() {
        return on;
    }

    /**
     * Начало кадра слоя (до атласа моделей): решить, идёт ли проба, прочитать готовые метки, поставить первую. true — кадр
     * слива: квадраты ещё и по частям ({@link #parts}).
     */
    static boolean begin() {
        slot = -1;
        long now = System.nanoTime();
        on = always || lastRead != 0 && now - lastRead < READ_NS || Minecraft.getInstance().getDebugOverlay().showDebugScreen();
        if (!on) {
            free();
            return false;
        }
        if (!timer()) return false;
        poll(now);
        if (ISSUED[ring]) return false;
        slot = ring;
        boolean drain = (lastDrain == 0 || now - lastDrain >= DRAIN_EVERY_NS) && CLOCK.free();
        DRAINED[slot] = drain;
        PART_SLOT[slot] = -1;
        if (drain) {
            lastDrain = now;
            // мир кадра — до первой метки: его хвост не в атласе моделей
            GL11.glFinish();
        }
        GL33.glQueryCounter(MARK[slot][0], GL33.GL_TIMESTAMP);
        return drain;
    }

    /** Конец прохода p (на кадре слива — когда его работа вся кончилась). */
    static void mark(Pass p) {
        if (slot < 0) return;
        if (DRAINED[slot]) GL11.glFinish();
        GL33.glQueryCounter(MARK[slot][p.ordinal() + 1], GL33.GL_TIMESTAMP);
    }

    /** Кадр слоя дошёл до конца: метки — в полёт. */
    static void end() {
        if (slot < 0) return;
        ISSUED[slot] = true;
        ring = (slot + 1) % RING;
        slot = -1;
    }

    /**
     * Кадр слива, после общего вызова квадратов и его метки: копия кадра, каждая часть отдельно, кадр назад. Буфер кадра с
     * несколькими отсчётами на пиксель, с ножницами или с несколькими целями копией не вернуть — тогда частей нет.
     */
    static void parts(FxQuads quads, net.minecraft.client.renderer.ShaderInstance s) {
        if (slot < 0 || !DRAINED[slot]) return;
        int x = GlStateManager.Viewport.x(), y = GlStateManager.Viewport.y(), w = GlStateManager.Viewport.width(), h = GlStateManager.Viewport.height();
        String why = w <= 0 || h <= 0 ? "empty viewport" : GL11.glGetInteger(GL13.GL_SAMPLES) > 0 ? "multisampled frame"
                : GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) ? "scissor on" : GL11.glGetInteger(GL20.GL_DRAW_BUFFER1) != GL11.GL_NONE ? "several targets"
                : !copy(w, h) ? "no frame copy" : null;
        if (why != null) {
            partsMissing = why;
            return;
        }
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, draw);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
        GL30.glBlitFramebuffer(x, y, x + w, y + h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        CLOCK.pixels = (double) w * h;
        quads.drawParts(s, CLOCK);
        PART_SLOT[slot] = CLOCK.issue();
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, copyFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, x, y, x + w, y + h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
    }

    /** Раз в секунду: среднее проходов. */
    static void second() {
        for (int i = 0; i < PASSES; i++) {
            MEAN[i] = frames > 0 ? SUM[i] / frames : 0;
            SUM[i] = 0;
        }
        lastFrames = frames;
        frames = 0;
    }

    /** Выход из мира или смена измерения. */
    static void reset() {
        Arrays.fill(SUM, 0);
        Arrays.fill(MEAN, 0);
        Arrays.fill(EXACT, 0);
        frames = lastFrames = 0;
        exactAt = 0;
        partsMissing = "no drained frame yet";
        CLOCK.forget();
    }

    /**
     * Строка пробы (её чтение держит пробу включённой): проходы — среднее за прошлую секунду и точные последнего кадра
     * слива; части квадратов того же кадра — мс на видеокарте, доля экрана, которую часть залила (пиксели после теста
     * глубины, в экранах кадра), квадраты и отрезки в порядке сортировки.
     */
    static String describe() {
        lastRead = System.nanoTime();
        if (timer < 0) return "GPU by pass: no timer queries";
        if (lastFrames == 0 && exactAt == 0) return "GPU by pass: no frames yet (the probe runs while this line or F3 is read)";
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "GPU by pass (ms, mean of %d frames; a pass's tail may spill into the next):",
                lastFrames));
        passes(b, MEAN);
        if (exactAt == 0) return b.append("; drained frame: none yet").toString();
        b.append(String.format(Locale.ROOT, "; drained frame (glFinish at every mark, one in %d s, %.1f s ago):", DRAIN_EVERY_NS / 1_000_000_000L,
                (System.nanoTime() - exactAt) / 1e9));
        passes(b, EXACT);
        b.append(String.format(Locale.ROOT, ", probe itself %.2f; ", EXACT[Pass.PROBE.ordinal()]));
        b.append(partsMissing == null ? CLOCK.describe(EXACT[Pass.QUADS.ordinal()]) : "quads by part: none (" + partsMissing + ")");
        return b.toString();
    }

    /** Проходы без работы самой пробы и их сумма. */
    private static void passes(StringBuilder b, double[] ms) {
        double sum = 0;
        for (Pass p : Pass.values()) {
            if (p == Pass.PROBE) continue;
            b.append(String.format(Locale.ROOT, " %s %.2f", p.label, ms[p.ordinal()]));
            sum += ms[p.ordinal()];
        }
        b.append(String.format(Locale.ROOT, " (sum %.2f)", sum));
    }

    /** Строки F3 (справа): проходы (среднее за секунду) и части квадратов (кадр слива). */
    static void debugLines(List<String> out) {
        if (!on || lastFrames == 0) return;
        out.add("");
        double sum = 0;
        for (double v : MEAN) sum += v;
        out.add(String.format(Locale.ROOT, "Incoming FX GPU %.2f ms (%d frames)", sum, lastFrames));
        StringBuilder b = new StringBuilder();
        for (Pass p : Pass.values()) {
            double v = MEAN[p.ordinal()];
            if (p == Pass.PROBE || v < 0.005 && p != Pass.QUADS) continue;
            wrap(out, b);
            b.append(String.format(Locale.ROOT, "%s %.2f ", p.label, v));
        }
        if (!b.isEmpty()) out.add(b.toString().trim());
        if (exactAt != 0 && partsMissing == null) CLOCK.debugLines(out, EXACT[Pass.QUADS.ordinal()]);
    }

    /** Строка F3 не шире ~45 знаков: длинная — в вывод, дальше с начала. */
    private static void wrap(List<String> out, StringBuilder b) {
        if (b.length() <= 40) return;
        out.add(b.toString().trim());
        b.setLength(0);
    }

    /** Есть ли метки времени OpenGL (GL 3.3 или ARB_timer_query, и счётчик не пустой); запросы заводятся при первой проверке. */
    private static boolean timer() {
        if (timer == 0) {
            GLCapabilities caps = GL.getCapabilities();
            boolean ok = (caps.OpenGL33 || caps.GL_ARB_timer_query) && GL15.glGetQueryi(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) > 0;
            if (ok) {
                for (int[] marks : MARK) for (int i = 0; i < MARKS; i++) marks[i] = GL15.glGenQueries();
                CLOCK.create();
            }
            timer = ok ? 1 : -1;
        }
        return timer > 0;
    }

    /**
     * Готовые кадры меток: обычный — в сумму проходов (метка пробы — квадратам: в ней только протёкший хвост квадратов),
     * кадр слива — в точные проходы, с ним его части: их метки стоят раньше последней метки кадра и уже готовы.
     */
    private static void poll(long now) {
        for (int k = 0; k < RING; k++) {
            if (!ISSUED[k] || GL15.glGetQueryObjecti(MARK[k][MARKS - 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long prev = GL33.glGetQueryObjecti64(MARK[k][0], GL15.GL_QUERY_RESULT);
            for (Pass p : Pass.values()) {
                long t = GL33.glGetQueryObjecti64(MARK[k][p.ordinal() + 1], GL15.GL_QUERY_RESULT);
                double ms = Math.max(0, t - prev) / 1e6;
                prev = t;
                if (DRAINED[k]) EXACT[p.ordinal()] = ms;
                else SUM[(p == Pass.PROBE ? Pass.QUADS : p).ordinal()] += ms;
            }
            if (DRAINED[k]) {
                exactAt = now;
                if (PART_SLOT[k] >= 0) {
                    CLOCK.read(PART_SLOT[k]);
                    partsMissing = null;
                }
            } else {
                frames++;
            }
            ISSUED[k] = false;
        }
    }

    /** Копия кадра w×h (RGBA16F: 8-битный, 10-битный и половинный кадр в ней и обратно — те же числа); false — не завести. */
    private static boolean copy(int w, int h) {
        if (copyFbo >= 0 && copyW == w && copyH == h) return true;
        int draw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (copy < 0) {
            copy = GlStateManager._genTexture();
            GlStateManager._bindTexture(copy);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_NEAREST);
            GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_NEAREST);
        }
        GlStateManager._bindTexture(copy);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
        if (copyFbo < 0) copyFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, copy, 0);
        boolean ok = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        copyW = w;
        copyH = h;
        if (!ok) {
            Incoming.LOG.warn("Effects layer probe: frame copy {}×{} not created, no times by part", w, h);
            free();
        }
        return ok;
    }

    /** Проба выключена: копии кадра нет (32 МБ видеопамяти у 2560×1600). */
    private static void free() {
        if (copyFbo >= 0) GlStateManager._glDeleteFramebuffers(copyFbo);
        if (copy >= 0) GlStateManager._deleteTexture(copy);
        copy = copyFbo = -1;
        copyW = copyH = 0;
    }

    /**
     * Метки частей квадратов на кадре слива ({@link FxQuads#drawParts}): перед первой и после каждой — glFinish, время части
     * — между метками, пиксели — запрос GL_SAMPLES_PASSED вокруг неё (фрагменты, отброшенные в шейдере, в них не входят,
     * хоть время и стоили). Кольцо из двух мест: кадры слива идут раз в {@link #DRAIN_EVERY_NS}, их метки готовы задолго
     * до следующего; читаются вместе с метками проходов своего кадра.
     */
    static final class PartClock {
        private static final int SLOTS = 2;
        private final int[][] marks = new int[SLOTS][PARTS + 1], samples = new int[SLOTS][PARTS];
        private final boolean[] issued = new boolean[SLOTS];
        private final int[][] quads = new int[SLOTS][PARTS], runs = new int[SLOTS][PARTS];
        private final double[] slotPixels = new double[SLOTS];
        private int at;
        double pixels;
        /** Последний прочитанный кадр: мс, экраны, квадраты, отрезки. */
        private final double[] ms = new double[PARTS], screens = new double[PARTS];
        private final int[] lastQuads = new int[PARTS], lastRuns = new int[PARTS];
        private boolean any;

        void create() {
            for (int s = 0; s < SLOTS; s++) {
                for (int i = 0; i <= PARTS; i++) marks[s][i] = GL15.glGenQueries();
                for (int i = 0; i < PARTS; i++) samples[s][i] = GL15.glGenQueries();
            }
        }

        boolean free() {
            return !issued[at];
        }

        void start() {
            GL11.glFinish();
            GL33.glQueryCounter(marks[at][0], GL33.GL_TIMESTAMP);
        }

        void begin(int part) {
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, samples[at][part]);
        }

        void end(int part, int quadCount, int runCount) {
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            GL11.glFinish();
            GL33.glQueryCounter(marks[at][part + 1], GL33.GL_TIMESTAMP);
            quads[at][part] = quadCount;
            runs[at][part] = runCount;
        }

        /** Части кадра нарисованы: метки — в полёт; ответ — их место для {@link #read}. */
        int issue() {
            int s = at;
            slotPixels[s] = pixels;
            issued[s] = true;
            at = (s + 1) % SLOTS;
            return s;
        }

        /** Метки места s готовы: последняя метка их кадра прочитана. */
        void read(int s) {
            if (!issued[s]) return;
            long prev = GL33.glGetQueryObjecti64(marks[s][0], GL15.GL_QUERY_RESULT);
            for (int p = 0; p < PARTS; p++) {
                long t = GL33.glGetQueryObjecti64(marks[s][p + 1], GL15.GL_QUERY_RESULT);
                ms[p] = Math.max(0, t - prev) / 1e6;
                prev = t;
                screens[p] = GL33.glGetQueryObjecti64(samples[s][p], GL15.GL_QUERY_RESULT) / Math.max(1, slotPixels[s]);
                lastQuads[p] = quads[s][p];
                lastRuns[p] = runs[s][p];
            }
            any = true;
            issued[s] = false;
        }

        void forget() {
            any = false;
        }

        /** Части; together — общий вызов квадратов того же кадра, мс. */
        String describe(double together) {
            if (!any) return "quads by part: none yet";
            double alone = 0;
            for (double v : ms) alone += v;
            StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "quads by part (same frame, each part alone; together %.2f ms, alone %.2f ms):",
                    together, alone));
            for (FxQuads.Part p : FxQuads.Part.values()) {
                int i = p.ordinal();
                b.append(String.format(Locale.ROOT, " %s %.2f ms %.2f scr %d q %d runs,", p.label, ms[i], screens[i], lastQuads[i], lastRuns[i]));
            }
            b.setLength(b.length() - 1);
            return b.toString();
        }

        void debugLines(List<String> out, double together) {
            if (!any) return;
            out.add(String.format(Locale.ROOT, "quads %.2f ms by part, ms/screens:", together));
            StringBuilder b = new StringBuilder();
            for (FxQuads.Part p : FxQuads.Part.values()) {
                int i = p.ordinal();
                if (lastQuads[i] == 0) continue;
                wrap(out, b);
                b.append(String.format(Locale.ROOT, "%s %.2f/%.2f ", p.label, ms[i], screens[i]));
            }
            if (!b.isEmpty()) out.add(b.toString().trim());
        }
    }
}
