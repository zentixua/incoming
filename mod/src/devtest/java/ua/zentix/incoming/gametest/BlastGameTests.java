package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.model.CraterModel;
import ua.zentix.incoming.nuclear.model.FuelFireball;
import ua.zentix.incoming.nuclear.model.PulseIgnition;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponSpec;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.FuelFires;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/**
 * Explosion of a warhead according to charge (S3): blocks break down to the Jarrett radii of their threshold, gases knock out the room,
 *  crater - according to the law of charge, fire - only in a ball of fuel, bodies - according to pressure. Radii - from the spec
 *  ({@code WeaponSpec.charge}) and the law ({@link BlastDamage}), tolerance ±20% (dispersion of block thresholds - ±15%).
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BlastGameTests {
    /**
     * Middle of the site “range”: grass block (floor – 11 layers: stone, earth, grass; GameTest places the template on the block above)
     *  and the first air above it.
     */
    private static final BlockPos GRASS = new BlockPos(32, 11, 32), CENTER = GRASS.above();

    private BlastGameTests() {}

    /**
     * Columns of the same material, each on its own beam (a broken block extinguishes part of the wave - it is impossible to check the open
     *  field behind it), at 0,6–1,35 Jarrett's radius of its own threshold: the far broken one and the near surviving one are ±20% of
     *  it. Each block has its own threshold - the spread is ±15 % according to the hash of the place ({@link BlockResponse#jitterAt}), and the site
     * GameTest is in a new place each time: Jarrett's radius is calculated according to the threshold of the column itself, and the places are chosen so that the columns stand at the same fractions of their radius at any place sites.
     */
    private static void radiusTest(GameTestHelper h, Block material, double psi, double tnt) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(CENTER);
        Vec3 at = Vec3.atCenterOf(c);
        double nominal = BlastDamage.breakRange(BlastModel.kpa(psi), tnt);
        List<Post> posts = layPosts(c, at, psi, tnt, nominal, List.of(), b -> true);
        h.assertTrue(posts.size() == POST_AT.length, "there was no room for columns on their rays: " + posts.size());
        // grass around (up to 1,7 radius): the surviving grass does not hold the wave - the columns behind it break the same way
        int n = (int) Math.ceil(nominal * 1.7);
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-n, 0, -n), c.offset(n, 0, n))) {
            if (level.getBlockState(p).isAir()) level.setBlock(p, Blocks.SHORT_GRASS.defaultBlockState(), 2);
        }
        for (Post p : posts) level.setBlock(p.pos(), material.defaultBlockState(), 2);
        Warheads.Probe blast = Warheads.testCharge(level, at, tnt);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "the explosion didn't end");
            // fractions of the Jarrett radius of its threshold; broken - there is no material in place (the crater shaft can lie on the demolished one)
            double broken = 0, kept = Double.MAX_VALUE;
            StringBuilder rows = new StringBuilder();
            for (Post p : posts) {
                boolean gone = !level.getBlockState(p.pos()).is(material);
                if (gone) broken = Math.max(broken, p.share());
                else kept = Math.min(kept, p.share());
                rows.append(String.format(Locale.ROOT, " %.2f%s", p.share(), gone ? "x" : "|"));
            }
            String got = String.format(Locale.ROOT, "%s, %.1f kg: broken to %.2f, intact from %.2f of its Jarrett radius (%.1f m without spread); columns:%s",
                    material, tnt, broken, kept, nominal, rows);
            Incoming.LOG.info("BLASTBENCH {}", got);
            h.assertTrue(broken >= 0.7 && broken <= 1.2, got);
            h.assertTrue(kept >= 0.7 && kept <= 1.3, got);
        });
    }

    /**
     * Fractions of the Jarrett radius of its threshold, on which the columns stand: on both sides of the tolerance edges (0,7 and 1,2–1,3),
     * deep in the zone and beyond it. The edge of the model is 0,9–1,11 of the radius of its threshold (the pressure-momentum law divides into the spread of both
     *  pressure and impulse). Places are selected in this order: on the close circle of masonry at Shahed (columns in 2–4,5 m,
     * each covers 12–28° azimuth) the best places go to columns at the edges of the tolerance.
     */
    static final double[] POST_AT = {0.75, 1.25, 0.8, 1.2, 0.7, 1.35, 0.6, 0.9, 1.0, 1.1};
    /** The gap between the columns in azimuth is in excess of their angular half-widths, rad: the beam to the far one does not touch the near one. */
    private static final double POST_GAP = Math.toRadians(3);

    /**
     * Column: location, distance from the charge to the nearest point of the block (the beam breaks the block upon entering it), Jarrett's radius of its
     * threshold, azimuth and angular half-width of the block from the charge.
     */
    record Post(BlockPos pos, double distance, double jarrett, double bearing, double halfWidth) {
        double share() {
            return distance / jarrett;
        }
    }

    /**
     * Places of columns in the charge layer: for each fraction {@link #POST_AT} - a block whose distance divided by the
     * Jarrett radius of its threshold is closest to it, and which does not overlap the already selected ones in azimuth. It depends only on the
     * thresholds of the places, and their spread depends on the place of the site: the shares are the same everywhere. {@code taken} - already standing
     *  columns of a different material: new ones do not overlap them either; {@code side} - which azimuths (rad, from +x to +z) are suitable.
     */
    static List<Post> layPosts(BlockPos c, Vec3 at, double psi, double tnt, double nominal, List<Post> taken,
                               java.util.function.DoublePredicate side) {
        int n = (int) Math.ceil(nominal * 1.6);
        Set<BlockPos> sampled = sampledPosts(c, at, n);
        List<Post> candidates = new ArrayList<>();
        for (int dx = -n; dx <= n; dx++) {
            for (int dz = -n; dz <= n; dz++) {
                BlockPos p = c.offset(dx, 0, dz);
                if (!sampled.contains(p)) continue;
                double distance = Math.sqrt(new AABB(p).distanceToSqr(at));
                if (distance < 1 || !side.test(Math.atan2(p.getZ() + 0.5 - at.z, p.getX() + 0.5 - at.x))) continue;
                double jarrett = BlastDamage.breakRange(BlastModel.kpa(psi * BlockResponse.jitterAt(p)), tnt);
                double bearing = Math.atan2(p.getZ() + 0.5 - at.z, p.getX() + 0.5 - at.x), half = 0;
                for (int cx = 0; cx <= 1; cx++) {
                    for (int cz = 0; cz <= 1; cz++) {
                        half = Math.max(half, Math.abs(Mth.wrapDegrees(Math.toDegrees(
                                Math.atan2(p.getZ() + cz - at.z, p.getX() + cx - at.x) - bearing))));
                    }
                }
                candidates.add(new Post(p, distance, jarrett, bearing, Math.toRadians(half)));
            }
        }
        List<Post> posts = new ArrayList<>(), all = new ArrayList<>(taken);
        for (double share : POST_AT) {
            Post best = null;
            for (Post q : candidates) {
                boolean free = true;
                for (Post o : all) {
                    double apart = Math.abs(Math.toRadians(Mth.wrapDegrees(Math.toDegrees(q.bearing() - o.bearing()))));
                    if (apart < q.halfWidth() + o.halfWidth() + POST_GAP) free = false;
                }
                if (free && (best == null || Math.abs(q.share() - share) < Math.abs(best.share() - share))) best = q;
            }
            if (best == null) break;
            posts.add(best);
            all.add(best);
        }
        return posts;
    }

    /**
     * A one-block post must intersect an actual 0.3 m sample of the 1352-ray charge wave. The even 16³ direction
     * lattice has no zero component: (0, 9), for example, can lie between rays from the bottom centre of a block.
     * The position hash changes which post is closest to 0.6 R; it used to select such a gap on some test origins.
     * Keep every requested share and threshold, but choose measurable posts rather than changing the pressure law.
     */
    static Set<BlockPos> sampledPosts(BlockPos c, Vec3 at, int n) {
        Set<BlockPos> sampled = new HashSet<>();
        for (int j = 0; j < 16; j++) for (int k = 0; k < 16; k++) for (int l = 0; l < 16; l++) {
            if (j != 0 && j != 15 && k != 0 && k != 15 && l != 0 && l != 15) continue;
            double x = (float) j / 15.0F * 2.0F - 1.0F;
            double y = (float) k / 15.0F * 2.0F - 1.0F;
            double z = (float) l / 15.0F * 2.0F - 1.0F;
            double len = Math.sqrt(x * x + y * y + z * z);
            x /= len; y /= len; z /= len;
            for (double d = 0; d <= 2 * n + 2; d += 0.3) {
                BlockPos p = BlockPos.containing(at.x + x * d, at.y + y * d, at.z + z * d);
                if (p.getY() != c.getY()) break;
                sampled.add(p);
            }
        }
        return sampled;
    }

    /** Shahed (50 kg): masonry breaks up to ~3,4 m (Jarrett A). */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_radius_brick", skyAccess = true)
    public static void droneBreaksBrickToJarrettA(GameTestHelper h) {
        radiusTest(h, Blocks.STONE_BRICKS, 12, WeaponType.DRONE.spec().charge());
    }

    /** Shahed: planks (light buildings, 4 psi) break up to ~6 m. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_radius_wood", skyAccess = true)
    public static void droneBreaksWoodToJarrettB(GameTestHelper h) {
        radiusTest(h, Blocks.OAK_PLANKS, 4, WeaponType.DRONE.spec().charge());
    }

    /** "Tomahawk" (150 kg): masonry - up to ~7 m. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_radius_missile", skyAccess = true)
    public static void missileBreaksBrickToJarrettA(GameTestHelper h) {
        radiusTest(h, Blocks.STONE_BRICKS, 12, WeaponType.MISSILE.spec().charge());
    }

    /**
     * Charge at the floor of a high closed room (masonry, 5×7×5 air): gases maintain pressure on all walls (UFC 3-340-02) - the walls
     *  are knocked out entirely, although a wave in an open field would break the masonry only a meter away from 10 kg.
     */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_room", skyAccess = true)
    public static void roomBlastBlowsOutWalls(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos base = h.absolutePos(CENTER.offset(-3, 0, -3));
        List<BlockPos> walls = new ArrayList<>();
        // Tall room, charge near the floor: gas loads the upper vertical walls horizontally, not along the steep
        // charge-to-block vector. Otherwise an adjacent wall block above wrongly looks like structural backing.
        for (int x = 0; x < 7; x++) for (int y = 0; y < 9; y++) for (int z = 0; z < 7; z++) {
            boolean wall = x == 0 || x == 6 || y == 0 || y == 8 || z == 0 || z == 6;
            BlockPos p = base.offset(x, y, z);
            level.setBlock(p, wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
            if (wall && y > 0) walls.add(p);
        }
        // walls are blocks to which the gases are adjacent (the edges and corners of the box remain pillars)
        walls.removeIf(p -> {
            for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                BlockPos n = p.relative(d);
                    if (n.getX() > base.getX() && n.getX() < base.getX() + 6 && n.getY() > base.getY() && n.getY() < base.getY() + 8
                        && n.getZ() > base.getZ() && n.getZ() < base.getZ() + 6) return false;
            }
            return true;
        });
        Vec3 at = Vec3.atCenterOf(base.offset(3, 2, 3));
        h.assertTrue(BlastDamage.breakRange(BlastModel.kpa(12), 10) < 1.5, "in an open field 10 kg break masonry further than a meter - check is empty");
        Warheads.Probe blast = Warheads.testCharge(level, at, 10);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "the explosion didn't end");
            int gone = 0;
            for (BlockPos p : walls) if (level.getBlockState(p).isAir()) gone++;
            h.assertTrue(gone >= walls.size() * 0.9, "gases knocked out " + gone + " from " + walls.size() + " wall and ceiling blocks");
        });
    }

    /**
     * The missile crater follows the charge law (D = 0,65·∛W, depth is a quarter) in its own soil: dry soil
     * (earth, sand, gravel embankments) has multiplier 1 (complaint 23.10: gravel under tracks was treated as rock,
     * ×0,7, and sand as water-saturated, ×1,3). One missile crater removes ~4 m³ in whole blocks with a random
     * fractional addition (1–8 blocks), so use nine craters on a grid 12 m apart and their sum: average
     * 9 × 4,0 = 36 m³; rock would give 12, saturated soil 79. Debris does not settle as blocks during the check:
     * only removed soil is counted; each hole stays within the rim (2 r + 1).
     */
    private static void craterTest(GameTestHelper h, Block ground, CraterModel.Soil soil) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos grass = h.absolutePos(GRASS);
        h.assertTrue(ua.zentix.incoming.warhead.GroundMaterial.of(ground.defaultBlockState()).soil() == soil, ground + " - not " + soil);
        // the top three layers are the soil being tested: a rocket crater less than a meter deep is entirely in it
        for (BlockPos p : BlockPos.betweenClosed(grass.offset(-18, -2, -18), grass.offset(18, 0, 18))) level.setBlock(p, ground.defaultBlockState(), 2);
        List<BlockPos> soilBlocks = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(grass.offset(-18, -4, -18), grass.offset(18, 0, 18))) soilBlocks.add(p.immutable());
        double tnt = WeaponType.MISSILE.spec().charge(), rc = CraterModel.chargeRadius(tnt, soil);
        List<Vec3> hits = new ArrayList<>();
        for (int i = -1; i <= 1; i++) {
            for (int k = -1; k <= 1; k++) hits.add(Vec3.atBottomCenterOf(grass.offset(12 * i, 1, 12 * k)));
        }
        for (Vec3 at : hits) Warheads.detonate(level, WeaponType.MISSILE, at, null, null);
        double law = hits.size() * CraterModel.chargeVolume(tnt, soil);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "hit queue is not empty");
            int dug = 0;
            double far = 0;
            for (BlockPos p : soilBlocks) {
                // removed - empty space; crater fuel (FuelFires) can burn in it, fire is also emptiness
                BlockState now = level.getBlockState(p);
                if (!now.isAir() && !(now.getBlock() instanceof BaseFireBlock)) continue;
                dug++;
                double near = Double.MAX_VALUE;
                for (Vec3 at : hits) near = Math.min(near, Math.hypot(p.getX() + 0.5 - at.x, p.getZ() + 0.5 - at.z));
                far = Math.max(far, near);
            }
            String got = String.format(Locale.ROOT, "%s: removed %d blocks in %d crater up to %.1f m (law: %.1f m³, r %.1f)",
                    ground, dug, hits.size(), far, law, rc);
            Incoming.LOG.info("BLASTBENCH crater {}", got);
            h.assertTrue(dug >= 0.6 * law && dug <= 1.45 * law, got);
            h.assertTrue(far <= 2 * rc + 1, got);
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_crater", skyAccess = true)
    public static void craterMatchesChargeLaw(GameTestHelper h) {
        craterTest(h, Blocks.DIRT, CraterModel.Soil.DRY);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_crater_gravel", skyAccess = true)
    public static void craterInRailBallastIsLooseGround(GameTestHelper h) {
        craterTest(h, Blocks.GRAVEL, CraterModel.Soil.DRY);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_crater_sand", skyAccess = true)
    public static void craterInDrySandIsDryGround(GameTestHelper h) {
        craterTest(h, Blocks.SAND, CraterModel.Soil.DRY);
    }

    /**
     * Fire after the ball of fuel (complaint 23.10: after the rocket, almost every block in the circle burned - roofs, sand, rails).
     * Around the impact on the grass: pillars of logs (thick wood) on the beams 3–20 m, pillars of foliage and hay (thin) on their rays
     *  inside the ball, a log near the crater, grass and poppies on the grass (burning in their place). The fire of the ball is set only for
     * fuel: for leaves and hay - with a share of their threshold ({@link PulseIgnition}), for logs - not; not a single fire of the ball stands on a
     *  floor that itself does not burn (the floor is grass and turf; this is how sand and rails “burned”), and everything is in the ball. The fuel that remains in the
     *  crater burns on the floor at the impact with its cells ({@link FuelFires}: as much as the area of ​​the law) - this is the fire of the
     *  fuel, not the fire of the ball; the log has only this kind of fire. The ball floats to 0,75·D: a crown of foliage above the impact at a height of D
     *  (outside the previous hemisphere D/2) lights up (BLASTSEQ D18). “Grad” has no fuel - no fire. Fire vanilla does not grow during the
     *  check ({@code doFireTick}), the wave of blocks does not break: only ignition is considered.
     */
    private static void fireTest(GameTestHelper h, WeaponType weapon) {
        ServerLevel level = h.getLevel();
        onlyIgnition(h);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        WeaponSpec spec = weapon.spec();
        double ballKg = FuelFireball.ballKg(spec.fuelAtImpact());
        double r = FuelFireball.diameter(ballKg) / 2, wet = Math.sqrt(spec.pool().cells() / Math.PI);
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        // logs: 16 rays, 3–20 m; thin - between them, 2,5 m to the edge of the ball
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8, d = 3 + i * 17 / 15.0;
            column(level, at, a, d, Blocks.OAK_LOG.defaultBlockState());
            double thin = 2.5 + (Math.max(r, 3) - 3) * i / 15.0;
            column(level, at, a + Math.PI / 16, thin, i % 2 == 0 ? leaves : Blocks.HAY_BLOCK.defaultBlockState());
        }
        // log near the crater - in a circle of burning fuel
        level.setBlock(BlockPos.containing(at.x + 1, at.y, at.z), Blocks.OAK_LOG.defaultBlockState(), 2);
        // plants on the grass (grass, flowers) in a circle 2,5 m to the edge of the ball: they burn in their place
        java.util.Set<BlockPos> plants = new java.util.HashSet<>();
        for (int i = 0; i < 24; i++) {
            double a = (i + 0.25) * Math.PI / 12, d = 2.5 + (Math.max(r, 3) - 3) * (i % 6) / 5.0;
            BlockPos p = BlockPos.containing(at.x + Math.cos(a) * d, at.y, at.z + Math.sin(a) * d);
            if (!level.getBlockState(p).isAir()) continue;
            level.setBlock(p, (i % 2 == 0 ? Blocks.SHORT_GRASS : Blocks.POPPY).defaultBlockState(), 2);
            plants.add(p.immutable());
        }
        // crown above the ball: at a height of D (not higher than the platform), outside the previous hemisphere D/2 at the point of impact - the ball floats up to it
        int crownY = (int) Math.min(Math.round(2 * r), 26), crownR = 6;
        java.util.List<BlockPos> crown = new java.util.ArrayList<>();
        if (r > 0) {
            for (int dx = -crownR; dx <= crownR; dx++) {
                for (int dz = -crownR; dz <= crownR; dz++) {
                    BlockPos p = BlockPos.containing(at.x + dx, at.y + crownY, at.z + dz);
                    level.setBlock(p, leaves, 2);
                    crown.add(p);
                }
            }
        }
        StrikeGameTests.afterTest(h, () -> {
            for (BlockPos p : crown) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), 2);
                level.setBlock(p.below(), Blocks.AIR.defaultBlockState(), 2);
            }
        });
        Warheads.detonate(level, weapon, at, null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "hit queue is not empty");
            int canopy = 0;
            for (BlockPos p : crown) {
                if (level.getBlockState(p.above()).getBlock() instanceof BaseFireBlock) canopy++;
                if (level.getBlockState(p.below()).getBlock() instanceof BaseFireBlock) canopy++;
            }
            Set<BlockPos> fuelCells = new HashSet<>();
            for (BlockPos p : FuelFires.get(level).cells()) if (Vec3.atCenterOf(p).distanceTo(at) < 24) fuelCells.add(p);
            int fires = 0, thin = 0, solid = 0, floor = 0, plant = 0, fuel = 0;
            double far = 0;
            for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(CENTER.offset(-24, -1, -24)), h.absolutePos(CENTER.offset(24, 6, 24)))) {
                if (!(level.getBlockState(p).getBlock() instanceof BaseFireBlock)) continue;
                // fire fuel crater - on the floor at the impact, on its cells: it should stand on the ground
                if (fuelCells.contains(p)) {
                    fuel++;
                    h.assertTrue(Vec3.atCenterOf(p).distanceTo(at) <= wet + 1.5,
                            String.format(Locale.ROOT, "%s: fuel cell at %.1f m - further than its circle %.1f m", weapon, Vec3.atCenterOf(p).distanceTo(at), wet));
                    continue;
                }
                fires++;
                far = Math.max(far, Vec3.atCenterOf(p).distanceTo(at));
                boolean byThin = false, bySolid = false;
                for (Direction d : Direction.values()) {
                    BlockState n = level.getBlockState(p.relative(d));
                    if (n.is(Blocks.OAK_LEAVES) || n.is(Blocks.HAY_BLOCK)) byThin = true;
                    else if (n.is(Blocks.OAK_LOG)) bySolid = true;
                }
                // fire in place of the plant - the plant itself burns; otherwise, a fire on a hard floor that does not burn itself (sand,
                //  stone, turf) is a fire “on the ground” next to the fuel
                BlockPos below = p.below();
                BlockState under = level.getBlockState(below);
                if (plants.contains(p)) plant++;
                else if (under.isFaceSturdy(level, below, Direction.UP) && !under.isFlammable(level, below, Direction.UP)) floor++;
                else if (byThin) thin++;
                else if (bySolid) solid++;
                else floor++;

            }
            String got = String.format(Locale.ROOT, "%s: fire ball %d (for a thin %d, plants %d, for logs %d, on an empty floor %d) up to %.1f m (ball %.1f m), in the crown on %d m - %d, fuel cells %d (law %d)",
                    weapon, fires, thin, plant, solid, floor, far, r, crownY, canopy, fuel, spec.pool().cells());
            Incoming.LOG.info("BLASTBENCH {}", got);
            h.assertTrue(floor == 0, got);
            // a thick tree does not ignite the ball's impulse; a log has only fuel fire
            h.assertTrue(solid == 0, got);
            h.assertTrue(fuel == spec.pool().cells(), got);
            if (r > 0) {
                h.assertTrue(thin >= 4, got);
                // plants in the ball - 24: more than half of the rocket lights up (14–15); for Shahed near the ground - a fifth of grass and less towards the edge of the ball
                //  (the ball floats up), on average 2–3 - not enough to check
                if (weapon == WeaponType.MISSILE) h.assertTrue(plant >= 6, got);
                h.assertTrue(far <= r + 1.5, got);
                // the bypass reaches the edge of the ball near the ground: before, the limit of cells ended it in the sky above the surfaced ball of the rocket
                //  (on the pulse 10 cal/cm², review #467), and the foliage at the edge did not light up
                h.assertTrue(far >= r - 2.5, got);
                // it burns the crown above the rising ball (BLASTSEQ D18: before the server’s ball stood as a hemisphere D/2 at the impact)
                h.assertTrue(canopy >= 5, got);
            } else h.assertTrue(fires == 0, got);
        });
    }

    /**
     * For the duration of the check, only ignition is considered: the wave of blocks does not break (the explosion would have blown away foliage and hay before the ball), debris
     *  does not remain, the vanilla fire does not grow ({@code doFireTick}).
     */
    private static void onlyIgnition(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get(), damage = IncomingConfig.SERVER.blockDamage.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        IncomingConfig.SERVER.blockDamage.set(false);
        GameRules.BooleanValue tick = level.getGameRules().getRule(GameRules.RULE_DOFIRETICK);
        boolean ticked = tick.get();
        tick.set(false, level.getServer());
        StrikeGameTests.afterTest(h, () -> {
            IncomingConfig.SERVER.debrisStay.set(debris);
            IncomingConfig.SERVER.blockDamage.set(damage);
            tick.set(ticked, level.getServer());
        });
    }

    /**
     * Columns of foliage in three blocks in a circle in {@code d} m from {@code at} ({@code n} pieces); returns their blocks. Their fire is
     * {@link #firesAt}.
     */
    private static List<BlockPos> leafRing(ServerLevel level, Vec3 at, double d, int n) {
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        List<BlockPos> placed = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double a = i * 2 * Math.PI / n;
            BlockPos p = BlockPos.containing(at.x + Math.cos(a) * d, at.y, at.z + Math.sin(a) * d);
            for (int y = 0; y < 3; y++) {
                level.setBlock(p.above(y), leaves, 2);
                placed.add(p.above(y).immutable());
            }
        }
        return placed;
    }

    /** How many fire cells do the {@code fuel} blocks have (along the edge). */
    private static int firesAt(ServerLevel level, List<BlockPos> fuel) {
        Set<BlockPos> fires = new HashSet<>();
        for (BlockPos p : fuel) {
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (level.getBlockState(q).getBlock() instanceof BaseFireBlock) fires.add(q);
            }
        }
        return fires.size();
    }

    /**
     * A rocket under a wide canopy (stone 63×63 at a height of 30 m - above the floating ball): under it - 120 thousand air cells with
     *  an impulse higher than {@code PulseIgnition.FLOOR}, three times the bypass limit. The bypass goes from near cells to far ones and
     *  reaches the foliage near the ground in 23 m, where the impulse is 6 cal/cm² (half of the foliage). Previously, it came from a larger impulse and
     *  ended in the air under a canopy at 9 cal/cm², not reaching the ground beyond the edge of the ball (review #467).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "blast_fire_roof", skyAccess = true)
    public static void missileFireReachesTheGroundUnderAWideRoof(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        onlyIgnition(h);
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        List<BlockPos> roof = new ArrayList<>();
        for (int dx = -31; dx <= 31; dx++) {
            for (int dz = -31; dz <= 31; dz++) {
                BlockPos p = h.absolutePos(CENTER.offset(dx, 30, dz));
                level.setBlock(p, Blocks.STONE.defaultBlockState(), 2);
                roof.add(p);
            }
        }
        List<BlockPos> leaves = leafRing(level, at, 23, 12);
        StrikeGameTests.afterTest(h, () -> {
            for (BlockPos p : roof) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
        });
        Warheads.detonate(level, WeaponType.MISSILE, at, null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "hit queue is not empty");
            int fires = firesAt(level, leaves);
            Incoming.LOG.info("BLASTBENCH MISSILE under a canopy: fire near the foliage in 23 m - {}", fires);
            h.assertTrue(fires >= 6, "under a canopy near the foliage in 23 m fire " + fires);
        });
    }

    /**
     * A rocket explodes in the air, 6 m above the ground (non-contact detonation, roof breach): the detonation point is highest in the columns
     *  nearby, and the bypass does not go above it and above the ceiling of the columns - to the foliage near the ground in 6 m. Previously, the ceiling cut off the detonation point
     *  from the ground: bypass ended on one cell (review #467).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "blast_fire_air", skyAccess = true)
    public static void missileAirburstLightsTheGroundBelow(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        onlyIgnition(h);
        Vec3 ground = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        List<BlockPos> leaves = leafRing(level, ground, 6, 8);
        Warheads.detonate(level, WeaponType.MISSILE, ground.add(0, 6, 0), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "hit queue is not empty");
            int fires = firesAt(level, leaves);
            Incoming.LOG.info("BLASTBENCH MISSILE in the air: fire near the foliage on the ground in 6 m - {}", fires);
            h.assertTrue(fires >= 4, "explosion at 6 m above the ground: fire near the foliage " + fires);
        });
    }

    /** A pillar of three blocks on a beam at an angle {@code a} in {@code d} m from {@code at}. */
    private static void column(ServerLevel level, Vec3 at, double a, double d, BlockState state) {
        BlockPos p = BlockPos.containing(at.x + Math.cos(a) * d, at.y, at.z + Math.sin(a) * d);
        for (int y = 0; y < 3; y++) level.setBlock(p.above(y), state, 2);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fire_drone", skyAccess = true)
    public static void droneFuelIgnitesOnlyInFireball(GameTestHelper h) {
        fireTest(h, WeaponType.DRONE);
    }

    @GameTest(template = "range", timeoutTicks = 400, batch = "blast_fire_missile", skyAccess = true)
    public static void missileFireballSparesSolidWood(GameTestHelper h) {
        fireTest(h, WeaponType.MISSILE);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fire_rocket", skyAccess = true)
    public static void rocketWithoutFuelLightsNothing(GameTestHelper h) {
        fireTest(h, WeaponType.ROCKET);
    }

    /** Bodies - according to wave pressure ({@code BlastBody}): a pig in 2 m from Shahed dies, in 30 m it is intact. */
    @GameTest(template = "range", timeoutTicks = 200, batch = "blast_bodies", skyAccess = true)
    public static void bodiesHurtByPressure(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(h.absolutePos(CENTER));
        Pig near = h.spawn(EntityType.PIG, CENTER.offset(2, 0, 0));
        Pig far = h.spawn(EntityType.PIG, CENTER.offset(-28, 0, 10));
        near.setNoAi(true);
        far.setNoAi(true);
        Warheads.Probe blast = Warheads.testCharge(level, at, WeaponType.DRONE.spec().charge());
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "the explosion didn't end");
            h.assertTrue(!near.isAlive(), "in 2 m alive: " + near.getHealth());
            h.assertTrue(far.isAlive() && far.getHealth() >= far.getMaxHealth() - 0.5f, "in 30 m wounded: " + far.getHealth());
        });
    }
}
