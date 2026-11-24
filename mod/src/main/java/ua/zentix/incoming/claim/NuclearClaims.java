package ua.zentix.incoming.claim;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.ClaimQuery;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.NuclearEvents;
import ua.zentix.incoming.registry.ModDamageTypes;
import ua.zentix.incoming.util.Terrain;

/**
 * Claim decisions of nuclear detonations, per detonation and chunk, saved with the world: the flash layer, the ruins
 * (live, behind the wave, settled on disk, built on a later load), the crater, toppled trunks and distant LOD copies all
 * read the same record. A chunk is asked at every position its write changes, in chunks in memory only; a refusal
 * anywhere denies the whole chunk to the detonation for good (a chunk's ruins are one physical result: written in part
 * they would leave floating blocks and stale heightmaps). A denial is never lifted, so a claim removed between the flash
 * and the wave keeps the chunk as it was, and a claim placed after the flash keeps the wave out. A chunk not in memory is
 * not asked ({@link Claims}): a LOD copy of it follows a stored denial only, and its world write asks when it loads.
 * Another detonation asks on its own. A detonation the world no longer keeps ({@link NuclearEvents}) loses its record.
 */
public final class NuclearClaims extends SavedData {
    private static final String NAME = Incoming.MOD_ID + "_nuclear_claims";

    /** Denied chunks ({@code ChunkPos.toLong}) by detonation id. */
    private final Int2ObjectOpenHashMap<LongOpenHashSet> denied = new Int2ObjectOpenHashMap<>();

    public static NuclearClaims get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(NuclearClaims::new, NuclearClaims::load, null), NAME);
    }

    /** Stored denial of {@code chunk} to detonation {@code id} (no question asked). */
    public boolean denied(int id, long chunk) {
        LongOpenHashSet set = denied.get(id);
        return set != null && set.contains(chunk);
    }

    /**
     * Whether detonation {@code d} may write {@code chunk}, which the write changes at {@code at[0, n)} ({@code
     * BlockPos.asLong}, all inside the chunk). A stored denial answers at once; otherwise handlers are asked, and a
     * refusal is stored. Server thread; a chunk not in memory gets only its stored answer.
     */
    public boolean allows(ServerLevel level, Detonation d, long chunk, long[] at, int n) {
        if (denied(d.id(), chunk)) return false;
        if (n == 0 || !Terrain.ready(level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk))) return true;
        if (Claims.offer(level, query(level, d, chunk), at, 0, n, null, true) == 0) return true;
        deny(level, d.id(), chunk);
        return false;
    }

    /**
     * Detonation {@code d}'s gate for a writer that knows its positions only as it goes (the crater, one column at a
     * time): positions are asked by chunk; a refusal denies the chunk ({@link #allows}).
     */
    public static BlockGate gate(ServerLevel level, Detonation d) {
        NuclearClaims claims = get(level);
        return (l, positions) -> {
            Long2ObjectOpenHashMap<LongArrayList> byChunk = new Long2ObjectOpenHashMap<>();
            for (BlockPos p : positions) byChunk.computeIfAbsent(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), k -> new LongArrayList()).add(p.asLong());
            LongOpenHashSet refused = new LongOpenHashSet();
            byChunk.long2ObjectEntrySet().forEach(e -> {
                long[] at = e.getValue().toLongArray();
                if (!claims.allows(l, d, e.getLongKey(), at, at.length)) refused.add(e.getLongKey());
            });
            if (!refused.isEmpty()) positions.removeIf(p -> refused.contains(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4)));
        };
    }

    /**
     * Deny {@code chunk} to detonation {@code id}; records of detonations the world no longer keeps go (a denial is rare,
     * so the walk is too).
     */
    public void deny(ServerLevel level, int id, long chunk) {
        if (!denied.computeIfAbsent(id, k -> new LongOpenHashSet()).add(chunk)) return;
        NuclearEvents events = NuclearEvents.get(level);
        IntOpenHashSet kept = new IntOpenHashSet();
        events.detonations().forEach(x -> kept.add(x.id()));
        events.past().forEach(x -> kept.add(x.id()));
        kept.add(id);
        denied.keySet().removeIf(k -> !kept.contains(k));
        setDirty();
    }

    /**
     * Detonation {@code d}'s question about {@code chunk}: no source entity, nuclear blast damage, centred in the middle
     * of the chunk at the burst height (never at ground zero, which need not be in memory).
     */
    static ClaimQuery query(ServerLevel level, Detonation d, long chunk) {
        double y = Mth.clamp(d.burst().y, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
        return new ClaimQuery(level, null, ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_BLAST, null, null),
                new Vec3((ChunkPos.getX(chunk) << 4) + 8.5, y, (ChunkPos.getZ(chunk) << 4) + 8.5));
    }

    /** Denied chunk count of detonation {@code id} (logs, tests). */
    public int deniedCount(int id) {
        LongOpenHashSet set = denied.get(id);
        return set == null ? 0 : set.size();
    }

    public static NuclearClaims load(CompoundTag tag, HolderLookup.Provider registries) {
        NuclearClaims c = new NuclearClaims();
        CompoundTag all = tag.getCompound("denied");
        for (String k : all.getAllKeys()) {
            try {
                c.denied.put(Integer.parseInt(k), new LongOpenHashSet(all.getLongArray(k)));
            } catch (NumberFormatException e) {
                Incoming.LOG.warn("Nuclear claim record: skipping detonation key {}", k);
            }
        }
        return c;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag all = new CompoundTag();
        denied.int2ObjectEntrySet().forEach(e -> all.putLongArray(Integer.toString(e.getIntKey()), e.getValue().toLongArray()));
        tag.put("denied", all);
        return tag;
    }
}
