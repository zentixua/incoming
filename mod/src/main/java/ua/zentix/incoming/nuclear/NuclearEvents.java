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
     * Detonations are forgotten after 7 game days: fallout has weakened hundreds of times by then. Destruction is remembered
     * longer ({@link #past}): a chunk first loaded a month later is still destroyed.
     */
    public static final long FORGET_AFTER = 7 * 24_000L;

    /**
     * Scheduled strike: target, yield, burst type, when and where the missile launched.
     *
     * @param surface target is a ground position (from the map): surface detonation regardless of the launch height estimate
     */
    public record ScheduledStrike(int id, Vec3 target, double yieldKt, boolean airBurst, long launchTime, long detonateTime,
                                  Vec3 launchPos, Optional<UUID> owner, boolean surface) {
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
                Codec.BOOL.optionalFieldOf("surface", false).forGetter(ScheduledStrike::surface)
        ).apply(i, ScheduledStrike::new));
    }

    private final List<Detonation> detonations = new ArrayList<>();
    /** Forgotten detonations: no fallout or visuals — only destruction in chunks loaded later. */
    private final List<Detonation> past = new ArrayList<>();
    private final List<ScheduledStrike> scheduled = new ArrayList<>();
    /** Unfinished craters: detonation number → number of chunks already dug. */
    private final Map<Integer, Integer> craters = new LinkedHashMap<>();
    private static final Codec<Map<Integer, Integer>> CRATERS_CODEC = Codec.unboundedMap(Codec.STRING.xmap(Integer::parseInt, String::valueOf), Codec.INT);
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

    public void add(Detonation d) {
        detonations.add(d);
        setDirty();
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
        if (prev == null || prev != chunks || done) setDirty();
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
        setDirty();
        return n;
    }

    /** Forget old detonations: fallout and effects ended; destruction remains in {@link #past}. */
    public void prune(long now) {
        List<Detonation> faded = detonations.stream().filter(d -> now - d.gameTime() > FORGET_AFTER).toList();
        if (!faded.isEmpty()) {
            detonations.removeAll(faded);
            past.addAll(faded);
            setDirty();
        }
        if (craters.keySet().removeIf(id -> detonations.stream().noneMatch(d -> d.id() == id))) setDirty();
    }

    private static NuclearEvents load(CompoundTag tag, HolderLookup.Provider registries) {
        NuclearEvents e = new NuclearEvents();
        e.nextId = Math.max(1, tag.getInt("next_id"));
        e.lateFrom = tag.contains("late_from") ? tag.getInt("late_from") : e.nextId;
        Detonation.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("detonations")).resultOrPartial(Incoming.LOG::error).ifPresent(e.detonations::addAll);
        if (tag.contains("past")) Detonation.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("past")).resultOrPartial(Incoming.LOG::error).ifPresent(e.past::addAll);
        ScheduledStrike.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("scheduled")).resultOrPartial(Incoming.LOG::error).ifPresent(e.scheduled::addAll);
        if (tag.contains("craters")) CRATERS_CODEC.parse(NbtOps.INSTANCE, tag.get("craters")).resultOrPartial(Incoming.LOG::error).ifPresent(e.craters::putAll);
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
        return tag;
    }
}
