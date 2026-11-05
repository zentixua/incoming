package ua.zentix.incoming.nuclear.world;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.gametest.StrikeGameTests;
import ua.zentix.incoming.nuclear.Detonation;
import ua.zentix.incoming.nuclear.world.landing.PreparedLandingModel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/** Snapshot contracts only; the experiment never publishes a material owner or commits prepared landings. */
@GameTestHolder(Incoming.MOD_ID)
@PrefixGameTestTemplate(false)
public final class PreparedLandingPaletteGameTests {
    @GameTest(template = "pad", batch = "nuke_prepared_landing_palette", timeoutTicks = 200)
    public static void staticPalettePreservesBothNativeCellOperands(GameTestHelper h) {
        var builder = new PreparedLandingPalette.Builder(h.getLevel());
        var states = new ArrayList<BlockState>();
        for (Block block : List.of(Blocks.OAK_FENCE, Blocks.BIRCH_FENCE, Blocks.OAK_TRAPDOOR, Blocks.OAK_DOOR,
                Blocks.SMOOTH_STONE_SLAB, Blocks.OAK_STAIRS, Blocks.JUKEBOX, Blocks.CHEST, Blocks.WATER,
                Blocks.LIGHT, Blocks.END_PORTAL, Blocks.SNOW, Blocks.TORCH, Blocks.GLASS_PANE)) {
            states.addAll(block.getStateDefinition().getPossibleStates());
        }
        states.forEach(builder::add);
        builder.add(Blocks.MOVING_PISTON.defaultBlockState());
        var frozen = builder.freeze();
        for (BlockState state : states) {
            h.assertTrue(!state.getBlock().hasDynamicShape(), "Control unexpectedly has dynamic geometry: " + state);
            Blast.Props props = Blast.props(state);
            boolean free = props.air() || props.fluid() || props.is(Blast.Props.REPLACEABLE)
                    || !props.bearing() && !props.leaves() && !props.fixed() && !state.hasBlockEntity();
            boolean empty = state.getCollisionShape(h.getLevel(), h.absolutePos(BlockPos.ZERO)).isEmpty();
            byte expected = free ? empty ? PreparedLandingModel.AIR : PreparedLandingModel.FREE_COLLIDING
                    : empty ? PreparedLandingModel.BLOCKED_EMPTY : PreparedLandingModel.SOLID;
            h.assertTrue(frozen.get(state).collision() == expected, "Frozen collision differs from native operands: " + state);
        }
        h.assertTrue(frozen.get(Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 2)).collision() == PreparedLandingModel.FREE_COLLIDING,
                "Replaceable collision lost its independent free operand");
        h.assertTrue(frozen.get(Blocks.END_PORTAL.defaultBlockState()).collision() == PreparedLandingModel.BLOCKED_EMPTY,
                "Protected empty shape lost its independent collision operand");
        h.assertTrue(frozen.get(Blocks.CHEST.defaultBlockState()).collision() == PreparedLandingModel.SOLID
                        && !frozen.get(Blocks.CHEST.defaultBlockState()).ordinary(),
                "Static cargo obstacle was rejected or cargo source became ordinary");
        h.assertTrue(frozen.get(Blocks.MOVING_PISTON.defaultBlockState()).collision() == PreparedLandingModel.UNSUPPORTED,
                "Dynamic collision was silently treated as a static shape");
        int count = frozen.size();
        builder.add(Blocks.GOLD_BLOCK.defaultBlockState());
        h.assertTrue(frozen.size() == count, "Builder mutation changed a published palette");
        Incoming.LOG.info("LANDING_PALETTE states={} bytes_estimate={} static_native_operands=PASS", count, frozen.bytesEstimate());
        h.succeed();
    }

    @GameTest(template = "pad", batch = "nuke_prepared_landing_palette_worker", timeoutTicks = 200)
    public static void partialSourcesMatchNativeCaptureAndConvertOnWorker(GameTestHelper h) {
        var level = h.getLevel();
        var states = List.of(Blocks.OAK_FENCE.defaultBlockState(), Blocks.OAK_TRAPDOOR.defaultBlockState(),
                Blocks.SMOOTH_STONE_SLAB.defaultBlockState(),
                Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                Blocks.OAK_STAIRS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 2));
        ChunkPos center = new ChunkPos(h.absolutePos(new BlockPos(8, 8, 8)));
        int minY = Math.floorDiv(h.absolutePos(new BlockPos(0, 8, 0)).getY(), 16) * 16;
        var builder = new PreparedLandingPalette.Builder(level);
        var section = new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
        var removals = new ArrayList<PreparedLandingSnapshot.Removal>();
        var nativePieces = new ArrayList<RuinMatter.Piece>();
        var d = new Detonation(1, new Vec3(center.getMinBlockX() - 100, minY + 8, center.getMinBlockZ() - 5),
                minY, 15, true, 0, 0, 0, 20000, 42, 1, false, .25f, 6000, false);
        for (int i = 0; i < states.size(); i++) {
            BlockState state = states.get(i);
            BlockPos at = new BlockPos(center.getMinBlockX() + i, minY + 8, center.getMinBlockZ() + 8);
            section.set(i, 8, 8, state);
            level.setBlock(at, state, 2);
            nativePieces.add(RuinMatter.capture(level, at, state, d));
            removals.add(new PreparedLandingSnapshot.Removal(at, 0, true));
        }
        section.getAll(builder::add);
        var facts = builder.freeze();
        @SuppressWarnings("unchecked")
        var contents = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[]{section};
        var read = new DiskShots.Read(center, minY, contents, new int[4 * 256], 15, null, 0);
        var shots = new ChunkShot[9];
        shots[4] = ChunkShot.fromDisk(read.primed(Blast.SERVER), null, null, false);
        var window = new RuinWindow(center, minY, minY + 16, shots, Blast.publish());
        StrikeGameTests.gameSpeed(h);
        var executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "landing-palette-control"));
        StrikeGameTests.afterTest(h, executor::shutdownNow);
        BlockState uncaptured = Blocks.GOLD_BLOCK.defaultBlockState();
        var future = CompletableFuture.supplyAsync(() -> {
            boolean refused = false;
            try { builder.add(uncaptured); }
            catch (IllegalStateException expected) { refused = true; }
            if (!refused) throw new IllegalStateException("Worker was allowed to invoke palette capture");
            return PreparedLandingSnapshot.from(window, removals, d, facts);
        }, executor);
        h.succeedWhen(() -> {
            h.assertTrue(future.isDone(), "Waiting for frozen palette conversion");
            var input = future.join();
            h.assertTrue(input.sources().size() == states.size(), "Partial source disappeared from material accounting");
            for (int i = 0; i < states.size(); i++) {
                var source = input.sources().get(i);
                var nativePiece = nativePieces.get(i);
                h.assertTrue(nativePiece != null && source.kind() == PreparedLandingModel.Kind.ORDINARY,
                        "A static ordinary partial source remains unsupported: " + states.get(i));
                h.assertTrue(input.states().get(source.state()) == nativePiece.state,
                        "Source lost exact normalized block state");
                h.assertTrue(source.volume() == nativePiece.volume && source.mass() == nativePiece.mass
                                && source.drag() == nativePiece.drag && source.windX() == nativePiece.windX
                                && source.windZ() == nativePiece.windZ && source.pulse() == nativePiece.pulse,
                        "Prepared partial material differs from native capture");
            }
            h.assertTrue(input.sources().getLast().landingCell() == PreparedLandingModel.FREE_COLLIDING,
                    "Deposited partial source was changed to a solid cube");
        });
    }

    private PreparedLandingPaletteGameTests() {}
}
