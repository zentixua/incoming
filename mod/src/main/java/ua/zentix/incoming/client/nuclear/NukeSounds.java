package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.multiplayer.ClientLevel;
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
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.registry.ModSounds;
import ua.zentix.incoming.warhead.GroundMaterial;


/**
 * Звук ядерного удара (DESIGN-nuke §5). Свет приходит сразу, звук — с фронтом ударной волны: каждый тик клиент
 * сравнивает радиус фронта ({@link Detonation#frontRadius}) со своим расстоянием до эпицентра, и когда фронт
 * дошёл — удар, рёв, ветер, звон стёкол, раскаты. Громкость и тембр — от давления у слушателя, а не от
 * ванильного затухания; направление честное (точка в 3 блоках от уха в сторону эпицентра).
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
        return sb.isEmpty() ? "" : sb + ua.zentix.incoming.client.sound.OwnSources.describe() + " " + FireWatch.describe();
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
        private boolean arrived, rumbled, shook;
        private long arrivalTick;
        private double psi;
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
        /** Эхо от рельефа — только вживую. */
        @Nullable
        final NukeEchoes echoes;
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

        void tick() {
            after.tick();
            if (!live) return;
            Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            long now = (long) a.ticks(0);
            double since = now;
            if (!shook) seismic(ear, since);
            if (!arrived) {
                double dist = ear.distanceTo(d.burst());
                if (d.frontRadius(since) < dist) {
                    approach(ear, d.arrivalTicks(dist) - since);
                    if (echoes != null) echoes.approach(ear);
                    return;
                }
                arrived = true;
                arrivalTick = now;
                psi = d.psi(ear);
                arrive(ear);
                return;
            }
            if (echoes != null) echoes.tick();
            // стена пыли проходит мимо игрока секунды три
            if (now - arrivalTick < 60 && psi >= 0.5) NukeDust.gust(a, ear, psi, (int) (now - arrivalTick));
            wind(ear, now - arrivalTick);
        }

        /** Строка {@code SCENARIO}: что дошло прямым ударом; пусто — фронт ещё не дошёл. */
        String describe() {
            if (!arrived) return "";
            return String.format(java.util.Locale.ROOT, "direct %.2f psi vol %.3f t+ %.2fs rise %.3fms %s wind %.1fm/s; ", psi, heard,
                    phaseTicks / 20, riseMs, pulse ? "pulse" : "file", windPeak);
        }

        /**
         * Наземный взрыв: толчок грунта дошёл раньше волны в воздухе ({@link CameraShake#seismic}) — с ним низкий гул
         * (у воздушного — гул с первой дрожью перед стеной пыли, {@link #approach}).
         */
        private void seismic(Vec3 ear, double since) {
            if (!d.surface()) {
                shook = true;
                return;
            }
            double metres = d.metres(ear.distanceTo(d.burst()));
            if (since < CameraShake.seismicTicks(metres, d.scale())) return;
            shook = true;
            double amp = CameraShake.seismicAmplitude(metres, d.yieldKt());
            if (amp <= 0) return;
            CameraShake.seismic(amp);
            if (!rumbled) {
                rumbled = true;
                ClientSounds.atEar(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), (float) Mth.clamp(amp / 4, 0.4, 1), 0.7f);
            }
        }

        /**
         * Стена пыли подходит: земля дрожит всё сильнее ({@link CameraShake#approach}), с первой дрожью — низкий гул
         * толчка грунта (он бежит по земле быстрее волны в воздухе).
         */
        private void approach(Vec3 ear, double eta) {
            double p = d.psi(ear);
            CameraShake.approach(p, eta);
            if (!rumbled && p >= 1 && eta < CameraShake.approachTicks(p)) {
                rumbled = true;
                ClientSounds.atEar(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), (float) Mth.clamp(p / 5, 0.4, 1), 0.7f);
            }
        }

        /**
         * Фронт у слушателя. Громкость — по пиковому давлению у уха и пути звука, как у обычных взрывов
         * ({@link Outdoor#pressureVolume}: холм между, зона тени днём, дождь), всё по «Общей громкости». Вдали (меньше
         * 1 psi) удар фронта — дорожка из модели ({@link BlastPulse}: один скачок, его крутизна по давлению,
         * положительная фаза по Кинни–Грэхему) своим источником с начала прямого удара, и запись далёкого огромного
         * взрыва ({@code nuke.boom_far}) — раскат; эхо от рельефа повторяет её ({@link NukeEchoes}). Вблизи — оглушающий
         * удар (несколько источников вместе — одному OpenAL не даёт громче единицы). Тряска, ветер за фронтом, стёкла,
         * оглушение (пыль у глаза — по голове стены, {@link NukeDust#veil}).
         */
        private void arrive(Vec3 ear) {
            Minecraft mc = Minecraft.getInstance();
            Vec3 b = d.burst();
            double dist = ear.distanceTo(b), pa = 1000 * d.overpressureKpa(ear);
            phaseTicks = Math.max(1, 20 * d.scale() * BlastModel.positivePhaseSeconds(Math.max(1, d.metres(dist)), d.yieldKt()));
            windPeak = BlastModel.windSpeed(pa / 1000);
            Outdoor.Path path = path(ear, dist);
            float v = path == null ? 1 : Outdoor.pressureVolume(pa, FULL_PA, path);
            float highs = path == null ? 1 : BlastSounds.highs(path);
            heard = v;
            long boom = echoes == null ? d.seed() : echoes.seed;
            float tone = psi >= 1 ? 0.75f : 1;
            // эхо и удар фронта ждут настоящего начала прямого удара: ожидание — до play (с готовым буфером звук стартует сразу)
            SoundInstance direct = ClientSounds.loudShot(ModSounds.NUKE_BOOM_FAR.get(), b, v, tone, boom, 1, highs);
            if (echoes != null) echoes.expect(arrivalTick, direct);
            if (psi >= 1) {
                Vec3 side = new Vec3(b.z - ear.z, 0, ear.x - b.x).normalize().scale(40);
                ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b, v, 1);
                ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.add(side), v, 0.92f);
                ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.subtract(side), v, 0.85f);
                ClientSounds.start(direct);
                ClientSounds.atEarLoud(ModSounds.BLAST_NEAR.get(), b, v, 0.55f);
            } else if (v >= AUDIBLE) {
                OwnSources.Anchor start = OwnSources.anchor(this, direct, a::ticks, arrivalTick);
                // холм и зона тени срезают верха и у скачка; воздух — нет: крутизну скачка держит сама волна
                float front = path == null || !IncomingConfig.CLIENT.soundMuffling.get() ? 1 : Outdoor.highs(path);
                riseMs = 1000 * BlastPulse.riseSeconds(pa);
                pulse = OwnSources.play(this, start, 0, Math.atan2(b.z - ear.z, b.x - ear.x), v,
                        BlastPulse.render(pa, phaseTicks / 20, front), BlastPulse.RATE);
                // своих источников нет (заняты, OpenAL не готов) — запись удара
                if (!pulse) ClientSounds.atEarLoud(ModSounds.NUKE_CRACK_FAR.get(), b, v, 1, boom);
                ClientSounds.start(direct);
            }
            if (echoes != null) echoes.arrive(ear, v, tone);
            CameraShake.nuke(psi, ear.subtract(b));
            CameraShake.quake((int) Mth.clamp(20 + psi * 8, 20, 120));
            if (windPeak / WIND_FULL >= AUDIBLE && mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0) {
                wind = new AmbientLoop(ModSounds.NUKE_WIND.get(), SoundSource.MASTER, 1, d.seed(), 1);
                wind(ear, 0);
                mc.getSoundManager().play(wind);
            }
            if (psi >= GLASS_PSI) ClientSounds.atEar(ModSounds.NUKE_GLASS.get(), ear.add(0, 2, 0), (float) Mth.clamp(psi / 3, 0.3, 0.9), 1);
            if (psi >= 2 && IncomingConfig.CLIENT.nukeTinnitus.get()) {
                Deafness.start((int) (20 * Mth.clamp(8 + psi, 10, 20)), psi);
            }
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
