package ua.zentix.stand;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Measure every frame without GPU waits. CPU means render-thread CPU from {@code RenderFrameEvent.Pre}
 * to {@code Post} ({@code cpu_thread_ms}, {@link ThreadMXBean#getCurrentThreadCpuTime}, like mod
 * {@code FxLayer.cpuNanos}): frame work (world, mod layers, GUI), excluding buffer swap and CPU waits when
 * preempted by other jobs. Driver waits for GPU at buffer swap, not here (A/B in client.py). Also record
 * wall-clock duration ({@code cpu_ms}, including waits) and Pre → Pre interval (player view): a frame row
 * contains time since previous start ({@code prev_interval_ms}, known now); its own duration is in the
 * next row (results.py shifts it). GPU uses {@code glQueryCounter(GL_TIMESTAMP)} at frame start/end and
 * at every {@code RenderLevelStageEvent} twice, before handlers ({@code HIGHEST}) and afterward
 * ({@code LOWEST}); interval before first stage timestamp is preceding game work, between timestamps is
 * mod handler work (Incoming effects layer: {@code AFTER_LEVEL}). Do not use {@code GL_TIME_ELAPSED}:
 * OpenGL has one shared query, held by Minecraft (F3) and Incoming. Read ready timestamps several frames
 * later ({@link #RING}-frame ring, like mod {@code GpuTimer}); write frame rows in order. Timestamp
 * difference is GPU-clock time from first to last frame command: GPU work when GPU-bound, including gaps
 * between commands when CPU-bound.
 */
final class FrameClock {
    private static final int RING = 8, MAX_STAMPS = 48;
    private final Out out;
    private final int[][] queries = new int[RING][MAX_STAMPS];
    private final String[][] labels = new String[RING][MAX_STAMPS];
    private final int[] used = new int[RING];
    private final long[] frameNo = new long[RING];
    private final double[] tMs = new double[RING], interval = new double[RING], cpu = new double[RING], cpuThread = new double[RING];
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    /** Thread CPU time at frame start, ns (−1 if JVM does not provide it; column empty). */
    private long preThread = -1;
    private final int[] shot = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private int head, tail;
    /** GPU timestamp support: 0 unchecked, 1 available, −1 unavailable. */
    private int timer;
    private long frame, pre, lastPre;
    private boolean inFrame;
    private long lastGpuStart = -1;
    private final Set<Object> stagesThisFrame = new HashSet<>();
    /** Stage with first timestamp but no second/post-handler timestamp yet. */
    @Nullable
    private Object open;
    /** Shot requested at this frame end and completion callback. */
    @Nullable
    private Path shotFile;
    @Nullable
    private Consumer<Long> shotDone;
    private boolean afterShot;
    /** Repeated stages in a frame (shadow pass etc.): timestamp only the first. */
    long repeatedStages;

    FrameClock(Out out) {
        this.out = out;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onPost);
        // The bus cannot infer a lambda event type; specify its class
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderLevelStageEvent.class, e -> onStage(e, false));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderLevelStageEvent.class, e -> onStage(e, true));
    }

    /** Capture at the next frame end; {@code done} receives frame number. */
    void requestShot(Path file, Consumer<Long> done) {
        shotFile = file;
        shotDone = done;
    }

    boolean shotPending() {
        return shotFile != null;
    }

    long frame() {
        return frame;
    }

    private boolean timerAvailable() {
        if (timer == 0) {
            GLCapabilities caps = GL.getCapabilities();
            boolean ok = (caps.OpenGL33 || caps.GL_ARB_timer_query) && GL15.glGetQueryi(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) > 0;
            if (ok) for (int[] slot : queries) for (int i = 0; i < slot.length; i++) slot[i] = GL15.glGenQueries();
            timer = ok ? 1 : -1;
            Stand.LOG.info("STAND: GPU timestamps {}", ok ? "on" : "unavailable — GPU columns empty");
        }
        return timer > 0;
    }

    private void onPre(RenderFrameEvent.Pre e) {
        long now = System.nanoTime();
        frame++;
        int s = head;
        if (timerAvailable()) {
            // Full ring (GPU more than RING frames behind; not normal for drivers, but retain all rows):
            // wait for the oldest
            if (pending[s]) drain(true);
            used[s] = 0;
            stamp(s, "start");
        }
        frameNo[s] = frame;
        tMs[s] = Stand.ms(now);
        interval[s] = lastPre != 0 ? (now - lastPre) / 1e6 : Double.NaN;
        shot[s] = afterShot ? 2 : 0;
        afterShot = false;
        lastPre = now;
        pre = now;
        preThread = threadCpu();
        inFrame = true;
        stagesThisFrame.clear();
        open = null;
    }

    private void onStage(RenderLevelStageEvent e, boolean after) {
        if (!inFrame || timer <= 0) return;
        Object stage = e.getStage();
        if (!after) {
            if (!stagesThisFrame.add(stage)) {
                repeatedStages++;
                return;
            }
            stamp(head, name(stage));
            open = stage;
        } else if (stage == open) {
            stamp(head, name(stage) + "+");
            open = null;
        }
    }

    private void onPost(RenderFrameEvent.Post e) {
        if (!inFrame) return;
        long now = System.nanoTime();
        int s = head;
        if (timer > 0) stamp(s, "tail");
        cpu[s] = (now - pre) / 1e6;
        long thread = threadCpu();
        cpuThread[s] = preThread >= 0 && thread >= 0 ? (thread - preThread) / 1e6 : Double.NaN;
        inFrame = false;
        // Capture after end timestamp: GPU readback wait is outside frame time, but exclude this frame
        // and the following interval from measurement
        if (shotFile != null) {
            takeShot(shotFile, shotDone, frame);
            shotFile = null;
            shotDone = null;
            shot[s] = 1;
            afterShot = true;
        }
        if (timer > 0) {
            pending[s] = true;
            head = (head + 1) % RING;
            drain(false);
        } else {
            write(s, null);
        }
    }

    private void stamp(int slot, String label) {
        if (used[slot] >= MAX_STAMPS) return;
        GL33.glQueryCounter(queries[slot][used[slot]], GL33.GL_TIMESTAMP);
        labels[slot][used[slot]] = label;
        used[slot]++;
    }

    /** Write ready frames in order; {@code block} waits for the oldest. */
    private void drain(boolean block) {
        while (pending[tail]) {
            int last = queries[tail][used[tail] - 1];
            if (!block && GL15.glGetQueryObjecti(last, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return;
            long[] ts = new long[used[tail]];
            for (int i = 0; i < ts.length; i++) ts[i] = GL33.glGetQueryObjecti64(queries[tail][i], GL15.GL_QUERY_RESULT);
            write(tail, ts);
            pending[tail] = false;
            tail = (tail + 1) % RING;
            block = false;
        }
    }

    private void write(int s, @Nullable long[] ts) {
        StringBuilder b = new StringBuilder(160);
        b.append(frameNo[s]).append(',').append(fmt(tMs[s])).append(',').append(fmt(interval[s])).append(',').append(fmt(cpu[s])).append(',')
                .append(fmt(cpuThread[s])).append(',');
        if (ts != null && ts.length >= 2) {
            b.append(fmt((ts[ts.length - 1] - ts[0]) / 1e6)).append(',');
            b.append(lastGpuStart >= 0 ? fmt((ts[0] - lastGpuStart) / 1e6) : "").append(',');
            lastGpuStart = ts[0];
            b.append(shot[s]).append(',');
            for (int i = 1; i < ts.length; i++) {
                if (i > 1) b.append(';');
                b.append(labels[s][i]).append('=').append(fmt(Math.max(0, ts[i] - ts[i - 1]) / 1e6));
            }
        } else {
            b.append(",,").append(shot[s]).append(',');
        }
        b.append('\n');
        out.frame(b.toString());
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format(Locale.ROOT, "%.3f", v) : "";
    }

    private static long threadCpu() {
        return THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : -1;
    }

    /** Stage name: "neoforge:after_level" → "after_level". */
    private static String name(Object stage) {
        String s = stage.toString();
        int colon = s.indexOf(':');
        return colon >= 0 ? s.substring(colon + 1) : s;
    }

    private static void takeShot(Path file, @Nullable Consumer<Long> done, long frame) {
        Minecraft mc = Minecraft.getInstance();
        NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        Util.ioPool().execute(() -> {
            try (image) {
                image.writeToFile(file);
            } catch (IOException ex) {
                Stand.LOG.error("STAND: shot {} not written", file, ex);
            }
        });
        if (done != null) done.accept(frame);
    }
}
