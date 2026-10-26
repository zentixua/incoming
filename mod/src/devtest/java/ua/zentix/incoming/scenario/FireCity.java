package ua.zentix.incoming.scenario;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;

import java.util.ArrayList;
import java.util.List;

/**
 * Detailed city for measuring the client's fire ({@code FireWatch}, line {@code SCENARIO nuke-sound … watch}): from tick
 * {@code T} in each client chunk the surface section and {@code N − 1} below it receive {@link #DISTINCT} of different states
 *  in the two lower layers - the section palette global, as in the detailed city, and {@code maybeHas} answers “yes” to any
 *  question. Only in the client’s copy of the world: the server does not know it, and the picture of the sections is not rebuilt. Property
 * {@code incoming.scenario.city=N@T} (in the cloud - variable {@code INCOMING_CITY}). For comparison, once per
 * {@link #OLD_EVERY} tick (and before tick {@code T}) is the price of the previous fire count ({@code PalettedContainer.count} of each section with fire in the palette,
 * 16 chunks per tick): line {@code SCENARIO city old-scan}.
 */
final class FireCity {
    /** Different states in the section: more 256 - global palette. */
    private static final int DISTINCT = 300;
    /** Chunks per tick. */
    private static final int PER_TICK = 32;
    /** The previous count of fire: chunks per tick and times per tick to measure it. */
    private static final int OLD_CHUNKS = 16, OLD_EVERY = 20;

    private final int sections, from;
    private final LongOpenHashSet done = new LongOpenHashSet();
    private final List<BlockState> states = new ArrayList<>();
    private int logged, oldCursor;

    private FireCity(int sections, int from) {
        this.sections = sections;
        this.from = from;
    }

    /** From property {@code N@T} (or {@code N} - from the beginning); null - no city. */
    @Nullable
    static FireCity of(@Nullable String spec) {
        if (spec == null || spec.isBlank()) return null;
        String[] p = spec.strip().split("@");
        try {
            int n = Integer.parseInt(p[0].strip()), t = p.length > 1 ? Integer.parseInt(p[1].strip()) : 0;
            return n > 0 ? new FireCity(n, t) : null;
        } catch (NumberFormatException e) {
            Incoming.LOG.warn("SCENARIO city: “{}” is missing - you need N or N@tick", spec);
            return null;
        }
    }

    void tick(int tick) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        if (level == null || p == null) return;
        int r = mc.options.getEffectiveRenderDistance(), cx = p.chunkPosition().x, cz = p.chunkPosition().z, made = 0;
        if (tick % OLD_EVERY == 0) oldScan(level, cx, cz, r);
        if (tick < from) return;
        if (states.isEmpty()) {
            for (BlockState s : Block.BLOCK_STATE_REGISTRY) {
                if (states.size() == DISTINCT) break;
                if (!s.isAir() && !(s.getBlock() instanceof BaseFireBlock)) states.add(s);
            }
        }
        for (int dz = -r; dz <= r && made < PER_TICK; dz++) {
            for (int dx = -r; dx <= r && made < PER_TICK; dx++) {
                long key = ChunkPos.asLong(cx + dx, cz + dz);
                if (done.contains(key)) continue;
                LevelChunk c = level.getChunkSource().getChunk(cx + dx, cz + dz, false);
                if (c == null) continue;
                // down from the surface: the air above it (and the viewer in it) is not touched
                int top = c.getSectionIndex(Math.max(level.getMinBuildHeight(), c.getHeight(Heightmap.Types.MOTION_BLOCKING, 8, 8) - 1));
                for (int i = Math.max(0, top - sections + 1); i <= top; i++) {
                    LevelChunkSection s = c.getSection(i);
                    for (int k = 0; k < DISTINCT; k++) s.setBlockState(k & 15, k >> 8, (k >> 4) & 15, states.get(k), false);
                }
                done.add(key);
                made++;
            }
        }
        if (done.size() >= logged + 200) {
            logged = done.size();
            Incoming.LOG.info("SCENARIO city: {} chunks with {} global-palette sections", logged, sections);
        }
    }

    /** The price of the previous fire count (before sampling under the budget) on the same chunks: 16 chunks in a row in a drawing circle. */
    private void oldScan(ClientLevel level, int cx, int cz, int r) {
        int side = 2 * r + 1, all = side * side, scanned = 0, fire = 0;
        long t0 = System.nanoTime();
        for (int k = 0; k < OLD_CHUNKS; k++) {
            int i = oldCursor++ % all;
            LevelChunk c = level.getChunkSource().getChunk(cx - r + i % side, cz - r + i / side, false);
            if (c == null) continue;
            for (LevelChunkSection s : c.getSections()) {
                if (s.hasOnlyAir() || !s.maybeHas(st -> st.getBlock() instanceof BaseFireBlock)) continue;
                scanned++;
                int[] n = {0};
                s.getStates().count((st, count) -> {
                    if (st.getBlock() instanceof BaseFireBlock) n[0] += count;
                });
                fire += n[0];
            }
        }
        Incoming.LOG.info(String.format(java.util.Locale.ROOT, "SCENARIO city old-scan %d chunks: %.3f ms, sections %d, fire %d",
                OLD_CHUNKS, (System.nanoTime() - t0) / 1e6, scanned, fire));
    }
}
