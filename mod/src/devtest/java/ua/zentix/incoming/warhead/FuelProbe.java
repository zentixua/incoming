package ua.zentix.incoming.warhead;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import ua.zentix.incoming.nuclear.model.PulseIgnition;

/** Test access to the fuel fireball's ignition class of a block ({@link Craters#fuel}, package-private). */
public final class FuelProbe {
    private FuelProbe() {}

    public static PulseIgnition.Fuel fireball(ServerLevel level, BlockPos at, BlockState s) {
        return Craters.fuel(level, at, s, Direction.UP);
    }
}
