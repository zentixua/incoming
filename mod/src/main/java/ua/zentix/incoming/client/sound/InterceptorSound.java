package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
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

import java.util.Arrays;
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
 * Путь — места ракеты по тикам клиента, пока её вид есть у клиента ({@link InterceptorEntity}); вид пропал (разрыв,
 * ушла из тикающих чанков) — звук последнего места ещё идёт до уха, потом стихает.
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
    private final Track track = new Track();
    private final Occlusion occlusion = new Occlusion();
    private float smoothVolume;
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
        // путь — по месту вида ракеты в этот тик клиента (звуки тикают после мира)
        if (!rocket.isRemoved()) track.add(FlightTracks.now(), rocket.position(), rocket.age());
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double now = FlightTracks.now();
        double te = track.size() == 0 ? Double.NaN : Acoustics.emissionTime(track, now, ear.x, ear.y, ear.z);
        Heard heard = Double.isNaN(te) ? null : hear(te, ear, Minecraft.getInstance().level == null ? Propagation.Weather.NIGHT
                : Propagation.Weather.of(Minecraft.getInstance().level));
        boolean over = rocket.isRemoved() && (heard == null ? now - track.last() > Hearing.MAX_DELAY : te >= track.last() - 1.0e-6);
        double gain = over || heard == null ? 0 : heard.gain * ClientSounds.duck();
        smoothVolume += (float) ((gain - smoothVolume) * 0.35);
        volume = smoothVolume;
        if (over && smoothVolume <= VoiceBudget.AUDIBLE) {
            stop();
            return;
        }
        if (heard == null) return;
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

    /** Что слышно у уха из момента излучения {@code te}: место, громкость, тон и доля верхов. */
    private Heard hear(double te, Vec3 ear, Propagation.Weather weather) {
        double[] p = new double[3];
        track.at(te, p);
        Vec3 at = new Vec3(p[0], p[1], p[2]);
        double d = Math.max(0.5, at.distanceTo(ear));
        double age = track.age(te);
        Noise.Rocket motor = age <= SPEC.boostTicks() ? BOOST : SUSTAIN;
        double lw = age >= SPEC.fuelTicks() ? Noise.SILENT : motor.power(FlightPhase.BOOST, age);
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

    /** Путь ракеты у клиента: место и возраст полёта по тикам клиента ({@link FlightTracks#now}). */
    static final class Track implements Acoustics.Path {
        private double[] t = new double[64], x = new double[64], y = new double[64], z = new double[64], age = new double[64];
        private int n;
        private double maxSpeed;

        void add(double time, Vec3 at, int flightAge) {
            if (n > 0 && time <= t[n - 1]) return;
            if (n == t.length) {
                t = Arrays.copyOf(t, n * 2);
                x = Arrays.copyOf(x, n * 2);
                y = Arrays.copyOf(y, n * 2);
                z = Arrays.copyOf(z, n * 2);
                age = Arrays.copyOf(age, n * 2);
            }
            if (n > 0) {
                double dt = time - t[n - 1];
                maxSpeed = Math.max(maxSpeed, Math.sqrt(sq(at.x - x[n - 1]) + sq(at.y - y[n - 1]) + sq(at.z - z[n - 1])) / dt);
            }
            t[n] = time;
            x[n] = at.x;
            y[n] = at.y;
            z[n] = at.z;
            age[n] = flightAge;
            n++;
        }

        int size() {
            return n;
        }

        double last() {
            return t[n - 1];
        }

        @Override
        public double start() {
            return t[0];
        }

        @Override
        public double maxSpeed() {
            return maxSpeed;
        }

        @Override
        public void at(double time, double[] out) {
            int i = index(time);
            double k = fraction(i, time);
            out[0] = x[i] + (x[i + 1 < n ? i + 1 : i] - x[i]) * k;
            out[1] = y[i] + (y[i + 1 < n ? i + 1 : i] - y[i]) * k;
            out[2] = z[i] + (z[i + 1 < n ? i + 1 : i] - z[i]) * k;
        }

        /** Скорость на отрезке, где момент {@code time}, блоков/тик. */
        Vec3 velocity(double time) {
            if (n < 2) return Vec3.ZERO;
            int i = Math.min(index(time), n - 2);
            double dt = t[i + 1] - t[i];
            return new Vec3((x[i + 1] - x[i]) / dt, (y[i + 1] - y[i]) / dt, (z[i + 1] - z[i]) / dt);
        }

        /** Возраст полёта в момент {@code time}, тики. */
        double age(double time) {
            int i = index(time);
            return age[i] + (age[i + 1 < n ? i + 1 : i] - age[i]) * fraction(i, time);
        }

        /** Последняя точка не позже {@code time} (раньше начала — первая). */
        private int index(double time) {
            int i = Arrays.binarySearch(t, 0, n, time);
            if (i >= 0) return i;
            return Math.max(0, -i - 2);
        }

        private double fraction(int i, double time) {
            if (i + 1 >= n) return 0;
            return Math.max(0, Math.min(1, (time - t[i]) / (t[i + 1] - t[i])));
        }

        private static double sq(double v) {
            return v * v;
        }
    }
}
