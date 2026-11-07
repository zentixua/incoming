package ua.zentix.incoming.grid;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * Dimension grid (saved in the world): nodes and outages. Which district is dark is a function of outages and time
 * ({@link #dark}); loaded chunks are brought into line with it ({@link BlackoutWorld}, {@link ChunkSaves}).
 */
public final class PowerGrid extends SavedData {
    private static final String NAME = Incoming.MOD_ID + "_grid";
    private static final Codec<Map<Integer, Long>> PROGRESS_CODEC =
            Codec.unboundedMap(Codec.STRING.xmap(Integer::parseInt, String::valueOf), Codec.LONG);

    private final Map<Integer, Node> nodes = new LinkedHashMap<>();
    private final List<Outage> outages = new ArrayList<>();
    /**
     * How far (game time) the outage cascade has been issued; {@code Long.MAX_VALUE} — whole area processed,
     * not repeated after restart.
     */
    private final Map<Integer, Long> darkSwept = new LinkedHashMap<>();
    /** The same for power restoration. */
    private final Map<Integer, Long> lightSwept = new LinkedHashMap<>();
    /**
     * Lamps placed in a dark district (chunk → block position → game time placed): their own supply, not the grid's
     * ({@link Blackouts#onBlockPlaced}). Kept here, not in the chunk, so a chunk loading from disk and a distant LOD copy
     * know them before the chunk enters the world; forgotten once no outage covers the chunk.
     */
    private final Long2ObjectOpenHashMap<Long2LongOpenHashMap> own = new Long2ObjectOpenHashMap<>();
    private int nextId = 1;

    public static PowerGrid get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(PowerGrid::new, PowerGrid::load, null), NAME);
    }

    // ---------------------------------------------------------------- nodes

    public Iterable<Node> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    @Nullable
    public Node node(int id) {
        return nodes.get(id);
    }

    @Nullable
    public Node nodeAt(BlockPos pos) {
        for (Node n : nodes.values()) {
            if (n.pos().equals(pos)) return n;
        }
        return null;
    }

    public Node addNode(BlockPos pos, double radius, boolean block) {
        Node n = new Node(nextId++, pos.immutable(), radius, block);
        nodes.put(n.id(), n);
        setDirty();
        return n;
    }

    @Nullable
    public Node removeNode(int id) {
        Node n = nodes.remove(id);
        if (n != null) setDirty();
        return n;
    }

    /** Outage caused by a node failure whose power restoration has not started yet. */
    public Optional<Outage> downOutage(int node, long now) {
        return outages.stream().filter(o -> o.node() == node && now < o.restoreAt()).findFirst();
    }

    // ---------------------------------------------------------------- outages

    public List<Outage> outages() {
        return Collections.unmodifiableList(outages);
    }

    public Outage addOutage(double x, double z, double radius, long start, double speed, long restoreAt, int restoreSpread, int node) {
        return addOutage(new Outage(0, x, z, radius, start, speed, restoreAt, restoreSpread, node));
    }

    /** Add an outage like {@code o}, under a new number. */
    public Outage addOutage(Outage o) {
        o = new Outage(nextId++, o.x(), o.z(), o.radius(), o.start(), o.speed(), o.restoreAt(), o.restoreSpread(), o.node(), o.dead(), o.damaged());
        outages.add(o);
        setDirty();
        return o;
    }

    /** Replace an outage (same number) — for example, assign power restoration. */
    public void replace(Outage o) {
        for (int i = 0; i < outages.size(); i++) {
            if (outages.get(i).id() == o.id()) {
                outages.set(i, o);
                lightSwept.remove(o.id());
                setDirty();
                return;
            }
        }
    }

    /** The chunk's district should be dark at {@code now}. */
    public boolean dark(int chunkX, int chunkZ, long now) {
        if (outages.isEmpty()) return false;
        long district = Districts.of(chunkX, chunkZ);
        for (Outage o : outages) {
            if (o.dark(district, now)) return true;
        }
        return false;
    }

    /**
     * When the chunk's district went dark, from the outages keeping it dark at {@code now} (the latest arrival);
     * {@code Long.MIN_VALUE} — the district has power.
     */
    public long darkSince(int chunkX, int chunkZ, long now) {
        long since = Long.MIN_VALUE;
        if (outages.isEmpty()) return since;
        long district = Districts.of(chunkX, chunkZ);
        for (Outage o : outages) {
            if (o.dark(district, now)) since = Math.max(since, o.darkAt(district));
        }
        return since;
    }

    /** The chunk's district is dark at {@code now} where a nuclear blast destroyed the grid: power never comes back by itself. */
    public boolean destroyed(int chunkX, int chunkZ, long now) {
        if (outages.isEmpty()) return false;
        long district = Districts.of(chunkX, chunkZ);
        for (Outage o : outages) {
            if (o.byDamage() && o.lightAt(district) == Outage.NEVER && o.dark(district, now)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- lamps on their own supply

    /** A lamp placed at {@code pos} at {@code now} in a dark district: its own supply, the grid leaves it lit. */
    public void placeOwn(BlockPos pos, long now) {
        own.computeIfAbsent(ChunkPos.asLong(pos), k -> new Long2LongOpenHashMap()).put(pos.asLong(), now);
        setDirty();
    }

    /**
     * Which lamps of a chunk stay lit in its dark district (block positions as {@code BlockPos.asLong}): those placed after
     * the district went dark ({@link #placeOwn}). Null — none (no such lamps, or the district has power).
     */
    @Nullable
    public LongPredicate keepsLit(int chunkX, int chunkZ, long now) {
        Long2LongOpenHashMap lamps = own.get(ChunkPos.asLong(chunkX, chunkZ));
        if (lamps == null) return null;
        long since = darkSince(chunkX, chunkZ, now);
        if (since == Long.MIN_VALUE) return null;
        return pos -> lamps.getOrDefault(pos, Long.MIN_VALUE) >= since;
    }

    /** The chunk's district is affected by at least one outage (dark now, due to go dark, or still being restored). */
    public boolean covered(int chunkX, int chunkZ) {
        if (outages.isEmpty()) return false;
        long district = Districts.of(chunkX, chunkZ);
        for (Outage o : outages) {
            if (o.covers(district)) return true;
        }
        return false;
    }

    /**
     * Forget outages with power restored everywhere: deadline passed and restoration cascade processed the whole area
     * (otherwise loaded chunks not reached by it would stay dark).
     */
    public void prune(long now) {
        List<Outage> over = outages.stream().filter(o -> o.over(now) && swept(o.id(), true) == Long.MAX_VALUE).toList();
        if (!over.isEmpty()) {
            outages.removeAll(over);
            over.forEach(o -> {
                darkSwept.remove(o.id());
                lightSwept.remove(o.id());
            });
            setDirty();
        }
        // own-supply lamps matter only while an outage covers their chunk
        if (!own.isEmpty() && own.keySet().removeIf(c -> !covered(ChunkPos.getX(c), ChunkPos.getZ(c)))) setDirty();
    }

    public long swept(int outage, boolean restore) {
        return (restore ? lightSwept : darkSwept).getOrDefault(outage, Long.MIN_VALUE);
    }

    public void swept(int outage, boolean restore, long upTo) {
        Long prev = (restore ? lightSwept : darkSwept).put(outage, upTo);
        if (prev == null || prev != upTo) setDirty();
    }

    // ---------------------------------------------------------------- persistence

    static PowerGrid load(CompoundTag tag, HolderLookup.Provider registries) {
        PowerGrid g = new PowerGrid();
        g.nextId = Math.max(1, tag.getInt("next_id"));
        Node.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("nodes")).resultOrPartial(Incoming.LOG::error)
                .ifPresent(l -> l.forEach(n -> g.nodes.put(n.id(), n)));
        Outage.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("outages")).resultOrPartial(Incoming.LOG::error).ifPresent(g.outages::addAll);
        if (tag.contains("dark_swept")) PROGRESS_CODEC.parse(NbtOps.INSTANCE, tag.get("dark_swept")).resultOrPartial(Incoming.LOG::error).ifPresent(g.darkSwept::putAll);
        if (tag.contains("light_swept")) PROGRESS_CODEC.parse(NbtOps.INSTANCE, tag.get("light_swept")).resultOrPartial(Incoming.LOG::error).ifPresent(g.lightSwept::putAll);
        // own-supply lamps: position, time placed — pairs
        long[] pairs = tag.getLongArray("own_lamps");
        for (int i = 0; i + 1 < pairs.length; i += 2) g.own.computeIfAbsent(ChunkPos.asLong(BlockPos.of(pairs[i])), k -> new Long2LongOpenHashMap()).put(pairs[i], pairs[i + 1]);
        return g;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("next_id", nextId);
        Node.CODEC.listOf().encodeStart(NbtOps.INSTANCE, List.copyOf(nodes.values())).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("nodes", t));
        Outage.CODEC.listOf().encodeStart(NbtOps.INSTANCE, outages).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("outages", t));
        PROGRESS_CODEC.encodeStart(NbtOps.INSTANCE, darkSwept).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("dark_swept", t));
        PROGRESS_CODEC.encodeStart(NbtOps.INSTANCE, lightSwept).resultOrPartial(Incoming.LOG::error).ifPresent(t -> tag.put("light_swept", t));
        if (!own.isEmpty()) {
            LongArrayList pairs = new LongArrayList();
            for (Long2LongOpenHashMap lamps : own.values()) {
                for (var e : lamps.long2LongEntrySet()) {
                    pairs.add(e.getLongKey());
                    pairs.add(e.getLongValue());
                }
            }
            tag.putLongArray("own_lamps", pairs.toLongArray());
        }
        return tag;
    }
}
