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
    private static Block container, decoration;
    private static net.minecraft.world.level.block.entity.BlockEntityType<CargoEntity> cargoType;
    private static BlockPos placementFailure, cargoFailure;
    private static boolean dropFailure;

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
            container = new CargoBlock();
            helper.register(ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, "test_material_container"), container);
            decoration = new Block(BlockBehaviour.Properties.of().noCollission()) {
                @Override protected java.util.List<ItemStack> getDrops(BlockState state, net.minecraft.world.level.storage.loot.LootParams.Builder params) {
                    if (dropFailure) throw new CallbackFailure();
                    return java.util.List.of();
                }
            };
            helper.register(ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, "test_material_decoration"), decoration);
        });
        event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
            cargoType = net.minecraft.world.level.block.entity.BlockEntityType.Builder.of(CargoEntity::new, container).build(null);
            helper.register(ResourceLocation.fromNamespaceAndPath(Incoming.MOD_ID, "test_material_cargo"), cargoType);
        });
    }

    private static final class CargoBlock extends Block implements net.minecraft.world.level.block.EntityBlock {
        CargoBlock() { super(BlockBehaviour.Properties.of().strength(1)); }
        @Override public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos at, BlockState state) {
            return new CargoEntity(at, state);
        }
        @Override protected void onPlace(BlockState state, net.minecraft.world.level.Level level, BlockPos at, BlockState old, boolean piston) {
            if (at.equals(placementFailure)) throw new CallbackFailure();
        }
    }

    private static final class CargoEntity extends net.minecraft.world.level.block.entity.BlockEntity {
        private ItemStack stack = ItemStack.EMPTY;
        CargoEntity(BlockPos at, BlockState state) { super(cargoType, at, state); }
        @Override protected void saveAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
            super.saveAdditional(tag, registries);
            if (!stack.isEmpty()) tag.put("item", stack.save(registries));
        }
        @Override protected void loadAdditional(CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries) {
            super.loadAdditional(tag, registries);
            stack = ItemStack.parseOptional(registries, tag.getCompound("item"));
            if (getBlockPos().equals(cargoFailure)) {
                stack.setCount(3); // A real partial transfer plus mutation of the supplied input.
                tag.remove("item");
                throw new CallbackFailure();
            }
        }
    }

    private static final class CallbackFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static void work(RuinMatter ledger, ServerLevel level) {
        var clock = WorkClock.counting(1);
        clock.start(1);
        ledger.work(level, clock);
    }

    @GameTest(template = "pad", timeoutTicks = 1200, batch = "nuke_material_failure_placement", skyAccess = true)
    public static void partialPlacementIsSavedWithoutAutomaticReplay(GameTestHelper h) throws Exception {
        partialTransfer(h, false);
    }

    @GameTest(template = "pad", timeoutTicks = 1200, batch = "nuke_material_failure_cargo", skyAccess = true)
    public static void partialCargoLoadPreservesOriginalAndDoesNotReplay(GameTestHelper h) throws Exception {
        partialTransfer(h, true);
    }

    private static void partialTransfer(GameTestHelper h, boolean cargo) throws Exception {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(32, 3, 32));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            level.getChunk((at.getX() >> 4) + x, (at.getZ() >> 4) + z);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(at.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
            level.setBlock(at.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
        }
        var source = at.above(3);
        level.setBlock(source, container.defaultBlockState(), 2);
        var original = (CargoEntity) level.getBlockEntity(source);
        original.stack = new ItemStack(Items.DIAMOND, 7);
        var contents = original.saveWithoutMetadata(level.registryAccess());
        var piece = new RuinMatter.Piece(source, container.defaultBlockState(), contents,
                new Vec3(0, -1, 0), level.getGameTime() - 1);
        piece.y = at.getY();
        ColumnScar.replace(level, source, piece.state, Blocks.AIR.defaultBlockState());
        var ledger = new RuinMatter(); ledger.release(java.util.List.of(piece));
        if (cargo) cargoFailure = at; else placementFailure = at;
        StrikeGameTests.afterTest(h, () -> { placementFailure = null; cargoFailure = null; ledger.unload(level); });
        try { work(ledger, level); h.fail("Placement/cargo callback did not throw"); }
        catch (CallbackFailure expected) { /* Assert actual mutation and retained ownership below. */ }
        placementFailure = null; cargoFailure = null;
        h.assertTrue(level.getBlockState(at).is(container), "Fixture failed before actual world mutation");
        if (cargo) h.assertTrue(((CargoEntity) level.getBlockEntity(at)).stack.getCount() == 3,
                "Fixture did not partially transfer cargo before throwing");
        var saved = ledger.save(new CompoundTag(), level.registryAccess());
        var row = saved.getList("moving", 10).getCompound(0);
        h.assertTrue(ledger.pending() == 1 && ledger.deposited() == 0 && row.getInt("transfer") == (cargo ? 2 : 1),
                "Ambiguous world mutation lost explicit pending ownership");
        h.assertTrue(row.getCompound("contents").equals(contents), "Callback mutated authoritative cargo");
        var legacy = RuinMatter.load(saved, level.registryAccess());
        h.assertTrue(legacy.save(new CompoundTag(), level.registryAccess()).equals(saved), "Named-row restore changed paused ownership");
        legacy.unload(level);
        // A player edit after the error must never be overwritten by a blind transfer retry.
        level.setBlock(at, Blocks.GOLD_BLOCK.defaultBlockState(), 2);
        var file = java.nio.file.Files.createTempDirectory(level.getDataStorage().dataFolder.toPath(), "material-partial-")
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
                    h.assertTrue(scan.owned() >= restored[0].retainedBytes(), "Paused import exceeded preflight ownership");
                    var nativeTag = net.minecraft.nbt.NbtIo.readCompressed(file, net.minecraft.nbt.NbtAccounter.create(1L << 20));
                    var packed = nativeTag.getCompound("data").getList("moving", 10).getCompound(0);
                    h.assertTrue(packed.getByteArray("motion").length == 145, "Transfer changed the packed motion schema");
                    // Packed rows take precedence over legacy fields in the reader and preflight.
                    packed.putBoolean("outside", true);
                    var mixed = file.resolveSibling("mixed-fields.dat");
                    net.minecraft.nbt.NbtIo.writeCompressed(nativeTag, mixed);
                    var mixedScan = LedgerScan.measure(mixed, "incoming_ruin_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP);
                    var mixedOwner = (RuinMatter) LedgerReader.read(mixed, "incoming_ruin_matter", level.registryAccess(), mixedScan.row(), java.util.Set.of());
                    h.assertTrue(mixedScan.owned() >= mixedOwner.retainedBytes()
                            && mixedOwner.save(new CompoundTag(), level.registryAccess()).equals(saved), "Packed/legacy precedence differs in preflight and decode");
                    mixedOwner.unload(level);
                    packed.putLong("transfer", cargo ? 2 : 1);
                    var invalid = file.resolveSibling("invalid-transfer.dat");
                    net.minecraft.nbt.NbtIo.writeCompressed(nativeTag, invalid);
                    try { LedgerScan.measure(invalid, "incoming_ruin_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP); h.fail("Preflight coerced transfer phase type"); }
                    catch (java.io.IOException expected) { /* Reject before allocating a live owner. */ }
                    try { RuinMatter.load(nativeTag.getCompound("data"), level.registryAccess()); h.fail("Decoder coerced transfer phase type"); }
                    catch (IllegalStateException expected) { /* Exact scalar typing, as in preflight. */ }
                } catch (java.io.IOException error) { throw new RuntimeException(error); }
            }
            for (int i = 0; i < 32; i++) work(restored[0], level);
            h.assertTrue(restored[0].pending() == 1 && restored[0].deposited() == 0, "Partial transfer was replayed or retired");
            h.assertTrue(restored[0].save(new CompoundTag(), level.registryAccess()).equals(saved), "Native reload changed IDs, destination or cargo");
            h.assertTrue(level.getBlockState(at).is(Blocks.GOLD_BLOCK), "Pending transfer overwrote a subsequent player edit");
        });
    }

    @GameTest(template = "pad", timeoutTicks = 1200, batch = "nuke_material_failure_after_transfer", skyAccess = true)
    public static void notificationFailureCannotDuplicateTransferredCargo(GameTestHelper h) throws Exception {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(32, 3, 32));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            level.setBlock(at.offset(x, -1, z), Blocks.BEDROCK.defaultBlockState(), 2);
            level.setBlock(at.offset(x, 0, z), Blocks.AIR.defaultBlockState(), 2);
        }
        var source = at.above(3);
        level.setBlock(source, Blocks.BARREL.defaultBlockState(), 2);
        var barrel = (BarrelBlockEntity) level.getBlockEntity(source);
        barrel.setItem(0, new ItemStack(Items.DIAMOND, 7));
        var contents = barrel.saveWithoutMetadata(level.registryAccess());
        var piece = new RuinMatter.Piece(source, Blocks.BARREL.defaultBlockState(), contents,
                new Vec3(0, -1, 0), level.getGameTime() - 1);
        piece.y = at.getY();
        ColumnScar.replace(level, source, piece.state, Blocks.AIR.defaultBlockState());
        level.setBlock(at, decoration.defaultBlockState(), 2);
        h.assertTrue(!decoration.defaultBlockState().canBeReplaced(), "Fixture decoration must use the ordinary drop path");
        var ledger = new RuinMatter(); ledger.release(java.util.List.of(piece));
        dropFailure = true;
        StrikeGameTests.afterTest(h, () -> { dropFailure = false; ledger.unload(level); });
        try { work(ledger, level); h.fail("Post-transfer decoration callback did not throw"); }
        catch (CallbackFailure expected) { /* Cargo transfer must already be complete. */ }
        dropFailure = false;
        h.assertTrue(ledger.pending() == 0 && ledger.deposited() == 1, "Transferred material was requeued after a later callback failed");
        var placed = (BarrelBlockEntity) level.getBlockEntity(at);
        h.assertTrue(placed != null && placed.getItem(0).getCount() == 7, "Cargo was not fully transferred before notification");
        var file = java.nio.file.Files.createTempDirectory(level.getDataStorage().dataFolder.toPath(), "material-transferred-")
                .resolve("material.dat");
        ledger.save(file.toFile(), level.registryAccess());
        h.succeedWhen(() -> {
            h.assertFalse(ledger.writing(), "Native save has not completed");
            try {
                var scan = LedgerScan.measure(file, "incoming_ruin_matter", java.util.Set.of(), NuclearPrep.PLANS_CAP);
                var restored = (RuinMatter) LedgerReader.read(file, "incoming_ruin_matter", level.registryAccess(), scan.row(), java.util.Set.of());
                work(restored, level);
                h.assertTrue(restored.pending() == 0 && restored.deposited() == 1 && placed.getItem(0).getCount() == 7,
                        "Native reload duplicated a completed transfer");
                restored.unload(level);
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
        });
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
