package ua.zentix.incoming.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A power-grid outage in one dimension: {@link GridOutageEvent} when it is recorded, {@link GridRestoreEvent} when power
 * is back in its whole area. Posted on {@code NeoForge.EVENT_BUS} from the server thread.
 */
public abstract class GridEvent extends Event {
    private final ResourceKey<Level> dimension;
    private final UUID outage;
    private final double x, z, radius;
    @Nullable
    private final BlockPos node;
    private final boolean nuclear;

    protected GridEvent(ResourceKey<Level> dimension, UUID outage, double x, double z, double radius, @Nullable BlockPos node, boolean nuclear) {
        this.dimension = dimension;
        this.outage = outage;
        this.x = x;
        this.z = z;
        this.radius = radius;
        this.node = node;
        this.nuclear = nuclear;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    /** Outage identifier, the same in its outage and restore events and across restarts. */
    public UUID getOutageId() {
        return outage;
    }

    /** Centre, block x. */
    public double getCentreX() {
        return x;
    }

    /** Centre, block z. */
    public double getCentreZ() {
        return z;
    }

    /** Radius of the dark area, blocks. */
    public double getRadius() {
        return radius;
    }

    /** The substation whose failure caused it; null for a nuclear detonation or a command. */
    @Nullable
    public BlockPos getNodePos() {
        return node;
    }

    /** Caused by a nuclear detonation: power returns by damage (hours, days, or never near the burst). */
    public boolean isNuclear() {
        return nuclear;
    }
}
