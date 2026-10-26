package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.sound.Spectrum;
import ua.zentix.incoming.strike.WeaponSpec;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Continuous projectile sound: engine receives position, volume and pitch from us each tick.
 * Position and velocity come from the “retarded” instant (sound travels to the ear at 343 m/s, {@link Emission}),
 * giving Doppler and delay. Volume follows sound power of the projectile's noisy part ({@link Noise} spec: engine,
 * booster, airflow, drilling — from its speed and RPM) and path to the ear ({@link Propagation}: spreading, air,
 * ground, daytime shadow; background), mapped to the game by {@link Loudness}; a hill or house between projectile
 * and ear muffles, and the path absorbs highs ({@link SoundFilters}).
 * <p>
 * A layer plays (holds an OpenAL channel) only while audible: {@link ClientSounds} starts and releases it according
 * to {@link VoiceBudget}; a released layer fades and stops itself. A returning layer is a new sound, rising from
 * zero with the same smoothing, without a click.
 */
public final class EngineSound extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /**
     * Distant recordings are near recordings after this many metres of air ({@code tools/build_sounds.py}:
     * {@code FAR_M}): beyond this, a near recording with EFX no longer resembles distant sound (EFX muffles highs
     * with one shelf, air by octave), so near yields to far between half this distance and the full distance.
     */
    static final double FAR_AIR = 500;

    /**
     * Sound layers: recording, which projectile noise part it plays ({@link Noise.Part}) and how it shares that
     * part with other recordings — near/far, front/rear (power shares, {@link #share}).
     */
    public enum Layer {
        DRONE_NEAR(() -> ModSounds.DRONE_ENGINE.get(), Noise.Part.ENGINE, 0),
        DRONE_FAR(() -> ModSounds.DRONE_ENGINE_FAR.get(), Noise.Part.ENGINE, FAR_AIR),
        /** Cruise missile nearby: fan whistle in front, jet roar behind, piercing whistle in a dive. */
        MISSILE_FRONT(() -> ModSounds.MISSILE_ENGINE.get(), Noise.Part.ENGINE, 0),
        MISSILE_REAR(() -> ModSounds.MISSILE_ENGINE_REAR.get(), Noise.Part.ENGINE, 0),
        MISSILE_DIVE(() -> ModSounds.MISSILE_DIVE.get(), Noise.Part.ENGINE, 0),
        /** Far sideways and behind — jet rumble. */
        MISSILE_FAR(() -> ModSounds.MISSILE_ENGINE_FAR.get(), Noise.Part.ENGINE, FAR_AIR),
        /** Far ahead — fan whine: projects forward, audible from afar on approach. */
        MISSILE_WHISTLE(() -> ModSounds.MISSILE_WHISTLE.get(), Noise.Part.ENGINE, 0),
        BOMBER_NEAR(() -> ModSounds.BOMBER_ENGINE.get(), Noise.Part.ENGINE, 0),
        BOMBER_FAR(() -> ModSounds.BOMBER_ENGINE_FAR.get(), Noise.Part.ENGINE, FAR_AIR),
        BOMB_NEAR(() -> ModSounds.BOMB_FALL.get(), Noise.Part.BODY, 0),
        BOMB_FAR(() -> ModSounds.BOMB_FALL_FAR.get(), Noise.Part.BODY, FAR_AIR),
        BOMB_DRILL(() -> ModSounds.BOMB_DRILL.get(), Noise.Part.DRILL, 0),
        /** Shahed and missile launch boosters, ICBM and MLRS rocket engines; same layer also marks ignition and separation. */
        BOOSTER(() -> ModSounds.BOOSTER_ENGINE.get(), Noise.Part.MOTOR, 0),
        /** MLRS rocket: airflow howl (under engine roar while burning, alone afterward). */
        ROCKET_AIR(() -> ModSounds.ROCKET_INCOMING.get(), Noise.Part.BODY, 0),
        /** Loitering munition: electric motor and propeller near and far, airflow (louder than motor in a dive). */
        LOITER_NEAR(() -> ModSounds.LOITER_ENGINE.get(), Noise.Part.ENGINE, 0),
        LOITER_FAR(() -> ModSounds.LOITER_ENGINE_FAR.get(), Noise.Part.ENGINE, FAR_AIR),
        LOITER_DIVE(() -> ModSounds.LOITER_DIVE.get(), Noise.Part.BODY, 0),
        /** SAM missile engine: no weapon assigned, law from SAM spec ({@link InterceptorSound}). */
        SAM(() -> ModSounds.BOOSTER_ENGINE.get(), Noise.Part.MOTOR, 0);

        /** Registry sound is fetched only when a layer starts: volume and pitch also compute without registries (unit tests). */
        final Supplier<SoundEvent> event;
        /** Which noise part it plays. */
        final Noise.Part part;
        /** Metres of air already in the recording ({@link #FAR_AIR} for far, 0 for near). */
        final double air;

        Layer(Supplier<SoundEvent> event, Noise.Part part, double air) {
            this.event = event;
            this.part = part;
            this.air = air;
        }

        /** Layer volume and pitch for what the listener hears now (before smoothing). */
        Tone tone(FlightTrack track, Emission e) {
            if (this == SAM) return InterceptorSound.tone(e);
            Noise noise = noise(track);
            boolean drilling = e.phase() == FlightPhase.DRILL.ordinal();
            Emission.Heard heard = e.heard(noise, part, drilling);
            if (heard.level() == Noise.SILENT) return Tone.SILENT;
            FlightPhase ph = FlightPhase.byId(e.phase());
            double d = e.distance(), age = e.phaseAge(), v = e.velocity().length();
            // directivity applies to power before volume conversion: airflow and fan noise project forward
            double level = heard.level();
            double share = 1;
            double pitch = e.doppler();
            switch (this) {
                case DRONE_NEAR, DRONE_FAR, LOITER_NEAR, LOITER_FAR, BOMBER_NEAR, BOMBER_FAR -> {
                    share = share(this.air > 0 ? 1 - near(d) : near(d));
                    pitch *= noise.engine().revs(ph, age, v);
                }
                case MISSILE_FRONT, MISSILE_REAR, MISSILE_DIVE -> {
                    Vec3 f = track.forward(e.time());
                    double front = (1 + f.x * e.nx() + f.y * e.ny() + f.z * e.nz()) / 2;
                    boolean dive = ph == FlightPhase.TERMINAL;
                    double w = switch (this) {
                        case MISSILE_FRONT -> dive ? 0 : front;
                        case MISSILE_DIVE -> dive ? front : 0;
                        default -> 1 - front;
                    };
                    share = share(near(d)) * share(w);
                    pitch *= noise.engine().revs(ph, age, v);
                }
                case MISSILE_FAR, MISSILE_WHISTLE -> {
                    double ahead = e.ahead();
                    share = share(1 - near(d)) * share(this == MISSILE_WHISTLE ? ahead : 1 - ahead);
                    pitch *= noise.engine().revs(ph, age, v);
                }
                case BOMB_NEAR, BOMB_FAR -> {
                    share = share(this.air > 0 ? 1 - near(d) : near(d));
                    level += airflow(e);
                    pitch *= noise.body().pitch(v);
                }
                case ROCKET_AIR, LOITER_DIVE -> {
                    level += airflow(e);
                    pitch *= noise.body().pitch(v);
                }
                case BOMB_DRILL -> pitch = 0.85;
                case BOOSTER -> pitch *= ClientWeaponSpec.of(track.weapon).booster().pitch();
            }
            double gain = Loudness.gain(level, heard.margin() + level - heard.level(), ceiling(track)) * share;
            // distant recording already passed through FAR_AIR of air: filter only the excess
            double highs = this.air > 0 ? Math.min(1, heard.highs() / e.heardAt(noise, part, drilling, this.air)) : heard.highs();
            // projectile camera: listener sits on it — engine at point-blank range, but not deafening (not physical: camera
            // is operator's view, not ears on the body)
            if (e.onboard()) gain *= 0.5;
            // Minecraft pitch limit is 0.5…2 (SoundEngine): pitch cannot go beyond it
            return new Tone(gain, Math.max(0.5, Math.min(2.0, pitch)), highs);
        }
    }

    /**
     * Projectile loudness ceiling, LUFS: {@link Loudness#CEILING_LUFS} for all, old {@link Loudness#ENGINE_LUFS} for
     * ICBM (nuclear strike unchanged; under the same law its launch near the pad would be up to 5.5 dB louder).
     */
    static double ceiling(FlightTrack track) {
        return ClientWeaponSpec.of(track.weapon).ceilingLufs();
    }

    /** Noise of this path's projectile: craft spec (B-2 uses aircraft, its bomb uses bomb). */
    static Noise noise(FlightTrack track) {
        WeaponSpec spec = track.weapon.spec();
        return !track.bomber && spec.payload() != null ? spec.payload().noise() : spec.airframe().noise();
    }

    /**
     * Airflow-noise directivity, dB: loudest on approach, trailing at 0.3 power (noise projects forward; fraction is
     * an estimate for wake vortices).
     */
    static double airflow(Emission e) {
        double v = e.velocity().length();
        if (v < 1.0e-6) return 0;
        double front = (1 + e.radial() / v) / 2;
        return 10 * Math.log10(0.3 + 0.7 * front * front);
    }

    /** Layer volume, pitch and high fraction this tick. */
    record Tone(double gain, double pitch, double highs) {
        static final Tone SILENT = new Tone(0, 1, 1);
    }

    private final FlightTrack track;
    private final Occlusion occlusion;
    private final Layer layer;
    private float smoothVolume;
    private float filterGain = 1, filterHighs = 1;
    /** What is heard this tick ({@code null} — no projectile data at this time) and layer volume and pitch. */
    private Emission heard;
    private Tone tone;
    /** Released ({@link VoiceBudget}): fades and stops. */
    private boolean released;

    EngineSound(FlightTrack track, Occlusion occlusion, Layer layer) {
        super(layer.event.get(), SoundSource.AMBIENT, RandomSource.create());
        this.track = track;
        this.occlusion = occlusion;
        this.layer = layer;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = false;
        this.volume = 0;
        this.pitch = 1;
    }

    /**
     * Layer gain relative to {@link Loudness#ENGINE_LUFS} ({@link Loudness#gain}, smoothed): compared against
     * {@link VoiceBudget#AUDIBLE}; Minecraft volume ({@code getVolume}) is the same adjusted for file loudness.
     */
    public double loudness() {
        return smoothVolume;
    }

    String describe() {
        // source filter: gain and high fraction behind barrier and air (1 — open)
        return String.format(Locale.ROOT, "%s[f %.2f/%.2f]", layer.name().toLowerCase(Locale.ROOT), filterGain, filterHighs);
    }

    /** What listener hears this tick: {@code heard} is {@code null} if no projectile data exists for this time. */
    void aim(Emission heard, Tone tone) {
        this.heard = heard;
        this.tone = tone;
    }

    /** Release channel: layer fades and stops itself. */
    void release() {
        released = true;
    }

    /** Needed again before fully silent: do not stop. */
    void keep() {
        released = false;
    }

    void kill() {
        stop();
    }

    @Override
    public float lowpassGain() {
        return filterGain;
    }

    @Override
    public float lowpassHighs() {
        return filterHighs;
    }

    @Override
    public void tick() {
        // vanilla keeps ticking a stopped sound without a channel: zero category slider removes channel but leaves sound
        // in tickingSounds (SoundEngine.tickNonPaused) — no more volume or barrier calculations needed for it
        if (isStopped()) return;
        if (heard == null) {
            // no projectile data at this time (exploded or left hearing range): fade without moving source abruptly
            smoothVolume *= 0.5f;
            this.volume = Loudness.motor(smoothVolume);
        } else {
            // smoothing: layer and perspective changes, layer start and release, ducking under blasts — no clicks
            double gain = released ? 0 : tone.gain() * ClientSounds.duck();
            smoothVolume += (float) ((gain - smoothVolume) * 0.35);
            this.volume = Loudness.motor(smoothVolume);
            this.pitch = (float) tone.pitch();
            this.x = heard.x();
            this.y = heard.y();
            this.z = heard.z();
            if (smoothVolume > VoiceBudget.AUDIBLE) {
                Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                float open = occlusion.open(ClientSounds.now(), ear, heard.position());
                filterGain = SoundFilters.blockedGain(open);
                filterHighs = (SoundFilters.pathEffects() ? (float) tone.highs() : 1) * SoundFilters.blockedHighs(open);
                SoundFilters.update(this, filterGain, filterHighs);
            }
        }
        if (released && smoothVolume <= VoiceBudget.AUDIBLE) stop();
    }

    /**
     * Launch one-shots when their front reaches the ear: booster ignition (impact and roar at launcher) and
     * separation (squib bang and lock clank), MLRS — each rocket's tube exit, loitering munition — catapult. Volume
     * follows event sound power (booster at full thrust, catapult and separation from {@link Noise} spec) and path
     * to ear. Called when listener hears a phase change: {@code prev} is previously heard phase, −1 — none yet.
     * ICBM has its own launch sound ({@code NukeSounds}).
     */
    static void launchEvents(FlightTrack track, Emission e, int prev) {
        ClientWeaponSpec spec = ClientWeaponSpec.of(track.weapon);
        if (spec.launch() == ClientWeaponSpec.LaunchCue.NONE) return;
        Noise noise = noise(track);
        FlightPhase ph = FlightPhase.byId(e.phase());
        double age = e.phaseAge();
        Vec3 at = e.position();
        // ignition: phase change or projectile entered view already igniting
        if (ph == FlightPhase.IGNITION && (prev >= 0 || age < 5)) {
            double motor = noise.motor() == null ? Noise.SILENT : noise.motor().power(FlightPhase.BOOST, 0);
            switch (spec.launch()) {
                case ROCKET_TUBE -> {
                    // rocket rail exit: sharp “fsh-sh” with crackle; half-second cadence merges into salvo roar
                    double v = shot(motor, Spectra.ROCKET_LAUNCH, e);
                    if (v > 0.01) ClientSounds.atEar(ModSounds.ROCKET_LAUNCH.get(), at, Loudness.volume(v, Loudness.ROCKET_LAUNCH_LUFS), 0.94f + (float) Math.random() * 0.12f);
                }
                case CATAPULT -> {
                    // loitering munition catapult: piston impact and guide whistle
                    double v = shot(noise.catapult() - Spectra.LOITER_LAUNCH.aWeighting(), Spectra.LOITER_LAUNCH, e);
                    if (v > 0.01) ClientSounds.atEar(ModSounds.LOITER_LAUNCH.get(), at, Loudness.volume(v, Loudness.ENGINE_LUFS), 0.95f + (float) Math.random() * 0.1f);
                }
                case BOOSTER, BOOSTER_LOW -> {
                    double v = shot(motor, Spectra.LAUNCH_BOOSTER, e);
                    if (v > 0.01) ClientSounds.atEar(ModSounds.LAUNCH_BOOSTER.get(), at, Loudness.motor(v), spec.launch() == ClientWeaponSpec.LaunchCue.BOOSTER_LOW ? 0.92f : 1.05f);
                }
                case NONE -> {}
            }
        } else if (ph == FlightPhase.CLIMB && prev >= 0 && FlightPhase.byId(prev).boosterLit() && spec.separates()) {
            double v = shot(noise.separation() - Spectra.BOOSTER_SEPARATE.aWeighting(), Spectra.BOOSTER_SEPARATE, e);
            if (v > 0.01) ClientSounds.atEar(ModSounds.BOOSTER_SEPARATE.get(), at, Loudness.volume(v, Loudness.ENGINE_LUFS), 1);
        }
    }

    /** Gain ({@link Loudness#gain}) of a one-shot with power {@code lw} (unweighted), recording spectrum, at this ear. */
    private static double shot(double lw, Spectrum spectrum, Emission e) {
        if (Double.isNaN(lw) || lw == Noise.SILENT) return 0;
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(lw, spectrum, e.path(e.distance()), bands);
        double v = Loudness.gain(level, Propagation.margin(bands, e.weather()));
        return e.onboard() ? v * 0.5 : v;
    }

    /** Near recording weight: 1 closer than half {@link #FAR_AIR}, 0 from {@link #FAR_AIR} itself. */
    static double near(double d) {
        return 1 - Acoustics.smoothstep(FAR_AIR / 2, FAR_AIR, d);
    }

    /**
     * Layer share in transitions between different recordings (near/far, front/rear): shares {@code w} and
     * {@code 1 − w} sum by power (square root), not amplitude. Recordings differ and are uncorrelated, so amplitude
     * weights 0.5 and 0.5 make the sum 3 dB quieter: a missile would dip on an overhead pass and at the near-to-far
     * rumble transition, becoming quieter while approaching.
     */
    static double share(double w) {
        return Math.sqrt(Math.max(0, w));
    }
}
