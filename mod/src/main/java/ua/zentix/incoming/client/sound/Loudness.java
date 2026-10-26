package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.sound.Propagation;

/**
 * Game volume from sound level at the ear — one reference for all mod engine sounds and source-law world sounds.
 * <ul>
 * <li>Volume is half the level's decibels (10<sup>ΔL/40</sup>), like distant blasts ({@code Outdoor}) and near ones
 * ({@code BlastMix}: −3 dB per distance doubling): home speakers have ~60 dB from room noise to loud sound, outside
 * has ~110 dB from quiet night to rocket launch roar. This is a physics deviation — mapping to speakers, not a
 * propagation law: propagation itself ({@link Propagation}) uses full decibels.</li>
 * <li>Reference is quiet-night background ({@link Propagation#NIGHT}): sound at background level plays at
 * {@link VoiceBudget#AUDIBLE} relative to {@link #ENGINE_LUFS}, so game audibility threshold matches real life;
 * hence {@link #ENGINE_LUFS} corresponds to {@link #REF} dBA.</li>
 * <li>Ceiling is {@link #CEILING_LUFS}: a near blast at 1 psi ({@code BlastMix.NEAR_LUFS}), nothing in game is
 * louder; an engine reaches it at {@link #CEILING_LEVEL} dBA. Engine and launch files are built at the ceiling
 * ({@link #MOTOR_LUFS}), so volume grows with level until then, not clipping at file loudness: 3.5.0 files were
 * at {@link #ENGINE_LUFS}, and anything above {@link #REF} dBA sounded the same: missile and SAM ignition impact
 * closer than ~45 m to launcher, booster roar closer than ~20 m.</li>
 * <li>Sound sinks into background over the final {@link Propagation#FADE} dB above it in its octave (partial masking):
 * daytime and rain have higher background, and the same sound is audible over a shorter range.</li>
 * </ul>
 * Gain {@link #gain} is relative to {@link #ENGINE_LUFS} (also used by {@link VoiceBudget} to select layers);
 * Minecraft receives {@link #volume}: the same adjusted for file loudness.
 */
final class Loudness {
    /**
     * Loudness reference, LUFS (maximum momentary): sound at ear level {@link #REF} plays this loud — same value as
     * {@code ENGINE_LUFS} in {@code tools/build_sounds.py} (checked by {@code LoudnessTest}). presentation: mixing
     * reference, not SPL.
     */
    static final double ENGINE_LUFS = -19;
    /** Game loudness ceiling, LUFS: near blast at 1 psi ({@code BlastMix.NEAR_LUFS}, checked by {@code LoudnessTest}; presentation). */
    static final double CEILING_LUFS = -13.5;
    /**
     * Engine-loop and launch one-shot file loudness, LUFS ({@code MOTOR_LUFS} in {@code tools/build_sounds.py},
     * {@code SoundAssetsTest} measures actual files): at the ceiling so game volume does not hit file limit before it.
     */
    static final double MOTOR_LUFS = CEILING_LUFS;
    /**
     * MLRS launch rail exit file loudness, LUFS ({@code ROCKET_LAUNCH_LUFS} in build; presentation): a short impact
     * cannot reach ceiling without compression — 2 dB lower, hits file limit from
     * {@code REF + 2·(−15.5 − ENGINE_LUFS)} ≈ 133 dBA at ear. Booster separation bang and catapult are at
     * {@link #ENGINE_LUFS}: neither exceeds {@link #REF} even at one metre ({@code LoudnessTest}).
     */
    static final double ROCKET_LAUNCH_LUFS = MOTOR_LUFS - 2;

    /** A-level at the ear that plays at {@link #ENGINE_LUFS}, dBA. */
    static final double REF = Propagation.NIGHT + 40 * Math.log10(1 / VoiceBudget.AUDIBLE);
    /** A-level at the ear from which sound plays at ceiling {@link #CEILING_LUFS}, dBA. */
    static final double CEILING_LEVEL = REF + 2 * (CEILING_LUFS - ENGINE_LUFS);
    /** Ceiling gain relative to {@link #ENGINE_LUFS}. */
    static final double TOP = Math.pow(10, (CEILING_LUFS - ENGINE_LUFS) / 20);

    private Loudness() {}

    /**
     * Gain multiplier bringing a {@code lufs} file to {@link #ENGINE_LUFS}: ear level sets game volume, not how
     * loudly the file was built.
     */
    static double fileGain(double lufs) {
        return Math.pow(10, (ENGINE_LUFS - lufs) / 20);
    }

    /**
     * Game gain for sound with ear A-level {@code level}, {@code margin} dB above background in the best octave
     * ({@link Propagation#margin}), relative to {@link #ENGINE_LUFS}, at most ceiling {@link #TOP}.
     */
    static double gain(double level, double margin) {
        return gain(level, margin, CEILING_LUFS);
    }

    /** Same with ceiling {@code ceilingLufs} (nuke keeps old {@link #ENGINE_LUFS}: its sound is unchanged). */
    static double gain(double level, double margin, double ceilingLufs) {
        if (!(margin > 0)) return 0;
        double top = Math.pow(10, (ceilingLufs - ENGINE_LUFS) / 20);
        return Math.min(top, Math.pow(10, (level - REF) / 40)) * Propagation.smoothstep(0, Propagation.FADE, margin);
    }

    /** Minecraft volume (0..1) for gain {@code gain} ({@link #gain}) with a {@code fileLufs} file. */
    static float volume(double gain, double fileLufs) {
        return (float) Math.min(1, gain * fileGain(fileLufs));
    }

    /** Minecraft volume for {@code gain} with an engine or launch file ({@link #MOTOR_LUFS}). */
    static float motor(double gain) {
        return volume(gain, MOTOR_LUFS);
    }
}
