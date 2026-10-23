package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.defense.InterceptorSpec;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.entity.InterceptorEntity;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.strike.Article;
import ua.zentix.incoming.strike.Hearing;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Мотор зенитной ракеты — те же законы, что у моторов снарядов ({@link EngineSound}): мощность звука РДТТ — доля
 * механической мощности струи по его тяге и истечению из паспорта ({@link Noise.Rocket}: на разгоне — двигатель
 * разгона, дальше — маршевый режим), путь до уха — {@link Propagation} (разлёт, воздух по октавам, земля, тень днём,
 * фон), громкость в игре — {@link Loudness}; докуда слышно, выходит само: пока хоть одна октава громче фона. Слышно то,
 * что ракета издала в «запаздывающий» момент ({@link Acoustics#emissionTime}: ракета летит почти со скоростью звука —
 * её слышно позже и не там, где её видно), отсюда задержка и Доплер. Запись — петля ускорителя ({@code booster.engine},
 * одна на все РДТТ), голос — по величине двигателя. Холм или дом между ракетой и ухом глушат ({@link SoundFilters}).
 * <p>
 * Путь — тот же, что у шлейфа ({@link FlightTracks#interceptor}: места вида ракеты по тикам клиента); вид пропал
 * (разрыв, ушла из тикающих чанков) — звук последнего места ещё идёт до уха, потом стихает.
 */
public final class InterceptorSound extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    private static final InterceptorSpec SPEC = InterceptorSpec.SAM;
    /** Двигатель на разгоне и в маршевом режиме (игровом, не по физике — {@link InterceptorSpec.Passport#sustain}). */
    static final Noise.Rocket BOOST = rocket(SPEC.passport().boost()), SUSTAIN = rocket(SPEC.passport().sustain());
    /**
     * Голос записи: одна запись на все РДТТ, тон — по величине двигателя (как у {@link EngineSound.Layer#BOOSTER}:
     * ускоритель шахеда и ракеты 1, снаряд «Града» 1,3); 9М96 (Ø 0,24 м) — между ними. Сдвиг записи в пределах ±25 %.
     */
    static final double VOICE = 1.2;
    private static final Set<InterceptorEntity> PLAYING = Collections.newSetFromMap(new WeakHashMap<>());

    private final InterceptorEntity rocket;
    @Nullable
    private FlightTrack track;
    private final Occlusion occlusion = new Occlusion();
    private float smoothVolume;
    /** Удар поджига уже был (или звук начался не со схода). */
    private boolean ignited;
    /** Звук схода слышен, только если мотор зазвучал у уха с первых тиков разгона. */
    private static final int IGNITION_HEARD = 10;
    private float filterGain = 1, filterHighs = 1;

    private InterceptorSound(InterceptorEntity rocket) {
        super(ModSounds.BOOSTER_ENGINE.get(), SoundSource.AMBIENT, RandomSource.create());
        this.rocket = rocket;
        this.looping = true;
        this.delay = 0;
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = false;
        this.volume = 0;
        this.pitch = (float) VOICE;
        this.x = rocket.getX();
        this.y = rocket.getY();
        this.z = rocket.getZ();
    }

    private static Noise.Rocket rocket(Article.SolidMotor m) {
        return new Noise.Rocket(m.thrust(), m.exhaust(), Spectra.BOOSTER_ENGINE);
    }

    /** Раз в тик клиента для каждой ракеты: мотор новой ракеты зазвучит. */
    public static void tick(InterceptorEntity e) {
        if (PLAYING.add(e)) Minecraft.getInstance().getSoundManager().play(new InterceptorSound(e));
    }

    /** Звучит с нуля: громкость набирается, когда до уха дойдёт звук. */
    @Override
    public boolean canStartSilent() {
        return true;
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
        if (isStopped()) return;
        // путь — тот, с которым мотор зазвучал: вид ракеты, вернувшийся к клиенту, — новый путь и свой звук
        if (track == null) track = FlightTracks.interceptor(rocket.getUUID());
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double now = FlightTracks.now();
        double te = track == null ? Double.NaN : Acoustics.emissionTime(track, now, ear.x, ear.y, ear.z);
        // за концом полёта звука нет: звук конца дошёл до уха — мотор стих
        boolean past = !Double.isNaN(te) && !track.covers(te);
        Heard heard = Double.isNaN(te) || past ? null : hear(track, te, ear, Minecraft.getInstance().level == null ? Propagation.Weather.NIGHT
                : Propagation.Weather.of(Minecraft.getInstance().level));
        // путь забыт или кончился, и его конец уже слышен (не дошёл за самый долгий путь звука — уже не дойдёт)
        boolean over = rocket.isRemoved() && (track == null || track.isDead() && (past || now - track.deathTick() > Hearing.MAX_DELAY));
        double gain = over || heard == null ? 0 : heard.gain;
        smoothVolume += (float) ((gain - smoothVolume) * 0.35);
        volume = Loudness.volume(smoothVolume, Loudness.LAUNCH_LUFS);
        if (over && smoothVolume <= VoiceBudget.AUDIBLE) {
            stop();
            return;
        }
        if (heard == null) return;
        if (!ignited) {
            ignited = true;
            // до уха дошёл звук самого схода (а не середины полёта: ракета появилась у клиента уже в пути) — удар поджига
            if (FlightPhase.byId(track.phase(te)).boosterLit() && track.phaseAge(te) < IGNITION_HEARD) ignition(heard.at, ear);
        }
        pitch = (float) heard.pitch;
        x = heard.at.x;
        y = heard.at.y;
        z = heard.at.z;
        if (smoothVolume > VoiceBudget.AUDIBLE) {
            float open = occlusion.open(ClientSounds.now(), ear, heard.at);
            filterGain = SoundFilters.blockedGain(open);
            filterHighs = (SoundFilters.pathEffects() ? (float) heard.highs : 1) * SoundFilters.blockedHighs(open);
            SoundFilters.update(this, filterGain, filterHighs);
        }
    }

    /**
     * Удар поджига у пакета ЗРК — как у ускорителей снарядов ({@code EngineSound.launchEvents}): запись старта
     * ускорителя ({@code launch.booster}) громкостью по мощности звука двигателя разгона на полной тяге и пути до уха,
     * голосом этого двигателя.
     */
    private static void ignition(Vec3 at, Vec3 ear) {
        var level = Minecraft.getInstance().level;
        Propagation.Weather weather = level == null ? Propagation.Weather.NIGHT : Propagation.Weather.of(level);
        double d = Math.max(0.5, at.distanceTo(ear));
        Propagation.Path path = Propagation.Path.over(d, Hearing.height(at, ear), weather);
        double[] bands = new double[Propagation.BANDS];
        double level0 = Propagation.level(BOOST.power(FlightPhase.BOOST, 0), Spectra.LAUNCH_BOOSTER, path, bands);
        double v = Loudness.gain(level0, Propagation.margin(bands, weather));
        if (v > 0.01) ClientSounds.atEar(ModSounds.LAUNCH_BOOSTER.get(), at, Loudness.volume(v, Loudness.LAUNCH_LUFS), (float) VOICE);
    }

    /** Что слышно у уха из момента излучения {@code te}: место, громкость, тон и доля верхов. */
    private static Heard hear(FlightTrack track, double te, Vec3 ear, Propagation.Weather weather) {
        double[] p = new double[3];
        track.at(te, p);
        Vec3 at = new Vec3(p[0], p[1], p[2]);
        double d = Math.max(0.5, at.distanceTo(ear));
        Noise.Rocket motor = FlightPhase.byId(track.phase(te)).boosterLit() ? BOOST : SUSTAIN;
        double lw = motor.power(FlightPhase.BOOST, track.phaseAge(te));
        Propagation.Path path = Propagation.Path.over(d, Hearing.height(at, ear), weather);
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(lw, Spectra.BOOSTER_ENGINE, path, bands);
        double gain = lw == Noise.SILENT ? 0 : Loudness.gain(level, Propagation.margin(bands, weather));
        Vec3 v = track.velocity(te);
        double nx = (ear.x - at.x) / d, ny = (ear.y - at.y) / d, nz = (ear.z - at.z) / d;
        // предел тона у Minecraft — 0,5…2 (SoundEngine)
        double pitch = Math.max(0.5, Math.min(2, VOICE * Acoustics.doppler(v.x, v.y, v.z, nx, ny, nz)));
        return new Heard(at, gain, pitch, lw == Noise.SILENT ? 1 : Emission.highs(lw, Spectra.BOOSTER_ENGINE, path));
    }

    private record Heard(Vec3 at, double gain, double pitch, double highs) {}
}
