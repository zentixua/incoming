package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.client.fx.ClientAir;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.fx.BlastHit;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.nuclear.Deafness;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Spectra;
import ua.zentix.incoming.warhead.BunkerImpact;
import ua.zentix.incoming.warhead.GroundMaterial;

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
 * уха, эхо, звуки бомбы) — в одной смеси ({@link Mix}): потолок громкости — у суммы, а не у каждого.
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
        ClientSounds.blastAtEar(event, pos, v, pitch, seed, 1, highs, BlastSounds::factor, mixed(event, v, pitch));
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

    /** Доля верхов записи раската по пути {@link Outdoor} и воздуху; путь звука считает Sound Physics или он выключен — 1. */
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
        BlastEchoes e = new BlastEchoes(clock, pos, new TerrainEcho.Blast() {
            @Override
            public double arrivalTicks(double blocks) {
                return blocks / Acoustics.SPEED;
            }

            @Override
            public double pressure(double blocks) {
                return BlastModel.overpressureKpa(blocks, kg, s.surface());
            }
        }, ModSounds.BLAST_FAR.get(), seed);
        e.bus(BlastSounds::factor);
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
        List<SoundInstance> shots = new ArrayList<>(4);
        SoundInstance loudest = null;
        float most = 0;
        float[] volumes = {l.near(), l.mid(), l.far()};
        SoundEvent[] events = {ModSounds.BLAST_NEAR.get(), ModSounds.BLAST_MID.get(), ModSounds.BLAST_FAR.get()};
        // слои — в смесь до того, как зазвучат: громкость первого же тика — уже с этим взрывом
        Mix.Blast blast = MIX.blast(ticks);
        for (int i = 0; i < 3; i++) {
            if (volumes[i] < AUDIBLE) continue;
            Mix.Layer layer = new Mix.Layer(Envelopes.of(events[i].getLocation()), pitch, volumes[i]);
            blast.add(layer);
            SoundInstance shot = ClientSounds.blastShot(events[i], pos, volumes[i], pitch, seed, BlastSounds::factor, life(layer));
            shots.add(shot);
            if (volumes[i] > most) {
                most = volumes[i];
                loudest = shot;
            }
        }
        if (l.sub() >= AUDIBLE) {
            // удар в грудь — своя запись (низ других подрывов и синтез): с ракурсами — по мощности
            Mix.Layer layer = new Mix.Layer(Envelopes.of(ModSounds.BLAST_SUB.get().getLocation()), pitch, l.sub(), true);
            blast.add(layer);
            shots.add(ClientSounds.blastShot(ModSounds.BLAST_SUB.get(), pos, l.sub(), pitch, seed ^ SUB_SALT, BlastSounds::factor, life(layer)));
        }
        // эхо и удар у уха ждут настоящего начала прямого звука: ожидание — до play
        if (echoes != null) echoes.expect(tick, loudest);
        boolean pulse = false;
        if (l.pulse() >= AUDIBLE) {
            Object owner = new Object();
            OwnSources.Anchor start = OwnSources.anchor(owner, loudest, clock, tick);
            short[] track = BlastPulse.render(pa, BlastMix.phase(s, d), 1);
            // удар у уха — дорожка из модели: в смеси своей огибающей, по мощности
            float[] x = new float[track.length];
            for (int i = 0; i < x.length; i++) x[i] = track[i] / 32768f;
            blast.add(new Mix.Layer(Envelope.of(x, BlastPulse.RATE), 1, l.pulse(), true));
            pulse = OwnSources.play(owner, start, 0, Math.atan2(pos.z - ear.z, pos.x - ear.x), l.pulse(), track, BlastPulse.RATE, true, true,
                    BlastSounds::factor);
        }
        last = String.format(Locale.ROOT, "%s %.0f kg d %.0f p %.0f Pa (%.1f dB) phase %.1f ms loud %.1f LUFS pitch %.2f near %.2f mid %.2f far %.2f sub %.2f pulse %.2f %s echo %s",
                s.weapon() == null ? "air" : s.weapon().name(), s.chargeKg(), d, pa, 20 * Math.log10(Math.max(pa, 1e-9) / 2e-5), 1000 * BlastMix.phase(s, d),
                loudness, pitch, l.near(), l.mid(), l.far(), l.sub(), l.pulse(), pulse ? "played" : "-", echoes == null ? "-" : "on");
        if (echoes != null) lastEchoes = echoes;
        for (SoundInstance shot : shots) {
            Deafness.before(shot);
            ClientSounds.start(shot);
        }
        if (echoes != null) echoes.arrive(ear, BlastMix.uncapped(loudness - BlastMix.FAR_LUFS), pitch);
    }

    /** Строка {@code SCENARIO blast-sound}: последний обычный взрыв у уха и его эхо; пусто — взрывов не было. */
    public static String describe() {
        if (last.isEmpty()) return "";
        BlastEchoes e = lastEchoes;
        return last + (e == null ? "" : "; " + e.describe()) + "; " + BlastHit.describe();
    }

    /** Часы взрыва, родившегося сейчас: тики с этого мига, с долей. */
    public static OwnSources.Clock clock() {
        long born = ticks;
        return partial -> ticks - born + partial;
    }

    /** Тик клиента. */
    static void tick() {
        ticks++;
    }

    /** Множитель громкости всех звуков взрывов сейчас ({@link Mix}). */
    static double factor() {
        return MIX.factor(ticks);
    }

    /** Разовый звук взрыва целиком (одна запись — один взрыв) — в смесь, до того как зазвучит; срок звука, тиков. */
    private static int mixed(SoundEvent event, float volume, float pitch) {
        Mix.Layer layer = new Mix.Layer(Envelopes.of(event.getLocation()), pitch, volume);
        MIX.blast(ticks).add(layer);
        return life(layer);
    }

    /**
     * Срок звука слоя, тиков: запись с её тоном и запас. Звук смеси тикает, а тикающий звук, чей канал движок снял без
     * {@code stop} (ползунок «Окружение» в нуле), так и остался бы в его списке.
     */
    private static int life(Mix.Layer layer) {
        return (int) Math.ceil(layer.ticks()) + 20;
    }

    /** Отбой, перемотка повтора, выход из мира: эхо, что ещё готовилось, забыть (его голоса снимает {@link OwnSources#reset}). */
    static void reset() {
        ECHOES.forEach(BlastEchoes::stop);
        ECHOES.clear();
        MIX.reset();
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
        ClientSounds.blastAtEar(event, pos, v, 1, BlastSounds::factor, mixed(event, v, 1));
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

    /** Толчок земли у слушателя (сервер шлёт тем, кто стоит над взрывом). */
    public static void quake() {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        bomb(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), 1, 1);
    }

    /** Звук бомбы — в смеси взрывов. */
    private static void bomb(SoundEvent event, Vec3 pos, float volume, float pitch) {
        ClientSounds.blastAtEar(event, pos, volume, pitch, BlastSounds::factor, mixed(event, volume, pitch));
    }

    /**
     * Выброс воронки падает обратно: {@code kg} грунта с высоты {@code height} м — мощность звука осыпи по энергии
     * падения ({@link Sources#fall}), докуда слышно — путь до уха ({@link Sources}).
     */
    public static void debris(Vec3 pos, double kg, double height, float pitch) {
        Sources.play(ModSounds.DEBRIS_FALL.get(), pos, Sources.EJECTA.power(Sources.fall(kg, height, EJECTA_FALL_S, Spectra.DEBRIS_FALL)), pitch);
    }

    /** Осыпь выброса длится столько, с — длина записи осыпи без хвоста (О: выброс падает за 1–3 с). */
    static final double EJECTA_FALL_S = 2;

    /**
     * Пожар в воронке гудит и трещит: мощность шума пламени — доля тепловыделения лужи {@code watts}, Вт
     * ({@link Sources#flame}), докуда слышно — путь до уха.
     */
    public static void fire(Vec3 pos, double watts, float pitch) {
        Sources.play(ModSounds.BLAST_FIRE.get(), pos, Sources.BLAST_FIRE.power(Sources.flame(watts, Spectra.BLAST_FIRE)), pitch);
    }
}
