package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.client.ClientWeaponSpec;
import ua.zentix.incoming.client.flight.FlightTrack;
import ua.zentix.incoming.client.flight.FlightTracks;
import ua.zentix.incoming.sound.Propagation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;

/**
 * Звук снарядов на клиенте. У каждого снаряда свой независимый набор зацикленных звуков (без общих ячеек
 * и перебивающих друг друга свистов); задержку и Доплер считает {@link EngineSound}.
 * Путь снаряда — {@link FlightTracks}: по сущности, а пока её у клиента нет (далеко или летит вне мира) — по пакетам
 * сервера; один и тот же звук по UUID переходит с одного на другое. Где звук тонет в фоне ({@code Hearing}, тот же
 * расчёт, что у сервера), громкость слоёв — ноль: путь, который сервер шлёт только ради картинки вдали, канала не
 * занимает.
 * <p>
 * Каждый тик здесь считается, что слышно от каждого снаряда ({@link Emission}) и как громко каждый его слой;
 * звучат (держат канал OpenAL) только слышимые слои и не больше {@link VoiceBudget#CAP} сразу.
 */
public final class ClientSounds {
    private static final Map<UUID, Tracked> TRACKS = new HashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();
    /** Моторы уходят вниз под громкий взрыв. */
    private static final Ducking DUCK = new Ducking();
    /** Движок не дал слою канал (все заняты): через столько тиков попробовать снова. */
    private static final int RETRY = 10;
    /**
     * Мотор снаряда стихает столько тиков после того, как до уха дошёл конец полёта (слой без данных — вдвое тише
     * за тик), и только потом глушится: оборванный на полной громкости, он щёлкал бы, а оборванный на тик раньше
     * фронта взрыва — оставлял бы перед ним паузу тишины.
     */
    private static final int FADE = 4;

    private ClientSounds() {}

    /** Звук одного снаряда: путь, слои и фаза полёта, которую слушатель уже «услышал» (−1 — ещё никакую). */
    private static final class Tracked {
        final FlightTrack track;
        final Occlusion occlusion = new Occlusion();
        final List<Voice> voices;
        /** Есть слой ускорителя: смену фазы отмечают разовые звуки старта ({@link EngineSound#launchEvents}). */
        final boolean launchEvents;
        int heardPhase = -1;
        /** Звук снаряда дошёл до уха в прошлый тик (у сверхзвукового — слушатель уже внутри конуса Маха). */
        boolean reached;

        Tracked(FlightTrack track, List<EngineSound.Layer> layers) {
            this.track = track;
            this.voices = layers.stream().map(l -> new Voice(track, occlusion, l)).toList();
            this.launchEvents = layers.contains(EngineSound.Layer.BOOSTER);
        }

        void kill() {
            voices.forEach(Voice::kill);
        }
    }

    /** Слой звука снаряда: что с ним в этот тик и звук, пока слой звучит. */
    private static final class Voice {
        final FlightTrack track;
        final Occlusion occlusion;
        final EngineSound.Layer layer;
        /** Что слышно от снаряда в этот тик ({@code null} — данных нет) и громкость с тоном слоя. */
        Emission heard;
        EngineSound.Tone tone;
        /** Сколько тиков подряд слой не слышно. */
        int quiet;
        /** Раньше этого тика не запускать: движок не дал канал. */
        long retryAt;
        /** Звучит сейчас; {@code null} — канала не держит. */
        EngineSound sound;

        Voice(FlightTrack track, Occlusion occlusion, EngineSound.Layer layer) {
            this.track = track;
            this.occlusion = occlusion;
            this.layer = layer;
        }

        void hear(Emission e) {
            heard = e;
            tone = e == null ? null : layer.tone(track, e);
            quiet = gain() >= VoiceBudget.AUDIBLE ? 0 : quiet + 1;
            if (sound != null) sound.aim(heard, tone);
        }

        float gain() {
            return tone == null ? 0 : (float) tone.gain();
        }

        /**
         * Слой звучит: звук не смолк сам и у него есть канал движка ({@link #playing}). F3+T и смена устройства бросают все
         * звуки, ползунок категории в нуле снимает канал — слой заводится снова, а не молчит до конца полёта.
         */
        boolean live() {
            if (sound != null && (sound.isStopped() || !playing(sound))) kill();
            return sound != null;
        }

        /** Запустить слой; движок не дал канал — попробовать позже. */
        void start() {
            SoundManager manager = Minecraft.getInstance().getSoundManager();
            EngineSound s = new EngineSound(track, occlusion, layer);
            s.aim(heard, tone);
            // громкость, тон, место и фильтр — до запуска: так ваниль тикает отложенный звук перед play
            s.tick();
            manager.play(s);
            if (playing(s)) sound = s;
            else retryAt = FlightTracks.now() + RETRY;
        }

        void kill() {
            if (sound != null) {
                sound.kill();
                // канал снял ползунок категории в нуле: ваниль убирает канал, но не тикаемый звук (SoundEngine.tickNonPaused),
                // и он жил бы до сброса движка — каждый вывод ползунка в ноль копил бы по звуку на слой
                if (!playing(sound)) Minecraft.getInstance().getSoundManager().soundEngine.tickingSounds.remove(sound);
            }
            sound = null;
        }
    }

    /** Текущее время звука, тики клиента. */
    static double now() {
        return FlightTracks.now();
    }

    /** Раз в тик после {@link FlightTracks#tick}: слои звука у каждого пути, громкости, каналы. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        BlastSounds.tick();
        Sources.tick();
        if (mc.level == null) {
            reset();
            return;
        }
        // путь забыт или по тому же UUID начался новый полёт — прежний звук глушится
        Iterator<Tracked> it = TRACKS.values().iterator();
        while (it.hasNext()) {
            Tracked t = it.next();
            if (current(t.track) != t.track) {
                t.kill();
                it.remove();
            }
        }
        // звук мотора заводит путь, а не сущность: и снаряды, и зенитные ракеты, которых у клиента нет (пакеты сервера)
        for (FlightTrack track : FlightTracks.all()) {
            if (!TRACKS.containsKey(track.id)) TRACKS.put(track.id, new Tracked(track, layers(track)));
        }
        for (FlightTrack track : FlightTracks.interceptors()) {
            if (!TRACKS.containsKey(track.id)) TRACKS.put(track.id, new Tracked(track, layers(track)));
        }
        DUCK.tick();
        budget(hear(mc));
    }

    /**
     * Звук держит канал движка ({@code SoundEngine.instanceToChannel}), а не только запись о запуске: {@code isActive}
     * отвечает «звучит» и после того, как ползунок категории в нуле снял канал.
     */
    static boolean playing(SoundInstance s) {
        return Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(s);
    }

    /** Громкость моторов под взрывами ({@link Ducking}). */
    static float duck() {
        return DUCK.factor();
    }

    /** До уха дошёл взрыв с громкостью прямого звука lufs: моторы уходят вниз ({@link Ducking}). */
    static void ducked(double lufs) {
        DUCK.blast(lufs);
    }

    /** Что слышно от каждого снаряда в этот тик: громкость каждого слоя и разовые звуки старта. */
    private static List<Voice> hear(Minecraft mc) {
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        Entity camera = mc.getCameraEntity();
        Propagation.Weather weather = Propagation.Weather.of(mc.level);
        List<Voice> voices = new ArrayList<>();
        double tick = now();
        double[] roots = new double[1];
        for (Tracked t : TRACKS.values()) {
            FlightTrack track = t.track;
            // звук ещё не дошёл (или слушатель вне конуса Маха) — момента излучения нет, слои молчат
            boolean reached = Acoustics.emissions(track, tick, ear.x, ear.y, ear.z, roots) > 0;
            double te = reached ? roots[0] : Double.NaN;
            if (reached && !t.reached) Booms.reached(track, te, ear, weather);
            t.reached = reached;
            if (track.isDead() && te >= track.deathTick() + FADE) {
                // фронт взрыва дошёл и мотор стих: дальше слушатель слышит уже сам взрыв
                t.kill();
                continue;
            }
            // данных о снаряде на этот момент нет (взорвался или ушёл из слуха) — слои стихают, не дёргая источник
            Emission e = track.covers(te) ? Emission.at(track, te, ear, camera != null && camera.getUUID().equals(track.id), weather) : null;
            if (e != null && t.launchEvents && e.phase() != t.heardPhase) {
                EngineSound.launchEvents(track, e, t.heardPhase);
                t.heardPhase = e.phase();
            } else if (e != null && track.weapon == null && t.heardPhase < 0) {
                // зенитная ракета: поджиг слышен, если мотор зазвучал у уха с самого схода
                InterceptorSound.heard(e);
                t.heardPhase = e.phase();
            }
            for (Voice v : t.voices) {
                v.hear(e);
                voices.add(v);
            }
        }
        return voices;
    }

    /** Звучат слои, которые выбрал {@link VoiceBudget}: лишние отпускают канал, недостающие запускаются. */
    private static void budget(List<Voice> voices) {
        int n = voices.size();
        float[] gain = new float[n];
        boolean[] live = new boolean[n];
        int[] quiet = new int[n];
        for (int i = 0; i < n; i++) {
            Voice v = voices.get(i);
            gain[i] = v.gain();
            live[i] = v.live();
            quiet[i] = v.quiet;
        }
        boolean[] keep = VoiceBudget.select(gain, live, quiet, VoiceBudget.CAP);
        for (int i = 0; i < n; i++) {
            Voice v = voices.get(i);
            if (live[i]) {
                if (keep[i]) v.sound.keep();
                else v.sound.release();
            } else if (keep[i] && now() >= v.retryAt) {
                v.start();
            }
        }
    }

    /** Слои звука снаряда — из клиентского паспорта его сущности (у B-2 свои, у его бомбы свои); у зенитной ракеты — её мотор. */
    static List<EngineSound.Layer> layers(FlightTrack track) {
        return track.weapon == null ? List.of(EngineSound.Layer.SAM) : ClientWeaponSpec.of(track.weapon).airframe(!track.bomber).layers();
    }

    /** Нынешний путь того же UUID ({@code null} — забыт): снаряда или зенитной ракеты. */
    private static FlightTrack current(FlightTrack track) {
        return track.weapon == null ? FlightTracks.interceptor(track.id) : FlightTracks.get(track.id);
    }

    /**
     * Слышимые сейчас слои: «оружие (откуда путь: e — сущность, s — пакеты сервера; до уха, блоков) слой громкость×тон»,
     * в конце — сколько слоёв держат канал; для отладки звука по логу.
     */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double[] p = new double[3];
        int live = 0;
        for (Tracked t : TRACKS.values()) {
            StringBuilder layers = new StringBuilder();
            for (Voice v : t.voices) {
                EngineSound s = v.sound;
                if (s == null) continue;
                live++;
                // без звука, который движок подставляет при запуске (нет устройства), слой не звучит
                // громкость против мерки (Loudness.gain), а не громкость файла: числа строки не зависят от сведения файлов
                if (s.getSound() != null && s.loudness() > 0.01) layers.append(String.format(Locale.ROOT, " %s %.2f×%.2f", s.describe(), s.loudness(), s.getPitch()));
            }
            if (layers.isEmpty()) continue;
            t.track.at(t.track.lastTick(), p);
            sb.append(String.format(Locale.ROOT, " %s(%s %.0f)", t.track.weapon == null ? "sam" : t.track.weapon.getSerializedName(), t.track.fromServer() ? "s" : "e",
                    Math.sqrt((p[0] - ear.x) * (p[0] - ear.x) + (p[1] - ear.y) * (p[1] - ear.y) + (p[2] - ear.z) * (p[2] - ear.z)))).append(layers);
        }
        return sb.append(" live=").append(live).toString();
    }

    /** Отбой, перемотка повтора или выход из мира: заглушить всё сразу (и эхо на своих источниках). */
    public static void reset() {
        TRACKS.values().forEach(Tracked::kill);
        TRACKS.clear();
        DUCK.reset();
        BlastSounds.reset();
        Sources.reset();
        OwnSources.reset();
    }

    /** Обычный отбой: заглушить отменённые снаряды; оставшиеся (ядерные) звучат дальше. */
    public static void cancelled(Collection<UUID> projectiles) {
        for (UUID id : projectiles) {
            Tracked t = TRACKS.remove(id);
            if (t != null) t.kill();
        }
    }

    // ---------------------------------------------------------------- разовые звуки

    /**
     * Звук, который пришёл к уху «отовсюду сразу» — удар взрывной волны: громкость не зависит от расстояния
     * (её задаёт вызывающий), но направление честное — точка в 3 блоках от уха в сторону источника.
     * По дороге воздух съедает верха, холм или дом между — глушит ({@link SoundFilters}).
     */
    public static SoundInstance atEar(SoundEvent event, Vec3 source, float volume, float pitch) {
        return atEar(event, source, volume, pitch, SoundSource.AMBIENT);
    }

    /**
     * Ядерный удар по ушам: как {@link #atEar}, но по ползунку «Общая громкость» — его не должно быть тише
     * от того, что «Окружение» у игрока убавлено.
     *
     * @return отданный звук
     */
    public static SoundInstance atEarLoud(SoundEvent event, Vec3 source, float volume, float pitch) {
        return atEar(event, source, volume, pitch, SoundSource.MASTER);
    }

    /** Как {@link #atEarLoud(SoundEvent, Vec3, float, float)}, но вариант записи выбирает зерно (эхо от рельефа повторяет ту же запись). */
    public static SoundInstance atEarLoud(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        SoundInstance s = loudShot(event, source, volume, pitch, seed);
        play(s);
        return s;
    }

    /**
     * Звук {@link #atEarLoud(SoundEvent, Vec3, float, float, long)}, ещё не отданный движку ({@link #start}): кто ждёт
     * его начала, заводит ожидание до {@code play} (эхо от рельефа).
     */
    public static SoundInstance loudShot(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        return shot(event, source, volume, pitch, SoundSource.MASTER, RandomSource.create(seed));
    }

    /**
     * Как {@link #loudShot(SoundEvent, Vec3, float, float, long)}, но фильтр пути задаёт вызывающий (что между — холм,
     * земля, погода, воздух — уже посчитал {@link Outdoor}): без луча до источника и без своего воздуха. Преграда у самого
     * уха (стены и крыша дома, {@link SoundFilters#openToward}: 12 блоков в сторону источника; рельеф {@code Sightline}
     * считает дальше 16 блоков) — своя; путь звука выключен настройкой — без неё.
     */
    public static SoundInstance loudShot(SoundEvent event, Vec3 source, float volume, float pitch, long seed, float gain, float highs) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.pathEffects() ? SoundFilters.openToward(ear, Math.atan2(source.z - ear.z, source.x - ear.x)) : 1;
        return new Shot(event.getLocation(), SoundSource.MASTER, volume, pitch, RandomSource.create(seed), SoundInstance.Attenuation.NONE,
                toward(ear, source), false, gain * SoundFilters.blockedGain(open), highs * SoundFilters.blockedHighs(open));
    }

    /**
     * Звук {@link #atEar(SoundEvent, Vec3, float, float, long)} (вариант записи — по зерну, категория «Окружение»), ещё
     * не отданный движку ({@link #start}): кто ждёт его начала, заводит ожидание до {@code play}.
     */
    public static SoundInstance shot(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        return shot(event, source, volume, pitch, SoundSource.AMBIENT, RandomSource.create(seed));
    }

    /**
     * Разовый звук взрыва в смеси взрывов ({@link Mix}), ещё не отданный движку ({@link #start}): как
     * {@link #shot(SoundEvent, Vec3, float, float, long)}, но громкость — {@code volume} × множитель его взрыва в смеси
     * {@code mix}, заново каждый тик (звук тикает). Отдаёт его движку {@link BlastSounds}, если на него есть голос
     * ({@link BlastVoices}).
     */
    static MixedShot blastShot(SoundEvent event, Vec3 source, float volume, float pitch, long seed, DoubleSupplier mix, int life) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.open(ear, source.add(0, 1.5, 0));
        float highs = farAir(ear.distanceTo(source)) * SoundFilters.blockedHighs(open);
        return new MixedShot(event, SoundSource.AMBIENT, volume, pitch, RandomSource.create(seed), toward(ear, source), SoundFilters.blockedGain(open), highs, mix,
                life);
    }

    /** Как {@link #blastShot}, но фильтр пути — вызывающего ({@link Outdoor}: что между — холм, земля, погода, воздух). */
    static MixedShot blastAtEar(SoundEvent event, Vec3 source, float volume, float pitch, long seed, float gain, float highs, DoubleSupplier mix, int life) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return new MixedShot(event, SoundSource.AMBIENT, volume, pitch, RandomSource.create(seed), toward(ear, source), gain, highs, mix, life);
    }

    /** Отдать звук движку. */
    public static void start(SoundInstance sound) {
        play(sound);
    }

    private static SoundInstance atEar(SoundEvent event, Vec3 source, float volume, float pitch, SoundSource category) {
        return atEar(event, source, volume, pitch, category, RANDOM);
    }

    /**
     * Как {@link #atEar(SoundEvent, Vec3, float, float)}, но запись из вариантов события выбирает зерно: ракурсы одного
     * взрыва с одним зерном — одна и та же запись (у событий ракурсов поровну вариантов).
     */
    public static void atEar(SoundEvent event, Vec3 source, float volume, float pitch, long seed) {
        atEar(event, source, volume, pitch, SoundSource.AMBIENT, RandomSource.create(seed));
    }

    private static SoundInstance atEar(SoundEvent event, Vec3 source, float volume, float pitch, SoundSource category, RandomSource random) {
        SoundInstance s = shot(event, source, volume, pitch, category, random);
        play(s);
        return s;
    }

    private static SoundInstance shot(SoundEvent event, Vec3 source, float volume, float pitch, SoundSource category, RandomSource random) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        float open = SoundFilters.open(ear, source.add(0, 1.5, 0));
        float highs = farAir(ear.distanceTo(source)) * SoundFilters.blockedHighs(open);
        return new Shot(event.getLocation(), category, volume, pitch, random, SoundInstance.Attenuation.NONE, toward(ear, source), false,
                SoundFilters.blockedGain(open), highs);
    }

    /**
     * Как {@link #atEar(SoundEvent, Vec3, float, float, long)}, но фильтр пути задаёт вызывающий (дальний взрыв: что между — холм, земля,
     * погода — уже посчитал {@link Outdoor}): без луча по блокам и без своего воздуха.
     */
    public static void atEar(SoundEvent event, Vec3 source, float volume, float pitch, long seed, float gain, float highs) {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        play(new Shot(event.getLocation(), SoundSource.AMBIENT, volume, pitch, RandomSource.create(seed), SoundInstance.Attenuation.NONE,
                toward(ear, source), false, gain, highs));
    }

    /** Верха записи раската, дошедшие по воздуху: дальние записи уже глухие — воздух добавляет не больше −12 дБ. */
    static float farAir(double d) {
        return Math.max(0.25f, SoundFilters.air(d));
    }

    /** Точка в 3 блоках от уха в сторону источника (ближе — сам источник). */
    private static Vec3 toward(Vec3 ear, Vec3 source) {
        Vec3 dir = source.subtract(ear);
        return dir.lengthSqr() < 9 ? source : ear.add(dir.normalize().scale(3));
    }

    private static void play(SoundInstance sound) {
        Minecraft.getInstance().getSoundManager().play(sound);
    }

    /**
     * Разовый звук взрыва в смеси ({@link Mix}): тикает, и движок каждый тик берёт громкость заново — своя × множитель
     * его взрыва в смеси (хвост этого взрыва уступает место следующему, а не держит громкость, с которой начался). Голос
     * уступил место ({@link BlastVoices}) — тик ещё полный, потом стихает за {@link BlastVoices#FADE} тика
     * ({@link Rumble#voice}: в те же тики движка его мощность переходит в гул) и уходит.
     */
    static final class MixedShot extends AbstractTickableSoundInstance implements SoundFilters.Muffled, BlastVoices.Sound {
        private final float gain, highs;
        private final DoubleSupplier mix;
        /** Тиков до конца записи с запасом: дальше звук снимается сам. */
        private int life;
        /** Тиков движка с тех пор, как голос уступил; −1 — звучит. */
        private int fading = -1;

        MixedShot(SoundEvent event, SoundSource category, float volume, float pitch, RandomSource random, Vec3 at, float gain, float highs,
                  DoubleSupplier mix, int life) {
            super(event, category, random);
            this.volume = volume;
            this.pitch = pitch;
            this.attenuation = Attenuation.NONE;
            this.relative = false;
            this.looping = false;
            this.delay = 0;
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
            this.gain = gain;
            this.highs = highs;
            this.mix = mix;
            this.life = life;
        }

        @Override
        public float getVolume() {
            return super.getVolume() * level();
        }

        /** Множитель громкости сейчас: смесь × стихание уступившего голоса. */
        float level() {
            return (float) (mix.getAsDouble() * (fading < 0 ? 1 : Rumble.voice(fading, 0, BlastVoices.FADE)));
        }

        @Override
        public void tick() {
            // тик движка: громкость — getVolume, место — неизменно; запись кончилась или голос стих — снять
            if (fading >= 0 && Rumble.voice(++fading, 0, BlastVoices.FADE) <= 0 || --life <= 0) stop();
        }

        @Override
        public void fade() {
            if (fading < 0) fading = 0;
        }

        @Override
        public void cut() {
            stop();
        }

        /** Звучит: движок взял канал и не снял его (нехватка каналов, перезапуск движка, ползунок категории в нуле). */
        boolean playing() {
            return !isStopped() && Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
        }

        @Override
        public boolean over() {
            return !playing();
        }

        /**
         * Канал ещё у движка: остановленный звук держит его, пока движок не остановит канал в своём тике и звуковой поток не
         * вернёт его пулу ({@code ChannelAccess.ChannelHandle.isStopped}); звук, которого движок не взял, канала не держит.
         */
        @Override
        public boolean held() {
            ChannelAccess.ChannelHandle h = Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.get(this);
            return h != null && !h.isStopped();
        }

        /** Громкость записи, с которой он задан, до смеси. */
        float volume0() {
            return volume;
        }

        @Override
        public float lowpassGain() {
            return gain;
        }

        @Override
        public float lowpassHighs() {
            return highs;
        }
    }

    /** Разовый звук с фильтром пути (воздух, преграды). */
    private static final class Shot extends SimpleSoundInstance implements SoundFilters.Muffled {
        private final float gain, highs;

        Shot(ResourceLocation id, SoundSource category, float volume, float pitch, RandomSource random, Attenuation attenuation, Vec3 at,
             boolean relative, float gain, float highs) {
            super(id, category, volume, pitch, random, false, 0, attenuation, at.x, at.y, at.z, relative);
            this.gain = gain;
            this.highs = highs;
        }

        @Override
        public float lowpassGain() {
            return gain;
        }

        @Override
        public float lowpassHighs() {
            return highs;
        }
    }
}
