package ua.zentix.incoming.grid.block;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import ua.zentix.incoming.compat.SubLevels;
import ua.zentix.incoming.grid.BlackoutWorld;
import ua.zentix.incoming.grid.ChunkLights;

import java.util.function.Supplier;

/**
 * Unpowered grid lamp counterpart: same model, state properties, sound, strength and drops as the lamp, only without
 * light. The lamp thus goes dark everywhere — world lighting, shaders (Complementary makes lamps glow by block ID,
 * not surrounding light), and Distant Horizons LODs of loaded chunks (light is computed from block emission).
 * Counterparts have no item: they occur in the world only through blackout, so restoration is unambiguous
 * ({@link ua.zentix.incoming.grid.GridLights}).
 */
public interface Unlit {
    /** Lamp of which this block is a counterpart. */
    Block lit();

    /** “Glowstone (no power)” — from the lamp name, without a separate translation for every counterpart. */
    static MutableComponent name(Supplier<Block> lit) {
        return Component.translatable("block.incoming.unlit", lit.get().getName());
    }

    /**
     * A counterpart appeared outside blackout — moved by a piston, assembled and disassembled by a Create contraption,
     * moved into a plot by a Sable craft: in a lit district (always in a plot: a craft has its own power), the lamp relights
     * ({@link BlackoutWorld#relightLater}; not by block tick — Sable plots have no block ticks).
     */
    static void placed(BlockState state, Level level, BlockPos pos, BlockState oldState) {
        if (level instanceof ServerLevel server && !oldState.is(state.getBlock()) && BlackoutWorld.powered(server, pos)) {
            BlackoutWorld.get(server).relightLater(pos);
        }
    }

    /**
     * A counterpart in a Sable craft plot received a neighbor update — relight immediately, in this tick. Craft assembly
     * places blocks directly in plot sections (without {@code onPlace}), then notifies each assembled block's neighbors:
     * this lets the counterpart learn it is in a plot before autosave (Sable saves the plot through its own codec, bypassing
     * {@link ua.zentix.incoming.grid.ChunkSaves}, and autosave runs before the server tick ends).
     */
    static void neighborChanged(BlockState state, Level level, BlockPos pos) {
        if (level instanceof ServerLevel server && SubLevels.isInPlot(server, Vec3.atCenterOf(pos))) ChunkLights.relight(server, pos, state);
    }

    /**
     * Fallback: a counterpart's random tick where power exists makes it a lamp again. This relights counterparts seen by
     * neither the blackout queue nor {@link #placed}: in Sable craft plots (assembly places blocks directly in sections),
     * or in a chunk left dark for any other reason.
     */
    static void randomTick(BlockState state, ServerLevel level, BlockPos pos) {
        if (BlackoutWorld.powered(level, pos)) BlackoutWorld.get(level).relightLater(pos);
    }

    /** Pick block (middle button) gives the lamp. */
    static ItemStack clone(Supplier<Block> lit) {
        return new ItemStack(lit.get());
    }
}
