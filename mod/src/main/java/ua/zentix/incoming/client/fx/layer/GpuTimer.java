package ua.zentix.incoming.client.fx.layer;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.Arrays;

/**
 * GPU pass time: timestamps (glQueryCounter) before and after, in a ring of {@link #RING} pairs — read after
 * a few frames once ready, without waiting (as for the pall, {@code PallPass}: OpenGL has one shared GL_TIME_ELAPSED query, and
 * Minecraft holds it over the entire frame while F3 GPU load is displayed). One-second summary ({@link #second}).
 * Measures only while the layer probe is active ({@link LayerTimes#on}): no timestamps otherwise.
 */
final class GpuTimer {
    private static final int RING = 4;
    private final int[] queries = new int[2 * RING];
    private final boolean[] issued = new boolean[RING];
    private int ring;
    /** Timestamps: 0 — unchecked, 1 — supported, −1 — unsupported. */
    private int timer;
    private double sum, max;
    private int frames;
    /** Previous-second summary: mean and maximum, ms; timestamped frame count. */
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

    /** Timestamp before the pass; −1 — unmeasured frame (probe inactive, {@link LayerTimes}; unsupported timestamps or all pairs still pending). */
    int begin() {
        if (!LayerTimes.on() || !available()) return -1;
        poll();
        if (issued[ring]) return -1;
        GL33.glQueryCounter(queries[2 * ring], GL33.GL_TIMESTAMP);
        return ring;
    }

    /** Timestamp after the pass. */
    void end(int pair) {
        if (pair < 0) return;
        GL33.glQueryCounter(queries[2 * pair + 1], GL33.GL_TIMESTAMP);
        issued[pair] = true;
        ring = (pair + 1) % RING;
    }

    /** Add ready pairs to the summary. */
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

    /** Once per second: summary. */
    void second() {
        lastMean = frames > 0 ? sum / frames : 0;
        lastMax = max;
        lastFrames = frames;
        sum = max = 0;
        frames = 0;
    }
}
