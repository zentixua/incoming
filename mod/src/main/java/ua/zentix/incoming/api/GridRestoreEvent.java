package ua.zentix.incoming.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Restoration is complete: every district of the outage has power again and the outage is forgotten. An outage whose
 * centre the blast destroyed never completes.
 */
public final class GridRestoreEvent extends GridEvent {
    public GridRestoreEvent(ResourceKey<Level> dimension, UUID outage, double x, double z, double radius, @Nullable BlockPos node, boolean nuclear) {
        super(dimension, outage, x, z, radius, node, nuclear);
    }
}
