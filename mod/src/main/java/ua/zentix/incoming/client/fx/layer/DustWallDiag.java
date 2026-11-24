package ua.zentix.incoming.client.fx.layer;

/**
 * Diagnostics of the dust wave's passes, set only by the client scenario's {@code dw:} steps (devtest); the defaults draw as
 * shipped. One run switches them between shots, so one take answers which pass a defect comes from.
 */
public final class DustWallDiag {
    /**
     * The pall's rung: −1 as shipped (a client scenario pins the setting's rung), −2 follows the GPU budget as in play, 0 and up
     * pins that rung (never finer than the setting's).
     */
    public static volatile int rung = -1;
    /** False: every frame's march is resolved without the history. */
    public static volatile boolean history = true;
    /** False: every march pixel is traced every frame (no interleaved rays). */
    public static volatile boolean interleave = true;
    /** True: the march's per-frame offsets (ray within its pixel, sample offset along it) stay at frame 0's. */
    public static volatile boolean freeze;
    /** Not null: the next drawn frame writes the pass's targets to files with this path prefix, then clears it. */
    public static volatile String dump;

    private DustWallDiag() {}
}
