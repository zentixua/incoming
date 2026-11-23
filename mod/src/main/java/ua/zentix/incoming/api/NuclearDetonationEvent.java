package ua.zentix.incoming.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A nuclear detonation: an ICBM, a cruise missile or B-2 bomb with a nuclear warhead, {@code /incoming nuke now}. Posted
 * on {@code NeoForge.EVENT_BUS} from the server thread at the end of the burst's tick, before its round's {@link
 * StrikeRoundEvent.Ended}. A burst outside the order lifecycle (another mod's call, a strike saved before these events)
 * is reported too, with no order or round id.
 */
public final class NuclearDetonationEvent extends Event {
    private final ResourceKey<Level> dimension;
    private final int id;
    private final Vec3 position;
    private final double yieldKt;
    private final boolean airBurst;
    @Nullable
    private final UUID owner, order, round;
    @Nullable
    private final String tag;

    public NuclearDetonationEvent(ResourceKey<Level> dimension, int id, Vec3 position, double yieldKt, boolean airBurst, @Nullable UUID owner,
                                  @Nullable UUID order, @Nullable UUID round, @Nullable String tag) {
        this.dimension = dimension;
        this.id = id;
        this.position = position;
        this.yieldKt = yieldKt;
        this.airBurst = airBurst;
        this.owner = owner;
        this.order = order;
        this.round = round;
        this.tag = tag;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    /** Detonation number in its dimension (as in Incoming's log). */
    public int getDetonationId() {
        return id;
    }

    /** Burst point. */
    public Vec3 getPosition() {
        return position;
    }

    public double getYieldKt() {
        return yieldKt;
    }

    public boolean isAirBurst() {
        return airBurst;
    }

    @Nullable
    public UUID getOwnerId() {
        return owner;
    }

    /** The order it belongs to; null for a detonation outside the order lifecycle (another mod's call, an old save's strike). */
    @Nullable
    public UUID getOrderId() {
        return order;
    }

    @Nullable
    public UUID getRoundId() {
        return round;
    }

    @Nullable
    public String getTag() {
        return tag;
    }
}
