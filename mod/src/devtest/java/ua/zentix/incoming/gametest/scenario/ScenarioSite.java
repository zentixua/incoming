package ua.zentix.incoming.gametest.scenario;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.util.Terrain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleBiFunction;

/**
 * Scenario site in the world GameTest (flat world): its place on the grid away from test sites and from other scenarios,
 *  areas that tick from the first tick of flight, and terrain (hill, pit).
 * <p>
 * Determinism. The location is the same in any run (it is set by the script cell, not the order of the tests): the flight has the same
 *  floating point numbers and the same chunk grid. Districts hold their own region ticket, their chunks are generated immediately
 * ({@code level.getChunk}), and the flight begins only when they all tick: where the projectile will return to the world and where
 *  will leave it does not depend on the background generation rate. The projectile areas (target - {@code FlightTickets}, own chunks -
 * {@code ChunkTickets}) lie inside the site areas and do not load anything new.
 */
final class ScenarioSite {
    private static final TicketType<ChunkPos> ZONE = TicketType.create("incoming_test_scenario", Comparator.comparingLong(ChunkPos::toLong));
    /** The pitch of the grid of sites: further than the longest projectile path (B-2 - up to 9,7 thousand blocks) plus target teleport. */
    private static final int SPACING = 24576;
    private static final int PER_ROW = 64;
    /** Start of the grid: far from the world spawn point and within the limits where the coordinates still have many signs. */
    private static final int ORIGIN = 2_000_000;
    /** Target area: entities tick in 11×11 chunks (±88 blocks), loaded 15×15 - wider than the projectile areas at the target. */
    static final int TARGET_ZONE = 7;
    /** Launcher area: ticking 5×5 chunks. */
    static final int LAUNCH_ZONE = 4;
    /** Corridor over the hill: a strip of ticking chunks 3 wide, loaded with 9. */
    static final int CORRIDOR_ZONE = 4;

    private record Zone(ChunkPos centre, int distance) {}

    private final ServerLevel level;
    /** The middle of the area on the ground: the target point. */
    final Vec3 origin;
    private final List<Zone> zones = new ArrayList<>();

    ScenarioSite(ServerLevel level, int cell) {
        this.level = level;
        int cx = (ORIGIN + (cell % PER_ROW) * SPACING) >> 4, cz = (ORIGIN + (cell / PER_ROW) * SPACING) >> 4;
        ChunkPos c = new ChunkPos(cx, cz);
        this.origin = ground(c.getMiddleBlockX() + 0.5, c.getMiddleBlockZ() + 0.5, true);
    }

    /** A point on the ground of a flat world (without a ready-made chunk - according to the generator: the height is the same). */
    Vec3 ground(double x, double z, boolean generator) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        int y = !generator && Terrain.ready(level, bx >> 4, bz >> 4) ? Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, bx, bz)
                : level.getChunkSource().getGenerator().getBaseHeight(bx, bz, Heightmap.Types.MOTION_BLOCKING, level, level.getChunkSource().randomState());
        return new Vec3(x, y, z);
    }

    /** The region with the center in the point chunk: ticket of the region of level {@code 33 − distance}. */
    void zone(Vec3 at, int distance) {
        zones.add(new Zone(new ChunkPos(BlockPos.containing(at)), distance));
    }

    /** An area taken in the middle of a flight: ticket immediately (chunks - {@link InstantChunks} at the end of the tick or background generation). */
    void zoneNow(Vec3 at, int distance) {
        Zone z = new Zone(new ChunkPos(BlockPos.containing(at)), distance);
        zones.add(z);
        level.getChunkSource().addRegionTicket(ZONE, z.centre, z.distance, z.centre);
        // into the chunk levels immediately, like ticket mod (AreaLoader.apply): otherwise, in the middle of the flight, someone would have to use them
        level.getChunkSource().runDistanceManagerUpdates();
    }

    /** District corridor in increments of two chunks from {@code from} to {@code to}. */
    void corridor(Vec3 from, Vec3 to) {
        double length = from.subtract(to).horizontalDistance();
        for (double d = 0; d <= length; d += 32) zone(from.lerp(to, d / length), CORRIDOR_ZONE);
    }

    /** Take areas and generate their chunks at once. */
    void load() {
        for (Zone z : zones) level.getChunkSource().addRegionTicket(ZONE, z.centre, z.distance, z.centre);
        for (Zone z : zones) {
            for (int dx = -z.distance; dx <= z.distance; dx++)
                for (int dz = -z.distance; dz <= z.distance; dz++) level.getChunk(z.centre.x + dx, z.centre.z + dz);
        }
    }

    /** All areas are ticking (entities), and the chunks around them are ready: the flight can begin. */
    boolean ticking() {
        for (Zone z : zones) {
            int r = z.distance - 2;
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++) {
                    ChunkPos c = new ChunkPos(z.centre.x + dx, z.centre.z + dz);
                    if (!Terrain.ready(level, c.x, c.z) || !level.isPositionEntityTicking(c.getMiddleBlockPosition(0))) return false;
                }
        }
        return true;
    }

    void release() {
        for (Zone z : zones) level.getChunkSource().removeRegionTicket(ZONE, z.centre, z.distance, z.centre);
    }

    /**
     * Hill across the course: ridge in {@code place} blocks from the target opposite the approach direction, height {@code height},
     * slopes 1: 1,5; width along the ridge 20 blocks. Only within the corridor (±48 blocks from the course).
     */
    void hill(Vec3 approach, double place, double height) {
        Vec3 crest = origin.subtract(approach.scale(place));
        double run = height * 1.5;
        relief(crest, run + 1, 48, (u, v) -> height - Math.max(Math.abs(u), Math.max(0, Math.abs(v) - 10)) / 1.5, approach);
    }

    /** Pit: flat bottom with a radius of {@code bottom} around the target, slopes 1: 2 to the depth of {@code depth}, edge of the block 3. */
    void quarry(double bottom, double depth) {
        double rim = bottom + depth * 2, outer = rim + 3;
        relief(origin, outer, outer, (u, v) -> {
            double r = Math.sqrt(u * u + v * v);
            return r > outer ? 0 : r >= rim ? depth : Math.max(0, (r - bottom) / 2);
        }, new Vec3(1, 0, 0));
    }

    /**
     * Relief height {@code h(u, v)} above the ground around {@code centre} ({@code u} - along {@code along}, {@code v} -
     *  across). Blocks only on the outside: a pillar from the top to the level of the lowest neighbor - walls without cracks, empty inside
     *  (the height map, collisions and flight over the terrain only need the top). No neighbor updates.
     */
    private void relief(Vec3 centre, double halfU, double halfV, ToDoubleBiFunction<Double, Double> h, Vec3 along) {
        int span = (int) Math.ceil(Math.max(halfU, halfV) * Math.sqrt(2)) + 1;
        int ox = Mth.floor(centre.x), oz = Mth.floor(centre.z);
        int size = span * 2 + 1;
        int[][] height = new int[size][size];
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++) {
                double x = i - span + 0.5, z = j - span + 0.5;
                double u = x * along.x + z * along.z, v = -x * along.z + z * along.x;
                height[i][j] = Math.abs(u) > halfU || Math.abs(v) > halfV ? 0 : Math.max(0, (int) Math.round(h.applyAsDouble(u, v)));
            }
        int groundY = Mth.floor(origin.y);
        for (int i = 0; i < size; i++)
            for (int j = 0; j < size; j++) {
                int top = height[i][j];
                if (top == 0) continue;
                int low = top;
                for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int a = i + n[0], b = j + n[1];
                    low = Math.min(low, a < 0 || b < 0 || a >= size || b >= size ? 0 : height[a][b]);
                }
                int x = ox + i - span, z = oz + j - span;
                if (!Terrain.ready(level, x >> 4, z >> 4)) continue;
                for (int y = Math.max(0, low - 1); y < top; y++) {
                    level.setBlock(new BlockPos(x, groundY + y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
    }
}
