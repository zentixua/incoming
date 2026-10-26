package ua.zentix.incoming.grid;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.IncomingConfig;
import ua.zentix.incoming.net.ClientHooks;

/**
 * Transformer substation: when placed, a grid node of radius {@code grid.node_radius}; a nearby strike knocks it out,
 * and its area goes dark in a cascade ({@link Blackouts}). Working — hums; knocked out — sparks and smokes until power
 * is restored. The grid sets {@code powered} ({@link Substations#sync}); players do not change it.
 */
public class SubstationBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<SubstationBlock> CODEC = simpleCodec(SubstationBlock::new);
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;
    /** As in the model: concrete slab, tank with side radiators, row of bushings on top (front faces north, same 180° rotations). */
    private static final VoxelShape SHAPE_NS = Shapes.or(Block.box(0, 0, 0, 16, 2, 16), Block.box(0.5, 2, 2, 15.5, 12, 14), Block.box(3.5, 12, 7, 12.5, 16, 9));
    private static final VoxelShape SHAPE_EW = Shapes.or(Block.box(0, 0, 0, 16, 2, 16), Block.box(2, 2, 0.5, 14, 12, 15.5), Block.box(7, 12, 3.5, 9, 16, 12.5));

    public SubstationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(POWERED, true));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(FACING).getAxis() == Direction.Axis.X ? SHAPE_EW : SHAPE_NS;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (level instanceof ServerLevel server && !oldState.is(this)) {
            PowerGrid grid = PowerGrid.get(server);
            if (grid.nodeAt(pos) == null) grid.addNode(pos, IncomingConfig.SERVER.gridNodeRadius.get(), true);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (level instanceof ServerLevel server && !newState.is(this)) {
            PowerGrid grid = PowerGrid.get(server);
            Node node = grid.nodeAt(pos);
            if (node != null && node.block()) grid.removeNode(node.id());
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Block is a knocked-out substation (unpowered). */
    public static boolean out(BlockState state) {
        return state.getBlock() instanceof SubstationBlock && !state.getValue(POWERED);
    }

    /**
     * Working — hums; knocked out — crackles with an arc and showers sparks, with transformer oil burning above it
     * (smoke and sound on the client, {@code ClientHooks.substation}: volume by the source law, smoke by the cloud model).
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.9, z = pos.getZ() + 0.5;
        // random block animation near a player — once per 2 s on average; 4 s hum recording with faded ends, no audible overlap
        boolean spark = !state.getValue(POWERED) && random.nextInt(4) == 0;
        ClientHooks.get().substation(pos, state.getValue(POWERED), spark, 0.9f + random.nextFloat() * 0.2f);
        if (spark) {
            for (int i = 0; i < 6; i++) {
                level.addParticle(ParticleTypes.ELECTRIC_SPARK, x + random.nextGaussian() * 0.25, y + 0.15, z + random.nextGaussian() * 0.25,
                        random.nextGaussian() * 0.2, 0.1 + random.nextDouble() * 0.2, random.nextGaussian() * 0.2);
            }
        }
    }
}
