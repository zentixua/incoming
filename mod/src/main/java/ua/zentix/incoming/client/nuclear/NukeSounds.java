package ua.zentix.incoming.client.nuclear;

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
import ua.zentix.incoming.client.sound.ClientSounds;
import ua.zentix.incoming.client.sound.Outdoor;
import ua.zentix.incoming.client.sound.OwnSources;
import ua.zentix.incoming.client.sound.SirenSound;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.BodySway;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.registry.ModSounds;
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
        if (w.mine() && now - w.launchTime() < 40) ClientSounds.at(ModSounds.NUKE_LAUNCH.get(), w.launchPos(), 24, 1);
    }

    /**
     * Петля чёрного дождя — после {@link NukeSky#tick}, который решает, идёт ли он у камеры; сирены; пока где-то горит
     * от подрыва — огонь в своих чанках ({@link FireWatch}).
     */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (NukeSky.inBlackRain() && (rainLoop == null || rainLoop.isStopped())) {
            rainLoop = new RainLoop();
            mc.getSoundManager().play(rainLoop);
        }
        sirens();
        boolean burning = false;
        for (ClientNuclear.Active a : ClientNuclear.detonations()) burning |= a.sounds.after.burning();
        if (burning && mc.level != null) {
            Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
            FireWatch.tick(mc.level, (int) Math.floor(eye.x) >> 4, (int) Math.floor(eye.z) >> 4, mc.options.getEffectiveRenderDistance());
        } else {
            FireWatch.idle();
        }
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
         * С какого давления у уха прямой удар — в полную громкость, Па: 1 psi (ближние ракурсы обычных взрывов полны
         * при 1,1–2,2 psi у края своей полной громкости,
         * {@link ua.zentix.incoming.client.sound.BlastMix.Profile#flat}). Громче динамики не сыграют — дальше
         * громкость не растёт (кроме удара фронта вблизи, он и так перегружен).
         */
        static final double FULL_PA = 1000 * BlastModel.kpa(1);
        /** Ветер, при котором петля урагана звучит в полную громкость, м/с: её запись — ураган (12 баллов Бофорта, от 33 м/с). */
        static final double WIND_FULL = 33;
        /** Петля ветра уходит, когда прошло столько положительных фаз: обратный ветер в разрежении стих. */
        static final double WIND_PHASES = 5;
        /** Тише — звук не запускать. */
        private static final float AUDIBLE = 0.005f;
        /** Окна лопаются от 0,34 psi падающей волны (у стекла отражённое — вдвое): край руин, {@link Detonation#RUIN_EDGE_KPA}. */
        static final double GLASS_PSI = BlastModel.psi(Detonation.RUIN_EDGE_KPA);

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
            if (echoes != null) echoes.stop();
            OwnSources.stop(this);
            if (wind != null) wind.end();
            wind = null;
            after.end();
        }

        /** Сервер снёс блоки в чанке рядом: места руин — для поздних обрушений. */
        void debris(java.util.List<S2C.NukeDebris.Piece> pieces) {
            after.ruins(pieces);
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
            if (!meeting.heard()) return "";
            return String.format(java.util.Locale.ROOT, "direct %.2f psi vol %.3f t+ %.2fs rise %.3fms %s wind %.1fm/s body %.2f psi sway %.2fdeg jolt %.1fdeg/s open %.2f; ",
                    psi, heard, phaseTicks / 20, riseMs, pulse ? "pulse" : "file", windPeak, bodyPsi, swayPeak, swayRate, swayOpen);
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
            float tone = psi >= 1 ? 0.75f : 1;
            // эхо и удар фронта ждут настоящего начала прямого удара: ожидание — до play (с готовым буфером звук стартует сразу)
            SoundInstance direct = ClientSounds.loudShot(ModSounds.NUKE_BOOM_FAR.get(), b, v, tone, boom, 1, highs);
            // звуки самой волны слышит ухо, каким оно было до неё (слух — после, hurtEars)
            wave(direct, hit);
            if (echoes != null) echoes.expect(arrivalTick, direct);
            if (psi >= 1) {
                Vec3 side = new Vec3(b.z - ear.z, 0, ear.x - b.x).normalize().scale(40);
                wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b, v, 1), hit);
                wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.add(side), v, 0.92f), hit);
                wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.subtract(side), v, 0.85f), hit);
                ClientSounds.start(direct);
                wave(ClientSounds.atEarLoud(ModSounds.BLAST_NEAR.get(), b, v, 0.55f), hit);
            } else if (v >= AUDIBLE) {
                OwnSources.Anchor start = OwnSources.anchor(this, direct, a::ticks, arrivalTick);
                // холм и зона тени срезают верха и у скачка; воздух — нет: крутизну скачка держит сама волна
                float front = path == null || !IncomingConfig.CLIENT.soundMuffling.get() ? 1 : Outdoor.highs(path);
                riseMs = 1000 * BlastPulse.riseSeconds(pa);
                pulse = OwnSources.play(this, start, 0, Math.atan2(b.z - ear.z, b.x - ear.x), v,
                        BlastPulse.render(pa, phaseTicks / 20, front), BlastPulse.RATE, true, hit);
                // своих источников нет (заняты, OpenAL не готов) — запись удара
                if (!pulse) wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK_FAR.get(), b, v, 1, boom), hit);
                ClientSounds.start(direct);
            }
            if (echoes != null) echoes.arrive(ear, v, tone);
            if (windPeak / WIND_FULL >= AUDIBLE && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0) {
                wind = new AmbientLoop(ModSounds.NUKE_WIND.get(), SoundSource.MASTER, 1, d.seed(), 1);
                wind(ear, 0);
                mc.getSoundManager().play(wind);
            }
            if (psi >= GLASS_PSI) wave(ClientSounds.atEar(ModSounds.NUKE_GLASS.get(), ear.add(0, 2, 0), (float) Mth.clamp(psi / 3, 0.3, 0.9), 1), hit);
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
            double[] tilt = CameraShake.sway(1000 * d.overpressureKpa(body), 1000 * d.dynamicPressureKpa(body), swayOpen, phase / 20, late,
                    body.subtract(b), p.getYRot());
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
