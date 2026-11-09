package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.ChunkSkyLightSources;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Detached blocks and vanilla's actual block-light solver: no hand-maintained opacity or emitter table. */
public final class BlockLightReference implements BlockGetter, LightChunkGetter {
    public final Map<ChunkPos, Tile> tiles = new LinkedHashMap<>();
    private final int bottom, height;

    public BlockLightReference(int bottom, int height) { this.bottom = bottom; this.height = height; }

    public Tile add(ChunkPos pos) {
        Tile tile = new Tile(pos);
        tiles.put(pos, tile);
        return tile;
    }

    /** Capture a loaded square, with an extra complete chunk beyond the region being compared. */
    public static BlockLightReference capture(ServerLevel level, ChunkPos center, int radius) {
        BlockLightReference result = new BlockLightReference(level.getMinBuildHeight(), level.getHeight());
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int z = center.z - radius; z <= center.z + radius; z++) for (int x = center.x - radius; x <= center.x + radius; x++) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
            if (chunk == null) throw new IllegalStateException("Reference capture needs ready chunk " + x + "," + z);
            Tile tile = result.add(new ChunkPos(x, z));
            for (int i = 0; i < tile.blocks.length; i++) {
                p.set(x * 16 + (i & 15), result.bottom + i / 256, z * 16 + (i / 16 & 15));
                tile.blocks[i] = chunk.getBlockState(p);
                tile.stored[i] = (byte) level.getBrightness(LightLayer.BLOCK, p);
            }
        }
        return result;
    }

    public record Difference(int cells, int over, int under, String examples) {}

    public Difference compare(ChunkPos center, int radius) {
        LevelLightEngine reference = engine(false);
        int total = 0, over = 0, under = 0;
        StringBuilder examples = new StringBuilder();
        for (Tile tile : tiles.values()) {
            if (Math.abs(tile.pos.x - center.x) > radius || Math.abs(tile.pos.z - center.z) > radius) continue;
            for (int i = 0; i < tile.blocks.length; i++) {
                BlockPos p = tile.position(i);
                int actual = tile.stored[i], expected = reference.getLayerListener(LightLayer.BLOCK).getLightValue(p);
                total++;
                if (actual == expected) continue;
                if (over + under < 12) examples.append(p).append(':').append(actual).append('/').append(expected).append(' ');
                if (actual > expected) over++; else under++;
            }
        }
        return new Difference(total, over, under, examples.toString());
    }

    @Override public int getMinBuildHeight() { return bottom; }
    @Override public int getHeight() { return height; }
    @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
    @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
    @Override public BlockState getBlockState(BlockPos p) {
        Tile tile = tiles.get(new ChunkPos(p));
        return tile == null ? Blocks.BEDROCK.defaultBlockState() : tile.getBlockState(p);
    }
    @Override public LightChunk getChunkForLighting(int x, int z) { return tiles.get(new ChunkPos(x, z)); }
    @Override public BlockGetter getLevel() { return this; }

    /** Fresh solver or saved arrays on the same blocks; every section allocated, including all-air sections. */
    public LevelLightEngine engine(boolean saved) {
        LevelLightEngine light = new LevelLightEngine(this, true, false);
        for (Tile tile : tiles.values()) {
            for (int sy = getMinSection(); sy < getMaxSection(); sy++) {
                SectionPos section = SectionPos.of(tile.pos, sy);
                light.updateSectionStatus(section, false);
                if (saved) {
                    DataLayer data = new DataLayer();
                    for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        data.set(x, y, z, tile.stored[((sy * 16 + y - bottom) * 256) + z * 16 + x]);
                    }
                    light.queueSectionData(LightLayer.BLOCK, section, data);
                }
            }
            light.setLightEnabled(tile.pos, true);
        }
        drain(light);
        if (!saved) {
            for (Tile tile : tiles.values()) light.propagateLightSources(tile.pos);
            drain(light);
        }
        return light;
    }

    public static void drain(LevelLightEngine light) {
        int count = 0;
        while (light.hasLightWork()) {
            light.runLightUpdates();
            if (++count > 100) throw new IllegalStateException("Detached light engine did not drain");
        }
    }

    public final class Tile implements LightChunk {
        public final ChunkPos pos;
        public final BlockState[] blocks = new BlockState[height * 256];
        public final byte[] stored = new byte[height * 256];
        Tile(ChunkPos pos) { this.pos = pos; java.util.Arrays.fill(blocks, Blocks.AIR.defaultBlockState()); }
        public int index(BlockPos p) { return (p.getY() - bottom) * 256 + (p.getZ() & 15) * 16 + (p.getX() & 15); }
        public BlockPos position(int i) { return new BlockPos(pos.getMinBlockX() + (i & 15), bottom + i / 256, pos.getMinBlockZ() + (i / 16 & 15)); }
        @Override public int getMinBuildHeight() { return bottom; }
        @Override public int getHeight() { return height; }
        @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
        @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        @Override public BlockState getBlockState(BlockPos p) {
            if (p.getY() < bottom || p.getY() >= bottom + height) return Blocks.AIR.defaultBlockState();
            if (!new ChunkPos(p).equals(pos)) return BlockLightReference.this.getBlockState(p);
            return blocks[index(p)];
        }
        @Override public ChunkSkyLightSources getSkyLightSources() { return null; }
        @Override public void findBlockLightSources(BiConsumer<BlockPos, BlockState> output) {
            for (int i = 0; i < blocks.length; i++) {
                BlockPos p = position(i);
                if (blocks[i].getLightEmission(BlockLightReference.this, p) > 0) output.accept(p, blocks[i]);
            }
        }
    }
}
