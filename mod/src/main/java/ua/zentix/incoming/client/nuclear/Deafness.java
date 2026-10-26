package ua.zentix.incoming.client.nuclear;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.client.sound.SoundFilters;
import ua.zentix.incoming.nuclear.model.HearingModel;
import ua.zentix.incoming.registry.ModSounds;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Player hearing after a nuclear or conventional blast wave (DESIGN-nuke §5, laws in {@link HearingModel}): threshold
 * shift from audible energy at the ear, recovery by game time (one hour — 1000 ticks), eardrum rupture. All game sounds are muffled by an OpenAL EFX
 * low-pass filter ({@link ua.zentix.incoming.client.sound.SoundFilters}; own sources —
 * {@link ua.zentix.incoming.client.sound.OwnSources}): lows lose volume through cochlear and conductive loss, highs lose
 * the high–low difference on top of that. Ears ring while the shift remains. The player's category volumes are unchanged.
 * <p>
 * Hearing time follows unpaused client ticks and forward time-of-day jumps (sleep, {@code /time add}): a night of sleep restores
 * hearing as a full night would. A jump counts only when game time advanced normally (sleep and {@code /time} do not
 * move it): a new client world (portal) starts with a zero clock until the time packet, so its first packet would otherwise
 * count as sleep. Hearing belongs to the body: death (respawn) restores it, as does leaving the world (state is client
 * only).
 */
public final class Deafness {
    /** Ticks per game hour. */
    private static final double HOUR_TICKS = 1000;
    /** A larger time-of-day jump means elapsed time (sleep); a smaller one is a server clock correction. */
    private static final long DAY_JUMP = 20;
    /** Ringing volume at a shift of {@link #RINGING_SHIFT} dB or more (ringing volume is an approximation, not from the model). */
    private static final float RINGING = 0.75f;
    private static final double RINGING_SHIFT = 50;
    /** Below this, ringing is inaudible. */
    private static final float RINGING_QUIET = 0.01f;

    /** Waves that reached the ear. */
    private static final HearingModel.Ear EAR = new HearingModel.Ear();
    /** When the eardrums ruptured (hearing clock); −1 — intact. */
    private static long rupturedAt = -1;
    private static long clock;
    /** Time of day, game time and client world from the previous tick. */
    private static long lastDay, lastGame;
    private static WeakReference<ClientLevel> lastLevel = new WeakReference<>(null);
    /** Current filter: volume and highs (also read by the sound thread). */
    private static volatile float gain = 1, highs = 1;
    /** Current high-frequency shift, dB (for ringing and the scenario line). */
    private static double shiftNow;
    @Nullable
    private static Ringing ringing;
    /**
     * Sounds of the wave itself (impact, cracks, glass): heard with the hearing that preceded it. Otherwise chance would decide: on its first play
     * in the game, a recording without preload starts on the sound thread after the wave hits the ears, muting the impact by its own effect,
     * while later plays would not. Weak keys; also read by the sound thread.
     */
    private static final Map<SoundInstance, float[]> BEFORE = Collections.synchronizedMap(new WeakHashMap<>());

    private Deafness() {}

    /**
     * The wave reached the ear: {@code exposure} — its audible energy, dB·s ({@link HearingModel#exposure(double, double)}, minus
     * path losses), {@code kpa} — ear pressure, {@code seed} — blast seed (eardrums rupture identically
     * on replaying the same blast).
     */
    public static void blast(double exposure, double kpa, long seed) {
        EAR.blast(exposure, clock / HOUR_TICKS);
        // ruptured eardrums that have not healed rupture again — restart healing
        Minecraft mc = Minecraft.getInstance();
        long who = mc.player == null ? 0 : mc.player.getUUID().getLeastSignificantBits();
        if (RandomSource.create(seed ^ who ^ 0x7E3D_7E3DL).nextDouble() < HearingModel.ruptureProbability(kpa)) rupturedAt = clock;
        update();
    }

    /** Sound of a wave about to reach the ear: heard as before it ({@link #BEFORE}). Call before {@link #blast}. */
    public static void before(SoundInstance sound) {
        BEFORE.put(sound, new float[]{gain, highs});
    }

    /** Client tick: hearing clock, filter, ringing. */
    static void tick(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        long day = level.getDayTime(), game = level.getGameTime(), jump = 0;
        // time of day advanced beyond game time while game time ran normally — time elapsed (sleep, /time add)
        if (lastLevel.get() == level && Math.abs(game - lastGame - 1) <= DAY_JUMP) jump = day - lastDay - (game - lastGame);
        if (lastLevel.get() != level) lastLevel = new WeakReference<>(level);
        lastDay = day;
        lastGame = game;
        if (!mc.isPaused()) clock += 1 + (jump > DAY_JUMP ? jump : 0);
        if (!EAR.any() && rupturedAt < 0) return;
        // hearing damage disabled in settings — immediately restore hearing
        if (!IncomingConfig.CLIENT.nukeTinnitus.get()) {
            reset();
            return;
        }
        update();
        ring(mc);
    }

    private static void update() {
        double high = EAR.shift(clock / HOUR_TICKS);
        shiftNow = high;
        double days = rupturedAt < 0 ? 0 : (clock - rupturedAt) / (24 * HOUR_TICKS);
        double torn = rupturedAt < 0 ? 0 : 1;
        double lossHigh = HearingModel.loudnessLoss(high) + torn * HearingModel.rupturedLoss(true, days);
        double lossLow = HearingModel.loudnessLoss(HearingModel.low(high)) + torn * HearingModel.rupturedLoss(false, days);
        if (rupturedAt >= 0 && HearingModel.rupturedLoss(false, days) <= 0) rupturedAt = -1;
        gain = (float) Math.pow(10, -lossLow / 20);
        highs = (float) Math.pow(10, -Math.max(0, lossHigh - lossLow) / 20);
    }

    /** Ringing in the ears: loop in the head, volume based on threshold shift. */
    private static void ring(Minecraft mc) {
        float v = (float) Math.min(RINGING, RINGING * shiftNow / RINGING_SHIFT);
        if (ringing != null && !ringing.playing()) ringing = null;
        if (v < RINGING_QUIET) {
            if (ringing != null) ringing.end();
            ringing = null;
            return;
        }
        if (ringing == null) {
            if (mc.options.getSoundSourceVolume(SoundSource.MASTER) <= 0) return;
            ringing = new Ringing();
            ringing.level(v);
            mc.getSoundManager().play(ringing);
            return;
        }
        ringing.level(v);
    }

    /** Restore hearing: leaving the world, replay seek, respawn. */
    static void reset() {
        if (gain < 1 || highs < 1) SoundFilters.restoreLoops();
        EAR.clear();
        shiftNow = 0;
        rupturedAt = -1;
        gain = highs = 1;
        if (ringing != null) ringing.end();
        ringing = null;
    }

    private static boolean deaf(SoundInstance sound) {
        // leave music, records and the ringing itself unchanged
        return sound.getSource() != SoundSource.MUSIC && sound.getSource() != SoundSource.RECORDS && !(sound instanceof Ringing);
    }

    /** Current volume of this sound (lows), 1 — heard normally. Called from the sound thread. */
    public static float gain(SoundInstance sound) {
        if (!deaf(sound)) return 1;
        float[] b = BEFORE.get(sound);
        return b == null ? gain : b[0];
    }

    /** Current high-to-low share of this sound. Called from the sound thread. */
    public static float highs(SoundInstance sound) {
        if (!deaf(sound)) return 1;
        float[] b = BEFORE.get(sound);
        return b == null ? highs : b[1];
    }

    /** Current world-sound volume — including own OpenAL sources ({@link ua.zentix.incoming.client.sound.OwnSources}). */
    public static float gain() {
        return gain;
    }

    /** Current high-frequency share of world sound. */
    public static float highs() {
        return highs;
    }

    /** {@code SCENARIO} line: threshold shift, eardrums, filter, ringing. */
    static String describe() {
        return String.format(Locale.ROOT, "hearing shift %.1fdB torn %s gain %.2f highs %.2f ringing %s", shiftNow,
                rupturedAt >= 0, gain, highs, ringing != null);
    }

    /**
     * Ringing is in the head: an unpositioned loop (at the listener regardless of position or speed), bypassing path,
     * pall and Sound Physics filters. A world-space position at the ear would lag the listener by a tick: the tone would move side to side while walking.
     */
    private static final class Ringing extends AbstractTickableSoundInstance {
        /** Volume approaches its target by this much per tick. */
        private static final float SLEW = 0.05f;
        /** If the owner stays silent this many ticks, the ringing fades. */
        private static final int ORPHAN = 10;
        private float target;
        private int quiet;
        private boolean ending;

        Ringing() {
            super(ModSounds.NUKE_TINNITUS.get(), SoundSource.MASTER, SoundInstance.createUnseededRandom());
            looping = true;
            delay = 0;
            volume = 0.001f;
            relative = true;
            attenuation = Attenuation.NONE;
            x = y = z = 0;
        }

        void level(float v) {
            target = v;
            quiet = 0;
        }

        void end() {
            ending = true;
            target = 0;
        }

        /** Whether it is playing (like {@link ua.zentix.incoming.client.sound.AmbientLoop#playing}). */
        boolean playing() {
            return !isStopped() && Minecraft.getInstance().getSoundManager().soundEngine.instanceToChannel.containsKey(this);
        }

        @Override
        public void tick() {
            if (++quiet > ORPHAN) end();
            volume += Math.max(-SLEW, Math.min(SLEW, target - volume));
            if (ending && volume <= 0.002f) stop();
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
