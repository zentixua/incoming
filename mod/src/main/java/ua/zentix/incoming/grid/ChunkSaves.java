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
     * Saved chunk tag: rebuild sections with counterparts from a copy where they are lamps again; recompute lighting on
     * load (saved lighting was computed for unlit lamps). A tag unlike vanilla's (different pipeline or sections) is left
     * alone: a section is rewritten only if the tag's blocks are exactly this section's blocks in the chunk.
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
                int index = chunk.getSectionIndexFromSectionY(s.getByte("Y"));
                if (index < 0 || index >= chunk.getSectionsCount()) continue;
                LevelChunkSection section = chunk.getSection(index);
                if (!ChunkLights.needs(section, false)) continue;
                // section tag is exactly what vanilla just wrote from this section (comparing tags instead of 4096 blocks)
                if (!s.getCompound("block_states").equals(BLOCK_STATES.encodeStart(NbtOps.INSTANCE, section.getStates()).getOrThrow())) {
                    // tag was not written by the vanilla pipeline for this chunk — not ours; this section's counterparts go to disk as is
                    if (!foreignLogged) {
                        foreignLogged = true;
                        Incoming.LOG.warn("Blackout: section {} of chunk {} in the save differs from memory (another mod changes the tag) — its unlit lamps are saved as they are",
                                s.getByte("Y"), chunk.getPos());
                    }
                    continue;
                }
                // own copy from the tag, not copy(): any non-global palette copy retains the live section's resize handler
                // (a single-value palette is even the same palette); a replacement beyond the copy palette's capacity (lamp absent
                // and no space left) would expand the world section and crash the write
                PalettedContainer<BlockState> states = BLOCK_STATES.parse(NbtOps.INSTANCE, s.getCompound("block_states")).getOrThrow();
                // the palette remembers removed states too: a counterpart in it does not imply one in the section
                if (ChunkLights.apply(states, false) == 0) continue;
                s.put("block_states", BLOCK_STATES.encodeStart(NbtOps.INSTANCE, states).getOrThrow());
                changed = true;
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

    /**
     * Chunk from disk (server thread, before becoming part of the world): in a dark district, lamps go dark directly in the
     * palette — no neighbor updates, Sable or flicker. Their light came from disk with the chunk: {@link BlackoutWorld}
     * removes it in the first work on the chunk ({@code checkBlock}; light reduction does not reach unloaded neighbors —
     * they have nothing to reduce). A counterpart in a lit district (save without this interception, counterpart moved by
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
        boolean dark = grid.dark(pos.x, pos.z, level.getGameTime());
        LongArrayList stale = new LongArrayList();
        int[] lit = {0};
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || !ChunkLights.needs(section, dark)) continue;
            int x0 = pos.getMinBlockX(), y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i)), z0 = pos.getMinBlockZ();
            ChunkLights.scan(section, dark, (x, y, z, to) -> {
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
