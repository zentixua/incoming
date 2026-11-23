package ua.zentix.incoming.strike;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import ua.zentix.incoming.Incoming;
import ua.zentix.incoming.api.StrikeOrderEvent;
import ua.zentix.incoming.api.StrikeRoundEvent;

import java.util.UUID;

/**
 * Order lifecycle ({@link ua.zentix.incoming.api}): the one admission step every entry takes once Incoming's own checks
 * passed (designator, commands, fixed launcher, nuclear key wait, {@code nuke now}), and the round events. Server thread
 * only.
 */
public final class Orders {
    /** {@link StrikeOrderEvent.Request} listeners running now: an order they start is queued to the next tick. */
    private static int requesting;

    private Orders() {}

    /** What an admitted order does: pay, record, {@link Order#accept}, launch; on a shortfall {@link Order#reject} it. */
    public interface Admission {
        /** @return the entry's own result: launched or recorded */
        boolean run(Order order);
    }

    /**
     * Ask the listeners ({@link StrikeOrderEvent.Request}), then run {@code body}, which pays and answers. Exactly one
     * Rejected or Accepted follows: a veto rejects before payment; a body that did not answer (exception) is rejected
     * with {@link StrikeOrderEvent.Reason#ERROR}. An order started by a Request listener is queued to the next tick.
     *
     * @return {@code body}'s result; false if vetoed; true if queued
     */
    public static boolean admit(Order order, Admission body) {
        ServerLevel level = order.level();
        if (requesting > 0) {
            Incoming.LOG.info("Order {} ({} ×{}) — {} was started inside another order's request, queued to the next tick", order.id(),
                    order.weapon().getSerializedName(), order.count(), order.actorName());
            StrikeWorld.get(level).defer(level.getGameTime() + 1, () -> admit(order, body));
            return true;
        }
        StrikeOrderEvent.Request request = new StrikeOrderEvent.Request(order);
        boolean asked;
        requesting++;
        try {
            asked = post(request);
        } finally {
            requesting--;
        }
        if (!asked || request.isCanceled()) {
            order.reject(asked ? StrikeOrderEvent.Reason.VETOED : StrikeOrderEvent.Reason.ERROR);
            Component why = request.getRefusal() != null ? request.getRefusal() : Component.translatable("incoming.order.refused");
            if (order.feedback() != null) order.feedback().accept(why);
            Incoming.LOG.info("Strike order {} refused{}: {} ×{} at {} {} {} — {}", order.id(), asked ? " by a listener" : " (listener error)",
                    order.weapon().getSerializedName(), order.count(), Mth.floor(order.target().x), Mth.floor(order.target().y),
                    Mth.floor(order.target().z), order.actorName());
            return false;
        }
        try {
            boolean ok = body.run(order);
            if (!order.answered()) {
                Incoming.LOG.error("Strike order {} finished admission without an answer", order.id());
                order.reject(StrikeOrderEvent.Reason.ERROR);
            }
            return ok;
        } catch (RuntimeException e) {
            if (!order.answered()) order.reject(StrikeOrderEvent.Reason.ERROR);
            throw e;
        }
    }

    /** Round {@code r} left at {@code origin} toward {@code target}. */
    public static void released(ServerLevel level, RoundRef r, WeaponType weapon, @Nullable UUID owner, Vec3 origin, Vec3 target) {
        post(new StrikeRoundEvent.Released(level.dimension(), r, weapon.getSerializedName(), owner, origin, target));
    }

    /** Round {@code r} is over. Callers end each round once: its carrier forgets it in the same step. */
    public static void ended(ServerLevel level, RoundRef r, WeaponType weapon, @Nullable UUID owner, RoundEnd end) {
        post(new StrikeRoundEvent.Ended(level.dimension(), r, weapon.getSerializedName(), owner, end));
    }

    /**
     * Post on the game bus; a listener's exception is logged, never thrown into a projectile's removal, a salvo or a
     * world tick.
     *
     * @return false if a listener threw
     */
    public static boolean post(Event event) {
        try {
            NeoForge.EVENT_BUS.post(event);
            return true;
        } catch (RuntimeException e) {
            Incoming.LOG.error("A listener of {} failed", event.getClass().getSimpleName(), e);
            return false;
        }
    }
}
