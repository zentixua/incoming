package ua.zentix.incoming.client.fx.layer;

/**
 * Diagnostics of the dust wave's passes, set only by the client scenario's {@code dw:} steps (devtest); the defaults draw as
 * shipped. One run switches them between shots, so one take answers which pass a defect comes from.
 */
public final class DustWallDiag {
    /**
     * The pall's rung: −1 as shipped (a client scenario pins the setting's rung), −2 follows the GPU budget as in play, 0 and up
     * pins that rung (never finer than the setting's), in a client scenario and in the dev client alike.
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
    /** k ≥ 0: the march writes every ray's k-th occupied sample's terms instead of the picture (−1: the picture). */
    public static volatile int probe = -1;
    /**
     * Not null: the next drawn frame redraws its march once per occupied sample k = 0 .. {@link #sweepSamples} − 1 with the probe
     * on and writes each pass's targets to {@code <prefix>_p<k>_*.f32} (one frame: every record walks the same ray), then
     * clears it.
     */
    public static volatile String sweep;
    public static volatile int sweepSamples = 48;
    /** Above 0: the client scenario turns {@link #freezeAge} on once the front is this many blocks from the camera. */
    public static volatile float freezeAt = -1;
    /** False: the march splits its intervals at a clear gap alone, never at the profile's bend (dustwall.glsl dwKnot). */
    public static volatile boolean split = true;
    /** False: layer quads and source probes read no dust in front of them (pall_front.glsl dustWallThrough). */
    public static volatile boolean frontDust = true;
    /** Not null: the next drawn frame writes the pass's targets to files with this path prefix, then clears it. */
    public static volatile String dump;

    private DustWallDiag() {}
}
