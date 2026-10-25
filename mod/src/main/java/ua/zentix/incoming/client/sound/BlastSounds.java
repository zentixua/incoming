package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.fx.BlastHit;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.fx.smoke.CloudModel;
import ua.zentix.incoming.client.nuclear.Deafness;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.warhead.BunkerImpact;
import ua.zentix.incoming.warhead.GroundMaterial;
import ua.zentix.incoming.nuclear.model.ChemicalFront;
import ua.zentix.incoming.client.far.FarBlasts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Что слышно от взрыва на разном расстоянии — картинку рисует {@code client.fx.BlastEffects}, звук весь здесь.
 * Всё приходит со фронтом, громкость задаём сами, а не ванильным затуханием; заряд — из паспорта оружия
 * ({@link BlastSource}).
 * <ul>
 * <li>ближе {@link Outdoor#NEAR} блоков — ракурсы одной записи по давлению и воздуху у уха, тон и длина — по заряду
 * ({@link BlastMix}), с «ударом в грудь» снизу; удар у уха там, где ближний ракурс уходит, — дорожка из модели своим
 * источником ({@link BlastPulse}); эхо от рельефа — {@link BlastEchoes}; воздух и преграды глушат верха
 * ({@link SoundFilters});</li>
 * <li>дальше — дальний ракурс по модели распространения {@link Outdoor} ({@link #far}), докуда слышно — по заряду
 * ({@link HearingModel#audibleRangeM}).</li>
 * </ul>
 * Вариант записи выбирает зерно взрыва (пакет {@code S2C.Blast}): каждый взрыв звучит по-своему, а его ракурсы — одна
 * запись. Моторы снарядов под громкий взрыв уходят вниз ({@link Ducking}). Все звуки взрывов (ракурсы, удар в грудь, удар у
 * уха, эхо, звуки бомбы) — в одной смеси ({@link Mix}): потолок громкости — у суммы, а не у каждого, доля каждого взрыва —
 * его доля в жизни. Разовые звуки взрывов держат каналы движка не больше своего потолка ({@link BlastVoices}): места нет —
 * тихие звучат гулом в четырёх петлях вокруг уха ({@link Rumble}) с той же мощностью.
 */
public final class BlastSounds {
    /**
     * Громкость записей бомбы, LUFS (наибольшая мгновенная, окно 0,4 с; замер файлов, собранных
     * {@code tools/build_sounds.py}, {@code bunker()}): играются по давлению у уха, как ракурсы взрывов ({@link BlastMix}).
     * У подземного взрыва — громкий из двух вариантов: тише нужного он не играет.
     */
    static final double IMPACT_LUFS = -6.4, DEEP_LUFS = -7.7, VENT_LUFS = -11.2, CAVE_LUFS = -9.3, QUAKE_LUFS = -7.7;
    /** Соль зерна «удара в грудь»: его вариантов меньше, они не привязаны к варианту ракурсов. */
    private static final long SUB_SALT = 0x5DEECE66DL;
    /**
     * Своих источников на эхо: набор эха — {@link EchoMix#SECTORS} дорожек; новый набор — когда на него есть места
     * сверх уже начатых и сверх остатка {@link OwnSources#MAX} по наборам (его держат удары у уха, {@link BlastPulse}).
     */
    static final int PULSE_RESERVE = OwnSources.MAX % EchoMix.SECTORS;
    /** Эхо, которое ещё ищет склоны или сводит дорожки. */
    private static final List<BlastEchoes> ECHOES = new ArrayList<>();
    /** Тише — слой не запускать. */
    private static final float AUDIBLE = 0.005f;
    /**
     * Тики клиента: часы взрывов, по которым эхо и удар у уха ждут начала прямого звука. Свои, а не возраст эффекта
     * взрыва: эффект кончается, а голоса эха, что встают позже, ждут этих часов — с остановленными они не зазвучали
     * бы никогда и держали свои источники.
     */
    private static long ticks;
    /** Смесь взрывов у уха: громкость в игре — половина децибел суммарного уровня, потолок — у суммы ({@link Mix}). */
    private static final Mix MIX = new Mix();
    /** Гул взрывов без голоса ({@link Rumble}) и кто держит голоса ({@link BlastVoices}). */
    private static final Rumble RUMBLE = new Rumble();
    private static final BlastVoices VOICES = new BlastVoices(RUMBLE);
    /**
     * Громкость петли гула, LUFS (наибольшая мгновенная) — {@code RUMBLE_LUFS} в {@code build_sounds.py}: на потолке, чтобы
     * громкость петли не упёрлась в 1 раньше, чем гул дойдёт до потолка суммы.
     */
    static final double RUMBLE_LUFS = Loudness.CEILING_LUFS;
    /** Петли гула по четвертям; null — молчит. */
    private static final RumbleLoop[] LOOPS = new RumbleLoop[Rumble.QUADRANTS];
    private static final int[] LOOP_RETRY = new int[Rumble.QUADRANTS];
    /** Статических каналов движка, которые даст OpenAL рядом со своими источниками ({@link BlastVoices#reach}). */
    private static int reach = BlastVoices.reach(BlastVoices.MONO, BlastVoices.STEREO);
    /** Взрывы, чей звук ещё не начался на деле: смесь сдвинется к настоящему началу ({@link OwnSources.Anchor}). */
    private static final List<Late> LATE = new ArrayList<>();
    /** Зерно звуков без своего зерна (бомба). */
    private static final RandomSource SEEDS = RandomSource.create();

    private record Late(Mix.Blast blast, OwnSources.Anchor anchor) {}
    private static final List<CraterFire> FIRES = new ArrayList<>();
    /** Гул пожаров в воронках — суммой по четвертям вокруг уха ({@link Quarters}), по петле на четверть, а не на пожар. */
    private static final Quarters FIRE = new Quarters();
    /** Лучи преграды к пожарам — по бюджету тика, не к каждому в каждом тике ({@link FireRays}). */
    private static final FireRays FIRE_RAYS = new FireRays();
    private static final AmbientLoop[] FIRE_LOOPS = new AmbientLoop[Quarters.N];
    private static final int[] FIRE_RETRY = new int[Quarters.N], FIRE_QUIET = new int[Quarters.N];
    /** Последний обычный взрыв у уха и последнее эхо (строка сценария клиента). */
    private static String last = "";
    @Nullable
    private static BlastEchoes lastEchoes;

    private BlastSounds() {}

    /**
     * Докуда слышно взрыв в обычных условиях (R0 у {@link Outdoor}), блоков: по заряду, но не дальше, чем сервер
     * шлёт взрывы ({@link IncomingConfig#FAR_RANGE_MAX}).
     */
    public static double range(BlastSource s) {
        return Math.min(IncomingConfig.FAR_RANGE_MAX, HearingModel.audibleRangeM(s.chargeKg()));
    }

    /**
     * Взрыв дальше {@link Outdoor#NEAR}: дальний ракурс с той громкостью, что у ближней модели на её краю
     * ({@link BlastMix#v640}), тоном по заряду, у бомбы — глухой удар из-под земли. Громкость и верха — {@link Outdoor}:
     * что между (кромка по лучу рельефа {@code z}), земля у взрыва и у слушателя, день или ночь, дождь.
     *
     * @param s      что взорвалось
     * @param hs     высота источника над землёй, блоков
     * @param ground грунт у взрыва
     * @param d      до уха, блоков
     * @param z      разность хода через кромку рельефа, блоков (0 — прямая видимость)
     * @param seed   зерно взрыва
     * @return что дошло (для лога); {@code null} — мира нет
     */
    public static Outdoor.@Nullable Heard far(BlastSource s, Vec3 pos, double hs, GroundMaterial ground, double d, double z, long seed) {
        Outdoor.Path path = path(d, z, hs, ground.porosity);
        if (path == null) return null;
        Outdoor.Heard heard = Outdoor.hear(range(s), s.underground() ? atNear(s, DEEP_LUFS) : BlastMix.v640(s), path);
        float v = heard.volume();
        if (v < AUDIBLE) return heard;
        float highs = highs(path);
        SoundEvent event = s.underground() ? ModSounds.BOMB_DEEP.get() : ModSounds.BLAST_FAR.get();
        float pitch = s.underground() ? 1 : BlastMix.pitch(s.chargeKg());
        Mix.Blast blast = mixed(event, v, pitch, seed);
        voice(blast, List.of(ClientSounds.blastAtEar(event, pos, v, pitch, seed, 1, highs, blast::factor, life(blast))), pos, pitch, false);
        return heard;
    }

    /**
     * Путь звука от взрыва до уха для {@link Outdoor}: земля у слушателя — верх колонки под ухом (чанк под камерой у
     * клиента всегда есть), день или ночь, дождь и гроза сейчас.
     *
     * @param d  до уха, блоков
     * @param z  разность хода через кромку рельефа, блоков (0 — прямая видимость)
     * @param hs высота источника над землёй, блоков
     * @param gs пористость земли у источника
     * @return {@code null} — мира нет
     */
    public static Outdoor.@Nullable Path path(double d, double z, double hs, double gs) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        int x = Mth.floor(ear.x), zz = Mth.floor(ear.z);
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, zz);
        GroundMaterial under = GroundMaterial.of(level.getBlockState(new BlockPos(x, top - 1, zz)));
        // зона тени — по устойчивости воздуха мира: днём земля греет воздух, звук загибает вверх; инверсия ночью — нет
        return new Outdoor.Path(d, z, hs, Math.max(1.5, ear.y - top), gs, under == null ? 0.5 : under.porosity,
                level.dimensionType().hasSkyLight() ? Propagation.refraction(ClientAir.now().stability()) : 0,
                level.getRainLevel(1), level.getThunderLevel(1));
    }

    /** Доля верхов записи раската по пути {@link Outdoor} и воздуху; путь звука выключен настройкой — 1. */
    public static float highs(Outdoor.Path path) {
        return SoundFilters.pathEffects() ? Outdoor.highs(path) * ClientSounds.farAir(path.d()) : 1;
    }

    /**
     * Эхо от рельефа этого взрыва ({@link BlastEchoes}: лучи ищут склоны, пока фронт идёт) — если на него есть свои
     * источники ({@link #PULSE_RESERVE}); иначе null: залп РСЗО — десятки разрывов, эхо слышно у тех, кому хватило мест.
     * Зерно — вариант дальнего ракурса этого взрыва; часы — часы взрыва у клиента.
     */
    public static @Nullable BlastEchoes echoes(BlastSource s, Vec3 pos, long seed, OwnSources.Clock clock) {
        ECHOES.removeIf(BlastEchoes::settled);
        if (OwnSources.free() - EchoMix.SECTORS * ECHOES.size() < EchoMix.SECTORS + PULSE_RESERVE) return null;
        double kg = s.chargeKg();
        // путь отражения — тот же фронт, что у прямого звука ({@link FarBlasts#arrivalTicks}): вблизи быстрее звука
        ChemicalFront front = kg > 0 ? ChemicalFront.cached(kg, s.surface()) : null;
        BlastEchoes e = new BlastEchoes(clock, pos, new TerrainEcho.Blast() {
            @Override
            public double arrivalTicks(double blocks) {
                return FarBlasts.arrivalTicks(front, blocks);
            }

            @Override
            public double pressure(double blocks) {
                return BlastModel.overpressureKpa(blocks, kg, s.surface());
            }
        }, ModSounds.BLAST_FAR.get(), seed);
        ECHOES.add(e);
        return e;
    }

    /**
     * Обычный взрыв (на земле или в воздухе) — фронт дошёл до уха сейчас (часы взрыва — {@link #clock}): ракурсы записи и
     * «удар в грудь» ({@link BlastMix}), удар у уха из модели своим источником ({@link BlastPulse}), эхо от рельефа
     * ({@code echoes}, null — без него) — от настоящего начала самого громкого ракурса. Звуки самой волны слышит ухо,
     * каким оно было до неё ({@link Deafness#before}): слух, если волна его бьёт, — после.
     */
    public static void arrive(BlastSource s, Vec3 pos, long seed, OwnSources.Clock clock, @Nullable BlastEchoes echoes) {
        double tick = clock.ticks(0);
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double d = ear.distanceTo(pos), pa = BlastMix.pressure(s, d), loudness = BlastMix.loudness(pa);
        BlastMix.Layers l = BlastMix.at(s, d);
        float pitch = BlastMix.pitch(s.chargeKg());
        ClientSounds.ducked(loudness);
        List<ClientSounds.MixedShot> shots = new ArrayList<>(4);
        SoundInstance loudest = null;
        float most = 0;
        float[] volumes = {l.near(), l.mid(), l.far()};
        SoundEvent[] events = {ModSounds.BLAST_NEAR.get(), ModSounds.BLAST_MID.get(), ModSounds.BLAST_FAR.get()};
        // слои — в смесь до того, как зазвучат: громкость первого же тика — уже с этим взрывом
        Mix.Blast blast = MIX.blast(ticks);
        for (int i = 0; i < 3; i++) {
            if (volumes[i] < AUDIBLE) continue;
            Mix.Layer layer = new Mix.Layer(Envelopes.of(events[i].getLocation()), Envelopes.energy(events[i], seed), pitch, volumes[i], Mix.Kind.VIEW, 0);
            blast.add(layer);
            ClientSounds.MixedShot shot = ClientSounds.blastShot(events[i], pos, volumes[i], pitch, seed, blast::factor, life(layer));
            shots.add(shot);
            if (volumes[i] > most) {
                most = volumes[i];
                loudest = shot;
            }
        }
        if (l.sub() >= AUDIBLE) {
            // удар в грудь — своя запись (низ других подрывов и синтез): с ракурсами — по мощности
            SoundEvent sub = ModSounds.BLAST_SUB.get();
            Mix.Layer layer = new Mix.Layer(Envelopes.of(sub.getLocation()), Envelopes.energy(sub, seed ^ SUB_SALT), pitch, l.sub(), Mix.Kind.APART, 0);
            blast.add(layer);
            shots.add(ClientSounds.blastShot(sub, pos, l.sub(), pitch, seed ^ SUB_SALT, blast::factor, life(layer)));
        }
        // эхо и удар у уха ждут настоящего начала прямого звука: ожидание — до play; смесь — тоже от него
        if (echoes != null) {
            echoes.bus(blast);
            echoes.expect(tick, loudest);
        }
        Object owner = new Object();
        OwnSources.Anchor start = OwnSources.anchor(owner, loudest, clock, tick);
        if (loudest != null) LATE.add(new Late(blast, start));
        boolean pulse = false;
        if (l.pulse() >= AUDIBLE) {
            short[] track = BlastPulse.render(pa, BlastMix.phase(s, d), 1);
            // удар у уха — дорожка из модели своим источником: в смеси своей огибающей, по мощности
            float[] x = new float[track.length];
            for (int i = 0; i < x.length; i++) x[i] = track[i] / 32768f;
            pulse = OwnSources.play(owner, start, 0, Math.atan2(pos.z - ear.z, pos.x - ear.x), l.pulse(), track, BlastPulse.RATE, true, true,
                    blast::factor);
            // своих источников не нашлось — удара нет и в смеси
            if (pulse) blast.add(Mix.Layer.own(Envelope.of(x, BlastPulse.RATE), Envelope.energy(x, BlastPulse.RATE), l.pulse(), 0));
        }
        last = String.format(Locale.ROOT, "%s %.0f kg d %.0f p %.0f Pa (%.1f dB) phase %.1f ms loud %.1f LUFS pitch %.2f near %.2f mid %.2f far %.2f sub %.2f pulse %.2f %s echo %s",
                s.weapon() == null ? "air" : s.weapon().name(), s.chargeKg(), d, pa, 20 * Math.log10(Math.max(pa, 1e-9) / 2e-5), 1000 * BlastMix.phase(s, d),
                loudness, pitch, l.near(), l.mid(), l.far(), l.sub(), l.pulse(), pulse ? "played" : "-", echoes == null ? "-" : "on");
        if (echoes != null) lastEchoes = echoes;
        voice(blast, shots, pos, pitch, true);
        if (echoes != null) echoes.arrive(ear, BlastMix.uncapped(loudness - BlastMix.FAR_LUFS), pitch);
    }

    /**
     * Разовые звуки взрыва {@code blast} (его слои уже в смеси) — голосами движка, если на них есть место ({@link BlastVoices}),
     * иначе взрыв звучит в гуле ({@link Rumble}); звуки, которых движок не взял, — тоже в гул, сразу. {@code before} — звуки
     * самой волны: их слышит ухо, каким оно было до неё ({@link Deafness#before}).
     */
    private static void voice(Mix.Blast blast, List<ClientSounds.MixedShot> shots, Vec3 pos, float pitch, boolean before) {
        if (shots.isEmpty()) return;
        Vec3 ear = ear();
        ClientSounds.MixedShot loud = shots.get(0);
        for (ClientSounds.MixedShot s : shots) if (s.volume0() > loud.volume0()) loud = s;
        // за преградой гул так же глух, как голос: громкость фильтра — в мощности, верха — в фильтре петли
        float open = loud.lowpassGain();
        int now = soundTick(), yielded = VOICES.yielded;
        BlastVoices.Voice v = VOICES.admit(blast, shots, now, Math.atan2(pos.z - ear.z, pos.x - ear.x), pitch, open * open, loud.lowpassHighs(), free());
        if (v != null) {
            boolean[] took = new boolean[shots.size()];
            for (int i = 0; i < took.length; i++) {
                ClientSounds.MixedShot shot = shots.get(i);
                if (before) Deafness.before(shot);
                ClientSounds.start(shot);
                took[i] = shot.playing();
            }
            VOICES.started(v, took, now);
        }
        // гул изменился: петли встают сейчас, а взрыв без голоса (весь или невзятые звуки) слышен в них сразу, не с тика движка
        if (v == null || v.fading() || VOICES.yielded != yielded) rumble(true);
    }

    /**
     * Свободных статических каналов у движка сейчас: из тех, что даст OpenAL ({@link #reach}), минус каналы, которые он
     * держит (остановленный звук — пока звуковой поток не вернул его пулу, {@code ChannelHandle.isStopped}).
     */
    private static int free() {
        int held = 0;
        for (var e : Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.entrySet()) {
            if (e.getValue().isStopped()) continue;
            var sound = e.getKey().getSound();
            if (sound == null || !sound.shouldStream()) held++;
        }
        return reach - held;
    }

    /** Тик звукового движка: часы, по которым голос взрыва стихает и гул растёт ({@link BlastVoices}, {@link Rumble}). */
    private static int soundTick() {
        return Minecraft.getInstance().getSoundManager().soundEngine.tickCount;
    }

    /** Строка {@code SCENARIO blast-sound}: последний обычный взрыв у уха и его эхо; пусто — взрывов не было. */
    public static String describe() {
        if (last.isEmpty()) return "";
        BlastEchoes e = lastEchoes;
        return last + (e == null ? "" : "; " + e.describe()) + "; " + voices() + "; " + BlastHit.describe();
    }

    /**
     * Голоса взрывов и гул: сколько каналов держат (звучащие/все, потолок), сколько голосов просили у движка, сколько он
     * взял и отказал, сколько взрывов ушло в гул сразу и уступило место; громкость петель гула по четвертям.
     */
    static String voices() {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "voices %d/%d cap %d requested %d started %d refused %d rumble %d yielded %d loops",
                VOICES.steady(), VOICES.channels(), VOICES.cap(), VOICES.requested, VOICES.started, VOICES.refused, VOICES.toRumble, VOICES.yielded));
        for (int q = 0; q < Rumble.QUADRANTS; q++) sb.append(String.format(Locale.ROOT, " %.2f", Rumble.volume(RUMBLE.potential(q), RUMBLE_LUFS)));
        return sb.toString();
    }

    /** Часы взрыва, родившегося сейчас: тики с этого мига, с долей. */
    public static OwnSources.Clock clock() {
        long born = ticks;
        return partial -> ticks - born + partial;
    }

    /** Тик клиента. */
    static void tick() {
        ticks++;
        FIRE.clear();
        FIRES.removeIf(fire -> !fire.tick(Minecraft.getInstance()));
        FIRE_RAYS.tick(FIRES, ear(), SoundFilters.WORLD, FIRE);
        fireLoops();
        MIX.at(ticks);
        // звук начался на деле позже своего тика — смесь взрыва от настоящего начала, как его эхо и удар у уха
        LATE.removeIf(l -> {
            if (Double.isNaN(l.anchor().zero())) return false;
            l.blast().late(20 * l.anchor().lateSeconds());
            return true;
        });
        VOICES.prune(soundTick());
        // запас свободных каналов на ближний: мир его съел — заранее уступают самые тихие свои (их каналы вернутся за тики)
        if (VOICES.reserve(soundTick(), free()) > 0) rumble(true);
        rumble(false);
    }

    /**
     * Петли гула пожаров по четвертям, раз в тик: громкость — корень суммы мощностей пожаров четверти, верха — средние по
     * мощности; играют все четверти с долей слышной суммы ({@link Quarters#plays}: порог — у суммы, не у петли); петлю,
     * которой движок не держит, — заново (раз в {@code 20} тиков); не играет секунду — отпустить; ползунок «Окружение» в
     * нуле — не запускать. У каждой четверти свой вариант `blast.fire` — звуки петель некогерентны, и их мощности
     * складываются, как считает {@link Quarters}.
     */
    private static void fireLoops() {
        Minecraft mc = Minecraft.getInstance();
        boolean ambient = mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0;
        Vec3 ear = ear();
        for (int q = 0; q < Quarters.N; q++) {
            if (FIRE_RETRY[q] > 0) FIRE_RETRY[q]--;
            float v = FIRE.volume(q);
            boolean plays = FIRE.plays(q);
            FIRE_QUIET[q] = plays ? 0 : FIRE_QUIET[q] + 1;
            double az = Quarters.azimuth(q);
            Vec3 toward = ear.add(Math.cos(az) * 10, 0, Math.sin(az) * 10);
            AmbientLoop l = FIRE_LOOPS[q];
            if (l != null && l.playing() && FIRE_QUIET[q] <= Rumble.HOLD) {
                l.set(ear, toward, v, FIRE.highs(q), 1);
                continue;
            }
            if (l != null) l.end();
            FIRE_LOOPS[q] = null;
            if (!plays || !ambient || FIRE_RETRY[q] > 0) continue;
            AmbientLoop fresh = new AmbientLoop(ModSounds.BLAST_FIRE.get(), SoundSource.AMBIENT, 1, variant(ModSounds.BLAST_FIRE.get(), q));
            fresh.set(ear, toward, v, FIRE.highs(q), 1);
            mc.getSoundManager().play(fresh);
            FIRE_RETRY[q] = 20;
            FIRE_LOOPS[q] = fresh;
        }
    }

    /**
     * Петли гула по четвертям ({@link Rumble}), раз в тик клиента ({@code now} — сразу, когда гул изменился): встают, когда
     * их члены слышны (по мощности, когда голоса стихнут: петля должна звучать раньше, чем стихающий голос отдаст ей
     * долю), с громкостью этого мига; петлю, которой движок не держит, — заново; не играет {@link Rumble#HOLD} тиков —
     * отпустить; ползунок «Окружение» в нуле — не запускать. Слышно ли — решает весь гул ({@link Rumble#plays}), а не
     * каждая петля. Громкость, тон и верха петля дальше берёт сама, в своём тике движка ({@link RumbleLoop}). Вариант записи
     * у каждой четверти свой — шум разных четвертей не согласован.
     */
    private static void rumble(boolean now) {
        Minecraft mc = Minecraft.getInstance();
        boolean ambient = mc.options.getSoundSourceVolume(SoundSource.AMBIENT) > 0;
        RUMBLE.tick(soundTick());
        float total = Rumble.volume(RUMBLE.potentialTotal(), RUMBLE_LUFS);
        for (int q = 0; q < Rumble.QUADRANTS; q++) {
            if (!now && LOOP_RETRY[q] > 0) LOOP_RETRY[q]--;
            boolean keep = now || RUMBLE.keep(q, total);
            RumbleLoop l = LOOPS[q];
            if (l != null && l.playing() && keep) {
                if (now) l.now();
                continue;
            }
            if (l != null) l.end();
            LOOPS[q] = null;
            if (!RUMBLE.plays(q, total) || !ambient || LOOP_RETRY[q] > 0) continue;
            RumbleLoop fresh = new RumbleLoop(q);
            fresh.aim();
            fresh.prime();
            mc.getSoundManager().play(fresh);
            LOOP_RETRY[q] = 20;
            LOOPS[q] = fresh;
        }
    }

    /**
     * Петля гула четверти: громкость, тон и верха берёт в своём тике звукового движка — в том же, где голоса взрывов
     * стихают (голос — {@code MixedShot.tick}, гул — здесь, одни часы): посчитанная в тике клиента после него, она
     * отставала бы на тик, и переход голос → гул терял бы до 3,5 дБ.
     */
    private static final class RumbleLoop extends AmbientLoop {
        private final int q;
        /** Хозяин отпустил её: стихает и уходит, громкость гула больше не берёт. */
        private boolean released;

        RumbleLoop(int q) {
            super(ModSounds.BLAST_RUMBLE.get(), SoundSource.AMBIENT, RUMBLE.pitch(q), variant(ModSounds.BLAST_RUMBLE.get(), q), 1);
            this.q = q;
        }

        /** Громкость, тон и верха — по гулу сейчас. */
        void aim() {
            Vec3 ear = ear();
            double az = Rumble.azimuth(q);
            set(ear, ear.add(Math.cos(az) * 10, 0, Math.sin(az) * 10), Rumble.volume(RUMBLE.power(q), RUMBLE_LUFS), RUMBLE.highs(q), RUMBLE.pitch(q));
        }

        @Override
        public void tick() {
            if (!released) {
                RUMBLE.tick(soundTick());
                aim();
            }
            super.tick();
        }

        @Override
        public void end() {
            released = true;
            super.end();
        }

        /**
         * Громкость — в канал сейчас, не дожидаясь тика движка: взрыв, у которого голоса нет вовсе, звучит в гуле с того же
         * мига, с какого звучал бы голос (так же, как движок ставит громкость в тике: × ползунок категории).
         */
        void now() {
            aim();
            prime();
            var h = Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.get(this);
            if (h == null || getSound() == null) return;
            float v = Math.max(0, Math.min(1, getVolume() * Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.AMBIENT)));
            h.execute(ch -> ch.setVolume(v));
        }
    }

    /** Зерно, которым петля четверти {@code q} берёт свой вариант записи {@code event} (гул взрывов, гул пожаров). */
    private static long variant(SoundEvent event, int q) {
        WeighedSoundEvents w = Minecraft.getInstance().getSoundManager().getSoundEvent(event.getLocation());
        return Quarters.seed(w == null ? 1 : w.getWeight(), q);
    }

    /** Движок звука загружен заново: потолок голосов — по источникам устройства; прежние каналы ушли с ним. */
    static void engineLoaded() {
        java.util.Arrays.fill(LOOPS, null);
        java.util.Arrays.fill(FIRE_LOOPS, null);
        RUMBLE.clear();
        int[] ms = OwnSources.deviceSources();
        if (ms == null) return;
        VOICES.device(ms[0], ms[1]);
        reach = BlastVoices.reach(ms[0], ms[1]);
        Incoming.LOG.info("Blast voices: device {} mono + {} stereo sources, {} engine channels reachable, {} for blast sounds", ms[0], ms[1], reach,
                VOICES.cap());
    }

    /** Разовый звук взрыва целиком (одна запись — один взрыв, вариант по зерну {@code seed}) — в смесь, до того как зазвучит. */
    private static Mix.Blast mixed(SoundEvent event, float volume, float pitch, long seed) {
        Mix.Blast b = MIX.blast(ticks);
        b.add(new Mix.Layer(Envelopes.of(event.getLocation()), Envelopes.energy(event, seed), pitch, volume, Mix.Kind.APART, 0));
        return b;
    }

    /**
     * Срок звука слоя, тиков: запись с её тоном и запас. Звук смеси тикает, а тикающий звук, чей канал движок снял без
     * {@code stop} (ползунок «Окружение» в нуле), так и остался бы в его списке.
     */
    private static int life(Mix.Layer layer) {
        return (int) Math.ceil(layer.ticks()) + 20;
    }

    /** Срок звука взрыва из одной записи ({@link #mixed}), тиков. */
    private static int life(Mix.Blast blast) {
        return life(blast.layers.get(0));
    }

    /** Отбой, перемотка повтора, выход из мира: эхо, что ещё готовилось, забыть (его голоса снимает {@link OwnSources#reset}). */
    static void reset() {
        ECHOES.forEach(BlastEchoes::stop);
        ECHOES.clear();
        MIX.reset();
        LATE.clear();
        VOICES.reset();
        RUMBLE.clear();
        for (int q = 0; q < LOOPS.length; q++) {
            if (LOOPS[q] != null) LOOPS[q].end();
            LOOPS[q] = null;
        }
        FIRES.clear();
        FIRE.clear();
        for (int q = 0; q < FIRE_LOOPS.length; q++) {
            if (FIRE_LOOPS[q] != null) FIRE_LOOPS[q].end();
            FIRE_LOOPS[q] = null;
        }
        lastEchoes = null;
        last = "";
    }

    /**
     * Бомба взорвалась под землёй и наружу не прорвалась: над землёй слышна та доля заряда, что вышла в воздух
     * ({@code s.chargeKg()}, {@code warhead.BuriedBlast#airShare}), — глухим ударом (запись без верхов: их съел грунт),
     * громкость — по её давлению у уха. Слушатель под землёй — гул в толще, той же громкости.
     *
     * @param underground слушатель сам под землёй
     */
    public static void buried(BlastSource s, Vec3 pos, boolean underground) {
        double pa = BlastMix.pressure(s, ear().distanceTo(pos));
        float v = byPressure(pa, underground ? CAVE_LUFS : DEEP_LUFS);
        if (v < AUDIBLE) return;
        ClientSounds.ducked(BlastMix.loudness(pa));
        SoundEvent event = underground ? ModSounds.BOMB_CAVE.get() : ModSounds.BOMB_DEEP.get();
        bomb(event, pos, v, 1);
    }

    /**
     * Бомба вошла в грунт ({@code tntKg} — энергия удара, кг ТНТ, {@code warhead.BunkerImpact}): тупой удар — громкость по
     * давлению той доли энергии, что ушла в воздух. Хлопка нет: N-волну сверхзвуковой бомбы играет её полёт ({@link Booms}).
     */
    public static void bunkerImpact(Vec3 pos, double tntKg) {
        float v = byPressure(BlastMix.pressure(BlastSource.air(BunkerImpact.airTnt(tntKg)), ear().distanceTo(pos)), IMPACT_LUFS);
        if (v >= AUDIBLE) bomb(ModSounds.BOMB_IMPACT.get(), pos, v, 1);
    }

    /** Газы взрыва вырвались по скважине: рёв выброса, громкость — по давлению доли заряда {@code airKg}, что вышла в воздух. */
    public static void vent(Vec3 pos, double airKg) {
        float v = byPressure(BlastMix.pressure(BlastSource.air(airKg), ear().distanceTo(pos)), VENT_LUFS);
        if (v >= AUDIBLE) bomb(ModSounds.BOMB_VENT.get(), pos, v, 1);
    }

    /**
     * Свод над полостью обрушился ({@code tntKg} — энергия падения породы, кг ТНТ): гул обвала — громкость по давлению
     * доли, что ушла в воздух (как у удара бомбы о землю, {@link BunkerImpact#airTnt}).
     */
    public static void collapse(Vec3 pos, double tntKg) {
        float v = byPressure(BlastMix.pressure(BlastSource.air(BunkerImpact.airTnt(tntKg)), ear().distanceTo(pos)), QUAKE_LUFS);
        if (v >= AUDIBLE) bomb(ModSounds.BOMB_QUAKE.get(), pos, v, 0.8f);
    }

    /** Громкость записи громкостью {@code fileLufs} для давления {@code pa} у уха ({@link BlastMix#loudness}). */
    static float byPressure(double pa, double fileLufs) {
        return BlastMix.gain(BlastMix.loudness(pa) - fileLufs);
    }

    /** Громкость записи громкостью {@code fileLufs} у взрыва {@code s} на краю ближней модели ({@link Outdoor#NEAR}). */
    static float atNear(BlastSource s, double fileLufs) {
        return byPressure(BlastMix.pressure(s, Outdoor.NEAR), fileLufs);
    }

    private static Vec3 ear() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    /** Толчок земли у слушателя по прежнему пакету без источника (старые записи повтора Flashback): в полную силу. */
    public static void quake() {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        bomb(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), 1, 1);
    }

    /**
     * Удельное акустическое сопротивление воздуха ρ₀c₀, Па·с/м (1,204 кг/м³ × 343 м/с при 20 °C): плоская поверхность,
     * что ходит со скоростью v, излучает давление ρ₀c₀·v.
     */
    static final double AIR_IMPEDANCE = 1.204 * 343;

    /**
     * Гул земли, когда до ног дошла волна грунта с пиковой скоростью {@code ppvMps}, м/с: давление — ρ₀c₀·v у земли под
     * ухом (вся округа ходит одной фазой — излучатель много больше длины волны; ⚠ оценка: коэффициент излучения 1, у
     * волны в десятки метров длиной на мягком грунте он меньше), громкость — как у взрывов по давлению у уха.
     */
    public static void quake(double ppvMps) {
        float v = byPressure(rumblePa(ppvMps), QUAKE_LUFS);
        if (v < AUDIBLE) return;
        Vec3 ear = ear();
        bomb(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), v, 1);
    }

    /** Давление гула земли, Па, у скорости грунта {@code ppvMps}, м/с. */
    static double rumblePa(double ppvMps) {
        return AIR_IMPEDANCE * Math.max(0, ppvMps);
    }

    /** Звук бомбы — в смеси взрывов, своим взрывом в ней (каждый приход — свой). */
    private static void bomb(SoundEvent event, Vec3 pos, float volume, float pitch) {
        long seed = SEEDS.nextLong();
        Mix.Blast blast = mixed(event, volume, pitch, seed);
        voice(blast, List.of(ClientSounds.blastShot(event, pos, volume, pitch, seed, blast::factor, life(blast))), pos, pitch, false);
    }

    /**
     * Выброс воронки падает обратно: {@code kg} грунта с высоты {@code height} м — мощность звука осыпи по энергии
     * падения ({@link Sources#fall}), докуда слышно — путь до уха ({@link Sources}).
     */
    public static void debris(Vec3 pos, double kg, double height, float pitch) {
        Sources.play(ModSounds.DEBRIS_FALL.get(), pos, Sources.EJECTA.power(Sources.fall(kg, height, EJECTA_FALL_S, Spectra.DEBRIS_FALL)), pitch,
                BlastSounds::oneShot);
    }

    /**
     * Разовый звук взрыва, дошедший до уха с громкостью {@code volume} (осыпь выброса: громкость — по закону источника,
     * {@link Sources}), — в смесь взрывов своим взрывом и голосом через бюджет ({@link BlastVoices}): в налёте осыпь каждой
     * воронки — свой звук на 2–9 с, и мимо бюджета 300 шахедов держали ею ~150 каналов, пока ближний не находил ни одного.
     */
    private static void oneShot(SoundEvent event, Vec3 at, float volume, float pitch) {
        long seed = SEEDS.nextLong();
        Mix.Blast blast = mixed(event, volume, pitch, seed);
        voice(blast, List.of(ClientSounds.blastShot(event, at, volume, pitch, seed, blast::factor, life(blast))), at, pitch, false);
    }

    /** Осыпь выброса длится столько, с — длина записи осыпи без хвоста (О: выброс падает за 1–3 с). */
    static final double EJECTA_FALL_S = 2;

    /**
     * Пожар топлива в воронке (огонь на блоках сервера, с удара прошло {@code age} тиков): запись петлёй в своей скорости,
     * тепловыделение, длительность и место — у топлива {@code burn} (стекло, засыпало — гул идёт за ним).
     */
    public static void fire(ua.zentix.incoming.client.fx.FuelBurn burn, int age) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null && burn.law().watts() > 0) FIRES.add(new CraterFire(burn, world, age));
    }
}
