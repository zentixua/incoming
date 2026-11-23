package ua.zentix.incoming.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** An outage is recorded: districts in its radius go dark outward from the centre. */
public final class GridOutageEvent extends GridEvent {
    public GridOutageEvent(ResourceKey<Level> dimension, UUID outage, double x, double z, double radius, @Nullable BlockPos node, boolean nuclear) {
        super(dimension, outage, x, z, radius, node, nuclear);
    }
}
