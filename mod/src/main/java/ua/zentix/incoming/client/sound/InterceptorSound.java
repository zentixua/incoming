package ua.zentix.incoming.client.sound;

import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.strike.Article;

/**
 * SAM engine uses the same laws as projectile engines ({@link EngineSound}): solid rocket motor sound power is a
 * fraction of jet mechanical power from spec thrust and exhaust velocity ({@link Noise.Rocket}: boost engine
 * during acceleration, sustain afterward); path to ear is {@link Propagation} (spreading, octave air, ground,
 * daytime shadow, background), game volume is {@link Loudness}; audible range follows naturally: while any octave
 * exceeds background. Recording is the booster loop ({@code booster.engine}, one for all solid rocket motors),
 * voice from engine size.
 * <p>
 * Plays as {@link EngineSound.Layer#SAM} in shared engine management ({@link ClientSounds}): path from air-defense
 * tracks ({@link ua.zentix.incoming.client.flight.FlightTracks#interceptors}: entity and server packets), emission
 * time, channels ({@link VoiceBudget}), restart after F3+T and device change, barriers — as for projectiles.
 */
public final class InterceptorSound {
    private static final InterceptorSpec SPEC = InterceptorSpec.SAM;
    /** Boost and sustain engine (game sustain, not physical — {@link InterceptorSpec.Passport#sustain}). */
    static final Noise.Rocket BOOST = rocket(SPEC.passport().boost()), SUSTAIN = rocket(SPEC.passport().sustain());
    /**
     * Recording voice: one recording for all solid rocket motors, pitch from engine size (as for
     * {@link EngineSound.Layer#BOOSTER}: Shahed and missile booster 1, Grad rocket 1.3); 9M96 (Ø 0.24 m) lies between.
     * Recording shift within ±25 %. Presentation.
     */
    static final double VOICE = 1.2;
    /** Launch rail exit sound is audible only if engine sound reached the ear from the first boost ticks (presentation). */
    static final int IGNITION_HEARD = 10;
    /**
     * Body from spec for the N-wave when missile is supersonic ({@link Booms}: flight scale up to real 9M96 speed).
     * SAM has no airflow layer: recording pitch ({@code reference}) is unnecessary.
     */
    static final Noise.Body BODY = new Noise.Body(SPEC.passport().diameter(), SPEC.passport().length(), Double.NaN, Spectra.BOMB_FALL);

    private InterceptorSound() {}

    private static Noise.Rocket rocket(Article.SolidMotor m) {
        return new Noise.Rocket(m.thrust(), m.exhaust(), Spectra.BOOSTER_ENGINE);
    }

    /** Engine sound power at emission, dB: boost engine during acceleration, sustain afterward. */
    static double power(Emission e) {
        Noise.Rocket motor = FlightPhase.byId(e.phase()).boosterLit() ? BOOST : SUSTAIN;
        return motor.power(FlightPhase.BOOST, e.phaseAge());
    }

    /** Engine volume, pitch and high fraction at the ear from what the listener hears now. */
    static EngineSound.Tone tone(Emission e) {
        double lw = power(e);
        if (lw == Noise.SILENT) return EngineSound.Tone.SILENT;
        Propagation.Path path = e.path(e.distance());
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(lw, Spectra.BOOSTER_ENGINE, path, bands);
        double gain = Loudness.gain(level, Propagation.margin(bands, e.weather()));
        // Minecraft pitch limit is 0.5…2 (SoundEngine)
        double pitch = Math.max(0.5, Math.min(2, VOICE * e.doppler()));
        return new EngineSound.Tone(gain, pitch, Emission.highs(lw, Spectra.BOOSTER_ENGINE, path));
    }

    /**
     * Engine first audible at the ear: if it is the launch rail exit sound (not mid-flight — missile appeared on
     * client already travelling), ignition impact works like projectile boosters ({@code EngineSound.launchEvents}):
     * booster launch recording ({@code launch.booster}) with volume from full-thrust boost-engine sound power and
     * path to ear, with that engine's voice.
     */
    static void heard(Emission e) {
        if (!FlightPhase.byId(e.phase()).boosterLit() || e.phaseAge() >= IGNITION_HEARD) return;
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(BOOST.power(FlightPhase.BOOST, 0), Spectra.LAUNCH_BOOSTER, e.path(e.distance()), bands);
        double v = Loudness.gain(level, Propagation.margin(bands, e.weather()));
        if (v > 0.01) ClientSounds.atEar(ModSounds.LAUNCH_BOOSTER.get(), new Vec3(e.x(), e.y(), e.z()), Loudness.motor(v), (float) VOICE);
    }
}
