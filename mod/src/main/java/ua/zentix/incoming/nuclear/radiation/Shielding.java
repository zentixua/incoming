package ua.zentix.incoming.nuclear.radiation;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import ua.zentix.incoming.nuclear.model.PromptRadiationModel;
import ua.zentix.incoming.util.Terrain;

/**
 * Radiation shielding by blocks: attenuation by material mass along the path (kg/m²,
 * {@link PromptRadiationModel#transmission}). A block is a meter of material at its density ({@link #density}) for any
 * {@code effects_scale}: scale compresses burst radii, while walls, windows and crowns around the body retain their
 * size. Ray path in each block follows the grid (Amanatides–Woo), not fixed steps: a wall corner clips only a little.
 * Only ready chunks are read ({@link Terrain#ready}): beyond an unready chunk, air.
 */
public final class Shielding {
    /** Ray toward burst stops at this many blocks: shielding is near the body (walls, basement, trench), not distant terrain. */
    private static final double PROMPT_RANGE = 64;
    /** Air-scattered share of first-minute radiation: arrives from sky instead of directly from the ball (est.). */
    private static final double SKY_SHARE = 0.3;
    private static final double SKY_RANGE = 32;
    /** Directions toward surrounding fallout. */
    private static final int AZIMUTHS = 8;
    /** Rings, blocks: each has a surface point with fallout and a ray to it. */
    private static final double[] RINGS = {1, 2, 4, 8, 16};
    /** Ring boundaries (between sample points, logarithmic). */
    private static final double[] EDGES = {0, 1.5, 3, 6, 12};
    /** Body height above fallout and maximum fallout gamma range (air attenuation range), m. */
    private static final double BODY_M = 1, AIR_RANGE_M = 100;
    /** Trace rays while mass is below this (kg/m²): transmittance beyond it is below 10⁻⁹. */
    private static final double MASS_LIMIT = 30 * PromptRadiationModel.HALF_PROMPT;

    private Shielding() {}

    /**
     * First-minute radiation fraction reaching {@code eye}: {@link #SKY_SHARE} scattered share uses two sky rays
     * (upward and 45° toward burst), the rest directly toward burst. An open trench or basement blocks direct radiation,
     * not scattered radiation.
     */
    public static double prompt(Level level, Vec3 eye, Vec3 burst) {
        Vec3 dir = burst.subtract(eye);
        double len = dir.length();
        Vec3 to = len > PROMPT_RANGE ? eye.add(dir.scale(PROMPT_RANGE / len)) : burst;
        double direct = PromptRadiationModel.transmission(mass(level, eye, to), PromptRadiationModel.HALF_PROMPT);
        double h = Math.hypot(dir.x, dir.z);
        Vec3 slant = h < 1e-6 ? new Vec3(0, 1, 0) : new Vec3(dir.x / h, 1, dir.z / h).normalize();
        double sky = 0.5 * (PromptRadiationModel.transmission(mass(level, eye, eye.add(0, SKY_RANGE, 0)), PromptRadiationModel.HALF_PROMPT)
                + PromptRadiationModel.transmission(mass(level, eye, eye.add(slant.scale(SKY_RANGE))), PromptRadiationModel.HALF_PROMPT));
        return (1 - SKY_SHARE) * direct + SKY_SHARE * sky;
    }

    /**
     * Fraction of open flat-ground fallout dose rate reaching the body at {@code at} (body center). Fallout lies on
     * everything open to sky: ground, roofs, crowns. Flat ground around a body one meter above it contributes
     * ln(1 + r²)/ln(1 + R²) from inside radius r (R — {@link #AIR_RANGE_M}); sample {@link #RINGS} along {@link #AZIMUTHS}
     * directions — exposed surface point (heightmap) and ray to it; beyond the last ring, use its rays. Under a roof,
     * fallout shines through roof and walls; in a pit, through its edge; in a cave, through the overburden.
     */
    public static double fallout(Level level, Vec3 at) {
        double total = Math.log1p(Mth.square(AIR_RANGE_M / BODY_M));
        double sum = 0;
        for (int k = 0; k < RINGS.length; k++) {
            double lo = Math.log1p(Mth.square(EDGES[k] / BODY_M));
            double hi = k + 1 < EDGES.length ? Math.log1p(Mth.square(EDGES[k + 1] / BODY_M)) : total;
            double weight = Math.max(0, hi - lo) / total / AZIMUTHS;
            for (int i = 0; i < AZIMUTHS; i++) {
                double a = (i + 0.5 * (k & 1)) * Math.PI * 2 / AZIMUTHS;
                int x = Mth.floor(at.x + Math.cos(a) * RINGS[k]), z = Mth.floor(at.z + Math.sin(a) * RINGS[k]);
                double t = 1;
                if (Terrain.ready(level, x >> 4, z >> 4)) {
                    double top = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING, x, z);
                    Vec3 source = new Vec3(at.x + Math.cos(a) * RINGS[k], top + 0.05, at.z + Math.sin(a) * RINGS[k]);
                    t = PromptRadiationModel.transmission(mass(level, at, source), PromptRadiationModel.HALF_FALLOUT);
                }
                sum += weight * t;
            }
        }
        return sum;
    }

    /** Material mass along a segment, kg/m²: each block's density × path within it. */
    public static double mass(Level level, Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-9) return 0;
        int x = Mth.floor(from.x), y = Mth.floor(from.y), z = Mth.floor(from.z);
        int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        double ddx = dx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx);
        double ddy = dy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy);
        double ddz = dz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = dx == 0 ? Double.POSITIVE_INFINITY : (dx > 0 ? x + 1 - from.x : from.x - x) * ddx;
        double ty = dy == 0 ? Double.POSITIVE_INFINITY : (dy > 0 ? y + 1 - from.y : from.y - y) * ddy;
        double tz = dz == 0 ? Double.POSITIVE_INFINITY : (dz > 0 ? z + 1 - from.z : from.z - z) * ddz;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
        double t = 0, mass = 0;
        while (t < 1 && mass < MASS_LIMIT) {
            double next = Math.min(1, Math.min(tx, Math.min(ty, tz)));
            if (x >> 4 != chunkX || z >> 4 != chunkZ) {
                chunkX = x >> 4;
                chunkZ = z >> 4;
                if (!Terrain.ready(level, chunkX, chunkZ)) break;
            }
            if (y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()) {
                mass += density(level, p.set(x, y, z)) * (next - t) * len;
            }
            t = next;
            if (tx <= ty && tx <= tz) {
                x += sx;
                tx += ddx;
            } else if (ty <= tz) {
                y += sy;
                ty += ddy;
            } else {
                z += sz;
                tz += ddz;
            }
        }
        return mass;
    }

    /**
     * Block density, kg/m³, weighted by occupied volume (slab — half). Material from block sound (the game's material
     * classification): wood 600, earth and sand 1600, metal 7800, stone, brick and concrete 2300, water 1000.
     * Glass means a window (1 cm glass, not a meter), leaves a crown, wool felt; snow and ice have their own values.
     */
    static double density(Level level, BlockPos p) {
        return density(level, p, level.getBlockState(p));
    }

    /**
     * Density of block {@code s} at {@code p} ({@link #density(Level, BlockPos)}), kg/m³; a block is a meter, so also its mass,
     * kg. The block may already be absent (removed by the blast, for debris sound): use its shape and sound.
     */
    public static double density(LevelReader level, BlockPos p, BlockState s) {
        if (s.isAir()) return 0;
        FluidState fluid = s.getFluidState();
        double liquid = fluid.isEmpty() ? 0 : fluid.is(FluidTags.LAVA) ? 2600 : 1000;
        VoxelShape shape = s.getCollisionShape(level, p);
        if (shape.isEmpty()) return liquid;
        double fill = s.isCollisionShapeFullBlock(level, p) ? 1 : volume(shape);
        return material(level, p, s) * fill + liquid * (1 - fill);
    }

    /** Whether block {@code s} is a window: glass by block sound (material as {@link #density}: a window, not a meter of glass), not ice. */
    public static boolean window(LevelReader level, BlockPos p, BlockState s) {
        return !s.is(BlockTags.LEAVES) && !s.is(BlockTags.ICE) && !s.is(BlockTags.SNOW) && s.getSoundType(level, p, null) == SoundType.GLASS;
    }

    /**
     * Material density of block {@code s}, kg/m³ (without volume fraction, {@link #density}): by block sound; glass is a window,
     * leaves a crown.
     */
    public static double material(LevelReader level, BlockPos p, BlockState s) {
        if (s.is(BlockTags.LEAVES)) return 50;
        if (s.is(BlockTags.ICE)) return 900;
        if (s.is(BlockTags.SNOW)) return 300;
        if (window(level, p, s)) return 25;
        SoundType t = s.getSoundType(level, p, null);
        if (t == SoundType.WOOL) return 150;
        if (t == SoundType.WOOD || t == SoundType.NETHER_WOOD || t == SoundType.CHERRY_WOOD || t == SoundType.BAMBOO_WOOD
                || t == SoundType.STEM || t == SoundType.BAMBOO) return 600;
        if (t == SoundType.GRAVEL || t == SoundType.SAND || t == SoundType.GRASS || t == SoundType.ROOTED_DIRT
                || t == SoundType.MUD || t == SoundType.SOUL_SAND || t == SoundType.SOUL_SOIL || t == SoundType.MOSS) return 1600;
        if (t == SoundType.METAL || t == SoundType.ANVIL || t == SoundType.NETHERITE_BLOCK || t == SoundType.COPPER
                || t == SoundType.CHAIN || t == SoundType.LANTERN || t == SoundType.HEAVY_CORE) return 7800;
        return 2300;
    }

    private static double volume(VoxelShape shape) {
        double v = 0;
        for (AABB b : shape.toAabbs()) {
            v += Math.max(0, Math.min(b.maxX, 1) - Math.max(b.minX, 0)) * Math.max(0, Math.min(b.maxY, 1) - Math.max(b.minY, 0))
                    * Math.max(0, Math.min(b.maxZ, 1) - Math.max(b.minZ, 0));
        }
        return Math.min(1, v);
    }
}
