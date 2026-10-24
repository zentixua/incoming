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
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import ua.zentix.incoming.Incoming;

import java.util.Locale;

/**
 * GPU Gems 3, ch.23: запечённые клубы в уменьшенной цели RGBA16F, затем цвет с умноженной альфой — на кадр.
 * Последовательные частицы одного разрешения сохраняют общую сортировку; модели и мелкие детали остаются native.
 * Бюджет — четыре настоящих экрана фрагментов, по рамкам уже построенных квадратов (прозрачные углы тоже стоят).
 * Плотные клубы и широкие тонкие поля получают разные шаги в одном суммарном бюджете; масса и контуры не меняются. Обратный вывод — с весом по глубине мира, без ореола
 * на границе стены. Цель заводится под окно один раз, меньший viewport живёт в её углу: смена шага не выделяет память.
 */
public final class PuffTarget {
    public static final double BUDGET = 4;
    @Nullable static ShaderInstance resolve;
    private static int fbo = -1, texture = -1, width, height;
    private static final int[] VIEWPORT = new int[4], SCISSOR = new int[4];
    private static final int[] COLOR_MASK = new int[4];
    private static boolean scissor;
    private static int previous, atlas, depth, activeW, activeH;
    private static ShaderInstance fx;
    private static final float[] ZERO = new float[4];
    private static int step = 1, runs;
    private static Resolution fine = new Resolution(1, true), thin = new Resolution(1, true);
    private static double rawFill, shadedFill, compositeFill;

    private PuffTarget() {}

    /** Верхняя оценка пикселей квадратов в цели: целые границы растра, включая неполные крайние пиксели. */
    public static double pixels(float[] bounds, int count, int w, int h) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            int o = 4 * i;
            int x0 = (int) Math.floor(bounds[o] * w), y0 = (int) Math.floor(bounds[o + 1] * h);
            int x1 = (int) Math.ceil(bounds[o + 2] * w), y1 = (int) Math.ceil(bounds[o + 3] * h);
            sum += (double) Math.max(0, x1 - x0) * Math.max(0, y1 - y0);
        }
        return sum;
    }

    /** Шаг цели и сохранение мелких клубов в полном разрешении, когда они помещаются в бюджет. */
    public record Resolution(int step, boolean keepSmall) {}

    public static Resolution plan(float[] bounds, int count, int w, int h, double budget) {
        double limit = budget * w * h;
        int s = Math.max(1, (int) Math.ceil(Math.sqrt(pixels(bounds, count, w, h) / limit)));
        while (s > 1 && workPixels(bounds, count, w, h, s - 1, false) <= limit) s--;
        while (workPixels(bounds, count, w, h, s, false) > limit && s < Math.max(w, h)) s++;
        // A bounded quality search: native subpixel smoke is preferred; the hard raster budget wins if it cannot fit.
        int last = s + Math.max(2, s / 2);
        for (int mixed = s; mixed <= last; mixed++) {
            if (workPixels(bounds, count, w, h, mixed, true) <= limit) return new Resolution(mixed, true);
        }
        return new Resolution(s, false);
    }

    /** Different sampling densities, one native-pixel budget. Broad low-contrast fields cannot blur detailed clubs. */
    public record FrameResolution(Resolution fine, Resolution thin) {}

    public static FrameResolution planFrame(float[] dense, float[] broad, int count, int w, int h, double budget) {
        double area = (double) w * h;
        double reserve = Math.min(budget / 4, pixels(broad, count, w, h) / area);
        Resolution f = plan(dense, count, w, h, budget - reserve);
        double used = workPixels(dense, count, w, h, f.step(), f.keepSmall()) / area;
        Resolution t = plan(broad, count, w, h, Math.max(1e-9, budget - used));
        return new FrameResolution(f, t);
    }

    /** A thin or subpixel club keeps native sampling, so a nearby cloud cannot make a far trail disappear. */
    public static boolean reduced(float[] b, int index, int w, int h, int s) {
        int o = 4 * index;
        return s > 1 && (b[o + 2] - b[o]) * w >= s && (b[o + 3] - b[o + 1]) * h >= s;
    }

    /** Both paths count: reduced large clubs and native small/thin clubs. */
    public static double workPixels(float[] b, int count, int w, int h, int s, boolean protectSmall) {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            int rw = w, rh = h;
            if (!protectSmall || reduced(b, i, w, h, s)) { rw = Math.max(1, w / s); rh = Math.max(1, h / s); }
            int o = 4 * i;
            sum += (Math.ceil(b[o + 2] * rw) - Math.floor(b[o] * rw)) * (Math.ceil(b[o + 3] * rh) - Math.floor(b[o + 1] * rh));
        }
        return sum;
    }

    static int resolution(float[] b, int index, boolean broad) {
        Resolution r = broad ? thin : fine;
        return !r.keepSmall() || reduced(b, index, VIEWPORT[2], VIEWPORT[3], r.step()) ? r.step() : 1;
    }

    static boolean begin(ShaderInstance shader, int scene, float[] dense, float[] broad, int count) {
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, VIEWPORT);
        int w = VIEWPORT[2], h = VIEWPORT[3];
        FrameResolution resolution = planFrame(dense, broad, count, w, h, BUDGET);
        fine = resolution.fine(); thin = resolution.thin();
        step = Math.max(fine.step(), thin.step());
        rawFill = (pixels(dense, count, w, h) + pixels(broad, count, w, h)) / ((double) w * h);
        shadedFill = (workPixels(dense, count, w, h, fine.step(), fine.keepSmall())
                + workPixels(broad, count, w, h, thin.step(), thin.keepSmall())) / ((double) w * h);
        runs = 0;
        compositeFill = 0;
        shader.safeGetUniform("FxTarget").set((float) w, (float) h, LinearStage.active() ? 2f : 0f);
        if (step == 1) return false;
        if (resolve == null || scene < 0 || !ensure(w, h)) {
            fine = thin = new Resolution(1, true);
            step = 1;
            shadedFill = rawFill;
            return false;
        }
        fx = shader;
        depth = scene;
        atlas = RenderSystem.getShaderTexture(0);
        previous = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (scissor) GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, SCISSOR);
        GL11.glGetIntegerv(GL11.GL_COLOR_WRITEMASK, COLOR_MASK);
        return true;
    }

    static boolean low(float x0, float y0, float x1, float y1, int sampleStep) {
        activeW = Math.max(1, VIEWPORT[2] / sampleStep);
        activeH = Math.max(1, VIEWPORT[3] / sampleStep);
        if (x1 <= x0 || y1 <= y0) return false;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        RenderSystem.viewport(0, 0, activeW, activeH);
        // Два чистых текселя вокруг группы: билинейный вывод не должен прочитать остаток прошлой группы.
        int lx = Math.max(0, (int) Math.floor(x0 * activeW) - 2), ly = Math.max(0, (int) Math.floor(y0 * activeH) - 2);
        int hx = Math.min(activeW, (int) Math.ceil(x1 * activeW) + 2), hy = Math.min(activeH, (int) Math.ceil(y1 * activeH) + 2);
        RenderSystem.enableScissor(lx, ly, hx - lx, hy - ly);
        RenderSystem.colorMask(true, true, true, true); // The intermediate target must accumulate opacity even if the world masks alpha.
        GL30.glClearBufferfv(GL11.GL_COLOR, 0, ZERO);
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(() -> fx);
        RenderSystem.setShaderTexture(0, atlas);
        RenderSystem.setShaderTexture(1, depth);
        fx.safeGetUniform("FxTarget").set((float) activeW, (float) activeH, 1f);
        runs++;
        return true;
    }

    static void composite(float x0, float y0, float x1, float y1) {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        RenderSystem.colorMask(COLOR_MASK[0] != 0, COLOR_MASK[1] != 0, COLOR_MASK[2] != 0, COLOR_MASK[3] != 0);
        int lx = Math.max(VIEWPORT[0], VIEWPORT[0] + (int) Math.floor(x0 * VIEWPORT[2]) - 1);
        int ly = Math.max(VIEWPORT[1], VIEWPORT[1] + (int) Math.floor(y0 * VIEWPORT[3]) - 1);
        int hx = Math.min(VIEWPORT[0] + VIEWPORT[2], VIEWPORT[0] + (int) Math.ceil(x1 * VIEWPORT[2]) + 1);
        int hy = Math.min(VIEWPORT[1] + VIEWPORT[3], VIEWPORT[1] + (int) Math.ceil(y1 * VIEWPORT[3]) + 1);
        if (scissor) {
            lx = Math.max(lx, SCISSOR[0]);
            ly = Math.max(ly, SCISSOR[1]);
            hx = Math.min(hx, SCISSOR[0] + SCISSOR[2]);
            hy = Math.min(hy, SCISSOR[1] + SCISSOR[3]);
        }
        RenderSystem.enableScissor(lx, ly, Math.max(0, hx - lx), Math.max(0, hy - ly));
        compositeFill += (double) Math.max(0, hx - lx) * Math.max(0, hy - ly) / (VIEWPORT[2] * (double) VIEWPORT[3]);
        ShaderInstance s = resolve;
        RenderSystem.setShader(() -> s);
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShaderTexture(1, depth);
        s.safeGetUniform("PuffSize").set((float) activeW, (float) activeH);
        s.safeGetUniform("PuffViewport").set((float) VIEWPORT[0], (float) VIEWPORT[1], (float) VIEWPORT[2], (float) VIEWPORT[3]);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        b.addVertex(-1, -1, 0);
        b.addVertex(1, -1, 0);
        b.addVertex(1, 1, 0);
        b.addVertex(-1, 1, 0);
        MeshData mesh = b.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        restore();
    }

    private static void restore() {
        RenderSystem.colorMask(COLOR_MASK[0] != 0, COLOR_MASK[1] != 0, COLOR_MASK[2] != 0, COLOR_MASK[3] != 0);
        if (scissor) RenderSystem.enableScissor(SCISSOR[0], SCISSOR[1], SCISSOR[2], SCISSOR[3]);
        else RenderSystem.disableScissor();
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(() -> fx);
        RenderSystem.setShaderTexture(0, atlas);
        RenderSystem.setShaderTexture(1, depth);
        fx.safeGetUniform("FxTarget").set((float) VIEWPORT[2], (float) VIEWPORT[3], LinearStage.active() ? 2f : 0f);
    }

    static void end() {
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
        RenderSystem.viewport(VIEWPORT[0], VIEWPORT[1], VIEWPORT[2], VIEWPORT[3]);
        restore();
    }

    /** Заливка обоих разрешений вместе; вывод групп — отдельная стоимость (до 4 выборок цвета и глубины). */
    public static String describe() {
        return String.format(Locale.ROOT, "puff raster %.2f/%.0f screens (native %.2f, steps %d/%d, %d groups, composite %.2f)",
                shadedFill, BUDGET, rawFill, fine.step(), thin.step(), runs, compositeFill);
    }

    static void reset() {
        fine = thin = new Resolution(1, true);
        step = 1;
        runs = 0;
        rawFill = shadedFill = compositeFill = 0;
    }

    private static boolean ensure(int w, int h) {
        if (fbo >= 0 && width == w && height == h) return true;
        if (texture < 0) texture = GlStateManager._genTexture();
        if (fbo < 0) fbo = GlStateManager.glGenFramebuffers();
        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MIN_FILTER, GlConst.GL_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_MAG_FILTER, GlConst.GL_LINEAR);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_S, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texParameter(GlConst.GL_TEXTURE_2D, GlConst.GL_TEXTURE_WRAP_T, GlConst.GL_CLAMP_TO_EDGE);
        GlStateManager._texImage2D(GlConst.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, w, h, 0, GL11.GL_RGBA, GL11.GL_FLOAT, null);
        int bound = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GlConst.GL_COLOR_ATTACHMENT0, GlConst.GL_TEXTURE_2D, texture, 0);
        boolean ok = GlStateManager.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER) == GlConst.GL_FRAMEBUFFER_COMPLETE;
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bound);
        if (ok) {
            width = w;
            height = h;
        } else Incoming.LOG.error("Smoke particle target could not be created: drawing clubs at full resolution");
        return ok;
    }
}
