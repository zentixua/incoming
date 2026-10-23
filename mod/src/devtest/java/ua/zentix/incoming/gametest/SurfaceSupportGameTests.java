package ua.zentix.incoming.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.nuclear.model.BlastDamage;
import ua.zentix.incoming.nuclear.model.BlastModel;
import ua.zentix.incoming.nuclear.world.BlockResponse;
import ua.zentix.incoming.registry.ModAttachments;
import ua.zentix.incoming.strike.StrikeWorld;
import ua.zentix.incoming.strike.WeaponType;
import ua.zentix.incoming.warhead.SurfaceDamage;
import ua.zentix.incoming.warhead.Warheads;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Regression classes: backing rather than block names, fractional cohesive excavation, outward block/body order. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurfaceSupportGameTests {
    private static final BlockPos C = new BlockPos(32, 12, 32);

    private SurfaceSupportGameTests() {}

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_support", skyAccess = true)
    public static void backingHoldsTheSamePanelThatBreaksInAir(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(C.above(2));
        Vec3 at = Vec3.atCenterOf(c);
        BlockPos free = c.east(2), backed = c.west(2);
        for (BlockPos p : List.of(free, backed)) level.setBlock(p, Blocks.GRAY_CONCRETE.defaultBlockState(), 2);
        level.setBlock(backed.west(), Blocks.DIRT.defaultBlockState(), 2);
        Warheads.Probe blast = Warheads.testCharge(level, at, WeaponType.DRONE.spec().charge());
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "wave unfinished");
            h.assertTrue(level.getBlockState(free).isAir(), "free concrete panel survived");
            h.assertTrue(level.getBlockState(backed).is(Blocks.GRAY_CONCRETE), "backed concrete panel broke");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fractional", skyAccess = true)
    public static void lancetDamagesConcreteWithoutOpeningTheSlab(GameTestHelper h) {
        fractional(h, false);
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_fractional_ground", skyAccess = true)
    public static void shahedDamagesSupportedPavingWithoutRemovingAMetre(GameTestHelper h) {
        fractional(h, true);
    }

    private static void fractional(GameTestHelper h, boolean supported) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos top = h.absolutePos(C.above(3));
        BlockState concrete = Blocks.BLACK_CONCRETE.defaultBlockState();
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-6, -3, -6), top.offset(6, 0, 6))) {
            level.setBlock(p, p.getY() == top.getY() ? concrete : supported ? Blocks.DIRT.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        Warheads.detonate(level, supported ? WeaponType.DRONE : WeaponType.LOITER, Vec3.atBottomCenterOf(top.above()), null, null);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).impacts().isEmpty(), "impact unfinished");
            for (BlockPos p : BlockPos.betweenClosed(top.offset(-6, 0, -6), top.offset(6, 0, 6))) {
                h.assertTrue(level.getBlockState(p).is(Blocks.BLACK_CONCRETE), "sub-block excavation removed slab at " + p);
            }
            double depth = SurfaceDamage.depth(level, top);
            h.assertTrue(depth > 0 && depth < 1, "missing fractional depth: " + depth);
            List<SurfaceDamage.Mark> marks = level.getChunkAt(top).getData(ModAttachments.SURFACE_DAMAGE);
            var encoded = SurfaceDamage.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, marks).getOrThrow();
            var restored = SurfaceDamage.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, encoded).getOrThrow();
            h.assertTrue(restored.equals(marks), "fractional damage did not survive save/load codec");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_front_wall", skyAccess = true)
    public static void bodiesBehindBrokenWallsAreHitButIntactWallsStillShield(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(C.above(2));
        Vec3 at = Vec3.atCenterOf(c);
        for (int y = -2; y <= 3; y++) for (int z = -4; z <= 4; z++) {
            level.setBlock(c.offset(1, y, z), Blocks.OAK_PLANKS.defaultBlockState(), 2);
            level.setBlock(c.offset(-1, y, z), Blocks.OBSIDIAN.defaultBlockState(), 2);
        }
        Pig open = pig(level, Vec3.atBottomCenterOf(c.offset(2, 0, 0)), 1);
        Pig shielded = pig(level, Vec3.atBottomCenterOf(c.offset(-2, 0, 0)), 2);
        h.assertTrue(net.minecraft.world.level.Explosion.getSeenPercent(at, open) == 0, "broken-wall test has no initial shield");
        Warheads.Probe blast = Warheads.testCharge(level, at, WeaponType.DRONE.spec().charge());
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "wave unfinished");
            h.assertTrue(level.getBlockState(c.east()).isAir(), "plank wall did not break");
            h.assertTrue(!open.isAlive() || open.getHealth() < open.getMaxHealth(), "body behind broken wall not wounded");
            h.assertTrue(shielded.isAlive() && shielded.getHealth() == shielded.getMaxHealth(), "body behind obsidian wounded");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_front_breach", skyAccess = true)
    public static void theSameChargeWoundsABodyUnderItsBreach(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos top = h.absolutePos(C.above(5));
        for (BlockPos p : BlockPos.betweenClosed(top.offset(-6, -4, -6), top.offset(6, 0, 6))) {
            level.setBlock(p, p.getY() == top.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
        }
        Vec3 at = Vec3.atBottomCenterOf(top.above());
        Pig body = pig(level, Vec3.atBottomCenterOf(top.below(2)), 3);
        h.assertTrue(net.minecraft.world.level.Explosion.getSeenPercent(at, body) == 0, "slab does not initially shield body");
        Warheads.Probe blast = Warheads.testSurfaceCharge(level, at, WeaponType.DRONE.spec().charge(), 15);
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "wave unfinished");
            h.assertTrue(blast.breached() > 0 && level.getBlockState(top).isAir(), "no breach");
            h.assertTrue(!body.isAlive() || body.getHealth() < body.getMaxHealth(), "body under breach not wounded");
        });
    }

    private static Pig pig(ServerLevel level, Vec3 at, int id) {
        Pig pig = EntityType.PIG.create(level);
        pig.setUUID(new UUID(0x415333, id));
        pig.setNoAi(true);
        pig.setNoGravity(true);
        pig.setPos(at);
        level.addFreshEntity(pig);
        return pig;
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_front_order", skyAccess = true)
    public static void theFrontDoesNotRemoveAFurtherWallBeforeHittingANearerBody(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos c = h.absolutePos(C.above(2)), far = c.east(4);
        level.setBlock(far, Blocks.OAK_PLANKS.defaultBlockState(), 2);
        boolean[] hit = {false}, intactAtHit = {false};
        Pig body = new Pig(EntityType.PIG, level) {
            @Override
            public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
                hit[0] = true;
                intactAtHit[0] = level.getBlockState(far).is(Blocks.OAK_PLANKS);
                return super.hurt(source, amount);
            }
        };
        body.setUUID(new UUID(0x415333, 4));
        body.setNoAi(true);
        body.setNoGravity(true);
        body.setPos(Vec3.atBottomCenterOf(c.east(2)));
        level.addFreshEntity(body);
        Warheads.Probe blast = Warheads.testCharge(level, Vec3.atCenterOf(c), WeaponType.DRONE.spec().charge());
        h.succeedWhen(() -> {
            h.assertTrue(blast.done(), "wave unfinished");
            h.assertTrue(hit[0] && intactAtHit[0], "further wall was already gone at the nearer body's hit");
            h.assertTrue(level.getBlockState(far).isAir(), "further wall never broke: ordering test is empty");
        });
    }

    @GameTest(template = "range", timeoutTicks = 300, batch = "blast_crater_seed", skyAccess = true)
    public static void craterSamplingDoesNotDependOnOtherWorldRandomDraws(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        boolean debris = IncomingConfig.SERVER.debrisStay.get();
        IncomingConfig.SERVER.debrisStay.set(false);
        StrikeGameTests.afterTest(h, () -> IncomingConfig.SERVER.debrisStay.set(debris));
        BlockPos a = h.absolutePos(new BlockPos(18, 11, 32)), b = a.east(28);
        for (BlockPos top : List.of(a, b)) {
            for (BlockPos p : BlockPos.betweenClosed(top.offset(-6, -5, -6), top.offset(6, 0, 6))) {
                level.setBlock(p, Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        Warheads.testDetonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(a.above()), null, 0x415333L);
        for (int i = 0; i < 1024; i++) level.random.nextLong();
        Warheads.testDetonate(level, WeaponType.MISSILE, Vec3.atBottomCenterOf(b.above()), null, 0x415333L);
        h.succeedWhen(() -> {
            h.assertTrue(StrikeWorld.get(level).idle(), "impacts unfinished");
            int holes = 0;
            for (BlockPos rel : BlockPos.betweenClosed(new BlockPos(-5, -4, -5), new BlockPos(5, 0, 5))) {
                BlockState left = level.getBlockState(a.offset(rel)), right = level.getBlockState(b.offset(rel));
                h.assertTrue(left.equals(right), "same impact seed produced different craters at " + rel);
                if (left.isAir()) holes++;
            }
            h.assertTrue(holes > 0, "seed comparison did not excavate soil");
        });
    }

    /** Pure layout check over translated threshold hashes, including a reproduced failing origin. */
    @GameTest(template = "range", timeoutTicks = 100, batch = "surface_layout", skyAccess = true)
    public static void nearbyPostsAreSampledForEveryThresholdHash(GameTestHelper h) {
        double tnt = WeaponType.MISSILE.spec().charge();
        for (int i = 0; i < 32; i++) {
            BlockPos c = new BlockPos(-10982969 + i * 997, 177, -15764973 + i * 613);
            Vec3 at = Vec3.atBottomCenterOf(c);
            Set<BlockPos> samples = BlastGameTests.sampledPosts(c, at, 22);
            h.assertTrue(!samples.contains(c.offset(0, 0, 9)), "regression origin no longer demonstrates a gap between rays");
            List<BlastGameTests.Post> bricks = BlastGameTests.layPosts(c, at, 12, tnt,
                    BlastDamage.breakRange(BlastModel.kpa(12), tnt), List.of(), b -> b < 0);
            List<BlastGameTests.Post> wood = BlastGameTests.layPosts(c, at, 4, tnt,
                    BlastDamage.breakRange(BlastModel.kpa(4), tnt), bricks, b -> b > 0);
            h.assertTrue(!bricks.isEmpty() && !wood.isEmpty(), "no test posts");
            h.assertTrue(wood.stream().anyMatch(p -> p.share() <= 0.65), "lost the strict 0.6 R inner plank post");
            h.assertTrue(wood.stream().anyMatch(p -> p.share() >= 1.3), "lost outer plank post");
            for (var p : wood) h.assertTrue(samples.contains(p.pos()), "unsampled plank " + p);
            for (var p : bricks) h.assertTrue(samples.contains(p.pos()), "unsampled brick " + p);
        }
        h.succeed();
    }
}
