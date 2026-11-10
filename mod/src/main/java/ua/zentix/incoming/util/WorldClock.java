package ua.zentix.incoming.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;

/**
 * The world's clock for slow consequences (hours and days: fire burning out, fallout decay, sickness, burns healing,
 * hearing, power repairs). It counts world time ({@code dayTime}: 1000 ticks are an hour of the day), but never slower
 * than real time: an hour of real play is at least an hour. So it follows how the world lives:
 * <ul>
 *     <li>vanilla day (20 real minutes) — 1000 ticks of clock per 1000 game ticks, an hour every 50 s, as game time;</li>
 *     <li>a day-length mod (a day of 24 real hours) — an hour per real hour, everything 1:1 with no setting;</li>
 *     <li>{@code doDaylightCycle false} — real time;</li>
 *     <li>sleep and {@code /time add} — the clock jumps forward with the day: a night's sleep heals as a night does;</li>
 *     <li>{@code /time set} backward — the clock never runs back: that tick counts as real time.</li>
 * </ul>
 * Value in world-clock ticks (1000 per hour). Server-wide (all dimensions share the overworld's day), saved with the
 * overworld. A new clock starts at the overworld game time, so times saved before it (stored as game time) keep their
 * meaning: under a vanilla day both clocks advance together.
 */
public final class WorldClock extends SavedData {
    private static final String NAME = Incoming.MOD_ID + "_world_clock";
    /** World-clock ticks per hour of the day. */
    public static final double HOUR = 1000;
    /** World-clock seconds per world-clock tick: an hour is 1000 ticks. */
    public static final double SECONDS_PER_TICK = 3600 / HOUR;
    /** Real time in world-clock ticks per server tick: a real hour (72 000 ticks) is 1000 world-clock ticks. */
    public static final double REAL_RATE = HOUR / 72_000;

    private double ticks;
    /** Clock at the end of each of the last {@link #PACE_TICKS} + 1 server ticks, by game time (not saved). */
    private final double[] recent = new double[PACE_TICKS + 1];
    private long recentAt = Long.MIN_VALUE, filled;
    /** Overworld time of day at the previous tick; {@link Long#MIN_VALUE} — none yet (load, first tick). */
    private long lastDay = Long.MIN_VALUE;

    private WorldClock(double ticks) {
        this.ticks = ticks;
    }

    public static WorldClock get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new Factory<>(() -> new WorldClock(overworld.getGameTime()), WorldClock::load, null), NAME);
    }

    /** World clock now, ticks. */
    public static double now(Level level) {
        return level.getServer() == null ? level.getGameTime() : get(level.getServer()).ticks;
    }

    /** World clock now, whole ticks (for times stored as {@code long}). */
    public static long ticks(Level level) {
        return (long) Math.floor(now(level));
    }

    /** Span of {@link #lastSecond}, server ticks. */
    public static final int PACE_TICKS = 20;

    /**
     * World-clock ticks over the last {@link #PACE_TICKS} server ticks ({@link #PACE_TICKS} under a vanilla day; less under a
     * slow or stopped day; more across a sleep): paces game-tick timers (effect durations) to the clock.
     */
    public static double lastSecond(Level level) {
        if (level.getServer() == null) return PACE_TICKS;
        WorldClock c = get(level.getServer());
        if (c.recentAt == Long.MIN_VALUE) return PACE_TICKS;
        long oldest = c.recentAt - PACE_TICKS;
        int filled = (int) Math.min(PACE_TICKS, c.filled);
        if (filled < PACE_TICKS) return PACE_TICKS;
        // whole ticks: a timer sampled every second keeps the fractions of a slow day (successive windows telescope)
        return Math.floor(c.ticks) - Math.floor(c.recent[(int) Math.floorMod(oldest, (long) c.recent.length)]);
    }

    /**
     * World-clock ticks per server tick over the last {@link #PACE_TICKS} ticks: 1 under a vanilla day, {@link #REAL_RATE}
     * under a stopped one (a sleep inflates it for a second).
     */
    public static double rate(Level level) {
        if (level.getServer() == null) return 1;
        WorldClock c = get(level.getServer());
        if (c.recentAt == Long.MIN_VALUE || c.filled < PACE_TICKS) return 1;
        return (c.ticks - c.recent[(int) Math.floorMod(c.recentAt - PACE_TICKS, (long) c.recent.length)]) / PACE_TICKS;
    }

    /** World-clock ticks for one server tick in which the time of day changed by {@code dayDelta}. */
    public static double step(long dayDelta) {
        return dayDelta < 0 ? REAL_RATE : Math.max(dayDelta, REAL_RATE);
    }

    /** After all worlds ticked (time of day advanced, sleepers woke): count this tick. */
    public static void onServerTick(ServerTickEvent.Post e) {
        WorldClock c = get(e.getServer());
        long day = e.getServer().overworld().getDayTime();
        if (c.lastDay != Long.MIN_VALUE) c.ticks += step(day - c.lastDay);
        c.lastDay = day;
        long game = e.getServer().overworld().getGameTime();
        c.filled = c.recentAt == game - 1 ? c.filled + 1 : 0;
        c.recentAt = game;
        c.recent[(int) Math.floorMod(game, (long) c.recent.length)] = c.ticks;
        c.setDirty();
    }

    /** Test seam: advance by world-clock ticks as if the time of day had moved by that much. */
    public void advance(double worldTicks) {
        ticks += Math.max(0, worldTicks);
        setDirty();
    }

    private static WorldClock load(CompoundTag tag, HolderLookup.Provider registries) {
        return new WorldClock(tag.getDouble("ticks"));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putDouble("ticks", ticks);
        return tag;
    }
}
