package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.entity.BunkerBusterEntity;
import ua.zentix.incoming.scenario.SandboxStrikes;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.BuriedBlast;
import ua.zentix.incoming.warhead.Penetration;
import ua.zentix.incoming.warhead.Warheads;

import java.util.Locale;

/**
 * Physics of the bunker-buster: soil penetration follows Young using block materials (the
 * {@code incoming:penetration} data map), with a void sensor and an underground explosion by scaled depth:
 * no crater when deep, an ejecta crater when shallow.
 */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BombGameTests {
    private static final Vec3 DOWN = new Vec3(0, -1, 0);

    private BombGameTests() {}

    /** Pillar {@code state} 3×3 under {@code top} (in site coordinates) with height {@code height}. */
    private static void column(GameTestHelper h, BlockPos top, int height, BlockState state) {
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-1, -height + 1, -1), top.offset(1, 0, 1))) {
            h.getLevel().setBlock(h.absolutePos(p), state, Block.UPDATE_CLIENTS);
        }
    }

    /** The bomb's path is vertical downward at a speed of {@code v} m/s from the top of the block {@code top}. */
    private static BunkerBusterEntity.Path down(GameTestHelper h, BlockPos top, double v) {
        Vec3 face = Vec3.atBottomCenterOf(h.absolutePos(top)).add(0, 1, 0);
        return BunkerBusterEntity.plan(h.getLevel(), face, DOWN, v, 0);
    }

    private static String m(double x) {
        return String.format(Locale.ROOT, "%.2f", x);
    }

    /**
     * Penetration by material: stone and concrete (S 0.84), deepslate (0.67), soil (12) — exactly Young's path
     * for that medium (the data map is read block by block), ending at rest. For a concrete slab above soil,
     * the soil behind it uses S/2 (the same {@link Penetration.Run} that unit tests check).
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "bomb_penetration", skyAccess = true)
    public static void penetrationByMaterial(GameTestHelper h) {
        Penetration.Body mop = BunkerBusterEntity.body();
        record Case(String name, BlockState state, double s, double v, BlockPos top) {}
        Case[] cases = {
                new Case("stone", Blocks.STONE.defaultBlockState(), 0.84, 250, new BlockPos(16, 30, 16)),
                new Case("concrete", Blocks.GRAY_CONCRETE.defaultBlockState(), 0.84, 250, new BlockPos(24, 30, 16)),
                new Case("slate", Blocks.DEEPSLATE.defaultBlockState(), 0.67, 250, new BlockPos(32, 30, 16)),
                new Case("earth", Blocks.DIRT.defaultBlockState(), 12, 100, new BlockPos(40, 30, 16))};
        for (Case c : cases) column(h, c.top, 29, c.state);
        for (Case c : cases) {
            BunkerBusterEntity.Path path = down(h, c.top, c.v);
            double expect = Penetration.depth(mop, c.s, c.v) / ua.zentix.incoming.registry.ModDataMaps.Penetrability.of(c.state).thickness();
            h.assertTrue(path.end() == BunkerBusterEntity.Path.End.REST, c.name + ": bomb did not come to rest — " + path.end());
            h.assertTrue(Math.abs(path.length() - expect) < 1e-6, c.name + ": path " + m(path.length()) + ", according to Young " + m(expect));
        }
        // In stone at 235 m/s, ~3.9 m: impact after the game fall (B-2 drop at 12 blocks/tick from 170 blocks),
        // half the real speed.
        double stone = down(h, cases[0].top, 235).length();
        h.assertTrue(stone > 3.4 && stone < 4.5, "stone on 235 m/s: " + m(stone));
        // concrete slab in a block above the ground
        BlockPos slab = new BlockPos(48, 30, 16);
        column(h, slab.below(), 28, Blocks.DIRT.defaultBlockState());
        column(h, slab, 1, Blocks.GRAY_CONCRETE.defaultBlockState());
        Penetration.Run run = new Penetration.Run(mop, 100);
        double concreteThickness = ua.zentix.incoming.registry.ModDataMaps.Penetrability.of(Blocks.GRAY_CONCRETE.defaultBlockState()).thickness();
        double expect = run.through(Penetration.Kind.CONCRETE, 0.84, concreteThickness) / concreteThickness;
        for (int i = 0; i < 28 && !run.stopped(); i++) expect += run.through(Penetration.Kind.SOIL, 12, 1);
        double under = down(h, slab, 100).length();
        h.assertTrue(Math.abs(under - expect) < 1e-6, "slab and earth: path " + m(under) + ", waited " + m(expect));
        h.assertTrue(under < Penetration.depth(mop, 12, 100), "behind the slab the earth is not harder");
        // carpet on the ground - 1/16 of a block of other hard materials (S 1), and not a meter of brick; and not a concrete slab: the ground behind it is ordinary
        BlockPos rug = new BlockPos(56, 30, 16);
        column(h, rug.below(), 28, Blocks.DIRT.defaultBlockState());
        column(h, rug, 1, Blocks.WHITE_CARPET.defaultBlockState());
        Penetration.Run soft = new Penetration.Run(mop, 100);
        double rugExpect = soft.through(Penetration.Kind.ROCK, 1, 1 / 16.0);
        for (int i = 0; i < 28 && !soft.stopped(); i++) rugExpect += soft.through(Penetration.Kind.SOIL, 12, 1);
        double onRug = BunkerBusterEntity.plan(h.getLevel(), Vec3.atBottomCenterOf(h.absolutePos(rug)).add(0, 1 / 16.0, 0), DOWN, 100, 0).length();
        h.assertTrue(Math.abs(onRug - rugExpect) < 1e-6, "carpet and ground: path " + m(onRug) + ", waited " + m(rugExpect));
        h.succeed();
    }

    /**
     * An oblique path through homogeneous soil equals the vertical Young path: crossing block faces must not
     * restart the layer. Found by the end-to-end {@code surface_bomb} test (ET 23.10): at 89°, the B-2 bomb's
     * soil path exceeded the law by 18–29 % (40 % at 80°; vertical was exact). A tail outside the block shape
     * as short as float error was counted as a void. Speed 160 m/s, path ~35 m: fits the platform height.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "bomb_oblique", skyAccess = true)
    public static void obliquePathIsYoung(GameTestHelper h) {
        Penetration.Body mop = BunkerBusterEntity.body();
        BlockPos top = new BlockPos(32, 38, 32);
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(8, 2, 8), new BlockPos(55, 38, 55))) {
            h.getLevel().setBlock(h.absolutePos(p), (p.getY() == 38 ? Blocks.PODZOL : Blocks.DIRT).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        double v = 160, expect = Penetration.depth(mop, 12, v);
        h.assertTrue(expect > 25 && expect < 36, "path along Young " + m(expect) + " does not fit into the earth cube");
        Vec3 face = Vec3.atBottomCenterOf(h.absolutePos(top)).add(0.13, 1, 0.29);
        for (double pitch : new double[]{90, 89, 80, 70}) {
            for (double yaw : new double[]{0, 30, 45}) {
                double p = Math.toRadians(pitch), y = Math.toRadians(yaw);
                Vec3 dir = new Vec3(Math.cos(p) * Math.sin(y), -Math.sin(p), Math.cos(p) * Math.cos(y));
                BunkerBusterEntity.Path path = BunkerBusterEntity.plan(h.getLevel(), face, dir, v, 0);
                h.assertTrue(path.end() == BunkerBusterEntity.Path.End.REST, pitch + "°/" + yaw + "°: bomb did not come to rest — " + path.end());
                h.assertTrue(Math.abs(path.length() - expect) < 1e-3 * expect,
                        pitch + "°/" + yaw + "°: path " + m(path.length()) + ", according to Young " + m(expect));
            }
        }
        h.succeed();
    }

    /**
     * Void sensor: after 5 m of stone, a cave in 10 blocks - detonation when the bomb entered it entirely (its own length); for
     *  with an overlap thinner than {@value BunkerBusterEntity#SENSOR_SOLID} m - it doesn’t work, the bomb goes on.
     */
    @GameTest(template = "range", timeoutTicks = 20, batch = "bomb_void", skyAccess = true)
    public static void voidSensor(GameTestHelper h) {
        BlockPos top = new BlockPos(20, 30, 20);
        column(h, top, 5, Blocks.STONE.defaultBlockState());
        column(h, top.below(15), 10, Blocks.STONE.defaultBlockState());
        BunkerBusterEntity.Path path = down(h, top, 400);
        h.assertTrue(path.end() == BunkerBusterEntity.Path.End.VOID, "the sensor did not work: " + path.end());
        h.assertTrue(Math.abs(path.length() - (5 + BunkerBusterEntity.voidDepth())) < 1e-6, "explosion in " + m(path.length()) + " from the entrance");
        BlockPos thin = new BlockPos(40, 30, 20);
        column(h, thin, 2, Blocks.STONE.defaultBlockState());
        column(h, thin.below(12), 15, Blocks.STONE.defaultBlockState());
        BunkerBusterEntity.Path through = down(h, thin, 400);
        h.assertTrue(through.end() == BunkerBusterEntity.Path.End.REST && through.length() > 12,
                "roof in block 2: the sensor was triggered or the bomb landed before the floor - " + through.end() + " on " + m(through.length()));
        // crown in 6 blocks of foliage above the clearing: the foliage slows down barely - the sensor does not arm, the bomb goes into the ground
        BlockPos crown = new BlockPos(30, 30, 40);
        column(h, crown, 6, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        column(h, crown.below(16), 15, Blocks.DIRT.defaultBlockState());
        BunkerBusterEntity.Path forest = down(h, crown, 250);
        h.assertTrue(forest.end() == BunkerBusterEntity.Path.End.REST && forest.length() > 16,
                "the crown cocked the void sensor: " + forest.end() + " on " + m(forest.length()));
        h.succeed();
    }

    /** Site soil: stone {@code lo}…{@code hi} (in site coordinates). */
    private static void rock(GameTestHelper h, BlockPos lo, BlockPos hi) {
        for (BlockPos p : BlockPos.betweenClosed(lo, hi)) h.getLevel().setBlock(h.absolutePos(p), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void generateNow(ServerLevel level, Vec3 at, int radius) {
        ChunkPos c = new ChunkPos(BlockPos.containing(at));
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) level.getChunk(c.x + dx, c.z + dz);
    }

    /** /fx real bunker should not silently pass a bomb charge through a conventional zero charge warhead. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "sandbox_bunker", skyAccess = true)
    public static void sandboxBombActuallyDetonates(GameTestHelper h) {
        BlockPos point = new BlockPos(32, 2, 32);
        Vec3 at = Vec3.atCenterOf(h.absolutePos(point));
        generateNow(h.getLevel(), at, 4);
        SandboxStrikes.detonate(h.getLevel(), WeaponType.BUNKER, at);
        h.succeedWhen(() -> {
            h.assertTrue(BlastLog.near(h, point, 3), "no real bomb detonation");
            h.assertTrue(StrikeWorld.get(h.getLevel()).impacts().isEmpty(), "demolition work is underway");
        });
    }

    /**
     * Charge at a depth of ~20 m in the stone (scaled depth 1,5): no crater - the top above the charge is intact; at the place of the charge there is a
     *  cavity, nearby there is crushed rock (cobblestone), the arch of the passage in the crack zone has collapsed with rubble.
     */
    @GameTest(template = "range", timeoutTicks = 400, batch = "bomb_deep", skyAccess = true)
    public static void deepChargeLeavesNoCrater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        rock(h, new BlockPos(16, 1, 16), new BlockPos(47, 28, 47));
        // movement of 1×2 along z in 12–17 blocks from the charge: behind the crushing zone (10 m), in the crack zone (27 m)
        for (int z = 22; z <= 42; z++) {
            for (int y = 8; y <= 9; y++) level.setBlock(h.absolutePos(new BlockPos(44, y, z)), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 8, 32)));
        generateNow(level, charge, 3);
        BuriedBlast law = new BuriedBlast(2423, 29 - charge.y + h.absolutePos(BlockPos.ZERO).getY());
        h.assertTrue(!law.breaches(), "charge shallow: scaled depth " + m(law.scaledDepth()));
        Warheads.bunker(level, charge, charge.add(0, 20, 0), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "demolition work is underway");
            h.assertTrue(h.getBlockState(new BlockPos(32, 28, 32)).is(Blocks.STONE), "the top above the charge has been demolished - crater at depth 20");
            h.assertTrue(h.getBlockState(new BlockPos(32, 8, 32)).isAir(), "no cavity");
            h.assertTrue(h.getBlockState(new BlockPos(32, 8, 38)).is(Blocks.COBBLESTONE), "there is no crushing in 6 blocks");
            int fallen = 0;
            for (int z = 22; z <= 42; z++) {
                BlockState roof = h.getBlockState(new BlockPos(44, 10, z)), floor = h.getBlockState(new BlockPos(44, 8, z));
                if (!roof.is(Blocks.STONE) || floor.is(Blocks.GRAVEL)) fallen++;
            }
            h.assertTrue(fallen > 0, "the roof of the passage in the crack zone is intact");
        });
    }

    /** Explosion canceled by private (ExplosionEvent.Start), does not dig: no cavity, no crushing. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "bomb_cancelled", skyAccess = true)
    public static void cancelledBlastDigsNothing(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        rock(h, new BlockPos(16, 1, 16), new BlockPos(47, 28, 47));
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 8, 32)));
        generateNow(level, charge, 3);
        java.util.function.Consumer<net.neoforged.neoforge.event.level.ExplosionEvent.Start> deny = e -> {
            if (e.getLevel() == level && e.getExplosion().center().distanceTo(charge) < 1) e.setCanceled(true);
        };
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(deny);
        StrikeGameTests.afterTest(h, () -> net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(deny));
        Warheads.bunker(level, charge, charge.add(0, 20, 0), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "demolition work is underway");
            h.assertTrue(h.getBlockState(new BlockPos(32, 8, 32)).is(Blocks.STONE), "canceled explosion gnawed out a cavity");
            h.assertTrue(h.getBlockState(new BlockPos(32, 8, 38)).is(Blocks.STONE), "a canceled explosion crushed the rock");
        });
    }

    /** Charge at a depth of 3 m (scaled depth 0,22): ejection crater - the top above the charge and around it has been demolished. */
    @GameTest(template = "range", timeoutTicks = 400, batch = "bomb_shallow", skyAccess = true)
    public static void shallowChargeBlowsACrater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        rock(h, new BlockPos(16, 1, 16), new BlockPos(47, 22, 47));
        Vec3 charge = Vec3.atCenterOf(h.absolutePos(new BlockPos(32, 19, 32)));
        generateNow(level, charge, 4);
        Warheads.bunker(level, charge, charge.add(0, 3, 0), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "demolition work is underway");
            int gone = 0;
            for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) if (h.getBlockState(new BlockPos(32 + dx, 22, 32 + dz)).isAir()) gone++;
            // bowl crater in stone (BuriedBlast.craterRadius): the top is removed where it is deeper than the block - a circle with radius r,
            // where h·(1 − (r/R)²) ≥ 1
            BuriedBlast law = new BuriedBlast(2423, 3);
            var rock = ua.zentix.incoming.nuclear.model.CraterModel.Soil.ROCK;
            double r = law.craterRadius(rock) * Math.sqrt(Math.max(0, 1 - 1 / law.craterDepth(rock)));
            int expect = (int) Math.floor(0.8 * Math.PI * r * r);
            h.assertTrue(gone >= expect, "crater no: demolished " + gone + " from the 81 block of the top around the charge, we waited no less " + expect);
            h.assertTrue(h.getBlockState(new BlockPos(32, 20, 32)).isAir(), "crater did not reach the cavity");
        });
    }
}
