package ua.zentix.incoming.gametest;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.LockCode;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.warhead.Warheads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static ua.zentix.incoming.gametest.CollapseGameTests.GROUND;

@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CollapseRegressionGameTests {
    private static final BlockState BRICKS = Blocks.BRICKS.defaultBlockState();
    private static final BlockPos PILLAR = new BlockPos(31, GROUND + 1, 31);

    private CollapseRegressionGameTests() {}

    private static void set(GameTestHelper h, BlockPos p, BlockState state) {
        h.getLevel().setBlock(h.absolutePos(p), state, Block.UPDATE_CLIENTS);
    }

    private static Warheads.CollapseProbe collapse(GameTestHelper h, BlockPos centre, double reach, List<BlockPos> removed) {
        LongArrayList at = new LongArrayList();
        for (BlockPos p : removed) at.add(h.absolutePos(p).asLong());
        return Warheads.testCollapse(h.getLevel(), h.absoluteVec(Vec3.atCenterOf(centre)), reach,
                at, Collections.nCopies(removed.size(), BRICKS), System::nanoTime);
    }

    private static List<BlockPos> cutPillar(GameTestHelper h) {
        CollapseGameTests.quiet(h);
        CollapseGameTests.ground(h);
        for (int i = 0; i < 5; i++) set(h, PILLAR.above(i), BRICKS);
        List<BlockPos> roof = new ArrayList<>();
        for (int x = 29; x <= 33; x++) for (int z = 29; z <= 33; z++) {
            BlockPos p = new BlockPos(x, GROUND + 6, z);
            roof.add(p);
            set(h, p, BRICKS);
        }
        set(h, PILLAR, Blocks.AIR.defaultBlockState());
        set(h, PILLAR.above(2), Blocks.AIR.defaultBlockState());
        return roof;
    }

    @GameTest(template = "range", batch = "collapse_overlap", timeoutTicks = 2400, skyAccess = true)
    public static void simultaneousHolesInOnePillarDoNotLeaveRoofHanging(GameTestHelper h) {
        List<BlockPos> roof = cutPillar(h);
        Warheads.CollapseProbe a = collapse(h, PILLAR, 23, List.of(PILLAR));
        Warheads.CollapseProbe b = collapse(h, PILLAR.above(2), 23, List.of(PILLAR.above(2)));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(a.done() && b.done(), "both concurrent collapses must finish"))
                .thenExecute(() -> {
                    long hanging = roof.stream().filter(p -> h.getBlockState(p).is(Blocks.BRICKS)).count();
                    h.assertTrue(hanging == 0 && a.fell() + b.fell() == 28, hanging + "/" + roof.size()
                            + " roof blocks stayed (fell " + a.fell() + " + " + b.fell() + ", expected 28 total)");
                }).thenSucceed();
    }

    @GameTest(template = "range", batch = "collapse_overlap", timeoutTicks = 2400, skyAccess = true)
    public static void overlappingHolesKeepBothClaimContexts(GameTestHelper h) {
        cutPillar(h);
        BlockPos protectedRoof = h.absolutePos(PILLAR.above(5));
        Vec3 allowed = h.absoluteVec(Vec3.atCenterOf(PILLAR));
        Vec3 denied = h.absoluteVec(Vec3.atCenterOf(PILLAR.above(2)));
        boolean[] offered = new boolean[2];
        Consumer<ExplosionEvent.Detonate> claim = e -> {
            if (e.getLevel() != h.getLevel() || e.getExplosion().radius() != 0) return;
            Vec3 center = e.getExplosion().center();
            if (center.equals(allowed) && e.getExplosion().getToBlow().contains(protectedRoof)) offered[0] = true;
            if (center.equals(denied) && e.getExplosion().getToBlow().remove(protectedRoof)) offered[1] = true;
        };
        NeoForge.EVENT_BUS.addListener(claim);
        StrikeGameTests.afterTest(h, () -> NeoForge.EVENT_BUS.unregister(claim));
        Warheads.CollapseProbe a = collapse(h, PILLAR, 23, List.of(PILLAR));
        Warheads.CollapseProbe b = collapse(h, PILLAR.above(2), 23, List.of(PILLAR.above(2)));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(a.done() && b.done(), "both concurrent collapses must finish"))
                .thenExecute(() -> {
                    h.assertTrue(offered[0] && offered[1], "shared roof offered in both impact contexts");
                    h.assertTrue(a.fell() + b.fell() == 0, "protected bearing block must keep the fall plan standing");
                    h.assertBlockPresent(Blocks.BRICKS, PILLAR.above(5));
                }).thenSucceed();
    }

    @GameTest(template = "range", batch = "collapse_storage", timeoutTicks = 2400, skyAccess = true)
    public static void collapseDropsNamedEmptyStorageLikeVanillaBreak(GameTestHelper h) {
        CollapseGameTests.ground(h);
        BlockPos normal = PILLAR.east(3).above();
        BlockPos collapsing = PILLAR.above();
        var level = h.getLevel();
        ItemStack stack = new ItemStack(Blocks.CYAN_SHULKER_BOX);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Emergency supplies"));
        stack.set(DataComponents.LOCK, new LockCode("Emergency key"));
        for (BlockPos p : List.of(normal, collapsing)) {
            set(h, p, Blocks.CYAN_SHULKER_BOX.defaultBlockState());
            level.getBlockEntity(h.absolutePos(p)).applyComponentsFromItemStack(stack);
        }
        level.destroyBlock(h.absolutePos(normal), true);
        AABB control = new AABB(h.absolutePos(normal)).inflate(0.5);
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, control, e -> e.getItem().is(stack.getItem()));
        h.assertTrue(items.size() == 1, "vanilla break must drop one shulker box");
        ItemStack expected = items.getFirst().getItem().copy();
        h.assertTrue(expected.getHoverName().equals(stack.getHoverName()), "vanilla drop must keep the name");
        h.assertTrue(expected.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY)
                .equals(ItemContainerContents.EMPTY), "control must be empty");
        h.assertTrue(stack.get(DataComponents.LOCK).equals(expected.get(DataComponents.LOCK)), "vanilla drop must keep the lock");
        items.getFirst().discard();

        Warheads.CollapseProbe probe = collapse(h, PILLAR, 23, List.of(PILLAR));
        AABB area = new AABB(h.absolutePos(collapsing)).inflate(2);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(probe.done(), "collapse unfinished"))
                .thenExecute(() -> {
                    h.assertTrue(probe.fell() == 1, "storage must collapse");
                    h.assertBlockNotPresent(Blocks.CYAN_SHULKER_BOX, collapsing);
                    int dropped = level.getEntitiesOfClass(ItemEntity.class, area,
                            e -> ItemStack.isSameItemSameComponents(expected, e.getItem())).size();
                    h.assertTrue(dropped == 1, "collapse dropped " + dropped + " matching boxes; vanilla break dropped one");
                }).thenSucceed();
    }

    @GameTest(template = "range", batch = "collapse_debris_off", timeoutTicks = 2400, skyAccess = true)
    public static void collapseRemovesStructureButLeavesNoDebrisWhenDisabled(GameTestHelper h) {
        var setting = IncomingConfig.SERVER.debrisStay;
        boolean original = setting.get();
        setting.set(false);
        StrikeGameTests.afterTest(h, () -> setting.set(original));
        CollapseGameTests.ground(h);
        List<BlockPos> removed = new ArrayList<>();
        // The fixture extends beyond the fall area but stays in loaded chunks, exercising both landing paths.
        for (int x = 17; x <= 46; x += 2) for (int z = 17; z <= 46; z += 2) {
            BlockPos foot = new BlockPos(x, GROUND + 1, z);
            set(h, foot.above(), BRICKS);
            removed.add(foot);
        }
        BlockPos storage = new BlockPos(30, GROUND + 2, 30);
        set(h, storage, Blocks.CYAN_SHULKER_BOX.defaultBlockState());
        removed.add(storage.below());
        Warheads.CollapseProbe probe = collapse(h, PILLAR, 10, removed);
        AABB area = new AABB(h.absolutePos(new BlockPos(15, GROUND + 1, 15))).expandTowards(34, 4, 34);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(probe.done(), "collapse unfinished"))
                .thenWaitUntil(() -> h.assertTrue(h.getLevel().getEntitiesOfClass(FallingBlockEntity.class, area).isEmpty(),
                        "live rubble still falling"))
                .thenExecute(() -> {
                    h.assertTrue(probe.fell() == removed.size(), "expected all fixture blocks to fall, got " + probe.fell());
                    h.assertTrue(probe.liveRubble() > 0 && probe.directRubble() > 0,
                            "expected both landing paths, got " + probe.liveRubble() + " live and " + probe.directRubble() + " direct");
                    int debris = 0;
                    for (BlockPos foot : BlockPos.betweenClosed(new BlockPos(15, GROUND + 1, 15), new BlockPos(48, GROUND + 2, 48))) {
                        if (h.getBlockState(foot).is(Blocks.BRICKS)) debris++;
                    }
                    h.assertTrue(debris == 0, debris + " brick rubble blocks landed with debris_stay=false");
                    h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, area,
                            e -> e.getItem().is(Blocks.CYAN_SHULKER_BOX.asItem())).size() == 1, "storage item must still drop");
                }).thenSucceed();
    }
}
