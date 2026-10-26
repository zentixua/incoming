package ua.zentix.incoming.client.sound;

/**
 * Projectile engines dip briefly when a loud blast reaches the ear and recover in just over a second (without
 * Minecraft, covered by a unit test). Games with a “loudness window” (HDR audio) mix this way: loud sound briefly
 * sets the window, quiet sound beneath it is attenuated so the blast does not drown in neighbouring salvo
 * projectile engines. Minecraft cannot play louder than the file, so engines make room for the blast instead of
 * compressing it. Values are presentation: mixing, not auditory masking.
 */
final class Ducking {
    /** Blasts below this at the ear (LUFS) leave engines alone; from {@link #FULL}, attenuate by full {@link #DEPTH}. */
    static final double FROM = -24, FULL = -15;
    /** Depth, dB. */
    static final double DEPTH = 8;
    /** Hold attenuation for this many ticks, then release at {@link #RELEASE} dB per tick. */
    static final int HOLD = 4;
    /** Release, dB per tick (6 dB/s: full depth in ~1.3 s). */
    static final double RELEASE = 0.3;

    private double db;
    private int hold;

    /** Blast with direct-sound loudness lufs reached the ear. */
    void blast(double lufs) {
        double t = Math.max(0, Math.min(1, (lufs - FROM) / (FULL - FROM)));
        double depth = DEPTH * t * t * (3 - 2 * t);
        if (depth > db) {
            db = depth;
            hold = HOLD;
        }
    }

    /** Once per tick. */
    void tick() {
        if (hold > 0) hold--;
        else db = Math.max(0, db - RELEASE);
    }

    /** Engine volume multiplier. */
    float factor() {
        return (float) Math.pow(10, -db / 20);
    }

    void reset() {
        db = 0;
        hold = 0;
    }
}
