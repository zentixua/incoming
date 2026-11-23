package ua.zentix.incoming.grid;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkType;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.registry.ModAttachments;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.LongPredicate;

/**
 * No counterparts on disk: an unlit lamp is only an in-memory chunk state. The chunk is saved with real lamps (and
 * relighting on load); loaded in a dark district, it goes dark immediately in the palette, before becoming part of the
 * world. The world on disk is thus always a normal world: it can be opened without the mod, with an old mod version,
 * or from a backup, and nothing is lost after a server failure.
 */
public final class ChunkSaves {
    /** As in vanilla ({@code ChunkSerializer}): the same section block codec. */
    private static final Codec<PalettedContainer<BlockState>> BLOCK_STATES = PalettedContainer.codecRW(
            Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    private static final String BLOCK_TICKS = "block_ticks", TWIN_PREFIX = Incoming.MOD_ID + ":unlit_";
    private static boolean failureLogged;

    private ChunkSaves() {}

    /**
     * Saved chunk tag: twins become lamps again in each section's palette list, and lighting is recomputed on load (saved
     * lighting was computed for unlit lamps). A twin and its lamp have the same properties, so renaming the twin's palette
     * entry restores every such block while the packed block data stays as vanilla wrote it. Decoding, converting and
     * re-encoding all 4096 blocks of each section (and encoding the live section once more to compare) cost 1.6 times
     * vanilla's whole chunk serialization while a 1 Mt wave saved a blacked-out city (server thread, srvlag run 23.11).
     * A rename that would list one state twice (a kept lamp beside its own twin state) rebuilds that section instead:
     * palette implementations (Lithium's too) need not keep duplicate entries at their indices.
     */
    public static void onSave(ChunkDataEvent.Save e) {
        if (!(e.getChunk() instanceof LevelChunk chunk) || !(e.getLevel() instanceof ServerLevel)) return;
        if (!ChunkLights.anyUnlit(chunk.getSections())) return;
        CompoundTag tag = e.getData();
        if (!tag.contains(ChunkSerializer.SECTIONS_TAG, Tag.TAG_LIST)) return;
        ListTag list = tag.getList(ChunkSerializer.SECTIONS_TAG, Tag.TAG_COMPOUND);
        try {
            boolean changed = false;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag s = list.getCompound(i);
                if (!s.contains("Y", Tag.TAG_ANY_NUMERIC) || !s.contains("block_states", Tag.TAG_COMPOUND)) continue;
                // only sections of this chunk whose live blocks hold twins (palette check, no block scan): a section
                // outside the chunk's height or without twins in memory is another writer's, left byte for byte
                int index = chunk.getSectionIndexFromSectionY(s.getByte("Y"));
                if (index < 0 || index >= chunk.getSectionsCount() || !ChunkLights.needs(chunk.getSection(index), false)) continue;
                CompoundTag states = s.getCompound("block_states");
                int renamed = relightPalette(states);
                if (renamed == DUPLICATE) {
                    // own copy from the tag, not copy(): any non-global palette copy retains the live section's resize handler
                    PalettedContainer<BlockState> copy = BLOCK_STATES.parse(NbtOps.INSTANCE, states).getOrThrow();
                    if (ChunkLights.apply(copy, false) == 0) continue;
                    s.put("block_states", BLOCK_STATES.encodeStart(NbtOps.INSTANCE, copy).getOrThrow());
                    REBUILT.incrementAndGet();
                    changed = true;
                } else if (renamed > 0) {
                    RENAMED.incrementAndGet();
                    changed = true;
                }
            }
            if (changed) {
                tag.putBoolean(ChunkSerializer.IS_LIGHT_ON_TAG, false);
                // scheduled counterpart ticks (nearby redstone, neighbors) are now lamp ticks: they do not belong on disk
                if (tag.contains(BLOCK_TICKS, Tag.TAG_LIST)) tag.getList(BLOCK_TICKS, Tag.TAG_COMPOUND).removeIf(t -> t instanceof CompoundTag c && c.getString("i").startsWith(TWIN_PREFIX));
            }
        } catch (RuntimeException ex) {
            if (!failureLogged) {
                failureLogged = true;
                Incoming.LOG.error("Blackout: chunk {} saved with unlit lamps — could not restore them in the tag", chunk.getPos(), ex);
            }
        }
    }

    /** {@link #relightPalette}: the rename would list one state twice. */
    public static final int DUPLICATE = -1;
    /** Sections restored by renaming palette entries / by rebuilding the section (tests, measurements). */
    public static final java.util.concurrent.atomic.AtomicLong RENAMED = new java.util.concurrent.atomic.AtomicLong(),
            REBUILT = new java.util.concurrent.atomic.AtomicLong();

    /**
     * Renames twin entries of an encoded section's {@code palette} list to their lamps in place; returns the number
     * renamed, or {@link #DUPLICATE} (nothing changed) if a lamp state is already listed.
     */
    public static int relightPalette(CompoundTag states) {
        if (!states.contains("palette", Tag.TAG_LIST)) return 0;
        ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
        java.util.Set<String> twins = twinNames();
        String[] names = new String[palette.size()];
        int[] at = null;
        int n = 0;
        for (int i = 0; i < palette.size(); i++) {
            names[i] = palette.getCompound(i).getString("Name");
            if (!twins.contains(names[i])) continue;
            if (at == null) at = new int[palette.size()];
            at[n++] = i;
        }
        if (n == 0) return 0;
        CompoundTag[] lamps = new CompoundTag[n];
        java.util.Set<String> lampNames = new java.util.HashSet<>();
        for (int k = 0; k < n; k++) {
            BlockState twin = BlockState.CODEC.parse(NbtOps.INSTANCE, palette.get(at[k])).getOrThrow();
            BlockState lamp = GridLights.lit(twin);
            if (lamp == null) throw new IllegalStateException("not a twin: " + twin);
            lamps[k] = (CompoundTag) BlockState.CODEC.encodeStart(NbtOps.INSTANCE, lamp).getOrThrow();
            lampNames.add(lamps[k].getString("Name"));
            for (int j = 0; j < k; j++) if (lamps[j].equals(lamps[k])) return DUPLICATE;
        }
        // a lamp state already listed: only entries of the lamps' own blocks can equal one (full-palette hashing cost most of the save)
        for (int i = 0; i < palette.size(); i++) {
            if (!lampNames.contains(names[i])) continue;
            CompoundTag entry = palette.getCompound(i);
            for (CompoundTag lamp : lamps) if (lamp.equals(entry)) return DUPLICATE;
        }
        for (int k = 0; k < n; k++) palette.set(at[k], lamps[k]);
        return n;
    }

    private static volatile java.util.Set<String> twinNames;

    private static java.util.Set<String> twinNames() {
        java.util.Set<String> names = twinNames;
        if (names == null) {
            names = new java.util.HashSet<>();
            for (ua.zentix.incoming.registry.ModBlocks.UnlitPair p : ua.zentix.incoming.registry.ModBlocks.UNLIT) {
                names.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(p.unlit().get()).toString());
            }
            twinNames = names = java.util.Set.copyOf(names);
        }
        return names;
    }

    /**
     * Chunk from disk (server thread, before becoming part of the world): in a dark district, lamps go dark directly in the
     * palette — no neighbor updates, Sable or flicker. Their light came from disk with the chunk: {@link BlackoutWorld}
     * removes it in the first work on the chunk ({@code checkBlock}). The shared light lease retains the initialized ring and saves corrected
     * wrapped neighbors: INITIALIZE_LIGHT receives reductions, but ImposterProtoChunk itself never saves them. A counterpart in a lit district (save without this interception, counterpart moved by
     * piston or craft) becomes a lamp again, and lighting is recomputed on load.
     */
    public static void onLoad(ChunkDataEvent.Load e) {
        if (e.getType() != ChunkType.LEVELCHUNK || !(e.getChunk() instanceof LevelChunk chunk) || !(e.getLevel() instanceof ServerLevel level)) return;
        // the event comes from ChunkSerializer.read itself: other mods (LODs, maps) also call it, including on their own
        // threads — this is a disk copy, not a world chunk. The world loads chunks on the server thread (ChunkMap.scheduleChunkLoad);
        // on another thread, touch nothing (grid state and queues belong only to the server thread)
        if (!level.getServer().isSameThread()) {
            foreignRead(OFF_THREAD, chunk.getPos(), "thread " + Thread.currentThread().getName());
            return;
        }
        // copy of a chunk already in the world: it can be darkened, but the world chunk has no disk light to remove — leave it alone
        boolean copy = BlackoutWorld.inMemory(level, chunk.getPos().toLong()) != null;
        if (copy) foreignRead(COPY, chunk.getPos(), "chunk already in the world");
        PowerGrid grid = PowerGrid.get(level);
        LevelChunkSection[] sections = chunk.getSections();
        // the GRID_DARK mark is not saved: a chunk from disk has none
        if (grid.outages().isEmpty() && !ChunkLights.anyUnlit(sections)) return;
        ChunkPos pos = chunk.getPos();
        boolean dark = grid.dark(pos.x, pos.z, Blackouts.now(level));
        // lamps placed after the district went dark run on their own supply: they stay lit
        LongPredicate keep = dark ? grid.keepsLit(pos.x, pos.z, Blackouts.now(level)) : null;
        LongArrayList stale = new LongArrayList();
        int[] lit = {0};
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || !ChunkLights.needs(section, dark)) continue;
            int x0 = pos.getMinBlockX(), y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i)), z0 = pos.getMinBlockZ();
            ChunkLights.scan(section, dark, x0, y0, z0, keep, (x, y, z, to) -> {
                section.setBlockState(x, y, z, to, false);
                if (dark) stale.add(BlockPos.asLong(x0 + x, y0 + y, z0 + z));
                else lit[0]++;
            });
        }
        if (lit[0] > 0) {
            chunk.setLightCorrect(false);
            // counterparts on disk (save without interception) — rewrite on the next save
            chunk.setUnsaved(true);
        }
        if (copy) return;
        if (!stale.isEmpty() || level.hasData(ModAttachments.BLACKOUT_WORLD)) BlackoutWorld.get(level).staleLight(pos, stale);
        if (dark && ChunkLights.anyUnlit(sections)) chunk.setData(ModAttachments.GRID_DARK, true);
    }

    /** Disk chunk reads not for the world (since startup): on another thread and copies of chunks already in the world. */
    public static final int OFF_THREAD = 0, COPY = 1;
    private static final AtomicLongArray FOREIGN_READS = new AtomicLongArray(2);
    private static final AtomicIntegerArray FOREIGN_LOGGED = new AtomicIntegerArray(2);

    private static void foreignRead(int kind, ChunkPos pos, String what) {
        FOREIGN_READS.incrementAndGet(kind);
        // first case of each kind includes a stack trace: it identifies the mod reading chunks
        if (FOREIGN_LOGGED.compareAndSet(kind, 0, 1)) {
            Incoming.LOG.info("Blackout: chunk {} read from disk not for the world ({}) — the blackout queue ignores it", pos, what, new Throwable("who reads it"));
        }
    }

    public static long foreignReads(int kind) {
        return FOREIGN_READS.get(kind);
    }
}
