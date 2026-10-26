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
 * What a blast sounds like at different distances — {@code client.fx.BlastEffects} draws the visuals; all sound is here.
 * Everything arrives with the front; we set volume ourselves instead of vanilla attenuation; charge comes from the
 * weapon spec ({@link BlastSource}).
 * <ul>
 * <li>closer than {@link Outdoor#NEAR} blocks — perspectives of one recording based on pressure and air at the ear,
 * pitch and duration from charge ({@link BlastMix}), with a low “chest hit”; the impact at the ear where near fades
 * is a model track on our own source ({@link BlastPulse}); terrain echoes are {@link BlastEchoes}; air and barriers
 * muffle highs ({@link SoundFilters});</li>
 * <li>farther — the far perspective via the {@link Outdoor} propagation model ({@link #far}); audible range comes
 * from charge ({@link HearingModel#audibleRangeM}).</li>
 * </ul>
 * The blast seed selects the recording variant ({@code S2C.Blast} packet): each blast sounds different, while its
 * perspectives are one recording. Projectile engines dip under a loud blast ({@link Ducking}). All blast sounds
 * (perspectives, chest hit, impact at the ear, echoes, bomb sounds) share one mix ({@link Mix}): the ceiling applies
 * to the sum, not each sound, and each blast's share is its real-life share. Blast one-shots hold no more engine
 * channels than their ceiling ({@link BlastVoices}); when slots run out, quiet ones play as rumble in four loops
 * around the ear ({@link Rumble}) with the same power.
 */
public final class BlastSounds {
    /**
     * Bomb recording loudness, LUFS (maximum momentary, 0.4 s window; measurement of files built by
     * {@code tools/build_sounds.py}, {@code bunker()}): played by pressure at the ear, like blast perspectives
     * ({@link BlastMix}). For an underground blast, use the louder of two variants: it never plays quieter than required.
     */
    static final double IMPACT_LUFS = -6.4, DEEP_LUFS = -7.7, VENT_LUFS = -11.2, CAVE_LUFS = -9.3, QUAKE_LUFS = -7.7;
    /** Salt for the “chest hit” seed: it has fewer variants, unrelated to the perspective variant. */
    private static final long SUB_SALT = 0x5DEECE66DL;
    /**
     * Our own sources for echoes: a set has {@link EchoMix#SECTORS} tracks; admit a new set only when slots exist
     * beyond those already started and beyond the remainder of {@link OwnSources#MAX} divided into sets
     * (reserved for impacts at the ear, {@link BlastPulse}).
     */
    static final int PULSE_RESERVE = OwnSources.MAX % EchoMix.SECTORS;
    /** Echoes still searching for slopes or mixing tracks. */
    private static final List<BlastEchoes> ECHOES = new ArrayList<>();
    /** Do not start a quieter layer (presentation: −46 dB relative to file, not a hearing threshold). */
    private static final float AUDIBLE = 0.005f;
    /**
     * Client ticks: blast clock by which echoes and impacts at the ear await the direct sound's start. Separate from
     * blast effect age: the effect ends, but echo voices that start later await this clock; if stopped, they would
     * never play and would hold their sources.
     */
    private static long ticks;
    /** Blast mix at the ear: game loudness is half the total level's decibels, ceiling on the sum ({@link Mix}). */
    private static final Mix MIX = new Mix();
    /** Rumble of blasts without voices ({@link Rumble}) and who holds voices ({@link BlastVoices}). */
    private static final Rumble RUMBLE = new Rumble();
    private static final BlastVoices VOICES = new BlastVoices(RUMBLE);
    /**
     * Rumble-loop loudness, LUFS (maximum momentary) — {@code RUMBLE_LUFS} in {@code build_sounds.py}: at the ceiling
     * so loop volume does not reach 1 before the rumble reaches the sum's ceiling.
     */
    static final double RUMBLE_LUFS = Loudness.CEILING_LUFS;
    /** Rumble loops by quarter; null — silent. */
    private static final RumbleLoop[] LOOPS = new RumbleLoop[Rumble.QUADRANTS];
    private static final int[] LOOP_RETRY = new int[Rumble.QUADRANTS];
    /** Static engine channels OpenAL can provide alongside our own sources ({@link BlastVoices#reach}). */
    private static int reach = BlastVoices.reach(BlastVoices.MONO, BlastVoices.STEREO);
    /** Blasts whose sound has not actually started: shift the mix to the actual start ({@link OwnSources.Anchor}). */
    private static final List<Late> LATE = new ArrayList<>();
    /** Seed for sounds without their own seed (bomb). */
    private static final RandomSource SEEDS = RandomSource.create();

    private record Late(Mix.Blast blast, OwnSources.Anchor anchor) {}
    private static final List<CraterFire> FIRES = new ArrayList<>();
    /** Crater-fire roar summed by quarters around the ear ({@link Quarters}), one loop per quarter, not per fire. */
    private static final Quarters FIRE = new Quarters();
    /** Fire barrier rays follow a tick budget, not every fire every tick ({@link FireRays}). */
    private static final FireRays FIRE_RAYS = new FireRays();
    private static final AmbientLoop[] FIRE_LOOPS = new AmbientLoop[Quarters.N];
    private static final int[] FIRE_RETRY = new int[Quarters.N], FIRE_QUIET = new int[Quarters.N];
    /** Last conventional blast at the ear and last echoes (client scenario line). */
    private static String last = "";
    @Nullable
    private static BlastEchoes lastEchoes;

    private BlastSounds() {}

    /**
     * Audible blast range under normal conditions (R0 in {@link Outdoor}), blocks: from charge, but no farther than
     * the server sends blasts ({@link IncomingConfig#FAR_RANGE_MAX}).
     */
    public static double range(BlastSource s) {
        return Math.min(IncomingConfig.FAR_RANGE_MAX, HearingModel.audibleRangeM(s.chargeKg()));
    }

    /**
     * Blast beyond {@link Outdoor#NEAR}: far perspective at the near model's edge volume ({@link BlastMix#v640}),
     * charge-based pitch; for a bomb, a dull thud from underground. Volume and highs use {@link Outdoor}: what lies
     * between (edge from terrain ray {@code z}), ground at blast and listener, day or night, rain.
     *
     * @param s      what exploded
     * @param hs     source height above ground, blocks
     * @param ground ground at the blast
     * @param d      distance to the ear, blocks
     * @param z      path difference over the terrain edge, blocks (0 — line of sight)
     * @param seed   blast seed
     * @return what arrived (for the log); {@code null} — no world
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
     * Blast-to-ear sound path for {@link Outdoor}: listener ground is the column top below the ear (the client
     * always has the chunk under the camera), current day or night, rain and thunder.
     *
     * @param d  distance to the ear, blocks
     * @param z  path difference over the terrain edge, blocks (0 — line of sight)
     * @param hs source height above ground, blocks
     * @param gs ground porosity at the source
     * @return {@code null} — no world
     */
    public static Outdoor.@Nullable Path path(double d, double z, double hs, double gs) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return null;
        Vec3 ear = mc.gameRenderer.getMainCamera().getPosition();
        int x = Mth.floor(ear.x), zz = Mth.floor(ear.z);
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, zz);
        GroundMaterial under = GroundMaterial.of(level.getBlockState(new BlockPos(x, top - 1, zz)));
        // shadow zone follows world air stability: daytime ground heats air, bending sound upward; nighttime inversion does not
        return new Outdoor.Path(d, z, hs, Math.max(1.5, ear.y - top), gs, under == null ? 0.5 : under.porosity,
                level.dimensionType().hasSkyLight() ? Propagation.refraction(ClientAir.now().stability()) : 0,
                level.getRainLevel(1), level.getThunderLevel(1));
    }

    /** Rumble recording's high fraction from the {@link Outdoor} path and air; sound path disabled in settings — 1. */
    public static float highs(Outdoor.Path path) {
        return SoundFilters.pathEffects() ? Outdoor.highs(path) * ClientSounds.farAir(path.d()) : 1;
    }

    /**
     * Terrain echoes of this blast ({@link BlastEchoes}: rays search slopes while the front travels), if our own
     * sources are available ({@link #PULSE_RESERVE}); otherwise null: an MLRS salvo has dozens of bursts, echoes play
     * for those with slots. Seed selects this blast's far-perspective variant; clock is the client's blast clock.
     */
    public static @Nullable BlastEchoes echoes(BlastSource s, Vec3 pos, long seed, OwnSources.Clock clock) {
        ECHOES.removeIf(BlastEchoes::settled);
        if (OwnSources.free() - EchoMix.SECTORS * ECHOES.size() < EchoMix.SECTORS + PULSE_RESERVE) return null;
        double kg = s.chargeKg();
        // reflection path uses the same front as direct sound ({@link FarBlasts#arrivalTicks}): faster than sound nearby
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
     * Conventional blast (ground or air) — front reached the ear now (blast clock: {@link #clock}): recording
     * perspectives and “chest hit” ({@link BlastMix}), model impact at the ear on our own source ({@link BlastPulse}),
     * terrain echoes ({@code echoes}, null — none) from the actual start of the loudest perspective. The wave's
     * sounds are heard by the ear as it was before the wave ({@link Deafness#before}); any wave-induced hearing loss comes after.
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
        // add layers to the mix before they play: the very first tick's volume already includes this blast
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
            // chest hit is its own recording (low end of other detonations and synthesis): power sum with perspectives
            SoundEvent sub = ModSounds.BLAST_SUB.get();
            Mix.Layer layer = new Mix.Layer(Envelopes.of(sub.getLocation()), Envelopes.energy(sub, seed ^ SUB_SALT), pitch, l.sub(), Mix.Kind.APART, 0);
            blast.add(layer);
            shots.add(ClientSounds.blastShot(sub, pos, l.sub(), pitch, seed ^ SUB_SALT, blast::factor, life(layer)));
        }
        // echoes and impact at the ear await the direct sound's actual start: set waiting before play; mix starts there too
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
            // impact at the ear is a model track on our own source: its own envelope in the mix, summed by power
            float[] x = new float[track.length];
            for (int i = 0; i < x.length; i++) x[i] = track[i] / 32768f;
            pulse = OwnSources.play(owner, start, 0, Math.atan2(pos.z - ear.z, pos.x - ear.x), l.pulse(), track, BlastPulse.RATE, true, true,
                    blast::factor);
            // no sources available — no impact in the mix either
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
     * One-shots for {@code blast} (its layers already in the mix) use engine voices if slots exist ({@link BlastVoices});
     * otherwise the blast plays in the rumble ({@link Rumble}); sounds refused by the engine also join rumble immediately.
     * {@code before} marks the wave's own sounds: heard by the ear as it was before the wave ({@link Deafness#before}).
     */
    private static void voice(Mix.Blast blast, List<ClientSounds.MixedShot> shots, Vec3 pos, float pitch, boolean before) {
        if (shots.isEmpty()) return;
        Vec3 ear = ear();
        ClientSounds.MixedShot loud = shots.get(0);
        for (ClientSounds.MixedShot s : shots) if (s.volume0() > loud.volume0()) loud = s;
        // behind a barrier, rumble is as muffled as a voice: filter gain enters power, highs enter the loop filter
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
        // rumble changed: loops start now; a blast without a voice (all or refused sounds) is heard immediately, not at engine tick
        if (v == null || v.fading() || VOICES.yielded != yielded) rumble(true);
    }

    /**
     * Free static engine channels now: what OpenAL provides ({@link #reach}) minus channels it holds (a stopped sound
     * holds its channel until the sound thread returns it to the pool, {@code ChannelHandle.isStopped}).
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

    /** Sound-engine tick: clock for blast-voice fade and rumble rise ({@link BlastVoices}, {@link Rumble}). */
    private static int soundTick() {
        return Minecraft.getInstance().getSoundManager().soundEngine.tickCount;
    }

    /** {@code SCENARIO blast-sound} line: last conventional blast at the ear and its echoes; empty — no blasts yet. */
    public static String describe() {
        if (last.isEmpty()) return "";
        BlastEchoes e = lastEchoes;
        return last + (e == null ? "" : "; " + e.describe()) + "; " + voices() + "; " + BlastHit.describe();
    }

    /**
     * Blast voices and rumble: channels held (playing/all, ceiling), voices requested from the engine, accepted and
     * refused, blasts sent straight to rumble and those that yielded slots; quarter rumble-loop volumes.
     */
    static String voices() {
        StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "voices %d/%d cap %d requested %d started %d refused %d rumble %d yielded %d loops",
                VOICES.steady(), VOICES.channels(), VOICES.cap(), VOICES.requested, VOICES.started, VOICES.refused, VOICES.toRumble, VOICES.yielded));
        for (int q = 0; q < Rumble.QUADRANTS; q++) sb.append(String.format(Locale.ROOT, " %.2f", Rumble.volume(RUMBLE.potential(q), RUMBLE_LUFS)));
        return sb.toString();
    }

    /** Clock for a blast born now: ticks since this instant, with a fraction. */
    public static OwnSources.Clock clock() {
        long born = ticks;
        return partial -> ticks - born + partial;
    }

    /** Client tick. */
    static void tick() {
        ticks++;
        FIRE.clear();
        FIRES.removeIf(fire -> !fire.tick(Minecraft.getInstance()));
        FIRE_RAYS.tick(FIRES, ear(), SoundFilters.WORLD, FIRE);
        fireLoops();
        MIX.at(ticks);
        // sound actually started after its tick — mix starts at the actual start, like its echoes and impact at the ear
        LATE.removeIf(l -> {
            if (Double.isNaN(l.anchor().zero())) return false;
            l.blast().late(20 * l.anchor().lateSeconds());
            return true;
        });
        VOICES.prune(soundTick());
        // free-channel reserve for near impact: world used it — quietest own voices yield early (channels return after ticks)
        if (VOICES.reserve(soundTick(), free()) > 0) rumble(true);
        rumble(false);
    }

    /**
     * Quarter fire-roar loops, once per tick: volume is the square root of summed quarter fire powers, highs are
     * power-weighted means; all quarters with a share of the audible sum play ({@link Quarters#plays}: threshold on
     * the sum, not the loop); restart a loop the engine does not hold (every {@code 20} ticks); release after one
     * second without playing; do not start while the “Ambient” slider is zero. Each quarter has its own `blast.fire`
     * variant — loop sounds are incoherent and their powers add as {@link Quarters} computes.
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
     * Quarter rumble loops ({@link Rumble}), once per client tick ({@code now} — immediately when rumble changed):
     * start when their members are audible (with power after the voices fade: the loop must play before a fading
     * voice hands it a share), at the volume of that instant; restart a loop the engine does not hold; release after
     * {@link Rumble#HOLD} ticks without playing; do not start while the “Ambient” slider is zero. Audibility is decided
     * by all rumble ({@link Rumble#plays}), not each loop. The loop then takes volume, pitch and highs itself in its
     * engine tick ({@link RumbleLoop}). Each quarter has its own recording variant — different quarters' noise is incoherent.
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
     * Quarter rumble loop: takes volume, pitch and highs in its own sound-engine tick, the same tick in which blast
     * voices fade (voice — {@code MixedShot.tick}, rumble — here, same clock). If computed in the client tick after
     * it, it would lag by a tick and the voice → rumble transition would lose up to 3.5 dB.
     */
    private static final class RumbleLoop extends AmbientLoop {
        private final int q;
        /** Owner released it: fade and go away, no longer taking rumble volume. */
        private boolean released;

        RumbleLoop(int q) {
            super(ModSounds.BLAST_RUMBLE.get(), SoundSource.AMBIENT, RUMBLE.pitch(q), variant(ModSounds.BLAST_RUMBLE.get(), q), 1);
            this.q = q;
        }

        /** Volume, pitch and highs from the rumble now. */
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
         * Set channel volume now without waiting for the engine tick: a blast with no voice at all plays in the
         * rumble from the instant its voice would have played (as the engine sets volume in its tick: × category slider).
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

    /** Seed with which quarter {@code q}'s loop selects its own {@code event} variant (blast rumble, fire roar). */
    private static long variant(SoundEvent event, int q) {
        WeighedSoundEvents w = Minecraft.getInstance().getSoundManager().getSoundEvent(event.getLocation());
        return Quarters.seed(w == null ? 1 : w.getWeight(), q);
    }

    /** Sound engine reloaded: voice ceiling follows device sources; old channels went away with it. */
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

    /** Entire blast one-shot (one recording — one blast, variant from {@code seed}) joins the mix before playing. */
    private static Mix.Blast mixed(SoundEvent event, float volume, float pitch, long seed) {
        Mix.Blast b = MIX.blast(ticks);
        b.add(new Mix.Layer(Envelopes.of(event.getLocation()), Envelopes.energy(event, seed), pitch, volume, Mix.Kind.APART, 0));
        return b;
    }

    /**
     * Layer sound lifetime, ticks: recording at its pitch plus margin. Mix sounds tick, and a ticking sound whose
     * channel the engine removed without {@code stop} (“Ambient” slider zero) would otherwise stay in its list.
     */
    private static int life(Mix.Layer layer) {
        return (int) Math.ceil(layer.ticks()) + 20;
    }

    /** Blast sound lifetime for one recording ({@link #mixed}), ticks. */
    private static int life(Mix.Blast blast) {
        return life(blast.layers.get(0));
    }

    /** All-clear, replay rewind, world exit: forget pending echoes ({@link OwnSources#reset} removes their voices). */
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
     * Bomb exploded underground without breaking through: above ground, the charge fraction that escaped into air
     * ({@code s.chargeKg()}, {@code warhead.BuriedBlast#airShare}) is heard as a dull thud (recording without highs:
     * ground absorbed them), volume from its pressure at the ear. For an underground listener, a rumble within the
     * ground at the same volume.
     *
     * @param underground listener is underground too
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
     * Bomb entered ground ({@code tntKg} — impact energy, kg TNT, {@code warhead.BunkerImpact}): dull impact with volume
     * from the pressure of the energy fraction sent into air. No crack: the supersonic bomb's N-wave plays during
     * flight ({@link Booms}).
     */
    public static void bunkerImpact(Vec3 pos, double tntKg) {
        float v = byPressure(BlastMix.pressure(BlastSource.air(BunkerImpact.airTnt(tntKg)), ear().distanceTo(pos)), IMPACT_LUFS);
        if (v >= AUDIBLE) bomb(ModSounds.BOMB_IMPACT.get(), pos, v, 1);
    }

    /** Blast gases escaped through the borehole: vent roar, volume from pressure of charge fraction {@code airKg} released into air. */
    public static void vent(Vec3 pos, double airKg) {
        float v = byPressure(BlastMix.pressure(BlastSource.air(airKg), ear().distanceTo(pos)), VENT_LUFS);
        if (v >= AUDIBLE) bomb(ModSounds.BOMB_VENT.get(), pos, v, 1);
    }

    /**
     * Roof above a cavity collapsed ({@code tntKg} — falling rock energy, kg TNT): collapse rumble, volume from the
     * pressure of the fraction sent into air (as for bomb ground impact, {@link BunkerImpact#airTnt}).
     */
    public static void collapse(Vec3 pos, double tntKg) {
        float v = byPressure(BlastMix.pressure(BlastSource.air(BunkerImpact.airTnt(tntKg)), ear().distanceTo(pos)), QUAKE_LUFS);
        if (v >= AUDIBLE) bomb(ModSounds.BOMB_QUAKE.get(), pos, v, 0.8f);
    }

    /** Playback volume of a {@code fileLufs} recording for pressure {@code pa} at the ear ({@link BlastMix#loudness}). */
    static float byPressure(double pa, double fileLufs) {
        return BlastMix.gain(BlastMix.loudness(pa) - fileLufs);
    }

    /** Playback volume of a {@code fileLufs} recording for blast {@code s} at the near model's edge ({@link Outdoor#NEAR}). */
    static float atNear(BlastSource s, double fileLufs) {
        return byPressure(BlastMix.pressure(s, Outdoor.NEAR), fileLufs);
    }

    private static Vec3 ear() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

    /** Ground jolt at the listener from the old sourceless packet (old Flashback recordings): full strength. */
    public static void quake() {
        Vec3 ear = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        bomb(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), 1, 1);
    }

    /**
     * Specific acoustic impedance of air ρ₀c₀, Pa·s/m (1.204 kg/m³ × 343 m/s at 20 °C, table value 413): a flat
     * surface moving at speed v radiates pressure ρ₀c₀·v.
     */
    static final double AIR_IMPEDANCE = ua.zentix.incoming.sound.Air.DENSITY * BlastModel.SOUND_SPEED;

    /**
     * Ground rumble when a ground wave with peak velocity {@code ppvMps}, m/s, reaches the feet: pressure ρ₀c₀·v at
     * the ground under the ear (the surroundings move in phase — radiator much larger than wavelength; ⚠ estimate:
     * radiation coefficient 1, lower for a tens-of-metres wave on soft ground), volume from pressure at the ear as for blasts.
     */
    public static void quake(double ppvMps) {
        float v = byPressure(rumblePa(ppvMps), QUAKE_LUFS);
        if (v < AUDIBLE) return;
        Vec3 ear = ear();
        bomb(ModSounds.BOMB_QUAKE.get(), ear.add(0, -4, 0), v, 1);
    }

    /** Ground-rumble pressure, Pa, for ground velocity {@code ppvMps}, m/s. */
    static double rumblePa(double ppvMps) {
        return AIR_IMPEDANCE * Math.max(0, ppvMps);
    }

    /** Bomb sound joins the blast mix as its own blast (each arrival is separate). */
    private static void bomb(SoundEvent event, Vec3 pos, float volume, float pitch) {
        long seed = SEEDS.nextLong();
        Mix.Blast blast = mixed(event, volume, pitch, seed);
        voice(blast, List.of(ClientSounds.blastShot(event, pos, volume, pitch, seed, blast::factor, life(blast))), pos, pitch, false);
    }

    /**
     * Crater ejecta falls back: {@code kg} of ground from {@code height} m — fall sound power from falling energy
     * ({@link Sources#fall}), audible range from the path to the ear ({@link Sources}).
     */
    public static void debris(Vec3 pos, double kg, double height, float pitch) {
        Sources.play(ModSounds.DEBRIS_FALL.get(), pos, Sources.EJECTA.power(Sources.fall(kg, height, EJECTA_FALL_S, Spectra.DEBRIS_FALL)), pitch,
                BlastSounds::oneShot);
    }

    /**
     * Blast one-shot arriving at the ear with {@code volume} (ejecta fall: volume from source law, {@link Sources})
     * joins the blast mix as its own blast with a budgeted voice ({@link BlastVoices}): in a raid each crater's fall
     * is a separate 2–9 s sound, and bypassing the budget, 300 Shaheds held ~150 channels with it, leaving none for
     * the near impact.
     */
    private static void oneShot(SoundEvent event, Vec3 at, float volume, float pitch) {
        long seed = SEEDS.nextLong();
        Mix.Blast blast = mixed(event, volume, pitch, seed);
        voice(blast, List.of(ClientSounds.blastShot(event, at, volume, pitch, seed, blast::factor, life(blast))), at, pitch, false);
    }

    /** Ejecta fall window, s: mean fall power is mgh over this time (⚠ estimate: ejecta falls over 1–3 s). */
    static final double EJECTA_FALL_S = 2;

    /**
     * Fuel fire in a crater (fire on server blocks, {@code age} ticks since impact): recording loops at native speed;
     * heat release, duration and position come from fuel {@code burn} (flowed or buried — the roar follows it).
     */
    public static void fire(ua.zentix.incoming.client.fx.FuelBurn burn, int age) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null && burn.law().watts() > 0) FIRES.add(new CraterFire(burn, world, age));
    }
}
