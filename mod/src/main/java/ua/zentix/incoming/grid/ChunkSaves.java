package ua.zentix.incoming.grid;

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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
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
    private static final String BLOCK_TICKS = "block_ticks", TWIN_PREFIX = Incoming.MOD_ID + ":unlit_";
    private static boolean failureLogged;

    private ChunkSaves() {}

    /**
     * Saved chunk tag: twins become lamps again in each section's palette list, and lighting is recomputed on load (saved
     * lighting was computed for unlit lamps). The decision comes from the saved tag itself, not the live chunk: the tag is
     * a snapshot, and the live blocks may have changed since. A twin and its lamp have the same properties, so renaming
     * the twin's palette entry restores every such block while the packed block data stays as vanilla wrote it. Decoding,
     * converting and re-encoding all 4096 blocks of each section (and encoding the live section once more to compare) cost
     * 1.6 times vanilla's whole chunk serialization while a 1 Mt wave saved a blacked-out city (server thread, srvlag run
     * 23.11). Only the registered name changes: other keys of the entry and the section (another mod's data) stay. A twin
     * whose lamp state is already listed merges into that entry instead ({@link #relightPalette}). A section that does not
     * read as a vanilla palette is another writer's and stays as it is; it does not stop the other sections.
     */
    public static void onSave(ChunkDataEvent.Save e) {
        // twins live only in full chunks: a below-full chunk's tag is another pipeline's, left byte for byte
        if (!(e.getChunk() instanceof LevelChunk) || !(e.getLevel() instanceof ServerLevel level)) return;
        CompoundTag tag = e.getData();
        if (!tag.contains(ChunkSerializer.SECTIONS_TAG, Tag.TAG_LIST)) return;
        ListTag list = tag.getList(ChunkSerializer.SECTIONS_TAG, Tag.TAG_COMPOUND);
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag s = list.getCompound(i);
            if (!s.contains("Y", Tag.TAG_ANY_NUMERIC) || !s.contains("block_states", Tag.TAG_COMPOUND)) continue;
            // a section outside the world's height is another writer's, left byte for byte
            int y = s.getByte("Y");
            if (y < level.getMinSection() || y >= level.getMaxSection()) continue;
            CompoundTag states = s.getCompound("block_states");
            if (!holdsTwin(states)) continue;
            try {
                if (relightPalette(states) > 0) changed = true;
            } catch (RuntimeException ex) {
                if (!failureLogged) {
                    failureLogged = true;
                    Incoming.LOG.error("Blackout: section {} of chunk {} is not a vanilla palette (another mod writes it?): its unlit lamps are saved as they are",
                            y, e.getChunk().getPos(), ex);
                }
            }
        }
        if (changed) {
            // every converted section's saved light was computed for unlit lamps, even if another section failed
            tag.putBoolean(ChunkSerializer.IS_LIGHT_ON_TAG, false);
            // scheduled counterpart ticks (nearby redstone, neighbors) are now lamp ticks: they do not belong on disk
            if (tag.contains(BLOCK_TICKS, Tag.TAG_LIST)) tag.getList(BLOCK_TICKS, Tag.TAG_COMPOUND).removeIf(t -> t instanceof CompoundTag c && c.getString("i").startsWith(TWIN_PREFIX));
        }
    }

    /** Palette list names a twin: a prefix test first, most entries fail it at the first character. */
    private static boolean holdsTwin(CompoundTag states) {
        if (!states.contains("palette", Tag.TAG_LIST)) return false;
        ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
        java.util.Set<String> twins = twinNames();
        for (int i = 0; i < palette.size(); i++) {
            String name = palette.getCompound(i).getString("Name");
            if (name.startsWith(TWIN_PREFIX) && twins.contains(name)) return true;
        }
        return false;
    }

    /**
     * Turns the twin entries of an encoded section ({@code palette} list, packed {@code data}) into their lamps in place;
     * returns how many twin entries it restored. A twin entry keeps its tag with the lamp's registered name. A twin whose
     * lamp state is already listed (a kept lamp beside its own twin state; compared by decoded state, as extra keys
     * may differ) cannot be renamed, since palette readers (Lithium's too) need not keep a state listed twice at its
     * index: its blocks move to the lamp's index in the packed data, its entry leaves the list, and the data is packed
     * again at the width vanilla reads for the new list size. Other entries and keys stay.
     *
     * @throws RuntimeException the section does not read as a vanilla palette (an entry, the data length or an index)
     */
    public static int relightPalette(CompoundTag states) {
        ListTag palette = states.getList("palette", Tag.TAG_COMPOUND);
        int size = palette.size();
        java.util.Set<String> twins = twinNames();
        // decided first, written only once the whole section reads: a section that fails stays as it was
        // target[i]: palette index whose state block i becomes; renamed[i]: entry i's new tag
        int[] target = null;
        CompoundTag[] renamed = null;
        int restored = 0, merged = 0;
        java.util.Map<BlockState, Integer> listed = null;
        for (int i = 0; i < size; i++) {
            CompoundTag entry = palette.getCompound(i);
            String name = entry.getString("Name");
            if (!name.startsWith(TWIN_PREFIX) || !twins.contains(name)) continue;
            BlockState twin = BlockState.CODEC.parse(NbtOps.INSTANCE, entry).getOrThrow();
            BlockState lamp = GridLights.lit(twin);
            if (lamp == null) throw new IllegalStateException("not a twin: " + twin);
            if (listed == null) {
                listed = lampsListed(palette);
                target = new int[size];
                for (int k = 0; k < size; k++) target[k] = k;
                renamed = new CompoundTag[size];
            }
            restored++;
            Integer at = listed.get(lamp);
            if (at != null) {
                target[i] = at;
                merged++;
                continue;
            }
            CompoundTag tag = entry.copy();
            tag.putString("Name", BuiltInRegistries.BLOCK.getKey(lamp.getBlock()).toString());
            if (BlockState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow() != lamp) {
                // properties written differently from the lamp's: its canonical properties, other keys kept
                CompoundTag canonical = (CompoundTag) BlockState.CODEC.encodeStart(NbtOps.INSTANCE, lamp).getOrThrow();
                tag.remove("Properties");
                if (canonical.contains("Properties")) tag.put("Properties", canonical.get("Properties"));
            }
            renamed[i] = tag;
            listed.put(lamp, i);
        }
        if (restored == 0) return 0;
        long[] data = null;
        if (merged > 0) {
            // indices of the remaining entries once merged twins leave the list
            int[] index = new int[size];
            for (int i = 0, n = 0; i < size; i++) index[i] = target[i] == i ? n++ : -1;
            int bits = serializedBits(size), newBits = serializedBits(size - merged);
            if (!states.contains("data", Tag.TAG_LONG_ARRAY)) throw new IllegalStateException("palette of " + size + " without data");
            var old = new net.minecraft.util.SimpleBitStorage(bits, 4096, states.getLongArray("data"));
            if (newBits > 0) {
                var packed = new net.minecraft.util.SimpleBitStorage(newBits, 4096);
                for (int c = 0; c < 4096; c++) {
                    int v = old.get(c);
                    if (v >= size) throw new IllegalStateException("block index " + v + " beyond a palette of " + size);
                    packed.set(c, index[target[v]]);
                }
                data = packed.getRaw();
            }
        }
        for (int i = size - 1; i >= 0; i--) {
            if (renamed[i] != null) palette.set(i, renamed[i]);
            else if (target[i] != i) palette.remove(i);
        }
        if (merged > 0) {
            if (data == null) states.remove("data");
            else states.putLongArray("data", data);
        }
        return restored;
    }

    /** Lamp states already in the palette list, by decoded state, with their index. */
    private static java.util.Map<BlockState, Integer> lampsListed(ListTag palette) {
        java.util.Set<String> lampNames = lampNames();
        java.util.Map<BlockState, Integer> listed = new java.util.HashMap<>();
        for (int i = 0; i < palette.size(); i++) {
            CompoundTag entry = palette.getCompound(i);
            if (!lampNames.contains(entry.getString("Name"))) continue;
            BlockState st = BlockState.CODEC.parse(NbtOps.INSTANCE, entry).getOrThrow();
            listed.putIfAbsent(st, i);
        }
        return listed;
    }

    /** Bits per block vanilla reads for a palette list of {@code size} ({@code PalettedContainer.Strategy.SECTION_STATES}). */
    static int serializedBits(int size) {
        int bits = net.minecraft.util.Mth.ceillog2(size);
        return bits == 0 ? 0 : Math.max(4, bits);
    }

    private static volatile java.util.Set<String> twinNames, lampNames;

    private static java.util.Set<String> twinNames() {
        java.util.Set<String> names = twinNames;
        if (names == null) twinNames = names = names(true);
        return names;
    }

    private static java.util.Set<String> lampNames() {
        java.util.Set<String> names = lampNames;
        if (names == null) lampNames = names = names(false);
        return names;
    }

    private static java.util.Set<String> names(boolean twin) {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ua.zentix.incoming.registry.ModBlocks.UnlitPair p : ua.zentix.incoming.registry.ModBlocks.UNLIT) {
            names.add(BuiltInRegistries.BLOCK.getKey((twin ? p.unlit() : p.lit()).get()).toString());
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
