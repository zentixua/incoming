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
 * Защита от радиации блоками: ослабление по массе вещества на пути (кг/м², {@link PromptRadiationModel#transmission}).
 * Блок — метр вещества своей плотности ({@link #density}) при любом {@code effects_scale}: масштаб сжимает радиусы
 * подрыва, а стены, окна и кроны вокруг тела остаются своего размера. Путь луча в каждом блоке — по сетке
 * (Amanatides–Woo), а не шагами: угол стены срезает мало. Чанки читаются только готовые ({@link Terrain#ready}): за
 * неготовым — воздух.
 */
public final class Shielding {
    /** Луч к подрыву — не дальше стольких блоков: защита — то, что рядом с телом (стены, подвал, окоп), а не рельеф вдали. */
    private static final double PROMPT_RANGE = 64;
    /** Рассеянная воздухом часть радиации первой минуты: приходит с неба, а не по прямой от шара (оценка). */
    private static final double SKY_SHARE = 0.3;
    private static final double SKY_RANGE = 32;
    /** Направления на осадки вокруг. */
    private static final int AZIMUTHS = 8;
    /** Кольца, блоки: на каждом — точка поверхности, где лежат осадки, и луч к ней. */
    private static final double[] RINGS = {1, 2, 4, 8, 16};
    /** Границы колец (между точками, по логарифму). */
    private static final double[] EDGES = {0, 1.5, 3, 6, 12};
    /** Высота тела над осадками и дальность, дальше которой гамма осадков не доходит (пробег в воздухе), м. */
    private static final double BODY_M = 1, AIR_RANGE_M = 100;
    /** Лучи считаются, пока масса меньше этого (кг/м²): дальше пропускание меньше 10⁻⁹. */
    private static final double MASS_LIMIT = 30 * PromptRadiationModel.HALF_PROMPT;

    private Shielding() {}

    /**
     * Доля радиации первой минуты, доходящая до {@code eye}: {@link #SKY_SHARE} рассеянного — по двум лучам к небу
     * (вверх и под 45° к подрыву), остальное — по прямой к подрыву. Окоп и подвал без потолка спасают от прямого, но не
     * от рассеянного.
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
     * Доля мощности дозы осадков на открытой ровной земле, доходящая до тела в {@code at} (середина тела). Осадки
     * лежат на всём, что открыто небу: на земле, крышах, кронах. Ровная земля вокруг тела в метре над ней светит долей
     * ln(1 + r²)/ln(1 + R²) изнутри радиуса r (R — {@link #AIR_RANGE_M}); на кольцах {@link #RINGS} по
     * {@link #AZIMUTHS} направлениям — точка открытой поверхности (карта высот) и луч к ней, дальнее последнего кольца —
     * по его лучам. Под крышей осадки светят сквозь крышу и стены, в яме — сквозь край ямы, в пещере — сквозь толщу.
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

    /** Масса вещества на отрезке, кг/м²: плотность каждого блока × путь в нём. */
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
     * Плотность блока, кг/м³, с долей объёма, которую он занимает (полублок — половина). Вещество — по звуку блока (так
     * игра сама делит материалы): дерево 600, земля и песок 1600, металл 7800, камень, кирпич и бетон 2300, вода 1000.
     * Стекло — окно (1 см стекла, а не метр), листва — крона, шерсть — войлок, снег и лёд — свои.
     */
    static double density(Level level, BlockPos p) {
        return density(level, p, level.getBlockState(p));
    }

    /**
     * Плотность блока {@code s} на месте {@code p} ({@link #density(Level, BlockPos)}), кг/м³; блок — метр, поэтому это и
     * его масса, кг. Блока на месте может уже не быть (снесённый волной — у звука обломков): форма и звук — его.
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

    /** Окно ли блок {@code s}: стекло по звуку блока (вещество — {@link #density}: окно, а не метр стекла), не лёд. */
    public static boolean window(LevelReader level, BlockPos p, BlockState s) {
        return !s.is(BlockTags.LEAVES) && !s.is(BlockTags.ICE) && !s.is(BlockTags.SNOW) && s.getSoundType(level, p, null) == SoundType.GLASS;
    }

    private static double material(LevelReader level, BlockPos p, BlockState s) {
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
