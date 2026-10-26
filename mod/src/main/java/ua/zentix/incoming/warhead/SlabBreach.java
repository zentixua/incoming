package ua.zentix.incoming.warhead;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.util.Terrain;

import java.util.Optional;

/**
 * Contact-charge obstacle breach ({@link BreachModel}) in world blocks. From charge contact face ({@link
 * #locate}), blast ray enters obstacle; divide each block path by material breach radius (FM group from {@code
 * incoming:penetration}, {@link ModDataMaps.Penetrability#breaching}). Reaching covered void ({@link #covered}:
 * room, cave, channel under deck, FM free rear face) before sum exceeds 1 removes all traversed blocks: slab
 * breached along ray, wave continues into void. Failure or open-surface exit gives no breach: crater law
 * excavates ground there. Same rule for all blocks: thickness/material to void holds charge, not
 * natural/structure identity. Hole width follows each oblique ray's path as thickness; FM R gives thickness under
 * charge, not hole width, which is ours.
 */
final class SlabBreach {
    /**
     * Breach ray step, blocks: finer than 0.3 wave step; comparing slab path/radius with 0.3 added up to 0.3 m
     * near oblique hole edge, making edge one block narrower than law. Repeated steps within block still read it
     * once.
     */
    static final double STEP = 0.1;
    /** Vanilla-blast-resistant block (obsidian 1200, protected mod blocks): charge does not displace it. */
    static final float BLAST_PROOF = 1200;

    private SlabBreach() {}

    /** Charge placement against obstacle. */
    enum Placement {
        /** No contact (airburst, escaped charge share, tests): no breach, wave only. */
        NONE,
        /** On warhead impact face, untamped ({@link BreachModel#UNTAMPED}). */
        SURFACE,
        /** Buried (ground-penetrating bomb), tamped ({@link BreachModel#TAMPED}). */
        BURIED
    }

    /** Block role along breach ray. */
    enum Cell {
        /** Void beyond obstacle: air, fluid, no collision shape (grass, torch). */
        VOID,
        /** Charge cannot displace: indestructible, extremely strong, data-map impenetrable, claim-protected. */
        STOP,
        /** Obstacle. */
        SOLID
    }

    /**
     * Breach ray origin.
     *
     * @param at charge point on face (or buried charge center)
     * @param open outward from obstacle, warhead arrival side; rays only inward (negative dot product); null all
     * directions (buried or slab open both sides)
     * @param tolerance void distance allowed before obstacle while still in contact, blocks
     * @param radius breach radii per material group ({@link BreachModel.Material#ordinal}), m
     */
    record Origin(Vec3 at, @Nullable Vec3 open, double tolerance, double[] radius) {
        /** Maximum breach radius: ray stops beyond it. */
        double reach() {
            double r = 0;
            for (double v : radius) r = Math.max(r, v);
            return r + tolerance;
        }

        /** Ray {@code (dx, dy, dz)} enters obstacle. */
        boolean inward(double dx, double dy, double dz) {
            return open == null || dx * open.x + dy * open.y + dz * open.z < 0;
        }
    }

    /** Block {@code s} at {@code p} for explosion {@code e}'s breach. */
    static Cell cell(ServerLevel level, BlockPos p, BlockState s, Explosion e, ExplosionDamageCalculator damage) {
        if (s.isAir() || s.getBlock() instanceof LiquidBlock || s.getCollisionShape(level, p).isEmpty()) return Cell.VOID;
        if (s.getExplosionResistance(level, p, e) >= BLAST_PROOF || BlockResponse.of(s).kind() == BlockResponse.Kind.NONE
                || ModDataMaps.Penetrability.of(s).breaching() == null || !damage.shouldBlockExplode(e, level, p, s, 1f)) return Cell.STOP;
        return Cell.SOLID;
    }

    /**
     * Solid block material ({@link Cell#SOLID}): data map; unlisted (stairs, slabs, walls, doors, mod blocks) by
     * mining tool: axe wood (“good timber”), shovel/hoe soil, pickaxe stone/masonry if no stronger than
     * cobblestone (2); other unknown reinforced concrete, per FM strongest-material rule (3-12c).
     */
    static BreachModel.Material material(BlockState s) {
        ModDataMaps.Penetrability entry = ModDataMaps.Penetrability.find(s);
        BreachModel.Material m = entry != null ? entry.breaching() : null;
        if (m != null) return m;
        if (s.is(BlockTags.MINEABLE_WITH_AXE)) return BreachModel.Material.POOR;
        if (s.is(BlockTags.MINEABLE_WITH_SHOVEL) || s.is(BlockTags.MINEABLE_WITH_HOE)) return BreachModel.Material.EARTH;
        if (s.is(BlockTags.MINEABLE_WITH_PICKAXE) && s.getBlock().defaultDestroyTime() <= ROCK_HARDNESS) return BreachModel.Material.ROCK;
        return BreachModel.Material.REINFORCED;
    }

    /** Cobblestone/brick strength (vanilla mining time): stronger is not ordinary masonry. */
    private static final float ROCK_HARDNESS = 2;

    /**
     * Where {@code tnt} kg TNT centered at {@code centre} contacts obstacle; null if not (air center beyond
     * charge radius from all faces, unready chunk, impenetrable). Surface placement ({@link Placement#SURFACE}):
     * center in solid (aim half-block into ground) rests on open face (void neighbor) through which warhead
     * arrived ({@code axis}, impact heading). One-block deck has both faces open, full thickness from impact face
     * rather than half from center; rays inward from face, FM surface thickness (external charge R is full
     * thickness). No heading: face from sum of open directions (one-block deck: center all directions). Center in
     * void near face (nose impact): from same point inward.
     */
    @Nullable
    static Origin locate(ServerLevel level, Vec3 centre, double tnt, Placement placement, @Nullable Vec3 axis, Explosion e,
                         ExplosionDamageCalculator damage) {
        if (placement == Placement.NONE || !(tnt > 0)) return null;
        double c = placement == Placement.BURIED ? BreachModel.TAMPED : BreachModel.UNTAMPED;
        double[] radius = new double[BreachModel.Material.values().length];
        for (BreachModel.Material m : BreachModel.Material.values()) radius[m.ordinal()] = m.radius(tnt, c);
        double rc = BreachModel.chargeRadius(tnt);
        BlockPos cell = BlockPos.containing(centre);
        if (!Terrain.ready(level, cell)) return null;
        // Buried: charge/bore (bomb channel) are void inside charge block, then rock.
        if (placement == Placement.BURIED) return new Origin(centre, null, rc + 1, radius);
        Cell here = cell(level, cell, level.getBlockState(cell), e, damage);
        if (here == Cell.STOP) return null;
        double x = 0, y = 0, z = 0, gap = Double.MAX_VALUE;
        // Warhead entry face: where reverse heading exits block if open; otherwise
        // open face whose outward normal most opposes heading.
        Direction entry = axis != null && here == Cell.SOLID ? exitFace(cell, centre, axis.scale(-1)) : null;
        Direction hit = null;
        double facing = 0;
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (!Terrain.ready(level, n)) return null;
            Cell k = cell(level, n, level.getBlockState(n), e, damage);
            if (here == Cell.SOLID ? k == Cell.VOID : k == Cell.SOLID) {
                x += d.getStepX();
                y += d.getStepY();
                z += d.getStepZ();
            }
            if (here == Cell.VOID && k == Cell.SOLID) gap = Math.min(gap, faceDistance(cell, centre, d));
            if (here == Cell.SOLID && k == Cell.VOID && axis != null) {
                double f = d == entry ? Double.MAX_VALUE : -(d.getStepX() * axis.x + d.getStepY() * axis.y + d.getStepZ() * axis.z);
                if (hit == null || f > facing) {
                    hit = d;
                    facing = f;
                }
            }
        }
        if (hit != null) {
            Vec3 open = new Vec3(hit.getStepX(), hit.getStepY(), hit.getStepZ());
            return new Origin(exit(cell, centre, open), open, rc + STEP, radius);
        }
        Vec3 sum = new Vec3(x, y, z);
        if (here == Cell.VOID) {
            // In void: contact only within charge-sphere radius of face; outward from solid neighbors.
            if (gap > rc + 1e-9) return null;
            return new Origin(centre, sum.lengthSqr() < 1e-9 ? null : sum.scale(-1).normalize(), rc + STEP, radius);
        }
        if (sum.lengthSqr() < 1e-9) return new Origin(centre, null, rc + STEP, radius);
        Vec3 open = sum.normalize();
        return new Origin(exit(cell, centre, open), open, rc + STEP, radius);
    }

    /** Distance from {@code p} in {@code cell} to face {@code d}. */
    private static double faceDistance(BlockPos cell, Vec3 p, Direction d) {
        return switch (d) {
            case DOWN -> p.y - cell.getY();
            case UP -> cell.getY() + 1 - p.y;
            case NORTH -> p.z - cell.getZ();
            case SOUTH -> cell.getZ() + 1 - p.z;
            case WEST -> p.x - cell.getX();
            case EAST -> cell.getX() + 1 - p.x;
        };
    }

    /**
     * Void {@code p} covered: above in same column below ground top (no-leaves heightmap), a solid nonleaf block
     * exists. Open-sky void (surface near impact, terrain ledge, bomb bore, open water) is ground surface for
     * crater-law ejecta ({@code CraterModel}, bomb {@link BuriedBlast}), not slab rear face.
     */
    static boolean covered(ServerLevel level, BlockPos p) {
        int top = Terrain.height(level, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(p.getX(), p.getY(), p.getZ());
        for (int y = p.getY() + 1; y < top; y++) {
            BlockState s = level.getBlockState(m.setY(y));
            if (!s.is(BlockTags.LEAVES) && !s.getCollisionShape(level, m).isEmpty()) return true;
        }
        return false;
    }

    /** Where ray from {@code p} along {@code dir} exits {@code cell}. */
    static Vec3 exit(BlockPos cell, Vec3 p, Vec3 dir) {
        double t = Double.MAX_VALUE;
        t = Math.min(t, axisExit(p.x, dir.x, cell.getX()));
        t = Math.min(t, axisExit(p.y, dir.y, cell.getY()));
        t = Math.min(t, axisExit(p.z, dir.z, cell.getZ()));
        return t == Double.MAX_VALUE ? p : p.add(dir.scale(t));
    }

    /** Exit face of {@code cell} for ray from {@code p} along {@code dir}; null if no ray. */
    @Nullable
    static Direction exitFace(BlockPos cell, Vec3 p, Vec3 dir) {
        double tx = axisExit(p.x, dir.x, cell.getX()), ty = axisExit(p.y, dir.y, cell.getY()), tz = axisExit(p.z, dir.z, cell.getZ());
        double t = Math.min(tx, Math.min(ty, tz));
        if (t == Double.MAX_VALUE) return null;
        if (t == tx) return dir.x > 0 ? Direction.EAST : Direction.WEST;
        if (t == ty) return dir.y > 0 ? Direction.UP : Direction.DOWN;
        return dir.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static double axisExit(double p, double d, int lo) {
        if (d > 1e-9) return (lo + 1 - p) / d;
        if (d < -1e-9) return (lo - p) / d;
        return Double.MAX_VALUE;
    }

    /**
     * Breach ray from {@code o} along {@code (dx, dy, dz)}: obstacle blocks in {@code path}. Result distance from
     * {@code o.at()} to void exit beyond obstacle (breach); −1 if no breach (path sum >1, impenetrable, excess
     * pre-contact void, unready chunk, world edge); {@code liquid[0]} marks fluid exit (wave stops).
     */
    static double march(ServerLevel level, Origin o, double dx, double dy, double dz, Explosion e, ExplosionDamageCalculator damage,
                        LongArrayList path, boolean[] liquid) {
        return march(level, o, dx, dy, dz, e, damage, path, liquid, null);
    }

    /**
     * Same; non-null {@code used} gives sum of obstacle paths divided by respective breach radius ({@code
     * used[0]}) and total path ({@code used[1]}, m) until exit/stop.
     */
    static double march(ServerLevel level, Origin o, double dx, double dy, double dz, Explosion e, ExplosionDamageCalculator damage,
                        LongArrayList path, boolean[] liquid, @Nullable double[] used) {
        path.clear();
        if (used != null) used[0] = used[1] = 0;
        double sum = 0, r = 0;
        long last = Long.MIN_VALUE;
        double reach = o.reach();
        // Ray point is step midpoint: d − STEP/2 to d + STEP/2 belongs to its block.
        for (double d = STEP / 2; d <= reach + STEP; d += STEP) {
            BlockPos pos = BlockPos.containing(o.at().x + dx * d, o.at().y + dy * d, o.at().z + dz * d);
            long key = pos.asLong();
            if (key != last) {
                last = key;
                if (!level.isInWorldBounds(pos) || !Terrain.ready(level, pos)) return -1;
                BlockState s = level.getBlockState(pos);
                switch (cell(level, pos, s, e, damage)) {
                    case STOP -> {
                        return -1;
                    }
                    case VOID -> {
                        if (path.isEmpty()) {
                            if (d > o.tolerance()) return -1;
                            continue;
                        }
                        // Free breach face is covered void (room, cave, channel under deck);
                        // open surface belongs to crater law, not breach.
                        if (!covered(level, pos)) return -1;
                        liquid[0] = s.getBlock() instanceof LiquidBlock;
                        return d - STEP / 2;
                    }
                    case SOLID -> {
                        path.add(key);
                        r = o.radius()[material(s).ordinal()];
                    }
                }
            } else if (path.isEmpty()) {
                if (d > o.tolerance()) return -1;
                continue;
            }
            if (r <= 0) return -1;
            sum += STEP / r;
            if (used != null) {
                used[0] = sum;
                used[1] += STEP;
            }
            if (sum > 1) return -1;
        }
        return -1;
    }

    /**
     * Impact-face support for client visuals ({@link S2C.Blast.Support}): {@code tnt} kg TNT at {@code centre},
     * surface contact ({@link Placement#SURFACE}), same {@link #locate}/{@link #march} as breach rays, one inward
     * face-normal ray (both sides open uses {@code axis}, absent uses down). Not heading: shortest slab path is
     * normal, breached first by near-normal rays; oblique heading crosses t/cos θ and “failed” a slab that rays
     * remove (45° missile onto 2 m stone deck: 2.8 m heading path at R 2.7 m, hole ~1.8 m). Normal is hole axis
     * (flat slab rays make circle √(R² − t²) around it). Empty means no contact (material-based visual). Reads
     * world now: blast packet before rays (⚠ neighboring same-tick explosion removing obstacle first leaves
     * visual slab absent from rays). ⚠ One ray: uneven obstacle (charge over wall under roof) may stop normal in
     * solid while oblique rays breach nearby.
     */
    static Optional<S2C.Blast.Support> support(ServerLevel level, Vec3 centre, double tnt, @Nullable Vec3 axis, Explosion e,
                                               ExplosionDamageCalculator damage) {
        Origin o = locate(level, centre, tnt, Placement.SURFACE, axis, e, damage);
        if (o == null) return Optional.empty();
        Vec3 n = o.open() != null ? o.open().scale(-1) : axis != null && axis.lengthSqr() > 1e-9 ? axis.normalize() : S2C.Blast.DOWN;
        LongArrayList path = new LongArrayList();
        double[] used = new double[2];
        double out = march(level, o, n.x, n.y, n.z, e, damage, path, new boolean[1], used);
        // Struck material is first obstacle block along normal; absent (impenetrable at face) means cohesive.
        boolean loose = !path.isEmpty() && material(level.getBlockState(BlockPos.of(path.getLong(0)))) == BreachModel.Material.EARTH;
        float lift = (float) centre.subtract(o.at()).dot(n);
        if (out < 0 || used[0] <= 0) return Optional.of(new S2C.Blast.Support(loose, 0, 0, n, lift));
        return Optional.of(new S2C.Blast.Support(loose, (float) used[1], (float) (used[1] / used[0]), n, lift));
    }
}
