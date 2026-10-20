package ua.zentix.incoming.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.client.fx.BlastSource;
import ua.zentix.incoming.client.nuclear.Deafness;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.ArrayList;
import java.util.List;

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
 * запись. Моторы снарядов под громкий взрыв уходят вниз ({@link Ducking}).
 */
public final class BlastSounds {
    /** Громкость глухого удара бомбы из-под земли — и на краю ближней модели, с неё продолжается {@link #far}. */
    static final float BUNKER_FAR_FLOOR = 1;
    /**
     * Докуда слышно взрыв бомбы под землёй, блоков: заряда бомбы в паспорте пока нет (0 — её взрыв считает сервер
     * своими силами, {@code WeaponSpec.BUNKER}), поэтому — прежняя дальность (не из модели), пока паспорт его не получит.
     */
    static final double BUNKER_RANGE = 30_000;
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

    private BlastSounds() {}

    /** Докуда слышно взрыв в обычных условиях (R0 у {@link Outdoor}), блоков. */
    public static double range(BlastSource s) {
        return s.underground() ? BUNKER_RANGE : HearingModel.audibleRangeM(s.chargeKg());
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
        Outdoor.Heard heard = Outdoor.hear(range(s), s.underground() ? BUNKER_FAR_FLOOR : BlastMix.v640(s), path);
        float v = heard.volume();
        if (v < AUDIBLE) return heard;
        float highs = highs(path);
        if (s.underground()) ClientSounds.atEar(ModSounds.BOMB_DEEP.get(), pos, v, 1, seed, 1, highs);
        else ClientSounds.atEar(ModSounds.BLAST_FAR.get(), pos, v, BlastMix.pitch(s.chargeKg()), seed, 1, highs);
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
        DimensionType type = level.dimensionType();
        // день: земля греет воздух, звук загибает вверх; нет солнца (Незер, Энд) — как ночью
        double day = type.hasSkyLight() && !type.hasFixedTime() ? Outdoor.day(Mth.cos(level.getTimeOfDay(1) * Mth.TWO_PI)) : 0;
        return new Outdoor.Path(d, z, hs, Math.max(1.5, ear.y - top), gs, under == null ? 0.5 : under.porosity,
                day, level.getRainLevel(1), level.getThunderLevel(1));
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
        ECHOES.add(e);
        return e;
    }

    /**
     * Обычный взрыв (на земле или в воздухе) — фронт дошёл до уха в тик {@code tick} по часам взрыва: ракурсы записи и
     * «удар в грудь» ({@link BlastMix}), удар у уха из модели своим источником ({@link BlastPulse}), эхо от рельефа
     * ({@code echoes}, null — без него) — от настоящего начала самого громкого ракурса. Звуки самой волны слышит ухо,
     * каким оно было до неё ({@link Deafness#before}): слух, если волна его бьёт, — после.
     */
    public static void arrive(BlastSource s, Vec3 pos, long seed, OwnSources.Clock clock, double tick, @Nullable BlastEchoes echoes) {
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
        for (int i = 0; i < 3; i++) {
            if (volumes[i] < AUDIBLE) continue;
            SoundInstance shot = ClientSounds.shot(events[i], pos, volumes[i], pitch, seed);
            shots.add(shot);
            if (volumes[i] > most) {
                most = volumes[i];
                loudest = shot;
            }
        }
        if (l.sub() >= AUDIBLE) shots.add(ClientSounds.shot(ModSounds.BLAST_SUB.get(), pos, l.sub(), pitch, seed ^ SUB_SALT));
        // эхо и удар у уха ждут настоящего начала прямого звука: ожидание — до play
        if (echoes != null) echoes.expect(tick, loudest);
        if (l.pulse() >= AUDIBLE) {
            Object owner = new Object();
            OwnSources.Anchor start = OwnSources.anchor(owner, loudest, clock, tick);
            OwnSources.play(owner, start, 0, Math.atan2(pos.z - ear.z, pos.x - ear.x), l.pulse(),
                    BlastPulse.render(pa, BlastMix.phase(s, d), 1), BlastPulse.RATE, true, true);
        }
        for (SoundInstance shot : shots) {
            Deafness.before(shot);
            ClientSounds.start(shot);
        }
        if (echoes != null) echoes.arrive(ear, BlastMix.uncapped(loudness - BlastMix.FAR_LUFS), pitch);
    }

    /** Отбой, перемотка повтора, выход из мира: эхо, что ещё готовилось, забыть (его голоса снимает {@link OwnSources#reset}). */
    static void reset() {
        ECHOES.forEach(BlastEchoes::stop);
        ECHOES.clear();
    }

    /** Бетонобойная бомба взорвалась под землёй. underground — слушатель сам под землёй (в той же толще). */
    public static void bunker(Vec3 pos, int band, boolean underground) {
        if (underground && band <= 4) {
            // взрыв в замкнутом пространстве: жёстко и с долгим эхом, сверху сыплется порода
            ClientSounds.atEar(ModSounds.BOMB_CAVE.get(), pos, 1, 1);
            ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 1, 0.8f);
            ClientSounds.atEar(ModSounds.BLAST_NEAR.get(), pos, 0.7f, 0.75f);
            ClientSounds.atEar(ModSounds.DEBRIS_FALL.get(), pos, 1, 0.8f);
        } else {
            // на поверхности — глухой удар из-под земли, земля дрожит
            ClientSounds.atEar(ModSounds.BOMB_DEEP.get(), pos, BUNKER_FAR_FLOOR, 1);
            if (band <= 6) ClientSounds.atEar(ModSounds.BLAST_SUB.get(), pos, 0.7f, 0.6f);
        }
    }

    /** Бомба вошла в грунт: хлопок и тупой удар. */
    public static void bunkerImpact(Vec3 pos, int band) {
        if (band > 18) return;
        float v = Math.max(0.3f, 1 - band / 18f);
        ClientSounds.atEar(ModSounds.BOMB_CRACK.get(), pos, v, 1);
        ClientSounds.atEar(ModSounds.BOMB_IMPACT.get(), pos, v, 1);
    }

    /** Газы взрыва вырвались по скважине. */
    public static void vent(Vec3 pos, int band) {
        if (band <= 10) ClientSounds.atEar(ModSounds.BOMB_VENT.get(), pos, Math.max(0.3f, 1 - band / 10f), 1);
    }

    /** Свод над полостью обрушился. */
    public static void collapse(Vec3 pos, int band) {
        if (band <= 7) ClientSounds.atEar(ModSounds.BOMB_QUAKE.get(), pos, Math.max(0.4f, 1 - band / 8f), 0.8f);
    }

    /** Толчок земли у слушателя (сервер шлёт тем, кто стоит над взрывом). */
    public static void quake() {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        ClientSounds.atEar(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), 1, 1);
    }

    /** Обломки и земля падают обратно — слышно тем, кто ближе radius блоков. */
    public static void debris(Vec3 pos, double radius, float pitch) {
        if (near(pos, radius)) ClientSounds.at(ModSounds.DEBRIS_FALL.get(), pos, 3, pitch);
    }

    /** Пожар в воронке гудит и трещит (слышно в пределах ~80 блоков). */
    public static void fire(Vec3 pos, float pitch) {
        if (near(pos, 90)) ClientSounds.at(ModSounds.BLAST_FIRE.get(), pos, 5, pitch);
    }

    /** Вторичный подрыв в воронке: небольшой взрыв, выше тоном. */
    public static void cookoff(Vec3 pos) {
        ClientSounds.at(ModSounds.BLAST_NEAR.get(), pos, 6, 1.35f);
    }

    private static boolean near(Vec3 pos, double radius) {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().distanceTo(pos) <= radius;
    }
}
