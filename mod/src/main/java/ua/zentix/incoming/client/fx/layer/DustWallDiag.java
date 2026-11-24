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
    /** True: the dust wave's sources keep the age they had when it was set (a still wave; everything else runs on). */
    public static volatile boolean freezeAge;
    /** False: the march reads flat ground (no terrain relief), all soil (no material tiles), all open air (no window). */
    public static volatile boolean terrain = true, material = true, openAir = true;
    /** The resolve's clamp width in standard deviations (0: shipped); reprojection false: each pixel's own history (a still eye). */
    public static volatile float clampWidth;
    public static volatile boolean reprojection = true;
    /** div > 0: the march at this divisor. */
    public static volatile int div;
    /** False: a fresh ray's value is not held within its depth-alike neighbours' range (the shipped resolve holds it). */
    public static volatile boolean outliers = true;
    /** Not null: the next drawn frame writes the pass's targets to files with this path prefix, then clears it. */
    public static volatile String dump;

    private DustWallDiag() {}
}
