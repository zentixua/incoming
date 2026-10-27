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
 * Probe: GPU effects layer time by part — so a number identifies the cause of frame cost, rather than the model.
 * <ul>
 * <li>Layer passes ({@link Pass}: model atlas, scene depth, water decal, pall with volumetric smoke, fire light and
 * shimmer, source probes, quads, bloom) — timestamps (glQueryCounter) at their boundaries every frame, in a ring
 * of {@link #RING} frames: read after 2–3 frames once ready, without waiting (like {@link GpuTimer}). A timestamp between
 * neighbouring passes “leaks”: the GPU records it before the tail of previous work finishes, charging that tail to the next
 * pass (measurement HB2, #442). Such frame averages serve monitoring; the sum is correct, shares approximate.</li>
 * <li>So while requested ({@link #drains}: F3 open or {@code incoming.fx.drain} property), once every
 * {@link #DRAIN_EVERY_NS} a drain frame runs: glFinish before every timestamp finishes all preceding work, and
 * its pass timings are exact. Quads are one sorted call with parts interleaved by depth: after the combined call, this frame
 * draws each part ({@link FxQuads.Part}) separately again, with a drain before and after (pixel
 * count via GL_SAMPLES_PASSED); the frame is copied first and restored afterwards: same image on screen.
 * A drain frame costs another quad draw, two frame copies and GPU stalls between passes — 20–28 ms CPU (look_gate
 * 25.10, every 2 s): a hitch counted in scene measurements themselves; scenarios and the bench that only read the line therefore
 * skip it, and it is excluded from pass averages.</li>
 * </ul>
 * Probe active only while read: F3 open, {@link #describe} line requested within {@link #READ_NS} (bench,
 * scenarios), or held on ({@link #always}). Otherwise no timestamps at all (including {@link GpuTimer}), no frame copy.
 */
public final class LayerTimes {
    /**
     * Layer passes in frame order; pass time — from previous timestamp to its own. {@link #PROBE} — probe work on a drain
     * frame (frame copy, parts, frame restore); on other frames its timestamp immediately follows quads, and any leaked quad
     * tail is charged to them.
     */
    enum Pass {
        MODELS, DEPTH, WATER, PALL, HEAT, PROBES, QUADS, PROBE, BLOOM;

        final String label = name().toLowerCase(Locale.ROOT);
    }

    private static final int RING = 4, MARKS = Pass.values().length + 1, PASSES = Pass.values().length, PARTS = FxQuads.Part.values().length;
    /** Minimum drain frame interval. */
    static final long DRAIN_EVERY_NS = 2_000_000_000L;
    /** Drain frames requested at launch (part analysis on the bench or in the sandbox): {@code -Dincoming.fx.drain=true}. */
    private static final boolean DRAIN_ASKED = Boolean.getBoolean("incoming.fx.drain");
    /** Probe remains active this long after the last line read. */
    static final long READ_NS = 5_000_000_000L;

    private static boolean always, on;
    /** Last line read and drain frame times (0 — never). */
    private static long lastRead, lastDrain;
    /** Timestamps: 0 — unchecked, 1 — supported, −1 — unsupported. */
    private static int timer;
    private static final int[][] MARK = new int[RING][MARKS];
    private static final boolean[] ISSUED = new boolean[RING], DRAINED = new boolean[RING];
    /** Drain frame's part timestamp slot in {@link PartClock} (−1 — no parts). */
    private static final int[] PART_SLOT = new int[RING];
    private static int ring, slot = -1;
    private static final double[] SUM = new double[PASSES], MEAN = new double[PASSES], EXACT = new double[PASSES];
    private static int frames, lastFrames;
    /** Last read drain frame: time; reason for missing parts (null — parts present). */
    private static long exactAt;
    private static String partsMissing = "no drained frame yet";

    private static final PartClock CLOCK = new PartClock();
    /** Drain frame copy: texture, framebuffer and size. */
    private static int copy = -1, copyFbo = -1, copyW, copyH;

    private LayerTimes() {}

    /** Client holds the probe on (scenarios and sandbox: their lines read layer time each time). */
    public static void always() {
        always = true;
    }

    /** Whether the probe is active this frame (decided by {@link #begin}). */
    static boolean on() {
        return on;
    }

    /**
     * Layer frame start (before model atlas): decide whether to probe, read ready timestamps, set the first. true — drain
     * frame: draw quads by part too ({@link #parts}).
     */
    static boolean begin() {
        slot = -1;
        long now = System.nanoTime();
        boolean f3 = Minecraft.getInstance().getDebugOverlay().showDebugScreen();
        on = always || lastRead != 0 && now - lastRead < READ_NS || f3;
        if (!on) {
            free();
            return false;
        }
        if (!timer()) return false;
        poll(now);
        if (ISSUED[ring]) return false;
        slot = ring;
        boolean drain = drains(f3, DRAIN_ASKED, lastDrain, now) && CLOCK.free();
        DRAINED[slot] = drain;
        PART_SLOT[slot] = -1;
        if (drain) {
            lastDrain = now;
            // drain the frame world before the first timestamp: its tail is excluded from model atlas time
            GL11.glFinish();
        }
        GL33.glQueryCounter(MARK[slot][0], GL33.GL_TIMESTAMP);
        return drain;
    }

    /**
     * Whether this is a drain frame: only when requested — F3 open (a person inspecting parts) or launch property
     * ({@link #DRAIN_ASKED}) — and no more often than {@link #DRAIN_EVERY_NS}. Scenarios and bench also read the probe line (needing pass
     * timestamps every frame), but a drain hitch in their frames would contaminate their own measurement.
     */
    static boolean drains(boolean f3, boolean asked, long lastDrain, long now) {
        return (f3 || asked) && (lastDrain == 0 || now - lastDrain >= DRAIN_EVERY_NS);
    }

    /** End pass p (on a drain frame — after all its work finishes). */
    static void mark(Pass p) {
        if (slot < 0) return;
        if (DRAINED[slot]) GL11.glFinish();
        GL33.glQueryCounter(MARK[slot][p.ordinal() + 1], GL33.GL_TIMESTAMP);
    }

    /** Layer frame ended: timestamps pending. */
    static void end() {
        if (slot < 0) return;
        ISSUED[slot] = true;
        ring = (slot + 1) % RING;
        slot = -1;
    }

    /**
     * Drain frame after the combined quad call and its timestamp: copy frame, draw each part separately, restore frame. A multisampled,
     * scissored or multi-target framebuffer cannot be restored by copying — no parts then.
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

    /** Once per second: pass averages. */
    static void second() {
        for (int i = 0; i < PASSES; i++) {
            MEAN[i] = frames > 0 ? SUM[i] / frames : 0;
            SUM[i] = 0;
        }
        lastFrames = frames;
        frames = 0;
    }

    /** World exit or dimension change. */
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
     * Probe line (reading keeps it active): passes — previous-second averages and exact last drain frame timings;
     * quad parts from that frame — GPU ms, filled screen fraction (pixels after depth
     * testing, in frame screens), quads and spans in sorted order.
     */
    static String describe() {
        lastRead = System.nanoTime();
        if (timer < 0) return "GPU by pass: no timer queries";
        if (lastFrames == 0 && exactAt == 0) return "GPU by pass: no frames yet (the probe runs while this line or F3 is read)";
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "GPU by pass (ms, mean of %d frames; a pass's tail may spill into the next):",
                lastFrames));
        passes(b, MEAN);
        if (exactAt == 0) return b.append("; drained frame: none (F3 or -Dincoming.fx.drain=true)").toString();
        b.append(String.format(Locale.ROOT, "; drained frame (glFinish at every mark, one in %d s, %.1f s ago):", DRAIN_EVERY_NS / 1_000_000_000L,
                (System.nanoTime() - exactAt) / 1e9));
        passes(b, EXACT);
        b.append(String.format(Locale.ROOT, ", probe itself %.2f; ", EXACT[Pass.PROBE.ordinal()]));
        b.append(partsMissing == null ? CLOCK.describe(EXACT[Pass.QUADS.ordinal()]) : "quads by part: none (" + partsMissing + ")");
        return b.toString();
    }

    /** Passes excluding probe work, and their sum. */
    private static void passes(StringBuilder b, double[] ms) {
        double sum = 0;
        for (Pass p : Pass.values()) {
            if (p == Pass.PROBE) continue;
            b.append(String.format(Locale.ROOT, " %s %.2f", p.label, ms[p.ordinal()]));
            sum += ms[p.ordinal()];
        }
        b.append(String.format(Locale.ROOT, " (sum %.2f)", sum));
    }

    /** F3 lines (right side): passes (one-second average) and quad parts (drain frame). */
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

    /** F3 line at most ~45 characters: long text goes to output, then continues from the start. */
    private static void wrap(List<String> out, StringBuilder b) {
        if (b.length() <= 40) return;
        out.add(b.toString().trim());
        b.setLength(0);
    }

    /** OpenGL timestamps supported (GL 3.3 or ARB_timer_query, nonempty counter); queries created on first check. */
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
     * Ready timestamp frames: ordinary — into pass sums (probe timestamp charged to quads: only their leaked tail),
     * drain frame — exact passes and its parts: part timestamps precede its final timestamp and are already ready.
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

    /** Frame copy w×h (RGBA16F: preserves 8-bit, 10-bit and half-float frame values in both directions); false — creation failed. */
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

    /** Probe off: no frame copy (32 MB VRAM at 2560×1600). */
    private static void free() {
        if (copyFbo >= 0) GlStateManager._glDeleteFramebuffers(copyFbo);
        if (copy >= 0) GlStateManager._deleteTexture(copy);
        copy = copyFbo = -1;
        copyW = copyH = 0;
    }

    /**
     * Quad part timestamps on a drain frame ({@link FxQuads#drawParts}): glFinish before the first and after each; part time
     * between timestamps; pixel count via GL_SAMPLES_PASSED around it (shader-discarded fragments are excluded,
     * although they cost time). Ring of two slots: drain frames run every {@link #DRAIN_EVERY_NS}, their timestamps are ready long
     * before the next; read with the pass timestamps from their frame.
     */
    static final class PartClock {
        private static final int SLOTS = 2;
        private final int[][] marks = new int[SLOTS][PARTS + 1], samples = new int[SLOTS][PARTS], halfSamples = new int[SLOTS][PARTS];
        /** The part drew into the half-resolution target ({@link HalfPass}): its samples count four frame pixels. */
        private final boolean[][] halved = new boolean[SLOTS][PARTS];
        private boolean counting;
        private final boolean[] issued = new boolean[SLOTS];
        private final int[][] quads = new int[SLOTS][PARTS], runs = new int[SLOTS][PARTS];
        private final double[] slotPixels = new double[SLOTS];
        private int at;
        double pixels;
        /** Last read frame: ms, screens, quads, spans. */
        private final double[] ms = new double[PARTS], screens = new double[PARTS];
        private final int[] lastQuads = new int[PARTS], lastRuns = new int[PARTS];
        private boolean any;

        void create() {
            for (int s = 0; s < SLOTS; s++) {
                for (int i = 0; i <= PARTS; i++) marks[s][i] = GL15.glGenQueries();
                for (int i = 0; i < PARTS; i++) {
                    samples[s][i] = GL15.glGenQueries();
                    halfSamples[s][i] = GL15.glGenQueries();
                }
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
            counting = true;
            halved[at][part] = false;
        }

        /** The part's half-resolution quads follow ({@link HalfPass#drawParts}): their samples apart. */
        void half(int part) {
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            GL15.glBeginQuery(GL15.GL_SAMPLES_PASSED, halfSamples[at][part]);
            halved[at][part] = true;
        }

        /** The part's half-resolution quads are drawn; laying them onto the frame is not its fill. */
        void halfDone(int part) {
            GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            counting = false;
        }

        void end(int part, int quadCount, int runCount) {
            if (counting) GL15.glEndQuery(GL15.GL_SAMPLES_PASSED);
            counting = false;
            GL11.glFinish();
            GL33.glQueryCounter(marks[at][part + 1], GL33.GL_TIMESTAMP);
            quads[at][part] = quadCount;
            runs[at][part] = runCount;
        }

        /** Frame parts drawn: timestamps pending; returns their slot for {@link #read}. */
        int issue() {
            int s = at;
            slotPixels[s] = pixels;
            issued[s] = true;
            at = (s + 1) % SLOTS;
            return s;
        }

        /** Slot s timestamps ready: their frame's final timestamp has been read. */
        void read(int s) {
            if (!issued[s]) return;
            long prev = GL33.glGetQueryObjecti64(marks[s][0], GL15.GL_QUERY_RESULT);
            for (int p = 0; p < PARTS; p++) {
                long t = GL33.glGetQueryObjecti64(marks[s][p + 1], GL15.GL_QUERY_RESULT);
                ms[p] = Math.max(0, t - prev) / 1e6;
                prev = t;
                long fill = GL33.glGetQueryObjecti64(samples[s][p], GL15.GL_QUERY_RESULT);
                if (halved[s][p]) fill += 4 * GL33.glGetQueryObjecti64(halfSamples[s][p], GL15.GL_QUERY_RESULT);
                screens[p] = fill / Math.max(1, slotPixels[s]);
                lastQuads[p] = quads[s][p];
                lastRuns[p] = runs[s][p];
            }
            any = true;
            issued[s] = false;
        }

        void forget() {
            any = false;
        }

        /** Parts; together — combined quad call from the same frame, ms. */
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
