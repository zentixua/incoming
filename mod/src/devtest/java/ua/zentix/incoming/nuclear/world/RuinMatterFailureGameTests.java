package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.RegisterEvent;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;

/** Real block callbacks, with the production SavedData file and streaming reload paths. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
@EventBusSubscriber(modid = Incoming.MOD_ID)
public final class RuinMatterFailureGameTests {
    private RuinMatterFailureGameTests() {}

    private static BlockPos collisionFailure;
    private static int collisionCalls;
    private static Block collider;

    private static Block collider() { return new Block(BlockBehaviour.Properties.of().replaceable().dynamicShape()) {
        @Override public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos at, CollisionContext context) {
            if (world instanceof ServerLevel && at.equals(collisionFailure)) {
                collisionCalls++;
                throw new CallbackFailure();
            }
            return Shapes.empty();
        }
    }; }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        event.register(Registries.BLOCK, helper -> {
            collider = collider();
            helper.register(ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, "test_material_collision"), collider);
        });
    }

    private static final class CallbackFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static void work(RuinMatter ledger, ServerLevel level) {
        var clock = WorkClock.counting(1);
        clock.start(1);
        ledger.work(level, clock);
    }

    @GameTest(template = "pad", timeoutTicks = 1200, batch = "nuke_material_failure_collision", skyAccess = true)
    public static void collisionExceptionRetainsCargoAcrossNativeReload(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(8, 3, 8));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(at.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
            for (int y = 0; y <= 3; y++) level.setBlock(at.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
        }
        var source = at.above(3);
        level.setBlock(source, Blocks.BARREL.defaultBlockState(), 2);
        var barrel = (BarrelBlockEntity) level.getBlockEntity(source);
        barrel.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var cargo = barrel.saveWithoutMetadata(level.registryAccess());
        var piece = new RuinMatter.Piece(source, Blocks.BARREL.defaultBlockState(), cargo,
                new Vec3(0, -1, 0), level.getGameTime() - 1);
        piece.y = at.getY() + 0.1;
        ColumnScar.replace(level, source, piece.state, Blocks.AIR.defaultBlockState());
        var ledger = new RuinMatter();
        ledger.release(java.util.List.of(piece));
        level.setBlock(at, collider.defaultBlockState(), 2);
        collisionFailure = at;
        collisionCalls = 0;
        StrikeGameTests.afterTest(h, () -> { collisionFailure = null; ledger.unload(level); });
        try {
            work(ledger, level);
            h.fail("Real collision callback did not throw");
        } catch (CallbackFailure expected) {
            h.assertTrue(collisionCalls == 1, "Failure did not come from the live collision callback");
        }
        collisionFailure = null;
        h.assertTrue(ledger.pending() == 1 && ledger.released() == 1 && ledger.deposited() == 0,
                "Collision exception erased sole material owner before placement: pending=" + ledger.pending());
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
        var file = java.nio.file.Files.createTempDirectory(level.getDataStorage().dataFolder.toPath(), "material-collision-")
                .resolve("material.dat");
        ledger.save(file.toFile(), level.registryAccess());
        RuinMatter[] restored = {null};
        StrikeGameTests.afterTest(h, () -> { if (restored[0] != null) restored[0].unload(level); });
        h.succeedWhen(() -> {
            h.assertFalse(ledger.writing(), "Native save has not completed");
            if (restored[0] == null) {
                try {
                    var scan = LedgerScan.measure(file, "incoming_ruin_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP);
                    restored[0] = (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(), scan.row(), java.util.Set.of());
                    h.assertTrue(scan.owned() >= restored[0].retainedBytes(), "Native preflight undercharged restored owner");
                    h.assertTrue(restored[0].pending() == 1, "Native reload lost pending material");
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
            }
            work(restored[0], level);
            h.assertTrue(restored[0].pending() == 0 && restored[0].deposited() == 1, "Restored block has not settled exactly once");
            h.assertTrue(level.getBlockEntity(at) instanceof BarrelBlockEntity, "Restored material did not reach its destination");
            var placed = (BarrelBlockEntity) level.getBlockEntity(at);
            h.assertTrue(placed.getItem(0).is(Items.DIAMOND) && placed.getItem(0).getCount() == 7, "Restored cargo was lost or duplicated");
        });
    }
}
