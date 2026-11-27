package ua.zentix.incoming.client.map;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.fml.loading.LoadingModList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntUnaryOperator;
import java.util.function.LongToIntFunction;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review probe (PR #800): an oak forest on flat ground, mipped to Voxy's coarse levels by Voxy 0.2.16's own rule
 * ({@code Mipper.mip}: the non-air child with the greatest {@code opacity << 4 | x << 2 | y << 1 | z}; {@code Mapper.StateEntry}
 * forces leaves to opacity 15, the same as grass, dirt, stone and logs; {@code WorldVoxilizedSectionMipper.mipSection} child
 * order), then summarized by {@link VoxyColumns.Summary}. The dust ground ({@link TerrainSource.Column#ground}) of a coarse
 * column must stay within the voxel-midpoint error ({@code 2^L / 2}, plus 1.5 blocks for the cell mean over trunks) of the cell's mean dust ground the client chunks give
 * ({@code Terrain.dustTop} + 1) — the same error the column height already has.
 */
class VoxyCoarseCanopyProbeTest {
    private static final int AIR = 0, LEAF = 1, GRASS = 2, DIRT = 3, STONE = 4, LOG = 5;
    private static final LongToIntFunction ID = v -> (int) (v >>> 27 & 0xFFFFF);
    private static final IntUnaryOperator INFO = id -> switch (id) {
        case LEAF -> VoxyColumns.KNOWN | VoxyColumns.MOTION | VoxyColumns.LEAVES | MapColor.PLANT.id;
        case GRASS -> VoxyColumns.KNOWN | VoxyColumns.MOTION | VoxyColumns.GROUND | MapColor.GRASS.id;
        case DIRT -> VoxyColumns.KNOWN | VoxyColumns.MOTION | VoxyColumns.GROUND | MapColor.DIRT.id;
        case STONE -> VoxyColumns.KNOWN | VoxyColumns.MOTION | VoxyColumns.GROUND | MapColor.STONE.id;
        case LOG -> VoxyColumns.KNOWN | VoxyColumns.MOTION | VoxyColumns.GROUND | MapColor.WOOD.id;
        default -> 0;
    };
    private static final int SIDE = 16, BOTTOM = 0, HEIGHT = 128;
    /** Trunk positions in the 16×16 cell. */
    private static final int[][] TREES = {{3, 3}, {11, 4}, {6, 11}, {13, 13}};

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        if (LoadingModList.get() == null) LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        Bootstrap.bootStrap();
    }

    /** Blocks [x][y][z] of a 16×16 forest cell whose grass top is at {@code g}. */
    private static int[][][] forest(int g) {
        int[][][] b = new int[SIDE][HEIGHT][SIDE];
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                for (int y = BOTTOM; y < g - 3; y++) b[x][y][z] = STONE;
                for (int y = g - 3; y < g; y++) b[x][y][z] = DIRT;
                b[x][g][z] = GRASS;
            }
        }
        for (int[] t : TREES) {
            for (int dy = 3; dy <= 6; dy++) {
                int r = dy <= 4 ? 2 : 1;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int x = t[0] + dx, z = t[1] + dz;
                        if (x < 0 || z < 0 || x >= SIDE || z >= SIDE) continue;
                        if (b[x][g + dy][z] == AIR) b[x][g + dy][z] = LEAF;
                    }
                }
            }
            for (int dy = 1; dy <= 4; dy++) b[t[0]][g + dy][t[1]] = LOG;
        }
        return b;
    }

    /** Dust ground of one block column as the client chunks give it: Terrain.belowCanopy over the MOTION_BLOCKING top, + 1. */
    private static int dustGround(int[][][] b, int x, int z) {
        int top = HEIGHT - 1;
        while (top > BOTTOM && b[x][top][z] == AIR) top--;
        if (b[x][top][z] != LEAF) return top + 1;
        int y = top;
        while (y > BOTTOM && (b[x][y][z] == AIR || b[x][y][z] == LEAF)) y--;
        return y + 1;
    }

    /** One Voxy mip step: [x][y][z] of side n to side n/2. */
    private static int[][][] mip(int[][][] v) {
        int nx = v.length / 2, ny = v[0].length / 2, nz = v[0][0].length / 2;
        int[][][] out = new int[nx][ny][nz];
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    int best = -1, id = AIR;
                    for (int dx = 0; dx < 2; dx++) {
                        for (int dy = 0; dy < 2; dy++) {
                            for (int dz = 0; dz < 2; dz++) {
                                int c = v[2 * x + dx][2 * y + dy][2 * z + dz];
                                if (c == AIR) continue;
                                int p = 15 << 4 | dx << 2 | dy << 1 | dz;
                                if (p > best) {
                                    best = p;
                                    id = c;
                                }
                            }
                        }
                    }
                    out[x][y][z] = id;
                }
            }
        }
        return out;
    }

    /** Level-{@code level} Voxy column over the cell's corner (ox, oz) of side 2^level, summarized. */
    private static TerrainSource.Column coarse(int[][][] b, int level, int ox, int oz) {
        int n = 1 << level;
        int[][][] v = new int[n][HEIGHT][n];
        for (int x = 0; x < n; x++) for (int z = 0; z < n; z++) for (int y = 0; y < HEIGHT; y++) v[x][y][z] = b[ox + x][y][oz + z];
        for (int l = 0; l < level; l++) v = mip(v);
        TreeMap<Integer, long[]> sections = new TreeMap<>();
        for (int vy = 0; vy < v[0].length; vy++) {
            if (v[0][vy][0] == AIR) continue;
            long[] s = sections.computeIfAbsent(vy >> 5, k -> new long[VoxyColumns.VOLUME]);
            s[(vy & 31) << 10] = (long) v[0][vy][0] << 27;
        }
        VoxyColumns.Summary s = new VoxyColumns.Summary(level);
        for (int sy = 3; sy >= 0 && !s.done(); sy--) s.section(sy, sections.get(sy), ID, INFO);
        return LssColumns.column(s.finish()[0]);
    }

    @Test
    void coarseCanopyGroundStaysWithinTheVoxelError() {
        List<String> bad = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        for (int level : new int[] {1, 2, 4}) {
            int n = 1 << level;
            double worst = 0, worstOld = 0;
            int known = 0, all = 0;
            for (int g = 56; g <= 79; g++) {
                int[][][] b = forest(g);
                for (int ox = 0; ox < SIDE; ox += n) {
                    for (int oz = 0; oz < SIDE; oz += n) {
                        double real = 0;
                        for (int x = ox; x < ox + n; x++) for (int z = oz; z < oz + n; z++) real += dustGround(b, x, z);
                        real /= n * n;
                        TerrainSource.Column c = coarse(b, level, ox, oz);
                        all++;
                        // an unknown column (no air voxel proves the gap) leaves the cell to DustGround's nearest known ground
                        if (Double.isNaN(c.ground())) continue;
                        known++;
                        double err = c.ground() - real, old = c.height() - real;
                        if (Math.abs(err) > Math.abs(worst)) worst = err;
                        if (Math.abs(old) > Math.abs(worstOld)) worstOld = old;
                        if (Math.abs(err) > n / 2.0 + 1.5) bad.add(String.format(Locale.ROOT,
                                "L%d g=%d cell(%d,%d): real floor %.1f, PR ground %.1f (canopy %d), old top %d", level, g, ox, oz, real,
                                c.ground(), c.canopy(), c.height()));
                    }
                }
            }
            log.append(String.format(Locale.ROOT, "level %d: known %d of %d, worst ground error %.1f m, worst old top error %.1f m%n",
                    level, known, all, worst, worstOld));
            // level 1 proves most of this forest's canopies (73 % known at review 2 of #800); a rule that made every coarse
            // column unknown would pass the error check alone
            if (level == 1) assertTrue(known >= 0.6 * all, "level 1 knows " + known + " of " + all);
        }
        System.out.println(log);
        bad.stream().forEach(System.out::println);
        System.out.println(bad.size() + " cells beyond the voxel error");
        assertTrue(bad.isEmpty(), log + "\n" + bad.size() + " cells beyond the voxel error, e.g. " + bad.subList(0, Math.min(5, bad.size())));
    }
}
