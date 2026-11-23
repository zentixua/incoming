package ua.zentix.incoming.api;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.strike.Order;

import java.util.Locale;
import java.util.UUID;

/**
 * One strike order on its way to admission: {@link Request}, then exactly one {@link Rejected} or {@link Accepted} at
 * the end of the same tick. Posted on {@code NeoForge.EVENT_BUS} from the server thread. Getters return immutable values only; the order's
 * rounds follow in {@link StrikeRoundEvent}.
 */
public abstract class StrikeOrderEvent extends Event {
    /** Who issued the order. */
    public enum Actor implements StringRepresentable {
        /** A player: the designator, or their own {@code /incoming} command. */
        PLAYER,
        /** A fixed launcher firing its mission: redstone or {@code /incoming launcher … fire}. */
        LAUNCHER,
        /** The console, a command block, a function or the game master. */
        SERVER;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How the order's munitions are paid. */
    public enum Payment implements StringRepresentable {
        /** In full from the player's inventory when the order is admitted; unlaunched rounds are refunded. */
        UPFRONT,
        /** One munition from the fixed launcher's magazine as each round takes its rail; a failed release puts it back. */
        PER_RELEASE,
        /** Nothing: the console, command blocks, operators' commands, creative players. */
        WAIVED;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why a request was not admitted. */
    public enum Reason implements StringRepresentable {
        /** A {@link Request} listener cancelled it. */
        VETOED,
        /** The payer lacked munitions when paying. */
        MUNITIONS,
        /** Admission failed with an error (logged). */
        ERROR;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Order order;

    protected StrikeOrderEvent(Order order) {
        this.order = order;
    }

    /** This order's identifier; every round event carries it. */
    public UUID getOrderId() {
        return order.id();
    }

    public Actor getActor() {
        return order.actor();
    }

    /** The player who ordered ({@link Actor#PLAYER}); null otherwise. */
    @Nullable
    public UUID getActorId() {
        return order.actorId();
    }

    /** Player name, command source name, or "launcher x, y, z". */
    public String getActorName() {
        return order.actorName();
    }

    /** Whose rounds these are (friend and foe, recall): the player, the launcher's owner; null for unowned. */
    @Nullable
    public UUID getOwnerId() {
        return order.owner();
    }

    /** The fixed launcher's block ({@link Actor#LAUNCHER}); null otherwise. */
    @Nullable
    public BlockPos getLauncherPos() {
        return order.launcher();
    }

    public ResourceKey<Level> getDimension() {
        return order.dimension();
    }

    /** Weapon id: {@code drone} (Shahed-136), {@code loiter} (Lancet), {@code rocket} (Grad), {@code missile}, {@code bunker} (B-2), {@code nuke} (ICBM). */
    public String getWeapon() {
        return order.weapon().getSerializedName();
    }

    /** Rounds ordered, already within the server's salvo limit. */
    public int getCount() {
        return order.count();
    }

    /** Salvo spread radius, blocks. */
    public int getSpread() {
        return order.spread();
    }

    /** Target point when ordered. */
    public Vec3 getTarget() {
        return order.target();
    }

    /** Target entity (a player, a mob); null for a place or a craft. */
    @Nullable
    public UUID getTargetEntityId() {
        return order.targetEntity();
    }

    /** A nuclear warhead: the ICBM, or a cruise missile or B-2 bomb carrying one. */
    public boolean isNuclear() {
        return order.nuclear();
    }

    /** Nuclear yield, kilotons; 0 for a conventional order. */
    public int getYieldKt() {
        return order.nuclear() ? order.nuke().yieldKt() : 0;
    }

    /** Nuclear air burst; false for a ground burst or a conventional order. */
    public boolean isAirBurst() {
        return order.nuclear() && order.nuke().airBurst();
    }

    /** Group name the order's rounds carry; null without one. */
    @Nullable
    public String getTag() {
        return order.tag();
    }

    /**
     * Validation: a listener cancels to refuse the order and charges nothing here (debit in {@link Accepted}). Incoming's
     * own checks (permissions, target rules, nuclear gates, active-strike limit, a fixed launcher's mission and stock)
     * already passed; munitions are paid only after every listener has let it through. An order started from this
     * listener is made again, with all its checks, on the next tick.
     */
    public static final class Request extends StrikeOrderEvent implements ICancellableEvent {
        @Nullable
        private Component refusal;

        public Request(Order order) {
            super(order);
        }

        /** Line shown to the ordering player (or in the launcher's status) when cancelled; a generic line without it. */
        public void setRefusal(@Nullable Component refusal) {
            this.refusal = refusal;
        }

        @Nullable
        public Component getRefusal() {
            return refusal;
        }
    }

    /** The order was not admitted: nothing launches and Incoming kept no payment. */
    public static final class Rejected extends StrikeOrderEvent {
        private final Reason reason;

        public Rejected(Order order, Reason reason) {
            super(order);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }
    }

    /**
     * The order is recorded and paid as {@link #getPayment()} says: each of its {@link #getRounds()} rounds now ends in
     * exactly one {@link StrikeRoundEvent.Ended}.
     */
    public static final class Accepted extends StrikeOrderEvent {
        private final int rounds;

        public Accepted(Order order, int rounds) {
            super(order);
            this.rounds = rounds;
        }

        /** Rounds admitted (a nuclear order: 1). */
        public int getRounds() {
            return rounds;
        }

        public Payment getPayment() {
            return getOrder().payment();
        }
    }

    Order getOrder() {
        return order;
    }
}
