package ua.zentix.incoming.claim;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.ClaimQuery;
import ua.zentix.incoming.nuclear.Detonation;
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
 * Another detonation asks on its own.
 */
public final class NuclearClaims extends SavedData {
    private static final String NAME = Incoming.MOD_ID + "_nuclear_claims";

    /** Denied chunks ({@code ChunkPos.toLong}) by detonation id. */
    private final Int2ObjectOpenHashMap<LongOpenHashSet> denied = new Int2ObjectOpenHashMap<>();
    /** Each detonation's query (not saved): no source entity, nuclear blast damage, at the burst point. */
    private final Int2ObjectOpenHashMap<ClaimQuery> queries = new Int2ObjectOpenHashMap<>();

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
        if (Claims.offer(level, query(level, d), at, 0, n, null, true) == 0) return true;
        deny(d.id(), chunk);
        return false;
    }

    /**
     * Detonation {@code d}'s gate for a writer that knows its positions only as it goes (the crater, one column at a
     * time): positions are asked by chunk; a refusal denies the chunk ({@link #allows}).
     */
    public static BlockGate gate(ServerLevel level, Detonation d) {
        NuclearClaims claims = get(level);
        return (l, positions) -> {
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.LongArrayList> byChunk = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
            for (net.minecraft.core.BlockPos p : positions) {
                byChunk.computeIfAbsent(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4), k -> new it.unimi.dsi.fastutil.longs.LongArrayList()).add(p.asLong());
            }
            it.unimi.dsi.fastutil.longs.LongOpenHashSet refused = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            byChunk.long2ObjectEntrySet().forEach(e -> {
                long[] at = e.getValue().toLongArray();
                if (!claims.allows(l, d, e.getLongKey(), at, at.length)) refused.add(e.getLongKey());
            });
            if (!refused.isEmpty()) positions.removeIf(p -> refused.contains(ChunkPos.asLong(p.getX() >> 4, p.getZ() >> 4)));
        };
    }

    /** Deny {@code chunk} to detonation {@code id} (a writer that asked by itself was refused). */
    public void deny(int id, long chunk) {
        if (denied.computeIfAbsent(id, k -> new LongOpenHashSet()).add(chunk)) setDirty();
    }

    /** Detonation {@code d}'s query. */
    public ClaimQuery query(ServerLevel level, Detonation d) {
        return queries.computeIfAbsent(d.id(), k -> new ClaimQuery(level, null, ModDamageTypes.source(level, ModDamageTypes.NUCLEAR_BLAST, null, null), d.burst()));
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
            int id;
            try {
                id = Integer.parseInt(k);
            } catch (NumberFormatException e) {
                continue;
            }
            c.denied.put(id, new LongOpenHashSet(all.getLongArray(k)));
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
