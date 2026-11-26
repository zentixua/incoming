package ua.zentix.incoming.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import ua.zentix.incoming.Incoming;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The world's clock for what stays in place (hours and days: fires burning down, contamination, sickness, burns, hearing,
 * power repairs; AGENTS.md “Time”, {@code docs/design/time.md} §8.3). It counts the world's day ({@code dayTime}: 1000
 * ticks are an hour of the day), never slower than real time:
 * <ul>
 *     <li>the world's rate k ({@link #pace}) is the day's measured advance per real time, at least 1: vanilla 72 (an hour in
 *     50 s), a 2-hour day 12, a 24-hour day 1 ({@link Steps}: a day that steps +N every M ticks is counted once, the real
 *     time already credited since its last step is subtracted);</li>
 *     <li>a stopped day ({@code doDaylightCycle false}, a mod holding the time) keeps the last running rate, vanilla 72 if
 *     the day never ran: stopping the sun stops the light, not the world;</li>
 *     <li>a jump (a forward step of at least an hour in one tick: sleep, {@code /time add}, and, while the day runs, a
 *     {@code /time set} whose forward distance modulo a day is under 12 hours) moves the clock with the day: a night's
 *     sleep heals as a night does. A {@code /time set} on a stopped day only chooses the light; a backward set never
 *     invents days;</li>
 *     <li>real time is game time: a frozen tick manager ({@code /tick freeze}) stops the clock.</li>
 * </ul>
 * Three readings:
 * <ul>
 *     <li>{@link #now} — the clock with every jump: what nobody sees change (doses, injuries, outages, repairs);</li>
 *     <li>{@link #rateClock} — the clock without jumps: the world's rate alone, for a strike's clock after its show
 *     ({@link StrikeClock});</li>
 *     <li>per dimension, {@link #counted} — jumps that fell while nobody in that dimension was awake (spectators and idle
 *     players are awake); they move the strikes there and end their shows. A jump with someone awake is {@link #unseen}:
 *     it moves only what nobody sees change. A counted jump that falls while a blast front crosses the dimension
 *     ({@link #holdJumpsWhile}) is counted when the front ends. Fixed-time dimensions (Nether, End) count no jumps.</li>
 * </ul>
 * Values in world-clock ticks (1000 per hour). Server-wide (all dimensions share the overworld's day), saved with the
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

    /** The clock with every jump ({@link #now}). */
    private double ticks;
    /** The clock without jumps ({@link #rateClock}). */
    private double rateTicks;
    /** Clock at the end of each of the last {@link #PACE_TICKS} + 1 server ticks, by game time (not saved). */
    private final double[] recent = new double[PACE_TICKS + 1];
    private long recentAt = Long.MIN_VALUE, filled;
    /** Overworld time of day at the previous counted tick; {@link Long#MIN_VALUE} — none yet (load, first tick). */
    private long lastDay = Long.MIN_VALUE;
    private final Steps steps = new Steps();
    /** Jumps per dimension, by dimension id. */
    private final Map<String, Jumps> jumps = new HashMap<>();
    /** A {@code /time set} command ran since the last counted tick ({@link #onCommand}). */
    private boolean timeSet;
    /** Dimensions with no awake player before this tick's worlds ticked (sleepers are woken inside the tick). */
    private final Map<ResourceKey<Level>, Boolean> noneAwake = new HashMap<>();

    /** Jumps of one dimension, world-clock ticks. */
    static final class Jumps {
        double counted, unseen, pending;
    }

    /** While true for a dimension, its counted jumps wait (a blast front crossing it: flight and the wave are never jumped). */
    private static Predicate<ServerLevel> hold = level -> false;

    private WorldClock(double ticks, double rateTicks) {
        this.ticks = ticks;
        this.rateTicks = rateTicks;
    }

    public static WorldClock get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new Factory<>(() -> new WorldClock(overworld.getGameTime(), overworld.getGameTime()), WorldClock::load, null), NAME);
    }

    /** Counted jumps in a dimension wait while {@code busy} holds for it (a blast front still crossing). */
    public static void holdJumpsWhile(Predicate<ServerLevel> busy) {
        hold = busy;
    }

    /** World clock now with every jump, ticks: what nobody sees change (doses, injuries, outages, repairs). */
    public static double now(Level level) {
        return level.getServer() == null ? level.getGameTime() : get(level.getServer()).ticks;
    }

    /** World clock now, whole ticks (for times stored as {@code long}). */
    public static long ticks(Level level) {
        return (long) Math.floor(now(level));
    }

    /** World clock without jumps, ticks: the world's rate alone ({@link StrikeClock}). */
    public static double rateClock(Level level) {
        return level.getServer() == null ? level.getGameTime() : get(level.getServer()).rateTicks;
    }

    /** The world's rate k: world time per real time, at least 1 (vanilla 72; a stopped day keeps its last rate). */
    public static double pace(Level level) {
        return level.getServer() == null ? Steps.VANILLA_PACE : get(level.getServer()).steps.pace;
    }

    /** Jumps counted in {@code level}'s dimension so far (nobody there awake), ticks: they move its strikes. */
    public static double counted(Level level) {
        Jumps j = jumps(level);
        return j == null ? 0 : j.counted;
    }

    /**
     * Jumps not counted in {@code level}'s dimension so far (someone there awake), ticks: they move only what nobody sees
     * change; a clock of such a state adds them to its strike clock.
     */
    public static double unseen(Level level) {
        Jumps j = jumps(level);
        return j == null ? 0 : j.unseen;
    }

    /**
     * Jumps in {@code level}'s dimension counted but held while a blast front crosses ({@link #holdJumpsWhile}), ticks: they
     * join {@link #counted} when it has crossed. A strike born meanwhile takes them as already its past.
     */
    public static double held(Level level) {
        Jumps j = jumps(level);
        return j == null ? 0 : j.pending;
    }

    private static Jumps jumps(Level level) {
        return level.getServer() == null ? null : get(level.getServer()).jumps.get(level.dimension().location().toString());
    }

    /** What a strike clock reads in {@code level} now ({@link StrikeClock}). */
    public static Reading reading(Level level) {
        return new Reading(level.getGameTime(), rateClock(level), counted(level));
    }

    /**
     * One moment of a dimension's clocks: game time (real time), the clock without jumps and the counted jumps, ticks.
     */
    public record Reading(long game, double rate, double counted) {}

    /** Span of {@link #lastSecond}, server ticks. */
    public static final int PACE_TICKS = 20;

    /**
     * World-clock ticks over the last {@link #PACE_TICKS} server ticks ({@link #PACE_TICKS} under a vanilla day; less under a
     * slow day; more across a sleep): paces game-tick timers (effect durations) to the clock.
     */
    public static double lastSecond(Level level) {
        if (level.getServer() == null) return PACE_TICKS;
        WorldClock c = get(level.getServer());
        if (c.recentAt == Long.MIN_VALUE) return PACE_TICKS;
        long oldest = c.recentAt - PACE_TICKS;
        int filled = (int) Math.min(PACE_TICKS, c.filled);
        if (filled < PACE_TICKS) return PACE_TICKS;
        // whole ticks: a timer sampled on every PACE_TICKS-th game tick keeps the fractions of a slow day (successive windows
        // telescope); sampling on its own remainder would not (BurnsEffect)
        return Math.floor(c.ticks) - Math.floor(c.recent[(int) Math.floorMod(oldest, (long) c.recent.length)]);
    }

    /**
     * World-clock ticks per server tick over the last {@link #PACE_TICKS} ticks: 1 under a vanilla day (and a stopped one
     * that ran at vanilla speed), {@link #REAL_RATE} under a 24-hour day (a sleep inflates it for a second).
     */
    public static double rate(Level level) {
        if (level.getServer() == null) return 1;
        WorldClock c = get(level.getServer());
        if (c.recentAt == Long.MIN_VALUE || c.filled < PACE_TICKS) return 1;
        return (c.ticks - c.recent[(int) Math.floorMod(c.recentAt - PACE_TICKS, (long) c.recent.length)]) / PACE_TICKS;
    }

    /**
     * Counts the clock tick by tick from the time of day's changes. Each counted tick yields the world's rate ({@link #rate}:
     * the clock without jumps moves by it) and a jump ({@link #jump}, at least an hour, or 0).
     * <p>
     * The rate is measured from the day's own steps: a step of d clock ticks after g real ticks that follows a running
     * step at its cadence (g at most twice the previous gap, which was within {@link #RUN_WINDOW}) sets k = d / (g real),
     * at least 1; between steps the clock is
     * credited at k, and a step adds only what the credit has not covered yet. So a vanilla day (+1 a tick) runs 72×, a
     * day-length mod's +10 every 720 ticks 1×, not twice, and a day that stops keeps the last k: neither a nudge of a
     * stopped day nor the first step after a stop measures it, and the counting state is saved, so a load does not either.
     * A day never seen running is credited at the vanilla pace until its second step. A step faster than {@link #MAX_STEP} clock ticks a tick (a setting, {@code /time add} under an hour) is
     * added as it is and does not set k.
     */
    public static final class Steps {
        /** The world's rate before the day has been seen running: a vanilla day. */
        public static final double VANILLA_PACE = 72;
        /** A jump: a forward step of the day of at least an hour in one tick. */
        public static final long JUMP = (long) HOUR;
        static final long DAY = 24_000, HALF_DAY = DAY / 2;
        /** Day steps faster than this, clock ticks per real tick (k = 1440), are settings, not the day's pace. */
        static final double MAX_STEP = 20;
        /** The day counts as running for this many real ticks after its last step (at least twice the step's gap). */
        static final long RUN_WINDOW = 1200;

        private double pace = VANILLA_PACE;
        /** Rate credited since the day's last step, clock ticks. */
        private double credited;
        /** Real ticks since the day's last step, and between its last two. */
        private long gap, lastGap;
        private double rate, jump;

        /**
         * One counted tick: the time of day moved by {@code dayDelta} over {@code real} game ticks (1 on the server; a
         * client may see several at once). {@code timeSet} — a {@code /time set} command set it.
         */
        public void step(long dayDelta, long real, boolean timeSet) {
            rate = 0;
            jump = 0;
            real = Math.max(1, real);
            gap += real;
            long d = dayDelta;
            if (timeSet || d < 0) {
                // a set: forward distance modulo a day. Under 12 hours while the day runs — a night skip; on a stopped day
                // it only chooses the light; further — backward, never inventing days
                long f = Math.floorMod(d, DAY);
                if (!running() || f >= HALF_DAY || f == 0) {
                    credit(real);
                    return;
                }
                d = f;
            }
            if (d >= JUMP) {
                // the jump covers this tick; a running day's next step follows it at its own gap
                jump = d;
                if (running()) {
                    credited = 0;
                    gap = 0;
                } else {
                    credit(real);
                }
                return;
            }
            if (d == 0) {
                credit(real);
                return;
            }
            long g = Math.max(gap, 1);
            if (d > MAX_STEP * g) {
                // a setting, not the day's pace: added as it is
                rate = d;
                credit(real);
                return;
            }
            // the pace comes only from a step that follows a running step at its cadence: its gap is the day's own. The
            // first step after a stop or a fresh start, and a nudge of a stopped day, span time the day did not run, so
            // they only add what the credit has not covered
            if (lastGap > 0 && g <= 2 * lastGap + 1) pace = Math.max(1, d / (g * REAL_RATE));
            rate = Math.max(0, d - credited);
            credited = 0;
            gap = 0;
            // a step after a long stop does not show the day running
            lastGap = g <= RUN_WINDOW ? g : 0;
        }

        private void credit(long real) {
            double c = real * pace * REAL_RATE;
            rate += c;
            credited += c;
        }

        /** The day advanced by its own steps lately (not stopped). */
        public boolean running() {
            return lastGap > 0 && gap <= Math.max(RUN_WINDOW, 2 * lastGap);
        }

        /** The world's rate k (world time per real time, at least 1). */
        public double pace() {
            return pace;
        }

        /** Last tick's rate: clock ticks without jumps. */
        public double rate() {
            return rate;
        }

        /** Last tick's jump, clock ticks; 0 — none. */
        public double jump() {
            return jump;
        }

        /** Restore the world's rate (saved, or the server's). */
        public void pace(double k) {
            if (Double.isFinite(k)) pace = Math.max(1, k);
        }

        /** Save the counting state (the world's rate, the credit since the day's last step and the gaps). */
        public void save(CompoundTag tag) {
            tag.putDouble("pace", pace);
            tag.putDouble("credited", credited);
            tag.putLong("gap", gap);
            tag.putLong("last_gap", lastGap);
        }

        /** Load the counting state; absent values: the vanilla pace, nothing credited, the day not yet seen running. */
        public void load(CompoundTag tag) {
            if (tag.contains("pace")) pace(tag.getDouble("pace"));
            credited = tag.getDouble("credited");
            gap = Math.max(0, tag.getLong("gap"));
            lastGap = Math.max(0, tag.getLong("last_gap"));
        }
    }

    /**
     * {@code /time set} by any command source, also through {@code /execute … run} (the last child context holds the
     * command that runs): its step is a set, not an addition ({@link Steps#step}). Limit: datapack functions run without
     * a {@code CommandEvent}, so a forward set they make counts like {@code /time add}.
     */
    public static void onCommand(CommandEvent e) {
        var nodes = e.getParseResults().getContext().getLastChild().getNodes();
        if (nodes.size() >= 2 && "time".equals(nodes.get(0).getNode().getName()) && "set".equals(nodes.get(1).getNode().getName())) {
            MinecraftServer server = e.getParseResults().getContext().getSource().getServer();
            if (server != null) get(server).timeSet = true;
        }
    }

    /** Before the worlds tick: which dimensions have no awake player (sleepers wake inside the tick that skips the night). */
    public static void onServerTickPre(ServerTickEvent.Pre e) {
        WorldClock c = get(e.getServer());
        for (ServerLevel level : e.getServer().getAllLevels()) c.noneAwake.put(level.dimension(), noneAwake(level));
    }

    private static boolean noneAwake(ServerLevel level) {
        for (ServerPlayer p : level.players()) if (!p.isSleeping()) return false;
        return true;
    }

    /** After all worlds ticked (time of day advanced, sleepers woke): count this tick. */
    public static void onServerTick(ServerTickEvent.Post e) {
        MinecraftServer server = e.getServer();
        // real time is game time: a frozen tick manager stops the clock; what the day did meanwhile counts on the next tick
        if (!server.tickRateManager().runsNormally()) return;
        WorldClock c = get(server);
        long day = server.overworld().getDayTime();
        if (c.lastDay != Long.MIN_VALUE) {
            c.steps.step(day - c.lastDay, 1, c.timeSet);
            c.rateTicks += c.steps.rate();
            c.ticks += c.steps.rate() + c.steps.jump();
            if (c.steps.jump() > 0) c.distribute(server, c.steps.jump());
        }
        c.timeSet = false;
        c.lastDay = day;
        c.settle(server);
        long game = server.overworld().getGameTime();
        c.filled = c.recentAt == game - 1 ? c.filled + 1 : 0;
        c.recentAt = game;
        c.recent[(int) Math.floorMod(game, (long) c.recent.length)] = c.ticks;
        c.setDirty();
    }

    /** A jump of {@code hours} clock ticks: counted where nobody is awake, unseen elsewhere; fixed-time dimensions ignore it. */
    private void distribute(MinecraftServer server, double jump) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.dimensionType().hasFixedTime()) continue;
            Jumps j = jumps.computeIfAbsent(level.dimension().location().toString(), k -> new Jumps());
            if (noneAwake.getOrDefault(level.dimension(), noneAwake(level))) j.pending += jump;
            else j.unseen += jump;
        }
    }

    /** Counted jumps waiting for a front join their dimension's count once it has crossed. */
    private void settle(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            Jumps j = jumps.get(level.dimension().location().toString());
            if (j != null && j.pending > 0 && !hold.test(level)) {
                j.counted += j.pending;
                j.pending = 0;
            }
        }
    }

    /** Test seam: advance by world-clock ticks as if the time of day had moved by that much at the world's rate. */
    public void advance(double worldTicks) {
        ticks += Math.max(0, worldTicks);
        rateTicks += Math.max(0, worldTicks);
        setDirty();
    }

    /** Test seam: note who is awake in each dimension now, as before a tick ({@link #onServerTickPre}). */
    public void recordAwakeForTest(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) noneAwake.put(level.dimension(), noneAwake(level));
    }

    /** Test seam: a jump of {@code worldTicks} as the day's step would hand it out, then the waiting counted jumps settle. */
    public void jumpForTest(MinecraftServer server, double worldTicks) {
        distribute(server, worldTicks);
        settle(server);
        setDirty();
    }

    /** Test seam: the waiting counted jumps settle as at the end of a tick. */
    public void settleForTest(MinecraftServer server) {
        settle(server);
    }

    /** Test seam: put dimension {@code dim}'s jumps back to {counted, unseen, pending}. */
    public void restoreJumpsForTest(String dim, double[] j) {
        Jumps saved = jumps.computeIfAbsent(dim, k -> new Jumps());
        saved.counted = j[0];
        saved.unseen = j[1];
        saved.pending = j[2];
        setDirty();
    }

    static WorldClock load(CompoundTag tag, HolderLookup.Provider registries) {
        double ticks = tag.getDouble("ticks");
        // saved before the clock without jumps: it starts where the clock is (only its differences are read)
        WorldClock c = new WorldClock(ticks, tag.contains("rate_ticks") ? tag.getDouble("rate_ticks") : ticks);
        c.steps.load(tag);
        CompoundTag saved = tag.getCompound("jumps");
        for (String dim : saved.getAllKeys()) {
            CompoundTag t = saved.getCompound(dim);
            Jumps j = new Jumps();
            j.counted = t.getDouble("counted");
            j.unseen = t.getDouble("unseen");
            j.pending = t.getDouble("pending");
            c.jumps.put(dim, j);
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putDouble("ticks", ticks);
        tag.putDouble("rate_ticks", rateTicks);
        steps.save(tag);
        CompoundTag saved = new CompoundTag();
        jumps.forEach((dim, j) -> {
            CompoundTag t = new CompoundTag();
            t.putDouble("counted", j.counted);
            t.putDouble("unseen", j.unseen);
            t.putDouble("pending", j.pending);
            saved.put(dim, t);
        });
        tag.put("jumps", saved);
        return tag;
    }

    /** For tests: this clock as a restart would read it back. */
    public static WorldClock readBackForTest(MinecraftServer server) {
        return load(get(server).save(new CompoundTag(), server.overworld().registryAccess()), server.overworld().registryAccess());
    }

    /** For tests: the saved clock's readings — {ticks, rate ticks, pace}. */
    public double[] readingsForTest() {
        return new double[]{ticks, rateTicks, steps.pace};
    }

    /** For tests: the saved jumps of dimension {@code dim} — {counted, unseen, pending}; zeros if none. */
    public double[] jumpsForTest(String dim) {
        Jumps j = jumps.get(dim);
        return j == null ? new double[3] : new double[]{j.counted, j.unseen, j.pending};
    }
}
