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
import ua.zentix.incoming.strike.WeaponType;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Непрерывный звук снаряда: движок каждый тик получает от нас положение, громкость и тон.
 * Положение и скорость берутся в «запаздывающий» момент (звук идёт к уху со скоростью 343 м/с, {@link Emission}),
 * отсюда Доплер и задержка. Громкость — от мощности звука шумящей части снаряда (паспорт {@link Noise}: мотор,
 * ускоритель, обтекание, бурение — по его скорости и оборотам) и пути до уха ({@link Propagation}: разлёт, воздух,
 * земля, тень днём; фон), в игре — {@link Loudness}; холм или дом между снарядом и ухом глушит, а верха съедает путь
 * ({@link SoundFilters}).
 * <p>
 * Слой звучит (держит канал OpenAL), только пока его слышно: запускает и отпускает его {@link ClientSounds}
 * по {@link VoiceBudget}; отпущенный слой стихает и останавливается сам. Возвращаясь, слой — новый звук,
 * он нарастает с нуля тем же сглаживанием, без щелчка.
 */
public final class EngineSound extends AbstractTickableSoundInstance implements SoundFilters.Muffled {
    /**
     * Дальние записи — ближние, прошедшие столько метров воздуха ({@code tools/build_sounds.py}: {@code FAR_M}):
     * дальше ближняя запись с фильтром EFX уже не похожа на дальний звук (EFX глушит верха одной полкой, а воздух —
     * по октавам), поэтому ближняя уступает дальней между половиной этого расстояния и им самим.
     */
    static final double FAR_AIR = 500;

    /**
     * Слои звука: какая запись, какую часть шума снаряда она играет ({@link Noise.Part}) и как делит её с соседними
     * записями той же части — ближняя и дальняя, спереди и сзади (доли по мощности, {@link #share}).
     */
    public enum Layer {
        DRONE_NEAR(() -> ModSounds.DRONE_ENGINE.get(), Noise.Part.ENGINE, 0),
        DRONE_FAR(() -> ModSounds.DRONE_ENGINE_FAR.get(), Noise.Part.ENGINE, FAR_AIR),
        /** Крылатая ракета вблизи: спереди — свист вентилятора, сзади — рёв струи, в пике — пронзительный свист. */
        MISSILE_FRONT(() -> ModSounds.MISSILE_ENGINE.get(), Noise.Part.ENGINE, 0),
        MISSILE_REAR(() -> ModSounds.MISSILE_ENGINE_REAR.get(), Noise.Part.ENGINE, 0),
        MISSILE_DIVE(() -> ModSounds.MISSILE_DIVE.get(), Noise.Part.ENGINE, 0),
        /** Вдали сбоку и сзади — гул струи. */
        MISSILE_FAR(() -> ModSounds.MISSILE_ENGINE_FAR.get(), Noise.Part.ENGINE, FAR_AIR),
        /** Вдали впереди — вой вентилятора: его уносит вперёд, на подлёте его слышно издалека. */
        MISSILE_WHISTLE(() -> ModSounds.MISSILE_WHISTLE.get(), Noise.Part.ENGINE, 0),
        BOMBER_NEAR(() -> ModSounds.BOMBER_ENGINE.get(), Noise.Part.ENGINE, 0),
        BOMBER_FAR(() -> ModSounds.BOMBER_ENGINE_FAR.get(), Noise.Part.ENGINE, FAR_AIR),
        BOMB_NEAR(() -> ModSounds.BOMB_FALL.get(), Noise.Part.BODY, 0),
        BOMB_FAR(() -> ModSounds.BOMB_FALL_FAR.get(), Noise.Part.BODY, FAR_AIR),
        BOMB_DRILL(() -> ModSounds.BOMB_DRILL.get(), Noise.Part.DRILL, 0),
        /** Стартовый ускоритель шахеда и ракеты, двигатель МБР и снаряда РСЗО; этот же слой отмечает поджиг и отделение. */
        BOOSTER(() -> ModSounds.BOOSTER_ENGINE.get(), Noise.Part.MOTOR, 0),
        /** Снаряд РСЗО: вой рассекаемого воздуха (пока горит двигатель — под его рёвом, после — один). */
        ROCKET_AIR(() -> ModSounds.ROCKET_INCOMING.get(), Noise.Part.BODY, 0),
        /** Барражирующий боеприпас: электромотор с винтом вблизи и вдали, рассекаемый воздух (громче мотора — в пике). */
        LOITER_NEAR(() -> ModSounds.LOITER_ENGINE.get(), Noise.Part.ENGINE, 0),
        LOITER_FAR(() -> ModSounds.LOITER_ENGINE_FAR.get(), Noise.Part.ENGINE, FAR_AIR),
        LOITER_DIVE(() -> ModSounds.LOITER_DIVE.get(), Noise.Part.BODY, 0),
        /** Мотор зенитной ракеты ЗРК: оружия у неё нет, закон — по паспорту ЗРК ({@link InterceptorSound}). */
        SAM(() -> ModSounds.BOOSTER_ENGINE.get(), Noise.Part.MOTOR, 0);

        /** Звук из реестра берётся, только когда слой запускают: громкость и тон считаются и без реестров (юнит-тесты). */
        final Supplier<SoundEvent> event;
        /** Какую часть шума играет. */
        final Noise.Part part;
        /** Сколько метров воздуха уже в записи ({@link #FAR_AIR} у дальних, 0 у ближних). */
        final double air;

        Layer(Supplier<SoundEvent> event, Noise.Part part, double air) {
            this.event = event;
            this.part = part;
            this.air = air;
        }

        /** Громкость и тон слоя для того, что слушатель слышит сейчас (до сглаживания). */
        Tone tone(FlightTrack track, Emission e) {
            if (this == SAM) return InterceptorSound.tone(e);
            Noise noise = noise(track);
            boolean drilling = e.phase() == FlightPhase.DRILL.ordinal();
            Emission.Heard heard = e.heard(noise, part, drilling);
            if (heard.level() == Noise.SILENT) return Tone.SILENT;
            FlightPhase ph = FlightPhase.byId(e.phase());
            double d = e.distance(), age = e.phaseAge(), v = e.velocity().length();
            // направленность — по мощности до перевода в громкость: обтекание и вентилятор уносит вперёд
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
                case BOOSTER -> pitch *= switch (ClientWeaponSpec.of(track.weapon).booster()) {
                    // голос записи: одна запись на все твердотопливные двигатели, тон — по их величине
                    case ICBM -> 0.7;
                    case ROCKET -> 1.3;
                    case SOLID_LOW -> 0.9;
                    case SOLID, NONE -> 1;
                };
            }
            double gain = Loudness.gain(level, heard.margin() + level - heard.level(), ceiling(track)) * share;
            // дальняя запись уже прошла FAR_AIR воздуха: фильтр — только сверх него
            double highs = this.air > 0 ? Math.min(1, heard.highs() / e.heardAt(noise, part, drilling, this.air)) : heard.highs();
            // камера снаряда: слушатель сидит на нём самом — мотор в упор, но не оглушающе (не физика: камера —
            // взгляд оператора, а не уши на корпусе)
            if (e.onboard()) gain *= 0.5;
            // предел тона у Minecraft — 0,5…2 (SoundEngine): дальше тон не уходит
            return new Tone(gain, Math.max(0.5, Math.min(2.0, pitch)), highs);
        }
    }

    /**
     * Потолок громкости снаряда, LUFS: у всех — {@link Loudness#CEILING_LUFS}, у МБР — прежний {@link Loudness#ENGINE_LUFS}
     * (ядерный удар не меняется; по тому же закону её старт у стола звучал бы до 5,5 дБ громче).
     */
    static double ceiling(FlightTrack track) {
        return track.weapon == WeaponType.NUKE ? Loudness.ENGINE_LUFS : Loudness.CEILING_LUFS;
    }

    /** Шум снаряда этого пути: паспорт его аппарата (у B-2 — самолёт, у его бомбы — бомба). */
    static Noise noise(FlightTrack track) {
        WeaponSpec spec = track.weapon.spec();
        return !track.bomber && spec.payload() != null ? spec.payload().noise() : spec.airframe().noise();
    }

    /**
     * Направленность шума обтекания, дБ: на подлёте громче всего, вслед — 0,3 по мощности (шум уносит вперёд; доля —
     * оценка, у вихрей за кормой).
     */
    static double airflow(Emission e) {
        double v = e.velocity().length();
        if (v < 1.0e-6) return 0;
        double front = (1 + e.radial() / v) / 2;
        return 10 * Math.log10(0.3 + 0.7 * front * front);
    }

    /** Громкость, тон и доля верхов слоя в этот тик. */
    record Tone(double gain, double pitch, double highs) {
        static final Tone SILENT = new Tone(0, 1, 1);
    }

    private final FlightTrack track;
    private final Occlusion occlusion;
    private final Layer layer;
    private float smoothVolume;
    private float filterGain = 1, filterHighs = 1;
    /** Что слышно в этот тик ({@code null} — данных о снаряде на этот момент нет) и громкость с тоном слоя. */
    private Emission heard;
    private Tone tone;
    /** Отпущен ({@link VoiceBudget}): стихает и останавливается. */
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
     * Громкость слоя против мерки {@link Loudness#ENGINE_LUFS} ({@link Loudness#gain}, сглаженная): ей сравнивают с порогом
     * {@link VoiceBudget#AUDIBLE}; громкость для Minecraft ({@code getVolume}) — она же, приведённая к громкости файла.
     */
    public double loudness() {
        return smoothVolume;
    }

    String describe() {
        // фильтр у источника: громкость и доля верхов за преградой и воздухом (1 — открыто)
        return String.format(Locale.ROOT, "%s[f %.2f/%.2f]", layer.name().toLowerCase(Locale.ROOT), filterGain, filterHighs);
    }

    /** Что слушатель слышит в этот тик: {@code heard} — {@code null}, если данных о снаряде на этот момент нет. */
    void aim(Emission heard, Tone tone) {
        this.heard = heard;
        this.tone = tone;
    }

    /** Отпустить канал: слой стихает и останавливается сам. */
    void release() {
        released = true;
    }

    /** Снова нужен, пока ещё не смолк: не останавливать. */
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
        if (heard == null) {
            // данных о снаряде на этот момент нет (взорвался или ушёл из слуха): стихнуть, не дёргая источник
            smoothVolume *= 0.5f;
            this.volume = Loudness.motor(smoothVolume);
        } else {
            // сглаживание: смена слоёв и ракурса, запуск и отпускание слоя, приглушение под взрыв — без щелчков
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
     * Разовые звуки старта в момент, когда их фронт дошёл до уха: поджиг ускорителя (удар и рёв у пусковой)
     * и отделение (хлопок пиропатронов и лязг замков), у РСЗО — сход каждого снаряда с трубы, у барражирующего —
     * катапульта. Громкость — от мощности звука события (ускоритель на полной тяге, катапульта и отстрел — из паспорта
     * {@link Noise}) и пути до уха. Зовётся, когда слушатель услышал смену фазы: {@code prev} — прошлая услышанная, −1 —
     * ещё никакой. У МБР свой звук пуска ({@code NukeSounds}).
     */
    static void launchEvents(FlightTrack track, Emission e, int prev) {
        ClientWeaponSpec spec = ClientWeaponSpec.of(track.weapon);
        if (spec.launch() == ClientWeaponSpec.LaunchCue.NONE) return;
        Noise noise = noise(track);
        FlightPhase ph = FlightPhase.byId(e.phase());
        double age = e.phaseAge();
        Vec3 at = e.position();
        // поджиг: при смене фазы или если снаряд попал в поле зрения уже на поджиге
        if (ph == FlightPhase.IGNITION && (prev >= 0 || age < 5)) {
            double motor = noise.motor() == null ? Noise.SILENT : noise.motor().power(FlightPhase.BOOST, 0);
            switch (spec.launch()) {
                case ROCKET_TUBE -> {
                    // сход реактивного снаряда: резкое «фш-ш» с треском; очередь по полсекунды сливается в рёв залпа
                    double v = shot(motor, Spectra.ROCKET_LAUNCH, e);
                    if (v > 0.01) ClientSounds.atEar(ModSounds.ROCKET_LAUNCH.get(), at, Loudness.volume(v, Loudness.ROCKET_LAUNCH_LUFS), 0.94f + (float) Math.random() * 0.12f);
                }
                case CATAPULT -> {
                    // катапульта барражирующего: удар поршня и свист направляющей
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

    /** Громкость ({@link Loudness#gain}) разового звука мощностью {@code lw} (без взвешивания) со спектром его записи у этого уха. */
    private static double shot(double lw, Spectrum spectrum, Emission e) {
        if (Double.isNaN(lw) || lw == Noise.SILENT) return 0;
        double[] bands = new double[Propagation.BANDS];
        double level = Propagation.level(lw, spectrum, e.path(e.distance()), bands);
        double v = Loudness.gain(level, Propagation.margin(bands, e.weather()));
        return e.onboard() ? v * 0.5 : v;
    }

    /** Вес ближней записи: 1 ближе половины {@link #FAR_AIR}, 0 с самого {@link #FAR_AIR}. */
    static double near(double d) {
        return 1 - Acoustics.smoothstep(FAR_AIR / 2, FAR_AIR, d);
    }

    /**
     * Доля слоя в переходе между разными записями (ближняя и дальняя, спереди и сзади): доли {@code w} и {@code 1 − w}
     * складываются по мощности (корень), а не по амплитуде. Записи разные и не коррелированы, поэтому при весах
     * 0,5 и 0,5 сумма по амплитуде тише на 3 дБ: ракета проседала бы на пролёте прямо над головой и на смене ближнего
     * гула дальним, становясь тише, хотя приближается.
     */
    static double share(double w) {
        return Math.sqrt(Math.max(0, w));
    }
}
