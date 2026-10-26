package ua.zentix.incoming.nuclear.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.flash.FlashChar;
import ua.zentix.incoming.nuclear.flash.FlashMarks;
import ua.zentix.incoming.nuclear.model.WorldNoise;

/**
 * Flash-charred chunk faces for ruin plan (any thread, like {@link RuinPlanner#finish}): faces of blocks tagged
 * {@code incoming:nuke_chars}/{@code nuke_soots} that survive ruins, are exposed (no full block ahead of face
 * in pre-wave world), and see fireball (shadow from detonation heightmaps, {@link RuinContext.Grid#sees}). Face light is
 * pulse at center × incidence cosine; tier from {@link FlashChar#level} with face randomness.
 * Reads no world: blocks from window snapshots, material and block hash from property table ({@link Blast.Props#flash}).
 */
final class FlashFaces {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int[] NONE = new int[0];

    private FlashFaces() {}

    /** Face marks ({@link FlashChar#mark}), at most {@link FlashMarks#MAX_CHARS} darkest. */
    static int[] chars(RuinContext.Grid g, RuinPlanner.Sections s) {
        Detonation d = g.ctx().d;
        Vec3 burst = d.burst();
        ChunkShot shot = g.at(0, 0);
        int x0 = shot.x << 4, z0 = shot.z << 4;
        // brightest point is chunk point nearest fireball: if no face can darken even there, chunk is clean
        Vec3 nearest = new Vec3(Mth.clamp(burst.x, x0, x0 + 16), Mth.clamp(burst.y, shot.minY, shot.maxY()), Mth.clamp(burst.z, z0, z0 + 16));
        double brightest = d.fluence(nearest);
        if (!FlashChar.possible(brightest, FlashChar.CHARS)) return NONE;
        boolean soots = FlashChar.possible(brightest, FlashChar.SOOTS);
        Blast.PropsView view = g.view();
        WorldNoise noise = g.ctx().noise;
        IntArrayList out = new IntArrayList();
        for (int i = 0; i < shot.sections; i++) {
            if (!shot.mayHave(i, st -> burns(view.get(st), soots))) continue;
            int y0 = shot.minY + (i << 4);
            for (int y = y0; y < y0 + 16; y++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        BlockState st = shot.get(lx, y, lz);
                        Blast.Props p = view.get(st);
                        if (!burns(p, soots) || s.get(lx, y, lz) != st) continue;
                        faces(g, shot, view, noise, d, burst, p, lx, y, lz, x0 + lx, z0 + lz, out);
                    }
                }
            }
        }
        return FlashMarks.strongest(out.toIntArray(), FlashMarks.MAX_CHARS);
    }

    private static boolean burns(Blast.Props p, boolean soots) {
        int m = p.flashMaterial();
        return m == FlashChar.CHARS || soots && m == FlashChar.SOOTS;
    }

    private static void faces(RuinContext.Grid g, ChunkShot shot, Blast.PropsView view, WorldNoise noise, Detonation d, Vec3 burst, Blast.Props p,
                              int lx, int y, int lz, int x, int z, IntArrayList out) {
        int material = p.flashMaterial();
        for (Direction face : DIRECTIONS) {
            double cos = FlashChar.incidence(burst, x, y, z, face);
            if (cos <= 0) continue;
            Vec3 at = FlashChar.front(x, y, z, face);
            double q = d.fluence(at) * cos;
            if (!FlashChar.possible(q, material)) continue;
            if (covered(g, shot, view, lx + face.getStepX(), y + face.getStepY(), lz + face.getStepZ())) continue;
            if (!g.sees(at)) continue;
            double chance = FlashChar.saturated(q, material) ? 0 : FlashChar.chance(noise, x, y, z, face);
            int level = FlashChar.level(q, material, chance);
            if (level > 0) out.add(FlashChar.mark(FlashChar.key(lx, y, lz, face), level, p.flashHash()));
        }
    }

    /** Whether neighbor covers face (position in central-chunk coordinates, may extend outside): full pre-wave block. */
    private static boolean covered(RuinContext.Grid g, ChunkShot shot, Blast.PropsView view, int lx, int y, int lz) {
        if (y < shot.minY || y >= shot.maxY()) return false;
        ChunkShot c = shot;
        if (lx < 0 || lx > 15 || lz < 0 || lz > 15) {
            c = g.at(lx >> 4, lz >> 4);
            if (c == null) return false;
        }
        return view.get(c.get(lx & 15, y, lz & 15)).full();
    }
}
