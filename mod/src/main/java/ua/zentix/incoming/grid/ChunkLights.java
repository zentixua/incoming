package ua.zentix.incoming.grid;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.util.Palettes;

import java.util.ArrayList;
import java.util.List;

/**
 * Align chunk lamps to grid state: extinguish (lamps → twins) or relight (twins → lamps). Skip sections without
 * relevant blocks by palette, and global palettes by exact count ({@link Palettes#contains}): otherwise every city
 * chunk load/save would scan all blocks of such sections on the server thread and enqueue the chunk for blackout
 * despite having neither grid lamps nor outages.
 */
public final class ChunkLights {
    /**
     * No neighbor or neighbor-shape updates: a twin has the lamp's shape, so neighbors need no notification (a redstone
     * lamp would relight on update). Notify clients; the world itself recalculates light.
     */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static boolean revertLogged;

    private ChunkLights() {}

    /**
     * Bounded chunk pass: number of lamps converted, whether chunk end was reached, and resume location ({@code next}:
     * section number × 4096 + block index — first lamp beyond the limit). Palette cannot reveal the end (it remembers
     * converted states too), so the pass stops at the first lamp beyond the limit rather than the last lamp
     * within it: a chunk with exactly {@code limit} lamps finishes in this pass, without an empty second one. Next pass
     * starts at {@code next}, not chunk start; otherwise a building with thousands of lamps would be rescanned dozens of times.
     */
    public record Pass(int changed, boolean done, int next, int reverted) {
        public static final Pass NONE = new Pass(0, true, 0, 0);
    }

    @FunctionalInterface
    interface Change {
        void accept(int x, int y, int z, BlockState to);
    }

    /** Section has blocks to convert ({@link Palettes#contains}). */
    static boolean needs(LevelChunkSection section, boolean dark) {
        return !section.hasOnlyAir() && Palettes.contains(section.getStates(), s -> GridLights.needs(s, dark));
    }

    /** Chunk has blocks to convert (by section palettes). */
    static boolean needs(LevelChunk chunk, boolean dark) {
        for (LevelChunkSection section : chunk.getSections()) {
            if (needs(section, dark)) return true;
        }
        return false;
    }

    static void scan(LevelChunkSection section, boolean dark, Change change) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                    if (to != null) change.accept(x, y, z, to);
                }
            }
        }
    }

    /**
     * World chunk: changes through the world (light, clients, Sable). Neighboring chunks must be loaded — checked
     * by caller. Returns number of lamps converted.
     * <p>
     * Signal lamps (redstone, copper) reconcile with the signal after power restoration, as on neighbor
     * update: a signal lost or gained while dark is noticed as soon as power returns.
     */
    public static int apply(ServerLevel level, LevelChunk chunk, boolean dark) {
        return apply(level, chunk, dark, Integer.MAX_VALUE).changed();
    }

    /** Same, at most {@code limit} lamps (one budgeted work unit): the rest on the next call. */
    public static Pass apply(ServerLevel level, LevelChunk chunk, boolean dark, int limit) {
        return apply(level, chunk, dark, limit, 0);
    }

    /** Same, starting at {@code from} ({@link Pass#next} from the previous pass). */
    public static Pass apply(ServerLevel level, LevelChunk chunk, boolean dark, int limit, int from) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        List<BlockPos> signalled = new ArrayList<>();
        int changed = 0, next = 0, reverted = 0;
        boolean done = true;
        sections:
        for (int i = from >> 12; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!needs(section, dark)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int b = i == from >> 12 ? from & 4095 : 0; b < 4096; b++) {
                int x = b & 15, z = b >> 4 & 15, y = b >> 8;
                BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                if (to == null) continue;
                if (changed >= limit) {
                    done = false;
                    next = i << 12 | b;
                    break sections;
                }
                if (!level.setBlock(p.set(x0 + x, y0 + y, z0 + z), to, FLAGS)) continue;
                changed++;
                BlockState now = section.getBlockState(x, y, z);
                if (now != to) {
                    // Another mod restored/replaced the block: position visited (continue from resume point), do not loop.
                    reverted++;
                    if (!revertLogged) {
                        revertLogged = true;
                        Incoming.LOG.warn("Blackout: {} was set to {}, but holds {} — another mod changes the block; such lamps are skipped", p, to, now);
                    }
                    continue;
                }
                if (!dark && signal(to)) signalled.add(p.immutable());
            }
        }
        for (BlockPos at : signalled) resignal(level, at);
        return new Pass(changed, done, next, reverted);
    }

    /**
     * Change blocks directly in the section palette: to the world a lamp and its twin are the same block except for light
     * ({@link GridLights#inPlace}), so {@code setBlock} with neighbor updates, heightmaps and Sable (≈10 µs per lamp
     * in the host's pack) is unnecessary; at the loaded-world edge (chunk in memory, neighbors unloaded) it is harmful — Sable
     * and neighbors would read and synchronously load a neighboring chunk. Light: {@code checkBlock} at position (decrease and increase
     * propagate to neighboring chunks too); clients: block change (to chunk recipients). At most {@code limit} lamps.
     * <p>
     * Signal lamps relit here reconcile separately ({@link #resignal}): their positions go to
     * {@code signalled}. Starts at {@code from} ({@link Pass#next} from previous pass). A pair that cannot change in the palette
     * (none among grid lamps — GameTest) converts through the world if neighbors are loaded ({@code neighbours});
     * otherwise skips until neighbors load.
     */
    public static Pass applyInPlace(ServerLevel level, LevelChunk chunk, boolean dark, int limit, int from,
                                    boolean neighbours, LongArrayList signalled) {
        LevelChunkSection[] sections = chunk.getSections();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        var light = level.getChunkSource().getLightEngine();
        int changed = 0, next = 0;
        boolean done = true;
        sections:
        for (int i = from >> 12; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!needs(section, dark)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(i));
            for (int b = i == from >> 12 ? from & 4095 : 0; b < 4096; b++) {
                int x = b & 15, z = b >> 4 & 15, y = b >> 8;
                BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                if (to == null) continue;
                if (changed >= limit) {
                    done = false;
                    next = i << 12 | b;
                    break sections;
                }
                BlockPos p = new BlockPos(x0 + x, y0 + y, z0 + z);
                if (GridLights.inPlace(to)) {
                    section.setBlockState(x, y, z, to);
                    light.checkBlock(p);
                    level.getChunkSource().blockChanged(p);
                } else if (!neighbours || !level.setBlock(p, to, FLAGS)) {
                    // At the edge, such a pair waits for the next trigger (load, cascade); grid lamps have no such pairs (GameTest).
                    continue;
                }
                if (!dark && signal(to)) signalled.add(p.asLong());
                changed++;
            }
        }
        if (changed > 0) chunk.setUnsaved(true);
        return new Pass(changed, done, next, 0);
    }

    /**
     * Align lamps of an outside-world chunk copy (distant Distant Horizons LOD) to grid state: {@code dark} — extinguish,
     * otherwise relight. Directly in copy sections, without light or neighbors. Server thread.
     *
     * @return number of lamps converted
     */
    public static int applyToCopy(LevelChunkSection[] sections, boolean dark) {
        int changed = 0;
        for (LevelChunkSection section : sections) {
            if (section == null || !needs(section, dark)) continue;
            for (int b = 0; b < 4096; b++) {
                int x = b & 15, z = b >> 4 & 15, y = b >> 8;
                BlockState to = GridLights.toward(section.getBlockState(x, y, z), dark);
                if (to == null) continue;
                section.setBlockState(x, y, z, to, false);
                changed++;
            }
        }
        return changed;
    }

    /**
     * Lit grid lamps in an outside-world chunk copy (any thread): positions {@code section index << 12 | block index} —
     * extinguish later ({@link #darken}) without section scans.
     */
    public static int[] litLamps(LevelChunkSection[] sections) {
        IntArrayList out = new IntArrayList();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || !needs(section, true)) continue;
            for (int b = 0; b < 4096; b++) {
                if (GridLights.isLit(section.getBlockState(b & 15, b >> 8, b >> 4 & 15))) out.add(i << 12 | b);
            }
        }
        return out.toIntArray();
    }

    /** Extinguish copy lamps at positions from {@link #litLamps} (directly in sections, no light or neighbors); number extinguished. */
    public static int darken(LevelChunkSection[] sections, int[] lamps) {
        int changed = 0;
        for (int at : lamps) {
            LevelChunkSection section = sections[at >>> 12];
            int x = at & 15, z = at >> 4 & 15, y = at >> 8 & 15;
            BlockState to = GridLights.unlit(section.getBlockState(x, y, z));
            if (to == null) continue;
            section.setBlockState(x, y, z, to, false);
            changed++;
        }
        return changed;
    }

    /** Convert one twin back to a lamp (twin tick: piston, craft). */
    public static void relight(ServerLevel level, BlockPos pos, BlockState twin) {
        BlockState lit = GridLights.lit(twin);
        if (lit == null || !level.setBlock(pos, lit, FLAGS)) return;
        if (signal(lit)) resignal(level, pos);
    }

    /** A signal-driven lamp (redstone, copper). */
    static boolean signal(BlockState state) {
        return state.getBlock() instanceof RedstoneLampBlock || state.getBlock() instanceof CopperBulbBlock;
    }

    /**
     * Signal lamp reconciles with the signal as on neighbor update (copper also toggles from it, as on
     * placement). Leave any other block at this position alone (lamp broken, district dark again).
     */
    static void resignal(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (signal(s)) s.handleNeighborChanged(level, pos, s.getBlock(), pos, false);
    }

    /** Sections contain extinguished lamps (by palettes). */
    static boolean anyUnlit(LevelChunkSection[] sections) {
        for (LevelChunkSection section : sections) {
            if (section != null && !section.hasOnlyAir() && Palettes.contains(section.getStates(), GridLights::isUnlit)) return true;
        }
        return false;
    }

    /** Section blocks outside a chunk (save copy): change directly in palette. Returns number of lamps converted. */
    static int apply(PalettedContainer<BlockState> states, boolean dark) {
        if (!Palettes.contains(states, s -> GridLights.needs(s, dark))) return 0;
        int changed = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState to = GridLights.toward(states.get(x, y, z), dark);
                    if (to != null) {
                        states.set(x, y, z, to);
                        changed++;
                    }
                }
            }
        }
        return changed;
    }
}
