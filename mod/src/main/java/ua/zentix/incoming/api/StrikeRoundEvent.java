package ua.zentix.incoming.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.strike.RoundRef;
import ua.zentix.incoming.strike.RoundEnd;

import java.util.Locale;
import java.util.UUID;

/**
 * One round of an admitted order ({@link StrikeOrderEvent.Accepted}): {@link Released} when it leaves, {@link Ended} once
 * when it is over. The round id stays the same while the projectile flies outside the loaded world, returns to it and
 * across a server restart. Posted on {@code NeoForge.EVENT_BUS} from the server thread at the end of the tick in which
 * they happened: a round's Released always reaches every listener before its Ended.
 */
public abstract class StrikeRoundEvent extends Event {
    /** How a round ended. */
    public enum Outcome implements StringRepresentable {
        /** The warhead went off: at the target, where it struck something on the way, or where its range ran out. */
        IMPACT,
        /** Shot down: by a SAM, or by damage (a player's weapon). */
        INTERCEPTED,
        /** Never went off: crashed before arming, a failed launch, lost, or removed by something other than Incoming. */
        FAILED,
        /** Stood down by its owner, cleared by an operator, its salvo or nuclear key wait cancelled. */
        RECALLED;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final ResourceKey<Level> dimension;
    private final RoundRef round;
    private final String weapon;
    @Nullable
    private final UUID owner;

    protected StrikeRoundEvent(ResourceKey<Level> dimension, RoundRef round, String weapon, @Nullable UUID owner) {
        this.dimension = dimension;
        this.round = round;
        this.weapon = weapon;
        this.owner = owner;
    }

    public UUID getRoundId() {
        return round.round();
    }

    public UUID getOrderId() {
        return round.order();
    }

    /** Group name from the order; null without one. */
    @Nullable
    public String getTag() {
        return round.tag();
    }

    /** Weapon id, as {@link StrikeOrderEvent#getWeapon()}. */
    public String getWeapon() {
        return weapon;
    }

    @Nullable
    public UUID getOwnerId() {
        return owner;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    /**
     * The round left: a projectile on its rail or already flying outside the loaded world, an ICBM strike scheduled (its
     * missile is the visible part), or a burst without flight. A round that never leaves gets no Released.
     */
    public static final class Released extends StrikeRoundEvent {
        private final Vec3 origin, target;

        public Released(ResourceKey<Level> dimension, RoundRef round, String weapon, @Nullable UUID owner, Vec3 origin, Vec3 target) {
            super(dimension, round, weapon, owner);
            this.origin = origin;
            this.target = target;
        }

        /** Where the round starts: its rail, the point it enters from afar, the ICBM pad, the burst point. */
        public Vec3 getOrigin() {
            return origin;
        }

        /** Where it is aimed at release (a followed target moves on). */
        public Vec3 getTarget() {
            return target;
        }
    }

    /** The round is over. Posted exactly once for each admitted round, released or not. */
    public static final class Ended extends StrikeRoundEvent {
        private final RoundEnd end;

        public Ended(ResourceKey<Level> dimension, RoundRef round, String weapon, @Nullable UUID owner, RoundEnd end) {
            super(dimension, round, weapon, owner);
            this.end = end;
        }

        public Outcome getOutcome() {
            return end.outcome();
        }

        /** Where it ended: the burst, where it was shot down or broke up; null for a round that never left. */
        @Nullable
        public Vec3 getPosition() {
            return end.position();
        }

        /** {@link Outcome#IMPACT}: TNT-equivalent charge, kg (a nuclear yield: kilotons × 10⁶); 0 otherwise. */
        public double getChargeKg() {
            return end.chargeKg();
        }

        /** {@link Outcome#INTERCEPTED} by a SAM: the SAM's block; null otherwise. */
        @Nullable
        public BlockPos getInterceptorPos() {
            return end.interceptor();
        }

        /** {@link Outcome#INTERCEPTED}: the SAM's owner, or the entity whose damage brought it down; null if unknown. */
        @Nullable
        public UUID getInterceptorOwnerId() {
            return end.interceptorOwner();
        }

        /**
         * Its owner steered it through the projectile camera during flight: retargeted it from the camera, or the camera
         * held its sighted target in frame (a Shahed-136 or Lancet following a moving target).
         */
        public boolean isCameraGuided() {
            return end.cameraGuided();
        }

        /** A {@link Released} was posted for this round. */
        public boolean isReleased() {
            return end.released();
        }

        /**
         * The round's munitions are back with its payer or never left: an upfront payment returned to its online owner, a
         * launcher round that never took its rail. False for released rounds, waived orders and a refund the offline owner
         * could not receive.
         */
        public boolean isRefunded() {
            return end.refunded();
        }
    }
}
