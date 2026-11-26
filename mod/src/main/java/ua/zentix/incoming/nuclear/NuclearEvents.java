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
import ua.zentix.incoming.nuclear.material.MaterialBirth;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.util.StrikeClock;
import ua.zentix.incoming.util.StrikeHistory;
import ua.zentix.incoming.util.WorldClock;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static ua.zentix.incoming.nuclear.NukeClock.SHOW_TICKS;

/**
 * Dimension nuclear events (saved in the world): detonations that have occurred — server and clients derive destruction,
 * cloud and fallout from them — and scheduled ICBM strikes (flight is a timer, not an entity: survives server restarts
 * and holds no chunks).
 */
public final class NuclearEvents extends SavedData {
    private static final String NAME = Incoming.MOD_ID + "_nuclear";
    /**
     * Detonations are forgotten 7 days after the burst on their strike clock ({@link StrikeClock}): fallout has weakened hundreds of
     * times by then. Destruction is remembered longer ({@link #past}): a chunk first loaded a month later is still destroyed.
     */
    public static final double FORGET_AFTER_S = 7 * 24 * 3600;
    /**
     * A detonation's clock anchors: the world clock with every jump ({@link WorldClock#now}) at the burst and at its show's
     * end (NaN — the show is not over), which date its bodies' exposure, and its two ages ({@link StrikeClock}: counted
     * jumps at the burst; real age, clock without jumps and counted jumps at the show's end; clock without jumps at the
     * blend's end). Saved before the two ages: no jumps, a show that ended at {@link NukeClock#SHOW_TICKS} with the clock
     * without jumps where the clock was.
     */
    public record Anchor(int id, double burst, double showEnd, StrikeClock clock) {
        public Anchor {
            if (!clock.ended() && !Double.isNaN(showEnd)) clock = new StrikeClock(clock.jumpsAtStrike(), SHOW_TICKS, showEnd, clock.jumpsAtStrike(), Double.NaN);
            else if (clock.ended() && Double.isNaN(clock.rateAtEnd())) clock = new StrikeClock(clock.jumpsAtStrike(), clock.endAge(), showEnd, clock.jumpsAtEnd(), clock.rateAtRamp());
        }

        static final Codec<Anchor> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("id").forGetter(Anchor::id),
                Codec.DOUBLE.fieldOf("burst").forGetter(Anchor::burst),
                Codec.DOUBLE.optionalFieldOf("show_end", Double.NaN).forGetter(Anchor::showEnd),
                Codec.DOUBLE.optionalFieldOf("jumps", 0.0).forGetter(a -> a.clock.jumpsAtStrike()),
                Codec.LONG.optionalFieldOf("end_age", StrikeClock.RUNNING).forGetter(a -> a.clock.endAge()),
                Codec.DOUBLE.optionalFieldOf("end_rate", Double.NaN).forGetter(a -> a.clock.rateAtEnd()),
                Codec.DOUBLE.optionalFieldOf("end_jumps", 0.0).forGetter(a -> a.clock.jumpsAtEnd()),
                Codec.DOUBLE.optionalFieldOf("ramp_rate", Double.NaN).forGetter(a -> a.clock.rateAtRamp())
        ).apply(i, (id, burst, showEnd, jumps, endAge, endRate, endJumps, ramp) ->
                new Anchor(id, burst, showEnd, new StrikeClock(jumps, endAge, endRate, endAge < 0 ? Double.NaN : endJumps, ramp))));
    }

    /**
     * Scheduled strike: target, yield, burst type, when and where the missile launched.
     *
     * @param surface target is a ground position (from the map): surface detonation regardless of the launch height estimate
     * @param round   the order round this strike is ({@code strike.Orders}): the ICBM entity is only its visible part;
     *                absent for a strike outside the order lifecycle or saved before order events
     */
    public record ScheduledStrike(int id, Vec3 target, double yieldKt, boolean airBurst, long launchTime, long detonateTime,
                                  Vec3 launchPos, Optional<UUID> owner, boolean surface, Optional<IcbmArrival> arrival, Optional<RoundRef> round) {
        public ScheduledStrike(int id, Vec3 target, double yieldKt, boolean airBurst, long launchTime, long detonateTime,
                               Vec3 launchPos, Optional<UUID> owner, boolean surface) {
            this(id, target, yieldKt, airBurst, launchTime, detonateTime, launchPos, owner, surface, Optional.empty(), Optional.empty());
        }

        public ScheduledStrike(int id, Vec3 target, double yieldKt, boolean airBurst, long launchTime, long detonateTime,
                               Vec3 launchPos, Optional<UUID> owner, boolean surface, Optional<IcbmArrival> arrival) {
            this(id, target, yieldKt, airBurst, launchTime, detonateTime, launchPos, owner, surface, arrival, Optional.empty());
        }

        public ScheduledStrike withArrival(IcbmArrival a) {
            return new ScheduledStrike(id, target, yieldKt, airBurst, launchTime, detonateTime, launchPos, owner, surface, Optional.of(a), round);
        }

        public ScheduledStrike withRound(@org.jetbrains.annotations.Nullable RoundRef r) {
            return new ScheduledStrike(id, target, yieldKt, airBurst, launchTime, detonateTime, launchPos, owner, surface, arrival, Optional.ofNullable(r));
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
                IcbmArrival.CODEC.optionalFieldOf("arrival").forGetter(ScheduledStrike::arrival),
                RoundRef.CODEC.optionalFieldOf("round").forGetter(ScheduledStrike::round)
        ).apply(i, ScheduledStrike::new));
    }

    public void commit(ScheduledStrike old, IcbmArrival arrival) {
        int index = scheduled.indexOf(old);
        if (index >= 0) {
            scheduled.set(index, old.withArrival(arrival));
            setDirty();
        }
    }

    /**
     * A nuclear round's impact ({@code strike.OrderStatus}): detonation {@code detonation} of order round {@code round},
     * for {@code owner}. Kept while its blast front travels and hits what it caught; ruins that follow the front, fires and
     * fallout are consequences in the world, not the round's work.
     */
    public record Impact(int detonation, RoundRef round, Optional<UUID> owner) {
        static final Codec<Impact> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("detonation").forGetter(Impact::detonation),
                RoundRef.CODEC.fieldOf("round").forGetter(Impact::round),
                UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(Impact::owner)
        ).apply(i, Impact::new));
    }

    private final List<Detonation> detonations = new ArrayList<>();
    /** Nuclear rounds whose impact work runs, by detonation number (saved: a restart mid-front keeps them). */
    private final Map<Integer, Impact> impacts = new LinkedHashMap<>();
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
    /** Detonation number → its world-clock history ({@link StrikeHistory}): its strike clock at any past moment. */
    private final Map<Integer, StrikeHistory> histories = new LinkedHashMap<>();
    private long materialEpoch;
    public long materialEpoch() { return materialEpoch; }
    public java.util.Collection<MaterialBirth> currentMaterials() { return materials.values(); }
    private final Map<Integer, MaterialBirth> materials = new LinkedHashMap<>();
    private final java.util.ArrayDeque<MaterialBirth> encoding = new java.util.ArrayDeque<>();
    /** Start at most one new background encoding per tick even when seventeen source maps are sealed together. */
    public void encodeMaterial() {
        MaterialBirth birth = encoding.peek();
        if (birth != null && birth.encoded() != null) {
            encoding.remove();
            setDirty(); // a save during encoding wrote sealed inputs; the next one can store compact cells
        }
    }
    public MaterialBirth material(int id) { return materials.get(id); }
    public List<MaterialBirth> visibleMaterials() {
        return detonations.stream().map(d -> materials.get(d.id())).filter(java.util.Objects::nonNull).toList();
    }
    public void material(int id, MaterialBirth birth) {
        materialEpoch++;
        materials.put(id, birth);
        if (!birth.surveying()) encoding.add(birth);
        setDirty();
    }
    public void forgetMaterial(int id) {
        if (materials.remove(id) != null) { materialEpoch++; setDirty(); }
    }
    public MaterialBirth moveMaterial(int strike, int detonation) {
        MaterialBirth birth = materials.remove(strike);
        if (birth != null) {
            materialEpoch++;
            materials.put(detonation, birth);
            setDirty();
        }
        return birth;
    }
    Map<Integer, UUID> materialIds() {
        Map<Integer, UUID> ids = new LinkedHashMap<>();
        for (Detonation d : detonations) {
            MaterialBirth birth = material(d.id());
            if (birth != null) ids.put(d.id(), birth.id);
        }
        return ids;
    }
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

    /** For tests: this data as a restart would read it back — saved and loaded into a detached copy (the live data stays). */
    public static NuclearEvents readBackForTest(ServerLevel level) {
        return load(get(level).save(new CompoundTag(), level.registryAccess()), level.registryAccess());
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

    /** Detonation at world clock {@code clock} ({@link WorldClock#now}), with no jumps counted and the clock without jumps there. */
    public void add(Detonation d, double clock) {
        add(d, clock, clock, 0, 0);
    }

    private void add(Detonation d, double clock, double rate, double jumps, long age) {
        detonations.add(d);
        clocks.put(d.id(), new Anchor(d.id(), clock, Double.NaN, StrikeClock.start(jumps)));
        StrikeHistory h = new StrikeHistory();
        h.sample(0, rate, jumps, 0, true);
        if (age > 0) h.sample(age, rate + age, jumps, age / 20.0, true);
        histories.put(d.id(), h);
        setDirty();
    }

    /**
     * Add detonation {@code d} in {@code level}: its burst clock is the world clock now, or, for a detonation dated in the past
     * (tests, imports), the world clock that many game ticks ago as under a vanilla day.
     */
    public void add(Detonation d, ServerLevel level) {
        long age = Math.max(0, level.getGameTime() - d.gameTime());
        add(d, WorldClock.now(level) - age, WorldClock.rateClock(level) - age, WorldClock.counted(level), age);
    }

    /**
     * World clock at detonation {@code d}'s burst; for a detonation saved before anchors, estimated from game time as if
     * the day had run at vanilla speed (the clock then advanced with game time).
     */
    public double burstClock(Detonation d, long gameNow, double clockNow) {
        Anchor a = clocks.get(d.id());
        return a != null ? a.burst() : Math.min(clockNow, clockNow - (gameNow - d.gameTime()));
    }

    /**
     * Detonation {@code d}'s two ages ({@link StrikeClock}); for a detonation saved before anchors, as if the day had run at
     * vanilla speed since, with no jumps.
     */
    public StrikeClock clock(Detonation d, ServerLevel level) {
        Anchor a = clocks.get(d.id());
        if (a != null) return a.clock();
        long age = level.getGameTime() - d.gameTime();
        return age < SHOW_TICKS ? StrikeClock.start(0) : StrikeClock.start(0).end(SHOW_TICKS, WorldClock.rateClock(level) - (age - SHOW_TICKS), 0);
    }

    /** Detonation {@code d}'s show is over (its length, or a jump counted while nobody in the dimension was awake). */
    public boolean showEnded(Detonation d, ServerLevel level) {
        return clock(d, level).ended();
    }

    /** Detonation {@code d}'s strike clock S now, seconds since the burst ({@link StrikeClock#strike}): what stays in place. */
    public double seconds(Detonation d, ServerLevel level) {
        return clock(d, level).strike(level.getGameTime() - d.gameTime(), WorldClock.rateClock(level), WorldClock.counted(level));
    }

    /** Detonation {@code d}'s motion age M now, seconds since the burst ({@link StrikeClock#motion}): what is in the air. */
    public double motion(Detonation d, ServerLevel level) {
        return clock(d, level).motion(level.getGameTime() - d.gameTime(), WorldClock.counted(level));
    }

    /**
     * Detonation {@code d}'s strike clock S at a past game time {@code game} (now or earlier), seconds since the burst: from
     * its world-clock history ({@link StrikeHistory}).
     */
    public double secondsAt(Detonation d, ServerLevel level, long game) {
        double[] r = readingAt(d, level, game);
        return clock(d, level).strike(game - d.gameTime(), r[0], r[1]);
    }

    /** Detonation {@code d}'s motion age M at a past game time {@code game}, seconds since the burst. */
    public double motionAt(Detonation d, ServerLevel level, long game) {
        return clock(d, level).motion(game - d.gameTime(), readingAt(d, level, game)[1]);
    }

    /** {clock without jumps, counted jumps} at game time {@code game}: history, or as a vanilla day without it. */
    private double[] readingAt(Detonation d, ServerLevel level, long game) {
        long now = level.getGameTime();
        double rate = WorldClock.rateClock(level), counted = WorldClock.counted(level);
        StrikeHistory h = histories.get(d.id());
        if (h == null || h.size() == 0) return game >= now ? new double[]{rate, counted} : new double[]{rate - (now - game), counted};
        return h.at(game - d.gameTime(), now - d.gameTime(), rate, counted);
    }

    /** Detonation {@code d}'s world-clock history; null — none (saved before it). */
    public @org.jetbrains.annotations.Nullable StrikeHistory history(Detonation d) {
        return histories.get(d.id());
    }

    /**
     * Detonation clock at the previous radiation second, and remember {@code seconds} for the next: the dose step. First
     * second (detonation, restart) — one real second back.
     */
    public double stepFrom(Detonation d, double seconds) {
        Double before = steps.put(d.id(), seconds);
        return before == null || before > seconds ? Math.max(0, seconds - 1) : before;
    }

    /**
     * The clocks' tick in {@code level}: record shows that ended (their length passed, or a jump was counted since the
     * burst), the blend's end and the history samples; returns the detonations whose show ended now.
     */
    public List<Detonation> endShows(ServerLevel level) {
        List<Detonation> ended = new ArrayList<>();
        long gameNow = level.getGameTime();
        double clockNow = WorldClock.now(level), rate = WorldClock.rateClock(level), counted = WorldClock.counted(level);
        for (Detonation d : detonations) {
            long age = gameNow - d.gameTime();
            Anchor a = clocks.get(d.id());
            if (a == null) {
                // saved before anchors: estimated as a vanilla day, recorded so it stays put
                a = new Anchor(d.id(), burstClock(d, gameNow, clockNow), Double.NaN, clock(d, level));
                if (a.clock().ended()) a = new Anchor(d.id(), a.burst(), clockNow - (age - SHOW_TICKS), a.clock());
                clocks.put(d.id(), a);
                setDirty();
            }
            StrikeClock c = a.clock();
            boolean force = false;
            if (!c.ended() && (age >= SHOW_TICKS || counted > c.jumpsAtStrike())) {
                // on the tick it ends; a jump ends it at once, its hours already in the motion age
                long end = Math.min(age, SHOW_TICKS);
                double late = age - end;
                c = c.end(end, rate - late, counted);
                a = new Anchor(d.id(), a.burst(), Math.max(a.burst(), clockNow - late), c);
                clocks.put(d.id(), a);
                ended.add(d);
                force = true;
                setDirty();
            }
            if (c.rampDue(age)) {
                c = c.ramp(age, rate);
                clocks.put(d.id(), new Anchor(d.id(), a.burst(), a.showEnd(), c));
                force = true;
                setDirty();
            }
            StrikeHistory h = histories.get(d.id());
            if (h != null) {
                int before = h.size();
                h.sample(age, rate, counted, c.motion(age, counted), force);
                if (h.size() != before) setDirty();
            }
        }
        return ended;
    }

    /** The anchors of this dimension's detonations, for clients ({@code S2C.NukeClock}). */
    public List<Anchor> anchors() {
        List<Anchor> out = new ArrayList<>();
        for (Detonation d : detonations) {
            Anchor a = clocks.get(d.id());
            if (a != null) out.add(a);
        }
        return out;
    }

    public void schedule(ScheduledStrike s) {
        scheduled.add(s);
        setDirty();
    }

    public void unschedule(ScheduledStrike s) {
        if (scheduled.remove(s)) setDirty();
    }

    /** A strike called off before its burst: unscheduled, and the ground material surveyed for it is forgotten. */
    public void cancel(ScheduledStrike s) {
        unschedule(s);
        forgetMaterial(s.id());
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

    /** Detonation {@code detonation} is round {@code round}'s impact ({@link Impact}). */
    public void impact(int detonation, RoundRef round, @org.jetbrains.annotations.Nullable UUID owner) {
        impacts.put(detonation, new Impact(detonation, round, Optional.ofNullable(owner)));
        setDirty();
    }

    /** Detonation {@code detonation}'s round impact, or null (no round, or its work is over). */
    @org.jetbrains.annotations.Nullable
    public Impact impact(int detonation) {
        return impacts.get(detonation);
    }

    /** Nuclear rounds whose impact work runs. */
    public java.util.Collection<Impact> impacts() {
        return java.util.Collections.unmodifiableCollection(impacts.values());
    }

    /** Forget impacts whose work is over: {@code busy} tells, by detonation, whether it still runs (a forgotten detonation's does not). */
    public void settleImpacts(java.util.function.Predicate<Detonation> busy) {
        if (impacts.isEmpty()) return;
        if (impacts.values().removeIf(i -> detonations.stream().filter(d -> d.id() == i.detonation()).findFirst().map(d -> !busy.test(d)).orElse(true))) {
            setDirty();
        }
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
        impacts.clear();
        past.clear();
        materialEpoch++;
        materials.clear();
        encoding.clear();
        craters.clear();
        craterGrounds.clear();
        clocks.clear();
        histories.clear();
        steps.clear();
        setDirty();
        return n;
    }

    /** Forget old detonations: fallout and effects ended; destruction remains in {@link #past}. */
    public void prune(ServerLevel level) {
        prune(d -> seconds(d, level));
    }

    /** Same, with each detonation's strike clock now, seconds ({@link #seconds}). */
    public void prune(java.util.function.ToDoubleFunction<Detonation> seconds) {
        List<Detonation> faded = detonations.stream().filter(d -> seconds.applyAsDouble(d) > FORGET_AFTER_S).toList();
        if (!faded.isEmpty()) {
            detonations.removeAll(faded);
            past.addAll(faded);
            faded.forEach(d -> forgetMaterial(d.id()));
            setDirty();
        }
        // a material whose strike was called off by any path, not only cancel(): nothing will ever read it
        if (materials.keySet().removeIf(id -> scheduled.stream().noneMatch(s -> s.id() == id)
                && detonations.stream().noneMatch(d -> d.id() == id))) { materialEpoch++; setDirty(); }
        if (clocks.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id))) setDirty();
        if (histories.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id))) setDirty();
        steps.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id));
        if (craters.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id))) setDirty();
        if (craterGrounds.keySet().removeIf(id -> !craters.containsKey(id))) setDirty();
    }

    public static NuclearEvents load(CompoundTag tag, HolderLookup.Provider registries) {
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
        CompoundTag hist = tag.getCompound("clock_history");
        for (String key : hist.getAllKeys()) {
            try {
                e.histories.put(Integer.parseInt(key), StrikeHistory.load(hist.getCompound(key)));
            } catch (NumberFormatException ex) {
                Incoming.LOG.warn("Ignoring clock history with invalid detonation number {}", key);
            }
        }
        // saved before order status: detonations from then have no impact work
        if (tag.contains("impacts")) Impact.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("impacts")).resultOrPartial(Incoming.LOG::error)
                .ifPresent(l -> l.forEach(i -> e.impacts.put(i.detonation(), i)));
        var saved = tag.getList("materials", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag entry = saved.getCompound(i);
            try {
                MaterialBirth birth = MaterialBirth.load(entry);
                e.materials.put(entry.getInt("source"), birth);
                if (!entry.contains("map") && !birth.surveying()) e.encoding.add(birth);
            } catch (RuntimeException ex) {
                Incoming.LOG.warn("Ignoring invalid ground material entry {} for source {}", i, entry.getInt("source"), ex);
            }
        }
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
        CompoundTag hist = new CompoundTag();
        histories.forEach((id, h) -> hist.put(String.valueOf(id), h.save()));
        tag.put("clock_history", hist);
        Impact.CODEC.listOf().encodeStart(NbtOps.INSTANCE, List.copyOf(impacts.values())).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("impacts", t));
        if (!materials.isEmpty()) {
            var list = new net.minecraft.nbt.ListTag();
            materials.forEach((id, birth) -> {
                try {
                    CompoundTag entry = birth.save();
                    entry.putInt("source", id);
                    list.add(entry);
                } catch (RuntimeException ex) {
                    Incoming.LOG.warn("Cannot save ground material for source {}", id, ex);
                }
            });
            tag.put("materials", list);
        }
        return tag;
    }
}
