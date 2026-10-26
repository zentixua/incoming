package ua.zentix.incoming.nuclear.flash;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.nuclear.model.Fragility;
import ua.zentix.incoming.nuclear.model.WorldNoise;
import ua.zentix.incoming.registry.ModTags;

/**
 * Flash-charred block face (DESIGN-nuke §3.5): scorch tier by face light, shadowed pulse multiplied
 * by cosine between fireball ray and face normal, according to material. Wood, wool, hay (tag
 * {@code incoming:nuke_chars}) char to black; masonry and concrete ({@code incoming:nuke_soots}) only darken:
 * soot and scorched whitewash. Natural ground and stone untouched: ruin plan burns ground (turf, scorch, biomes).
 * <p>
 * Tier follows damage curve ({@link Fragility}) by light and local randomness (world noise and face hash), steps
 * 1–{@link #LEVELS}; 0 means clean face. Same function for ruin plan (background), silhouettes (server thread) and client
 * (smoothing by neighboring faces).
 * <p>
 * Marked face key: chunk position and side ({@link #key}), tier and short block hash ({@link #hash}). If block
 * changes (ruins, player construction), mark no longer belongs there and client does not draw it.
 */
public final class FlashChar {
    /** Scorch tiers: 1 lightly scorched, {@code LEVELS} black. */
    public static final int LEVELS = 7;
    /** Face material: non-charring, wood and fabric, masonry and concrete. */
    public static final int NONE = 0, CHARS = 1, SOOTS = 2;
    /**
     * Curve median (cal/cm²) and spread: dark wood chars from ~10 cal/cm² (Glasstone, table 7.40); masonry
     * and concrete do not burn; their paint, whitewash and dust darken under light of the same order, but less
     * ({@link #SOOT_MAX}): for 15 kt, a wall surviving wave (below ~4 psi) receives at most ~20 cal/cm².
     */
    static final double CHAR_MEDIAN = 10, CHAR_BETA = 0.5, SOOT_MEDIAN = 12, SOOT_BETA = 0.5;
    /** Maximum masonry darkening as fraction of wood blackness. */
    static final double SOOT_MAX = 0.45;
    /** Face randomness: world noise (patches) and face hash (grain); squared weights sum to 1. */
    public static final double SMOOTH = 0.85, GRAIN = 0.53;

    private FlashChar() {}

    /** State scorch material (server or client thread: tags). Only full opaque cubes have complete faces. */
    public static int material(BlockState st) {
        if (st.isAir() || !st.canOcclude()) return NONE;
        boolean chars = st.is(ModTags.NUKE_CHARS), soots = !chars && st.is(ModTags.NUKE_SOOTS);
        if (!chars && !soots) return NONE;
        // from state cache: material also read when removing marks at every neighbor notification and chunk send
        if (!st.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return NONE;
        return chars ? CHARS : SOOTS;
    }

    /**
     * Face scorch tier (any thread).
     *
     * @param q        face light, cal/cm² (shadowed pulse × incidence cosine)
     * @param material {@link #CHARS} or {@link #SOOTS}
     * @param z        face randomness ~N(0, 1) (world noise and hash), truncated at ±{@link Fragility#TAIL}
     * @return 0 clean, 1–{@link #LEVELS}
     */
    public static int level(double q, int material, double z) {
        if (q <= 0 || material == NONE) return 0;
        double median = material == CHARS ? CHAR_MEDIAN : SOOT_MEDIAN, beta = material == CHARS ? CHAR_BETA : SOOT_BETA;
        double degree = Fragility.cdf(Math.log(q / median) / beta + Math.max(-Fragility.TAIL, Math.min(Fragility.TAIL, z)));
        if (material == SOOTS) degree *= SOOT_MAX;
        return Math.min(LEVELS, (int) Math.round(degree * LEVELS));
    }

    /** Can face darken by at least one tier at this light and maximum randomness? If not, no noise needed. */
    public static boolean possible(double q, int material) {
        return q > 0 && material != NONE && level(q, material, Fragility.TAIL) > 0;
    }

    /** Same tier at any randomness (light so strong everything is black): no noise needed. */
    public static boolean saturated(double q, int material) {
        return level(q, material, -Fragility.TAIL) == level(q, material, Fragility.TAIL);
    }

    /** World-noise layer for scorch patches (ruin plan uses layers 1–5). */
    public static final int LAYER = 6;

    /**
     * Face randomness ~N(0, 1) (any thread): volume world noise at face center gives patches several blocks across, face hash
     * gives grain. Identical for ruin plan, silhouettes and client.
     */
    public static double chance(WorldNoise noise, int x, int y, int z, Direction face) {
        double smooth = noise.at(x + 0.5 + 0.5 * face.getStepX(), y + 0.5 + 0.5 * face.getStepY(), z + 0.5 + 0.5 * face.getStepZ(), LAYER);
        int h = Mth.murmurHash3Mixer(Long.hashCode(BlockPos.asLong(x, y, z)) * 31 + 53 + face.get3DDataValue());
        return SMOOTH * smooth + GRAIN * Fragility.inverse(((h >>> 8) + 0.5) / (1 << 24));
    }

    /**
     * Cosine between face normal and direction from face center to {@code burst}; 0 if face points away
     * or sideways.
     */
    public static double incidence(Vec3 burst, int x, int y, int z, Direction face) {
        double rx = burst.x - (x + 0.5 + 0.5 * face.getStepX()), ry = burst.y - (y + 0.5 + 0.5 * face.getStepY()),
                rz = burst.z - (z + 0.5 + 0.5 * face.getStepZ());
        double len = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (len < 1e-6) return 1;
        return Math.max(0, (rx * face.getStepX() + ry * face.getStepY() + rz * face.getStepZ()) / len);
    }

    /** Face point for shadow and light: face center slightly ahead of it. */
    public static Vec3 front(int x, int y, int z, Direction face) {
        return new Vec3(x + 0.5 + 0.55 * face.getStepX(), y + 0.5 + 0.55 * face.getStepY(), z + 0.5 + 0.55 * face.getStepZ());
    }

    // ---------------------------------------------------------------- packing

    /** Chunk face key: x, z (4 bits each), y + 2048 (12 bits), side (3 bits), 23 bits total. */
    public static int key(int lx, int y, int lz, Direction face) {
        return lx & 15 | (lz & 15) << 4 | (y + 2048 & 0xFFF) << 8 | face.get3DDataValue() << 20;
    }

    public static final int KEY_MASK = (1 << 23) - 1;
    /** Face position in key without side: x, z and y (20 bits). */
    public static final int PLACE_MASK = (1 << 20) - 1;

    /** Mark: key, tier (3 bits) and block hash (6 bits). */
    public static int mark(int key, int level, int hash) {
        return key & KEY_MASK | (level & 7) << 23 | (hash & 63) << 26;
    }

    public static int lx(int mark) {
        return mark & 15;
    }

    public static int lz(int mark) {
        return mark >> 4 & 15;
    }

    public static int y(int mark) {
        return (mark >> 8 & 0xFFF) - 2048;
    }

    public static Direction face(int mark) {
        return Direction.from3DDataValue(mark >> 20 & 7);
    }

    public static int level(int mark) {
        return mark >>> 23 & 7;
    }

    public static int hashOf(int mark) {
        return mark >>> 26 & 63;
    }

    /** Short block hash (not state: opening a door preserves mark), by registry name, identical server/client. */
    public static int hash(Block block) {
        return Mth.murmurHash3Mixer(BuiltInRegistries.BLOCK.getKey(block).hashCode()) >>> 7 & 63;
    }

    /**
     * Mark still belongs to block at position: scorch material (tagged full cube, otherwise block broken or replaced) and same
     * block hash.
     */
    public static boolean holds(BlockState st, int mark) {
        return material(st) != NONE && hash(st.getBlock()) == hashOf(mark);
    }

    /** Face world position from chunk mark {@code cx, cz}. */
    public static BlockPos pos(int mark, int cx, int cz) {
        return new BlockPos(cx << 4 | lx(mark), y(mark), cz << 4 | lz(mark));
    }
}
