package ua.zentix.incoming.client.fx.layer;

import java.util.Locale;

/**
 * Watchdog on the nuclear volume's GPU time per frame (the {@link PallPass} timestamps). Windows resets a graphics driver
 * whose GPU work runs past two seconds (TDR), and the game is left showing its last frame while sound plays on (#656: a
 * nuke froze the picture on an RX 6600 XT; on the RTX 4060 laptop the same scene's longest pall frame is 13.8 ms). The
 * detail ladder ({@code PallPass.Detail}) answers ordinary overload over half-second windows; this answers a single frame
 * that is already an order of magnitude past any budget, before it can reach the driver's limit:
 * <ul>
 *     <li>{@link #FLOOR_MS} — ten times the HIGH budget: the pall drops to its lowest rung for the rest of the session;</li>
 *     <li>{@link #OFF_MS} — half the driver's limit: the volume is turned off for the session (one chat line).</li>
 * </ul>
 * A software renderer (llvmpipe in the cloud scenarios) has no driver timeout and takes seconds per frame by design, so
 * it is not watched. No world or OpenGL — unit test.
 */
public final class GpuWatch {
    /** Pall frame GPU time that sends the detail to its floor, ms (HIGH budget 24 ms × 10). */
    public static final double FLOOR_MS = 250;
    /** Pall frame GPU time that turns the volume off, ms (half of the 2 s Windows TDR limit). */
    public static final double OFF_MS = 1000;

    /** Watch state: everything allowed, detail held at its floor, volume off. */
    public enum State { WATCHING, FLOOR, OFF }

    private final String renderer;
    private final boolean watched;
    private State state = State.WATCHING;
    /** The frame that caused the last change, ms. */
    private double cause;

    /** Watch on the renderer named {@code renderer} (GL_RENDERER). */
    public GpuWatch(String renderer) {
        this.renderer = renderer == null ? "unknown renderer" : renderer;
        this.watched = !software(renderer);
    }

    /** GL_RENDERER this watch was made for (log lines). */
    public String renderer() {
        return renderer;
    }

    /** CPU rasterizers: Mesa llvmpipe/softpipe, SwiftShader, generic "software" renderers. */
    public static boolean software(String renderer) {
        String r = renderer == null ? "" : renderer.toLowerCase(Locale.ROOT);
        return r.contains("llvmpipe") || r.contains("softpipe") || r.contains("swiftshader") || r.contains("software");
    }

    /** One measured pall frame of {@code ms} GPU time; the new state when it changed, otherwise null. */
    public State sample(double ms) {
        if (!watched || state == State.OFF || !(ms >= FLOOR_MS)) return null;
        State next = ms >= OFF_MS ? State.OFF : State.FLOOR;
        if (next == state) return null;
        state = next;
        cause = ms;
        return state;
    }

    public State state() {
        return state;
    }

    public boolean watched() {
        return watched;
    }

    /** GPU time of the frame behind the last change, ms. */
    public double cause() {
        return cause;
    }
}
