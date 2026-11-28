package ua.zentix.incoming.util;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Whether a small box moving one step meets no block: the shortcut in front of vanilla's block sweep
 * ({@code Entity.collideBoundingBox} with no entity, as {@code Particle.move} calls it) for thousands of particles and
 * debris pieces a tick. A piece in open air needs no sweep: the sweep builds shapes and lists per call (Lithium's
 * {@code ChunkAwareBlockCollisionSweeper}), which a nuclear wave's 1 200 debris pieces and ash flakes paid every tick
 * (5 % of the render thread during the passage of a 300 kt front).
 * <p>
 * Conservative: true only when every block the sweep may test (the swept box widened by one block, as vanilla's
 * {@code BlockCollisions} does for shapes taller than a block) is air or has an empty collision shape; a section that
 * holds only air answers for all its blocks at once. Unloaded chunks hold nothing, as for the sweep
 * ({@code getChunkForCollisions}). With true, the sweep would return the motion unchanged; with false, ask it.
 * Never reads unloaded chunks ({@code getChunkNow}).
 */
public final class OpenBox {
    private OpenBox() {}

    /** Box (minX..maxZ) moving by (dx, dy, dz) in {@code level}: true — no block collision shape anywhere it may touch. */
    public static boolean clear(Level level, double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                double dx, double dy, double dz) {
        int x0 = Mth.floor(Math.min(minX, minX + dx) - 1.0E-7) - 1, x1 = Mth.floor(Math.max(maxX, maxX + dx) + 1.0E-7) + 1;
        int y0 = Mth.floor(Math.min(minY, minY + dy) - 1.0E-7) - 1, y1 = Mth.floor(Math.max(maxY, maxY + dy) + 1.0E-7) + 1;
        int z0 = Mth.floor(Math.min(minZ, minZ + dz) - 1.0E-7) - 1, z1 = Mth.floor(Math.max(maxZ, maxZ + dz) + 1.0E-7) + 1;
        // a step this long is not a particle's: leave it to the sweep
        if (x1 - x0 > 32 || y1 - y0 > 32 || z1 - z0 > 32) return false;
        y0 = Math.max(y0, level.getMinBuildHeight());
        y1 = Math.min(y1, level.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) continue;
                int ax = Math.max(x0, cx << 4), bx = Math.min(x1, (cx << 4) + 15), az = Math.max(z0, cz << 4), bz = Math.min(z1, (cz << 4) + 15);
                for (int y = y0; y <= y1; ) {
                    LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                    int top = Math.min(y1, (y >> 4 << 4) + 15);
                    if (!section.hasOnlyAir()) {
                        for (int yy = y; yy <= top; yy++) {
                            for (int x = ax; x <= bx; x++) {
                                for (int z = az; z <= bz; z++) {
                                    BlockState s = section.getBlockState(x & 15, yy & 15, z & 15);
                                    if (s.isAir()) continue;
                                    if (!s.getCollisionShape(level, pos.set(x, yy, z)).isEmpty()) return false;
                                }
                            }
                        }
                    }
                    y = top + 1;
                }
            }
        }
        return true;
    }
}
