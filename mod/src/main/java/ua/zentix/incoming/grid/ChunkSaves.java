package ua.zentix.incoming.grid;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
    private static boolean failureLogged, foreignLogged;

    private ChunkSaves() {}

    /**
     * Saved chunk tag: twins become lamps again, and lighting is recomputed on load (saved lighting was computed for unlit
     * lamps). The common case takes the lossless fast path ({@link #relightPalette}): renaming the twin's palette entry
     * restores every such block, and the packed block data stays as vanilla wrote it. Decoding, converting and re-encoding
     * all 4096 blocks of a section, with the live section encoded once more to compare, cost 1.6 times vanilla's whole
     * chunk serialization while a 1 Mt wave saved a blacked-out city (server thread, srvlag run 23.11). Any other section
     * (the lamp state already listed, data that does not read as vanilla's) goes the full way ({@link #rebuild}): rewritten
     * only if its tag is exactly what vanilla wrote from the live section, otherwise left as it is.
     */
    public static void onSave(ChunkDataEvent.Save e) {
        if (!(e.getChunk() instanceof LevelChunk chunk) || !(e.getLevel() instanceof ServerLevel)) return;
        CompoundTag tag = e.getData();
        if (!tag.contains(ChunkSerializer.SECTIONS_TAG, Tag.TAG_LIST)) return;
        ListTag list = tag.getList(ChunkSerializer.SECTIONS_TAG, Tag.TAG_COMPOUND);
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompound(i);
            if (!s.contains("Y", Tag.TAG_ANY_NUMERIC) || !s.contains("block_states", Tag.TAG_COMPOUND)) continue;
            int index = chunk.getSectionIndexFromSectionY(s.getByte("Y"));
            if (index < 0 || index >= chunk.getSectionsCount()) continue;
            // each section on its own: one that fails leaves the others converted (and the light flag below dropped)
            try {
                int renamed = relightPalette(s.getCompound("block_states"));
                if (renamed > 0 || renamed == DECLINED && rebuild(chunk, index, s)) changed = true;
            } catch (RuntimeException ex) {
                if (!failureLogged) {
                    failureLogged = true;
                    Incoming.LOG.error("Blackout: section {} of chunk {} saved with unlit lamps — could not restore them in the tag", s.getByte("Y"), chunk.getPos(), ex);
                }
            }
        }
        if (changed) {
            tag.putBoolean(ChunkSerializer.IS_LIGHT_ON_TAG, false);
            // scheduled counterpart ticks (nearby redstone, neighbors) are now lamp ticks: they do not belong on disk
            if (tag.contains(BLOCK_TICKS, Tag.TAG_LIST)) tag.getList(BLOCK_TICKS, Tag.TAG_COMPOUND).removeIf(t -> t instanceof CompoundTag c && c.getString("i").startsWith(TWIN_PREFIX));
        }
    }

    /**
     * Full way for a section the fast path declined: the section is rebuilt from a copy where twins are lamps again, but
     * only if its tag is exactly what vanilla just wrote from the live section (comparing tags instead of 4096 blocks);
     * another writer's section keeps its twins.
     */
    private static boolean rebuild(LevelChunk chunk, int index, CompoundTag s) {
        LevelChunkSection section = chunk.getSection(index);
        if (!ChunkLights.needs(section, false)) return false;
        if (!s.getCompound("block_states").equals(BLOCK_STATES.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow())) {
            if (!foreignLogged) {
                foreignLogged = true;
                Incoming.LOG.warn("Blackout: section {} of chunk {} in the save differs from memory (another mod changes the tag) — its unlit lamps are saved as they are",
                        s.getByte("Y"), chunk.getPos());
            }
            return false;
        }
        // own copy from the tag, not copy(): any non-global palette copy retains the live section's resize handler
        // (a single-value palette is even the same palette); a replacement beyond the copy palette's capacity (lamp absent
        // and no space left) would expand the world section and crash the write
        PalettedContainer<BlockState> states = BLOCK_STATES.parse(NbtOps.INSTANCE, s.getCompound("block_states")).getOrThrow();
        // the palette remembers removed states too: a counterpart in it does not imply one in the section
        if (ChunkLights.apply(states, false) == 0) return false;
        s.put("block_states", BLOCK_STATES.encodeStart(NbtOps.INSTANCE, states).getOrThrow());
        return true;
    }

    /** {@link #relightPalette}: twins are listed, but the section is not for the fast path; nothing changed. */
    public static final int DECLINED = -1;

    /**
     * Lossless fast path: renames each twin entry of an encoded section ({@code palette} list, packed {@code data}) to its
     * lamp, changing only the entry's {@code Name}, so other keys of the entry and the section stay. Returns how many
     * entries it renamed, 0 if the palette lists no twin, or {@link #DECLINED} without touching the tag when a rename could
     * lose or misread anything: the lamp state is already listed (two entries of one state), a twin entry does not
     * decode or omits a property, or the packed data does not read as vanilla's for this palette (its length, or an index beyond the list).
     */
    public static int relightPalette(CompoundTag states) {
        ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
        int size = palette.size();
        java.util.Set<String> twins = twinNames();
        int[] at = null;
        int n = 0;
        for (int i = 0; i < size; i++) {
            // a prefix test first: most entries fail it at the first character
            String name = palette.getCompound(i).getString("Name");
            if (!name.startsWith(TWIN_PREFIX) || !twins.contains(name)) continue;
            if (at == null) at = new int[size];
            at[n++] = i;
        }
        if (n == 0) return 0;
        try {
            BlockState[] lamps = new BlockState[n];
            String[] lampNames = new String[n];
            CompoundTag[] renamed = new CompoundTag[n];
            for (int k = 0; k < n; k++) {
                CompoundTag entry = palette.getCompound(at[k]);
                BlockState twin = BlockState.CODEC.parse(NbtOps.INSTANCE, entry).getOrThrow();
                BlockState lamp = GridLights.lit(twin);
                // every property written out: an omitted one would decode to each block's own default
                if (lamp == null || entry.getCompound("Properties").size() != twin.getValues().size()) return DECLINED;
                for (int j = 0; j < k; j++) if (lamps[j] == lamp) return DECLINED;
                lamps[k] = lamp;
                lampNames[k] = BuiltInRegistries.BLOCK.getKey(lamp.getBlock()).toString();
                // the lamp has the twin's properties with the same values (GridLights): the renamed entry decodes to it
                renamed[k] = entry.copy();
                renamed[k].putString("Name", lampNames[k]);
            }
            // the lamp state already listed: only entries named as one of the lamps can be it
            for (int i = 0; i < size; i++) {
                String name = palette.getCompound(i).getString("Name");
                for (int k = 0; k < n; k++) {
                    if (name.equals(lampNames[k]) && BlockState.CODEC.parse(NbtOps.INSTANCE, palette.get(i)).getOrThrow() == lamps[k]) return DECLINED;
                }
            }
            if (!indicesValid(states, size)) return DECLINED;
            for (int k = 0; k < n; k++) palette.set(at[k], renamed[k]);
            return n;
        } catch (RuntimeException ex) {
            return DECLINED;
        }
    }

    /**
     * Packed data reads as vanilla's for a palette list of {@code size}: none needed for one entry, otherwise the length
     * vanilla expects at its width ({@code PalettedContainer.Strategy.SECTION_STATES}) and every index within the list.
     */
    static boolean indicesValid(CompoundTag states, int size) {
        int bits = net.minecraft.util.Mth.ceillog2(size);
        if (bits == 0) return true;
        bits = Math.max(4, bits);
        if (!states.contains("data", Tag.TAG_LONG_ARRAY)) return false;
        long[] data = states.getLongArray("data");
        int perLong = 64 / bits;
        if (data.length != (4096 + perLong - 1) / perLong) return false;
        if (size == 1 << bits) return true;
        long mask = (1L << bits) - 1;
        for (int w = 0, c = 0; w < data.length; w++) {
            long word = data[w];
            for (int k = 0; k < perLong && c < 4096; k++, c++, word >>>= bits) if ((word & mask) >= size) return false;
        }
        return true;
    }

    private static volatile java.util.Set<String> twinNames;

    private static java.util.Set<String> twinNames() {
        java.util.Set<String> names = twinNames;
        if (names == null) twinNames = names = names();
        return names;
    }

    private static java.util.Set<String> names() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ua.zentix.incoming.registry.ModBlocks.UnlitPair p : ua.zentix.incoming.registry.ModBlocks.UNLIT) {
            names.add(BuiltInRegistries.BLOCK.getKey(p.unlit().get()).toString());
        }
        return java.util.Set.copyOf(names);
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
