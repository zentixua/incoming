package ua.zentix.incoming.grid.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.HitResult;

import java.util.function.Supplier;

/**
 * Redstone lamp counterpart: retains “lit” to restore light exactly as it was, but does not respond to signals itself.
 * A vanilla unlit lamp ({@code lit=false}) would relight on the first neighbor update if powered, and after blackout
 * would be indistinguishable from a lamp that was switched off.
 */
public class UnlitLampBlock extends Block implements Unlit {
    private final Supplier<Block> lit;

    public UnlitLampBlock(Properties properties, Supplier<Block> lit) {
        super(properties);
        this.lit = lit;
        registerDefaultState(defaultBlockState().setValue(BlockStateProperties.LIT, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.LIT);
    }

    @Override
    public Block lit() {
        return lit.get();
    }

    @Override
    public MutableComponent getName() {
        return Unlit.name(lit);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        Unlit.placed(state, level, pos, oldState);
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos, boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        Unlit.neighborChanged(state, level, pos);
    }

    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        Unlit.randomTick(state, level, pos);
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return Unlit.clone(lit);
    }
}
