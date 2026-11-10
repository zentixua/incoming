package ua.zentix.incoming.nuclear;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dimension nuclear events (saved in the world): detonations that have occurred — server and clients derive destruction,
 * cloud and fallout from them — and scheduled ICBM strikes (flight is a timer, not an entity: survives server restarts
 * and holds no chunks).
 */
public final class NuclearEvents extends SavedData {
    private static final String NAME = Incoming.MOD_ID + "_nuclear";
    /**
     * Detonations are forgotten 7 days after the burst on their own clock ({@link NukeClock}): fallout has weakened hundreds of
     * times by then. Destruction is remembered longer ({@link #past}): a chunk first loaded a month later is still destroyed.
     */
    public static final double FORGET_AFTER_S = 7 * 24 * 3600;
    /** Game ticks of the show ({@link NukeClock#SHOW_S}). */
    static final long SHOW_TICKS = (long) (NukeClock.SHOW_S * 20);

    /**
     * World-clock readings ({@link ua.zentix.incoming.util.WorldClock}) of a detonation: at the burst and at its show's end
     * (NaN — the show is not over). They set its clock ({@link NukeClock}) and when its bodies were exposed.
     */
    public record Anchor(int id, double burst, double showEnd) {
        static final Codec<Anchor> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(Anchor::id),
                Codec.DOUBLE.fieldOf("burst").forGetter(Anchor::burst),
                Codec.DOUBLE.optionalFieldOf("show_end", Double.NaN).forGetter(Anchor::showEnd)
        ).apply(i, Anchor::new));
    }

    /**
     * Scheduled strike: target, yield, burst type, when and where the missile launched.
     *
     * @param surface target is a ground position (from the map): surface detonation regardless of the launch height estimate
     */
    public record ScheduledStrike(int id, Vec3 target, double yieldKt, boolean airBurst, long launchTime, long detonateTime,
                                  Vec3 launchPos, Optional<UUID> owner, boolean surface, Optional<IcbmArrival> arrival) {
        public ScheduledStrike(int id, Vec3 target, double yieldKt, boolean airBurst, long launchTime, long detonateTime,
                               Vec3 launchPos, Optional<UUID> owner, boolean surface) {
            this(id, target, yieldKt, airBurst, launchTime, detonateTime, launchPos, owner, surface, Optional.empty());
        }

        public ScheduledStrike withArrival(IcbmArrival a) {
            return new ScheduledStrike(id, target, yieldKt, airBurst, launchTime, detonateTime, launchPos, owner, surface, Optional.of(a));
        }
        public static final Codec<ScheduledStrike> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(ScheduledStrike::id),
                Vec3.CODEC.fieldOf("target").forGetter(ScheduledStrike::target),
                // a strike launched before the 15 kt limit (old-version save) detonates with the limit applied
                Codec.DOUBLE.fieldOf("yield").xmap(y -> Math.min(y, ua.zentix.incoming.strike.Loadout.Nuke.MAX_YIELD), y -> y)
                        .forGetter(ScheduledStrike::yieldKt),
                Codec.BOOL.fieldOf("air_burst").forGetter(ScheduledStrike::airBurst),
                Codec.LONG.fieldOf("launch_time").forGetter(ScheduledStrike::launchTime),
                Codec.LONG.fieldOf("detonate_time").forGetter(ScheduledStrike::detonateTime),
                Vec3.CODEC.fieldOf("launch_pos").forGetter(ScheduledStrike::launchPos),
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(ScheduledStrike::owner),
                Codec.BOOL.optionalFieldOf("surface", false).forGetter(ScheduledStrike::surface),
                IcbmArrival.CODEC.optionalFieldOf("arrival").forGetter(ScheduledStrike::arrival)
        ).apply(i, ScheduledStrike::new));
    }

    public void commit(ScheduledStrike old, IcbmArrival arrival) {
        int index = scheduled.indexOf(old);
        if (index >= 0) {
            scheduled.set(index, old.withArrival(arrival));
            setDirty();
        }
    }

    private final List<Detonation> detonations = new ArrayList<>();
    /** Forgotten detonations: no fallout or visuals — only destruction in chunks loaded later. */
    private final List<Detonation> past = new ArrayList<>();
    private final List<ScheduledStrike> scheduled = new ArrayList<>();
    /** Unfinished craters: detonation number → number of chunks already dug. */
    private final Map<Integer, Integer> craters = new LinkedHashMap<>();
    private static final Codec<Map<Integer, Integer>> CRATERS_CODEC = Codec.unboundedMap(Codec.STRING.xmap(Integer::parseInt, String::valueOf), Codec.INT);
    /** Unfinished craters: detonation number → surveyed ground plane {@code {a, b, c}} ({@code CraterJob#ground}). */
    private final Map<Integer, List<Double>> craterGrounds = new LinkedHashMap<>();
    private static final Codec<Map<Integer, List<Double>>> GROUNDS_CODEC = Codec.unboundedMap(Codec.STRING.xmap(Integer::parseInt, String::valueOf), Codec.DOUBLE.listOf());
    /** Detonation number → its clock at the previous radiation second (not saved: a restart starts a fresh step). */
    private final Map<Integer, Double> steps = new java.util.HashMap<>();
    /** Detonation number → world-clock anchors ({@link Anchor}); detonations saved before them have none (estimated). */
    private final Map<Integer, Anchor> clocks = new LinkedHashMap<>();
    private int nextId = 1;
    /**
     * First detonation number whose flash, prompt radiation and wave reach bodies loaded later ({@code LateExposure}). A world
     * saved before bodies were marked ({@code NukeSeen}) cannot tell a hit body from a missed one, so its earlier detonations
     * stay as they were.
     */
    private int lateFrom = 1;

    public static NuclearEvents get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(NuclearEvents::new, NuclearEvents::load, null), NAME);
    }

    public int nextId() {
        setDirty();
        return nextId++;
    }

    /** Highest detonation number so far; 0 — none yet. */
    public int lastId() {
        int last = nextId - 1;
        for (Detonation d : detonations) last = Math.max(last, d.id());
        for (Detonation d : past) last = Math.max(last, d.id());
        return last;
    }

    /** First detonation number that reaches bodies loaded later ({@link #lateFrom}). */
    public int lateFrom() {
        return lateFrom;
    }

    public List<Detonation> detonations() {
        return detonations;
    }

    /** Forgotten detonations still destroying chunks as they load (see {@link #prune}). */
    public List<Detonation> past() {
        return past;
    }

    public boolean isPast(int id) {
        return past.stream().anyMatch(d -> d.id() == id);
    }

    public List<ScheduledStrike> scheduled() {
        return scheduled;
    }

    /** Detonation at world clock {@code clock} ({@link ua.zentix.incoming.util.WorldClock#now}). */
    public void add(Detonation d, double clock) {
        detonations.add(d);
        clocks.put(d.id(), new Anchor(d.id(), clock, Double.NaN));
        setDirty();
    }

    /**
     * Add detonation {@code d} in {@code level}: its burst clock is the world clock now, or, for a detonation dated in the past
     * (tests, imports), the world clock that many game ticks ago as under a vanilla day.
     */
    public void add(Detonation d, ServerLevel level) {
        double now = ua.zentix.incoming.util.WorldClock.now(level);
        add(d, now - Math.max(0, level.getGameTime() - d.gameTime()));
    }

    /**
     * World clock at detonation {@code d}'s burst; for a detonation saved before anchors, estimated from game time as if
     * the day had run at vanilla speed (the clock then advanced with game time).
     */
    public double burstClock(Detonation d, long gameNow, double clockNow) {
        Anchor a = clocks.get(d.id());
        return a != null ? a.burst() : Math.min(clockNow, clockNow - (gameNow - d.gameTime()));
    }

    /** World clock at the end of detonation {@code d}'s show; NaN — not over at game time {@code gameNow}. */
    public double showEndClock(Detonation d, long gameNow, double clockNow) {
        if (gameNow - d.gameTime() < SHOW_TICKS) return Double.NaN;
        Anchor a = clocks.get(d.id());
        if (a != null && !Double.isNaN(a.showEnd())) return a.showEnd();
        // not recorded (saved before anchors): as if the day had run at vanilla speed since
        double end = clockNow - (gameNow - d.gameTime() - SHOW_TICKS);
        return a != null ? Math.max(a.burst(), end) : end;
    }

    /**
     * Detonation {@code d}'s own clock ({@link NukeClock}), seconds since the burst, at game time {@code game} and world clock
     * {@code clock}: now, or a past moment after the show's end was recorded.
     */
    public double seconds(Detonation d, long game, double clock) {
        double real = (game - d.gameTime()) / 20.0;
        if (real <= NukeClock.SHOW_S) return real;
        return NukeClock.fromClock(real, clock - showEndClock(d, game, clock));
    }

    /** Same, now. */
    public double seconds(Detonation d, ServerLevel level) {
        return seconds(d, level.getGameTime(), ua.zentix.incoming.util.WorldClock.now(level));
    }

    /**
     * Detonation clock at the previous radiation second, and remember {@code seconds} for the next: the dose step. First
     * second (detonation, restart) — one real second back.
     */
    public double stepFrom(Detonation d, double seconds) {
        Double before = steps.put(d.id(), seconds);
        return before == null || before > seconds ? Math.max(0, seconds - 1) : before;
    }

    /** Record shows that ended by game time {@code gameNow} at world clock {@code clockNow}; returns them. */
    public List<Detonation> endShows(long gameNow, double clockNow) {
        List<Detonation> ended = new ArrayList<>();
        for (Detonation d : detonations) {
            if (gameNow - d.gameTime() < SHOW_TICKS) continue;
            Anchor a = clocks.get(d.id());
            if (a != null && !Double.isNaN(a.showEnd())) continue;
            // recorded on the tick it ends; a detonation saved before anchors, at the estimate
            double end = a == null ? showEndClock(d, gameNow, clockNow) : Math.max(a.burst(), clockNow - (gameNow - d.gameTime() - SHOW_TICKS));
            clocks.put(d.id(), new Anchor(d.id(), a != null ? a.burst() : burstClock(d, gameNow, clockNow), end));
            ended.add(d);
            setDirty();
        }
        return ended;
    }

    public void schedule(ScheduledStrike s) {
        scheduled.add(s);
        setDirty();
    }

    public void unschedule(ScheduledStrike s) {
        if (scheduled.remove(s)) setDirty();
    }

    public Map<Integer, Integer> craters() {
        return craters;
    }

    /** Crater progress: chunks dug; {@code done} — fully dug. */
    public void craterProgress(int detonation, int chunks, boolean done) {
        Integer prev = done ? craters.remove(detonation) : craters.put(detonation, chunks);
        if (done) craterGrounds.remove(detonation);
        if (prev == null || prev != chunks || done) setDirty();
    }

    /** Surveyed ground plane of an unfinished crater, null — not surveyed (or dug before the survey existed). */
    public @org.jetbrains.annotations.Nullable double[] craterGround(int detonation) {
        List<Double> p = craterGrounds.get(detonation);
        return p == null || p.size() != 3 ? null : new double[] {p.get(0), p.get(1), p.get(2)};
    }

    /** Remember a crater's ground plane so a restart digs on from the same ground; only finite planes. */
    public void craterGround(int detonation, double[] plane) {
        if (plane.length != 3 || !Double.isFinite(plane[0]) || !Double.isFinite(plane[1]) || !Double.isFinite(plane[2])) return;
        List<Double> p = List.of(plane[0], plane[1], plane[2]);
        if (!p.equals(craterGrounds.put(detonation, p))) setDirty();
    }

    /**
     * Cancel: scheduled strikes canceled, detonations forgotten — no more fallout or destruction in chunks loaded later.
     * Existing destruction remains. Detonation numbers keep increasing (chunk marks remain valid).
     */
    public int clear() {
        int n = scheduled.size() + detonations.size();
        scheduled.clear();
        detonations.clear();
        past.clear();
        craters.clear();
        craterGrounds.clear();
        clocks.clear();
        steps.clear();
        setDirty();
        return n;
    }

    /** Forget old detonations: fallout and effects ended; destruction remains in {@link #past}. */
    public void prune(long gameNow, double clockNow) {
        List<Detonation> faded = detonations.stream().filter(d -> seconds(d, gameNow, clockNow) > FORGET_AFTER_S).toList();
        if (!faded.isEmpty()) {
            detonations.removeAll(faded);
            past.addAll(faded);
            setDirty();
        }
        if (clocks.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id))) setDirty();
        steps.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id));
        if (craters.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id))) setDirty();
        if (craterGrounds.keySet().removeIf(id -> !craters.containsKey(id))) setDirty();
    }

    private static NuclearEvents load(CompoundTag tag, HolderLookup.Provider registries) {
        NuclearEvents e = new NuclearEvents();
        e.nextId = Math.max(1, tag.getInt("next_id"));
        e.lateFrom = tag.contains("late_from") ? tag.getInt("late_from") : e.nextId;
        Detonation.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("detonations")).resultOrPartial(Incoming.LOG::error).ifPresent(e.detonations::addAll);
        if (tag.contains("past")) Detonation.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("past")).resultOrPartial(Incoming.LOG::error).ifPresent(e.past::addAll);
        ScheduledStrike.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("scheduled")).resultOrPartial(Incoming.LOG::error).ifPresent(e.scheduled::addAll);
        if (tag.contains("craters")) CRATERS_CODEC.parse(NbtOps.INSTANCE, tag.get("craters")).resultOrPartial(Incoming.LOG::error).ifPresent(e.craters::putAll);
        if (tag.contains("crater_grounds")) GROUNDS_CODEC.parse(NbtOps.INSTANCE, tag.get("crater_grounds")).resultOrPartial(Incoming.LOG::error).ifPresent(e.craterGrounds::putAll);
        if (tag.contains("clocks")) Anchor.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("clocks")).resultOrPartial(Incoming.LOG::error)
                .ifPresent(l -> l.forEach(a -> e.clocks.put(a.id(), a)));
        return e;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("next_id", nextId);
        tag.putInt("late_from", lateFrom);
        Detonation.CODEC.listOf().encodeStart(NbtOps.INSTANCE, detonations).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("detonations", t));
        Detonation.CODEC.listOf().encodeStart(NbtOps.INSTANCE, past).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("past", t));
        ScheduledStrike.CODEC.listOf().encodeStart(NbtOps.INSTANCE, scheduled).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("scheduled", t));
        CRATERS_CODEC.encodeStart(NbtOps.INSTANCE, craters).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("craters", t));
        GROUNDS_CODEC.encodeStart(NbtOps.INSTANCE, craterGrounds).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("crater_grounds", t));
        Anchor.CODEC.listOf().encodeStart(NbtOps.INSTANCE, List.copyOf(clocks.values())).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("clocks", t));
        return tag;
    }
}
