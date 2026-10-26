package ua.zentix.incoming.client.sound;

import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.sound.Air;

/**
 * What a conventional blast sounds like at distance d, and how loud — from charge and pressure at the ear
 * (without Minecraft, covered by a unit test).
 * <ul>
 * <li>Timbre and duration follow Sachs scaling: a charge W's wave at distance R is the same as charge W₀'s wave at
 * R·∛(W₀/W), compressed in time by ∛(W₀/W). Perspective recordings ({@code tools/build_sounds.py}, blasts section)
 * play as recorded for charge {@link #REFERENCE_KG}; for another, ∛(W₀/W) times faster or slower: higher and shorter
 * for the Lancet, lower and longer for the missile ({@link #pitch}). Minecraft only plays pitch from 0.5 to 2
 * ({@code SoundEngine.calculatePitch}): recordings cannot be compressed beyond a 6.25 kg charge or stretched beyond
 * 400 kg — the Lancet (3 kg) sounds like a recording of a 6.25 kg charge (deviation: engine limit). The model's
 * impact at the ear ({@link BlastPulse}) has no such limit.</li>
 * <li>Volume follows peak pressure at the ear (Kinney–Graham, {@link BlastModel}): full from {@link #FULL_PA}, quieter
 * by half the pressure decibels, as with the nuclear strike ({@link Outdoor#pressureVolume}) and the distant model
 * {@link Outdoor}: far away pressure falls as 1/R — −3 dB per doubling, faster nearby.</li>
 * <li>Perspectives of one recording (near — impact and blast body, mid — recording with slope and building echoes,
 * far — the same through a kilometre of air) add by amplitude, summing to 1. Far: how much of the highs air absorbed;
 * the far perspective's share is the 4 kHz path loss (ISO 9613-1, 22.9 dB/km, the same table used to build the far
 * perspective) relative to the loss at the near model's edge ({@link Outdoor#NEAR}). There the far perspective plays
 * alone, and the distant model continues it without a step. Near or mid depends on the front at the ear: the near
 * perspective has an instantaneous pressure jump (a Friedlander pulse is embedded in it), whereas the real jump
 * rises more slowly the weaker it is ({@link BlastPulse#riseSeconds}); the near share is the jump's 2 kHz high
 * fraction ({@link #crack}). Where near fades, the model plays the impact at the ear ({@link Layers#pulse}): no
 * double impact.</li>
 * <li>The low “chest hit” is the near perspective's low end (the build split it into its own recording), with the same share.</li>
 * </ul>
 */
public final class BlastMix {
    /** Perspective file loudness, LUFS (maximum momentary) — the same numbers as in {@code tools/build_sounds.py}. */
    static final double NEAR_LUFS = -13.5, MID_LUFS = -13.5, FAR_LUFS = -15;
    /**
     * Charge for which the perspective recordings play as recorded, kg TNT. Estimate: perspectives are assembled and
     * mixed as a Shahed blast (near: 8 ms phase, “~50 kg charge at a couple of dozen metres”, {@code build_sounds.py});
     * the recordings themselves range from 4 kg detonations to quarry blasts.
     */
    public static final double REFERENCE_KG = 50;
    /** Volume is full from this peak pressure, Pa (1 psi — as with the nuclear strike, {@code NukeSounds}). */
    public static final double FULL_PA = 1000 * BlastModel.kpa(1);
    /** Pitch played by Minecraft ({@code SoundEngine.calculatePitch}). */
    static final double PITCH_MIN = 0.5, PITCH_MAX = 2;
    /**
     * Air absorption at 4 kHz under recording conditions, dB/m ({@link Air#alpha}: ISO 9613-1, 20 °C, 70 %): controls
     * the far perspective replacing nearer ones — recording calibration (far already contains its own air), not current air.
     */
    static final double AIR_4K = Air.alpha(4000);
    /** Band whose highs control the near perspective yielding to mid, Hz ({@link Outdoor} highs). */
    static final double CRACK_HZ = BlastPulse.HIGHS_HZ;

    /**
     * Layer volumes (0..1, like Minecraft sound) and impact at the ear on our own source ({@link BlastPulse}: source
     * gain, track peak 0.98).
     */
    public record Layers(float near, float mid, float far, float sub, float pulse) {}

    private BlastMix() {}

    /** Blast peak pressure at the ear, {@code d} blocks away, Pa. */
    public static double pressure(BlastSource s, double d) {
        return 1000 * BlastModel.overpressureKpa(d, s.chargeKg(), s.surface());
    }

    /** Positive phase at the ear, s. */
    public static double phase(BlastSource s, double d) {
        return BlastModel.chemicalPhaseSeconds(d, s.chargeKg(), s.surface());
    }

    /** Pitch and recording speed for charge {@code chargeKg}: ∛(W₀/W) within engine limits. */
    public static float pitch(double chargeKg) {
        if (!(chargeKg > 0)) return 1;
        return (float) Math.max(PITCH_MIN, Math.min(PITCH_MAX, Math.cbrt(REFERENCE_KG / chargeKg)));
    }

    /** Direct-sound loudness at the ear, LUFS: near perspective from {@link #FULL_PA}, quieter by half the pressure decibels. */
    public static double loudness(double pa) {
        return NEAR_LUFS + 10 * Math.log10(Math.max(1e-12, Math.min(1, pa / FULL_PA)));
    }

    /**
     * High fraction of the pressure jump at the ear at {@link #CRACK_HZ}: Taylor front ½(1 + th(t/θ)) — spectrum
     * (πωθ/2)/sh(πωθ/2) relative to a jump, θ = 10–90 % rise / (2·arth 0.8). For 50 kg, almost all up to ~200 m
     * (a jump), one tenth by ~900 m.
     */
    public static double crack(double pa) {
        double theta = BlastPulse.riseSeconds(pa) / (2 * 1.0986), x = Math.PI * 2 * Math.PI * CRACK_HZ * theta / 2;
        return x < 1e-6 ? 1 : x / Math.sinh(Math.min(x, 700));
    }

    /** Far-perspective share at distance {@code d}: 4 kHz air loss relative to that at {@link Outdoor#NEAR}. */
    public static double far(double d) {
        double lost = 1 - Math.pow(10, -AIR_4K * Math.max(0, d) / 20), edge = 1 - Math.pow(10, -AIR_4K * Outdoor.NEAR / 20);
        return Math.min(1, lost / edge);
    }

    /** Far-perspective volume at the near model's edge ({@link Outdoor#NEAR}): {@link Outdoor} continues from here. */
    public static float v640(BlastSource s) {
        return gain(loudness(pressure(s, Outdoor.NEAR)) - FAR_LUFS);
    }

    /** Layers of blast {@code s} at distance {@code d} blocks. */
    public static Layers at(BlastSource s, double d) {
        double pa = pressure(s, d), l = loudness(pa), far = far(d), crack = crack(pa);
        double near = (1 - far) * crack, mid = (1 - far) * (1 - crack);
        float nearV = (float) near * gain(l - NEAR_LUFS);
        return new Layers(nearV, (float) mid * gain(l - MID_LUFS), (float) far * gain(l - FAR_LUFS), nearV,
                (float) ((1 - near) * Math.sqrt(Math.min(1, pa / FULL_PA))));
    }

    /** Volume multiplier for a difference of db, at most 1 (Minecraft cannot play louder than the file). */
    static float gain(double db) {
        return (float) Math.min(1, Math.pow(10, db / 20));
    }

    /** Unbounded volume multiplier for a difference of db (our own OpenAL sources may exceed unity). */
    static float uncapped(double db) {
        return (float) Math.pow(10, db / 20);
    }
}
