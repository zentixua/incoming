package ua.zentix.incoming.compat;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Lighting of a chunk copy outside the world (ruins, detonation scorch, district lamps — {@code nuclear/world/FarLods})
 * for Voxy LODs: a copy has no light (the world has not computed it), and Voxy takes voxel light as is — without it LODs
 * are black; with disk lighting, a dark district's old lamps would still glow. Computed by vanilla rules within one chunk
 * (Distant Horizons computes its own LOD light the same way, {@code DhLightingEngine}): block light comes from block
 * emission; skylight travels down through transparent blocks at 15 without loss, then in all six directions losing at
 * least 1 per block, or the opacity of translucent material (water, leaves). Deviations from vanilla: neighboring chunks
 * do not shine inward (neighbor light is absent at chunk edges — fractions of a block at LOD distance); block shape is
 * ignored (an opaque slab attenuates like a full block).
 */
public final class CopyLight {
    /** Light at each position: {@code [section][y·256 + z·16 + x]}, 0–15. */
    final byte[][] block, sky;
    private final int sections;

    private CopyLight(int sections) {
        this.sections = sections;
        block = new byte[sections][4096];
        sky = new byte[sections][4096];
    }

    /**
     * Chunk section light from bottom to top ({@code sections[i]} — section {@code minSection + i}; null — air).
     *
     * @param hasSky whether the dimension has skylight ({@code DimensionType.hasSkyLight}); if not, skylight is 0
     */
    public static CopyLight of(LevelChunkSection[] sections, boolean hasSky) {
        CopyLight l = new CopyLight(sections.length);
        int cells = sections.length * 4096;
        // opacity (0–15) and emission at each position: hundreds of states in a chunk, hundreds of thousands of positions
        byte[] opacity = new byte[cells];
        byte[] emission = new byte[cells];
        Map<BlockState, Integer> props = new IdentityHashMap<>();
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection section = sections[s];
            if (section == null || section.hasOnlyAir()) continue;
            for (int i = 0; i < 4096; i++) {
                BlockState state = section.getBlockState(i & 15, i >> 8, i >> 4 & 15);
                int p = props.computeIfAbsent(state, CopyLight::properties);
                opacity[s * 4096 + i] = (byte) (p & 15);
                emission[s * 4096 + i] = (byte) (p >> 4 & 15);
            }
        }
        int[] queue = new int[cells];
        if (hasSky) l.skyLight(opacity, queue);
        l.blockLight(opacity, emission, queue);
        return l;
    }

    /** State opacity (bits 0–3) and emission (4–7); skylight traveling down without loss is bit 8. */
    private static int properties(BlockState state) {
        int opacity = Math.max(0, Math.min(15, state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        int emission = Math.max(0, Math.min(15, state.getLightEmission(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
        return opacity | emission << 4;
    }

    private int get(byte[][] layer, int cell) {
        return layer[cell >> 12][cell & 4095];
    }

    private void skyLight(byte[] opacity, int[] queue) {
        int height = sections * 16, tail = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int light = 15;
                for (int y = height - 1; y >= 0; y--) {
                    int cell = cell(x, y, z), o = opacity[cell];
                    // 15 travels down through transparent blocks without loss; lower values lose at least 1 per block
                    light = light == 15 && o == 0 ? 15 : Math.max(0, light - Math.max(1, o));
                    if (light == 0) break;
                    sky[cell >> 12][cell & 4095] = (byte) light;
                    queue[tail++] = cell;
                }
            }
        }
        spread(sky, opacity, queue, tail);
    }

    private void blockLight(byte[] opacity, byte[] emission, int[] queue) {
        int tail = 0;
        for (int cell = 0; cell < emission.length; cell++) {
            if (emission[cell] <= 0) continue;
            block[cell >> 12][cell & 4095] = emission[cell];
            queue[tail++] = cell;
        }
        spread(block, opacity, queue, tail);
    }

    /**
     * Light from queued positions spreads in all six directions within the chunk, losing at least 1 per block (the opacity
     * for translucent material): a position is queued again only when its light increases (at most 15 times).
     */
    private void spread(byte[][] layer, byte[] opacity, int[] seeds, int count) {
        int height = sections * 16;
        IntArrayFIFOQueue ring = new IntArrayFIFOQueue(Math.max(16, count));
        for (int i = 0; i < count; i++) ring.enqueue(seeds[i]);
        while (!ring.isEmpty()) {
            int cell = ring.dequeueInt(), light = get(layer, cell);
            if (light <= 1) continue;
            int x = cell & 15, z = cell >> 4 & 15, y = cell >> 8;
            for (int d = 0; d < 6; d++) {
                int nx = x + DX[d], ny = y + DY[d], nz = z + DZ[d];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15 || ny < 0 || ny >= height) continue;
                int next = cell(nx, ny, nz);
                int lit = light - Math.max(1, opacity[next]);
                if (lit <= get(layer, next)) continue;
                layer[next >> 12][next & 4095] = (byte) lit;
                ring.enqueue(next);
            }
        }
    }

    private static final int[] DX = {1, -1, 0, 0, 0, 0}, DY = {0, 0, 1, -1, 0, 0}, DZ = {0, 0, 0, 0, 1, -1};

    /**
     * Position within a chunk: y from the bottom of the first section; index within a section as in {@link DataLayer}
     * and palettes (y·256 + z·16 + x).
     */
    static int cell(int x, int y, int z) {
        return (y >> 4) << 12 | (y & 15) << 8 | z << 4 | x;
    }

    /** Light at a position (x, y from the bottom of the first section, z). */
    int block(int x, int y, int z) {
        return get(block, cell(x, y, z));
    }

    int sky(int x, int y, int z) {
        return get(sky, cell(x, y, z));
    }

    /** Block light of section {@code i} as a Minecraft layer; null — dark everywhere. */
    @Nullable
    public DataLayer blockLayer(int i) {
        return layer(block[i]);
    }

    /** Skylight of section {@code i}; null — dark everywhere (underground or without a sky). */
    @Nullable
    public DataLayer skyLayer(int i) {
        return layer(sky[i]);
    }

    /** Section {@code i} is entirely under open sky (skylight 15 everywhere). */
    public boolean fullSky(int i) {
        for (byte b : sky[i]) if (b != 15) return false;
        return true;
    }

    @Nullable
    private static DataLayer layer(byte[] light) {
        boolean any = false;
        for (byte b : light) if (b != 0) {
            any = true;
            break;
        }
        if (!any) return null;
        DataLayer out = new DataLayer();
        for (int i = 0; i < 4096; i++) if (light[i] != 0) out.set(i & 15, i >> 8, i >> 4 & 15, light[i]);
        return out;
    }
}
