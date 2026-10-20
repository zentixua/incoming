package ua.zentix.incoming.client.fx.layer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.Arrays;

/**
 * Время прохода на видеокарте: метки времени (glQueryCounter) до и после, по кольцу из {@link #RING} пар — читаются через
 * кадры, когда готовы, без ожидания (как у пелены, {@code PallPass}: запрос GL_TIME_ELAPSED у OpenGL один на всех, а
 * Minecraft держит его на весь кадр, пока на экране F3 с загрузкой видеокарты). Сводка — за секунду ({@link #second}).
 */
final class GpuTimer {
    private static final int RING = 4;
    private final int[] queries = new int[2 * RING];
    private final boolean[] issued = new boolean[RING];
    private int ring;
    /** Метки времени: 0 — не проверяли, 1 — есть, −1 — нет. */
    private int timer;
    private double sum, max;
    private int frames;
    /** Сводка прошлой секунды: среднее и наибольшее, мс; кадров с меткой. */
    double lastMean, lastMax;
    int lastFrames;

    private boolean available() {
        if (timer == 0) {
            GLCapabilities caps = GL.getCapabilities();
            boolean ok = (caps.OpenGL33 || caps.GL_ARB_timer_query) && GL15.glGetQueryi(GL33.GL_TIMESTAMP, GL15.GL_QUERY_COUNTER_BITS) > 0;
            if (ok) for (int i = 0; i < queries.length; i++) queries[i] = GL15.glGenQueries();
            Arrays.fill(issued, false);
            timer = ok ? 1 : -1;
        }
        return timer > 0;
    }

    /** Метка до прохода; −1 — кадр не меряется (меток нет или все пары ещё в полёте). */
    int begin() {
        if (!available()) return -1;
        poll();
        if (issued[ring]) return -1;
        GL33.glQueryCounter(queries[2 * ring], GL33.GL_TIMESTAMP);
        return ring;
    }

    /** Метка после прохода. */
    void end(int pair) {
        if (pair < 0) return;
        GL33.glQueryCounter(queries[2 * pair + 1], GL33.GL_TIMESTAMP);
        issued[pair] = true;
        ring = (pair + 1) % RING;
    }

    /** Готовые пары — в сводку. */
    private void poll() {
        for (int i = 0; i < RING; i++) {
            if (!issued[i] || GL15.glGetQueryObjecti(queries[2 * i + 1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) continue;
            long start = GL33.glGetQueryObjecti64(queries[2 * i], GL15.GL_QUERY_RESULT), stop = GL33.glGetQueryObjecti64(queries[2 * i + 1], GL15.GL_QUERY_RESULT);
            double ms = Math.max(0, stop - start) / 1e6;
            sum += ms;
            max = Math.max(max, ms);
            frames++;
            issued[i] = false;
        }
    }

    /** Раз в секунду: сводка. */
    void second() {
        lastMean = frames > 0 ? sum / frames : 0;
        lastMax = max;
        lastFrames = frames;
        sum = max = 0;
        frames = 0;
    }
}
