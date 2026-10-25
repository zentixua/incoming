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
 * Время каждого кадра без ожидания видеокарты. Процессор — время процессора потока отрисовки от {@code RenderFrameEvent.Pre}
 * до {@code Post} ({@code cpu_thread_ms}, {@link ThreadMXBean#getCurrentThreadCpuTime}, как {@code FxLayer.cpuNanos} мода:
 * своя работа кадра — мир, слои модов, интерфейс; без обмена буферов и без ожидания процессора, пока поток вытеснен
 * чужими задачами; видеокарту драйвер ждёт в обмене буферов, а не здесь — A/B в client.py), то
 * же настенными часами ({@code cpu_ms}: в нём и ожидания) и интервал Pre → Pre (что видит игрок): в строке кадра — время
 * от начала прошлого кадра ({@code prev_interval_ms}, оно известно в начале этого), длительность самого кадра — в строке
 * следующего (сдвиг делает разбор, results.py). Видеокарта — метки
 * {@code glQueryCounter(GL_TIMESTAMP)}: в начале и в конце кадра и на каждой стадии {@code RenderLevelStageEvent} дважды —
 * до всех обработчиков стадии (первым, {@code HIGHEST}) и после них ({@code LOWEST}); отрезок до первой метки стадии —
 * работа игры до неё, между метками — обработчики модов на ней (у Incoming слой эффектов — на {@code AFTER_LEVEL}). Не
 * {@code GL_TIME_ELAPSED}: такой запрос у OpenGL один на всех, его держит Minecraft (F3) и сам Incoming. Метки читаются
 * через кадры, когда готовы (кольцо {@link #RING} кадров, как {@code GpuTimer} мода); строки кадров пишутся по порядку.
 * Разность меток — время видеокарты по её часам от первой до последней команды кадра: когда упор в неё, это её работа,
 * когда в процессор — с простоями между командами.
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
    /** Время процессора потока в начале кадра, нс (−1 — JVM его не даёт: колонка пуста). */
    private long preThread = -1;
    private final int[] shot = new int[RING];
    private final boolean[] pending = new boolean[RING];
    private int head, tail;
    /** Метки времени видеокарты: 0 — не проверяли, 1 — есть, −1 — нет. */
    private int timer;
    private long frame, pre, lastPre;
    private boolean inFrame;
    private long lastGpuStart = -1;
    private final Set<Object> stagesThisFrame = new HashSet<>();
    /** Стадия, у которой стоит первая метка, а второй (после обработчиков) ещё нет. */
    @Nullable
    private Object open;
    /** Кадр, который снять в конце этого кадра, и что сделать по записи. */
    @Nullable
    private Path shotFile;
    @Nullable
    private Consumer<Long> shotDone;
    private boolean afterShot;
    /** Повторные стадии в кадре (проход теней и т. п.): метки ставятся только у первой. */
    long repeatedStages;

    FrameClock(Out out) {
        this.out = out;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, this::onPre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, this::onPost);
        // у лямбды шина тип события не выводит — класс явно
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, false, RenderLevelStageEvent.class, e -> onStage(e, false));
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, RenderLevelStageEvent.class, e -> onStage(e, true));
    }

    /** Снять кадр в конце ближайшего кадра; {@code done} получает его номер. */
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
            // кольцо полно (видеокарта позади больше чем на RING кадров — не бывает у драйверов, но не терять строки):
            // самый старый — дождаться
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
        // кадр — после метки конца: ожидание чтения кадра у видеокарты не входит во время кадра, но этот кадр и
        // следующий (его интервал) в замер не идут
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

    /** Готовые кадры по порядку — в строки; {@code block} — самый старый дождаться. */
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

    /** Имя стадии: «neoforge:after_level» → «after_level». */
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
