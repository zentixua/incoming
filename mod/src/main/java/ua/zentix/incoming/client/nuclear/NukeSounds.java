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
 * Nuclear strike sound (DESIGN-nuke §5). Light arrives immediately, sound with the shock front: each client tick
 * compares front radius ({@link Detonation#frontRadius}) with its epicenter distance; when front
 * arrives, hit, post-front wind, glass, then terrain echo. Volume/timbre follow listener pressure and
 * sound path rather than vanilla attenuation; true direction (point 3 blocks from ear toward epicenter).
 */
public final class NukeSounds {
    /** Warhead entry visible for last 3 s, silent: at 7 km/s its sound arrives after the explosion. */
    static final int REENTRY_TICKS = 60;
    /** Sirens on different sides of the listener's alarm-start position, like city streets, at native pitch. */
    private static final Vec3[] SIREN_AT = {new Vec3(140, 25, -90), new Vec3(-120, 25, 130)};
    private static final float[] SIREN_TONE = {1.0f, 0.98f};
    /** Engine refused a siren channel (silent, channels occupied): retry after this many ticks. */
    private static final int SIREN_RETRY = 20;

    @Nullable
    private static RainLoop rainLoop;
    /** Sirens while listener has an alarm; {@code null} means this one is silent (disabled ones fade themselves). */
    private static final SirenSound[] SIRENS = new SirenSound[SIREN_AT.length];
    /** Listener position/world when alarm began; {@code null} means no alarm. */
    @Nullable
    private static Vec3 sirenOrigin;
    @Nullable
    private static ClientLevel sirenLevel;
    private static int sirenWait;

    private NukeSounds() {}

    /** ICBM launch: engine roar from the pad for the launcher player. Civil-defense siren: {@link #sirens}. */
    static void warning(S2C.NukeWarning w) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = mc.level.getGameTime();
        if (w.mine() && now - w.launchTime() < 40) {
            // power from first-stage solid-rocket law in ICBM sound spec, recording is pad launch
            Noise.Rocket motor = WeaponSpec.NUKE.airframe().noise().motor();
            Sources.Source s = Sources.NUKE_LAUNCH;
            if (motor != null) {
                Sources.play(ModSounds.NUKE_LAUNCH.get(), w.launchPos(), s.power(motor.power(FlightPhase.BOOST, 0) + s.spectrum().aWeighting()), 1);
            }
        }
    }

    /**
     * Black-rain loop after {@link NukeSky#tick}, which determines camera-local rain; sirens. Client tick inspects fire near
     * detonations in local chunks ({@link FireWatch}).
     */
    static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (NukeSky.inBlackRain() && (rainLoop == null || rainLoop.isStopped())) {
            rainLoop = new RainLoop();
            mc.getSoundManager().play(rainLoop);
        }
        sirens();
    }

    /** {@code SCENARIO} line: each detonation's echo/burning city; empty if no detonations. */
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
     * Civil-defense sirens wail while any ICBM for which listener was warned is flying: until detonation
     * (detonation packet removes warning), cancel (server resends warnings), or impact-location
     * wait deadline, then fade. Restart a siren not held by engine (sound restart, dimension change, Ambient slider
     * was zero) at its position; do not start with slider zero: the engine does not remove the sound it rejected.
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

    /** One detonation's sounds at this listener. */
    static final class Schedule {
        /**
         * Ear pressure for full-volume direct hit, Pa: 1 psi, like conventional explosions
         * ({@link ua.zentix.incoming.client.sound.BlastMix#FULL_PA}). Speakers cannot play louder; volume
         * does not increase beyond this (except near-front hit, already overloaded).
         */
        static final double FULL_PA = 1000 * BlastModel.kpa(1);
        /** Wind for full-volume hurricane loop, m/s: recorded hurricane (Beaufort 12, starting at 33 m/s). */
        static final double WIND_FULL = 33;
        /** Wind loop ends after this many positive phases: reverse wind in rarefaction has died. */
        static final double WIND_PHASES = 5;
        /** Do not start quieter sounds. */
        private static final float AUDIBLE = 0.005f;
        /**
         * Front-hit recording pitch: native speed, shift no greater than ±25 % ({@code SHIFT_MAX} in sound build,
         * {@code NukeTonesTest}); three nearby hits use different pitches so one recording's copies do not form a comb.
         */
        static final float BOOM_TONE = 1;
        static final float[] CRACK_TONES = {1, 0.92f, 0.85f};

        private final ClientNuclear.Active a;
        private final Detonation d;
        private final boolean live;
        /** Where front met listener/player body. */
        private final FrontMeeting meeting = new FrontMeeting();
        private long arrivalTick;
        /** Front pressure at listener/body, psi. */
        private double psi, bodyPsi = -1;
        /** Positive phase at listener, game ticks. */
        private double phaseTicks;
        /** Maximum post-front wind at listener, m/s. */
        private double windPeak;
        @Nullable
        private AmbientLoop wind;
        /** Direct hit received (scenario line): volume, front rise, hit from own source or recording. */
        private float heard;
        private double riseMs;
        private boolean pulse;
        /** Maximum wave-wind body lean, °, and unobstructed ray share toward explosion (scenario line). */
        private double swayPeak, swayOpen = -1;
        /** Immediate post-front lean rate, front jolt, °/s (scenario line). */
        private double swayRate;
        /** Wave sounds at listener: removed if listener is ahead of front again ({@link #again}). */
        private final List<SoundInstance> shots = new ArrayList<>();
        /** Wave-removed glass/debris near listener, to ear ({@link #clatter}); played events for scenario line. */
        private final List<Ruins> ruinPackets = new ArrayList<>();
        private final Clatter clatter = new Clatter(new ua.zentix.incoming.sound.Spectrum[]{Sources.RUIN_GLASS.spectrum(), Sources.RUIN_CLATTER.spectrum()});
        private final List<Clatter.Play> plays = new ArrayList<>();
        private final int[] heardClatter = new int[Clatter.KINDS];
        private final double[] loudestClatter = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        /** Terrain echo, live only. */
        @Nullable
        NukeEchoes echoes;
        /** Burning city at any detonation age. */
        final NukeAfter after;

        Schedule(ClientNuclear.Active a) {
            this.a = a;
            this.d = a.d;
            this.live = a.live;
            this.echoes = live ? new NukeEchoes(a) : null;
            this.after = new NukeAfter(a);
        }

        /** Detonation removed (dimension change, respawn, replay seek, expiry): remove hit, echo, wind and burning-city loops. */
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
         * Server removed nearby chunk blocks: ruin positions for late collapses; glass/debris sound when front
         * reaches their position ({@link #clatter}).
         */
        void debris(S2C.NukeDebris p) {
            after.ruins(p.pieces());
            if (live && !p.pieces().isEmpty()) ruinPackets.add(new Ruins(p, a.ticks(0)));
        }

        /** Debris packet received at tick {@code at} (detonation clock): sound calculated when chunk ruins reach client. */
        private record Ruins(S2C.NukeDebris packet, double at) {}

        /**
         * Ticks after debris packet to wait for chunk ruins on client: server sends packet on replacement tick, ruins themselves
         * (section changes) go to player next server tick; until then client world at those positions is unchanged, so it cannot
         * tell where dislodged matter will land.
         */
        static final int RUINS_SETTLE = 2;
        /** Maximum blocks searched below dislodged position for landing (beyond: ground under epicenter). */
        static final int FALL_SCAN = 256;

        /**
         * Chunk destruction sound ({@link Clatter}): each removed block is its material mass (block = meter,
         * {@link Shielding#density}: glass is a 25 kg window), thrown by dynamic pressure at {@link Detonation#throwSpeed} and
         * falling to first block beneath its position in the post-ruin world ({@link #landing}), giving {@link Clatter#watts}. Packet
         * positions sample all removals ({@link S2C.NukeDebris#weight}); windows ({@link Shielding#window}) and others are separate,
         * each one event at the energy-weighted center from front arrival there. Ruins more than one second after front
         * ({@link NukeDust#DEBRIS_LATE}) have no sound, just as no pieces.
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
                // power is energy over fall time; position/time weighted by power
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
         * Landing for matter dislodged at {@code pos}: top of first block below in client post-ruin world (window lands on its
         * floor's sill, collapsed masonry on rubble/ground). No chunk or empty below for
         * {@link #FALL_SCAN} blocks: ground under epicenter.
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

        /** Glass/debris whose sound reached ear enters the game ({@link Clatter}): kind recording, volume from ear level. */
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
            clatter.tick(a.ticks(0), ear.x, ear.y, ear.z, ua.zentix.incoming.util.Air.weather(level), plays);
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
         * Wave sound at listener (camera), ear hit/wind at player body ({@link FrontMeeting}): in a projectile
         * camera orbiting the explosion, camera ear used to receive a close hit while player was kilometers away.
         */
        void tick() {
            after.tick();
            if (!live) return;
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            Vec3 ear = mc.gameRenderer.getMainCamera().getPosition(), b = d.burst();
            clatterTick(ear);
            // spectator body is camera: client player viewing through another entity stays where viewing began
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
            // dust wall passes player for about three seconds
            if (now - arrivalTick < 60 && psi >= 0.5) NukeDust.gust(a, ear, psi, (int) (now - arrivalTick));
            wind(ear, now - arrivalTick);
        }

        /** {@code SCENARIO} line: direct hit received; empty before front arrival. */
        String describe() {
            String ruins = String.format(java.util.Locale.ROOT, "clatter glass %d (%.0f dBA) debris %d (%.0f dBA) pending %d; ",
                    heardClatter[Clatter.GLASS], loudestClatter[Clatter.GLASS], heardClatter[Clatter.DEBRIS], loudestClatter[Clatter.DEBRIS], clatter.pending());
            if (!meeting.heard()) return ruins;
            return String.format(java.util.Locale.ROOT, "direct %.2f psi vol %.3f t+ %.2fs rise %.3fms %s wind %.1fm/s body %.2f psi sway %.2fdeg jolt %.1fdeg/s open %.2f; ",
                    psi, heard, phaseTicks / 20, riseMs, pulse ? "pulse" : "file", windPeak, bodyPsi, swayPeak, swayRate, swayOpen) + ruins;
        }

        /**
         * Listener ahead of front again (camera returned to player, teleport): remove old-position wave sounds, wind/echo;
         * listener will meet front again.
         */
        private void again() {
            SoundManager sounds = Minecraft.getInstance().getSoundManager();
            for (SoundInstance s : shots) sounds.stop(s);
            shots.clear();
            // front has not been here: eye-local dust waits for new meeting
            psi = 0;
            OwnSources.stop(this);
            if (wind != null) wind.end();
            wind = null;
            if (echoes != null) {
                echoes.stop();
                echoes = new NukeEchoes(a);
            }
        }

        /** Wave sound at listener; if player ears are hit this tick too ({@code hit}), heard as before the wave. */
        private SoundInstance wave(SoundInstance sound, boolean hit) {
            if (hit) Deafness.before(sound);
            shots.add(sound);
            return sound;
        }

        /**
         * Front at listener. Volume follows ear peak pressure and sound path, like conventional explosions
         * ({@link Outdoor#pressureVolume}: intervening hill, daytime shadow, rain), all under Master volume. Far away (below
         * 1 psi), front hit is a model track ({@link BlastPulse}: one shock, pressure-dependent rise,
         * model positive phase, {@link Detonation#positivePhaseSeconds}) on own source from direct-hit onset, while a distant huge
         * explosion recording ({@code nuke.boom_far}) supplies roll; terrain echo repeats it ({@link NukeEchoes}). Nearby: deafening
         * hit (several sources together; OpenAL limits one to unity). Post-front wind noise, glass (eye-local
         * dust follows wall head, {@link NukeDust#veil}). Hearing/lean belong to body ({@link #hit}); {@code hit} means front reaches
         * body this tick too: wave sounds heard as before it.
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
            // echo/front hit wait for actual direct-hit onset: anchor before play (ready buffer starts sound immediately)
            SoundInstance direct = ClientSounds.loudShot(ModSounds.NUKE_BOOM_FAR.get(), b, v, BOOM_TONE, boom, 1, highs);
            // wave sounds heard with pre-wave hearing (hearing damage afterward, hurtEars)
            wave(direct, hit);
            if (echoes != null) echoes.expect(arrivalTick, direct);
            if (v >= AUDIBLE) {
                OwnSources.Anchor start = OwnSources.anchor(this, direct, a::ticks, arrivalTick);
                // shock itself with model positive phase at any distance: law supplies hit magnitude, not a slowed
                // recording. Hills/shadow cut shock highs too; air does not, since wave itself sustains shock rise
                float front = path == null || !IncomingConfig.CLIENT.soundMuffling.get() ? 1 : Outdoor.highs(path);
                riseMs = 1000 * BlastPulse.riseSeconds(pa);
                pulse = OwnSources.play(this, start, 0, Math.atan2(b.z - ear.z, b.x - ear.x), v,
                        BlastPulse.render(pa, phaseTicks / 20, front), BlastPulse.RATE, true, hit);
                if (psi >= 1) {
                    // nearby front hit from recordings: point-blank clang/crash from three sides (several sources together;
                    // OpenAL limits one to unity)
                    Vec3 side = new Vec3(b.z - ear.z, 0, ear.x - b.x).normalize().scale(40);
                    wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b, v, CRACK_TONES[0]), hit);
                    wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.add(side), v, CRACK_TONES[1]), hit);
                    wave(ClientSounds.atEarLoud(ModSounds.NUKE_CRACK.get(), b.subtract(side), v, CRACK_TONES[2]), hit);
                } else if (!pulse) {
                    // no own sources (occupied, OpenAL not ready): recorded hit
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
         * Front at player body: hearing ({@link #hurtEars})/lean ({@link #sway}) use player position, pressure, path
         * and phase rather than camera.
         */
        private void hit(LocalPlayer p, Vec3 body) {
            bodyPsi = d.psi(body);
            sway(p, body, Math.max(1, 20 * d.scale() * d.positivePhaseSeconds(body)));
            if (IncomingConfig.CLIENT.nukeTinnitus.get()) hurtEars(1000 * d.overpressureKpa(body), d.positivePhaseSeconds(body), path(body, body.distanceTo(d.burst())));
        }

        /**
         * Hearing: audible energy at player ear ({@link HearingModel#exposure(double, double)}: wave with model positive phase
         * {@code tPlus}, s; phase fractions of a second or longer uses shock energy), minus path loss: hill/shadow
         * attenuate pressure and highs more strongly; front A-energy is at 1–3 kHz, so use low loss plus high share
         * ({@link Outdoor#pressureHighs}: excluding ground, whose reflection is already in wave pressure). Eardrums use pressure with
         * path loss. Indoors same as outdoors: windows broke long ago (0.34 psi), wave enters house.
         */
        private void hurtEars(double pa, double tPlus, Outdoor.@Nullable Path path) {
            double lost = path == null ? 0 : Outdoor.pressureLoss(path), highs = path == null ? 1 : Outdoor.pressureHighs(path);
            Deafness.blast(HearingModel.exposure(pa, tPlus) - lost + 20 * Math.log10(Math.max(1e-3, highs)), pa / 1000 * Math.pow(10, -lost / 20), d.seed());
        }

        /**
         * Front hits standing player, post-front wind pushes: body leans downwind then straightens ({@link BodySway}), camera follows
         * ({@link CameraShake#sway}); jolt begins at body front arrival, not the tick detecting it. Pressure is
         * front pressure at body; dynamic pressure is post-front along-ground dynamic pressure at body
         * ({@link Detonation#dynamicPressureKpa}: only along-ground share beneath airburst); {@code phase} is
         * body positive phase, ticks. Behind a wall/house/hill toward explosion (rays {@link SoundFilters#openRays}),
         * in its wind shadow: jolt/wind scale by open-ray share (est.: front bends around obstruction but no longer pushes
         * along ground). Bodies carried entirely by wind (riding, swimming, flying)
         * do not sway; server computes throw. No lean while jumping until standing ({@link CameraShake#standing}).
         */
        private void sway(LocalPlayer p, Vec3 body, double phase) {
            if (CameraShake.carried(p)) return;
            Vec3 b = d.burst();
            swayOpen = SoundFilters.openRays(body, Math.atan2(b.z - body.z, b.x - body.x));
            // front crossed body between previous/current tick: jolt starts at arrival
            double late = a.ticks(0) - d.arrivalTicks(body.distanceTo(b));
            double[] tilt = CameraShake.sway(1000 * d.overpressureKpa(body), 1000 * d.dynamicPressureKpa(body), swayOpen, phase / 20,
                    d.positivePhaseSeconds(body), late, body.subtract(b), p.getYRot(), false).body();
            swayPeak = Math.toDegrees(BodySway.peak(tilt, 0));
            swayRate = tilt.length < 2 ? 0 : Math.toDegrees(tilt[1]) * BodySway.RATE;
        }

        /**
         * Post-front wind at ear. Air behind a weak wave follows pressure (u = p/ρc): gust with front, decay over
         * positive phase and weak reverse wind in rarefaction. Ear wind noise follows dynamic pressure (noise
         * pressure ∝ ρu², Strasberg 1988, JASA 83), in game half its decibels, volume ∝ u. Wind is at the ear:
         * no path attenuation.
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
         * Sound path from detonation to ear ({@link Outdoor}): terrain edge along source line; source is detonation
         * position, for surface bursts 0.4 sphere radii above ground (like conventional explosions in {@code FarBlasts}); ground under
         * epicenter is water or surface soil if client knows chunk.
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

    /** Black-rain noise: while player is beneath it, fades smoothly. */
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
