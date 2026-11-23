package ua.zentix.incoming.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Restoration of this outage is complete: it no longer darkens any district and is forgotten. Another outage may still
 * keep some of its districts dark. An outage around a zone the blast destroyed completes only when restored by command.
 * A server whose outage began before a listener was registered (a restart) delivers the restore without the outage.
 */
public final class GridRestoreEvent extends GridEvent {
    public GridRestoreEvent(ResourceKey<Level> dimension, UUID outage, double x, double z, double radius, @Nullable BlockPos node, boolean nuclear) {
        super(dimension, outage, x, z, radius, node, nuclear);
    }
}
