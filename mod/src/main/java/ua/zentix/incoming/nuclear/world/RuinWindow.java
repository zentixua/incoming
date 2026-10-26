package ua.zentix.incoming.nuclear.world;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Original world around a chunk — chunk and 8 neighbors (48×48 columns), as before this burst: ruined neighbors use
 * old blocks at plan positions; fire and flowing water absent originally count as air. Missing neighbors are solid,
 * unbreakable mass. Window coordinates: {@code wx, wz} 0 to 47, world y.
 * <p>
 * Built from chunk snapshots ({@link ChunkShot}) on any thread: window does not read the world. Remembers source
 * snapshots ({@link #stamp}).
 */
final class RuinWindow {
    static final int SIDE = 48;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** Missing window data: unbreakable, supporting mass. */
    static final BlockState MASS = Blocks.BEDROCK.defaultBlockState();

    final ChunkPos centre;
    final int minY, maxY, x0, z0;
    final Blast.PropsView view;
    private final Blast.Props airProps, massProps;
    private final ChunkShot[] shots;
    /** Position properties by window chunk sections: {@code k * sections + section} (from snapshots, populated as read). */
    private final ChunkShot.Sec[] props;
    private final int sections;
    /** Original column top (highest non-air) and support top (as {@code MOTION_BLOCKING_NO_LEAVES} − 1); MIN — empty. */
    private final int[] top = new int[SIDE * SIDE], solid = new int[SIDE * SIDE];

    /**
     * @param shots chunk and neighbor snapshots (index {@code k = (dz + 1) * 3 + dx + 1}); null — missing chunk
     */
    RuinWindow(ChunkPos centre, int minY, int maxY, ChunkShot[] shots, Blast.PropsView view) {
        this.centre = centre;
        this.x0 = centre.getMinBlockX() - 16;
        this.z0 = centre.getMinBlockZ() - 16;
        this.minY = minY;
        this.maxY = maxY;
        this.shots = shots;
        this.view = view;
        this.airProps = view.get(AIR);
        this.massProps = view.get(MASS);
        this.sections = (maxY - minY) >> 4;
        this.props = new ChunkShot.Sec[9 * sections];
        for (int k = 0; k < 9; k++) {
            ChunkShot c = shots[k];
            int bx = (k % 3) << 4, bz = (k / 3) << 4;
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int i = (bz + lz) * SIDE + bx + lx;
                    if (c == null) {
                        top[i] = solid[i] = maxY - 1;
                        continue;
                    }
                    int t = c.surface[lz << 4 | lx], s = c.solid[lz << 4 | lx];
                    if (c.ruined) {
                        // ruins below original: top from old plan-position blocks; above original (fire, rubble in former air) — descend
                        // to an original block
                        if (c.oldTops != null) {
                            t = Math.max(t, c.oldTops[lz << 4 | lx]);
                            s = Math.max(s, c.oldTops[256 + (lz << 4 | lx)]);
                        }
                        int wx = bx + lx, wz = bz + lz;
                        while (t >= minY && get(wx, t, wz).isAir()) t--;
                        while (s >= minY && !Heightmap.Types.MOTION_BLOCKING_NO_LEAVES.isOpaque().test(get(wx, s, wz))) s--;
                    }
                    top[i] = t < minY ? Integer.MIN_VALUE : t;
                    solid[i] = s < minY ? Integer.MIN_VALUE : s;
                }
            }
        }
    }

    /** Original state at window position. */
    BlockState get(int wx, int y, int wz) {
        if (y < minY || y >= maxY) return AIR;
        if (wx < 0 || wx >= SIDE || wz < 0 || wz >= SIDE) return MASS;
        ChunkShot s = shots[(wz >> 4) * 3 + (wx >> 4)];
        return s == null ? MASS : s.get(wx & 15, y, wz & 15);
    }

    /** Original block properties at window position. */
    Blast.Props props(int wx, int y, int wz) {
        if (y < minY || y >= maxY) return airProps;
        if (wx < 0 || wx >= SIDE || wz < 0 || wz >= SIDE) return massProps;
        int k = (wz >> 4) * 3 + (wx >> 4);
        ChunkShot s = shots[k];
        if (s == null) return massProps;
        int sec = (y - minY) >> 4, slot = k * sections + sec;
        ChunkShot.Sec p = props[slot];
        if (p == null) p = props[slot] = s.props(sec, view);
        return p.get((y & 15) << 8 | (wz & 15) << 4 | wx & 15);
    }

    /** Original block at position is air. */
    boolean air(int wx, int y, int wz) {
        return props(wx, y, wz).air();
    }

    /** Whether section {@code section} (index from world bottom) of window chunk {@code k} may contain a state matching {@code test}. */
    boolean mayHave(int k, int section, java.util.function.Predicate<BlockState> test) {
        ChunkShot s = shots[k];
        return s != null && s.mayHave(section, test);
    }

    boolean has(int wx, int wz) {
        return wx >= 0 && wx < SIDE && wz >= 0 && wz < SIDE && shots[(wz >> 4) * 3 + (wx >> 4)] != null;
    }

    /** Original column top (highest non-air), {@code Integer.MIN_VALUE} — empty column; outside window, up to sky. */
    int top(int wx, int wz) {
        if (wx < 0 || wx >= SIDE || wz < 0 || wz >= SIDE) return maxY - 1;
        return top[wz * SIDE + wx];
    }

    /** Original support top excluding leaves. */
    int solid(int wx, int wz) {
        return solid[wz * SIDE + wx];
    }

    /** Whether chunk {@code k} is in the window. */
    boolean present(int k) {
        return shots[k] != null;
    }

    /** Snapshots used to build the window. */
    Stamp stamp() {
        return Stamp.of(shots);
    }

    /** Window snapshots used to build a fracture or plan: their numbers ({@link ChunkShot#serial}; 0 — chunk absent). */
    record Stamp(long[] serials) {
        static Stamp of(ChunkShot[] shots) {
            long[] serials = new long[9];
            for (int k = 0; k < 9; k++) if (shots[k] != null) serials[k] = shots[k].serial;
            return new Stamp(serials);
        }

        /** Built from the same snapshots. */
        boolean same(Stamp o) {
            return java.util.Arrays.equals(serials, o.serials);
        }
    }
}
