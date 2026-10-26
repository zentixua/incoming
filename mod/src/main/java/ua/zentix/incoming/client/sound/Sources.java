package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.Hearing;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * World sounds other than engines and blasts follow source law, like engines ({@code sound/Noise}): source sound
 * power (A-weighted L<sub>WA</sub>, dBA) from its spec below, recording octave spectrum and file loudness
 * ({@link Spectra}, measured by {@code tools/build_sounds.py}); ISO 9613 path to ear ({@link Propagation}: spreading,
 * air, ground, daytime shadow, octave background), game volume from {@link Loudness}, file adjusted to one
 * {@link Loudness#ENGINE_LUFS} reference. No manual ranges: power, path and background decide audibility. Sound
 * arrives at sound speed ({@link #play}).
 * <p>
 * Numbers have sources; without measurements, marked (est.) as order-of-magnitude estimates.
 */
public final class Sources {
    /**
     * Source: A-weighted sound power level, dBA; recording spectrum and loudness, LUFS; game loudness ceiling,
     * LUFS ({@link Loudness#CEILING_LUFS}; nuclear-strike sounds keep old {@link Loudness#ENGINE_LUFS}: unchanged).
     */
    public record Source(double lwA, Spectrum spectrum, double fileLufs, double ceilingLufs) {
        Source(double lwA, Spectrum spectrum, double fileLufs) {
            this(lwA, spectrum, fileLufs, Loudness.CEILING_LUFS);
        }

        /** Same source at different power (source law computes it from its own numbers). */
        public Source power(double lwA) {
            return new Source(lwA, spectrum, fileLufs, ceilingLufs);
        }
    }

    /**
     * Civil-defense siren (electromechanical, outdoor): outdoor warning sirens give 100–130 dB(C) at 100 feet
     * (30.5 m) on axis (FEMA CPG 1-17, “Outdoor Warning Systems Guide”, 1980); use 115 dB(C), middle of large-city
     * class; siren tone (400–600 Hz) A ≈ C − 3: L<sub>WA</sub> = 112 + 20·lg 30.5 + 11 ≈ 153 dBA (est.) (mod
     * city's siren type unspecified). Rotor rotates — louder on axis, quieter aside; here on-axis, no averaging.
     */
    public static final Source SIREN = new Source(153, Spectra.SIREN, Spectra.SIREN_LUFS);
    /** Nuclear alarm siren — same outdoor siren ({@link #SIREN}), own recording. */
    public static final Source NUKE_ALARM = new Source(153, Spectra.NUKE_ALARM, Spectra.NUKE_ALARM_LUFS, Loudness.ENGINE_LUFS);
    /**
     * Substation arc short circuit: (est.) ~140 dBA (~120 dBA at 10 m; no public sound measurements found for
     * distribution-substation arc faults — order of nearby thunder, whose recording was used to build it).
     */
    public static final Source GRID_FAIL = new Source(140, Spectra.GRID_FAIL, Spectra.GRID_FAIL_LUFS);
    /** Disabled substation arc crackle: (est.) ~115 dBA (~95 dBA at 1 m). */
    public static final Source GRID_SPARK = new Source(115, Spectra.GRID_SPARK, Spectra.GRID_SPARK_LUFS);
    /**
     * Operating substation transformer hum: 84 dBA sound power (est.). Eaton, “Three-phase pad-mounted
     * compartmental type transformer”, CA202003EN (April 2016), p. 3, table 4, reproduces the NEMA TR 1 average sound-level limit
     * of 68 dB for self-cooled 7501–10000 kVA units. This is a limit, not a measurement of our transformer.
     * Assuming that surface level and a 40 m² measurement surface (est.) gives L_WA = 68 + 10·lg 40 ≈ 84 dBA.
     */
    public static final Source GRID_HUM = new Source(84, Spectra.GRID_HUM, Spectra.GRID_HUM_LUFS);
    /** City block loses or regains power — breaker click and hum dying or starting: (est.) ~100 dBA at its centre. */
    public static final Source GRID_POWER_DOWN = new Source(100, Spectra.GRID_POWER_DOWN, Spectra.GRID_POWER_DOWN_LUFS);
    public static final Source GRID_POWER_UP = new Source(100, Spectra.GRID_POWER_UP, Spectra.GRID_POWER_UP_LUFS);
    /** House collapse in nuclear ruins: (est.) ~125 dBA (building demolition ~100–110 dBA at 100 m around it). */
    public static final Source NUKE_COLLAPSE = new Source(125, Spectra.NUKE_COLLAPSE, Spectra.NUKE_COLLAPSE_LUFS, Loudness.ENGINE_LUFS);
    /** Debris fall in ruins: (est.) ~110 dBA. */
    public static final Source RUIN_DEBRIS = new Source(110, Spectra.DEBRIS_FALL, Spectra.DEBRIS_FALL_LUFS, Loudness.ENGINE_LUFS);
    /**
     * ICBM launch — recording of pad launch; power from first-stage solid motor law in spec
     * ({@link Noise.Rocket#power}, {@code WeaponSpec.ICBM_NOISE}); only recording here.
     */
    public static final Source NUKE_LAUNCH = new Source(Double.NaN, Spectra.NUKE_LAUNCH, Spectra.NUKE_LAUNCH_LUFS, Loudness.ENGINE_LUFS);

    /**
     * Flame noise: 10⁻⁶ of open-flame heat release becomes sound (est.; model efficiency, not a verified
     * measured range). Strahle, “Combustion noise”, Prog. Energy Combust. Sci. 4 (1978), 157–176,
     * doi:10.1016/0360-1285(78)90002-3, reviews the mechanism; its accessible abstract supplies no universal
     * heat-to-sound efficiency for this pool fire.
     */
    public static final double FLAME_EFFICIENCY = 1e-6;
    /** Crater-fire roar: fire recording, power from pool heat ({@link #flame}). */
    public static final Source BLAST_FIRE = new Source(Double.NaN, Spectra.BLAST_FIRE, Spectra.BLAST_FIRE_LUFS);
    /**
     * This fraction of debris and ground falling energy becomes sound: stone impacts on ground and each other —
     * (est.) 10⁻⁴ (solid-body impacts 10⁻⁴–10⁻³, order of magnitude).
     */
    public static final double IMPACT_EFFICIENCY = 1e-4;
    /** Crater ejecta fall: recording, power from mass and fall height ({@link #fall}). */
    public static final Source EJECTA = new Source(Double.NaN, Spectra.DEBRIS_FALL, Spectra.DEBRIS_FALL_LUFS);
    /**
     * Glass blown out by nuclear wave near listener: recording of windows blown out along a street, power from
     * shard energy ({@link Clatter#watts}). Nuclear ceiling ({@link Loudness#ENGINE_LUFS}).
     */
    public static final Source RUIN_GLASS = new Source(Double.NaN, Spectra.NUKE_GLASS, Spectra.NUKE_GLASS_LUFS, Loudness.ENGINE_LUFS);
    /** Debris torn off by nuclear wave near listener: fall recording, power from debris energy ({@link Clatter#watts}). */
    public static final Source RUIN_CLATTER = new Source(Double.NaN, Spectra.DEBRIS_FALL, Spectra.DEBRIS_FALL_LUFS, Loudness.ENGINE_LUFS);
    /**
     * Burning-city roar: large-fire recording, power from burning heat ({@link #flame}: model storm and fire in
     * local chunks, {@code NukeAfter}).
     */
    public static final Source NUKE_FIRE = new Source(Double.NaN, Spectra.NUKE_FIRE, Spectra.NUKE_FIRE_LUFS, Loudness.ENGINE_LUFS);

    /** Sound still travelling to ear: event, position, pitch, source, ticks in transit, who plays it. */
    private record Pending(SoundEvent event, Vec3 at, float pitch, Source source, ClientLevel level, long born, @Nullable Player player) {}

    /** Plays sound when its front reaches ear, at that instant's volume (default: world one-shot). */
    @FunctionalInterface
    interface Player {
        void play(SoundEvent event, Vec3 at, float volume, float pitch);
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static long ticks;

    private Sources() {}

    /** Flame A-weighted power level for heat release {@code watts}, W ({@link #FLAME_EFFICIENCY}), dBA from recording spectrum. */
    public static double flame(double watts, Spectrum spectrum) {
        return lw(FLAME_EFFICIENCY * watts) + spectrum.aWeighting();
    }

    /**
     * Fall A-weighted power level: {@code kg} mass falls from {@code height} m over {@code seconds} s
     * ({@link #IMPACT_EFFICIENCY}), dBA from recording spectrum.
     */
    public static double fall(double kg, double height, double seconds, Spectrum spectrum) {
        return lw(IMPACT_EFFICIENCY * kg * 9.81 * height / Math.max(seconds, 0.1)) + spectrum.aWeighting();
    }

    /** Power level, dB re 1 pW. */
    public static double lw(double watts) {
        return watts > 0 ? 10 * Math.log10(watts / 1e-12) : Noise.SILENT;
    }

    /**
     * Game volume of source {@code s} at {@code at}, ear {@code ear}: ear level along ISO 9613 path against
     * background ({@link Loudness#gain}, no louder than source ceiling), file adjusted to
     * {@link Loudness#ENGINE_LUFS}. Barriers are not here (sound applies them itself).
     */
    public static float volume(Source s, Vec3 at, Vec3 ear, Propagation.Weather weather) {
        return Loudness.volume(gain(s, at, ear, weather), s.fileLufs);
    }

    /**
     * Source {@code s} gain at ear in shared calibrated measure — relative to {@link Loudness#ENGINE_LUFS},
     * without file ({@link Loudness#gain}): 0 — every octave below background, inaudible. Determines audibility;
     * file volume ({@link #volume}) is for playback only: remixing changes it, whereas audibility belongs to
     * sound at the ear.
     */
    static double gain(Source s, Vec3 at, Vec3 ear, Propagation.Weather weather) {
        if (Double.isNaN(s.lwA) || s.lwA == Noise.SILENT) return 0;
        double[] bands = new double[Propagation.BANDS];
        Propagation.Path path = Propagation.Path.over(at.distanceTo(ear), Hearing.height(at, ear), weather);
        double level = Propagation.level(s.lwA - s.spectrum.aWeighting(), s.spectrum, path, bands);
        return Loudness.gain(level, Propagation.margin(bands, weather), s.ceilingLufs);
    }

    /** Current volume at camera ear (0 — no world). */
    public static float volume(Source s, Vec3 at) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return 0;
        return volume(s, at, mc.gameRenderer.getMainCamera().getPosition(), ua.zentix.incoming.util.Air.weather(mc.level));
    }

    /**
     * One-shot of source {@code s} at {@code at}: plays when its front reaches ear (sound speed
     * {@link Noise#SOUND}), at that instant's path-based volume; below audible threshold — skipped.
     */
    public static void play(SoundEvent event, Vec3 at, Source s, float pitch) {
        play(event, at, s, pitch, null);
    }

    /** Like {@link #play(SoundEvent, Vec3, Source, float)}, but {@code player} plays the sound reaching ear. */
    static void play(SoundEvent event, Vec3 at, Source s, float pitch, @Nullable Player player) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PENDING.add(new Pending(event, at, pitch, s, mc.level, ticks, player));
        tick(mc);
    }

    /** Once per client tick ({@link ClientSounds#tick}): sounds whose front reached ear. */
    static void tick() {
        ticks++;
        tick(Minecraft.getInstance());
    }

    private static void tick(Minecraft mc) {
        if (PENDING.isEmpty()) return;
        if (mc.level == null) {
            PENDING.clear();
            return;
        }
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        Propagation.Weather weather = ua.zentix.incoming.util.Air.weather(mc.level);
        for (Iterator<Pending> it = PENDING.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            double d = ear.distanceTo(p.at), path = (ticks - p.born) * Noise.SOUND / 20;
            // world changed or sound travelled beyond client's remembered sound path — forget
            if (p.level != mc.level || path > Hearing.RANGE) {
                it.remove();
                continue;
            }
            if (path < d) continue;
            it.remove();
            float v = volume(p.source, p.at, ear, weather);
            if (v < VoiceBudget.AUDIBLE) continue;
            if (p.player == null) ClientSounds.atEar(p.event, p.at, v, p.pitch);
            else p.player.play(p.event, p.at, v, p.pitch);
        }
    }

    /** World exit, rewind: no sounds in transit. */
    static void reset() {
        PENDING.clear();
    }
}
