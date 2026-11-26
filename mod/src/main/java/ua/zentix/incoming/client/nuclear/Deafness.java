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

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import ua.zentix.incoming.net.C2S;
import ua.zentix.incoming.util.RecoveryClock;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Player hearing after a nuclear or conventional blast wave (DESIGN-nuke §5, laws in {@link HearingModel}): threshold
 * shift from audible energy at the ear, recovery on the ear's recovery clock (one hour — 1000 ticks), eardrum rupture. All game sounds are muffled by an OpenAL EFX
 * low-pass filter ({@link ua.zentix.incoming.client.sound.SoundFilters}; own sources —
 * {@link ua.zentix.incoming.client.sound.OwnSources}): lows lose volume through cochlear and conductive loss, highs lose
 * the high–low difference on top of that. Ears ring faintly while the shift remains ({@link HearingModel#ringing}).
 * The player's category volumes are unchanged.
 * <p>
 * Hearing time is the ear's recovery clock ({@link RecoveryClock}, {@code docs/design/time.md} §8.3 rule 8): real time while a
 * blast that hurt the ear is in its show (a detonation's 20 minutes; a conventional strike until its cloud's apex and its crater
 * fuel's burn-out, {@code S2C.Blast#show}), then the world's day ({@link ClientClock}) through the rate blend; every jump counts,
 * so a night of sleep restores hearing as a full night would, and a day-length mod's hour is a real hour. Blasts within 2
 * minutes of event time (real time plus jumps nobody was awake for) form one dose. Hearing belongs to the body: death (respawn)
 * restores it. It is kept across relogging: the client tells the server its ear every {@link #TELL_TICKS} ticks
 * ({@code C2S.Ear}) and gets it back on login ({@code S2C.Ear}); the clock does not move while the player is away.
 */
public final class Deafness {
    /** World-clock ticks per hour. */
    private static final double HOUR_TICKS = ua.zentix.incoming.util.WorldClock.HOUR;
    /** Ringing loop volume for a tone as loud as world sound ({@link HearingModel#ringing} 1), the cap (est.). */
    private static final float RINGING = 0.75f;
    /** Below this, ringing is inaudible. */
    private static final float RINGING_QUIET = 0.01f;

    /** How often the client tells the server its ear while it is hurt, game ticks. */
    static final int TELL_TICKS = 100;
    /** Waves that reached the ear. */
    private static final HearingModel.Ear EAR = new HearingModel.Ear();
    /** When the eardrums ruptured (hearing clock); −1 — intact. */
    private static double rupturedAt = -1;
    /** The ear's recovery clock. */
    private static RecoveryClock recovery = new RecoveryClock(0);
    /** Hearing clock and event time now, world-clock ticks ({@link #recovery}). */
    private static double clock, event;
    /** Conventional blasts' show ends by blast seed, game time ({@code S2C.Blast#show}): a blast that hurts the ear holds it. */
    private static final Map<Long, Show> SHOWS = new HashMap<>();

    /** A conventional blast's show: its end and blast time (game time), where it was, whether it hurt the ear. */
    private static final class Show {
        long end;
        final long at;
        final double x, z;
        boolean heard;

        Show(long end, long at, double x, double z) {
            this.end = end;
            this.at = at;
            this.x = x;
            this.z = z;
        }
    }
    /** The server holds a hurt ear for this player ({@link #tell}). */
    private static boolean told;
    /** A blast just hurt the ear: tell the server on the next tick. */
    private static boolean tellNow;
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
        Show show = SHOWS.get(seed);
        if (show != null) {
            show.heard = true;
            recovery.until(show.end);
        }
        hurt(exposure, kpa, seed);
    }

    /** The same for detonation {@code strike}'s wave: its show holds the ear's recovery. */
    public static void nuke(double exposure, double kpa, long seed, int strike) {
        recovery.strike(strike);
        hurt(exposure, kpa, seed);
    }

    /**
     * A conventional blast with seed {@code seed} happened at game time {@code game} at {@code (x, z)}; its show lasts
     * {@code show} game ticks until its cloud's apex ({@code StrikeShow.NONE} — none), longer if its crater fuel lights
     * ({@link #fuel}).
     */
    public static void announce(long seed, long game, double x, double z, int show) {
        if (show < 0) return;
        SHOWS.values().removeIf(s -> s.end < game);
        SHOWS.put(seed, new Show(game + show, game, x, z));
    }

    /** Blasts this far from a lit crater (blocks) and this close in time (game ticks) are its strike. */
    private static final double FUEL_NEAR = 32;
    private static final long FUEL_SOON = 100;

    /**
     * Crater fuel of a strike at game time {@code blastTick} lit cells starting at {@code (x, z)} and burns out after
     * {@code ticks} ({@code S2C.FuelFire}): the strike's show lasts until then.
     */
    public static void fuel(long blastTick, double x, double z, int ticks) {
        for (Show s : SHOWS.values()) {
            if (Math.abs(s.at - blastTick) > FUEL_SOON || (s.x - x) * (s.x - x) + (s.z - z) * (s.z - z) > FUEL_NEAR * FUEL_NEAR) continue;
            s.end = Math.max(s.end, blastTick + ticks);
            if (s.heard) recovery.until(s.end);
        }
    }

    private static void hurt(double exposure, double kpa, long seed) {
        // the server holds the ear from the hurting blast on: a relog seconds later cures nothing
        tellNow = true;
        EAR.blast(exposure, clock / HOUR_TICKS, event / HOUR_TICKS);
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
        // the jump counters are per dimension: those the server last told, for the dimension it told them for
        recovery.step(level.getGameTime(), ClientClock.rateClock(), ClientClock.counted(), ClientClock.unseen(), ClientClock.dimension(),
                ClientNuclear::showEnd);
        clock = recovery.clock();
        event = recovery.event();
        tell(level.getGameTime());
        if (!EAR.any() && rupturedAt < 0) return;
        // hearing damage disabled in settings — immediately restore hearing
        if (!IncomingConfig.CLIENT.nukeTinnitus.get()) {
            reset();
            return;
        }
        update();
        ring(mc);
    }

    /** Tell the server the ear while it is hurt, and once when it has healed ({@code C2S.Ear}); never from a replay. */
    private static void tell(long game) {
        boolean hurt = EAR.any() || rupturedAt >= 0;
        if (hurt ? game % TELL_TICKS != 0 && !tellNow : !told) return;
        if (ua.zentix.incoming.client.replay.Replay.active() || Minecraft.getInstance().getConnection() == null) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToServer(new C2S.Ear(hurt ? save() : new CompoundTag()));
        told = hurt;
        tellNow = false;
    }

    /** The ear's state: doses, eardrums and the recovery clock. */
    static CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.put("doses", Codec.DOUBLE.listOf().encodeStart(NbtOps.INSTANCE, EAR.save()).getOrThrow());
        t.putDouble("ruptured", rupturedAt);
        t.put("clock", RecoveryClock.CODEC.encodeStart(NbtOps.INSTANCE, recovery).getOrThrow());
        return t;
    }

    /** The ear as the server kept it ({@code S2C.Ear}, on login): what the player left with. Empty — a healthy ear. */
    public static void restore(CompoundTag t) {
        reset();
        if (t.isEmpty()) return;
        Codec.DOUBLE.listOf().parse(NbtOps.INSTANCE, t.get("doses")).result().ifPresent(EAR::restore);
        RecoveryClock.CODEC.parse(NbtOps.INSTANCE, t.getCompound("clock")).result().ifPresent(c -> recovery = c);
        double ruptured = t.getDouble("ruptured");
        rupturedAt = Double.isFinite(ruptured) && t.contains("ruptured") ? ruptured : -1;
        clock = recovery.clock();
        event = recovery.event();
        told = EAR.any() || rupturedAt >= 0;
        update();
    }

    private static void update() {
        double high = EAR.shift(clock / HOUR_TICKS, event / HOUR_TICKS);
        shiftNow = high;
        double days = rupturedAt < 0 ? 0 : (clock - rupturedAt) / (24 * HOUR_TICKS);
        double torn = rupturedAt < 0 ? 0 : 1;
        double lossHigh = HearingModel.loudnessLoss(high) + torn * HearingModel.rupturedLoss(true, days);
        double lossLow = HearingModel.loudnessLoss(HearingModel.low(high)) + torn * HearingModel.rupturedLoss(false, days);
        if (rupturedAt >= 0 && HearingModel.rupturedLoss(false, days) <= 0) rupturedAt = -1;
        gain = (float) Math.pow(10, -lossLow / 20);
        highs = (float) Math.pow(10, -Math.max(0, lossHigh - lossLow) / 20);
    }

    /** Ringing in the ears: loop in the head, a faint tone above the shifted threshold ({@link HearingModel#ringing}). */
    private static void ring(Minecraft mc) {
        float v = (float) Math.min(RINGING, RINGING * HearingModel.ringing(shiftNow));
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
        SHOWS.clear();
        recovery = new RecoveryClock(0);
        clock = event = 0;
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
