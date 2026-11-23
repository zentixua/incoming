package ua.zentix.incoming.defense;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.registry.ModBlockEntities;
import ua.zentix.incoming.registry.ModItems;
import ua.zentix.incoming.util.SignalEdge;

/**
 * SAM ({@link SamBlockEntity}): the block is a radar cabin with antennas on four faces; above it the client draws
 * four vertical missile canisters at real size ({@code client.render.SamRenderer}, ~5.4 m). The rack is visual
 * only; there is one block. Remembers who placed it: their side is friendly. Load interceptor missiles by hand
 * (right-click with missiles), hopper, or Create funnel; an empty hand shows a status line. Broken or exploded,
 * it drops itself (loot table), stored missiles, and missiles on the rails. Redstone: weak power 15 from every face
 * while its radar tracks a hostile projectile ({@link #ALARM}); a comparator reads the magazine.
 */
public class SamBlock extends BaseEntityBlock {
    public static final MapCodec<SamBlock> CODEC = simpleCodec(SamBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /**
     * The latest radar sweep saw a hostile projectile ({@link SamBlockEntity}): the redstone alarm. A block state, like a
     * daylight detector's power, so the chunk saves it and observers see it change; the model is the same.
     */
    public static final BooleanProperty ALARM = BooleanProperty.create("alarm");

    public SamBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(ALARM, false));
    }

    @Override
    protected MapCodec<SamBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING, ALARM);
    }

    /** Faces the placing player, like a furnace; the square rack looks the same in all four orientations. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }

    /** Vanilla does not render a model for a block with a block entity by default. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SamBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, ModBlockEntities.SAM.get(), SamBlockEntity::serverTick);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player p && level.getBlockEntity(pos) instanceof SamBlockEntity be) be.setOwner(p.getUUID());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SamBlockEntity be) {
            be.dropContents(level, pos);
            level.updateNeighbourForOutputSignal(pos, this);
        }
        // Removed while sounding the alarm: ruin and collapse writes skip neighbour updates, and dust beside it would
        // keep carrying 15 from a SAM that is gone.
        if (!state.is(newState.getBlock()) && state.getValue(ALARM) && !level.isClientSide && SignalEdge.ready(level, pos)) {
            level.updateNeighborsAt(pos, this);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
                                              BlockHitResult hit) {
        if (!stack.is(ModItems.INTERCEPTOR.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SamBlockEntity be) {
            ItemStack rest = be.load(stack.copy());
            int put = stack.getCount() - rest.getCount();
            if (put > 0) {
                if (!player.getAbilities().instabuild) stack.shrink(put);
                level.playSound(null, pos, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 0.6f, 1.4f);
            } else {
                player.displayClientMessage(Component.translatable("incoming.sam.full"), true);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SamBlockEntity be) player.displayClientMessage(be.status(), true);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** Alarm output: dust connects, adjacent redstone reads it ({@link #getSignal}). */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * Weak power 15 from every face while the radar's latest sweep saw a hostile projectile, like a daylight detector:
     * it powers what touches it, not through that block.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(ALARM) ? 15 : 0;
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SamBlockEntity be ? be.comparator() : 0;
    }
}
