package ua.zentix.incoming.client.nuclear;

import ua.zentix.incoming.client.fx.FireWatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.far.FarTerrain;
import ua.zentix.incoming.client.far.Sightline;
import ua.zentix.incoming.client.fx.CameraShake;
import ua.zentix.incoming.client.sound.AmbientLoop;
import ua.zentix.incoming.client.sound.BlastPulse;
import ua.zentix.incoming.client.sound.BlastSounds;
import ua.zentix.incoming.client.sound.Clatter;
import ua.zentix.incoming.client.sound.ClientSounds;
import ua.zentix.incoming.client.sound.Outdoor;
import ua.zentix.incoming.client.sound.OwnSources;
import ua.zentix.incoming.client.sound.SirenSound;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.client.sound.Sources;
import ua.zentix.incoming.entity.FlightPhase;
import ua.zentix.incoming.sound.Noise;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.nuclear.radiation.Shielding;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.sound.Propagation;
import ua.zentix.incoming.warhead.GroundMaterial;

import java.util.ArrayList;
import java.util.List;

/**
 * Звук ядерного удара (DESIGN-nuke §5). Свет приходит сразу, звук — с фронтом ударной волны: каждый тик клиент
 * сравнивает радиус фронта ({@link Detonation#frontRadius}) со своим расстоянием до эпицентра, и когда фронт
 * дошёл — удар, ветер за фронтом, звон стёкол, потом эхо от рельефа. Громкость и тембр — от давления у слушателя и
 * пути звука, а не от ванильного затухания; направление честное (точка в 3 блоках от уха в сторону эпицентра).
 */
public final class NukeSounds {
    /** Вход боеголовки виден последние 3 с — беззвучно: на 7 км/с её звук придёт уже после взрыва. */
    static final int REENTRY_TICKS = 60;
    /** Сирены по разные стороны от места, где слушателя застала тревога, — как на улицах города, и их тон на полной скорости. */
    private static final Vec3[] SIREN_AT = {new Vec3(140, 25, -90), new Vec3(-120, 25, 130)};
    private static final float[] SIREN_TONE = {1.0f, 0.98f};
    /** Движок не дал сирене канал (звука нет, каналы заняты): через столько тиков попробовать снова. */
    private static final int SIREN_RETRY = 20;

    @Nullable
    private static RainLoop rainLoop;
    /** Сирены, пока у слушателя тревога; {@code null} — эта не звучит (выключенные сбегают сами). */
    private static final SirenSound[] SIRENS = new SirenSound[SIREN_AT.length];
    /** Где слушателя застала тревога, в каком мире; {@code null} — тревоги нет. */
    @Nullable
    private static Vec3 sirenOrigin;
    @Nullable
    private static ClientLevel sirenLevel;
    private static int sirenWait;

    private NukeSounds() {}

    /** Пуск МБР: у запустившего — рёв двигателя со стола. Сирена гражданской обороны — {@link #sirens}. */
    static void warning(S2C.NukeWarning w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = mc.level.getGameTime();
        if (w.mine() && now - w.launchTime() < 40) {
            // мощность — закон РДТТ первой ступени из паспорта звука МБР, запись — старт со стола
            Noise.Rocket motor = WeaponSpec.NUKE.airframe().noise().motor();
            Sources.Source s = Sources.NUKE_LAUNCH;
            if (motor != null) {
                Sources.play(ModSounds.NUKE_LAUNCH.get(), w.launchPos(), s.power(motor.power(FlightPhase.BOOST, 0) + s.spectrum().aWeighting()), 1);
            }
        }
    }

    /**
     * Петля чёрного дождя — после {@link NukeSky#tick}, который решает, идёт ли он у камеры; сирены. Огонь в своих чанках
     * ({@link FireWatch}) смотрит тик клиента — всегда: по нему и дым над любыми пожарами.
     */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (NukeSky.inBlackRain() && (rainLoop == null || rainLoop.isStopped())) {
            rainLoop = new RainLoop();
            mc.getSoundManager().play(rainLoop);
        }
        sirens();
    }

    /** Строка {@code SCENARIO}: эхо и горящий город каждого подрыва; пусто — подрывов нет. */
    public static String describe() {
        StringBuilder sb = new StringBuilder();
        for (ClientNuclear.Active a : ClientNuclear.detonations()) {
            sb.append(String.format(java.util.Locale.ROOT, "#%d t=%.0f ", a.d.id(), a.ticks(0)));
            sb.append(a.sounds.describe());
            if (a.sounds.echoes != null) sb.append(a.sounds.echoes.describe()).append("; ");
            sb.append(a.sounds.after.describe()).append(" | ");
        }
        return sb.isEmpty() ? "" : sb + ua.zentix.incoming.client.sound.OwnSources.describe() + " " + FireWatch.describe() + " " + Deafness.describe();
    }

    /**
     * Сирены гражданской обороны воют, пока летит хоть одна МБР, о которой слушателю объявлена тревога: до подрыва
     * (пакет подрыва снимает предупреждение), отбоя (сервер присылает предупреждения заново) или конца ожидания места
     * удара; потом сбегают. Сирену, которую движок не держит (перезапуск звука, смена измерения, ползунок «Окружение»
     * был в нуле), — запустить снова на её месте; пока ползунок в нуле — не запускать: снятый им звук движок не убирает.
     */
    private static void sirens() {
        Minecraft mc = Minecraft.getInstance();
        if (sirenWait > 0) sirenWait--;
        boolean alarm = false;
        for (S2C.NukeWarning w : ClientNuclear.warnings()) alarm |= w.alarm();
        if (!alarm || mc.level != sirenLevel) {
            for (int i = 0; i < SIRENS.length; i++) {
                if (SIRENS[i] != null) SIRENS[i].off();
                SIRENS[i] = null;
            }
            sirenOrigin = null;
            sirenLevel = null;
            if (!alarm) return;
        }
        if (sirenWait > 0 || mc.player == null || mc.options.getSoundSourceVolume(SoundSource.AMBIENT) <= 0) return;
        if (sirenOrigin == null) {
            sirenOrigin = mc.player.position();
            sirenLevel = mc.level;
        }
        for (int i = 0; i < SIRENS.length; i++) {
            if (SIRENS[i] != null && SIRENS[i].playing()) continue;
            if (SIRENS[i] != null) SIRENS[i].kill();
            SIRENS[i] = new SirenSound(ModSounds.NUKE_ALARM.get(), sirenOrigin.add(SIREN_AT[i]), SIREN_TONE[i]);
            mc.getSoundManager().play(SIRENS[i]);
            sirenWait = SIREN_RETRY;
        }
    }

    static void reset() {
        if (rainLoop != null) rainLoop.end();
        rainLoop = null;
        for (int i = 0; i < SIRENS.length; i++) {
            if (SIRENS[i] != null) SIRENS[i].kill();
            SIRENS[i] = null;
        }
        sirenOrigin = null;
        sirenLevel = null;
        sirenWait = 0;
        FireWatch.reset();
        ua.zentix.incoming.client.sound.OwnSources.reset();
    }

    /** Звуки одного подрыва у этого слушателя. */
    static final class Schedule {
        /**
         * С какого давления у уха прямой удар — в полную громкость, Па: 1 psi, как у обычных взрывов
         * ({@link ua.zentix.incoming.client.sound.BlastMix#FULL_PA}). Громче динамики не сыграют — дальше
         * громкость не растёт (кроме удара фронта вблизи, он и так перегружен).
         */
        static final double FULL_PA = 1000 * BlastModel.kpa(1);
        /** Ветер, при котором петля урагана звучит в полную громкость, м/с: её запись — ураган (12 баллов Бофорта, от 33 м/с). */
        static final double WIND_FULL = 33;
        /** Петля ветра уходит, когда прошло столько положительных фаз: обратный ветер в разрежении стих. */
        static final double WIND_PHASES = 5;
        /** Тише — звук не запускать. */
        private static final float AUDIBLE = 0.005f;
        /**
         * Тон записей удара фронта: записи — в своей скорости, сдвиг не больше ±25 % ({@code SHIFT_MAX} сборки звуков,
         * {@code NukeTonesTest}); три удара вблизи — разным тоном, чтобы копии одной записи не складывались в гребёнку.
         */
        static final float BOOM_TONE = 1;
        static final float[] CRACK_TONES = {1, 0.92f, 0.85f};

        private final ClientNuclear.Active a;
        private final Detonation d;
        private final boolean live;
        /** Где фронт встретил слушателя и тело игрока. */
        private final FrontMeeting meeting = new FrontMeeting();
        private long arrivalTick;
        /** Давление фронта у слушателя и у тела, psi. */
        private double psi, bodyPsi = -1;
        /** Положительная фаза у слушателя, тики игры. */
        private double phaseTicks;
        /** Наибольший ветер за фронтом у слушателя, м/с. */
        private double windPeak;
        @Nullable
        private AmbientLoop wind;
        /** Что дошло прямым ударом (для строки сценария): громкость, нарастание фронта, удар — своим источником или записью. */
        private float heard;
        private double riseMs;
        private boolean pulse;
        /** Наибольший наклон тела ветром волны, °, и доля лучей к взрыву без стены (для строки сценария). */
        private double swayPeak, swayOpen = -1;
        /** Скорость наклона сразу за фронтом — удар фронта, °/с (для строки сценария). */
        private double swayRate;
        /** Звуки волны у слушателя: он снова впереди фронта — они прочь ({@link #again}). */
        private final List<SoundInstance> shots = new ArrayList<>();
        /** Стёкла и обломки, что снесла волна у слушателя, — к уху ({@link #clatter}); сыгранные — для строки сценария. */
        private final List<Ruins> ruinPackets = new ArrayList<>();
        private final Clatter clatter = new Clatter(new ua.zentix.incoming.sound.Spectrum[]{Sources.RUIN_GLASS.spectrum(), Sources.RUIN_CLATTER.spectrum()});
        private final List<Clatter.Play> plays = new ArrayList<>();
        private final int[] heardClatter = new int[Clatter.KINDS];
        private final double[] loudestClatter = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        /** Эхо от рельефа — только вживую. */
        @Nullable
        NukeEchoes echoes;
        /** Горящий город — в любом возрасте подрыва. */
        final NukeAfter after;

        Schedule(ClientNuclear.Active a) {
            this.a = a;
            this.d = a.d;
            this.live = a.live;
            this.echoes = live ? new NukeEchoes(a) : null;
            this.after = new NukeAfter(a);
        }

        /** Подрыв убран (смена измерения, возрождение, перемотка повтора, срок): удар, эхо, ветер и петли горящего города — прочь. */
        void stop() {
            clatter.clear();
            ruinPackets.clear();
            if (echoes != null) echoes.stop();
            OwnSources.stop(this);
            if (wind != null) wind.end();
            wind = null;
            after.end();
        }

        /**
         * Сервер снёс блоки в чанке рядом: места руин — для поздних обрушений; стёкла и обломки — звуком, когда до места
         * дойдёт фронт ({@link #clatter}).
         */
        void debris(S2C.NukeDebris p) {
            after.ruins(p.pieces());
            if (live && !p.pieces().isEmpty()) ruinPackets.add(new Ruins(p, a.ticks(0)));
        }

        /** Пакет обломков, пришедший в тик {@code at} (часы подрыва): звук считается, когда руины чанка дошли до клиента. */
        private record Ruins(S2C.NukeDebris packet, double at) {}

        /**
         * Сколько тиков после пакета обломков ждать руин чанка у клиента: сервер шлёт пакет в тике подмены, а сами руины
         * (изменения секций) уходят игроку в следующем тике сервера — до них мир клиента у этих мест ещё прежний, и куда
         * упадёт сорванное, по нему не узнать.
         */
        static final int RUINS_SETTLE = 2;
        /** Ниже места сорванное ищет, куда упасть, не дальше стольких блоков (дальше — земля под эпицентром). */
        static final int FALL_SCAN = 256;

        /**
         * Звук разрушения чанка ({@link Clatter}): каждый снесённый блок — масса его вещества (блок — метр,
         * {@link Shielding#density}: стекло — окно, 25 кг), брошенная напором со скоростью {@link Detonation#throwSpeed} и
         * упавшая до первого блока под своим местом в мире после руин ({@link #landing}), — {@link Clatter#watts}. Места
         * пакета — выборка из всех снесённых ({@link S2C.NukeDebris#weight}); окна ({@link Shielding#window}) и прочее — по
         * отдельности, каждое — одним событием в середине по энергии, с прихода фронта туда. Руины позже фронта больше чем
         * на секунду ({@link NukeDust#DEBRIS_LATE}) — без звука, как и без кусков.
         */
        private void clatter(ClientLevel level, S2C.NukeDebris p) {
            double weight = p.weight(), now = a.ticks(0);
            double[] watts = new double[Clatter.KINDS], seconds = new double[Clatter.KINDS], x = new double[Clatter.KINDS],
                    y = new double[Clatter.KINDS], z = new double[Clatter.KINDS];
            for (S2C.NukeDebris.Piece piece : p.pieces()) {
                BlockPos pos = piece.pos();
                double kg = Shielding.density(level, pos, piece.state());
                if (kg <= 0) continue;
                int kind = Shielding.window(level, pos, piece.state()) ? Clatter.GLASS : Clatter.DEBRIS;
                Vec3 c = Vec3.atCenterOf(pos);
                double h = c.y - landing(level, pos), fall = Clatter.seconds(h);
                // мощность — энергия за время падения; место и время — по мощности
                double w = weight * Clatter.watts(kg, 20 * Detonation.throwSpeed(d.dynamicPressureKpa(c)), h);
                watts[kind] += w;
                seconds[kind] += w * fall;
                x[kind] += w * c.x;
                y[kind] += w * c.y;
                z[kind] += w * c.z;
            }
            for (int k = 0; k < Clatter.KINDS; k++) {
                if (watts[k] <= 0) continue;
                Vec3 at = new Vec3(x[k] / watts[k], y[k] / watts[k], z[k] / watts[k]);
                double start = d.arrivalTicks(at.distanceTo(d.burst()));
                if (start < now - NukeDust.DEBRIS_LATE) continue;
                clatter.add(new Clatter.Event(at.x, at.y, at.z, start, Sources.lw(watts[k]), seconds[k] / watts[k], k));
            }
        }

        /**
         * Куда падает сорванное с места {@code pos}: верх первого блока под ним в мире клиента после руин (окно — на
         * подоконник своего этажа, кладка рухнувшего дома — на завал или землю). Чанка нет или под местом пусто на
         * {@link #FALL_SCAN} блоков — земля под эпицентром.
         */
        private double landing(ClientLevel level, BlockPos pos) {
            LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
            if (chunk == null) return d.groundY();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int y = pos.getY() - 1, end = Math.max(level.getMinBuildHeight(), pos.getY() - FALL_SCAN); y >= end; y--) {
                if (!chunk.getBlockState(m.set(pos.getX(), y, pos.getZ())).isAir()) return y + 1;
            }
            return d.groundY();
        }

        /** Стёкла и обломки, чей звук дошёл до уха, — в игру ({@link Clatter}): запись вида, громкость — по уровню у уха. */
        private void clatterTick(Vec3 ear) {
            if (clatter.pending() == 0 && plays.isEmpty() && ruinPackets.isEmpty()) return;
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) return;
            double now = a.ticks(0);
            for (java.util.Iterator<Ruins> it = ruinPackets.iterator(); it.hasNext(); ) {
                Ruins r = it.next();
                if (now < r.at + RUINS_SETTLE) continue;
                it.remove();
                clatter(level, r.packet);
            }
            plays.clear();
            clatter.tick(a.ticks(0), ear.x, ear.y, ear.z, Propagation.Weather.of(level), plays);
            for (Clatter.Play play : plays) {
                Sources.Source s = play.kind() == Clatter.GLASS ? Sources.RUIN_GLASS : Sources.RUIN_CLATTER;
                float v = Clatter.volume(play, s.fileLufs(), s.ceilingLufs());
                if (v <= 0) continue;
                heardClatter[play.kind()]++;
                loudestClatter[play.kind()] = Math.max(loudestClatter[play.kind()], play.level());
                ClientSounds.atEar(play.kind() == Clatter.GLASS ? ModSounds.NUKE_GLASS.get() : ModSounds.DEBRIS_FALL.get(),
                        new Vec3(play.x(), play.y(), play.z()), v, 1);
            }
            plays.clear();
        }

        /**
         * Звук волны — у слушателя (камера), удар по ушам и ветер — у тела игрока ({@link FrontMeeting}): в камере
         * снаряда, которая облетает место взрыва, ухо камеры получало удар вплотную у игрока за километры.
         */
        void tick() {
            after.tick();
            if (!live) return;
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            Vec3 ear = mc.gameRenderer.getMainCamera().getPosition(), b = d.burst();
            clatterTick(ear);
            // наблюдатель тела не имеет: глазами другой сущности игрок у клиента стоит, где начал смотреть, — тело у камеры
            Vec3 body = p == null ? null : p.isSpectator() ? ear : p.getEyePosition();
            long now = (long) a.ticks(0);
            int met = meeting.tick(d.frontRadius(now), ear.distanceTo(b), body == null ? Double.NaN : body.distanceTo(b),
                    p != null && mc.getCameraEntity() == p);
            if ((met & FrontMeeting.AGAIN) != 0) again();
            if ((met & FrontMeeting.HEARD) != 0) {
                arrivalTick = now;
                psi = d.psi(ear);
                arrive(ear, (met & FrontMeeting.HIT) != 0);
            }
            if ((met & FrontMeeting.HIT) != 0 && p != null && body != null) hit(p, body);
            if (!meeting.heard()) {
                if (echoes != null) echoes.approach(ear);
                return;
            }
            if ((met & FrontMeeting.HEARD) != 0) return;
            if (echoes != null) echoes.tick();
            // стена пыли проходит мимо игрока секунды три
            if (now - arrivalTick < 60 && psi >= 0.5) NukeDust.gust(a, ear, psi, (int) (now - arrivalTick));
            wind(ear, now - arrivalTick);
        }

        /** Строка {@code SCENARIO}: что дошло прямым ударом; пусто — фронт ещё не дошёл. */
        String describe() {
            String ruins = String.format(java.util.Locale.ROOT, "clatter glass %d (%.0f dBA) debris %d (%.0f dBA) pending %d; ",
                    heardClatter[Clatter.GLASS], loudestClatter[Clatter.GLASS], heardClatter[Clatter.DEBRIS], loudestClatter[Clatter.DEBRIS], clatter.pending());
            if (!meeting.heard()) return ruins;
            return String.format(java.util.Locale.ROOT, "direct %.2f psi vol %.3f t+ %.2fs rise %.3fms %s wind %.1fm/s body %.2f psi sway %.2fdeg jolt %.1fdeg/s open %.2f; ",
                    psi, heard, phaseTicks / 20, riseMs, pulse ? "pulse" : "file", windPeak, bodyPsi, swayPeak, swayRate, swayOpen) + ruins;
        }

        /**
         * Слушатель снова впереди фронта (камера вернулась к игроку, перенос): звуки волны у прежнего места, ветер и эхо —
         * прочь, фронт он встретит снова.
         */
        private void again() {
            SoundManager sounds = Minecraft.getInstance().getSoundManager();
            for (SoundInstance s : shots) sounds.stop(s);
            shots.clear();
            // здесь фронта не было: пыль у глаза — с новой встречей
            psi = 0;
            OwnSources.stop(this);
            if (wind != null) wind.end();
            wind = null;
            if (echoes != null) {
                echoes.stop();
                echoes = new NukeEchoes(a);
            }
        }

        /** Звук самой волны у слушателя; бьёт она в тот же тик и по ушам игрока ({@code hit}) — его слышно, как до неё. */
        private SoundInstance wave(SoundInstance sound, boolean hit) {
            if (hit) Deafness.before(sound);
            shots.add(sound);
            return sound;
        }

        /**
         * Фронт у слушателя. Громкость — по пиковому давлению у уха и пути звука, как у обычных взрывов
         * ({@link Outdoor#pressureVolume}: холм между, зона тени днём, дождь), всё по «Общей громкости». Вдали (меньше
         * 1 psi) удар фронта — дорожка из модели ({@link BlastPulse}: один скачок, его крутизна по давлению,
         * положительная фаза модели, {@link Detonation#positivePhaseSeconds}) своим источником с начала прямого удара, и запись далёкого огромного
         * взрыва ({@code nuke.boom_far}) — раскат; эхо от рельефа повторяет её ({@link NukeEchoes}). Вблизи — оглушающий
         * удар (несколько источников вместе — одному OpenAL не даёт громче единицы). Шум ветра за фронтом, стёкла (пыль у
         * глаза — по голове стены, {@link NukeDust#veil}). Слух и наклон — у тела ({@link #hit}); {@code hit} — фронт в этот
         * тик и у тела: звуки волны слышно, как до неё.
         */
        private void arrive(Vec3 ear, boolean hit) {
            Minecraft mc = Minecraft.getInstance();
            Vec3 b = d.burst();
            double dist = ear.distanceTo(b), pa = 1000 * d.overpressureKpa(ear);
            phaseTicks = Math.max(1, 20 * d.scale() * d.positivePhaseSeconds(ear));
            windPeak = d.blastWind(ear);
            Outdoor.Path path = path(ear, dist);
            float v = path == null ? 1 : Outdoor.pressureVolume(pa, FULL_PA, path);
            float highs = path == null ? 1 : BlastSounds.highs(path);
            heard = v;
            long boom = echoes == null ? d.seed() : echoes.seed;
            // эхо и удар фронта ждут настоящего начала прямого удара: ожидание — до play (с готовым буфером звук стартует сразу)
            SoundInstance direct = ClientSounds.loudShot(ModSounds.NUKE_BOOM_FAR.get(), b, v, BOOM_TONE, boom, 1, highs);
            // звуки самой волны слышит ухо, каким оно было до неё (слух — после, hurtEars)
            wave(direct, hit);
            if (echoes != null) echoes.expect(arrivalTick, direct);
            if (v >= AUDIBLE) {
                OwnSources.Anchor start = OwnSources.anchor(this, direct, a::ticks, arrivalTick);
                // сам скачок с положительной фазой модели — на любой дальности: величину удара даёт закон, а не замедленная
                // запись. Холм и зона тени срезают верха и у скачка; воздух — нет: крутизну скачка держит сама волна
                float front = path == null || !IncomingConfig.CLIENT.soundMuffling.get() ? 1 : Outdoor.highs(path);
                riseMs = 1000 * BlastPulse.riseSeconds(pa);
                pulse = OwnSources.play(this, start, 0, Math.atan2(b.z - ear.z, b.x - ear.x), v,
                        BlastPulse.render(pa, phaseTicks / 20, front), BlastPulse.RATE, true, hit);
                if (psi >= 1) {
                    // вблизи — удар фронта записями: лязг и грохот в упор с трёх сторон (несколько источников вместе —
                    // одному OpenAL не даёт громче единицы)
                    Vec3 side = new Vec3(b.z - ear.z, 0, ear.x - b.x).normalize().scale(40);
                    wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b, v, CRACK_TONES[0]), hit);
                    wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.add(side), v, CRACK_TONES[1]), hit);
                    wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.subtract(side), v, CRACK_TONES[2]), hit);
                } else if (!pulse) {
                    // своих источников нет (заняты, OpenAL не готов) — запись удара
                    wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK_FAR.get(), b, v, 1, boom), hit);
                }
                ClientSounds.start(direct);
            }
            if (echoes != null) echoes.arrive(ear, v, BOOM_TONE);
            if (windPeak / WIND_FULL >= AUDIBLE && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0) {
                wind = new AmbientLoop(ModSounds.NUKE_WIND.get(), SoundSource.MASTER, 1, d.seed(), 1);
                wind(ear, 0);
                mc.getSoundManager().play(wind);
            }
        }

        /**
         * Фронт у тела игрока: слух ({@link #hurtEars}) и наклон ({@link #sway}) — по месту игрока, его давлению, пути к
         * нему и его фазе, а не по камере.
         */
        private void hit(LocalPlayer p, Vec3 body) {
            bodyPsi = d.psi(body);
            sway(p, body, Math.max(1, 20 * d.scale() * d.positivePhaseSeconds(body)));
            if (IncomingConfig.CLIENT.nukeTinnitus.get()) hurtEars(1000 * d.overpressureKpa(body), d.positivePhaseSeconds(body), path(body, body.distanceTo(d.burst())));
        }

        /**
         * Слух: энергия слышимого у уха игрока ({@link HearingModel#exposure(double, double)}: волна с положительной фазой
         * модели {@code tPlus}, с; у фазы в доли секунды и больше — энергия скачка) — за вычетом того, что съел путь: холм и зона
         * тени гасят давление и сильнее верха, а A-энергия фронта — на 1–3 кГц, поэтому на потерю низов и долю верхов
         * ({@link Outdoor#pressureHighs}: без земли — у волны отражение от неё уже в давлении). Перепонки — по давлению с
         * потерей пути. В доме — как снаружи: окна давно выбиты (0,34 psi), волна входит в дом.
         */
        private void hurtEars(double pa, double tPlus, Outdoor.@Nullable Path path) {
            double lost = path == null ? 0 : Outdoor.pressureLoss(path), highs = path == null ? 1 : Outdoor.pressureHighs(path);
            Deafness.blast(HearingModel.exposure(pa, tPlus) - lost + 20 * Math.log10(Math.max(1e-3, highs)), pa / 1000 * Math.pow(10, -lost / 20), d.seed());
        }

        /**
         * Фронт бьёт стоящего, ветер за ним толкает: тело отклоняется по ветру и выпрямляется ({@link BodySway}), камера —
         * с ним ({@link CameraShake#sway}); удар — с прихода фронта к телу, а не с тика, в котором его заметили. Давление —
         * фронта у тела, напор — скоростной напор за фронтом вдоль земли у тела
         * ({@link Detonation#dynamicPressureKpa}: под воздушным подрывом — только его доля вдоль земли), {@code phase} —
         * положительная фаза у тела, тики. За стеной, домом или холмом в сторону взрыва (лучи {@link SoundFilters#openRays})
         * — в её ветровой тени: удар и ветер — по доле открытых лучей (оценка: фронт огибает преграду, но толкает уже не
         * вдоль земли). Кого ветер несёт целиком (верхом, вплавь, в полёте), того
         * не качает — бросок считает сервер; в прыжке наклона нет, пока игрок не встанет ({@link CameraShake#standing}).
         */
        private void sway(LocalPlayer p, Vec3 body, double phase) {
            if (CameraShake.carried(p)) return;
            Vec3 b = d.burst();
            swayOpen = SoundFilters.openRays(body, Math.atan2(b.z - body.z, b.x - body.x));
            // фронт прошёл тело между прошлым тиком и этим: удар — с его прихода
            double late = a.ticks(0) - d.arrivalTicks(body.distanceTo(b));
            double[] tilt = CameraShake.sway(1000 * d.overpressureKpa(body), 1000 * d.dynamicPressureKpa(body), swayOpen, phase / 20,
                    d.positivePhaseSeconds(body), late, body.subtract(b), p.getYRot(), false).body();
            swayPeak = Math.toDegrees(BodySway.peak(tilt, 0));
            swayRate = tilt.length < 2 ? 0 : Math.toDegrees(tilt[1]) * BodySway.RATE;
        }

        /**
         * Ветер за фронтом у уха. Воздух за слабой волной идёт за давлением (u = p/ρc): порыв со фронтом, спад за
         * положительную фазу и слабый обратный ветер в разрежении. Шум ветра у уха — по скоростному напору (давление
         * шума ∝ ρu², Strasberg 1988, JASA 83), в игре — половина его децибел, громкость ∝ u. Ветер — у самого уха:
         * без затухания по дороге.
         */
        private void wind(Vec3 ear, long ticks) {
            if (wind == null) return;
            if (ticks > WIND_PHASES * phaseTicks) {
                wind.end();
                wind = null;
                return;
            }
            double u = windPeak * Math.abs(BlastPulse.friedlander(ticks, phaseTicks));
            wind.set(ear, d.burst(), (float) Math.min(1, u / WIND_FULL), 0);
        }

        /**
         * Путь звука от подрыва до уха ({@link Outdoor}): кромка рельефа на прямой от источника, источник — точка
         * подрыва, у наземного — над землёй на 0,4 радиуса шара (как у обычных взрывов в {@code FarBlasts}); земля под
         * эпицентром — вода или грунт поверхности, если клиент знает чанк.
         */
        @Nullable
        private Outdoor.Path path(Vec3 ear, double dist) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) return null;
            Vec3 b = d.burst();
            double hs = Math.max(b.y - d.groundY(), 0.4 * d.fireballRadius());
            double z = Sightline.trace(FarTerrain.of(level), b.x, d.groundY() + hs, b.z, ear.x, ear.y, ear.z).pathDifference();
            int x = Mth.floor(b.x), zz = Mth.floor(b.z);
            double land = 0.5;
            LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, zz >> 4, false);
            if (chunk != null) {
                int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x & 15, zz & 15);
                GroundMaterial g = GroundMaterial.of(chunk.getBlockState(new BlockPos(x, top, zz)));
                if (g != null) land = g.porosity;
            }
            return BlastSounds.path(dist, z, hs, (1 - a.water.under()) * land);
        }
    }

    /** Шум чёрного дождя: пока игрок под ним, гаснет плавно. */
    private static final class RainLoop extends AbstractTickableSoundInstance {
        RainLoop() {
            super(ModSounds.NUKE_RAIN.get(), SoundSource.WEATHER, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0.01f;
            relative = true;
            attenuation = Attenuation.NONE;
        }

        @Override
        public void tick() {
            volume = Math.max(0, NukeSky.blackRain() * 0.8f);
            if (!NukeSky.inBlackRain() && NukeSky.blackRain() <= 0) stop();
        }

        void end() {
            stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
