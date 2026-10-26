package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.S2C;
import ua.zentix.incoming.registry.ModDataMaps;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BreachModel;
import ua.zentix.incoming.warhead.Warheads;

import java.util.Locale;

/**
 * Breakdown of the slab by an applied charge (FM 5-250, {@link BreachModel}; complaint 23.10: a rocket on the bridge deck a meter of stone
 *  over the river and across the stadium field above the room left a hole in the block). A slab of stone above a void: thinner than the breakdown radius
 *  is a hole according to the law, thicker is not broken (only the crater on top). The soil is a half-space - a regular crater
 * ({@code BlastGameTests.craterTest}): there is no void underneath - there is no breakdown.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BreachGameTests {
    /** Middle of the “range” platform: upper floor block (floor – 11 layers, y 1…11 pads). */
    private static final BlockPos TOP = new BlockPos(32, 11, 32);
    /** The half-width of the slab and the voids underneath it, blocks: beyond the breakdown radius of any warhead. */
    private static final int HALF = 8;

    private BreachGameTests() {}

    /**
     * A slab of stone with a thickness of {@code thickness} (top - {@link #TOP}) above the void in the 3 block; impact of the warhead
     * {@code weapon} in the middle of the plate: the rocket - with its nose on the edge (a point on the upper edge), Shahed - at the aiming point (half a block
     *  in the upper block, like {@code Target}). Stone penetration radius - R; the path from the face to the bottom of the slab is no longer than R: the hole
     *  on the bottom face is a circle with radius √(R² − t²).
     */
    private static void slab(GameTestHelper h, WeaponType weapon, int thickness, boolean breaks) {
        slab(h, weapon, Blocks.STONE.defaultBlockState(), thickness, breaks, true);
    }

    /**
     * The same with a slab from the {@code block} block. The support that the server sends to the client in the explosion package ({@link Warheads#support}:
     *  picture of the target - a slab above the void or a support) - the same result: the slab is there and only where the breakdown rays demolish it,
     *  as thick as a slab (front review F: the client measured the slab with his rule and showed the breach where the server did not break through).
     * Without {@code blast} - only a support: on the boards, the wave breaks what is further than the breakdown (their pressure-impulse curve), and the demolished
     *  cannot be distinguished from the broken one; the support beam is the same {@code SlabBreach.march} as the stone breaker with {@code blast}.
     */
    private static void slab(GameTestHelper h, WeaponType weapon, BlockState block, int thickness, boolean breaks, boolean blast) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos top = h.absolutePos(TOP);
        int bottom = top.getY() - thickness + 1;
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF - 1, -thickness + 1, -HALF - 1), top.offset(HALF + 1, 0, HALF + 1))) {
            level.setBlock(p, block, Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF, -thickness - 2, -HALF), top.offset(HALF, -thickness, HALF))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        double tnt = weapon.spec().charge();
        BreachModel.Material material = ModDataMaps.Penetrability.of(block).breaching();
        h.assertTrue(material != null, "the block does not have a breakdown group: " + block);
        double r = material.radius(tnt, BreachModel.UNTAMPED);
        h.assertTrue(breaks == r > thickness, String.format(Locale.ROOT, "check empty: R %.2f m, plate %d m", r, thickness));
        Vec3 at = weapon == WeaponType.MISSILE ? Vec3.atBottomCenterOf(top.above()) : Vec3.atCenterOf(top);
        // package support - before the explosion: the package leaves before the rays
        S2C.Blast.Support support = Warheads.support(level, at, tnt, S2C.Blast.DOWN, null).orElse(null);
        h.assertTrue(support != null, "the charge is at the edge of the plate, but there is no support in the package");
        h.assertTrue(!support.loose(), "slab - not loose");
        h.assertTrue(breaks == support.slab() > 0, String.format(Locale.ROOT, "package support: plate %.2f m, and breakdown %s", support.slab(), breaks));
        if (breaks) {
            h.assertTrue(Math.abs(support.slab() - thickness) < 0.15, String.format(Locale.ROOT, "thickness in package %.2f m, plate %d m", support.slab(), thickness));
            h.assertTrue(Math.abs(support.breach() - r) < 0.01, String.format(Locale.ROOT, "breakdown radius in the package %.2f m, law %.2f m", support.breach(), r));
        }
        if (!blast) {
            h.succeed();
            return;
        }
        Warheads.detonate(level, weapon, at, null, null);
        double hole = breaks ? Math.sqrt(r * r - thickness * thickness) : 0;
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "hit queue is not empty");
            int gone = 0, missed = 0, far = 0, bottomGone = 0;
            for (int dx = -HALF; dx <= HALF; dx++) {
                for (int dz = -HALF; dz <= HALF; dz++) {
                    // the closest point of the block to the impact axis in plan
                    double near = Math.hypot(Math.max(Math.abs(dx) - 0.5, 0), Math.max(Math.abs(dz) - 0.5, 0));
                    for (int y = bottom; y <= top.getY(); y++) {
                        BlockState s = level.getBlockState(new BlockPos(top.getX() + dx, y, top.getZ() + dz));
                        if (!s.isAir()) {
                            // on the lower edge inside the breakdown circle - must be demolished; half block reserve: 1352 beam at the edge
                            //  holes lie through ~0,3 m, and in the corner of the block, which extends tens of centimeters into the circle,
                            //  the beam may not reach
                            if (breaks && y == bottom && near <= hole - 0.5) missed++;
                            continue;
                        }
                        gone++;
                        if (y == bottom) bottomGone++;
                        // beyond the radius of the breakdown (and the crater in the stone) nothing was demolished: the span stands
                        if (near > Math.max(hole, r) + 0.6) far++;
                    }
                }
            }
            String got = String.format(Locale.ROOT, "%s (%.0f kg) on the slab %d m: R %.2f m, hole at the bottom r %.2f m; demolished %d, on the lower edge of %d, "
                    + "not demolished in circle %d, demolished behind it %d", weapon, tnt, thickness, r, hole, gone, bottomGone, missed, far);
            Incoming.LOG.info("BLASTBENCH breakdown {}", got);
            h.assertTrue(far == 0, got);
            if (breaks) {
                h.assertTrue(missed == 0, got);
            } else {
                h.assertTrue(bottomGone == 0, got);
            }
        });
    }

    /** Rocket (150 kg): a meter of stone above the void - a hole from below ~5 m across (bridge deck, complaint 23.10). */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_missile", skyAccess = true)
    public static void missileBreachesMetreOfStone(GameTestHelper h) {
        slab(h, WeaponType.MISSILE, 1, true);
    }

    /** Rocket: three meters of stone are held above the void (R 2,7 m) - a crater on top, a whole below. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_missile_thick", skyAccess = true)
    public static void missileDoesNotBreachThreeMetres(GameTestHelper h) {
        slab(h, WeaponType.MISSILE, 3, false);
    }

    /** Shahed (50 kg): a meter of stone is broken (R 1,75 m). */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_drone", skyAccess = true)
    public static void droneBreachesMetreOfStone(GameTestHelper h) {
        slab(h, WeaponType.DRONE, 1, true);
    }

    /** Shahed (50 kg): a meter of boards is broken above the void (R wood ~1,8 m) - roof: support in a package - slab. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_drone_planks", skyAccess = true)
    public static void droneBreachesMetreOfPlanks(GameTestHelper h) {
        slab(h, WeaponType.DRONE, Blocks.OAK_PLANKS.defaultBlockState(), 1, true, false);
    }

    /**
     * “Grad” (6,4 kg): a meter of boards above the void holds (R wood ~0,9 m) - the support in the package is not a slab (review of the front F: client
     *  measured the thickness from the point of explosion, and not from the edge, and showed a gap that the server did not make).
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_rocket_planks", skyAccess = true)
    public static void rocketDoesNotBreachMetreOfPlanks(GameTestHelper h) {
        slab(h, WeaponType.ROCKET, Blocks.OAK_PLANKS.defaultBlockState(), 1, false, false);
    }

    /** Shahed: they hold two meters of stone. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_drone_thick", skyAccess = true)
    public static void droneDoesNotBreachTwoMetres(GameTestHelper h) {
        slab(h, WeaponType.DRONE, 2, false);
    }

    /**
     * The rocket hit the ground at 3 m from the relief ledge into the block (review complaint: gentle rays along the top layer went out into the
     *  void at the ledge and cut off a wedge of earth to 4 m - four times further than the crater). The void at the ledge is in the open air: this is
     *  the surface of the earth, the edge of the crater law, and not the back edge of the slab: there is no breakdown. No crater or debris - just
     *  the main explosion of the charge applied to the edge.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_terrace", skyAccess = true)
    public static void terraceStepIsNoBreachFace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(TOP);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF, -2, -HALF), top.offset(HALF, 0, HALF))) {
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(top.offset(3, 0, -HALF), top.offset(HALF, 0, HALF))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        double tnt = WeaponType.MISSILE.spec().charge();
        double r = BreachModel.Material.EARTH.radius(tnt, BreachModel.UNTAMPED);
        h.assertTrue(r > 3.5, String.format(Locale.ROOT, "check empty: R ground %.2f m", r));
        S2C.Blast.Support support = Warheads.support(level, Vec3.atBottomCenterOf(top.above()), tnt, S2C.Blast.DOWN, null).orElse(null);
        h.assertTrue(support != null && support.loose() && support.slab() == 0, "the support of the package at the ledge is soil, not a slab: " + support);
        Warheads.Probe blast = Warheads.testSurfaceCharge(level, Vec3.atBottomCenterOf(top.above()), tnt, 2 * r + 4);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "the explosion didn't end");
            h.assertTrue(blast.breached() == 0, "near a ledge in the open air demolished by a breakdown " + blast.breached() + " blocks");
        });
    }

    /**
     * A charge in the ground at a depth of 6,5 m in the open air (like a bomb at the given depth ~0,5; 1210 kg is the fraction that
     *  remained in the ground): the radius of breakdown of the earth ~10 m is greater than the depth, but the surface is the limit of the law crater
     *  ({@code BuriedBlast}), the breakdown does not go to it (review complaint: cone of earth to the surface above the ejection crater).
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "blast_breach_buried_open", skyAccess = true)
    public static void buriedChargeLeavesTheSurfaceToTheCrater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(16, 1, 16)), h.absolutePos(new BlockPos(47, 20, 47)))) {
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 14, 32)));
        double tnt = 1210;
        double r = BreachModel.Material.EARTH.radius(tnt, BreachModel.TAMPED);
        h.assertTrue(r > 6.5 + 1 && r < 12.5, String.format(Locale.ROOT, "check empty: R %.2f m", r));
        ChunkPos c = new ChunkPos(BlockPos.containing(at));
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.getChunk(c.x + dx, c.z + dz);
        Warheads.Probe blast = Warheads.testBuriedCharge(level, at, tnt, r + 4);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "the explosion didn't end");
            h.assertTrue(blast.breached() == 0, "carried to the open surface by breakdown " + blast.breached() + " blocks");
            h.assertTrue(h.getBlockState(new BlockPos(32, 20, 32)).is(Blocks.DIRT), "the top of the charge is blown away by the breakdown");
        });
    }

    /**
     * Bomb charge (2423 kg) in the thickness of the stone (hammered, C = 1: R ≈ 8,2 m): void in 4,5 m - pierced (the rock between the charge and
     *  has been demolished), void in 9,5 m - no (the wall to it is intact). The law and path are the same as for detonating a bomb
     *  ({@code Warheads.BunkerBlast}: a charge wave in the thickness); cavity, crushing and cracks are his work, they are not here.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "blast_breach_buried", skyAccess = true)
    public static void buriedChargeBreachesNearVoidOnly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(16, 1, 16)), h.absolutePos(new BlockPos(47, 28, 47)))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        // voids 3×3×3: near - edge in x 37 (4,5 m from the charge center), far - edge in x 23 (9,5 m)
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(37, 13, 31)), h.absolutePos(new BlockPos(39, 15, 33)))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(h.absolutePos(new BlockPos(20, 13, 31)), h.absolutePos(new BlockPos(22, 15, 33)))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Vec3 at = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 14, 32)));
        double tnt = WeaponType.BUNKER.spec().warheadTnt();
        double r = BreachModel.Material.ROCK.radius(tnt, BreachModel.TAMPED);
        h.assertTrue(r > 4.5 + 0.5 && r < 9.5, String.format(Locale.ROOT, "check empty: R %.2f m", r));
        ChunkPos c = new ChunkPos(BlockPos.containing(at));
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) level.getChunk(c.x + dx, c.z + dz);
        Warheads.Probe blast = Warheads.testBuriedCharge(level, at, tnt, r + 4);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "the explosion didn't end");
            int near = 0, far = 0;
            for (int x = 33; x <= 36; x++) if (h.getBlockState(new BlockPos(x, 14, 32)).isAir()) near++;
            for (int x = 23; x <= 31; x++) if (h.getBlockState(new BlockPos(x, 14, 32)).isAir()) far++;
            String got = String.format(Locale.ROOT, "bomb %.0f kg in stone: R %.2f m; demolished by breakdown %d, to the near void %d from 4, to the far void %d",
                    tnt, r, blast.breached(), near, far);
            Incoming.LOG.info("BLASTBENCH breakdown {}", got);
            h.assertTrue(near == 4, got);
            h.assertTrue(far == 0, got);
        });
    }

    /**
     * The support of the package ({@link Warheads#support}: the client's type of explosion is a slab over a void or a support) is the same solution that
     *  made by breaking down the server with this charge ({@code Probe.breached}): an explosion with the same course removes the slab with rays there and only where the
     *  support is a slab. {@code cell} - the block of the platform, in the middle of which the blow is made (the aiming point is half a block per block); {@code breaks} —
     * what we are waiting for according to the law.
     */
    private static void oneDecision(GameTestHelper h, String what, Vec3 at, Vec3 axis, double tnt, boolean breaks) {
        ServerLevel level = h.getLevel();
        ChunkPos c = new ChunkPos(BlockPos.containing(at));
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.getChunk(c.x + dx, c.z + dz);
        // package support - before the explosion: the package leaves before the rays
        S2C.Blast.Support support = Warheads.support(level, at, tnt, axis, null).orElse(null);
        boolean slab = support != null && support.slab() > 0;
        Warheads.Probe blast = Warheads.testSurfaceCharge(level, at, tnt, 2 * BreachModel.Material.EARTH.radius(tnt, BreachModel.UNTAMPED) + 4, axis);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "the explosion didn't end");
            String got = String.format(Locale.ROOT, "%s (%.1f kg): support package %s, breakdown demolished %d blocks", what, tnt, support, blast.breached());
            Incoming.LOG.info("BLASTBENCH breakdown support {}", got);
            h.assertTrue(slab == blast.breached() > 0, got);
            h.assertTrue(slab == breaks, got);
        });
    }

    /** Single layer roof {@code roof} over a room 9×9×3 dug into the floor of the site; roof top - {@link #TOP}. */
    private static void roof(GameTestHelper h, BlockState roof) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(TOP);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-4, -3, -4), top.offset(4, -1, 4))) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-5, 0, -5), top.offset(5, 0, 5))) level.setBlock(p, roof, Block.UPDATE_CLIENTS);
    }

    /**
     * Rocket (150 kg) at an angle spec (45° to the horizon) along the flooring in 2 m of stone above the void: R stone 2,7 m - punched (hole
     * ~1,8 m). Along the course, the path through the flooring is 2,8 m, longer than R: the support along the beam along the course (14f6c04) “did not penetrate” what the beams
     *  carry; The breakdown is decided by the rays at the normal - along it and the support.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void obliqueMissileOnTwoMetresOneDecision(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(TOP);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF - 1, -1, -HALF - 1), top.offset(HALF + 1, 0, HALF + 1))) {
            level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-HALF, -4, -HALF), top.offset(HALF, -2, HALF))) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        double tnt = WeaponType.MISSILE.spec().charge(), r = BreachModel.Material.ROCK.radius(tnt, BreachModel.UNTAMPED);
        Vec3 axis = new Vec3(0, -1, 1).normalize();
        h.assertTrue(r > 2 && r < 2 * Math.sqrt(2), String.format(Locale.ROOT, "check empty: R %.2f m", r));
        oneDecision(h, "rocket under 45° along 2 m stone", Vec3.atBottomCenterOf(top.above()), axis, tnt, true);
    }

    /** “Grad” (6,4 kg) per meter of boards, point - half a block in the roof: R boards 0,885 m - not pierced. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void gradOnPlanksOneDecision(GameTestHelper h) {
        double tnt = WeaponType.ROCKET.spec().charge();
        h.assertTrue(BreachModel.Material.POOR.radius(tnt, BreachModel.UNTAMPED) < 1, "check is empty: “Grad” penetrates a meter of boards");
        roof(h, Blocks.OAK_PLANKS.defaultBlockState());
        oneDecision(h, "\"Grad\" on the boards", Vec3.atCenterOf(h.absolutePos(TOP)), S2C.Blast.DOWN, tnt, false);
    }

    /**
     * Roof from lower half-blocks (review 1, B3): the client’s previous probe was not solid (the top is not a full face), there was no type of roof;
     *  for breakdown, a block with a collision shape is an obstacle. Shahed - broken, "Grad" - not.
     */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void droneOnHalfSlabRoofOneDecision(GameTestHelper h) {
        roof(h, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        oneDecision(h, "shahed by half blocks", Vec3.atCenterOf(h.absolutePos(TOP)), S2C.Blast.DOWN, WeaponType.DRONE.spec().charge(), true);
    }

    /** Same as "Grad". */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void gradOnHalfSlabRoofOneDecision(GameTestHelper h) {
        roof(h, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        oneDecision(h, "\"Grad\" in half blocks", Vec3.atCenterOf(h.absolutePos(TOP)), S2C.Blast.DOWN, WeaponType.ROCKET.spec().charge(), false);
    }

    /** Obsidian (resistance 1200, review 1, B3 - STOP): the charge does not displace it - it is not pierced. */
    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void blastProofRoofOneDecision(GameTestHelper h) {
        roof(h, Blocks.OBSIDIAN.defaultBlockState());
        oneDecision(h, "shahed by obsidian", Vec3.atCenterOf(h.absolutePos(TOP)), S2C.Blast.DOWN, WeaponType.DRONE.spec().charge(), false);
    }

    /**
     * A meter-high stone wall on the floor of the platform, Shahed strike from the side (course +x with a downward slope) into the middle of the block; behind the wall is
     * emptiness. Above it there is one crown (foliage that is not cut off by decay) - open sky for breakdown (height map without foliage,
     *  review 1, B3): support; under the stone canopy there is a ceiling, a meter of stone has been broken (R 1,75 m). The face normal is +x, not the course.
     */
    private static void wall(GameTestHelper h, boolean crown) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(TOP);
        for (BlockPos p : BlockPos.betweenClosed(top.offset(0, 1, -6), top.offset(0, 4, 6))) level.setBlock(p, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        BlockState over = crown ? Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true) : Blocks.STONE.defaultBlockState();
        for (BlockPos p : BlockPos.betweenClosed(top.offset(1, 6, -6), top.offset(8, crown ? 7 : 6, 6))) level.setBlock(p, over, Block.UPDATE_CLIENTS);
        oneDecision(h, crown ? "shahed into the wall under the crown" : "shahed into the wall under the canopy", Vec3.atCenterOf(top.offset(0, 2, 0)),
                new Vec3(1, -0.3, 0).normalize(), WeaponType.DRONE.spec().charge(), !crown);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void crownOverTheExitOneDecision(GameTestHelper h) {
        wall(h, true);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_breach_support", skyAccess = true)
    public static void roofOverTheExitOneDecision(GameTestHelper h) {
        wall(h, false);
    }
}
